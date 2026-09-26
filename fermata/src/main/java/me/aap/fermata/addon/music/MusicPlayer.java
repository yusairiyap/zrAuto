package me.aap.fermata.addon.music;

import static me.aap.utils.async.Completed.completed;

import android.content.Context;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.BodyLayout;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.ui.UiUtils;

/**
 * Entry points into the Music tab: "Play as music" / "Add into music queue" from the library's
 * context menus, "Play as music" for whatever is playing right now (the FAB action and the video
 * control panel's Audio menu), and the switch from music back to video.
 * <p>
 * YouTube tracks play in the YouTube tab's own player, just with its video held at the lowest
 * quality while playing as music ({@link #setYoutubeAudioMode}); switching between video and
 * music there is only a quality change and a tab change, so the sound never stops. A local file
 * keeps playing on the very same engine, which just stops decoding video (see
 * {@link MediaSessionCallback#switchItem}).
 */
public final class MusicPlayer {
	private static final String TAG = "MUSIC";
	@Nullable
	private static YoutubeHooks youtube;
	private static boolean youtubeAudioMode;
	@Nullable
	private static String pendingVideoId;
	private static long pendingVideoPos;
	// See watch(): the YouTube track about to start is to be watched, not listened to.
	private static boolean watchRequested;
	private static WeakReference<MainActivityDelegate> activity = new WeakReference<>(null);

	private MusicPlayer() {
	}

	/**
	 * What the Music tab needs from the YouTube addon (which the {@code fermata} module can't
	 * reference directly), registered by the addon itself.
	 */
	public interface YoutubeHooks {
		/**
		 * Starts {@code t}'s video in the YouTube tab's player (from {@link #takeVideoStartPosition}),
		 * with {@code t} as that player's queue item. False if it can't.
		 */
		boolean play(MainActivityDelegate a, MusicTrackItem t);

		/**
		 * Shows the YouTube tab with what it's playing as fullscreen video (showing the tab ends
		 * music mode, so the video gets its usual quality back).
		 */
		void showVideo(MainActivityDelegate a);

		/** Shows the YouTube player's effects screen (its in-page equalizer); false if it can't. */
		boolean showEffects(MainActivityDelegate a);

		/** Makes {@code item} the YouTube player's queue item without touching what's playing. */
		void setQueueItem(PlayableItem item);

		/**
		 * Applies {@link #isYoutubeAudioMode()} to {@code eng}'s video quality, if {@code eng} is the
		 * YouTube player: the lowest while playing as music, the usual one otherwise.
		 */
		void applyQuality(@Nullable MediaEngine eng);
	}

	public static void setYoutubeHooks(@Nullable YoutubeHooks hooks) {
		youtube = hooks;
	}

	/** Whether YouTube is playing as music: its video held at the lowest quality. */
	public static boolean isYoutubeAudioMode() {
		return youtubeAudioMode;
	}

	public static void setYoutubeAudioMode(boolean on) {
		if (youtubeAudioMode == on) return;
		youtubeAudioMode = on;
		DiagnosticLog.log(TAG, "YouTube " + (on ? "music mode (lowest video quality)" : "video mode"));
		MainActivityDelegate a = activity.get();
		YoutubeHooks h = youtube;
		if ((h != null) && (a != null)) h.applyQuality(a.getMediaSessionCallback().getEngine());
	}

	/**
	 * Used by the YouTube page loader: where a video started from the Music tab should start from,
	 * consumed by the first call for that video.
	 */
	public static long takeVideoStartPosition(String videoId) {
		if (!videoId.equals(pendingVideoId)) return 0;
		pendingVideoId = null;
		return pendingVideoPos;
	}

	static void activityCreated(MainActivityDelegate a) {
		activity = new WeakReference<>(a);
	}

	static void activityDestroyed(MainActivityDelegate a) {
		if (activity.get() == a) activity = new WeakReference<>(null);
	}

