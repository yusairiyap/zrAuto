package me.aap.fermata.addon.web;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.fermata.util.Utils.dynCtx;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;
import me.aap.fermata.ui.view.ToolBarPill;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.view.ToolBarView;

/**
 * The browser tab's tabs: any number of pages, each in a WebView of its own, a strip of pills
 * to pick one (or close it, or open another), and the home page shown in place of a tab's page
 * whenever the tab is on it.
 * <p>
 * Everything the browser fragment does to "the" WebView -- the toolbar, fullscreen video, the
 * profile switch -- finds it by the id {@code browserWebView}. To keep all of that working
 * unchanged, only the selected tab's WebView carries that id; the others have none (which is also
 * how {@link FermataWebView#pageLoaded} knows not to touch the tool bar for a page that finished
 * loading in the background).
 */
final class BrowserTabs implements BrowserHomeView.Host, FermataWebView.PageListener {
	private static final int MAX_TABS = 12;
	private static final long CAPTURE_DELAY_MS = 1600;

	private static final class Tab {
		FermataWebView web;
		/** The tab is showing the home page in place of its own page. */
		boolean home;
		String title = "";

		Tab(FermataWebView web, boolean home) {
			this.web = web;
			this.home = home;
		}
	}

	private final WebBrowserFragment fragment;
	private final WebBrowserAddon addon;
	private final MainActivityDelegate activity;
	private final FrameLayout root;
	private final FrameLayout tabHost;
	private final BrowserHomeView homeView;
	private final LinearLayout bar;
	private final HorizontalScrollView scroller;
	private final LinearLayout row;
	private final List<Tab> tabs = new ArrayList<>();
	private final boolean light;
	private final boolean car;
	private final int textPrimary;
	private final int textSecondary;
	private final int pillIdle;
	private final int pillActive;
	private final int ripple;
	private int active = -1;
	private int justAdded = -1;
	private final View panel;
	private final int[] loc1 = new int[2];
	private final int[] loc2 = new int[2];
	private final android.graphics.RectF pillRect = new android.graphics.RectF();
	private final android.view.ViewTreeObserver.OnPreDrawListener panelSync = () -> {
		syncPanel();
		return true;
	};
	/** How much of the tab strip is out: 0 when entering the tab, growing to 1. */
	private float expand;
	private boolean expandStarted;
	private android.animation.ValueAnimator expandAnim;

