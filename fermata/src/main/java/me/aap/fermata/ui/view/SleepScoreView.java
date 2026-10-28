package me.aap.fermata.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.addon.sleep.SleepAnalyzer;
import me.aap.fermata.addon.sleep.SleepPrefs;

/**
 * The score of a night as a ring that fills in, with the four parts it is made of as bars beside it (duration, regularity, time to
 * fall asleep, interruptions), each with the points it earned of the points it could. Everything glides when the night
 * changes, and the settings page uses the same view to show a made-up night live while the weights are moved.
 */
public final class SleepScoreView extends View {
	private static final int PARTS = 4;
	private final SettingsStyle.Colors c;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final String[] names;
	private final int[] partColors;
	private final float[] fromFrac = new float[PARTS];
	private final float[] toFrac = new float[PARTS];
	private final float[] maxPts = new float[PARTS];
	private final float[] gotPts = new float[PARTS];
	private float fromScore;
	private float toScore;
	private float p = 1f;
	private int gradeLabel = -1;
	@Nullable
	private String caption;
	@Nullable
	private ValueAnimator anim;
	private boolean empty = true;

	public SleepScoreView(Context ctx, SettingsStyle.Colors colors) {
		super(ctx);
		c = colors;
		names = new String[]{ctx.getString(R.string.sleep_part_duration), ctx.getString(R.string.sleep_part_regular),
				ctx.getString(R.string.sleep_part_latency), ctx.getString(R.string.sleep_part_interruptions)};
		partColors = new int[]{0xFF64B5F6, 0xFFBA68C8, 0xFF4DB6AC, 0xFFFFB74D};
	}

	/** Shows {@code r} (null: no night yet); {@code caption} sits under the number. */
	public void setResult(@Nullable SleepAnalyzer.Result r, @Nullable String caption) {
		this.caption = caption;
		fromScore = shownScore();
		for (int i = 0; i < PARTS; i++) fromFrac[i] = shownFrac(i);
		if (r == null) {
			empty = true;
			toScore = 0;
			for (int i = 0; i < PARTS; i++) {
				toFrac[i] = 0;
				gotPts[i] = 0;
			}
			gradeLabel = -1;
		} else {
			empty = false;
			toScore = r.score;
			SleepAnalyzer.Part[] parts = {r.duration, r.consistency, r.latency, r.interruptions};
			for (int i = 0; i < PARTS; i++) {
				toFrac[i] = parts[i].fraction;
				maxPts[i] = parts[i].max;
				gotPts[i] = parts[i].points();
			}
			gradeLabel = SleepAnalyzer.grade(r.score);
		}
		if (anim != null) anim.cancel();
		if (!SleepPrefs.animate() || !isShown()) {
			p = 1f;
			fromScore = toScore;
			System.arraycopy(toFrac, 0, fromFrac, 0, PARTS);
			invalidate();
			return;
		}
		p = 0f;
		anim = ValueAnimator.ofFloat(0f, 1f);
		anim.setDuration(900);
		anim.setInterpolator(new DecelerateInterpolator(2f));
		anim.addUpdateListener(a -> {
			p = (float) a.getAnimatedValue();
			invalidate();
		});
		anim.start();
	}

	/** Replays the fill from empty (the tab was just opened). */
	public void replay() {
		fromScore = 0;
		for (int i = 0; i < PARTS; i++) fromFrac[i] = 0;
		setResultAgain();
	}

	private void setResultAgain() {
		if (anim != null) anim.cancel();
		if (!SleepPrefs.animate()) {
			p = 1f;
			invalidate();
			return;
		}
		p = 0f;
		anim = ValueAnimator.ofFloat(0f, 1f);
		anim.setDuration(1100);
		anim.setInterpolator(new DecelerateInterpolator(2f));
		anim.addUpdateListener(a -> {
			p = (float) a.getAnimatedValue();
			invalidate();
		});
		anim.start();
	}

	private float shownScore() {
		return fromScore + (toScore - fromScore) * p;
	}

