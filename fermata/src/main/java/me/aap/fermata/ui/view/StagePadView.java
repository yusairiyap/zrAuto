package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.AttrRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import me.aap.fermata.R;

/**
 * A top-down view of the room around the listener, for placing the sound: the listener's head is in
 * the middle, "front" is up, and the glowing puck is where the sound comes from. Two dots either
 * side of the puck are the left and right speakers (their distance is the Spread), and they circle
 * around the listener while Orbit is on. Drag the puck anywhere; the audio follows (see
 * {@link me.aap.fermata.media.engine.stage.StageDsp}). The arrow keys move it too, for a D-pad or
 * a rotary controller.
 * <p>
 * Positions are -100..100 to the right ({@code x}) and to the front ({@code y}).
 */
public class StagePadView extends View {
	private static final int KEY_STEP = 5;

	/** Told of every move of the puck, and of the end of a drag. */
	public interface Listener {
		void onPositionChanged(int x, int y, boolean finished);
	}

	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path nose = new Path();
	private final float density;
	private final int accent;
	private int discColor;
	private int lineColor;
	private int iconColor;
	private int textColor;
	private Listener listener;
	private int posX;
	private int posY = 100;
	private int spread = 30;
	private int orbit;
	private boolean active;
	private long orbitStart;

	public StagePadView(Context context) {
		this(context, null);
	}

	public StagePadView(Context context, AttributeSet attrs) {
		super(context, attrs);
		density = context.getResources().getDisplayMetrics().density;
		accent = ContextCompat.getColor(context, R.color.music_accent);
		discColor = color(R.attr.musicChipFill);
		lineColor = color(R.attr.musicSeekTrack);
		iconColor = color(R.attr.musicIconSecondary);
		textColor = color(R.attr.musicTextTertiary);
		stroke.setStyle(Paint.Style.STROKE);
		stroke.setStrokeWidth(Math.max(1f, density));
		text.setTextSize(11 * density);
		text.setTextAlign(Paint.Align.CENTER);
		setFocusable(true);
		setContentDescription(context.getString(R.string.stage_pad_description));
	}

