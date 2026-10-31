package me.aap.fermata.ui.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.ContextThemeWrapper;
import android.view.View;

import androidx.annotation.StyleRes;

/**
 * A small phone drawn in code from a theme's own colours, for the theme gallery (Settings &gt; Appearance &gt; Theme):
 * status bar, a tool bar pill, a list row, a cover card, a nav pill and one accent button. It reads the colours by
 * attribute name from the theme, so a theme added later shows up with no change here.
 */
public final class ThemePreviewView extends View {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF r = new RectF();
	private final float d;
	private int background = 0xFF101010;
	private int bars = 0xFF202020;
	private int accent = 0xFF8AB4F8;
	private int text = 0xFFFFFFFF;
	private int item = 0xFF181818;

	public ThemePreviewView(Context ctx, @StyleRes int theme) {
		super(ctx);
		d = ctx.getResources().getDisplayMetrics().density;
		Context t = new ContextThemeWrapper(ctx, theme);
		background = color(t, android.R.attr.colorBackground, background);
		bars = color(t, named(t, "colorPrimary"), bars);
		accent = color(t, named(t, "colorOnPrimary"), accent);
		text = color(t, android.R.attr.textColorSecondary, text);
		item = color(t, named(t, "colorSecondary"), item);
		// A bar the same colour as the page would vanish: lift it a little.
		if (bars == background) bars = androidx.core.graphics.ColorUtils.blendARGB(background, accent, 0.12f);
		if (item == background) item = androidx.core.graphics.ColorUtils.blendARGB(background, accent, 0.07f);
	}

	/** An app attribute by name (which library declares it does not matter here). */
	private static int named(Context ctx, String name) {
		return ctx.getResources().getIdentifier(name, "attr", ctx.getPackageName());
	}

	private static int color(Context ctx, int attr, int fallback) {
		if (attr == 0) return fallback;
		try (TypedArray a = ctx.obtainStyledAttributes(new int[]{attr})) {
			int c = a.getColor(0, fallback);
			return c | 0xFF000000;
		} catch (RuntimeException ex) {
			return fallback;
		}
	}

	@Override
	protected void onDraw(Canvas c) {
		float w = getWidth();
		float h = getHeight();
		if ((w <= 0) || (h <= 0)) return;
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(background);
		r.set(0, 0, w, h);
		c.drawRoundRect(r, 10 * d, 10 * d, paint);
		float p = 8 * d;
		// Tool bar pill.
		paint.setColor(bars);
		r.set(p, 10 * d, w - p, 10 * d + 14 * d);
		c.drawRoundRect(r, 7 * d, 7 * d, paint);
		paint.setColor(text);
		r.set(p + 6 * d, 15 * d, p + 30 * d, 19 * d);
		c.drawRoundRect(r, 2 * d, 2 * d, paint);
		// Two list rows, a cover card and an accent button.
		float y = 32 * d;
		for (int i = 0; i < 2; i++) {
			paint.setColor(item);
			r.set(p, y, w - p, y + 18 * d);
			c.drawRoundRect(r, 6 * d, 6 * d, paint);
			paint.setColor(accent);
			r.set(p + 4 * d, y + 4 * d, p + 14 * d, y + 14 * d);
			c.drawRoundRect(r, 3 * d, 3 * d, paint);
			paint.setColor(text);
			r.set(p + 20 * d, y + 5 * d, w - p - 10 * d, y + 8 * d);
			c.drawRoundRect(r, 1.5f * d, 1.5f * d, paint);
			paint.setAlpha(110);
			r.set(p + 20 * d, y + 11 * d, w * 0.6f, y + 13 * d);
			c.drawRoundRect(r, 1 * d, 1 * d, paint);
			paint.setAlpha(255);
			y += 22 * d;
		}
		paint.setColor(item);
		r.set(p, y, w - p, y + 26 * d);
		c.drawRoundRect(r, 8 * d, 8 * d, paint);
		paint.setColor(accent);
		r.set(w - p - 30 * d, y + 7 * d, w - p - 6 * d, y + 19 * d);
		c.drawRoundRect(r, 6 * d, 6 * d, paint);
		// Nav pill.
		paint.setColor(bars);
		r.set(p + 6 * d, h - 22 * d, w - p - 6 * d, h - 8 * d);
		c.drawRoundRect(r, 7 * d, 7 * d, paint);
		paint.setColor(accent);
		c.drawCircle(w / 2f, h - 15 * d, 3 * d, paint);
	}
}
