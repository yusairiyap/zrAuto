package me.aap.fermata.action;

import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
import static android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AbsSeekBar;
import android.widget.CompoundButton;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import me.aap.fermata.R;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.DownloadsFragment;
import me.aap.fermata.ui.fragment.MediaLibFragment;
import me.aap.fermata.ui.fragment.SettingsFragment;
import me.aap.fermata.ui.fragment.AudioEffectsFragment;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.menu.OverlayMenuView;

/**
 * Car mode (Settings &gt; Key bindings &gt; Car mode): the steering wheel's previous/next buttons
 * move a highlight through whatever is on top of the screen, so it can be used without touching it.
 * <ul>
 *   <li>Previous / next: the item before / after (left / right in a grid).</li>
 *   <li>Long next: taps the highlighted item (plays it, opens a folder, picks a playlist, runs a
 *   search chip, ...).</li>
 *   <li>Long previous: back (out of a folder, closes a panel or a picker).</li>
 * </ul>
 * What's "on top", first match wins: an open menu, picker or panel ({@link #markScope} - the
 * YouTube search / Up next panel, the Music tab's queue, the add to playlist / download pickers, every
 * overlay menu), else a Favorites / Playlists / Folders / Downloads list. Anywhere else, and with car
 * mode off, the keys do what they're bound to.
 * <p>
 * The items are found, not declared: every visible view with a click listener inside the scope, and
 * the rows of its lists (a list row without a click listener of its own offers the clickable views
 * in it instead, like the YouTube panel's search chips).
 */
public final class CarNav {
	private static final String TAG = "CARNAV";
	private static final long LONG_MS = 800;
	private static final long IDLE_HIDE_MS = 12000;
	/** Rows without anything to select (section headers) skipped in one go, at most. */
	private static final int MAX_SKIP = 64;
	private static final Runnable NO_BACK = () -> {};
	private static final Handler handler = new Handler(Looper.getMainLooper());

	// The key being held, see handleKeyEvent().
	private static int downCode;
	private static boolean longFired;
	@Nullable
	private static Scope downScope;
	@Nullable
	private static MediaSessionCallback downCb;
	private static long downTime;
	@Nullable
	private static MainActivityDelegate downActivity;
	private static final Runnable longPress = CarNav::onLongPress;

	// What's selected.
	@Nullable
	private static Scope scope;
	@Nullable
	private static Sel sel;
	/** The next selection starts at the first list row (rather than any button above it). */
	private static boolean fresh = true;
	/** A slider is grabbed: previous/next move it, see activate(). */
	private static boolean adjusting;
	@Nullable
	private static Highlight highlight;
	private static final Runnable idleHide = () -> {
		if (adjusting) return;
		Highlight h = highlight;
		if (h != null) h.fadeOut();
	};

	private CarNav() {
	}

	public static boolean isEnabled() {
		return Key.getPrefs().getBooleanPref(Key.CAR_MODE);
	}

	/**
	 * Makes {@code root} something car mode moves through while it's shown, above the tab under it.
	 *
	 * @param back what long previous does in it; null for the usual back (the back button)
	 */
	public static void markScope(@NonNull View root, @Nullable Runnable back) {
		root.setTag(R.id.car_nav_scope, (back != null) ? back : NO_BACK);
	}

	/**
	 * Same as {@link #markScope}, for a picker or menu opened over everything (add to playlist):
	 * moved through even over fullscreen video, where the keys otherwise keep their bindings.
	 */
	public static void markModalScope(@NonNull View root, @Nullable Runnable back) {
		markScope(root, back);
		root.setTag(R.id.car_nav_modal, Boolean.TRUE);
	}

	/** Leaves {@code v} (and what's in it) out: a button too risky to land on, like Clear. */
	public static void skip(@Nullable View v) {
		if (v != null) v.setTag(R.id.car_nav_skip, Boolean.TRUE);
	}

	// ---------------------------------------------------------------------------------------------
	// Keys

