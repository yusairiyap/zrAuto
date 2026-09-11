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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.media.AudioFocusRequestCompat;

import com.google.android.play.core.splitcompat.SplitCompat;

import me.aap.fermata.addon.web.FermataChromeClient;
import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.yt.YoutubeAddon.VideoScale;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.ExtPlayable;
import me.aap.fermata.media.lib.ExtRoot;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
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
	// being trusted as a real pause. Kept to one retry -- confirmed on-device that a window too
	// small for YouTube to sustain playback under makes each retry produce its own brief, audible
	// play/stop blip, and doubling that up before giving up sounded worse than the original pause.
	private static final long PLAY_RETRY_GRACE_MS = 2000L;
	private static final int MAX_PLAY_RETRIES = 1;
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
	// Set once the retry above is spent and the page still won't hold playback -- the WebView's
	// size at that moment, so a further pause at the same size (or smaller) skips straight to
	// honoring it instead of re-attempting play() and producing the same blip again. Cleared once
	// the WebView is bigger than this (playing() below) or the user explicitly asks to play again
	// (start() below), either of which deserves a fresh attempt. 0 means not currently blocked.
	private int blockedWidth;
	private int blockedHeight;

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
				return queueAwareNextPlayable();
			}

			@NonNull
			@Override
			public FutureSupplier<PlayableItem> getPrevPlayable() {
				return queueAwarePrevPlayable();
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

		// The page is genuinely holding playback now, at a size at least as big as whatever we
		// previously gave up at -- worth a fresh attempt if it pauses again.
		if ((blockedWidth != 0) && (web.getWidth() > blockedWidth || web.getHeight() > blockedHeight)) {
			blockedWidth = 0;
			blockedHeight = 0;
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
		// Repeat One loops whatever video is currently playing, regardless of whether it's part of a
		// Favorites/Playlist queue (see YoutubeAddon#isRepeatOneEnabled()) -- handled here directly,
		// short-circuiting before current becomes end/cb.onEngineEnded() runs, so it works the exact
		// same way whether or not a queue item exists (unlike "repeat the whole playlist", which
		// necessarily needs one -- see queueAwareNextPlayable/PrevPlayable below).
		if (web.getAddon().isRepeatOneEnabled()) {
			web.setPosition(0);
			web.play();
			return;
		}

		current = end;
		qualityUrl = null;
		cb.onEngineEnded(this);
	}

	/** The page-side ad detector (see {@link YoutubeWebView}) just started muting/skipping an ad. */
	void adShowing() {
		YoutubeVideoView v = getFullScreenView();
		if (v != null) v.showTransitionOverlay(true);
	}

	/** The page-side ad detector cleared -- either the ad ended or it was never really one. */
	void adEnded() {
		YoutubeVideoView v = getFullScreenView();
		if (v != null) v.hideTransitionOverlay();
	}

	/**
	 * A next/prev switch was just requested (see {@link #prepare}) -- covers the switch with a
	 * plain fade (no spinner, unlike {@link #adShowing()}: this is a deliberate transition, not an
	 * indeterminate wait) until {@link #contentPlaying()} confirms the new video is actually up.
	 */
	private void transitioning() {
		YoutubeVideoView v = getFullScreenView();
		if (v != null) v.showTransitionOverlay(false);
	}

	/**
	 * The page's {@code <video>} element fired a real (non-ad) "playing" event -- see {@link
	 * YoutubeWebView}'s {@code JS_CONTENT_PLAYING}. Hides whichever of {@link #transitioning()}/
	 * {@link #adShowing()} is currently covering the screen; a no-op if neither is.
	 */
	void contentPlaying() {
		YoutubeVideoView v = getFullScreenView();
		if (v != null) v.hideTransitionOverlay();
	}

	@Nullable
	private YoutubeVideoView getFullScreenView() {
		FermataChromeClient chrome = web.getWebChromeClient();
		if (!(chrome instanceof YoutubeChromeClient yt)) return null;
		VideoView v = yt.getFullScreenView();
		return (v instanceof YoutubeVideoView yv) ? yv : null;
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

		// Already known to be too small at this size (or smaller) -- don't repeat the same failed
		// play() attempt and its audible blip, just honor the pause silently (the toast already
		// told the user why, below, the first time this happened).
		boolean stillBlocked = (blockedWidth != 0) &&
				(web.getWidth() <= blockedWidth) && (web.getHeight() <= blockedHeight);

		if (!stillBlocked && !ignorePause && (lastActivePlayTime != 0) &&
				(playRetries < MAX_PLAY_RETRIES) && (now - lastActivePlayTime < PLAY_RETRY_GRACE_MS)) {
			playRetries++;
			Log.i("YoutubeMediaEngine.paused(): retrying play(), attempt ", playRetries);
			web.play();
			return;
		}

		if (!stillBlocked && !ignorePause && (lastActivePlayTime != 0) &&
				(playRetries >= MAX_PLAY_RETRIES)) {
			// The retry just above didn't stick -- assume the current size is the reason and stop
			// asking the page to play at it until it grows (see playing() above) or the user
			// explicitly taps play again (see start() below).
			blockedWidth = web.getWidth();
			blockedHeight = web.getHeight();
			Toast.makeText(web.getContext(), R.string.youtube_window_too_small, Toast.LENGTH_LONG)
					.show();
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
			transitioning();
			web.next();
		} else if (source == prev) {
			transitioning();
			web.prev();
		} else if (source instanceof YoutubeVideoItem yt) {
			// Reached from MediaSessionCallback.skipTo()/engineEnded() when queueAwareNextPlayable()/
			// PrevPlayable() below resolved a real sibling from the app's own Favorites/Playlist --
			// navigate straight to it (same as the initial tap-to-play in YoutubeVideoItem#
			// loadInFragment()) instead of asking the page for its own next/prev, which has no idea
			// this item even exists. YouTube's own autoplay-on-load takes it from there and playing()
			// above reports back once the new video is actually up, same as any other navigation.
			transitioning();
			web.getAddon().setQueueItem(yt);
			web.loadUrl(YoutubeVideoItem.watchUrl(yt.getVideoId()));
		} else {
			cb.onEnginePrepared(this);
		}
	}

	@Override
	public void start() {
		lastActivePlayTime = System.currentTimeMillis();
		lastPausedTime = 0;
		playRetries = 0;
		blockedWidth = 0;
		blockedHeight = 0;
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

		// This is also the app's normal control panel "..." menu (it shows over fullscreen YouTube
		// playback too, see YoutubeFragment's video-view overlay elevation), which otherwise never
		// offers Repeat/Shuffle for YouTube: PlayableItem#isExternal() is true for every YouTube item,
		// and ControlPanelView's own repeat/shuffle menu entries are gated on that being false.
		//
		// Repeat One (see YoutubeAddon#isRepeatOneEnabled()) is a property of "whatever video is
		// playing right now" -- shown unconditionally, unlike Shuffle and "repeat the whole playlist"
		// below, which only mean something with a real Favorites/Playlist queue behind the current
		// video (see YoutubeAddon#getQueueItem()).
		YoutubeAddon addon = web.getAddon();
		YoutubeVideoItem q = addon.getQueueItem();
		boolean repeatFolder = (q != null) && q.getParent().getPrefs().getRepeatPref();
		if (addon.isRepeatOneEnabled() || repeatFolder) {
			b.addItem(me.aap.fermata.R.id.repeat,
					ResourcesCompat.getDrawable(r, me.aap.fermata.R.drawable.repeat_filled, ctx.getTheme()),
					r.getString(me.aap.fermata.R.string.repeat)).setSubmenu(this::repeatMenu);
		} else {
			b.addItem(me.aap.fermata.R.id.repeat_enable,
					ResourcesCompat.getDrawable(r, me.aap.fermata.R.drawable.repeat, ctx.getTheme()),
					r.getString(me.aap.fermata.R.string.repeat)).setSubmenu(this::repeatMenu);
		}

		if (q != null) {
			BrowsableItemPrefs p = q.getParent().getPrefs();
			if (p.getShufflePref()) {
				b.addItem(me.aap.fermata.R.id.shuffle_disable,
						ResourcesCompat.getDrawable(r, me.aap.fermata.R.drawable.shuffle_filled, ctx.getTheme()),
						r.getString(me.aap.fermata.R.string.shuffle_disable)).setHandler(i -> {
					p.setShufflePref(false);
					return true;
				});
			} else {
				b.addItem(me.aap.fermata.R.id.shuffle_enable,
						ResourcesCompat.getDrawable(r, me.aap.fermata.R.drawable.shuffle, ctx.getTheme()),
						r.getString(me.aap.fermata.R.string.shuffle)).setHandler(i -> {
					p.setShufflePref(true);
					return true;
				});
			}
		}
	}

	/**
	 * Repeat submenu -- mirrors ControlPanelView's own repeat menu, but "Current track" toggles
	 * {@link YoutubeAddon#setRepeatOneEnabled} (works with or without a queue item) while "Current
	 * folder" reads/writes the queue item's parent prefs ({@link YoutubeAddon#getQueueItem()}) rather
	 * than {@link #getSource()}'s (a transient, internally parented placeholder -- see {@link
	 * YoutubeItem}), which is what {@code ControlPanelView.MenuHandler} would otherwise use, and is
	 * omitted entirely when there's no queue item to repeat around. Each item gets its own {@link
	 * OverlayMenuItem#setHandler}, same as {@link #showEqualizer()} below, so this stays independent
	 * of whatever selection handler the surrounding (shared, control-panel-owned) menu already has.
	 */
	private void repeatMenu(OverlayMenu.Builder b) {
		YoutubeAddon addon = web.getAddon();
		b.addItem(me.aap.fermata.R.id.repeat_track, me.aap.fermata.R.string.current_track)
				.setHandler(i -> {
					addon.setRepeatOneEnabled(true);
					return true;
				});

		YoutubeVideoItem q = addon.getQueueItem();
		if (q != null) {
			b.addItem(me.aap.fermata.R.id.repeat_folder, me.aap.fermata.R.string.current_folder)
					.setHandler(i -> {
						addon.setRepeatOneEnabled(false);
						q.getParent().getPrefs().setRepeatPref(true);
						return true;
					});
		}

		b.addItem(me.aap.fermata.R.id.repeat_disable_all, me.aap.fermata.R.string.repeat_disable)
				.setHandler(i -> {
					addon.setRepeatOneEnabled(false);
					if (q != null) q.getParent().getPrefs().setRepeatPref(false);
					return true;
				});
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
				// GenericFragment's root never insets itself against tool_bar/control_panel/nav_bar,
				// so without this the first and last equalizer rows sit underneath them. Same call
				// MediaItemListView and the Settings list make from their own constructors; this
				// content is built by the caller instead, so it has to be requested here.
				a.insetScrollableContent(v);
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

		// Bypasses MediaEngineManager's generic, preference-driven engine selection (which can be
		// overridden by unrelated prefs such as SubGenAddon.ENABLED, forcing ExoPlayer regardless of
		// getVideoEnginePref()) -- these items (current/next/prev/end) must always stay on the live
		// YoutubeMediaEngine. next/prev's resource is a fake URL (http://youtube.com/next) that exists
		// only to carry a JS button-click signal; handing it to a real player engine instead makes it
		// genuinely try to open that URL and fail with a "Source error" toast. Returning null when
		// there's no live YoutubeMediaEngine yet preserves default engine selection for a fresh start.
		@Nullable
		@Override
		public MediaEngine getMediaEngine(@Nullable MediaEngine current, MediaEngine.Listener listener) {
			return (current instanceof YoutubeMediaEngine) ? current : null;
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
			return queueAwarePrevPlayable();
		}

		@NonNull
		@Override
		public FutureSupplier<PlayableItem> getNextPlayable() {
			return queueAwareNextPlayable();
		}
	}

	/**
	 * When the currently loaded video was selected from a Favorites/Playlist list (see {@link
	 * YoutubeAddon#getQueueItem()}), resolves the previous item in that list -- honoring its
	 * shuffle/repeat prefs via {@link PlayableItem#getPrevPlayable()}'s normal sibling-based logic --
	 * instead of {@link #prev}, whose {@link #prepare} handling just asks the page for its own
	 * page-internal previous video. Falls back to {@link #prev} when there's no queue context (plain
	 * YouTube browsing) or the list has no previous item.
	 */
	@NonNull
	private FutureSupplier<PlayableItem> queueAwarePrevPlayable() {
		YoutubeVideoItem q = web.getAddon().getQueueItem();
		if (q == null) return completed(prev);
		// A Favorites/Playlist can mix YouTube videos with local/other media -- prepare() below only
		// knows how to navigate this engine to another YoutubeVideoItem (a plain loadUrl()), not swap
		// it out for a completely different engine, so hitting a non-YouTube neighbor (or the start of
		// the list) falls back to prev, same as having no queue context at all.
		return q.getPrevPlayable().map(pi -> (pi instanceof YoutubeVideoItem) ? pi : prev);
	}

	/** Next-direction counterpart of {@link #queueAwarePrevPlayable()} -- see there for details. */
	@NonNull
	private FutureSupplier<PlayableItem> queueAwareNextPlayable() {
		YoutubeVideoItem q = web.getAddon().getQueueItem();
		if (q == null) return completed(next);
		return q.getNextPlayable().map(pi -> (pi instanceof YoutubeVideoItem) ? pi : next);
	}
}
