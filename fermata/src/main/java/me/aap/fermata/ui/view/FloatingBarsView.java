package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toPx;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.view.NavBarView;

/**
 * Paints the floating pill behind nav_bar and control_panel, plus the soft fade that tab content
 * dissolves into on the pill's side of the screen. Neither bar draws a background of its own any
 * more; this view sits between body_layout and the bars in every main_activity layout variant, so
 * plain drawing order puts the pill above the content and below the bars. It never takes touches.
 * <p>
 * With a bottom nav bar, a visible control panel joins the nav bar as a second row of the same
 * pill (the pill grows upward to take it in, and shrinks back when it goes). With a left/right
 * nav bar, the nav bar is a vertical pill on its side and the control panel floats as a pill of
 * its own along the bottom of the content. Either bar alone (e.g. only the control panel over a
 * video with the other bars hidden) still gets its own pill.
 * <p>
 * Tracks the bars' actual bounds after every layout pass and animates the pill between the old
 * and new shape, so bars appearing, disappearing or resizing morph the pill instead of snapping it.
 */
public class FloatingBarsView extends View implements ViewTreeObserver.OnGlobalLayoutListener {
	private static final long ANIM_MS = 260;
	private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint fadePaint = new Paint();
	private final Pill main = new Pill();
	private final Pill aux = new Pill();
	private final RectF tmp = new RectF();
	private final float maxRadius;
	private final float fadeLen;
	private final float sideFadeLen;
	private final float dividerInset;
	private final int bgColor;
	private final int pillColor;
	private boolean mergedDivider;
	private float dividerPos = -1;
	private int fadeKey;
	@Nullable
	private ValueAnimator anim;

	public FloatingBarsView(@NonNull Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
		setClickable(false);
		setFocusable(false);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		maxRadius = toPx(ctx, 28);
		fadeLen = toPx(ctx, 24);
		// A side bar's content is padded clear of the pill by the pill's own margin (see
		// MainActivityDelegate#syncSideNavInset), so fade out only across that gap, not the content.
		sideFadeLen = toPx(ctx, 12);
		dividerInset = toPx(ctx, 20);

		int[] nb = NavBarView.resolveStyleColors(ctx);
		// Mostly opaque, so content passing underneath just barely shows through.
		pillColor = (nb[1] & 0x00FFFFFF) | 0xF2000000;
		pillPaint.setColor(pillColor);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			pillPaint.setShadowLayer(toPx(ctx, 12), 0, toPx(ctx, 3), 0x40000000);
		}
		dividerPaint.setColor((nb[0] & 0x00FFFFFF) | 0x26000000);
		dividerPaint.setStrokeWidth(Math.max(1f, toPx(ctx, 1)));

