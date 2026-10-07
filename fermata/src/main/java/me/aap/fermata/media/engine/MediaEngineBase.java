package me.aap.fermata.media.engine;

import static android.media.session.PlaybackState.STATE_PAUSED;
import static android.media.session.PlaybackState.STATE_PLAYING;
import static android.media.session.PlaybackState.STATE_STOPPED;
import static me.aap.fermata.media.sub.SubGrid.Position.BOTTOM_CENTER;
import static me.aap.fermata.media.sub.SubGrid.Position.BOTTOM_LEFT;
import static me.aap.fermata.media.sub.SubGrid.Position.BOTTOM_RIGHT;
import static me.aap.utils.async.Completed.cancelled;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedEmptyList;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.collection.CollectionUtils.comparing;
import static me.aap.utils.text.TextUtils.timeToString;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.addon.SubGenAddon;
import me.aap.fermata.media.sub.FileSubtitles;
import me.aap.fermata.media.sub.SubGrid;
import me.aap.fermata.media.sub.SubScheduler;
import me.aap.fermata.media.sub.Subtitles;
import me.aap.fermata.ui.view.VideoView;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.BiConsumer;
import me.aap.utils.log.Log;
import me.aap.utils.vfs.VirtualFile;
import me.aap.utils.vfs.VirtualResource;

/**
 * @author Andrey Pavlenko
 */
public abstract class MediaEngineBase implements MediaEngine {
	protected final Listener listener;
	@Nullable
	protected VideoView videoView;
	private int state = STATE_STOPPED;
	private SubMgr subMgr;

	protected MediaEngineBase(Listener listener) {this.listener = listener;}

	// ---------------------------------------------------------------------------------------------
	// Volume fades: the sound comes in when playback starts and goes out before it pauses or skips,
	// as YouTube's does (youtube_fade.js), for every engine that can set its volume.

	private static final long FADE_IN_MS = 450;
	private static final long FADE_OUT_MS = 250;
	private static final long FADE_STEP_MS = 25;
	// The level a fade ends at: 1, or 0 while muted.
	private float volumeTarget = 1f;
	private float volumeNow = 1f;
	// The step of the fade that is going on.
	@Nullable
	private Runnable fade;
	// What runs once that fade is over...
	@Nullable
	private Runnable fadeDone;
	// ...and whether that is the pause the sound was going out for: one that is cut short still has to
	// pause the player (or it plays on, silently marked paused), while a skip's follow-up is dropped.
	private boolean fadeDoneIsPause;
	@Nullable
	private Handler fadeHandler;

	/** Whether the engine can set its volume (see {@link #setFadeVolume}), so the fades apply. */
	protected boolean supportsFade() {
		return false;
	}

	/** Sets the player's volume, 0 (silent) to 1; only called if {@link #supportsFade()}. */
	protected void setFadeVolume(float volume) {
	}

	private Handler fadeHandler() {
		Handler h = fadeHandler;
		if (h == null) fadeHandler = h = new Handler(Looper.getMainLooper());
		return h;
	}

	private void applyVolume(float v) {
		volumeNow = v;
		if (!supportsFade()) return;
		try {
			setFadeVolume(v);
		} catch (RuntimeException ex) {
			// The player isn't in a state to take it (released, in error): the fade has no more to do.
			Log.d(ex, "Failed to set the volume");
		}
	}

	/** The engine's mute/unmute: the level fades return to. */
	protected final void setMuteLevel(boolean muted) {
		volumeTarget = muted ? 0f : 1f;
		cancelFade();
		applyVolume(volumeTarget);
	}

	/** Stops any fade in progress and puts the volume back to the level of the day. */
	protected final void resetFade() {
		stopEndWatch();
		cancelFade();
		applyVolume(volumeTarget);
	}

	// How close to its end a track is when the sound starts going out, so it is silent as it ends, as
	// YouTube's page does (youtube_fade.js) -- the next track comes in with its own fade.
	private static final long END_FADE_MS = 400;
	private static final long END_WATCH_MS = 150;
	private boolean endWatching;
	private boolean endFaded;

