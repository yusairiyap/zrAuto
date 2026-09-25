package me.aap.fermata.addon.web.yt;

import static me.aap.utils.async.Completed.completed;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.util.List;

import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.engine.MediaEngineException;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.VideoView;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.fragment.GenericFragment;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.UiUtils;

/**
 * The Music tab's last-resort engine for YouTube audio, used only when no direct audio-only stream
 * can be had (see {@code MusicTrackItem#isWebFallback()}): YouTube's own mobile page -- signed in
 * with the user's session, so it passes the checks the direct streams fail -- in a small WebView of
 * its own, hidden behind the app's content, with the player held at its lowest video quality.
 * <p>
 * Deliberately shares nothing with the YouTube tab: its own WebView, page, player and state, and
 * its own engine id ({@link MediaPrefs#MEDIA_ENG_YT_AUDIO}), so the YouTube tab's video playback
 * (and {@link YoutubeMediaEngine}'s queue, autonav and fullscreen handling) is never involved.
 * Every step is written to the diagnostic log under "MUSIC web-fallback".
 */
@SuppressLint("SetJavaScriptEnabled")
final class YoutubeWebAudioEngine implements MusicPlayer.WebAudioEngine, PreferenceStore.Listener {
	private static final String TAG = "MUSIC";
	private static final long START_TIMEOUT = 45_000L;
	private static final String JS_FIND = "var v=document.querySelector('video');" +
			"var p=document.getElementById('movie_player');";
	private final Listener listener;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final WebView web;
	// The activity whose window currently hosts the hidden page; null while between activities.
	@Nullable
	private MainActivityDelegate activity;
	@Nullable
	private final YoutubeAddon addon;
	@Nullable
	private PlayableItem source;
	@Nullable
	private String videoId;
	private boolean want;
	private boolean prepared;
	private boolean endedReported;
	private boolean closed;
	private boolean ad;
	private String quality = "";
	private long position;
	private long duration;
	private float speed = 1f;
	private volatile int generation;

	YoutubeWebAudioEngine(MainActivityDelegate a, Listener listener) {
		this.listener = listener;
		addon = AddonManager.get().getAddon(YoutubeAddon.class);
		// The same effects as the YouTube tab's (and the same settings): the in-page equalizer --
		// Android's own effects can't reach a web page's audio.
		if (addon != null) addon.getPreferenceStore().addBroadcastListener(this);
		// The application context, not the activity's: the page has to outlive the activity it was
		// started in (a rotation, Android Auto (re)connecting), moving to the new one's window -- see
		// moveTo() -- instead of stopping the music every time the screen is rebuilt.
		web = new WebView(a.getContext().getApplicationContext());
		WebSettings s = web.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setMediaPlaybackRequiresUserGesture(false);
		CookieManager cm = CookieManager.getInstance();
		cm.setAcceptCookie(true);
		cm.setAcceptThirdPartyCookies(web, true);
		web.addJavascriptInterface(new Js(), "ZrAudio");
		web.setWebChromeClient(new WebChromeClient());
		web.setWebViewClient(new WebViewClient() {
			@Override
			public void onPageFinished(WebView view, String url) {
				log("page loaded");
				inject();
				injectEqualizer();
			}
		});

		// Invisible and out of the way: behind all of the app's content (index 0 of the window's
		// decor view), nearly transparent, not focusable and swallowing any stray touch. It still
		// has a real size, since YouTube's player won't play in a window that's too small.
		web.setAlpha(0.01f);
		web.setFocusable(false);
		web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		web.setOnTouchListener((v, e) -> true);
		log("created (hidden page, separate from the YouTube tab)");
		moveTo(a);
	}

	/**
	 * Moves the hidden page into {@code a}'s window (behind all of its content), or just detaches it
	 * when {@code a} is null (the old activity is gone and no new one exists yet) -- the page keeps
	 * playing either way.
	 */
	@Override
	public void moveTo(@Nullable MainActivityDelegate a) {
		if (closed || (a == activity)) return;
		ViewGroup old = (ViewGroup) web.getParent();
		if (old != null) old.removeView(web);
		activity = a;

		if (a == null) {
			log("detached from its old window (activity gone), still playing");
			return;
		}

		ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
		decor.addView(web, 0, new FrameLayout.LayoutParams(UiUtils.toIntPx(a.getContext(), 320),
				UiUtils.toIntPx(a.getContext(), 180)));
		log((old == null) ? "attached to the activity window" : "moved to the new activity window");
	}

	@Nullable
	@Override
	public MainActivityDelegate getActivity() {
		return activity;
	}

	@Override
	public int getId() {
		return MediaPrefs.MEDIA_ENG_YT_AUDIO;
	}

