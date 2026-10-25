package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The frame holding YouTube's fullscreen picture (child 0 of YoutubeVideoView): with the experimental blur over
 * video on ({@link Glass#webBlur}), it records itself into a RenderNode,
 * the same display list the screen draws, and glass surfaces draw that node blurred instead of the whole tab:
 * the fullscreen video buttons ({@link Glass#videoButton}, beside the frame in the VideoView), and the bars,
 * floating buttons, menus and dialogs ({@link GlassLens#behindBars}) whenever the tab itself is not recorded (a
 * web tab, fullscreen YouTube). Drawing the fullscreen picture through this frame did not flicker on the device
 * where drawing it through the whole tab did; a frame around the plain YouTube page (not fullscreen) crashed the app
 * natively on entering the tab, so the page itself is never put in one. Off, it is a plain FrameLayout.
 */
public class GlassFrame extends FrameLayout {
	private static final List<WeakReference<GlassFrame>> frames = new ArrayList<>();
	/** A RenderNode while recorded (Android 12+). */
	@Nullable
	private Object node;
	private boolean recording;
	private long drawnAt;

	public GlassFrame(@NonNull Context ctx) {
		this(ctx, null);
	}

	public GlassFrame(@NonNull Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
		frames.add(new WeakReference<>(this));
	}

	@Override
	public void draw(@NonNull Canvas canvas) {
		if (Glass.webBlur() && Glass.wantsSource() && canvas.isHardwareAccelerated()) {
			drawRecorded(canvas);
			drawnAt = SystemClock.uptimeMillis();
			// What is behind the glass changed: the surfaces showing it are drawn again.
			Glass.invalidateSurfaces();
		} else {
			setRecording(false);
			super.draw(canvas);
		}
	}

	private void setRecording(boolean on) {
		if (recording == on) return;
		recording = on;
		// Written before the first recorded frame: a native crash right after it leaves this as the last line.
		View c = (getChildCount() > 0) ? getChildAt(0) : null;
		me.aap.fermata.util.DiagnosticLog.log("BODY", "glass frame", on ? "recording" : "plain",
				(c != null) ? c.getClass().getSimpleName() : "empty", getWidth() + "x" + getHeight());
	}

	@Override
	public void onDescendantInvalidated(@NonNull View child, @NonNull View target) {
		super.onDescendantInvalidated(child, target);
		if (recording) Glass.invalidateSurfaces();
	}

	@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
	private void drawRecorded(Canvas canvas) {
		if (!(node instanceof android.graphics.RenderNode)) node = new android.graphics.RenderNode("glassFrame");
		setRecording(true);
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
		return drawFrame(f, self, c, r, radius, alpha, look, slot);
	}

	/**
	 * For a surface over the whole window (a bar, a menu) while the tab is not recorded: the frame on screen that
	 * was drawn last (fullscreen YouTube's picture over the YouTube tab's page), never one holding {@code self}.
	 */
	static boolean drawBehindAny(View self, Canvas c, RectF r, float radius, float alpha, Glass.Look look,
															 GlassLens[] slot) {
		if (!Glass.realBlur() || !Glass.webBlur()) return false;
		GlassFrame best = null;
		for (int i = frames.size() - 1; i >= 0; i--) {
			GlassFrame f = frames.get(i).get();
			if (f == null) {
				frames.remove(i);
				continue;
			}
			if (!f.recording || !f.isAttachedToWindow() || !f.isShown() || holds(f, self)) continue;
			// The biggest on screen (fullscreen video's picture over the page under it), then the last drawn.
			long area = (long) f.getWidth() * f.getHeight();
			long bestArea = (best == null) ? -1 : (long) best.getWidth() * best.getHeight();
			if ((best == null) || (area > bestArea) || ((area == bestArea) && (f.drawnAt > best.drawnAt))) best = f;
		}
		return (best != null) && drawFrame(best, self, c, r, radius, alpha, look, slot);
	}

	private static boolean drawFrame(GlassFrame f, View self, Canvas c, RectF r, float radius, float alpha,
																	 Glass.Look look, GlassLens[] slot) {
		if (!Glass.realBlur()) return false;
		return drawFrame31(f, self, c, r, radius, alpha, look, slot);
	}

	@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
	private static boolean drawFrame31(GlassFrame f, View self, Canvas c, RectF r, float radius, float alpha,
																		 Glass.Look look, GlassLens[] slot) {
		if (!(f.node instanceof android.graphics.RenderNode n) || !n.hasDisplayList()) return false;
		if (slot[0] == null) slot[0] = new GlassLens();
		return slot[0].draw(c, self, n, f, r, radius, alpha, look);
	}

	private static boolean holds(View frame, View v) {
		for (ViewParent p = v.getParent(); p != null; p = p.getParent()) {
			if (p == frame) return true;
		}
		return false;
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
