package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
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
import me.aap.utils.ui.menu.OverlayMenu;

/**
 * Settings &gt; Interface &gt; All in One (and its Android Auto twin): which actions the All in One
 * card holds and in what order (add, remove, drag to reorder), how big its tiles are, and whether
 * the Music tab's More follows it. A live preview at the top shows the card exactly as it will
 * look, see {@link AllInOneMenu}.
 * <p>
 * One list: the preview and the options are its first row, the buttons its last, so a dragged
 * action scrolls the whole page along when it reaches an edge.
 */
public class AllInOneFragment extends MainActivityFragment {
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ITEM = 1;
	private static final int TYPE_FOOTER = 2;
	/** Editing the car screen's own card (Settings &gt; Interface &gt; Android Auto). */
	private boolean aa;
	private Context palette;
	private int textPrimary;
	private int textSecondary;
	private int tileFill;
	private int accent;
	private int onAccent;
	private float density;
	private final List<Action> items = new ArrayList<>();
	@Nullable
	private RecyclerView list;
	@Nullable
	private View header;
	@Nullable
	private View footer;
	@Nullable
	private FrameLayout previewHolder;
	private final List<TextView> gridChips = new ArrayList<>();
	@Nullable
	private SwitchCompat musicSwitch;
	@Nullable
	private ItemTouchHelper dragHelper;
	private final Adapter adapter = new Adapter();

	@Override
	public int getFragmentId() {
		return R.id.all_in_one_fragment;
	}

