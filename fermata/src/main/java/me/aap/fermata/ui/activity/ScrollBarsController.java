package me.aap.fermata.ui.activity;

import static android.view.View.VISIBLE;
import static me.aap.utils.ui.UiUtils.toPx;

import android.animation.ValueAnimator;
import android.graphics.RectF;
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
import me.aap.fermata.ui.view.ToolBarPill;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.NavBarView;
import me.aap.utils.ui.view.ToolBarView;

/**
 * The One UI style bars that make way for the content while it scrolls: scrolling down first
 * shrinks tool_bar and the bottom bars (control_panel, plus nav_bar when it's at the bottom) into
 * compact pills -- the tool bar shows only its icons, the control panel row folds down into the
 * nav bar's pill -- and scrolling on hides them off the screen altogether. Scrolling back up
 * brings them back the same way, compact first, then in full; at the top of the content they are
 * always in full.
 * <p>
 * One value drives it all: {@link #progress}, 0 = full, 1 = compact, 2 = hidden, animated from
 * state to state. The bars are only moved and scaled (never resized or relaid out), so nothing
 * under them is resized either -- the YouTube page above all, whose player restarts on every
 * resize of its WebView. The tab content follows through its bottom padding (see
 * MainActivityDelegate#computeContentInsets, which reads the bars' live, transformed position),
 * so its last row still ends right above wherever the bars are; its top padding stays put, so a
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
 * puts the bars back in full.
 */
