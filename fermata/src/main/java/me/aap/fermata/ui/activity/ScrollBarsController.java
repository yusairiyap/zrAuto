package me.aap.fermata.ui.activity;

import static android.view.View.VISIBLE;
import static me.aap.utils.ui.UiUtils.toPx;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.webkit.WebView;
import android.widget.AbsListView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.fermata.ui.view.BodyLayout;
import me.aap.fermata.ui.view.ControlPanelView;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.FloatingButton;
import me.aap.utils.ui.view.NavBarView;
import me.aap.utils.ui.view.ToolBarView;

/**
 * The One UI style bars that make way for the content while it scrolls, one step at a time:
 * scrolling down a little tucks control_panel away (down behind a bottom nav_bar, or off the
 * screen), scrolling on slides a bottom nav_bar down off the screen, and further on tool_bar up
 * off it. Scrolling back up brings them back in the reverse order: tool_bar, nav_bar, then
 * control_panel; at the top of the content everything is shown. A step with nothing to hide (no
 * control panel while nothing plays, a nav bar on the side) is skipped. The floating buttons
 * follow the bottom bars, always just above whatever of them is still showing, else down at the
 * screen's bottom edge.
 * <p>
 * One value drives it all: {@link #progress}, 0 = all shown, 1 = control panel tucked away, 2 =
 * bottom nav bar too, 3 = tool bar too, animated from step to step. The bars are only moved
 * (never resized or relaid out), so nothing under them is resized either. A web page (the YouTube
 * tab, the browser) slides up into the room tool_bar leaves, without being resized either: it is
 * laid out as tall as if it started at the top, its bottom running past the screen's by as much
 * as the tool bar's room, until it slides up (see MainActivityDelegate#insetWebViewTop) -- the
 * YouTube player restarts on every resize of its WebView. Lists follow through their bottom
 * padding (see MainActivityDelegate#computeContentInsets, which reads the bars' live position), so
 * the last row still ends right above wherever the bars are; their top padding stays put, so a
 * list scrolled back to its top never starts under the tool bar.
 * <p>
 * Driven by whatever the finger scrolls inside body_layout: the innermost vertically scrollable
 * view under it when it went down (see {@link #onBodyTouch}), followed through every scroll of
 * the window, flings included. A WebView is followed by the finger's own movement instead: a
 * page often scrolls inside itself (YouTube's comments panel) without the WebView's own scroll
 * position ever moving.
 * <p>
 * Off over video, the car screen, while the bars are hidden for other reasons (see
 * MainActivityDelegate#setBarsHidden) and on tabs that keep their own layout around the bars
 * (see MainActivityFragment#collapsesBarsOnScroll); every such change, and every tab switch,
 * puts the bars back.
 */
