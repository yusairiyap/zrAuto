package me.aap.fermata.ui.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

import me.aap.fermata.R;

/**
 * One place in the Home grid: a {@link HomeStackView} of the widgets there and, in edit mode, the
 * handles around it -- remove (top left), options (top right), resize (bottom right) -- and a
 * dashed outline. Everything about moving it lives in {@link HomeGridLayout}; this view only
 * tells the grid when it was picked up, from a long press or, in edit mode, a short hold.
 */
final class HomeCellView extends FrameLayout implements HomeStackView.Callback {
	private static final long LONG_PRESS_MS = 480;
	private static final long PICKUP_MS = 170;
	private static final float EDIT_SCALE = 0.968f;

	final HomeModel.Cell cell;
	private final HomeGridLayout grid;
	private final HomeHost host;
	final HomeStackView stack;
	private final ImageView removeBtn;
	private final ImageView optionsBtn;
	private final ImageView handle;
	private final TextView badge;
	private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final int slop;
	/** The size the grid shows it at (the model's, fitted to the screen). */
	int cw;
	int ch;
	private boolean editing;
	private float editAmount;
	private boolean stackTarget;
	private boolean lifted;
	@Nullable
	private ValueAnimator scaleAnim;
	@Nullable
	private ValueAnimator editAnim;
	@Nullable
	private View panel;

	// Touch tracking, see dispatchTouchEvent().
	private boolean pressed;
	private boolean grabbed;
	private float downRawX;
	private float downRawY;
	private float lastRawX;
	private float lastRawY;
	private long downTime;
	private final Runnable pickup = this::pickUp;

	HomeCellView(Context ctx, HomeGridLayout grid, HomeHost host, HomeModel.Cell cell) {
		super(ctx);
		this.grid = grid;
		this.host = host;
		this.cell = cell;
		slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
		setClipChildren(false);
		setWillNotDraw(false);

		stack = new HomeStackView(ctx, this);
		addView(stack, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

		removeBtn = chromeButton(ctx, me.aap.utils.R.drawable.close, R.string.home_remove);
		removeBtn.setOnClickListener(v -> grid.removeTop(this));
		addView(removeBtn, chromeParams(ctx, 36, Gravity.TOP | Gravity.START, 10));
		optionsBtn = chromeButton(ctx, R.drawable.settings, R.string.home_options);
		optionsBtn.setOnClickListener(v -> grid.openOptions(this));
		addView(optionsBtn, chromeParams(ctx, 36, Gravity.TOP | Gravity.END, 10));

		handle = chromeButton(ctx, R.drawable.home_resize, R.string.home_resize);
		handle.setOnClickListener(null);
		handle.setClickable(true);
		handle.setOnTouchListener((v, e) -> {
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN -> {
					// This cell (v's parent) must not take the drag over once the finger leaves the handle.
					if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
					grid.startResize(this, e.getRawX(), e.getRawY());
				}
				case MotionEvent.ACTION_MOVE -> grid.moveResize(this, e.getRawX(), e.getRawY());
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> grid.endResize(this);
				default -> {
				}
			}
			return true;
		});
		addView(handle, chromeParams(ctx, 42, Gravity.BOTTOM | Gravity.END, 8));

		badge = new TextView(ctx);
		badge.setTextColor(0xFFFFFFFF);
		badge.setTextSize(13);
		badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		badge.setPadding(HomeUi.dp(ctx, 12), HomeUi.dp(ctx, 5), HomeUi.dp(ctx, 12), HomeUi.dp(ctx, 5));
		badge.setBackground(HomeUi.round(0xCC000000, HomeUi.dp(ctx, 14)));
		badge.setVisibility(GONE);
		addView(badge, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
				Gravity.CENTER));

