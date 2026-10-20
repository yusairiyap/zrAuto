package me.aap.fermata.ui.home;

import me.aap.fermata.ui.home.widget.HomeWidgets;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.aap.fermata.R;
import me.aap.fermata.ui.view.TopToast;

/**
 * The widgets of the Home tab, packed in the order of the model: each cell takes the first free
 * place that fits it, scanning along the scrolling direction. Scrolling down, the grid has a fixed
 * number of columns and grows downwards; scrolling sideways it has a fixed number of rows and
 * grows to the right. Because only the order is stored, the same layout fits any screen.
 * <p>
 * This class also does everything that moves cells in edit mode: dragging one to a new place (the
 * others glide out of the way), dropping it onto a cell of the same size to stack the two, and
 * resizing by the handle in a corner. Positions change with a glide (the old place is the start of
 * the animation), sizes with a little bounce.
 */
final class HomeGridLayout extends ViewGroup {
	interface Listener {
		/** The layout changed (order, sizes, stacks, options): the model was updated. */
		void onChanged();

		/** A long press wants edit mode on. */
		void onEditRequested();
	}

	private static final long GLIDE_MS = 260;
	private static final long SWAP_COOLDOWN_MS = 200;
	/** How long a finger may hover over a cell it could stack on before the cell makes way. */
	private static final long DWELL_MS = 380;

	private final HomeHost host;
	private final Listener listener;
	private final List<HomeCellView> order = new ArrayList<>();
	private final List<int[]> slots = new ArrayList<>();
	private final List<boolean[]> occupied = new ArrayList<>();
	private final int[] loc = new int[2];
	private HomeModel model;
	private boolean vertical = true;
	private int cross = 4;
	private int gap;
	private int unit = 100;
	private boolean editing;
	@Nullable
	private ViewGroup scroller;
	/** The size across the scrolling direction at the last layout; positions only glide while it stays. */
	private int lastCross;

	// Dragging.
	@Nullable
	private HomeCellView dragging;
	private float grabX;
	private float grabY;
	private float lastRawX;
	private float lastRawY;
	@Nullable
	private HomeCellView stackTarget;
	@Nullable
	private HomeCellView lastHit;
	private long lastSwap;
	private boolean orderChanged;
	@Nullable
	private HomeCellView hoverCell;
	private long hoverSince;
	private final Runnable dwellCheck = () -> {
		if (dragging != null) moveDrag(lastRawX, lastRawY);
	};
	private final Runnable autoScroll = this::autoScrollStep;
	private boolean autoScrolling;

	// Resizing.
	private int startW;
	private int startH;
	private float resizeRawX;
	private float resizeRawY;

	HomeGridLayout(Context ctx, HomeHost host, Listener listener) {
		super(ctx);
		this.host = host;
		this.listener = listener;
		setClipChildren(false);
		setClipToPadding(false);
		gap = HomeUi.dp(ctx, 10);
	}

	void setScroller(@Nullable ViewGroup s) {
		if (scroller != null) scroller.setOnScrollChangeListener(null);
		scroller = s;
		if (s != null) s.setOnScrollChangeListener((v, x, y, ox, oy) -> updateOnScreen());
	}

	/**
	 * Cells well outside the screen are made invisible, which is what tells their widgets to stop
	 * listening and drawing: a lyrics line or a power graph that nobody can see costs nothing.
	 */
	void updateOnScreen() {
		ViewGroup s = scroller;
		if ((s == null) || !s.isAttachedToWindow()) return;
		s.getLocationOnScreen(loc);
		int left = loc[0];
		int top = loc[1];
		int mx = vertical ? 0 : s.getWidth() / 2;
		int my = vertical ? s.getHeight() / 2 : 0;
		int[] at = new int[2];
		for (HomeCellView c : order) {
			if (c == dragging) continue;
			c.getLocationOnScreen(at);
			boolean on = (at[0] + c.getWidth() > left - mx) && (at[0] < left + s.getWidth() + mx) &&
					(at[1] + c.getHeight() > top - my) && (at[1] < top + s.getHeight() + my);
			c.setOnScreen(on);
		}
	}

	boolean isEditing() {
		return editing;
	}

