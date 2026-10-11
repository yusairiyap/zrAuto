package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.utils.ui.UiUtils;

/**
 * A settings page that is a list of rows to put in order, like the All in One page used to be: a
 * drag handle, the item's icon, its name and state, a switch (shown or not) and a "more" button
 * for the rest of its options (a tap on the row does the same). Under a header the subclass
 * makes (its own options) and above a Reset button.
 * <p>
 * Dragging works as on the All in One page, with its fixes: the list only scrolls along with a
 * dragged row, and only when it is in the outer half of the room the bars take (the default scrolled
 * the whole list away under it), and the row stays under the finger after a swap.
 */
public abstract class ReorderListFragment extends MainActivityFragment {
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ITEM = 1;
	private static final int TYPE_FOOTER = 2;

	/** One row: what it is called, which way it is set, and its place in the order. */
	protected static final class Row {
		public final String key;
		@DrawableRes
		public final int icon;
		public final CharSequence title;
		@Nullable
		public final CharSequence subtitle;
		/** Whether its switch is on (the item is shown); the row is dimmed when it is not. */
		public final boolean checked;

		public Row(@NonNull String key, @DrawableRes int icon, @NonNull CharSequence title,
							 @Nullable CharSequence subtitle, boolean checked) {
			this.key = key;
			this.icon = icon;
			this.title = title;
			this.subtitle = subtitle;
			this.checked = checked;
		}
	}

	protected Context palette;
	protected int textPrimary;
	protected int textSecondary;
	protected int tileFill;
	protected int ripple;
	protected int accent;
	protected int onAccent;
	protected float density;
	private final List<Row> rows = new ArrayList<>();
	@Nullable
	private RecyclerView list;
	@Nullable
	private View header;
	@Nullable
	private View footer;
	@Nullable
	private ItemTouchHelper dragHelper;
	private final Adapter adapter = new Adapter();

	// ---- What a page says ----

	/** The rows, in their current order. */
	protected abstract List<Row> loadRows();

	/** The rows were dragged into a new order: the keys in it. */
	protected abstract void saveOrder(List<String> keys);

	/** A row's switch was flipped. */
	protected abstract void setChecked(String key, boolean checked);

	/** The options of a row: opens its menu. */
	protected abstract void showRowMenu(Row row);

	/** Back to the defaults (already confirmed). */
	protected abstract void onReset();

	@StringRes
	protected abstract int resetTitle();

	@StringRes
	protected abstract int resetQuestion();

	/** A line under the page's title, what to do here. */
	@StringRes
	protected abstract int hintRes();

	/** Where Back goes. */
	protected abstract void goBack();

	/** The page's own options above the rows (a section title and what goes with it), if any. */
	@Nullable
	protected View createExtraHeader() {
		return null;
	}

	/** Called when the rows are laid out again from their settings. */
	protected void onReloaded() {
	}

	// ---- Fragment ----

	/** The rows stay put under a drag: the bars never move away under them. */
	@Override
	public boolean collapsesBarsOnScroll() {
		return false;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		palette = EffectsUi.palette(ctx);
		density = ctx.getResources().getDisplayMetrics().density;
		textPrimary = paletteColor(R.attr.musicTextPrimary);
		textSecondary = paletteColor(R.attr.musicTextSecondary);
		tileFill = paletteColor(R.attr.musicChipFill);
		ripple = paletteColor(R.attr.musicChipRipple);
		accent = EffectsUi.accent(ctx);
		onAccent = EffectsUi.onAccent(accent);

		RecyclerView rv = new RecyclerView(palette);
		rv.setLayoutManager(new LinearLayoutManager(palette));
		rv.setClipToPadding(false);
		int pad = dp(16);
		rv.setPadding(pad, dp(8), pad, pad);
		rv.setAdapter(adapter);
		dragHelper = new ItemTouchHelper(new DragCallback());
		dragHelper.attachToRecyclerView(rv);
		list = rv;
		header = createHeader();
		footer = createFooter();
		reload();
		return rv;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		getActivityDelegate().insetScrollableContent((ViewGroup) view);
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		// Something may have changed from elsewhere meanwhile.
		if (!hidden) reload();
	}

