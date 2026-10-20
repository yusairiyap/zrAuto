package me.aap.fermata.ui.home.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.fermata.ui.home.HomeModal;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.view.ChipFlowLayout;
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
		if (applySize()) rebuild();
	}

	/** The card's pixel size is known: icon size and names follow what the height really has room for. */
	@Override
	protected void onSized(int w, int h) {
		if (column == null) return;
		if (applySize()) rebuild();
	}

	@Override
	protected void onActive() {
		rebuild();
	}

	/** The names under the icons: 0 when they fit, 1 always, 2 never. */
	protected int labelMode() {
		return 0;
	}

	private boolean labelsOn;
	private int diamPx;
	private boolean headerOn = true;

	/**
	 * Sets the icon size, whether the names show and whether the header does, from the card's height
	 * in pixels (a card on a wide screen can be two cells high and still have no room for a name).
	 *
	 * @return true if something about the look changed, so the tiles are to be made again
	 */
	private boolean applySize() {
		int pref = (getCellsH() == 1) ? 44 : ((isLarge() || getCellsW() >= 4) ? 54 : 48);
		int mode = labelMode();
		int h = heightPx();
		boolean header = getCellsH() >= 2;
		boolean labels;
		int d;
		if (h <= 0) {
			// Not measured yet: the cell count decides, and the measure pass corrects it.
			labels = (mode == 1) || ((mode == 0) && (getCellsH() >= 2));
			d = px(pref);
		} else {
			int inner = h - px(28);
			header = header && (inner >= px(34 + 4 + 40));
			int room = inner - (header ? px(38) : 0);
			int fit = Math.min(px(pref), room - px(22));
			if ((mode == 2) || ((mode == 0) && (getCellsH() < 2))) {
				labels = false;
				d = Math.max(px(28), Math.min(px(pref), room));
			} else if (fit >= px((mode == 1) ? 28 : 38)) {
				labels = true;
				d = fit;
			} else {
				labels = (mode == 1);
				d = labels ? px(28) : Math.max(px(28), Math.min(px(pref), room));
			}
		}
		boolean changed = (labels != labelsOn) || (d != diamPx) || (header != headerOn);
		labelsOn = labels;
		diamPx = d;
		headerOn = header;
		headerRow.setVisibility(header ? VISIBLE : GONE);
		flow.setTile(labels ? Math.max(px(66), d + px(14)) : d, labels ? d + px(22) : d);
		return changed;
	}

	protected final void rebuild() {
		if (flow == null) return;
		flow.removeAllViews();
		fill();
	}

	/** One thing the card can show, as the picker lists it. */
	protected record Pick(String key, @DrawableRes int icon, CharSequence name) {
	}

	/** Everything the card could show, in its order; the picker lists these. */
	protected List<Pick> candidates() {
		return new ArrayList<>();
	}

	/** Whether {@code key} shows while nothing was picked (the card's own default). */
	protected boolean defaultPicked(String key) {
		return true;
	}

	/** The words above the picker's chips. */
	protected int pickHintRes() {
		return R.string.home_pick_items_hint;
	}

	/** The option that opens the picker. */
	protected final HomeOption pickOption() {
		return HomeOption.button(ctx.getString(R.string.home_opt_pick_items), this::pickItems);
	}

	/** The keys picked in the picker, or null while nothing was picked (the default set shows). */
	@Nullable
	private Set<String> pickedKeys() {
		String s = optString("items", "");
		if (s.isEmpty()) return null;
		Set<String> keys = new LinkedHashSet<>();
		if (!"-".equals(s)) for (String k : s.split(",")) keys.add(k);
		return keys;
	}

	/** Whether the tile for {@code key} shows on the card. */
	protected final boolean isPicked(String key) {
		Set<String> keys = pickedKeys();
		return (keys == null) ? defaultPicked(key) : keys.contains(key);
	}

	/** A card of chips, like the one that picks the ambience sounds: a tap shows or hides that item. */
	private void pickItems() {
		List<Pick> all = candidates();
		Set<String> chosen = new LinkedHashSet<>();
		for (Pick p : all) if (isPicked(p.key())) chosen.add(p.key());
		Context pc = host.palette().ctx;
		LinearLayout box = new LinearLayout(pc);
		box.setOrientation(LinearLayout.VERTICAL);
		TextView hint = new TextView(pc);
		hint.setText(pickHintRes());
		hint.setTextColor(host.palette().textSecondary);
		hint.setTextSize(13);
		box.addView(hint);
		ChipFlowLayout chips = new ChipFlowLayout(pc);
		chips.setPadding(0, px(12), 0, px(4));
		box.addView(chips);
		fillPicker(chips, all, chosen);
		HomeModal.show(host, ctx.getString(R.string.home_opt_pick_items), R.drawable.home_add, box, null);
	}

	private void fillPicker(ChipFlowLayout chips, List<Pick> all, Set<String> chosen) {
		chips.removeAllViews();
		Context pc = host.palette().ctx;
		for (Pick p : all) {
			chips.addView(ChipFlowLayout.chip(pc, p.icon(), p.name(), chosen.contains(p.key()), () -> {
				if (!chosen.remove(p.key())) chosen.add(p.key());
				save(all, chosen);
				fillPicker(chips, all, chosen);
			}));
		}
	}

	/** Stores the choice in the card's order; the default set is stored as nothing, so new pages still join it. */
	private void save(List<Pick> all, Set<String> chosen) {
		List<String> ordered = new ArrayList<>();
		boolean isDefault = true;
		for (Pick p : all) {
			boolean on = chosen.contains(p.key());
			if (on) ordered.add(p.key());
			if (on != defaultPicked(p.key())) isDefault = false;
		}
		setOpt("items", isDefault ? "" : ordered.isEmpty() ? "-" : String.join(",", ordered));
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
		int d = diamPx;
		FrameLayout disc = new FrameLayout(ctx);
		disc.setBackground(HomeUi.oval(HomeUi.alpha(tint, lightText() ? 0.28f : 0.2f)));
		ImageView ic = new ImageView(ctx);
		ic.setImageResource(icon);
		ic.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
		disc.addView(ic, new FrameLayout.LayoutParams(d * 5 / 11, d * 5 / 11, Gravity.CENTER));
		t.addView(disc, new LinearLayout.LayoutParams(d, d));
		if (labelsOn) {
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