	private int color(@AttrRes int attr) {
		TypedValue tv = new TypedValue();
		getContext().getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	public void setListener(Listener l) {
		listener = l;
	}

	/** Whether the 3D speaker is switched on (the puck is dimmed while it isn't). */
	public void setActive(boolean on) {
		if (active == on) return;
		active = on;
		invalidate();
	}

	public void setPosition(int x, int y) {
		x = Math.max(-100, Math.min(100, x));
		y = Math.max(-100, Math.min(100, y));
		if ((x == posX) && (y == posY)) return;
		posX = x;
		posY = y;
		invalidate();
	}

	public void setSpread(int degrees) {
		spread = degrees;
		invalidate();
	}

	/** Orbit speed in tenths of a revolution per minute; 0 = not circling. */
	public void setOrbit(int tenthsRpm) {
		if ((orbit == 0) && (tenthsRpm > 0)) orbitStart = System.nanoTime();
		orbit = tenthsRpm;
		invalidate();
	}

	private float radius() {
		return Math.min(getWidth(), getHeight()) / 2f - 14 * density;
	}

	@Override
	protected void onDraw(Canvas c) {
		float cx = getWidth() / 2f;
		float cy = getHeight() / 2f;
		float r = radius();
		float dim = active ? 1f : 0.45f;

		// The room: a disc with rings and a cross.
		fill.setStyle(Paint.Style.FILL);
		fill.setColor(discColor);
		c.drawCircle(cx, cy, r, fill);
		stroke.setColor(lineColor);
		for (int i = 1; i <= 3; i++) c.drawCircle(cx, cy, r * i / 3f, stroke);
		c.drawLine(cx - r, cy, cx + r, cy, stroke);
		c.drawLine(cx, cy - r, cx, cy + r, stroke);

		text.setColor(textColor);
		float ty = 11 * density * 0.35f;
		c.drawText(getContext().getString(R.string.stage_pos_front), cx, cy - r + 16 * density, text);
		c.drawText(getContext().getString(R.string.stage_pos_back), cx, cy + r - 8 * density, text);
		c.drawText(getContext().getString(R.string.stage_pos_left).substring(0, 1), cx - r + 12 * density, cy + ty, text);
		c.drawText(getContext().getString(R.string.stage_pos_right).substring(0, 1), cx + r - 12 * density, cy + ty, text);

		// The listener, looking up.
		fill.setColor(iconColor);
		c.drawCircle(cx, cy, 13 * density, fill);
		nose.reset();
		nose.moveTo(cx - 5 * density, cy - 11 * density);
		nose.lineTo(cx, cy - 20 * density);
		nose.lineTo(cx + 5 * density, cy - 11 * density);
		nose.close();
		c.drawPath(nose, fill);
		c.drawCircle(cx - 14 * density, cy, 3.5f * density, fill);
		c.drawCircle(cx + 14 * density, cy, 3.5f * density, fill);

		float px = cx + posX / 100f * r;
		float py = cy - posY / 100f * r;
		float dist = (float) Math.min(1, Math.hypot(posX, posY) / 100d);

		// The two speakers, either side of the puck's direction (turning with Orbit).
		double az = Math.atan2(posX, posY);
		if (orbit > 0) {
			double sec = (System.nanoTime() - orbitStart) / 1e9;
			az += 2 * Math.PI * (orbit / 10.0) / 60.0 * sec;
		}
		double sp = Math.toRadians(spread);
		stroke.setColor(ColorUtils.setAlphaComponent(accent, (int) (0x66 * dim)));
		for (int i = 0; i < 2; i++) {
			double a = az + ((i == 0) ? -sp : sp);
			float sx = cx + (float) Math.sin(a) * dist * r;
			float sy = cy - (float) Math.cos(a) * dist * r;
			if (orbit == 0) c.drawLine(px, py, sx, sy, stroke);
			fill.setColor(ColorUtils.setAlphaComponent(accent, (int) (0xB0 * dim)));
			c.drawCircle(sx, sy, 8 * density, fill);
			text.setColor(0xFF000000);
			c.drawText((i == 0) ? "L" : "R", sx, sy + ty, text);
		}

		// The puck.
		glow.setShader(new RadialGradient(px, py, 34 * density,
				ColorUtils.setAlphaComponent(accent, (int) (0x66 * dim)),
				ColorUtils.setAlphaComponent(accent, 0), Shader.TileMode.CLAMP));
		c.drawCircle(px, py, 34 * density, glow);
		fill.setColor(ColorUtils.setAlphaComponent(accent, (int) (0xFF * dim)));
		c.drawCircle(px, py, 13 * density, fill);
		fill.setColor(0xFF000000);
		c.drawCircle(px, py, 4 * density, fill);

		if (orbit > 0 && active) postInvalidateOnAnimation();
	}

	@Override
	public boolean onTouchEvent(MotionEvent e) {
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				getParent().requestDisallowInterceptTouchEvent(true);
				requestFocus();
				moveTo(e.getX(), e.getY(), false);
				return true;
			case MotionEvent.ACTION_MOVE:
				moveTo(e.getX(), e.getY(), false);
				return true;
			case MotionEvent.ACTION_UP:
				moveTo(e.getX(), e.getY(), true);
				performClick();
				return true;
			case MotionEvent.ACTION_CANCEL:
				moveTo(e.getX(), e.getY(), true);
				return true;
			default:
				return super.onTouchEvent(e);
		}
	}

	@Override
	public boolean performClick() {
		return super.performClick();
	}

	private void moveTo(float touchX, float touchY, boolean finished) {
		float r = radius();
		if (r <= 0) return;
		float dx = (touchX - getWidth() / 2f) / r;
		float dy = (getHeight() / 2f - touchY) / r;
		float len = (float) Math.hypot(dx, dy);
		if (len > 1f) {
			dx /= len;
			dy /= len;
		}
		set(Math.round(dx * 100), Math.round(dy * 100), finished);
	}

	private void set(int x, int y, boolean finished) {
		setPosition(x, y);
		if (listener != null) listener.onPositionChanged(posX, posY, finished);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		int dx = 0;
		int dy = 0;
		switch (keyCode) {
			case KeyEvent.KEYCODE_DPAD_LEFT -> dx = -KEY_STEP;
			case KeyEvent.KEYCODE_DPAD_RIGHT -> dx = KEY_STEP;
			case KeyEvent.KEYCODE_DPAD_UP -> dy = KEY_STEP;
			case KeyEvent.KEYCODE_DPAD_DOWN -> dy = -KEY_STEP;
			default -> {
				return super.onKeyDown(keyCode, event);
			}
		}
		int x = posX + dx;
		int y = posY + dy;
		float len = (float) Math.hypot(x, y);
		if (len > 100) {
			x = Math.round(x * 100 / len);
			y = Math.round(y * 100 / len);
		}
		set(x, y, true);
		return true;
	}

}
