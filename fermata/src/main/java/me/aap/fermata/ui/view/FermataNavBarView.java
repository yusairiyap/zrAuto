package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toPx;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.GestureDetectorCompat;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.view.GestureListener;
import me.aap.utils.ui.view.NavBarView;
import me.aap.utils.ui.view.NavButtonView;

/**
 * The nav bar, drawn as the lower row of the floating pill {@link FloatingBarsView} paints behind
 * it (so it has no background of its own), with a rounded "selected" indicator that slides from
 * the previously selected tab to the newly selected one instead of just jumping.
 *
 * @author Andrey Pavlenko
 */
public class FermataNavBarView extends NavBarView implements GestureListener {
	private static final long INDICATOR_ANIM_MS = 280;
	private final GestureDetectorCompat gestureDetector;
	private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF indicator = new RectF();
	private final RectF indicatorFrom = new RectF();
	private final RectF indicatorTo = new RectF();
	private final float indicatorInset;
	private final float indicatorRadius;
	@Nullable
	private View indicatorTarget;
	@Nullable
	private ValueAnimator indicatorAnim;

	public FermataNavBarView(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		gestureDetector = new GestureDetectorCompat(context, this);
		indicatorInset = toPx(context, 4);
		indicatorRadius = toPx(context, 20);
		initPill();
	}

	public FermataNavBarView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		gestureDetector = new GestureDetectorCompat(context, this);
		indicatorInset = toPx(context, 4);
		indicatorRadius = toPx(context, 20);
		initPill();
	}

	private void initPill() {
		// The pill behind it (FloatingBarsView) supplies the background, in this same color.
		setBackgroundColor(Color.TRANSPARENT);
		int tint = getTint();
		indicatorPaint.setColor((tint & 0x00FFFFFF) | 0x2E000000);
	}

	/**
	 * NavBarView sizes a bottom bar's width (or a side bar's height) as MATCH_PARENT, which ignores
	 * the margins that make it float; MATCH_CONSTRAINT between its parent-anchored constraints fills
	 * the same space while honoring them.
	 */
	@Override
	public void setSize(float scale) {
		super.setSize(scale);
		matchConstraints();
	}

	public void matchConstraints() {
		if (!(getLayoutParams() instanceof ConstraintLayout.LayoutParams lp)) return;
		boolean changed = false;
		if (isBottom()) {
			if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
				lp.width = 0;
				changed = true;
			}
		} else if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
			lp.height = 0;
			changed = true;
		}
		if (changed) setLayoutParams(lp);
	}

	@Override
	protected void dispatchDraw(@NonNull Canvas canvas) {
		View sel = findSelected();

		if (sel == null) {
			cancelIndicatorAnim();
			indicatorTarget = null;
			indicator.setEmpty();
		} else {
			setChildRect(sel, indicatorTo);

			if (sel != indicatorTarget) {
				View prev = indicatorTarget;
				indicatorTarget = sel;

				if ((prev == null) || indicator.isEmpty() || !isLaidOut()) {
					cancelIndicatorAnim();
					indicator.set(indicatorTo);
				} else {
					startIndicatorAnim();
				}
			} else if (indicatorAnim == null) {
				// Follows the selected tab through relayouts (resizing, a label appearing, etc.).
				indicator.set(indicatorTo);
			}

			if (!indicator.isEmpty()) {
				canvas.drawRoundRect(indicator, indicatorRadius, indicatorRadius, indicatorPaint);
			}
		}

		super.dispatchDraw(canvas);
	}

	/**
	 * Tabs get a pill-shaped press/hover/focus highlight, matching the selection indicator, in place
	 * of the rectangular one NavBarView.Mediator#initButton gives them (it runs before the add).
	 */
	@Override
	public void onViewAdded(View child) {
		super.onViewAdded(child);
		if (child instanceof NavButtonView) child.setBackgroundResource(R.drawable.nav_item_pill_bg);
	}

	/**
	 * A tab's setSelected() only redraws that tab itself; redraw this bar too, so dispatchDraw()
	 * above sees the new selection and starts sliding the indicator over to it.
	 */
	@Override
	public void childDrawableStateChanged(View child) {
		super.childDrawableStateChanged(child);
		invalidate();
	}

	@Nullable
	private View findSelected() {
		for (int i = 0, n = getChildCount(); i < n; i++) {
			View c = getChildAt(i);
			if (c.isSelected() && (c.getVisibility() == VISIBLE)) return c;
		}
		return null;
	}

	private void setChildRect(View c, RectF r) {
		r.set(c.getLeft() + indicatorInset, c.getTop() + indicatorInset,
				c.getRight() - indicatorInset, c.getBottom() - indicatorInset);
		if ((r.width() <= 0) || (r.height() <= 0)) r.setEmpty();
	}

	private void startIndicatorAnim() {
		cancelIndicatorAnim();
		indicatorFrom.set(indicator);
		ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
		a.setDuration(INDICATOR_ANIM_MS);
		a.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
		a.addUpdateListener(va -> {
			float f = (float) va.getAnimatedValue();
			View t = indicatorTarget;
			if (t != null) setChildRect(t, indicatorTo);
			indicator.set(lerp(indicatorFrom.left, indicatorTo.left, f),
					lerp(indicatorFrom.top, indicatorTo.top, f),
					lerp(indicatorFrom.right, indicatorTo.right, f),
					lerp(indicatorFrom.bottom, indicatorTo.bottom, f));
			if (f >= 1f) indicatorAnim = null;
			invalidate();
		});
		indicatorAnim = a;
		a.start();
	}

	private void cancelIndicatorAnim() {
		ValueAnimator a = indicatorAnim;
		if (a == null) return;
		indicatorAnim = null;
		a.cancel();
	}

	private static float lerp(float from, float to, float f) {
		return from + (to - from) * f;
	}

	@Override
	protected void onDetachedFromWindow() {
		cancelIndicatorAnim();
		super.onDetachedFromWindow();
	}

	protected boolean interceptTouchEvent(MotionEvent e) {
		gestureDetector.onTouchEvent(e);
		return super.onTouchEvent(e);
	}

	@Override
	protected MainActivityDelegate getActivity() {
		return MainActivityDelegate.get(getContext());
	}

	// The bottom bar passes horizontal swipes on to the control panel right above it (prev/next,
	// seeking). A side bar doesn't: a finger sliding off a left/right pill, or a swipe in from the
	// screen edge, isn't meant as a skip -- it used to jump to the previous track.
	@Override
	public boolean onSwipeLeft(MotionEvent e1, MotionEvent e2) {
		return isBottom() && getMainActivity().getControlPanel().onSwipeLeft(e1, e2);
	}

	@Override
	public boolean onSwipeRight(MotionEvent e1, MotionEvent e2) {
		return isBottom() && getMainActivity().getControlPanel().onSwipeRight(e1, e2);
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
		return isBottom() &&
				getMainActivity().getControlPanel().onScroll(e1, e2, distanceX, distanceY);
	}

	private MainActivityDelegate getMainActivity() {
		return MainActivityDelegate.get(getContext());
	}
}