	/** While playing, checks how long is left and fades the sound out for the end of the track. */
	private void watchEnd() {
		endFaded = false;
		if (endWatching) return;
		endWatching = true;
		fadeHandler().postDelayed(endWatch, END_WATCH_MS);
	}

	private final Runnable endWatch = new Runnable() {
		@Override
		public void run() {
			if (!endWatching) return;
			if (!isPlaying() && (fade == null)) {
				// Paused or stopped: watching starts again with the next start.
				endWatching = false;
				return;
			}

			try {
				var dur = getDuration();
				var pos = getPosition();
				if (dur.isDoneNotFailed() && pos.isDoneNotFailed() && (fade == null) && isPlaying()) {
					long d = dur.getOrThrow();
					long left = (d > 0) ? (d - pos.getOrThrow()) : -1;
					if ((left > 0) && (left <= END_FADE_MS + END_WATCH_MS) && !endFaded &&
							(volumeTarget > 0f)) {
						endFaded = true;
						fadeTo(0f, Math.max(100, left - 50), null, false);
					} else if (endFaded && (left > 2000)) {
						// Back from the end (repeat, a seek): the sound with it.
						endFaded = false;
						fadeTo(volumeTarget, FADE_IN_MS, null, false);
					}
				}
			} catch (RuntimeException ex) {
				Log.d(ex, "Failed to check the end of the track");
			}
			fadeHandler().postDelayed(this, END_WATCH_MS);
		}
	};

	private void stopEndWatch() {
		endWatching = false;
		if (fadeHandler != null) fadeHandler.removeCallbacks(endWatch);
	}

	@Override
	public void restoreVolume() {
		if (fade == null) applyVolume(volumeTarget);
	}

	/** Ends the fade in progress where it is; the pause it was for, if it was one, still happens. */
	private void cancelFade() {
		Runnable f = fade;
		fade = null;
		if ((f != null) && (fadeHandler != null)) fadeHandler.removeCallbacks(f);
		Runnable d = fadeDone;
		boolean pause = fadeDoneIsPause;
		fadeDone = null;
		fadeDoneIsPause = false;
		if ((d != null) && pause) d.run();
	}

	/** Just before the player starts: the sound comes in over a moment. */
	protected final void fadeIn() {
		if (!supportsFade()) return;
		watchEnd();
		// Started again while already playing (a repeated play, audio focus back): nothing to fade in.
		if ((volumeTarget <= 0f) || isPlaying()) return;
		// Playing again: the pause the sound was going out for no longer applies.
		if (fadeDoneIsPause) {
			fadeDone = null;
			fadeDoneIsPause = false;
		}
		cancelFade();
		applyVolume(0f);
		fadeTo(volumeTarget, FADE_IN_MS, null, false);
	}

	/**
	 * The engine's pause(): records the pause (see {@link #stopped}), then lets the sound go out
	 * before {@code realPause} pauses the player -- at once if it wasn't playing.
	 */
	protected final void pauseWithFade(Runnable realPause) {
		boolean playing = isPlaying();
		stopped(true);
		Runnable pause = () -> {
			try {
				realPause.run();
			} catch (RuntimeException ex) {
				// The player is in no state to pause (being prepared, in error): nothing to pause.
				Log.d(ex, "Failed to pause");
			}
			applyVolume(volumeTarget);
		};
		cancelFade();
		if (!playing || !supportsFade() || (volumeTarget <= 0f)) {
			pause.run();
			return;
		}
		fadeTo(0f, FADE_OUT_MS, pause, true);
	}

	@Override
	public void fadeOut(Runnable then) {
		cancelFade();
		if (!isPlaying() || !supportsFade() || (volumeTarget <= 0f)) {
			then.run();
			return;
		}
		// Not brought back up afterwards: what plays next sets its own volume (and this engine is
		// closed or restarted by then); see restoreVolume() for a skip that came to nothing.
		fadeTo(0f, FADE_OUT_MS, then, false);
	}

