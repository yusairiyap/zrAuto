package me.aap.fermata.ui.home.widget;

import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.TopToast;

/**
 * The sleep timer: how long is left (or that it is off), a bar running down, and the usual lengths
 * as chips to start one with a tap. The header opens the full timer card (any length, finish the
 * song first). In a strip it is the time and one chip.
 */
final class TimerWidget extends HomeWidgetView {
	private static final int[] PRESETS = {5, 15, 30, 45, 60, 90};

	private LinearLayout column;
	private View headerRow;
	private LinearLayout body;
	private TextView value;
	private TextView sub;
	private MeterBar bar;
	private LinearLayout chips;
	/** The timer's length in seconds, as far as it is known (what the bar runs down from). */
	private int total;
	private boolean active;
	private final Runnable tick = this::refresh;
	private final MediaSessionCallback.Listener listener = new MediaSessionCallback.Listener() {
		@Override
		public void onPlaybackTimerChanged(MediaSessionCallback cb) {
			post(TimerWidget.this::refresh);
		}
	};

	TimerWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	private MediaSessionCallback cb() {
		return host.activity().getMediaSessionCallback();
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		headerRow = header(() -> host.run(Action.SLEEP_TIMER));
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
		column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		bar = new MeterBar(ctx);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6));
		blp.bottomMargin = px(10);
		column.addView(bar, blp);

		chips = new LinearLayout(ctx);
		chips.setGravity(Gravity.CENTER_VERTICAL);
		column.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected void onStyleChanged() {
		if (bar != null) bar.setColors(softFill(), color(ROLE_ACCENT));
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
	protected void onSized(int w, int h) {
		if ((column != null) && (chips.getChildCount() == 0)) rebuildChips();
	}

	@Override
	protected void onActive() {
		cb().addBroadcastListener(listener);
		refresh();
	}

	@Override
	protected void onInactive() {
		cb().removeBroadcastListener(listener);
		removeCallbacks(tick);
	}

	private void layoutFor() {
		boolean strip = getCellsH() == 1;
		headerRow.setVisibility(strip ? GONE : VISIBLE);
		bar.setVisibility(strip ? GONE : VISIBLE);
		body.setOrientation(LinearLayout.HORIZONTAL);
		value.setTextSize(strip ? 22 : (getCellsH() >= 3 ? 44 : 34));
		sub.setVisibility(strip ? GONE : VISIBLE);
		LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) chips.getLayoutParams();
		if (strip) {
			// Beside the time, not under it.
			if (chips.getParent() == column) {
				column.removeView(chips);
				body.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34)));
			}
		} else if (chips.getParent() == body) {
			body.removeView(chips);
			column.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
		}
		rebuildChips();
	}

	private int presetCount() {
		if (getCellsH() == 1) return 1;
		int fit = (widthPx() - px(28)) / px(84);
		return Math.max(2, Math.min(PRESETS.length, fit));
	}

	private String minutes(int m) {
		return ctx.getString(R.string.music_timer_minutes, m);
	}

	private void rebuildChips() {
		forget(chips);
		chips.removeAllViews();
		boolean strip = getCellsH() == 1;
		if (active && strip) {
			chips.addView(offChip());
			return;
		}
		if (strip) {
			chips.addView(presetChip(30), chipLp(false));
			return;
		}
		int n = presetCount();
		int shown = 0;
		if (active) {
			chips.addView(offChip(), chipLp(false));
			shown++;
		}
		// The usual lengths, thinned out evenly when there are fewer places than lengths.
		int slots = Math.max(1, n - shown);
		for (int i = 0; i < slots; i++) {
			int idx = (slots >= PRESETS.length) ? i : Math.round(i * (PRESETS.length - 1f) / Math.max(1, slots - 1));
			if (slots == 1) idx = 2;
			chips.addView(presetChip(PRESETS[idx]), chipLp(chips.getChildCount() > 0));
		}
	}

	private LinearLayout.LayoutParams chipLp(boolean margin) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34));
		if (margin) lp.setMarginStart(px(6));
		return lp;
	}

	private View presetChip(int m) {
		TextView c = chip(0, minutes(m), () -> start(m));
		c.setPadding(px(12), 0, px(12), 0);
		return c;
	}

	private View offChip() {
		TextView c = chip(R.drawable.timer, ctx.getString(R.string.music_timer_turn_off), () -> {
			cb().cancelPlaybackTimer();
			TopToast.show(R.drawable.timer, ctx.getString(R.string.music_timer_cancelled));
		});
		return c;
	}

	private void start(int m) {
		total = m * 60;
		cb().setPlaybackTimer(total, false);
		TopToast.show(R.drawable.timer, ctx.getString(R.string.music_timer_set, minutes(m)));
	}

	private void refresh() {
		if (column == null) return;
		removeCallbacks(tick);
		MediaSessionCallback cb = cb();
		boolean waiting = cb.isPlaybackTimerWaitingForTrackEnd();
		int left = cb.getPlaybackTimer();
		boolean on = cb.hasPlaybackTimer() && (waiting || (left > 0));
		if (on != active) {
			active = on;
			rebuildChips();
		}
		if (!on) {
			total = 0;
			value.setText(R.string.music_timer_off);
			sub.setText(R.string.home_timer_idle);
			bar.setFraction(0f);
			return;
		}
		if (waiting) {
			value.setText(DateUtils.formatElapsedTime(0));
			sub.setText(R.string.music_timer_waiting);
			bar.setFraction(0f);
			return;
		}
		if (total < left) total = left;
		value.setText(DateUtils.formatElapsedTime(left));
		sub.setText(R.string.home_timer_running);
		bar.setFraction(left / (float) Math.max(1, total));
		if (isActive()) postDelayed(tick, 1000);
	}
}
