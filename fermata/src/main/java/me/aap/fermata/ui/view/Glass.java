package me.aap.fermata.ui.view;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Random;

import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.pref.PreferenceStore;

/**
 * The UI style (Settings > Interface > UI style): the usual bars and cards, or glass. With glass, the
 * tool bar, the nav bar / control panel pill and Home's Glass cards show what is behind them blurred
 * (real time, Android 12+), under a tint of the bar's own colour, a little saturation, a faint grain
 * and a light rim along the top edge, the way frosted glass catches light.
 * <p>
 * How it is drawn: {@code BodyLayout} records the tab's content into a RenderNode once per frame
 * (the same display list the screen draws, so nothing is drawn twice on the UI thread), and each
 * glass surface draws that node through a {@link GlassLens}: its own small RenderNode with a blur
 * RenderEffect, as big as the surface (plus a margin for the blur's edge), clipped to its shape. The
 * blur runs on the GPU over that area only, which is what makes it cheap enough to follow scrolling.
 * Below Android 12 there is no RenderEffect (RenderScript is deprecated and slow), so the glass is the
 * tint, grain and rim over the content without the blur.
 * <p>
 * A web page (the YouTube and browser tabs) is never blurred: a WebView draws through a functor,
 * which must not be drawn a second time in the frame; the bars there get the tint only.
 */
public final class Glass {
	public static final int NORMAL = 0;
	public static final int FROSTED = 1;
	public static final int CLEAR = 2;
	public static final int ACRYLIC = 3;
	public static final int STYLE_COUNT = 4;

	/** Each style's blur (dp), tint (%), saturation (%), grain and rim (1 = on). NORMAL has none. */
	private static final int[][] PRESETS = {
			null,
			{22, 55, 140, 1, 1},
			{10, 22, 125, 0, 1},
			{34, 74, 110, 1, 0},
	};

	/** What a glass surface is drawn with: read from the prefs, kept until one of them changes. */
	public static final class Look {
		public final int style;
		public final float blurDp;
		public final int tint;
		public final float saturation;
		public final boolean grain;
		public final boolean rim;
		public final boolean bars;
		public final boolean home;

		Look(int style, float blurDp, int tint, float saturation, boolean grain, boolean rim, boolean bars,
				 boolean home) {
			this.style = style;
			this.blurDp = blurDp;
			this.tint = tint;
			this.saturation = saturation;
			this.grain = grain;
			this.rim = rim;
			this.bars = bars;
			this.home = home;
		}

		public boolean isGlass() {
			return style != NORMAL;
		}

		/** The tint's alpha, 0 to 255. */
		public int tintAlpha() {
			return Math.round(Math.max(0, Math.min(100, tint)) * 2.55f);
		}
	}

	@Nullable
	private static Look look;
	@Nullable
	private static PreferenceStore.Listener listener;
	@Nullable
	private static Bitmap grainTile;

	private Glass() {
	}

