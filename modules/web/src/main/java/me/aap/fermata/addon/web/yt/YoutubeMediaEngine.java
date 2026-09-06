package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static me.aap.fermata.media.pref.MediaPrefs.MEDIA_ENG_YT;
import static me.aap.fermata.util.Utils.dynCtx;
import static me.aap.utils.async.Completed.completed;

import android.content.Context;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.support.v4.media.MediaMetadataCompat;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.media.AudioFocusRequestCompat;

import com.google.android.play.core.splitcompat.SplitCompat;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.yt.YoutubeAddon.VideoScale;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.ExtPlayable;
import me.aap.fermata.media.lib.ExtRoot;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.VideoView;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.GenericFragment;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.vfs.VirtualResource;
import me.aap.utils.vfs.generic.GenericFileSystem;

/**
 * @author Andrey Pavlenko
 */
class YoutubeMediaEngine implements MediaEngine, OverlayMenu.SelectionHandler {
	private static final int VIDEO_QUALITY_MASK = 1 << 31;
	private static final String ID = "youtube";
	private static final String CURRENT_ID = ID + ":current";
	private static final String NEXT_ID = ID + ":next";
	private static final String PREV_ID = ID + ":prev";
	private static final String END_ID = ID + ":end";
	private final YoutubeWebView web;
	private final MediaSessionCallback cb;
	private final ExtRoot mediaRoot;
	private final YoutubeItem next;
	private final YoutubeItem prev;
	private final YoutubeItem end;
	private YoutubeItem current;
	private String qualityUrl;
	private boolean ignorePause;
	// See paused() below: how long after our own start() or the page's own last confirmed playing()
	// a page-reported pause is still treated as suspect, and how many times it's retried before
	// being trusted as a real pause.
	private static final long PLAY_RETRY_GRACE_MS = 2000L;
	private static final int MAX_PLAY_RETRIES = 2;
	// How long a playing() confirmation must hold, with no intervening pause, before the retry
	// budget is considered "spent" and safe to refill -- see playing() below. Without this, a
	// persistent condition the page keeps refusing to stay playing under (e.g. a window too small
	// for YouTube to run video in) turns retrying into an unbounded loop: each retry's own
	// playing() confirmation would otherwise refill the budget right before the next pause spends
	// it again, one attempt at a time, forever.
	private static final long RETRY_BUDGET_REFILL_MS = 4000L;
	private long lastActivePlayTime;
	private long lastPausedTime;
	private int playRetries;

	public YoutubeMediaEngine(YoutubeWebView web, MainActivityDelegate a) {
		this.web = web;
		cb = a.getMediaSessionCallback();
		mediaRoot = new ExtRoot("youtube", a.getLib());
		next = new YoutubeItem(NEXT_ID, mediaRoot, GenericFileSystem.getInstance().create("http://youtube.com/next"));
		prev = new YoutubeItem(PREV_ID, mediaRoot, GenericFileSystem.getInstance().create("http://youtube.com/prev"));
		end = new YoutubeItem(END_ID, mediaRoot, GenericFileSystem.getInstance().create("http://youtube.com/end")) {
			@NonNull
			@Override
			public FutureSupplier<PlayableItem> getNextPlayable() {
				return completed(next);
			}
		};
	}

	void playing(String url) {
		// Every confirmed-playing moment re-arms the retry guard in paused() below -- not just an
		// explicit native start() -- since a page-reported pause can also follow a resize-triggered
		// player restart the app never asked for (confirmed on-device: a window resize alone, with
		// no play/pause tap at all, produces the same rapid playing-then-paused pairs from YouTube's
		// own player settling its layout). The retry budget itself only refills once playback has
		// actually held for a while with no pause in between -- see RETRY_BUDGET_REFILL_MS -- so a
		// persistent block (the page refusing to stay playing no matter how many times we ask, e.g.
		// a window too small to run video in) still gives up instead of retrying forever.
		long now = System.currentTimeMillis();
		if ((lastPausedTime == 0) || (now - lastPausedTime > RETRY_BUDGET_REFILL_MS)) playRetries = 0;
		lastActivePlayTime = now;

		if (BuildConfig.AUTO && web.getAddon().skipAd()) {
			web.loadUrl("javascript:\n" +
					"if (document.querySelectorAll('.ad-showing').length > 0) {\n" +
					"  var video = document.querySelector('video');\n" +
					"  if (video != null) video.currentTime = video.duration;\n" +
					"}");
		}

		if (url.startsWith("blob:")) url = url.substring(5);
		current = new Current(url);
		if (!web.getAddon().autoHighestQuality()) {
			qualityUrl = null;
		} else if (!url.isEmpty() && !url.equals(qualityUrl)) {
			qualityUrl = url;
			web.setHighestVideoQuality();
		}
		cb.setEngine(this);
		cb.onEngineStarted(this);
	}

