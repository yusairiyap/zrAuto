package me.aap.fermata.ui.view;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.util.DiagnosticLog;

/**
 * The video's picture for the glass, with the experimental blur over video on ({@link Glass#webBlur}). Nothing the app
 * draws can read a video: local video plays into a SurfaceView (a layer of its own), and YouTube's page is drawn by Chrome,
 * which may be drawn only once a frame (drawing it again for the blur flickered or crashed). So a small copy of the picture
 * is taken with {@link PixelCopy} several times a second while it is on screen, and the glass blurs that copy:
 * <ul>
 * <li><b>Live</b>: when the video is in a SurfaceView anywhere under the VideoView (local video always; fullscreen YouTube
 * when Chrome puts its video on such a layer), the copy is taken straight from that surface: the video alone, never the
 * controls over it. A surface that only ever copies as black (protected, or not where the picture is) is given up.</li>
 * <li><b>Snapshot</b> (fullscreen YouTube otherwise): a copy of the window where the video is, taken only while no glass
 * control is showing over it ({@link FrameBackdrop#quietMs}), so the copy is the video alone. While the controls are up it
 * stays as it was when they came; it is taken again as soon as they are gone.</li>
 * </ul>
 * The copy is drawn under the tab's recording for the bars (local video, {@link #drawUnder}), alone behind the fullscreen
 * buttons of local video ({@link #drawBehindButton}), and by {@link FrameBackdrop} under every glass surface over
 * fullscreen YouTube. Nothing in the playback is touched; with the switch off nothing here runs.
 */
public final class VideoBackdrop {
	/** Copies a second: from a surface while playing / paused; window snapshots while playing / paused. */
	private static final long PLAY_MS = 66;
	private static final long PAUSE_MS = 500;
	private static final long SNAP_PLAY_MS = 350;
	private static final long SNAP_PAUSE_MS = 1500;
	/** How long the controls must have been gone before a window snapshot (their fade out). */
	private static final long QUIET_MS = 350;
	private static final List<VideoBackdrop> running = new ArrayList<>();
	private static final Handler handler = new Handler(Looper.getMainLooper());

	private final VideoView view;
	private final Bitmap[] bufs = new Bitmap[3];
	private int nextBuf;
	@Nullable
	Bitmap front;
	/** Where {@link #front} is, in window coordinates. */
	final RectF frontRect = new RectF();
	private boolean on;
	private boolean copying;
	/** The surface copies came out black this many times in a row; past a few, the surface is not used. */
	private int blackCopies;
	private boolean surfaceGivenUp;
	private String lastMode = "";
	private final Paint under = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final RectF dst = new RectF();
	private final int[] loc = new int[2];

