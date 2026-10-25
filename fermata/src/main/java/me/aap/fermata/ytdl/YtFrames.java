package me.aap.fermata.ytdl;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import androidx.annotation.Nullable;

import java.util.Collections;

import me.aap.fermata.util.DiagnosticLog;

/**
 * A still picture of a YouTube video taken from the video itself, for the album art when High quality is chosen: YouTube's
 * maxresdefault.jpg is often missing (and always 1280 wide), while a frame of the best stream is as sharp as the video.
 * <p>
 * Uses the same streams as the downloads ({@link YtStreamResolver}): the tallest H.264 picture up to {@link #MAX_HEIGHT}
 * lines, read over HTTP at the middle of the video. Blocks: call it on a background thread.
 */
public final class YtFrames {
	/** The tallest H.264 picture YouTube gives out (its higher resolutions are VP9 or AV1, which are not taken). */
	private static final int MAX_HEIGHT = 1080;

	private YtFrames() {
	}

	/**
	 * The frame at the middle of {@code videoId}, or null when the video has no such stream or the frame can't be read (the
	 * reason goes to the Diagnostic log under ART).
	 */
	@Nullable
	public static Bitmap middleFrame(String videoId) {
		MediaMetadataRetriever mmr = null;
		try {
			YtStreamResolver.Result r = YtStreamResolver.resolve(videoId, true, MAX_HEIGHT, 0);
			if ((r.video == null) || (r.durationMs <= 0)) {
				DiagnosticLog.log("ART", "frame: no stream", videoId, "height=" + MAX_HEIGHT);
				return null;
			}
			mmr = new MediaMetadataRetriever();
			mmr.setDataSource(r.video.url, Collections.singletonMap("User-Agent", r.userAgent));
			// The middle in microseconds: durationMs / 2 milliseconds.
			Bitmap bm = mmr.getFrameAtTime(r.durationMs * 500L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
			DiagnosticLog.log("ART", "frame taken", videoId, "height=" + r.video.height,
					(bm == null) ? "no picture" : (bm.getWidth() + "x" + bm.getHeight()));
			return bm;
		} catch (Exception ex) {
			DiagnosticLog.log("ART", "frame failed", videoId, String.valueOf(ex));
			return null;
		} finally {
			if (mmr != null) mmr.release();
		}
	}
}
