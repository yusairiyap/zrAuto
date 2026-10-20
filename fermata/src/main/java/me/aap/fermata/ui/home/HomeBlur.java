package me.aap.fermata.ui.home;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

/**
 * A soft blur for the Home tab's pictures, made the way the Music tab does its cover: a small copy
 * (smaller as the blur grows) run through a few box blur passes. Stretched over the screen it is
 * smooth; a plain scaled-down copy is not (it shows as blocks).
 */
public final class HomeBlur {
	private HomeBlur() {
	}

	/**
	 * @param strength 0 (the picture itself) to 100
	 * @return the blurred copy (not the size of {@code src}: draw it stretched), or null if it failed
	 */
	@Nullable
	public static Bitmap blur(Bitmap src, int strength) {
		try {
			if (strength <= 0) return src;
			if (src.getConfig() == Bitmap.Config.HARDWARE) src = src.copy(Bitmap.Config.ARGB_8888, false);
			if ((src == null) || (src.getWidth() <= 0) || (src.getHeight() <= 0)) return null;
			float f = Math.min(100, strength) / 100f;
			// 360 px wide at the lightest, 48 at the heaviest: smaller is cheaper and softer.
			int w = Math.max(24, Math.round(360f * (float) Math.pow(48f / 360f, f)));
			int h = Math.max(1, Math.round(w * (float) src.getHeight() / src.getWidth()));
			int r = 1 + Math.round(3 * f);
			Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
			int[] px = new int[w * h];
			small.getPixels(px, 0, w, 0, 0, w, h);
			int[] tmp = new int[px.length];
			for (int pass = 0; pass < 3; pass++) {
				box(px, tmp, w, h, r, true);
				box(tmp, px, w, h, r, false);
			}
			return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
		} catch (Throwable ex) {
			return null;
		}
	}

	/** One box blur pass along rows or columns, the edges repeated. */
	private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
		int len = horizontal ? w : h;
		int lines = horizontal ? h : w;
		int win = 2 * r + 1;
		for (int line = 0; line < lines; line++) {
			int rs = 0, gs = 0, bs = 0;
			for (int k = -r; k <= r; k++) {
				int c = in[at(line, clamp(k, len), w, horizontal)];
				rs += (c >> 16) & 0xFF;
				gs += (c >> 8) & 0xFF;
				bs += c & 0xFF;
			}
			for (int i = 0; i < len; i++) {
				out[at(line, i, w, horizontal)] = 0xFF000000 | ((rs / win) << 16) | ((gs / win) << 8) | (bs / win);
				int drop = in[at(line, clamp(i - r, len), w, horizontal)];
				int add = in[at(line, clamp(i + r + 1, len), w, horizontal)];
				rs += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
				gs += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
				bs += (add & 0xFF) - (drop & 0xFF);
			}
		}
	}

	private static int at(int line, int i, int w, boolean horizontal) {
		return horizontal ? line * w + i : i * w + line;
	}

	private static int clamp(int i, int len) {
		return Math.max(0, Math.min(len - 1, i));
	}
}
