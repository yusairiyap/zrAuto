package me.aap.fermata.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import me.aap.fermata.R;
import me.aap.fermata.ui.home.widget.HomeWidgets;
import me.aap.fermata.ui.view.ChipFlowLayout;
import me.aap.fermata.ui.view.TopToast;

/**
 * Manage stack: the widgets of one stack as a list. Drag the handle to change their order (the top one is the one
 * shown when the stack is not turning), the minus takes a widget off the screen (Undo brings it back), and "Add" puts
 * another widget on the stack. Rows move as the library's rows do; every change is laid out and saved at once.
 */
final class HomeStackManager {
	private static final int ROW_DP = 56;
	private static final int LIST_DP = 420;
	private static final int TYPE_ITEM = 0;
	private static final int TYPE_ADD = 1;
	private static final int TYPE_PICKER = 2;

	private final HomeHost host;
	private final HomeGridLayout grid;
	private final HomeModel model;
	private final HomeModel.Cell cell;
	private final Context ctx;
	private final Adapter adapter = new Adapter();
	private RecyclerView list;
	private ItemTouchHelper touch;
	/** The add row is open: the widgets that can be added are listed under it. */
	private boolean adding;

	private HomeStackManager(HomeHost host, HomeGridLayout grid, HomeModel.Cell cell) {
		this.host = host;
		this.grid = grid;
		this.model = grid.model();
		this.cell = cell;
		this.ctx = host.palette().ctx;
	}

	static void show(HomeHost host, HomeGridLayout grid, HomeModel.Cell cell) {
		new HomeStackManager(host, grid, cell).open();
	}

	private int px(int dp) {
		return HomeUi.dp(ctx, dp);
	}

	private void open() {
		LinearLayout body = new LinearLayout(ctx);
		body.setOrientation(LinearLayout.VERTICAL);
		TextView hint = new TextView(ctx);
		hint.setText(R.string.home_stack_hint);
		hint.setTextSize(13);
		hint.setTextColor(host.palette().textSecondary);
		hint.setPadding(0, 0, 0, px(6));
		body.addView(hint);

		list = new RecyclerView(ctx);
		list.setLayoutManager(new LinearLayoutManager(ctx));
		list.setAdapter(adapter);
		DefaultItemAnimator ia = new DefaultItemAnimator();
		ia.setSupportsChangeAnimations(false);
		ia.setAddDuration(200);
		ia.setRemoveDuration(220);
		ia.setMoveDuration(240);
		list.setItemAnimator(ia);
		touch = new ItemTouchHelper(new Reorder());
		touch.attachToRecyclerView(list);
		int h = Math.min(px(LIST_DP), (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.6f));
		body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

		HomeModal.show(host, ctx.getString(R.string.home_manage_stack), R.drawable.view_grid, body, null);
	}

	/** Drag and drop of the widget rows; the model follows each step, the cards are made again when the drop is done. */
	private final class Reorder extends ItemTouchHelper.Callback {
		@Override
		public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
			return (vh.getItemViewType() != TYPE_ITEM) ? 0 : makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
		}

		@Override
		public boolean isLongPressDragEnabled() {
			// Dragged by the handle only.
			return false;
		}

		@Override
		public boolean isItemViewSwipeEnabled() {
			return false;
		}

