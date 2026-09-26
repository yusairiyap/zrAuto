package me.aap.fermata.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A loading indicator laid over artwork: the picture dims slightly and a soft light band sweeps
 * across it, over and over, fading in and out smoothly as loading starts and ends. The animation
 * only runs while the view is actually visible on screen.
 */
public class ShimmerView extends View {
	private static final long SWEEP_MS = 1500;
	private static final long FADE_IN_MS = 250;
	private static final long FADE_OUT_MS = 350;
	private static final int DIM_COLOR = 0x33000000;
	private static final int BAND_COLOR = 0x55FFFFFF;
	private static final float BAND_ANGLE = 20f;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Matrix matrix = new Matrix();
	@Nullable
	private ValueAnimator animator;
	private float bandWidth;
	private float progress;
	private boolean shimmering;

	public ShimmerView(Context context) {
		this(context, null);
	}

	public ShimmerView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		setAlpha(0f);
		setVisibility(GONE);
	}

	public void setShimmering(boolean on) {
		if (shimmering == on) return;
		shimmering = on;
		animate().cancel();

		if (on) {
			setVisibility(VISIBLE);
			animate().alpha(1f).setDuration(FADE_IN_MS).start();
		} else {
			animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(() -> {
				if (!shimmering) setVisibility(GONE);
			}).start();
		}
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		bandWidth = Math.max(w, h) * 0.6f;
		paint.setShader(new LinearGradient(0, 0, bandWidth, 0,
				new int[]{0, BAND_COLOR, 0}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
	}

	@Override
	public void onVisibilityAggregated(boolean isVisible) {
		super.onVisibilityAggregated(isVisible);
		if (isVisible) startSweep();
		else stopSweep();
	}

	@Override
	protected void onDetachedFromWindow() {
		stopSweep();
		super.onDetachedFromWindow();
	}

	private void startSweep() {
		if (animator != null) return;
		ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
		a.setDuration(SWEEP_MS);
		a.setRepeatCount(ValueAnimator.INFINITE);
		a.setInterpolator(new AccelerateDecelerateInterpolator());
		a.addUpdateListener(v -> {
			progress = (float) v.getAnimatedValue();
			invalidate();
		});
		a.start();
		animator = a;
	}

	private void stopSweep() {
		if (animator == null) return;
		animator.cancel();
		animator = null;
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;
		canvas.drawColor(DIM_COLOR);
		// The band travels from fully off the left edge to fully off the right one, tilted a little.
		float x = -bandWidth * 1.5f + progress * (w + bandWidth * 3f);
		matrix.setRotate(BAND_ANGLE);
		matrix.postTranslate(x, 0);
		paint.getShader().setLocalMatrix(matrix);
		canvas.drawRect(0, 0, w, h, paint);
	}
}
