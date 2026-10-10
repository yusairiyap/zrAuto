package me.aap.fermata.ui.activity;

import static android.view.View.VISIBLE;
import static me.aap.utils.ui.UiUtils.toPx;

import android.animation.ValueAnimator;
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
 * The One UI style bars that make way for the content while it scrolls: scrolling down a little
 * slides the bottom bars (control_panel, plus nav_bar when it's at the bottom) down off the
 * screen, scrolling on slides tool_bar up off it too. Scrolling back up brings them back the same
 * way, tool_bar first, then the bottom bars; at the top of the content everything is shown. The
 * floating buttons follow the bottom bars down to the screen's bottom edge and back up above them.
 * <p>
 * One value drives it all: {@link #progress}, 0 = all shown, 1 = bottom bars hidden, 2 = all
 * hidden, animated from state to state. The bars are only moved (never resized or relaid out), so
 * nothing under them is resized either. A web page (the YouTube tab, the browser) slides up into
 * the room tool_bar leaves, without being resized either: it is laid out as tall as if it started
 * at the top, its bottom running past the screen's by as much as the tool bar's room, until it
 * slides up (see MainActivityDelegate#insetWebViewTop) -- the YouTube player restarts on every
 * resize of its WebView. Lists follow through their bottom padding (see
 * MainActivityDelegate#computeContentInsets, which reads the bars' live position), so the last row
 * still ends right above wherever the bars are; their top padding stays put, so a list scrolled
 * back to its top never starts under the tool bar.
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
	static final int BOTTOM_HIDDEN = 1;
	static final int HIDDEN = 2;
	private static final long ANIM_MS = 280;
	private final MainActivityDelegate a;
	private final float toBottomHidden;
	private final float toHidden;
	private final float toBottomHiddenUp;
	private final float toFullUp;
	private final float touchSlop;
	private final float shadowPad;
	private final float belowToolBar;
	// The offset each floating button carries from this, on top of its own translation (a dragged
	// button's place), see moveFab().
	private final float[] fabDy = new float[6];
	private int state = FULL;
	private float progress;
	// Whether the bars carry any transformation of ours, so a full, settled state leaves them alone
	// (their own show/hide animations own their translation then).
	private boolean applied;
	private float toolBarDy;
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
		toBottomHidden = toPx(a.getContext(), 16);
		toHidden = toPx(a.getContext(), 64);
		toBottomHiddenUp = toPx(a.getContext(), 16);
		toFullUp = toPx(a.getContext(), 96);
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

		if (acc > 0f) {
			if ((state == FULL) && (acc >= toBottomHidden)) setState(BOTTOM_HIDDEN);
			else if ((state == BOTTOM_HIDDEN) && (acc >= toHidden)) setState(HIDDEN);
		} else if (acc < 0f) {
			if ((state == HIDDEN) && (-acc >= toBottomHiddenUp)) setState(BOTTOM_HIDDEN);
			else if ((state == BOTTOM_HIDDEN) && (-acc >= toFullUp)) setState(FULL);
		}
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
		va.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
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
		float b = Math.min(1f, p); // How far the bottom bars are out of the way.
		float t = Math.max(0f, p - 1f); // How far the tool bar is.

		// The bottom bars slide down off the screen, together: with the nav bar at the bottom, the
		// control panel sits on it as one pill.
		boolean bottomNav = (nb != null) && nb.isBottom() && (nb.getVisibility() == VISIBLE);
		boolean cpShown = (cp.getVisibility() == VISIBLE) && !cp.isSuppressed() && !cp.isVideoLook();
		View parent = (View) cp.getParent();
		int parentH = (parent != null) ? parent.getHeight() : 0;
		int top = Integer.MAX_VALUE;
		if (bottomNav) top = nb.getTop();
		if (cpShown) top = Math.min(top, cp.getTop());
		boolean anyBottom = top != Integer.MAX_VALUE;
		float hideDy = anyBottom ? b * (parentH - top + shadowPad) : 0f;
		if (bottomNav) nb.setTranslationY(hideDy);
		// Gone meanwhile (playback stopped): it comes back where it belongs.
		cp.setTranslationY(cpShown ? hideDy : 0f);

		// The floating buttons sit on the bottom bars: down to the screen's bottom edge with them.
		float fdy = (anyBottom && (parent != null)) ?
				b * Math.max(0, parentH - parent.getPaddingBottom() - top) : 0f;
		boolean fabsLeft = moveFab(0, a.getFloatingButton(), fdy);
		FloatingButton[] extra = a.getExtraFloatingButtons();
		for (int i = 0; i < extra.length; i++) fabsLeft |= moveFab(i + 1, extra[i], fdy);

		// The tool bar slides up off the screen, the web pages up into its room.
		if (tb.getMediator() != ToolBarView.Mediator.Invisible.instance) {
			// Far enough for whatever a tab hangs below its pill to clear the top as well: the
			// browser's tab strip follows the tool bar wherever it is (see BrowserTabs#syncPanel).
			toolBarDy = -t * (tb.getBottom() + belowToolBar);
			tb.setTranslationY(toolBarDy);
		} else {
			// No tool bar to move (a tab whose page draws its own): none of ours left on it either.
			toolBarDy = 0f;
			tb.setTranslationY(0f);
		}
		a.slideWebViews(t);

		applied = (p != 0f) || fabsLeft;
		if (moved) a.refreshContentInsets();
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
