package me.aap.fermata.ytdl;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Collections;

import me.aap.fermata.util.DiagnosticLog;

/**
 * A still picture of a YouTube video taken from the video itself, for the album art when High quality is chosen: YouTube's
 * maxresdefault.jpg is often missing (and only 1280 wide), while a frame of the best stream is as sharp as the video.
 * <p>
 * Uses the same streams as the downloads ({@link YtStreamResolver}), but every codec YouTube gives out: the tallest picture
 * up to {@link #MAX_HEIGHT} lines (4K where the video has it, as AV1 in MP4), read over HTTP at the middle of the video. If a
 * stream can't be decoded here, the next one down is tried. Blocks: call it on a background thread.
 */
public final class YtFrames {
	/** The tallest picture asked for: 4K. */
	private static final int MAX_HEIGHT = 2160;
	/** How many streams are tried before giving up on the video (each one lower than the one before). */
	private static final int MAX_TRIES = 3;

	private YtFrames() {
	}

	/**
	 * The frame at the middle of {@code videoId}, or null when no stream or frame can be read (the reason goes to the
	 * Diagnostic log under ART). The frame is at the full size of the stream it came from.
	 */
	@Nullable
	public static Bitmap middleFrame(String videoId) {
		long start = System.currentTimeMillis();
		try {
			YtStreamResolver.Frames f = YtStreamResolver.frames(videoId, MAX_HEIGHT);
			DiagnosticLog.log("ART", "frame streams listed", videoId, "ms=" + (System.currentTimeMillis() - start),
					"count=" + f.streams.size());
			if ((f.durationMs <= 0) || f.streams.isEmpty()) {
				DiagnosticLog.log("ART", "frame: no stream", videoId, "height=" + MAX_HEIGHT);
				return null;
			}
			int tries = 0;
			for (YtStreamResolver.Stream s : f.streams) {
				if (tries++ == MAX_TRIES) break;
				Bitmap bm = grab(s, f);
				if (bm != null) {
					DiagnosticLog.log("ART", "frame taken", videoId, "height=" + s.height,
							bm.getWidth() + "x" + bm.getHeight(), "ms=" + (System.currentTimeMillis() - start));
					return bm;
				}
				DiagnosticLog.log("ART", "frame: stream failed, trying a lower one", videoId, "height=" + s.height);
			}
			return null;
		} catch (IOException | RuntimeException ex) {
			DiagnosticLog.log("ART", "frame failed", videoId, String.valueOf(ex));
			return null;
		}
	}

	/** The frame at the middle of one stream, or null if this device can't decode it. */
	@Nullable
	private static Bitmap grab(YtStreamResolver.Stream s, YtStreamResolver.Frames f) {
		MediaMetadataRetriever mmr = new MediaMetadataRetriever();
		try {
			mmr.setDataSource(s.url, Collections.singletonMap("User-Agent", f.userAgent));
			// The middle in microseconds: durationMs / 2 milliseconds.
			return mmr.getFrameAtTime(f.durationMs * 500L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
		} catch (RuntimeException ex) {
			DiagnosticLog.log("ART", "frame decode error", String.valueOf(ex));
			return null;
		} finally {
			try {
				mmr.release();
			} catch (IOException ex) {
				// Nothing is left to free: the retriever is only dropped.
			}
		}
	}
}
