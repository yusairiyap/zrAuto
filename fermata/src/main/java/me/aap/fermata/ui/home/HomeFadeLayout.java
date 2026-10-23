package me.aap.fermata.ui.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

/**
 * Holds the page's scroller and lets its content melt away at the edges it can still scroll to
 * (never at an end it has reached), so widgets don't end in a hard line under the status bar or run
 * into the navigation pill: the background shows through instead. Vertical pages fade at the top
 * and bottom, sideways pages at the left and right. The part of the page that sits under the
 * navigation pill (the scroller's padding) is cleared with it.
 */
public final class HomeFadeLayout extends FrameLayout {
	private final Paint paint = new Paint();
	private final int length;
	private final int reach;

	public HomeFadeLayout(Context ctx) {
		super(ctx);
		paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
		length = HomeUi.dp(ctx, 30);
		reach = HomeUi.dp(ctx, 26);
		setClipChildren(false);
		setClipToPadding(false);
	}

	/** The scroller to follow: redrawn as it scrolls ({@code onScrolled}, if any, also runs). */
	public void follow(ViewGroup scroller, @Nullable Runnable onScrolled) {
		scroller.setOnScrollChangeListener((v, x, y, ox, oy) -> {
			invalidate();
			if (onScrolled != null) onScrolled.run();
		});
	}

	private static float strength(float distance, float reach) {
		return Math.max(0f, Math.min(1f, distance / reach));
	}

	@Override
	protected void dispatchDraw(Canvas c) {
		View child = (getChildCount() > 0) ? getChildAt(0) : null;
		float top = 0, bottom = 0, left = 0, right = 0;
		int padB = 0, padR = 0;
		if (child instanceof ScrollView sv && sv.getChildCount() > 0) {
			int range = sv.getChildAt(0).getHeight() + sv.getPaddingTop() + sv.getPaddingBottom() - sv.getHeight();
			if (range > 0) {
				top = strength(sv.getScrollY(), reach);
				bottom = strength(range - sv.getScrollY(), reach);
			}
			padB = sv.getPaddingBottom();
		} else if (child instanceof HorizontalScrollView hv && hv.getChildCount() > 0) {
			int range = hv.getChildAt(0).getWidth() + hv.getPaddingLeft() + hv.getPaddingRight() - hv.getWidth();
			if (range > 0) {
				left = strength(hv.getScrollX(), reach);
				right = strength(range - hv.getScrollX(), reach);
			}
			padR = hv.getPaddingRight();
		}
		int w = getWidth();
		int h = getHeight();
		if (((top + bottom + left + right) == 0f) || (w == 0) || (h == 0)) {
			super.dispatchDraw(c);
			return;
		}
		int layer = c.saveLayer(0, 0, w, h, null);
		super.dispatchDraw(c);
		if (top > 0f) band(c, 0, 0, w, length, true, 0, length, top, 1f, 0f);
		if (bottom > 0f) {
			int y0 = h - padB - length;
			band(c, 0, y0, w, h, true, y0, y0 + length, bottom, 0f, 1f);
		}
		if (left > 0f) band(c, 0, 0, length, h, false, 0, length, left, 1f, 0f);
		if (right > 0f) {
			int x0 = w - padR - length;
			band(c, x0, 0, w, h, false, x0, x0 + length, right, 0f, 1f);
		}
		c.restoreToCount(layer);
	}

	/**
	 * Clears a band of the content: the amount runs from {@code a0} to {@code a1} (0 to 1, times
	 * {@code s}) between {@code from} and {@code to} along one axis, and stays at the last value past it.
	 */
	private void band(Canvas c, int l, int t, int r, int b, boolean alongY, float from, float to, float s,
										float a0, float a1) {
		int c0 = Math.round(255 * a0 * s) << 24;
		int c1 = Math.round(255 * a1 * s) << 24;
		paint.setShader(alongY ? new LinearGradient(0, from, 0, to, c0, c1, Shader.TileMode.CLAMP) :
				new LinearGradient(from, 0, to, 0, c0, c1, Shader.TileMode.CLAMP));
		c.drawRect(l, t, r, b, paint);
		paint.setShader(null);
	}
}
