package me.aap.fermata.ui.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.ui.view.Glass;
import me.aap.fermata.ui.view.GlassLens;

/**
 * The fill of something drawn inside Home over its background (the edit button and the chip bar, a widget's own
 * settings panel): Home's background blurred behind it, as the glass cards have it, when the UI style puts glass on
 * Home; else the plain rounded fill it always had. It cannot blur the tab itself (it is part of it: a RenderNode
 * cycle, see GlassLens), so it blurs Home's background layers, like the cards. Drawn again when it moves.
 */
final class HomeGlassFill extends Drawable {
	private final View view;
	private final HomeHost host;
	private final int color;
	private final float radius;
	private final boolean light;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final GlassLens[] lens = new GlassLens[1];
	private final int[] loc = new int[2];
	private long lastLoc = Long.MIN_VALUE;
	private int alpha = 255;

	private final ViewTreeObserver.OnPreDrawListener moved = new ViewTreeObserver.OnPreDrawListener() {
		@Override
		public boolean onPreDraw() {
			if (!Glass.onHome() || !Glass.realBlur() || !view.isShown()) return true;
			view.getLocationInWindow(loc);
			long key = (((long) loc[0]) << 32) ^ (loc[1] & 0xFFFFFFFFL);
			if (key != lastLoc) {
				lastLoc = key;
				invalidateSelf();
			}
			return true;
		}
	};

	HomeGlassFill(View view, HomeHost host, int color, float radius) {
		this.view = view;
		this.host = host;
		this.color = color;
		this.radius = radius;
		light = host.palette().light;
		view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override
			public void onViewAttachedToWindow(@NonNull View v) {
				v.getViewTreeObserver().addOnPreDrawListener(moved);
			}

			@Override
			public void onViewDetachedFromWindow(@NonNull View v) {
				v.getViewTreeObserver().removeOnPreDrawListener(moved);
			}
		});
		if (view.isAttachedToWindow()) view.getViewTreeObserver().addOnPreDrawListener(moved);
	}

	@Override
	public void draw(@NonNull Canvas c) {
		rect.set(getBounds());
		if (rect.isEmpty()) return;
		float a = alpha / 255f;
		if (!Glass.onHome()) {
			paint.setShader(null);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(color);
			paint.setAlpha(Math.round((color >>> 24) * a));
			c.drawRoundRect(rect, radius, radius, paint);
			return;
		}
		// Text and buttons sit on it: never thinner than 55% over a blur, 80% with nothing blurred under it.
		Glass.Look l = Glass.look().withMinTint(55f);
		boolean blurred = host.drawGlassBehind(view, c, rect, radius, a, l, lens);
		if (!blurred) l = l.withMinTint(80f);
		Glass.paintOver(c, rect, radius, color, a, l.rimFor(Glass.RIM_HOME), light,
				view.getResources().getDisplayMetrics().density, paint, blurred);
	}

	@Override
	public void setAlpha(int alpha) {
		this.alpha = alpha;
		invalidateSelf();
	}

	@Override
	public void setColorFilter(@Nullable ColorFilter colorFilter) {
		paint.setColorFilter(colorFilter);
	}

	@Override
	public int getOpacity() {
		return PixelFormat.TRANSLUCENT;
	}

	@Override
	public void getOutline(@NonNull Outline outline) {
		outline.setRoundRect(getBounds(), radius);
	}
}
