package me.aap.fermata.ui.home.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * A thin rounded bar filled to a fraction. The fill eases to a new value over about a second, and while a timer runs a
 * band of light travels along it (see {@link FillSheen}).
 */
final class MeterBar extends View {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final MeterGlide glide = new MeterGlide();
	private final FillSheen sheen = new FillSheen();
	private int track;
	private int fill;

	MeterBar(Context ctx) {
		super(ctx);
	}

	void setColors(int track, int fill) {
		this.track = track;
		this.fill = fill;
		invalidate();
	}

	void setFraction(float f) {
		f = Math.max(0f, Math.min(1f, f));
		if (!glide.isSet() || !isShown()) {
			glide.jump(f);
			invalidate();
			return;
		}
		if (f == glide.target()) return;
		glide.glideTo(f, SystemClock.uptimeMillis());
		postInvalidateOnAnimation();
	}

	/** Whether the band of light runs along the fill (a timer is counting down). */
	void setRunning(boolean on) {
		if (sheen.isOn() == on) return;
		sheen.setOn(on);
		invalidate();
	}

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;
		float r = h / 2f;
		long now = SystemClock.uptimeMillis();
		paint.setColor(track);
		rect.set(0, 0, w, h);
		c.drawRoundRect(rect, r, r, paint);
		float shown = glide.value(now);
		if (shown > 0f) {
			float fw = Math.max(h, w * shown);
			paint.setColor(fill);
			rect.set(0, 0, fw, h);
			c.drawRoundRect(rect, r, r, paint);
			sheen.draw(c, fw, h, r, now);
		}
		if (glide.moving(now)) postInvalidateOnAnimation();
		else if (sheen.isOn()) postInvalidateDelayed(FillSheen.FRAME_MS);
	}
}
