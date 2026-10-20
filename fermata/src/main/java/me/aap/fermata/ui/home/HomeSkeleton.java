package me.aap.fermata.ui.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Grey rows with a light sweeping over them, standing in for a list while it loads: a picture
 * square and two lines of text per row, as many rows as fit. It only animates while it is on screen.
 */
public final class HomeSkeleton extends View {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final Matrix matrix = new Matrix();
	private final float dp;
	private final LinearGradient sweep;
	private int base = 0x33FFFFFF;
	private int rowDp = 48;
	private int thumbW = 40;
	private int thumbH = 40;
	private boolean thumbs = true;
	private int cols = 1;
	private float phase;
	private ValueAnimator anim;

	public HomeSkeleton(Context ctx) {
		super(ctx);
		dp = ctx.getResources().getDisplayMetrics().density;
		sweep = new LinearGradient(0, 0, 1, 0, new int[]{0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF},
				new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
	}

	/** The colour of the placeholder shapes. */
	public void setColor(int color) {
		base = color;
		invalidate();
	}

	/** Row height and picture size in dp (a width of 0 for no picture) and how many columns of rows. */
	public void setRows(int rowDp, int thumbW, int thumbH, int cols) {
		this.rowDp = rowDp;
		this.thumbW = thumbW;
		this.thumbH = thumbH;
		this.thumbs = thumbW > 0;
		this.cols = Math.max(1, cols);
		invalidate();
	}

	@Override
	public void onVisibilityAggregated(boolean visible) {
		super.onVisibilityAggregated(visible);
		if (visible && (anim == null)) {
			anim = ValueAnimator.ofFloat(0f, 1f);
			anim.setDuration(1300);
			anim.setRepeatCount(ValueAnimator.INFINITE);
			anim.setInterpolator(new LinearInterpolator());
			anim.addUpdateListener(a -> {
				phase = (float) a.getAnimatedValue();
				invalidate();
			});
			anim.start();
		} else if (!visible && (anim != null)) {
			anim.cancel();
			anim = null;
		}
	}

	@Override
	protected void onDraw(Canvas c) {
		if ((getWidth() == 0) || (getHeight() == 0)) return;
		paint.setShader(null);
		paint.setColor(base);
		shapes(c);
		// The light: the same shapes again, drawn with a moving soft band over them.
		float band = Math.max(getWidth() * 0.45f, 120 * dp);
		matrix.reset();
		matrix.setScale(band, 1f);
		matrix.postTranslate(-band + (getWidth() + band) * phase, 0f);
		sweep.setLocalMatrix(matrix);
		paint.setShader(sweep);
		shapes(c);
		paint.setShader(null);
	}

	private void shapes(Canvas c) {
		float row = rowDp * dp;
		int rows = Math.max(1, (int) (getHeight() / row));
		float gutter = 10 * dp;
		float colW = (getWidth() - gutter * (cols - 1)) / cols;
		for (int col = 0; col < cols; col++) {
			float x0 = col * (colW + gutter);
			for (int r = 0; r < rows; r++) {
				float cy = r * row + row / 2f;
				float tx = x0;
				if (thumbs) {
					float tw = thumbW * dp;
					float th = thumbH * dp;
					rect.set(x0, cy - th / 2f, x0 + tw, cy + th / 2f);
					c.drawRoundRect(rect, 10 * dp, 10 * dp, paint);
					tx = x0 + tw + 10 * dp;
				}
				float avail = x0 + colW - tx;
				float line1 = avail * (0.62f + 0.2f * ((r * 7 + col * 3) % 3) / 2f);
				float line2 = avail * (0.36f + 0.16f * ((r * 5 + col) % 3) / 2f);
				rect.set(tx, cy - 14 * dp, tx + line1, cy - 2 * dp);
				c.drawRoundRect(rect, 6 * dp, 6 * dp, paint);
				rect.set(tx, cy + 4 * dp, tx + line2, cy + 14 * dp);
				c.drawRoundRect(rect, 5 * dp, 5 * dp, paint);
			}
		}
	}
}
