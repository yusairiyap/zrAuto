package me.aap.fermata.ui.home.widget;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.InfoOverlayView;
import me.aap.utils.pref.PreferenceStore;

/**
 * The info overlay itself, the one fullscreen video and the Music tab show: the same items, order,
 * icons and size, live (clock, battery, temperature, power, data, distance...). What it shows is
 * set in Settings > Info overlay, which its header opens; with nothing picked there it says so.
 */
final class InfoOverlayWidget extends HomeWidgetView implements PreferenceStore.Listener {
	private LinearLayout column;
	private FrameLayout holder;
	private InfoOverlayView overlay;
	private TextView none;
	private View pick;

	InfoOverlayWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		column.addView(header(() -> open(R.id.info_overlay_fragment)),
				new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
		holder = new FrameLayout(ctx);
		overlay = new InfoOverlayView(ctx);
		holder.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
		LinearLayout empty = new LinearLayout(ctx);
		empty.setOrientation(LinearLayout.VERTICAL);
		empty.setGravity(Gravity.CENTER);
		none = note(R.string.home_overlay_none);
		empty.addView(none, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		pick = chip(R.drawable.settings, ctx.getString(R.string.home_overlay_pick), () -> open(R.id.info_overlay_fragment));
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34));
		plp.topMargin = px(8);
		empty.addView(pick, plp);
		empty.setTag("empty");
		holder.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
		LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		column.addView(holder, hlp);
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected void onBind() {
		apply();
	}

	@Override
	protected void onSized(int w, int h) {
		if (column != null) fit();
	}

	@Override
	protected void onActive() {
		host.activity().getPrefs().addBroadcastListener(this);
		apply();
	}

	@Override
	protected void onInactive() {
		host.activity().getPrefs().removeBroadcastListener(this);
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> changed) {
		if (!Collections.disjoint(MainActivityPrefs.infoOverlayPrefs(), changed)) post(this::apply);
	}

	/** The overlay's settings as the Music tab applies them (the position setting does not matter here). */
	private void apply() {
		if (overlay == null) return;
		MainActivityDelegate ad = host.activity();
		MainActivityPrefs p = ad.getPrefs();
		boolean clock = p.getInfoOverlayShowClockPref(ad);
		boolean battery = p.getInfoOverlayShowBatteryPctPref(ad);
		boolean temp = p.getInfoOverlayShowBatteryTempPref(ad);
		boolean distance = p.getInfoOverlayShowDistancePref(ad);
		boolean dataUsage = p.getInfoOverlayShowDataUsagePref(ad);
		boolean dataRemaining = p.getInfoOverlayShowDataRemainingPref(ad);
		boolean downloaded = p.getInfoOverlayShowDownloadedPref(ad);
		boolean dataSession = p.getInfoOverlayShowDataSessionPref(ad);
		boolean power = p.getInfoOverlayShowPowerPref(ad);
		boolean any = clock || battery || temp || distance || dataUsage || dataRemaining || downloaded ||
				dataSession || power;
		overlay.setVisibility(any ? VISIBLE : GONE);
		View empty = holder.findViewWithTag("empty");
		if (empty != null) empty.setVisibility(any ? GONE : VISIBLE);
		if (!any) return;
		overlay.setSize(p.getInfoOverlaySizePref(ad));
		overlay.setOrder(p.getInfoOverlayOrderPref(ad));
		overlay.setItems(clock, p.getInfoOverlayShowClockIconPref(ad), battery,
				p.getInfoOverlayShowBatteryIconPref(ad), temp, p.getInfoOverlayShowTempIconPref(ad), distance,
				p.getInfoOverlayShowDistanceIconPref(ad));
		overlay.setDataItems(dataUsage, dataRemaining, p.getInfoOverlayShowDataIconPref(ad));
		overlay.setSessionItem(dataSession, p.getInfoOverlayDataSessionBarPref(ad),
				p.getInfoOverlayDataSessionLeftPref(ad));
		overlay.setPowerItem(power, p.getInfoOverlayShowPowerIconPref(ad));
		overlay.setDownloadedItem(downloaded);
		fit();
	}

	/** Scales the overlay down when its single line is wider than the card. */
	private void fit() {
		if ((overlay == null) || (overlay.getVisibility() != VISIBLE) || (widthPx() == 0)) return;
		overlay.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int avail = widthPx() - px(28);
		int need = overlay.getMeasuredWidth();
		float k = (need > avail) ? (avail / (float) need) : 1f;
		overlay.setScaleX(k);
		overlay.setScaleY(k);
	}
}
