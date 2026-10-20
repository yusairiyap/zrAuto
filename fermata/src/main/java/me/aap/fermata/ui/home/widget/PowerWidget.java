package me.aap.fermata.ui.home.widget;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.addon.power.PowerUsageAddon;
import me.aap.fermata.addon.power.PowerUsageStore;
import me.aap.fermata.addon.power.PowerUsageTracker;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.PowerGaugeView;
import me.aap.fermata.ui.view.PowerLiveGraphView;

/**
 * The battery current right now, like an ampere meter: a ring that fills with the draw (or the
 * charge), the number in mA, whether the load is light, moderate or heavy, the battery level and the
 * time left. A wide card swaps the ring for the live graph and the figures beside it.
 */
final class PowerWidget extends HomeWidgetView {
	private static final int STRIP = 0;
	private static final int GAUGE = 1;
	private static final int GRAPH = 2;
	private static final int BREAKDOWN = 3;
	private static final int[] CAT_NAMES = {R.string.power_cat_video, R.string.power_cat_music,
			R.string.power_cat_idle};
	private static final int[] CAT_COLORS = {R.color.data_usage_video, R.color.data_usage_music,
			R.color.data_usage_other};

	private LinearLayout column;
	private FrameLayout gaugeBox;
	private PowerGaugeView gauge;
	private PowerLiveGraphView graph;
	private TextView value;
	private TextView unit;
	private TextView status;
	private TextView stats;
	private LinearLayout valueRow;
	private LinearLayout body;
	private TextView off;
	private LinearLayout breakdown;
	private final TextView[] catValues = new TextView[PowerUsageStore.CATS];
	private final MeterBar[] catBars = new MeterBar[PowerUsageStore.CATS];
	private TextView carLine;
	private int mode = -1;
	/** Whether the card is narrow (two cells): the status goes under the number there, not beside it. */
	private boolean narrow;
	private final Runnable listener = this::refresh;

	PowerWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		column.addView(header(() -> open(R.id.power_usage_addon)),
				new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

		body = new LinearLayout(ctx);
		body.setOrientation(LinearLayout.VERTICAL);
		body.setGravity(Gravity.CENTER);

		gaugeBox = new FrameLayout(ctx);
		gauge = new PowerGaugeView(ctx);
		gaugeBox.addView(gauge, new FrameLayout.LayoutParams(-1, -1));

		valueRow = new LinearLayout(ctx);
		valueRow.setOrientation(LinearLayout.HORIZONTAL);
		valueRow.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		value = text(ROLE_PRIMARY, 24, true);
		unit = text(ROLE_SECONDARY, 12.5f, false);
		unit.setText(R.string.home_power_unit);
		unit.setPadding(px(4), 0, 0, px(3));
		valueRow.addView(value);
		valueRow.addView(unit);
		status = text(ROLE_SECONDARY, 12.5f, true);
		status.setGravity(Gravity.CENTER);
		stats = text(ROLE_SECONDARY, 12, false);
		stats.setGravity(Gravity.CENTER);

		graph = new PowerLiveGraphView(ctx);

		breakdown = new LinearLayout(ctx);
		breakdown.setOrientation(LinearLayout.VERTICAL);
		breakdown.setGravity(Gravity.CENTER_VERTICAL);
		for (int i = 0; i < PowerUsageStore.CATS; i++) {
			LinearLayout line = new LinearLayout(ctx);
			line.setGravity(Gravity.CENTER_VERTICAL);
			TextView name = text(ROLE_SECONDARY, 12.5f, false);
			name.setText(CAT_NAMES[i]);
			name.setSingleLine(true);
			line.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
			catValues[i] = text(ROLE_PRIMARY, 12.5f, true);
			line.addView(catValues[i]);
			LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
			llp.topMargin = (i == 0) ? 0 : px(8);
			breakdown.addView(line, llp);
			catBars[i] = new MeterBar(ctx);
			LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, px(6));
			blp.topMargin = px(3);
			breakdown.addView(catBars[i], blp);
		}
		carLine = text(ROLE_SECONDARY, 11.5f, false);
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
		clp.topMargin = px(10);
		breakdown.addView(carLine, clp);

