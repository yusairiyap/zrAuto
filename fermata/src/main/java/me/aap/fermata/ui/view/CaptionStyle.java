package me.aap.fermata.ui.view;

import android.graphics.Color;
import android.graphics.Typeface;

import androidx.annotation.StringRes;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityPrefs;

/**
 * How the captions over fullscreen video look (Settings > Captions), read once from the
 * preferences: font, size, bold/italic, text colour and opacity, an edge (none, shadow, outline,
 * glow) with its colour and strength, a box behind the text with its colour and opacity, and where
 * on the picture it sits. The lists here are what the settings page offers, in the order of the
 * stored indexes: new entries only ever go at the end.
 */
public final class CaptionStyle {
	public static final int EDGE_NONE = 0;
	public static final int EDGE_SHADOW = 1;
	public static final int EDGE_OUTLINE = 2;
	public static final int EDGE_GLOW = 3;

	public static final int POS_BOTTOM = 0;
	public static final int POS_MIDDLE = 1;
	public static final int POS_TOP = 2;

	/** Settings > Captions > Font. */
	@StringRes
	public static final int[] FONT_NAMES = {R.string.caption_font_sans, R.string.caption_font_serif,
			R.string.caption_font_mono, R.string.caption_font_casual, R.string.caption_font_cursive,
			R.string.caption_font_condensed, R.string.caption_font_smallcaps};
	private static final String[] FONT_FAMILIES = {"sans-serif", "serif", "monospace",
			"casual", "cursive", "sans-serif-condensed", "sans-serif-smallcaps"};

	/** Text, edge and box colours, by the stored index. */
	@StringRes
	public static final int[] COLOR_NAMES = {R.string.caption_color_white, R.string.caption_color_yellow,
			R.string.caption_color_cyan, R.string.caption_color_green, R.string.caption_color_pink,
			R.string.caption_color_orange, R.string.caption_color_blue, R.string.caption_color_black,
			R.string.caption_color_gray, R.string.caption_color_red};
	public static final int[] COLORS = {Color.WHITE, 0xFFFFEB3B, 0xFF00E5FF, 0xFF69F0AE, 0xFFFF80AB,
			0xFFFFAB40, 0xFF82B1FF, Color.BLACK, 0xFF424242, 0xFFFF5252};

	@StringRes
	public static final int[] EDGE_NAMES = {R.string.caption_edge_none, R.string.caption_edge_shadow,
			R.string.caption_edge_outline, R.string.caption_edge_glow};

	@StringRes
	public static final int[] POSITION_NAMES = {R.string.caption_pos_bottom, R.string.caption_pos_middle,
			R.string.caption_pos_top};

	public final Typeface typeface;
	/** 0.5 .. 3: the text's size relative to the default (a share of the picture's height). */
	public final float size;
	public final int textColor;
	public final int edge;
	public final int edgeColor;
	/** 0 .. 1 */
	public final float edgeStrength;
	/** ARGB: the box's colour with its opacity, 0 alpha for none. */
	public final int boxColor;
	/** The box is as wide as the longest line of text, not the whole text area. */
	public final boolean boxFit;
	public final int position;
	/** How far from the top or bottom edge, as a share (0 .. 0.4) of the picture's height. */
	public final float margin;

	private CaptionStyle(Typeface typeface, float size, int textColor, int edge, int edgeColor,
											 float edgeStrength, int boxColor, boolean boxFit, int position, float margin) {
		this.typeface = typeface;
		this.size = size;
		this.textColor = textColor;
		this.edge = edge;
		this.edgeColor = edgeColor;
		this.edgeStrength = edgeStrength;
		this.boxColor = boxColor;
		this.boxFit = boxFit;
		this.position = position;
		this.margin = margin;
	}

	/** The typeface of a font index of {@link #FONT_NAMES} with the given emphasis. */
	public static Typeface typeface(int font, boolean bold, boolean italic) {
		int style = (bold ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
		return Typeface.create(FONT_FAMILIES[clamp(font, 0, FONT_FAMILIES.length - 1)], style);
	}

	public static CaptionStyle read(MainActivityPrefs p) {
		int font = clamp(p.getIntPref(MainActivityPrefs.CAPTION_FONT), 0, FONT_FAMILIES.length - 1);
		boolean bold = p.getBooleanPref(MainActivityPrefs.CAPTION_BOLD);
		boolean italic = p.getBooleanPref(MainActivityPrefs.CAPTION_ITALIC);
		int style = (bold ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
		Typeface tf = Typeface.create(FONT_FAMILIES[font], style);
		float size = clamp(p.getIntPref(MainActivityPrefs.CAPTION_SIZE), 50, 300) / 100f;
		int text = withOpacity(color(p.getIntPref(MainActivityPrefs.CAPTION_COLOR)),
				p.getIntPref(MainActivityPrefs.CAPTION_OPACITY));
		int edge = clamp(p.getIntPref(MainActivityPrefs.CAPTION_EDGE), 0, 3);
		int edgeColor = color(p.getIntPref(MainActivityPrefs.CAPTION_EDGE_COLOR));
		float strength = clamp(p.getIntPref(MainActivityPrefs.CAPTION_EDGE_STRENGTH), 0, 100) / 100f;
		int box = withOpacity(color(p.getIntPref(MainActivityPrefs.CAPTION_BG_COLOR)),
				p.getIntPref(MainActivityPrefs.CAPTION_BG_OPACITY));
		boolean fit = p.getBooleanPref(MainActivityPrefs.CAPTION_BOX_FIT);
		int pos = clamp(p.getIntPref(MainActivityPrefs.CAPTION_POSITION), 0, 2);
		float margin = clamp(p.getIntPref(MainActivityPrefs.CAPTION_MARGIN), 0, 40) / 100f;
		return new CaptionStyle(tf, size, text, edge, edgeColor, strength, box, fit, pos, margin);
	}

	private static int color(int i) {
		return COLORS[clamp(i, 0, COLORS.length - 1)];
	}

	private static int withOpacity(int rgb, int percent) {
		int a = Math.round(255 * clamp(percent, 0, 100) / 100f);
		return (rgb & 0x00FFFFFF) | (a << 24);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