	/** Scrolling down ({@code vertical}) with {@code cross} columns, or sideways with that many rows. */
	void setArrangement(boolean vertical, int cross, int gapPx) {
		if ((this.vertical == vertical) && (this.cross == cross) && (gap == gapPx)) return;
		this.vertical = vertical;
		this.cross = Math.max(1, cross);
		this.gap = gapPx;
		lastCross = 0; // A new arrangement: everything jumps to its place.
		arrange();
		requestLayout();
	}

	int getCross() {
		return cross;
	}

	boolean isVertical() {
		return vertical;
	}

	// ---------------------------------------------------------------------------------------------
	// Cells

	void bind(HomeModel model) {
		this.model = model;
		sync();
	}

	/** Makes the views match the model: new cells appear, removed ones go, changed stacks are rebuilt. */
	void sync() {
		if (model == null) return;
		Map<Long, HomeCellView> have = new HashMap<>();
		for (HomeCellView v : order) have.put(v.cell.id, v);
		Map<String, HomeWidgetView> pool = new HashMap<>();
		for (HomeModel.Cell mc : model.cells()) {
			HomeCellView v = have.get(mc.id);
			if ((v != null) && !sameItems(v, mc)) {
				for (HomeWidgetView w : v.stack.takeAll()) pool.put(w.getItem().id, w);
			}
		}
		List<HomeCellView> next = new ArrayList<>();
		boolean laidOut = getWidth() > 0;
		for (HomeModel.Cell mc : model.cells()) {
			HomeCellView v = have.remove(mc.id);
			boolean fresh = false;
			if (v == null) {
				v = new HomeCellView(getContext(), this, host, mc);
				addView(v);
				fresh = true;
			}
			if (v.stack.count() == 0) {
				for (HomeModel.Item it : mc.items) {
					HomeWidgetView w = pool.remove(it.id);
					if (w == null) w = makeWidget(it);
					v.addWidget(w);
				}
				v.stack.setTop(mc.top, false);
			}
			v.setEditing(editing, false);
			next.add(v);
			if (fresh && laidOut) {
				final HomeCellView nv = v;
				nv.setAlpha(0f);
				post(() -> {
					nv.animate().alpha(1f).setDuration(240).start();
					nv.pop();
				});
			}
		}
		for (HomeCellView gone : have.values()) {
			gone.animate().cancel();
			removeView(gone);
		}
		order.clear();
		order.addAll(next);
		arrange();
		reorderChildren();
		requestLayout();
	}

	private boolean sameItems(HomeCellView v, HomeModel.Cell mc) {
		List<HomeWidgetView> ws = v.stack.getWidgets();
		if (ws.size() != mc.items.size()) return false;
		for (int i = 0; i < ws.size(); i++) {
			if (!ws.get(i).getItem().id.equals(mc.items.get(i).id)) return false;
		}
		return true;
	}

	private HomeWidgetView makeWidget(HomeModel.Item it) {
		HomeWidgetType t = HomeWidgets.find(it.type);
		HomeWidgetView w = t.create(host);
		w.init(it);
		return w;
	}

	/** Fits every cell's size to what the screen has room for, and lets its widgets know. */
	private void arrange() {
		for (HomeCellView v : order) {
			int w = v.cell.w;
			int h = v.cell.h;
			if (vertical) w = Math.min(w, cross);
			else h = Math.min(h, cross);
			v.setEffectiveSize(Math.max(1, w), Math.max(1, h));
		}
	}

	private void reorderChildren() {
		boolean same = getChildCount() == order.size();
		for (int i = 0; same && (i < order.size()); i++) same = getChildAt(i) == order.get(i);
		if (same) return;
		detachAllViewsFromParent();
		for (int i = 0; i < order.size(); i++) {
			HomeCellView c = order.get(i);
			attachViewToParent(c, i, c.getLayoutParams() != null ? c.getLayoutParams() :
					generateDefaultLayoutParams());
		}
		invalidate();
	}

	/** Closes an options panel that is open inside a cell; true if one was. */
	boolean closePanels() {
		boolean any = false;
		for (HomeCellView c : order) {
			if (c.hasPanel()) {
				c.hidePanel(true);
				any = true;
			}
		}
		return any;
	}

	void restyle() {
		for (HomeCellView c : order) for (HomeWidgetView w : c.stack.getWidgets()) w.restyle();
	}

