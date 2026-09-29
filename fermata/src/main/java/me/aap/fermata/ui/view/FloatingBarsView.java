package me.aap.fermata.ui.view;

import static me.aap.utils.ui.UiUtils.toPx;

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.utils.ui.view.NavBarView;

/**
 * Paints the floating pill behind nav_bar and control_panel, plus the soft fade that tab content
 * dissolves into on the pill's side of the screen. Neither bar draws a background of its own;
 * this view sits between body_layout and the bars in every main_activity layout variant, so
 * plain drawing order puts the pill above the content and below the bars. It never takes touches.
 * <p>
 * With a bottom nav bar, a visible control panel joins the nav bar as a second row of the same
 * pill. With a left/right nav bar, the nav bar is a vertical pill on its side and the control
 * panel floats as a pill of its own along the bottom of the content. Either bar alone still gets
 * its own pill -- except the control panel in its fullscreen-video look, which has its own
 * edge-to-edge scrim instead (see ControlPanelView#isVideoLook).
 * <p>
 * The pill is recomputed from the bars' live state before every frame -- position (including any
 * translation), visibility and alpha -- so it follows whatever animates them (the show/hide
 * animations of MainActivityDelegate#animateNavBar and the control panel's fades) frame by frame: sliding off with a
 * side nav bar, fading with it, or growing and shrinking as the control panel row comes and goes.
 */
public class FloatingBarsView extends View implements ViewTreeObserver.OnPreDrawListener {
	/** How far the pill eases toward or away from see-through, per frame. */
	private static final float GLASS_STEP = 0.1f;
	/** The pill's opacity over a blurred background, against the usual 0xF2. */
	private static final int GLASS_ALPHA = 0x73;
	private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint fadePaint = new Paint();
	private final RectF main = new RectF();
	private final RectF aux = new RectF();
	private final RectF navRect = new RectF();
	private final RectF cpRect = new RectF();
	private final RectF tmp = new RectF();
	private final float maxRadius;
	private final float fadeLen;
	private final float sideFadeLen;
	private final float dividerInset;
	private final float shadowRadius;
	private final float shadowDy;
	private final int bgColor;
	private final int pillColor;
	private final int dividerColor;
	private float mainAlpha;
	private float auxAlpha;
	private float dividerAlpha;
	private float dividerPos;
	private float fadeAlpha;
	private int navPos;
	// 0 = the usual, mostly opaque pill; 1 = see-through, over the Music tab's blurred cover. Eased
	// from one to the other so the pill doesn't snap when the tab changes.
	private float glass;

	public FloatingBarsView(@NonNull Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
		setClickable(false);
		setFocusable(false);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		maxRadius = toPx(ctx, 28);
		fadeLen = toPx(ctx, 16);
		// A side bar's content is padded clear of the pill by the pill's own margin (see
		// MainActivityDelegate#syncSideNavInset), so fade out only across that gap, not the content.
		sideFadeLen = toPx(ctx, 12);
		dividerInset = toPx(ctx, 20);
		shadowRadius = toPx(ctx, 12);
		shadowDy = toPx(ctx, 3);

		int[] nb = NavBarView.resolveStyleColors(ctx);
		// Mostly opaque, so content passing underneath just barely shows through.
		pillColor = (nb[1] & 0x00FFFFFF) | 0xF2000000;
		dividerColor = nb[0] & 0x00FFFFFF;
		dividerPaint.setStrokeWidth(Math.max(1f, toPx(ctx, 1)));

		TypedArray ta = ctx.obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
		bgColor = ta.getColor(0, Color.BLACK);
		ta.recycle();
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
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
		if (a == null) return true;
		NavBarView nb = a.getNavBar();
		ControlPanelView cp = a.getControlPanel();
		if ((nb == null) || (cp == null)) return true;

		float navA = barAlpha(nb);
		float cpA = cp.isVideoLook() ? 0f : barAlpha(cp);
		if (navA > 0f) bounds(nb, navRect);
		else navRect.setEmpty();
		if (cpA > 0f) bounds(cp, cpRect);
		else cpRect.setEmpty();

		float newMainA = 0f;
		float newAuxA = 0f;
		float newDivA = 0f;
		float newDivPos = 0f;
		tmp.setEmpty();
		RectF newAux = new RectF();

		if (nb.isBottom()) {
			if ((navA > 0f) && (cpA > 0f)) {
				// One pill, two rows. A fading control panel row folds down into the nav bar row
				// instead of the whole pill fading or snapping to its new height.
				tmp.set(cpRect);
				tmp.top = cpRect.bottom - cpRect.height() * cpA;
				tmp.union(navRect);
				newMainA = Math.max(navA, cpA);
				newDivA = Math.min(navA, cpA);
				newDivPos = navRect.top;
			} else if (navA > 0f) {
				tmp.set(navRect);
				newMainA = navA;
			} else if (cpA > 0f) {
				tmp.set(cpRect);
				newMainA = cpA;
			}
		} else {
			if (navA > 0f) {
				tmp.set(navRect);
				newMainA = navA;
			}
			if (cpA > 0f) {
				newAux.set(cpRect);
				newAuxA = cpA;
			}
		}

		// No fade over video, nor over a tab running its own background on behind a side pill (the
		// Music tab's blurred cover): there the background itself should show through, untinted.
		boolean ownBg = !nb.isBottom() && (a.getActiveFragment() instanceof MainActivityFragment f)
				&& f.drawsBehindSideNavBar();
		float newFadeA = (a.isVideoMode() || ownBg) ? 0f : navA;
		int pos = nb.getPosition();

		// Over a tab running its own (blurred) background under the bars, the pill turns into frosted
		// glass: that background shows through it.
		float glassTarget = (a.getActiveFragment() instanceof MainActivityFragment mf)
				&& mf.drawsBehindSideNavBar() ? 1f : 0f;
		float newGlass = glass;
		if (newGlass != glassTarget) {
			newGlass += Math.max(-GLASS_STEP, Math.min(GLASS_STEP, glassTarget - newGlass));
		}

		if ((glass != newGlass) || !main.equals(tmp) || !aux.equals(newAux) || (mainAlpha != newMainA)
				|| (auxAlpha != newAuxA) || (dividerAlpha != newDivA) || (dividerPos != newDivPos)
				|| (fadeAlpha != newFadeA) || (navPos != pos)) {
			main.set(tmp);
			aux.set(newAux);
			mainAlpha = newMainA;
			auxAlpha = newAuxA;
			dividerAlpha = newDivA;
			dividerPos = newDivPos;
			fadeAlpha = newFadeA;
			navPos = pos;
			glass = newGlass;
			invalidate();
		}

		return true;
	}