	private VideoBackdrop(VideoView view) {
		this.view = view;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) under.setBlendMode(android.graphics.BlendMode.DST_OVER);
	}

	/** Called before each of the VideoView's frames: starts or stops copying as the conditions change. */
	static void follow(VideoView v) {
		VideoBackdrop b = v.backdrop;
		boolean want = wanted(v);
		if (want && (b == null)) {
			b = new VideoBackdrop(v);
			v.backdrop = b;
		}
		if (b == null) return;
		if (want && !b.on) b.start();
		else if (!want && b.on) b.stop();
	}

	private static boolean wanted(VideoView v) {
		if (!Glass.webBlur() || !Glass.wantsSource() || !v.isAttachedToWindow() || !v.isShown() || (v.getWidth() <= 0))
			return false;
		VideoBackdrop b = v.backdrop;
		return (surfaceOf(v, b) != null) || snapshots(v);
	}

	/** The SurfaceView with the video under {@code v}, if any (and not given up). */
	@Nullable
	private static SurfaceView surfaceOf(View v, @Nullable VideoBackdrop b) {
		if ((b != null) && b.surfaceGivenUp) return null;
		return findSurface(v);
	}

	@Nullable
	private static SurfaceView findSurface(View v) {
		if ((v instanceof SurfaceView sv) && (sv.getVisibility() == View.VISIBLE) && (sv.getWidth() > 0) &&
				sv.getHolder().getSurface().isValid()) {
			return sv;
		}
		if (v instanceof ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) {
				View c = g.getChildAt(i);
				if (c.getVisibility() != View.VISIBLE) continue;
				SurfaceView sv = findSurface(c);
				if (sv != null) return sv;
			}
		}
		return null;
	}

	/** Whether window snapshots stand in when there is no surface: fullscreen YouTube (it has a FrameBackdrop). */
	private static boolean snapshots(VideoView v) {
		return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) && (FrameBackdrop.of(v) != null) && videoMode(v);
	}

	/** Fullscreen video is up (the YouTube video view may be on screen with the page too). */
	static boolean videoMode(View v) {
		try {
			var a = me.aap.fermata.ui.activity.MainActivityDelegate.getActivityDelegate(v.getContext()).peek();
			return (a != null) && a.isVideoMode();
		} catch (Throwable ex) {
			return false;
		}
	}

	private void start() {
		on = true;
		blackCopies = 0;
		surfaceGivenUp = false;
		lastMode = "";
		if (!running.contains(this)) running.add(this);
		// What is in the video view, so a log tells whether Chrome put fullscreen YouTube on a layer of its own.
		DiagnosticLog.log("BODY", "video copy start", "tree=" + tree(view, 0));
		handler.post(tick);
	}

	void stop() {
		on = false;
		running.remove(this);
		handler.removeCallbacks(tick);
		front = null;
		Glass.invalidateSurfaces();
	}

	private final Runnable tick = new Runnable() {
		@Override
		public void run() {
			if (!on) return;
			if (!wanted(view)) {
				stop();
				return;
			}
			boolean live = copy();
			boolean playing = playing();
			handler.postDelayed(this, live ? (playing ? PLAY_MS : PAUSE_MS) : (playing ? SNAP_PLAY_MS : SNAP_PAUSE_MS));
		}
	};

	private boolean playing() {
		try {
			var a = me.aap.fermata.ui.activity.MainActivityDelegate.getActivityDelegate(view.getContext()).peek();
			return (a != null) && a.getMediaSessionCallback().isPlaying();
		} catch (Throwable ex) {
			return true;
		}
	}

	/** Takes the next copy if one is due; true if the live (surface) mode is in use. */
	private boolean copy() {
		SurfaceView sv = surfaceOf(view, this);
		if (sv != null) {
			mode("surface " + sv.getClass().getSimpleName() + " " + sv.getWidth() + "x" + sv.getHeight());
			if (!copying) copySurface(sv);
			return true;
		}
		if (!snapshots(view)) return false;
		FrameBackdrop fb = FrameBackdrop.of(view);
		if ((fb == null) || (fb.quietMs() < QUIET_MS)) return false;
		mode("window snapshot");
		if (!copying && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)) copyWindow();
		return false;
	}

	private void mode(String m) {
		if (m.equals(lastMode)) return;
		lastMode = m;
		DiagnosticLog.log("BODY", "video copy from", m);
	}

	private Bitmap buffer(int srcW, int srcH) {
		// A small copy (a quarter of the size, at most 480 wide): it is blurred anyway.
		int w = Math.max(32, Math.min(480, srcW / 4));
		int h = Math.max(18, Math.round(w * (float) srcH / Math.max(1, srcW)));
		Bitmap b = bufs[nextBuf];
		if ((b == null) || (b.getWidth() != w) || (b.getHeight() != h)) {
			b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
			bufs[nextBuf] = b;
		}
		return b;
	}

	private void copySurface(SurfaceView sv) {
		Bitmap target = buffer(sv.getWidth(), sv.getHeight());
		sv.getLocationInWindow(loc);
		RectF where = new RectF(loc[0], loc[1], loc[0] + sv.getWidth(), loc[1] + sv.getHeight());
		copying = true;
		try {
			PixelCopy.request(sv, target, result -> {
				copying = false;
				if (!on || (result != PixelCopy.SUCCESS)) return;
				if (isBlack(target)) {
					// A surface that never gives a picture (Chrome's video is elsewhere): window snapshots instead.
					if ((++blackCopies >= 8) && snapshots(view) && !surfaceGivenUp) {
						surfaceGivenUp = true;
						DiagnosticLog.log("BODY", "video copy surface only black, using window snapshots");
					}
					return;
				}
				blackCopies = 0;
				shown(target, where);
			}, handler);
		} catch (Throwable ex) {
			copying = false;
		}
	}

	@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
	private void copyWindow() {
		Activity act = activity(view.getContext());
		if ((act == null) || (act.getWindow() == null)) return;
		view.getLocationInWindow(loc);
		Rect src = new Rect(loc[0], loc[1], loc[0] + view.getWidth(), loc[1] + view.getHeight());
		if (src.isEmpty()) return;
		Bitmap target = buffer(src.width(), src.height());
		RectF where = new RectF(src);
		copying = true;
		try {
			PixelCopy.request(act.getWindow(), src, target, result -> {
				copying = false;
				if (!on || (result != PixelCopy.SUCCESS)) return;
				// The controls may have come up while the copy was made: only a copy taken in quiet is kept.
				FrameBackdrop fb = FrameBackdrop.of(view);
				if ((fb != null) && (fb.quietMs() < QUIET_MS)) return;
				shown(target, where);
			}, handler);
		} catch (Throwable ex) {
			copying = false;
		}
	}

	private void shown(Bitmap target, RectF where) {
		front = target;
		frontRect.set(where);
		nextBuf = (nextBuf + 1) % bufs.length;
		// The surfaces showing the picture blurred, and the patches and buttons in the video view, are drawn again.
		Glass.invalidateSurfaces();
		Glass.invalidateTree(view);
	}

	/** Nearly black at nine points across the picture. */
	private static boolean isBlack(Bitmap b) {
		int w = b.getWidth();
		int h = b.getHeight();
		for (int i = 1; i <= 3; i++) {
			for (int j = 1; j <= 3; j++) {
				int c = b.getPixel(w * i / 4, h * j / 4);
				if (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF) > 24) return false;
			}
		}
		return true;
	}

	@Nullable
	private static Activity activity(Context ctx) {
		for (Context c = ctx; c != null; c = (c instanceof ContextWrapper w) ? w.getBaseContext() : null) {
			if (c instanceof Activity a) return a;
			if (!(c instanceof ContextWrapper)) break;
		}
		return null;
	}

	/** A short picture of the views under {@code v}: class names, nested in brackets, 4 levels deep. */
	private static String tree(View v, int depth) {
		StringBuilder sb = new StringBuilder(v.getClass().getSimpleName());
		if (v.getVisibility() != View.VISIBLE) sb.append(v.getVisibility() == View.GONE ? "(gone)" : "(invisible)");
		if ((v instanceof ViewGroup g) && (g.getChildCount() > 0)) {
			if (depth >= 4) return sb.append("[..]").toString();
			sb.append('[');
			for (int i = 0, n = g.getChildCount(); i < n; i++) {
				if (i > 0) sb.append(',');
				sb.append(tree(g.getChildAt(i), depth + 1));
			}
			sb.append(']');
		}
		return sb.toString();
	}

	/**
	 * Draws every copied picture where it is, relative to {@code origin} (whose recording is already on {@code c}),
	 * behind what is drawn there: only the hole a surface leaves is filled.
	 */
	static void drawUnder(Canvas c, View origin) {
		if (running.isEmpty()) return;
		int[] o = new int[2];
		origin.getLocationInWindow(o);
		for (VideoBackdrop b : running) {
			Bitmap f = b.front;
			if ((f == null) || !b.view.isShown()) continue;
			b.dst.set(b.frontRect);
			b.dst.offset(-o[0], -o[1]);
			c.drawBitmap(f, null, b.dst, b.under);
		}
	}

	/** The picture alone behind a fullscreen button of local video ({@code self}, inside the VideoView); false if none. */
	static boolean drawBehindButton(View self, Canvas c, RectF r, float radius, float alpha, Glass.Look look,
																	GlassLens[] slot) {
		if (!Glass.realBlur()) return false;
		VideoView vv = null;
		for (ViewParent p = self.getParent(); p != null; p = p.getParent()) {
			if (p instanceof VideoView v) {
				vv = v;
				break;
			}
		}
		if ((vv == null) || (vv.backdrop == null) || (FrameBackdrop.of(vv) != null)) return false;
		VideoBackdrop b = vv.backdrop;
		Bitmap f = b.front;
		if (f == null) return false;
		return drawBlurred(self, f, b.frontRect, c, r, radius, alpha, look, slot, b.plain);
	}

	/** Draws {@code f} (at {@code where}, window coordinates) blurred under {@code r} of {@code self}. */
	static boolean drawBlurred(View self, Bitmap f, RectF where, Canvas c, RectF r, float radius, float alpha,
														 Glass.Look look, GlassLens[] slot, Paint paint) {
		if (!Glass.realBlur()) return false;
		return drawBlurred31(self, f, where, c, r, radius, alpha, look, slot, paint);
	}

	@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
	private static boolean drawBlurred31(View self, Bitmap f, RectF where, Canvas c, RectF r, float radius,
																			 float alpha, Glass.Look look, GlassLens[] slot, Paint paint) {
		int[] me = new int[2];
		self.getLocationInWindow(me);
		RectF d = new RectF(where);
		d.offset(-me[0], -me[1]);
		if (slot[0] == null) slot[0] = new GlassLens();
		return slot[0].draw(c, rc -> rc.drawBitmap(f, null, d, paint), 0, 0, r, radius, alpha, look,
				self.getResources().getDisplayMetrics().density);
	}
}
