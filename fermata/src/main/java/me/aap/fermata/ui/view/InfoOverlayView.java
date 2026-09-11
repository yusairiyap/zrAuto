package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toIntPx;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Locale;

import me.aap.fermata.R;

/**
 * Fullscreen video playback overlay showing any combination of the clock, battery percentage and
 * battery temperature, in a single horizontal line with a separator between shown items (only
 * when more than one item is visible), scaled by a single size factor.
 */
public class InfoOverlayView extends LinearLayout {
	private static final IntentFilter BATTERY_FILTER =
			new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
	private static final float BASE_TEXT_SIZE_SP = 24f;
	private static final int BASE_PAD_H_DP = 10;
	private static final int BASE_PAD_V_DP = 6;
	private static final int BASE_DIVIDER_MARGIN_DP = 4;

	private final TextClock clock;
	private final TextView batteryPct;
	private final TextView batteryTemp;
	private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			updateBattery(intent);
		}
	};
	private boolean batteryReceiverRegistered;
	private boolean showClock;
	private boolean showBatteryPct;
	private boolean showBatteryTemp;
	private float size = 1f;

	public InfoOverlayView(Context context) {
		super(context);
		setOrientation(HORIZONTAL);
		setGravity(Gravity.CENTER_VERTICAL);
		setBackgroundResource(R.drawable.clock_bg);
		clock = (TextClock) LayoutInflater.from(context).inflate(R.layout.clock_view, this, false);
		batteryPct = newTextView(context);
		batteryTemp = newTextView(context);
		applyPadding();
	}

	private static TextView newTextView(Context ctx) {
		TextView t = new TextView(ctx);
		t.setTextColor(0xFFFFFFFF);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP);
		return t;
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

	/** Updates which items are shown and rebuilds the panel (with separators) if that changed. */
	public void setItems(boolean showClock, boolean showBatteryPct, boolean showBatteryTemp) {
		boolean changed = (this.showClock != showClock) || (this.showBatteryPct != showBatteryPct) ||
				(this.showBatteryTemp != showBatteryTemp);
		this.showClock = showClock;
		this.showBatteryPct = showBatteryPct;
		this.showBatteryTemp = showBatteryTemp;
		if (changed) layoutRows();
		updateBatteryReceiverState();
	}

	public boolean hasVisibleItems() {
		return showClock || showBatteryPct || showBatteryTemp;
	}

	public void setSize(float size) {
		if (this.size == size) return;
		this.size = size;
		clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		batteryPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		batteryTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		applyPadding();
		layoutRows();
	}

	private void applyPadding() {
		int padH = toIntPx(getContext(), Math.round(BASE_PAD_H_DP * size));
		int padV = toIntPx(getContext(), Math.round(BASE_PAD_V_DP * size));
		setPadding(padH, padV, padH, padV);
	}

	private void layoutRows() {
		// removeAllViews() detaches clock/batteryPct/batteryTemp too, so they're always free to
		// re-add below regardless of which combination was showing before.
		removeAllViews();
		boolean first = true;

		if (showClock) {
			addView(clock);
			first = false;
		}
		if (showBatteryPct) {
			if (!first) addView(newDivider());
			addView(batteryPct);
			first = false;
		}
		if (showBatteryTemp) {
			if (!first) addView(newDivider());
			addView(batteryTemp);
		}

		setVisibility(hasVisibleItems() ? VISIBLE : GONE);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		updateBatteryReceiverState();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		unregisterBatteryReceiver();
	}

	private void updateBatteryReceiverState() {
		boolean needed = isAttachedToWindow() && (showBatteryPct || showBatteryTemp);
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
	}
}