		off = note(R.string.home_power_off);
		off.setVisibility(GONE);
		column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		column.addView(off, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.choice("view", ctx.getString(R.string.home_opt_power_view), 0,
				ctx.getString(R.string.home_opt_auto), ctx.getString(R.string.home_opt_power_meter),
				ctx.getString(R.string.home_opt_power_graph), ctx.getString(R.string.home_opt_power_breakdown)));
		l.add(HomeOption.sw("battery", ctx.getString(R.string.home_opt_power_battery), true));
		l.add(HomeOption.sw("left", ctx.getString(R.string.home_opt_power_left), true));
		l.add(HomeOption.sw("temp", ctx.getString(R.string.home_opt_power_temp), true));
		return l;
	}

	@Override
	protected void onStyleChanged() {
		if (gauge == null) return;
		int track = softFill();
		gauge.setColors(track, ContextCompat.getColor(ctx, R.color.power_low),
				ContextCompat.getColor(ctx, R.color.power_mid), ContextCompat.getColor(ctx, R.color.power_high),
				ContextCompat.getColor(ctx, R.color.power_charge));
		graph.setColors(ContextCompat.getColor(ctx, R.color.power_charge),
				ContextCompat.getColor(ctx, R.color.power_mid), track, color(ROLE_SECONDARY));
		for (int i = 0; i < catBars.length; i++) {
			catBars[i].setColors(track, ContextCompat.getColor(ctx, CAT_COLORS[i]));
		}
		refresh();
	}

	@Override
	protected void onBind() {
		onStyleChanged();
		rebuild();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column != null) rebuild();
	}

	@Override
	protected void onSized(int w, int h) {
		if ((column != null) && (mode == GAUGE)) {
			sizeGauge();
			refresh();
		}
	}

	/** Puts the pieces together for the card's size. */
	private void rebuild() {
		int view = optInt("view", 0);
		int m;
		if (getCellsH() == 1) m = STRIP;
		else if (view == 1) m = GAUGE;
		else if (view == 2) m = GRAPH;
		else if (view == 3) m = BREAKDOWN;
		else m = (getCellsW() >= 3) ? GRAPH : GAUGE;
		boolean nar = getCellsW() < 3;
		if ((m == mode) && (nar == narrow)) {
			refresh();
			return;
		}
		mode = m;
		narrow = nar;
		body.removeAllViews();
		detach(gaugeBox);
		detach(valueRow);
		detach(status);
		detach(stats);
		detach(graph);
		detach(breakdown);
		switch (m) {
			case STRIP -> {
				flatValue(true);
				stats.setSingleLine(false);
				body.setOrientation(LinearLayout.HORIZONTAL);
				body.setGravity(Gravity.CENTER_VERTICAL);
				valueRow.setGravity(Gravity.BOTTOM);
				status.setGravity(Gravity.START);
				body.addView(valueRow);
				LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
				slp.setMarginStart(px(12));
				body.addView(status, slp);
				body.addView(stats);
			}
			case GAUGE -> {
				// The number with its unit under it in the ring's middle: side by side they were wider than the ring.
				flatValue(false);
				stats.setSingleLine(true);
				stats.setEllipsize(android.text.TextUtils.TruncateAt.END);
				body.setOrientation(LinearLayout.VERTICAL);
				body.setGravity(Gravity.CENTER);
				// Left as the last mode had them, these put the labels off to one side.
				valueRow.setGravity(Gravity.CENTER);
				status.setGravity(Gravity.CENTER);
				gaugeBox.addView(valueRow, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
				body.addView(gaugeBox, new LinearLayout.LayoutParams(px(100), px(100)));
				body.addView(status, new LinearLayout.LayoutParams(-1, -2));
				stats.setGravity(Gravity.CENTER);
				body.addView(stats, new LinearLayout.LayoutParams(-1, -2));
				sizeGauge();
			}
			case BREAKDOWN -> {
				flatValue(true);
				stats.setSingleLine(false);
				body.setOrientation(LinearLayout.VERTICAL);
				body.setGravity(Gravity.TOP);
				LinearLayout top = new LinearLayout(ctx);
				top.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
				top.setGravity(narrow ? Gravity.START : Gravity.BOTTOM);
				valueRow.setGravity(Gravity.BOTTOM);
				top.addView(valueRow);
				status.setGravity(narrow ? Gravity.START : Gravity.END);
				LinearLayout.LayoutParams slp = narrow ? new LinearLayout.LayoutParams(-1, -2) :
						new LinearLayout.LayoutParams(0, -2, 1f);
				if (!narrow) slp.setMarginStart(px(10));
				top.addView(status, slp);
				body.addView(top, new LinearLayout.LayoutParams(-1, -2));
				stats.setGravity(Gravity.START);
				LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
				stp.topMargin = px(2);
				body.addView(stats, stp);
				LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
				blp.topMargin = px(10);
				body.addView(breakdown, blp);
			}
			default -> {
				flatValue(true);
				stats.setSingleLine(false);
				body.setOrientation(LinearLayout.VERTICAL);
				body.setGravity(Gravity.TOP);
				LinearLayout top = new LinearLayout(ctx);
				top.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
				top.setGravity(narrow ? Gravity.START : Gravity.BOTTOM);
				valueRow.setGravity(Gravity.BOTTOM);
				top.addView(valueRow);
				status.setGravity(narrow ? Gravity.START : Gravity.END);
				LinearLayout.LayoutParams slp = narrow ? new LinearLayout.LayoutParams(-1, -2) :
						new LinearLayout.LayoutParams(0, -2, 1f);
				if (!narrow) slp.setMarginStart(px(10));
				top.addView(status, slp);
				body.addView(top, new LinearLayout.LayoutParams(-1, -2));
				stats.setGravity(Gravity.START);
				LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
				stp.topMargin = px(2);
				body.addView(stats, stp);
				LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
				glp.topMargin = px(4);
				body.addView(graph, glp);
			}
		}
		value.setTextSize((m == STRIP) ? 22 : (m == GAUGE) ? 19 : 28);
		if (m == GAUGE) sizeGauge();
		// The strip has room for one line of figures: a small card shows the rest only when asked to.
		refresh();
	}

	/** The number and its unit side by side ({@code flat}) or the unit under the number. */
	private void flatValue(boolean flat) {
		valueRow.setOrientation(flat ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
		unit.setPadding(flat ? px(4) : 0, 0, 0, flat ? px(3) : 0);
		unit.setTextSize(flat ? 12.5f : 11f);
	}

	private static void detach(android.view.View v) {
		if (v.getParent() instanceof ViewGroup p) p.removeView(v);
	}

	/** The meter's size from the measured card (the first frame already has it, nothing jumps). */
	private void sizeGauge() {
		if (heightPx() == 0) return;
		// What sits under the meter is the status and one line of figures; the meter gets all the rest.
		int availH = heightPx() - px(28) - px(34) - px(40);
		int availW = widthPx() - px(20);
		int side = Math.max(px(72), Math.min(availH, availW));
		// The number follows the ring, so it always fits inside it.
		value.setTextSize(Math.max(14f, Math.min(26f, side / px(1f) * 0.2f)));
		LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) gaugeBox.getLayoutParams();
		if ((lp != null) && ((lp.width != side) || (lp.height != side))) {
			lp.width = side;
			lp.height = side;
			gaugeBox.setLayoutParams(lp);
		}
	}

	@Override
	protected void onActive() {
		if (!PowerUsageAddon.isEnabled()) return;
		PowerUsageTracker.get().addListener(listener);
		refresh();
	}

	@Override
	protected void onInactive() {
		if (!PowerUsageAddon.isEnabled()) return;
		PowerUsageTracker.get().removeListener(listener);
	}

	private void refresh() {
		if (column == null) return;
		boolean on = PowerUsageAddon.isEnabled();
		off.setVisibility(on ? GONE : VISIBLE);
		body.setVisibility(on ? VISIBLE : GONE);
		if (!on) return;
		PowerUsageTracker t = PowerUsageTracker.get();
		if (!t.isSupported() || !t.hasCurrent()) {
			value.setText(R.string.power_stat_unknown);
			unit.setVisibility(GONE);
			status.setText(t.isSupported() ? R.string.power_state_waiting : R.string.home_power_unsupported);
			stats.setText("");
			stats.setVisibility(GONE);
			graph.setData(new float[0]);
			return;
		}
		unit.setVisibility(VISIBLE);
		float ma = t.getCurrent();
		float load = t.getLoad();
		gauge.setValue(ma, load);
		long v = Math.round(ma);
		value.setText((v == 0) ? "0" : String.format(Locale.getDefault(), "%+d", v));
		int s;
		int accent;
		if (t.isFull() && t.isPlugged() && (Math.abs(ma) < 150)) {
			s = R.string.power_state_full;
			accent = ContextCompat.getColor(ctx, R.color.power_charge);
		} else if (ma > 5) {
			s = R.string.power_state_charging;
			accent = ContextCompat.getColor(ctx, R.color.power_charge);
		} else if (ma < -5) {
			s = (load < 0.4f) ? R.string.power_state_light : (load < 1f) ? R.string.power_state_moderate :
					R.string.power_state_heavy;
			accent = gauge.severityColor(load);
		} else {
			s = R.string.power_state_steady;
			accent = color(ROLE_SECONDARY);
		}
		status.setText(s);
		status.setTextColor(accent);

		String battery = (optBool("battery", true) && (t.getLevel() >= 0)) ? t.getLevel() + "%" : null;
		String left = null;
		long est = t.getEstimateMs();
		if (optBool("left", true) && (est != -1)) {
			long min = Math.abs(est) / 60_000;
			String dur = (min >= 60) ? ctx.getString(R.string.power_hours_minutes, min / 60, min % 60) :
					ctx.getString(R.string.power_minutes, min);
			left = ctx.getString((est < 0) ? R.string.home_power_full_in : R.string.home_power_left, dur);
		}
		String temp = (optBool("temp", true) && (t.getTemperatureTenths() != Integer.MIN_VALUE)) ?
				String.format(Locale.getDefault(), "%.1f°C", t.getTemperatureTenths() / 10f) : null;
		String sb = join(battery, left, temp);
		if ((mode == GAUGE) && (widthPx() > 0)) {
			// One line under the ring: what does not fit in the card's width is left out, the temperature first.
			float room = widthPx() - px(28);
			for (String c : new String[]{sb, join(battery, left, null), join(battery, null, null), join(null, left, null)}) {
				sb = c;
				if (stats.getPaint().measureText(c) <= room) break;
			}
		}
		stats.setText(sb);
		stats.setVisibility(sb.isEmpty() ? GONE : VISIBLE);
		if (mode == GRAPH) graph.setData(t.getGraph());
		if (mode == BREAKDOWN) refreshBreakdown(t);
	}

	private static String join(@androidx.annotation.Nullable String a, @androidx.annotation.Nullable String b,
														 @androidx.annotation.Nullable String c) {
		StringBuilder sb = new StringBuilder();
		for (String x : new String[]{a, b, c}) {
			if (x == null) continue;
			if (sb.length() > 0) sb.append(" · ");
			sb.append(x);
		}
		return sb.toString();
	}

	/** Today's draw per kind of use, and how much of it was in the car. */
	private void refreshBreakdown(PowerUsageTracker t) {
		PowerUsageStore store = t.getStore();
		long day = PowerUsageStore.dayKey(Calendar.getInstance());
		PowerUsageStore.Usage u = store.days(day, day);
		long total = Math.max(1, u.totalUsed());
		for (int i = 0; i < PowerUsageStore.CATS; i++) {
			// µAh to mAh
			catValues[i].setText(String.format(Locale.getDefault(), "%d mAh", Math.round(u.used[i] / 1000.0)));
			catBars[i].setFraction(u.used[i] / (float) total);
		}
		carLine.setText(ctx.getString(R.string.home_power_car_phone,
				Math.round(u.carUsed / 1000.0), Math.round(u.phoneUsed / 1000.0)));
	}
}
