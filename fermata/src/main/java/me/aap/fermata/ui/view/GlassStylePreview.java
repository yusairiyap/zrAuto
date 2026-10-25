package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.view.NavBarView;

/**
 * A phone drawn in code for Settings > Interface > UI style, the way the Home settings draw their
 * arrangements (HomeLayoutPreview): colourful content (a picture's glow, a Home card, list rows with
 * covers) scrolling slowly under a tool bar pill at the top and a nav bar pill at the bottom, both in
 * the style shown. On Android 12+ the glass is the real thing: the content is recorded into a
 * RenderNode and the bars blur it through a {@link GlassLens}, exactly as the app's bars do.
 * <p>
 * {@code style} &lt; 0 shows the style now set (and follows the prefs while attached); else that
 * style's own preset. Frames run only while it is on screen.
 */
public final class GlassStylePreview extends View implements PreferenceStore.Listener {
	private final MainActivityPrefs prefs;
	private final int style;
	private final boolean animated;
	private final float d;
	private final int pillColor;
	private final int bgColor;
	private final boolean light;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF r = new RectF();
	private final RectF scr = new RectF();
	private final android.graphics.Path clip = new android.graphics.Path();
	private final GlassLens[] lenses = new GlassLens[3];
	@Nullable
	private Object scene;
	private float scroll;
	private long clock;
	private boolean running;

	public GlassStylePreview(Context ctx, MainActivityPrefs prefs, int style, boolean animated) {
		super(ctx);
		this.prefs = prefs;
		this.style = style;
		this.animated = animated;
		d = ctx.getResources().getDisplayMetrics().density;
		int[] nb = NavBarView.resolveStyleColors(ctx);
		pillColor = nb[1] | 0xFF000000;
		light = ColorUtils.calculateLuminance(pillColor) > 0.5;
		bgColor = light ? 0xFFF1F2F6 : 0xFF0E0F14;
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
	}

	private Glass.Look look() {
		return (style < 0) ? Glass.look() : Glass.preset(style);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		if (style < 0) {
			prefs.addBroadcastListener(this);
			Glass.addAnimListener(glide);
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		prefs.removeBroadcastListener(this);
		Glass.removeAnimListener(glide);
		running = false;
		super.onDetachedFromWindow();
	}

	/** The look gliding to a new one after a change: drawn each frame of it. */
	private final Runnable glide = this::invalidate;

	@Override
	public void onPreferenceChanged(PreferenceStore store, java.util.List<PreferenceStore.Pref<?>> changed) {
		if (Glass.touches(changed)) {
			Glass.reset();
			invalidate();
		}
	}

	@Override
	public void onVisibilityAggregated(boolean visible) {
		super.onVisibilityAggregated(visible);
		if (visible && animated && !running) {
			running = true;
			clock = SystemClock.uptimeMillis();
			postOnAnimation(frame);
		} else if (!visible) {
			running = false;
		}
	}

	private final Runnable frame = new Runnable() {
		@Override
		public void run() {
			if (!running || !isShown()) {
				running = false;
				return;
			}
			long t = SystemClock.uptimeMillis();
			// A slow, steady scroll: the rows pass under the bars so the blur is seen following them.
			scroll += Math.min(0.1f, (t - clock) / 1000f) * 14f * d;
			clock = t;
			invalidate();
			postOnAnimation(this);
		}
	};

	@Override
	protected void onDraw(@NonNull Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;
		// The phone: as tall as the view, 9:19.5, centred.
		float ph = h - 2 * d;
		float pw = Math.min(w - 2 * d, ph * 0.48f);
		float x = (w - pw) / 2f;
		float y = d;
		float bezel = Math.max(3f, pw * 0.035f);
		r.set(x, y, x + pw, y + ph);
		paint.setShader(null);
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(light ? 0xFFD5D8E0 : 0xFF2A2C34);
		float outer = pw * 0.13f;
		c.drawRoundRect(r, outer, outer, paint);
		scr.set(x + bezel, y + bezel, x + pw - bezel, y + ph - bezel);
		float sr = Math.max(2f, outer - bezel);

		Glass.Look l = look();
		boolean glass = l.isGlass();
		int save = c.save();
		clip.reset();
		clip.addRoundRect(scr, sr, sr, android.graphics.Path.Direction.CW);
		c.clipPath(clip);
		c.translate(scr.left, scr.top);
		float sw = scr.width();
		float sh = scr.height();
		boolean blur = glass && Glass.realBlur() && c.isHardwareAccelerated();
		if (blur) recordScene(c, sw, sh);
		else drawScene(c, sw, sh);

		float m = sw * 0.04f;
		// A Home card over the picture, the tool bar at the top and the nav bar at the bottom.
		RectF card = new RectF(m, sh * 0.16f, sw - m, sh * 0.36f);
		RectF tb = new RectF(m, sh * 0.035f, sw - m, sh * 0.035f + sh * 0.075f);
		RectF nb = new RectF(m, sh - m - sh * 0.085f, sw - m, sh - m);
		drawSurface(c, card, sw * 0.07f, light ? 0xFFFFFFFF : 0xFF101018, l, blur, 0, glass ? 1f : 0.85f);
		drawSurface(c, tb, tb.height() / 2f, pillColor, l, blur, 1, 1f);
		drawSurface(c, nb, nb.height() / 2f, pillColor, l, blur, 2, 1f);
		drawBarContent(c, tb, nb);
		c.restoreToCount(save);
	}

	private void recordScene(Canvas c, float sw, float sh) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
		if (!(scene instanceof android.graphics.RenderNode)) scene = new android.graphics.RenderNode("glassPreview");
		GlassLens.recordAndDraw(c, (android.graphics.RenderNode) scene, Math.round(sw), Math.round(sh),
				rc -> drawScene(rc, sw, sh));
	}