public final class ScrollBarsController implements ViewTreeObserver.OnScrollChangedListener,
		ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener {
	static final int FULL = 0;
	static final int PANEL_HIDDEN = 1;
	static final int NAV_HIDDEN = 2;
	static final int HIDDEN = 3;
	private static final long ANIM_MS = 360;
	private final MainActivityDelegate a;
	// How far to scroll down for the first step away from all shown and for each next one, and up
	// for each step back.
	private final float firstStep;
	private final float nextStep;
	private final float upStep;
	private final float touchSlop;
	private final float shadowPad;
	private final float belowToolBar;
	private final Rect cpClip = new Rect();
	// The offset each floating button carries from this, on top of its own translation (a dragged
	// button's place), see moveFab().
	private final float[] fabDy = new float[6];
	private int state = FULL;
	private float progress;
	// Whether the bars carry any transformation of ours, so a full, settled state leaves them alone
	// (their own show/hide animations own their translation then).
	private boolean applied;
	private float toolBarDy;
	private float toolBarHidden;
	@Nullable
	private ValueAnimator anim;
	// The scrolled view followed, see onBodyTouch().
	@Nullable
	private View target;
	private boolean targetIsWeb;
	private int lastOffset;
	private int lastFirstPos;
	private int lastFirstTop;
	private float lastTouchY;
	private float touchDrag;
	private boolean dragging;
	// Scroll in the current direction since the last change of state or direction.
	private float acc;

	ScrollBarsController(MainActivityDelegate a) {
		this.a = a;
		firstStep = toPx(a.getContext(), 24);
		nextStep = toPx(a.getContext(), 140);
		upStep = toPx(a.getContext(), 32);
		touchSlop = ViewConfiguration.get(a.getContext()).getScaledTouchSlop();
		shadowPad = toPx(a.getContext(), 16);
		belowToolBar = toPx(a.getContext(), 72);
	}

	/** Starts following the window's scrolls; once, when the activity's views are set up. */
	void attach(View root) {
		ViewTreeObserver o = root.getViewTreeObserver();
		o.addOnScrollChangedListener(this);
		o.addOnPreDrawListener(this);
		o.addOnGlobalLayoutListener(this);
	}

	/** How far tool_bar is out of the way right now: 0 = in place, 1 = gone. */
	public float getToolBarHidden() {
		return toolBarHidden;
	}

	/** How far tool_bar is moved up out of the way right now, by this alone (zero or negative). */
	public float getToolBarDy() {
		return toolBarDy;
	}

	/** Back to full bars, animated or at once. */
	public void reset(boolean animate) {
		target = null;
		acc = 0f;
		if (!animate) {
			if (anim != null) anim.cancel();
			anim = null;
			state = FULL;
			progress = 0f;
			if (applied) apply(true);
			return;
		}
		setState(FULL);
	}

	private boolean isEnabled() {
		if (a.isCarActivity() || a.isVideoMode() || a.isBarsHidden()) return false;
		BodyLayout b = a.getBody();
		if ((b == null) || !b.isFrameMode()) return false;
		ActivityFragment f = a.getActiveFragment();
		return !(f instanceof MainActivityFragment mf) || mf.collapsesBarsOnScroll();
	}

	/** Every touch inside body_layout, before body_layout handles it -- never consumed here. */
	public void onBodyTouch(ViewGroup body, MotionEvent e) {
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				if (!isEnabled()) {
					if (state != FULL) reset(true);
					target = null;
					return;
				}
				target = findTarget(body, e.getRawX(), e.getRawY());
				targetIsWeb = target instanceof WebView;
				if (target != null) rememberOffset(target);
				lastTouchY = e.getRawY();
				touchDrag = 0f;
				dragging = false;
			}
			case MotionEvent.ACTION_MOVE -> {
				if (!targetIsWeb || (target == null)) return;
				if (e.getPointerCount() > 1) return; // Pinch zoom, not a scroll.
				float y = e.getRawY();
				float dy = lastTouchY - y;
				lastTouchY = y;
				if (!dragging) {
					touchDrag += dy;
					if (Math.abs(touchDrag) < touchSlop) return;
					dragging = true;
					dy = touchDrag;
				}
				onScrolled(dy);
			}
			case MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
				// A finger more or less (pinch zoom): start over from where the first one is now.
				lastTouchY = e.getRawY();
				touchDrag = 0f;
				dragging = false;
			}
			default -> {
			}
		}
	}

	@Override
	public void onScrollChanged() {
		View t = target;
		if (t == null) return;
		if (!t.isAttachedToWindow() || !isEnabled()) {
			target = null;
			return;
		}
		if (targetIsWeb) {
			// Followed by the finger (see onBodyTouch()); only the page itself scrolling back to its
			// top -- a fling, say -- puts the bars back in full here. Not a panel scrolling inside a
			// page that sits at its top (YouTube's comments), which never moves the WebView's own.
			int off = t.getScrollY();
			if (off == lastOffset) return;
			lastOffset = off;
			if (!t.canScrollVertically(-1)) setState(FULL);
			return;
		}
		int dy = scrollDelta(t);
		if (dy != 0) onScrolled(dy);
		if (!t.canScrollVertically(-1)) setState(FULL);
	}

	/**
	 * A layout pass can move the content without the user scrolling it: the bottom padding
	 * following the bars (a list at its end pulled down to fill the room they leave, a ScrollView
	 * clamped to its shorter range). Taken as a scroll, that would bring the bars right back, and
	 * with them the padding, over and over. A layout comes before the scroll notifications of the
	 * same frame, so taking the content's offset afresh here makes that frame's move count as none.
	 */
	@Override
	public void onGlobalLayout() {
		View t = target;
		if ((t != null) && t.isAttachedToWindow()) rememberOffset(t);
	}

	@Override
	public boolean onPreDraw() {
		// Re-applied before every frame while collapsed: a bar shown, laid out or restyled meanwhile
		// (the control panel coming up when playback starts) takes its compact or hidden look at
		// once. Setting a view's translation to the value it already has is a no-op.
		if (applied && (anim == null)) apply(false);
		return true;
	}

	private void onScrolled(float dy) {
		if (!isEnabled()) return;
		if ((acc != 0f) && (Math.signum(acc) != Math.signum(dy))) acc = 0f;
		acc += dy;

		// Down: a little to start, more for each next step. Up: each bar back after a little.
		float step = (acc < 0f) ? upStep : (state == FULL) ? firstStep : nextStep;
		if (Math.abs(acc) < step) return;
		int s = state;
		if (acc > 0f) {
			do s++; while ((s < HIDDEN) && isEmptyStep(s));
		} else {
			do s--; while ((s > FULL) && isEmptyStep(s));
		}
		if ((s >= FULL) && (s <= HIDDEN)) setState(s);
	}

	/** Whether there's nothing for {@code step} to hide or show, so it's passed over. */
	private boolean isEmptyStep(int step) {
		if (step == PANEL_HIDDEN) return !isPanelShown(a.getControlPanel());
		if (step == NAV_HIDDEN) return !isBottomNavShown(a.getNavBar());
		return false;
	}

	private static boolean isPanelShown(@Nullable ControlPanelView cp) {
		return (cp != null) && (cp.getVisibility() == VISIBLE) && !cp.isSuppressed() &&
				!cp.isVideoLook();
	}

	private static boolean isBottomNavShown(@Nullable NavBarView nb) {
		return (nb != null) && nb.isBottom() && (nb.getVisibility() == VISIBLE);
	}

	private void setState(int s) {
		if (s == state) return;
		state = s;
		acc = 0f;
		if (anim != null) anim.cancel();
		float from = progress;
		float to = s;
		BodyLayout b = a.getBody();
		if ((b == null) || !b.isLaidOut()) {
			progress = to;
			apply(true);
			return;
		}
		ValueAnimator va = ValueAnimator.ofFloat(from, to);
		va.setDuration(ANIM_MS);
		// Eases in and out (Material's standard curve): starting gently reads smoother than the
		// emphasized one, which jumped off the mark.
		va.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
		va.addUpdateListener(v -> {
			if (anim != v) return;
			progress = (float) v.getAnimatedValue();
			apply(true);
			if (v.getAnimatedFraction() >= 1f) anim = null;
		});
		anim = va;
		va.start();
	}

	/**
	 * Moves the bars, the floating buttons and the web pages to {@link #progress}; with
	 * {@code moved}, the tab content's insets follow (not needed when only re-applied before a
	 * frame: nothing moved then).
	 */
	private void apply(boolean moved) {
		ToolBarView tb = a.getToolBar();
		NavBarView nb = a.getNavBar();
		ControlPanelView cp = a.getControlPanel();
		if ((tb == null) || (cp == null)) return;
		float p = progress;
		float c = clamp(p); // How far the control panel is out of the way.
		float n = clamp(p - 1f); // The bottom nav bar.
		float t = clamp(p - 2f); // The tool bar.

		boolean bottomNav = isBottomNavShown(nb);
		boolean cpShown = isPanelShown(cp);
		View parent = (View) cp.getParent();
		int parentH = (parent != null) ? parent.getHeight() : 0;
		int floor = parentH - ((parent != null) ? parent.getPaddingBottom() : 0);

		// A bottom nav bar slides down off the screen, the control panel on it with it.
		float navDy = bottomNav ? n * (parentH - nb.getTop() + shadowPad) : 0f;
		if (bottomNav) {
			nb.setTranslationY(navDy);
			fade(nb, n);
		}

		// The control panel tucks away first: down behind a bottom nav bar, cut off at its top edge
		// as it goes (the nav bar has no background of its own to hide it), or off the screen.
		float fabDyNow = 0f;
		if (cpShown) {
			int h = cp.getHeight();
			if (bottomNav) {
				float tuck = c * h;
				cp.setTranslationY(tuck + navDy);
				if (tuck > 0f) {
					cpClip.set(0, 0, cp.getWidth(), Math.max(0, Math.round(h - tuck)));
					cp.setClipBounds(cpClip);
				} else {
					cp.setClipBounds(null);
				}
				// No fade while tucking: the pill behind it shrinks with the cut, which a fade would
				// outrun. It fades out with the nav bar it's tucked behind (all of it cut off by then).
				fade(cp, n);
				fabDyNow = c * (nb.getTop() - cp.getTop());
			} else {
				cp.setTranslationY(c * (parentH - cp.getTop() + shadowPad));
				cp.setClipBounds(null);
				fade(cp, c);
				fabDyNow = c * Math.max(0, floor - cp.getTop());
			}
		} else {
			// Gone meanwhile (playback stopped): it comes back where it belongs.
			cp.setTranslationY(0f);
			cp.setClipBounds(null);
			fade(cp, 0f);
		}

		// The floating buttons stay just above whatever of the bottom bars still shows.
		if (bottomNav) fabDyNow += n * Math.max(0, floor - nb.getTop());
		boolean fabsLeft = moveFab(0, a.getFloatingButton(), fabDyNow);
		FloatingButton[] extra = a.getExtraFloatingButtons();
		for (int i = 0; i < extra.length; i++) fabsLeft |= moveFab(i + 1, extra[i], fabDyNow);

		// The tool bar slides up off the screen, the web pages up into its room.
		if (tb.getMediator() != ToolBarView.Mediator.Invisible.instance) {
			// Far enough for whatever a tab hangs below its pill to clear the top as well: the
			// browser's tab strip follows the tool bar wherever it is (see BrowserTabs#syncPanel).
			toolBarDy = -t * (tb.getBottom() + belowToolBar);
			toolBarHidden = t;
			tb.setTranslationY(toolBarDy);
			fade(tb, t);
		} else {
			// No tool bar to move (a tab whose page draws its own): none of ours left on it either.
			toolBarDy = 0f;
			toolBarHidden = 0f;
			tb.setTranslationY(0f);
			fade(tb, 0f);
		}
		a.slideWebViews(t);

		applied = (p != 0f) || fabsLeft;
		if (moved) a.refreshContentInsets();
	}

	/**
	 * Fades {@code v} as it goes ({@code gone}: 0 = all there, 1 = gone), a little ahead of its
	 * slide so it's faded out by the time it leaves the screen. Through the transition alpha,
	 * which nothing else animates on the bars (their own show/hide and fades use the plain alpha);
	 * FloatingBarsView reads it too, so the pill behind fades along. Only from Android 10, where
	 * it can be set; before that the bars just slide.
	 */
	private static void fade(View v, float gone) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
		float alpha = 1f - clamp(gone * 1.25f);
		if (v.getTransitionAlpha() != alpha) v.setTransitionAlpha(alpha);
	}

	private static float clamp(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	/**
	 * How far {@code fab} is moved by this right now, on top of any translation of its own: where
	 * something else puts a floating button back in place (dragging turned off), it goes to here.
	 */
	public float getFabDy(@Nullable View fab) {
		if (fab == null) return 0f;
		if (fab == a.getFloatingButton()) return fabDy[0];
		FloatingButton[] extra = a.getExtraFloatingButtons();
		for (int i = 0; i < extra.length; i++) {
			if (extra[i] == fab) return fabDy[i + 1];
		}
		return 0f;
	}

	/**
	 * Moves a floating button by {@code dy} from where it would be without this, keeping whatever
	 * translation it has of its own. Left alone while one of the delegate's own glides runs on it
	 * (it restores the translation it started from); caught up once that's done.
	 *
	 * @return whether the button still carries an offset of ours
	 */
	private boolean moveFab(int i, @Nullable View f, float dy) {
		if (f == null) return false;
		float have = fabDy[i];
		if ((have != dy) && (f.getTag(me.aap.fermata.R.id.floating_bars) == null)) {
			f.setTranslationY(f.getTranslationY() - have + dy);
			fabDy[i] = have = dy;
		}
		return have != 0f;
	}

	/**
	 * The innermost view under the point that scrolls vertically (or is a WebView, whose page may
	 * scroll inside itself) -- a horizontal row inside a vertical list leads to the list.
	 */
	@Nullable
	private static View findTarget(View v, float x, float y) {
		if ((v.getVisibility() != VISIBLE) || !hit(v, x, y)) return null;
		if (v instanceof WebView) return v;
		if (v instanceof ViewGroup g) {
			for (int i = g.getChildCount() - 1; i >= 0; i--) {
				View t = findTarget(g.getChildAt(i), x, y);
				if (t != null) return t;
			}
		}
		return (v.canScrollVertically(1) || v.canScrollVertically(-1)) ? v : null;
	}

	private static final int[] loc = new int[2];

	private static boolean hit(View v, float x, float y) {
		v.getLocationOnScreen(loc);
		return (x >= loc[0]) && (x < loc[0] + v.getWidth() * v.getScaleX()) && (y >= loc[1])
				&& (y < loc[1] + v.getHeight() * v.getScaleY());
	}

	private void rememberOffset(View t) {
		if (t instanceof AbsListView lv) {
			lastFirstPos = lv.getFirstVisiblePosition();
			View c = lv.getChildAt(0);
			lastFirstTop = (c != null) ? c.getTop() : 0;
		} else {
			lastOffset = offset(t);
		}
	}

	/** How far {@code t} scrolled down since the last call, in pixels (negative: up). */
	private int scrollDelta(View t) {
		if (t instanceof AbsListView lv) {
			// A ListView keeps no scroll offset of its own: go by its first row.
			View c = lv.getChildAt(0);
			int pos = lv.getFirstVisiblePosition();
			int childTop = (c != null) ? c.getTop() : 0;
			int dy = (pos == lastFirstPos) ? (lastFirstTop - childTop) :
					(pos - lastFirstPos) * Math.max(1, (c != null) ? c.getHeight() : 1);
			lastFirstPos = pos;
			lastFirstTop = childTop;
			return dy;
		}
		int off = offset(t);
		int dy = off - lastOffset;
		lastOffset = off;
		return dy;
	}

	private static int offset(View t) {
		if (t instanceof RecyclerView rv) return rv.computeVerticalScrollOffset();
		return t.getScrollY();
	}
}