	void ended() {
		current = end;
		qualityUrl = null;
		cb.onEngineEnded(this);
	}

	void paused() {
		// Confirmed on-device (window-resize repro): YouTube's own player can auto-pause the
		// <video> element for a beat right after it (or we) told it to play -- its internal layout
		// is still settling from a container-size change, and the pause DOM event this fires is
		// indistinguishable from a real one. Only second-guess it when nothing on the native side
		// asked for a pause since the last confirmed playing() (see pause()/stop() below, which
		// clear lastActivePlayTime), and only for a short grace window / bounded number of attempts
		// -- capped across the whole storm, not per playing() confirmation (see playing() above) --
		// so a genuine pause, or a persistent condition the page won't play under at all, still gets
		// honored instead of retrying indefinitely.
		long now = System.currentTimeMillis();
		lastPausedTime = now;

		if (!ignorePause && (lastActivePlayTime != 0) && (playRetries < MAX_PLAY_RETRIES) &&
				(now - lastActivePlayTime < PLAY_RETRY_GRACE_MS)) {
			playRetries++;
			Log.i("YoutubeMediaEngine.paused(): retrying play(), attempt ", playRetries);
			web.play();
			return;
		}

		ignorePause = true;
		cb.onPause();
		ignorePause = false;
	}

	@Override
	public int getId() {
		return MEDIA_ENG_YT;
	}

	@Override
	public boolean isVideoModeRequired() {
		// YouTube renders into its own WebView, not the app's native VideoView surface -- forcing
		// video mode would just show an empty black VideoView on top of the real content.
		return false;
	}

	@Override
	public void prepare(PlayableItem source) {
		if (source == next) {
			web.next();
		} else if (source == prev) {
			web.prev();
		} else {
			cb.onEnginePrepared(this);
		}
	}

	@Override
	public void start() {
		lastActivePlayTime = System.currentTimeMillis();
		lastPausedTime = 0;
		playRetries = 0;
		web.play();
	}

	@Override
	public void stop() {
		lastActivePlayTime = 0;
		if ((current == null) || (current == end)) return;
		current = null;
		qualityUrl = null;
		web.stop();
	}

	@Override
	public void pause() {
		lastActivePlayTime = 0;
		if (!ignorePause) web.pause();
	}

	@Override
	public PlayableItem getSource() {
		return current;
	}

	@Override
	public FutureSupplier<Long> getDuration() {
		return web.getDuration();
	}

	@Override
	public FutureSupplier<Long> getPosition() {
		return web.getPosition();
	}

	@Override
	public void setPosition(long position) {
		web.setPosition(position);
	}

	@Override
	public FutureSupplier<Float> getSpeed() {
		return web.getSpeed();
	}

	@Override
	public void setSpeed(float speed) {
		web.setSpeed(speed);
	}

	@Override
	public void setVideoView(VideoView view) {
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
	}

	// True no-ops, not "best-effort real request, ignore the result": actually making the real
	// AudioManagerCompat call (as a prior attempt at this did) registers MediaSessionCallback's
	// OnAudioFocusChangeListener for real, which makes its AUDIOFOCUS_LOSS_TRANSIENT-triggered
	// auto-pause path live for YouTube -- and since that pref request is a single object reused for
	// the whole session and never released except on a full stop, every resume-from-pause ends up
	// issuing a duplicate real focus request for a grant already held. Confirmed on-device this
	// broke pause/resume under Android Auto outright (not just during a display-takeover edge case),
	// with no interruption needed to trigger it -- staying fully inert here, as this class always
	// has, avoids the whole mechanism rather than trying to tune it further.
	@Override
	public boolean requestAudioFocus(@Nullable AudioManager audioManager,
																		@Nullable AudioFocusRequestCompat audioFocusReq) {
		return true;
	}

