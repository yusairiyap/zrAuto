package me.aap.fermata.ui.home;

import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY;

import android.animation.ValueAnimator;
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
import android.view.animation.DecelerateInterpolator;
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
import me.aap.fermata.ui.view.InfoOverlayView;
import me.aap.utils.app.App;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.view.NavBarView;

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
	private final HomeBackgroundView base;
	private final LinearLayout greeting;
	private final TextView greetTitle;
	private final TextView greetDate;
	private final LinearLayout chipBar;
	/** The info overlay's chips on the greeting's row, when Settings > Home asks for them. */
	private final InfoOverlayView topInfo;
	private final TextView editBtn;
	private final TextView[] editChips = new TextView[4];
	private final LinearLayout empty;
	@Nullable
	private ViewGroup scroller;
	@Nullable
	private HomeFadeLayout fade;
	private final int[] insetLoc = new int[2];
	private final int[] insetLoc2 = new int[2];
	@Nullable
	private ValueAnimator insetAnim;
	private final View.OnLayoutChangeListener insetSync = (v, l, t, r, b, ol, ot, or, ob) -> syncInsets(true);
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
	private final HomeNowPlayingBg nowBg;
	private final Runnable blurCover;
	@Nullable
	private ValueAnimator bgFade;

	public HomeScreen(MainActivityDelegate a) {
		this.a = a;
		this.prefs = a.getPrefs();
		this.palette = new HomePalette(a.getContext());
		this.model = new HomeModel(prefs, a.isCarActivity());
		Context ctx = palette.ctx;

		root = new FrameLayout(ctx);
		root.setClipChildren(false);
		root.setClipToPadding(false);
		// Opaque from the very first frame: the tab crossfades in over the one before it, and until the
		// background (which comes in a little later, see fadeBackgroundIn) is there the window's dark
		// colour showed through as a veil that then lifted.
		root.setBackgroundColor(palette.background | 0xFF000000);
		// The plain gradient on its own layer that reaches under the bars with the tab, never faded: the
		// picture's layer fades in over it later, so the status and navigation bar areas are never bare.
		base = new HomeBackgroundView(ctx, palette);
		root.addView(base, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		bg = new HomeBackgroundView(ctx, palette);
		root.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		behind = new BehindBarsLayers(a, root, base, bg);
		nowBg = new HomeNowPlayingBg(a, (bm, top) -> {
			if (bm == null) {
				bg.setImage(null, true);
				topColor = defaultTopColor();
			} else {
				bg.setImage(bm, true);
				float dark = 1f - (1f - prefs.getIntPref(MainActivityPrefs.HOME_BG_DIM) / 100f) * 0.4f;
				topColor = HomeUi.mix(top, 0xFF000000, dark);
			}
			applyGreeting();
			if (shown) systemBars(true);
		});
		blurCover = () -> nowBg.setBlur(prefs.getIntPref(MainActivityPrefs.HOME_BG_BLUR));

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
		editChips[2] = pillChip(R.drawable.settings, R.string.settings, false, () -> {
			a.exitVideoMode();
			a.showFragment(R.id.home_settings_fragment, car() ? me.aap.fermata.ui.fragment.HomeSettingsFragment.AA_FROM_HOME :
					me.aap.fermata.ui.fragment.HomeSettingsFragment.PHONE_FROM_HOME);
		});
		editChips[3] = pillChip(R.drawable.done, R.string.home_done, true, () -> setEditing(false));
		chipBar.addView(editBtn);
		for (TextView c : editChips) chipBar.addView(c);
		FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
		clp.setMargins(0, HomeUi.dp(ctx, 10), HomeUi.dp(ctx, 12), 0);
		root.addView(chipBar, clp);

		topInfo = new InfoOverlayView(ctx);
		topInfo.setVisibility(View.GONE);
		FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
		ilp.setMargins(0, HomeUi.dp(ctx, 15), HomeUi.dp(ctx, 68), 0);
		root.addView(topInfo, ilp);
		root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if ((r - l) != (or - ol)) fitTopInfo();
		});

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
	/** The Home page that is alive (there is at most one), for Settings to reach. */
	@Nullable
	private static HomeScreen live;

	/**
	 * What Settings > Home > Look shows: 0 to 2 when every widget follows it, 3 (Custom) when some
	 * widget has a look of its own.
	 */
	public static int styleShown(MainActivityPrefs prefs) {
		HomeScreen h = live;
		boolean custom = (h != null) ? h.model.anyStyleOverride() : new HomeModel(prefs, false).anyStyleOverride();
		return custom ? 3 : Math.max(0, Math.min(2, prefs.getIntPref(MainActivityPrefs.HOME_CARD_STYLE)));
	}

	/** A look picked in Settings: every widget follows it, whatever look it had of its own. */
	public static void setAllStyles(MainActivityPrefs prefs, int style) {
		prefs.applyIntPref(MainActivityPrefs.HOME_CARD_STYLE, style);
		HomeScreen h = live;
		if (h != null) {
			h.model.clearStyles();
			h.model.saveLater();
			h.grid.restyle();
		} else {
			for (boolean car : new boolean[]{false, true}) {
				HomeModel m = new HomeModel(prefs, car);
				m.clearStyles();
				m.save();
			}
		}
	}

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
		live = this;
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

	/** True when the background is the cover of what plays (Settings > Home) instead of a picture. */
	private boolean followsCover() {
		return prefs.getIntPref(MainActivityPrefs.HOME_BG_SOURCE) == 1;
	}

	/** The background's source changed (or Home came back): follow the cover, or load the picture. */
	private void applyBackgroundSource() {
		if (followsCover()) {
			bgGeneration++;
			nowBg.forget();
			if (shown) nowBg.start(prefs.getIntPref(MainActivityPrefs.HOME_BG_BLUR));
		} else {
			nowBg.stop();
			bgVersion = -1;
			loadBackground(true);
		}
	}

	public void onDestroyView() {
		if (live == this) live = null;
		nowBg.stop();
		if (bgFade != null) bgFade.cancel();
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
		fadeBackgroundIn();
		if (followsCover()) nowBg.start(prefs.getIntPref(MainActivityPrefs.HOME_BG_BLUR));
		applyGreeting();
		if (model.reloadIfChanged()) {
			grid.sync();
			updateEmpty();
		}
		// Hidden right now, not in the next frame: otherwise the widgets show once, then vanish and fade in.
		grid.prepareEntrance();
		if (greeting.getVisibility() == View.VISIBLE) {
			greeting.animate().cancel();
			greeting.setAlpha(animate() ? 0f : 1f);
		}
		root.post(() -> {
			if (!shown) return;
			grid.playEntrance();
			greeting.animate().alpha(1f).setDuration(380).setInterpolator(new DecelerateInterpolator(1.8f)).start();
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
		nowBg.stop();
		fadeBackgroundOut();
	}

	/**
	 * Leaving: the background fades away with the tab instead of vanishing when its extension under the
	 * bars is given back (that and the status bar going back at once made the bars flash).
	 */
	private void fadeBackgroundOut() {
		if (bgFade != null) bgFade.cancel();
		if (!animate()) {
			behind.setActive(false);
			bgShown(1f);
			systemBars(false);
			return;
		}
		ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
		a.setDuration(260);
		a.addUpdateListener(v -> bgShown((float) v.getAnimatedValue()));
		a.addListener(new android.animation.AnimatorListenerAdapter() {
			private boolean cancelled;

			@Override
			public void onAnimationCancel(android.animation.Animator an) {
				cancelled = true;
			}

			@Override
			public void onAnimationEnd(android.animation.Animator an) {
				if (cancelled || shown) return;
				behind.setActive(false);
				bgShown(1f);
				systemBars(false);
			}
		});
		bgFade = a;
		a.start();
	}

	/**
	 * The background is there from the first frame, like the Music tab's: its layers keep the margins
	 * that reach under the bars from the last visit (and on the very first one the tab is still almost
	 * transparent in the crossfade when they are applied), so nothing needs holding back and fading in.
	 */
	private void fadeBackgroundIn() {
		if (bgFade != null) bgFade.cancel();
		bgShown(1f);
	}

	/** Whether this is the car screen: it has its own layout, spacing, greeting switch and top overlay switch. */
	private boolean car() {
		return a.isCarActivity();
	}

	/** Fades the picture layer (the plain gradient under it stays). */
	private void bgShown(float f) {
		float c = Math.max(0f, Math.min(1f, f));
		bg.setAlpha(c);
		bg.setVisibility(c <= 0f ? View.INVISIBLE : View.VISIBLE);
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
		if ((changed.contains(MainActivityPrefs.HOME_LAYOUT) || changed.contains(MainActivityPrefs.HOME_LAYOUT_AA)) &&
				model.reloadIfChanged()) {
			grid.sync();
			updateEmpty();
		}
		if (changed.contains(MainActivityPrefs.HOME_ARRANGE) || changed.contains(MainActivityPrefs.HOME_COLUMNS) ||
				changed.contains(MainActivityPrefs.HOME_GAP) || changed.contains(MainActivityPrefs.HOME_ARRANGE_AA) ||
				changed.contains(MainActivityPrefs.HOME_COLUMNS_AA) || changed.contains(MainActivityPrefs.HOME_GAP_AA)) {
			applyArrangement();
		}
		if (changed.contains(MainActivityPrefs.HOME_CARD_STYLE)) grid.restyle();
		if (changed.contains(MainActivityPrefs.HOME_GREETING) || changed.contains(MainActivityPrefs.HOME_GREETING_AA) ||
				changed.contains(MainActivityPrefs.HOME_GREET_MORNING) ||
				changed.contains(MainActivityPrefs.HOME_GREET_AFTERNOON) ||
				changed.contains(MainActivityPrefs.HOME_GREET_EVENING) || changed.contains(MainActivityPrefs.HOME_GREET_NIGHT)) {
			applyGreeting();
		}
		if (changed.contains(MainActivityPrefs.HOME_TOP_OVERLAY) || changed.contains(MainActivityPrefs.HOME_TOP_OVERLAY_AA) ||
				!java.util.Collections.disjoint(MainActivityPrefs.infoOverlayPrefs(), changed)) {
			applyTopInfo();
		}
		if (changed.contains(MainActivityPrefs.HOME_BG_DIM)) bg.setDim(prefs.getIntPref(MainActivityPrefs.HOME_BG_DIM));
		if (changed.contains(MainActivityPrefs.HOME_BG_SOURCE)) {
			applyBackgroundSource();
		} else if (followsCover()) {
			if (changed.contains(MainActivityPrefs.HOME_BG_BLUR)) {
				root.removeCallbacks(blurCover);
				root.postDelayed(blurCover, 350);
			}
		} else if (changed.contains(MainActivityPrefs.HOME_BG_VERSION) || changed.contains(MainActivityPrefs.HOME_BG_BLUR)) {
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
		// On a phone the four chips with their words are wider than the screen (the done button went off its
		// edge): Background and Settings keep only their icons there.
		Context ctx = palette.ctx;
		int wPx = (root.getWidth() > 0) ? root.getWidth() : ctx.getResources().getDisplayMetrics().widthPixels;
		boolean narrow = (wPx / ctx.getResources().getDisplayMetrics().density) < 520f;
		int[] labels = {R.string.home_add, R.string.home_background, R.string.settings, R.string.home_done};
		for (int i = 1; i <= 2; i++) {
			TextView c = editChips[i];
			c.setText(narrow ? "" : ctx.getString(labels[i]));
			c.setCompoundDrawablePadding(narrow ? 0 : HomeUi.dp(ctx, 6));
			int padH = HomeUi.dp(ctx, narrow ? 8 : 14);
			c.setPadding(padH, 0, padH, 0);
			c.setContentDescription(ctx.getString(labels[i]));
		}
		applyTopInfo();
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
		int mode = prefs.getIntPref(car() ? MainActivityPrefs.HOME_ARRANGE_AA : MainActivityPrefs.HOME_ARRANGE);
		boolean landscape = w > h;
		boolean down = (mode == 1) || ((mode == 0) && !(landscape && ((h / d) < 600)));
		int count = prefs.getIntPref(car() ? MainActivityPrefs.HOME_COLUMNS_AA : MainActivityPrefs.HOME_COLUMNS);
		if (count <= 0) {
			count = down ? Math.max(4, Math.min(8, (int) (w / d / 90f))) : Math.max(2, Math.min(5, (int) (h / d / 105f)));
		}
		count = down ? Math.max(2, Math.min(8, count)) : Math.max(2, Math.min(5, count));
		int gap = HomeUi.dp(ctx, Math.max(4, Math.min(20,
				prefs.getIntPref(car() ? MainActivityPrefs.HOME_GAP_AA : MainActivityPrefs.HOME_GAP))));
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
			stopInsets();
			root.removeView(fade);
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
		s.setClipToPadding(false);
		fade = new HomeFadeLayout(ctx);
		fade.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		fade.follow(s);
		root.addView(fade, 2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		if (!down) {
			// Sideways the greeting stays put at the top left, in the room the grid leaves there.
			root.addView(greeting, 3, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
		}
		grid.setScroller(s);
		scroller = s;
		startInsets(s);
	}

	// ---------------------------------------------------------------------------------------------
	// Insets

	/**
	 * Home keeps its own room: nothing at the top (the title bar is gone here, so there is nothing to
	 * clear, and following it fading out made the page jump up once it was gone) and, at the bottom,
	 * only a bottom navigation pill. The control panel is covered here, so it needs none either.
	 * The room is right from the first frame and glides when the pill comes or goes.
	 */
	private void startInsets(ViewGroup s) {
		s.setPadding(0, 0, 0, 0);
		NavBarView nb = a.getNavBar();
		if (nb != null) nb.addOnLayoutChangeListener(insetSync);
		s.addOnLayoutChangeListener(insetSync);
		s.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override
			public void onViewAttachedToWindow(View v) {
				syncInsets(false);
			}

			@Override
			public void onViewDetachedFromWindow(View v) {
			}
		});
		if (s.isAttachedToWindow()) syncInsets(false);
	}

	private void stopInsets() {
		if (insetAnim != null) insetAnim.cancel();
		insetAnim = null;
		NavBarView nb = a.getNavBar();
		if (nb != null) nb.removeOnLayoutChangeListener(insetSync);
		if (scroller != null) scroller.removeOnLayoutChangeListener(insetSync);
	}

	private int navOverlap(View s) {
		NavBarView nb = a.getNavBar();
		if ((nb == null) || (nb.getVisibility() != View.VISIBLE) || !nb.isBottom() || a.isBarsHidden() ||
				(nb.getHeight() == 0)) {
			return 0;
		}
		s.getLocationOnScreen(insetLoc);
		nb.getLocationOnScreen(insetLoc2);
		float dy = (a.getScrollBars() != null) ? a.getScrollBars().getNavDy() : 0f;
		return Math.max(0, insetLoc[1] + s.getHeight() - Math.round(insetLoc2[1] - dy));
	}

	private void syncInsets(boolean animate) {
		ViewGroup s = scroller;
		if ((s == null) || !s.isAttachedToWindow() || (s.getHeight() == 0)) return;
		int want = navOverlap(s);
		int now = s.getPaddingBottom();
		if (insetAnim != null) {
			if (insetTarget == want) return;
			insetAnim.cancel();
			insetAnim = null;
			now = s.getPaddingBottom();
		}
		if (now == want) return;
		insetTarget = want;
		if (!animate || !shown || !animate()) {
			s.setPadding(0, 0, 0, want);
			return;
		}
		ValueAnimator va = ValueAnimator.ofInt(now, want);
		va.setDuration(240);
		va.addUpdateListener(an -> s.setPadding(0, 0, 0, (Integer) an.getAnimatedValue()));
		va.addListener(new android.animation.AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(android.animation.Animator an) {
				if (insetAnim == va) insetAnim = null;
			}
		});
		insetAnim = va;
		va.start();
	}

	private int insetTarget;

	private void applyGreeting() {
		boolean show = prefs.getBooleanPref(car() ? MainActivityPrefs.HOME_GREETING_AA : MainActivityPrefs.HOME_GREETING);
		greeting.setVisibility(show ? View.VISIBLE : View.GONE);
		if (!show) {
			applyTopInfo();
			return;
		}
		Context ctx = palette.ctx;
		greetTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, vertical ? 28 : 22);
		greetDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, vertical ? 14 : 13);
		greeting.setPadding(HomeUi.dp(ctx, 20), HomeUi.dp(ctx, vertical ? 16 : 8), HomeUi.dp(ctx, vertical ? 76 : 20),
				HomeUi.dp(ctx, vertical ? 8 : 0));
		greetTitle.setText(greetingText(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)));
		greetDate.setText(DateUtils.formatDateTime(ctx, System.currentTimeMillis(),
				FORMAT_SHOW_WEEKDAY | FORMAT_SHOW_DATE));
		boolean onPicture = bg.hasImage();
		greetTitle.setTextColor(onPicture ? 0xFFFFFFFF : palette.textPrimary);
		greetDate.setTextColor(onPicture ? 0xCCFFFFFF : palette.textSecondary);
		applyTopInfo();
	}

	/**
	 * What the greeting says at {@code hour}: morning from 5, afternoon from 12, evening from 17, night from 21
	 * (so 10:47 pm is night); the words in Settings > Home if there are any, else the built-in ones.
	 */
	private String greetingText(int hour) {
		Context ctx = palette.ctx;
		int res;
		var pref = MainActivityPrefs.HOME_GREET_NIGHT;
		if ((hour >= 5) && (hour < 12)) {
			res = R.string.home_greet_morning;
			pref = MainActivityPrefs.HOME_GREET_MORNING;
		} else if ((hour >= 12) && (hour < 17)) {
			res = R.string.home_greet_afternoon;
			pref = MainActivityPrefs.HOME_GREET_AFTERNOON;
		} else if ((hour >= 17) && (hour < 21)) {
			res = R.string.home_greet_evening;
			pref = MainActivityPrefs.HOME_GREET_EVENING;
		} else {
			res = R.string.home_greet_night;
		}
		String mine = prefs.getStringPref(pref);
		return ((mine != null) && !mine.trim().isEmpty()) ? mine.trim() : ctx.getString(res);
	}

	/** The overlay's chips beside the edit button: on when asked for, never while editing (the chip bar grows then). */
	private void applyTopInfo() {
		boolean want = !editing && prefs.getBooleanPref(a.isCarActivity() ? MainActivityPrefs.HOME_TOP_OVERLAY_AA :
				MainActivityPrefs.HOME_TOP_OVERLAY);
		boolean any = want && HomeInfo.apply(a, topInfo, 0.85f);
		topInfo.setVisibility(any ? View.VISIBLE : View.GONE);
		if (!any) return;
		int fg = bg.hasImage() ? 0xFFFFFFFF : palette.textPrimary;
		topInfo.setChipStyle(fg, (fg & 0x00FFFFFF) | 0x2E000000);
		fitTopInfo();
	}

	/** One row, shrunk to the room the greeting leaves it; gone when even that would be too small to read. */
	private void fitTopInfo() {
		if ((topInfo.getVisibility() == View.GONE) || (root.getWidth() == 0)) return;
		Context ctx = palette.ctx;
		float greetW = 0f;
		if (greeting.getVisibility() == View.VISIBLE) {
			greetW = Math.max(greetTitle.getPaint().measureText(greetTitle.getText().toString()) * greetTitle.getTextSize() /
					Math.max(1f, greetTitle.getPaint().getTextSize()), greetDate.getPaint().measureText(greetDate.getText().toString()))
					+ HomeUi.dp(ctx, 28);
		}
		int avail = Math.round(root.getWidth() - HomeUi.dp(ctx, 68) - greetW - HomeUi.dp(ctx, 8));
		topInfo.measure(View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int need = topInfo.getMeasuredWidth();
		float k = ((need > avail) && (need > 0)) ? (avail / (float) need) : 1f;
		if (k < 0.55f) {
			topInfo.setVisibility(View.INVISIBLE);
			return;
		}
		topInfo.setVisibility(View.VISIBLE);
		FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topInfo.getLayoutParams();
		if (lp.width != need) {
			lp.width = need;
			topInfo.setLayoutParams(lp);
		}
		topInfo.setPivotX(need);
		topInfo.setPivotY(0f);
		topInfo.setScaleX(k);
		topInfo.setScaleY(k);
	}

	// ---------------------------------------------------------------------------------------------
	// Background and the status bar

	private void loadBackground(boolean animate) {
		if (followsCover()) return;
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
			glideStatusBar(win, c);
			ic.setAppearanceLightStatusBars(HomeUi.isLight(c));
		} else if (barsApplied) {
			barsApplied = false;
			if (statusAnim != null) statusAnim.cancel();
			a.matchStatusBarToBackground();
			if (savedLightIcons != null) ic.setAppearanceLightStatusBars(savedLightIcons);
		}
	}

	@Nullable
	private ValueAnimator statusAnim;

	/** The status bar moves to the colour of the background's top edge, not jumps (below Android 15, where it is its own). */
	private void glideStatusBar(Window win, int to) {
		if (statusAnim != null) statusAnim.cancel();
		int from = win.getStatusBarColor();
		if ((from == to) || !animate()) {
			win.setStatusBarColor(to);
			return;
		}
		ValueAnimator va = ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), from, to);
		va.setDuration(360);
		va.addUpdateListener(v -> win.setStatusBarColor((Integer) v.getAnimatedValue()));
		statusAnim = va;
		va.start();
	}
}