	@Override
	public void prepare(PlayableItem source) {
		this.source = source;
		videoId = (source instanceof MusicTrackItem t) ? t.getVideoId() : null;
		prepared = false;
		endedReported = false;
		ad = false;
		quality = "";
		position = 0;
		duration = 0;
		want = true;
		int gen = ++generation;

		if (videoId == null) {
			fail("not a YouTube track: " + source);
			return;
		}

		// Consumed here, not by the session's own seek after "prepared": the page starts right there.
		long start = source.getPrefs().getPositionPref();
		String url = "https://m.youtube.com/watch?v=" + videoId;
		if (start >= 1000) url += "&t=" + (start / 1000) + 's';
		log("loading", "start=" + (start / 1000) + 's');
		web.loadUrl(url);

		handler.postDelayed(() -> {
			if ((gen == generation) && !prepared && !closed) {
				fail("the page didn't start playing within " + (START_TIMEOUT / 1000) + "s" +
						(ad ? " (stuck on an ad)" : ""));
			}
		}, START_TIMEOUT);
	}

	private void fail(String msg) {
		log("FAILED", msg);
		listener.onEngineError(this, new MediaEngineException("YouTube web player: " + msg));
	}

	/**
	 * Polls the page twice a second: keeps the player at its lowest quality, skips (or fast-forwards
	 * through) ads, (re)starts playback while the app wants it playing -- YouTube's own page pauses
	 * itself now and then -- and reports the state back through {@link Js#state}.
	 */
	private void inject() {
		eval("window.__zrWant=" + (want ? 1 : 0) + ";" +
				"if(!window.__zrAudio){window.__zrAudio=1;" +
				"setInterval(function(){" + JS_FIND +
				"var ad=!!(p&&p.classList&&p.classList.contains('ad-showing'));" +
				"if(ad){var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern," +
				".ytp-skip-ad-button');if(b){b.click();}else if(v&&isFinite(v.duration)&&v.duration>0)" +
				"{v.muted=true;window.__zrAdMuted=1;v.currentTime=v.duration;}}" +
				// YouTube's mobile page autoplays MUTED when nobody has tapped it (and nobody ever taps
				// this one): unmute through the player's own API too, or it just mutes itself again.
				"else if(v){window.__zrAdMuted=0;if(v.muted||v.volume<0.05||(p&&p.isMuted&&p.isMuted())){" +
				"try{if(p&&p.unMute)p.unMute();if(p&&p.setVolume)p.setVolume(100);}catch(e){}" +
				"v.muted=false;v.volume=1;" +
				"if(!window.__zrUnmuted){window.__zrUnmuted=1;ZrAudio.note('unmuted the page " +
				"(it had autoplayed muted)');}}}" +
				"var q=(p&&p.getPlaybackQuality)?p.getPlaybackQuality():'';" +
				"if(!ad&&p&&q&&q!=='tiny'){try{if(p.setPlaybackQualityRange)" +
				"p.setPlaybackQualityRange('tiny','tiny');else if(p.setPlaybackQuality)" +
				"p.setPlaybackQuality('tiny');}catch(e){}}" +
				"if(v&&window.__zrWant&&v.paused&&!v.ended){var pr=v.play();if(pr&&pr.catch)pr.catch(function(){});}" +
				"var id=(p&&p.getVideoData)?(p.getVideoData().video_id||''):'';" +
				// The 'ended' event itself, not just the poll: YouTube's own autoplay can move on to
				// another video before the next poll would ever see v.ended.
				"if(v&&!v.__zrEnd){v.__zrEnd=1;v.addEventListener('ended',function(){" +
				"var pp=document.getElementById('movie_player');" +
				"if(!(pp&&pp.classList&&pp.classList.contains('ad-showing')))" +
				"ZrAudio.state(0,1,v.currentTime*1000,v.duration*1000,0,'',id);});}" +
				"if(v)ZrAudio.state(v.paused?0:1,v.ended?1:0,v.currentTime*1000," +
				"isFinite(v.duration)?v.duration*1000:0,ad?1:0,q,id);" +
				"else ZrAudio.state(-1,0,0,0,ad?1:0,q,id);" +
				"},500);}");
	}

	private void injectEqualizer() {
		if (addon == null) return;
		String script = YoutubeEqualizerScript.getScript(web.getContext());
		if (!script.isEmpty()) web.evaluateJavascript(script, r -> configureEqualizer());
	}

	private void configureEqualizer() {
		if (closed || (addon == null)) return;
		web.evaluateJavascript("if (window.FermataEqualizer) window.FermataEqualizer.configure(" +
				YoutubeEqualizerScript.getConfigJson(addon) + ");", null);
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		if ((addon != null) && addon.eqPrefsChanged(prefs)) configureEqualizer();
	}

