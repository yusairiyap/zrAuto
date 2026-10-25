package me.aap.fermata.ui.view;

import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.util.DiagnosticLog;

/**
 * The glass over a web page (the YouTube tab, the browser) with the experimental blur on ({@link Glass#webBlur}).
 * Our bars cannot read the page: drawing a WebView a second time in a frame flickered, and recording it crashed.
 * So the page blurs itself: a small script puts see-through boxes into the page (position: fixed, no touches)
 * exactly where the glass surfaces are, each with CSS {@code backdrop-filter: blur() saturate()}, and Chromium blurs
 * the page under them on the GPU as part of its own drawing. Our surfaces then draw only their tint and rim on top,
 * which looks as if they blurred the page. Nothing is drawn twice.
 * <p>
 * The boxes follow the surfaces: each glass draw over a page reports its rect ({@link #cover}), a surface that
 * blurs something else or stops being glass takes its box away ({@link #clear}), and the boxes are sent to the
 * page at most once a frame, only when they changed (and again every couple of seconds, for a page that was
 * loaded meanwhile). When the page cannot do {@code backdrop-filter}, {@link #cover} returns false and the surface
 * draws the frosted look without a blur (thicker tint and grain, see {@link Glass#paintOver}).
 */
public final class PageBlur {
	private static final long REFRESH_MS = 2000;
	/** How often the boxes are compared with their surfaces while any are up (their fades, a surface hidden). */
	private static final long CHECK_MS = 100;
	private static final Handler handler = new Handler(Looper.getMainLooper());
	/** Per glass surface (its lens slot, one per pill): where it is. */
	private static final Map<Object, Entry> entries = new IdentityHashMap<>();
	/** What each page can do (null = not known yet) and what it was last sent. */
	private static final WeakHashMap<WebView, Boolean> supported = new WeakHashMap<>();
	private static final WeakHashMap<WebView, String> sent = new WeakHashMap<>();
	private static final WeakHashMap<WebView, Long> sentAt = new WeakHashMap<>();
	private static boolean posted;
	private static boolean checking;
	private static final int[] loc = new int[2];

	private static int nextId;

	private static final class Entry {
		final int id = ++nextId;
		final WeakReference<View> view;
		final RectF rect = new RectF();
		float radius;
		float alpha;
		float blurPx;
		float saturation;
		/** The alpha the box shows with: the surface's own times its views'. */
		float shown;

		Entry(View v) {
			view = new WeakReference<>(v);
		}
	}

	private PageBlur() {
	}

	/**
	 * A glass surface ({@code self}, {@code r} in its coordinates) is drawn over whatever is under it, and nothing of
	 * ours could blur it: if that is a web page, its box in the page blurs it. True when the page can (the surface
	 * then draws as over a blur); false when it cannot or it is not known yet.
	 */
	static boolean cover(View self, Object key, RectF r, float radius, float alpha, Glass.Look look) {
		if (!Glass.webBlur()) {
			clear(key);
			return false;
		}
		anchor = new WeakReference<>(self);
		WebView w = webViewUnder(self);
		if (w == null) {
			clear(key);
			return false;
		}
		Entry e = entries.get(key);
		if ((e == null) || (e.view.get() != self)) {
			e = new Entry(self);
			entries.put(key, e);
		}
		self.getLocationInWindow(loc);
		e.rect.set(r);
		e.rect.offset(loc[0], loc[1]);
		e.radius = radius;
		e.alpha = alpha;
		e.blurPx = look.blurDp * self.getResources().getDisplayMetrics().density;
		e.saturation = look.saturation;
		e.shown = alpha * viewAlpha(self);
		schedule();
		return Boolean.TRUE.equals(supported.get(w));
	}

	/** The surface {@code key} blurs something else now, or is not glass: its box leaves the page. */
	static void clear(Object key) {
		if (entries.remove(key) != null) schedule();
	}

	/** The UI style changed: every surface reports again when it is drawn (a bar no longer glass takes its box away). */
	static void reset() {
		if (entries.isEmpty()) return;
		entries.clear();
		schedule();
	}

	private static void schedule() {
		if (posted) return;
		posted = true;
		// Never from inside a draw: the script is sent after it.
		handler.post(push);
	}

	private static final Runnable push = () -> {
		posted = false;
		send(false);
	};

	private static final Runnable check = new Runnable() {
		@Override
		public void run() {
			checking = false;
			send(true);
		}
	};

	private static void send(boolean periodic) {
		List<WebView> pages = pages();
		// Surfaces gone or hidden take their boxes with them.
		entries.values().removeIf(e -> {
			View v = e.view.get();
			return (v == null) || !v.isAttachedToWindow() || !v.isShown() || (e.alpha <= 0f);
		});
		// A surface fading as a view (a menu opening or closing, a bar sliding away) is not drawn again on each step:
		// its box takes the view's own alpha here, and the page eases between the values (see script).
		for (Entry e : entries.values()) {
			View v = e.view.get();
			if (v != null) e.shown = e.alpha * viewAlpha(v);
		}
		long now = SystemClock.uptimeMillis();
		for (WebView w : pages) {
			String data = boxes(w);
			Long at = sentAt.get(w);
			boolean stale = (at == null) || (now - at >= REFRESH_MS);
			if (data.equals(sent.get(w)) && !stale) continue;
			if (data.equals("[]") && (sent.get(w) == null)) continue;
			sent.put(w, data);
			sentAt.put(w, now);
			try {
				w.evaluateJavascript(script(data), res -> {
					Boolean ok = "true".equals(res);
					Boolean was = supported.put(w, ok);
					if (!ok.equals(was)) Glass.invalidateSurfaces();
				});
			} catch (Throwable ex) {
				DiagnosticLog.log("BODY", "page blur failed", ex.toString());
			}
		}
		// While boxes are up, look again now and then: a surface hidden without a redraw, a page loaded anew.
		if (!entries.isEmpty() && !checking) {
			checking = true;
			handler.postDelayed(check, CHECK_MS);
		}
	}

