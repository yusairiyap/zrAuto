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
import me.aap.fermata.ui.view.VideoView;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.fermata.ytdl.YtOffline;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.ui.UiUtils;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.utils.ui.fragment.ActivityFragment;

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
	// A downloaded video's picture is being watched (fullscreen), the queue carrying on through it:
	// set by "Video" and by playing downloads as videos, dropped by anything that starts a track as
	// music. Not the YouTube player's music mode ({@link #youtubeAudioMode}), which it leaves alone.
	private static volatile boolean watchingLocal;
	// How startTrack() asked for its track to be shown, for that track only: applied to the flags
	// above when that track's engine is chosen, dropped when any other track's is, so it never
	// outlives a start that did not happen (e.g. superseded by a library item).
	@Nullable
	private static volatile StartRequest startRequest;
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

		/**
		 * Shows the YouTube tab's search / Up next panel over whatever is playing, without
		 * interrupting it -- {@code upNextOnly} just opens the panel, otherwise the search field gets
		 * the cursor too.
		 */
		void openSearch(MainActivityDelegate a, boolean upNextOnly);

		/**
		 * Adds the YouTube videos among {@code items} to the end of the YouTube player's Up next, if
		 * a YouTube video is what's playing. The number added, or -1 if no YouTube video is playing.
		 */
		int addToVideoQueue(MainActivityDelegate a, List<? extends PlayableItem> items);
	}

	public static void setYoutubeHooks(@Nullable YoutubeHooks hooks) {
		youtube = hooks;
	}

	/** Whether the YouTube addon is installed, i.e. its search / Up next can be opened. */
	public static boolean hasYoutube() {
		return youtube != null;
	}

	/** See {@link YoutubeHooks#openSearch}; a no-op without the YouTube addon. */
	public static void openYoutubeSearch(MainActivityDelegate a, boolean upNextOnly) {
		YoutubeHooks h = youtube;
		if (h != null) h.openSearch(a, upNextOnly);
	}

	/** Shows the YouTube tab with its video fullscreen (a no-op without the YouTube addon). */
	public static void showYoutubeVideo(MainActivityDelegate from) {
		MainActivityDelegate a = from.getPlaybackDelegate(); // The car's while Android Auto is on.
		YoutubeHooks h = youtube;
		if (h != null) h.showVideo(a);
	}

	/** Whether YouTube is playing as music: its video held at the lowest quality. */
	public static boolean isYoutubeAudioMode() {
		return youtubeAudioMode;
	}

	/**
	 * Whether {@code t}'s downloaded picture is to be watched: as its pending start asked, if it
	 * has one (see {@link #startRequest}), otherwise {@link #watchingLocal}.
	 */
	static boolean isWatchingLocal(MusicTrackItem t) {
		StartRequest r = startRequest;
		return ((r != null) && r.track.equals(t)) ? r.watch : watchingLocal;
	}

	private record StartRequest(MusicTrackItem track, boolean watch) {}

	/**
	 * {@code t}'s engine is being chosen: the start startTrack() asked for it takes effect now, and a
	 * request left by a start for another track is dropped.
	 */
	private static void applyStartRequest(MusicTrackItem t) {
		StartRequest r = startRequest;
		if (r == null) return;
		startRequest = null;
		if (!r.track.equals(t)) {
			DiagnosticLog.log(TAG, "start request dropped", "for=" + r.track, "starting=" + t);
			return;
		}
		watchRequested = r.watch;
		watchingLocal = r.watch;
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

	/**
	 * Whether the YouTube queue track about to play is to be watched rather than listened to: "Video"
	 * was asked for, or YouTube is already out of music mode with its tab showing the video.
	 */
	private static boolean isWatchingVideo(@Nullable MediaEngine current) {
		if (watchRequested) return true;
		if (isShowingDownloadedVideo(current)) return true;
		if (youtubeAudioMode) return false;
		MainActivityDelegate a = activity.get();
		if (a == null) return false;
		ActivityFragment f = a.getActiveFragment();
		return (f != null) && (f.getFragmentId() == R.id.youtube_fragment);
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
		applyStartRequest(t);
		// Music unless the user switched to watching it (the Music tab's "Video"): then Next/Prev
		// through the queue keep showing video, at its usual quality, instead of dropping back to
		// the lowest one.
		// A downloaded video shown fullscreen counts as watching: the track after it, streamed, is a
		// video too (at its usual quality), shown in the YouTube tab.
		if (!isWatchingVideo(current)) {
			setYoutubeAudioMode(true);
		} else if (isShowingDownloadedVideo(current)) {
			// Out of the file's player and into YouTube's, at its usual quality.
			watchRequested = true;
			setYoutubeAudioMode(false);
		}
		watchingLocal = false;
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

	/** Whether {@code eng} is playing a downloaded video's file with its picture on screen. */
	private static boolean isShowingDownloadedVideo(@Nullable MediaEngine eng) {
		return (eng != null) && (eng.getId() != MediaPrefs.MEDIA_ENG_YT) &&
				(eng.getSource() instanceof MusicTrackItem t) && t.isVideo();
	}

	/**
	 * A downloaded track is about to play from its file on a non-YouTube engine: music, or -- if the
	 * player is in video mode (the YouTube tab watched, or a downloaded video shown) and the file
	 * has a picture -- video, fullscreen like the YouTube player's.
	 */
	static void startingDownloadedTrack(MusicTrackItem t, @Nullable MediaEngine current,
																			boolean hasPicture) {
		applyStartRequest(t);
		boolean requested = watchRequested;
		boolean watching = isWatchingVideo(current);
		MainActivityDelegate act = activity.get();
		ActivityFragment shown = (act == null) ? null : act.getActiveFragment();
		// Started from the Music tab (a track tapped there, or from a list while music mode is on): music,
		// whatever was watched before -- the fullscreen picture would come up over the tab itself.
		if (watching && !requested && (shown != null) && (shown.getFragmentId() == R.id.music_addon)) {
			watching = false;
		}
		watchRequested = false;
		watchingLocal = watching && hasPicture;
		if (!watching) {
			setYoutubeAudioMode(true);
			// Started as music: the fullscreen of a video before it is over.
			MainActivityDelegate a = activity.get();
			BodyLayout b = (a == null) ? null : a.getBody();
			if ((b != null) && b.isVideoMode()) b.setMode(BodyLayout.Mode.FRAME);
			return;
		}
		MainActivityDelegate a = activity.get();
		boolean showsVideo = (shown instanceof MainActivityFragment f) && f.isVideoModeSupported();
		BodyLayout b = (a == null) ? null : a.getBody();
		if (hasPicture && (b != null) && !b.isVideoMode()) {
			// From a tab that cannot show video (YouTube's, left a moment ago): through the Downloads tab,
			// as YtOffline#tryPlayLocal does, or the file would play with no picture on a page behind.
			if (!showsVideo) {
				a.fadeToBlackForLocalVideo();
				a.showFragment(R.id.downloads_addon);
			}
			b.setMode(BodyLayout.Mode.VIDEO);
		}
	}

	/**
	 * "Audio effects" while a downloaded YouTube video plays from its file: the YouTube equalizer's
	 * own screen, not Android's -- the same settings, which the native player applies to the file's
	 * sound (see FxDsp). False for anything else, and the usual effects screen opens.
	 */
	public static boolean showDownloadedEffects(MainActivityDelegate a) {
		YoutubeHooks h = youtube;
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		if ((h == null) || (eng == null) || (eng.getId() == MediaPrefs.MEDIA_ENG_YT)) return false;
		return YtOffline.isDownloadedYoutube(eng.getSource()) && h.showEffects(a);
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
		// Showing the YouTube tab ends music mode (see YoutubeFragment#switchingFrom). Through black
		// from a video on screen, so the page loading isn't seen.
		if (watch) {
			if (a.isVideoMode()) a.fadeToBlackForYoutube();
			a.showFragment(R.id.youtube_fragment);
		}
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
		play(a, item, true);
	}

	/**
	 * See {@link #play(MainActivityDelegate, Item)}; {@code show}: whether to switch to the Music
	 * tab, or stay where the item was tapped (music mode is already on, the control panel shows
	 * what's playing).
	 */
	public static void play(MainActivityDelegate a, Item item, boolean show) {
		if (getQueue(a) == null) return;

		if (item instanceof PlayableItem pi) {
			siblings(pi).main().onSuccess(list -> {
				int idx = indexOfSame(list, pi);
				if (idx == -1) {
					list = Collections.singletonList(pi);
					idx = 0;
				}
				play(a, list, idx, show);
			});
		} else if (item instanceof BrowsableItem bi) {
			bi.getPlayableChildren(true).main().onSuccess(list -> {
				if (list.isEmpty()) UiUtils.showToast(a.getContext(), R.string.music_nothing_to_play);
				else play(a, list, 0, show);
			});
		}
	}

	/**
	 * Plays {@code item} (the one that was playing when the app last ran, say) as music, from
	 * {@code pos}, within its own list -- as "Play as music" does -- when the Music tab's play is
	 * pressed with nothing playing.
	 */
	public static void playAsMusic(MainActivityDelegate from, PlayableItem item, long pos) {
		MainActivityDelegate a = from.getPlaybackDelegate();
		MusicQueue q = getQueue(a);
		if (q == null) return;
		siblings(item).main().onSuccess(list -> {
			int idx = indexOfSame(list, item);
			List<? extends PlayableItem> items = list;
			if (idx == -1) {
				items = Collections.singletonList(item);
				idx = 0;
			}
			MusicTrackItem t = q.replace(items, idx).get(idx);
			DiagnosticLog.log(TAG, "play last item as music", "item=" + item, "pos=" + (pos / 1000) + 's');
			playTrack(a, t, pos);
		});
	}

	/**
	 * "Video" for what plays on in the background (a local or downloaded video, the Music tab being
	 * showing): the video, fullscreen, from the tab it belongs to. The YouTube player's own is shown
	 * in its tab.
	 */
	public static void showCurrentVideo(MainActivityDelegate from) {
		MainActivityDelegate a = from.getPlaybackDelegate();
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		PlayableItem src = (eng == null) ? null : eng.getSource();
		if ((src == null) || !src.isVideo()) return;

		if (eng.getId() == MediaPrefs.MEDIA_ENG_YT) {
			showYoutubeVideo(a);
			return;
		}
		// A queue track belongs to the Music tab, which has no picture: its video is shown from the
		// list it came from, or Downloads.
		if (src instanceof MusicTrackItem) {
			switchToVideo(a);
			return;
		}

		a.goToItem(src);
		BodyLayout b = a.getBody();
		if ((b != null) && !b.isVideoMode()) b.setMode(BodyLayout.Mode.VIDEO);
		a.post(a::updateExtraFabsVisibility);
	}

	/**
	 * Plays a list of downloaded videos as the queue from {@code startIdx}, watched: fullscreen
	 * wherever the file has a picture, the next ones following on like a Favorites list does. The
	 * tab it is started from stays as it is, and is where leaving fullscreen goes back to.
	 */
	public static void playDownloadedVideos(MainActivityDelegate from,
																					List<? extends PlayableItem> items, int startIdx) {
		MainActivityDelegate a = from.getPlaybackDelegate();
		MusicQueue q = getQueue(a);
		if ((q == null) || items.isEmpty()) return;
		int first = Math.max(0, Math.min(startIdx, items.size() - 1));
		MusicTrackItem t = q.replace(items, first).get(first);
		BodyLayout b = a.getBody();

		if ((b == null) || !t.hasVideo()) {
			setYoutubeAudioMode(true);
			playTrack(a, t, 0);
			return;
		}

		DiagnosticLog.log(TAG, "play downloaded videos", "first=" + t, "count=" + items.size());
		// Fullscreen first, and once: the engine is given the picture's surface only if it is there
		// when the engine is created (see BodyLayout#playLocalVideo).
		if (!b.isVideoMode()) {
			a.fadeToBlackForLocalVideo();
			b.setMode(BodyLayout.Mode.VIDEO);
		}
		VideoView vv = b.getVideoView();
		if (!vv.isSurfaceCreated() && !a.getMediaSessionCallback().hasCustomEngineProvider()) {
			vv.onSurfaceCreated(() -> startTrack(a, t, 0, true));
		} else {
			startTrack(a, t, 0, true);
		}
	}

	/** Replaces the queue with {@code items} and plays from {@code startIdx}. */
	public static void play(MainActivityDelegate a, List<? extends PlayableItem> items,
													int startIdx) {
		play(a, items, startIdx, true);
	}

	private static void play(MainActivityDelegate a, List<? extends PlayableItem> items,
													 int startIdx, boolean show) {
		MusicQueue q = getQueue(a);
		if ((q == null) || items.isEmpty()) return;
		int first = Math.max(0, Math.min(startIdx, items.size() - 1));
		MusicTrackItem t = q.replace(items, first).get(first);
		if (show) open(a);
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		PlayableItem cur = (eng == null) ? null : eng.getSource();
		if ((cur != null) && isSameMedia(eng, cur, t)) continueAsMusic(a, eng, t);
		else playTrack(a, t, 0);
	}

	/**
	 * Moves what {@code eng} had queued to play next (YouTube's Up next, see
	 * {@link MediaEngine#takeUpNext()}) into {@code q}, in order, right after {@code after}.
	 */
	private static void moveUpNextIntoQueue(MusicQueue q, MediaEngine eng, MusicTrackItem after) {
		List<PlayableItem> up = eng.takeUpNext();
		if (!up.isEmpty()) q.addAfter(after, up);
	}

	/**
	 * Puts {@code item} into the music queue -- right after the track playing now ({@code next}) or
	 * at the end. False when no queue track is playing (nothing for it to follow) -- unless the
	 * Music tab is what's playing ({@link #isMusicModeActive}), when YouTube's player may just not
	 * be reporting its queue track at this very moment: then it goes after where the queue is (its
	 * saved current track), so it still shows up in the Music tab's queue rather than nowhere.
	 */
	public static boolean queueAfterCurrent(MainActivityDelegate a, PlayableItem item, boolean next) {
		MusicQueue q = getQueue(a);
		if (q == null) return false;
		MusicTrackItem cur = getCurrentTrack(a.getMediaSessionCallback());
		if (cur == null) {
			if (!isMusicModeActive(a)) return false;
			cur = q.getSavedCurrent();
		}
		List<PlayableItem> l = Collections.singletonList(item);
		if (next) q.addAfter(cur, l);
		else q.add(l);
		return true;
	}

	/**
	 * Whether the Music tab is what's playing (or was, paused): a queue track is the session's item,
	 * or YouTube is playing as music. Tapping a Favorites/Playlist entry then plays it as music too.
	 */
	public static boolean isMusicModeActive(MainActivityDelegate a) {
		if (!isEnabled()) return false;
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		if (eng == null) return false;
		// YouTube: its queue item stays the music track after "Video" -- only the quality says
		// whether it's being listened to or watched.
		if (eng.getId() == MediaPrefs.MEDIA_ENG_YT) return youtubeAudioMode;
		// A local file: switched to video, the session item is the file itself again.
		return cb.getCurrentItem() instanceof MusicTrackItem;
	}

	/**
	 * "Play next" for a track already in the queue: moves it right behind the one playing. False
	 * when no queue track is playing.
	 */
	public static boolean playNext(MainActivityDelegate a, MusicTrackItem t) {
		MusicQueue q = getQueue(a);
		MusicTrackItem cur = getCurrentTrack(a.getMediaSessionCallback());
		return (q != null) && (cur != null) && (t.getParent() == q) && q.moveAfter(cur, t);
	}

	/**
	 * The next queue track (in play order, after the one playing or where the queue left off) that
	 * doesn't need the internet: a file on the phone, not a YouTube video. Null if there's none.
	 */
	@Nullable
	private static MusicTrackItem nextOfflineTrack(MainActivityDelegate a) {
		MusicQueue q = getQueue(a);
		if (q == null) return null;
		List<MusicTrackItem> order = q.getPlayOrder();
		if (order.isEmpty()) return null;
		MusicTrackItem cur = getCurrentTrack(a.getMediaSessionCallback());
		if (cur == null) cur = q.getSavedCurrent();
		int start = (cur == null) ? -1 : order.indexOf(cur);
		for (int i = 1, n = order.size(); i <= n; i++) {
			MusicTrackItem t = order.get(Math.floorMod(start + i, n));
			if (((t.getVideoId() == null) || YtDownloads.get().isDownloaded(t.getVideoId())) &&
					!t.equals(cur)) {
				return t;
			}
		}
		return null;
	}

	/**
	 * Whether something plays without the internet: a queue track, or any downloaded YouTube
	 * video (see {@link #playOfflineTrack}).
	 */
	public static boolean hasOfflineTrack(MainActivityDelegate a) {
		return (nextOfflineTrack(a) != null) || YtOffline.hasDownloaded();
	}

	/**
	 * Carries on with the next queue track that plays without the internet (see
	 * {@link #nextOfflineTrack}) -- the network dropped mid-stream. False if there's none.
	 */
	public static boolean playOfflineTrack(MainActivityDelegate a) {
		MusicTrackItem t = nextOfflineTrack(a);
		// Nothing offline in the queue: everything that was downloaded becomes the queue.
		if (t == null) return YtOffline.playAllDownloaded(a);
		DiagnosticLog.log(TAG, "network lost: playing an offline track", "track=" + t);
		playTrack(a, t, 0);
		return true;
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
			// Watching a YouTube video (not music mode): "Add into queue" means that player's Up next.
			YoutubeHooks h = youtube;
			MediaEngine eng = a.getMediaSessionCallback().getEngine();
			if ((h != null) && !isMusicModeActive(a) && (eng != null) &&
					(eng.getId() == MediaPrefs.MEDIA_ENG_YT)) {
				int n = h.addToVideoQueue(a, list);
				if (n > 0) {
					UiUtils.showToast(ctx, ctx.getResources().getQuantityString(
							R.plurals.video_added_to_queue, n, n));
					return;
				} else if (n == 0) {
					UiUtils.showToast(ctx, R.string.video_queue_nothing_added);
					return;
				}
			}
			// Right after the track playing now (or where the queue left off), not at the far end of
			// a long queue: what was just added is what the user wants to hear next.
			MusicTrackItem cur = getCurrentTrack(a.getMediaSessionCallback());
			if (cur == null) cur = q.getSavedCurrent();
			q.addAfter(cur, list);
			UiUtils.showToast(ctx, ctx.getResources().getQuantityString(R.plurals.music_added_to_queue,
					list.size(), list.size()));
		});
	}

	/** Plays a queue track -- from the Music tab itself (a queue row, or play with nothing on). */
	public static void playTrack(MainActivityDelegate a, MusicTrackItem t, long pos) {
		startTrack(a, t, pos, false);
	}

	/**
	 * @param watch the track's picture is wanted ("Video", downloads played as videos): kept with
	 *              the track (see {@link #startRequest}) until its engine is chosen, so it never
	 *              outlives a start that did not happen
	 */
	private static void startTrack(MainActivityDelegate a, MusicTrackItem t, long pos, boolean watch) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		DiagnosticLog.log(TAG, "play", "track=" + t, "id=" + t.getSourceId(),
				"pos=" + (pos / 1000) + 's', "watch=" + watch);
		// The YouTube player's close() is deliberately inert: a local track taking over from it has
		// to silence its page explicitly.
		if ((t.getVideoId() == null) && (eng != null) && (eng.getId() == MediaPrefs.MEDIA_ENG_YT)) {
			eng.pause();
		}
		t.setStartPosition(pos);
		startRequest = new StartRequest(t, watch);
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

		MusicTrackItem playing = getCurrentTrack(cb);
		if ((cur == null) || (playing != null)) {
			if (cur != null) {
				// Already a queue track (switched to video and back): its queue stays as it is, but
				// whatever was queued in the video player since goes in right after it.
				if (playing != null) moveUpNextIntoQueue(q, eng, playing);
				boolean yt = (eng.getId() == MediaPrefs.MEDIA_ENG_YT);
				boolean local = !yt && (playing != null) && playing.isDownloaded();
				setYoutubeAudioMode(yt || local);
				if (local) watchingLocal = false;
				open(a);
				// A downloaded video's file: the same engine goes on without the picture.
				if (local) continueAsMusic(a, eng, playing);
				return;
			}
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
			moveUpNextIntoQueue(q, eng, existing);
			open(a);
			continueAsMusic(a, eng, existing);
			return;
		}

		// Something from outside the list is playing (YouTube's Up next): keep the list, with this
		// video slotted in right after the entry it interrupted, so the queue carries on there.
		PlayableItem context = eng.getQueueContextItem();
		if ((context != null) && !context.getParent().isExternal()) {
			siblings(context).main().onSuccess(l -> {
				List<PlayableItem> list = new ArrayList<>(l);
				int ci = indexOfSame(list, context);
				int idx;
				if (ci == -1) {
					list = new ArrayList<>(Collections.singletonList(item));
					idx = 0;
				} else {
					idx = ci + 1;
					list.add(idx, item);
					q.copyModes(context.getParent().getPrefs());
				}
				list.addAll(idx + 1, eng.takeUpNext());
				MusicTrackItem t = q.replace(list, idx).get(idx);
				open(a);
				continueAsMusic(a, eng, t);
			});
			return;
		}

		boolean browsing = item.getParent().isExternal();
		FutureSupplier<List<PlayableItem>> list =
				browsing ? completed(Collections.singletonList(item)) : siblings(item);
		list.main().onSuccess(l -> {
			int idx = indexOfSame(l, item);
			List<PlayableItem> items = new ArrayList<>(l);
			if (idx == -1) {
				items = new ArrayList<>(Collections.singletonList(item));
				idx = 0;
			}
			// What was queued to play next (YouTube's Up next) comes right after this track, so the
			// Music tab's queue shows -- and plays -- exactly what the video player would have.
			items.addAll(idx + 1, eng.takeUpNext());
			// Modes first: with the list's Shuffle on, the new shuffled order starts from this track.
			if (!browsing) q.copyModes(item.getParent().getPrefs());
			MusicTrackItem t = q.replace(items, idx).get(idx);
			open(a);
			continueAsMusic(a, eng, t);
		});
	}

	/**
	 * Switches the music track that's playing now back to its video: YouTube's page is already
	 * playing it, so it's just a matter of showing it at its usual quality again; a local file
	 * gets its picture back on the same engine.
	 */
	public static void switchToVideo(MainActivityDelegate from) {
		// The video shows where it plays: the car's screen while Android Auto is connected.
		MainActivityDelegate a = from.getPlaybackDelegate();
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		MusicTrackItem t = getCurrentTrack(cb);
		if (eng == null) return;
		boolean yt = (eng.getId() == MediaPrefs.MEDIA_ENG_YT);
		// YouTube in music mode may momentarily not report its queue track: still its video to show.
		if ((t == null) && !(yt && youtubeAudioMode)) return;

		if ((t != null) && (t.getVideoId() != null) && !yt) {
			// A downloaded video: its file has the picture, on the very same engine.
			if (!t.hasVideo()) return;
			DiagnosticLog.log(TAG, "switch to video (downloaded)", "id=" + t.getVideoId());
			watchingLocal = true;
			// Out of the Music tab, which keeps the floating buttons and the control panel away: the
			// downloads list is where the picture is shown from, and where leaving fullscreen returns.
			if (a.showFragment(R.id.downloads_addon) == null) a.backToNavFragment();
			BodyLayout b = a.getBody();
			if ((b != null) && !b.isVideoMode()) b.setMode(BodyLayout.Mode.VIDEO);
			// The Music tab lets go of the floating buttons a moment later: they come with the video.
			a.post(a::updateExtraFabsVisibility);
			// Started again from where it is, picture on, rather than the same player switching its
			// picture track back on mid-play (switchItem): after that ExoPlayer raced through the rest
			// of the file (23 s to 177 s in 8 s in the log), its picture black, then skipped to the next
			// track. A fresh start with the picture on always plays; a local file is ready in ~0.1 s.
			// Once the picture's new surfaces are there: the engine gets them as it starts, as on a first
			// start.
			boolean[] started = {false};
			Runnable start = () -> {
				if (started[0]) return;
				started[0] = true;
				eng.getPosition().main().onSuccess(pos -> {
					if (cb.getEngine() != eng) return;
					startTrack(a, t, pos, true);
				});
			};
			VideoView vv = (b == null) ? null : b.getVideoView();
			if ((vv != null) && !cb.hasCustomEngineProvider()) {
				// New surfaces every time: a reused one could stay black (see VideoView#recreateSurfaces).
				vv.recreateSurfaces();
				vv.onSurfaceCreated(start);
				// Should the new surface not come, the track still starts (on whatever screen there is).
				a.getHandler().postDelayed(start, 1500);
			} else {
				start.run();
			}
			return;
		}

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
			a.post(a::updateExtraFabsVisibility);
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
	public static void watch(MainActivityDelegate from, MusicTrackItem t) {
		MainActivityDelegate a = from.getPlaybackDelegate(); // See switchToVideo().
		MusicQueue q = getQueue(a);
		long pos = ((q != null) && t.equals(q.getSavedCurrent())) ? q.getSavedPosition() : 0;
		DiagnosticLog.log(TAG, "watch", "track=" + t, "pos=" + (pos / 1000) + 's');

		if (t.getVideoId() != null) {
			startTrack(a, t, pos, true);
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