		outline.setStyle(Paint.Style.STROKE);
		outline.setStrokeWidth(HomeUi.dp(ctx, 2));
		outline.setPathEffect(new DashPathEffect(new float[]{HomeUi.dp(ctx, 7), HomeUi.dp(ctx, 5)}, 0));
		setChromeAlpha(0f);
	}

	private static LayoutParams chromeParams(Context ctx, int sizeDp, int gravity, int marginDp) {
		LayoutParams lp = new LayoutParams(HomeUi.dp(ctx, sizeDp), HomeUi.dp(ctx, sizeDp), gravity);
		int m = HomeUi.dp(ctx, marginDp);
		lp.setMargins(m, m, m, m);
		return lp;
	}

	private ImageView chromeButton(Context ctx, int icon, int desc) {
		ImageView b = new ImageView(ctx);
		b.setImageResource(icon);
		b.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
		int pad = HomeUi.dp(ctx, 9);
		b.setPadding(pad, pad, pad, pad);
		b.setBackground(HomeUi.pressable(0x44FFFFFF, 0xCC000000, HomeUi.dp(ctx, 21)));
		b.setContentDescription(ctx.getString(desc));
		b.setClickable(true);
		b.setFocusable(true);
		b.setElevation(HomeUi.dp(ctx, 3));
		return b;
	}

	// ---------------------------------------------------------------------------------------------
	// Content

	/** Adds a widget to the stack, at the size this cell has. */
	void addWidget(HomeWidgetView w) {
		if (cw > 0) w.setCells(cw, ch);
		stack.add(w);
	}

	@Nullable
	HomeWidgetView topWidget() {
		List<HomeWidgetView> l = stack.getWidgets();
		if (l.isEmpty()) return null;
		return l.get(Math.max(0, Math.min(stack.topIndex(), l.size() - 1)));
	}

	/** The grid fits the cell's size to the screen: widgets lay themselves out for it. */
	void setEffectiveSize(int w, int h) {
		cw = w;
		ch = h;
		for (HomeWidgetView v : stack.getWidgets()) v.setCells(w, h);
	}

	int minW() {
		int m = 1;
		for (HomeWidgetView v : stack.getWidgets()) m = Math.max(m, v.getType().minW);
		return m;
	}

	int minH() {
		int m = 1;
		for (HomeWidgetView v : stack.getWidgets()) m = Math.max(m, v.getType().minH);
		return m;
	}

	int maxW() {
		int m = 99;
		for (HomeWidgetView v : stack.getWidgets()) m = Math.min(m, v.getType().maxW);
		return m;
	}

	int maxH() {
		int m = 99;
		for (HomeWidgetView v : stack.getWidgets()) m = Math.min(m, v.getType().maxH);
		return m;
	}

	// ---------------------------------------------------------------------------------------------
	// HomeStackView.Callback

	@Override
	public boolean isEditing() {
		return editing;
	}

	@Override
	public boolean isCar() {
		return host.isCar();
	}

	@Override
	public boolean animations() {
		return host.animate();
	}

	@Override
	public void onTopChanged(int index) {
		cell.top = index;
		grid.changed();
	}

	// ---------------------------------------------------------------------------------------------
	// Edit mode

	void setEditing(boolean on, boolean animated) {
		if (editing == on) return;
		editing = on;
		if (editAnim != null) editAnim.cancel();
		float to = on ? 1f : 0f;
		if (!animated) {
			editAmount = to;
			setChromeAlpha(to);
			applyScale(false);
		} else {
			ValueAnimator a = ValueAnimator.ofFloat(editAmount, to);
			a.setDuration(220);
			a.addUpdateListener(v -> {
				editAmount = (float) v.getAnimatedValue();
				setChromeAlpha(editAmount);
			});
			editAnim = a;
			a.start();
			applyScale(true);
		}
		stack.syncControls();
		if (!on) hidePanel(false);
	}

	private void setChromeAlpha(float a) {
		for (View v : new View[]{removeBtn, optionsBtn, handle}) {
			v.setAlpha(a);
			v.setVisibility(a <= 0.01f ? INVISIBLE : VISIBLE);
		}
		invalidate();
	}

	void setLifted(boolean on) {
		if (lifted == on) return;
		lifted = on;
		setElevation(on ? HomeUi.dp(getContext(), 14) : 0f);
		applyScale(true);
	}

	/** Off screen (well past the scroller's edge): invisible, so the widgets inside go quiet. */
	void setOnScreen(boolean on) {
		int v = on ? VISIBLE : INVISIBLE;
		if (getVisibility() != v) setVisibility(v);
	}

	void setStackTarget(boolean on) {
		if (stackTarget == on) return;
		stackTarget = on;
		invalidate();
	}

	void showBadge(@Nullable String text) {
		if (text == null) {
			badge.setVisibility(GONE);
			return;
		}
		badge.setText(text);
		badge.setVisibility(VISIBLE);
	}

	/** A short bounce, for a size change. */
	void pop() {
		if (!host.animate()) return;
		ValueAnimator a = ValueAnimator.ofFloat(0.93f, targetScale());
		a.setDuration(320);
		a.setInterpolator(new OvershootInterpolator(2f));
		a.addUpdateListener(v -> {
			float s = (float) v.getAnimatedValue();
			setScaleX(s);
			setScaleY(s);
		});
		if (scaleAnim != null) scaleAnim.cancel();
		scaleAnim = a;
		a.start();
	}

	private float targetScale() {
		return lifted ? 1.05f : editing ? EDIT_SCALE : 1f;
	}

	private void applyScale(boolean animated) {
		float to = targetScale();
		if (scaleAnim != null) scaleAnim.cancel();
		if (!animated || !host.animate()) {
			setScaleX(to);
			setScaleY(to);
			return;
		}
		ValueAnimator a = ValueAnimator.ofFloat(getScaleX(), to);
		a.setDuration(180);
		a.setInterpolator(new DecelerateInterpolator());
		a.addUpdateListener(v -> {
			float s = (float) v.getAnimatedValue();
			setScaleX(s);
			setScaleY(s);
		});
		scaleAnim = a;
		a.start();
	}

	// ---------------------------------------------------------------------------------------------
	// Options panel shown inside the cell

	boolean hasPanel() {
		return panel != null;
	}

	void showPanel(View p) {
		hidePanel(false);
		panel = p;
		p.setAlpha(0f);
		addView(p, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
		p.animate().alpha(1f).setDuration(180).start();
	}

	void hidePanel(boolean animated) {
		View p = panel;
		if (p == null) return;
		panel = null;
		if (animated && host.animate()) {
			p.animate().alpha(0f).setDuration(140).withEndAction(() -> removeView(p)).start();
		} else {
			p.animate().cancel();
			removeView(p);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Touch: a long press anywhere picks the widget up (and starts edit mode); in edit mode a short
	// hold does, while a swipe scrolls the page. Done here, before the children see anything, so a
	// button under the finger doesn't matter.

	@Override
	public boolean dispatchTouchEvent(MotionEvent e) {
		boolean was = grabbed;
		track(e);
		if (was || grabbed) return true;
		return super.dispatchTouchEvent(e);
	}

	private void track(MotionEvent e) {
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				downRawX = lastRawX = e.getRawX();
				downRawY = lastRawY = e.getRawY();
				downTime = e.getDownTime();
				pressed = true;
				grabbed = false;
				removeCallbacks(pickup);
				if (panel != null) break;
				if (!editing && hitNoGrab(this, e.getX(), e.getY())) break;
				if (!(editing && hitChrome(e.getX(), e.getY()))) {
					postDelayed(pickup, editing ? PICKUP_MS : LONG_PRESS_MS);
				}
			}
			case MotionEvent.ACTION_MOVE -> {
				lastRawX = e.getRawX();
				lastRawY = e.getRawY();
				if (grabbed) {
					grid.moveDrag(lastRawX, lastRawY);
				} else if ((Math.abs(lastRawX - downRawX) > slop) || (Math.abs(lastRawY - downRawY) > slop)) {
					removeCallbacks(pickup);
				}
			}
			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				removeCallbacks(pickup);
				pressed = false;
				if (grabbed) {
					grabbed = false;
					grid.endDrag(e.getActionMasked() == MotionEvent.ACTION_CANCEL);
				}
			}
			default -> {
			}
		}
	}

	private void pickUp() {
		if (!pressed || grabbed) return;
		grabbed = true;
		if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
		performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
		// What was under the finger is told it's over.
		MotionEvent c = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
				MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
		super.dispatchTouchEvent(c);
		c.recycle();
		grid.beginDrag(this, lastRawX, lastRawY);
	}

	/** Whether (x, y) of {@code g} is on a control marked {@link HomeWidgetView#noGrab}. */
	private static boolean hitNoGrab(ViewGroup g, float x, float y) {
		for (int i = g.getChildCount() - 1; i >= 0; i--) {
			View c = g.getChildAt(i);
			if (c.getVisibility() != VISIBLE) continue;
			float cx = x - c.getLeft() + g.getScrollX();
			float cy = y - c.getTop() + g.getScrollY();
			if ((cx < 0) || (cy < 0) || (cx > c.getWidth()) || (cy > c.getHeight())) continue;
			if (c.getTag(R.id.home_no_grab) != null) return true;
			if ((c instanceof ViewGroup cg) && hitNoGrab(cg, cx, cy)) return true;
		}
		return false;
	}

	private boolean hitChrome(float x, float y) {
		for (View v : new View[]{removeBtn, optionsBtn, handle}) {
			if ((v.getVisibility() == VISIBLE) && (x >= v.getLeft()) && (x <= v.getRight()) &&
					(y >= v.getTop()) && (y <= v.getBottom())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent e) {
		// In edit mode the widgets only get dragged: the handles are all that reacts. (Not while the
		// options panel is open over it: that has controls of its own.)
		return editing && (panel == null) && !hitChrome(e.getX(), e.getY());
	}

	@Override
	public boolean onTouchEvent(MotionEvent e) {
		// Stays the target of the gesture, so the long press can be seen.
		return true;
	}

	@Override
	protected void dispatchDraw(Canvas c) {
		super.dispatchDraw(c);
		if ((editAmount <= 0.01f) && !stackTarget) return;
		float r = HomeWidgetView.CORNER_DP * getResources().getDisplayMetrics().density;
		rect.set(0, 0, getWidth(), getHeight());
		if (stackTarget) {
			tintPaint.setStyle(Paint.Style.FILL);
			tintPaint.setColor(HomeUi.alpha(host.palette().accent, 0.35f));
			c.drawRoundRect(rect, r, r, tintPaint);
		}
		float inset = outline.getStrokeWidth() / 2f;
		rect.inset(inset, inset);
		outline.setColor(stackTarget ? host.palette().accent : 0xFFFFFFFF);
		outline.setAlpha(Math.round((stackTarget ? 255 : 150) * Math.max(editAmount, stackTarget ? 1f : 0f)));
		c.drawRoundRect(rect, r, r, outline);
	}
}
