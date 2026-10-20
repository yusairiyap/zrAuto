package me.aap.fermata.ui.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;

/**
 * Widgets of the same size stacked on top of each other. A swipe left or right slides the top one
 * away and brings the next up (looking like a pager with a little depth); a row of dots, away
 * again a moment after the last touch, says how many there are and which one shows. In car mode
 * the dots come with two buttons, as a steering wheel can't swipe. The stack goes round: past the
 * last widget comes the first again, and back from the first is the last.
 */
final class HomeStackView extends FrameLayout {
	private static final long SETTLE_MS = 280;
	private static final long DOTS_SHOW_MS = 1800;

	interface Callback {
		boolean isEditing();

		boolean isCar();

		boolean animations();

		/** The widget on top changed (swiped, or a dot was tapped). */
		void onTopChanged(int index);
	}

	private final Callback cb;
	private final List<HomeWidgetView> widgets = new ArrayList<>();
	private final Dots dots;
	private final ImageView prev;
	private final ImageView next;
	private final int slop;
	private final float minFling;
	private float pos;
	private boolean dragging;
	private boolean armed;
	private float downX;
	private float downY;
	private float startX;
	private float startPos;
	@Nullable
	private VelocityTracker velocity;
	@Nullable
	private ValueAnimator settle;
	private final Runnable hideDots = this::fadeDotsOut;

