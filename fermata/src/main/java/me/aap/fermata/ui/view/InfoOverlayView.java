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
import android.view.animation.DecelerateInterpolator;
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
	// The Android Auto session's data (DataSessionLimit): text over a mini progress bar.
	private final TextView dataSession;
	private final ImageView dataSessionIcon;
	private final android.widget.ProgressBar dataSessionBar;
	private final LinearLayout dataSessionRow;
	// The battery current (PowerUsageTracker), live while the overlay is on screen.
	private final TextView power;
	private final ImageView powerIcon;
	private final LinearLayout powerRow;
	private final Runnable powerListener = this::updatePowerText;
	private boolean powerListenerRegistered;
	private boolean showPower;
	private final Runnable sessionListener = this::updateSessionText;
	private boolean sessionListenerRegistered;
	private boolean showDataSession;
	private boolean showSessionBar = true;
	private boolean showSessionLeft;
	// Laid out only during a session.
	private boolean dataSessionShown;
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
				dataUsageRow, dataRemainingRow, powerRow}) {
			for (int i = 0, n = row.getChildCount(); i < n; i++) {
				View v = row.getChildAt(i);
				if (v instanceof TextView t) t.setTextColor(color);
				else if (v instanceof ImageView img) img.setImageTintList(ColorStateList.valueOf(color));
			}
		}
		downloadedIcon.setImageTintList(ColorStateList.valueOf(color));
		dataSession.setTextColor(color);
		dataSessionIcon.setImageTintList(ColorStateList.valueOf(color));
		dataSessionBar.setProgressBackgroundTintList(ColorStateList.valueOf((color & 0x00FFFFFF) | 0x4D000000));
		updateSessionBarColor();
		for (int i = 0, n = getChildCount(); i < n; i++) {
			View v = getChildAt(i);
			if (!(v instanceof LinearLayout) && (v != downloadedIcon) && (v != chipFlow)) v.setBackgroundColor(dividerColor());
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
		downloadedIcon.setImageResource(R.drawable.download);
		clock = (TextClock) LayoutInflater.from(context).inflate(R.layout.clock_view, this, false);
		clock.setSingleLine(true);
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
		dataSession = newTextView(context);
		dataSessionIcon = newIconView(context);
		dataSessionIcon.setImageResource(R.drawable.car);
		dataSessionBar = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
		dataSessionBar.setIndeterminate(false);
		dataSessionBar.setMax(1000);
		LinearLayout sessionCol = new LinearLayout(context);
		sessionCol.setOrientation(VERTICAL);
		sessionCol.addView(dataSession);
		sessionCol.addView(dataSessionBar, new LayoutParams(LayoutParams.MATCH_PARENT, toIntPx(context, 3)));
		dataSessionRow = newRow(context, dataSessionIcon, sessionCol);
		power = newTextView(context);
		powerIcon = newIconView(context);
		powerIcon.setImageResource(R.drawable.power_usage);
		powerRow = newRow(context, powerIcon, power);
		applyPadding();
		applyIconSize();
	}

	private static TextView newTextView(Context ctx) {
		TextView t = new TextView(ctx);
		t.setTextColor(0xFFFFFFFF);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP);
		t.setSingleLine(true);
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
			dataSessionIcon.setVisibility(showIcon ? VISIBLE : GONE);
			dataRemainingShown = showRemaining && (DataUsageTracker.getLimit() > 0);
			layoutRows();
		}
		updateDataListenerState();
	}

	/**
	 * The Android Auto session's data item (see {@link me.aap.fermata.addon.data.DataSessionLimit}):
	 * only laid out while a session goes on, used or what's left, over a mini progress bar unless
	 * {@code bar} is off.
	 */
	public void setSessionItem(boolean show, boolean bar, boolean left) {
		boolean changed = (showDataSession != show) || (showSessionBar != bar) || (showSessionLeft != left);
		showDataSession = show;
		showSessionBar = bar;
		showSessionLeft = left;
		if (changed) {
			dataSessionIcon.setVisibility(showDataIcon ? VISIBLE : GONE);
			dataSessionBar.setVisibility(bar ? VISIBLE : GONE);
			updateSessionText();
		}
		updateSessionListenerState();
	}

	private void updateSessionListenerState() {
		me.aap.fermata.addon.data.DataSessionLimit l = me.aap.fermata.addon.data.DataSessionLimit.get();
		if (isAttachedToWindow() && showDataSession) {
			if (!sessionListenerRegistered) {
				l.addListener(sessionListener);
				sessionListenerRegistered = true;
			}
			updateSessionText();
		} else if (sessionListenerRegistered) {
			sessionListenerRegistered = false;
			l.removeListener(sessionListener);
		}
	}

	private void updateSessionText() {
		me.aap.fermata.addon.data.DataSessionLimit l = me.aap.fermata.addon.data.DataSessionLimit.get();
		boolean shown = showDataSession && l.isActive();
		if (shown) {
			Context ctx = getContext();
			dataSession.setText(showSessionLeft ? ctx.getString(R.string.data_usage_left,
					Formatter.formatShortFileSize(ctx, l.getRemaining())) :
					Formatter.formatShortFileSize(ctx, l.getUsed()));
			dataSessionBar.setProgress(Math.round(1000 * l.getFraction()));
			updateSessionBarColor();
		}
		if (shown != dataSessionShown) {
			dataSessionShown = shown;
			layoutRows();
		}
	}

	/** The overlay's own colour, amber from 80% of the limit and red past it. */
	private void updateSessionBarColor() {
		float f = me.aap.fermata.addon.data.DataSessionLimit.get().getFraction();
		int c = (f >= 1f) ? 0xFFFF5252 : (f >= 0.8f) ? 0xFFFFB300 : fgColor;
		dataSessionBar.setProgressTintList(ColorStateList.valueOf(c));
	}

	/**
	 * The battery current item (see {@link me.aap.fermata.addon.power.PowerUsageTracker}): "-342 mA"
	 * while the phone draws from the battery, "+1250 mA" while it charges, live. The tracker reads
	 * once a second for as long as this is on screen.
	 */
	public void setPowerItem(boolean show, boolean icon) {
		boolean changed = (showPower != show) || (powerIcon.getVisibility() != (icon ? VISIBLE : GONE));
		showPower = show;
		powerIcon.setVisibility(icon ? VISIBLE : GONE);
		if (changed) layoutRows();
		updatePowerListenerState();
	}

	private void updatePowerListenerState() {
		me.aap.fermata.addon.power.PowerUsageTracker t = me.aap.fermata.addon.power.PowerUsageTracker.get();
		// Only while it can be seen: a hidden tab's overlay stays attached, and reading the battery
		// every second for nobody would be a waste.
		if (isAttachedToWindow() && showPower && isShown()) {
			if (!powerListenerRegistered) {
				t.addListener(powerListener);
				powerListenerRegistered = true;
			}
			updatePowerText();
		} else if (powerListenerRegistered) {
			powerListenerRegistered = false;
			t.removeListener(powerListener);
		}
	}

	@Override
	public void onVisibilityAggregated(boolean isVisible) {
		super.onVisibilityAggregated(isVisible);
		if (showPower) updatePowerListenerState();
	}

	private void updatePowerText() {
		me.aap.fermata.addon.power.PowerUsageTracker t = me.aap.fermata.addon.power.PowerUsageTracker.get();
		long ma = Math.round(t.getCurrent());
		String v = !t.hasCurrent() ? "--" : (ma == 0) ? "0" : String.format(Locale.getDefault(), "%+d", ma);
		power.setText(getContext().getString(R.string.power_overlay_text, v));
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
				dataRemainingShown || dataSessionShown || showPower;
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
		dataSession.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		power.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size);
		LayoutParams blp = (LayoutParams) dataSessionBar.getLayoutParams();
		blp.height = Math.max(2, toIntPx(getContext(), Math.round(3 * size)));
		dataSessionBar.setLayoutParams(blp);
		applyPadding();
		applyIconSize();
		layoutRows();
	}

	private void applyPadding() {
		int padH = toIntPx(getContext(), Math.round(BASE_PAD_H_DP * size));
		int padV = toIntPx(getContext(), Math.round(BASE_PAD_V_DP * size));
		if (chipFill != 0) setPadding(0, 0, 0, 0);
		else setPadding(padH, padV, padH, padV);
	}

	private void applyIconSize() {
		int iconSize = Math.round(
				TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * size,
						getResources().getDisplayMetrics()));
		int margin = toIntPx(getContext(), Math.round(BASE_ICON_MARGIN_DP * size));
		for (ImageView icon : new ImageView[]{clockIcon, batteryIcon, tempIcon, distanceIcon,
				dataUsageIcon, dataRemainingIcon, dataSessionIcon, powerIcon}) {
			LayoutParams lp = new LayoutParams(iconSize, iconSize);
			lp.setMarginEnd(margin);
			icon.setLayoutParams(lp);
		}
		// On its own, no text beside it: no margin either.
		downloadedIcon.setLayoutParams(new LayoutParams(iconSize, iconSize));
	}

	/** The overlay's items, in their default order: what {@link #setOrder} and the settings page use. */
	public static final String[] ITEM_KEYS = {"downloaded", "clock", "battery", "temp", "distance",
			"data_usage", "data_remaining", "data_session", "power"};
	private String[] order = ITEM_KEYS;

	/** The order of the items, as the setting stores it (keys joined by commas; empty: default). */
	public static String[] parseOrder(@Nullable String s) {
		java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
		if ((s != null) && !s.isEmpty()) {
			for (String k : s.split(",")) {
				if (java.util.Arrays.asList(ITEM_KEYS).contains(k)) keys.add(k);
			}
		}
		// Anything not mentioned (an item added since) follows, in default order.
		keys.addAll(java.util.Arrays.asList(ITEM_KEYS));
		return keys.toArray(new String[0]);
	}

	public void setOrder(@Nullable String s) {
		String[] o = parseOrder(s);
		if (java.util.Arrays.equals(o, order)) return;
		order = o;
		layoutRows();
	}

	private boolean isItemShown(String key) {
		return switch (key) {
			case "downloaded" -> downloadedShown;
			case "clock" -> showClock;
			case "battery" -> showBatteryPct;
			case "temp" -> showBatteryTemp;
			case "distance" -> showDistance;
			case "data_usage" -> showDataUsage;
			case "data_remaining" -> dataRemainingShown;
			case "data_session" -> dataSessionShown;
			case "power" -> showPower;
			default -> false;
		};
	}

	private View itemView(String key) {
		return switch (key) {
			case "downloaded" -> downloadedIcon;
			case "clock" -> clockRow;
			case "battery" -> batteryRow;
			case "temp" -> tempRow;
			case "distance" -> distanceRow;
			case "data_usage" -> dataUsageRow;
			case "data_session" -> dataSessionRow;
			case "power" -> powerRow;
			default -> dataRemainingRow;
		};
	}

	private void layoutRows() {
		// removeAllViews() detaches every row too, so they're always free to re-add below
		// regardless of which combination was showing before.
		removeAllViews();
		if (chipFill != 0) {
			layoutChips();
			applyVisibility();
			return;
		}
		boolean first = true;

		for (String key : order) {
			if (!isItemShown(key)) continue;
			if (!first) addView(newDivider());
			addView(itemView(key));
			first = false;
		}

		applyVisibility();
	}

	/**
	 * Chip mode, for the Home tab's info overlay widget: no shaded bar and no dividers, every item
	 * in its own rounded chip, wrapping onto more lines when the card is narrow. Order, icons and
	 * which items show are exactly the overlay's own.
	 */
	public void setChipStyle(int fg, int fill) {
		boolean changed = (chipFill != fill);
		chipFill = fill;
		if (chipFlow == null) {
			chipFlow = new ChipFlowLayout(getContext());
			chipFlow.setOneRow(oneRow);
		}
		setBackground(null);
		applyPadding();
		setForegroundColor(fg);
		if (changed) {
			chipWrappers.clear();
		}
		layoutRows();
	}

	private void layoutChips() {
		chipFlow.removeAllViews();
		for (LinearLayout w : chipWrappers.values()) w.removeAllViews();
		int padH = toIntPx(getContext(), Math.round(10 * size));
		int padV = toIntPx(getContext(), Math.round(5 * size));
		// Every chip is as tall as one with text; the icon-only one (downloaded) is as wide as it is tall, so
		// it is a round chip of the same size rather than a smaller one.
		int rowH = batteryPct.getLineHeight() + 2 * padV;
		for (String key : order) {
			if (!isItemShown(key)) continue;
			LinearLayout w = chipWrappers.get(key);
			if (w == null) {
				w = new LinearLayout(getContext());
				w.setGravity(Gravity.CENTER_VERTICAL);
				android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
				d.setColor(chipFill);
				d.setCornerRadius(toIntPx(getContext(), 40));
				w.setBackground(d);
				chipWrappers.put(key, w);
			}
			w.setPadding(padH, padV, padH, padV);
			w.setMinimumHeight(rowH);
			boolean iconOnly = "downloaded".equals(key);
			w.setMinimumWidth(iconOnly ? rowH : 0);
			w.setGravity(iconOnly ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
			w.addView(itemView(key));
			chipFlow.addView(w);
		}
		addView(chipFlow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
	}

	private int chipFill;
	private ChipFlowLayout chipFlow;
	private boolean oneRow;

	/** In chip mode: one row however many chips (for a scroller to show part of), instead of wrapping. */
	public void setOneRow(boolean oneRow) {
		this.oneRow = oneRow;
		if (chipFlow != null) chipFlow.setOneRow(oneRow);
	}
	private final java.util.Map<String, LinearLayout> chipWrappers = new java.util.HashMap<>();

	private void applyVisibility() {
		boolean visible = hasVisibleItems() && (!onlyWhenControlPanelVisible || controlPanelVisible);
		animate().cancel();

		// Coming and going with the control panel, it fades in and out with it.
		if (!onlyWhenControlPanelVisible || !isAttachedToWindow()) {
			setAlpha(1f);
			setTranslationY(0f);
			setVisibility(visible ? VISIBLE : GONE);
			if (!visible) notifyHidden();
		} else if (visible) {
			// Same motion as the title bar: fades in while sliding down into place.
			if (getVisibility() != VISIBLE) {
				setAlpha(0f);
				setTranslationY(-toIntPx(getContext(), 8));
				setVisibility(VISIBLE);
			}
			animate().alpha(1f).translationY(0f).setDuration(FADE_MS)
					.setInterpolator(new DecelerateInterpolator()).start();
		} else if (getVisibility() == VISIBLE) {
			// ... and fades out while sliding up, like the title bar going.
			animate().alpha(0f).translationY(-toIntPx(getContext(), 8)).setDuration(FADE_MS)
					.setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
						setVisibility(GONE);
						setAlpha(1f);
						setTranslationY(0f);
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
		updateSessionListenerState();
		updatePowerListenerState();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		// Still counts as attached in here: let go of it directly.
		unregisterPlayableListener();
		unregisterBatteryReceiver();
		unregisterDistanceListener();
		unregisterDataListener();
		if (powerListenerRegistered) {
			powerListenerRegistered = false;
			me.aap.fermata.addon.power.PowerUsageTracker.get().removeListener(powerListener);
		}
		if (sessionListenerRegistered) {
			sessionListenerRegistered = false;
			me.aap.fermata.addon.data.DataSessionLimit.get().removeListener(sessionListener);
		}
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