		TypedArray ta = ctx.obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
		bgColor = ta.getColor(0, Color.BLACK);
		ta.recycle();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		getViewTreeObserver().addOnGlobalLayoutListener(this);
	}

	@Override
	protected void onDetachedFromWindow() {
		getViewTreeObserver().removeOnGlobalLayoutListener(this);
		if (anim != null) {
			anim.cancel();
			anim = null;
		}
		super.onDetachedFromWindow();
	}

	@Override
	public void onGlobalLayout() {
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
		if (a == null) return;
		NavBarView nb = a.getNavBar();
		View cp = a.getControlPanel();
		if ((nb == null) || (cp == null)) return;

		boolean nbShown = barShown(nb);
		boolean cpShown = barShown(cp);
		RectF mainTarget = new RectF();
		RectF auxTarget = new RectF();
		boolean merged = false;

		if (nb.isBottom()) {
			if (nbShown) {
				bounds(nb, mainTarget);
				if (cpShown) {
					bounds(cp, tmp);
					mainTarget.union(tmp);
					merged = true;
				}
			} else if (cpShown) {
				bounds(cp, mainTarget);
			}
		} else {
			if (nbShown) bounds(nb, mainTarget);
			if (cpShown) bounds(cp, auxTarget);
		}

		float div = merged ? (nb.getTop() - getTop()) : -1;
		int key = (nbShown ? 1 : 0) | (a.isVideoMode() ? 2 : 0) | (nb.getPosition() << 2);
		if ((key != fadeKey) || (merged != mergedDivider) || (div != dividerPos)) {
			fadeKey = key;
			mergedDivider = merged;
			dividerPos = div;
			invalidate();
		}

		if (main.to.equals(mainTarget) && aux.to.equals(auxTarget)) return;
		main.retarget(mainTarget);
		aux.retarget(auxTarget);
		animatePills();
	}

	private static boolean barShown(View v) {
		return (v.getVisibility() == VISIBLE) && (v.getWidth() > 0) && (v.getHeight() > 0);
	}

	/** v's bounds in this view's own coordinates (both are children of the same parent). */
	private void bounds(View v, RectF out) {
		out.set(v.getLeft() - getLeft(), v.getTop() - getTop(), v.getRight() - getLeft(),
				v.getBottom() - getTop());
	}

	private void animatePills() {
		if (anim != null) anim.cancel();

		if (!isLaidOut()) {
			main.finish();
			aux.finish();
			invalidate();
			return;
		}

		ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
		va.setDuration(ANIM_MS);
		va.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
		va.addUpdateListener(v -> {
			float f = (float) v.getAnimatedValue();
			main.step(f);
			aux.step(f);
			invalidate();
		});
		anim = va;
		va.start();
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		drawFade(canvas);
		drawPill(canvas, aux);
		drawPill(canvas, main);

		if (mergedDivider && (dividerPos > 0) && (main.alpha >= 1f)) {
			RectF r = main.cur;
			if ((dividerPos > r.top) && (dividerPos < r.bottom)) {
				canvas.drawLine(r.left + dividerInset, dividerPos, r.right - dividerInset, dividerPos,
						dividerPaint);
			}
		}
	}

	private void drawPill(Canvas canvas, Pill p) {
		RectF r = p.cur;
		if (r.isEmpty() || (p.alpha <= 0f)) return;
		float radius = Math.min(maxRadius, Math.min(r.width(), r.height()) / 2f);
		pillPaint.setAlpha(Math.round(Color.alpha(pillColor) * p.alpha));
		canvas.drawRoundRect(r, radius, radius, pillPaint);
	}

	/**
	 * Content fades out toward the screen edge the nav bar floats over -- below a bottom bar, or
	 * outward from a side bar -- so it softly dissolves behind the pill rather than being cut off
	 * by it. Skipped over video (bars hidden or not), which should never be tinted.
	 */
	private void drawFade(Canvas canvas) {
		if ((fadeKey & 1) == 0 || (fadeKey & 2) != 0) return;
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
		if (a == null) return;
		NavBarView nb = a.getNavBar();
		if (nb == null) return;
		RectF r = main.cur;
		if (r.isEmpty()) return;
		int w = getWidth();
		int h = getHeight();
		int solid = (bgColor & 0x00FFFFFF) | 0xE6000000;
		int clear = bgColor & 0x00FFFFFF;
		int[] colors = {clear, (bgColor & 0x00FFFFFF) | 0x99000000, solid};
		float[] stops = {0f, 0.55f, 1f};

		if (nb.isBottom()) {
			float top = Math.max(0, r.top - fadeLen);
			tmp.set(0, top, w, h);
			fadePaint.setShader(new LinearGradient(0, top, 0, h, colors, stops, Shader.TileMode.CLAMP));
		} else if (nb.isLeft()) {
			float right = Math.min(w, r.right + sideFadeLen);
			tmp.set(0, 0, right, h);
			fadePaint.setShader(new LinearGradient(right, 0, 0, 0, colors, stops, Shader.TileMode.CLAMP));
		} else {
			float left = Math.max(0, r.left - sideFadeLen);
			tmp.set(left, 0, w, h);
			fadePaint.setShader(new LinearGradient(left, 0, w, 0, colors, stops, Shader.TileMode.CLAMP));
		}

		canvas.drawRect(tmp, fadePaint);
	}

	private static final class Pill {
		final RectF from = new RectF();
		final RectF to = new RectF();
		final RectF cur = new RectF();
		float fromAlpha;
		float toAlpha;
		float alpha;

		void retarget(RectF target) {
			from.set(cur);
			fromAlpha = alpha;

			if (target.isEmpty()) {
				// Fade out in place, keeping the last shape.
				to.setEmpty();
				toAlpha = 0f;
			} else if (cur.isEmpty() || (alpha <= 0f)) {
				// Fade in at the new shape.
				from.set(target);
				cur.set(target);
				to.set(target);
				toAlpha = 1f;
			} else {
				to.set(target);
				toAlpha = 1f;
			}
		}

		void step(float f) {
			alpha = fromAlpha + (toAlpha - fromAlpha) * f;
			if (!to.isEmpty()) {
				cur.set(from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
						from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f);
			} else if (f >= 1f) {
				cur.setEmpty();
			}
		}

		void finish() {
			step(1f);
		}
	}
}