	/** @return true if car mode took the event */
	static boolean handleKeyEvent(KeyEvent e, @Nullable MainActivityDelegate activity,
																MediaSessionCallback cb) {
		int code = e.getKeyCode();
		if ((code != KEYCODE_MEDIA_NEXT) && (code != KEYCODE_MEDIA_PREVIOUS)) return false;

		switch (e.getAction()) {
			case ACTION_DOWN -> {
				if (e.getRepeatCount() > 0) return downCode == code;
				if (!isEnabled()) return false;
				MainActivityDelegate a = (activity != null) ? activity : MainActivityDelegate.getUiDelegate();
				handler.removeCallbacks(longPress);
				// Fullscreen video (YouTube's too): the keys are the user's own bindings, untouched,
				// unless a picker or menu is open over it (add to playlist): that one is moved through.
				boolean fullscreen = (a != null) && isFullscreenVideo(a);
				Scope s = (a == null) ? null : findScope(a, fullscreen);
				if (fullscreen && (s == null)) {
					downCode = 0;
					clear();
					return false;
				}
				if (s == null) {
					clear();
					// The YouTube tab, video not fullscreen: a long press (next or previous) goes
					// fullscreen, a click is still the key's own binding.
					if ((a != null) && isYoutubeWindowed(a)) {
						downCode = code;
						downScope = null;
						downActivity = a;
						downCb = cb;
						downTime = SystemClock.uptimeMillis();
						longFired = false;
						handler.postDelayed(longPress, LONG_MS);
						return true;
					}
					downCode = 0;
					return false;
				}
				downCode = code;
				downScope = s;
				downActivity = a;
				downCb = cb;
				downTime = SystemClock.uptimeMillis();
				longFired = false;
				handler.postDelayed(longPress, LONG_MS);
				return true;
			}
			case ACTION_UP -> {
				if (downCode != code) return false;
				downCode = 0;
				handler.removeCallbacks(longPress);
				if (!longFired) {
					if (downScope != null) click(downScope, code == KEYCODE_MEDIA_NEXT);
					else runBinding((code == KEYCODE_MEDIA_NEXT) ? Key.MEDIA_NEXT.getClickAction() :
							Key.MEDIA_PREVIOUS.getClickAction());
				}
				downScope = null;
				return true;
			}
			default -> {
				return downCode == code;
			}
		}
	}

	/**
	 * A next/previous command from Android Auto rather than a key: no press and release to tell a
	 * long press by, so it only ever moves the highlight.
	 *
	 * @return true if car mode took it
	 */
	public static boolean onTransport(boolean next) {
		if (!isEnabled()) return false;
		MainActivityDelegate a = MainActivityDelegate.getUiDelegate();
		Scope s = (a == null) ? null : findScope(a, isFullscreenVideo(a));
		if (s == null) {
			clear();
			return false;
		}
		click(s, next);
		return true;
	}

	private static void onLongPress() {
		if (downCode == 0) return;
		Scope s = downScope;
		if (s == null) {
			// See the YouTube tab case in handleKeyEvent().
			longFired = true;
			DiagnosticLog.log(TAG, "long press: YouTube fullscreen");
			runBinding(Action.FULLSCREEN_TOGGLE);
			return;
		}
		longFired = true;
		boolean next = downCode == KEYCODE_MEDIA_NEXT;
		DiagnosticLog.log(TAG, next ? "long next" : "long previous", "scope=" + s);
		if (next) activate(s);
		else back(s);
	}

	private static void runBinding(@Nullable Action action) {
		MainActivityDelegate a = downActivity;
		if ((action == null) || (a == null)) return;
		MediaSessionCallback cb = (downCb != null) ? downCb : a.getMediaSessionCallback();
		action.getHandler().handle(cb, a, downTime);
	}

	/** A video is fullscreen: local video mode, or a WebView player's own (YouTube). */
	private static boolean isFullscreenVideo(MainActivityDelegate a) {
		if (a.isVideoMode()) return true;
		me.aap.fermata.ui.view.VideoView vv = a.getActiveVideoView();
		return (vv != null) && vv.isInNativeFullscreen();
	}

	/** The YouTube tab is showing, its video not fullscreen. */
	private static boolean isYoutubeWindowed(MainActivityDelegate a) {
		ActivityFragment f = a.getActiveFragment();
		if ((f == null) || (f.getFragmentId() != R.id.youtube_fragment) || a.isVideoMode()) return false;
		me.aap.fermata.ui.view.VideoView vv = a.getActiveVideoView();
		return (vv != null) && !vv.isInNativeFullscreen();
	}

	// ---------------------------------------------------------------------------------------------
	// Scopes

	private static final class Scope {
		final MainActivityDelegate activity;
		final View root;
		/** Long previous: null for the back button, as in a list. */
		@Nullable
		final Runnable back;
		@Nullable
		final ActivityFragment fragment;