	/** Ramps the volume to {@code to} over {@code ms}, then runs {@code done}. */
	private void fadeTo(float to, long ms, @Nullable Runnable done, boolean isPause) {
		float from = volumeNow;
		long t0 = SystemClock.uptimeMillis();
		Handler h = fadeHandler();
		fadeDone = done;
		fadeDoneIsPause = isPause;
		Runnable step = new Runnable() {
			@Override
			public void run() {
				if (fade != this) return;
				float k = Math.min(1f, (SystemClock.uptimeMillis() - t0) / (float) ms);
				applyVolume(from + (to - from) * k);
				if (k < 1f) {
					h.postDelayed(this, FADE_STEP_MS);
				} else {
					fade = null;
					Runnable d = fadeDone;
					fadeDone = null;
					fadeDoneIsPause = false;
					if (d != null) d.run();
				}
			}
		};
		fade = step;
		h.post(step);
	}

	@CallSuper
	@Override
	public void setVideoView(@Nullable VideoView view) {
		if ((subMgr != null) && (videoView != null)) subMgr.removeSubtitleConsumer(videoView);
		videoView = view;
		if (view == null) return;
		view.clearSubtitleSurface();
		if (subMgr != null) subMgr.addSubtitleConsumer(view);
		else selectSubtitleStream();
	}

	protected boolean isPlaying() {
		return state == STATE_PLAYING;
	}

	protected boolean isPaused() {
		return state == STATE_PAUSED;
	}

	protected FutureSupplier<Long> getSubtitlePosition() {
		return getPosition();
	}

	protected long subSchedulerClock() {
		var pos = getSubtitlePosition();
		return pos.isDoneNotFailed() ? pos.getOrThrow() : System.currentTimeMillis();
	}

	public FutureSupplier<List<SubtitleStreamInfo>> getSubtitleStreamInfo() {
		var src = getSource();
		if (src == null) return completedEmptyList();

		var srcFile = src.getResource();
		var srcName = srcFile.getName();
		var idx = srcName.lastIndexOf('.');
		var baseName = (idx == -1) ? srcName : srcName.substring(0, idx);

		return srcFile.getParent().then(srcDir -> {
			if (srcDir == null) return completedEmptyList();
			var filter = srcDir.filterChildren();
			for (var ext : FileSubtitles.getSupportedFileExtensions())
				filter = filter.or().startsEnds(baseName, ext);
			return filter.apply();
		}).map(children -> {
			if (children.isEmpty()) return Collections.emptyList();

			int id = 0xFFFF;
			var list = new ArrayList<SubtitleStreamInfo>();
			Collections.sort(children, comparing(VirtualResource::getName));

			for (var f : children) {
				if (!f.isFile()) continue;
				var name = f.getName();
				var langStart = baseName.length() + 1;
				var langEnd = name.length() - 4;
				var lang = langStart >= langEnd ? null : name.substring(langStart, langEnd);
				list.add(new SubtitleStreamInfo(id++, lang, null, (VirtualFile) f));
			}

			for (int i = 0, n = list.size(); i < n; i++) {
				for (int j = 0; j < n; j++) {
					if (i != j) list.add(list.get(i).join(id++, list.get(j)));
				}
			}

			return list;
		});
	}

	@Override
	public int getSubtitleDelay() {
		return (subMgr == null) ? 0 : subMgr.getSubtitleDelay();
	}

	@Override
	public void setSubtitleDelay(int milliseconds) {
		if ((milliseconds == 0) && (subMgr == null)) return;
		sub().setSubtitleDelay(milliseconds);
	}

	@Override
	public boolean isSubtitlesSupported() {
		return true;
	}

	@Nullable
	@Override
	public SubtitleStreamInfo getCurrentSubtitleStreamInfo() {
		return (subMgr == null) ? null : subMgr.getCurrentSubtitleStreamInfo();
	}

	@Override
	public void setCurrentSubtitleStream(@Nullable SubtitleStreamInfo i) {
		if ((i == null) && (subMgr == null)) return;
		sub().setCurrentSubtitleStream(i);
	}