	@Override
	public boolean showOwnAudioEffects() {
		MainActivityDelegate activity = this.activity;
		if ((addon == null) || closed || (activity == null)) return false;
		log("opening effects (in-page equalizer)");
		if (!(activity.showFragment(me.aap.utils.R.id.generic_fragment) instanceof GenericFragment f))
			return false;
		f.setTitle(activity.getContext().getString(me.aap.fermata.R.string.audio_effects));
		f.setContentProvider(g -> {
			YoutubeEqualizerView v = new YoutubeEqualizerView(g.getContext());
			v.init(addon, this::configureEqualizer);
			g.addView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT));
			activity.insetScrollableContent(v);
		});
		return true;
	}

	@Override
	public void contributeToMenuEnd(OverlayMenu.Builder b) {
		b.addItem(me.aap.fermata.addon.web.R.id.youtube_equalizer, me.aap.fermata.R.drawable.equalizer,
				me.aap.fermata.R.string.effects).setHandler(item -> showOwnAudioEffects());
	}

	private void onState(int gen, int playing, int ended, double pos, double dur, int isAd,
											 String q, String pageVideoId) {
		if (closed || (gen != generation) || (source == null) || endedReported) return;

		boolean otherVideo = (pageVideoId != null) && !pageVideoId.isEmpty() &&
				!pageVideoId.equals(videoId);
		// Before this video has started, a different one is just the previous page still winding
		// down while the new one loads: not this song starting.
		if (!prepared && otherVideo) return;

		if (prepared && otherVideo) {
			// YouTube's own autoplay moved the page on to something else: that's this song over.
			log("the page moved on to another video (" + pageVideoId + "), treating as ended");
			ended = 1;
		}

		boolean nowAd = isAd != 0;
		if (nowAd != ad) {
			ad = nowAd;
			log(ad ? "ad showing, skipping it" : "ad over");
		}
		if ((q != null) && !q.equals(quality)) {
			quality = q;
			log("video quality=" + q + (q.equals("tiny") ? " (lowest)" : ""));
		}
		if (ad) return;

		position = (long) pos;
		if (dur > 0) duration = (long) dur;

		if (!prepared && (playing == 1)) {
			prepared = true;
			log("playing", "quality=" + quality, "duration=" + (duration / 1000) + 's');
			listener.onEnginePrepared(this);
		} else if (prepared && (ended == 1)) {
			endedReported = true;
			want = false;
			pause();
			log("ended");
			listener.onEngineEnded(this);
		}
	}

	@Override
	public void start() {
		want = true;
		eval("window.__zrWant=1;" + JS_FIND + "if(v){var pr=v.play();if(pr&&pr.catch)pr.catch(function(){});}");
		listener.onEngineStarted(this);
	}

	@Override
	public void pause() {
		want = false;
		eval("window.__zrWant=0;" + JS_FIND + "if(v)v.pause();");
	}

	@Override
	public void stop() {
		pause();
		source = null;
		generation++;
	}

	@Override
	public PlayableItem getSource() {
		return source;
	}

	@Override
	public boolean canPause() {
		return true;
	}

	@Override
	public FutureSupplier<Long> getDuration() {
		return completed(duration);
	}

	@Override
	public FutureSupplier<Long> getPosition() {
		return completed(position);
	}

	@Override
	public void setPosition(long position) {
		this.position = position;
		eval(JS_FIND + "if(v)v.currentTime=" + (position / 1000.0) + ";");
	}

	@Override
	public FutureSupplier<Float> getSpeed() {
		return completed(speed);
	}

	@Override
	public void setSpeed(float speed) {
		this.speed = speed;
		eval(JS_FIND + "if(v)v.playbackRate=" + speed + ";");
	}

	@Override
	public void setVideoView(@Nullable VideoView view) {
		// Audio only: the hidden page's picture is never shown anywhere.
	}

	@Override
	public float getVideoWidth() {
		return 0;
	}

	@Override
	public float getVideoHeight() {
		return 0;
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		generation++;
		handler.removeCallbacksAndMessages(null);
		if (addon != null) addon.getPreferenceStore().removeBroadcastListener(this);
		log("closed");
		MusicPlayer.webAudioEngineClosed(this);

		try {
			web.stopLoading();
			web.loadUrl("about:blank");
			ViewGroup parent = (ViewGroup) web.getParent();
			if (parent != null) parent.removeView(web);
			web.destroy();
		} catch (Throwable ex) {
			Log.e(ex, "Failed to destroy the web fallback player");
		}
	}

	private void eval(String js) {
		if (!closed) web.evaluateJavascript("(function(){" + js + "})();", null);
	}

	private void log(Object... details) {
		Object[] d = new Object[details.length + 2];
		d[0] = "web-fallback";
		d[1] = "id=" + videoId;
		System.arraycopy(details, 0, d, 2, details.length);
		DiagnosticLog.log(TAG, d);
	}

	private final class Js {
		@JavascriptInterface
		public void state(int playing, int ended, double pos, double dur, int ad, String quality,
											String pageVideoId) {
			int gen = generation;
			handler.post(() -> onState(gen, playing, ended, pos, dur, ad, quality, pageVideoId));
		}

		/** A one-off event worth a diagnostic line (e.g. the page had to be unmuted). */
		@JavascriptInterface
		public void note(String msg) {
			handler.post(() -> log(msg));
		}
	}
}