	/** How much of a bar is showing: 0 when it's gone, else its (transition) alpha. */
	private static float barAlpha(View v) {
		if ((v.getVisibility() != VISIBLE) || (v.getWidth() == 0) || (v.getHeight() == 0)) return 0f;
		float alpha = v.getAlpha();
		// Fade transitions animate this separate alpha instead; only readable from API 29 on.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) alpha *= v.getTransitionAlpha();
		return Math.max(0f, Math.min(1f, alpha));
	}

	/** v's live bounds, translation included, in this view's own coordinates (same parent). */
	private void bounds(View v, RectF out) {
		float x = v.getX() - getLeft();
		float y = v.getY() - getTop();
		out.set(x, y, x + v.getWidth(), y + v.getHeight());
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		drawFade(canvas);
		drawPill(canvas, aux, auxAlpha);
		drawPill(canvas, main, mainAlpha);

		if ((dividerAlpha > 0f) && (dividerPos > main.top) && (dividerPos < main.bottom)) {
			dividerPaint.setColor(dividerColor | (Math.round(0x26 * dividerAlpha) << 24));
			canvas.drawLine(main.left + dividerInset, dividerPos, main.right - dividerInset,
					dividerPos, dividerPaint);
		}
	}

	private void drawPill(Canvas canvas, RectF r, float alpha) {
		if (r.isEmpty() || (alpha <= 0f)) return;
		float radius = Math.min(maxRadius, Math.min(r.width(), r.height()) / 2f);
		pillPaint.setColor(pillColor);
		float fill = Color.alpha(pillColor) + (GLASS_ALPHA - Color.alpha(pillColor)) * glass;
		pillPaint.setAlpha(Math.round(fill * alpha));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			// The shadow fades with the pill: left at full strength, it lingers as a grey smear
			// while the pill itself is already fading or sliding away. None under the glass look,
			// where it would show through the pill as a dark smudge.
			pillPaint.setShadowLayer(shadowRadius, 0, shadowDy,
					Math.round(0x40 * alpha * (1f - glass)) << 24);
		}
		canvas.drawRoundRect(r, radius, radius, pillPaint);
	}

	/**
	 * Content fades out toward the screen edge the nav bar floats over -- below a bottom bar, or
	 * outward from a side bar -- so it softly dissolves behind the pill rather than being cut off
	 * by it. Skipped over video, which should never be tinted.
	 */
	private void drawFade(Canvas canvas) {
		RectF r = main;
		if ((fadeAlpha <= 0f) || r.isEmpty()) return;
		int w = getWidth();
		int h = getHeight();
		int rgb = bgColor & 0x00FFFFFF;
		int[] colors = {rgb, rgb | (Math.round(0x55 * fadeAlpha) << 24),
				rgb | (Math.round(0x99 * fadeAlpha) << 24)};
		float[] stops = {0f, 0.5f, 1f};

		if (navPos == NavBarView.POSITION_BOTTOM) {
			float top = Math.max(0, r.top - fadeLen);
			tmp.set(0, top, w, h);
			fadePaint.setShader(new LinearGradient(0, top, 0, h, colors, stops, Shader.TileMode.CLAMP));
		} else if (navPos == NavBarView.POSITION_LEFT) {
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
}
