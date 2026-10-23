package me.aap.fermata.ui.home.widget;

import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import androidx.core.content.ContextCompat;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.data.DataSessionLimit;
import me.aap.fermata.addon.data.DataUsageAddon;
import me.aap.fermata.addon.data.DataUsageStore;
import me.aap.fermata.addon.data.DataUsageTracker;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.DataUsageRingView;

/**
 * How much data the app used this cycle: a ring split into video, music, other and downloads with
 * the total in its middle, and, as the card gets wider, the figures per kind beside it. A strip
 * shows just the total and how much of the limit it is.
 */
final class DataUsageWidget extends HomeWidgetView {
	private static final int[] NAMES = {R.string.data_usage_cat_video, R.string.data_usage_cat_music,
			R.string.data_usage_cat_other, R.string.data_usage_cat_downloads};

	private LinearLayout column;
	private View headerRow;
	private boolean headerOn = true;
	private LinearLayout body;
	private FrameLayout ringBox;
	private DataUsageRingView ring;
	private TextView used;
	private TextView caption;
	private LinearLayout centre;
	private LinearLayout legend;
	private final TextView[] legendValues = new TextView[DataUsageStore.CATS];
	/** The "Breakdown" view: a line per kind with its share as a bar, in place of the ring. */
	private LinearLayout bdBox;
	private TextView bdTotal;
	private final MeterBar[] bdBars = new MeterBar[DataUsageStore.CATS];
	private final TextView[] bdValues = new TextView[DataUsageStore.CATS];
	/** The strip (one cell high): beside the total, the car session's figures, else the four kinds in a small grid. */
	private LinearLayout stripBox;
	private TextView stripText;
	private MeterBar stripBar;
	private LinearLayout stripLegend;
	private final TextView[] stripValues = new TextView[DataUsageStore.CATS];
	private TextView off;
	private LinearLayout session;
	private TextView sessionText;
	private MeterBar sessionBar;
	private int[] catColors;
	/** A small ring has no room for "used of 18 GB" in its middle: it says "of 18 GB". */
	private boolean shortCaption;
	private final Runnable listener = this::refresh;

	DataUsageWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	static boolean enabled() {
		AddonManager m = AddonManager.get();
		return (m != null) && (m.getAddon(DataUsageAddon.class) != null);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		catColors = new int[]{ContextCompat.getColor(ctx, R.color.data_usage_video),
				ContextCompat.getColor(ctx, R.color.data_usage_music),
				ContextCompat.getColor(ctx, R.color.data_usage_other),
				ContextCompat.getColor(ctx, R.color.data_usage_downloads)};
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		headerRow = header(() -> open(R.id.data_usage_addon));
		column.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

		body = new LinearLayout(ctx);
		body.setOrientation(LinearLayout.HORIZONTAL);
		body.setGravity(Gravity.CENTER_VERTICAL);

		ringBox = new FrameLayout(ctx);
		ring = new DataUsageRingView(ctx);
		ringBox.addView(ring, new FrameLayout.LayoutParams(-1, -1));
		centre = new LinearLayout(ctx);
		centre.setOrientation(LinearLayout.VERTICAL);
		centre.setGravity(Gravity.CENTER);
		used = text(ROLE_PRIMARY, 19, true);
		used.setGravity(Gravity.CENTER);
		caption = text(ROLE_SECONDARY, 11.5f, false);
		caption.setGravity(Gravity.CENTER);
		centre.addView(used);
		centre.addView(caption);
		ringBox.addView(centre, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
		body.addView(ringBox, new LinearLayout.LayoutParams(px(100), px(100)));

		legend = new LinearLayout(ctx);
		legend.setOrientation(LinearLayout.VERTICAL);
		legend.setGravity(Gravity.CENTER_VERTICAL);
		for (int i = 0; i < DataUsageStore.CATS; i++) {
			LinearLayout row = new LinearLayout(ctx);
			row.setGravity(Gravity.CENTER_VERTICAL);
			View dot = new View(ctx);
			dot.setBackground(HomeUi.oval(catColors[i]));
			row.addView(dot, new LinearLayout.LayoutParams(px(9), px(9)));
			TextView name = text(ROLE_SECONDARY, 12.5f, false);
			name.setText(NAMES[i]);
			name.setSingleLine(true);
			LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			nlp.setMarginStart(px(8));
			row.addView(name, nlp);
			legendValues[i] = text(ROLE_PRIMARY, 12.5f, true);
			row.addView(legendValues[i]);
			LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			rlp.topMargin = px(3);
			legend.addView(row, rlp);
		}
		LinearLayout.LayoutParams lgp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		lgp.setMarginStart(px(14));
		body.addView(legend, lgp);

		bdBox = new LinearLayout(ctx);
		bdBox.setOrientation(LinearLayout.VERTICAL);
		bdBox.setGravity(Gravity.CENTER_VERTICAL);
		bdTotal = text(ROLE_PRIMARY, 16, true);
		bdTotal.setSingleLine(true);
		bdTotal.setEllipsize(android.text.TextUtils.TruncateAt.END);
		bdBox.addView(bdTotal);
		for (int i = 0; i < DataUsageStore.CATS; i++) {
			LinearLayout line = new LinearLayout(ctx);
			line.setGravity(Gravity.CENTER_VERTICAL);
			View dot = new View(ctx);
			dot.setBackground(HomeUi.oval(catColors[i]));
			line.addView(dot, new LinearLayout.LayoutParams(px(9), px(9)));
			TextView name = text(ROLE_SECONDARY, 12.5f, false);
			name.setText(NAMES[i]);
			name.setSingleLine(true);
			LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			nlp.setMarginStart(px(8));
			line.addView(name, nlp);
			bdValues[i] = text(ROLE_PRIMARY, 12.5f, true);
			line.addView(bdValues[i]);
			LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
			llp.topMargin = px(i == 0 ? 6 : 5);
			bdBox.addView(line, llp);
			bdBars[i] = new MeterBar(ctx);
			LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, px(5));
			blp.topMargin = px(3);
			bdBox.addView(bdBars[i], blp);
		}
		bdBox.setVisibility(GONE);
		body.addView(bdBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		stripBox = new LinearLayout(ctx);
		stripBox.setOrientation(LinearLayout.VERTICAL);
		stripBox.setGravity(Gravity.CENTER_VERTICAL);
		stripText = text(ROLE_SECONDARY, 11.5f, false);
		stripText.setMaxLines(2);
		stripText.setEllipsize(android.text.TextUtils.TruncateAt.END);
		stripBox.addView(stripText);
		stripBar = new MeterBar(ctx);
		LinearLayout.LayoutParams stb = new LinearLayout.LayoutParams(-1, px(6));
		stb.topMargin = px(4);
		stripBox.addView(stripBar, stb);
		stripLegend = new LinearLayout(ctx);
		stripLegend.setOrientation(LinearLayout.VERTICAL);
		for (int r = 0; r < 2; r++) {
			LinearLayout row = new LinearLayout(ctx);
			row.setGravity(Gravity.CENTER_VERTICAL);
			for (int c = 0; c < 2; c++) {
				int i = r * 2 + c;
				LinearLayout cell = new LinearLayout(ctx);
				cell.setGravity(Gravity.CENTER_VERTICAL);
				View dot = new View(ctx);
				dot.setBackground(HomeUi.oval(catColors[i]));
				cell.addView(dot, new LinearLayout.LayoutParams(px(8), px(8)));
				stripValues[i] = text(ROLE_SECONDARY, 11.5f, false);
				stripValues[i].setSingleLine(true);
				LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, -2, 1f);
				vlp.setMarginStart(px(6));
				cell.addView(stripValues[i], vlp);
				row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
			}
			LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
			rlp.topMargin = px(r == 0 ? 0 : 3);
			stripLegend.addView(row, rlp);
		}
		stripBox.addView(stripLegend);
		stripBox.setVisibility(GONE);
		LinearLayout.LayoutParams sxp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		sxp.setMarginStart(px(16));
		body.addView(stripBox, sxp);
		column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		session = new LinearLayout(ctx);
		session.setOrientation(LinearLayout.VERTICAL);
		sessionText = text(ROLE_SECONDARY, 11.5f, false);
		sessionText.setSingleLine(true);
		session.addView(sessionText);
		sessionBar = new MeterBar(ctx);
		LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, px(6));
		sbp.topMargin = px(4);
		session.addView(sessionBar, sbp);
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
		slp.topMargin = px(6);
		column.addView(session, slp);

