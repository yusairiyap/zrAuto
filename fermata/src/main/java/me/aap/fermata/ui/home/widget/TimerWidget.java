package me.aap.fermata.ui.home.widget;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeModal;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.ChipFlowLayout;
import me.aap.fermata.ui.view.TopToast;

/**
 * The sleep timer: how long is left (or that it is off), a bar running down, and the usual lengths
 * as chips to start one with a tap. The header opens the full timer card (any length, finish the
 * song first). In a strip it is the time and one chip.
 */
final class TimerWidget extends HomeWidgetView {
	private static final int[] PRESETS = {5, 15, 30, 45, 60, 90};
	/** The pick that shows the lengths started most, in place of the picked ones. */
	private static final String RECOMMENDED = "rec";

	private BackdropView backdrop;
	private LinearLayout column;
	private View headerRow;
	private LinearLayout body;
	private TextView value;
	private TextView sub;
	private MeterBar bar;
	private LinearLayout chips;
	/** How many chips the row was built for: rebuilt when the card measures a different count. */
	private int chipsFor = -1;
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
		// Behind the words in a strip: the time left as a fill that runs out.
		backdrop = new BackdropView(ctx);
		content.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
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
		if (backdrop != null) backdrop.setColor((color(ROLE_ACCENT) & 0x00FFFFFF) | 0x40000000);
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
		// The first layout (before the card is measured) sees no width, so the count is only right from here on.
		if ((column != null) && (presetCount() != chipsFor)) rebuildChips();
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
		// A strip keeps the second line too, smaller and on one line: what the timer is doing and when it stops.
		sub.setVisibility(VISIBLE);
		sub.setTextSize(strip ? 11.5f : 13f);
		sub.setSingleLine(strip);
		sub.setEllipsize(strip ? android.text.TextUtils.TruncateAt.END : null);
		backdrop.setVisibility(strip ? VISIBLE : GONE);
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
		chipsFor = presetCount();
		boolean strip = getCellsH() == 1;
		if (active && strip) {
			chips.addView(offChip());
			return;
		}
		if (strip) {
			for (int m : minutesToShow(1)) chips.addView(presetChip(m), chipLp(false));
			return;
		}
		int n = presetCount();
		int shown = 0;
		if (active) {
			chips.addView(offChip(), chipLp(false));
			shown++;
		}
		for (int m : minutesToShow(Math.max(1, n - shown))) chips.addView(presetChip(m), chipLp(chips.getChildCount() > 0));
	}

	/** The picked keys: the lengths in minutes, {@link #RECOMMENDED}, or nothing (an empty set means the default: all of them). */
	private Set<String> pickedKeys() {
		Set<String> keys = new LinkedHashSet<>();
		String s = optString("items", "");
		if (s.isEmpty()) return null;
		if (!"-".equals(s)) keys.addAll(Arrays.asList(s.split(",")));
		return keys;
	}

	/**
	 * The lengths the chips show, in order, thinned out evenly when there are fewer places than lengths. The default is every
	 * length; the picks show theirs, and the recommended pick shows the lengths started most.
	 */
	private int[] minutesToShow(int slots) {
		int[] pool = poolMinutes();
		if (pool.length <= slots) return pool;
		if (slots == 1) return new int[]{pool[Math.min(pool.length - 1, pool.length == PRESETS.length ? 2 : 0)]};
		int[] out = new int[slots];
		for (int i = 0; i < slots; i++) out[i] = pool[Math.round(i * (pool.length - 1f) / (slots - 1))];
		return out;
	}

	private int[] poolMinutes() {
		Set<String> keys = pickedKeys();
		if (keys == null) return PRESETS;
		if (keys.contains(RECOMMENDED)) return ranked();
		List<Integer> l = new ArrayList<>();
		for (int m : PRESETS) if (keys.contains(String.valueOf(m))) l.add(m);
		return l.stream().mapToInt(Integer::intValue).toArray();
	}

	/** Every length, the most started first (ties keep the usual order). */
	private int[] ranked() {
		java.util.Map<Integer, Integer> uses = uses();
		List<Integer> l = new ArrayList<>();
		for (int m : PRESETS) l.add(m);
		l.sort((a, b) -> Integer.compare(uses.getOrDefault(b, 0), uses.getOrDefault(a, 0)));
		return l.stream().mapToInt(Integer::intValue).toArray();
	}

	/** How many times each length was started, kept with the widget. */
	private java.util.Map<Integer, Integer> uses() {
		java.util.Map<Integer, Integer> m = new java.util.HashMap<>();
		for (String pair : optString("uses", "").split(",")) {
			String[] kv = pair.split(":");
			if (kv.length != 2) continue;
			try {
				m.put(Integer.parseInt(kv[0]), Integer.parseInt(kv[1]));
			} catch (NumberFormatException ignored) {
			}
		}
		return m;
	}

	private void countUse(int m) {
		java.util.Map<Integer, Integer> uses = uses();
		uses.merge(m, 1, Integer::sum);
		StringBuilder s = new StringBuilder();
		for (java.util.Map.Entry<Integer, Integer> e : uses.entrySet()) {
			if (s.length() > 0) s.append(',');
			s.append(e.getKey()).append(':').append(e.getValue());
		}
		setOpt("uses", s.toString());
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.button(ctx.getString(R.string.home_opt_pick_items), this::pickItems));
		return l;
	}

	/** The lengths to show as chips: a tap picks or drops one; recommended replaces the rest. A card of chips, like All in One's. */
	private void pickItems() {
		Set<String> chosen = new LinkedHashSet<>();
		Set<String> picked = pickedKeys();
		if (picked == null) {
			for (int m : PRESETS) chosen.add(String.valueOf(m));
		} else {
			chosen.addAll(picked);
		}
		Context pc = host.palette().ctx;
		LinearLayout box = new LinearLayout(pc);
		box.setOrientation(LinearLayout.VERTICAL);
		TextView hint = new TextView(pc);
		hint.setText(R.string.home_timer_pick_hint);
		hint.setTextColor(host.palette().textSecondary);
		hint.setTextSize(13);
		box.addView(hint);
		ChipFlowLayout flow = new ChipFlowLayout(pc);
		flow.setPadding(0, px(12), 0, px(4));
		box.addView(flow);
		fillPicker(flow, chosen);
		HomeModal.show(host, ctx.getString(R.string.home_opt_pick_items), R.drawable.home_add, box, null);
	}

	private void fillPicker(ChipFlowLayout flow, Set<String> chosen) {
		flow.removeAllViews();
		Context pc = host.palette().ctx;
		List<String> keys = new ArrayList<>();
		keys.add(RECOMMENDED);
		for (int m : PRESETS) keys.add(String.valueOf(m));
		for (String k : keys) {
			CharSequence label = RECOMMENDED.equals(k) ? ctx.getString(R.string.home_timer_recommended) :
					minutes(Integer.parseInt(k));
			flow.addView(ChipFlowLayout.chip(pc, 0, label, chosen.contains(k), () -> {
				// Recommended stands alone: picking it drops the lengths, and a length drops it.
				if (!chosen.remove(k)) {
					if (RECOMMENDED.equals(k)) chosen.clear();
					else chosen.remove(RECOMMENDED);
					chosen.add(k);
				}
				saveItems(chosen);
				fillPicker(flow, chosen);
			}));
		}
	}

	/** Stores the picks; the default (every length) is stored as nothing, so it stays the default. */
	private void saveItems(Set<String> chosen) {
		Set<String> all = new LinkedHashSet<>();
		for (int m : PRESETS) all.add(String.valueOf(m));
		String v;
		if (chosen.isEmpty()) v = "-";
		else if (chosen.equals(all)) v = "";
		else v = String.join(",", chosen);
		setOpt("items", v);
		rebuildChips();
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
		// After the timer is set: counting the use saves an option, which binds the card again (the chip row is rebuilt
		// under the finger), and nothing in that may keep the timer from starting.
		post(() -> countUse(m));
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
			backdrop.setFraction(-1f);
			return;
		}
		if (waiting) {
			value.setText(DateUtils.formatElapsedTime(0));
			sub.setText(R.string.music_timer_waiting);
			bar.setFraction(0f);
			backdrop.setFraction(1f);
			return;
		}
		if (total < left) total = left;
		value.setText(DateUtils.formatElapsedTime(left));
		String stops = ctx.getString(R.string.home_timer_stops_at,
				android.text.format.DateFormat.getTimeFormat(ctx).format(new java.util.Date(System.currentTimeMillis() + left * 1000L)));
		sub.setText((getCellsH() == 1) ? stops : ctx.getString(R.string.home_timer_running));
		bar.setFraction(left / (float) Math.max(1, total));
		backdrop.setFraction(left / (float) Math.max(1, total));
		if (isActive()) postDelayed(tick, 1000);
	}
}
