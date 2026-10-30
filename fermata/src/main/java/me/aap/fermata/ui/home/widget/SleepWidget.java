package me.aap.fermata.ui.home.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.sleep.SleepAnalyzer;
import me.aap.fermata.addon.sleep.SleepInsights;
import me.aap.fermata.addon.sleep.SleepNight;
import me.aap.fermata.addon.sleep.SleepPrefs;
import me.aap.fermata.addon.sleep.SleepTracker;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;

/**
 * Sleep on Home: last night's score and how long it was, with the last week as little bars (from three cells wide), and the
 * button that starts or stops a session. While a session runs it shows the time recorded so far, as a bar against the goal. The
 * header opens the Sleep tab. In a strip it is the figure and the button.
 */
final class SleepWidget extends HomeWidgetView {
	private static final long TICK_MS = 15_000;
	private final SleepTracker tracker = SleepTracker.get();
	private BackdropView backdrop;
	private LinearLayout column;
	private View headerRow;
	private LinearLayout body;
	private TextView value;
	private TextView sub;
	private MeterBar bar;
	private Week week;
	private LinearLayout buttonRow;
	private TextView button;
	private final Runnable tick = this::refresh;
	private final Runnable listener = () -> post(this::refresh);

	SleepWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		// Behind the words in a strip: the last nights as bars, or the night so far against the goal.
		backdrop = new BackdropView(ctx);
		content.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
		column.setOrientation(LinearLayout.VERTICAL);
		headerRow = header(() -> host.run(Action.SLEEP));
		column.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

