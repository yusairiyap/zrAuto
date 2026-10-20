package me.aap.fermata.ui.home;

import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.Calendar;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.action.CarNav;
import me.aap.fermata.addon.power.PowerSaver;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.BehindBarsLayers;
import me.aap.utils.app.App;
import me.aap.utils.pref.PreferenceStore;

/**
 * Everything the Home tab is made of, so the fragment only has to pass its lifecycle on: the
 * background picture, the scrolling grid of widgets (scrolling down or sideways), the greeting, the
 * edit button in the top right corner and what it opens, and the status bar following the
 * background. Implements what the widgets may ask of their tab ({@link HomeHost}).
 */
public final class HomeScreen implements HomeHost, HomeGridLayout.Listener, PreferenceStore.Listener {
	private final MainActivityDelegate a;
	private final MainActivityPrefs prefs;
	private final HomePalette palette;
	private final HomeModel model;
	private final FrameLayout root;
	private final HomeBackgroundView bg;
	private final HomeGridLayout grid;
	private final BehindBarsLayers behind;
	private final LinearLayout greeting;
	private final TextView greetTitle;
	private final TextView greetDate;
	private final LinearLayout chipBar;
	private final TextView editBtn;
	private final TextView[] editChips = new TextView[3];
	private final LinearLayout empty;
	@Nullable
	private ViewGroup scroller;
	private boolean vertical = true;
	private boolean editing;
	private boolean shown;
	private boolean entered;
	private boolean barsApplied;
	@Nullable
	private Boolean savedLightIcons;
	private int topColor;
	private int bgVersion = -1;
	private int bgBlur = -1;
	private int bgGeneration;
	private final Runnable loadBg = () -> loadBackground(true);

	public HomeScreen(MainActivityDelegate a) {
		this.a = a;
		this.prefs = a.getPrefs();
		this.palette = new HomePalette(a.getContext());
		this.model = new HomeModel(prefs);
		Context ctx = palette.ctx;

		root = new FrameLayout(ctx);
		root.setClipChildren(false);
		root.setClipToPadding(false);
		bg = new HomeBackgroundView(ctx, palette);
		root.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		behind = new BehindBarsLayers(a, root, bg);

		grid = new HomeGridLayout(ctx, this, this);
		grid.bind(model);

		greeting = new LinearLayout(ctx);
		greeting.setOrientation(LinearLayout.VERTICAL);
		greeting.setPadding(HomeUi.dp(ctx, 20), HomeUi.dp(ctx, 16), HomeUi.dp(ctx, 76), HomeUi.dp(ctx, 8));
		greetTitle = new TextView(ctx);
		greetTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
		greetTitle.setTypeface(Typeface.DEFAULT_BOLD);
		greetTitle.setSingleLine(true);
		greetDate = new TextView(ctx);
		greetDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		greeting.addView(greetTitle);
		greeting.addView(greetDate);

		chipBar = new LinearLayout(ctx);
		chipBar.setOrientation(LinearLayout.HORIZONTAL);
		chipBar.setGravity(Gravity.CENTER_VERTICAL);
		chipBar.setPadding(HomeUi.dp(ctx, 4), HomeUi.dp(ctx, 4), HomeUi.dp(ctx, 4), HomeUi.dp(ctx, 4));
		chipBar.setBackground(HomeUi.round((palette.panelFill & 0xFFFFFF) | 0xE6000000, HomeUi.dp(ctx, 26)));
		chipBar.setElevation(HomeUi.dp(ctx, 6));
		editBtn = pillChip(R.drawable.edit, 0, false, () -> setEditing(true));
		editBtn.setContentDescription(ctx.getString(R.string.home_edit));
		editChips[0] = pillChip(R.drawable.home_add, R.string.home_add, false, this::showAdd);
		editChips[1] = pillChip(R.drawable.home_image, R.string.home_background, false,
				() -> HomePickers.showBackground(this));
		editChips[2] = pillChip(R.drawable.done, R.string.home_done, true, () -> setEditing(false));
		chipBar.addView(editBtn);
		for (TextView c : editChips) chipBar.addView(c);
		FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
		clp.setMargins(0, HomeUi.dp(ctx, 10), HomeUi.dp(ctx, 12), 0);
		root.addView(chipBar, clp);

		empty = new LinearLayout(ctx);
		empty.setOrientation(LinearLayout.VERTICAL);
		empty.setGravity(Gravity.CENTER);
		TextView et = new TextView(ctx);
		et.setText(R.string.home_empty);
		et.setTextSize(15);
		et.setGravity(Gravity.CENTER);
		et.setTextColor(palette.textSecondary);
		empty.addView(et);
		TextView ea = pillChip(R.drawable.home_add, R.string.home_add_widget, true, this::showAdd);
		LinearLayout.LayoutParams ealp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		ealp.topMargin = HomeUi.dp(ctx, 14);
		empty.addView(ea, ealp);
		empty.setVisibility(View.GONE);
		root.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

		applyEditUi(false);
		topColor = defaultTopColor();
	}

