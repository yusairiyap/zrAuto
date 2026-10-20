package me.aap.fermata.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;

import androidx.annotation.AttrRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

/** Small drawing helpers shared by the Home tab's classes. */
public final class HomeUi {
	private HomeUi() {
	}

	public static int dp(Context c, float v) {
		return Math.round(v * c.getResources().getDisplayMetrics().density);
	}

	/**
	 * A soft glowing outline round {@code r}: a few strokes, wide and faint to thin and bright, so it
	 * fades out into the page instead of ending in a hard dashed line. {@code strength} 0 to 1.
	 */
	public static void glow(Canvas c, RectF r, float radius, int color, float density, float strength, Paint p) {
		if (strength <= 0.01f) return;
		p.setStyle(Paint.Style.STROKE);
		p.setStrokeCap(Paint.Cap.ROUND);
		p.setStrokeJoin(Paint.Join.ROUND);
		float[] widths = {14f, 10f, 6.5f, 3f, 1.6f};
		float[] alphas = {0.05f, 0.09f, 0.16f, 0.45f, 1f};
		for (int i = 0; i < widths.length; i++) {
			float w = widths[i] * density;
			p.setStrokeWidth(w);
			p.setColor(color);
			p.setAlpha(Math.round(255 * alphas[i] * Math.min(1f, strength)));
			// Each stroke is centred on the edge, the thin bright one exactly on it.
			c.drawRoundRect(r, radius, radius, p);
		}
		p.setStyle(Paint.Style.FILL);
	}

	/** The colour theme attribute {@code attr} resolves to in {@code c}, or 0. */
	public static int attr(Context c, @AttrRes int attr) {
		TypedValue tv = new TypedValue();
		if (c.getTheme().resolveAttribute(attr, tv, true)) {
			if ((tv.type >= TypedValue.TYPE_FIRST_COLOR_INT) && (tv.type <= TypedValue.TYPE_LAST_COLOR_INT)) {
				return tv.data;
			}
			if (tv.resourceId != 0) return ContextCompat.getColor(c, tv.resourceId);
		}
		return 0;
	}

	public static GradientDrawable round(int color, float radiusPx) {
		GradientDrawable g = new GradientDrawable();
		g.setColor(color);
		g.setCornerRadius(radiusPx);
		return g;
	}

	public static GradientDrawable oval(int color) {
		GradientDrawable g = new GradientDrawable();
		g.setShape(GradientDrawable.OVAL);
		g.setColor(color);
		return g;
	}

	/** {@code fill} (may be 0 for none) rounded by {@code radiusPx} with a ripple over it. */
	public static Drawable pressable(int ripple, int fill, float radiusPx) {
		Drawable content = (fill == 0) ? null : round(fill, radiusPx);
		GradientDrawable mask = round(Color.WHITE, radiusPx);
		return new RippleDrawable(ColorStateList.valueOf(ripple), content, mask);
	}

	public static int alpha(int color, float a) {
		return ColorUtils.setAlphaComponent(color, Math.max(0, Math.min(255, Math.round(a * 255f))));
	}

	public static int mix(int a, int b, float t) {
		return ColorUtils.blendARGB(a, b, Math.max(0f, Math.min(1f, t)));
	}

	/** The colour with its brightness scaled: below 1 darker. */
	public static int shade(int color, float f) {
		int r = Math.min(255, Math.round(Color.red(color) * f));
		int g = Math.min(255, Math.round(Color.green(color) * f));
		int b = Math.min(255, Math.round(Color.blue(color) * f));
		return Color.argb(Color.alpha(color), r, g, b);
	}

	public static boolean isLight(int color) {
		return ColorUtils.calculateLuminance(color) > 0.5;
	}
}
