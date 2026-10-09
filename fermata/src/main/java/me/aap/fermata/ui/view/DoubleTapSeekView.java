package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;

import me.aap.utils.ui.UiUtils;

/**
 * The YouTube-like feedback of a double tap seek over fullscreen video: a light half-oval over the
 * tapped side, a ripple from the tap, three arrows lighting up one after another and the seconds
 * jumped so far ("+10s", "+20s", ... or "-10s", ...). Drawn by hand, frame by frame, while it is up;
 * it fades out by itself a moment after the last tap. Never takes touches.
 */
final class DoubleTapSeekView extends View {
	private static final long FADE_IN_MS = 120L;
	private static final long RIPPLE_MS = 450L;
	/** How long it stays after the last tap before fading out. */
	private static final long HOLD_MS = 650L;
	private static final long FADE_OUT_MS = 250L;
	private static final long ARROWS_CYCLE_MS = 750L;
	private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path arrow = new Path();
	private final Path clip = new Path();
	private final RectF oval = new RectF();
	private final float arrowW;
	private final float arrowH;
	private final float arrowGap;
	private final float textGap;
	private boolean active;
	private boolean forward;
	private int seconds;
	private float tapX;
	private float tapY;
	private long startTime;
	private long tapTime;

	DoubleTapSeekView(Context ctx) {
		super(ctx);
		setClickable(false);
		setFocusable(false);
		setWillNotDraw(false);
		bgPaint.setColor(0xFFFFFFFF);
		ripplePaint.setColor(0xFFFFFFFF);
		arrowPaint.setColor(0xFFFFFFFF);
		textPaint.setColor(0xFFFFFFFF);
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setTypeface(Typeface.DEFAULT_BOLD);
		textPaint.setTextSize(UiUtils.toPx(ctx, 15));
		textPaint.setShadowLayer(UiUtils.toPx(ctx, 2), 0, 0, 0x80000000);
		arrowW = UiUtils.toPx(ctx, 11);
		arrowH = UiUtils.toPx(ctx, 14);
		arrowGap = UiUtils.toPx(ctx, 3);
		textGap = UiUtils.toPx(ctx, 10);
	}

	/**
	 * Shows (or carries on showing) the feedback for one more tap: {@code seconds} is the total jumped
	 * in this streak so far, {@code x}/{@code y} where the tap was, in this view's coordinates.
	 */
	void show(boolean forward, int seconds, float x, float y) {
		long now = SystemClock.uptimeMillis();
		if (!active || (this.forward != forward)) startTime = now;
		this.forward = forward;
		this.seconds = seconds;
		tapX = x;
		tapY = y;
		tapTime = now;
		active = true;
		postInvalidateOnAnimation();
	}

	void hide() {
		if (!active) return;
		active = false;
		invalidate();
	}

	@Override
	protected void onDraw(@NonNull Canvas c) {
		if (!active) return;
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;

		long now = SystemClock.uptimeMillis();
		long sinceTap = now - tapTime;
		long sinceStart = now - startTime;
		float alpha;

		if (sinceTap < HOLD_MS) {
			alpha = 1f;
		} else if (sinceTap < HOLD_MS + FADE_OUT_MS) {
			alpha = 1f - (sinceTap - HOLD_MS) / (float) FADE_OUT_MS;
		} else {
			active = false;
			return;
		}
		if (sinceStart < FADE_IN_MS) alpha *= sinceStart / (float) FADE_IN_MS;

		// The half-oval bulging in from the tapped side, its inner edge at 40% of the width.
		float rx = w * 0.55f;
		float ry = h * 0.9f;
		float cx = forward ? (w * 1.15f) : (-w * 0.15f);
		float cy = h / 2f;
		oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
		bgPaint.setAlpha(Math.round(0x30 * alpha));
		c.drawOval(oval, bgPaint);

		// A ripple spreading from the tap, kept inside the half-oval.
		if (sinceTap < RIPPLE_MS) {
			float p = sinceTap / (float) RIPPLE_MS;
			float eased = 1f - (1f - p) * (1f - p);
			int cs = c.save();
			clip.reset();
			clip.addOval(oval, Path.Direction.CW);
			c.clipPath(clip);
			ripplePaint.setAlpha(Math.round(0x40 * (1f - p) * alpha));
			c.drawCircle(tapX, tapY, eased * Math.max(w, h) * 0.3f, ripplePaint);
			c.restoreToCount(cs);
		}

		// Three arrows, lit one after another in the direction of the seek.
		float contentX = forward ? (w * 0.8f) : (w * 0.2f);
		float arrowsW = 3 * arrowW + 2 * arrowGap;
		float ay = cy - (arrowH + textGap + textPaint.getTextSize()) / 2f + arrowH / 2f;
		float phase = (sinceStart % ARROWS_CYCLE_MS) / (float) ARROWS_CYCLE_MS * 3f;

		for (int i = 0; i < 3; i++) {
			float local = phase - i;
			float lit = ((local >= 0f) && (local < 1f)) ? (float) Math.sin(Math.PI * local) : 0f;
			arrowPaint.setAlpha(Math.round(255 * alpha * (0.3f + 0.7f * lit)));
			// The i-th arrow in the order they light up: left to right going forward, the other way back.
			int slot = forward ? i : (2 - i);
			float x0 = contentX - arrowsW / 2f + slot * (arrowW + arrowGap);
			arrow.reset();
			if (forward) {
				arrow.moveTo(x0, ay - arrowH / 2f);
				arrow.lineTo(x0 + arrowW, ay);
				arrow.lineTo(x0, ay + arrowH / 2f);
			} else {
				arrow.moveTo(x0 + arrowW, ay - arrowH / 2f);
				arrow.lineTo(x0, ay);
				arrow.lineTo(x0 + arrowW, ay + arrowH / 2f);
			}
			arrow.close();
			c.drawPath(arrow, arrowPaint);
		}

		textPaint.setAlpha(Math.round(255 * alpha));
		String text = (forward ? "+" : "-") + seconds + "s";
		c.drawText(text, contentX, ay + arrowH / 2f + textGap + textPaint.getTextSize(), textPaint);

		postInvalidateOnAnimation();
	}
}
