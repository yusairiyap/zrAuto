package me.aap.fermata.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * A loading indicator laid over artwork: a black veil that slowly darkens and lightens again, over
 * and over, never hiding the picture completely, fading away smoothly once loading ends. The
 * animation only runs while the view is actually visible on screen.
 */
public class LoadingDimView extends View {
	private static final long PULSE_MS = 900;
	private static final long FADE_OUT_MS = 400;
	private static final float MIN_ALPHA = 0.1f;
	private static final float MAX_ALPHA = 0.55f;
	@Nullable
	private ValueAnimator pulse;
	private boolean loading;
	private boolean shown;

	public LoadingDimView(Context context) {
		this(context, null);
	}

	public LoadingDimView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		setBackgroundColor(0xFF000000);
		setAlpha(0f);
		setVisibility(GONE);
	}

	public void setLoading(boolean loading) {
		if (this.loading == loading) return;
		this.loading = loading;
		animate().cancel();

		if (loading) {
			setVisibility(VISIBLE);
			if (shown) startPulse();
		} else {
			stopPulse();
			animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(() -> {
				if (!this.loading) setVisibility(GONE);
			}).start();
		}
	}

	@Override
	public void onVisibilityAggregated(boolean isVisible) {
		super.onVisibilityAggregated(isVisible);
		shown = isVisible;
		if (isVisible && loading) startPulse();
		else stopPulse();
	}

	@Override
	protected void onDetachedFromWindow() {
		stopPulse();
		super.onDetachedFromWindow();
	}

	private void startPulse() {
		if (pulse != null) return;
		// Starts from wherever the veil is now, so there's never a jump.
		ValueAnimator a = ValueAnimator.ofFloat(getAlpha(), MAX_ALPHA, MIN_ALPHA);
		a.setDuration(PULSE_MS * 2);
		a.setRepeatCount(ValueAnimator.INFINITE);
		a.setRepeatMode(ValueAnimator.REVERSE);
		a.setInterpolator(new AccelerateDecelerateInterpolator());
		a.addUpdateListener(v -> setAlpha((float) v.getAnimatedValue()));
		a.start();
		pulse = a;
	}

	private void stopPulse() {
		if (pulse == null) return;
		pulse.cancel();
		pulse = null;
	}
}
