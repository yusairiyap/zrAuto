package me.aap.fermata.addon.music;

import static me.aap.utils.async.Completed.completed;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.engine.MediaEngineException;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.ui.view.VideoView;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.async.FutureSupplier;

/**
 * Stands in as the session's engine for a YouTube queue track while the YouTube tab's player is
 * being pointed at it (see {@link MusicPlayer#getYoutubeEngine}): it asks that player to load the
 * video and then just waits. As soon as the video starts playing, the YouTube player takes the
 * session over by itself (as it does for any video it plays), which closes this one.
 */
final class YoutubeStartEngine implements MediaEngine {
	private static final long START_TIMEOUT = 45_000L;
	private MusicTrackItem track;
	private final Listener listener;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private boolean closed;

	YoutubeStartEngine(MusicTrackItem track, Listener listener) {
		this.track = track;
		this.listener = listener;
	}

	@Override
	public int getId() {
		return MediaPrefs.MEDIA_ENG_YT;
	}

	@Override
	public void prepare(PlayableItem source) {
		handler.removeCallbacksAndMessages(null);
		if (source instanceof MusicTrackItem t) track = t;
		if (!MusicPlayer.startYoutube(track, source.getPrefs().getPositionPref())) {
			fail("the YouTube addon is disabled");
			return;
		}
		handler.postDelayed(() -> {
			if (!closed) fail("the video didn't start within " + (START_TIMEOUT / 1000) + 's');
		}, START_TIMEOUT);
	}

	private void fail(String msg) {
		DiagnosticLog.log("MUSIC", "YouTube track didn't start", "id=" + track.getVideoId(), msg);
		listener.onEngineError(this, new MediaEngineException("YouTube: " + msg));
	}

	@Override
	public void start() {
	}

	@Override
	public void stop() {
	}

	@Override
	public void pause() {
	}

	@Override
	public boolean canPause() {
		return false;
	}

	@Override
	public PlayableItem getSource() {
		return track;
	}

	@Nullable
	@Override
	public PlayableItem getQueueItem() {
		return track;
	}

	@Override
	public FutureSupplier<Long> getDuration() {
		return completed(0L);
	}

	@Override
	public FutureSupplier<Long> getPosition() {
		return completed(0L);
	}

	@Override
	public void setPosition(long position) {
	}

	@Override
	public FutureSupplier<Float> getSpeed() {
		return completed(1f);
	}

	@Override
	public void setSpeed(float speed) {
	}

	@Override
	public void setVideoView(@Nullable VideoView view) {
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
		closed = true;
		handler.removeCallbacksAndMessages(null);
	}
}
