package me.aap.fermata.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import me.aap.fermata.R;
import me.aap.fermata.addon.sleep.SleepAnalyzer;
import me.aap.fermata.addon.sleep.SleepPrefs;

/**
 * The last nights as bars that grow in one after another: either how long each was asleep with the goal as a dashed line, or when
 * each was in bed (a floating bar from bedtime to waking, so a regular bedtime is a row of bars at the same height). The bars take
 * the colour of the night's score. A tap picks a night; the one picked is drawn in full, the others dimmer.
 */
public final class SleepBarsView extends View {
	public static final int MODE_DURATION = 0;
	public static final int MODE_TIMES = 1;
	private final SettingsStyle.Colors c;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private List<SleepAnalyzer.Result> all = new ArrayList<>();
	private int range = 7;
	private int mode = MODE_DURATION;
	private int goalMin = 480;
	private int targetBed = 23 * 60;
	private int targetWake = 7 * 60;
	private int selected = -1;
	private float p = 1f;
	@Nullable
	private IntConsumer onSelect;
	@Nullable
	private ValueAnimator anim;

	public SleepBarsView(Context ctx, SettingsStyle.Colors colors) {
		super(ctx);
		c = colors;
	}

	public void setOnSelect(@Nullable IntConsumer l) {
		onSelect = l;
	}

	/** The nights, oldest first, and the goal and usual times that are drawn as guide lines. */
	public void setData(List<SleepAnalyzer.Result> results, int goalMin, int bedMin, int wakeMin) {
		all = new ArrayList<>(results);
		this.goalMin = goalMin;
		targetBed = bedMin;
		targetWake = wakeMin;
		if (selected >= all.size()) selected = all.size() - 1;
		replay();
	}

	public void setRange(int nights) {
		range = nights;
		replay();
	}

	public void setMode(int m) {
		mode = m;
		replay();
	}

	/** Index in the list given to {@link #setData}. */
	public void setSelected(int index) {
		selected = index;
		invalidate();
	}

	public void replay() {
		if (anim != null) anim.cancel();
		if (!SleepPrefs.animate() || !isShown()) {
			p = 1f;
			invalidate();
			return;
		}
		p = 0f;
		anim = ValueAnimator.ofFloat(0f, 1f);
		anim.setDuration(1000);
		anim.setInterpolator(new DecelerateInterpolator(1.5f));
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
		setMeasuredDimension(resolveSize(c.dp(300), w), c.dp(210));
	}

	private float sp(float v) {
		return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
	}

	private int first() {
		return Math.max(0, all.size() - range);
	}

	private float plotLeft() {
		return c.dp(42);
	}

	@Override
	public boolean onTouchEvent(MotionEvent e) {
		int n = all.size() - first();
		if (n <= 0) return false;
		if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
		if (e.getActionMasked() == MotionEvent.ACTION_UP) {
			float left = plotLeft();
			float slot = (getWidth() - c.dp(8) - left) / n;
			int i = (int) ((e.getX() - left) / slot);
			if ((i >= 0) && (i < n)) {
				selected = first() + i;
				invalidate();
				if (onSelect != null) onSelect.accept(selected);
			}
			performClick();
			return true;
		}
		return super.onTouchEvent(e);
	}

	@Override
	public boolean performClick() {
		return super.performClick();
	}

	private int scoreColor(int score) {
		return (score >= 85) ? 0xFF81C784 : (score >= 70) ? 0xFF64B5F6 : (score >= 55) ? 0xFFFFB74D : 0xFFE57373;
	}