	HomeStackView(Context ctx, Callback cb) {
		super(ctx);
		this.cb = cb;
		ViewConfiguration vc = ViewConfiguration.get(ctx);
		slop = vc.getScaledTouchSlop();
		minFling = vc.getScaledMinimumFlingVelocity();
		setClipChildren(true);

		dots = new Dots(ctx);
		dots.setAlpha(0f);
		LayoutParams dlp = new LayoutParams(LayoutParams.WRAP_CONTENT, HomeUi.dp(ctx, 22),
				Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		dlp.bottomMargin = HomeUi.dp(ctx, 8);
		addView(dots, dlp);

		prev = arrow(ctx, true);
		next = arrow(ctx, false);
		LayoutParams plp = new LayoutParams(HomeUi.dp(ctx, 34), HomeUi.dp(ctx, 34),
				Gravity.BOTTOM | Gravity.START);
		plp.setMargins(HomeUi.dp(ctx, 10), 0, 0, HomeUi.dp(ctx, 4));
		addView(prev, plp);
		LayoutParams nlp = new LayoutParams(HomeUi.dp(ctx, 34), HomeUi.dp(ctx, 34),
				Gravity.BOTTOM | Gravity.END);
		nlp.setMargins(0, 0, HomeUi.dp(ctx, 10), HomeUi.dp(ctx, 4));
		addView(next, nlp);
		syncControls();
	}

	private ImageView arrow(Context ctx, boolean left) {
		ImageView b = new ImageView(ctx);
		b.setImageResource(me.aap.utils.R.drawable.chevron_right);
		b.setRotation(left ? 180f : 0f);
		b.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
		int pad = HomeUi.dp(ctx, 5);
		b.setPadding(pad, pad, pad, pad);
		b.setBackground(HomeUi.pressable(0x33FFFFFF, 0x66000000, HomeUi.dp(ctx, 17)));
		b.setContentDescription(ctx.getString(left ? R.string.home_stack_prev : R.string.home_stack_next));
		b.setClickable(true);
		b.setFocusable(true);
		b.setOnClickListener(v -> goBy(left ? -1 : 1));
		b.setVisibility(GONE);
		return b;
	}

	int count() {
		return widgets.size();
	}

	List<HomeWidgetView> getWidgets() {
		return widgets;
	}

	int topIndex() {
		int n = widgets.size();
		return (n == 0) ? 0 : ((Math.round(pos) % n) + n) % n;
	}

	/** {@code p} brought into 0 (inclusive) to the number of widgets (exclusive). */
	private float mod(float p) {
		int n = widgets.size();
		if (n == 0) return 0f;
		p %= n;
		return (p < 0) ? p + n : p;
	}

	/** The shortest way round the stack from here to {@code d} steps away, from -n/2 up to n/2. */
	private float wrap(float d) {
		int n = widgets.size();
		if (n < 2) return d;
		d %= n;
		if (d >= n / 2f) d -= n;
		else if (d < -n / 2f) d += n;
		return d;
	}

	void add(HomeWidgetView w) {
		// Under the dots and buttons, which are the last children.
		addView(w, widgets.size(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
		widgets.add(w);
		apply();
		syncControls();
	}

	/** Takes every widget out (for another stack to take over). */
	List<HomeWidgetView> takeAll() {
		List<HomeWidgetView> l = new ArrayList<>(widgets);
		for (HomeWidgetView w : l) removeView(w);
		widgets.clear();
		pos = 0;
		syncControls();
		return l;
	}

	void remove(HomeWidgetView w) {
		int at = widgets.indexOf(w);
		if (at < 0) return;
		widgets.remove(at);
		removeView(w);
		pos = Math.max(0, Math.min(Math.round(pos), widgets.size() - 1));
		if (settle != null) settle.cancel();
		apply();
		syncControls();
	}

	/** Sets the widget on top, with a slide when {@code animate}. */
	void setTop(int index, boolean animate) {
		if (widgets.isEmpty()) return;
		index = Math.max(0, Math.min(index, widgets.size() - 1));
		if (settle != null) settle.cancel();
		if (animate && cb.animations() && isLaidOut()) {
			int r = Math.round(pos);
			animateTo(r + Math.round(wrap(index - r)));
		} else {
			pos = index;
			apply();
		}
	}

	private void goBy(int d) {
		int n = widgets.size();
		if (n < 2) return;
		int r = Math.round(pos);
		showDots();
		animateTo(r + d);
		cb.onTopChanged((((r + d) % n) + n) % n);
	}

	/** Shows the dots now and takes them down again a moment after the last touch. */
	void showDots() {
		if (widgets.size() < 2) return;
		removeCallbacks(hideDots);
		dots.animate().cancel();
		dots.animate().alpha(1f).setDuration(160).start();
		if (!pinned()) postDelayed(hideDots, DOTS_SHOW_MS);
	}

	private boolean pinned() {
		return cb.isEditing();
	}

	private void fadeDotsOut() {
		if (pinned()) return;
		dots.animate().cancel();
		dots.animate().alpha(0f).setDuration(350).start();
	}

	/** The dots stay up in edit mode (and only then), and the buttons show in car mode. */
	void syncControls() {
		boolean many = widgets.size() > 1;
		boolean car = cb.isCar() && many;
		prev.setVisibility(car ? VISIBLE : GONE);
		next.setVisibility(car ? VISIBLE : GONE);
		dots.setVisibility(many ? VISIBLE : GONE);
		removeCallbacks(hideDots);
		if (many && pinned()) {
			dots.animate().cancel();
			dots.setAlpha(1f);
		} else if (!many) {
			dots.setAlpha(0f);
		} else if (dots.getAlpha() > 0f) {
			// Edit mode over: the dots that stayed up for it go away again after a moment.
			postDelayed(hideDots, DOTS_SHOW_MS);
		}
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		// A moment of dots when a stack first shows, so it is known to be one.
		if (widgets.size() > 1) post(this::showDots);
	}

	@Override
	protected void onDetachedFromWindow() {
		removeCallbacks(hideDots);
		if (settle != null) settle.cancel();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		super.onSizeChanged(w, h, ow, oh);
		apply();
	}

	// ---------------------------------------------------------------------------------------------
	// Position

	/** Lays the widgets out for the position: the front one slides off, the next comes up behind. */
	private void apply() {
		int n = widgets.size();
		int w = getWidth();
		for (int i = 0; i < n; i++) {
			HomeWidgetView v = widgets.get(i);
			float d = wrap(i - pos);
			float a = Math.abs(d);
			if (a >= 1f) {
				if (v.getVisibility() != INVISIBLE) v.setVisibility(INVISIBLE);
				continue;
			}
			if (v.getVisibility() != VISIBLE) v.setVisibility(VISIBLE);
			// The one leaving and the one coming dissolve into each other while sliding only part of the
			// way: no half-cut card left standing beside the other one.
			v.setTranslationX(d * w * 0.55f);
			float s = 1f - 0.06f * a;
			v.setScaleX(s);
			v.setScaleY(s);
			float f = 1f - a;
			v.setAlpha(f * f * (3f - 2f * f));
		}
		dots.setPos(mod(pos));
	}

	private void animateTo(int index) {
		if (settle != null) settle.cancel();
		float from = pos;
		ValueAnimator a = ValueAnimator.ofFloat(from, index);
		a.setDuration(SETTLE_MS);
		a.setInterpolator(new DecelerateInterpolator(1.6f));
		a.addUpdateListener(v -> {
			pos = (float) v.getAnimatedValue();
			apply();
		});
		a.addListener(new android.animation.AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(android.animation.Animator animation) {
				if (settle == a) settle = null;
				// Back into 0..n-1 after going round the end.
				pos = mod(pos);
				apply();
			}
		});
		settle = a;
		a.start();
	}

	// ---------------------------------------------------------------------------------------------
	// Touch

	@Override
	public boolean onInterceptTouchEvent(MotionEvent e) {
		if ((widgets.size() < 2) || cb.isEditing()) return false;
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				downX = e.getX();
				downY = e.getY();
				armed = true;
				dragging = false;
				if (settle != null) settle.cancel();
			}
			case MotionEvent.ACTION_MOVE -> {
				if (!armed || dragging) return dragging;
				float dx = e.getX() - downX;
				float dy = e.getY() - downY;
				if ((Math.abs(dx) > slop) && (Math.abs(dx) > Math.abs(dy) * 1.3f)) {
					startDrag(e);
					return true;
				}
				if (Math.abs(dy) > slop) armed = false;
			}
			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armed = false;
			default -> {
			}
		}
		return false;
	}

