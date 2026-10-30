package me.aap.fermata.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.Date;

import me.aap.fermata.R;
import me.aap.fermata.addon.sleep.SleepAnalyzer;
import me.aap.fermata.addon.sleep.SleepNight;
import me.aap.fermata.addon.sleep.SleepPrefs;

/**
 * A night drawn as the usual sleep chart: time across, the stage up and down (awake, REM, light, deep), the stretches as rounded
 * blocks that are drawn in from the left, and under them the phone's movement (and the room's sound when it was measured). Drag a finger
 * over it to read the time and stage under it. The stages are an estimate, which the card around it says.
 */
public final class SleepHypnogramView extends View {
	private static final int[] STAGE_COLORS = {0xFFFF8A65, 0xFFBA68C8, 0xFF64B5F6, 0xFF3F51B5};
	/** Rows top to bottom as stages: awake, REM, light, deep. */
	private static final int[] ROW_STAGE = {SleepAnalyzer.AWAKE, SleepAnalyzer.REM, SleepAnalyzer.LIGHT, SleepAnalyzer.DEEP};
	private final SettingsStyle.Colors c;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private final RectF rect = new RectF();
	private final String[] stageNames;
	@Nullable
	private SleepAnalyzer.Result result;
	private float p = 1f;
	private float touchX = -1f;
	@Nullable
	private ValueAnimator anim;

	public SleepHypnogramView(Context ctx, SettingsStyle.Colors colors) {
		super(ctx);
		c = colors;
		stageNames = ctx.getResources().getStringArray(R.array.sleep_stages);
	}

	public void setResult(@Nullable SleepAnalyzer.Result r) {
		result = r;
		touchX = -1f;
		replay();
	}

	/** Draws the night in again from the left. */
	public void replay() {
		if (anim != null) anim.cancel();
		if (!SleepPrefs.animate() || !isShown()) {
			p = 1f;
			invalidate();
			return;
		}
		p = 0f;
		anim = ValueAnimator.ofFloat(0f, 1f);
		anim.setDuration(1300);
		anim.setInterpolator(new DecelerateInterpolator(1.6f));
		anim.addUpdateListener(a -> {
			p = (float) a.getAnimatedValue();
			invalidate();
		});
		anim.start();
	}

