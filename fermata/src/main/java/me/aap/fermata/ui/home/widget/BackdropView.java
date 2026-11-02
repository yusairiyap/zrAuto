package me.aap.fermata.ui.home.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * A faint picture behind a short card's words: a fill that runs out from the left (a timer's time left, a night's length
 * against the goal) or little bars (the last nights), in a soft colour so the text over it stays easy to read. The fill
 * eases to its new value and, while a timer runs, a band of light travels along it. It takes no touches and draws only
 * what it was given.
 */
final class BackdropView extends View {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final MeterGlide glide = new MeterGlide();
	private final FillSheen sheen = new FillSheen();
	private int color;
	/** The fill's fraction, or -1 for no fill (bars or nothing). */
	private float fraction = -1f;
	private float[] bars = new float[0];

	BackdropView(Context ctx) {
		super(ctx);
		setClickable(false);
		setFocusable(false);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
	}

	/** The colour with its own strength (alpha) already in it. */
	void setColor(int color) {
		this.color = color;
		invalidate();
	}

	/** A fill from the left to this fraction of the width, easing to it; below 0 draws none (and at once). */
	void setFraction(float f) {
		bars = new float[0];
		if (f < 0f) {
			fraction = -1f;
			glide.reset();
			invalidate();
			return;
		}
		f = Math.min(1f, f);
		long now = SystemClock.uptimeMillis();
		if ((fraction < 0f) || !glide.isSet()) glide.jump(f);
		else if (f != glide.target()) glide.glideTo(f, now);
		fraction = f;
		invalidate();
	}

	/** Whether the band of light runs along the fill (a timer is counting down). */
	void setRunning(boolean on) {
		if (sheen.isOn() == on) return;
		sheen.setOn(on);
		invalidate();
	}

	/** Bars of these heights (0 to 1) across the width; -1 leaves a gap (no night). */
	void setBars(float[] v) {
		bars = (v == null) ? new float[0] : v;
		fraction = -1f;
		glide.reset();
		invalidate();
	}

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;
		paint.setStyle(Paint.Style.FILL);
		if (fraction >= 0f) {
			long now = SystemClock.uptimeMillis();
			float f = glide.value(now);
			if (f > 0f) {
				float fw = w * f;
				// Strongest at the left, melting away at the end of the fill.
				paint.setShader(new LinearGradient(0, 0, Math.max(fw, 1f), 0, color, color & 0x00FFFFFF,
						Shader.TileMode.CLAMP));
				c.drawRect(0, 0, fw, h, paint);
				paint.setShader(null);
				sheen.draw(c, fw, h, 0f, now);
			}
			if (glide.moving(now)) postInvalidateOnAnimation();
			else if (sheen.isOn() && (f > 0f)) postInvalidateDelayed(FillSheen.FRAME_MS, 0, 0, (int) Math.ceil(w * f), h);
			return;
		}
		int n = bars.length;
		if (n == 0) return;
		paint.setColor(color);
		float slot = w / (float) n;
		float bw = slot * 0.7f;
		for (int i = 0; i < n; i++) {
			if (bars[i] < 0f) continue;
			float bh = Math.max(h * 0.08f, h * bars[i]);
			float cx = slot * (i + 0.5f);
			rect.set(cx - bw / 2f, h - bh, cx + bw / 2f, h);
			c.drawRoundRect(rect, bw / 3f, bw / 3f, paint);
		}
	}
}
