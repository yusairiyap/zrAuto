package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toPx;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.util.ArrayList;
import java.util.List;

import me.aap.utils.ui.view.FloatingButton;
import me.aap.utils.ui.view.NavBarView;

/**
 * The floating buttons' look, in one place.
 * <p>
 * As one pill (the default, see MainActivityPrefs#FAB_PILL; inspired by One UI's floating
 * actions): the buttons lose their own round fill and shadow and sit side by side, and this view
 * paints a single pill behind whichever of them are showing, in the nav bar pill's colours (over
 * fullscreen video, the see-through dark of the buttons in the middle of the picture, white icons).
 * Like FloatingBarsView it is a full-size painter under the buttons that never takes touches; the
 * pill is worked out from the buttons' live place, size and alpha before every frame and eases
 * toward it, so buttons coming and going, gliding around the control panel or fading, all move it
 * smoothly. A tap shows as a soft round highlight within the pill and a small press bounce.
 * <p>
 * As separate buttons: their theme look, or over fullscreen video the same see-through dark circle
 * and white icon as the middle buttons, with no shadow (it showed through the see-through fill).
 */
public class FabPillView extends View implements ViewTreeObserver.OnPreDrawListener {
	/** How far the pill eases toward where the buttons are, per frame. */
	private static final float EASE = 0.3f;
	private static final float PILL_PRESS_SCALE = 1.15f;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF target = new RectF();
	private final RectF cur = new RectF();
	private final RectF tmp = new RectF();
	private final List<FloatingButton> fabs;
	private final List<Look> looks = new ArrayList<>();
	private final int pillColor;
	private final int iconColor;
	private final float shadowRadius;
	private final float shadowDy;
	private boolean pill;
	private boolean videoLook;
	private float alpha;

	/** A button's own look, as the layout and theme gave it, to go back to. */
	private record Look(@Nullable ColorStateList bg, @Nullable ColorStateList icon,
											@Nullable ColorStateList ripple, float elevation,
											@Nullable ViewOutlineProvider outline, int marginEnd) {}

	private FabPillView(Context ctx, List<FloatingButton> fabs) {
		super(ctx);
		this.fabs = fabs;
		setClickable(false);
		setFocusable(false);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		int[] nb = NavBarView.resolveStyleColors(ctx);
		pillColor = (nb[1] & 0x00FFFFFF) | 0xF2000000;
		iconColor = nb[0] | 0xFF000000;
		shadowRadius = toPx(ctx, 12);
		shadowDy = toPx(ctx, 3);

		for (FloatingButton f : fabs) {
			int me = (f.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp) ? lp.getMarginEnd() : 0;
			looks.add(new Look(f.getBackgroundTintList(), f.getSupportImageTintList(),
					f.getRippleColorStateList(), f.getCompatElevation(), f.getOutlineProvider(), me));
		}
	}