public final class ScrollBarsController implements ViewTreeObserver.OnScrollChangedListener,
		ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener {
	static final int FULL = 0;
	static final int COMPACT = 1;
	static final int HIDDEN = 2;
	/** How small the compact pills are, against their full size. */
	private static final float COMPACT_SCALE = 0.82f;
	private static final long ANIM_MS = 280;
	private final MainActivityDelegate a;
	private final float toCompact;
	private final float toHidden;
	private final float toCompactUp;
	private final float toFullUp;
	private final float touchSlop;
	private final float shadowPad;
	private final RectF pill = new RectF();
	private int state = FULL;
	private float progress;
	// Whether the bars carry any transformation of ours, so a full, settled state leaves them alone
	// (their own show/hide animations own their translation then).
	private boolean applied;
	private float toolBarDy;
	private float lastTitleAlpha = 1f;
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
		toCompact = toPx(a.getContext(), 16);
		toHidden = toPx(a.getContext(), 64);
		toCompactUp = toPx(a.getContext(), 16);
		toFullUp = toPx(a.getContext(), 96);
		touchSlop = ViewConfiguration.get(a.getContext()).getScaledTouchSlop();
		shadowPad = toPx(a.getContext(), 16);
	}

	/** Starts following the window's scrolls; once, when the activity's views are set up. */
	void attach(View root) {
		ViewTreeObserver o = root.getViewTreeObserver();
		o.addOnScrollChangedListener(this);
		o.addOnPreDrawListener(this);
		o.addOnGlobalLayoutListener(this);
	}

	/** How far tool_bar is moved up out of the way right now, by this alone. */
	float getToolBarDy() {
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
		// once. Setting a view's scale/translation to the value it already has is a no-op.
		if (applied && (anim == null)) apply(false);
		return true;
	}

	private void onScrolled(float dy) {
		if (!isEnabled()) return;
		if ((acc != 0f) && (Math.signum(acc) != Math.signum(dy))) acc = 0f;
		acc += dy;

		if (acc > 0f) {
			if ((state == FULL) && (acc >= toCompact)) setState(COMPACT);
			else if ((state == COMPACT) && (acc >= toHidden)) setState(HIDDEN);
		} else if (acc < 0f) {
			if ((state == HIDDEN) && (-acc >= toCompactUp)) setState(COMPACT);
			else if ((state == COMPACT) && (-acc >= toFullUp)) setState(FULL);
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
	 * Moves and scales the bars to {@link #progress}; with {@code moved}, the tab content's insets
	 * follow (not needed when only re-applied before a frame: nothing moved then).
	 */
	private void apply(boolean moved) {
		ToolBarView tb = a.getToolBar();
		NavBarView nb = a.getNavBar();
		ControlPanelView cp = a.getControlPanel();
		if ((tb == null) || (cp == null)) return;
		float p = progress;
		float c = Math.min(1f, p); // Compact fraction.
		float h = Math.max(0f, p - 1f); // Hidden fraction.
		float scale = 1f - (1f - COMPACT_SCALE) * c;

		// The tool bar: shrinks toward its top, around its pill's middle, with only its icons left,
		// then slides up off the screen.
		if (tb.getMediator() != ToolBarView.Mediator.Invisible.instance) {
			float px = ToolBarPill.getPillRect(tb, pill) ? pill.centerX() : tb.getWidth() / 2f;
			tb.setPivotX(px);
			tb.setPivotY(0f);
			tb.setScaleX(scale);
			tb.setScaleY(scale);
			float dy = -h * (tb.getBottom() + shadowPad);
			tb.setTranslationY(dy);
			toolBarDy = dy;
			if (p != 0f) {
				// A tab's title may be replaced while collapsed: every frame, not only on a change.
				View title = tb.findViewById(me.aap.utils.R.id.tool_bar_title);
				lastTitleAlpha = 1f - c;
				if ((title != null) && (title.getAlpha() != lastTitleAlpha)) title.setAlpha(lastTitleAlpha);
			}
		} else {
			toolBarDy = 0f;
		}

		// The bottom bars. With the nav bar at the bottom, the control panel row folds down into its
		// pill and the nav bar alone shrinks; else the control panel, a pill of its own, shrinks.
		boolean bottomNav = (nb != null) && nb.isBottom() && (nb.getVisibility() == VISIBLE);
		boolean cpShown = (cp.getVisibility() == VISIBLE) && !cp.isSuppressed() && !cp.isVideoLook();
		View parent = (View) cp.getParent();
		int parentH = (parent != null) ? parent.getHeight() : 0;
		int top = Integer.MAX_VALUE;
		if (bottomNav) top = nb.getTop();
		if (cpShown) top = Math.min(top, cp.getTop());
		float hideDy = (top == Integer.MAX_VALUE) ? 0f : h * (parentH - top + shadowPad);

		if (bottomNav) {
			nb.setPivotX(nb.getWidth() / 2f);
			nb.setPivotY(nb.getHeight());
			nb.setScaleX(scale);
			nb.setScaleY(scale);
			nb.setTranslationY(hideDy);
			if (cpShown) {
				// Sits on the nav bar's shrunk pill, as narrow, folding down into it -- never quite to
				// nothing, which would leave its transformation without an inverse for touches.
				float shrink = nb.getHeight() * (1f - scale);
				cp.setPivotX(cp.getWidth() / 2f);
				cp.setPivotY(cp.getHeight());
				cp.setScaleX(scale);
				cp.setScaleY(Math.max(0.01f, scale * (1f - c)));
				cp.setTranslationY(shrink + hideDy);
			}
		} else if (cpShown) {
			cp.setPivotX(cp.getWidth() / 2f);
			cp.setPivotY(cp.getHeight());
			cp.setScaleX(scale);
			cp.setScaleY(scale);
			cp.setTranslationY(hideDy);
		}
		// Gone meanwhile (playback stopped): it comes back as it should look, not folded away.
		if (!cpShown) resetView(cp);

		applied = (p != 0f);
		if (!applied) clear(tb, nb, cp);
		if (moved) a.refreshContentInsets();
	}

	/** Puts the bars back exactly as they were before any of this. */
	private void clear(ToolBarView tb, @Nullable NavBarView nb, ControlPanelView cp) {
		toolBarDy = 0f;
		if (tb.getMediator() != ToolBarView.Mediator.Invisible.instance) resetView(tb);
		if (lastTitleAlpha != 1f) {
			lastTitleAlpha = 1f;
			View title = tb.findViewById(me.aap.utils.R.id.tool_bar_title);
			if (title != null) title.setAlpha(1f);
		}
		if ((nb != null) && nb.isBottom()) resetView(nb);
		resetView(cp);
	}

	private static void resetView(View v) {
		v.setScaleX(1f);
		v.setScaleY(1f);
		v.setTranslationY(0f);
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