	BrowserTabs(WebBrowserFragment fragment, WebBrowserAddon addon, MainActivityDelegate activity,
							FrameLayout root, FrameLayout tabHost, FermataWebView first) {
		this.fragment = fragment;
		this.addon = addon;
		this.activity = activity;
		this.root = root;
		this.tabHost = tabHost;
		Context ctx = root.getContext();
		light = MusicPlayerFragment.isLightTheme(ctx);
		car = activity.isCarActivity();
		textPrimary = light ? 0xDE000000 : 0xFFFFFFFF;
		textSecondary = light ? 0x99000000 : 0xB3FFFFFF;
		pillIdle = light ? 0x0F000000 : 0x1FFFFFFF;
		pillActive = light ? 0x29000000 : 0x4DFFFFFF;
		ripple = light ? 0x29000000 : 0x40FFFFFF;

		homeView = new BrowserHomeView(ctx, addon, activity, this);
		root.addView(homeView, root.indexOfChild(tabHost) + 1,
				new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		bar = new LinearLayout(ctx);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setPadding(dp(6), 0, dp(6), 0);
		scroller = new HorizontalScrollView(ctx);
		scroller.setHorizontalScrollBarEnabled(false);
		scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
		row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		scroller.addView(row, new FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT));
		bar.addView(scroller, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));
		TextView plus = pillText("+", 22);
		plus.setContentDescription(dynCtx(ctx).getString(R.string.browser_new_tab));
		plus.setOnClickListener(v -> newTab());
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(car ? 46 : 38), dp(car ? 46 : 38));
		plp.setMarginStart(dp(4));
		bar.addView(plus, plp);
		// One pill with the tool bar: a panel in the tool bar's colour reaching down from it, the
		// tab strip on it. Both follow the tool bar's position every frame, see syncPanel().
		panel = new View(ctx);
		GradientDrawable pbg = new GradientDrawable();
		pbg.setCornerRadius(dp(28));
		panel.setBackground(pbg);
		// Above the home page's cards (raised 6dp), the strip above the panel.
		panel.setElevation(dp(12));
		bar.setElevation(dp(13));
		panel.setVisibility(View.GONE);
		root.addView(panel, root.indexOfChild(homeView) + 1, new FrameLayout.LayoutParams(0, 0));
		bar.setVisibility(View.GONE);
		root.addView(bar, root.indexOfChild(panel) + 1, new FrameLayout.LayoutParams(0, 0));
		root.getViewTreeObserver().addOnPreDrawListener(panelSync);

		add(first, addon.getHomeUrl() == null, false);
		select(0, false);
	}

	private int dp(int v) {
		return toIntPx(root.getContext(), v);
	}

	// ---------------------------------------------------------------- tabs

	/** Adopts an already created WebView as a new tab. */
	private Tab add(FermataWebView web, boolean home, boolean select) {
		Tab t = new Tab(web, home);
		tabs.add(t);
		web.setPageListener(this);
		if (select) select(tabs.size() - 1, true);
		return t;
	}

	/** The selected tab's WebView. */
	@Nullable
	FermataWebView getWebView() {
		return (active >= 0) ? tabs.get(active).web : null;
	}

	/** Opens a new tab on the home page. */
	void newTab() {
		if (tabs.size() >= MAX_TABS) {
			UiUtils.showToast(root.getContext(), dynCtx(root.getContext()).getString(R.string.browser_tab_limit));
			return;
		}
		captureNow();
		FermataWebView web = fragment.createTabWebView(root.getContext());
		tabHost.addView(web, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		String home = addon.getHomeUrl();
		justAdded = tabs.size();
		add(web, home == null, true);
		if (home != null) web.loadUrl(home);
	}

	/** Opens {@code url} in a tab of its own. */
	void newTab(String url) {
		if (tabs.size() >= MAX_TABS) {
			UiUtils.showToast(root.getContext(), dynCtx(root.getContext()).getString(R.string.browser_tab_limit));
			return;
		}
		captureNow();
		FermataWebView web = fragment.createTabWebView(root.getContext());
		tabHost.addView(web, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		justAdded = tabs.size();
		add(web, false, true);
		web.loadUrl(url);
	}

	void select(int idx, boolean animate) {
		if ((idx < 0) || (idx >= tabs.size())) return;
		for (int i = 0; i < tabs.size(); i++) {
			FermataWebView w = tabs.get(i).web;
			if (i == idx) {
				w.setId(R.id.browserWebView);
			} else {
				w.setId(View.NO_ID);
				w.setVisibility(View.GONE);
			}
		}
		active = idx;
		showState(animate);
		refreshBar();
		refreshToolbar();
	}

	private boolean closingTab;

	/** Closes a tab: its pill shrinks away first, then the page goes. */
	void close(int idx) {
		if ((idx < 0) || (idx >= tabs.size()) || closingTab) return;
		View pill = (idx < row.getChildCount()) ? row.getChildAt(idx) : null;
		if ((pill == null) || !pill.isAttachedToWindow() || (pill.getWidth() <= 0)) {
			doClose(idx);
			return;
		}
		closingTab = true;
		int w = pill.getWidth();
		LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pill.getLayoutParams();
		int margin = lp.getMarginEnd();
		android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 0f);
		a.setDuration(200);
		a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
		a.addUpdateListener(v -> {
			float f = (float) v.getAnimatedValue();
			lp.width = Math.round(w * f);
			lp.setMarginEnd(Math.round(margin * f));
			pill.setAlpha(f);
			pill.setLayoutParams(lp);
		});
		a.addListener(new android.animation.AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(android.animation.Animator animation) {
				closingTab = false;
				doClose(idx);
			}
		});
		a.start();
	}

	private void doClose(int idx) {
		if ((idx < 0) || (idx >= tabs.size())) return;
		Tab t = tabs.get(idx);
		if (tabs.size() == 1) {
			// Never no tabs: the last one is replaced by a fresh one on the home page.
			FermataWebView web = fragment.createTabWebView(root.getContext());
			tabHost.addView(web, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
			String home = addon.getHomeUrl();
			tabs.add(new Tab(web, home == null));
			web.setPageListener(this);
			if (home != null) web.loadUrl(home);
			tabs.remove(0);
			active = -1;
			destroy(t);
			select(0, false);
			return;
		}

		tabs.remove(idx);
		destroy(t);
		int next = (active > idx) ? active - 1 : Math.min(active, tabs.size() - 1);
		if (active == idx) next = Math.min(idx, tabs.size() - 1);
		active = -1;
		select(next, false);
	}

	private void destroy(Tab t) {
		t.web.setPageListener(null);
		t.web.stopLoading();
		tabHost.removeView(t.web);
		t.web.destroy();
	}

	/** Tears everything down with the fragment's view. */
	void release() {
		root.getViewTreeObserver().removeOnPreDrawListener(panelSync);
		if (expandAnim != null) expandAnim.cancel();
		homeView.release();
		for (Tab t : tabs) {
			t.web.setPageListener(null);
			t.web.stopLoading();
			tabHost.removeView(t.web);
			t.web.destroy();
		}
		tabs.clear();
		active = -1;
	}

	// ---------------------------------------------------------------- home and navigation

	boolean isHomeShown() {
		return (active >= 0) && tabs.get(active).home;
	}

	boolean isEditingHome() {
		return isHomeShown() && homeView.isEditing();
	}

	private boolean hasBuiltInHome() {
		return addon.getHomeUrl() == null;
	}

	/** The tool bar's Back can do something: go back in the page, or up to the home page. */
	boolean canGoBack() {
		if (active < 0) return false;
		Tab t = tabs.get(active);
		if (t.home) return homeView.isEditing();
		return t.web.canGoBack() || hasBuiltInHome();
	}

	boolean goBack() {
		if (active < 0) return false;
		Tab t = tabs.get(active);
		if (t.home) {
			if (!homeView.isEditing()) return false;
			homeView.setEditing(false);
			return true;
		}
		if (t.web.canGoBack()) {
			t.web.goBack();
			return true;
		}
		if (hasBuiltInHome()) {
			goHome();
			return true;
		}
		return false;
	}

	boolean canGoForward() {
		return (active >= 0) && !tabs.get(active).home && tabs.get(active).web.canGoForward();
	}

	/** The Home button: the built-in home page, or the page set in Settings. */
	void goHome() {
		if (active < 0) return;
		String custom = addon.getHomeUrl();
		if (custom != null) {
			navigate(custom);
			return;
		}
		Tab t = tabs.get(active);
		if (!t.home) {
			captureNow();
			t.home = true;
		}
		homeView.reload();
		homeView.loadBackground();
		showState(true);
		refreshBar();
		refreshToolbar();
	}

	/** Loads {@code url} in the selected tab, leaving the home page if it was on it. */
	void navigate(String url) {
		if (active < 0) return;
		Tab t = tabs.get(active);
		t.home = false;
		homeView.setEditing(false);
		showState(true);
		t.web.loadUrl(url);
		refreshBar();
		refreshToolbar();
	}

	/** Shows the selected tab's page or the home page over it, fading between the two. */
	private void showState(boolean animate) {
		if (active < 0) return;
		Tab t = tabs.get(active);
		if (t.home) {
			homeView.reload();
			homeView.setHomeVisible(true, animate);
			if (animate) {
				// Keep the page underneath until the home page has faded in over it.
				t.web.postDelayed(() -> {
					if ((active >= 0) && (tabs.get(active) == t) && t.home) t.web.setVisibility(View.INVISIBLE);
				}, 300);
			} else {
				t.web.setVisibility(View.INVISIBLE);
			}
		} else {
			t.web.setVisibility(View.VISIBLE);
			homeView.setHomeVisible(false, animate);
		}
	}

	// ---------------------------------------------------------------- page events

	@Override
	public void onPageLoaded(FermataWebView view, String url) {
		Tab t = find(view);
		if (t == null) return;
		String title = view.getTitle();
		t.title = (title == null) ? "" : title.trim();
		refreshBar();
		if ((active >= 0) && (tabs.get(active) == t)) refreshToolbar();
		// Once it has had a moment to paint: a picture of the site for its card and the background.
		view.postDelayed(() -> capture(view), CAPTURE_DELAY_MS);
	}

	@Nullable
	private Tab find(FermataWebView web) {
		for (Tab t : tabs) {
			if (t.web == web) return t;
		}
		return null;
	}

	private void captureNow() {
		if ((active < 0) || tabs.get(active).home) return;
		capture(tabs.get(active).web);
	}

	private void capture(FermataWebView web) {
		Tab t = find(web);
		if ((t == null) || t.home || (active < 0) || (tabs.get(active) != t)) return;
		String url = web.getUrl();
		if (url == null) return;
		Bitmap shot = BrowserBookmarks.snapshot(web);
		if (shot == null) return;
		BrowserBookmarks.saveLast(shot);
		for (BrowserBookmarks.Item i : BrowserBookmarks.list(addon)) {
			if (BrowserBookmarks.sameSite(i.url, url)) BrowserBookmarks.save(i.url, shot);
		}
	}

	/** Saves the screenshot of the selected page for a bookmark just added from it. */
	void captureForBookmark(String bookmarkUrl) {
		if ((active < 0) || tabs.get(active).home) return;
		Bitmap shot = BrowserBookmarks.snapshot(tabs.get(active).web);
		if (shot != null) BrowserBookmarks.save(bookmarkUrl, shot);
	}

	void reloadHome() {
		homeView.reload();
	}

	// ---------------------------------------------------------------- the panel

	private int tabOverlap() {
		return dp(10);
	}

	private int stripHeight() {
		return dp(car ? 50 : 42);
	}

	/** The room under the tool bar that the strip takes, panel bottom padding included. */
	private int panelExtra() {
		return stripHeight() + dp(6) - tabOverlap();
	}

	/** Starts the strip growing out of the tool bar's pill; the tab just came on screen. */
	void playEnter() {
		expandStarted = false;
		expand = 0f;
	}

	private void startExpand() {
		expandStarted = true;
		if (expandAnim != null) expandAnim.cancel();
		expandAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
		expandAnim.setDuration(380);
		expandAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
		expandAnim.addUpdateListener(v -> {
			expand = (float) v.getAnimatedValue();
			root.invalidate();
		});
		expandAnim.start();
	}

	/**
	 * Lays the panel and the strip out against the tool bar, in this view's own coordinates (the
	 * tool bar overlays the top of the tab): the panel runs from the tool bar's pill top down past
	 * its bottom by the strip's room, and the pages and the home page start below it.
	 */
	private void syncPanel() {
		FermataWebView cur = getWebView();
		FermataChromeClient cc = (cur != null) ? cur.getWebChromeClient() : null;
		if ((cc != null) && cc.isFullScreen()) {
			// A video over everything: the panel (raised above its siblings) must not cover it.
			panel.setVisibility(View.GONE);
			bar.setVisibility(View.GONE);
			return;
		}
		ToolBarView tb = activity.getToolBar();
		boolean tbShown = (tb != null) && (tb.getVisibility() == View.VISIBLE) && tb.isLaidOut();
		boolean merged = tbShown && ToolBarPill.isMerged(tb) && ToolBarPill.getPillRect(tb, pillRect);
		float left;
		float right;
		float top;
		float bottom;

		if (merged) {
			tb.getLocationOnScreen(loc1);
			root.getLocationOnScreen(loc2);
			float ox = loc1[0] - loc2[0];
			float oy = loc1[1] - loc2[1];
			left = ox + pillRect.left;
			right = ox + pillRect.right;
			top = oy + pillRect.top;
			bottom = oy + pillRect.bottom;
		} else if (!tbShown) {
			// No tool bar (bars hidden): the strip alone, at the top.
			left = dp(12);
			right = root.getWidth() - dp(12);
			top = dp(8);
			bottom = top;
		} else {
			// Another tool bar is still showing: nothing of ours yet.
			panel.setVisibility(View.GONE);
			bar.setVisibility(View.GONE);
			expandStarted = false;
			expand = 0f;
			return;
		}

		if (!expandStarted) startExpand();
		float extra = panelExtra() * expand;
		int ip = Math.round(top);
		int ib = Math.round(bottom + extra);

		panel.setVisibility(View.VISIBLE);
		bar.setVisibility(View.VISIBLE);
		((GradientDrawable) panel.getBackground()).setColor(merged ? ToolBarPill.getColor(tb) :
				(light ? 0xF2F5F6FA : 0xF21C1C22));
		FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) panel.getLayoutParams();
		int pw = Math.round(right - left);
		int ph = ib - ip;
		if ((plp.leftMargin != Math.round(left)) || (plp.topMargin != ip) || (plp.width != pw) ||
				(plp.height != ph)) {
			plp.gravity = Gravity.TOP | Gravity.START;
			plp.leftMargin = Math.round(left);
			plp.topMargin = ip;
			plp.width = pw;
			plp.height = ph;
			panel.setLayoutParams(plp);
		}

		FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) bar.getLayoutParams();
		// Tucked up into the tool bar's own bottom padding, so the gap to its buttons is small.
		int bt = Math.round(bottom) - tabOverlap();
		int bl = Math.round(left) + dp(8);
		int bw = pw - dp(16);
		int bh = stripHeight();
		if ((blp.leftMargin != bl) || (blp.topMargin != bt) || (blp.width != bw) || (blp.height != bh)) {
			blp.gravity = Gravity.TOP | Gravity.START;
			blp.leftMargin = bl;
			blp.topMargin = bt;
			blp.width = bw;
			blp.height = bh;
			bar.setLayoutParams(blp);
		}
		bar.setAlpha(expand);

		// The pages begin under the panel: each WebView already keeps a top margin for the tool bar,
		// this padding is the rest of the way.
		int wantTop = ib + dp(2);
		int have = 0;
		FermataWebView w = getWebView();
		if ((w != null) && (w.getLayoutParams() instanceof ViewGroup.MarginLayoutParams m)) {
			have = m.topMargin;
		}
		int pad = Math.max(0, wantTop - have);
		if (tabHost.getPaddingTop() != pad) tabHost.setPadding(0, pad, 0, 0);
		homeView.setShift(-(1f - expand) * panelExtra());
	}

	// ---------------------------------------------------------------- tool bar

	/** Puts the selected tab's address and Back/Forward state in the tool bar. */
	void refreshToolbar() {
		if ((active < 0) || (activity.getActiveFragment() != fragment)) return;
		ToolBarView.Mediator m = fragment.getToolBarMediator();
		if (!(m instanceof WebToolBarMediator wm)) return;
		ToolBarView tb = activity.getToolBar();
		Tab t = tabs.get(active);
		String url = t.home ? null : t.web.getUrl();
		wm.setAddress(tb, (url == null) ? "" : url);
		wm.setButtonsVisibility(tb, canGoBack(), canGoForward());
	}

	// ---------------------------------------------------------------- tab strip

	private TextView pillText(String text, int sp) {
		TextView t = new TextView(root.getContext());
		t.setText(text);
		t.setTextColor(textPrimary);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setGravity(Gravity.CENTER);
		t.setBackground(rippleShape(pillIdle, dp(40)));
		t.setClickable(true);
		t.setFocusable(true);
		return t;
	}

	private Drawable rippleShape(int fill, int radius) {
		GradientDrawable content = new GradientDrawable();
		content.setColor(fill);
		content.setCornerRadius(radius);
		GradientDrawable mask = new GradientDrawable();
		mask.setColor(Color.BLACK);
		mask.setCornerRadius(radius);
		return new RippleDrawable(ColorStateList.valueOf(ripple), content, mask);
	}

	private String titleOf(Tab t) {
		Context ctx = dynCtx(root.getContext());
		if (t.home) return ctx.getString(R.string.browser_home);
		if (!t.title.isEmpty()) return t.title;
		String url = t.web.getUrl();
		return (url == null) ? ctx.getString(R.string.browser_home) : BrowserBookmarks.hostOf(url);
	}

	private void refreshBar() {
		row.removeAllViews();
		Context ctx = root.getContext();
		int h = dp(car ? 42 : 34);
		View activePill = null;

		for (int i = 0; i < tabs.size(); i++) {
			final int idx = i;
			Tab t = tabs.get(i);
			boolean sel = (i == active);
			LinearLayout pill = new LinearLayout(ctx);
			pill.setOrientation(LinearLayout.HORIZONTAL);
			pill.setGravity(Gravity.CENTER_VERTICAL);
			pill.setPadding(dp(14), 0, dp(4), 0);
			pill.setBackground(rippleShape(sel ? pillActive : pillIdle, dp(40)));
			pill.setClickable(true);
			pill.setFocusable(true);
			pill.setOnClickListener(v -> select(idx, true));

			TextView title = new TextView(ctx);
			title.setText(titleOf(t));
			title.setTextColor(sel ? textPrimary : textSecondary);
			title.setTextSize(TypedValue.COMPLEX_UNIT_SP, car ? 16 : 14);
			title.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
			title.setSingleLine(true);
			title.setEllipsize(TextUtils.TruncateAt.END);
			title.setMaxWidth(dp(car ? 200 : 150));
			pill.addView(title, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

			ImageView close = new ImageView(ctx);
			close.setImageResource(me.aap.utils.R.drawable.close);
			close.setColorFilter(sel ? textPrimary : textSecondary);
			int pad = dp(car ? 9 : 7);
			close.setPadding(pad, pad, pad, pad);
			close.setContentDescription(dynCtx(ctx).getString(R.string.browser_close_tab));
			close.setBackground(rippleShape(0x00000000, dp(40)));
			close.setOnClickListener(v -> close(idx));
			int cs = dp(car ? 34 : 28);
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cs, cs);
			clp.setMarginStart(dp(4));
			pill.addView(close, clp);

			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, h);
			lp.setMarginEnd(dp(6));
			row.addView(pill, lp);
			if (sel) activePill = pill;

			if (i == justAdded) {
				pill.setAlpha(0f);
				pill.setScaleX(0.7f);
				pill.setScaleY(0.7f);
				pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
			}
		}
		justAdded = -1;

		View target = activePill;
		if (target != null) {
			scroller.post(() -> scroller.smoothScrollTo(Math.max(0, target.getLeft() - dp(24)), 0));
		}
	}

	// ---------------------------------------------------------------- home page callbacks

	@Override
	public void open(BrowserBookmarks.Item item) {
		fragment.loadUrl(item.url);
	}

	@Override
	public void add() {
		askBookmark(null);
	}

	@Override
	public void edit(BrowserBookmarks.Item item) {
		askBookmark(item);
	}

	@Override
	public void remove(BrowserBookmarks.Item item) {
		addon.removeBookmark(item.url);
		BrowserBookmarks.deleteThumbnail(item.url);
		homeView.reload();
	}

	@Override
	public void cardMenu(BrowserBookmarks.Item item, int index) {
		Context ctx = dynCtx(root.getContext());
		List<BrowserBookmarks.Item> all = BrowserBookmarks.list(addon);
		activity.getContextMenu().show(b -> {
			b.setTitle(item.name.isEmpty() ? item.host() : item.name);
			b.addItem(R.id.browser_card_open, me.aap.fermata.R.drawable.web,
					ctx.getString(R.string.browser_open)).setHandler(i -> {
				fragment.loadUrl(item.url);
				return true;
			});
			b.addItem(R.id.browser_card_open_tab, me.aap.fermata.R.drawable.web,
					ctx.getString(R.string.browser_open_new_tab)).setHandler(i -> {
				newTab(item.url);
				return true;
			});
			b.addItem(R.id.browser_card_edit, me.aap.fermata.R.drawable.edit,
					ctx.getString(R.string.browser_edit_bookmark)).setHandler(i -> {
				askBookmark(item);
				return true;
			});
			if (index > 0) {
				b.addItem(R.id.browser_card_earlier, me.aap.utils.R.drawable.move_left,
						ctx.getString(R.string.browser_move_earlier)).setHandler(i -> {
					homeView.move(index, index - 1);
					return true;
				});
			}
			if (index < all.size() - 1) {
				b.addItem(R.id.browser_card_later, me.aap.utils.R.drawable.move_right,
						ctx.getString(R.string.browser_move_later)).setHandler(i -> {
					homeView.move(index, index + 1);
					return true;
				});
			}
			b.addItem(R.id.browser_card_remove, me.aap.fermata.R.drawable.delete,
					ctx.getString(R.string.browser_remove_bookmark)).setHandler(i -> {
				remove(item);
				return true;
			});
		});
	}

	/** Asks for a bookmark's name and address: a new one, or {@code existing}'s new values. */
	private void askBookmark(@Nullable BrowserBookmarks.Item existing) {
		Context ctx = dynCtx(root.getContext());
		Pref<Supplier<String>> name = Pref.s("name", (existing != null) ? existing.name : "");
		Pref<Supplier<String>> url = Pref.s("url", (existing != null) ? existing.url : "https://");
		String title = ctx.getString(
				(existing != null) ? R.string.browser_edit_bookmark : R.string.browser_add_bookmark);

		UiUtils.queryPrefs(ctx, title, (store, set) -> {
			set.addStringPref(o -> {
				o.store = store;
				o.pref = name;
				o.title = me.aap.fermata.R.string.bookmark_name;
			});
			set.addStringPref(o -> {
				o.store = store;
				o.pref = url;
				o.title = R.string.url;
			});
		}, null).onSuccess(store -> {
			String n = store.getStringPref(name).trim();
			String u = BrowserBookmarks.normalizeUrl(store.getStringPref(url));
			if (u.isEmpty() || u.equals("https://")) return;
			if (n.isEmpty()) n = BrowserBookmarks.hostOf(u);
			if (existing != null) {
				addon.updateBookmark(existing.url, n, u);
				BrowserBookmarks.renameThumbnail(existing.url, u);
			} else {
				addon.addBookmark(n, u);
			}
			homeView.reload();
		});
	}

	// ---------------------------------------------------------------- profile switch

	/** All the tabs' WebViews, for the fragment's private-mode profile switch. */
	List<FermataWebView> getWebViews() {
		List<FermataWebView> l = new ArrayList<>(tabs.size());
		for (Tab t : tabs) l.add(t.web);
		return l;
	}

	/**
	 * Swaps every tab's WebView for {@code fresh} ones (see the fragment's profile switch), the
	 * same tab getting the WebView at the same position, hidden or shown just as before.
	 */
	void replaceWebViews(List<FermataWebView> fresh) {
		for (int i = 0; (i < tabs.size()) && (i < fresh.size()); i++) {
			Tab t = tabs.get(i);
			FermataWebView f = fresh.get(i);
			f.setId((i == active) ? R.id.browserWebView : View.NO_ID);
			f.setVisibility((i == active) ? (t.home ? View.INVISIBLE : View.VISIBLE) : View.GONE);
			f.setPageListener(this);
			t.web = f;
		}
	}
}