	void changed() {
		if (model != null) model.saveLater();
		listener.onChanged();
	}

	// ---------------------------------------------------------------------------------------------
	// Layout

	private void pack() {
		slots.clear();
		occupied.clear();
		for (HomeCellView c : order) {
			int sc = vertical ? c.cw : c.ch;
			int sm = vertical ? c.ch : c.cw;
			sc = Math.max(1, Math.min(sc, cross));
			int[] at = null;
			for (int m = 0; at == null; m++) {
				for (int cc = 0; (cc + sc) <= cross; cc++) {
					if (fits(m, cc, sm, sc)) {
						at = new int[]{m, cc};
						break;
					}
				}
			}
			for (int m = at[0]; m < (at[0] + sm); m++) {
				boolean[] row = row(m);
				for (int k = at[1]; k < (at[1] + sc); k++) row[k] = true;
			}
			slots.add(at);
		}
	}

	private boolean[] row(int m) {
		while (occupied.size() <= m) occupied.add(new boolean[cross]);
		return occupied.get(m);
	}

	private boolean fits(int m, int cc, int sm, int sc) {
		for (int i = m; i < (m + sm); i++) {
			boolean[] row = row(i);
			for (int k = cc; k < (cc + sc); k++) if (row[k]) return false;
		}
		return true;
	}

	private int span(int n) {
		return n * unit + (n - 1) * gap;
	}

	@Override
	protected void onMeasure(int wSpec, int hSpec) {
		int w = MeasureSpec.getSize(wSpec);
		int h = MeasureSpec.getSize(hSpec);
		int hMode = MeasureSpec.getMode(hSpec);
		int padW = getPaddingLeft() + getPaddingRight();
		int padH = getPaddingTop() + getPaddingBottom();
		if (vertical) {
			unit = Math.max(1, (w - padW - gap * (cross - 1)) / cross);
		} else if (hMode == MeasureSpec.UNSPECIFIED) {
			unit = HomeUi.dp(getContext(), 110);
		} else {
			unit = Math.max(1, (h - padH - gap * (cross - 1)) / cross);
		}
		pack();
		int main = 0;
		for (int i = 0; i < order.size(); i++) {
			HomeCellView c = order.get(i);
			int sm = vertical ? c.ch : c.cw;
			main = Math.max(main, slots.get(i)[0] + sm);
			c.measure(MeasureSpec.makeMeasureSpec(span(c.cw), MeasureSpec.EXACTLY),
					MeasureSpec.makeMeasureSpec(span(c.ch), MeasureSpec.EXACTLY));
		}
		int extent = (main == 0) ? 0 : main * unit + (main - 1) * gap;
		if (vertical) {
			setMeasuredDimension(w, extent + padH);
		} else {
			setMeasuredDimension(extent + padW, (hMode == MeasureSpec.UNSPECIFIED) ?
					cross * unit + (cross - 1) * gap + padH : h);
		}
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		int crossSize = vertical ? getWidth() : getHeight();
		boolean glide = host.animate() && (crossSize == lastCross) && (lastCross > 0);
		lastCross = crossSize;
		for (int i = 0; i < order.size(); i++) {
			HomeCellView c = order.get(i);
			int[] at = slots.get(i);
			int x = getPaddingLeft() + (vertical ? at[1] : at[0]) * (unit + gap);
			int y = getPaddingTop() + (vertical ? at[0] : at[1]) * (unit + gap);
			boolean had = c.getWidth() > 0;
			int oldL = c.getLeft();
			int oldT = c.getTop();
			c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
			if (c == dragging) {
				follow();
			} else if (glide && had && ((oldL != x) || (oldT != y))) {
				c.setTranslationX(c.getTranslationX() + (oldL - x));
				c.setTranslationY(c.getTranslationY() + (oldT - y));
				c.animate().translationX(0f).translationY(0f).setDuration(GLIDE_MS)
						.setInterpolator(new DecelerateInterpolator(1.5f)).start();
			}
		}
		post(this::updateOnScreen);
	}

	/** A short rise of the widgets when Home appears. */
	private boolean prepared;