	/** {@code v}'s alpha as seen on screen: its own and its parents', with the transition alphas of the bars. */
	private static float viewAlpha(View v) {
		float a = 1f;
		for (Object p = v; p instanceof View pv; p = pv.getParent()) {
			a *= pv.getAlpha();
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) a *= pv.getTransitionAlpha();
			if (a <= 0f) return 0f;
		}
		return a;
	}

	/**
	 * The boxes over {@code w}, in its own pixels: [[id, left, top, width, height, radius, alpha, blur, saturation]].
	 */
	private static String boxes(WebView w) {
		w.getLocationInWindow(loc);
		RectF page = new RectF(loc[0], loc[1], loc[0] + w.getWidth(), loc[1] + w.getHeight());
		StringBuilder sb = new StringBuilder("[");
		for (Entry e : entries.values()) {
			if (!RectF.intersects(page, e.rect)) continue;
			if (sb.length() > 1) sb.append(',');
			sb.append(String.format(Locale.ROOT, "[%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.2f,%.1f,%.2f]", e.id,
					e.rect.left - page.left, e.rect.top - page.top, e.rect.width(), e.rect.height(), e.radius, e.shown,
					e.blurPx, e.saturation));
		}
		return sb.append(']').toString();
	}

	/**
	 * Makes the page's boxes match {@code data} (pixels are turned into CSS pixels by the page's own pixel ratio) and
	 * returns whether the page can do backdrop-filter.
	 */
	private static String script(String data) {
		// Boxes are kept by id: a new one fades in from nothing, one no longer listed fades out and is then removed,
		// and a change of alpha eases (opacity transition). Size and place change at once, with the surface.
		return "(function(d){try{var id='__zrauto_glass',h=document.getElementById(id);" +
				"var ok=!!(window.CSS&&(CSS.supports('backdrop-filter','blur(1px)')||" +
				"CSS.supports('-webkit-backdrop-filter','blur(1px)')));" +
				"var host=document.body||document.documentElement;" +
				"if(!h){if(!d.length)return ok;h=document.createElement('div');h.id=id;" +
				"h.style.cssText='position:fixed;left:0;top:0;width:0;height:0;z-index:2147483647;pointer-events:none;';}" +
				"if(h.parentNode!==host)host.appendChild(h);" +
				"var r=window.devicePixelRatio||1,seen={};" +
				"for(var i=0;i<d.length;i++){var x=d[i],k='b'+x[0];seen[k]=1;var n=h.querySelector('[data-k=\"'+k+'\"]');" +
				"var fresh=!n;if(fresh){n=document.createElement('div');n.setAttribute('data-k',k);" +
				"n.style.cssText='position:fixed;pointer-events:none;margin:0;padding:0;border:0;background:transparent;" +
				"overflow:hidden;opacity:0;transition:opacity 220ms ease-out;';h.appendChild(n);}" +
				"n.removeAttribute('data-gone');var s=n.style;" +
				"s.left=(x[1]/r)+'px';s.top=(x[2]/r)+'px';s.width=(x[3]/r)+'px';s.height=(x[4]/r)+'px';" +
				"s.borderRadius=(x[5]/r)+'px';" +
				"var f='blur('+(x[7]/r)+'px) saturate('+x[8]+')';s.backdropFilter=f;s.webkitBackdropFilter=f;" +
				"if(fresh){(function(n,a){requestAnimationFrame(function(){requestAnimationFrame(function(){" +
				"if(!n.getAttribute('data-gone'))n.style.opacity=a;});});})(n,x[6]);}else s.opacity=x[6];}" +
				"for(var c=h.firstChild;c;){var nx=c.nextSibling;if(!seen[c.getAttribute('data-k')]&&!c.getAttribute('data-gone')){" +
				"c.setAttribute('data-gone','1');c.style.opacity=0;(function(c){setTimeout(function(){" +
				"if(c.getAttribute('data-gone')&&c.parentNode)c.parentNode.removeChild(c);},260);})(c);}c=nx;}" +
				"return ok;}catch(e){return false;}})(" + data + ")";
	}

	/** The web page under {@code v}: a WebView of the tab in front that is on screen and overlaps it. */
	@Nullable
	private static WebView webViewUnder(View v) {
		for (WebView w : pages()) {
			if (w != v) return w;
		}
		return null;
	}

	/** A surface's view, for the activity the pages are looked for in. */
	private static WeakReference<View> anchor = new WeakReference<>(null);
	private static final List<WebView> found = new ArrayList<>();
	private static long foundAt = -1;

	/** The WebViews of the tab in front (looked for at most once a frame's worth of time). */
	private static List<WebView> pages() {
		long now = SystemClock.uptimeMillis();
		if (now - foundAt < 16) return found;
		foundAt = now;
		found.clear();
		try {
			View v = anchor.get();
			if (v == null) return found;
			MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(v.getContext()).peek();
			if (a == null) return found;
			var f = a.getActiveFragment();
			if (f != null) collect(f.getView(), found);
		} catch (Throwable ignore) {
		}
		return found;
	}

	private static void collect(@Nullable View v, List<WebView> out) {
		if ((v == null) || (v.getVisibility() != View.VISIBLE)) return;
		if (v instanceof WebView w) {
			if ((w.getWidth() > 0) && (w.getHeight() > 0)) out.add(w);
			return;
		}
		if (v instanceof ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) collect(g.getChildAt(i), out);
		}
	}
}
