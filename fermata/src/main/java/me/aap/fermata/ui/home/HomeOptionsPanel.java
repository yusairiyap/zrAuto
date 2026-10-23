package me.aap.fermata.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import me.aap.fermata.R;
import me.aap.fermata.ui.view.ChipFlowLayout;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.fermata.ui.view.SwitchStyle;

/**
 * A widget's settings: the look and title every widget has, then the widget's own. Shown inside the
 * widget's card when it is big enough (3 x 2 units and up), else in a card over the page; the rows
 * are the same either way. Changes apply at once.
 */
final class HomeOptionsPanel {
	private HomeOptionsPanel() {
	}

	/** The panel to lay over the cell itself: a title, Done, and the rows scrolling below. */
	static View build(HomeHost host, HomeGridLayout grid, HomeCellView cell, HomeWidgetView w,
										Runnable done) {
		HomePalette p = host.palette();
		Context ctx = p.ctx;
		FrameLayout root = new FrameLayout(ctx);
		root.setBackground(HomeUi.round((p.panelFill & 0xFFFFFF) | 0xFF000000,
				HomeWidgetView.CORNER_DP * ctx.getResources().getDisplayMetrics().density));
		root.setClickable(true);

		LinearLayout col = new LinearLayout(ctx);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setPadding(HomeUi.dp(ctx, 14), HomeUi.dp(ctx, 10), HomeUi.dp(ctx, 14), HomeUi.dp(ctx, 10));

		LinearLayout head = new LinearLayout(ctx);
		head.setGravity(Gravity.CENTER_VERTICAL);
		TextView title = new TextView(ctx);
		title.setText(w.getType().name);
		title.setTextColor(p.textPrimary);
		title.setTextSize(16);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		title.setSingleLine(true);
		head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		TextView ok = ChipFlowLayout.chip(ctx, R.drawable.done, R.string.home_done, true, done);
		head.addView(ok);
		col.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		LinearLayout rows = rows(host, grid, cell, w, done);
		ScrollView sv = new ScrollView(ctx);
		sv.setVerticalScrollBarEnabled(false);
		// Inside the page's own scroller: this one keeps its drags.
		sv.setOnTouchListener((v, e) -> {
			if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
			return false;
		});
		sv.addView(rows);
		col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		return root;
	}

	static void showModal(HomeHost host, HomeGridLayout grid, HomeCellView cell, HomeWidgetView w) {
		LinearLayout rows = rows(host, grid, cell, w, HomeModal::dismissNow);
		HomeModal.show(host, host.palette().ctx.getString(w.getType().name), w.getType().icon, rows, null);
	}

	private static LinearLayout rows(HomeHost host, HomeGridLayout grid, HomeCellView cell,
																	 HomeWidgetView w, Runnable close) {
		HomePalette p = host.palette();
		Context ctx = p.ctx;
		LinearLayout rows = new LinearLayout(ctx);
		rows.setOrientation(LinearLayout.VERTICAL);
		LayoutInflater inf = LayoutInflater.from(ctx);

		for (HomeOption o : w.allOptions()) {
			switch (o.kind) {
				case SWITCH -> rows.addView(switchRow(p, w, o));
				case CHOICE -> rows.addView(choiceRow(p, w, o));
				case SLIDER -> addSlider(inf, rows, w, o);
				case BUTTON -> rows.addView(buttonRow(p, o.label, R.drawable.settings, o.action));
			}
		}
		if (cell.stack.count() > 1) {
			rows.addView(buttonRow(p, ctx.getString(R.string.home_unstack), R.drawable.view_grid, () -> {
				close.run();
				grid.unstack(cell, w);
			}));
			// Only where the stack turns by itself: this widget then stays up when the others would turn.
			if (host.stackRotateSeconds() > 0) {
				rows.addView(switchRow(p, w, HomeOption.sw("pin", ctx.getString(R.string.home_stack_pin), false)));
			}
		}
		rows.addView(buttonRow(p, ctx.getString(R.string.home_remove), R.drawable.delete, () -> {
			close.run();
			grid.removeTop(cell);
		}));
		return rows;
	}

	private static View switchRow(HomePalette p, HomeWidgetView w, HomeOption o) {
		Context ctx = p.ctx;
		LinearLayout row = new LinearLayout(ctx);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(HomeUi.dp(ctx, 48));
		TextView t = new TextView(ctx);
		t.setText(o.label);
		t.setTextColor(p.textPrimary);
		t.setTextSize(14);
		row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		SwitchCompat sw = new SwitchCompat(ctx);
		SwitchStyle.apply(sw, ctx);
		sw.setChecked(w.optBool(o.key, o.defBool));
		sw.setOnCheckedChangeListener((b, on) -> w.setOpt(o.key, on));
		row.addView(sw);
		return row;
	}

	private static View choiceRow(HomePalette p, HomeWidgetView w, HomeOption o) {
		Context ctx = p.ctx;
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(0, HomeUi.dp(ctx, 6), 0, HomeUi.dp(ctx, 6));
		TextView t = new TextView(ctx);
		t.setText(o.label);
		t.setTextColor(p.textPrimary);
		t.setTextSize(14);
		box.addView(t);
		ChipFlowLayout flow = new ChipFlowLayout(ctx);
		flow.setPadding(0, HomeUi.dp(ctx, 8), 0, 0);
		box.addView(flow);
		fillChoices(p, w, o, flow);
		return box;
	}

	private static void fillChoices(HomePalette p, HomeWidgetView w, HomeOption o, ChipFlowLayout flow) {
		flow.removeAllViews();
		int cur = w.optInt(o.key, o.def);
		for (int i = 0; i < o.choices.length; i++) {
			final int idx = i;
			TextView chip = ChipFlowLayout.chip(p.ctx, 0, o.choices[i], i == cur, () -> {
				w.setOpt(o.key, idx);
				fillChoices(p, w, o, flow);
			});
			chip.setPadding(HomeUi.dp(p.ctx, 14), 0, HomeUi.dp(p.ctx, 14), 0);
			chip.setMinHeight(HomeUi.dp(p.ctx, 36));
			flow.addView(chip);
		}
	}

	private static void addSlider(LayoutInflater inf, LinearLayout rows, HomeWidgetView w, HomeOption o) {
		int range = Math.max(1, o.max - o.min);
		int step = Math.max(1, range / 20);
		EffectsUi.Row row = EffectsUi.addRow(inf, rows, R.string.home_opt_title, range, step,
				v -> (o.format != null) ? o.format.apply(v + o.min) : String.valueOf(v + o.min),
				v -> w.setOpt(o.key, v + o.min));
		row.label.setText(o.label);
		row.set(Math.max(0, Math.min(range, w.optInt(o.key, o.def) - o.min)));
	}

	private static View buttonRow(HomePalette p, CharSequence label, int icon, Runnable action) {
		Context ctx = p.ctx;
		TextView t = new TextView(ctx);
		t.setText(label);
		t.setTextColor(p.textPrimary);
		t.setTextSize(14);
		t.setGravity(Gravity.CENTER_VERTICAL);
		t.setCompoundDrawablePadding(HomeUi.dp(ctx, 12));
		t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
		t.setCompoundDrawableTintList(ColorStateList.valueOf(p.icon));
		t.setMinHeight(HomeUi.dp(ctx, 48));
		t.setBackground(HomeUi.pressable(p.chipRipple, 0, HomeUi.dp(ctx, 12)));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> action.run());
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		t.setLayoutParams(lp);
		return t;
	}
}
