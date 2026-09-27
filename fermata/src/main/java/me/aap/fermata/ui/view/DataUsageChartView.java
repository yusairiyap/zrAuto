package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toPx;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * The Data Usage tab's bar chart: one bar per hour, day or month, each stacked by category, with
 * a light value grid, a few axis labels, and a tap on a bar showing its total in a bubble above
 * it. The bars grow in, one after the other, whenever the data changes.
 */
public class DataUsageChartView extends View {
	private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final Path path = new Path();
	private final float radius;
	private final float axisGap;
	private int[] colors = {0xFFFF4E45, 0xFF1ED760, 0xFF5B8DEF};
	private long[][] values = new long[0][];
	private String[] labels = new String[0];
	private String[] names = new String[0];
	private int highlight = -1;
	private int selected = -1;
	private float progress = 1f;
	@Nullable
	private ValueAnimator anim;
	// Chart area and scale of the last draw, for touch handling.
	private float chartLeft;
	private float slot;
	private long scaleMax;

	public DataUsageChartView(Context ctx) {
		this(ctx, null);
	}

	public DataUsageChartView(Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
		radius = toPx(ctx, 5);
		axisGap = toPx(ctx, 8);
		gridPaint.setStrokeWidth(toPx(ctx, 1));
		gridPaint.setPathEffect(new android.graphics.DashPathEffect(
				new float[]{toPx(ctx, 3), toPx(ctx, 4)}, 0));
		textPaint.setTextSize(toPx(ctx, 11));
		bubbleTextPaint.setTextSize(toPx(ctx, 12));
		bubbleTextPaint.setFakeBoldText(true);
		bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
	}

	/**
	 * @param categories the bar colours, one per stacked category
	 * @param text       axis label colour
	 * @param grid       grid line colour
	 * @param bubble     the tap bubble's fill; its text is drawn in {@code bubbleText}
	 */
	public void setColors(int[] categories, int text, int grid, int bubble, int bubbleText) {
		colors = categories;
		textPaint.setColor(text);
		gridPaint.setColor(grid);
		bubblePaint.setColor(bubble);
		bubbleTextPaint.setColor(bubbleText);
		invalidate();
	}