	/** The content: a dark or light page with a picture's glow at the top and rows of covers scrolling. */
	private void drawScene(Canvas c, float sw, float sh) {
		paint.setShader(null);
		paint.setColor(bgColor);
		c.drawRect(0, 0, sw, sh, paint);
		// The glow of a picture: three soft blobs.
		blob(c, sw * 0.2f, sh * 0.12f, sw * 0.55f, 0xFFFF5E8A);
		blob(c, sw * 0.85f, sh * 0.2f, sw * 0.5f, 0xFF5B8CFF);
		blob(c, sw * 0.5f, sh * 0.34f, sw * 0.45f, 0xFFFFC14D);
		paint.setShader(null);
		float rowH = sh * 0.085f;
		float gap = rowH * 0.25f;
		float top = sh * 0.4f;
		float step = rowH + gap;
		float off = scroll % (step * 6);
		int[] covers = {0xFFE84E6A, 0xFF4CB6FF, 0xFF7ED957, 0xFFB57CFF, 0xFFFF9F43, 0xFF2ED1C4};
		int text = light ? 0x33000000 : 0x40FFFFFF;
		int text2 = light ? 0x1F000000 : 0x26FFFFFF;
		float m = sw * 0.06f;
		// Rows from above the top edge, so they pass under the tool bar too.
		for (int i = -8; i < 12; i++) {
			float y = top + i * step - off;
			if ((y > sh) || (y + rowH < -sh * 0.5f)) continue;
			int ci = Math.floorMod(i, covers.length);
			paint.setColor(covers[ci]);
			r.set(m, y, m + rowH, y + rowH);
			c.drawRoundRect(r, rowH * 0.2f, rowH * 0.2f, paint);
			paint.setColor(text);
			float tx = m + rowH + m * 0.6f;
			r.set(tx, y + rowH * 0.18f, tx + (sw - tx - m) * (0.55f + 0.08f * (ci % 3)), y + rowH * 0.38f);
			c.drawRoundRect(r, rowH * 0.1f, rowH * 0.1f, paint);
			paint.setColor(text2);
			r.set(tx, y + rowH * 0.56f, tx + (sw - tx - m) * 0.38f, y + rowH * 0.74f);
			c.drawRoundRect(r, rowH * 0.1f, rowH * 0.1f, paint);
		}
	}

	private void blob(Canvas c, float cx, float cy, float rad, int color) {
		paint.setShader(new RadialGradient(cx, cy, rad, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
		c.drawCircle(cx, cy, rad, paint);
	}

	/** A bar or card: glass in the look given (blurred when it can be), else the usual mostly opaque pill. */
	private void drawSurface(Canvas c, RectF s, float radius, int color, Glass.Look l, boolean blur, int lens,
													 float alpha) {
		if (!l.isGlass()) {
			paint.setShader(null);
			paint.setColor((color & 0x00FFFFFF) | (Math.round(0xF2 * alpha) << 24));
			c.drawRoundRect(s, radius, radius, paint);
			return;
		}
		if (blur && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) &&
				(scene instanceof android.graphics.RenderNode n)) {
			if (lenses[lens] == null) lenses[lens] = new GlassLens();
			// The lens works in pixels: the preview's blur is scaled down with the phone.
			lenses[lens].draw(c, n, 0, 0, s, radius, 1f, scaled(l), d);
		}
		Glass.paintOver(c, s, radius, color, alpha, l, light, d * 0.6f, glassPaint);
	}

	/** The look with its blur scaled to the miniature (a phone ~0.35 of the real one). */
	private Glass.Look scaled(Glass.Look l) {
		return l.withBlur(l.blurDp * 0.45f);
	}

	/** Icons as dots on the bars, and a title line, so they read as bars. */
	private void drawBarContent(Canvas c, RectF tb, RectF nb) {
		paint.setShader(null);
		int fg = light ? 0xCC1B1C22 : 0xE6FFFFFF;
		paint.setColor(fg);
		float dot = tb.height() * 0.16f;
		c.drawCircle(tb.left + tb.height() * 0.5f, tb.centerY(), dot, paint);
		r.set(tb.left + tb.height(), tb.centerY() - dot * 0.6f, tb.left + tb.width() * 0.55f,
				tb.centerY() + dot * 0.6f);
		c.drawRoundRect(r, dot, dot, paint);
		c.drawCircle(tb.right - tb.height() * 0.5f, tb.centerY(), dot, paint);
		float nd = nb.height() * 0.14f;
		for (int i = 0; i < 5; i++) {
			float cx = nb.left + nb.width() * (i + 0.5f) / 5f;
			paint.setColor((i == 0) ? EffectsUi.accent(getContext()) : fg);
			c.drawCircle(cx, nb.centerY(), nd, paint);
		}
	}
}
