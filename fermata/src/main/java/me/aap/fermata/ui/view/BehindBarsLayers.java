package me.aap.fermata.ui.view;

import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * Lets a tab's background layers run on behind the floating bars, like the Music tab's blurred
 * cover does (see {@code MusicPlayerFragment#extendBackground}): out under a left/right nav pill,
 * and, from Android 15 on, under the transparent status bar and the gesture area too.
 * <p>
 * The layers must be direct children of a FrameLayout (the tab's root) with margin layout params;
 * this class only ever changes their margins (negative, reaching past the root's edges) and, for
 * {@link #setShown}, their alpha. Nothing is clipped at the root's own bounds: the
 * containers between the root and {@code main_activity} are unclipped for as long as the layers are
 * shown (see {@link #update}), and put back as they were once they're not.
 * <p>
 * Call {@link #attach} once the root has a parent, and {@link #detach} when its view is destroyed.
 */
public final class BehindBarsLayers {
	private final MainActivityDelegate activity;
	private final View root;
	private final View[] layers;
	private final int[] loc = new int[2];
	private final int[] loc2 = new int[2];
	private final int[] barExt = new int[2];
	@Nullable
	private ViewGroup[] clipViews;
	private boolean[] clipChildrenWas;
	private boolean[] clipToPaddingWas;
	private boolean active = true;
	private boolean attached;
	private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
		update();
		return true;
	};

	public BehindBarsLayers(@NonNull MainActivityDelegate activity, @NonNull View root,
													@NonNull View... layers) {
		this.activity = activity;
		this.root = root;
		this.layers = layers;
	}

	/** Starts following the root every frame. */
	public void attach() {
		if (attached) return;
		attached = true;
		root.getViewTreeObserver().addOnPreDrawListener(preDraw);
	}

	/** Stops following the root, and gives the containers their clipping back. */
	public void detach() {
		if (attached) {
			attached = false;
			ViewTreeObserver vto = root.getViewTreeObserver();
			if (vto.isAlive()) vto.removeOnPreDrawListener(preDraw);
		}
		restoreClipping();
	}

	/**
	 * Whether the layers are in use. While not, they aren't extended and the containers keep their
	 * normal clipping, so whatever else the tab shows can't spill under the bars.
	 */
	public void setActive(boolean active) {
		if (this.active == active) return;
		this.active = active;
		if (!active) restoreClipping();
	}

	/** Fades the layers in or out: {@code shown} 1 = fully there, 0 = gone. */
	public void setShown(float shown) {
		float f = Math.max(0f, Math.min(1f, shown));
		for (View v : layers) {
			if (v == null) continue;
			v.setAlpha(f);
			v.setVisibility(f <= 0f ? View.INVISIBLE : View.VISIBLE);
		}
	}

	public void update() {
		if (!active || !root.isAttachedToWindow()) return;
		View body = activity.getBody();
		int left = 0;
		int right = 0;

		if ((body != null) && body.isAttachedToWindow()) {
			root.getLocationOnScreen(loc);
			body.getLocationOnScreen(loc2);
			left = Math.max(0, loc[0] - loc2[0]);
			right = Math.max(0, (loc2[0] + body.getWidth()) - (loc[0] + root.getWidth()));
		}

		barExtensions(barExt);
		int top = barExt[0];
		int bottom = barExt[1];

		for (View v : layers) {
			if ((v == null) || !(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp)) {
				continue;
			}
			if ((lp.leftMargin == -left) && (lp.rightMargin == -right) && (lp.topMargin == -top)
					&& (lp.bottomMargin == -bottom)) {
				continue;
			}
			lp.leftMargin = -left;
			lp.rightMargin = -right;
			lp.topMargin = -top;
			lp.bottomMargin = -bottom;
			lp.setMarginStart(-left);
			lp.setMarginEnd(-right);
			v.setLayoutParams(lp);
		}
	}

	/**
	 * How far the layers reach past the root's top and bottom edges: under the transparent status
	 * bar and the system navigation area. Only from Android 15, where the window is already drawn
	 * edge to edge; before that the system owns the bars and nothing can show there. Zero on the
	 * car screen.
	 */
	private void barExtensions(int[] out) {
		out[0] = 0;
		out[1] = 0;
		if (VERSION.SDK_INT < VERSION_CODES.VANILLA_ICE_CREAM) return;
		if (activity.isCarActivity()) return;
		root.getLocationInWindow(loc);
		int top = Math.max(0, loc[1]);
		int bottom = Math.max(0, root.getRootView().getHeight() - (loc[1] + root.getHeight()));
		// Only while the root is on screen: with the containers unclipped, every other tab's
		// content would scroll out behind the bars too, which those tabs keep opaque.
		if (((top == 0) && (bottom == 0)) || !root.isShown()) restoreClipping();
		else unclipForStatusBar();
		out[0] = top;
		out[1] = bottom;
	}

	/**
	 * The bar's area is main_activity's own top padding: every container between it and the root
	 * has to let the layers draw out into it. The previous settings are kept, for
	 * {@link #restoreClipping()}.
	 */
	private void unclipForStatusBar() {
		if (clipViews != null) return;
		List<ViewGroup> l = new ArrayList<>();
		for (ViewParent p = root.getParent(); p instanceof ViewGroup g; p = g.getParent()) {
			l.add(g);
			if (g.getId() == R.id.main_activity) break;
		}
		ViewGroup[] views = l.toArray(new ViewGroup[0]);
		clipChildrenWas = new boolean[views.length];
		clipToPaddingWas = new boolean[views.length];
		for (int i = 0; i < views.length; i++) {
			clipChildrenWas[i] = views[i].getClipChildren();
			clipToPaddingWas[i] = views[i].getClipToPadding();
			views[i].setClipChildren(false);
			views[i].setClipToPadding(false);
		}
		clipViews = views;
	}

	private void restoreClipping() {
		ViewGroup[] views = clipViews;
		if (views == null) return;
		clipViews = null;
		for (int i = 0; i < views.length; i++) {
			views[i].setClipChildren(clipChildrenWas[i]);
			views[i].setClipToPadding(clipToPaddingWas[i]);
		}
	}
}