	private float shownFrac(int i) {
		return fromFrac[i] + (toFrac[i] - fromFrac[i]) * p;
	}

	@Override
	protected void onDetachedFromWindow() {
		if (anim != null) anim.cancel();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onMeasure(int w, int h) {
		setMeasuredDimension(resolveSize(c.dp(300), w), c.dp(150));
	}

	private float sp(float v) {
		return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
	}

	private int scoreColor(float score) {
		return (score >= 85) ? 0xFF81C784 : (score >= 70) ? 0xFF64B5F6 : (score >= 55) ? 0xFFFFB74D : 0xFFE57373;
	}

	@Override
	protected void onDraw(Canvas canvas) {
		int w = getWidth();
		int h = getHeight();
		float ring = Math.min(h - c.dp(16), c.dp(128));
		float stroke = c.dp(12);
		float cx = c.dp(8) + ring / 2f;
		float cy = h / 2f;
		rect.set(cx - ring / 2f + stroke / 2f, cy - ring / 2f + stroke / 2f, cx + ring / 2f - stroke / 2f,
				cy + ring / 2f - stroke / 2f);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeCap(Paint.Cap.ROUND);
		paint.setStrokeWidth(stroke);
		paint.setColor((c.textSecondary & 0xFFFFFF) | 0x26000000);
		canvas.drawArc(rect, 0, 360, false, paint);
		float score = shownScore();
		if (score > 0.5f) {
			paint.setColor(scoreColor(score));
			canvas.drawArc(rect, -90, 360f * Math.min(100f, score) / 100f, false, paint);
		}
		paint.setStyle(Paint.Style.FILL);
		paint.setTextAlign(Paint.Align.CENTER);
		paint.setColor(c.textPrimary);
		paint.setTypeface(Typeface.DEFAULT_BOLD);
		paint.setTextSize(sp(empty ? 26 : 34));
		canvas.drawText(empty ? "--" : String.valueOf(Math.round(score)), cx, cy + sp(empty ? 8 : 10), paint);
		paint.setTypeface(Typeface.DEFAULT);
		paint.setTextSize(sp(11));
		paint.setColor(c.textSecondary);
		String label = (caption != null) ? caption : gradeLabel < 0 ? "" : getResources().getStringArray(R.array.sleep_grades)[gradeLabel];
		if (!label.isEmpty()) canvas.drawText(label, cx, cy + sp(10) + sp(16), paint);

		// The parts.
		float left = c.dp(8) + ring + c.dp(20);
		float right = w - c.dp(8);
		float rowH = (h - c.dp(20)) / PARTS;
		paint.setTextAlign(Paint.Align.LEFT);
		for (int i = 0; i < PARTS; i++) {
			float top = c.dp(10) + i * rowH;
			paint.setTypeface(Typeface.DEFAULT);
			paint.setTextSize(sp(12));
			paint.setColor(c.textPrimary);
			canvas.drawText(names[i], left, top + sp(12), paint);
			paint.setTextAlign(Paint.Align.RIGHT);
			paint.setColor(c.textSecondary);
			String pts = empty ? "" : String.format(Locale.getDefault(), "%d/%d", Math.round(gotPts[i]), Math.round(maxPts[i]));
			canvas.drawText(pts, right, top + sp(12), paint);
			paint.setTextAlign(Paint.Align.LEFT);
			float barTop = top + sp(12) + c.dp(6);
			float bh = c.dp(6);
			paint.setColor((c.textSecondary & 0xFFFFFF) | 0x26000000);
			rect.set(left, barTop, right, barTop + bh);
			canvas.drawRoundRect(rect, bh / 2, bh / 2, paint);
			float f = shownFrac(i);
			if (f > 0.005f) {
				paint.setColor(partColors[i]);
				rect.set(left, barTop, left + (right - left) * f, barTop + bh);
				canvas.drawRoundRect(rect, bh / 2, bh / 2, paint);
			}
		}
	}
}