	@Override
	public FutureSupplier<SubGrid> getCurrentSubtitles() {
		return (subMgr == null) ? NO_SUBTITLES : subMgr.getCurrentSubtitles();
	}

	@Override
	public void addSubtitleConsumer(BiConsumer<SubGrid.Position, Subtitles.Text> consumer) {
		sub().addSubtitleConsumer(consumer);
	}

	@Override
	public void removeSubtitleConsumer(BiConsumer<SubGrid.Position, Subtitles.Text> consumer) {
		if (subMgr != null) subMgr.removeSubtitleConsumer(consumer);
	}

	protected SubGrid createSubStreamGrid() {
		return new SubGrid(new Subtitles.Stream()) ;
	}

	@CallSuper
	@Override
	public void close() {
		stopEndWatch();
		cancelFade();
		stopped(false);
	}

	protected void started() {
		if (state == STATE_PLAYING) return;
		if (state == STATE_PAUSED) {
			if (subMgr != null) subMgr.start();
			state = STATE_PLAYING;
			return;
		}

		state = STATE_PLAYING;

		if (videoView != null) {
			if (subMgr != null) subMgr.addSubtitleConsumer(videoView);
			else selectSubtitleStream();
		} else {
			var src = getSource();
			if (src != null && src.getPrefs().getBooleanPref(SubGenAddon.ENABLED)) selectSubtitleStream();
		}
	}

	protected void stopped(boolean paused) {
		if (paused) {
			if (state == STATE_PAUSED || state == STATE_STOPPED) return;
			state = STATE_PAUSED;
			if (subMgr != null) subMgr.stop(true);
		} else {
			state = STATE_STOPPED;
			videoView = null;
			if (subMgr != null) {
				subMgr.stop(false);
				subMgr = null;
			}
		}
	}

	protected void syncSub(long position, float speed, boolean restart) {
		if (subMgr != null) subMgr.sync(position, speed, restart);
	}

	private SubMgr sub() {
		if (subMgr == null) subMgr = new SubMgr();
		return subMgr;
	}

	private final class SubMgr implements BiConsumer<SubGrid.Position, Subtitles.Text> {
		private final List<BiConsumer<SubGrid.Position, Subtitles.Text>> consumers =
				new ArrayList<>(3);
		private int delay;
		private SubScheduler sub;
		private SubtitleStreamInfo streamInfo;
		private FutureSupplier<SubScheduler> loading = cancelled();

		int getSubtitleDelay() {
			return delay;
		}

		void setSubtitleDelay(int milliseconds) {
			if (delay == milliseconds) return;
			delay = milliseconds;
			if (sub != null) {
				getSubtitlePosition().then(pos -> getSpeed().main().onSuccess(speed -> {
					if (sub != null) {
						sub.stop(false);
						sub.start(getSubtitleDelay(), getSubtitleDelay(), speed);
					}
				}));
			}
		}

		SubtitleStreamInfo getCurrentSubtitleStreamInfo() {
			return streamInfo;
		}

		void setCurrentSubtitleStream(SubtitleStreamInfo i) {
			stop(false);
			streamInfo = i;

			if (videoView == null) {
				listener.onSubtitleStreamChanged(MediaEngineBase.this, i);
			} else {
				addSubtitleConsumer(videoView);
				load();
			}
		}

		FutureSupplier<SubGrid> getCurrentSubtitles() {
			return load().map(sub -> sub == null ? SubGrid.EMPTY : sub.getSubtitles());
		}

		void addSubtitleConsumer(@NonNull BiConsumer<SubGrid.Position, Subtitles.Text> consumer) {
			if (consumers.contains(consumer)) return;
			consumers.add(consumer);
			if (consumer == videoView) prepareDrawer(videoView);
			if (sub == null) load();
			else if ((state == STATE_PLAYING) && !sub.isStarted()) start();
		}