	@Override
	public void releaseAudioFocus(@Nullable AudioManager audioManager,
																 @Nullable AudioFocusRequestCompat audioFocusReq) {
	}

	@Override
	public boolean hasVideoMenu() {
		return true;
	}

	@Override
	public void contributeToMenu(OverlayMenu.Builder b) {
		Context ctx = dynCtx(web.getContext());
		Resources r = ctx.getResources();
		SplitCompat.install(ctx);
		b.addItem(R.id.video_quality,
				ResourcesCompat.getDrawable(r, R.drawable.video_quality, ctx.getTheme()),
				r.getString(R.string.video_quality)).setFutureSubmenu(this::videoQualityMenu);
		b.addItem(me.aap.fermata.R.id.video_scaling,
				ResourcesCompat.getDrawable(r, R.drawable.video_scaling, ctx.getTheme()),
				r.getString(me.aap.fermata.R.string.video_scaling)).setSubmenu(this::videoScalingMenu);
	}

	@Override
	public void contributeToMenuEnd(OverlayMenu.Builder b) {
		Context ctx = dynCtx(web.getContext());
		Resources r = ctx.getResources();
		b.addItem(R.id.youtube_equalizer,
				ResourcesCompat.getDrawable(r, me.aap.fermata.R.drawable.equalizer, ctx.getTheme()),
				r.getString(me.aap.fermata.R.string.audio_effects)).setHandler(item -> showEqualizer());
	}

