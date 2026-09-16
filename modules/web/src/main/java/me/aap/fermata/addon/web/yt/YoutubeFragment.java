package me.aap.fermata.addon.web.yt;

import static me.aap.fermata.addon.web.FermataWebClient.isYoutubeUri;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.web.FermataChromeClient;
import me.aap.fermata.addon.web.FermataWebView;
import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.WebBrowserAddon;
import me.aap.fermata.addon.web.WebBrowserFragment;
import me.aap.fermata.media.lib.DefaultMediaLib;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.VideoView;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
@Keep
@SuppressWarnings("unused")
public class YoutubeFragment extends WebBrowserFragment implements FermataServiceUiBinder.Listener {
	static final String DEFAULT_URL = "https://m.youtube.com";
	private static final Set<String> DEFAULT_URLS = new HashSet<>(Arrays.asList(DEFAULT_URL, DEFAULT_URL + '/'));
	private static final String YT_VIDEO_VIEW_TAG = "yt_video_view_overlay";
	/**
	 * How far apart a page-side pause and the start of a host interruption may be and still be
	 * treated as the same event. Needed in both directions: the page can stop playing the moment the
	 * projected screen is taken away, before the app is told anything, and the app can equally be
	 * told first and the page's own "pause" event arrive a beat later.
	 */
	private static final long HOST_INTERRUPTION_PAUSE_GRACE_MS = 4000L;
	/**
	 * How long after regaining the screen to run each recovery check, cumulatively. The first is
	 * delayed because coming back also kicks off a fullscreen exit+enter rebuild (see {@link
	 * WebBrowserFragment#rebuildFullscreenVideoIfActive()}), which resizes the WebView, and YouTube's
	 * player reacts to a resize by restarting -- issuing play() into the middle of that is exactly
	 * what {@code YoutubeMediaEngine#paused()}'s retry guard exists to paper over. The later ones
	 * exist because a single check was never enough: that rebuild's own budget runs to 6s (see {@code
	 * WebBrowserFragment#FULLSCREEN_RECOVERY_TIMEOUT_MS}), and captured traces show the page settling
	 * well after the one 1.5s check had already run and concluded there was nothing to do.
	 */
	private static final long[] HOST_INTERRUPTION_CHECK_DELAYS_MS =
			{1500L, 2000L, 3000L, 4000L, 5000L, 5000L};
	/**
	 * How many times one recovery will ask the page to start playing again before it stops nudging
	 * and just watches. Spread over the first few checks above, leaving the rest of the window purely
	 * observational: a captured trace has the page taking eight seconds to honour a play() after a
	 * two-and-a-half-minute background, so the useful thing after a few attempts is patience, not
	 * more attempts.
	 */
	private static final int MAX_PAGE_RESTARTS = 3;
	/** Below this, a restored position isn't worth the seek (and risks fighting the page over a
	 * video that legitimately just started). */
	private static final long MIN_POSITION_TO_RESTORE_MS = 10000L;
	/** A page-side position under this counts as "the element was reset", not as real progress. */
	private static final long RESET_POSITION_MS = 3000L;
	private boolean playOnResume;
	private boolean hostInterrupted;
	private long hostInterruptionStartedAt;
	private long hostResumeOperation;
	/**
	 * What the page's own {@code <video>} element was playing, and how far into it, when the
	 * interruption began -- captured because an Android Auto display takeover can leave YouTube's
	 * player having torn down and rebuilt that element, in which case what comes back is a fresh one
	 * sitting at 0. Simply telling it to play from there would silently restart the video from the
	 * beginning (which is what a manual pause/play does today). Best effort: if the snapshot doesn't
	 * come back before the screen goes, nothing is restored and the recovery just plays from wherever
	 * the page is, exactly as before.
	 */
	private long interruptedPositionMs;
	@Nullable
	private String interruptedVideoId;
	/** Bumped once per interruption START, so a snapshot that only comes back after the screen is
	 * already back is still accepted (unlike {@link #hostResumeOperation}, which also moves on the
	 * way out and would discard exactly the answer the recovery needs). */
	private long hostInterruptionCount;
	/** Restarts spent by the recovery currently running -- see {@link #MAX_PAGE_RESTARTS}. */
	private int pageRestarts;

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.youtube_fragment;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.youtube, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
		YoutubeAddon addon = AddonManager.get().getAddon(YoutubeAddon.class);
		if (addon == null) return;