	/**
	 * The engine for a YouTube queue track: the YouTube player itself when it's already the one
	 * playing -- its prepare() moves the page to the track's video, crossfading like any other skip
	 * in its queue -- otherwise one that starts the video in it (see {@link YoutubeStartEngine}).
	 */
	static MediaEngine getYoutubeEngine(MusicTrackItem t, @Nullable MediaEngine current,
																			MediaEngine.Listener listener) {
		setYoutubeAudioMode(true);
		if ((current != null) && (current.getId() == MediaPrefs.MEDIA_ENG_YT) &&
				!t.hasStartPosition()) {
			DiagnosticLog.log(TAG, "YouTube track on the playing YouTube player", "id=" + t.getVideoId());
			MainActivityDelegate a = activity.get();
			if (watchRequested && (a != null)) a.post(() -> a.showFragment(R.id.youtube_fragment));
			watchRequested = false;
			return current;
		}
		return new YoutubeStartEngine(t, listener);
	}

	/** Called by {@link YoutubeStartEngine#showOwnAudioEffects}. */
	static boolean showYoutubeEffects() {
		YoutubeHooks h = youtube;
		MainActivityDelegate a = activity.get();
		return (h != null) && (a != null) && h.showEffects(a);
	}

	/** Called by {@link YoutubeStartEngine#prepare}. */
	static boolean startYoutube(MusicTrackItem t, long pos) {
		YoutubeHooks h = youtube;
		MainActivityDelegate a = activity.get();
		if ((h == null) || (a == null)) return false;
		pendingVideoId = t.getVideoId();
		pendingVideoPos = pos;
		DiagnosticLog.log(TAG, "YouTube track: starting in the YouTube player", "id=" + t.getVideoId(),
				"pos=" + (pos / 1000) + 's');
		boolean watch = watchRequested;
		watchRequested = false;
		if (!h.play(a, t)) return false;
		// Showing the YouTube tab ends music mode (see YoutubeFragment#switchingFrom).
		if (watch) a.showFragment(R.id.youtube_fragment);
		return true;
	}

	public static boolean isEnabled() {
		return MusicAddon.get() != null;
	}

	@Nullable
	public static MusicQueue getQueue(MainActivityDelegate a) {
		MusicAddon addon = MusicAddon.get();
		return (addon == null) ? null : addon.getQueue(a.getLib());
	}

	/**
	 * The queue track playing right now, if any: the session's own item, or -- for YouTube, whose
	 * session item is its player's "current video" -- that player's queue item.
	 */
	@Nullable
	public static MusicTrackItem getCurrentTrack(MediaSessionCallback cb) {
		PlayableItem cur = cb.getCurrentItem();
		if (cur instanceof MusicTrackItem t) return t;
		MediaEngine eng = cb.getEngine();
		if ((eng == null) || (eng.getId() != MediaPrefs.MEDIA_ENG_YT)) return null;
		PlayableItem q = eng.getQueueItem();
		return (q instanceof MusicTrackItem t) ? t : null;
	}

	/** Shows the Music tab, leaving any video mode first. */
	public static void open(MainActivityDelegate a) {
		a.exitVideoMode();
		a.showFragment(R.id.music_addon);
		BodyLayout b = a.getBody();
		if ((b != null) && !b.isFrameMode()) b.setMode(BodyLayout.Mode.FRAME);
	}

	/**
	 * "Play as music" for a library item: a playable item plays within its own list (a
	 * Favorites/Playlist entry starts the whole list from it, like tapping a song in an album);
	 * a browsable one (a playlist card) plays all of its tracks.
	 */
	public static void play(MainActivityDelegate a, Item item) {
		if (getQueue(a) == null) return;

		if (item instanceof PlayableItem pi) {
			siblings(pi).main().onSuccess(list -> {
				int idx = indexOfSame(list, pi);
				if (idx == -1) {
					list = Collections.singletonList(pi);
					idx = 0;
				}
				play(a, list, idx);
			});
		} else if (item instanceof BrowsableItem bi) {
			bi.getPlayableChildren(true).main().onSuccess(list -> {
				if (list.isEmpty()) UiUtils.showToast(a.getContext(), R.string.music_nothing_to_play);
				else play(a, list, 0);
			});
		}
	}

