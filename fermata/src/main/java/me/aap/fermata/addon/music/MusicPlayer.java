package me.aap.fermata.addon.music;

import static me.aap.utils.async.Completed.completed;

import android.content.Context;
import android.os.SystemClock;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.BodyLayout;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * Entry points into the Music tab: "Play as music" / "Add into music queue" from the library's
 * context menus, "Play as music" for whatever is playing right now (the FAB action and the video
 * control panel's Audio menu), and the switch from music back to video.
 * <p>
 * Switching between video and music keeps the sound going wherever it can: a local file keeps
 * playing on the very same engine, which just stops decoding video (see {@link
 * MediaSessionCallback#switchItem}); a YouTube video keeps playing in its page while its
 * audio-only stream is fetched, and only fades out once that stream is ready to take over. Going
 * back from YouTube music to video only then loads the video, starting where the music was.
 */
public final class MusicPlayer {
	@Nullable
	private static String pendingVideoId;
	private static long pendingVideoPos;
	@Nullable
	private static WebAudioEngineFactory webAudioFactory;
	@Nullable
	private static WebAudioEngine webAudioEngine;
	private static WeakReference<MainActivityDelegate> activity = new WeakReference<>(null);

	/** The fallback engine: a hidden web page, living in whichever activity window is current. */
	public interface WebAudioEngine extends MediaEngine {
		/**
		 * Moves the hidden page into {@code a}'s window, or detaches it (still playing) when null.
		 */
		void moveTo(@Nullable MainActivityDelegate a);

		@Nullable
		MainActivityDelegate getActivity();
	}

	/**
	 * Creates the fallback engine for YouTube audio: a hidden web player of its own (registered by
	 * the YouTube addon, which the {@code fermata} module can't reference directly). Used only when
	 * no direct audio-only stream can be had -- see {@link MusicTrackItem#isWebFallback()}. Entirely
	 * separate from the YouTube tab's own page and engine, which it never touches.
	 */
	public interface WebAudioEngineFactory {
		WebAudioEngine create(MainActivityDelegate a, MediaEngine.Listener listener);
	}

	public static void setWebAudioEngineFactory(@Nullable WebAudioEngineFactory f) {
		webAudioFactory = f;
		DiagnosticLog.log("MUSIC", "web fallback player", (f != null) ? "available" : "unavailable");
	}

	static boolean isWebAudioAvailable() {
		return (webAudioFactory != null) && (activity.get() != null);
	}

	static void activityCreated(MainActivityDelegate a) {
		activity = new WeakReference<>(a);
		// A rebuilt screen (rotation, Android Auto (re)connecting): the hidden player moves into it.
		WebAudioEngine e = webAudioEngine;
		if ((e != null) && (e.getActivity() == null)) e.moveTo(a);
	}

	static void activityDestroyed(MainActivityDelegate a) {
		if (activity.get() == a) activity = new WeakReference<>(null);
		WebAudioEngine e = webAudioEngine;
		if ((e == null) || (e.getActivity() != a)) return;
		// Don't stop the music with the screen: move to another live activity if there is one (the
		// phone's while the car's goes away, or vice versa), else detach until the next one appears.
		MainActivityDelegate other = activity.get();
		e.moveTo(((other != null) && (other != a)) ? other : null);
	}

	/** The fallback engine for {@code current}'s replacement -- reused while it's still in use. */
	@Nullable
	static MediaEngine getWebAudioEngine(@Nullable MediaEngine current, MediaEngine.Listener l) {
		if ((current != null) && (current == webAudioEngine)) return current;
		WebAudioEngineFactory f = webAudioFactory;
		MainActivityDelegate a = activity.get();
		if ((f == null) || (a == null)) return null;
		webAudioEngine = f.create(a, l);
		return webAudioEngine;
	}

	public static void webAudioEngineClosed(MediaEngine e) {
		if (webAudioEngine == e) webAudioEngine = null;
	}

	private MusicPlayer() {
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
	 * Used by the YouTube page loader: where a video the Music tab just switched back to should
	 * start from, consumed by the first call for that video.
	 */
	public static long takeVideoStartPosition(String videoId) {
		if (!videoId.equals(pendingVideoId)) return 0;
		pendingVideoId = null;
		return pendingVideoPos;
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
		start(a, t);
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
		DiagnosticLog.log("MUSIC", "play", "track=" + t, "id=" + t.getSourceId(),
				"method=" + t.getPlaybackMethod(), "pos=" + (pos / 1000) + 's');
		t.setStartPosition(pos);
		a.getMediaSessionCallback().playItem(t, pos);
	}

	/**
	 * "Play as music" for whatever is playing right now -- the FAB action and the video control
	 * panel's Audio menu. The queue becomes the playing item's own list (Favorites, a playlist, its
	 * folder), unless the queue already has it -- then the queue is kept as it is, so switching to
	 * video and back doesn't throw away a queue the user put together.
	 */
	public static void playCurrentAsMusic(MainActivityDelegate a) {
		MusicQueue q = getQueue(a);
		if (q == null) return;
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		PlayableItem cur = (eng == null) ? null : eng.getSource();

		if ((cur == null) || (cur instanceof MusicTrackItem)) {
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
		String sourceId = MusicQueue.sourceIdOf(item);
		MusicTrackItem existing = findInQueue(q, sourceId);

		if (existing != null) {
			open(a);
			handOff(a, eng, existing);
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
			open(a);
			handOff(a, eng, t);
		});
	}

	/**
	 * Switches the music track that's playing now back to its video, only then loading the video
	 * (YouTube picks up where the music is; a local file just gets its picture back).
	 */
	public static void switchToVideo(MainActivityDelegate a) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		if ((eng == null) || !(eng.getSource() instanceof MusicTrackItem t)) return;

		String vid = t.getVideoId();

		if (vid != null) {
			eng.getPosition().main().onSuccess(pos -> a.getLib().getItem(MusicTrackItem.YT_PREFIX + vid)
					.main().onCompletion((i, err) -> {
						if (!(i instanceof MediaLib.ExternallyPlayableItem ext)) {
							if (err != null) Log.w(err);
							UiUtils.showToast(a.getContext(), R.string.music_video_unavailable);
							return;
						}
						pendingVideoId = vid;
						pendingVideoPos = pos;
						// The music keeps playing until the page's video actually starts, which takes
						// over the session (and stops this engine) on its own.
						ActivityFragment f = a.showFragment(ext.getPlayerFragmentId());
						if (f != null) ext.loadInFragment(f, ext);
					}));
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

	/** Starts {@code t}, taking over from the current playback if that's the same media. */
	private static void start(MainActivityDelegate a, MusicTrackItem t) {
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		PlayableItem cur = (eng == null) ? null : eng.getSource();
		if ((cur != null) && isSameMedia(eng, cur, t)) handOff(a, eng, t);
		else playTrack(a, t, 0);
	}

	private static boolean isSameMedia(MediaEngine eng, PlayableItem cur, MusicTrackItem t) {
		String vid = t.getVideoId();

		if (vid != null) {
			if (cur instanceof MusicTrackItem ct) return vid.equals(ct.getVideoId());
			if (eng.getId() != MediaPrefs.MEDIA_ENG_YT) return false;
			PlayableItem fav = eng.getFavoritableItem();
			return (fav != null) && (MusicTrackItem.YT_PREFIX + vid).equals(MusicQueue.sourceIdOf(fav));
		}

		PlayableItem src = t.getSource();
		if (src == null) return false;
		if (cur instanceof MusicTrackItem ct) return t.getSourceId().equals(ct.getSourceId());
		return cur.getLocation().equals(src.getLocation());
	}

	/**
	 * Hands playback of the same media over from {@code eng} to {@code t} at the current position.
	 */
	private static void handOff(MainActivityDelegate a, MediaEngine eng, MusicTrackItem t) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		PlayableItem cur = eng.getSource();
		if (cur == t) return;
		if ((cur instanceof MusicTrackItem ct) && (ct != t)) t.copyStreamFrom(ct);

		eng.getPosition().main().onSuccess(pos -> {
			if (cb.getEngine() != eng) return;

			if ((eng.getId() != MediaPrefs.MEDIA_ENG_YT) || (t.getVideoId() == null)) {
				// Same file (or the same, already resolved stream): keep the engine, drop the video.
				if (!cb.switchItem(t)) playTrack(a, t, pos);
				return;
			}

			// YouTube: the video keeps playing while its audio-only stream is being fetched.
			t.prepareSource().main().onSuccess(v -> {
				if (cb.getEngine() != eng) return;
				if (t.needsNetworkResolve()) {
					// Couldn't get one, and no fallback either -- the queue's own listener already told
					// the user why; just leave the video playing.
					return;
				}
				eng.getPosition().main().onSuccess(p -> {
					if (cb.getEngine() != eng) return;
					DiagnosticLog.log("MUSIC", "hand-off from the YouTube tab: begin (video keeps playing)",
							"id=" + t.getVideoId(), "method=" + t.getPlaybackMethod(), "pos=" + (p / 1000) + 's');
					// Gap-free: the video keeps playing (its page events no longer driving the session)
					// until the music is actually audible -- see HandOff.
					eng.beginHandOff();
					new HandOff(a, eng, t).start();
					playTrack(a, t, p);
				});
			});
		});
	}

	/**
	 * Finishes a gap-free switch from the YouTube tab's video to its music track: once the track is
	 * actually playing, catches it up to wherever the video has got to in the meantime (only if
	 * they've drifted noticeably apart -- a seek costs a moment of rebuffering) and only then fades
	 * the video out. If the track fails instead, the video just carries on as the session's player.
	 */
	private static final class HandOff implements MediaSessionCallback.Listener {
		private static final long TIMEOUT = 60_000L;
		private static final long MAX_DRIFT = 1500L;
		private static final long SEEK_SETTLE = 800L;
		// Strong reference: the session's listener list only holds weak ones.
		@Nullable
		private static HandOff active;
		private final MainActivityDelegate activity;
		private final MediaEngine video;
		private final MusicTrackItem track;
		private final long startTime = SystemClock.elapsedRealtime();
		private boolean done;

		HandOff(MainActivityDelegate activity, MediaEngine video, MusicTrackItem track) {
			this.activity = activity;
			this.video = video;
			this.track = track;
		}

		void start() {
			HandOff old = active;
			if (old != null) old.finish(false, "superseded by another hand-off");
			active = this;
			activity.getMediaSessionCallback().addBroadcastListener(this);
			activity.postDelayed(() -> {
				if (!done) finish(false, "the music didn't start within " + (TIMEOUT / 1000) + 's');
			}, TIMEOUT);
		}

		@Override
		public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
			if (done) return;
			PlayableItem cur = cb.getCurrentItem();
			int st = state.getState();

			if (st == PlaybackStateCompat.STATE_ERROR) {
				finish(false, "the music failed: " + state.getErrorMessage());
			} else if ((cur != null) && (cur != track) && (cur != video.getSource())) {
				// Something else was picked meanwhile: the video must not keep playing under it.
				video.handOff();
				finish(true, "something else started playing: " + cur);
			} else if ((cur == track) && (st == PlaybackStateCompat.STATE_PLAYING)) {
				MediaEngine music = cb.getEngine();
				if (music == null) return;
				done = true;
				video.getPosition().and(music.getPosition()).main().onSuccess(h -> {
					long drift = h.value1 - h.value2;
					boolean seek = Math.abs(drift) > MAX_DRIFT;
					if (seek) {
						cb.onSeekTo(h.value1);
						// Let the music's catch-up seek settle before the video fades: a brief overlap
						// (like a crossfade) rather than a moment of silence.
						activity.postDelayed(video::handOff, SEEK_SETTLE);
					} else {
						video.handOff();
					}
					finish(true, "music playing after " + (SystemClock.elapsedRealtime() - startTime) +
							"ms, drift=" + drift + "ms" + ((Math.abs(drift) > MAX_DRIFT) ? " (caught up)" : ""));
				});
			}
		}

		private void finish(boolean ok, String how) {
			if (active == this) active = null;
			boolean wasDone = done;
			done = true;
			MediaSessionCallback cb = activity.getMediaSessionCallback();
			cb.removeBroadcastListener(this);
			DiagnosticLog.log("MUSIC", "hand-off from the YouTube tab: " + (ok ? "done" : "cancelled"),
					"id=" + track.getVideoId(), how);
			if (ok || wasDone) return;

			// The music didn't make it: the video, still playing, is the session's player again.
			video.cancelHandOff();
			if (cb.getEngine() != video) {
				cb.setEngine(video);
				cb.onEngineStarted(video);
			}
		}
	}

	@Nullable
	private static MusicTrackItem findInQueue(MusicQueue q, String sourceId) {
		MusicTrackItem cur = q.getSavedCurrent();
		if ((cur != null) && sourceId.equals(cur.getSourceId())) return cur;
		for (MusicTrackItem t : q.getTracks()) {
			if (sourceId.equals(t.getSourceId())) return t;
		}
		return null;
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
