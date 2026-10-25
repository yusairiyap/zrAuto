package me.aap.fermata.ui.view;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;

/**
 * "Hide cover" (the COVER_HIDE action, MainActivityPrefs#COVER_HIDDEN): the Music tab's cover, Home's
 * media card and its now-playing background are blurred so far that only the cover's colours are
 * left, nothing of the picture itself. The blur is done once per cover on a tiny copy (a few pixels
 * across), so it costs next to nothing; stretched back up it is a soft wash of colour.
 */
public final class CoverHide {
	private static WeakReference<Bitmap> lastSrc = new WeakReference<>(null);
	@Nullable
	private static Bitmap lastVeil;

	private CoverHide() {
	}

	public static boolean isOn() {
		return MainActivityPrefs.get().getBooleanPref(MainActivityPrefs.COVER_HIDDEN);
	}

	/** The action: on / off, said in the top popup. */
	public static void toggle(MainActivityDelegate a) {
		toggle(a, true);
	}

	/** {@code say}: the top popup says so (the action); the eye buttons don't, their icon shows it. */
	public static void toggle(MainActivityDelegate a, boolean say) {
		boolean on = !isOn();
		a.getPrefs().applyBooleanPref(MainActivityPrefs.COVER_HIDDEN, on);
		if (!say) return;
		TopToast.show(on ? R.drawable.cover_hidden : R.drawable.visibility,
				on ? R.string.cover_hidden_on : R.string.cover_hidden_off);
	}

	/** {@code src} when covers are shown, else its colours only (see {@link #veil}). */
	@Nullable
	public static Bitmap apply(@Nullable Bitmap src) {
		return ((src == null) || !isOn()) ? src : veil(src);
	}

	/** {@link #apply} for small pictures shown many at once (list rows): not remembered. */
	@Nullable
	public static Bitmap applyToThumb(@Nullable Bitmap src) {
		if ((src == null) || !isOn()) return src;
		Bitmap v = make(src);
		return (v != null) ? v : src;
	}

	/**
	 * The cover blurred to its colours: 6 pixels across (the long side), box blurred three times over
	 * that. The last one is remembered, since the same cover is asked for again and again (a seek, a
	 * tab switch). Falls back to {@code src} itself only if the copy can't be made at all.
	 */
	public static synchronized Bitmap veil(Bitmap src) {
		if ((lastSrc.get() == src) && (lastVeil != null)) return lastVeil;
		Bitmap v = make(src);
		if (v == null) return src;
		lastSrc = new WeakReference<>(src);
		lastVeil = v;
		return v;
	}

	@Nullable
	private static Bitmap make(Bitmap src) {
		try {
			if (src.getConfig() == Bitmap.Config.HARDWARE) src = src.copy(Bitmap.Config.ARGB_8888, false);
			if ((src == null) || (src.getWidth() <= 0) || (src.getHeight() <= 0)) return null;
			int w, h;
			if (src.getWidth() >= src.getHeight()) {
				w = 6;
				h = Math.max(2, Math.round(6f * src.getHeight() / src.getWidth()));
			} else {
				h = 6;
				w = Math.max(2, Math.round(6f * src.getWidth() / src.getHeight()));
			}
			// Down in two steps: one straight to 6 px samples only a few pixels of the cover.
			Bitmap mid = Bitmap.createScaledBitmap(src, w * 8, h * 8, true);
			Bitmap small = Bitmap.createScaledBitmap(mid, w, h, true);
			int[] px = new int[w * h];
			small.getPixels(px, 0, w, 0, 0, w, h);
			int[] tmp = new int[px.length];
			for (int pass = 0; pass < 3; pass++) {
				box(px, tmp, w, h, true);
				box(tmp, px, w, h, false);
			}
			return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
		} catch (Throwable ex) {
			return null;
		}
	}

	/** A box blur of radius 1 along rows or columns, the edges repeated. */
	private static void box(int[] in, int[] out, int w, int h, boolean horizontal) {
		int len = horizontal ? w : h;
		int lines = horizontal ? h : w;
		for (int line = 0; line < lines; line++) {
			for (int i = 0; i < len; i++) {
				int rs = 0, gs = 0, bs = 0;
				for (int k = -1; k <= 1; k++) {
					int j = Math.min(len - 1, Math.max(0, i + k));
					int c = horizontal ? in[line * w + j] : in[j * w + line];
					rs += (c >> 16) & 0xFF;
					gs += (c >> 8) & 0xFF;
					bs += c & 0xFF;
				}
				int idx = horizontal ? (line * w + i) : (i * w + line);
				out[idx] = 0xFF000000 | ((rs / 3) << 16) | ((gs / 3) << 8) | (bs / 3);
			}
		}
	}
}
