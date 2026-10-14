package me.aap.fermata.ytcaptions;

import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.action.Action;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.ytcaptions.YtCaptions.Cue;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.utils.log.Log;

/**
 * The captions of whatever YouTube video plays, and where in them the playback is: the one place the
 * lyrics (Music tab and their card) and the captions over fullscreen video both get them from, so a
 * video's captions are fetched once however many of them show.
 * <p>
 * It only works while something is using it ({@link #acquire}): then, four times a second, it
 * notices a new video (and loads its captions) and asks the player for its position. Everything runs
 * on the main thread; only the download is off it.
 */
public final class CaptionsHub {
	private static final long TICK_MS = 250;
	private static final CaptionsHub instance = new CaptionsHub();

	public enum State {
		/** Nothing that has captions plays. */
		IDLE,
		LOADING,
		READY,
		/** The video has no captions. */
		NONE,
		/** They couldn't be fetched (no network, YouTube refusing). */
		FAILED
	}

	public interface Listener {
		/** The video, or what is known of its captions, changed. */
		default void onCaptionsState(CaptionsHub hub) {
		}

		/** The position was read again (several times a second while captions are READY). */
		default void onCaptionsTick(CaptionsHub hub) {
		}
	}

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final LruCache<String, YtCaptions.Result> cache = new LruCache<>(8);
	private final List<Listener> listeners = new ArrayList<>();
	private final Runnable tick = this::tick;
	@Nullable
	private MainActivityDelegate activity;
	@Nullable
	private String videoId;
	private String title = "";
	private State state = State.IDLE;
	private List<Cue> cues = java.util.Collections.emptyList();
	@Nullable
	private YtCaptions.Track track;
	private int index = -1;
	private long position;
	private int generation;
	private boolean polling;
	private boolean ticking;

	private CaptionsHub() {
	}

	public static CaptionsHub get() {
		return instance;
	}

	/** Starts (or keeps) the hub running for {@code l}, until {@link #release}. */
	public void acquire(MainActivityDelegate a, Listener l) {
		activity = a;
		if (!listeners.contains(l)) listeners.add(l);
		if (!ticking) {
			ticking = true;
			handler.post(tick);
		}
	}

	public void release(Listener l) {
		listeners.remove(l);
		if (listeners.isEmpty()) {
			ticking = false;
			handler.removeCallbacks(tick);
			generation++;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// What it knows

	public State getState() {
		return state;
	}

	@Nullable
	public String getVideoId() {
		return videoId;
	}

	/** The playing video's title, for the lyrics card. */
	public String getTitle() {
		return title;
	}

	@Nullable
	public YtCaptions.Track getTrack() {
		return track;
	}

	public List<Cue> getCues() {
		return cues;
	}

	/** The line playing now (the last one that began), -1 before the first. */
	public int getIndex() {
		return index;
	}

	public long getPosition() {
		return position;
	}

	/** The line to show as a caption right now: the one playing, unless its time is over. */
	@Nullable
	public Cue getCaption() {
		if ((state != State.READY) || (index < 0) || (index >= cues.size())) return null;
		Cue c = cues.get(index);
		return (position < c.end) ? c : null;
	}

	/** Seeks the playing video to the start of line {@code i}. */
	public void seekTo(int i) {
		MainActivityDelegate a = activity;
		if ((a == null) || (i < 0) || (i >= cues.size())) return;
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		if (eng == null) return;
		long t = Math.max(0, cues.get(i).start - 150);
		eng.setPosition(t);
		position = t;
		update(t);
	}

	/** Forgets what was fetched and loads again, e.g. after the language was changed. */
	public void invalidate() {
		cache.evictAll();
		videoId = null;
		if (ticking && (activity != null)) handler.post(tick);
	}

	// ---------------------------------------------------------------------------------------------
	// The tick

	private void tick() {
		if (!ticking) return;
		handler.removeCallbacks(tick);
		MainActivityDelegate a = activity;
		if (a != null) {
			try {
				step(a);
			} catch (Throwable ex) {
				Log.e(ex, "Captions tick failed");
			}
		}
		if (ticking) handler.postDelayed(tick, TICK_MS);
	}

	private void step(MainActivityDelegate a) {
		PlayableItem pi = Action.getFavoritableItem(a);
		String id = YtDownloads.videoIdOf(pi);
		if (!java.util.Objects.equals(id, videoId)) {
			videoId = id;
			title = (pi == null) ? "" : String.valueOf(pi.getName());
			load();
		} else if ((pi != null) && (title.isEmpty())) {
			title = String.valueOf(pi.getName());
		}
		if ((state != State.READY) || polling) return;
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		if (eng == null) return;
		polling = true;
		int gen = generation;
		eng.getPosition().main().onCompletion((pos, err) -> {
			polling = false;
			if ((err != null) || (pos == null) || (gen != generation)) return;
			update(pos);
		});
	}

	private void update(long pos) {
		position = pos;
		index = indexAt(cues, pos);
		for (Listener l : new ArrayList<>(listeners)) l.onCaptionsTick(this);
	}

	/** The index of the last line that began at or before {@code pos}, -1 when none has. */
	static int indexAt(List<Cue> cues, long pos) {
		int lo = 0;
		int hi = cues.size() - 1;
		int r = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (cues.get(mid).start <= pos) {
				r = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return r;
	}

	// ---------------------------------------------------------------------------------------------
	// Loading

	private void setState(State s) {
		state = s;
		for (Listener l : new ArrayList<>(listeners)) l.onCaptionsState(this);
	}

	private void load() {
		int gen = ++generation;
		polling = false;
		index = -1;
		position = 0;
		track = null;
		cues = java.util.Collections.emptyList();
		String id = videoId;
		if (id == null) {
			setState(State.IDLE);
			return;
		}
		YtCaptions.Result cached = cache.get(id);
		if (cached != null) {
			apply(cached);
			return;
		}
		setState(State.LOADING);
		MainActivityPrefs p = activity.getPrefs();
		String lang = p.getStringPref(MainActivityPrefs.CAPTIONS_LANG);
		boolean auto = p.getBooleanPref(MainActivityPrefs.CAPTIONS_AUTO);
		executor.execute(() -> {
			try {
				YtCaptions.Result r = YtCaptions.load(id, lang, auto);
				handler.post(() -> {
					if (gen != generation) return;
					cache.put(id, r);
					apply(r);
				});
			} catch (Exception ex) {
				Log.e(ex, "Failed to load the captions of ", id);
				DiagnosticLog.log("CAPTIONS", id, "load failed", ex);
				handler.post(() -> {
					if (gen != generation) return;
					setState(State.FAILED);
				});
			}
		});
	}

	private void apply(YtCaptions.Result r) {
		track = r.track;
		cues = r.cues;
		setState(r.isEmpty() ? State.NONE : State.READY);
	}
}