	@Override
	public void onDestroyView() {
		list = null;
		header = null;
		footer = null;
		dragHelper = null;
		super.onDestroyView();
	}

	@Override
	public boolean onBackPressed() {
		goBack();
		return true;
	}

	/** Reads the rows again from their settings. */
	@SuppressLint("NotifyDataSetChanged")
	protected final void reload() {
		rows.clear();
		rows.addAll(loadRows());
		adapter.notifyDataSetChanged();
		onReloaded();
	}

	protected final MainActivityDelegate delegate() {
		return getActivityDelegate();
	}

	// ---- Header and footer ----

	private View createHeader() {
		LinearLayout h = new LinearLayout(palette);
		h.setOrientation(LinearLayout.VERTICAL);
		TextView intro = text(hintRes(), 14, textSecondary, false);
		intro.setPadding(dp(4), dp(4), dp(4), dp(4));
		h.addView(intro);
		View extra = createExtraHeader();
		if (extra != null) h.addView(extra, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		h.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return h;
	}

	private View createFooter() {
		FrameLayout f = new FrameLayout(palette);
		f.setPadding(0, dp(16), 0, dp(8));
		TextView reset = text(resetTitle(), 15, textPrimary, true);
		reset.setGravity(Gravity.CENTER);
		reset.setSingleLine(true);
		reset.setBackground(pressable(rounded(tileFill, 24)));
		reset.setClickable(true);
		reset.setFocusable(true);
		reset.setOnClickListener(v -> UiUtils.showQuestion(requireContext(), resetTitle(),
				resetQuestion(), R.drawable.view_grid).onSuccess(ok -> {
			onReset();
			reload();
		}));
		f.addView(reset, new FrameLayout.LayoutParams(MATCH_PARENT, dp(48)));
		f.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return f;
	}

	// ---- The rows ----

	private static final class Holder extends RecyclerView.ViewHolder {
		@Nullable
		final ImageView icon;
		@Nullable
		final TextView name;
		@Nullable
		final TextView sub;
		@Nullable
		final SwitchCompat toggle;
		@Nullable
		final View texts;

		Holder(View v) {
			super(v);
			icon = null;
			name = null;
			sub = null;
			toggle = null;
			texts = null;
		}

		Holder(View v, ImageView icon, TextView name, TextView sub, SwitchCompat toggle, View texts) {
			super(v);
			this.icon = icon;
			this.name = name;
			this.sub = sub;
			this.toggle = toggle;
			this.texts = texts;
		}
	}

	private final class Adapter extends RecyclerView.Adapter<Holder> {

		@Override
		public int getItemViewType(int position) {
			if (position == 0) return TYPE_HEADER;
			return (position > rows.size()) ? TYPE_FOOTER : TYPE_ITEM;
		}

		@Override
		public int getItemCount() {
			return (header == null) ? 0 : rows.size() + 2;
		}

		@NonNull
		@Override
		public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			if ((viewType == TYPE_HEADER) || (viewType == TYPE_FOOTER)) {
				View v = (viewType == TYPE_HEADER) ? header : footer;
				// One of each, ever: a recycled one is reused, never a second made.
				if ((v.getParent() instanceof ViewGroup g)) g.removeView(v);
				return new Holder(v);
			}
			return createRow();
		}

		@Override
		public void onBindViewHolder(@NonNull Holder h, int position) {
			if ((h.name == null) || (h.icon == null) || (h.sub == null) || (h.toggle == null) ||
					(h.texts == null) || (position < 1) || (position > rows.size())) {
				return;
			}
			Row r = rows.get(position - 1);
			h.name.setText(r.title);
			h.sub.setText(r.subtitle);
			h.sub.setVisibility(((r.subtitle == null) || (r.subtitle.length() == 0)) ? View.GONE : View.VISIBLE);
			h.icon.setImageResource(r.icon);
			h.texts.setAlpha(r.checked ? 1f : 0.5f);
			// Set without telling anyone: the row is only being drawn.
			h.toggle.setOnCheckedChangeListener(null);
			h.toggle.setChecked(r.checked);
			h.toggle.setOnCheckedChangeListener((b, on) -> {
				int i = h.getBindingAdapterPosition() - 1;
				if ((i >= 0) && (i < rows.size())) {
					setChecked(rows.get(i).key, on);
					reload();
				}
			});
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	private Holder createRow() {
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(4), dp(6), dp(4), dp(6));
		row.setBackground(pressable(rounded(tileFill, 16)));
		row.setClickable(true);
		row.setFocusable(true);
		RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(6);
		row.setLayoutParams(lp);

		ImageView handle = new ImageView(palette);
		handle.setImageResource(R.drawable.drag_handle);
		handle.setImageTintList(ColorStateList.valueOf(textSecondary));
		handle.setPadding(dp(12), dp(12), dp(12), dp(12));
		row.addView(handle, new LinearLayout.LayoutParams(dp(48), dp(48)));

		FrameLayout disc = new FrameLayout(palette);
		GradientDrawable d = new GradientDrawable();
		d.setShape(GradientDrawable.OVAL);
		d.setColor(accent);
		disc.setBackground(d);
		ImageView icon = new ImageView(palette);
		icon.setImageTintList(ColorStateList.valueOf(onAccent));
		disc.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
		LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(36), dp(36));
		dlp.setMarginStart(dp(2));
		row.addView(disc, dlp);

		LinearLayout texts = new LinearLayout(palette);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.setPadding(dp(12), 0, dp(8), 0);
		TextView name = text(0, 15, textPrimary, true);
		name.setMaxLines(2);
		TextView sub = text(0, 12.5f, textSecondary, false);
		sub.setMaxLines(2);
		texts.addView(name);
		texts.addView(sub);
		row.addView(texts, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		SwitchCompat toggle = new SwitchCompat(palette);
		row.addView(toggle, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

		ImageView more = new ImageView(palette);
		more.setImageResource(R.drawable.more);
		more.setImageTintList(ColorStateList.valueOf(textSecondary));
		more.setPadding(dp(10), dp(10), dp(10), dp(10));
		more.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), null, null));
		more.setClickable(true);
		more.setFocusable(true);
		more.setContentDescription(getString(R.string.row_options));
		row.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));

		Holder h = new Holder(row, icon, name, sub, toggle, texts);
		// The handle and the icon pick a row up at once (the rest of the row scrolls the list, or
		// picks the row up after a hold).
		View.OnTouchListener grab = (v, e) -> {
			if ((e.getActionMasked() == MotionEvent.ACTION_DOWN) && (dragHelper != null)) {
				if (list != null) list.getParent().requestDisallowInterceptTouchEvent(true);
				dragHelper.startDrag(h);
				return true;
			}
			return false;
		};
		handle.setOnTouchListener(grab);
		disc.setOnTouchListener(grab);
		View.OnClickListener options = v -> {
			int i = h.getBindingAdapterPosition() - 1;
			if ((i >= 0) && (i < rows.size())) showRowMenu(rows.get(i));
		};
		row.setOnClickListener(options);
		more.setOnClickListener(options);
		return h;
	}

	/** Drags the rows (only) up and down; saved once dropped. */
	private final class DragCallback extends ItemTouchHelper.Callback {
		private boolean moved;
		/** Which way the row is being dragged: the list only scrolls along with it, never against. */
		private float lastDy;
		private int dragDir;

		@Override
		public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv,
														@NonNull RecyclerView.ViewHolder vh, float dX, float dY, int state,
														boolean active) {
			if ((state == ItemTouchHelper.ACTION_STATE_DRAG) && active) {
				if (Math.abs(dY - lastDy) > dp(2)) {
					dragDir = (dY > lastDy) ? 1 : -1;
					lastDy = dY;
				}
			}
			super.onChildDraw(c, rv, vh, dX, dY, state, active);
		}

		@Override
		public int interpolateOutOfBoundsScroll(@NonNull RecyclerView rv, int viewSize,
																						int viewSizeOutOfBounds, int totalSize,
																						long msSinceStartScroll) {
			// Under the see-through tool bar a row counts as out of bounds already: without this the
			// list scrolled up while a row was dragged down from near the top.
			if (Integer.signum(viewSizeOutOfBounds) != dragDir) return 0;
			// The list's padding is the room the bars take, a lot of it at the bottom (nav bar, control
			// panel, the floating buttons): a row counts as out of bounds as soon as it enters it, so
			// the list scrolled away under a row only just moved. Only the outer half of it scrolls.
			int slack = ((viewSizeOutOfBounds > 0) ? rv.getPaddingBottom() : rv.getPaddingTop()) / 2;
			int out = Math.abs(viewSizeOutOfBounds) - slack;
			if (out <= 0) return 0;
			return super.interpolateOutOfBoundsScroll(rv, viewSize,
					Integer.signum(viewSizeOutOfBounds) * out, totalSize, msSinceStartScroll);
		}

		/**
		 * Keeps the dragged row where the finger is after a swap: the default asks the layout
		 * manager to put it at the swapped row's place, measured from the list's top edge, which,
		 * with the tool bar's room as top padding here, could jump the list by that padding.
		 */
		@Override
		public void onMoved(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, int fromPos,
												@NonNull RecyclerView.ViewHolder target, int toPos, int x, int y) {
			if (!(rv.getLayoutManager() instanceof LinearLayoutManager lm)) {
				super.onMoved(rv, vh, fromPos, target, toPos, x, y);
				return;
			}
			OrientationHelper h = OrientationHelper.createVerticalHelper(lm);
			int pad = h.getStartAfterPadding();
			if (fromPos > toPos) {
				lm.scrollToPositionWithOffset(toPos, h.getDecoratedStart(target.itemView) - pad);
			} else {
				lm.scrollToPositionWithOffset(toPos, h.getDecoratedEnd(target.itemView) -
						h.getDecoratedMeasurement(vh.itemView) - pad);
			}
		}

		@Override
		public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
			if (vh.getItemViewType() != TYPE_ITEM) return 0;
			return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
		}

		@Override
		public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
													@NonNull RecyclerView.ViewHolder to) {
			if (to.getItemViewType() != TYPE_ITEM) return false;
			int f = from.getBindingAdapterPosition();
			int t = to.getBindingAdapterPosition();
			if ((f < 1) || (t < 1) || (f > rows.size()) || (t > rows.size())) return false;
			Collections.swap(rows, f - 1, t - 1);
			adapter.notifyItemMoved(f, t);
			moved = true;
			return true;
		}

		@Override
		public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
		}

		@Override
		public boolean isLongPressDragEnabled() {
			return true;
		}

		@Override
		public void onSelectedChanged(@Nullable RecyclerView.ViewHolder vh, int state) {
			super.onSelectedChanged(vh, state);
			if ((state == ItemTouchHelper.ACTION_STATE_DRAG) && (vh != null)) {
				lastDy = 0;
				dragDir = 0;
				vh.itemView.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();
				vh.itemView.setElevation(dp(6));
			}
		}

		@Override
		public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
			super.clearView(rv, vh);
			vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
			vh.itemView.setElevation(0);
			if (moved) {
				moved = false;
				List<String> keys = new ArrayList<>(rows.size());
				for (Row r : rows) keys.add(r.key);
				saveOrder(keys);
			}
		}
	}

	// ---- Helpers for the pages ----

	protected final void section(LinearLayout content, @StringRes int title) {
		TextView t = text(title, 13, accent, true);
		t.setAllCaps(true);
		t.setLetterSpacing(0.08f);
		t.setPadding(dp(4), dp(20), 0, dp(6));
		content.addView(t);
	}

	protected final TextView text(@StringRes int res, float sp, int color, boolean bold) {
		TextView t = new TextView(palette);
		if (res != 0) t.setText(res);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	/** A rounded fill with a ripple over it when pressed. */
	protected final RippleDrawable pressable(GradientDrawable fill) {
		GradientDrawable mask = new GradientDrawable();
		mask.setColor(0xFF000000);
		mask.setCornerRadius(fill.getCornerRadius());
		return new RippleDrawable(ColorStateList.valueOf(ripple), fill, mask);
	}

	protected final GradientDrawable rounded(int color, int radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(color);
		d.setCornerRadius(dp(radiusDp));
		return d;
	}

	protected final int paletteColor(int attr) {
		TypedValue tv = new TypedValue();
		palette.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	protected final int dp(int v) {
		return Math.round(v * density);
	}
}
