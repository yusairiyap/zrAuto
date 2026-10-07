package me.aap.fermata.ui.view;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.lang.ref.WeakReference;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.UiUtils;

/**
 * The one place for the cards that appear in the middle of the top of the screen -- the network
 * warning, the data usage warning, "added to downloads" -- so that they all look and go the same
 * way: dropping in from the top over whatever is showing (on the phone's screen and on the car's),
 * swiped up to dismiss ({@link SwipeDismissLayout}), and never blocking anything underneath.
 */
public final class TopPopup {
	private static WeakReference<SwipeDismissLayout> shown = new WeakReference<>(null);

	private TopPopup() {
	}

	/**
	 * The holder for {@code card}, ready to be added to the activity's root view
	 * ({@code R.id.main_activity}): centered at the top, below the status bar, at most 600dp wide.
	 */
	public static SwipeDismissLayout holder(@NonNull ViewGroup root, @NonNull View card) {
		Context ctx = root.getContext();
		SwipeDismissLayout h = new SwipeDismissLayout(ctx);
		int pad = UiUtils.toIntPx(ctx, 8);
		h.setPadding(pad, 0, pad, pad);
		h.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(
				ConstraintLayout.LayoutParams.MATCH_CONSTRAINT, ConstraintLayout.LayoutParams.WRAP_CONTENT);
		lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.matchConstraintMaxWidth = UiUtils.toIntPx(ctx, 600) + 2 * pad;
		lp.topMargin = topInset(root) + pad;
		h.setLayoutParams(lp);
		h.setElevation(UiUtils.toIntPx(ctx, 26));
		return h;
	}

	/** Puts {@code holder} below the status bar as it is now (it changes with fullscreen video). */
	public static void layoutTop(View holder, ViewGroup root) {
		if (!(holder.getLayoutParams() instanceof ConstraintLayout.LayoutParams lp)) return;
		int top = topInset(root) + UiUtils.toIntPx(root.getContext(), 8);
		if (lp.topMargin == top) return;
		lp.topMargin = top;
		holder.setLayoutParams(lp);
	}

	private static int topInset(View root) {
		WindowInsetsCompat i = ViewCompat.getRootWindowInsets(root);
		if (i == null) return 0;
		return i.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).top;
	}

	/**
	 * Shows {@code card} at the top of {@code a}'s screen, replacing the popup already up.
	 *
	 * @param onDismissed run when it is swiped away (not when {@link #dismiss()} takes it down)
	 * @return whether there was a screen to show it on
	 */
	public static boolean show(MainActivityDelegate a, View card, @Nullable Runnable onDismissed) {
		dismiss();
		View main = a.findViewById(R.id.main_activity);
		if (!(main instanceof ConstraintLayout root)) return false;

		SwipeDismissLayout h = holder(root, card);
		h.setOnDismissed(() -> {
			remove(h);
			if (onDismissed != null) onDismissed.run();
		});
		layoutTop(h, root);
		root.addView(h);
		h.setAlpha(0f);
		h.setTranslationY(-UiUtils.toIntPx(root.getContext(), 48));
		h.setScaleX(0.96f);
		h.setScaleY(0.96f);
		h.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420)
				.setInterpolator(new OvershootInterpolator(1.4f)).start();
		shown = new WeakReference<>(h);
		return true;
	}

	/** Takes the popup down, if it is up. */
	public static void dismiss() {
		SwipeDismissLayout h = shown.get();
		shown = new WeakReference<>(null);
		if ((h == null) || (h.getParent() == null)) return;
		h.animate().cancel();
		h.animate().alpha(0f).translationY(-UiUtils.toIntPx(h.getContext(), 32)).setDuration(220)
				.setInterpolator(new DecelerateInterpolator()).withEndAction(() -> remove(h)).start();
	}

	/** Takes the popup down only if it is the one showing {@code card}. */
	public static void dismiss(View card) {
		SwipeDismissLayout h = shown.get();
		if ((h != null) && (card.getParent() == h)) dismiss();
	}

	/** Whether {@code card} is the popup showing. */
	public static boolean isShown(@Nullable View card) {
		SwipeDismissLayout h = shown.get();
		return (h != null) && (card != null) && (card.getParent() == h) && (h.getParent() != null);
	}

	public static boolean isShown() {
		SwipeDismissLayout h = shown.get();
		return (h != null) && (h.getParent() != null);
	}

	private static void remove(SwipeDismissLayout h) {
		if (h.getParent() instanceof ViewGroup g) g.removeView(h);
		if (shown.get() == h) shown = new WeakReference<>(null);
	}
}
