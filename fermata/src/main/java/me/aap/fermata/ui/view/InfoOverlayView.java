package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toIntPx;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.os.BatteryManager;
import android.text.format.Formatter;
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
import me.aap.fermata.addon.data.DataUsageStore;
import me.aap.fermata.addon.data.DataUsageTracker;
import me.aap.fermata.addon.fuel.FuelLogStore;
import me.aap.fermata.addon.fuel.FuelTracker;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;
import me.aap.fermata.ytdl.YtDownloads;

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
	private static final float SIZE_SCALE = 0.65f;
	private static final int BASE_PAD_H_DP = 10;
	private static final int BASE_PAD_V_DP = 6;
	private static final int BASE_DIVIDER_MARGIN_DP = 4;
	private static final int BASE_ICON_MARGIN_DP = 4;
	/** Icons are drawn flat black (matching the rest of the app's drawables) and tinted to match
	 * the overlay's white text at render time -- there's no theme-driven tint pipeline for this
	 * view, unlike settings-list icons. */
	private static final int ICON_COLOR = 0xFFFFFFFF;

	/** First, icon only: what's playing has a downloaded copy (see {@link #setDownloadedItem}). */
	private final ImageView downloadedIcon;
	private boolean showDownloaded;
	// Whether the icon is laid out: the option is on and what's playing is downloaded.
	private boolean downloadedShown;
	private boolean playableListenerRegistered;
	private final FermataServiceUiBinder.Listener playableListener =
			new FermataServiceUiBinder.Listener() {
				@Override
				public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
					updateDownloaded();
				}

				@Override
				public void onPlaybackStopped() {
					updateDownloaded();
				}
			};
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
	private final TextView dataUsage;
	private final ImageView dataUsageIcon;
	private final LinearLayout dataUsageRow;
	private final TextView dataRemaining;
	private final ImageView dataRemainingIcon;
	private final LinearLayout dataRemainingRow;
	private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			updateBattery(intent);
		}
	};
	private final Runnable distanceListener = this::updateDistanceText;
	private final Runnable dataListener = this::updateDataText;
	private boolean batteryReceiverRegistered;
	private boolean distanceListenerRegistered;
	private boolean dataListenerRegistered;
	private boolean showDataUsage;
	private boolean showDataRemaining;
	private boolean showDataIcon = true;
	// Whether the Remaining item is actually laid out: only while a data limit is set.
	private boolean dataRemainingShown;
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
	// The items' colour: white on the shade, see setShaded().
	private int fgColor = ICON_COLOR;

	/**
	 * With (the default) or without its rounded dark shade behind the items. Without it (e.g. on
	 * the tool bar's own pill), the items take the theme's text colour instead of white, which the
	 * shade was there to make readable.
	 */
	public void setShaded(boolean shaded) {
		if (shaded) {
			setBackgroundResource(MusicPlayerFragment.isLightTheme(getContext()) ?
					R.drawable.clock_bg_light : R.drawable.clock_bg);
			setForegroundColor(ICON_COLOR);
		} else {
			setBackground(null);
			TypedArray ta = getContext().obtainStyledAttributes(
					new int[]{android.R.attr.textColorPrimary});
			int c = ta.getColor(0, ICON_COLOR);
			ta.recycle();
			setForegroundColor(c);
		}
	}

	private void setForegroundColor(int color) {
		fgColor = color;
		for (LinearLayout row : new LinearLayout[]{clockRow, batteryRow, tempRow, distanceRow,
				dataUsageRow, dataRemainingRow}) {
			for (int i = 0, n = row.getChildCount(); i < n; i++) {
				View v = row.getChildAt(i);
				if (v instanceof TextView t) t.setTextColor(color);
				else if (v instanceof ImageView img) img.setImageTintList(ColorStateList.valueOf(color));
			}
		}
		downloadedIcon.setImageTintList(ColorStateList.valueOf(color));
		for (int i = 0, n = getChildCount(); i < n; i++) {
			View v = getChildAt(i);
			if (!(v instanceof LinearLayout) && (v != downloadedIcon)) v.setBackgroundColor(dividerColor());
		}
	}

	private int dividerColor() {
		return (fgColor & 0x00FFFFFF) | 0x4D000000;
	}

	public InfoOverlayView(Context context) {
		super(context);
		setOrientation(HORIZONTAL);
		setGravity(Gravity.CENTER_VERTICAL);
		// A darker shade on a light theme: the white text is otherwise hard to read over light tabs.
		setBackgroundResource(MusicPlayerFragment.isLightTheme(context) ? R.drawable.clock_bg_light :
				R.drawable.clock_bg);
		downloadedIcon = newIconView(context);
		downloadedIcon.setImageResource(R.drawable.download_done);
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
		dataUsage = newTextView(context);
		dataUsageIcon = newIconView(context);
		dataUsageIcon.setImageResource(R.drawable.data_usage);
		dataUsageRow = newRow(context, dataUsageIcon, dataUsage);
		dataRemaining = newTextView(context);
		dataRemainingIcon = newIconView(context);
		dataRemainingIcon.setImageResource(R.drawable.data_remaining);
		dataRemainingRow = newRow(context, dataRemainingIcon, dataRemaining);
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
		v.setBackgroundColor(dividerColor());
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

	/**
	 * The Data Usage items: data used in the current cycle, and what's left of the data limit (only
	 * while one is set), see {@link DataUsageTracker}.
	 */
	public void setDataItems(boolean showUsage, boolean showRemaining, boolean showIcon) {
		boolean changed = (showDataUsage != showUsage) || (showDataRemaining != showRemaining) ||
				(showDataIcon != showIcon);
		showDataUsage = showUsage;
		showDataRemaining = showRemaining;
		showDataIcon = showIcon;
		if (changed) {
			dataUsageIcon.setVisibility(showIcon ? VISIBLE : GONE);
			dataRemainingIcon.setVisibility(showIcon ? VISIBLE : GONE);
			dataRemainingShown = showRemaining && (DataUsageTracker.getLimit() > 0);
			layoutRows();
		}
		updateDataListenerState();
	}

	/**
	 * The downloaded status item: an icon, first and without text, shown only while what's playing
	 * (a YouTube video or track, streamed or from its file) has a downloaded copy.
	 */
	public void setDownloadedItem(boolean show) {
		if (showDownloaded == show) return;
		showDownloaded = show;
		updatePlayableListenerState();
		updateDownloaded();
	}

	private void updateDownloaded() {
		boolean shown = showDownloaded && isPlayingDownloaded();
		if (shown == downloadedShown) return;
		downloadedShown = shown;
		layoutRows();
	}

	@Nullable
	private FermataServiceUiBinder binder() {
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
		return (a == null) ? null : a.getMediaServiceBinder();
	}

	private boolean isPlayingDownloaded() {
		FermataServiceUiBinder b = binder();
		PlayableItem i = (b == null) ? null : b.getCurrentItem();
		return YtDownloads.get().isDownloaded(YtDownloads.videoIdOf(i));
	}

	private void updatePlayableListenerState() {
		boolean needed = isAttachedToWindow() && showDownloaded;
		FermataServiceUiBinder b = binder();
		if (needed && !playableListenerRegistered && (b != null)) {
			b.addBroadcastListener(playableListener);
			playableListenerRegistered = true;
		} else if (!needed) {
			unregisterPlayableListener();
		}
	}

	private void unregisterPlayableListener() {
		if (!playableListenerRegistered) return;
		playableListenerRegistered = false;
		FermataServiceUiBinder b = binder();
		if (b != null) b.removeBroadcastListener(playableListener);
	}

	public boolean hasVisibleItems() {
		return downloadedShown || showClock || showBatteryPct || showBatteryTemp || showDistance || showDataUsage ||
				dataRemainingShown;
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
		// The size setting's 1.0 is this much of the original base size, which read as too big.
		size *= SIZE_SCALE;
		if (this.size == size) return;
		this.size = size;
		clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		batteryPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		batteryTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		distance.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		dataUsage.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		dataRemaining.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
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
		for (ImageView icon : new ImageView[]{clockIcon, batteryIcon, tempIcon, distanceIcon,
				dataUsageIcon, dataRemainingIcon}) {
			LayoutParams lp = new LayoutParams(iconSize, iconSize);
			lp.setMarginEnd(margin);
			icon.setLayoutParams(lp);
		}
		// On its own, no text beside it: no margin either.
		downloadedIcon.setLayoutParams(new LayoutParams(iconSize, iconSize));
	}

	private void layoutRows() {
		// removeAllViews() detaches clockRow/batteryRow/tempRow too, so they're always free to
		// re-add below regardless of which combination was showing before.
		removeAllViews();
		boolean first = true;

		if (downloadedShown) {
			addView(downloadedIcon);
			first = false;
		}
		if (showClock) {
			if (!first) addView(newDivider());
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
			first = false;
		}
		if (showDataUsage) {
			if (!first) addView(newDivider());
			addView(dataUsageRow);
			first = false;
		}
		if (dataRemainingShown) {
			if (!first) addView(newDivider());
			addView(dataRemainingRow);
		}

		applyVisibility();
	}

	private void applyVisibility() {
		boolean visible = hasVisibleItems() && (!onlyWhenControlPanelVisible || controlPanelVisible);
		animate().cancel();

		// Coming and going with the control panel, it fades in and out with it.
		if (!onlyWhenControlPanelVisible || !isAttachedToWindow()) {
			setAlpha(1f);
			setVisibility(visible ? VISIBLE : GONE);
			if (!visible) notifyHidden();
		} else if (visible) {
			if (getVisibility() != VISIBLE) {
				setAlpha(0f);
				setVisibility(VISIBLE);
			}
			animate().alpha(1f).setDuration(FADE_MS).start();
		} else if (getVisibility() == VISIBLE) {
			animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
				setVisibility(GONE);
				setAlpha(1f);
				notifyHidden();
			}).start();
		}
	}

	private static final long FADE_MS = 200L;
	@Nullable
	private Runnable hiddenListener;

	/** Called each time the overlay has gone out of sight (see {@code VideoView#moveInfoOverlay}). */
	public void setHiddenListener(@Nullable Runnable l) {
		hiddenListener = l;
	}

	private void notifyHidden() {
		if (hiddenListener != null) hiddenListener.run();
	}

	public boolean isOnlyWhenControlPanelVisible() {
		return onlyWhenControlPanelVisible;
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		updatePlayableListenerState();
		updateDownloaded();
		updateBatteryReceiverState();
		updateDistanceListenerState();
		updateDataListenerState();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		// Still counts as attached in here: let go of it directly.
		unregisterPlayableListener();
		unregisterBatteryReceiver();
		unregisterDistanceListener();
		unregisterDataListener();
	}

	private void updateDataListenerState() {
		if (isAttachedToWindow() && (showDataUsage || showDataRemaining)) {
			if (!dataListenerRegistered) {
				DataUsageTracker.get().addListener(dataListener);
				dataListenerRegistered = true;
			}
			updateDataText();
		} else {
			unregisterDataListener();
		}
	}

	private void unregisterDataListener() {
		if (!dataListenerRegistered) return;
		dataListenerRegistered = false;
		DataUsageTracker.get().removeListener(dataListener);
	}

	private void updateDataText() {
		DataUsageTracker t = DataUsageTracker.get();
		Context ctx = getContext();
		if (showDataUsage) {
			dataUsage.setText(Formatter.formatShortFileSize(ctx, DataUsageStore.sum(t.getCycleUsage())));
		}
		long remaining = showDataRemaining ? t.getRemaining() : -1;
		if (remaining >= 0) {
			dataRemaining.setText(ctx.getString(R.string.data_usage_left,
					Formatter.formatShortFileSize(ctx, remaining)));
		}
		// The limit may have been set or removed in Settings meanwhile.
		boolean remainingShown = remaining >= 0;
		if (remainingShown != dataRemainingShown) {
			dataRemainingShown = remainingShown;
			layoutRows();
		}
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
