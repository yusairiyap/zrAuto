package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.annotation.SuppressLint;
import android.content.Context;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.RippleDrawable;
import android.view.animation.PathInterpolator;
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
	private int ripple;
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
	private ValueAnimator previewAnim;
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
		refreshGridChips(false);
		refreshPreview(false);
	}

	private void saveItems() {
		prefs().applyStringPref(pref(MainActivityPrefs.AIO_ITEMS), AllInOneMenu.format(items));
		refreshPreview(true);
	}

	private void refreshPreview() {
		refreshPreview(false);
	}

	/**
	 * Builds the preview again; {@code animate}: the old card fades out as the new one fades in, and
	 * the room for it glides to its new height (a grid size change, an action added or removed).
	 */
	private void refreshPreview(boolean animate) {
		FrameLayout h = previewHolder;
		if (h == null) return;
		View next = AllInOneMenu.preview(getActivityDelegate(), palette, items, columns());
		FrameLayout.LayoutParams lp =
				new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
		if (previewAnim != null) previewAnim.cancel();
		// A change while one is still fading: only the newest of the old ones fades out.
		while (h.getChildCount() > 1) h.removeViewAt(0);
		View old = (h.getChildCount() > 0) ? h.getChildAt(0) : null;
		if (!animate || (old == null) || !h.isLaidOut()) {
			h.removeAllViews();
			h.addView(next, lp);
			return;
		}

		int fromH = h.getHeight();
		next.setAlpha(0f);
		h.addView(next, lp);
		next.measure(View.MeasureSpec.makeMeasureSpec(h.getWidth(), View.MeasureSpec.AT_MOST),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int toH = next.getMeasuredHeight();
		ViewGroup.LayoutParams hlp = h.getLayoutParams();
		hlp.height = fromH;
		h.setLayoutParams(hlp);

		old.animate().cancel();
		old.animate().alpha(0f).setDuration(140).withEndAction(() -> h.removeView(old)).start();
		next.animate().alpha(1f).setStartDelay(80).setDuration(220).start();
		ValueAnimator va = ValueAnimator.ofInt(fromH, toH);
		va.setDuration(260);
		va.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
		va.addUpdateListener(x -> {
			hlp.height = (int) x.getAnimatedValue();
			h.setLayoutParams(hlp);
		});
		va.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				hlp.height = WRAP_CONTENT;
				h.setLayoutParams(hlp);
			}
		});
		previewAnim = va;
		va.start();
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
		mask.setCornerRadii(fill.getCornerRadii());
		if (fill.getCornerRadii() == null) mask.setCornerRadius(fill.getCornerRadius());
		return new RippleDrawable(ColorStateList.valueOf(ripple), fill, mask);
	}

	private GradientDrawable rounded(int color, int radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(color);
		d.setCornerRadius(dp(radiusDp));
		return d;
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
			GradientDrawable fill = rounded(tileFill, 16);
			c.setTag(R.id.all_in_one_fragment, fill);
			c.setBackground(pressable(fill));
			c.setOnClickListener(v -> {
				if (columns() == cols) return;
				prefs().applyIntPref(pref(MainActivityPrefs.AIO_COLUMNS), cols);
				refreshGridChips(true);
				refreshPreview(true);
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
		row.setBackground(pressable(rounded(tileFill, 16)));
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
		t.setBackground(pressable(rounded(filled ? accent : tileFill, 24)));
		t.setClickable(true);
		t.setFocusable(true);
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

		TextView name = text(0, 15, textPrimary, true);
		name.setPadding(dp(12), 0, dp(8), 0);
		name.setMaxLines(2);
		row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		ImageView remove = new ImageView(palette);
		remove.setImageResource(me.aap.utils.R.drawable.close);
		remove.setImageTintList(ColorStateList.valueOf(textSecondary));
		remove.setPadding(dp(10), dp(10), dp(10), dp(10));
		remove.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), null, null));
		remove.setClickable(true);
		row.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(44)));

		Holder h = new Holder(row, handle, icon, name, remove);
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
			return super.interpolateOutOfBoundsScroll(rv, viewSize, viewSizeOutOfBounds, totalSize,
					msSinceStartScroll);
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