	/**
	 * @param values    per bar, bytes per category
	 * @param labels    per bar, its axis label ("" for none); only some are drawn when crowded
	 * @param names     per bar, its full name for the tap bubble
	 * @param highlight the bar for "now" (its label is drawn stronger), or -1
	 */
	public void setData(long[][] values, String[] labels, String[] names, int highlight) {
		this.values = values;
		this.labels = labels;
		this.names = names;
		this.highlight = highlight;
		selected = -1;
		if (anim != null) anim.cancel();
		ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
		a.setDuration(700);
		a.setInterpolator(new DecelerateInterpolator(1.6f));
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
		int n = values.length;
		Context ctx = getContext();
		float w = getWidth() - getPaddingLeft() - getPaddingRight();
		float h = getHeight() - getPaddingTop() - getPaddingBottom();
		float textH = textPaint.getTextSize();
		float bubbleSpace = bubbleTextPaint.getTextSize() * 2.6f;
		float top = getPaddingTop() + bubbleSpace;
		float bottom = getPaddingTop() + h - textH - axisGap;
		if ((w <= 0) || (bottom <= top)) return;

		long max = 0;
		for (long[] v : values) max = Math.max(max, sum(v));
		scaleMax = niceMax(max);

		// Value grid: 0, half and the top, labelled on the left.
		String topLabel = Formatter.formatShortFileSize(ctx, scaleMax);
		String midLabel = Formatter.formatShortFileSize(ctx, scaleMax / 2);
		float labelW = Math.max(textPaint.measureText(topLabel), textPaint.measureText(midLabel));
		chartLeft = getPaddingLeft() + labelW + axisGap;
		float chartW = getPaddingLeft() + w - chartLeft;
		float chartH = bottom - top;
		textPaint.setTextAlign(Paint.Align.LEFT);
		int textAlpha = textPaint.getAlpha();

		for (int i = 0; i <= 2; i++) {
			float y = bottom - chartH * i / 2f;
			if (i > 0) c.drawLine(chartLeft, y, chartLeft + chartW, y, gridPaint);
			String l = (i == 0) ? "0" : (i == 1) ? midLabel : topLabel;
			if ((max > 0) || (i == 0)) c.drawText(l, getPaddingLeft(), y + textH / 3f, textPaint);
		}

		if (n == 0) return;
		slot = chartW / n;
		float barW = Math.max(toPx(ctx, 2), Math.min(slot * 0.62f, toPx(ctx, 28)));
		float r = Math.min(radius, barW / 2f);
		// Every bar gets a label when there's room, else every 2nd, 3rd... one.
		float labelRoom = textPaint.measureText("00 ") + axisGap;
		int labelEvery = Math.max(1, (int) Math.ceil(labelRoom / slot));
		float baseStub = toPx(ctx, 2);

		for (int i = 0; i < n; i++) {
			float cx = chartLeft + slot * i + slot / 2f;
			long total = sum(values[i]);
			// Staggered: each bar starts growing a little after the one before it.
			float p = clamp((progress * 1.35f) - (0.35f * i / Math.max(1, n - 1)));
			float barH = (scaleMax > 0) ? chartH * total / scaleMax * p : 0;
			boolean dim = (selected >= 0) && (selected != i);

			if (barH < baseStub) {
				// Nothing (or next to nothing) used: a small stub keeps the bar's place visible.
				barPaint.setColor(gridPaint.getColor());
				rect.set(cx - barW / 2f, bottom - baseStub, cx + barW / 2f, bottom);
				c.drawRoundRect(rect, baseStub / 2f, baseStub / 2f, barPaint);
			} else {
				path.reset();
				rect.set(cx - barW / 2f, bottom - barH, cx + barW / 2f, bottom);
				path.addRoundRect(rect, new float[]{r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
				c.save();
				c.clipPath(path);
				float y = bottom;
				for (int cat = 0; cat < values[i].length; cat++) {
					float segH = barH * values[i][cat] / (float) total;
					if (segH <= 0) continue;
					barPaint.setColor(colors[cat % colors.length]);
					if (dim) barPaint.setAlpha(90);
					c.drawRect(cx - barW / 2f, y - segH, cx + barW / 2f, y, barPaint);
					y -= segH;
				}
				c.restore();
			}

			String l = (i < labels.length) ? labels[i] : "";
			if (!l.isEmpty() && ((i % labelEvery == 0) || (i == highlight))) {
				if ((i != highlight) && (highlight >= 0) && (Math.abs(i - highlight) < labelEvery)) {
					continue; // Room for the highlighted bar's own label.
				}
				textPaint.setTextAlign(Paint.Align.CENTER);
				textPaint.setFakeBoldText(i == highlight);
				textPaint.setAlpha((i == highlight) ? 255 : textAlpha);
				c.drawText(l, cx, getPaddingTop() + h, textPaint);
				textPaint.setFakeBoldText(false);
				textPaint.setAlpha(textAlpha);
			}
		}

		if ((selected >= 0) && (selected < n)) drawBubble(c, selected, top, bottom, chartH);
	}

	private void drawBubble(Canvas c, int i, float top, float bottom, float chartH) {
		long total = sum(values[i]);
		float barTop = (scaleMax > 0) ? bottom - chartH * total / scaleMax : bottom;
		String name = (i < names.length) ? names[i] : "";
		String text = name.isEmpty() ? Formatter.formatShortFileSize(getContext(), total) :
				name + "  ·  " + Formatter.formatShortFileSize(getContext(), total);
		float padH = toPx(getContext(), 10);
		float th = bubbleTextPaint.getTextSize();
		float bw = bubbleTextPaint.measureText(text) + padH * 2;
		float bh = th * 2f;
		float cx = chartLeft + slot * i + slot / 2f;
		float left = Math.max(getPaddingLeft(), Math.min(cx - bw / 2f, getWidth() - getPaddingRight() - bw));
		float b = Math.max(top - th * 0.3f, barTop - toPx(getContext(), 6));
		float t = b - bh;
		if (t < getPaddingTop()) {
			t = getPaddingTop();
			b = t + bh;
		}
		rect.set(left, t, left + bw, b);
		c.drawRoundRect(rect, bh / 2f, bh / 2f, bubblePaint);
		c.drawText(text, left + bw / 2f, t + bh / 2f + th / 3f, bubbleTextPaint);
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent e) {
		int n = values.length;
		if ((n == 0) || (slot <= 0)) return super.onTouchEvent(e);

		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
				int i = (int) ((e.getX() - chartLeft) / slot);
				if ((i < 0) || (i >= n)) i = -1;
				if (i != selected) {
					selected = i;
					invalidate();
				}
				return true;
			}
			case MotionEvent.ACTION_UP -> {
				performClick();
				return true;
			}
		}
		return super.onTouchEvent(e);
	}

	@Override
	public boolean performClick() {
		return super.performClick();
	}

	private static float clamp(float f) {
		return Math.max(0f, Math.min(1f, f));
	}

	private static long sum(long[] v) {
		long s = 0;
		for (long l : v) s += l;
		return s;
	}

	/** A round number (1, 2 or 5 times a power of ten) at or above {@code v}. */
	private static long niceMax(long v) {
		if (v <= 0) return 1_000_000; // An empty chart still reads as "up to 1 MB".
		long p = 1;
		while (p * 10 <= v) p *= 10;
		for (long m : new long[]{1, 2, 5, 10}) {
			if (p * m >= v) return p * m;
		}
		return p * 10;
	}
}