	/** Whether the blur itself can be drawn (RenderEffect, Android 12+); below, glass is the tint only. */
	@androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
	public static boolean realBlur() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
	}

	public static Look look() {
		Look l = look;
		if (l != null) return l;
		MainActivityPrefs p = MainActivityPrefs.get();
		if (listener == null) {
			// Held here: the store keeps its listeners weakly.
			listener = (store, prefs) -> {
				if (touches(prefs)) look = null;
			};
			p.addBroadcastListener(listener);
		}
		int style = Math.max(0, Math.min(STYLE_COUNT - 1, p.getIntPref(MainActivityPrefs.GLASS_STYLE)));
		l = new Look(style, Math.max(0, p.getIntPref(MainActivityPrefs.GLASS_BLUR)),
				p.getIntPref(MainActivityPrefs.GLASS_TINT), p.getIntPref(MainActivityPrefs.GLASS_SATURATION) / 100f,
				p.getBooleanPref(MainActivityPrefs.GLASS_GRAIN), p.getBooleanPref(MainActivityPrefs.GLASS_RIM),
				p.getBooleanPref(MainActivityPrefs.GLASS_BARS), p.getBooleanPref(MainActivityPrefs.GLASS_HOME));
		look = l;
		return l;
	}

	/** Forgets the look read: the next {@link #look()} reads the prefs again. */
	public static void reset() {
		look = null;
	}

	/** Whether a change of {@code prefs} changes the look. */
	public static boolean touches(List<PreferenceStore.Pref<?>> prefs) {
		return prefs.contains(MainActivityPrefs.GLASS_STYLE) || prefs.contains(MainActivityPrefs.GLASS_BLUR) ||
				prefs.contains(MainActivityPrefs.GLASS_TINT) || prefs.contains(MainActivityPrefs.GLASS_SATURATION) ||
				prefs.contains(MainActivityPrefs.GLASS_GRAIN) || prefs.contains(MainActivityPrefs.GLASS_RIM) ||
				prefs.contains(MainActivityPrefs.GLASS_BARS) || prefs.contains(MainActivityPrefs.GLASS_HOME);
	}

	/** Glass on the tool bar, nav bar and control panel. */
	public static boolean onBars() {
		Look l = look();
		return l.isGlass() && l.bars;
	}

	/** Glass under Home's cards that have the Glass look. */
	public static boolean onHome() {
		Look l = look();
		return l.isGlass() && l.home;
	}

	/** Picks a style: its values are written into the prefs (which can then be tuned one by one). */
	public static void applyStyle(MainActivityPrefs p, int style) {
		try (var e = p.editPreferenceStore()) {
			e.setIntPref(MainActivityPrefs.GLASS_STYLE, style);
			int[] v = (style > 0) && (style < PRESETS.length) ? PRESETS[style] : null;
			if (v != null) {
				e.setIntPref(MainActivityPrefs.GLASS_BLUR, v[0]);
				e.setIntPref(MainActivityPrefs.GLASS_TINT, v[1]);
				e.setIntPref(MainActivityPrefs.GLASS_SATURATION, v[2]);
				e.setBooleanPref(MainActivityPrefs.GLASS_GRAIN, v[3] != 0);
				e.setBooleanPref(MainActivityPrefs.GLASS_RIM, v[4] != 0);
			}
		}
	}

	/** Whether the values now set are the style's own (else Settings says "Tuned"). */
	public static boolean isPresetValues(MainActivityPrefs p) {
		int style = p.getIntPref(MainActivityPrefs.GLASS_STYLE);
		int[] v = (style > 0) && (style < PRESETS.length) ? PRESETS[style] : null;
		if (v == null) return true;
		return (p.getIntPref(MainActivityPrefs.GLASS_BLUR) == v[0]) && (p.getIntPref(MainActivityPrefs.GLASS_TINT) == v[1]) &&
				(p.getIntPref(MainActivityPrefs.GLASS_SATURATION) == v[2]) &&
				(p.getBooleanPref(MainActivityPrefs.GLASS_GRAIN) == (v[3] != 0)) &&
				(p.getBooleanPref(MainActivityPrefs.GLASS_RIM) == (v[4] != 0));
	}

	/** A preset's look, for the style previews (bars and Home on, as they would be). */
	public static Look preset(int style) {
		int[] v = (style > 0) && (style < PRESETS.length) ? PRESETS[style] : null;
		if (v == null) return new Look(NORMAL, 0, 0, 1f, false, false, true, true);
		return new Look(style, v[0], v[1], v[2] / 100f, v[3] != 0, v[4] != 0, true, true);
	}

	// ---------------------------------------------------------------------------------------------
	// Painting

	/**
	 * A glass surface's tint, grain and rim over whatever was drawn under {@code r} (the blur through a
	 * {@link GlassLens}, or nothing below Android 12). {@code rgb} is the surface's own colour (its alpha is
	 * ignored), {@code alpha} how much of the surface shows (a fading bar), {@code light} a light theme
	 * (the rim and grain are then darker and fainter).
	 */
	public static void paintOver(Canvas c, RectF r, float radius, int rgb, float alpha, Look l, boolean light,
															 float density, Paint paint) {
		if (r.isEmpty() || (alpha <= 0f)) return;
		paint.setShader(null);
		paint.setStyle(Paint.Style.FILL);
		paint.clearShadowLayer();
		// Below Android 12 the tint alone has to keep text readable: never thinner than 45%.
		int ta = realBlur() ? l.tintAlpha() : Math.max(115, l.tintAlpha());
		paint.setColor((rgb & 0x00FFFFFF) | (Math.round(ta * alpha) << 24));
		c.drawRoundRect(r, radius, radius, paint);
		if (l.grain) {
			paint.setColor(0xFFFFFFFF);
			paint.setShader(grainShader());
			paint.setAlpha(Math.round((light ? 22 : 30) * alpha));
			c.drawRoundRect(r, radius, radius, paint);
			paint.setShader(null);
		}
		if (l.rim) {
			float sw = Math.max(1f, density);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(sw);
			int top = light ? 0x99FFFFFF : 0x59FFFFFF;
			int bottom = light ? 0x14000000 : 0x0DFFFFFF;
			paint.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, bottom, Shader.TileMode.CLAMP));
			paint.setAlpha(Math.round(255 * alpha));
			float h = sw / 2f;
			c.drawRoundRect(r.left + h, r.top + h, r.right - h, r.bottom - h, Math.max(0f, radius - h),
					Math.max(0f, radius - h), paint);
			paint.setShader(null);
			paint.setStyle(Paint.Style.FILL);
		}
	}

	/** A 96 px tile of soft noise, repeated: the frosted look's grain. Made once. */
	private static Shader grainShader() {
		Bitmap t = grainTile;
		if (t == null) {
			int n = 96;
			int[] px = new int[n * n];
			Random rnd = new Random(7);
			for (int i = 0; i < px.length; i++) {
				int v = rnd.nextInt(256);
				// Light and dark specks around grey, most of them faint.
				int a = (int) (Math.abs(rnd.nextGaussian()) * 60);
				px[i] = (Math.min(255, a) << 24) | (v << 16) | (v << 8) | v;
			}
			t = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
			grainTile = t;
		}
		return new BitmapShader(t, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
	}

	/**
	 * Whether {@code v} holds a WebView that is on screen: such content is never drawn through a lens
	 * (see the class comment).
	 */
	public static boolean hasWebView(@Nullable View v) {
		if ((v == null) || (v.getVisibility() != View.VISIBLE)) return false;
		if (v instanceof android.webkit.WebView) return v.getWidth() > 0;
		if (v instanceof android.view.ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) {
				if (hasWebView(g.getChildAt(i))) return true;
			}
		}
		return false;
	}
}
