package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toIntPx;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.BatteryManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.addon.fuel.FuelLogStore;
import me.aap.fermata.addon.fuel.FuelTracker;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * Fullscreen video playback overlay showing any combination of the clock, battery percentage and
 * battery temperature, in a single horizontal line with a separator between shown items (only
 * when more than one item is visible), scaled by a single size factor. Each item can optionally
 * show a small icon (battery swaps between a plain and a charging glyph) alongside its label.
 */
public class InfoOverlayView extends LinearLayout {
	private static final IntentFilter BATTERY_FILTER =
			new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
	private static final float BASE_TEXT_SIZE_SP = 24f;
	private static final int BASE_PAD_H_DP = 10;
	private static final int BASE_PAD_V_DP = 6;
	private static final int BASE_DIVIDER_MARGIN_DP = 4;
	private static final int BASE_ICON_MARGIN_DP = 4;
	/** Icons are drawn flat black (matching the rest of the app's drawables) and tinted to match
	 * the overlay's white text at render time -- there's no theme-driven tint pipeline for this
	 * view, unlike settings-list icons. */
	private static final int ICON_COLOR = 0xFFFFFFFF;

	private final TextClock clock;
	private final ImageView clockIcon;
	private final LinearLayout clockRow;
	private final TextView batteryPct;
	private final ImageView batteryIcon;
	private final LinearLayout batteryRow;
	private final TextView batteryTemp;
	private final ImageView tempIcon;
	private final LinearLayout tempRow;
	private final TextView distance;
	private final ImageView distanceIcon;
	private final LinearLayout distanceRow;
	private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			updateBattery(intent);
		}
	};
	private final Runnable distanceListener = this::updateDistanceText;
	private boolean batteryReceiverRegistered;
	private boolean distanceListenerRegistered;
	private boolean showClock;
	private boolean showClockIcon;
	private boolean showBatteryPct;
	private boolean showBatteryIcon;
	private boolean showBatteryTemp;
	private boolean showTempIcon;
	private boolean showDistance;
	private boolean showDistanceIcon;
	private boolean charging;
	private boolean onlyWhenControlPanelVisible;
	private boolean controlPanelVisible = true;
	private float size = 1f;

	public InfoOverlayView(Context context) {
		super(context);
		setOrientation(HORIZONTAL);
		setGravity(Gravity.CENTER_VERTICAL);
		setBackgroundResource(R.drawable.clock_bg);
		clock = (TextClock) LayoutInflater.from(context).inflate(R.layout.clock_view, this, false);
		clockIcon = newIconView(context);
		clockIcon.setImageResource(R.drawable.clock);
		clockRow = newRow(context, clockIcon, clock);
		batteryPct = newTextView(context);
		batteryIcon = newIconView(context);
		batteryIcon.setImageResource(R.drawable.battery);
		batteryRow = newRow(context, batteryIcon, batteryPct);
		batteryTemp = newTextView(context);
		tempIcon = newIconView(context);
		tempIcon.setImageResource(R.drawable.thermometer);
		tempRow = newRow(context, tempIcon, batteryTemp);
		distance = newTextView(context);
		distanceIcon = newIconView(context);
		distanceIcon.setImageResource(R.drawable.distance);
		distanceRow = newRow(context, distanceIcon, distance);
		applyPadding();
		applyIconSize();
	}

	private static TextView newTextView(Context ctx) {
		TextView t = new TextView(ctx);
		t.setTextColor(0xFFFFFFFF);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP);
		return t;
	}

	private static ImageView newIconView(Context ctx) {
		ImageView v = new ImageView(ctx);
		v.setImageTintList(ColorStateList.valueOf(ICON_COLOR));
		return v;
	}

	private static LinearLayout newRow(Context ctx, ImageView icon, View label) {
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(icon);
		row.addView(label);
		return row;
	}

	private View newDivider() {
		View v = new View(getContext());
		int m = toIntPx(getContext(), Math.round(BASE_DIVIDER_MARGIN_DP * size));
		LayoutParams lp = new LayoutParams(toIntPx(getContext(), 1), LayoutParams.MATCH_PARENT);
		lp.setMargins(m, 0, m, 0);
		v.setLayoutParams(lp);
		v.setBackgroundColor(0x4DFFFFFF);
		return v;
	}

	/** Updates which items (and their icons) are shown and rebuilds the panel if that changed. */
	public void setItems(boolean showClock, boolean showClockIcon, boolean showBatteryPct,
												boolean showBatteryIcon, boolean showBatteryTemp, boolean showTempIcon,
												boolean showDistance, boolean showDistanceIcon) {
		boolean changed = (this.showClock != showClock) || (this.showBatteryPct != showBatteryPct) ||
				(this.showBatteryTemp != showBatteryTemp) || (this.showClockIcon != showClockIcon) ||
				(this.showBatteryIcon != showBatteryIcon) || (this.showTempIcon != showTempIcon) ||
				(this.showDistance != showDistance) || (this.showDistanceIcon != showDistanceIcon);
		this.showClock = showClock;
		this.showClockIcon = showClockIcon;
		this.showBatteryPct = showBatteryPct;
		this.showBatteryIcon = showBatteryIcon;
		this.showBatteryTemp = showBatteryTemp;
		this.showTempIcon = showTempIcon;
		this.showDistance = showDistance;
		this.showDistanceIcon = showDistanceIcon;
		if (changed) {
			clockIcon.setVisibility(showClockIcon ? VISIBLE : GONE);
			batteryIcon.setVisibility(showBatteryIcon ? VISIBLE : GONE);
			tempIcon.setVisibility(showTempIcon ? VISIBLE : GONE);
			distanceIcon.setVisibility(showDistanceIcon ? VISIBLE : GONE);
			layoutRows();
		}
		updateBatteryReceiverState();
		updateDistanceListenerState();
	}

	public boolean hasVisibleItems() {
		return showClock || showBatteryPct || showBatteryTemp || showDistance;
	}

	/**
	 * Whether the overlay should stay hidden unless the fullscreen video control panel is currently
	 * visible (i.e. the user just tapped the screen to reveal it).
	 */
	public void setOnlyWhenControlPanelVisible(boolean onlyWhenControlPanelVisible) {
		if (this.onlyWhenControlPanelVisible == onlyWhenControlPanelVisible) return;
		this.onlyWhenControlPanelVisible = onlyWhenControlPanelVisible;
		applyVisibility();
	}

	/** Called by {@code VideoView} whenever the control panel's own on-screen visibility flips. */
	public void setControlPanelVisible(boolean controlPanelVisible) {
		if (this.controlPanelVisible == controlPanelVisible) return;
		this.controlPanelVisible = controlPanelVisible;
		if (onlyWhenControlPanelVisible) applyVisibility();
	}

	public void setSize(float size) {
		if (this.size == size) return;
		this.size = size;
		clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		batteryPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		batteryTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		distance.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		applyPadding();
		applyIconSize();
		layoutRows();
	}

	private void applyPadding() {
		int padH = toIntPx(getContext(), Math.round(BASE_PAD_H_DP * size));
		int padV = toIntPx(getContext(), Math.round(BASE_PAD_V_DP * size));
		setPadding(padH, padV, padH, padV);
	}

	private void applyIconSize() {
		int iconSize = Math.round(
				TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size,
						getResources().getDisplayMetrics()));
		int margin = toIntPx(getContext(), Math.round(BASE_ICON_MARGIN_DP * size));
		for (ImageView icon : new ImageView[]{clockIcon, batteryIcon, tempIcon, distanceIcon}) {
			LayoutParams lp = new LayoutParams(iconSize, iconSize);
			lp.setMarginEnd(margin);
			icon.setLayoutParams(lp);
		}
	}

	private void layoutRows() {
		// removeAllViews() detaches clockRow/batteryRow/tempRow too, so they're always free to
		// re-add below regardless of which combination was showing before.
		removeAllViews();
		boolean first = true;

		if (showClock) {
			addView(clockRow);
			first = false;
		}
		if (showBatteryPct) {
			if (!first) addView(newDivider());
			addView(batteryRow);
			first = false;
		}
		if (showBatteryTemp) {
			if (!first) addView(newDivider());
			addView(tempRow);
			first = false;
		}
		if (showDistance) {
			if (!first) addView(newDivider());
			addView(distanceRow);
		}

		applyVisibility();
	}

	private void applyVisibility() {
		boolean visible = hasVisibleItems() && (!onlyWhenControlPanelVisible || controlPanelVisible);
		setVisibility(visible ? VISIBLE : GONE);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		updateBatteryReceiverState();
		updateDistanceListenerState();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		unregisterBatteryReceiver();
		unregisterDistanceListener();
	}

	private void updateDistanceListenerState() {
		boolean needed = isAttachedToWindow() && showDistance;
		if (needed) {
			// Showing this option implies wanting it tracked, even if the Fuel Log tab (the other
			// place tracking normally starts from) was never opened.
			FuelTracker.get(getContext()).start(MainActivityDelegate.get(getContext()));
			if (!distanceListenerRegistered) {
				FuelTracker.get(getContext()).addListener(distanceListener);
				distanceListenerRegistered = true;
			}
			updateDistanceText();
		} else {
			unregisterDistanceListener();
		}
	}

	private void unregisterDistanceListener() {
		if (!distanceListenerRegistered) return;
		distanceListenerRegistered = false;
		FuelTracker.get(getContext()).removeListener(distanceListener);
	}

	private void updateDistanceText() {
		double km = FuelLogStore.getTripDistanceKm(MainActivityDelegate.get(getContext()).getPrefs());
		distance.setText(String.format(Locale.getDefault(), "%.1f km", km));
	}

	private void updateBatteryReceiverState() {
		boolean needed = isAttachedToWindow() && (showBatteryPct || showBatteryTemp || showBatteryIcon);
		if (needed) {
			if (!batteryReceiverRegistered) {
				ContextCompat.registerReceiver(getContext(), batteryReceiver, BATTERY_FILTER,
						ContextCompat.RECEIVER_NOT_EXPORTED);
				batteryReceiverRegistered = true;
			}
			// registerReceiver() only hands back the current sticky intent on the call that actually
			// registers the receiver -- if it was already registered (e.g. percentage was already
			// shown and temperature just got turned on), that path is skipped above and the
			// newly-shown field would otherwise sit blank until the next real battery-changed
			// broadcast, which can be a long time away. A null-receiver registration is the standard
			// way to read the current sticky value on demand instead, so do that unconditionally here.
			Intent sticky = getContext().registerReceiver(null, BATTERY_FILTER);
			if (sticky != null) updateBattery(sticky);
		} else {
			unregisterBatteryReceiver();
		}
	}

	private void unregisterBatteryReceiver() {
		if (!batteryReceiverRegistered) return;
		batteryReceiverRegistered = false;
		try {
			getContext().unregisterReceiver(batteryReceiver);
		} catch (IllegalArgumentException ignore) {
			// Not registered -- nothing to do.
		}
	}

	private void updateBattery(@Nullable Intent i) {
		if (i == null) return;

		if (showBatteryPct) {
			int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
			int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
			batteryPct.setText(((level >= 0) && (scale > 0)) ?
					String.format(Locale.getDefault(), "%d%%", Math.round(level * 100f / scale)) : "--%");
		}

		if (showBatteryTemp) {
			int tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
			batteryTemp.setText((tenths != Integer.MIN_VALUE) ?
					String.format(Locale.getDefault(), "%.1f°C", tenths / 10f) : "--°C");
		}

		if (showBatteryIcon) {
			int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
			boolean nowCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING) ||
					(status == BatteryManager.BATTERY_STATUS_FULL);
			if (nowCharging != charging) {
				charging = nowCharging;
				batteryIcon.setImageResource(charging ? R.drawable.battery_charging : R.drawable.battery);
			}
		}
	}
}
