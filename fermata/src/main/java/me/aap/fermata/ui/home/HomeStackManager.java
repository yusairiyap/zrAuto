package me.aap.fermata.ui.home;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.ui.home.widget.HomeWidgets;
import me.aap.fermata.ui.view.ChipFlowLayout;
import me.aap.fermata.ui.view.TopToast;

/**
 * Manage stack: the widgets of one stack as a list. Drag the handle to change their order (the top one is the one
 * shown when the stack is not turning), the minus takes a widget off the screen (Undo brings it back), and "Add" puts
 * another widget on the stack. Every change is laid out and saved at once, as the card's own menu does.
 */
final class HomeStackManager {
	private static final int ROW_DP = 56;

	private final HomeHost host;
	private final HomeGridLayout grid;
	private final HomeModel model;
	private final HomeModel.Cell cell;
	private final Context ctx;
	private LinearLayout list;
	private LinearLayout picker;

	private HomeStackManager(HomeHost host, HomeGridLayout grid, HomeModel model, HomeModel.Cell cell) {
		this.host = host;
		this.grid = grid;
		this.model = model;
		this.cell = cell;
		this.ctx = host.palette().ctx;
	}

	static void show(HomeHost host, HomeGridLayout grid, HomeModel.Cell cell) {
		new HomeStackManager(host, grid, grid.model(), cell).open();
	}

	private void open() {
		LinearLayout body = new LinearLayout(ctx);
		body.setOrientation(LinearLayout.VERTICAL);
		TextView hint = new TextView(ctx);
		hint.setText(R.string.home_stack_hint);
		hint.setTextSize(13);
		hint.setTextColor(host.palette().textSecondary);
		body.addView(hint);

		list = new LinearLayout(ctx);
		list.setOrientation(LinearLayout.VERTICAL);
		body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		rebuildRows();

		TextView add = new TextView(ctx);
		add.setText(R.string.home_stack_add);
		add.setTextSize(14);
		add.setTextColor(host.palette().accent);
		add.setGravity(Gravity.CENTER_VERTICAL);
		add.setMinHeight(px(ROW_DP));
		add.setClickable(true);
		add.setOnClickListener(v -> togglePicker());
		body.addView(add);

		picker = new LinearLayout(ctx);
		picker.setOrientation(LinearLayout.VERTICAL);
		picker.setVisibility(View.GONE);
		body.addView(picker);

		ScrollView scroll = new ScrollView(ctx);
		scroll.addView(body);
		HomeModal.show(host, ctx.getString(R.string.home_manage_stack),
				R.drawable.view_grid, scroll, null);
	}

	private void rebuildRows() {
		list.removeAllViews();
		for (HomeModel.Item it : cell.items) list.addView(row(it), new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, px(ROW_DP)));
	}

	private int px(int dp) {
		return HomeUi.dp(ctx, dp);
	}

	private LinearLayout row(HomeModel.Item it) {
		HomeWidgetType t = HomeWidgets.find(it.type);
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);

		ImageView handle = new ImageView(ctx);
		handle.setImageResource(R.drawable.drag_handle);
		handle.setImageTintList(android.content.res.ColorStateList.valueOf(host.palette().textSecondary));
		handle.setPadding(px(12), px(12), px(12), px(12));
		handle.setContentDescription(ctx.getString(R.string.home_stack_drag));
		handle.setOnTouchListener(dragger(row));
		row.addView(handle, new LinearLayout.LayoutParams(px(48), px(48)));

		if (t != null) {
			ImageView ic = new ImageView(ctx);
			ic.setImageResource(t.icon);
			ic.setImageTintList(android.content.res.ColorStateList.valueOf(t.accent));
			row.addView(ic, new LinearLayout.LayoutParams(px(22), px(22)));
		}
		TextView name = new TextView(ctx);
		name.setText((t == null) ? it.type : ctx.getString(t.name));
		name.setTextSize(15);
		name.setTextColor(host.palette().textPrimary);
		name.setSingleLine(true);
		LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		nlp.setMarginStart(px(12));
		row.addView(name, nlp);

		if (cell.items.size() > 1) {
			ImageView remove = new ImageView(ctx);
			remove.setImageResource(R.drawable.home_row_remove);
			remove.setImageTintList(android.content.res.ColorStateList.valueOf(host.palette().textSecondary));
			remove.setPadding(px(12), px(12), px(12), px(12));
			remove.setContentDescription(ctx.getString(R.string.home_remove));
			remove.setClickable(true);
			remove.setOnClickListener(v -> removeOne(it, t));
			row.addView(remove, new LinearLayout.LayoutParams(px(48), px(48)));
		}
		return row;
	}

	/** Drag: the row follows the finger, one row per {@code ROW_DP} moved; the model is moved with it. */
	private View.OnTouchListener dragger(LinearLayout row) {
		float[] startY = new float[1];
		int[] startIdx = new int[1];
		return (v, e) -> {
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					startY[0] = e.getRawY();
					startIdx[0] = list.indexOfChild(row);
					v.getParent().requestDisallowInterceptTouchEvent(true);
					return true;
				case MotionEvent.ACTION_MOVE: {
					int max = list.getChildCount() - 1;
					int target = Math.max(0, Math.min(max, startIdx[0] + Math.round((e.getRawY() - startY[0]) / px(ROW_DP))));
					int cur = list.indexOfChild(row);
					if (target != cur) {
						list.removeViewAt(cur);
						list.addView(row, target);
						model.moveInStack(cell, cur, target);
					}
					return true;
				}
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					grid.stackEdited();
					return true;
				default:
					return false;
			}
		};
	}

	private void removeOne(HomeModel.Item it, HomeWidgetType t) {
		int cellIndex = model.cells().indexOf(cell);
		int itemIndex = cell.items.indexOf(it);
		model.remove(cell, it);
		rebuildRows();
		grid.stackEdited();
		TopToast.show((t == null) ? R.drawable.home_add : t.icon,
				ctx.getString(R.string.home_removed, (t == null) ? it.type : ctx.getString(t.name)), null, R.string.home_undo,
				() -> {
					model.restore(cell, it, cellIndex, itemIndex, false);
					rebuildRows();
					grid.stackEdited();
				});
	}

	private void togglePicker() {
		if (picker.getVisibility() == View.VISIBLE) {
			picker.setVisibility(View.GONE);
			return;
		}
		picker.removeAllViews();
		ChipFlowLayout flow = new ChipFlowLayout(ctx);
		flow.setPadding(0, px(8), 0, px(4));
		for (HomeWidgetType t : HomeWidgets.all()) {
			if (!t.isAvailable()) continue;
			flow.addView(ChipFlowLayout.chip(ctx, t.icon, ctx.getString(t.name), false, () -> {
				model.addToStack(cell, t);
				rebuildRows();
				grid.stackEdited();
				picker.setVisibility(View.GONE);
			}));
		}
		picker.addView(flow);
		picker.setVisibility(View.VISIBLE);
	}
}
