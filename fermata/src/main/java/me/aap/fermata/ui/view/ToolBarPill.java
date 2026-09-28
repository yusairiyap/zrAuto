package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toPx;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * tool_bar's background: the same floating pill as the nav bar and control panel (see
 * {@link FloatingBarsView}), in the same colour, with a soft shadow from its outline. Drawn by the
 * tool bar itself, so its show/hide fade and slide, and any tab's changes to its content, carry
 * the pill along with no extra syncing.
 * <p>
 * The pill spans the tool bar's width less its horizontal padding beyond {@code inner}: the tool
 * bar keeps {@code inner} of padding inside the pill's rounded ends, and any padding on top of
 * that (clearing a side nav bar, see MainActivityDelegate#syncToolBarInset) is left outside it.
 */
public final class ToolBarPill {
	private ToolBarPill() {
	}

	public static void apply(View tb, int color, int inner) {
		float maxRadius = toPx(tb.getContext(), 28);
		PillDrawable d = new PillDrawable(tb, color, inner, maxRadius);
		tb.setBackground(d);
		tb.setOutlineProvider(new ViewOutlineProvider() {
			private final RectF r = new RectF();

			@Override
			public void getOutline(View view, Outline outline) {
				pillRect(view, inner, 0, 0, view.getWidth(), view.getHeight(), r);
				if (r.isEmpty()) {
					outline.setEmpty();
					return;
				}
				outline.setRoundRect(Math.round(r.left), Math.round(r.top), Math.round(r.right),
						Math.round(r.bottom), radius(r, maxRadius));
				// A soft shadow, like the one FloatingBarsView paints under the nav bar's pill.
				outline.setAlpha(0.35f);
			}
		});
		tb.invalidateOutline();
	}

	static void pillRect(View v, int inner, int left, int top, int right, int bottom, RectF out) {
		float l = left + Math.max(0, v.getPaddingLeft() - inner);
		float r = right - Math.max(0, v.getPaddingRight() - inner);
		if (r <= l) out.setEmpty();
		else out.set(l, top, r, bottom);
	}

	static float radius(RectF r, float maxRadius) {
		return Math.min(maxRadius, Math.min(r.width(), r.height()) / 2f);
	}

	private static final class PillDrawable extends Drawable {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final View view;
		private final int color;
		private final int inner;
		private final float maxRadius;
		private int alpha = 255;

		PillDrawable(View view, int color, int inner, float maxRadius) {
			this.view = view;
			this.color = color;
			this.inner = inner;
			this.maxRadius = maxRadius;
			paint.setColor(color);
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			Rect b = getBounds();
			pillRect(view, inner, b.left, b.top, b.right, b.bottom, rect);
			if (rect.isEmpty()) return;
			paint.setColor(color);
			paint.setAlpha(Math.round(Color.alpha(color) * (alpha / 255f)));
			float radius = radius(rect, maxRadius);
			canvas.drawRoundRect(rect, radius, radius, paint);
		}

		@Override
		public void setAlpha(int alpha) {
			this.alpha = alpha;
			invalidateSelf();
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter colorFilter) {
			paint.setColorFilter(colorFilter);
			invalidateSelf();
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}
}