	/** Minutes after 18:00 (a night's day starts in the evening), for the time of day {@code minute}. */
	private static int rel(int minute) {
		return Math.floorMod(minute - 18 * 60, 1440);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		int w = getWidth();
		int h = getHeight();
		int total = all.size();
		int from = first();
		int n = total - from;
		paint.setTextAlign(Paint.Align.CENTER);
		paint.setStyle(Paint.Style.FILL);
		if (n <= 0) {
			paint.setColor(c.textSecondary);
			paint.setTextSize(sp(13));
			canvas.drawText(getContext().getString(R.string.sleep_no_night), w / 2f, h / 2f, paint);
			return;
		}
		float left = plotLeft();
		float right = w - c.dp(8);
		float top = c.dp(10);
		float bottom = h - c.dp(24);
		float plotH = bottom - top;
		float slot = (right - left) / n;
		float bw = Math.min(c.dp(30), slot * 0.62f);

		// The scale.
		float min = 0;
		float max;
		float step;
		if (mode == MODE_DURATION) {
			float hi = goalMin;
			for (int i = from; i < total; i++) hi = Math.max(hi, all.get(i).asleepMs / 60_000f);
			max = (float) Math.ceil(hi / 120f) * 120f;
			step = 120f;
		} else {
			float lo = Math.min(rel(targetBed), rel(targetWake));
			float hi = Math.max(rel(targetBed), rel(targetWake));
			for (int i = from; i < total; i++) {
				SleepAnalyzer.Result r = all.get(i);
				float s = rel(minuteOf(r.bedTime()));
				float e = s + (r.wakeTime() - r.bedTime()) / 60_000f;
				lo = Math.min(lo, s);
				hi = Math.max(hi, e);
			}
			min = (float) Math.floor((lo - 30) / 60f) * 60f;
			max = (float) Math.ceil((hi + 30) / 60f) * 60f;
			step = ((max - min) > 600) ? 180f : 120f;
		}
		paint.setTextSize(sp(10.5f));
		paint.setTextAlign(Paint.Align.RIGHT);
		for (float v = min; v <= max + 0.1f; v += step) {
			float y = yOf(v, min, max, top, bottom);
			paint.setColor((c.textSecondary & 0xFFFFFF) | 0x22000000);
			canvas.drawRect(left, y - 0.5f, right, y + 0.5f, paint);
			paint.setColor(c.textSecondary);
			String label = (mode == MODE_DURATION) ? String.format(Locale.getDefault(), "%dh", Math.round(v / 60f))
					: clockLabel(Math.round(v) + 18 * 60);
			canvas.drawText(label, left - c.dp(6), y + sp(3.5f), paint);
		}

		// Guide lines.
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(c.dp(1.2f));
		paint.setPathEffect(new DashPathEffect(new float[]{c.dp(5), c.dp(4)}, 0));
		paint.setColor(c.accent);
		if (mode == MODE_DURATION) {
			float y = yOf(goalMin, min, max, top, bottom);
			canvas.drawLine(left, y, right, y, paint);
		} else {
			float y1 = yOf(rel(targetBed), min, max, top, bottom);
			float y2 = yOf(rel(targetWake), min, max, top, bottom);
			canvas.drawLine(left, y1, right, y1, paint);
			canvas.drawLine(left, y2, right, y2, paint);
		}
		paint.setPathEffect(null);
		paint.setStyle(Paint.Style.FILL);

		// The bars.
		Calendar cal = Calendar.getInstance();
		for (int k = 0; k < n; k++) {
			SleepAnalyzer.Result r = all.get(from + k);
			float own = Math.max(0f, Math.min(1f, (p * (n + 3) - k) / 3f));
			float cx = left + slot * (k + 0.5f);
			int color = scoreColor(r.score);
			boolean sel = (from + k) == selected;
			paint.setColor(color);
			paint.setAlpha(sel || selected < 0 ? 255 : 150);
			float yTop;
			float yBot;
			if (mode == MODE_DURATION) {
				yBot = bottom;
				yTop = bottom - plotH * (r.asleepMs / 60_000f) / max * own;
			} else {
				float s = rel(minuteOf(r.bedTime()));
				float e = s + (r.wakeTime() - r.bedTime()) / 60_000f;
				// Time runs down the page: the bedtime is the top end. The bar grows out from its middle.
				float ys = yOf(s, min, max, top, bottom);
				float ye = yOf(e, min, max, top, bottom);
				float mid = (ys + ye) / 2f;
				float half = (ye - ys) / 2f * own;
				yTop = mid - half;
				yBot = mid + half;
			}
			if (yBot - yTop < c.dp(2)) yTop = yBot - c.dp(2);
			rect.set(cx - bw / 2f, yTop, cx + bw / 2f, yBot);
			canvas.drawRoundRect(rect, c.dp(5), c.dp(5), paint);
			paint.setAlpha(255);

			// The label under it.
			cal.setTimeInMillis(r.night.start);
			boolean labelIt = (n <= 8) || (k % 5 == 0) || (k == n - 1);
			if (labelIt) {
				paint.setColor(sel ? c.textPrimary : c.textSecondary);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(sp(10.5f));
				String label = (n <= 8) ? DateFormat.format("EEE", cal.getTime()).toString() :
						String.valueOf(cal.get(Calendar.DAY_OF_MONTH));
				canvas.drawText(label, cx, h - c.dp(7), paint);
			}
			if (sel && (mode == MODE_DURATION) && own > 0.99f) {
				paint.setColor(c.textPrimary);
				paint.setTextSize(sp(10.5f));
				paint.setTextAlign(Paint.Align.CENTER);
				long m = r.asleepMs / 60_000L;
				canvas.drawText(String.format(Locale.getDefault(), "%d:%02d", m / 60, m % 60), cx, yTop - c.dp(4), paint);
			}
		}
	}

	/** Time of day runs down the page, a duration up from the bottom. */
	private float yOf(float v, float min, float max, float top, float bottom) {
		float f = (v - min) / (max - min);
		return (mode == MODE_DURATION) ? bottom - (bottom - top) * f : top + (bottom - top) * f;
	}

	private static int minuteOf(long time) {
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(time);
		return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
	}

	private String clockLabel(int minuteOfDay) {
		int m = Math.floorMod(minuteOfDay, 1440);
		Calendar cal = Calendar.getInstance();
		cal.set(Calendar.HOUR_OF_DAY, m / 60);
		cal.set(Calendar.MINUTE, m % 60);
		Date d = cal.getTime();
		return DateFormat.getTimeFormat(getContext()).format(d);
	}
}
