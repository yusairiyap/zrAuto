package me.aap.fermata.ui.home.widget;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * A soft band of light that travels along a fill while a timer runs, so the bar reads as alive even when a
 * long timer moves only a few pixels a second. It is drawn only inside the fill, clipped by the caller's shape,
 * and it asks for frames at a steady 30 a second while it is on (no more).
 */
final class FillSheen {
	/** The time one pass of the band across the fill takes. */
	private static final long PERIOD_MS = 2600;
	/** The gap between frames while the band runs: about 30 a second. */
	static final long FRAME_MS = 33;
	private static final int[] COLORS = {0x00FFFFFF, 0x3DFFFFFF, 0x00FFFFFF};
	private static final float[] POSITIONS = {0f, 0.5f, 1f};
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Matrix matrix = new Matrix();
	private LinearGradient shader;
	private int shaderHeight = -1;
	private float band;
	private boolean on;

	boolean isOn() {
		return on;
	}

	void setOn(boolean on) {
		this.on = on;
	}

	/**
	 * Draws the band over the fill {@code (0, 0, fillWidth, height)} with corners {@code radius}, at time {@code now}.
	 */
	void draw(Canvas c, float fillWidth, float height, float radius, long now) {
		if ((!on) || (fillWidth <= 0f) || (height <= 0f)) return;
		if (height != shaderHeight) {
			shaderHeight = (int) height;
			band = Math.max(height * 3f, 24f);
			shader = new LinearGradient(0, 0, band, 0, COLORS, POSITIONS, Shader.TileMode.CLAMP);
		}
		float phase = (now % PERIOD_MS) / (float) PERIOD_MS;
		float x = -band + phase * (fillWidth + band);
		matrix.setTranslate(x, 0);
		shader.setLocalMatrix(matrix);
		paint.setShader(shader);
		c.drawRoundRect(0, 0, fillWidth, height, radius, radius, paint);
		paint.setShader(null);
	}
}
