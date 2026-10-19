package me.aap.fermata.ui.welcome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import androidx.core.graphics.ColorUtils;

/**
 * The wizard's background: the theme's own background colour with a few large, soft blobs of the
 * accent colour drifting slowly across it. Quiet enough to read over, alive enough that the pages
 * never feel like a form.
 */
final class WelcomeBackdropView extends View {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private int base = 0xFF101010;
	private int accent = 0xFF1ED760;
	private int second = 0xFF5B8DEF;
	private boolean running;
	private float t;
	private final long start = SystemClock.uptimeMillis();
	private final Runnable frame = new Runnable() {
		@Override
		public void run() {
			if (!running) return;
			t = (SystemClock.uptimeMillis() - start) / 1000f;
			invalidate();
			postOnAnimation(this);
		}
	};

	WelcomeBackdropView(Context ctx) {
		super(ctx);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
	}

	void setColors(int base, int accent, int second) {
		this.base = base;
		this.accent = accent;
		this.second = second;
		invalidate();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		running = true;
		postOnAnimation(frame);
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		running = false;
	}

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;
		c.drawColor(base);
		float s = Math.max(w, h);
		blob(c, w * (0.2f + 0.1f * (float) Math.sin(t * 0.21f)), h * (0.18f + 0.08f * (float) Math.cos(t * 0.17f)),
				s * 0.55f, accent, 0.30f);
		blob(c, w * (0.85f + 0.08f * (float) Math.cos(t * 0.19f)), h * (0.55f + 0.1f * (float) Math.sin(t * 0.23f)),
				s * 0.5f, second, 0.22f);
		blob(c, w * (0.4f + 0.12f * (float) Math.sin(t * 0.15f + 2f)), h * (0.95f + 0.05f * (float) Math.cos(t * 0.13f)),
				s * 0.5f, accent, 0.20f);
	}

	private void blob(Canvas c, float x, float y, float r, int color, float a) {
		paint.setShader(new RadialGradient(x, y, r, ColorUtils.setAlphaComponent(color, Math.round(255 * a)),
				ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP));
		c.drawCircle(x, y, r, paint);
		paint.setShader(null);
	}
}
