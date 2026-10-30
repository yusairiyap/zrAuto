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
				// A soft shadow, like the one FloatingBarsView paints under the nav bar's pill -- none
				// under a see-through pill (see setTranslucent()), where it would show as a smudge.
				boolean clear = Glass.onBars() ||
						((view.getBackground() instanceof PillDrawable d) && (d.translucent || d.merged));
				outline.setAlpha(clear ? 0f : 0.35f);
			}
		});
		tb.invalidateOutline();
	}

	/**
	 * A see-through pill (or back to the usual, mostly opaque one): over the Music tab's blurred
	 * cover, which then shows through it like frosted glass.
	 */
	public static void setTranslucent(View tb, boolean translucent) {
		if (!(tb.getBackground() instanceof PillDrawable d) || (d.translucent == translucent)) return;
		d.translucent = translucent;
		d.invalidateSelf();
		tb.invalidateOutline();
	}

	/**
	 * The tool bar's pill is drawn by someone else (a tab's panel reaching down from it, see the
	 * browser's tab strip): the tool bar then paints nothing of its own, only its buttons.
	 */
	public static void setMerged(View tb, boolean merged) {
		if (!(tb.getBackground() instanceof PillDrawable d) || (d.merged == merged)) return;
		d.merged = merged;
		d.invalidateSelf();
		tb.invalidateOutline();
	}

	public static boolean isMerged(View tb) {
		return (tb.getBackground() instanceof PillDrawable d) && d.merged;
	}

	/** The pill's colour, 0 if the tool bar has none. */
	public static int getColor(View tb) {
		return (tb.getBackground() instanceof PillDrawable d) ? ArtAccent.map(d.color) : 0;
	}

	/** The pill's rectangle in the tool bar's own coordinates; false if it has none. */
	public static boolean getPillRect(View tb, RectF out) {
		if (!(tb.getBackground() instanceof PillDrawable d)) return false;
		pillRect(tb, d.inner, 0, 0, tb.getWidth(), tb.getHeight(), out);
		return !out.isEmpty();
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
		boolean translucent;
		boolean merged;

		PillDrawable(View view, int color, int inner, float maxRadius) {
			this.view = view;
			this.color = color;
			this.inner = inner;
			this.maxRadius = maxRadius;
			paint.setColor(color);
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			int color = ArtAccent.map(this.color);
			if (merged) {
				Glass.notDrawn(lens);
				return;
			}
			Rect b = getBounds();
			pillRect(view, inner, b.left, b.top, b.right, b.bottom, rect);
			if (rect.isEmpty()) {
				Glass.notDrawn(lens);
				return;
			}
			float radius = radius(rect, maxRadius);
			if (Glass.onBars()) {
				// Glass: the content behind it blurred (Android 12+), under the bar's colour as a tint.
				Glass.Look l = Glass.look();
				float a = alpha / 255f;
				boolean blurred = Glass.realBlur() && GlassLens.behindBars(view, canvas, rect, radius, a, l, lens);
				Glass.paintOver(canvas, rect, radius, color, a, l.rimFor(Glass.RIM_TOOLBAR),
						androidx.core.graphics.ColorUtils.calculateLuminance(color | 0xFF000000) > 0.5,
						view.getResources().getDisplayMetrics().density, glassPaint, blurred);
				return;
			}
			paint.setColor(color);
			int a = translucent ? 0x59 : Color.alpha(color);
			paint.setAlpha(Math.round(a * (alpha / 255f)));
			canvas.drawRoundRect(rect, radius, radius, paint);
		}

		private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		// Made on first use, Android 12+ only (see Glass).
		private final GlassLens[] lens = new GlassLens[1];

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
