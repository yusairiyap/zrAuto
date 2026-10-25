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
import android.view.ViewTreeObserver;
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
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.BehindBarsLayers;
import me.aap.fermata.ui.view.InfoOverlayView;
import me.aap.fermata.ui.view.OverflowFrameLayout;
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
	/** Holds {@link #topInfo}: when its chips are wider than the room, the row scrolls and fades out at the end. */
	private final HomeFadeLayout topScroll;
	private final HorizontalScrollView topInner;
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
	/** The room a side navigation pill takes from one side, given to the other side as well so the page is centred in the window. */
	private int extraLeft;
	private int extraRight;
	private final int[] sideLoc = new int[2];
	private boolean editing;
	private boolean shown;
	/** The first screen since the app started: it stays invisible while the bars settle, then fades in once (like the Music tab). */
	private boolean quiet;
	private static final long QUIET_MS = 500;
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

		// Not a plain FrameLayout: with a tab's alpha below 1 (its crossfade, its fade in at start) a view that reports
		// overlapping rendering is drawn through a layer the size of its own bounds, which cut the background off where
		// it reaches under the status bar, the navigation bar and a side pill for the whole fade and popped it in at the
		// end (the late fill-in). The Music tab's root is this class for the same reason.
		root = new OverflowFrameLayout(ctx, null);
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
		// Glass cards show these layers blurred: drawn again with them.
		base.setOnRedraw(this::invalidateGlassCards);
		bg.setOnRedraw(this::invalidateGlassCards);
		nowBg = new HomeNowPlayingBg(a, (bm, top) -> {
			if (bm == null) {
				bg.setImage(null, true);
				topColor = defaultTopColor();
			} else {
				bg.setImage(bm, true);
				topColor = dimmedTop(top);
			}
			applyGreeting();
			if (shown) systemBars(true);
		});
		blurCover = () -> nowBg.setBlur(prefs.getIntPref(HomeBgPrefs.blur(car())));

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
		topInfo.setOneRow(true);
		// The scroller's own fading edge is not used: it drew hard dark boxes at the row's ends on the car screen.
		// The page's edge fade (a layer cleared with gradients) does the same job and is known to draw right there.
		topInner = new HorizontalScrollView(ctx);
		topInner.setHorizontalScrollBarEnabled(false);
		topInner.setOverScrollMode(View.OVER_SCROLL_NEVER);
		topInner.setHorizontalFadingEdgeEnabled(false);
		topInner.addView(topInfo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		topScroll = new HomeFadeLayout(ctx);
		topScroll.addView(topInner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		// Glass chips show the background blurred behind them: drawn again as the row scrolls.
		topScroll.follow(topInner, () -> {
			if (topGlass) topInfo.invalidateChips();
		});
		topScroll.setVisibility(View.GONE);
		FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
		ilp.setMargins(0, HomeUi.dp(ctx, 15), HomeUi.dp(ctx, 68), 0);
		root.addView(topScroll, ilp);
		// A chip's text grows as its reading does (the current in mA gets a digit): the row is fitted again.
		topInfo.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if ((r - l) != (or - ol)) root.post(this::fitTopInfo);
		});
		root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if ((r - l) != (or - ol)) fitTopInfo();
		});
		// The edit button and the info chips sit on the greeting's middle line; the chips leave with it.
		View.OnLayoutChangeListener align = (v, l, t, r, b, ol, ot, or, ob) -> {
			if (((b - t) != (ob - ot)) || (v == greeting)) alignHeader();
		};
		greeting.addOnLayoutChangeListener(align);
		chipBar.addOnLayoutChangeListener(align);
		topScroll.addOnLayoutChangeListener(align);

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

	/** The Home item's menu in the navigation bar: Home comes up in edit mode (at once when it is already showing). */
	public static void startEditing(MainActivityDelegate a) {
		HomeScreen h = live;
		if ((h != null) && h.shown) {
			h.setEditing(true);
			return;
		}
		editOnShow = true;
		a.exitVideoMode();
		a.showFragment(R.id.home_fragment);
	}

	/** Settings > Home: choose the background picture. */
	public static void pickBackground(MainActivityDelegate a, boolean car) {
		HomeBackground.pick(a, car, null);
	}

	/** Settings > Home: back to the plain background. */
	public static void clearBackground(MainActivityDelegate a, boolean car) {
		HomeBackground.clear(a, car);
	}

	public View getRoot() {
		return root;
	}

	public void onViewCreated(boolean quietStart) {
		live = this;
		quiet = quietStart;
		if (quiet) root.setAlpha(0f);
		behind.attach();
		root.getViewTreeObserver().addOnPreDrawListener(sideSync);
		prefs.addBroadcastListener(this);
		me.aap.fermata.ui.view.Glass.addAnimListener(glassGlide);
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
		return prefs.getIntPref(HomeBgPrefs.source(car())) == 1;
	}

	/** The background's source changed (or Home came back): follow the cover, or load the picture. */
	private void applyBackgroundSource() {
		if (followsCover()) {
			bgGeneration++;
			nowBg.forget();
			if (shown) nowBg.start(prefs.getIntPref(HomeBgPrefs.blur(car())));
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
		me.aap.fermata.ui.view.Glass.removeAnimListener(glassGlide);
		behind.detach();
		ViewTreeObserver vto = root.getViewTreeObserver();
		if (vto.isAlive()) vto.removeOnPreDrawListener(sideSync);
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
		if (followsCover()) nowBg.start(prefs.getIntPref(HomeBgPrefs.blur(car())));
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
		Runnable enter = () -> {
			if (!shown) return;
			grid.playEntrance();
			greeting.animate().alpha(1f).setDuration(380).setInterpolator(new DecelerateInterpolator(1.8f)).start();
		};
		// Decided when the view was made (see onViewCreated): it set the page invisible then, so it must fade in
		// now. Asking isInitialFragmentShow() here instead left the page blank whenever this ran after the switch.
		boolean first = quiet;
		quiet = false;
		if (first) {
			if (!animate()) {
				root.setAlpha(1f);
				root.post(enter);
			} else {
				// The first moments are a burst of layout (bars, insets, the widgets); the page fades in once it has
				// settled and its widgets rise with it, as the Music tab does.
				root.postDelayed(() -> {
					// Always faded in: if Home is not shown yet, the page must not stay blank (the bars still draw).
					if (!shown) DiagnosticLog.log("STATE", "home quiet start", "not shown yet, faded in anyway");
					root.animate().alpha(1f).setDuration(350).start();
					if (shown) enter.run();
				}, QUIET_MS);
			}
		} else {
			root.post(enter);
		}
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
	public int cardAlpha() {
		return Math.max(0, Math.min(100, prefs.getIntPref(MainActivityPrefs.HOME_CARD_ALPHA)));
	}

	/** The cards with real glass are drawn again (the background behind them changed). */
	private void invalidateGlassCards() {
		invalidateGlassCards(grid);
		if (topGlass) topInfo.invalidateChips();
	}

	private static void invalidateGlassCards(View v) {
		if (v instanceof HomeWidgetView w) {
			if (w.glassCard()) w.invalidate();
		} else if (v instanceof ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) invalidateGlassCards(g.getChildAt(i));
		}
	}

	private final int[] glassCardLoc = new int[2];
	private final int[] glassBgLoc = new int[2];

	@Override
	public boolean drawGlassBehind(View card, android.graphics.Canvas c, android.graphics.RectF r, float radius,
																 float alpha, me.aap.fermata.ui.view.Glass.Look look,
																 me.aap.fermata.ui.view.GlassLens[] slot) {
		if (!me.aap.fermata.ui.view.Glass.realBlur()) return false;
		return drawGlassBehind31(card, c, r, radius, alpha, look, slot);
	}

	/**
	 * Both background layers, as they are drawn (each recorded by its view): the plain one, then the
	 * picture at its own alpha (it fades when a new one comes and when Home is left). A blur of the two
	 * mixed is the mix of the two blurred, so drawing them so under one blur is exact.
	 */
	@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
	private boolean drawGlassBehind31(View card, android.graphics.Canvas c, android.graphics.RectF r, float radius,
																		float alpha, me.aap.fermata.ui.view.Glass.Look look,
																		me.aap.fermata.ui.view.GlassLens[] slot) {
		android.graphics.RenderNode b0 = base.glassNode();
		android.graphics.RenderNode b1 = bg.glassNode();
		if ((b0 == null) && (b1 == null)) return false;
		// Both layers have the same place and size (the root's, plus how far they reach under the bars).
		base.getLocationInWindow(glassBgLoc);
		card.getLocationInWindow(glassCardLoc);
		float bgAlpha = bg.getAlpha();
		float dx = bg.getX() - base.getX();
		float dy = bg.getY() - base.getY();
		if (slot[0] == null) slot[0] = new me.aap.fermata.ui.view.GlassLens();
		return slot[0].draw(c, rc -> {
			if (b0 != null) rc.drawRenderNode(b0);
			if ((b1 != null) && (bgAlpha > 0f)) {
				int save = rc.save();
				rc.translate(dx, dy);
				if (bgAlpha < 1f) rc.saveLayerAlpha(null, Math.round(255 * bgAlpha));
				rc.drawRenderNode(b1);
				rc.restoreToCount(save);
			}
		}, glassBgLoc[0] - glassCardLoc[0], glassBgLoc[1] - glassCardLoc[1], r, radius, alpha, look,
				card.getResources().getDisplayMetrics().density);
	}

	@Override
	public int stackRotateSeconds() {
		int s = prefs.getIntPref(MainActivityPrefs.HOME_STACK_ROTATE);
		return (s <= 0) ? 0 : Math.max(3, s);
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
				changed.contains(MainActivityPrefs.HOME_COLUMNS_AA) || changed.contains(MainActivityPrefs.HOME_GAP_AA) ||
				changed.contains(MainActivityPrefs.HOME_SNAP_GRID) || changed.contains(MainActivityPrefs.HOME_SNAP_GRID_AA)) {
			applyArrangement();
		}
		if (changed.contains(MainActivityPrefs.HOME_CARD_STYLE) || changed.contains(MainActivityPrefs.HOME_CARD_ALPHA) ||
				me.aap.fermata.ui.view.Glass.touches(changed)) {
			me.aap.fermata.ui.view.Glass.reset();
			base.invalidate();
			bg.invalidate();
			applyTopInfo();
			grid.restyle();
		}
		if (changed.contains(MainActivityPrefs.HOME_STACK_ROTATE)) grid.resyncRotation();
		if (changed.contains(MainActivityPrefs.HOME_EDIT_BUTTON) || changed.contains(MainActivityPrefs.HOME_EDIT_BUTTON_AA)) {
			applyEditUi(true);
		}
		if (changed.contains(MainActivityPrefs.HOME_GREETING) || changed.contains(MainActivityPrefs.HOME_GREETING_AA) ||
				changed.contains(MainActivityPrefs.HOME_GREET_MORNING) ||
				changed.contains(MainActivityPrefs.HOME_GREET_AFTERNOON) ||
				changed.contains(MainActivityPrefs.HOME_GREET_EVENING) || changed.contains(MainActivityPrefs.HOME_GREET_NIGHT)) {
			applyGreeting();
		}
		if (changed.contains(MainActivityPrefs.HOME_TOP_OVERLAY) || changed.contains(MainActivityPrefs.HOME_TOP_OVERLAY_AA) ||
				changed.contains(MainActivityPrefs.HOME_TOP_OVERLAY_HIDE) ||
				changed.contains(MainActivityPrefs.HOME_TOP_OVERLAY_HIDE_AA) ||
				!java.util.Collections.disjoint(MainActivityPrefs.infoOverlayPrefs(), changed)) {
			applyTopInfo();
		}
		if (changed.contains(MainActivityPrefs.COVER_HIDDEN)) nowBg.coverHideChanged();
		if (changed.contains(HomeBgPrefs.dim(car()))) bg.setDim(prefs.getIntPref(HomeBgPrefs.dim(car())));
		if (changed.contains(HomeBgPrefs.zoom(car()))) bg.setZoom(prefs.getIntPref(HomeBgPrefs.zoom(car())));
		if (changed.contains(HomeBgPrefs.source(car()))) {
			applyBackgroundSource();
		} else if (followsCover()) {
			if (changed.contains(HomeBgPrefs.blur(car()))) {
				root.removeCallbacks(blurCover);
				root.postDelayed(blurCover, 350);
			}
		} else if (changed.contains(HomeBgPrefs.version(car())) || changed.contains(HomeBgPrefs.blur(car()))) {
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
		DiagnosticLog.log("HOME", "edit mode", on ? "on" : "off");
		grid.setEditing(on);
		applyEditUi(true);
		if (!on) model.save();
	}

	private void applyEditUi(boolean animated) {
		if (animated && animate() && chipBar.isLaidOut()) {
			// A toggle on top of a toggle still running: that one ends first, the new one starts from where it is.
			TransitionManager.endTransitions(chipBar);
			TransitionManager.beginDelayedTransition(chipBar);
		}
		boolean button = editButtonWanted();
		editBtn.setVisibility((editing || !button) ? View.GONE : View.VISIBLE);
		for (TextView c : editChips) c.setVisibility(editing ? View.VISIBLE : View.GONE);
		// With the button off the bar has nothing in it (and no pill drawn): edit mode is still there from a long press
		// on a widget, from Settings > Home and from Home's menu in the navigation bar.
		chipBar.setVisibility((editing || button) ? View.VISIBLE : View.GONE);
		applyHeaderRoom();
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

	/** Whether the little edit button shows in the top right corner (Settings > Home > Header, per screen). */
	private boolean editButtonWanted() {
		return prefs.getBooleanPref(car() ? MainActivityPrefs.HOME_EDIT_BUTTON_AA : MainActivityPrefs.HOME_EDIT_BUTTON);
	}

	/**
	 * How far the header sits from the right edge: the corner's own room (more when the edit button is there), and what
	 * keeps the page centred when a side pill takes room from one side only.
	 */
	private void applyHeaderRoom() {
		Context ctx = palette.ctx;
		boolean button = editButtonWanted() || editing;
		FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) chipBar.getLayoutParams();
		int chipEnd = HomeUi.dp(ctx, 12) + extraRight;
		if (clp.getMarginEnd() != chipEnd) {
			clp.setMarginEnd(chipEnd);
			chipBar.setLayoutParams(clp);
		}
		FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) topScroll.getLayoutParams();
		int infoEnd = HomeUi.dp(ctx, button ? 68 : 12) + extraRight;
		if (ilp.getMarginEnd() != infoEnd) {
			ilp.setMarginEnd(infoEnd);
			topScroll.setLayoutParams(ilp);
		}
		applyGreeting();
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
		// Freeflow (3) scrolls down too, its cards keep the places they were dropped at.
		boolean down = (mode == 1) || (mode == 3) || ((mode == 0) && !(landscape && ((h / d) < 600)));
		int count = prefs.getIntPref(car() ? MainActivityPrefs.HOME_COLUMNS_AA : MainActivityPrefs.HOME_COLUMNS);
		if (count <= 0) {
			count = down ? Math.max(4, Math.min(8, (int) (w / d / 90f))) : Math.max(2, Math.min(5, (int) (h / d / 105f)));
		}
		count = down ? Math.max(2, Math.min(8, count)) : Math.max(2, Math.min(5, count));
		int gap = HomeUi.dp(ctx, Math.max(4, Math.min(20,
				prefs.getIntPref(car() ? MainActivityPrefs.HOME_GAP_AA : MainActivityPrefs.HOME_GAP))));
		if ((scroller == null) || (down != vertical)) buildScroller(down);
		int side = HomeUi.dp(ctx, 12);
		measureSideExtras(down);
		if (vertical) grid.setPadding(side + extraLeft, 0, side + extraRight, HomeUi.dp(ctx, 16));
		else grid.setPadding(side, HomeUi.dp(ctx, 58), side, HomeUi.dp(ctx, 8));
		grid.setViewportWidth(vertical ? 0 : w, vertical ? Float.NaN : sidewaysCentre());
		grid.setArrangement(vertical, count, gap);
		grid.setFreeform(mode == 3);
		// Each arrangement keeps its own sizes, places and order (see HomeModel#switchLayout).
		// Portrait and landscape keep their own arrangements, each with its own sizes, places and order.
		String arrangement = !down ? "side" : (mode == 3 ? "free" : "down");
		// The cards of another arrangement are made again from the model, not reused (their views kept the old cards).
		String key = arrangement + (landscape ? "-l" : "-p");
		// Each arrangement keeps its own background (blur, zoom, dim, quality, source); the car screen has its own set.
		boolean swapping = !key.equals(model.layoutKey());
		if (swapping) model.rememberBackground();
		if (model.switchLayout(key)) grid.rebuild();
		if (swapping) model.restoreBackground();
		grid.setSnap(prefs.getBooleanPref(car() ? MainActivityPrefs.HOME_SNAP_GRID_AA : MainActivityPrefs.HOME_SNAP_GRID));
		applyHeaderRoom();
	}

	/**
	 * Scrolling down, the page is centred in the room the side navigation pill leaves: the margin from the screen's edge
	 * to the first card equals the gap from the last card to the pill. The tab is padded clear of the pill on its own
	 * side only (and by more than the pill's visible width), which left a thin margin on one side and a wide empty
	 * strip on the other. Whichever side has the smaller margin gets the difference as extra padding. Without a side
	 * pill the page is centred in the window. Scrolling sideways keeps its own padding (the row is centred by the grid
	 * when it is shorter than the page).
	 */
	private void measureSideExtras(boolean down) {
		int l = 0;
		int r = 0;
		View decor = root.getRootView();
		if (down && (decor != root) && root.isAttachedToWindow() && (decor.getWidth() > 0) && (root.getWidth() > 0)) {
			root.getLocationInWindow(sideLoc);
			int rootLeft = sideLoc[0];
			int rootRight = rootLeft + root.getWidth();
			NavBarView nb = a.getNavBar();
			boolean pill = (nb != null) && (nb.getVisibility() == View.VISIBLE) && !nb.isBottom() && !a.isBarsHidden() &&
					(nb.getWidth() > 0);
			int diff;
			if (!pill) {
				// Left margin against right margin, both to the window's edges.
				diff = (decor.getWidth() - rootRight) - rootLeft;
			} else {
				nb.getLocationInWindow(navLoc);
				if (nb.isRight()) {
					// (pill's edge - last card) against (screen's edge - first card); the 12dp inside the root cancel out.
					diff = navLoc[0] - rootRight - rootLeft;
				} else {
					diff = rootLeft - (navLoc[0] + nb.getWidth()) - (decor.getWidth() - rootRight);
					diff = -diff;
				}
			}
			if (diff > 0) l = diff;
			else r = -diff;
			sideKey = sideKeyNow();
		}
		if ((extraLeft != l) || (extraRight != r)) {
			DiagnosticLog.log("HOME", "side room", "left=" + l, "right=" + r, "root=" + root.getWidth());
		}
		extraLeft = l;
		extraRight = r;
	}

	private final int[] navLoc = new int[2];

	/**
	 * Scrolling sideways: where the middle of the cards goes, in the page's own coordinates: the middle of the room between
	 * the screen's edge and the side pill (or of the window without one), so the margin on each side of the cards is equal.
	 */
	private float sidewaysCentre() {
		View decor = root.getRootView();
		if ((decor == root) || !root.isAttachedToWindow() || (decor.getWidth() <= 0)) return Float.NaN;
		root.getLocationInWindow(sideLoc);
		NavBarView nb = a.getNavBar();
		boolean pill = (nb != null) && (nb.getVisibility() == View.VISIBLE) && !nb.isBottom() && !a.isBarsHidden() &&
				(nb.getWidth() > 0);
		float from = 0f;
		float to = decor.getWidth();
		if (pill) {
			nb.getLocationInWindow(navLoc);
			if (nb.isRight()) to = navLoc[0];
			else from = navLoc[0] + nb.getWidth();
		}
		sideKey = sideKeyNow();
		return (from + to) / 2f - sideLoc[0];
	}

	/** What the side room depends on: where the root and the pill are (and which side the pill is on). */
	private int sideKeyNow() {
		NavBarView nb = a.getNavBar();
		boolean pill = (nb != null) && (nb.getVisibility() == View.VISIBLE) && !nb.isBottom() && !a.isBarsHidden() &&
				(nb.getWidth() > 0);
		int pillX = 0;
		if (pill) {
			nb.getLocationInWindow(navLoc);
			pillX = navLoc[0];
		}
		root.getLocationInWindow(sideLoc);
		return sideLoc[0] * 31 + root.getWidth() * 17 + (pill ? pillX + 7 : 0) + (pill && nb.isRight() ? 1 : 0);
	}
	/** What the side room was last worked out from; when the root or the pill moves, it is worked out again. */
	private int sideKey;
	private final ViewTreeObserver.OnPreDrawListener sideSync = this::onSidePreDraw;

	private boolean onSidePreDraw() {
		if (shown && root.isAttachedToWindow() && (root.getWidth() > 0)) {
			int key = sideKeyNow();
			if ((root.getRootView() != root) && (key != sideKey)) {
				sideKey = key;
				root.post(this::applyArrangement);
			}
		}
		return true;
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
		root.addView(fade, 2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		if (!down) {
			// Sideways the greeting stays put at the top left, in the room the grid leaves there.
			root.addView(greeting, 3, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
		}
		// One listener for the page's scroll (a scroller keeps only one): the grid's on-screen check, the edge fade
		// redraw and the info chips' turn to opaque (or away).
		grid.setScroller(s, () -> {
			fade.invalidate();
			updateTopInfoScroll();
		});
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
		// Room on the right for the edit button when it is there; the side pill's balancing room on either side.
		boolean button = editButtonWanted() || editing;
		greeting.setPadding(HomeUi.dp(ctx, 20) + (vertical ? extraLeft : 0), HomeUi.dp(ctx, vertical ? 16 : 8),
				HomeUi.dp(ctx, vertical ? (button ? 76 : 20) : 20) + (vertical ? extraRight : 0),
				HomeUi.dp(ctx, vertical ? 8 : 0));
		greetTitle.setText(greetingText(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)));
		greetDate.setText(DateUtils.formatDateTime(ctx, System.currentTimeMillis(),
				FORMAT_SHOW_WEEKDAY | FORMAT_SHOW_DATE));
		// The theme's own text colours on a picture too: the picture is dimmed towards black in a dark theme and towards
		// white in a light one, so the theme's text always reads on it.
		greetTitle.setTextColor(palette.textPrimary);
		greetDate.setTextColor(palette.textSecondary);
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
		topScroll.setVisibility(any ? View.VISIBLE : View.GONE);
		if (!any) return;
		int fg = palette.textPrimary;
		topGlass = me.aap.fermata.ui.view.Glass.onHome();
		if (topGlass) {
			// The glass UI style: the same tint and hairline rim as the glass cards under them.
			me.aap.fermata.ui.view.Glass.Look l = me.aap.fermata.ui.view.Glass.look();
			int ta = Math.round(Math.max(115, l.tintAlpha()) * cardAlpha() / 100f);
			topFillBase = (palette.light ? 0x00FFFFFF : 0x00101018) | (Math.min(255, ta) << 24);
			topInfo.setChipStroke(palette.light ? 0x1F000000 : 0x29FFFFFF);
			// Home's background blurred behind each chip, as behind the glass cards (Android 12+).
			topInfo.setChipBackdrop(me.aap.fermata.ui.view.Glass.realBlur() ? chipGlass : null);
		} else {
			topFillBase = (fg & 0x00FFFFFF) | 0x2E000000;
			topInfo.setChipStroke(0);
			topInfo.setChipBackdrop(null);
		}
		topInfo.setChipStyle(fg, topFillBase);
		fitTopInfo();
		updateTopInfoScroll();
	}

	/**
	 * Puts the edit button and the info chips on the middle line of the greeting's two lines (the date
	 * under the title). They stay where they are when the page scrolls. Without a greeting they keep
	 * their place at the top.
	 */
	private void alignHeader() {
		Context ctx = palette.ctx;
		boolean greet = (greeting.getVisibility() == View.VISIBLE) && (greeting.getHeight() > 0);
		if (!greet) {
			chipBar.setTranslationY(0f);
			topBase = 0f;
			updateTopInfoScroll();
			return;
		}
		float centre = greeting.getPaddingTop()
				+ (greeting.getHeight() - greeting.getPaddingTop() - greeting.getPaddingBottom()) / 2f;
		float margin = HomeUi.dp(ctx, 2);
		chipBar.setTranslationY(Math.max(margin - HomeUi.dp(ctx, 10), centre - chipBar.getHeight() / 2f - HomeUi.dp(ctx, 10)));
		topBase = Math.max(margin - HomeUi.dp(ctx, 15), centre - topScroll.getHeight() / 2f - HomeUi.dp(ctx, 15));
		updateTopInfoScroll();
	}

	/** Where the info chips rest (their translation) when the page is at the top. */
	private float topBase;

	/**
	 * The chips as the page scrolls down: by default they stay and their fill turns opaque, smoothly, so the
	 * widgets passing under them don't show through; with "hide while scrolling" they leave with the greeting
	 * (fading as it goes) and come back with it.
	 */
	private void updateTopInfoScroll() {
		Context ctx = palette.ctx;
		float scrolled = (vertical && (scroller != null)) ? scroller.getScrollY() : 0f;
		boolean hide = prefs.getBooleanPref(a.isCarActivity() ? MainActivityPrefs.HOME_TOP_OVERLAY_HIDE_AA :
				MainActivityPrefs.HOME_TOP_OVERLAY_HIDE);
		if (hide) {
			float gone = Math.min(1f, scrolled / Math.max(1f, Math.max(greeting.getHeight(), HomeUi.dp(ctx, 48)) * 0.8f));
			topScroll.setTranslationY(topBase - scrolled);
			topScroll.setAlpha(1f - gone);
			topInfo.setChipFill(topFillBase);
			// The blurred background behind them follows where they are now.
			if (topGlass) topInfo.invalidateChips();
			return;
		}
		topScroll.setTranslationY(topBase);
		topScroll.setAlpha(1f);
		float solid = Math.min(1f, scrolled / HomeUi.dp(ctx, 56));
		int target = (palette.panelFill & 0x00FFFFFF) | 0xF2000000;
		topInfo.setChipFill(androidx.core.graphics.ColorUtils.blendARGB(topFillBase, target, solid));
	}

	/** The info chips' glass: the background blurred behind each, through a lens kept on the chip. */
	private final me.aap.fermata.ui.view.InfoOverlayView.ChipBackdrop chipGlass = (chip, c, r, radius) -> {
		Object t = chip.getTag(R.id.home_glass_lens);
		me.aap.fermata.ui.view.GlassLens[] slot;
		if (t instanceof me.aap.fermata.ui.view.GlassLens[] s) {
			slot = s;
		} else {
			slot = new me.aap.fermata.ui.view.GlassLens[1];
			chip.setTag(R.id.home_glass_lens, slot);
		}
		drawGlassBehind(chip, c, r, radius, 1f, me.aap.fermata.ui.view.Glass.look(), slot);
	};

	/** Whether the info chips were last styled for glass; the glide's end restyles them (see glassGlide). */
	private boolean topGlass;
	private final Runnable glassGlide = () -> {
		if (me.aap.fermata.ui.view.Glass.onHome() != topGlass) applyTopInfo();
	};

	/** The chips' fill with the page at the top (see applyTopInfo). */
	private int topFillBase = 0x2EFFFFFF;

	/**
	 * One row, as wide as the room the greeting leaves it (or as its chips, if they are narrower); chips that
	 * do not fit are reached by scrolling the row, with its end fading out, and never wrap onto a second line.
	 */
	private void fitTopInfo() {
		if ((topScroll.getVisibility() == View.GONE) || (root.getWidth() == 0)) return;
		Context ctx = palette.ctx;
		float greetW = 0f;
		if (greeting.getVisibility() == View.VISIBLE) {
			greetW = Math.max(greetTitle.getPaint().measureText(greetTitle.getText().toString()),
					greetDate.getPaint().measureText(greetDate.getText().toString())) + HomeUi.dp(ctx, 28);
		}
		int reserve = ((FrameLayout.LayoutParams) topScroll.getLayoutParams()).getMarginEnd();
		int avail = Math.round(root.getWidth() - reserve - greetW - extraLeft - HomeUi.dp(ctx, 8));
		topInfo.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int need = topInfo.getMeasuredWidth();
		if (avail < HomeUi.dp(ctx, 90)) {
			topScroll.setVisibility(View.INVISIBLE);
			return;
		}
		topScroll.setVisibility(View.VISIBLE);
		FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topScroll.getLayoutParams();
		int want = Math.min(need, avail);
		if (lp.width != want) {
			lp.width = want;
			topScroll.setLayoutParams(lp);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Background and the status bar

	private void loadBackground(boolean animate) {
		// Before the early return: the cover background is drawn by the same view, so it needs them too.
		bg.setDim(prefs.getIntPref(HomeBgPrefs.dim(car())));
		bg.setZoom(prefs.getIntPref(HomeBgPrefs.zoom(car())));
		if (followsCover()) return;
		int version = prefs.getIntPref(HomeBgPrefs.version(car()));
		int blur = prefs.getIntPref(HomeBgPrefs.blur(car()));
		if ((version == bgVersion) && (blur == bgBlur)) return;
		bgVersion = version;
		bgBlur = blur;
		int gen = ++bgGeneration;
		Context ctx = a.getContext();
		boolean car = car();
		if (!HomeBackground.exists(ctx, car)) {
			bg.setImage(null, animate);
			topColor = defaultTopColor();
			applyGreeting();
			if (shown) systemBars(true);
			return;
		}
		int sw = ctx.getResources().getDisplayMetrics().widthPixels;
		int sh = ctx.getResources().getDisplayMetrics().heightPixels;
		App.get().getExecutor().submitTask(() -> HomeBackground.load(ctx, car, blur, sw, sh))
				.main().onSuccess(l -> {
					if (gen != bgGeneration) return;
					if (l == null) {
						bg.setImage(null, animate);
						topColor = defaultTopColor();
					} else {
						bg.setImage(l.bitmap(), animate);
						topColor = dimmedTop(l.topColor());
					}
					applyGreeting();
					if (shown) systemBars(true);
				});
	}

	private int defaultTopColor() {
		return HomeBackgroundView.plainTop(palette);
	}

	/** What shows at the top of a picture once it is dimmed and shaded (towards white in a light theme, black in a dark one). */
	private int dimmedTop(int top) {
		float dark = 1f - (1f - prefs.getIntPref(HomeBgPrefs.dim(car())) / 100f) * 0.4f;
		return HomeUi.mix(top, HomeBackgroundView.veil(palette), dark);
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
