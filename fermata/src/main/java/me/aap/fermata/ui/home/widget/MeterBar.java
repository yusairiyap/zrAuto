package me.aap.fermata.ui.home.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A thin rounded bar filled to a fraction; the fill glides to a new value. */
final class MeterBar extends View {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private int track;
	private int fill;
	private float target;
	private float shown;
	private boolean first = true;

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
		if (first || !isShown()) {
			first = false;
			shown = target = f;
			invalidate();
			return;
		}
		if (f == target) return;
		target = f;
		postInvalidateOnAnimation();
	}

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;
		float r = h / 2f;
		paint.setColor(track);
		rect.set(0, 0, w, h);
		c.drawRoundRect(rect, r, r, paint);
		shown += (target - shown) * 0.18f;
		boolean more = Math.abs(target - shown) > 0.002f;
		if (!more) shown = target;
		if (shown > 0f) {
			paint.setColor(fill);
			rect.set(0, 0, Math.max(h, w * shown), h);
			c.drawRoundRect(rect, r, r, paint);
		}
		if (more) postInvalidateOnAnimation();
	}
}
