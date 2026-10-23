package me.aap.fermata.ui.home.widget;

import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.fuel.FuelLogAddon;
import me.aap.fermata.addon.fuel.FuelLogEntry;
import me.aap.fermata.addon.fuel.FuelLogStore;
import me.aap.fermata.addon.fuel.FuelRefuelDialog;
import me.aap.fermata.addon.fuel.FuelTracker;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;

/**
 * The fuel log at a glance: the distance driven on the current trip (since the last refuel, counted
 * live while the car is connected), and the last refuel with its distance, the day of the week and
 * the date. A strip shows both figures side by side; taller cards stack them and add the refuel button.
 */
final class FuelWidget extends HomeWidgetView {
	private LinearLayout column;
	private TextView tripValue;
	private TextView tripCaption;
	private TextView lastValue;
	private TextView lastCaption;
	private TextView lastDay;
	private TextView off;
	private LinearLayout trip;
	private LinearLayout last;
	private LinearLayout figures;
	private TextView refuel;
	private View headerRow;
	private final Runnable listener = this::refresh;

	FuelWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	static boolean enabled() {
		AddonManager m = AddonManager.get();
		return (m != null) && (m.getAddon(FuelLogAddon.class) != null);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		headerRow = header(() -> open(R.id.fuel_log_addon));
		column.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

		figures = new LinearLayout(ctx);
		figures.setGravity(Gravity.CENTER_VERTICAL);

		trip = block();
		tripCaption = text(ROLE_SECONDARY, 12, false);
		tripCaption.setText(R.string.home_fuel_trip);
		tripValue = text(ROLE_PRIMARY, 26, true);
		trip.addView(tripCaption);
		trip.addView(tripValue);

		last = block();
		lastCaption = text(ROLE_SECONDARY, 12, false);
		lastCaption.setText(R.string.home_fuel_last);
		lastValue = text(ROLE_PRIMARY, 19, true);
		lastDay = text(ROLE_SECONDARY, 12.5f, false);
		last.addView(lastCaption);
		last.addView(lastValue);
		last.addView(lastDay);

		figures.addView(trip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		figures.addView(last, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
		LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		flp.topMargin = px(2);
		column.addView(figures, flp);

		refuel = chip(R.drawable.fuel, ctx.getString(R.string.fuel_log_refuel),
				() -> FuelRefuelDialog.show(host.activity(), this::refresh));
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		rlp.topMargin = px(6);
		column.addView(refuel, rlp);

		off = note(R.string.home_fuel_off);
		off.setVisibility(GONE);
		column.addView(off, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	private LinearLayout block() {
		LinearLayout b = new LinearLayout(ctx);
		b.setOrientation(LinearLayout.VERTICAL);
		b.setGravity(Gravity.CENTER_VERTICAL);
		return b;
	}

	@Override
	protected void onBind() {
		layoutFor();
		refresh();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column != null) layoutFor();
	}

	@Override
	protected void onSized(int w, int h) {
		if (column != null) layoutFor();
	}

	/** A strip lays the two figures side by side; a taller card stacks them. */
	private void layoutFor() {
		boolean strip = getCellsH() == 1;
		boolean tall = getCellsH() >= 2 && getCellsW() < 4;
		// A strip is 90dp high: the title row has no room next to two figures, so the figures are the card
		// (a tap on them opens the tab, as the title does elsewhere).
		headerRow.setVisibility(strip ? GONE : VISIBLE);
		figures.setOnClickListener(strip ? v -> open(R.id.fuel_log_addon) : null);
		figures.setClickable(strip);
		int avail = ((heightPx() > 0) ? heightPx() : px(getCellsH() * 90f)) - px(28) - (strip ? 0 : px(34));
		boolean room = avail >= px(118);
		boolean wide = getCellsW() >= 4;
		figures.setOrientation(tall ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
		for (LinearLayout b : new LinearLayout[]{trip, last}) {
			LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
			lp.width = tall ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
			lp.height = tall ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
			lp.weight = (b == last && !tall) ? 1.3f : 1f;
			b.setLayoutParams(lp);
		}
		tripValue.setTextSize(strip ? (wide ? 26 : 20) : tall ? (avail >= px(170) ? 26 : 22) : 26);
		lastValue.setTextSize(strip ? (wide ? 19 : 16) : tall ? (avail >= px(170) ? 19 : 17) : 19);
		float cap = (strip && !wide) ? 11 : 12;
		tripCaption.setTextSize(cap);
		lastCaption.setTextSize(cap);
		lastDay.setVisibility((strip && !wide) || (tall && avail < px(170)) ? GONE : VISIBLE);
		refuel.setVisibility((getCellsH() >= 2) && room ? VISIBLE : GONE);
	}

	@Override
	protected void onActive() {
		if (!enabled()) return;
		FuelTracker.get(host.activity().getContext()).addListener(listener);
		refresh();
	}

	@Override
	protected void onInactive() {
		if (!enabled()) return;
		FuelTracker.get(host.activity().getContext()).removeListener(listener);
	}

	private void refresh() {
		if (column == null) return;
		boolean on = enabled();
		off.setVisibility(on ? GONE : VISIBLE);
		figures.setVisibility(on ? VISIBLE : GONE);
		if (!on) {
			refuel.setVisibility(GONE);
			return;
		}
		var prefs = host.activity().getPrefs();
		tripValue.setText(km(FuelLogStore.getTripDistanceKm(prefs)));
		FuelLogEntry refueled = null;
		List<FuelLogEntry> entries = FuelLogStore.getEntries(prefs);
		for (FuelLogEntry e : entries) {
			if (e.type == FuelLogEntry.Type.REFUEL) {
				refueled = e;
				break;
			}
		}
		if (refueled == null) {
			lastValue.setText(R.string.home_fuel_none);
			lastDay.setText("");
		} else {
			lastValue.setText(km(refueled.distanceKm));
			long t = refueled.time;
			String day = DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_WEEKDAY |
					DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_ALL);
			CharSequence ago = DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(),
					DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
			lastDay.setText(day + " · " + ago);
		}
		layoutFor();
	}

	private static String km(double km) {
		return String.format(Locale.getDefault(), "%.1f km", km);
	}
}
