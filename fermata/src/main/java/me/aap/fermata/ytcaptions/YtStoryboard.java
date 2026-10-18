package me.aap.fermata.ytcaptions;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A YouTube video's storyboard: small pictures every few seconds, laid out in a grid on sheets
 * (JPEGs of about 20 to 40 KB), what YouTube's own seek bar shows while dragging. Parsed from the
 * player's {@code storyboards.playerStoryboardSpecRenderer.spec}:
 * <pre>base URL with $L and $N | w#h#count#cols#rows#interval ms#name#sigh | ...</pre>
 * one part per level of detail; level {@code L} is the part's index after the URL, and a sheet's
 * name replaces {@code $M} with its number. Only the sheets a seek actually reaches are loaded.
 */
public final class YtStoryboard {
	private final String base;
	private final List<Level> levels;

	public static final class Level {
		final int index;
		public final int width;
		public final int height;
		final int count;
		final int cols;
		final int rows;
		final long interval;
		final String name;
		final String sigh;

		Level(int index, int width, int height, int count, int cols, int rows, long interval,
					String name, String sigh) {
			this.index = index;
			this.width = width;
			this.height = height;
			this.count = count;
			this.cols = cols;
			this.rows = rows;
			this.interval = interval;
			this.name = name;
			this.sigh = sigh;
		}
	}

	/** One picture: the sheet it is on and where on it. */
	public static final class Frame {
		public final String url;
		final int col;
		final int row;
		final Level level;

		Frame(String url, int col, int row, Level level) {
			this.url = url;
			this.col = col;
			this.row = row;
			this.level = level;
		}

		/** The picture cut out of its loaded sheet, or null if the sheet is not as expected. */
		@Nullable
		public Bitmap crop(@Nullable Bitmap sheet) {
			if (sheet == null) return null;
			int w = sheet.getWidth() / Math.max(1, level.cols);
			int h = Math.min(level.height, sheet.getHeight() / Math.max(1, level.rows));
			if ((w <= 0) || (h <= 0)) return null;
			int x = col * w;
			int y = row * h;
			if ((x + w > sheet.getWidth()) || (y + h > sheet.getHeight())) return null;
			return Bitmap.createBitmap(sheet, x, y, w, h);
		}
	}

	private YtStoryboard(String base, List<Level> levels) {
		this.base = base;
		this.levels = levels;
	}

	/** Loads the video's storyboard; network, not on the main thread. Null when it has none. */
	@Nullable
	public static YtStoryboard load(String videoId) {
		String spec = YtCaptions.storyboardSpec(videoId);
		return (spec == null) ? null : parse(spec);
	}

	@Nullable
	static YtStoryboard parse(String spec) {
		String[] parts = spec.split("\\|");
		if (parts.length < 2) return null;
		List<Level> levels = new ArrayList<>(parts.length - 1);
		for (int i = 1; i < parts.length; i++) {
			String[] f = parts[i].split("#");
			if (f.length < 8) continue;
			try {
				levels.add(new Level(i - 1, Integer.parseInt(f[0]), Integer.parseInt(f[1]),
						Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]),
						Long.parseLong(f[5]), f[6], f[7]));
			} catch (NumberFormatException ignore) {
				// A part in a format not known yet: left out.
			}
		}
		return levels.isEmpty() ? null : new YtStoryboard(parts[0], levels);
	}

	/**
	 * The level to use: the largest pictures no wider than {@code maxWidth} that come on sheets
	 * (the first level is a single strip of tiny ones).
	 */
	@Nullable
	public Level pick(int maxWidth) {
		Level best = null;
		for (Level l : levels) {
			if ((l.cols * l.rows <= 1) || (l.width > maxWidth)) continue;
			if ((best == null) || (l.width > best.width)) best = l;
		}
		if (best == null) for (Level l : levels) if ((best == null) || (l.width < best.width)) best = l;
		return best;
	}

	/** The picture shown at {@code ms} into a video {@code durationMs} long. */
	@Nullable
	public Frame frameAt(Level l, long ms, long durationMs) {
		if (l.count <= 0) return null;
		int idx;
		if (l.interval > 0) idx = (int) (ms / l.interval);
		else if (durationMs > 0) idx = (int) (ms * l.count / durationMs);
		else return null;
		idx = Math.max(0, Math.min(l.count - 1, idx));
		int perSheet = Math.max(1, l.cols * l.rows);
		int sheet = idx / perSheet;
		int in = idx % perSheet;
		String name = l.name.replace("$M", String.valueOf(sheet));
		String url = base.replace("$L", String.valueOf(l.index)).replace("$N", name);
		url += ((url.indexOf('?') < 0) ? "?" : "&") + "sigh=" + l.sigh;
		return new Frame(url, in % l.cols, in / l.cols, l);
	}
}
