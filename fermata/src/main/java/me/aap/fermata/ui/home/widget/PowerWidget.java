package me.aap.fermata.ui.home.widget;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.addon.power.PowerUsageAddon;
import me.aap.fermata.addon.power.PowerUsageTracker;
import me.aap.fermata.ui.home.HomeHost;
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
	private int mode = -1;
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

		off = note(R.string.home_power_off);
		off.setVisibility(GONE);
		column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		column.addView(off, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
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
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		super.onSizeChanged(w, h, ow, oh);
		if ((column != null) && (mode == GAUGE)) sizeGauge();
	}

	/** Puts the pieces together for the card's size. */
	private void rebuild() {
		int m = (getCellsH() == 1) ? STRIP : (getCellsW() >= 3) ? GRAPH : GAUGE;
		if (m == mode) {
			refresh();
			return;
		}
		mode = m;
		body.removeAllViews();
		detach(gaugeBox);
		detach(valueRow);
		detach(status);
		detach(stats);
		detach(graph);
		switch (m) {
			case STRIP -> {
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
				body.setOrientation(LinearLayout.VERTICAL);
				body.setGravity(Gravity.CENTER);
				gaugeBox.addView(valueRow, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
				body.addView(gaugeBox, new LinearLayout.LayoutParams(px(100), px(100)));
				body.addView(status, new LinearLayout.LayoutParams(-1, -2));
				sizeGauge();
			}
			default -> {
				body.setOrientation(LinearLayout.VERTICAL);
				body.setGravity(Gravity.TOP);
				LinearLayout top = new LinearLayout(ctx);
				top.setOrientation(LinearLayout.HORIZONTAL);
				top.setGravity(Gravity.BOTTOM);
				valueRow.setGravity(Gravity.BOTTOM);
				top.addView(valueRow);
				status.setGravity(Gravity.END);
				LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1f);
				slp.setMarginStart(px(10));
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
		refresh();
	}

	private static void detach(android.view.View v) {
		if (v.getParent() instanceof ViewGroup p) p.removeView(v);
	}

	private void sizeGauge() {
		int availH = getHeight() - px(28) - px(34) - px(34);
		int availW = getWidth() - px(28);
		int side = Math.max(px(64), Math.min(availH, availW));
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

		StringBuilder sb = new StringBuilder();
		if (t.getLevel() >= 0) sb.append(t.getLevel()).append('%');
		long est = t.getEstimateMs();
		if (est != -1) {
			if (sb.length() > 0) sb.append(" · ");
			long min = Math.abs(est) / 60_000;
			String dur = (min >= 60) ? ctx.getString(R.string.power_hours_minutes, min / 60, min % 60) :
					ctx.getString(R.string.power_minutes, min);
			sb.append(ctx.getString((est < 0) ? R.string.home_power_full_in : R.string.home_power_left, dur));
		}
		if ((mode == GRAPH) && (t.getTemperatureTenths() != Integer.MIN_VALUE)) {
			if (sb.length() > 0) sb.append(" · ");
			sb.append(String.format(Locale.getDefault(), "%.1f°C", t.getTemperatureTenths() / 10f));
		}
		stats.setText(sb);
		if (mode == GRAPH) graph.setData(t.getGraph());
	}
}