		Scope(MainActivityDelegate activity, View root, @Nullable Runnable back,
					@Nullable ActivityFragment fragment) {
			this.activity = activity;
			this.root = root;
			this.back = back;
			this.fragment = fragment;
		}

		@NonNull
		@Override
		public String toString() {
			return (fragment != null) ? fragment.getClass().getSimpleName() :
					root.getClass().getSimpleName();
		}
	}

	@Nullable
	private static Scope findScope(MainActivityDelegate a) {
		return findScope(a, false);
	}

	/** @param overlaysOnly only an open picker, menu or panel, not the tab under it */
	@Nullable
	private static Scope findScope(MainActivityDelegate a, boolean overlaysOnly) {
		View main = a.findViewById(R.id.main_activity);
		if (main != null) {
			View[] top = new View[1];
			findTopScope(main, top, overlaysOnly);
			if (top[0] != null) {
				Object back = top[0].getTag(R.id.car_nav_scope);
				Runnable r = (back instanceof Runnable b) && (b != NO_BACK) ? b : null;
				return new Scope(a, top[0], (r != null) ? r : a::onBackPressed, null);
			}
		}

		if (overlaysOnly) return null;
		ActivityFragment f = a.getActiveFragment();
		if ((f instanceof MediaLibFragment) || (f instanceof DownloadsFragment) ||
				(f instanceof SettingsFragment) || (f instanceof AudioEffectsFragment)) {
			View v = f.getView();
			if ((v != null) && v.isShown()) return new Scope(a, v, null, f);
		}
		return null;
	}