	/** Its rows stay put under a drag: the bars never move away under them. */
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
		// Favorite / timer state shown on the tiles may have changed meanwhile.
		if (!hidden) refreshPreview();
	}

	@Override
	public void onDestroyView() {
		list = null;
		header = null;
		footer = null;
		previewHolder = null;
		musicSwitch = null;
		dragHelper = null;
		gridChips.clear();
		super.onDestroyView();
	}

	@Override
	public boolean onBackPressed() {
		getActivityDelegate().showFragment(R.id.settings_fragment,
				aa ? SettingsFragment.SHOW_AA_INTERFACE : SettingsFragment.SHOW_INTERFACE);
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
		return prefs().getIntPref(pref(MainActivityPrefs.AIO_COLUMNS));
	}

	@SuppressLint("NotifyDataSetChanged")
	private void load() {
		items.clear();
		items.addAll(AllInOneMenu.parse(prefs().getStringPref(pref(MainActivityPrefs.AIO_ITEMS))));
		adapter.notifyDataSetChanged();
		if (musicSwitch != null) {
			musicSwitch.setChecked(prefs().getBooleanPref(pref(MainActivityPrefs.AIO_MUSIC_MORE)));
		}
		refreshGridChips();
		refreshPreview();
	}

	private void saveItems() {
		prefs().applyStringPref(pref(MainActivityPrefs.AIO_ITEMS), AllInOneMenu.format(items));
		refreshPreview();
	}

	private void refreshPreview() {
		FrameLayout h = previewHolder;
		if (h == null) return;
		h.removeAllViews();
		h.addView(AllInOneMenu.preview(getActivityDelegate(), palette, items, columns()),
				new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
	}

	private void refreshGridChips() {
		int cols = columns();
		for (TextView c : gridChips) {
			boolean sel = ((Integer) c.getTag()) == cols;
			GradientDrawable bg = new GradientDrawable();
			bg.setCornerRadius(dp(16));
			bg.setColor(sel ? accent : tileFill);
			c.setBackground(bg);
			c.setTextColor(sel ? onAccent : textPrimary);
		}
	}

	private void addAction() {
		MainActivityDelegate a = getActivityDelegate();
		List<Action> offered = new ArrayList<>();
		for (Action act : AllInOneMenu.offeredActions()) {
			if (!items.contains(act)) offered.add(act);
		}
		if (offered.isEmpty()) {
			TopToast.show(R.drawable.view_grid, R.string.all_in_one_all_added);
			return;
		}
		OverlayMenu menu = a.findViewById(R.id.control_menu);
		if (menu == null) return;
		menu.show(b -> {
			b.setTitle(R.string.all_in_one_add);
			b.setSelectionHandler(item -> {
				Action act = item.getData();
				if ((act != null) && !items.contains(act)) {
					items.add(act);
					adapter.notifyItemInserted(items.size());
					saveItems();
					if (list != null) list.smoothScrollToPosition(items.size());
				}
				return true;
			});
			for (Action act : offered) {
				b.addItem(View.generateViewId(), ActionFabMediator.iconFor(a, act), act.getName())
						.setData(act);
			}
		});
	}

	private void reset() {
		prefs().applyStringPref(pref(MainActivityPrefs.AIO_ITEMS), MainActivityPrefs.AIO_DEFAULT_ITEMS);
		prefs().applyIntPref(pref(MainActivityPrefs.AIO_COLUMNS), 2);
		load();
	}

	// ---- Header: intro, preview, grid size, the Music tab's More ----

	private View createHeader() {
		LinearLayout h = new LinearLayout(palette);
		h.setOrientation(LinearLayout.VERTICAL);

		TextView intro = text(R.string.all_in_one_tagline, 14, textSecondary, false);
		intro.setPadding(dp(4), dp(4), dp(4), 0);
		h.addView(intro);

		section(h, R.string.all_in_one_preview);
		// The card over the app's own dimmed background, as it shows over a tab.
		FrameLayout stage = new FrameLayout(palette);
		GradientDrawable sbg = new GradientDrawable();
		sbg.setCornerRadius(dp(24));
		sbg.setColor(ColorUtils.setAlphaComponent(accent, 0x14));
		sbg.setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 0x40));
		stage.setBackground(sbg);
		stage.setPadding(dp(12), dp(16), dp(12), dp(16));
		previewHolder = new FrameLayout(palette);
		stage.addView(previewHolder, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		h.addView(stage, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

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
			c.setOnClickListener(v -> {
				prefs().applyIntPref(pref(MainActivityPrefs.AIO_COLUMNS), cols);
				refreshGridChips();
				refreshPreview();
			});
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
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(tileFill);
		bg.setCornerRadius(dp(16));
		row.setBackground(bg);
		LinearLayout labels = new LinearLayout(palette);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.addView(text(R.string.all_in_one_music_more, 16, textPrimary, true));
		labels.addView(text(R.string.all_in_one_music_more_sub, 13, textSecondary, false));
		row.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		SwitchCompat sw = new SwitchCompat(palette);
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
		hint.setPadding(dp(4), 0, dp(4), dp(4));
		h.addView(hint);
		h.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return h;
	}

	// ---- Footer: add, reset ----

	private View createFooter() {
		LinearLayout f = new LinearLayout(palette);
		f.setOrientation(LinearLayout.HORIZONTAL);
		f.setPadding(0, dp(14), 0, dp(8));

		TextView add = pill(R.string.all_in_one_add, true);
		add.setOnClickListener(v -> addAction());
		TextView reset = pill(R.string.all_in_one_reset, false);
		reset.setOnClickListener(v -> reset());
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(48), 1.4f);
		alp.setMarginEnd(dp(6));
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(48), 1f);
		rlp.setMarginStart(dp(6));
		f.addView(add, alp);
		f.addView(reset, rlp);
		f.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return f;
	}

	private TextView pill(@StringRes int label, boolean filled) {
		TextView t = text(label, 15, filled ? onAccent : textPrimary, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(dp(24));
		bg.setColor(filled ? accent : tileFill);
		t.setBackground(bg);
		return t;
	}

	// ---- The actions list ----

	private static final class Holder extends RecyclerView.ViewHolder {
		@Nullable
		final ImageView handle;
		@Nullable
		final ImageView icon;
		@Nullable
		final TextView name;
		@Nullable
		final ImageView remove;

		Holder(View v) {
			super(v);
			handle = null;
			icon = null;
			name = null;
			remove = null;
		}

		Holder(View v, ImageView handle, ImageView icon, TextView name, ImageView remove) {
			super(v);
			this.handle = handle;
			this.icon = icon;
			this.name = name;
			this.remove = remove;
		}
	}

	private final class Adapter extends RecyclerView.Adapter<Holder> {

		@Override
		public int getItemViewType(int position) {
			if (position == 0) return TYPE_HEADER;
			return (position > items.size()) ? TYPE_FOOTER : TYPE_ITEM;
		}

		@Override
		public int getItemCount() {
			return (header == null) ? 0 : items.size() + 2;
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
			if ((h.name == null) || (h.icon == null) || (position < 1) || (position > items.size())) return;
			Action act = items.get(position - 1);
			String n = getString(act.getName());
			h.name.setText(n);
			h.icon.setImageResource(ActionFabMediator.iconFor(getActivityDelegate(), act));
			if (h.remove != null) h.remove.setContentDescription(getString(R.string.all_in_one_remove, n));
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	private Holder createRow() {
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(4), dp(6), dp(4), dp(6));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(tileFill);
		bg.setCornerRadius(dp(16));
		row.setBackground(bg);
		RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(6);
		row.setLayoutParams(lp);

		ImageView handle = new ImageView(palette);
		handle.setImageResource(R.drawable.drag_handle);
		handle.setImageTintList(ColorStateList.valueOf(textSecondary));
		handle.setPadding(dp(8), dp(8), dp(8), dp(8));
		row.addView(handle, new LinearLayout.LayoutParams(dp(40), dp(40)));

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

		TextView name = text(0, 15, textPrimary, true);
		name.setPadding(dp(12), 0, dp(8), 0);
		name.setMaxLines(2);
		row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		ImageView remove = new ImageView(palette);
		remove.setImageResource(me.aap.utils.R.drawable.close);
		remove.setImageTintList(ColorStateList.valueOf(textSecondary));
		remove.setPadding(dp(10), dp(10), dp(10), dp(10));
		remove.setBackgroundResource(me.aap.utils.R.drawable.focusable_shape_transparent);
		row.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(44)));

		Holder h = new Holder(row, handle, icon, name, remove);
		handle.setOnTouchListener((v, e) -> {
			if ((e.getActionMasked() == MotionEvent.ACTION_DOWN) && (dragHelper != null)) {
				dragHelper.startDrag(h);
			}
			return false;
		});
		remove.setOnClickListener(v -> {
			int pos = h.getBindingAdapterPosition();
			if ((pos < 1) || (pos > items.size())) return;
			items.remove(pos - 1);
			adapter.notifyItemRemoved(pos);
			saveItems();
		});
		return h;
	}

	/** Drags the action rows (only) up and down; saved once dropped. */
	private final class DragCallback extends ItemTouchHelper.Callback {
		private boolean moved;

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
			if ((f < 1) || (t < 1) || (f > items.size()) || (t > items.size())) return false;
			Collections.swap(items, f - 1, t - 1);
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
