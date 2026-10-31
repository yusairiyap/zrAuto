package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.AllInOneMenu;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;

/**
 * Settings &gt; Interface &gt; All in One (and its Android Auto twin): the card itself, edited in
 * place. Its tiles look exactly as on the card (built by {@link AllInOneMenu#tileView}), in a grid
 * as wide as the chosen grid size: hold a tile to drag it to another place, tap it to swap it for
 * another action, its ✕ badge removes it, and the last cell adds one. Above it, the grid size and
 * whether the Music tab's More follows the card.
 * <p>
 * One list: the options are its first row and Reset its last, the card's tiles in between, so a
 * dragged tile scrolls the whole page along when it reaches an edge.
 */
public class AllInOneFragment extends MainActivityFragment {
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_CARD_TOP = 1;
	private static final int TYPE_ITEM = 2;
	private static final int TYPE_ADD = 3;
	private static final int TYPE_FOOTER = 4;
	/** The first tile's position: after the options and the card's title. */
	private static final int FIRST = 2;
	/** Editing the car screen's own card (Settings &gt; Interface &gt; Android Auto). */
	private boolean aa;
	private Context palette;
	private int textPrimary;
	private int textSecondary;
	private int tileFill;
	private int panelFill;
	private int ripple;
	private int accent;
	private int onAccent;
	private float density;
	private final List<Action> items = new ArrayList<>();
	@Nullable
	private RecyclerView list;
	@Nullable
	private GridLayoutManager grid;
	@Nullable
	private View header;
	@Nullable
	private View cardTop;
	@Nullable
	private View footer;
	private final List<TextView> gridChips = new ArrayList<>();
	@Nullable
	private SwitchCompat musicSwitch;
	@Nullable
	private ItemTouchHelper dragHelper;
	private final Adapter adapter = new Adapter();

	@Override
	public boolean isSettingsPage() {
		return true;
	}

	@Override
	public int settingsEntryTitle() {
		return R.string.layout_settings;
	}

	@Override
	public int getFragmentId() {
		return R.id.all_in_one_fragment;
	}

	/** Its tiles stay put under a drag: the bars never move away under them. */
	@Override
	public boolean collapsesBarsOnScroll() {
		return false;
	}

	@Override
	public CharSequence getTitle() {
		String t = getResources().getString(R.string.all_in_one);
		return aa ? t + " · " + getResources().getString(R.string.interface_prefs_aa) : t;
	}

