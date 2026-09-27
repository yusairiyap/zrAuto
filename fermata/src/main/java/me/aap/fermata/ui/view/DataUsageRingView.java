package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toPx;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * The ring at the top of the Data Usage tab: how much of the data limit is used, split into the
 * category colours -- or, with no limit set, the whole ring split by category. A small tick marks
 * the warning level, and an outer ring in the limit colour shows when the limit is passed. The
 * ring sweeps in whenever the numbers change.
 */
public class DataUsageRingView extends View {
	private static final float GAP_DEG = 2.5f;
	private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final float stroke;
	private int[] colors = {0xFFFF4E45, 0xFF1ED760, 0xFF5B8DEF};
	private int warnColor = 0xFFFFB020;
	private int limitColor = 0xFFFF5252;
	private long[] values = new long[0];
	private long capacity;
	private long warning;
	private float progress = 1f;
	// Degrees of the ring per category: when the data last changed, and on screen now. The ring
	// moves from where it is to the new values, so growing usage extends it instead of re-sweeping.
	private float[] fromDeg = new float[0];
	private float[] shownDeg = new float[0];
	@Nullable
	private ValueAnimator anim;

	public DataUsageRingView(Context ctx) {
		this(ctx, null);
	}

	public DataUsageRingView(Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
		stroke = toPx(ctx, 16);
		trackPaint.setStyle(Paint.Style.STROKE);
		trackPaint.setStrokeWidth(stroke);
		trackPaint.setStrokeCap(Paint.Cap.ROUND);
		arcPaint.setStyle(Paint.Style.STROKE);
		arcPaint.setStrokeWidth(stroke);
		arcPaint.setStrokeCap(Paint.Cap.BUTT);
		markPaint.setStyle(Paint.Style.STROKE);
		markPaint.setStrokeCap(Paint.Cap.ROUND);
		markPaint.setStrokeWidth(toPx(ctx, 3));
	}

	public void setColors(int[] categories, int track, int warn, int limit) {
		colors = categories;
		trackPaint.setColor(track);
		warnColor = warn;
		limitColor = limit;
		invalidate();
	}

	/**
	 * @param values   bytes per category
	 * @param capacity the data limit (the full ring), or 0 for none
	 * @param warning  the warning level to mark, or 0 for none
	 */
	public void setData(long[] values, long capacity, long warning) {
		boolean same = java.util.Arrays.equals(this.values, values) && (this.capacity == capacity) &&
				(this.warning == warning);
		this.values = values.clone();
		this.capacity = capacity;
		this.warning = warning;
		if (same) return;
		if (anim != null) anim.cancel();
		boolean first = shownDeg.length != values.length;
		fromDeg = first ? new float[values.length] : shownDeg.clone();
		if (first) shownDeg = new float[values.length];
		ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
		a.setDuration(first ? 900 : 500);
		a.setInterpolator(new DecelerateInterpolator(1.8f));
		a.addUpdateListener(v -> {
			progress = (float) v.getAnimatedValue();
			invalidate();
		});
		anim = a;
		progress = 0f;
		a.start();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		if (anim != null) anim.cancel();
		anim = null;
		progress = 1f;
	}

	@Override
	protected void onDraw(Canvas c) {
		super.onDraw(c);
		float size = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
				getHeight() - getPaddingTop() - getPaddingBottom());
		if (size <= stroke * 2) return;
		float outer = toPx(getContext(), 6);
		float r = (size - stroke) / 2f - outer;
		float cx = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) / 2f;
		float cy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
		rect.set(cx - r, cy - r, cx + r, cy + r);
		c.drawArc(rect, 0, 360, false, trackPaint);

		long total = 0;
		for (long v : values) total += v;
		long full = (capacity > 0) ? capacity : Math.max(total, 1);
		float targetDeg = 360f * Math.min(1f, (float) total / full);
		float usedDeg = 0;
		int shown = 0;
		for (int i = 0; (i < values.length) && (i < shownDeg.length); i++) {
			float target = (total > 0) ? targetDeg * values[i] / total : 0;
			float f = (i < fromDeg.length) ? fromDeg[i] : 0;
			shownDeg[i] = f + (target - f) * progress;
			usedDeg += shownDeg[i];
			if (shownDeg[i] > 0.1f) shown++;
		}

		if (usedDeg > 0.1f) {
			// A small gap between the category arcs (and after the last one, on a full ring).
			boolean fullRing = usedDeg >= 359.9f;
			int gapCount = (shown > 1) ? (fullRing ? shown : shown - 1) : 0;
			float gaps = (usedDeg > GAP_DEG * gapCount * 2) ? GAP_DEG * gapCount : 0;
			float start = -90f;

			for (int i = 0; i < shownDeg.length; i++) {
				if (shownDeg[i] <= 0.1f) continue;
				// Each arc gives up its share of the gaps, so the gaps don't lengthen the ring.
				float sweep = shownDeg[i] * (usedDeg - gaps) / usedDeg;
				if (sweep <= 0) continue;
				arcPaint.setColor(colors[i % colors.length]);
				c.drawArc(rect, start, sweep, false, arcPaint);
				start += sweep + ((gaps > 0) ? GAP_DEG : 0);
			}

			// Past the limit: a thin outer ring in the limit colour.
			if ((capacity > 0) && (total >= capacity)) {
				markPaint.setColor(limitColor);
				// Fades in when the limit is just passed; stays put while usage keeps growing.
				float wasDeg = 0;
				for (float d : fromDeg) wasDeg += d;
				markPaint.setAlpha((wasDeg >= 359.9f) ? 255 : Math.round(255 * progress));
				float ro = r + stroke / 2f + outer / 2f + markPaint.getStrokeWidth() / 2f;
				c.drawCircle(cx, cy, ro, markPaint);
				markPaint.setAlpha(255);
			}
		}

		// The warning level: a short tick across the ring.
		if ((capacity > 0) && (warning > 0) && (warning < capacity)) {
			double a = Math.toRadians(-90 + 360.0 * warning / capacity);
			float in = r - stroke / 2f - toPx(getContext(), 2);
			float out = r + stroke / 2f + toPx(getContext(), 2);
			markPaint.setColor(warnColor);
			c.drawLine(cx + (float) Math.cos(a) * in, cy + (float) Math.sin(a) * in,
					cx + (float) Math.cos(a) * out, cy + (float) Math.sin(a) * out, markPaint);
		}
	}
}