		off = note(R.string.home_data_off);
		off.setVisibility(GONE);
		column.addView(off, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.choice("view", ctx.getString(R.string.home_opt_power_view), 0,
				ctx.getString(R.string.home_opt_auto), ctx.getString(R.string.home_opt_data_pie),
				ctx.getString(R.string.home_opt_power_breakdown)));
		l.add(HomeOption.sw("breakdown", ctx.getString(R.string.home_opt_data_breakdown), true));
		l.add(HomeOption.sw("session", ctx.getString(R.string.home_opt_data_session), true));
		return l;
	}

	@Override
	protected void onStyleChanged() {
		if (ring == null) return;
		sessionBar.setColors(softFill(), color(ROLE_PRIMARY));
		stripBar.setColors(softFill(), color(ROLE_PRIMARY));
		for (int i = 0; i < bdBars.length; i++) bdBars[i].setColors(softFill(), catColors[i]);
		ring.setColors(catColors, softFill(), ContextCompat.getColor(ctx, R.color.data_usage_warning),
				ContextCompat.getColor(ctx, R.color.data_usage_limit));
		refresh();
	}

	@Override
	protected void onBind() {
		onStyleChanged();
		layoutFor();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column != null) layoutFor();
	}

	@Override
	protected void onSized(int w, int h) {
		if (column != null) layoutFor();
	}

	private void layoutFor() {
		boolean strip = getCellsH() == 1;
		int view = optInt("view", 0);
		boolean list = !strip && (view == 2);
		boolean wide = !strip && !list && (view == 0) && getCellsW() >= 3 && optBool("breakdown", true);
		int tallPx = (heightPx() > 0) ? heightPx() : px(getCellsH() * 90f);
		// The title row only while the card has the room: a strip, or a card too short in pixels for the ring
		// or the four lines under it, gives the whole card to the figures (a tap on them opens the tab).
		boolean header = !strip && (tallPx >= px(28 + 34 + 4 + (list ? 100 : 86)));
		headerOn = header;
		headerRow.setVisibility(header ? VISIBLE : GONE);
		body.setClickable(!header);
		body.setOnClickListener(header ? null : v -> open(R.id.data_usage_addon));
		// The session line and its bar need a card tall enough to leave the ring its room.
		boolean sess = !strip && (tallPx >= px(220)) && optBool("session", true) && DataSessionLimit.isEnabled();
		stripBox.setVisibility(strip ? VISIBLE : GONE);
		ringBox.setVisibility((strip || list) ? GONE : VISIBLE);
		bdBox.setVisibility(list ? VISIBLE : GONE);
		legend.setVisibility(wide ? VISIBLE : GONE);
		session.setVisibility(sess ? VISIBLE : GONE);
		if (list) {
			// The bars under each line only when the card is tall enough for all four lines with them.
			int room = tallPx - px(28) - (header ? px(34) : 0) - px(4) - (sess ? px(36) : 0);
			for (MeterBar b : bdBars) b.setVisibility((room >= px(150)) ? VISIBLE : GONE);
		}
		if (!strip && !list) {
			int availH = tallPx - px(28) - (header ? px(34) : 0) - px(4) - (sess ? px(36) : 0);
			int availW = widthPx() - px(28) - (wide ? px(150) : 0);
			int side = Math.max(px(64), Math.min(availH, availW));
			// The words in the ring's middle follow the ring, so they never spill over its edge.
			float sideDp = side / px(1f);
			used.setTextSize(Math.max(12f, Math.min(22f, sideDp * 0.16f)));
			caption.setTextSize(Math.max(8.5f, Math.min(11.5f, sideDp * 0.09f)));
			shortCaption = sideDp < 105f;
			LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) ringBox.getLayoutParams();
			if ((lp.width != side) || (lp.height != side)) {
				lp.width = side;
				lp.height = side;
				ringBox.setLayoutParams(lp);
			}
		}
		if (strip) {
			used.setTextSize(22);
			caption.setTextSize(11.5f);
		}
		body.setGravity(wide ? Gravity.CENTER_VERTICAL : strip ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER);
		refresh();
	}

	@Override
	protected void onActive() {
		if (!enabled()) return;
		DataUsageTracker.get().addListener(listener);
		DataSessionLimit.get().addListener(listener);
		refresh();
	}

	@Override
	protected void onInactive() {
		if (!enabled()) return;
		DataUsageTracker.get().removeListener(listener);
		DataSessionLimit.get().removeListener(listener);
	}

	private void refresh() {
		if (column == null) return;
		boolean on = enabled();
		off.setVisibility(on ? GONE : VISIBLE);
		body.setVisibility(on ? VISIBLE : GONE);
		if (!on) {
			session.setVisibility(GONE);
			return;
		}
		DataUsageTracker t = DataUsageTracker.get();
		long[] usage = t.getCycleUsage();
		long total = 0;
		for (long u : usage) total += u;
		long lim = DataUsageTracker.getLimit();
		long warn = DataUsageTracker.getWarning();
		ring.setData(usage, lim, warn);
		used.setText(Formatter.formatShortFileSize(ctx, total));
		boolean strip = getCellsH() == 1;
		if (lim > 0) {
			int pct = (int) Math.min(999, Math.round(100.0 * total / lim));
			caption.setText(strip ? ctx.getString(R.string.home_data_of_pct, Formatter.formatShortFileSize(ctx, lim), pct) :
					ctx.getString(shortCaption ? R.string.home_data_of : R.string.data_usage_of,
							Formatter.formatShortFileSize(ctx, lim)));
		} else {
			caption.setText(R.string.data_usage_used);
		}
		for (int i = 0; i < legendValues.length; i++) {
			legendValues[i].setText(Formatter.formatShortFileSize(ctx, (i < usage.length) ? usage[i] : 0));
		}
		String of = (lim > 0) ? ctx.getString(R.string.home_data_of, Formatter.formatShortFileSize(ctx, lim)) : "";
		bdTotal.setText(Formatter.formatShortFileSize(ctx, total) + (of.isEmpty() ? "" : "  " + of));
		float share = Math.max(1, total);
		for (int i = 0; i < bdValues.length; i++) {
			long u = (i < usage.length) ? usage[i] : 0;
			bdValues[i].setText(Formatter.formatShortFileSize(ctx, u));
			bdBars[i].setFraction(u / share);
		}
		placeCentre(strip);
		refreshSession();
		if (strip) refreshStrip(usage);
	}

	/** Beside the total in a strip: the Android Auto session (used of the limit, what is left), else the four kinds. */
	private void refreshStrip(long[] usage) {
		boolean car = optBool("session", true) && DataSessionLimit.isEnabled();
		stripText.setVisibility(car ? VISIBLE : GONE);
		stripBar.setVisibility(car ? VISIBLE : GONE);
		stripLegend.setVisibility(car ? GONE : VISIBLE);
		if (car) {
			DataSessionLimit l = DataSessionLimit.get();
			if (l.isActive()) {
				stripText.setText(ctx.getString(R.string.home_data_car_session,
						Formatter.formatShortFileSize(ctx, l.getUsed()),
						Formatter.formatShortFileSize(ctx, DataSessionLimit.getLimit()),
						Formatter.formatShortFileSize(ctx, l.getRemaining())));
				stripBar.setFraction(l.getFraction());
			} else {
				stripText.setText(R.string.data_session_waiting);
				stripBar.setFraction(0f);
			}
			return;
		}
		for (int i = 0; i < stripValues.length; i++) {
			stripValues[i].setText(ctx.getString(NAMES[i]) + " " +
					Formatter.formatShortFileSize(ctx, (i < usage.length) ? usage[i] : 0));
		}
	}

	/** The Android Auto session limit: used so far this session, and what is left. */
	private void refreshSession() {
		int tallPx = (heightPx() > 0) ? heightPx() : px(getCellsH() * 90f);
		boolean show = (getCellsH() != 1) && (tallPx >= px(220)) && optBool("session", true) && DataSessionLimit.isEnabled();
		if (show != (session.getVisibility() == VISIBLE)) {
			layoutFor();
			return;
		}
		if (!show) return;
		DataSessionLimit l = DataSessionLimit.get();
		if (l.isActive()) {
			sessionText.setText(ctx.getString(R.string.data_session_status,
					Formatter.formatShortFileSize(ctx, l.getUsed()), Formatter.formatShortFileSize(ctx, DataSessionLimit.getLimit()),
					Formatter.formatShortFileSize(ctx, l.getRemaining())));
			sessionBar.setFraction(l.getFraction());
		} else {
			sessionText.setText(R.string.data_session_waiting);
			sessionBar.setFraction(0f);
		}
	}

	/** In the ring's middle, or alone in a strip, which has no ring. */
	private void placeCentre(boolean strip) {
		ViewGroup want = strip ? body : ringBox;
		if (centre.getParent() == want) return;
		if (centre.getParent() instanceof ViewGroup p) p.removeView(centre);
		if (strip) {
			body.addView(centre, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			centre.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
			used.setGravity(Gravity.START);
			caption.setGravity(Gravity.START);
		} else {
			ringBox.addView(centre, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
			centre.setGravity(Gravity.CENTER);
			used.setGravity(Gravity.CENTER);
			caption.setGravity(Gravity.CENTER);
		}
	}
}
