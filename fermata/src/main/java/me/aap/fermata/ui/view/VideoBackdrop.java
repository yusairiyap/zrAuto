package me.aap.fermata.ui.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Local video's picture for the glass, with the experimental blur over video on ({@link Glass#webBlur}). Local
 * video plays into a SurfaceView, a layer of its own that nothing drawn by the app can read (the tab's recording
 * only has a hole where it is), so a small copy of the surface's current frame is taken with {@link PixelCopy}
 * (straight from the surface: the video alone, never the bars over it) a number of times a second while it is on
 * screen, and drawn where the hole is: under the tab's recording for the bars, menus and floating buttons
 * ({@link #drawUnder}), and alone behind the fullscreen video buttons ({@link #drawBehindButton}).
 * <p>
 * Nothing in the playback is touched: the engines keep their SurfaceView exactly as they have it, with the
 * switch off or on. With it off nothing here runs.
 */
public final class VideoBackdrop {
	/** Copies a second while the video plays, and while it is paused. */
	private static final long PLAY_MS = 66;
	private static final long PAUSE_MS = 500;
	private static final List<VideoBackdrop> running = new ArrayList<>();
	private static final Handler handler = new Handler(Looper.getMainLooper());

	private final VideoView view;
	private final Bitmap[] bufs = new Bitmap[3];
	private int nextBuf;
	@Nullable
	private Bitmap front;
	private boolean on;
	private boolean copying;
	private long lastCopy;
	private final Paint under = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final RectF dst = new RectF();

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
		if (!Glass.webBlur() || !Glass.wantsSource() || !v.isAttachedToWindow() || !v.isShown()) return false;
		View c = (v.getChildCount() > 0) ? v.getChildAt(0) : null;
		return (c instanceof SurfaceView sv) && (sv.getVisibility() == View.VISIBLE) && (sv.getWidth() > 0) &&
				sv.getHolder().getSurface().isValid();
	}

	private void start() {
		on = true;
		if (!running.contains(this)) running.add(this);
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
			copy();
			handler.postDelayed(this, playing() ? PLAY_MS : PAUSE_MS);
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

	private void copy() {
		if (copying || !(view.getChildAt(0) instanceof SurfaceView sv)) return;
		// A small copy (a quarter of the size, at most 480 wide): it is blurred anyway.
		int w = Math.max(32, Math.min(480, sv.getWidth() / 4));
		int h = Math.max(18, Math.round(w * (float) sv.getHeight() / sv.getWidth()));
		Bitmap b = bufs[nextBuf];
		if ((b == null) || (b.getWidth() != w) || (b.getHeight() != h)) {
			b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
			bufs[nextBuf] = b;
		}
		final Bitmap target = b;
		copying = true;
		try {
			PixelCopy.request(sv, target, result -> {
				copying = false;
				if (!on || (result != PixelCopy.SUCCESS)) return;
				front = target;
				nextBuf = (nextBuf + 1) % bufs.length;
				lastCopy = SystemClock.uptimeMillis();
				// The surfaces showing the picture blurred, and the video buttons over it, are drawn again.
				Glass.invalidateSurfaces();
				Glass.invalidateTree(view);
			}, handler);
		} catch (Throwable ex) {
			copying = false;
		}
	}

	/**
	 * Draws every copied picture where its surface is, relative to {@code origin} (whose recording is already on
	 * {@code c}), behind what is drawn there: only the hole the surface leaves is filled.
	 */
	static void drawUnder(Canvas c, View origin) {
		if (running.isEmpty()) return;
		int[] o = new int[2];
		int[] s = new int[2];
		origin.getLocationInWindow(o);
		for (VideoBackdrop b : running) {
			Bitmap f = b.front;
			if ((f == null) || !(b.view.getChildAt(0) instanceof SurfaceView sv) || !sv.isShown()) continue;
			sv.getLocationInWindow(s);
			b.dst.set(s[0] - o[0], s[1] - o[1], s[0] - o[0] + sv.getWidth(), s[1] - o[1] + sv.getHeight());
			c.drawBitmap(f, null, b.dst, b.under);
		}
	}

	/** The picture alone behind a fullscreen video button ({@code self}, inside the VideoView); false if none. */
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
		if ((vv == null) || (vv.backdrop == null)) return false;
		VideoBackdrop b = vv.backdrop;
		Bitmap f = b.front;
		if ((f == null) || !(vv.getChildAt(0) instanceof SurfaceView sv)) return false;
		return drawBehindButton31(self, vv, sv, f, b, c, r, radius, alpha, look, slot);
	}

	@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
	private static boolean drawBehindButton31(View self, VideoView vv, SurfaceView sv, Bitmap f, VideoBackdrop b,
																						Canvas c, RectF r, float radius, float alpha, Glass.Look look,
																						GlassLens[] slot) {
		int[] v = new int[2];
		int[] s = new int[2];
		int[] me = new int[2];
		vv.getLocationInWindow(v);
		sv.getLocationInWindow(s);
		self.getLocationInWindow(me);
		float l = s[0] - v[0];
		float t = s[1] - v[1];
		RectF d = new RectF(l, t, l + sv.getWidth(), t + sv.getHeight());
		if (slot[0] == null) slot[0] = new GlassLens();
		return slot[0].draw(c, rc -> rc.drawBitmap(f, null, d, b.plain), v[0] - me[0], v[1] - me[1], r, radius, alpha,
				look, self.getResources().getDisplayMetrics().density);
	}
}
