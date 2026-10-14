package me.aap.fermata.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;

/**
 * What the comments card shows while the first batch loads: rows in the shape of comments (a round
 * picture, a name, two lines of text), with a soft light sweeping across them. It only animates
 * while it is on screen.
 */
final class CommentsSkeletonView extends View {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Matrix matrix = new Matrix();
	private final RectF rect = new RectF();
	private final float density;
	private final int base;
	private final int light;
	private Shader shader;
	private float phase;
	private ValueAnimator sweep;

	CommentsSkeletonView(@NonNull Context ctx, int tint) {
		super(ctx);
		density = ctx.getResources().getDisplayMetrics().density;
		base = (tint & 0x00FFFFFF) | 0x2B000000;
		light = (tint & 0x00FFFFFF) | 0x66000000;
		setClickable(false);
		setFocusable(false);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
	}

	@Override
	public void onVisibilityAggregated(boolean visible) {
		super.onVisibilityAggregated(visible);
		if (visible && (sweep == null)) {
			sweep = ValueAnimator.ofFloat(0f, 1f);
			sweep.setDuration(1300);
			sweep.setRepeatCount(ValueAnimator.INFINITE);
			sweep.setInterpolator(new LinearInterpolator());
			sweep.addUpdateListener(a -> {
				phase = (float) a.getAnimatedValue();
				invalidate();
			});
			sweep.start();
		} else if (!visible && (sweep != null)) {
			sweep.cancel();
			sweep = null;
		}
	}

	@Override
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		super.onSizeChanged(w, h, ow, oh);
		float band = Math.max(1f, w * 0.7f);
		shader = new LinearGradient(0, 0, band, 0, new int[]{base, light, base},
				new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
	}

	private void bar(Canvas c, float x, float y, float w, float h) {
		rect.set(x, y, x + w, y + h);
		c.drawRoundRect(rect, h / 2f, h / 2f, paint);
	}

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (shader == null)) return;
		float band = w * 0.7f;
		// The band travels from off the left edge to off the right one.
		matrix.setTranslate(-band + phase * (w + band), 0f);
		shader.setLocalMatrix(matrix);
		paint.setShader(shader);

		float pad = 12 * density;
		float avatar = 38 * density;
		float rowH = 96 * density;
		float textX = pad + avatar + 12 * density;
		float textW = w - textX - pad;
		for (float y = pad; y < h; y += rowH) {
			c.drawCircle(pad + avatar / 2f, y + avatar / 2f, avatar / 2f, paint);
			bar(c, textX, y + 2 * density, textW * 0.34f, 11 * density);
			bar(c, textX, y + 24 * density, textW * 0.94f, 11 * density);
			bar(c, textX, y + 44 * density, textW * 0.70f, 11 * density);
			bar(c, textX, y + 64 * density, textW * 0.22f, 9 * density);
		}
		paint.setShader(null);
	}
}
