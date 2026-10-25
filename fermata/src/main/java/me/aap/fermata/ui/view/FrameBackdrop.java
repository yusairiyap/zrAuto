package me.aap.fermata.ui.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The blur under the glass surfaces over fullscreen YouTube (the video buttons, the floating buttons, the control panel,
 * menus and dialogs), with the experimental blur over video on. It sits in the YoutubeVideoView right above the picture
 * and draws, in the places the surfaces report ({@link #cover}), the video's copied picture ({@link VideoBackdrop}: live
 * from a surface, or a snapshot taken while no control showed) blurred. Under the surfaces, which then draw only their
 * tint and rim, it looks as if they blurred the video. The page itself is never drawn a second time.
 * <p>
 * It also says how long no glass control has shown over the video ({@link #quietMs}), which is when a window snapshot
 * holds the video alone. Off (the switch, not fullscreen, below Android 12) it draws nothing and has no entries.
 */
public class FrameBackdrop extends View {
	private static final long CHECK_MS = 100;
	private static final List<WeakReference<FrameBackdrop>> all = new ArrayList<>();
	private static final Handler handler = new Handler(Looper.getMainLooper());
	private final Map<Object, Entry> entries = new IdentityHashMap<>();
	private final int[] loc = new int[2];
	private final RectF local = new RectF();
	private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
	private String shownState = "";
	private boolean checking;
	/** The last time a glass control was seen showing over the video. */
	private long busyAt;

	private static final class Entry {
		final WeakReference<View> view;
		final RectF rect = new RectF();
		final GlassLens[] lens = new GlassLens[1];
		float radius;
		float alpha;
		Glass.Look look;

		Entry(View v) {
			view = new WeakReference<>(v);
		}
	}

	public FrameBackdrop(@NonNull Context ctx) {
		super(ctx);
		setWillNotDraw(false);
		setClickable(false);
		setFocusable(false);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		all.add(new WeakReference<>(this));
	}

	/** The backdrop in the VideoView {@code v}, if it has one (YouTube's). */
	@Nullable
	static FrameBackdrop of(VideoView v) {
		for (int i = 0, n = v.getChildCount(); i < n; i++) {
			if (v.getChildAt(i) instanceof FrameBackdrop b) return b;
		}
		return null;
	}

	/**
	 * A glass surface ({@code self}, {@code r} in its coordinates) over fullscreen YouTube: its blurred patch is drawn
	 * under it. True when there is a picture to blur (the surface then draws as over a blur). Each surface over the video
	 * is noted even before a picture exists, so no snapshot is taken while it shows.
	 */
	static boolean cover(View self, Object key, RectF r, float radius, float alpha, Glass.Look look) {
		FrameBackdrop b = Glass.realBlur() && Glass.webBlur() ? shownBackdrop() : null;
		if ((b == null) || insideOtherVideo(self, b)) {
			clear(key);
			return false;
		}
		for (WeakReference<FrameBackdrop> ref : all) {
			FrameBackdrop o = ref.get();
			if ((o != null) && (o != b) && (o.entries.remove(key) != null)) o.invalidate();
		}
		Entry e = b.entries.get(key);
		if ((e == null) || (e.view.get() != self)) {
			e = new Entry(self);
			b.entries.put(key, e);
		}
		self.getLocationInWindow(b.loc);
		float l = b.loc[0] + r.left;
		float t = b.loc[1] + r.top;
		boolean changed = (e.rect.left != l) || (e.rect.top != t) || (e.rect.width() != r.width()) ||
				(e.rect.height() != r.height()) || (e.radius != radius) || (e.alpha != alpha) || (e.look != look);
		e.rect.set(l, t, l + r.width(), t + r.height());
		e.radius = radius;
		e.alpha = alpha;
		e.look = look;
		if (alpha * viewAlpha(self) > 0.02f) b.busyAt = SystemClock.uptimeMillis();
		if (changed) b.invalidate();
		b.startChecking();
		VideoBackdrop src = b.source();
		return (src != null) && (src.front != null);
	}

	/** A surface inside some other VideoView (local video's buttons) is not this one's to cover. */
	private static boolean insideOtherVideo(View self, FrameBackdrop b) {
		for (ViewParent p = self.getParent(); p != null; p = p.getParent()) {
			if (p instanceof VideoView v) return v != b.getParent();
		}
		return false;
	}

	/** The surface {@code key} is not over fullscreen YouTube now, or not drawn: its patch goes. */
	static void clear(Object key) {
		for (int i = all.size() - 1; i >= 0; i--) {
			FrameBackdrop b = all.get(i).get();
			if (b == null) all.remove(i);
			else if (b.entries.remove(key) != null) b.invalidate();
		}
	}

	/** The UI style changed: the surfaces report again when they are drawn. */
	static void reset() {
		for (WeakReference<FrameBackdrop> ref : all) {
			FrameBackdrop b = ref.get();
			if ((b != null) && !b.entries.isEmpty()) {
				b.entries.clear();
				b.invalidate();
			}
		}
	}

	/** How long no glass control has been seen showing over the video (0 while one shows). */
	long quietMs() {
		if (busy()) busyAt = SystemClock.uptimeMillis();
		return SystemClock.uptimeMillis() - busyAt;
	}

	private boolean busy() {
		for (Entry e : entries.values()) {
			View v = e.view.get();
			if ((v != null) && v.isAttachedToWindow() && v.isShown() && (e.alpha * viewAlpha(v) > 0.02f)) return true;
		}
		return false;
	}

	/** The backdrop of a fullscreen YouTube view on screen, if any. */
	@Nullable
	private static FrameBackdrop shownBackdrop() {
		for (int i = all.size() - 1; i >= 0; i--) {
			FrameBackdrop b = all.get(i).get();
			if (b == null) {
				all.remove(i);
				continue;
			}
			if (b.isAttachedToWindow() && b.isShown() && (b.getWidth() > 0) && VideoBackdrop.videoMode(b)) return b;
		}
		return null;
	}

	@Nullable
	private VideoBackdrop source() {
		return (getParent() instanceof VideoView v) ? v.backdrop : null;
	}

	@Override
	protected void onDraw(@NonNull Canvas c) {
		VideoBackdrop src = source();
		Bitmap f = (src != null) ? src.front : null;
		if ((f == null) || entries.isEmpty()) return;
		for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
			Entry e = it.next();
			View v = e.view.get();
			if ((v == null) || !v.isAttachedToWindow() || !v.isShown()) {
				it.remove();
				continue;
			}
			float a = e.alpha * viewAlpha(v);
			if (a <= 0f) continue;
			getLocationInWindow(loc);
			local.set(e.rect);
			local.offset(-loc[0], -loc[1]);
			VideoBackdrop.drawBlurred(this, f, src.frontRect, c, local, e.radius, a, e.look, e.lens, paint);
		}
	}

	private void startChecking() {
		if (checking) return;
		checking = true;
		handler.postDelayed(check, CHECK_MS);
	}

	/**
	 * While patches are up: a surface hidden or fading as a view (a menu closing, the panel sliding away) is not drawn
	 * again on each step, so the patches are compared with it now and then and drawn again when they differ.
	 */
	private final Runnable check = new Runnable() {
		@Override
		public void run() {
			checking = false;
			if (entries.isEmpty() || !isAttachedToWindow()) return;
			StringBuilder sb = new StringBuilder();
			for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
				Entry e = it.next();
				View v = e.view.get();
				if ((v == null) || !v.isAttachedToWindow() || !v.isShown()) {
					it.remove();
					sb.append('x');
					continue;
				}
				v.getLocationInWindow(loc);
				sb.append(loc[0]).append(',').append(loc[1]).append(',').append(Math.round(viewAlpha(v) * 100)).append(';');
			}
			if (busy()) busyAt = SystemClock.uptimeMillis();
			String s = sb.toString();
			if (!s.equals(shownState)) {
				shownState = s;
				invalidate();
			}
			if (!entries.isEmpty()) startChecking();
		}
	};

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		entries.clear();
		handler.removeCallbacks(check);
		checking = false;
	}

	/** {@code v}'s alpha as seen on screen: its own and its parents', with the transition alphas of the bars. */
	static float viewAlpha(View v) {
		float a = 1f;
		Object o = v;
		while (o instanceof View cur) {
			a *= cur.getAlpha();
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) a *= cur.getTransitionAlpha();
			if (a <= 0f) return 0f;
			o = cur.getParent();
		}
		return a;
	}
}
