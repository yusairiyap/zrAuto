package me.aap.fermata.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.Date;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.addon.sleep.SleepInsights;
import me.aap.fermata.addon.sleep.SleepNight;
import me.aap.fermata.addon.sleep.SleepPrefs;

/**
 * What a night cost the battery, in the look of the Power usage tab: bars for the current drawn in each hour of the night (an hour
 * mostly on the charger is a faint outlined bar), and under them a breakdown of what the phone was doing: screen on, an ambience
 * sound playing, or quiet, each with its share of the energy, its average current and its time. The current is the whole phone's, as on
 * the Power tab; the split says only what was going on while it was drawn.
 */
public final class SleepPowerView extends View {
	private static final int CATS = 3;
	private static final int CAT_SCREEN = 0;
	private static final int CAT_AMBIENCE = 1;
	private static final int CAT_QUIET = 2;
	private final SettingsStyle.Colors c;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final String[] names;
	private final float[] hourMa = new float[24];
	private final boolean[] hourCharging = new boolean[24];
	private final long[] catMs = new long[CATS];
	private final float[] catMa = new float[CATS];
	private final float[] catShare = new float[CATS];
	private long chargeMs;
	private int hours;
	private long firstHourStart;
	private float maxMa = 1f;
	private float p = 1f;
	@Nullable
	private ValueAnimator anim;

	public SleepPowerView(Context ctx, SettingsStyle.Colors colors) {
		super(ctx);
		c = colors;
		names = new String[]{ctx.getString(R.string.sleep_power_screen), ctx.getString(R.string.sleep_power_ambience),
				ctx.getString(R.string.sleep_power_quiet)};
	}

	private float sp(float v) {
		return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
	}

	/** Shows the night; false when it has nothing to break down. */
	public boolean setNight(@Nullable SleepNight n, boolean animate) {
		hours = 0;
		java.util.Arrays.fill(catMs, 0);
		java.util.Arrays.fill(catMa, 0);
		java.util.Arrays.fill(catShare, 0);
		chargeMs = 0;
		if ((n == null) || !n.hasPowerSeries()) {
			requestLayout();
			invalidate();
			return false;
		}
		byte[] pw = n.power;
		int len = pw.length;
		hours = Math.min(24, (int) Math.ceil(len / 120.0));
		firstHourStart = n.start;
		float[] sum = new float[24];
		int[] cnt = new int[24];
		int[] chg = new int[24];
		float[] catSum = new float[CATS];
		int[] catN = new int[CATS];
		for (int i = 0; i < len; i++) {
			int v = pw[i] & 0xFF;
			if (v == 0) continue;
			int h = Math.min(23, i / 120);
			int cat = category(n, i);
			if (v == SleepNight.POWER_CHARGING) {
				chg[h]++;
				chargeMs += SleepNight.EPOCH_MS;
				continue;
			}
			float ma = v * 10f;
			sum[h] += ma;
			cnt[h]++;
			catSum[cat] += ma;
			catN[cat]++;
		}
		maxMa = 1f;
		float total = 0;
		for (int h = 0; h < hours; h++) {
			hourCharging[h] = (chg[h] > cnt[h]);
			hourMa[h] = (cnt[h] == 0) ? 0f : sum[h] / cnt[h];
			maxMa = Math.max(maxMa, hourMa[h]);
		}
		for (int k = 0; k < CATS; k++) {
			catMs[k] = catN[k] * SleepNight.EPOCH_MS;
			catMa[k] = (catN[k] == 0) ? 0f : catSum[k] / catN[k];
			total += catSum[k];
		}
		for (int k = 0; k < CATS; k++) catShare[k] = (total > 0) ? catSum[k] / total : 0f;
		requestLayout();
		replay(animate);
		return true;
	}

	private static int category(SleepNight n, int i) {
		int f = ((n.flags != null) && (i < n.flags.length)) ? n.flags[i] : 0;
		if ((f & SleepNight.FLAG_SCREEN) != 0) return CAT_SCREEN;
		if ((f & SleepNight.FLAG_AMBIENCE) != 0) return CAT_AMBIENCE;
		return CAT_QUIET;
	}

