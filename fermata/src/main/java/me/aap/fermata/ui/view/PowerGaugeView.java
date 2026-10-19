package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toPx;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * The Power tab's gauge: a three-quarter ring that fills with how much current flows, relative to
 * a scale that grows with it (so a phone idling at 120 mA and one fast charging at 4 A both read
 * well). Cool teal to amber to red while the phone draws from the battery, green while it
 * charges; a bright dot rides the end of the arc, and a soft glow sits under it. The value
 * glides to each new reading. The numbers sit in the middle, as views of the layout.
 */
public class PowerGaugeView extends View {
	private static final float START = 135f;
	private static final float SWEEP = 270f;
	private static final float[] SCALES = {250, 500, 1000, 1500, 2000, 3000, 4000, 6000, 10000};
	private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF oval = new RectF();
	private final Matrix matrix = new Matrix();
	private final float stroke;
	private int low = 0xFF4DD0E1;
	private int mid = 0xFFFFB020;
	private int high = 0xFFFF5252;
	private int charge = 0xFF1ED760;
	private float fraction;
	private float shown;
	private int color;
	private float scale = SCALES[2];
	private boolean charging;
	@Nullable
	private ValueAnimator anim;
	@Nullable
	private ValueAnimator pulse;
	private float pulsePhase;

	public PowerGaugeView(Context ctx) {
		this(ctx, null);
	}

	public PowerGaugeView(Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
		stroke = toPx(ctx, 14);
		track.setStyle(Paint.Style.STROKE);
		track.setStrokeCap(Paint.Cap.ROUND);
		track.setStrokeWidth(stroke);
		arc.setStyle(Paint.Style.STROKE);
		arc.setStrokeCap(Paint.Cap.ROUND);
		arc.setStrokeWidth(stroke);
		glow.setStyle(Paint.Style.STROKE);
		glow.setStrokeCap(Paint.Cap.ROUND);
		glow.setStrokeWidth(stroke * 2.2f);
		tick.setStyle(Paint.Style.STROKE);
		tick.setStrokeCap(Paint.Cap.ROUND);
		tick.setStrokeWidth(toPx(ctx, 2));
		color = low;
	}

	/**
	 * @param trackColor the unfilled part of the ring
	 * @param lowDraw    colour at a small draw; {@code midDraw} halfway; {@code highDraw} at the top
	 * @param chargeColor colour while charging
	 */
	public void setColors(int trackColor, int lowDraw, int midDraw, int highDraw, int chargeColor) {
		track.setColor(trackColor);
		tick.setColor(trackColor);
		low = lowDraw;
		mid = midDraw;
		high = highDraw;
		charge = chargeColor;
		color = pick(fraction);
		invalidate();
	}

	/** The scale the gauge reads against for a reading of {@code ma} (its magnitude). */
	public static float scaleFor(float ma) {
		float a = Math.abs(ma);
		for (float s : SCALES) if (a <= s * 0.92f) return s;
		return SCALES[SCALES.length - 1];
	}

	/** The scale in use now, mA at full ring. */
	public float getScale() {
		return scale;
	}

	/** Shows {@code ma} (negative: drawing from the battery, positive: charging). */
	public void setValue(float ma) {
		charging = ma > 0;
		scale = scaleFor(ma);
		float f = Math.min(1f, Math.abs(ma) / scale);
		fraction = f;
		color = pick(f);
		if (!isShown() || (getWidth() == 0)) {
			shown = f;
			invalidate();
			return;
		}
		if (anim != null) anim.cancel();
		float from = shown;
		ValueAnimator a = ValueAnimator.ofFloat(from, f);
		a.setDuration(480);
		a.setInterpolator(new DecelerateInterpolator(1.6f));
		a.addUpdateListener(v -> {
			shown = (float) v.getAnimatedValue();
			invalidate();
		});
		anim = a;
		a.start();
	}

	private int pick(float f) {
		if (charging) return charge;
		if (f < 0.5f) return blend(low, mid, f / 0.5f);
		return blend(mid, high, (f - 0.5f) / 0.5f);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		ValueAnimator p = ValueAnimator.ofFloat(0f, 1f);
		p.setDuration(1800);
		p.setRepeatCount(ValueAnimator.INFINITE);
		p.setRepeatMode(ValueAnimator.REVERSE);
		p.addUpdateListener(v -> {
			pulsePhase = (float) v.getAnimatedValue();
			invalidate();
		});
		pulse = p;
		p.start();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		if (anim != null) anim.cancel();
		if (pulse != null) pulse.cancel();
		anim = null;
		pulse = null;
	}

	@Override
	protected void onDraw(Canvas c) {
		float w = getWidth();
		float h = getHeight();
		float size = Math.min(w, h);
		if (size <= 0) return;
		float pad = stroke * 1.6f;
		float cx = w / 2f;
		float cy = h / 2f;
		oval.set(cx - size / 2f + pad, cy - size / 2f + pad, cx + size / 2f - pad, cy + size / 2f - pad);
		float r = oval.width() / 2f;

		// Tick marks outside the ring.
		float tickR1 = r + stroke * 1.05f;
		float tickLen = stroke * 0.5f;
		for (int i = 0; i <= 18; i++) {
			double ang = Math.toRadians(START + SWEEP * i / 18f);
			float cos = (float) Math.cos(ang);
			float sin = (float) Math.sin(ang);
			boolean major = i % 3 == 0;
			float len = major ? tickLen : tickLen * 0.55f;
			c.drawLine(cx + cos * tickR1, cy + sin * tickR1, cx + cos * (tickR1 + len),
					cy + sin * (tickR1 + len), tick);
		}

		c.drawArc(oval, START, SWEEP, false, track);
		float sweep = SWEEP * Math.max(0.0001f, shown);
		if (shown <= 0.002f) return;

		int dark = blend(color, 0xFF000000, 0.28f);
		SweepGradient g = new SweepGradient(cx, cy, new int[]{dark, color, color},
				new float[]{0f, SWEEP / 360f * Math.max(0.05f, shown), 1f});
		matrix.reset();
		matrix.postRotate(START, cx, cy);
		g.setLocalMatrix(matrix);
		arc.setShader(g);
		glow.setShader(null);
		glow.setColor(color);
		glow.setAlpha(Math.round(28 + 22 * pulsePhase));
		c.drawArc(oval, START, sweep, false, glow);
		c.drawArc(oval, START, sweep, false, arc);

		// The dot at the end of the arc.
		double end = Math.toRadians(START + sweep);
		float ex = cx + (float) Math.cos(end) * r;
		float ey = cy + (float) Math.sin(end) * r;
		dot.setColor(0xFFFFFFFF);
		c.drawCircle(ex, ey, stroke * (0.34f + 0.06f * pulsePhase), dot);
		dot.setColor(color);
		c.drawCircle(ex, ey, stroke * 0.2f, dot);
	}

	private static int blend(int a, int b, float t) {
		t = Math.max(0f, Math.min(1f, t));
		return Color.argb(255,
				Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
				Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
				Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
	}
}
