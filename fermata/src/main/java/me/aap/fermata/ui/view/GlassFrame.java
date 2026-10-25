package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The frame holding a video's picture when the picture is a view (YouTube's page): with the experimental
 * blur over web pages and video on ({@link Glass#webBlur}), it records itself into a RenderNode, the same
 * display list the screen draws, so the fullscreen video buttons over it ({@link Glass#videoButton}) can draw
 * the picture behind them blurred. The buttons are in the same VideoView, beside this frame, never inside it,
 * so there is no cycle. Off, it is a plain FrameLayout.
 */
public class GlassFrame extends FrameLayout {
	/** A RenderNode while recorded (Android 12+). */
	@Nullable
	private Object node;
	private boolean recording;

	public GlassFrame(@NonNull Context ctx) {
		super(ctx);
	}

	@Override
	public void draw(@NonNull Canvas canvas) {
		if (Glass.webBlur() && Glass.onBars() && canvas.isHardwareAccelerated()) {
			drawRecorded(canvas);
		} else {
			recording = false;
			super.draw(canvas);
		}
	}

	@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
	private void drawRecorded(Canvas canvas) {
		if (!(node instanceof android.graphics.RenderNode)) node = new android.graphics.RenderNode("glassFrame");
		recording = true;
		GlassLens.recordAndDraw(canvas, (android.graphics.RenderNode) node, getWidth(), getHeight(), super::draw);
	}

	/**
	 * Draws the picture behind {@code self} (a button over the video, {@code r} in its coordinates) blurred,
	 * when its VideoView holds a recording GlassFrame. False when there is none (local video, the blur off).
	 */
	static boolean drawBehind(View self, Canvas c, RectF r, float radius, float alpha, Glass.Look look,
														GlassLens[] slot) {
		if (!Glass.realBlur()) return false;
		GlassFrame f = find(self);
		if ((f == null) || !f.recording) return false;
		return drawBehind31(f, self, c, r, radius, alpha, look, slot);
	}

	@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
	private static boolean drawBehind31(GlassFrame f, View self, Canvas c, RectF r, float radius, float alpha,
																			Glass.Look look, GlassLens[] slot) {
		if (!(f.node instanceof android.graphics.RenderNode n) || !n.hasDisplayList()) return false;
		if (slot[0] == null) slot[0] = new GlassLens();
		return slot[0].draw(c, self, n, f, r, radius, alpha, look);
	}

	/** The GlassFrame among the children of the VideoView {@code v} sits in (never one of v's own parents). */
	@Nullable
	private static GlassFrame find(View v) {
		for (ViewParent p = v.getParent(); p != null; p = p.getParent()) {
			if (p instanceof VideoView vv) {
				for (int i = 0, n = vv.getChildCount(); i < n; i++) {
					if (vv.getChildAt(i) instanceof GlassFrame f) return f;
				}
				return null;
			}
		}
		return null;
	}
}