		void removeSubtitleConsumer(@NonNull BiConsumer<SubGrid.Position, Subtitles.Text> consumer) {
			if (!consumers.remove(consumer)) return;
			if (consumers.isEmpty()) stop(true);
			if (consumer == videoView) videoView.releaseSubDrawer();
		}

		private FutureSupplier<SubScheduler> load() {
			if (!loading.isCancelled()) return loading;
			var inf = streamInfo;
			if (inf == null) return loading;

			FutureSupplier<SubGrid> load;

			if (inf instanceof SubtitleStreamInfo.Generated) {
				load = completed(createSubStreamGrid());
			} else if (!inf.getFiles().isEmpty()) {
				load = App.get().execute(() -> {
					var src = getSource();
					if (src == null) return null;

					var files = inf.getFiles();
					var sg = FileSubtitles.load(files.get(0));

					if (files.size() == 1) {
						if (!src.isVideo()) sg.mergeAtPosition(BOTTOM_LEFT);
						return sg;
					}

					var sg1 = FileSubtitles.load(files.get(1));
					sg.mergeAtPosition(BOTTOM_LEFT);
					sg1.mergeAtPosition(BOTTOM_RIGHT);
					sg.mergeWith(sg1);

					if (src.isVideo()) {
						var s1 = sg.get(BOTTOM_LEFT);
						var s2 = sg.get(BOTTOM_RIGHT);
						if (s1.compareTime(s2)) {
							for (int i = 0, n = s1.size(); i < n; i++) {
								s1.get(i).setTranslation(s2.get(i).getText());
							}
							sg.remove(BOTTOM_RIGHT);
							sg.move(BOTTOM_LEFT, BOTTOM_CENTER);
						}
					}

					return sg;
				});
			} else {
				return loading;
			}

			return loading = load.main().map(sg -> {
				if ((sg == null) || (sub != null) || (inf != streamInfo)) return null;
				sub = new SubScheduler(App.get().getHandler(), sg, this,
						MediaEngineBase.this::subSchedulerClock);
				if ((state == STATE_PLAYING) && !consumers.isEmpty()) start();
				return sub;
			});
		}

		@Override
		public void accept(SubGrid.Position position, Subtitles.Text text) {
			for (var c : consumers) c.accept(position, text);

			if (BuildConfig.D) {
				getSubtitlePosition().onSuccess(t -> {
					String time = timeToString((int) (t / 1000));

					if (text == null) {
						Log.d('[', time, "][", position, "] null");
					} else {
						Log.d('[', time, "][", position, "][", timeToString((int) (text.getTime() / 1000)),
								'-',
								timeToString((int) ((text.getTime() + text.getDuration()) / 1000)), "] ",
								text.getText());
					}
				});
			}
		}

		void start() {
			SubScheduler sub = this.sub;
			if (sub == null) return;
			getSubtitlePosition().then(pos -> getSpeed().main().onSuccess(speed -> {
				if (sub != this.sub) return;
				for (@NonNull var c : consumers) {
					if (c == videoView) {
						prepareDrawer(videoView);
						break;
					}
				}
				sub.start(pos, delay, speed);
			}));
		}

		void stop(boolean pause) {
			if (pause) {
				if (sub != null) sub.stop(true);
			} else {
				loading.cancel();
				loading = cancelled();
				if (sub != null) {
					sub.stop(false);
					sub = null;
				}
				consumers.clear();
			}
		}

		void sync(long position, float speed, boolean restart) {
			if (sub == null) return;
			if (restart) {
				sub.stop(false);
				sub.start(position, getSubtitleDelay(), speed);
				if (!isPlaying()) App.get().getHandler().submit(() -> {if (!isPlaying()) sub.stop(true);});
			} else {
				sub.sync(position, getSubtitleDelay(), speed);
			}
		}

		private void prepareDrawer(VideoView videoView) {
			boolean dbl = (streamInfo != null) && (streamInfo.getFiles().size() == 2) ||
					(streamInfo instanceof SubtitleStreamInfo.Generated);
			videoView.prepareSubDrawer(dbl);
		}
	}
}
