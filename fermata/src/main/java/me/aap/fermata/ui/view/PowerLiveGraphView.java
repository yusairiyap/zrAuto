package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toPx;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * The Power tab's live graph: the last couple of minutes of current as a smooth line, with a dashed
 * zero line, the area between the line and zero tinted (green above it while charging, orange below
 * it while the phone draws from the battery), a dot on the newest reading, and the scale on the
 * left. The scale only ever grows while the readings do, and eases back down when they settle.
 */
public class PowerLiveGraphView extends View {
	private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint zero = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private final Path area = new Path();
	private float[] values = new float[0];
	private int chargeColor = 0xFF1ED760;
	private int drawColor = 0xFFFFB020;
	private float range = 250f;

	public PowerLiveGraphView(Context ctx) {
		this(ctx, null);
	}

	public PowerLiveGraphView(Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
		line.setStyle(Paint.Style.STROKE);
		line.setStrokeWidth(toPx(ctx, 5) / 2f);
		line.setStrokeCap(Paint.Cap.ROUND);
		line.setStrokeJoin(Paint.Join.ROUND);
		fill.setStyle(Paint.Style.FILL);
		zero.setStyle(Paint.Style.STROKE);
		zero.setStrokeWidth(toPx(ctx, 1));
		zero.setPathEffect(new DashPathEffect(new float[]{toPx(ctx, 3), toPx(ctx, 4)}, 0));
		text.setTextSize(toPx(ctx, 11));
		text.setTextAlign(Paint.Align.LEFT);
	}

	public void setColors(int charge, int draw, int grid, int textColor) {
		chargeColor = charge;
		drawColor = draw;
		zero.setColor(grid);
		text.setColor(textColor);
		invalidate();
	}

	/** The readings in mA, oldest first. */
	public void setData(float[] values) {
		this.values = values;
		float max = 0;
		for (float v : values) max = Math.max(max, Math.abs(v));
		float target = niceRange(max);
		// Grows at once, eases down.
		range = (target >= range) ? target : range + (target - range) * 0.15f;
		invalidate();
	}

	private static float niceRange(float v) {
		float[] steps = {100, 200, 300, 500, 750, 1000, 1500, 2000, 3000, 4000, 6000, 10000};
		for (float s : steps) if (v <= s * 0.9f) return s;
		return steps[steps.length - 1];
	}

	@Override
	protected void onDraw(Canvas c) {
		float left = getPaddingLeft();
		float top = getPaddingTop() + toPx(getContext(), 6);
		float w = getWidth() - left - getPaddingRight();
		float h = getHeight() - top - getPaddingBottom() - toPx(getContext(), 6);
		if ((w <= 0) || (h <= 0)) return;
		float labelW = text.measureText("-10000") + toPx(getContext(), 6);
		float x0 = left + labelW;
		float gw = w - labelW;
		float mid = top + h / 2f;
		float half = h / 2f;

		c.drawLine(x0, mid, x0 + gw, mid, zero);
		text.setAlpha(255);
		c.drawText("+" + Math.round(range), left, top + text.getTextSize() * 0.8f, text);
		c.drawText("0", left, mid + text.getTextSize() * 0.35f, text);
		c.drawText("-" + Math.round(range), left, top + h, text);

		int n = values.length;
		if (n < 2) return;
		float step = gw / (GRAPH_POINTS - 1);
		// New readings come in at the right, so the line fills from the right edge.
		float startX = x0 + gw - step * (n - 1);
		path.reset();
		float px = startX;
		float py = yOf(values[0], mid, half);
		path.moveTo(px, py);
		for (int i = 1; i < n; i++) {
			float x = startX + step * i;
			float y = yOf(values[i], mid, half);
			float mx = (px + x) / 2f;
			path.cubicTo(mx, py, mx, y, x, y);
			px = x;
			py = y;
		}
		float lastX = px;
		float lastY = py;

		area.set(path);
		area.lineTo(lastX, mid);
		area.lineTo(startX, mid);
		area.close();

		// Above zero the area is green, below it the orange of a draw.
		c.save();
		c.clipRect(x0, top, x0 + gw, mid);
		fill.setShader(new LinearGradient(0, top, 0, mid, withAlpha(chargeColor, 120),
				withAlpha(chargeColor, 0), Shader.TileMode.CLAMP));
		c.drawPath(area, fill);
		c.restore();
		c.save();
		c.clipRect(x0, mid, x0 + gw, top + h);
		fill.setShader(new LinearGradient(0, top + h, 0, mid, withAlpha(drawColor, 120),
				withAlpha(drawColor, 0), Shader.TileMode.CLAMP));
		c.drawPath(area, fill);
		c.restore();

		line.setColor((values[n - 1] > 0) ? chargeColor : drawColor);
		c.drawPath(path, line);
		dot.setColor(0xFFFFFFFF);
		c.drawCircle(lastX, lastY, toPx(getContext(), 5), dot);
		dot.setColor(line.getColor());
		c.drawCircle(lastX, lastY, toPx(getContext(), 3), dot);
	}

	private float yOf(float v, float mid, float half) {
		return mid - Math.max(-1f, Math.min(1f, v / range)) * half;
	}

	private static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | (alpha << 24);
	}

	/** The graph holds this many readings across its width (what the tracker keeps). */
	private static final int GRAPH_POINTS = 120;
}
