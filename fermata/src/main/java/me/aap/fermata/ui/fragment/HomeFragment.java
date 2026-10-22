package me.aap.fermata.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
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
		if (a != null) putBarsAway(a);
	}

	@Override
	public void switchingTo(@NonNull ActivityFragment to) {
		super.switchingTo(to);
		if (getContext() == null) return;
		getActivityDelegate().setOverlaysSuppressed(false);
	}

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
	 * widgets are its controls. Done at once, not faded, like the Music tab does.
	 */
	private static void putBarsAway(MainActivityDelegate a) {
		ToolBarView tb = a.getToolBar();
		if ((tb != null) && (tb.getVisibility() != View.GONE)) {
			tb.animate().cancel();
			tb.setAlpha(1f);
			tb.setVisibility(View.GONE);
		}
		a.setOverlaysSuppressed(true);
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
			tb.setAlpha(1f);
			ToolBarView.Mediator.super.disable(tb);
		}
	}
}