	/**
	 * Puts the painter under the buttons ({@code fabs}, the primary one first; nulls skipped) and
	 * gives them the look for {@code pill}.
	 */
	public static FabPillView install(Context ctx, boolean pill, FloatingButton... fabs) {
		List<FloatingButton> l = new ArrayList<>(fabs.length);
		for (FloatingButton f : fabs) if (f != null) l.add(f);
		FabPillView v = new FabPillView(ctx, l);
		FloatingButton first = l.get(0);
		ViewGroup parent = (ViewGroup) first.getParent();
		// Every direct child of the root ConstraintLayout needs an id (see ControlPanelView).
		v.setId(View.generateViewId());
		ViewGroup.LayoutParams lp;
		if (parent instanceof ConstraintLayout) {
			ConstraintLayout.LayoutParams clp = new ConstraintLayout.LayoutParams(0, 0);
			clp.startToStart = clp.endToEnd = clp.topToTop = clp.bottomToBottom =
					ConstraintLayout.LayoutParams.PARENT_ID;
			lp = clp;
		} else {
			lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT);
		}
		// Drawn just before the buttons: under them, over the content and bars drawn before. No
		// elevation of its own: the buttons lose theirs as a pill (noShadow), and a raised pill would
		// then be drawn over them.
		int idx = parent.indexOfChild(first);
		for (FloatingButton f : l) idx = Math.min(idx, parent.indexOfChild(f));
		parent.addView(v, idx, lp);
		v.pill = pill;
		v.restyle();
		return v;
	}

	public boolean isPill() {
		return pill;
	}

	public void setPill(boolean pill) {
		if (this.pill == pill) return;
		this.pill = pill;
		cur.setEmpty();
		restyle();
	}

	/** Over fullscreen video or not: see the class comment. */
	public void setVideoLook(boolean videoLook) {
		if (this.videoLook == videoLook) return;
		this.videoLook = videoLook;
		restyle();
	}

	private void restyle() {
		for (int i = 0; i < fabs.size(); i++) {
			FloatingButton f = fabs.get(i);
			Look l = looks.get(i);

			if (pill) {
				int icon = videoLook ? VideoControlsOverlay.BUTTON_ICON : iconColor;
				f.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
				f.setSupportImageTintList(ColorStateList.valueOf(icon));
				f.setRippleColor(ColorStateList.valueOf((icon & 0x00FFFFFF) | 0x33000000));
				noShadow(f);
				f.setPressScale(PILL_PRESS_SCALE);
				// Side by side, touching: the pill is their one shared shape. The primary one keeps
				// its distance from the screen edge.
				if (i > 0) setMarginEnd(f, 0);
			} else if (videoLook) {
				f.setBackgroundTintList(ColorStateList.valueOf(VideoControlsOverlay.BUTTON_BG));
				f.setSupportImageTintList(ColorStateList.valueOf(VideoControlsOverlay.BUTTON_ICON));
				f.setRippleColor(ColorStateList.valueOf(VideoControlsOverlay.BUTTON_RIPPLE));
				noShadow(f);
				f.setPressScale(1.5f);
				setMarginEnd(f, l.marginEnd());
			} else {
				f.setBackgroundTintList(l.bg());
				f.setSupportImageTintList(l.icon());
				f.setRippleColor(l.ripple());
				f.setCompatElevation(l.elevation());
				f.setOutlineProvider(l.outline());
				f.setPressScale(1.5f);
				setMarginEnd(f, l.marginEnd());
			}
		}
		invalidate();
	}

	/**
	 * No shadow at all: with no outline there is nothing to cast one. A lower elevation alone left
	 * the theme's black outline shadow showing through a see-through fill as a dark smudge.
	 */
	private static void noShadow(FloatingButton f) {
		f.setCompatElevation(0f);
		f.setOutlineProvider(null);
	}

	private static void setMarginEnd(View v, int m) {
		if (!(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp)) return;
		if (lp.getMarginEnd() == m) return;
		lp.setMarginEnd(m);
		v.setLayoutParams(lp);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		getViewTreeObserver().addOnPreDrawListener(this);
	}

	@Override
	protected void onDetachedFromWindow() {
		getViewTreeObserver().removeOnPreDrawListener(this);
		super.onDetachedFromWindow();
	}

	@Override
	public boolean onPreDraw() {
		if (!pill) {
			if (alpha != 0f) {
				alpha = 0f;
				invalidate();
			}
			return true;
		}

		float a = 0f;
		target.setEmpty();
		for (FloatingButton f : fabs) {
			float fa = fabAlpha(f);
			if (fa <= 0f) continue;
			a = Math.max(a, fa);
			// Its resting size (the size setting), not the press bounce: the pill doesn't pulse.
			float s = f.getScale();
			float cx = f.getX() - getLeft() + f.getWidth() / 2f;
			float cy = f.getY() - getTop() + f.getHeight() / 2f;
			float hw = f.getWidth() * s / 2f;
			float hh = f.getHeight() * s / 2f;
			tmp.set(cx - hw, cy - hh, cx + hw, cy + hh);
			if (target.isEmpty()) target.set(tmp);
			else target.union(tmp);
		}

		boolean changed = false;
		if (!target.isEmpty()) {
			if (cur.isEmpty() || (alpha == 0f)) {
				// Appearing: right where the buttons are, fading in with them.
				cur.set(target);
				changed = true;
			} else {
				changed = ease(target);
			}
		}
		if (a != alpha) {
			alpha = a;
			changed = true;
		}
		if (changed) invalidate();
		return true;
	}

	/** One step of {@link #cur} toward {@code t}: true while it's still on its way. */
	private boolean ease(RectF t) {
		float l = step(cur.left, t.left);
		float tp = step(cur.top, t.top);
		float r = step(cur.right, t.right);
		float b = step(cur.bottom, t.bottom);
		boolean moved = (l != cur.left) || (tp != cur.top) || (r != cur.right) || (b != cur.bottom);
		cur.set(l, tp, r, b);
		return moved;
	}

	private static float step(float from, float to) {
		float d = to - from;
		return (Math.abs(d) < 0.5f) ? to : from + d * EASE;
	}

	private static float fabAlpha(View v) {
		if ((v.getVisibility() != VISIBLE) || (v.getWidth() == 0) || (v.getHeight() == 0)) return 0f;
		float a = v.getAlpha();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) a *= v.getTransitionAlpha();
		return Math.max(0f, Math.min(1f, a));
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		if (!pill || cur.isEmpty() || (alpha <= 0f)) return;
		float radius = Math.min(cur.width(), cur.height()) / 2f;
		int color = videoLook ? VideoControlsOverlay.BUTTON_BG : pillColor;
		paint.setColor(color);
		paint.setAlpha(Math.round(Color.alpha(color) * alpha));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			// A soft shadow under the opaque pill; none under the see-through one over video, where
			// it would show through.
			int sa = videoLook ? 0 : Math.round(0x40 * alpha);
			paint.setShadowLayer(shadowRadius, 0, shadowDy, sa << 24);
		}
		canvas.drawRoundRect(cur, radius, radius, paint);
	}
}
