package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The frame holding YouTube's fullscreen picture (child 0 of YoutubeVideoView): with the experimental blur over
 * video on ({@link Glass#webBlur}), it records itself into a RenderNode,
 * the same display list the screen draws, and only the fullscreen video buttons ({@link Glass#videoButton}, beside
 * the frame in the VideoView) draw that node blurred. The bars, floating buttons and menus drawing it crashed the app
 * natively as soon as a video played; they use the page's own blur instead ({@link PageBlur}). Drawing the
 * fullscreen picture through this frame did not flicker on the device where drawing it through the whole tab did; a frame around the plain YouTube page (not fullscreen) crashed the app
 * natively on entering the tab, so the page itself is never put in one. Off, it is a plain FrameLayout.
 */
public class GlassFrame extends FrameLayout {
	/** A RenderNode while recorded (Android 12+). */
	@Nullable
	private Object node;
	/** The picture blurred once per frame into its own layer (Android 12+), shared by every patch. */
	@Nullable
	private Object blurNode;
	private float blurScale = 1f;
	private float effectBlur = -1f;
	private float effectSat = -1f;
	private final java.util.Map<Object, Object> patches = new java.util.WeakHashMap<>();
	private boolean recording;
	/** Set (written to disk) while patches of the picture are drawn; see {@link #crashGuard}. */
	private static boolean busy;

	public GlassFrame(@NonNull Context ctx) {
		this(ctx, null);
	}

	public GlassFrame(@NonNull Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);
	}

	@Override
	public void draw(@NonNull Canvas canvas) {
		if (Glass.webBlur() && Glass.wantsSource() && canvas.isHardwareAccelerated() && !offAfterCrash()) {
			drawRecorded(canvas);
			FrameBackdrop.frameDrawn(this);
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
		if (!on) setBusy(false);
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
		android.graphics.RenderNode src = (android.graphics.RenderNode) node;
		GlassLens.recordAndDraw(canvas, src, getWidth(), getHeight(), super::draw);
		recordBlur(src);
	}

	/**
	 * Blurs the picture once into {@code blurNode} (its own small layer, never drawn by the frame itself). Every glass
	 * patch over the video (the video buttons, and through FrameBackdrop the floating buttons, control panel, menus) draws
	 * a clipped piece of that layer, so the page's WebView is drawn only twice a frame however many patches there are.
	 * Each patch drawing the page through a lens of its own crashed the app natively once the control panel's joined the
	 * three buttons'.
	 */
	@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
	private void recordBlur(android.graphics.RenderNode src) {
		if (!(blurNode instanceof android.graphics.RenderNode)) blurNode = new android.graphics.RenderNode("glassFrameBlur");
		android.graphics.RenderNode b = (android.graphics.RenderNode) blurNode;
		Glass.Look l = Glass.look();
		float s = Glass.renderScale(l);
		int w = (int) Math.ceil(getWidth() * s);
		int h = (int) Math.ceil(getHeight() * s);
		if ((w <= 0) || (h <= 0)) return;
		float blur = Math.max(1f, l.blurDp * getResources().getDisplayMetrics().density * s);
		b.setPosition(0, 0, w, h);
		b.setUseCompositingLayer(true, null);
		if ((blur != effectBlur) || (l.saturation != effectSat)) {
			effectBlur = blur;
			effectSat = l.saturation;
			android.graphics.RenderEffect e = android.graphics.RenderEffect.createBlurEffect(blur, blur,
					android.graphics.Shader.TileMode.CLAMP);
			if (Math.abs(l.saturation - 1f) > 0.01f) {
				android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
				cm.setSaturation(l.saturation);
				e = android.graphics.RenderEffect.createColorFilterEffect(new android.graphics.ColorMatrixColorFilter(cm), e);
			}
			b.setRenderEffect(e);
		}
		android.graphics.RecordingCanvas rc = b.beginRecording(w, h);
		try {
			rc.scale(s, s);
			rc.drawRenderNode(src);
		} finally {
			b.endRecording();
		}
		blurScale = s;
	}

	/**
	 * Draws a piece of {@code f}'s blurred picture under {@code r} (in {@code self}'s coordinates), clipped to a rounded
	 * rect, at {@code alpha}. {@code slot} keys the small wrapper node that carries the alpha. False when there is none yet.
	 */
	static boolean drawShared(GlassFrame f, Object slot, View self, Canvas c, RectF r, float radius, float alpha) {
		if (!Glass.realBlur() || !c.isHardwareAccelerated() || r.isEmpty() || (alpha <= 0f) || !f.recording) return false;
		return drawShared31(f, slot, self, c, r, radius, alpha);
	}

	@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
	private static boolean drawShared31(GlassFrame f, Object slot, View self, Canvas c, RectF r, float radius,
																			float alpha) {
		if (!(f.blurNode instanceof android.graphics.RenderNode b) || !b.hasDisplayList()) return false;
		setBusy(true);
		Object po = f.patches.get(slot);
		if (!(po instanceof android.graphics.RenderNode)) {
			po = new android.graphics.RenderNode("glassPatch");
			f.patches.put(slot, po);
		}
		android.graphics.RenderNode p = (android.graphics.RenderNode) po;
		p.setPosition(0, 0, b.getWidth(), b.getHeight());
		p.setHasOverlappingRendering(false);
		p.setAlpha(Math.min(1f, alpha));
		android.graphics.RecordingCanvas rc = p.beginRecording(b.getWidth(), b.getHeight());
		try {
			rc.drawRenderNode(b);
		} finally {
			p.endRecording();
		}
		int[] fl = new int[2];
		int[] sl = new int[2];
		f.getLocationInWindow(fl);
		self.getLocationInWindow(sl);
		int save = c.save();
		android.graphics.Path clip = new android.graphics.Path();
		clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
		c.clipPath(clip);
		c.translate(fl[0] - sl[0], fl[1] - sl[1]);
		float s = f.blurScale;
		if (s != 1f) c.scale(1f / s, 1f / s);
		c.drawRenderNode(p);
		c.restoreToCount(save);
		return true;
	}

	// ---- Crash guard: if the app dies while the picture is blurred, the next start leaves it off ----

	private static final String BUSY_KEY = "GLASS_FRAME_BUSY";

	/**
	 * At start: when the last run ended (killed, crashed) while patches of the picture were being drawn, the blur over
	 * video stays off (GLASS_FRAME_OFF) until the experimental switch is turned off and on again; the rest of the glass
	 * is unchanged. It errs on the safe side: a run closed from the recent apps list while fullscreen video was blurred
	 * counts too.
	 */
	public static void crashGuard(me.aap.fermata.FermataApplication app) {
		try {
			android.content.SharedPreferences sp = app.getDefaultSharedPreferences();
			if (!sp.getBoolean(BUSY_KEY, false)) return;
			sp.edit().remove(BUSY_KEY).putBoolean("GLASS_FRAME_OFF", true).commit();
			me.aap.fermata.util.DiagnosticLog.log("BODY", "glass over video turned off: the last run ended while it was drawn");
		} catch (Throwable ignore) {
		}
	}

	private static boolean offAfterCrash() {
		return MainActivityPrefsAccess.frameOff();
	}

	private static void setBusy(boolean on) {
		if (busy == on) return;
		busy = on;
		try {
			// commit, not apply: it must be on disk before the frame that could crash is drawn.
			android.content.SharedPreferences.Editor e =
					me.aap.fermata.FermataApplication.get().getDefaultSharedPreferences().edit();
			if (on) e.putBoolean(BUSY_KEY, true);
			else e.remove(BUSY_KEY);
			e.commit();
		} catch (Throwable ignore) {
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		setRecording(false);
	}

	private static final class MainActivityPrefsAccess {
		static boolean frameOff() {
			return me.aap.fermata.ui.activity.MainActivityPrefs.get()
					.getBooleanPref(me.aap.fermata.ui.activity.MainActivityPrefs.GLASS_FRAME_OFF);
		}
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
		return drawShared(f, slot, self, c, r, radius, alpha);
	}

	boolean isRecording() {
		return recording;
	}

	/** Whether {@code v} is inside {@code frame}: such a surface must never draw the frame (a RenderNode cycle). */
	static boolean holds(View frame, View v) {
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