	/** Replaces the queue with {@code items} and plays from {@code startIdx}. */
	public static void play(MainActivityDelegate a, List<? extends PlayableItem> items,
													int startIdx) {
		MusicQueue q = getQueue(a);
		if ((q == null) || items.isEmpty()) return;
		List<MusicTrackItem> tracks = q.replace(items);
		MusicTrackItem t = tracks.get(Math.max(0, Math.min(startIdx, tracks.size() - 1)));
		open(a);
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		PlayableItem cur = (eng == null) ? null : eng.getSource();
		if ((cur != null) && isSameMedia(eng, cur, t)) continueAsMusic(a, eng, t);
		else playTrack(a, t, 0);
	}

	/** "Add into music queue" for a library item (all of a browsable item's tracks). */
	public static void addToQueue(MainActivityDelegate a, Item item) {
		MusicQueue q = getQueue(a);
		if (q == null) return;
		FutureSupplier<List<PlayableItem>> items;

		if (item instanceof PlayableItem pi) {
			items = completed(Collections.singletonList(pi));
		} else if (item instanceof BrowsableItem bi) {
			items = bi.getPlayableChildren(true);
		} else {
			return;
		}

		items.main().onSuccess(list -> {
			Context ctx = a.getContext();
			if (list.isEmpty()) {
				UiUtils.showToast(ctx, R.string.music_nothing_to_play);
				return;
			}
			q.add(list);
			UiUtils.showToast(ctx, ctx.getResources().getQuantityString(R.plurals.music_added_to_queue,
					list.size(), list.size()));
		});
	}

