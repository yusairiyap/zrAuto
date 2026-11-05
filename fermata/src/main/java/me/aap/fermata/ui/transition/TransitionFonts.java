package me.aap.fermata.ui.transition;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

import androidx.annotation.FontRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.res.ResourcesCompat;

import me.aap.fermata.R;

/**
 * The fonts of the transition screen: the first eight are the system's families, the rest are bundled (all open-licensed, in {@code res/font}).
 * The index is saved in the layout, so new fonts only go at the end.
 */
public final class TransitionFonts {
	/** How many system families come first (see {@link TransitionConfig#FONT_FAMILIES}). */
	public static final int SYSTEM = TransitionConfig.FONT_FAMILIES.length;
	@FontRes
	private static final int[] BUNDLED = {R.font.tr_bebas, R.font.tr_pacifico, R.font.tr_orbitron, R.font.tr_marker,
			R.font.tr_righteous, R.font.tr_pixel, R.font.tr_abril, R.font.tr_monoton, R.font.tr_bungee, R.font.tr_cinzel};
	@StringRes
	private static final int[] BUNDLED_NAMES = {R.string.tr_font_bebas, R.string.tr_font_pacifico, R.string.tr_font_orbitron,
			R.string.tr_font_marker, R.string.tr_font_righteous, R.string.tr_font_pixel, R.string.tr_font_abril,
			R.string.tr_font_monoton, R.string.tr_font_bungee, R.string.tr_font_cinzel};
	private static final SparseArray<Typeface> cache = new SparseArray<>();

	private TransitionFonts() {
	}

	public static int count() {
		return SYSTEM + BUNDLED.length;
	}

	/** The name of a bundled font (index at or past {@link #SYSTEM}). */
	@StringRes
	public static int nameOf(int index) {
		return BUNDLED_NAMES[index - SYSTEM];
	}

	@NonNull
	public static Typeface get(Context ctx, int index, boolean bold, boolean italic) {
		int style = (bold ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
		Typeface base;
		if (index < SYSTEM) {
			base = Typeface.create(TransitionConfig.FONT_FAMILIES[Math.max(0, index)], Typeface.NORMAL);
		} else {
			int i = Math.min(index - SYSTEM, BUNDLED.length - 1);
			base = cache.get(i);
			if (base == null) {
				try {
					base = ResourcesCompat.getFont(ctx, BUNDLED[i]);
				} catch (Exception ex) {
					base = null;
				}
				if (base == null) base = Typeface.DEFAULT;
				cache.put(i, base);
			}
		}
		return Typeface.create(base, style);
	}
}