	/** @param input {@code true} for the car screen's own card */
	@Override
	public void setInput(Object input) {
		boolean car = BuildConfig.AUTO && Boolean.TRUE.equals(input);
		if (car == aa) return;
		aa = car;
		if (list != null) {
			load();
			getActivityDelegate().fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
		}
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
		panelFill = AllInOneMenu.panelColor(palette);
		ripple = paletteColor(R.attr.musicChipRipple);
		accent = EffectsUi.accent(ctx);
		onAccent = EffectsUi.onAccent(accent);

		RecyclerView rv = new RecyclerView(palette);
		GridLayoutManager glm = new GridLayoutManager(palette, 2);
		glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
			@Override
			public int getSpanSize(int position) {
				int t = adapter.getItemViewType(position);
				return ((t == TYPE_ITEM) || (t == TYPE_ADD)) ? 1 : glm.getSpanCount();
			}
		});
		grid = glm;
		rv.setLayoutManager(glm);
		rv.setClipToPadding(false);
		int pad = dp(16);
		rv.setPadding(pad, dp(8), pad, pad);
		rv.addItemDecoration(new CardDecoration());
		rv.setAdapter(adapter);
		dragHelper = new ItemTouchHelper(new DragCallback());
		dragHelper.attachToRecyclerView(rv);
		list = rv;
		header = createHeader();
		cardTop = createCardTop();
		footer = createFooter();
		load();
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
		// Favorite / timer / quality state shown on the tiles may have changed meanwhile.
		if (!hidden) adapter.notifyItemRangeChanged(FIRST, items.size());
	}

	@Override
	public void onDestroyView() {
		list = null;
		grid = null;
		header = null;
		cardTop = null;
		footer = null;
		musicSwitch = null;
		dragHelper = null;
		gridChips.clear();
		super.onDestroyView();
	}

	@Override
	public boolean onBackPressed() {
		getActivityDelegate().showFragment(R.id.layout_settings_fragment, aa);
		return true;
	}

	// ---- Settings ----

	@SuppressWarnings("unchecked")
	private <S> Pref<S> pref(Pref<S> p) {
		if (!aa) return p;
		Pref<?> twin = MainActivityPrefs.fabAA(p);
		return (twin == null) ? p : (Pref<S>) twin;
	}

	private MainActivityPrefs prefs() {
		return getActivityDelegate().getPrefs();
	}

	private int columns() {
		return Math.max(2, Math.min(4, prefs().getIntPref(pref(MainActivityPrefs.AIO_COLUMNS))));
	}

	@SuppressLint("NotifyDataSetChanged")
	private void load() {
		items.clear();
		items.addAll(AllInOneMenu.parse(prefs().getStringPref(pref(MainActivityPrefs.AIO_ITEMS))));
		if (grid != null) grid.setSpanCount(columns());
		adapter.notifyDataSetChanged();
		if (musicSwitch != null) {
			musicSwitch.setChecked(prefs().getBooleanPref(pref(MainActivityPrefs.AIO_MUSIC_MORE)));
		}
		refreshGridChips(false);
	}

	private void saveItems() {
		prefs().applyStringPref(pref(MainActivityPrefs.AIO_ITEMS), AllInOneMenu.format(items));
		// A tile's column (and so its spacing) follows its place: worked out again for every tile.
		RecyclerView rv = list;
		if (rv != null) rv.post(rv::invalidateItemDecorations);
	}

	/** Another grid size: the tiles take their new size and places, fading across. */
	private void setColumns(int cols) {
		if (columns() == cols) return;
		prefs().applyIntPref(pref(MainActivityPrefs.AIO_COLUMNS), cols);
		refreshGridChips(true);
		if (grid != null) grid.setSpanCount(cols);
		adapter.notifyItemRangeChanged(FIRST, items.size() + 1);
		RecyclerView rv = list;
		if (rv != null) rv.post(rv::invalidateItemDecorations);
	}

	/** The chosen grid size filled with the accent, the others plain; {@code animate}: cross-faded. */
	private void refreshGridChips(boolean animate) {
		int cols = columns();
		for (TextView c : gridChips) {
			boolean sel = ((Integer) c.getTag()) == cols;
			GradientDrawable fill = (GradientDrawable) c.getTag(R.id.all_in_one_fragment);
			int toFill = sel ? accent : tileFill;
			int toText = sel ? onAccent : textPrimary;
			if (!animate) {
				fill.setColor(toFill);
				c.setTextColor(toText);
				continue;
			}
			int fromFill = (fill.getColor() != null) ? fill.getColor().getDefaultColor() : tileFill;
			int fromText = c.getCurrentTextColor();
			if ((fromFill == toFill) && (fromText == toText)) continue;
			ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
			va.setDuration(220);
			android.animation.ArgbEvaluator argb = new android.animation.ArgbEvaluator();
			va.addUpdateListener(x -> {
				float f = (float) x.getAnimatedValue();
				fill.setColor((int) argb.evaluate(f, fromFill, toFill));
				c.setTextColor((int) argb.evaluate(f, fromText, toText));
			});
			va.start();
		}
	}

	/** A rounded fill with a ripple over it when pressed. */
	private RippleDrawable pressable(GradientDrawable fill) {
		GradientDrawable mask = new GradientDrawable();
		mask.setColor(0xFF000000);
		mask.setCornerRadius(fill.getCornerRadius());
		return new RippleDrawable(ColorStateList.valueOf(ripple), fill, mask);
	}

	private GradientDrawable rounded(int color, int radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(color);
		d.setCornerRadius(dp(radiusDp));
		return d;
	}

	/** The actions not in the card yet, for the add / replace pickers. */
	private List<Action> notInCard() {
		List<Action> offered = new ArrayList<>();
		for (Action act : AllInOneMenu.offeredActions()) {
			if (!items.contains(act)) offered.add(act);
		}
		return offered;
	}

	/**
	 * Picks an action for the cell at {@code idx}: a new one at the end ({@code idx} == the number
	 * of tiles), else in place of the one there.
	 */
	private void pickAction(int idx) {
		MainActivityDelegate a = getActivityDelegate();
		List<Action> offered = notInCard();
		if (offered.isEmpty()) {
			TopToast.show(R.drawable.view_grid, R.string.all_in_one_all_added);
			return;
		}
		OverlayMenu menu = a.findViewById(R.id.control_menu);
		if (menu == null) return;
		boolean add = idx >= items.size();
		menu.show(b -> {
			if (add) b.setTitle(R.string.all_in_one_add);
			else b.setTitle(getString(R.string.all_in_one_replace,
					getString(ActionFabMediator.labelFor(a, items.get(idx)))));
			b.setSelectionHandler(item -> {
				Action act = item.getData();
				if ((act == null) || items.contains(act)) return true;
				if (idx >= items.size()) {
					items.add(act);
					adapter.notifyItemInserted(FIRST + items.size() - 1);
				} else {
					items.set(idx, act);
					adapter.notifyItemChanged(FIRST + idx);
				}
				saveItems();
				return true;
			});
			for (Action act : offered) {
				b.addItem(View.generateViewId(), ActionFabMediator.iconFor(a, act), act.getName())
						.setData(act);
			}
		});
	}

	private void remove(int idx) {
		if ((idx < 0) || (idx >= items.size())) return;
		items.remove(idx);
		adapter.notifyItemRemoved(FIRST + idx);
		saveItems();
	}

	private void reset() {
		prefs().applyStringPref(pref(MainActivityPrefs.AIO_ITEMS), MainActivityPrefs.AIO_DEFAULT_ITEMS);
		prefs().applyIntPref(pref(MainActivityPrefs.AIO_COLUMNS), 2);
		load();
	}

	// ---- Header: intro, grid size, the Music tab's More ----

	private View createHeader() {
		LinearLayout h = new LinearLayout(palette);
		h.setOrientation(LinearLayout.VERTICAL);

		TextView intro = text(R.string.all_in_one_tagline, 14, textSecondary, false);
		intro.setPadding(dp(4), dp(4), dp(4), 0);
		h.addView(intro);

		section(h, R.string.all_in_one_grid);
		LinearLayout chips = new LinearLayout(palette);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		gridChips.clear();
		int[] names = {R.string.all_in_one_grid_2, R.string.all_in_one_grid_3, R.string.all_in_one_grid_4};
		for (int i = 0; i < AllInOneMenu.COLUMNS.length; i++) {
			int cols = AllInOneMenu.COLUMNS[i];
			TextView c = new TextView(palette);
			c.setText(getString(names[i]) + "\n" + getString(R.string.all_in_one_grid_hint, cols));
			c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
			c.setTypeface(Typeface.DEFAULT_BOLD);
			c.setGravity(Gravity.CENTER);
			c.setPadding(dp(8), dp(10), dp(8), dp(10));
			c.setTag(cols);
			GradientDrawable fill = rounded(tileFill, 16);
			c.setTag(R.id.all_in_one_fragment, fill);
			c.setBackground(pressable(fill));
			c.setOnClickListener(v -> setColumns(cols));
			gridChips.add(c);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins((i == 0) ? 0 : dp(4), 0, (i == names.length - 1) ? 0 : dp(4), 0);
			chips.addView(c, lp);
		}
		h.addView(chips, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// Whether the Music tab's More shows these too.
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(12), dp(12), dp(12));
		row.setBackground(pressable(rounded(tileFill, 16)));
		LinearLayout labels = new LinearLayout(palette);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.addView(text(R.string.all_in_one_music_more, 16, textPrimary, true));
		labels.addView(text(R.string.all_in_one_music_more_sub, 13, textSecondary, false));
		row.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		SwitchCompat sw = new SwitchCompat(palette);
		me.aap.fermata.ui.view.SwitchStyle.apply(sw, palette);
		sw.setChecked(prefs().getBooleanPref(pref(MainActivityPrefs.AIO_MUSIC_MORE)));
		sw.setOnCheckedChangeListener((b, on) ->
				prefs().applyBooleanPref(pref(MainActivityPrefs.AIO_MUSIC_MORE), on));
		musicSwitch = sw;
		row.addView(sw);
		row.setOnClickListener(v -> sw.toggle());
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		rlp.topMargin = dp(16);
		h.addView(row, rlp);

		section(h, R.string.all_in_one_items);
		TextView hint = text(R.string.all_in_one_items_hint, 13, textSecondary, false);
		hint.setPadding(dp(4), 0, dp(4), dp(10));
		h.addView(hint);
		h.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return h;
	}

	/** The card's own title, as on the card: the top of the card drawn behind the tiles. */
	private View createCardTop() {
		TextView t = text(R.string.all_in_one, 18, textPrimary, true);
		t.setPadding(dp(24), dp(18), dp(24), dp(10));
		t.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return t;
	}

	// ---- Footer: reset ----

	private View createFooter() {
		FrameLayout f = new FrameLayout(palette);
		f.setPadding(0, dp(16), 0, dp(8));
		TextView reset = text(R.string.all_in_one_reset, 15, textPrimary, true);
		reset.setGravity(Gravity.CENTER);
		reset.setSingleLine(true);
		reset.setBackground(pressable(rounded(tileFill, 24)));
		reset.setClickable(true);
		reset.setFocusable(true);
		reset.setOnClickListener(v -> UiUtils.showQuestion(requireContext(), R.string.all_in_one_reset,
				R.string.all_in_one_reset_question, R.drawable.view_grid).onSuccess(ok -> reset()));
		f.addView(reset, new FrameLayout.LayoutParams(MATCH_PARENT, dp(48)));
		f.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return f;
	}

	// ---- The card's tiles ----

	private static final class Holder extends RecyclerView.ViewHolder {
		@Nullable
		final FrameLayout cell;

		Holder(View v, @Nullable FrameLayout cell) {
			super(v);
			this.cell = cell;
		}
	}

	private final class Adapter extends RecyclerView.Adapter<Holder> {

		@Override
		public int getItemViewType(int position) {
			if (position == 0) return TYPE_HEADER;
			if (position == 1) return TYPE_CARD_TOP;
			int i = position - FIRST;
			if (i < items.size()) return TYPE_ITEM;
			return (i == items.size()) ? TYPE_ADD : TYPE_FOOTER;
		}

		@Override
		public int getItemCount() {
			return (header == null) ? 0 : items.size() + 4;
		}

		@NonNull
		@Override
		public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			if ((viewType == TYPE_HEADER) || (viewType == TYPE_CARD_TOP) || (viewType == TYPE_FOOTER)) {
				View v = (viewType == TYPE_HEADER) ? header : (viewType == TYPE_CARD_TOP) ? cardTop : footer;
				// One of each, ever: a recycled one is reused, never a second made.
				if ((v.getParent() instanceof ViewGroup g)) g.removeView(v);
				return new Holder(v, null);
			}
			return (viewType == TYPE_ADD) ? createAddCell() : createCell();
		}

		@Override
		public void onBindViewHolder(@NonNull Holder h, int position) {
			FrameLayout cell = h.cell;
			if ((cell == null) || (h.getItemViewType() != TYPE_ITEM)) return;
			int i = position - FIRST;
			if ((i < 0) || (i >= items.size())) return;
			Action act = items.get(i);
			// The tile at the card's size and state; the badge stays on top of it.
			if (cell.getChildCount() > 1) cell.removeViewAt(0);
			View tile = AllInOneMenu.tileView(getActivityDelegate(), palette, act, columns());
			cell.addView(tile, 0, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
			View badge = cell.getChildAt(1);
			String n = getString(ActionFabMediator.labelFor(getActivityDelegate(), act));
			badge.setContentDescription(getString(R.string.all_in_one_remove, n));
			cell.setContentDescription(n);
		}
	}

	/** A tile's cell: the tile (bound later), a ripple over it, and the ✕ badge in its corner. */
	private Holder createCell() {
		FrameLayout cell = new FrameLayout(palette);
		cell.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		GradientDrawable mask = rounded(0xFF000000, (columns() == 2) ? 22 : 18);
		cell.setForeground(new RippleDrawable(ColorStateList.valueOf(ripple), null, mask));
		cell.setClickable(true);
		cell.setFocusable(true);

		ImageView badge = new ImageView(palette);
		badge.setImageResource(me.aap.utils.R.drawable.close);
		badge.setImageTintList(ColorStateList.valueOf(panelFill));
		badge.setPadding(dp(4), dp(4), dp(4), dp(4));
		GradientDrawable disc = new GradientDrawable();
		disc.setShape(GradientDrawable.OVAL);
		disc.setColor(ColorUtils.setAlphaComponent(textPrimary, 0xE0));
		badge.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), disc, null));
		badge.setElevation(dp(2));
		badge.setClickable(true);
		FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
		blp.topMargin = dp(4);
		blp.setMarginEnd(dp(4));
		cell.addView(badge, blp);

		Holder h = new Holder(cell, cell);
		cell.setOnClickListener(v -> {
			int i = h.getBindingAdapterPosition() - FIRST;
			if ((i >= 0) && (i < items.size())) pickAction(i);
		});
		badge.setOnClickListener(v -> remove(h.getBindingAdapterPosition() - FIRST));
		return h;
	}

	/** The last cell: a dashed outline with a plus, adding an action. */
	private Holder createAddCell() {
		FrameLayout cell = new FrameLayout(palette);
		cell.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		GradientDrawable outline = new GradientDrawable();
		outline.setCornerRadius(dp(20));
		outline.setColor(ColorUtils.setAlphaComponent(accent, 0x10));
		outline.setStroke(dp(2), ColorUtils.setAlphaComponent(accent, 0xA0), dp(6), dp(5));
		GradientDrawable mask = rounded(0xFF000000, 20);
		cell.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), outline, mask));
		cell.setClickable(true);
		cell.setFocusable(true);
		cell.setContentDescription(getString(R.string.all_in_one_add));

		LinearLayout content = new LinearLayout(palette);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setGravity(Gravity.CENTER);
		content.setMinimumHeight(dp(88));
		content.setPadding(dp(6), dp(10), dp(6), dp(10));
		TextView plus = text(0, 30, accent, false);
		plus.setText("+");
		plus.setGravity(Gravity.CENTER);
		content.addView(plus, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		TextView label = text(R.string.all_in_one_add, 13, accent, true);
		label.setGravity(Gravity.CENTER);
		label.setMaxLines(2);
		content.addView(label, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		cell.addView(content, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		Holder h = new Holder(cell, null);
		cell.setOnClickListener(v -> pickAction(items.size()));
		return h;
	}

	/**
	 * Spaces the tiles like the card's (an even gap between them and around them) and draws the
	 * card's fill behind them and its title, so the grid reads as the card itself.
	 */
	private final class CardDecoration extends RecyclerView.ItemDecoration {
		private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final Rect bounds = new Rect();

		@Override
		public void getItemOffsets(@NonNull Rect out, @NonNull View v, @NonNull RecyclerView rv,
															 @NonNull RecyclerView.State state) {
			int pos = rv.getChildAdapterPosition(v);
			if (pos == RecyclerView.NO_POSITION) return;
			int t = adapter.getItemViewType(pos);
			if ((t != TYPE_ITEM) && (t != TYPE_ADD)) return;
			int n = columns();
			int col = (pos - FIRST) % n;
			int s = dp(12);
			out.left = s - col * s / n;
			out.right = (col + 1) * s / n;
			out.bottom = s;
		}

		@Override
		public void onDraw(@NonNull Canvas c, @NonNull RecyclerView rv, @NonNull RecyclerView.State st) {
			RecyclerView.LayoutManager lm = rv.getLayoutManager();
			if (lm == null) return;
			float top = Float.MAX_VALUE;
			float bottom = -Float.MAX_VALUE;
			boolean topShown = false;
			boolean endShown = false;
			for (int i = 0, n = rv.getChildCount(); i < n; i++) {
				View v = rv.getChildAt(i);
				int pos = rv.getChildAdapterPosition(v);
				if (pos == RecyclerView.NO_POSITION) continue;
				int t = adapter.getItemViewType(pos);
				if ((t != TYPE_CARD_TOP) && (t != TYPE_ITEM) && (t != TYPE_ADD)) continue;
				lm.getDecoratedBoundsWithMargins(v, bounds);
				top = Math.min(top, bounds.top);
				bottom = Math.max(bottom, bounds.bottom);
				if (t == TYPE_CARD_TOP) topShown = true;
				if (t == TYPE_ADD) endShown = true;
			}
			if (top == Float.MAX_VALUE) return;
			float r = dp(28);
			// Scrolled partly away: the card runs on past the screen's edge.
			if (!topShown) top = -r;
			if (!endShown) bottom = rv.getHeight() + r;
			rect.set(rv.getPaddingLeft(), top, rv.getWidth() - rv.getPaddingRight(), bottom);
			fill.setColor(panelFill);
			c.drawRoundRect(rect, r, r, fill);
		}
	}

	/** Drags the tiles (only) around the grid; saved once dropped. */
	private final class DragCallback extends ItemTouchHelper.Callback {
		private boolean moved;
		/** Which way the tile is being dragged: the list only scrolls along with it, never against. */
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
			// Under the see-through tool bar a tile counts as out of bounds already: without this the
			// list scrolled up while a tile was dragged down from near the top.
			if (Integer.signum(viewSizeOutOfBounds) != dragDir) return 0;
			// The list's padding is the room the bars take, a lot of it at the bottom (nav bar, control
			// panel, the floating buttons): a tile counts as out of bounds as soon as it enters it, so
			// the list scrolled away under a tile only just moved. Only the outer half of it scrolls.
			int slack = ((viewSizeOutOfBounds > 0) ? rv.getPaddingBottom() : rv.getPaddingTop()) / 2;
			int out = Math.abs(viewSizeOutOfBounds) - slack;
			if (out <= 0) return 0;
			return super.interpolateOutOfBoundsScroll(rv, viewSize,
					Integer.signum(viewSizeOutOfBounds) * out, totalSize, msSinceStartScroll);
		}

		/**
		 * Keeps the dragged tile where the finger is after a swap: the default asks the layout
		 * manager to put it at the swapped tile's place, measured from the list's top edge, which,
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
			return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN |
					ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
		}

		@Override
		public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
													@NonNull RecyclerView.ViewHolder to) {
			if (to.getItemViewType() != TYPE_ITEM) return false;
			int f = from.getBindingAdapterPosition() - FIRST;
			int t = to.getBindingAdapterPosition() - FIRST;
			if ((f < 0) || (t < 0) || (f >= items.size()) || (t >= items.size())) return false;
			// Taken out and put in at the new place: the tiles between shift along by one.
			items.add(t, items.remove(f));
			adapter.notifyItemMoved(f + FIRST, t + FIRST);
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
				vh.itemView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start();
				vh.itemView.setElevation(dp(8));
			}
		}

		@Override
		public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
			super.clearView(rv, vh);
			vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
			vh.itemView.setElevation(0);
			if (moved) {
				moved = false;
				saveItems();
			}
		}
	}

	// ---- Helpers ----

	private void section(LinearLayout content, @StringRes int title) {
		TextView t = text(title, 13, accent, true);
		t.setAllCaps(true);
		t.setLetterSpacing(0.08f);
		t.setPadding(dp(4), dp(20), 0, dp(6));
		content.addView(t);
	}

	private TextView text(@StringRes int res, float sp, int color, boolean bold) {
		TextView t = new TextView(palette);
		if (res != 0) t.setText(res);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private int paletteColor(int attr) {
		TypedValue tv = new TypedValue();
		palette.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	private int dp(int v) {
		return Math.round(v * density);
	}
}