	/**
	 * Opens the Equalizer/Bass Boost/Virtualizer panel as a full-screen {@link GenericFragment}
	 * instead of an {@link OverlayMenu} submenu -- the submenu is a narrow, edge-docked popup
	 * ({@code wrap_content}), too cramped for 10 band sliders, especially on Android Auto's wider
	 * screen. {@link GenericFragment} is reused (not a new addon-registered fragment) since
	 * addon fragment routing is one-fragment-per-addon and {@link YoutubeAddon} already owns its
	 * id for {@link YoutubeFragment}.
	 * <p>
	 * Uses {@link MainActivityDelegate#getActivityDelegate} rather than the synchronous
	 * {@code MainActivityDelegate.get()} -- a menu tap can be delivered after the Activity backing
	 * this WebView's Context has already been destroyed/recreated (e.g. a rotation or an Android
	 * Auto reconnect while the menu was open), in which case {@code get()} throws
	 * {@code ActivityDestroyedException} instead of returning; {@code onSuccess} simply no-ops
	 * there instead of crashing.
	 */
	private boolean showEqualizer() {
		MainActivityDelegate.getActivityDelegate(web.getContext()).onSuccess(a -> {
			// Showing this as a fragment hides YoutubeFragment's own root view -- the same
			// FragmentTransaction that shows this one briefly flips the still-playing YoutubeWebView's
			// visibility to GONE (Fragment.hide() on the outgoing fragment) as part of that. Some
			// devices' WebView/Chromium implementation treats that visibility flip as the page going
			// into the background and auto-pauses the video as a side effect -- confirmed intermittent
			// (device/timing-dependent) rather than a deterministic app-level pause call anywhere in
			// this path. If it was actually playing going in, nudge it back once shortly after the
			// transition settles, rather than silently leaving a UI-only navigation the user never
			// asked to pause for. Harmless if nothing paused it: onPlay() on an already-playing video
			// is a no-op.
			boolean wasPlaying = cb.isPlaying();

			if (!(a.showFragment(me.aap.utils.R.id.generic_fragment) instanceof GenericFragment f))
				return;
			f.setTitle(a.getContext().getString(me.aap.fermata.R.string.audio_effects));
			f.setContentProvider(g -> {
				YoutubeEqualizerView v = new YoutubeEqualizerView(g.getContext());
				v.init(web);
				g.addView(v, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
			});

			if (wasPlaying) a.postDelayed(() -> {
				if (!cb.isPlaying()) cb.onPlay();
			}, 500L);
		});
		return true;
	}

	private FutureSupplier<Void> videoQualityMenu(OverlayMenu.Builder b) {
		b.setSelectionHandler(this);
		return web.getVideoQualities().timeout(1100).main()
				.onFailure(err -> Log.e(err, "Failed to load video qualities"))
				.map(qualities -> {
					if ((qualities == null) || (qualities.isEmpty())) {
						b.addItem(me.aap.fermata.R.id.auto, null, me.aap.fermata.R.string.auto)
								.setChecked(true, true);
						return null;
					}

					String[] all = qualities.split(";");
					for (int i = 0; i < all.length; i++) {
						String q = all[i];
						if (q.startsWith("*")) q = q.substring(1);
						//noinspection StringEquality
						b.addItem(UiUtils.getArrayItemId(i), null, q).setChecked(q != all[i], true)
								.setData(i | VIDEO_QUALITY_MASK);
					}
					return null;
				});
	}

	private void videoScalingMenu(OverlayMenu.Builder b) {
		VideoScale scale = web.getAddon().getScale();
		b.addItem(me.aap.fermata.R.id.video_scaling_best, null, me.aap.fermata.R.string.video_scaling_best)
				.setChecked(scale == VideoScale.CONTAIN, true);
		b.addItem(me.aap.fermata.R.id.video_scaling_fill, null, me.aap.fermata.R.string.video_scaling_fill)
				.setChecked(scale == VideoScale.FILL, true);
		b.addItem(R.id.video_scaling_fill_proportional, null, R.string.video_scaling_fill_proportional)
				.setChecked(scale == VideoScale.COVER, true);
		b.addItem(me.aap.fermata.R.id.video_scaling_orig, null, me.aap.fermata.R.string.video_scaling_orig)
				.setChecked(scale == VideoScale.NONE, true);
		b.setSelectionHandler(this);
	}

	@Override
	public boolean menuItemSelected(OverlayMenuItem item) {
		int itemId = item.getItemId();
		if (itemId == me.aap.fermata.R.id.video_scaling_best) {
			web.setScale(VideoScale.CONTAIN);
			return true;
		} else if (itemId == me.aap.fermata.R.id.video_scaling_fill) {
			web.setScale(VideoScale.FILL);
			return true;
		} else if (itemId == R.id.video_scaling_fill_proportional) {
			web.setScale(VideoScale.COVER);
			return true;
		} else if (itemId == me.aap.fermata.R.id.video_scaling_orig) {
			web.setScale(VideoScale.NONE);
			return true;
		} else if (item.getData() instanceof Integer) {
			int d = item.getData();
			if ((d & VIDEO_QUALITY_MASK) != 0) web.setVideoQuality(d & ~VIDEO_QUALITY_MASK);
		}
		return false;
	}

	@Override
	public boolean isSplitModeSupported() {
		return false;
	}

	static boolean isYoutubeItem(MediaLib.Item i) {
		return (i instanceof YoutubeItem);
	}

	private static class YoutubeItem extends ExtPlayable {
		public YoutubeItem(String id, @NonNull BrowsableItem parent, @NonNull VirtualResource resource) {
			super(id, parent, resource);
		}

		@Override
		public boolean isSeekable() {
			return true;
		}

		@Override
		public boolean isVideo() {
			return true;
		}

		@Override
		public int getVideoEnginePref() {
			return MEDIA_ENG_YT;
		}

		@Override
		public boolean equals(@Nullable Object obj) {
			return obj == this;
		}

		@Override
		protected String buildSubtitle(MediaMetadataCompat md, SharedTextBuilder tb) {
			return null;
		}
	}

	private final class Current extends YoutubeItem {

		public Current(String url) {
			super(CURRENT_ID, mediaRoot, GenericFileSystem.getInstance().create(url));
		}

		@NonNull
		@Override
		protected FutureSupplier<MediaMetadataCompat> loadMeta() {
			FutureSupplier<String> getTitle = web.getVideoTitle();
			return web.getDuration().then(dur -> getTitle.map(title -> {
				MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder();
				b.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
				b.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
				return b.build();
			}));
		}

		@NonNull
		@Override
		public FutureSupplier<PlayableItem> getPrevPlayable() {
			return completed(prev);
		}

		@NonNull
		@Override
		public FutureSupplier<PlayableItem> getNextPlayable() {
			return completed(next);
		}
	}
}
