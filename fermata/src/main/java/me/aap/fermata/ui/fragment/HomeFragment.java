package me.aap.fermata.ui.fragment;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.home.HomeScreen;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ToolBarView;

/**
 * The Home tab, the app's start page: a screen of widgets (see {@link HomeScreen}) over a
 * background picture of the user's choice. It hides the tool bar and the control panel (the media
 * widget is its player), lets the background run on under the status bar, and has its own small
 * edit button in the top right corner.
 */
public class HomeFragment extends MainActivityFragment {
	private HomeScreen screen;

	@Override
	public int getFragmentId() {
		return R.id.home_fragment;
	}

	@NonNull
	@Override
	public CharSequence getTitle() {
		return getString(R.string.home);
	}

	/** No title bar: Home has its own header, and the room is for widgets. */
	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return HomeToolBarMediator.instance;
	}

	/** The background reaches out under a side navigation pill, so the usual edge fade is not laid over it. */
	@Override
	public boolean drawsBehindSideNavBar() {
		return true;
	}

	@Override
	public boolean collapsesBarsOnScroll() {
		return false;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		screen = new HomeScreen(getActivityDelegate());
		return screen.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		// Like the Music tab: the first screen after the app starts (or comes back from being killed) stays
		// invisible while the bars and insets settle, then fades in once.
		screen.onViewCreated((savedInstanceState != null) || getActivityDelegate().isInitialFragmentShow());
	}

	@Override
	public void onDestroyView() {
		if (screen != null) screen.onDestroyView();
		screen = null;
		super.onDestroyView();
	}

	@Override
	public void onResume() {
		super.onResume();
		if (!isHidden()) enter();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) leave();
		else enter();
	}

	// Both run before the fragment transaction that shows or hides this tab commits, exactly as the Music
	// tab's do: the bars Home has no use for are put away here, so the tab is laid out once, at its final
	// size, instead of first with them and again without (which re-packed a sideways grid in the middle of
	// the fade in on the car screen, and cut the background short under the bars).
	@Override
	public void switchingFrom(@Nullable ActivityFragment from) {
		super.switchingFrom(from);
		MainActivityDelegate a = null;
		if (getContext() != null) a = getActivityDelegate();
		else if ((from instanceof MainActivityFragment m) && (m.getContext() != null)) a = m.getActivityDelegate();
		ghosted = false;
		// A picture of the tab being left is taken first, while it still has the layout the user last saw: without the
		// tool bar its list moved up the moment the bar went, and was seen doing so while it faded out.
		if ((a != null) && a.getPrefs().getBooleanPref(MainActivityPrefs.HOME_ANIMATE) && !(from instanceof MusicPlayerFragment)) {
			ghosted = ghostOf(from);
		}
		if (a != null) putBarsAway(a);
	}

	@Override
	public void switchingTo(@NonNull ActivityFragment to) {
		super.switchingTo(to);
		if (getContext() == null) return;
		MainActivityDelegate a = getActivityDelegate();
		a.setOverlaysSuppressed(false);
		// The tab coming up has a tool bar: its room is there from its first layout (invisible, then fading in as the
		// tab fills it), not added a frame later, which pushed its list down and left it scrolled a little.
		ToolBarView tb = a.getToolBar();
		ToolBarView.Mediator m = to.getToolBarMediator();
		if ((tb != null) && (tb.getVisibility() == View.GONE) && (m != null) && !(m instanceof ToolBarView.Mediator.Invisible)) {
			tb.animate().cancel();
			tb.setAlpha(0f);
			tb.setVisibility(View.INVISIBLE);
		}
	}

	/** True when the tab left is shown as a picture over Home (see ghostOf), which then needs no fade of its own. */
	private boolean ghosted;

	/**
	 * With the old tab's picture over it, Home is simply there (opaque) and the picture fades away: no dip through the
	 * background in between. Without one (the Music tab) a short crossfade as before.
	 */
	@Override
	public long getFadeInMillis() {
		return ghosted ? 0L : 140L;
	}

	@Override
	public long getFadeInDelayMillis() {
		return 0L;
	}

	/**
	 * Draws the tab being left as a picture in its parent's overlay (above Home, taking no room) and fades it out over
	 * {@link #GHOST_MS}. The tab itself is hidden by the switch at once.
	 */
	private static boolean ghostOf(@Nullable ActivityFragment from) {
		View v = (from != null) ? from.getView() : null;
		if ((v == null) || !v.isLaidOut() || (v.getVisibility() != View.VISIBLE) || (v.getWidth() <= 0) ||
				(v.getHeight() <= 0) || (v.getAlpha() < 0.99f) || !(v.getParent() instanceof ViewGroup parent)) {
			return false;
		}
		Bitmap bm;
		try {
			bm = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
			Canvas c = new Canvas(bm);
			android.util.TypedValue tv = new android.util.TypedValue();
			if (v.getContext().getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true) &&
					(tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT) && (tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT)) {
				c.drawColor(tv.data);
			}
			v.draw(c);
		} catch (RuntimeException | OutOfMemoryError ex) {
			return false;
		}
		BitmapDrawable d = new BitmapDrawable(v.getResources(), bm);
		d.setBounds(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
		parent.getOverlay().add(d);
		ValueAnimator va = ValueAnimator.ofInt(255, 0);
		va.setDuration(GHOST_MS);
		va.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
		va.addUpdateListener(an -> d.setAlpha((Integer) an.getAnimatedValue()));
		va.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator an) {
				parent.getOverlay().remove(d);
			}
		});
		va.start();
		return true;
	}

	private static final long GHOST_MS = 180L;

	private void enter() {
		putBarsAway(getActivityDelegate());
		if (screen != null) screen.onShown();
	}

	private void leave() {
		if (screen != null) screen.onHidden();
		// The control panel and the floating buttons were given back by switchingTo(), before the switch
		// committed: doing it again here, after the next tab (the Music tab suppresses them) had its say, would undo that.
	}

	/**
	 * No title bar, control panel or floating buttons over Home: the media widget is its player and the
	 * widgets are its controls. They are taken out of the layout at once, like the Music tab does (so Home is laid
	 * out once, at its final size); the tool bar is still seen fading away, as a picture of itself.
	 */
	private static void putBarsAway(MainActivityDelegate a) {
		ToolBarView tb = a.getToolBar();
		if ((tb != null) && (tb.getVisibility() != View.GONE)) {
			if (a.getPrefs().getBooleanPref(MainActivityPrefs.HOME_ANIMATE)) fadeGhost(tb);
			tb.animate().cancel();
			tb.setAlpha(1f);
			tb.setVisibility(View.GONE);
		}
		a.setOverlaysSuppressed(true);
	}

	/**
	 * Draws {@code v} as a picture in its parent's overlay and fades that out together with the old tab's picture. The view itself is about to go
	 * out of the layout (its room is Home's); the picture takes no room, so nothing is resized while it fades.
	 */
	private static void fadeGhost(View v) {
		if (!v.isLaidOut() || (v.getVisibility() != View.VISIBLE) || (v.getWidth() <= 0) || (v.getHeight() <= 0) ||
				(v.getAlpha() < 0.05f) || !(v.getParent() instanceof ViewGroup parent)) {
			return;
		}
		Bitmap bm;
		try {
			bm = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
			v.draw(new Canvas(bm));
		} catch (RuntimeException | OutOfMemoryError ex) {
			return;
		}
		BitmapDrawable d = new BitmapDrawable(v.getResources(), bm);
		int x = Math.round(v.getX());
		int y = Math.round(v.getY());
		d.setBounds(x, y, x + v.getWidth(), y + v.getHeight());
		int from = Math.round(255 * Math.min(1f, v.getAlpha()));
		d.setAlpha(from);
		parent.getOverlay().add(d);
		ValueAnimator va = ValueAnimator.ofInt(from, 0);
		va.setDuration(GHOST_MS);
		va.addUpdateListener(an -> d.setAlpha((Integer) an.getAnimatedValue()));
		va.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator an) {
				parent.getOverlay().remove(d);
			}
		});
		va.start();
	}

	@Override
	public boolean onBackPressed() {
		if ((screen != null) && screen.onBack()) return true;
		return super.onBackPressed();
	}

	/** The title bar is gone while Home shows (already, see putBarsAway); the next tab's brings its own back. */
	private static final class HomeToolBarMediator implements ToolBarView.Mediator {
		static final HomeToolBarMediator instance = new HomeToolBarMediator();

		@Override
		public void enable(ToolBarView tb, ActivityFragment f) {
			tb.animate().cancel();
			tb.setAlpha(1f);
			tb.setVisibility(View.GONE);
		}

		@Override
		public void disable(ToolBarView tb) {
			tb.animate().cancel();
			ToolBarView.Mediator.super.disable(tb);
			// Whatever follows (the next tab's own mediator) fills the bar next; it is seen fading in, not appearing.
			if (tb.getVisibility() != View.GONE) {
				tb.setAlpha(0f);
				tb.animate().alpha(1f).setDuration(160).start();
			} else {
				tb.setAlpha(1f);
			}
		}
	}
}