	@Override
	protected void onDetachedFromWindow() {
		if (anim != null) anim.cancel();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onMeasure(int w, int h) {
		boolean strip = (result != null) && result.fromSensors;
		setMeasuredDimension(resolveSize(c.dp(300), w), c.dp(strip ? 250 : 200));
	}

	private float sp(float v) {
		return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
	}

	private float plotLeft() {
		return c.dp(58);
	}

	private float plotRight() {
		return getWidth() - c.dp(8);
	}

	@Override
	public boolean onTouchEvent(MotionEvent e) {
		if (result == null) return false;
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
				getParent().requestDisallowInterceptTouchEvent(true);
				touchX = Math.max(plotLeft(), Math.min(plotRight(), e.getX()));
				invalidate();
			}
			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				getParent().requestDisallowInterceptTouchEvent(false);
				touchX = -1f;
				invalidate();
			}
			default -> {
			}
		}
		return true;
	}

	@Override
	protected void onDraw(Canvas canvas) {
		SleepAnalyzer.Result r = result;
		int w = getWidth();
		int h = getHeight();
		paint.setTextAlign(Paint.Align.CENTER);
		if (r == null) {
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(c.textSecondary);
			paint.setTextSize(sp(13));
			canvas.drawText(getContext().getString(R.string.sleep_no_night), w / 2f, h / 2f, paint);
			return;
		}
		boolean strip = r.fromSensors;
		float stripH = strip ? c.dp(44) : 0;
		float left = plotLeft();
		float right = plotRight();
		float top = c.dp(8);
		float axisH = c.dp(22);
		float bottom = h - axisH - stripH - (strip ? c.dp(6) : 0);
		float rowH = (bottom - top) / 4f;
		float plotW = right - left;
		int n = r.epochs;
		long span = n * SleepNight.EPOCH_MS;

		// Rows: a faint band each, the stage's name at the left.
		paint.setStyle(Paint.Style.FILL);
		for (int i = 0; i < 4; i++) {
			float y = top + i * rowH;
			paint.setColor((c.textSecondary & 0xFFFFFF) | ((i % 2 == 0) ? 0x14000000 : 0x0A000000));
			rect.set(left, y, right, y + rowH);
			canvas.drawRect(rect, paint);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(sp(11));
			paint.setColor(c.textSecondary);
			canvas.drawText(stageNames[ROW_STAGE[i]], c.dp(6), y + rowH / 2f + sp(4), paint);
		}

		// Time marks.
		java.util.Calendar cal = java.util.Calendar.getInstance();
		cal.setTimeInMillis(r.night.start);
		cal.set(java.util.Calendar.MINUTE, 0);
		cal.set(java.util.Calendar.SECOND, 0);
		cal.set(java.util.Calendar.MILLISECOND, 0);
		int stepH = (span > 9 * 3600_000L) ? 2 : 1;
		paint.setTextAlign(Paint.Align.CENTER);
		paint.setTextSize(sp(10.5f));
		cal.add(java.util.Calendar.HOUR_OF_DAY, 1);
		for (; cal.getTimeInMillis() < r.night.start + span; cal.add(java.util.Calendar.HOUR_OF_DAY, stepH)) {
			float x = left + plotW * (cal.getTimeInMillis() - r.night.start) / (float) span;
			if (x > right - c.dp(14)) break;
			paint.setColor((c.textSecondary & 0xFFFFFF) | 0x22000000);
			canvas.drawRect(x - 0.5f, top, x + 0.5f, bottom, paint);
			paint.setColor(c.textSecondary);
			canvas.drawText(DateFormat.getTimeFormat(getContext()).format(new Date(cal.getTimeInMillis())), x,
					bottom + c.dp(16), paint);
		}
		paint.setTextAlign(Paint.Align.LEFT);
		paint.setColor(c.textSecondary);
		canvas.drawText(DateFormat.getTimeFormat(getContext()).format(new Date(r.night.start)), left, bottom + c.dp(16), paint);

		// The stretches, drawn in from the left.
		canvas.save();
		canvas.clipRect(left, 0, left + plotW * p + 1, h);
		int i = 0;
		while (i < n) {
			int st = r.stage[i];
			int j = i;
			while ((j < n) && (r.stage[j] == st)) j++;
			int row = rowOf(st);
			float x0 = left + plotW * i / n;
			float x1 = left + plotW * j / n;
			float y = top + row * rowH;
			float pad = rowH * 0.18f;
			paint.setColor(STAGE_COLORS[st]);
			rect.set(x0, y + pad, Math.max(x1, x0 + c.dp(2)), y + rowH - pad);
			canvas.drawRoundRect(rect, c.dp(3), c.dp(3), paint);
			// A thin link to the next stretch.
			if (j < n) {
				int row2 = rowOf(r.stage[j]);
				float y1 = top + row * rowH + rowH / 2f;
				float y2 = top + row2 * rowH + rowH / 2f;
				paint.setColor((STAGE_COLORS[st] & 0xFFFFFF) | 0x66000000);
				rect.set(x1 - c.dp(0.75f), Math.min(y1, y2), x1 + c.dp(0.75f), Math.max(y1, y2));
				canvas.drawRect(rect, paint);
			}
			i = j;
		}

		// Movement and sound.
		if (strip) {
			float sy = bottom + c.dp(6) + axisH - c.dp(4);
			float sb = h - c.dp(2);
			float st0 = sb - stripH;
			paint.setColor((c.textSecondary & 0xFFFFFF) | 0x33000000);
			paint.setTextSize(sp(10));
			paint.setTextAlign(Paint.Align.LEFT);
			int cols = Math.max(1, (int) (plotW / c.dp(3)));
			byte[] mv = r.night.move;
			byte[] ns = r.night.noise;
			float bw = plotW / cols;
			for (int k = 0; k < cols; k++) {
				int a = (int) ((long) k * n / cols);
				int b = Math.max(a + 1, (int) ((long) (k + 1) * n / cols));
				int mx = 0;
				for (int e = a; (e < b) && (e < mv.length); e++) mx = Math.max(mx, mv[e] & 0xFF);
				float bh = (sb - st0) * Math.min(1f, mx / 160f);
				paint.setColor(c.accent);
				paint.setAlpha(190);
				rect.set(left + k * bw + bw * 0.15f, sb - Math.max(bh, c.dp(1)), left + (k + 1) * bw - bw * 0.15f, sb);
				canvas.drawRect(rect, paint);
			}
			if (ns != null && r.hasNoise) {
				path.reset();
				for (int k = 0; k < cols; k++) {
					int a = (int) ((long) k * n / cols);
					int b = Math.max(a + 1, (int) ((long) (k + 1) * n / cols));
					int mx = 0;
					for (int e = a; (e < b) && (e < ns.length); e++) mx = Math.max(mx, ns[e] & 0xFF);
					float x = left + (k + 0.5f) * bw;
					float y = sb - (sb - st0) * mx / 255f;
					if (k == 0) path.moveTo(x, y);
					else path.lineTo(x, y);
				}
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(c.dp(1.4f));
				paint.setColor(0xFFFFB74D);
				canvas.drawPath(path, paint);
				paint.setStyle(Paint.Style.FILL);
			}
			paint.setColor(c.textSecondary);
			paint.setTypeface(Typeface.DEFAULT);
			canvas.drawText(getContext().getString(R.string.sleep_movement), c.dp(6), sb - c.dp(2), paint);
		}
		canvas.restore();

		// The finger.
		if (touchX >= 0) {
			float frac = (touchX - left) / plotW;
			int e = Math.max(0, Math.min(n - 1, (int) (frac * n)));
			long t = r.night.start + e * SleepNight.EPOCH_MS;
			paint.setColor(c.accent);
			canvas.drawRect(touchX - c.dp(0.75f), top, touchX + c.dp(0.75f), bottom, paint);
			String label = DateFormat.getTimeFormat(getContext()).format(new Date(t)) + "  " + stageNames[r.stage[e]];
			paint.setTextSize(sp(12));
			paint.setTypeface(Typeface.DEFAULT_BOLD);
			float tw = paint.measureText(label) + c.dp(16);
			float bx = Math.max(left, Math.min(right - tw, touchX - tw / 2f));
			// The accent with its own contrasting text reads in every theme (the tile fill is see-through).
			paint.setColor(c.accent | 0xFF000000);
			rect.set(bx, top - c.dp(2), bx + tw, top + c.dp(24));
			canvas.drawRoundRect(rect, c.dp(8), c.dp(8), paint);
			paint.setColor(c.onAccent | 0xFF000000);
			paint.setTextAlign(Paint.Align.LEFT);
			canvas.drawText(label, bx + c.dp(8), top + c.dp(15), paint);
			paint.setTypeface(Typeface.DEFAULT);
		}
	}

	private static int rowOf(int stage) {
		for (int i = 0; i < ROW_STAGE.length; i++) if (ROW_STAGE[i] == stage) return i;
		return 0;
	}
}