	/** The widgets are put out of sight at once, to come in with {@link #playEntrance}. */
	void prepareEntrance() {
		prepared = host.animate();
		if (!prepared) return;
		float dy = HomeUi.dp(getContext(), 28);
		for (HomeCellView c : order) {
			c.animate().cancel();
			c.setAlpha(0f);
			c.setTranslationX(0f);
			c.setTranslationY(dy);
		}
	}

	void playEntrance() {
		boolean was = prepared;
		prepared = false;
		if (!host.animate()) {
			for (HomeCellView c : order) {
				c.setAlpha(1f);
				c.setTranslationY(0f);
			}
			return;
		}
		if (!was) prepareEntrance();
		prepared = false;
		float dy = HomeUi.dp(getContext(), 28);
		for (int i = 0; i < order.size(); i++) {
			HomeCellView c = order.get(i);
			c.animate().cancel();
			c.setAlpha(0f);
			c.setTranslationX(0f);
			c.setTranslationY(dy);
			c.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(i, 8) * 45L).setDuration(380)
					.setInterpolator(new DecelerateInterpolator(1.8f)).start();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Edit mode

	void setEditing(boolean on) {
		if (editing == on) return;
		editing = on;
		for (HomeCellView c : order) c.setEditing(on, true);
		if (!on) endDrag(true);
	}

	// ---------------------------------------------------------------------------------------------
	// Dragging

	void beginDrag(HomeCellView c, float rawX, float rawY) {
		if (!editing) listener.onEditRequested();
		dragging = c;
		lastHit = null;
		stackTarget = null;
		orderChanged = false;
		lastRawX = rawX;
		lastRawY = rawY;
		getLocationOnScreen(loc);
		grabX = rawX - loc[0] - (c.getLeft() + c.getTranslationX());
		grabY = rawY - loc[1] - (c.getTop() + c.getTranslationY());
		c.animate().cancel();
		c.setLifted(true);
	}

	void moveDrag(float rawX, float rawY) {
		HomeCellView d = dragging;
		if (d == null) return;
		lastRawX = rawX;
		lastRawY = rawY;
		follow();
		getLocationOnScreen(loc);
		float gx = rawX - loc[0];
		float gy = rawY - loc[1];

		HomeCellView hit = cellAt(gx, gy, d);
		long now = SystemClock.uptimeMillis();
		if (hit != null) {
			boolean stackable = canStack(d, hit);
			if (stackable && inCenter(hit, gx, gy)) {
				setStackTarget(hit);
				hoverCell = null;
			} else {
				setStackTarget(null);
				if ((hit != lastHit) && ((now - lastSwap) > SWAP_COOLDOWN_MS)) {
					if (!stackable) {
						moveTo(d, hit);
					} else {
						// The same size: this may be meant as a stack. The finger is given time to
						// reach the middle of the cell before the others make way.
						if (hoverCell != hit) {
							hoverCell = hit;
							hoverSince = now;
						}
						removeCallbacks(dwellCheck);
						if ((now - hoverSince) >= DWELL_MS) moveTo(d, hit);
						else postDelayed(dwellCheck, DWELL_MS - (now - hoverSince) + 20);
					}
				}
			}
		} else {
			setStackTarget(null);
			hoverCell = null;
			if (inSlot(d, gx, gy)) {
				// Back over its own place: the next cell it meets is a new one to swap with.
				lastHit = null;
			} else {
				HomeCellView near = nearest(gx, gy, d);
				if ((near != null) && ((now - lastSwap) > SWAP_COOLDOWN_MS)) moveTo(d, near);
			}
		}
		startAutoScroll();
	}

	private void follow() {
		HomeCellView d = dragging;
		if (d == null) return;
		getLocationOnScreen(loc);
		d.setTranslationX(lastRawX - loc[0] - grabX - d.getLeft());
		d.setTranslationY(lastRawY - loc[1] - grabY - d.getTop());
	}

	private void moveTo(HomeCellView d, HomeCellView hit) {
		int to = order.indexOf(hit);
		if (to < 0) return;
		order.remove(d);
		order.add(to, d);
		lastHit = hit;
		hoverCell = null;
		lastSwap = SystemClock.uptimeMillis();
		orderChanged = true;
		requestLayout();
	}

	@Nullable
	private HomeCellView cellAt(float x, float y, HomeCellView skip) {
		float g = gap / 2f;
		for (HomeCellView c : order) {
			if (c == skip) continue;
			if ((x >= c.getLeft() - g) && (x <= c.getRight() + g) && (y >= c.getTop() - g) &&
					(y <= c.getBottom() + g)) {
				return c;
			}
		}
		return null;
	}

	private boolean inSlot(HomeCellView c, float x, float y) {
		float g = gap / 2f;
		return (x >= c.getLeft() - g) && (x <= c.getRight() + g) && (y >= c.getTop() - g) &&
				(y <= c.getBottom() + g);
	}

	@Nullable
	private HomeCellView nearest(float x, float y, HomeCellView skip) {
		HomeCellView best = null;
		float bestD = Float.MAX_VALUE;
		for (HomeCellView c : order) {
			if (c == skip) continue;
			float dx = (c.getLeft() + c.getRight()) / 2f - x;
			float dy = (c.getTop() + c.getBottom()) / 2f - y;
			float dist = dx * dx + dy * dy;
			if (dist < bestD) {
				bestD = dist;
				best = c;
			}
		}
		return best;
	}

	private static boolean inCenter(HomeCellView c, float x, float y) {
		float cx = (c.getLeft() + c.getRight()) / 2f;
		float cy = (c.getTop() + c.getBottom()) / 2f;
		return (Math.abs(x - cx) < c.getWidth() * 0.36f) && (Math.abs(y - cy) < c.getHeight() * 0.36f);
	}

	/** Same size, and every widget being dropped can take the size of the stack it joins. */
	private static boolean canStack(HomeCellView d, HomeCellView t) {
		if ((d.cw != t.cw) || (d.ch != t.ch)) return false;
		for (HomeWidgetView w : d.stack.getWidgets()) {
			HomeWidgetType ty = w.getType();
			if ((t.cw < ty.minW) || (t.cw > ty.maxW) || (t.ch < ty.minH) || (t.ch > ty.maxH)) return false;
		}
		return true;
	}

	private void setStackTarget(@Nullable HomeCellView t) {
		if (stackTarget == t) return;
		if (stackTarget != null) stackTarget.setStackTarget(false);
		stackTarget = t;
		if (t != null) {
			t.setStackTarget(true);
			performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
		}
	}

	void endDrag(boolean cancel) {
		HomeCellView d = dragging;
		if (d == null) return;
		dragging = null;
		hoverCell = null;
		removeCallbacks(dwellCheck);
		stopAutoScroll();
		HomeCellView target = stackTarget;
		setStackTarget(null);
		d.setLifted(false);
		if ((target != null) && !cancel) {
			mergeInto(target, d);
			return;
		}
		d.animate().translationX(0f).translationY(0f).setDuration(220)
				.setInterpolator(new DecelerateInterpolator(1.5f)).start();
		if (orderChanged) commitOrder();
		orderChanged = false;
	}

	private void commitOrder() {
		List<HomeModel.Cell> cells = new ArrayList<>();
		for (HomeCellView c : order) cells.add(c.cell);
		model.setOrder(cells);
		reorderChildren();
		changed();
	}

	private void mergeInto(HomeCellView target, HomeCellView src) {
		List<HomeWidgetView> ws = src.stack.takeAll();
		for (HomeWidgetView w : ws) target.addWidget(w);
		model.stack(target.cell, src.cell);
		order.remove(src);
		src.animate().cancel();
		src.animate().alpha(0f).setDuration(160).withEndAction(() -> removeView(src)).start();
		target.stack.setTop(target.stack.count() - 1, true);
		target.cell.top = target.stack.count() - 1;
		target.stack.showDots();
		target.pop();
		reorderChildren();
		requestLayout();
		changed();
		model.save();
		TopToast.show(R.drawable.view_grid, R.string.home_stacked, target.stack.count());
	}

	// ---------------------------------------------------------------------------------------------
	// Auto scroll while dragging near the edge of the screen

	private void startAutoScroll() {
		if (autoScrolling || (scroller == null)) return;
		autoScrolling = true;
		postOnAnimation(autoScroll);
	}

	private void stopAutoScroll() {
		autoScrolling = false;
		removeCallbacks(autoScroll);
	}

	private void autoScrollStep() {
		ViewGroup s = scroller;
		if (!autoScrolling || (s == null) || (dragging == null)) {
			autoScrolling = false;
			return;
		}
		s.getLocationOnScreen(loc);
		float edge = HomeUi.dp(getContext(), 84);
		float max = HomeUi.dp(getContext(), 14);
		float pos = vertical ? lastRawY - loc[1] : lastRawX - loc[0];
		float len = vertical ? s.getHeight() : s.getWidth();
		float v = 0f;
		if (pos < edge) v = -max * Math.min(1f, (edge - pos) / edge);
		else if (pos > len - edge) v = max * Math.min(1f, (pos - (len - edge)) / edge);
		if (v != 0f) {
			int before = vertical ? s.getScrollY() : s.getScrollX();
			if (vertical) s.scrollBy(0, Math.round(v));
			else s.scrollBy(Math.round(v), 0);
			int after = vertical ? s.getScrollY() : s.getScrollX();
			if (before != after) moveDrag(lastRawX, lastRawY);
		}
		postOnAnimation(autoScroll);
	}

	// ---------------------------------------------------------------------------------------------
	// Resizing

	void startResize(HomeCellView c, float rawX, float rawY) {
		startW = c.cw;
		startH = c.ch;
		resizeRawX = rawX;
		resizeRawY = rawY;
		c.showBadge(c.cw + " × " + c.ch);
		c.setLifted(true);
	}

	void moveResize(HomeCellView c, float rawX, float rawY) {
		int step = unit + gap;
		int nw = startW + Math.round((rawX - resizeRawX) / step);
		int nh = startH + Math.round((rawY - resizeRawY) / step);
		nw = Math.max(c.minW(), Math.min(nw, c.maxW()));
		nh = Math.max(c.minH(), Math.min(nh, c.maxH()));
		if (vertical) nw = Math.min(nw, cross);
		else nh = Math.min(nh, cross);
		nw = Math.max(1, nw);
		nh = Math.max(1, nh);
		if ((nw == c.cw) && (nh == c.ch)) return;
		c.cell.w = nw;
		c.cell.h = nh;
		c.setEffectiveSize(nw, nh);
		c.showBadge(nw + " × " + nh);
		c.pop();
		performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
		requestLayout();
		changed();
	}

	void endResize(HomeCellView c) {
		c.showBadge(null);
		c.setLifted(false);
		model.save();
	}

	// ---------------------------------------------------------------------------------------------
	// Remove, unstack, options

	void removeTop(HomeCellView c) {
		HomeWidgetView w = c.topWidget();
		if (w == null) return;
		HomeModel.Item it = w.getItem();
		HomeModel.Cell cell = c.cell;
		int cellIndex = model.cells().indexOf(cell);
		int itemIndex = cell.items.indexOf(it);
		boolean cellGone = model.remove(cell, it);
		if (cellGone) {
			order.remove(c);
			c.animate().cancel();
			c.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(200)
					.withEndAction(() -> removeView(c)).start();
			requestLayout();
		} else {
			c.stack.remove(w);
			c.stack.setTop(cell.top, true);
			c.stack.syncControls();
		}
		changed();
		model.save();
		TopToast.show(w.getType().icon, getContext().getString(R.string.home_removed,
						getContext().getString(w.getType().name)), null, R.string.home_undo, () -> {
			model.restore(cell, it, cellIndex, itemIndex, cellGone);
			sync();
			changed();
			model.save();
		});
	}

	void unstack(HomeCellView c, HomeWidgetView w) {
		if (c.stack.count() < 2) return;
		c.hidePanel(false);
		model.unstack(c.cell, w.getItem());
		sync();
		changed();
		model.save();
	}

	void openOptions(HomeCellView c) {
		HomeWidgetView w = c.topWidget();
		if (w == null) return;
		if (c.hasPanel()) {
			c.hidePanel(true);
			return;
		}
		// Room for the settings inside the card: a block of 3 x 2 or more. Smaller ones get a card
		// of their own over the page.
		boolean inline = (c.cw >= 3) && (c.ch >= 2);
		if (inline) {
			c.showPanel(HomeOptionsPanel.build(host, this, c, w, () -> c.hidePanel(true)));
		} else {
			HomeOptionsPanel.showModal(host, this, c, w);
		}
	}
}