		@Override
		public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
			if (to.getItemViewType() != TYPE_ITEM) return false;
			int f = from.getBindingAdapterPosition();
			int t = to.getBindingAdapterPosition();
			if ((f < 0) || (t < 0)) return false;
			model.moveInStack(cell, f, t);
			adapter.notifyItemMoved(f, t);
			return true;
		}

		@Override
		public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
		}

		@Override
		public void onSelectedChanged(RecyclerView.ViewHolder vh, int state) {
			super.onSelectedChanged(vh, state);
			if ((state == ItemTouchHelper.ACTION_STATE_DRAG) && (vh != null)) {
				list.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
				vh.itemView.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();
			}
		}

		@Override
		public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
			super.clearView(rv, vh);
			vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
			grid.stackEdited();
		}
	}

	private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		@Override
		public int getItemCount() {
			return cell.items.size() + 1 + (adding ? 1 : 0);
		}

		@Override
		public int getItemViewType(int position) {
			int n = cell.items.size();
			if (position < n) return TYPE_ITEM;
			return (position == n) ? TYPE_ADD : TYPE_PICKER;
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
			if (type == TYPE_ITEM) return new ItemHolder();
			if (type == TYPE_ADD) return new AddHolder();
			return new PickerHolder();
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
			if (h instanceof ItemHolder ih) ih.bind(cell.items.get(position), cell.items.size() > 1);
		}
	}

	private final class ItemHolder extends RecyclerView.ViewHolder {
		private final ImageView icon;
		private final TextView name;
		private final ImageView remove;

		ItemHolder() {
			super(buildRow());
			LinearLayout row = (LinearLayout) itemView;
			ImageView handle = (ImageView) row.getChildAt(0);
			icon = (ImageView) row.getChildAt(1);
			name = (TextView) row.getChildAt(2);
			remove = (ImageView) row.getChildAt(3);
			// Pressing the handle starts the drag, as the library's rows do.
			handle.setOnTouchListener((v, e) -> {
				if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touch.startDrag(this);
				return false;
			});
		}


		void bind(HomeModel.Item it, boolean removable) {
			HomeWidgetType t = HomeWidgets.find(it.type);
			if (t != null) {
				icon.setImageResource(t.icon);
				icon.setImageTintList(ColorStateList.valueOf(t.accent));
				name.setText(ctx.getString(t.name));
			} else {
				icon.setImageDrawable(null);
				name.setText(it.type);
			}
			remove.setVisibility(removable ? View.VISIBLE : View.GONE);
			remove.setOnClickListener(v -> {
				int pos = getBindingAdapterPosition();
				if (pos >= 0) removeOne(pos, it, t);
			});
		}
	}

	private LinearLayout buildRow() {
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(px(ROW_DP));
		ImageView handle = new ImageView(ctx);
		handle.setImageResource(R.drawable.drag_handle);
		handle.setImageTintList(ColorStateList.valueOf(host.palette().textSecondary));
		handle.setPadding(px(12), px(12), px(12), px(12));
		handle.setContentDescription(ctx.getString(R.string.home_stack_drag));
		row.addView(handle, new LinearLayout.LayoutParams(px(48), px(48)));
		ImageView icon = new ImageView(ctx);
		row.addView(icon, new LinearLayout.LayoutParams(px(22), px(22)));
		TextView name = new TextView(ctx);
		name.setTextSize(15);
		name.setTextColor(host.palette().textPrimary);
		name.setSingleLine(true);
		LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		nlp.setMarginStart(px(12));
		row.addView(name, nlp);
		ImageView remove = new ImageView(ctx);
		remove.setImageResource(R.drawable.home_row_remove);
		remove.setImageTintList(ColorStateList.valueOf(host.palette().textSecondary));
		remove.setPadding(px(12), px(12), px(12), px(12));
		remove.setContentDescription(ctx.getString(R.string.home_remove));
		remove.setClickable(true);
		row.addView(remove, new LinearLayout.LayoutParams(px(48), px(48)));
		row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(ROW_DP)));
		return row;
	}

	/** The "Add a widget to this stack" row: opens the list of widgets that can be added under it. */
	private final class AddHolder extends RecyclerView.ViewHolder {
		AddHolder() {
			super(addRow());
		}
	}

	private TextView addRow() {
		TextView t = new TextView(ctx);
		t.setText(R.string.home_stack_add);
		t.setTextSize(14);
		t.setTextColor(host.palette().accent);
		t.setGravity(Gravity.CENTER_VERTICAL);
		t.setMinHeight(px(ROW_DP));
		t.setPadding(px(12), 0, px(12), 0);
		t.setClickable(true);
		t.setFocusable(true);
		t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		t.setOnClickListener(v -> toggleAdding());
		return t;
	}

	private void toggleAdding() {
		int picker = cell.items.size() + 1;
		if (adding) {
			adding = false;
			adapter.notifyItemRemoved(picker);
		} else {
			adding = true;
			adapter.notifyItemInserted(picker);
		}
	}

	/** The widgets that can be put on the stack, as chips. */
	private final class PickerHolder extends RecyclerView.ViewHolder {
		PickerHolder() {
			super(pickerChips());
		}
	}

	private ChipFlowLayout pickerChips() {
		ChipFlowLayout flow = new ChipFlowLayout(ctx);
		flow.setPadding(px(12), px(4), px(12), px(8));
		for (HomeWidgetType t : HomeWidgets.all()) {
			if (!t.isAvailable()) continue;
			flow.addView(ChipFlowLayout.chip(ctx, t.icon, ctx.getString(t.name), false, () -> addWidget(t)));
		}
		return flow;
	}

	private void addWidget(HomeWidgetType t) {
		model.addToStack(cell, t);
		int added = cell.items.size() - 1;
		adding = false;
		adapter.notifyItemInserted(added);
		// The remove buttons come up once there is more than one widget.
		if (cell.items.size() == 2) adapter.notifyItemRangeChanged(0, 2);
		adapter.notifyItemRemoved(cell.items.size() + 1);
		grid.stackEdited();
	}

	/** Takes the widget at {@code pos} off the screen, with Undo; the stack is laid out again at once. */
	private void removeOne(int pos, HomeModel.Item it, HomeWidgetType t) {
		int cellIndex = model.cells().indexOf(cell);
		int itemIndex = cell.items.indexOf(it);
		model.remove(cell, it);
		adapter.notifyItemRemoved(pos);
		if (cell.items.size() == 1) adapter.notifyItemChanged(0);
		grid.stackEdited();
		TopToast.show((t == null) ? R.drawable.home_add : t.icon,
				ctx.getString(R.string.home_removed, (t == null) ? it.type : ctx.getString(t.name)), null, R.string.home_undo,
				() -> {
					model.restore(cell, it, cellIndex, itemIndex, false);
					adapter.notifyItemInserted(itemIndex);
					if (cell.items.size() == 2) adapter.notifyItemRangeChanged(0, cell.items.size());
					grid.stackEdited();
				});
	}
}
