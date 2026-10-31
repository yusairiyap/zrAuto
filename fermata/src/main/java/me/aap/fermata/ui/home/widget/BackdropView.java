package me.aap.fermata.ui.home.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * A faint picture behind a short card's words: a fill that runs out from the left (a timer's time left, a night's length
 * against the goal) or little bars (the last nights), in a soft colour so the text over it stays easy to read. It takes
 * no touches and draws only what it was given.
 */
final class BackdropView extends View {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private int color;
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

	/** A fill from the left to this fraction of the width; below 0 draws none. */
	void setFraction(float f) {
		fraction = (f < 0f) ? -1f : Math.min(1f, f);
		bars = new float[0];
		invalidate();
	}

	/** Bars of these heights (0 to 1) across the width; -1 leaves a gap (no night). */
	void setBars(float[] v) {
		bars = (v == null) ? new float[0] : v;
		fraction = -1f;
		invalidate();
	}

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;
		paint.setStyle(Paint.Style.FILL);
		if (fraction >= 0f) {
			// Strongest at the left, melting away at the end of the fill.
			paint.setShader(new LinearGradient(0, 0, w * Math.max(0.05f, fraction), 0, color, color & 0x00FFFFFF,
					Shader.TileMode.CLAMP));
			c.drawRect(0, 0, w * fraction, h, paint);
			paint.setShader(null);
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
