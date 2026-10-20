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
import me.aap.fermata.ui.home.HomeInfo;
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
	private View headerRow;

	InfoOverlayWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		headerRow = header(() -> open(R.id.info_overlay_fragment));
		column.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
		holder = new FrameLayout(ctx);
		overlay = new InfoOverlayView(ctx);
		holder.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START));
		chipLook();
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
	protected void onStyleChanged() {
		chipLook();
		apply();
	}

	/** Chips in the card's own text colour: a faint fill of it, so it reads on any look. */
	private void chipLook() {
		if (overlay == null) return;
		int fg = color(ROLE_PRIMARY);
		overlay.setChipStyle(fg, (fg & 0x00FFFFFF) | 0x24000000);
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
		boolean any = HomeInfo.apply(host.activity(), overlay);
		overlay.setVisibility(any ? VISIBLE : GONE);
		View empty = holder.findViewWithTag("empty");
		if (empty != null) empty.setVisibility(any ? GONE : VISIBLE);
		fit();
	}

	/**
	 * Chips wrap onto lines; scaled down only when even that is taller than the room. A card too short
	 * for its title row (a one-cell strip, the car screen's small cards) drops the title, which gives the
	 * chips the whole card; a tap on them opens the settings instead.
	 */
	private void fit() {
		if ((overlay == null) || (heightPx() == 0) || (widthPx() == 0)) return;
		boolean strip = heightPx() < px(130);
		headerRow.setVisibility(strip ? GONE : VISIBLE);
		overlay.setOnClickListener(strip ? v -> open(R.id.info_overlay_fragment) : null);
		overlay.setClickable(strip);
		if (overlay.getVisibility() != VISIBLE) return;
		int availW = widthPx() - px(28);
		int availH = heightPx() - px(28) - (strip ? 0 : px(34) + px(4));
		if ((availW <= 0) || (availH <= 0)) return;
		overlay.measure(View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.AT_MOST),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int need = overlay.getMeasuredHeight();
		float k = ((need > availH) && (need > 0)) ? (availH / (float) need) : 1f;
		overlay.setPivotX(0f);
		overlay.setPivotY(overlay.getMeasuredHeight() / 2f);
		overlay.setScaleX(k);
		overlay.setScaleY(k);
	}
}