	private void startDrag(MotionEvent e) {
		dragging = true;
		startX = e.getX();
		startPos = pos;
		if (velocity != null) velocity.recycle();
		velocity = VelocityTracker.obtain();
		velocity.addMovement(e);
		if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
		showDots();
	}

	/**
	 * A touch no widget under the finger took (the text of a card, say) ends up here, because the stack
	 * says yes to it: so the whole card swipes, not just the parts that have a click of their own.
	 */
	@Override
	public boolean onTouchEvent(MotionEvent e) {
		if (!dragging) {
			if ((widgets.size() < 2) || cb.isEditing()) return false;
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN -> {
					downX = e.getX();
					downY = e.getY();
					armed = true;
					if (settle != null) settle.cancel();
					return true;
				}
				case MotionEvent.ACTION_MOVE -> {
					if (!armed) return true;
					float dx = e.getX() - downX;
					float dy = e.getY() - downY;
					if ((Math.abs(dx) > slop) && (Math.abs(dx) > Math.abs(dy) * 1.3f)) {
						startDrag(e);
						// The finger has already moved by the slop: the card follows from where it started.
						startX = downX;
					} else if (Math.abs(dy) > slop) {
						armed = false;
						return true;
					}
					if (!dragging) return true;
				}
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					armed = false;
					return true;
				}
				default -> {
					return true;
				}
			}
		}
		if (velocity != null) velocity.addMovement(e);
		int w = Math.max(1, getWidth());
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_MOVE -> {
				// No ends: the stack goes round (see apply).
				pos = startPos - (e.getX() - startX) / w;
				apply();
				showDots();
			}
			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				dragging = false;
				armed = false;
				float vx = 0f;
				if (velocity != null) {
					velocity.computeCurrentVelocity(1000);
					vx = velocity.getXVelocity();
					velocity.recycle();
					velocity = null;
				}
				int from = Math.round(startPos);
				float projected = pos - ((Math.abs(vx) > minFling) ? vx / w * 0.25f : 0f);
				int target = Math.round(projected);
				target = Math.max(from - 1, Math.min(from + 1, target));
				if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) target = Math.round(pos);
				animateTo(target);
				int n = widgets.size();
				if (target != from) cb.onTopChanged(((target % n) + n) % n);
				showDots();
			}
			default -> {
			}
		}
		return true;
	}

	// ---------------------------------------------------------------------------------------------

	/** The dots: the one on top is a longer pill; a translucent dark capsule behind keeps them readable. */
	private final class Dots extends View {
		private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF r = new RectF();
		private final float dot = HomeUi.dp(getContext(), 6);
		private final float wide = HomeUi.dp(getContext(), 16);
		private final float gap = HomeUi.dp(getContext(), 6);
		private float p;

		Dots(Context ctx) {
			super(ctx);
		}

		void setPos(float p) {
			this.p = p;
			invalidate();
		}

		@Override
		protected void onMeasure(int wm, int hm) {
			int n = widgets.size();
			float w = (n <= 0) ? 0 : (n - 1) * (dot + gap) + wide + dot * 2;
			setMeasuredDimension(Math.round(w), resolveSize(HomeUi.dp(getContext(), 22), hm));
		}

		@Override
		protected void onDraw(Canvas c) {
			int n = widgets.size();
			if (n < 2) return;
			float h = getHeight();
			r.set(0, h / 2f - dot, getWidth(), h / 2f + dot);
			r.inset(0f, -dot / 4f);
			fill.setColor(0x55000000);
			c.drawRoundRect(r, h / 2f, h / 2f, fill);
			float x = dot;
			for (int i = 0; i < n; i++) {
				float dist = Math.abs(i - p);
				float near = Math.max(0f, 1f - Math.min(dist, n - dist));
				float w = dot + (wide - dot) * near;
				fill.setColor(near > 0.5f ? 0xFFFFFFFF : 0x99FFFFFF);
				fill.setAlpha(Math.round(255 * (0.55f + 0.45f * near)));
				r.set(x, h / 2f - dot / 2f, x + w, h / 2f + dot / 2f);
				c.drawRoundRect(r, dot / 2f, dot / 2f, fill);
				x += w + gap;
			}
		}
	}
}
