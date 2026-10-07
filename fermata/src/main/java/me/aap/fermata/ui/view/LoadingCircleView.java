package me.aap.fermata.ui.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.utils.ui.UiUtils;

/**
 * The app's one loading indicator: a spinner in a soft dark circle that grows and fades in, and
 * shrinks and fades out. What the YouTube player shows while it buffers, and now what every wait
 * shows -- the next video loading, a list loading, playback starting -- so they all look the same.
 * Hidden until {@link #setLoading} turns it on; never takes touches or focus.
 */
public final class LoadingCircleView extends FrameLayout {
	private static final long FADE_MS = 200L;
	private static final int SIZE_DP = 72;
	private static final int PAD_DP = 14;
	private boolean loading;

	public LoadingCircleView(@NonNull Context ctx) {
		this(ctx, null);
	}

	public LoadingCircleView(@NonNull Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
		GradientDrawable bg = new GradientDrawable();
		bg.setShape(GradientDrawable.OVAL);
		bg.setColor(0x80000000);
		setBackground(bg);
		int pad = UiUtils.toIntPx(ctx, PAD_DP);
		int size = UiUtils.toIntPx(ctx, SIZE_DP);
		ProgressBar spinner = new ProgressBar(ctx);
		spinner.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
		LayoutParams lp = new LayoutParams(size - 2 * pad, size - 2 * pad, Gravity.CENTER);
		addView(spinner, lp);
		setMinimumWidth(size);
		setMinimumHeight(size);
		setClickable(false);
		setFocusable(false);
		setVisibility(GONE);
	}

	@Override
	protected void onMeasure(int w, int h) {
		int size = UiUtils.toIntPx(getContext(), SIZE_DP);
		super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
				MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
	}

	public boolean isLoading() {
		return loading;
	}

	/** Shows the circle (growing in) or takes it away (shrinking out). */
	public void setLoading(boolean on) {
		if (on == loading) return;
		loading = on;
		animate().cancel();

		if (on) {
			if (getVisibility() != VISIBLE) {
				setAlpha(0f);
				setScaleX(0.7f);
				setScaleY(0.7f);
				setVisibility(VISIBLE);
			}
			animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(FADE_MS)
					.setInterpolator(new DecelerateInterpolator()).start();
		} else if (getVisibility() == VISIBLE) {
			animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(FADE_MS)
					.setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
						// Shown again in the meantime: it stays.
						if (!loading) setVisibility(GONE);
					}).start();
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		animate().cancel();
		super.onDetachedFromWindow();
	}

	/** Right away, no animation: for a view about to be removed or reused. */
	public void reset() {
		animate().cancel();
		loading = false;
		setVisibility(GONE);
		setAlpha(1f);
		setScaleX(1f);
		setScaleY(1f);
	}
}