		String url;
		boolean pause;

		if (state != null) {
			url = state.getString("url", DEFAULT_URL);
			pause = state.getBoolean("pause", false);
		} else {
			url = DEFAULT_URL;
			pause = false;
		}

		MainActivityDelegate.getActivityDelegate(view.getContext()).onSuccess(a -> {
			YoutubeWebView webView = a.findViewById(R.id.ytWebView);
			initWebView(webView, addon, view);
			registerListeners(a);
			webView.loadUrl(DEFAULT_URL);
			if (!DEFAULT_URL.equals(url)) a.post(() -> webView.loadUrl(url));
			if (pause) {
				a.postDelayed(() -> {
					MediaSessionCallback cb = a.getMediaSessionCallback();
					if (cb.getEngine() instanceof YoutubeMediaEngine) cb.onPause();
				}, 3000L);
			}
		});
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle state) {
		super.onSaveInstanceState(state);
		String url = getUrl();
		if (url != null) state.putString("url", url);
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
		if (a == null) return;

		MediaSessionCallback cb = a.getMediaSessionCallback();
		if (cb.getEngine() instanceof YoutubeMediaEngine) state.putBoolean("pause", !cb.isPlaying());
	}

	// recoverFullscreenVideo() previously overrode WebBrowserFragment's plain "just re-enter
	// fullscreen" recovery with a page reload (plus a reseek and forced pause). That traded one bug
	// for another: it dropped playback on every routine Android Auto backgrounding, not just the
	// display-takeover freeze it was meant to catch, and even a more selective, health-probe-gated
	// version of it was found on-device to leave the WebView's fullscreen tracking stuck -- the
	// page's own document.fullscreenElement doesn't reliably clear from an app-initiated exit (see
	// FermataWebView#exitPageFullScreen(), now called from every force-exit site instead) -- which
	// blocked automatic recovery AND a manual re-tap of the fullscreen button alike, recoverable only
	// by restarting the app. No longer overridden: the base implementation (re-enter fullscreen on
	// the existing, already-loaded <video> element, no reload, no forced pause) is what runs here now.

	@Override
	public void onDestroyView() {
		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		// The WebView (and the YoutubeMediaEngine built around it) is about to be torn down along
		// with this view -- same situation as applyPrivateModeProfile() swapping the WebView below,
		// and the same fix applies: stop first, or the media session keeps pointing at an engine
		// tied to a dead WebView (e.g. after a theme switch recreates the Activity) until a new
		// video happens to start playing. Without this, the control panel/video menu built from
		// that stale engine stays showing and its actions (e.g. "Audio effects") can end up running
		// against an already-destroyed Activity.
		MediaSessionCallback cb = a.getMediaSessionCallback();
		if (cb.getEngine() instanceof YoutubeMediaEngine) cb.onStop();
		unregisterListeners(a);
		removeVideoViewOverlay(a);
		super.onDestroyView();
	}

	@Override
	protected FermataWebView createWebView(Context ctx) {
		YoutubeWebView v = new YoutubeWebView(ctx);
		v.setId(R.id.ytWebView);
		return v;
	}

	@Override
	protected void initWebView(FermataWebView webView, WebBrowserAddon addon, View root) {
		applyProfile(webView);
		YoutubeWebView yt = (YoutubeWebView) webView;
		MainActivityDelegate a = MainActivityDelegate.get(root.getContext());
		VideoView videoView = getOrCreateVideoViewOverlay(a);
		YoutubeWebClient webClient = new YoutubeWebClient();
		YoutubeChromeClient chromeClient = new YoutubeChromeClient(yt, videoView);
		yt.init(addon, webClient, chromeClient);
	}

	@Override
	protected void applyPrivateModeProfile() {
		// Swapping the WebView drops whatever JS interface/media engine was tied to the old
		// instance -- stop playback first so the media session doesn't keep pointing at it.
		MediaSessionCallback cb = MainActivityDelegate.get(requireContext()).getMediaSessionCallback();
		if (cb.getEngine() instanceof YoutubeMediaEngine) cb.onStop();

		// Also drop out of fullscreen first -- the video-view overlay outlives the WebView swap
		// (it's reused via getOrCreateVideoViewOverlay()), so leaving it up would keep showing the
		// old instance's last frame over a WebView that no longer has any content backing it.
		FermataWebView v = getWebView();
		if (v != null) {
			FermataChromeClient chrome = v.getWebChromeClient();
			if ((chrome != null) && chrome.isFullScreen()) chrome.exitFullScreen();
		}

		super.applyPrivateModeProfile();
	}

	@Override
	protected String urlToLoadAfterProfileSwitch(FermataWebView old, WebBrowserAddon addon) {
		// A Private Mode switch should always land on a clean homepage, even mid-video (including
		// fullscreen playback) -- reloading the same video would undercut the fresh,
		// no-recommendations session Private Mode is supposed to start.
		return DEFAULT_URL;
	}

	// YouTube's fullscreen custom view used to live nested inside this fragment's own layout, so
	// showing the app's control panel (which shrinks the fragment's container to make room for it)
	// also shrank the video, letterboxing it. Host the video as a full-window overlay directly on
	// the activity's root instead, so it keeps its own size regardless of the control panel/bars.
	private VideoView getOrCreateVideoViewOverlay(MainActivityDelegate a) {
		ConstraintLayout root = a.findViewById(me.aap.fermata.R.id.main_activity);
		View existing = root.findViewWithTag(YT_VIDEO_VIEW_TAG);
		if (existing instanceof VideoView) return (VideoView) existing;

		YoutubeVideoView v = new YoutubeVideoView(root.getContext(), null);
		v.setTag(YT_VIDEO_VIEW_TAG);
		v.setVisibility(View.GONE);
		// Whatever this overlay is crossfading over/under -- the page on the way into fullscreen, the
		// next video's player on the way between videos -- there is a moment where nothing has been
		// drawn into it yet, and an unpainted view shows whatever is behind it. Black is the only
		// backdrop that reads as a deliberate dissolve rather than a flash.
		v.setBackgroundColor(Color.BLACK);
		// Below control_panel/floating_button/menus (elevation 10dp) so they still show over the
		// video, but above the rest of the app chrome (toolbar/nav bar/body, elevation 0).
		v.setElevation(UiUtils.toPx(root.getContext(), 5));

		ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(0, 0);
		lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
		root.addView(v, lp);
		return v;
	}

	private void removeVideoViewOverlay(MainActivityDelegate a) {
		if (a == null) return;
		ConstraintLayout root = a.findViewById(me.aap.fermata.R.id.main_activity);
		if (root == null) return;
		View v = root.findViewWithTag(YT_VIDEO_VIEW_TAG);
		if (v == null) return;
		// Drop the chrome client hook with the view, or the fullscreen action would keep poking a
		// dead WebView instead of falling back to the app's own fullscreen once we're gone.
		if (v instanceof VideoView vv) vv.setNativeFullscreen(null);
		root.removeView(v);
	}

	@Override
	protected void registerListeners(MainActivityDelegate a) {
		super.registerListeners(a);
		a.getMediaServiceBinder().addBroadcastListener(this);
	}

	protected void unregisterListeners(MainActivityDelegate a) {
		super.unregisterListeners(a);
		a.getMediaServiceBinder().removeBroadcastListener(this);
	}

	/**
	 * An Android Auto host interruption (a reversing/360 camera overlay, the car's own system taking
	 * the projected screen) leaves YouTube playback stopped and nothing to ever start it again: the
	 * page stops the {@code <video>} element on its own -- the app's media session never issues a
	 * pause, and {@code YoutubeMediaEngine} deliberately holds no audio focus, so none of the usual
	 * "resume when the interruption is over" machinery is even in play -- and the resulting DOM
	 * "pause" event reaches {@code YoutubeMediaEngine#paused()}, which faithfully mirrors it into the
	 * media session. Recovery for that (and for anything else that briefly takes the screen and
	 * leaves the page paused) starts here: remember that this interruption began, and whether
	 * playback was live going into it.
	 * <p>
	 * Only arms; nothing is resumed until {@link #onHostInterruptionEnded()}, and only then if the
	 * pause really was the page's own (never a pause the user or the car's transport controls asked
	 * for -- see {@code YoutubeMediaEngine#getLastExternalPauseTime()}).
	 */
	@Override
	public void onHostInterruptionStarted() {
		if (!BuildConfig.AUTO || hostInterrupted) return;
		Context ctx = getContext();
		if (ctx == null) return;
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(ctx).peek();
		if (a == null) return;
		long now = SystemClock.elapsedRealtime();
		if (!wasYoutubePlaying(a, now)) {
			DiagnosticLog.log("INTERRUPT", "started, nothing to recover (YouTube not playing)");
			return;
		}
		DiagnosticLog.log("INTERRUPT", "started, armed for resume");
		hostInterrupted = true;
		hostInterruptionStartedAt = now;
		// Cancels any resume still pending from a previous interruption -- see
		// reconcileAfterHostInterruption().
		hostResumeOperation++;
		snapshotPlaybackPosition(a);
	}

	/** See {@link #interruptedPositionMs}. */
	private void snapshotPlaybackPosition(MainActivityDelegate a) {
		interruptedPositionMs = 0;
		interruptedVideoId = null;
		long n = ++hostInterruptionCount;
		if (!(a.getMediaSessionCallback().getEngine() instanceof YoutubeMediaEngine eng)) return;
		eng.getPageState().onSuccess(st -> {
			// Only a newer interruption invalidates this -- the answer routinely arrives after the
			// screen is already back, which is precisely when it gets used.
			if ((n != hostInterruptionCount) || !st.hasVideo) return;
			interruptedPositionMs = st.positionMs;
			interruptedVideoId = st.videoId;
		});
	}

	@Override
	public void onHostInterruptionEnded() {
		if (!BuildConfig.AUTO || !hostInterrupted) return;
		DiagnosticLog.log("INTERRUPT", "ended, resume check scheduled");
		hostInterrupted = false;
		long startedAt = hostInterruptionStartedAt;
		hostInterruptionStartedAt = 0;
		long op = ++hostResumeOperation;
		pageRestarts = 0;
		scheduleHostInterruptionCheck(op, startedAt, 0);
	}

	private void scheduleHostInterruptionCheck(long op, long startedAt, int attempt) {
		Context ctx = getContext();
		if (ctx == null) return;
		MainActivityDelegate.getActivityDelegate(ctx)
				.onSuccess(a -> a.postDelayed(() -> reconcileAfterHostInterruption(a, op, startedAt, attempt),
						HOST_INTERRUPTION_CHECK_DELAYS_MS[attempt]));
	}

	/**
	 * Puts playback back the way the interruption found it -- and, crucially, decides that against
	 * what the PAGE is actually doing rather than against what the media session believes.
	 * <p>
	 * The session's belief is not evidence here. A display takeover can leave YouTube's player having
	 * torn down and rebuilt its {@code <video>} element, and a freshly built element is simply paused
	 * at 0 -- it never fired a "pause" event, because as far as the DOM is concerned nothing paused;
	 * the thing that was playing stopped existing. Nothing else in this addon reports page state
	 * unprompted either ({@code YoutubeWebView#attachListeners()} only announces an element it finds
	 * already playing), so the session sails on saying PLAYING while the screen sits frozen -- the
	 * exact reported symptom, control panel showing playback that isn't happening, and previously
	 * unrecoverable because this method's first act was to believe it and return ("resume not needed:
	 * already playing"). Asking the page closes that hole by construction.
	 * <p>
	 * Runs up to {@code HOST_INTERRUPTION_CHECK_DELAYS_MS.length} times and stops as soon as the page
	 * confirms it is playing. Every outcome is verified, the resume included: asking for playback and
	 * assuming it happened is the same mistake as asking the session -- a trace has an
	 * interruption-triggered resume followed by thirty seconds of nothing, the page simply never
	 * having started, with no further check to notice. If the last check still finds them
	 * disagreeing, the session is corrected to PAUSED instead -- worst case the user taps play once,
	 * on controls that are at least telling the truth.
	 */
	private void reconcileAfterHostInterruption(MainActivityDelegate a, long op, long startedAt,
																							int attempt) {
		if ((op != hostResumeOperation) || isHidden() || (getView() == null)) {
			DiagnosticLog.log("INTERRUPT", "resume skipped: superseded or fragment gone");
			return;
		}
		FermataServiceUiBinder b = a.getMediaServiceBinder();
		if (!YoutubeMediaEngine.isYoutubeItem(b.getCurrentItem())) {
			DiagnosticLog.log("INTERRUPT", "resume skipped: current item is not YouTube");
			return;
		}
		MediaSessionCallback cb = a.getMediaSessionCallback();
		if (!(cb.getEngine() instanceof YoutubeMediaEngine eng)) {
			DiagnosticLog.log("INTERRUPT", "resume skipped: engine is not YoutubeMediaEngine");
			return;
		}
		// The last pass never restarts anything -- it only decides. Restarting and then, in the same
		// pass, concluding from the same (necessarily pre-restart) reading that the restart failed
		// would have it immediately pause what it just asked to play.
		boolean verdictPass = attempt >= HOST_INTERRUPTION_CHECK_DELAYS_MS.length - 1;

		eng.getPageState().onCompletion((st, err) -> {
			if (op != hostResumeOperation) return;
			if (err != null) Log.d(err, "Failed to read the YouTube page's playback state");
			// A missing answer (the JS bridge didn't come back, or there is no <video> element yet
			// mid-rebuild) is "don't know", never "it's fine" -- it just means try again.
			boolean known = (err == null) && (st != null) && st.hasVideo;
			boolean pageStalled = known && st.paused && !st.ended;

			// The page is running: whatever this recovery was for, it is over. The session follows off
			// the page's own "playing" event if it hasn't already.
			if (known && !st.paused) {
				DiagnosticLog.log("INTERRUPT", "settled: page is playing");
				return;
			}

			if (pageStalled && !verdictPass && (pageRestarts < MAX_PAGE_RESTARTS)) {
				long restore = positionToRestore(st);
				if (b.isPlaying()) {
					pageRestarts++;
					DiagnosticLog.logAndToast("INTERRUPT", "page stalled while session says PLAYING",
							"id=" + st.videoId, "pos=" + st.positionMs, "restorePos=" + restore);
					eng.resumePageAfterInterruption(restore);
				} else {
					long pausedAt = eng.getLastExternalPauseTime();
					// 0 means the last pause was the app's own -- the user (or the car's transport
					// controls) asked for it, so it stays. Anything older than the interruption is some
					// earlier pause the user has been sitting on, not something this interruption
					// caused. Either way there is nothing here to recover, now or on a later pass.
					if ((pausedAt == 0) || (pausedAt < startedAt - HOST_INTERRUPTION_PAUSE_GRACE_MS)) {
						DiagnosticLog.log("INTERRUPT", "resume skipped: pause was not this interruption's",
								"(pausedAt=" + pausedAt, "startedAt=" + startedAt + ')');
						return;
					}
					pageRestarts++;
					Log.i("Resuming YouTube playback paused by a host interruption");
					DiagnosticLog.logAndToast("INTERRUPT", "resuming playback",
							"restorePos=" + restore);
					// The page first and the session second, both. cb.onPlay() alone was what this
					// used to do, and it reaches the page only as a bare play() on whatever element
					// querySelector finds -- which after a long background is routinely one the player
					// has already abandoned, so nothing actually started (see resumeAt()). Doing the
					// page's half through the player object, and seeking before playing so a reset
					// element doesn't audibly restart from zero, is what makes the resume stick;
					// cb.onPlay() is still needed for the session's own state, and its own redundant
					// play() is a no-op by then.
					eng.resumePageAfterInterruption(restore);
					cb.onPlay();
				}
			}

			if (!verdictPass) {
				scheduleHostInterruptionCheck(op, startedAt, attempt + 1);
				return;
			}
			// Only on a definite reading -- an unanswered probe is not grounds for overriding
			// anything.
			if (b.isPlaying() && pageStalled) {
				// Out of attempts with the two still disagreeing. Leaving the session claiming PLAYING
				// is the worst of the available outcomes: the controls lie, and the play button (which
				// is showing as pause) does nothing useful. Say PAUSED, which is at least true and
				// which makes a single tap on play work.
				DiagnosticLog.logAndToast("INTERRUPT", "giving up -- marking playback paused");
				cb.onPause();
			}
		});
	}

	/**
	 * Where {@link #reconcileAfterHostInterruption} should resume from, or a negative value for
	 * "wherever the page already is". Only overrides the page when the page has clearly lost its
	 * place: same video as before the interruption, the element sitting at (or near) zero, and a
	 * pre-interruption position actually worth going back to.
	 */
	private long positionToRestore(YoutubeWebView.PageState st) {
		if ((interruptedVideoId == null) || !interruptedVideoId.equals(st.videoId)) return -1;
		if (interruptedPositionMs < MIN_POSITION_TO_RESTORE_MS) return -1;
		if (st.positionMs >= RESET_POSITION_MS) return -1;
		return interruptedPositionMs;
	}

	/**
	 * Whether YouTube playback is live right now, or was until a page-side pause moments ago --
	 * covering the case where the page already stopped before the app was told the screen was gone.
	 */
	private boolean wasYoutubePlaying(MainActivityDelegate a, long now) {
		FermataServiceUiBinder b = a.getMediaServiceBinder();
		if (!YoutubeMediaEngine.isYoutubeItem(b.getCurrentItem())) return false;
		if (b.isPlaying()) return true;
		if (!(a.getMediaSessionCallback().getEngine() instanceof YoutubeMediaEngine eng)) return false;
		long pausedAt = eng.getLastExternalPauseTime();
		return (pausedAt != 0) && ((now - pausedAt) < HOST_INTERRUPTION_PAUSE_GRACE_MS);
	}

	@Override
	public void onPause() {
		if (!BuildConfig.AUTO) {
			MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
				FermataServiceUiBinder b = a.getMediaServiceBinder();
				if (YoutubeMediaEngine.isYoutubeItem(b.getCurrentItem()) && b.isPlaying()) {
					b.getMediaSessionCallback().onPause();
					playOnResume = true;
				} else {
					playOnResume = false;
				}
			});
		}
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();
		if (BuildConfig.AUTO || !playOnResume) return;
		playOnResume = false;
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
			FermataServiceUiBinder b = a.getMediaServiceBinder();
			if (YoutubeMediaEngine.isYoutubeItem(b.getCurrentItem())) {
				b.getMediaSessionCallback().onPlay();
			}
		});
	}

	public void loadUrl(String url) {
		FermataWebView v = getWebView();
		if (v != null) v.loadUrl(url);
	}

	@Override
	public void onPlayableChanged(MediaLib.PlayableItem oldItem, MediaLib.PlayableItem newItem) {
		if (isHidden()) return;

		if (YoutubeMediaEngine.isYoutubeItem(newItem)) {
			FermataWebView v = getWebView();
			MainActivityDelegate a = MainActivityDelegate.get(getContext());
			if (v == null) return;

			FermataChromeClient chrome = v.getWebChromeClient();
			if (chrome == null) return;

			if (!DEFAULT_URLS.contains(getUrl())) chrome.enterFullScreen();
		} else if (YoutubeMediaEngine.isYoutubeItem(oldItem)) {
			FermataWebView v = getWebView();
			if (v == null) return;
			FermataChromeClient chrome = v.getWebChromeClient();
			if (chrome != null) chrome.exitFullScreen();
		}
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return YoutubeToolBarMediator.getInstance();
	}

	@Override
	public boolean canScrollUp() {
		FermataWebView v = getWebView();
		if (v == null) return false;
		FermataChromeClient chrome = v.getWebChromeClient();
		return (chrome != null) && (chrome.isFullScreen() || (v.getScrollY() > 0));
	}

	@Nullable
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(YoutubeAddon.class);
	}

	@Nullable
	protected YoutubeWebView getWebView() {
		View v = getView();
		return (v != null) ? v.findViewById(R.id.ytWebView) : null;
	}

	protected boolean isDesktopVersionSupported() {
		return false;
	}

	@Nullable
	String getCurrentVideoId() {
		FermataWebView v = getWebView();
		if (v == null) return null;
		String url = v.getUrl();
		if ((url == null) || !isYoutubeUri(Uri.parse(url))) return null;
		return YoutubeVideoItem.extractVideoId(url);
	}

	@Override
	public void contributeToNavBarMenu(OverlayMenu.Builder b) {
		super.contributeToNavBarMenu(b);

		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		DefaultMediaLib lib = (DefaultMediaLib) a.getLib();
		MediaLib.Favorites favorites = lib.getFavorites();
		YoutubeVideoItem current = getCurrentVideoItem(lib);

		boolean isFav = (current != null) && current.isFavoriteItem();
		b.addItem(me.aap.fermata.R.id.favorites,
				isFav ? me.aap.fermata.R.drawable.favorite_filled : me.aap.fermata.R.drawable.favorite,
				me.aap.fermata.R.string.favorites)
				.setFutureSubmenu(sb -> favoritesMenu(sb, favorites, current));

		b.addItem(me.aap.fermata.R.id.playlists, me.aap.fermata.R.drawable.playlist,
				me.aap.fermata.R.string.playlists)
				.setFutureSubmenu(sb -> playlistsMenu(sb, lib, current));
	}

	void showFavoritesMenu() {
		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		DefaultMediaLib lib = (DefaultMediaLib) a.getLib();
		MediaLib.Favorites favorites = lib.getFavorites();
		YoutubeVideoItem current = getCurrentVideoItem(lib);
		a.getToolBarMenu().showFuture(sb -> favoritesMenu(sb, favorites, current));
	}

	/**
	 * A single tap on the toolbar's favorites button now toggles the current video directly
	 * instead of opening favoritesMenu() (whose top item did the exact same toggle, just one menu
	 * open away) -- browsing to other favorited videos, the rest of that menu, is still reachable
	 * via long-press on the same button (see YoutubeToolBarMediator).
	 */
	void toggleCurrentVideoFavorite() {
		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		DefaultMediaLib lib = (DefaultMediaLib) a.getLib();
		YoutubeVideoItem current = getCurrentVideoItem(lib);
		if (current == null) return;

		MediaLib.Favorites favorites = lib.getFavorites();
		if (current.isFavoriteItem()) favorites.removeItem(current);
		else favorites.addItem(current);
		notifyFavoritesChanged();
	}

	void showPlaylistsMenu() {
		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		DefaultMediaLib lib = (DefaultMediaLib) a.getLib();
		YoutubeVideoItem current = getCurrentVideoItem(lib);
		a.getToolBarMenu().showFuture(sb -> playlistsMenu(sb, lib, current));
	}

	@Nullable
	private YoutubeVideoItem getCurrentVideoItem(DefaultMediaLib lib) {
		String videoId = getCurrentVideoId();
		if (videoId == null) return null;

		YoutubeAddon addon = (YoutubeAddon) getAddon();
		FermataWebView v = getWebView();
		if ((addon == null) || (v == null)) return null;

		// Don't overwrite a title the player itself reported (cached by YoutubeMediaEngine#playing()
		// for whatever is actually playing) with the WebView's document title, which lags YouTube's
		// single-page-app navigation and carries its " - YouTube" suffix. Only fill in a gap.
		if (videoId.equals(addon.getVideoTitle(videoId))) {
			String title = v.getTitle();
			addon.cacheVideoTitle(videoId, ((title == null) || title.isEmpty()) ? videoId : title);
		}
		return new YoutubeVideoItem(videoId, addon.getRootItem(lib));
	}

	/** Whether the video currently on screen is already a favorite -- see YoutubeToolBarMediator,
	 * which uses this to pick the toolbar favorites button's filled-vs-outline icon. */
	boolean isCurrentVideoFavorite() {
		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		DefaultMediaLib lib = (DefaultMediaLib) a.getLib();
		YoutubeVideoItem current = getCurrentVideoItem(lib);
		return (current != null) && current.isFavoriteItem();
	}

	/** Lets YoutubeToolBarMediator (and anything else reacting to FRAGMENT_CONTENT_CHANGED, e.g.
	 * a rebuilt nav-bar menu) pick up an add/remove that happened outside of a page navigation --
	 * see YoutubeToolBarMediator#onActivityEvent(). */
	private void notifyFavoritesChanged() {
		MainActivityDelegate.get(requireContext()).fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
	}

	private FutureSupplier<Void> favoritesMenu(OverlayMenu.Builder b, MediaLib.Favorites favorites,
																							@Nullable YoutubeVideoItem current) {
		if (current != null) {
			if (current.isFavoriteItem()) {
				b.addItem(me.aap.fermata.R.id.favorites_remove, me.aap.fermata.R.drawable.favorite_filled,
						me.aap.fermata.R.string.favorites_remove).setHandler(i -> {
					favorites.removeItem(current);
					notifyFavoritesChanged();
					return true;
				});
			} else {
				b.addItem(me.aap.fermata.R.id.favorites_add, me.aap.fermata.R.drawable.favorite,
						me.aap.fermata.R.string.favorites_add).setHandler(i -> {
					favorites.addItem(current);
					notifyFavoritesChanged();
					return true;
				});
			}
		}

		return favorites.getUnsortedChildren().main().then(list -> {
			int i = 0;
			for (MediaLib.Item it : list) {
				if (it instanceof MediaLib.ExternallyPlayableItem) {
					b.addItem(UiUtils.getArrayItemId(i++), it.getName()).setData(it)
							.setHandler(item -> favoriteItemSelected(item, favorites));
				}
			}
			return completedVoid();
		});
	}

	private boolean favoriteItemSelected(OverlayMenuItem item, MediaLib.Favorites favorites) {
		MediaLib.Item it = item.getData();
		if (item.isLongClick()) {
			item.getMenu().show(sb -> sb.addItem(me.aap.fermata.R.id.favorites_remove,
					me.aap.fermata.R.string.favorites_remove).setHandler(i -> {
				favorites.removeItem((MediaLib.PlayableItem) it);
				return true;
			}));
		} else if (it instanceof MediaLib.ExternallyPlayableItem ext) {
			ext.loadInFragment(this, ext);
		}
		return true;
	}

	private FutureSupplier<Void> playlistsMenu(OverlayMenu.Builder b, DefaultMediaLib lib,
																							@Nullable YoutubeVideoItem current) {
		if (current != null) {
			List<MediaLib.PlayableItem> selection = Collections.singletonList(current);
			MainActivityDelegate.get(requireContext()).addPlaylistMenu(b, completed(selection));
		}

		MediaLib.Playlists playlists = lib.getPlaylists();
		return playlists.getUnsortedChildren().main().then(list -> {
			int i = 0;
			for (MediaLib.Item it : list) {
				if (it instanceof MediaLib.Playlist pl) {
					b.addItem(UiUtils.getArrayItemId(i++), it.getName())
							.setFutureSubmenu(sb -> playlistItemsMenu(sb, pl));
				}
			}
			return completedVoid();
		});
	}

	private FutureSupplier<Void> playlistItemsMenu(OverlayMenu.Builder b, MediaLib.Playlist playlist) {
		return playlist.getUnsortedChildren().main().then(list -> {
			for (int i = 0; i < list.size(); i++) {
				MediaLib.Item it = list.get(i);
				if (it instanceof MediaLib.ExternallyPlayableItem) {
					int idx = i;
					b.addItem(UiUtils.getArrayItemId(i), it.getName()).setData(it)
							.setHandler(item -> playlistItemSelected(item, playlist, idx));
				}
			}
			return completedVoid();
		});
	}

	private boolean playlistItemSelected(OverlayMenuItem item, MediaLib.Playlist playlist, int idx) {
		MediaLib.Item it = item.getData();
		if (item.isLongClick()) {
			item.getMenu().show(sb -> sb.addItem(me.aap.fermata.R.id.playlist_remove_item,
					me.aap.fermata.R.string.playlist_remove_item).setHandler(i -> {
				playlist.removeItem(idx);
				return true;
			}));
		} else if (it instanceof MediaLib.ExternallyPlayableItem ext) {
			ext.loadInFragment(this, ext);
		}
		return true;
	}

	@Override
	protected String getSearchUrl() {
		return "https://www.youtube.com/results?search_query=";
	}
}
