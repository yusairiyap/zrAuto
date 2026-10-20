package me.aap.fermata.ui.home.widget;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;

/**
 * A card showing a grid of round icons with a name under each (the app's pages, the All in One
 * actions). Their size and whether the names show follow the card's size; the subclasses only list
 * what to show.
 */
abstract class TileWidget extends HomeWidgetView {
	protected LinearLayout column;
	protected TileFlow flow;
	private View headerRow;

	protected TileWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	/** The tab the header button opens, or 0 for none. */
	protected abstract int tabId();

	/** Fills {@link #flow} with {@link #tile} views. */
	protected abstract void fill();

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		int tab = tabId();
		headerRow = header((tab != 0) ? () -> open(tab) : null);
		column.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
		flow = new TileFlow(ctx, px(8));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		lp.topMargin = px(4);
		column.addView(flow, lp);
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected void onBind() {
		applySize();
		rebuild();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column == null) return;
		applySize();
		rebuild();
	}

	@Override
	protected void onActive() {
		rebuild();
	}

	/** Whether the names show: not in a strip, where the icons have no room for them. */
	protected boolean showLabels() {
		return getCellsH() >= 2;
	}

	private int diameterDp() {
		if (getCellsH() == 1) return 44;
		return (isLarge() || getCellsW() >= 4) ? 54 : 48;
	}

	private void applySize() {
		headerRow.setVisibility(getCellsH() >= 2 ? VISIBLE : GONE);
		int d = px(diameterDp());
		flow.setTile(showLabels() ? px(Math.max(66, diameterDp() + 14)) : d, showLabels() ? d + px(22) : d);
	}

	protected final void rebuild() {
		if (flow == null) return;
		flow.removeAllViews();
		fill();
	}

	/** One round icon (and its name); tapping it runs {@code click}. */
	protected final View tile(@DrawableRes int icon, CharSequence name, int tint, Runnable click) {
		LinearLayout t = new LinearLayout(ctx);
		t.setOrientation(LinearLayout.VERTICAL);
		t.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
		t.setBackground(HomeUi.pressable(ripple(), 0, px(18)));
		t.setClickable(true);
		t.setFocusable(true);
		t.setContentDescription(name);
		t.setOnClickListener(v -> click.run());
		int d = px(diameterDp());
		FrameLayout disc = new FrameLayout(ctx);
		disc.setBackground(HomeUi.oval(HomeUi.alpha(tint, lightText() ? 0.28f : 0.2f)));
		ImageView ic = new ImageView(ctx);
		ic.setImageResource(icon);
		ic.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
		disc.addView(ic, new FrameLayout.LayoutParams(d * 5 / 11, d * 5 / 11, Gravity.CENTER));
		t.addView(disc, new LinearLayout.LayoutParams(d, d));
		if (showLabels()) {
			TextView l = text(ROLE_SECONDARY, 11, false);
			l.setText(name);
			l.setSingleLine(true);
			l.setGravity(Gravity.CENTER);
			l.setEllipsize(android.text.TextUtils.TruncateAt.END);
			LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			llp.topMargin = px(4);
			t.addView(l, llp);
		}
		return t;
	}

	/** A bright colour for the {@code i}th tile, spread around the colour wheel. */
	protected final int hue(int i) {
		return android.graphics.Color.HSVToColor(new float[]{(i * 47f + 200f) % 360f, 0.5f, 1f});
	}

	@Nullable
	protected final HomeHost getHost() {
		return host;
	}
}
