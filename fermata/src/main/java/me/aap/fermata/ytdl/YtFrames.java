package me.aap.fermata.ytdl;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;

/**
 * A still picture of a YouTube video taken from the video itself, for the album art when High quality is chosen: YouTube's
 * maxresdefault.jpg is often missing (and only 1280 wide), while a frame of the best stream is as sharp as the video.
 * <p>
 * Uses the same streams as the downloads ({@link YtStreamResolver}), but every codec YouTube gives out: the tallest picture
 * up to {@link #MAX_HEIGHT} lines (4K where the video has it, as AV1 in MP4), read over HTTP near the middle of the video. If a
 * stream can't be decoded here, the next one down is tried. Blocks: call it on a background thread.
 */
public final class YtFrames {
	/** The tallest picture asked for: 4K. */
	private static final int MAX_HEIGHT = 2160;
	/** How many streams are tried before giving up on the video (each one lower than the one before). */
	private static final int MAX_TRIES = 3;
	/**
	 * Where the still is picked from, in thousandths of the video's length: the middle and a little either side. The one
	 * with the most contrast wins, so a fade or a plain dark frame is passed over.
	 */
	private static final long[] SHARES = {450, 500, 550};
	/** Mean brightness (0-255) below which a frame is a dark one (a fade, a black title card). */
	private static final double DARK = 30;

	private YtFrames() {
	}

	/**
	 * A still from near the middle of {@code videoId}, or null when no stream or frame can be read (the reason goes to the
	 * Diagnostic log under ART). The still is at the full size of the stream it came from.
	 */
	@Nullable
	public static Bitmap middleFrame(String videoId) {
		long start = System.currentTimeMillis();
		try {
			YtStreamResolver.Frames f = YtStreamResolver.frames(videoId, MAX_HEIGHT);
			DiagnosticLog.log(LogCat.ART, "frame streams listed", "id=" + videoId, "ms=" + (System.currentTimeMillis() - start), "count=" + f.streams.size());
			if ((f.durationMs <= 0) || f.streams.isEmpty()) {
				DiagnosticLog.log(LogCat.ART, "frame: no stream", "id=" + videoId, "height=" + MAX_HEIGHT);
				return null;
			}
			int tries = 0;
			for (YtStreamResolver.Stream s : f.streams) {
				if (tries++ == MAX_TRIES) break;
				Bitmap bm = grab(s, f);
				if (bm != null) {
					DiagnosticLog.log(LogCat.ART, "frame taken", "id=" + videoId, "height=" + s.height, "size=" + bm.getWidth() + "x" + bm.getHeight(), "ms=" + (System.currentTimeMillis() - start));
					return bm;
				}
				DiagnosticLog.w(LogCat.ART, "frame: stream failed, trying a lower one", "id=" + videoId, "height=" + s.height);
			}
			return null;
		} catch (IOException | RuntimeException ex) {
			DiagnosticLog.w(LogCat.ART, "frame failed", "id=" + videoId, ex);
			return null;
		}
	}

	/** The best still of one stream, or null if this device can't decode it. */
	@Nullable
	private static Bitmap grab(YtStreamResolver.Stream s, YtStreamResolver.Frames f) {
		MediaMetadataRetriever mmr = new MediaMetadataRetriever();
		Bitmap best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		try {
			mmr.setDataSource(s.url, Collections.singletonMap("User-Agent", f.userAgent));
			for (long share : SHARES) {
				// Microseconds: milliseconds * 1000, of share / 1000 of the length.
				Bitmap bm = mmr.getFrameAtTime(f.durationMs * share, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
				if (bm == null) continue;
				double score = score(bm);
				DiagnosticLog.log(LogCat.ART, "still candidate", "share=" + share, "score=" + String.format(Locale.ROOT, "%.1f", score));
				if (score > bestScore) {
					if (best != null) best.recycle();
					best = bm;
					bestScore = score;
				} else {
					bm.recycle();
				}
			}
			return best;
		} catch (RuntimeException ex) {
			DiagnosticLog.w(LogCat.ART, "frame decode error", ex);
			return best;
		} finally {
			try {
				mmr.release();
			} catch (IOException ex) {
				// Nothing is left to free: the retriever is only dropped.
			}
		}
	}

	/**
	 * How good a still looks: its brightness spread (contrast) across a small copy, minus a penalty when it is dark. A plain
	 * frame of one colour scores low, a busy one high.
	 */
	private static double score(Bitmap bm) {
		Bitmap small = Bitmap.createScaledBitmap(bm, 64, 36, true);
		double sum = 0;
		double sq = 0;
		int n = 64 * 36;
		for (int y = 0; y < 36; y++) {
			for (int x = 0; x < 64; x++) {
				int p = small.getPixel(x, y);
				double l = 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
				sum += l;
				sq += l * l;
			}
		}
		small.recycle();
		double mean = sum / n;
		double contrast = Math.sqrt(Math.max(0, sq / n - mean * mean));
		return (mean < DARK) ? contrast - 100 : contrast;
	}
}