	private static boolean editOnShow;

	/** Settings > Home > Edit widgets: the next time Home shows, it comes up in edit mode. */
	public static void editOnNextShow() {
		editOnShow = true;
	}

	/** Settings > Home: choose the background picture. */
	public static void pickBackground(MainActivityDelegate a) {
		HomeBackground.pick(a, null);
	}

	/** Settings > Home: back to the plain background. */
	public static void clearBackground(MainActivityDelegate a) {
		HomeBackground.clear(a);
	}

	public View getRoot() {
		return root;
	}

	public void onViewCreated() {
		behind.attach();
		prefs.addBroadcastListener(this);
		root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (((r - l) != (or - ol)) || ((b - t) != (ob - ot))) root.post(this::applyArrangement);
		});
		applyArrangement();
		applyGreeting();
		updateEmpty();
		loadBackground(false);
	}

	public void onDestroyView() {
		prefs.removeBroadcastListener(this);
		behind.detach();
		root.removeCallbacks(loadBg);
		model.save();
		HomeModal.dismissNow();
		bgGeneration++;
		if (barsApplied) systemBars(false);
	}

	// ---------------------------------------------------------------------------------------------
	// Lifecycle from the fragment

	public void onShown() {
		if (shown) return;
		shown = true;
		behind.setActive(true);
		systemBars(true);
		applyGreeting();
		if (model.reloadIfChanged()) {
			grid.sync();
			updateEmpty();
		}
		root.post(() -> {
			if (shown) grid.playEntrance();
		});
		entered = true;
		if (editOnShow) {
			editOnShow = false;
			root.postDelayed(() -> {
				if (shown) setEditing(true);
			}, 500);
		}
	}

	public void onHidden() {
		if (!shown) return;
		shown = false;
		if (editing) setEditing(false);
		model.save();
		HomeModal.dismissNow();
		behind.setActive(false);
		systemBars(false);
	}

	/** Back leaves edit mode first. */
	public boolean onBack() {
		if (grid.closePanels()) return true;
		if (!editing) return false;
		setEditing(false);
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// HomeHost

	@Override
	public MainActivityDelegate activity() {
		return a;
	}

	@Override
	public HomePalette palette() {
		return palette;
	}

	@Override
	public boolean isEditing() {
		return editing;
	}

	@Override
	public boolean isCar() {
		return CarNav.isCarConnected();
	}

	@Override
	public int defaultStyle() {
		return Math.max(0, Math.min(2, prefs.getIntPref(MainActivityPrefs.HOME_CARD_STYLE))) + 1;
	}

	@Override
	public void optionsChanged() {
		model.saveLater();
	}

	@Override
	public void run(Action action) {
		action.getHandler().handle(a.getMediaSessionCallback(), a, SystemClock.uptimeMillis());
	}

	@Override
	public void open(int fragmentId) {
		a.exitVideoMode();
		a.showFragment(fragmentId);
	}

	@Override
	public boolean animate() {
		if (!prefs.getBooleanPref(MainActivityPrefs.HOME_ANIMATE)) return false;
		try {
			return !PowerSaver.get().isActive();
		} catch (RuntimeException ex) {
			return true;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// HomeGridLayout.Listener

	@Override
	public void onChanged() {
		updateEmpty();
	}

	@Override
	public void onEditRequested() {
		setEditing(true);
	}

	// ---------------------------------------------------------------------------------------------
	// Preferences

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> changed) {
		if (changed.contains(MainActivityPrefs.HOME_LAYOUT) && model.reloadIfChanged()) {
			grid.sync();
			updateEmpty();
		}
		if (changed.contains(MainActivityPrefs.HOME_ARRANGE) || changed.contains(MainActivityPrefs.HOME_COLUMNS) ||
				changed.contains(MainActivityPrefs.HOME_GAP)) {
			applyArrangement();
		}
		if (changed.contains(MainActivityPrefs.HOME_CARD_STYLE)) grid.restyle();
		if (changed.contains(MainActivityPrefs.HOME_GREETING)) applyGreeting();
		if (changed.contains(MainActivityPrefs.HOME_BG_DIM)) bg.setDim(prefs.getIntPref(MainActivityPrefs.HOME_BG_DIM));
		if (changed.contains(MainActivityPrefs.HOME_BG_VERSION) || changed.contains(MainActivityPrefs.HOME_BG_BLUR)) {
			// A slider sends many values: only the last one is worked on.
			root.removeCallbacks(loadBg);
			root.postDelayed(loadBg, 350);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Edit mode

	private void setEditing(boolean on) {
		if (editing == on) return;
		editing = on;
		grid.setEditing(on);
		applyEditUi(true);
		if (!on) model.save();
	}

	private void applyEditUi(boolean animated) {
		if (animated && animate()) TransitionManager.beginDelayedTransition(chipBar);
		editBtn.setVisibility(editing ? View.GONE : View.VISIBLE);
		for (TextView c : editChips) c.setVisibility(editing ? View.VISIBLE : View.GONE);
	}

	private TextView pillChip(@DrawableRes int icon, @StringRes int label, boolean primary, Runnable onClick) {
		Context ctx = palette.ctx;
		TextView t = new TextView(ctx);
		if (label != 0) t.setText(label);
		t.setTextSize(13.5f);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setGravity(Gravity.CENTER_VERTICAL);
		t.setSingleLine(true);
		t.setMinHeight(HomeUi.dp(ctx, 40));
		t.setMinWidth(HomeUi.dp(ctx, 40));
		t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
		int fg = primary ? palette.onAccent : palette.textPrimary;
		t.setTextColor(fg);
		t.setCompoundDrawableTintList(ColorStateList.valueOf(primary ? palette.onAccent : palette.icon));
		t.setCompoundDrawablePadding((label != 0) ? HomeUi.dp(ctx, 6) : 0);
		int padH = HomeUi.dp(ctx, (label != 0) ? 14 : 8);
		t.setPadding(padH, 0, padH, 0);
		t.setBackground(HomeUi.pressable(palette.chipRipple, primary ? palette.accent : 0, HomeUi.dp(ctx, 22)));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> onClick.run());
		return t;
	}

	private void showAdd() {
		HomePickers.showAdd(this, type -> {
			HomeModel.Cell c = model.add(type);
			grid.sync();
			updateEmpty();
			grid.changed();
			model.save();
			scrollToEnd(c);
		});
	}

	/** Brings the cell just added into view. */
	private void scrollToEnd(HomeModel.Cell c) {
		ViewGroup s = scroller;
		if (s == null) return;
		s.post(() -> {
			if (vertical) ((ScrollView) s).smoothScrollTo(0, grid.getBottom());
			else ((HorizontalScrollView) s).smoothScrollTo(grid.getRight(), 0);
		});
	}

	private void updateEmpty() {
		boolean none = model.cells().isEmpty();
		if (none == (empty.getVisibility() == View.VISIBLE)) return;
		if (none) {
			empty.setAlpha(0f);
			empty.setVisibility(View.VISIBLE);
			empty.animate().alpha(1f).setDuration(240).start();
		} else {
			empty.setVisibility(View.GONE);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Arrangement

	/** Scrolling down or sideways, and how many fit across, from the screen's shape and the settings. */
	private void applyArrangement() {
		int w = root.getWidth();
		int h = root.getHeight();
		if ((w == 0) || (h == 0)) return;
		Context ctx = palette.ctx;
		float d = ctx.getResources().getDisplayMetrics().density;
		int mode = prefs.getIntPref(MainActivityPrefs.HOME_ARRANGE);
		boolean landscape = w > h;
		boolean down = (mode == 1) || ((mode == 0) && !(landscape && ((h / d) < 600)));
		int count = prefs.getIntPref(MainActivityPrefs.HOME_COLUMNS);
		if (count <= 0) {
			count = down ? Math.max(4, Math.min(8, (int) (w / d / 90f))) : Math.max(2, Math.min(5, (int) (h / d / 105f)));
		}
		count = down ? Math.max(2, Math.min(8, count)) : Math.max(2, Math.min(5, count));
		int gap = HomeUi.dp(ctx, Math.max(4, Math.min(20, prefs.getIntPref(MainActivityPrefs.HOME_GAP))));
		if ((scroller == null) || (down != vertical)) buildScroller(down);
		int side = HomeUi.dp(ctx, 12);
		if (vertical) grid.setPadding(side, 0, side, HomeUi.dp(ctx, 16));
		else grid.setPadding(side, HomeUi.dp(ctx, 58), side, HomeUi.dp(ctx, 8));
		grid.setArrangement(vertical, count, gap);
		applyGreeting();
	}

	private void buildScroller(boolean down) {
		Context ctx = palette.ctx;
		if (scroller != null) {
			root.removeView(scroller);
			if (grid.getParent() instanceof ViewGroup p) p.removeView(grid);
			if (greeting.getParent() instanceof ViewGroup p) p.removeView(greeting);
		}
		vertical = down;
		ViewGroup s;
		if (down) {
			ScrollView sv = new ScrollView(ctx);
			sv.setVerticalScrollBarEnabled(false);
			sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
			LinearLayout col = new LinearLayout(ctx);
			col.setOrientation(LinearLayout.VERTICAL);
			col.setClipChildren(false);
			col.addView(greeting, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			sv.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			s = sv;
		} else {
			HorizontalScrollView hv = new HorizontalScrollView(ctx);
			hv.setHorizontalScrollBarEnabled(false);
			hv.setOverScrollMode(View.OVER_SCROLL_NEVER);
			hv.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.MATCH_PARENT));
			s = hv;
		}
		s.setClipChildren(false);
		root.addView(s, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		a.insetScrollableContent(s);
		grid.setScroller(s);
		scroller = s;
	}

	private void applyGreeting() {
		boolean show = vertical && prefs.getBooleanPref(MainActivityPrefs.HOME_GREETING);
		greeting.setVisibility(show ? View.VISIBLE : View.GONE);
		if (!show) return;
		Context ctx = palette.ctx;
		int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
		greetTitle.setText((hour < 5) ? R.string.home_greet_night : (hour < 12) ? R.string.home_greet_morning :
				(hour < 18) ? R.string.home_greet_afternoon : R.string.home_greet_evening);
		greetDate.setText(DateUtils.formatDateTime(ctx, System.currentTimeMillis(),
				FORMAT_SHOW_WEEKDAY | FORMAT_SHOW_DATE));
		boolean onPicture = bg.hasImage();
		greetTitle.setTextColor(onPicture ? 0xFFFFFFFF : palette.textPrimary);
		greetDate.setTextColor(onPicture ? 0xCCFFFFFF : palette.textSecondary);
	}

	// ---------------------------------------------------------------------------------------------
	// Background and the status bar

	private void loadBackground(boolean animate) {
		int version = prefs.getIntPref(MainActivityPrefs.HOME_BG_VERSION);
		int blur = prefs.getIntPref(MainActivityPrefs.HOME_BG_BLUR);
		bg.setDim(prefs.getIntPref(MainActivityPrefs.HOME_BG_DIM));
		if ((version == bgVersion) && (blur == bgBlur)) return;
		bgVersion = version;
		bgBlur = blur;
		int gen = ++bgGeneration;
		Context ctx = a.getContext();
		if (!HomeBackground.exists(ctx)) {
			bg.setImage(null, animate);
			topColor = defaultTopColor();
			applyGreeting();
			if (shown) systemBars(true);
			return;
		}
		int sw = ctx.getResources().getDisplayMetrics().widthPixels;
		int sh = ctx.getResources().getDisplayMetrics().heightPixels;
		App.get().getExecutor().submitTask(() -> HomeBackground.load(ctx, blur, sw, sh))
				.main().onSuccess(l -> {
					if (gen != bgGeneration) return;
					if (l == null) {
						bg.setImage(null, animate);
						topColor = defaultTopColor();
					} else {
						bg.setImage(l.bitmap(), animate);
						// What shows at the top once dimmed and shaded.
						float dark = 1f - (1f - prefs.getIntPref(MainActivityPrefs.HOME_BG_DIM) / 100f) * 0.4f;
						topColor = HomeUi.mix(l.topColor(), 0xFF000000, dark);
					}
					applyGreeting();
					if (shown) systemBars(true);
				});
	}

	private int defaultTopColor() {
		int base = palette.background | 0xFF000000;
		return HomeUi.mix(base, palette.accent, palette.light ? 0.16f : 0.2f);
	}

	/**
	 * The status bar takes the colour of the background's top edge while Home shows (from Android 15
	 * the background itself runs under it) and its icons turn light or dark to read on it; leaving
	 * puts the app's own back.
	 */
	private void systemBars(boolean on) {
		if (a.isCarActivity()) return;
		Window win = a.getWindow();
		if (win == null) return;
		WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(win, win.getDecorView());
		if (on) {
			if (!barsApplied) {
				savedLightIcons = ic.isAppearanceLightStatusBars();
				barsApplied = true;
			}
			int c = topColor | 0xFF000000;
			win.setStatusBarColor(c);
			ic.setAppearanceLightStatusBars(HomeUi.isLight(c));
		} else if (barsApplied) {
			barsApplied = false;
			a.matchStatusBarToBackground();
			if (savedLightIcons != null) ic.setAppearanceLightStatusBars(savedLightIcons);
		}
	}
}
