package me.aap.fermata.ui.view;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.utils.ui.UiUtils;

/**
 * Holds a card that goes away when swiped up -- the dismissal of the popups that appear at the top
 * of the screen (see {@link TopPopup}). It follows the finger, fading as it goes; let go far enough
 * or fast enough and it carries on out of the screen, otherwise it springs back. A touch on a
 * button inside is still that button's: the swipe only takes over once the finger has moved
 * upward past the touch slop.
 */
public final class SwipeDismissLayout extends FrameLayout {
	private static final long OUT_MS = 180;
	private final int slop;
	private final float flingVelocity;
	private final float distance;
	@Nullable
	private Runnable onDismissed;
	@Nullable
	private VelocityTracker velocity;
	private float downX;
	private float downY;
	private boolean dragging;

	public SwipeDismissLayout(@NonNull Context ctx) {
		super(ctx);
		ViewConfiguration vc = ViewConfiguration.get(ctx);
		slop = vc.getScaledTouchSlop();
		flingVelocity = vc.getScaledMinimumFlingVelocity() * 4f;
		distance = UiUtils.toPx(ctx, 36);
		// The card's shadow reaches out of it.
		setClipChildren(false);
		setClipToPadding(false);
	}

	/** Called once the card has been swiped away (and is no longer visible). */
	public void setOnDismissed(@Nullable Runnable r) {
		onDismissed = r;
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent e) {
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				downX = e.getRawX();
				downY = e.getRawY();
				dragging = false;
				recycle();
				velocity = VelocityTracker.obtain();
				velocity.addMovement(e);
			}
			case MotionEvent.ACTION_MOVE -> {
				if (velocity != null) velocity.addMovement(e);
				float dy = e.getRawY() - downY;
				float dx = e.getRawX() - downX;
				if (!dragging && (dy < -slop) && (Math.abs(dy) > Math.abs(dx))) {
					dragging = true;
					if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
				}
			}
			case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
				if (!dragging) recycle();
			}
		}
		return dragging;
	}

	@Override
	public boolean onTouchEvent(MotionEvent e) {
		if (velocity != null) velocity.addMovement(e);

		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_MOVE -> {
				if (!dragging) return true;
				float dy = Math.min(0f, e.getRawY() - downY);
				setTranslationY(dy);
				setAlpha(Math.max(0.2f, 1f - (-dy / Math.max(1f, getHeight() * 1.5f))));
			}
			case MotionEvent.ACTION_UP -> {
				boolean away = false;
				if (dragging && (velocity != null)) {
					velocity.computeCurrentVelocity(1000);
					away = (getTranslationY() <= -distance) || (velocity.getYVelocity() < -flingVelocity);
				}
				finish(away);
			}
			case MotionEvent.ACTION_CANCEL -> finish(false);
		}
		// Anything that got here (the card's own background) is the swipe's to follow.
		return true;
	}

	private void finish(boolean away) {
		recycle();
		boolean was = dragging;
		dragging = false;
		if (!was) return;

		animate().cancel();
		if (away) {
			animate().translationY(-(getHeight() + UiUtils.toPx(getContext(), 24))).alpha(0f)
					.setDuration(OUT_MS).setInterpolator(new DecelerateInterpolator())
					.withEndAction(() -> {
						Runnable r = onDismissed;
						if (r != null) r.run();
					}).start();
		} else {
			animate().translationY(0f).alpha(1f).setDuration(160).start();
		}
	}

	private void recycle() {
		VelocityTracker v = velocity;
		velocity = null;
		if (v != null) v.recycle();
	}

	@Override
	protected void onDetachedFromWindow() {
		recycle();
		animate().cancel();
		super.onDetachedFromWindow();
	}

	/** Used by the owners of a card that is shown again: back to rest, ready to be swiped anew. */
	public void resetSwipe() {
		animate().cancel();
		dragging = false;
		setTranslationY(0f);
		setAlpha(1f);
	}
}
