package me.aap.fermata.ui.view;

import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * One glass surface's view of the content behind it (see {@link Glass}): a RenderNode as big as the
 * surface plus a margin, holding the content's own RenderNode moved so the part behind the surface
 * lines up, blurred (and saturated) by a RenderEffect, drawn clipped to the surface's shape. Re-recorded
 * on every draw of the surface, which only adds a node reference to its display list; the content
 * itself is drawn once, by the screen.
 * <p>
 * Only made from Android 12 on (RenderEffect).
 */
@RequiresApi(Build.VERSION_CODES.S)
public final class GlassLens {
	private final RenderNode node = new RenderNode("glassLens");
	private final Path clip = new Path();
	private final int[] srcLoc = new int[2];
	private final int[] selfLoc = new int[2];
	private float effectBlur = -1f;
	private float effectSat = -1f;

	/**
	 * Draws the blurred content under {@code r} (in {@code self}'s coordinates).
	 *
	 * @param src     the content, as recorded by its view (see BodyLayout#getGlassSource)
	 * @param srcView the view that recorded it, for where it is on screen
	 * @return false if nothing could be drawn (no recording yet, a software canvas)
	 */
	public boolean draw(@NonNull Canvas c, @NonNull View self, @NonNull RenderNode src, @NonNull View srcView,
											@NonNull RectF r, float radius, float alpha, @NonNull Glass.Look look) {
		if (!c.isHardwareAccelerated() || !src.hasDisplayList() || r.isEmpty() || (alpha <= 0f)) return false;
		srcView.getLocationInWindow(srcLoc);
		self.getLocationInWindow(selfLoc);
		return draw(c, src, srcLoc[0] - selfLoc[0], srcLoc[1] - selfLoc[1], r, radius, alpha, look,
				self.getResources().getDisplayMetrics().density);
	}

	/** As above, with the content's origin given in {@code c}'s coordinates. */
	public boolean draw(@NonNull Canvas c, @NonNull RenderNode src, float srcX, float srcY, @NonNull RectF r,
											float radius, float alpha, @NonNull Glass.Look look, float density) {
		if (!src.hasDisplayList()) return false;
		return draw(c, rc -> rc.drawRenderNode(src), srcX, srcY, r, radius, alpha, look, density);
	}

	/**
	 * The general form: {@code content} draws what is behind (RenderNodes of its views, see
	 * {@link #recordAndDraw}) with the content's origin at 0, 0; it is placed at {@code srcX, srcY} of
	 * {@code c}.
	 */
	public boolean draw(@NonNull Canvas c, @NonNull java.util.function.Consumer<RecordingCanvas> content, float srcX,
											float srcY, @NonNull RectF r, float radius, float alpha, @NonNull Glass.Look look,
											float density) {
		if (!c.isHardwareAccelerated() || r.isEmpty() || (alpha <= 0f)) return false;
		float blur = look.blurDp * density;
		// Rendered at a fraction of the size (the quality setting) and stretched back up: the blur's
		// work goes down with the square of it, and blurred content loses nothing to the stretch.
		float s = Glass.renderScale(look);
		// Room around the surface for the blur to read from, so its edges are not darkened.
		int m = (int) Math.ceil(blur * 1.5f);
		int w = (int) Math.ceil((r.width() + 2 * m) * s);
		int h = (int) Math.ceil((r.height() + 2 * m) * s);
		if ((w <= 0) || (h <= 0)) return false;
		node.setPosition(0, 0, w, h);
		// Its own layer at its own (small) size, the blur applied to that layer.
		node.setUseCompositingLayer(true, null);
		applyEffect(blur * s, look.saturation);
		RecordingCanvas rc = node.beginRecording(w, h);
		try {
			rc.scale(s, s);
			rc.translate(srcX - r.left + m, srcY - r.top + m);
			content.accept(rc);
		} finally {
			node.endRecording();
		}
		node.setAlpha(Math.max(0f, Math.min(1f, alpha)));
		int save = c.save();
		clip.reset();
		clip.addRoundRect(r, radius, radius, Path.Direction.CW);
		c.clipPath(clip);
		c.translate(r.left - m, r.top - m);
		if (s != 1f) c.scale(1f / s, 1f / s);
		c.drawRenderNode(node);
		c.restoreToCount(save);
		return true;
	}

	private void applyEffect(float blur, float sat) {
		if ((blur == effectBlur) && (sat == effectSat)) return;
		effectBlur = blur;
		effectSat = sat;
		RenderEffect e = (blur >= 0.5f) ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) : null;
		if (Math.abs(sat - 1f) > 0.01f) {
			ColorMatrix cm = new ColorMatrix();
			cm.setSaturation(sat);
			ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
			e = (e != null) ? RenderEffect.createColorFilterEffect(f, e) : RenderEffect.createColorFilterEffect(f);
		}
		node.setRenderEffect(e);
	}

	/**
	 * Draws the tab content behind a bar ({@code self}, {@code r} in its coordinates) blurred, through the
	 * lens in {@code slot[0]} (made on first use). Nothing while the content is not recorded for glass
	 * (see BodyLayout#getGlassSource).
	 */
	public static boolean behindBars(@NonNull View self, @NonNull Canvas c, @NonNull RectF r, float radius,
																	 float alpha, @NonNull Glass.Look look, @NonNull GlassLens[] slot) {
		var a = me.aap.fermata.ui.activity.MainActivityDelegate.getActivityDelegate(self.getContext()).peek();
		if (a == null) return false;
		BodyLayout body = a.getBody();
		if ((body == null) || inside(self, body)) return false;
		// Drawn again when the content under it changes or it moves (see Glass#register).
		Glass.register(self);
		RenderNode src = body.getGlassSource();
		if (src == null) return false;
		if (slot[0] == null) slot[0] = new GlassLens();
		return slot[0].draw(c, self, src, body, r, radius, alpha, look);
	}

	/**
	 * Whether {@code v} is inside {@code body}: such a surface (the Music tab's More card, the All in One
	 * preview on its settings page) would draw the content that holds itself, a RenderNode cycle that
	 * crashes the render thread (natively: no Java exception, nothing in the Diagnostic log). It gets the
	 * tint alone.
	 */
	private static boolean inside(View v, View body) {
		for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
			if (p == body) return true;
		}
		return false;
	}

	/** Records {@code drawer}'s drawing into {@code node} ({@code w} x {@code h}, unclipped) and draws it on {@code c}. */
	public static void recordAndDraw(@NonNull Canvas c, @NonNull RenderNode node, int w, int h,
																	 @NonNull java.util.function.Consumer<Canvas> drawer) {
		node.setPosition(0, 0, w, h);
		// Backgrounds reach past the view's edges (under the bars, see BehindBarsLayers): kept.
		node.setClipToBounds(false);
		RecordingCanvas rc = node.beginRecording(w, h);
		try {
			drawer.accept(rc);
		} finally {
			node.endRecording();
		}
		c.drawRenderNode(node);
	}
}