	/** Plays a queue track -- from the Music tab itself (a queue row, or play with nothing on). */
	public static void playTrack(MainActivityDelegate a, MusicTrackItem t, long pos) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		DiagnosticLog.log(TAG, "play", "track=" + t, "id=" + t.getSourceId(),
				"pos=" + (pos / 1000) + 's');
		// The YouTube player's close() is deliberately inert: a local track taking over from it has
		// to silence its page explicitly.
		if ((t.getVideoId() == null) && (eng != null) && (eng.getId() == MediaPrefs.MEDIA_ENG_YT)) {
			eng.pause();
		}
		t.setStartPosition(pos);
		cb.playItem(t, pos);
	}

	/**
	 * "Play as music" for whatever is playing right now -- the FAB action and the video control
	 * panel's Audio menu. The queue becomes a copy of the playing item's own list (Favorites, a
	 * playlist, its folder), in the same order and with its Shuffle/Repeat settings, so playback
	 * carries on through that list; being a copy, the queue can then be edited freely without
	 * touching the list itself. Only when the queue was last playing this very item (switched to
	 * video and back) is it kept as it is, so a queue the user put together isn't thrown away.
	 */
	public static void playCurrentAsMusic(MainActivityDelegate a) {
		MusicQueue q = getQueue(a);
		if (q == null) return;
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		PlayableItem cur = (eng == null) ? null : eng.getSource();

		if ((cur == null) || (getCurrentTrack(cb) != null)) {
			if (cur != null) setYoutubeAudioMode(eng.getId() == MediaPrefs.MEDIA_ENG_YT);
			open(a);
			return;
		}

		PlayableItem qi = eng.getQueueItem();
		if (qi == null) qi = eng.getFavoritableItem();
		if (qi == null) {
			UiUtils.showToast(a.getContext(), R.string.music_cant_play_current);
			return;
		}

		PlayableItem item = qi;
		MusicTrackItem existing = q.getSavedCurrent();

		if ((existing != null) && existing.getSourceId().equals(MusicQueue.sourceIdOf(item))) {
			open(a);
			continueAsMusic(a, eng, existing);
			return;
		}

		boolean browsing = item.getParent().isExternal();
		FutureSupplier<List<PlayableItem>> list =
				browsing ? completed(Collections.singletonList(item)) : siblings(item);
		list.main().onSuccess(l -> {
			int idx = indexOfSame(l, item);
			if (idx == -1) {
				l = Collections.singletonList(item);
				idx = 0;
			}
			MusicTrackItem t = q.replace(l).get(idx);
			if (!browsing) q.copyModes(item.getParent().getPrefs());
			open(a);
			continueAsMusic(a, eng, t);
		});
	}

	/**
	 * Switches the music track that's playing now back to its video: YouTube's page is already
	 * playing it, so it's just a matter of showing it at its usual quality again; a local file
	 * gets its picture back on the same engine.
	 */
	public static void switchToVideo(MainActivityDelegate a) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		MusicTrackItem t = getCurrentTrack(cb);
		if (eng == null) return;
		boolean yt = (eng.getId() == MediaPrefs.MEDIA_ENG_YT);
		// YouTube in music mode may momentarily not report its queue track: still its video to show.
		if ((t == null) && !(yt && youtubeAudioMode)) return;

		if ((t == null) || (t.getVideoId() != null)) {
			DiagnosticLog.log(TAG, "switch to video", "id=" + ((t != null) ? t.getVideoId() : "?"));
			setYoutubeAudioMode(false);
			YoutubeHooks h = youtube;
			if (h != null) h.showVideo(a);
			return;
		}

		PlayableItem src = t.getSource();
		if ((src == null) || !src.isVideo()) return;

		eng.getPosition().main().onSuccess(pos -> {
			a.goToItem(src);
			// Same file on the same engine: it just gets its video surface back (the library tab
			// just shown supports video mode, and switches into it as soon as the item becomes a
			// video again). Otherwise re-prepare it at the current position.
			if (!cb.switchItem(src)) {
				src.getPrefs().setPositionPref(pos);
				cb.playItem(src, pos);
			}
		});
	}

	/**
	 * "Video" for the queue track shown while nothing is playing (where the queue left off): starts
	 * it straight away as video, from where the queue left off.
	 */
	public static void watch(MainActivityDelegate a, MusicTrackItem t) {
		MusicQueue q = getQueue(a);
		long pos = ((q != null) && t.equals(q.getSavedCurrent())) ? q.getSavedPosition() : 0;
		DiagnosticLog.log(TAG, "watch", "track=" + t, "pos=" + (pos / 1000) + 's');

		if (t.getVideoId() != null) {
			watchRequested = true;
			playTrack(a, t, pos);
			return;
		}

		t.prepareSource().main().onSuccess(v -> {
			PlayableItem src = t.getSource();
			if (src == null) return;
			a.goToItem(src);
			src.getPrefs().setPositionPref(pos);
			a.getMediaSessionCallback().playItem(src, pos);
		});
	}

	/**
	 * Carries on with the media {@code eng} is playing, as {@code t}: for YouTube, the page keeps
	 * playing -- only its quality drops and its queue becomes the music queue; for a local file, the
	 * same engine keeps playing it without its video (or re-prepares it at the same position).
	 */
	private static void continueAsMusic(MainActivityDelegate a, MediaEngine eng, MusicTrackItem t) {
		MediaSessionCallback cb = a.getMediaSessionCallback();

		if (eng.getId() == MediaPrefs.MEDIA_ENG_YT) {
			YoutubeHooks h = youtube;
			if (h != null) h.setQueueItem(t);
			setYoutubeAudioMode(true);
			DiagnosticLog.log(TAG, "YouTube video continues as music", "id=" + t.getVideoId());
			return;
		}

		eng.getPosition().main().onSuccess(pos -> {
			if (cb.getEngine() != eng) return;
			if (!cb.switchItem(t)) playTrack(a, t, pos);
		});
	}

	private static boolean isSameMedia(MediaEngine eng, PlayableItem cur, MusicTrackItem t) {
		if (cur instanceof MusicTrackItem ct) return t.getSourceId().equals(ct.getSourceId());

		if (t.getVideoId() != null) {
			if (eng.getId() != MediaPrefs.MEDIA_ENG_YT) return false;
			PlayableItem fav = eng.getFavoritableItem();
			return (fav != null) && t.getSourceId().equals(MusicQueue.sourceIdOf(fav));
		}

		PlayableItem src = t.getSource();
		return (src != null) && cur.getLocation().equals(src.getLocation());
	}

	private static FutureSupplier<List<PlayableItem>> siblings(PlayableItem i) {
		BrowsableItem p = i.getParent();
		return p.getPlayableChildren(false).map(l -> {
			if (l.isEmpty()) return Collections.singletonList(i);
			return new ArrayList<>(l);
		});
	}

	private static int indexOfSame(List<? extends PlayableItem> list, PlayableItem i) {
		String id = i.getId();
		for (int n = 0; n < list.size(); n++) {
			if (id.equals(list.get(n).getId())) return n;
		}
		return -1;
	}
}