	/** The last (so, drawn on top) shown scope in the tree. */
	private static void findTopScope(View v, View[] top, boolean modalOnly) {
		if (v.getVisibility() != View.VISIBLE) return;
		boolean scope = (v instanceof OverlayMenuView) || (modalOnly ?
				(v.getTag(R.id.car_nav_modal) != null) : (v.getTag(R.id.car_nav_scope) != null));
		if (scope && v.isShown() && (v.getWidth() > 0)) top[0] = v;
		if (v instanceof ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) {
				findTopScope(g.getChildAt(i), top, modalOnly);
			}
		}
	}

	private static void enterScope(Scope s) {
		if ((scope != null) && (scope.root == s.root)) {
			scope = s;
			return;
		}
		DiagnosticLog.log(TAG, "scope", s);
		clear();
		scope = s;
	}

	private static void setAdjusting(boolean on) {
		adjusting = on;
		Highlight h = highlight;
		if (h != null) {
			h.setStrong(on);
			h.flash();
		}
		handler.removeCallbacks(idleHide);
		if (!on) handler.postDelayed(idleHide, IDLE_HIDE_MS);
	}

	/** Forgets the selection and takes the highlight down. */
	private static void clear() {
		adjusting = false;
		sel = null;
		scope = null;
		fresh = true;
		handler.removeCallbacks(idleHide);
		Highlight h = highlight;
		highlight = null;
		if (h != null) h.detach(true);
	}

	// ---------------------------------------------------------------------------------------------
	// Actions

	private static void click(Scope s, boolean next) {
		enterScope(s);
		// The keyboard (Android Auto's too) goes down: the keys are moving around the screen now, and
		// the press moves on as meant (to the next search chip).
		s.activity.dismissKeyboard();
		if (adjusting) {
			View v = (sel == null) ? null : resolve(sel);
			if (v instanceof AbsSeekBar sb) {
				int k = next ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
				sb.onKeyDown(k, new KeyEvent(KeyEvent.ACTION_DOWN, k));
				sb.onKeyUp(k, new KeyEvent(KeyEvent.ACTION_UP, k));
				show(sel);
				return;
			}
			setAdjusting(false);
		}
		// After a while without a press the highlight fades: the first press brings it back where it
		// was, rather than moving it somewhere unseen.
		Sel cur = sel;
		if ((cur != null) && (highlight != null) && highlight.faded && (resolve(cur) != null)) {
			show(cur);
			return;
		}
		step(s, next ? 1 : -1);
	}

	private static void activate(Scope s) {
		enterScope(s);
		s.activity.dismissKeyboard();
		Sel cur = sel;
		View v = (cur == null) ? null : resolve(cur);
		if (v == null) {
			step(s, 1); // Nothing selected yet: select, don't guess what to tap.
			return;
		}
		DiagnosticLog.log(TAG, "activate", v.getClass().getSimpleName());
		// A slider: hold next grabs it (previous/next then move it), hold again lets go.
		if (v instanceof AbsSeekBar) {
			setAdjusting(!adjusting);
			return;
		}
		Highlight h = highlight;
		if (h != null) h.flash();
		// What it opens (a folder, search results) starts again from its first row.
		fresh = true;
		sel = null;
		handler.postDelayed(() -> {
			if (h != null) h.detach(true);
			if (highlight == h) highlight = null;
		}, 260);
		v.performClick();
	}

	private static void back(Scope s) {
		enterScope(s);
		// A grabbed slider: let go of it, staying on it.
		if (adjusting) {
			setAdjusting(false);
			return;
		}
		if (s.back != null) {
			clear();
			s.back.run();
			return;
		}
		ActivityFragment f = s.fragment;
		sel = null;
		fresh = true;
		// Up a folder / a settings page; a screen opened over a tab (Audio effects, Settings from the
		// menu) back to that tab; only at the top of a tab itself, to what's playing.
		if ((f != null) && (!f.isRootPage() || (f.getFragmentId() != s.activity.getActiveNavItemId()))) {
			s.activity.onBackPressed();
			return;
		}
		s.activity.getControlPanel().openNowPlaying();
	}

	/**
	 * Puts the outline on row {@code pos} of {@code rv} (scrolled into view), as if the keys had
	 * moved there: car mode carries on from it. Shown with car mode off too, to point something out
	 * (the playing item when its tab opens).
	 */
	public static void highlightRow(MainActivityDelegate a, RecyclerView rv, int pos) {
		Scope s = findScope(a);
		if (s == null) return;
		List<Object> segs = new ArrayList<>();
		collect(s.root, segs, true);
		int idx = segs.indexOf(rv);
		if (idx < 0) return;
		enterScope(s);
		fresh = false;
		seek(segs, idx, rv, pos, 1, 1);
	}

	// ---------------------------------------------------------------------------------------------
	// Moving

	/** A selected item: a view of its own, or a list row (and which clickable in it). */
	private static final class Sel {
		@Nullable
		final RecyclerView rv;
		final int pos;
		final int sub;
		@Nullable
		final WeakReference<View> view;

		Sel(View view) {
			rv = null;
			pos = -1;
			sub = 0;
			this.view = new WeakReference<>(view);
		}

		Sel(RecyclerView rv, int pos, int sub) {
			this.rv = rv;
			this.pos = pos;
			this.sub = sub;
			view = null;
		}
	}

	private static void step(Scope s, int dir) {
		List<Object> segs = new ArrayList<>();
		collect(s.root, segs, true);
		if (segs.isEmpty()) return;

		Sel cur = sel;
		int idx = (cur == null) ? -1 : segs.indexOf((cur.rv != null) ? cur.rv :
				(cur.view != null) ? cur.view.get() : null);
		if ((cur == null) || (idx < 0)) {
			selectInitial(segs);
			return;
		}
		if (cur.rv == null) {
			moveFrom(segs, idx, dir);
			return;
		}

		RecyclerView rv = cur.rv;
		RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(cur.pos);
		List<View> units = (vh == null) ? Collections.emptyList() : unitsOf(vh.itemView);
		int sub = cur.sub + dir;
		if ((sub >= 0) && (sub < units.size())) {
			select(new Sel(rv, cur.pos, sub), units.get(sub));
			return;
		}
		seek(segs, idx, rv, cur.pos + dir, dir, MAX_SKIP);
	}

	/** The first row of the first list with any (the first visible one), else the first item. */
	private static void selectInitial(List<Object> segs) {
		boolean preferList = fresh;
		fresh = false;
		if (preferList) {
			for (int i = 0; i < segs.size(); i++) {
				if ((segs.get(i) instanceof RecyclerView rv) && (count(rv) > 0)) {
					int first = 0;
					if (rv.getLayoutManager() instanceof LinearLayoutManager lm) {
						first = Math.max(0, lm.findFirstCompletelyVisibleItemPosition());
						if (lm.findFirstCompletelyVisibleItemPosition() < 0) {
							first = Math.max(0, lm.findFirstVisibleItemPosition());
						}
					}
					seek(segs, i, rv, first, 1, MAX_SKIP);
					return;
				}
			}
		}
		moveFrom(segs, -1, 1);
	}

	/** To the segment after (or before) {@code idx}: its first (or last) item. */
	private static void moveFrom(List<Object> segs, int idx, int dir) {
		int j = idx + dir;
		if ((j < 0) || (j >= segs.size())) {
			// The end: stay, and show where.
			Highlight h = highlight;
			if (h != null) h.flash();
			return;
		}
		Object o = segs.get(j);
		if (o instanceof RecyclerView rv) {
			int n = count(rv);
			if (n == 0) moveFrom(segs, j, dir);
			else seek(segs, j, rv, (dir > 0) ? 0 : n - 1, dir, MAX_SKIP);
		} else {
			select(new Sel((View) o), (View) o);
		}
	}

	/** Row {@code pos} of {@code rv}, or the next one in {@code dir} that has anything to select. */
	private static void seek(List<Object> segs, int segIdx, RecyclerView rv, int pos, int dir,
													 int budget) {
		if ((pos < 0) || (pos >= count(rv)) || (budget <= 0)) {
			moveFrom(segs, segIdx, dir);
			return;
		}
		bound(rv, pos, vh -> {
			List<View> units = (vh == null) ? Collections.emptyList() : unitsOf(vh.itemView);
			if (units.isEmpty()) {
				seek(segs, segIdx, rv, pos + dir, dir, budget - 1);
				return;
			}
			int sub = (dir > 0) ? 0 : units.size() - 1;
			select(new Sel(rv, pos, sub), units.get(sub));
		});
	}

	/** Row {@code pos}'s view holder, scrolling it into the list first if it isn't laid out. */
	private static void bound(RecyclerView rv, int pos, Consumer<RecyclerView.ViewHolder> then) {
		RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
		if (vh != null) {
			then.accept(vh);
			return;
		}
		rv.scrollToPosition(pos);
		ViewTreeObserver.OnGlobalLayoutListener[] l = new ViewTreeObserver.OnGlobalLayoutListener[1];
		Runnable[] timeout = new Runnable[1];
		l[0] = () -> {
			rv.getViewTreeObserver().removeOnGlobalLayoutListener(l[0]);
			handler.removeCallbacks(timeout[0]);
			then.accept(rv.findViewHolderForAdapterPosition(pos));
		};
		timeout[0] = () -> {
			rv.getViewTreeObserver().removeOnGlobalLayoutListener(l[0]);
			then.accept(rv.findViewHolderForAdapterPosition(pos));
		};
		rv.getViewTreeObserver().addOnGlobalLayoutListener(l[0]);
		handler.postDelayed(timeout[0], 300);
		rv.requestLayout();
	}

	private static void select(Sel s, View v) {
		sel = s;
		show(s);
		// Into view: scrolls the list (and a row of chips sideways) as far as needed, with a little
		// room around it so it doesn't end up under the tool bar's edge.
		int m = Math.round(24 * v.getResources().getDisplayMetrics().density);
		v.requestRectangleOnScreen(new Rect(-m, -m, v.getWidth() + m, v.getHeight() + m), false);
	}

	private static void show(Sel s) {
		Scope sc = scope;
		if (sc == null) return;
		Highlight h = highlight;
		if ((h == null) || (h.root != sc.root)) {
			if (h != null) h.detach(true);
			highlight = h = new Highlight(sc.root);
		}
		h.track(s);
		handler.removeCallbacks(idleHide);
		handler.postDelayed(idleHide, IDLE_HIDE_MS);
	}

	// ---------------------------------------------------------------------------------------------
	// Finding items

	private static int count(RecyclerView rv) {
		RecyclerView.Adapter<?> a = rv.getAdapter();
		return (a == null) ? 0 : a.getItemCount();
	}

	/** The scope's items in order: views to select, and lists (whose rows are selected in turn). */
	private static void collect(View v, List<Object> out, boolean root) {
		if (v.getVisibility() != View.VISIBLE) return;
		if (!root && (v.getTag(R.id.car_nav_skip) != null)) return;
		if (v instanceof RecyclerView rv) {
			out.add(rv);
			return;
		}
		if (!root && isUnit(v)) {
			out.add(v);
			return;
		}
		if (v instanceof ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) collect(g.getChildAt(i), out, false);
		}
	}

	/** A list row: itself if it reacts to a tap, else the views in it that do. */
	private static List<View> unitsOf(View row) {
		if (isUnit(row)) return Collections.singletonList(row);
		List<View> out = new ArrayList<>();
		collectUnits(row, out);
		return out;
	}

	private static void collectUnits(View v, List<View> out) {
		if (v.getVisibility() != View.VISIBLE) return;
		if (v.getTag(R.id.car_nav_skip) != null) return;
		if (isUnit(v)) {
			out.add(v);
			return;
		}
		if (v instanceof RecyclerView) return;
		if (v instanceof ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) collectUnits(g.getChildAt(i), out);
		}
	}

	private static boolean isUnit(View v) {
		if (!v.isEnabled() || (v instanceof EditText) || (v.getWidth() <= 0) || (v.getHeight() <= 0) ||
				(v.getTag(R.id.car_nav_skip) != null)) {
			return false;
		}
		// A switch or check box reacts to a tap without a click listener of its own; a slider is
		// moved with the keys once grabbed.
		return v.hasOnClickListeners() || ((v instanceof CompoundButton) && v.isClickable()) ||
				(v instanceof AbsSeekBar);
	}

	/** The view {@code s} stands for right now, or null if it's not on screen. */
	@Nullable
	private static View resolve(Sel s) {
		View v;
		if (s.rv != null) {
			RecyclerView.ViewHolder vh = s.rv.findViewHolderForAdapterPosition(s.pos);
			if (vh == null) return null;
			List<View> units = unitsOf(vh.itemView);
			if (units.isEmpty()) return null;
			v = units.get(Math.min(s.sub, units.size() - 1));
		} else {
			v = (s.view == null) ? null : s.view.get();
		}
		return ((v != null) && v.isAttachedToWindow() && v.isShown()) ? v : null;
	}

	// ---------------------------------------------------------------------------------------------
	// Highlight

	/**
	 * The outline around the selected item: drawn on the item's own overlay, so it scrolls, slides
	 * and fades with it whatever it's in. Checked before every frame while it's up, as a list row's
	 * view is reused for another row once scrolled away (the outline then moves to the right one).
	 */
	private static final class Highlight implements ViewTreeObserver.OnPreDrawListener {
		final View root;
		@Nullable
		private Sel sel;
		@Nullable
		private View on;
		@Nullable
		private Outline outline;
		private boolean listening;
		boolean faded;
		/** A grabbed slider: drawn bolder. */
		private boolean strong;

		Highlight(View root) {
			this.root = root;
		}

		void track(Sel s) {
			sel = s;
			faded = false;
			if (!listening && root.getViewTreeObserver().isAlive()) {
				root.getViewTreeObserver().addOnPreDrawListener(this);
				listening = true;
			}
			View v = resolve(s);
			if (v != on) moveTo(v, true);
			else if (outline != null) outline.pop();
			root.invalidate();
		}

		@Override
		public boolean onPreDraw() {
			if (!root.isAttachedToWindow()) {
				detach(false);
				return true;
			}
			Sel s = sel;
			View v = (s == null) ? null : resolve(s);
			if (v != on) moveTo(v, false);
			else if ((v != null) && (outline != null)) outline.fit(v);
			return true;
		}

		private void moveTo(@Nullable View v, boolean animate) {
			if ((on != null) && (outline != null)) {
				View old = on;
				Outline o = outline;
				if (animate) o.fadeOut(() -> old.getOverlay().remove(o));
				else old.getOverlay().remove(o);
			}
			on = v;
			outline = null;
			if ((v == null) || faded) return;
			Outline o = new Outline(v);
			o.strong = strong;
			v.getOverlay().add(o);
			outline = o;
			if (animate) o.pop();
			else o.showNow();
		}

		void flash() {
			if (outline != null) outline.flash();
		}

		void setStrong(boolean on) {
			strong = on;
			if (outline != null) {
				outline.strong = on;
				outline.invalidateSelf();
			}
		}

		void fadeOut() {
			faded = true;
			// Nothing to follow while faded: no per-frame check either (track() adds it back).
			if (listening) {
				ViewTreeObserver vto = root.getViewTreeObserver();
				if (vto.isAlive()) vto.removeOnPreDrawListener(this);
				listening = false;
			}
			if ((on != null) && (outline != null)) {
				View old = on;
				Outline o = outline;
				o.fadeOut(() -> old.getOverlay().remove(o));
			}
			on = null;
			outline = null;
		}

		void detach(boolean animate) {
			if (listening) {
				ViewTreeObserver vto = root.getViewTreeObserver();
				if (vto.isAlive()) vto.removeOnPreDrawListener(this);
				listening = false;
			}
			sel = null;
			if ((on != null) && (outline != null)) {
				View old = on;
				Outline o = outline;
				if (animate) o.fadeOut(() -> old.getOverlay().remove(o));
				else old.getOverlay().remove(o);
			}
			on = null;
			outline = null;
		}
	}

	/** A rounded outline with a soft fill, inside its view's bounds; pops in and fades out. */
	private static final class Outline extends Drawable {
		private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final float strokeW;
		private final float radius;
		private final int accent;
		private float scale = 1f;
		private float alpha = 1f;
		private float flash;
		boolean strong;
		@Nullable
		private ValueAnimator anim;

		Outline(View v) {
			float d = v.getResources().getDisplayMetrics().density;
			strokeW = 3 * d;
			accent = EffectsUi.accent(v.getContext());
			stroke.setStyle(Paint.Style.STROKE);
			stroke.setStrokeWidth(strokeW);
			glow.setStyle(Paint.Style.STROKE);
			glow.setStrokeWidth(strokeW * 3);
			fill.setStyle(Paint.Style.FILL);
			radius = Math.min(14 * d, Math.min(v.getWidth(), v.getHeight()) / 2f);
			setBounds(0, 0, v.getWidth(), v.getHeight());
		}

		/** Follows its view's size (kept up to date by Highlight, before every frame). */
		void fit(View v) {
			Rect b = getBounds();
			if ((b.width() != v.getWidth()) || (b.height() != v.getHeight())) {
				setBounds(0, 0, v.getWidth(), v.getHeight());
			}
		}

		void pop() {
			animate(0.92f, 0f, 1f, 1f, 260, new OvershootInterpolator(2f), null);
		}

		void showNow() {
			if (anim != null) anim.cancel();
			scale = 1f;
			alpha = 1f;
			invalidateSelf();
		}

		void flash() {
			if (anim != null) anim.cancel();
			ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
			a.setDuration(380);
			a.addUpdateListener(x -> {
				flash = (float) x.getAnimatedValue();
				invalidateSelf();
			});
			anim = a;
			a.start();
		}

		void fadeOut(Runnable done) {
			animate(scale, alpha, 1.02f, 0f, 180, new DecelerateInterpolator(), done);
		}

		private void animate(float s0, float a0, float s1, float a1, long ms,
												 android.animation.TimeInterpolator interp, @Nullable Runnable done) {
			if (anim != null) anim.cancel();
			ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
			a.setDuration(ms);
			a.setInterpolator(interp);
			a.addUpdateListener(x -> {
				float f = (float) x.getAnimatedValue();
				scale = s0 + (s1 - s0) * f;
				alpha = a0 + (a1 - a0) * Math.min(1f, Math.max(0f, f));
				invalidateSelf();
			});
			if (done != null) {
				a.addListener(new android.animation.AnimatorListenerAdapter() {
					private boolean cancelled;

					@Override
					public void onAnimationCancel(android.animation.Animator animation) {
						cancelled = true;
					}

					@Override
					public void onAnimationEnd(android.animation.Animator animation) {
						if (!cancelled) done.run();
					}
				});
			}
			anim = a;
			a.start();
		}

		@Override
		public void draw(@NonNull Canvas c) {
			Rect b = getBounds();
			if (b.isEmpty() || (alpha <= 0f)) return;
			float inset = strokeW;
			rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
			float cx = rect.centerX();
			float cy = rect.centerY();
			c.save();
			c.scale(scale, scale, cx, cy);
			fill.setColor(withAlpha(accent, ((strong ? 0.32f : 0.16f) + 0.30f * flash) * alpha));
			stroke.setStrokeWidth(strong ? strokeW * 1.8f : strokeW);
			c.drawRoundRect(rect, radius, radius, fill);
			glow.setColor(withAlpha(accent, 0.25f * alpha));
			c.drawRoundRect(rect, radius, radius, glow);
			stroke.setColor(withAlpha(accent, alpha));
			c.drawRoundRect(rect, radius, radius, stroke);
			c.restore();
		}

		private static int withAlpha(int color, float a) {
			int al = Math.round(Math.max(0f, Math.min(1f, a)) * 255);
			return (color & 0x00FFFFFF) | (al << 24);
		}

		@Override
		public void setAlpha(int alpha) {
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter colorFilter) {
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}
}