	private void replay(boolean animate) {
		if (anim != null) anim.cancel();
		if (!animate || !SleepPrefs.animate() || !isShown()) {
			p = 1f;
			invalidate();
			return;
		}
		p = 0f;
		anim = ValueAnimator.ofFloat(0f, 1f);
		anim.setDuration(900);
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

	private int rows() {
		int r = 0;
		for (int k = 0; k < CATS; k++) if (catMs[k] > 0) r++;
		return r;
	}

	@Override
	protected void onMeasure(int wSpec, int hSpec) {
		int w = resolveSize(c.dp(300), wSpec);
		int h = (hours == 0) ? 0 : c.dp(128) + rows() * c.dp(40) + ((chargeMs > 0) ? c.dp(22) : 0);
		setMeasuredDimension(w, h);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		if (hours == 0) return;
		int w = getWidth();
		float left = c.dp(34);
		float right = w - c.dp(4);
		float top = c.dp(6);
		float bottom = c.dp(104);
		float plotW = right - left;
		// Guide lines with the current.
		paint.setTextSize(sp(10));
		paint.setTextAlign(Paint.Align.RIGHT);
		float step = niceStep(maxMa);
		for (float v = 0; v <= maxMa * 1.05f; v += step) {
			float y = bottom - (bottom - top) * (v / (maxMa * 1.1f));
			paint.setColor((c.textSecondary & 0xFFFFFF) | 0x22000000);
			paint.setStrokeWidth(c.dp(1));
			canvas.drawLine(left, y, right, y, paint);
			paint.setColor(c.textSecondary);
			canvas.drawText(String.valueOf(Math.round(v)), left - c.dp(4), y + sp(3.5f), paint);
		}
		// One bar per hour.
		float slot = plotW / hours;
		float bw = Math.min(slot * 0.7f, c.dp(34));
		paint.setTextAlign(Paint.Align.CENTER);
		for (int h = 0; h < hours; h++) {
			float cx = left + slot * (h + 0.5f);
			float bh = (bottom - top) * (hourMa[h] / (maxMa * 1.1f)) * p;
			if (hourCharging[h] && (hourMa[h] == 0f)) bh = (bottom - top) * 0.25f * p;
			rect.set(cx - bw / 2f, bottom - Math.max(bh, c.dp(2)), cx + bw / 2f, bottom);
			if (hourCharging[h]) {
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(c.dp(1.5f));
				paint.setColor(c.accent);
				paint.setAlpha(150);
				canvas.drawRoundRect(rect, c.dp(4), c.dp(4), paint);
				paint.setStyle(Paint.Style.FILL);
			} else {
				paint.setColor(c.accent);
				canvas.drawRoundRect(rect, c.dp(4), c.dp(4), paint);
			}
			paint.setColor(c.textSecondary);
			if ((hours <= 8) || (h % 2 == 0)) {
				String t = DateFormat.getTimeFormat(getContext()).format(new Date(firstHourStart + h * 3600_000L));
				canvas.drawText(t, cx, bottom + c.dp(14), paint);
			}
		}
		paint.setTextAlign(Paint.Align.LEFT);
		paint.setColor(c.textSecondary);
		canvas.drawText(getContext().getString(R.string.sleep_power_unit), c.dp(2), top + sp(2), paint);
		// The breakdown.
		float y = bottom + c.dp(30);
		for (int k = 0; k < CATS; k++) {
			if (catMs[k] == 0) continue;
			paint.setTextSize(sp(13));
			paint.setColor(c.textPrimary);
			paint.setTextAlign(Paint.Align.LEFT);
			canvas.drawText(names[k], c.dp(4), y + sp(4), paint);
			paint.setTextAlign(Paint.Align.RIGHT);
			paint.setColor(c.textSecondary);
			paint.setTextSize(sp(12));
			String info = String.format(Locale.getDefault(), "%d%%  ·  %d mA  ·  %s", Math.round(catShare[k] * 100),
					Math.round(catMa[k]), SleepInsights.duration(catMs[k]));
			canvas.drawText(info, w - c.dp(4), y + sp(4), paint);
			float by = y + c.dp(12);
			rect.set(c.dp(4), by, w - c.dp(4), by + c.dp(6));
			paint.setColor((c.textSecondary & 0xFFFFFF) | 0x28000000);
			canvas.drawRoundRect(rect, c.dp(3), c.dp(3), paint);
			rect.right = c.dp(4) + (w - c.dp(8)) * catShare[k] * p;
			paint.setColor(c.accent);
			if (rect.width() > 0) canvas.drawRoundRect(rect, c.dp(3), c.dp(3), paint);
			y += c.dp(40);
		}
		if (chargeMs > 0) {
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setColor(c.textSecondary);
			paint.setTextSize(sp(12));
			canvas.drawText(getContext().getString(R.string.sleep_power_charging, SleepInsights.duration(chargeMs)), c.dp(4),
					y + sp(2), paint);
		}
		paint.setTextAlign(Paint.Align.LEFT);
	}

	private static float niceStep(float max) {
		float[] steps = {10, 20, 50, 100, 200, 500, 1000};
		for (float s : steps) if (max / s <= 4) return s;
		return 1000;
	}
}