		body = new LinearLayout(ctx);
		body.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.setGravity(Gravity.CENTER_VERTICAL);
		value = text(ROLE_PRIMARY, 34, true);
		sub = text(ROLE_SECONDARY, 13, false);
		texts.addView(value);
		texts.addView(sub);
		body.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		week = new Week(ctx);
		body.addView(week, new LinearLayout.LayoutParams(px(120), px(52)));
		column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		bar = new MeterBar(ctx);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6));
		blp.bottomMargin = px(10);
		column.addView(bar, blp);

		buttonRow = new LinearLayout(ctx);
		buttonRow.setGravity(Gravity.CENTER_VERTICAL);
		button = chip(R.drawable.sleep, ctx.getString(R.string.sleep_start), () -> host.run(Action.SLEEP_TOGGLE));
		button.setPadding(px(14), 0, px(14), 0);
		buttonRow.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34)));
		column.addView(buttonRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected void onStyleChanged() {
		if (bar != null) bar.setColors(softFill(), color(ROLE_ACCENT));
		if (backdrop != null) backdrop.setColor((color(ROLE_ACCENT) & 0x00FFFFFF) | 0x38000000);
		if (week != null) week.setColors(softFill());
	}

	@Override
	protected void onBind() {
		layoutFor();
		refresh();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column == null) return;
		layoutFor();
		refresh();
	}

	@Override
	protected void onActive() {
		tracker.addListener(listener);
		refresh();
	}

	@Override
	protected void onInactive() {
		tracker.removeListener(listener);
		removeCallbacks(tick);
	}

	/** The faint picture ends where the button begins, so nothing shows through or behind it. */
	private void keepBackdropOffButton() {
		if ((backdrop == null) || (button == null)) return;
		post(() -> {
			boolean strip = getCellsH() == 1;
			FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) backdrop.getLayoutParams();
			int end = (strip && (button.getWidth() > 0)) ? button.getWidth() + px(20) : 0;
			if ((lp != null) && (lp.rightMargin != end)) {
				lp.rightMargin = end;
				backdrop.setLayoutParams(lp);
			}
		});
	}

	private void layoutFor() {
		boolean strip = getCellsH() == 1;
		headerRow.setVisibility(strip ? GONE : VISIBLE);
		bar.setVisibility(strip ? GONE : VISIBLE);
		sub.setVisibility(VISIBLE);
		sub.setTextSize(strip ? 11.5f : 13f);
		sub.setSingleLine(strip);
		sub.setEllipsize(strip ? android.text.TextUtils.TruncateAt.END : null);
		backdrop.setVisibility(strip ? VISIBLE : GONE);
		keepBackdropOffButton();
		value.setTextSize(strip ? 22 : (getCellsH() >= 3 ? 44 : 34));
		week.setVisibility((!strip && getCellsW() >= 3) ? VISIBLE : GONE);
		if (strip) {
			// Beside the figure, not under it.
			if (buttonRow.getParent() == column) {
				column.removeView(buttonRow);
				body.addView(buttonRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34)));
			}
		} else if (buttonRow.getParent() == body) {
			body.removeView(buttonRow);
			column.addView(buttonRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
		}
	}

	private void refresh() {
		if (column == null) return;
		removeCallbacks(tick);
		boolean on = tracker.isActive();
		button.setText(on ? R.string.sleep_stop : R.string.sleep_start);
		if (on) {
			long elapsed = System.currentTimeMillis() - tracker.getStart();
			value.setText(SleepInsights.duration(elapsed));
			sub.setText(ctx.getString(R.string.sleep_since, SleepInsights.clock(ctx, tracker.getStart())));
			bar.setFraction(elapsed / (SleepPrefs.goalMin() * 60_000f));
			backdrop.setFraction(elapsed / (SleepPrefs.goalMin() * 60_000f));
			week.setScores(new int[0]);
			if (isActive()) postDelayed(tick, TICK_MS);
			return;
		}
		List<SleepNight> all = tracker.getStore().all();
		if (all.isEmpty()) {
			value.setText("--");
			sub.setText(R.string.sleep_no_night_title);
			bar.setFraction(0f);
			backdrop.setBars(new float[0]);
			week.setScores(new int[0]);
			return;
		}
		List<SleepNight> recent = all.subList(Math.max(0, all.size() - 8), all.size());
		List<SleepAnalyzer.Result> rs = SleepInsights.analyzeAll(recent, SleepAnalyzer.Settings.fromPrefs());
		SleepAnalyzer.Result last = rs.get(rs.size() - 1);
		value.setText(String.valueOf(last.score));
		sub.setText(ctx.getString(R.string.home_sleep_last, SleepInsights.duration(last.asleepMs),
				ctx.getResources().getStringArray(R.array.sleep_grades)[SleepAnalyzer.grade(last.score)]));
		bar.setFraction(last.score / 100f);
		int[] scores = new int[Math.min(7, rs.size())];
		for (int i = 0; i < scores.length; i++) scores[i] = rs.get(rs.size() - scores.length + i).score;
		week.setScores(scores);
		// The last nights as bars behind the words, in the room the button leaves.
		float[] bars = new float[7];
		java.util.Arrays.fill(bars, -1f);
		for (int i = 0; i < scores.length; i++) bars[7 - scores.length + i] = scores[i] / 100f;
		backdrop.setBars(bars);
		keepBackdropOffButton();
	}

	/** The last nights as little bars, each in its score's colour. */
	private static final class Week extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private int[] scores = new int[0];
		private int track;

		Week(android.content.Context ctx) {
			super(ctx);
		}

		void setColors(int track) {
			this.track = track;
			invalidate();
		}

		void setScores(int[] s) {
			scores = s;
			invalidate();
		}

		@Override
		protected void onDraw(Canvas c) {
			int n = 7;
			float w = getWidth();
			float h = getHeight();
			float slot = w / n;
			float bw = slot * 0.6f;
			int off = n - scores.length;
			for (int i = 0; i < n; i++) {
				float cx = slot * (i + 0.5f);
				paint.setColor(track);
				rect.set(cx - bw / 2f, 0, cx + bw / 2f, h);
				c.drawRoundRect(rect, bw / 2f, bw / 2f, paint);
				if (i < off) continue;
				int s = scores[i - off];
				paint.setColor((s >= 85) ? 0xFF81C784 : (s >= 70) ? 0xFF64B5F6 : (s >= 55) ? 0xFFFFB74D : 0xFFE57373);
				float top = h - h * Math.max(0.08f, s / 100f);
				rect.set(cx - bw / 2f, top, cx + bw / 2f, h);
				c.drawRoundRect(rect, bw / 2f, bw / 2f, paint);
			}
		}
	}
}
