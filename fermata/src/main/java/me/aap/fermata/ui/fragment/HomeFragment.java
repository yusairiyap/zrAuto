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
import me.aap.fermata.ui.view.ControlPanelView;
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
	private boolean covered;

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
		screen.onViewCreated();
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

	// Both run before the fragment transaction that shows or hides this tab commits, so the control
	// panel is already on its way out while the tab fades in.
	@Override
	public void switchingFrom(@Nullable ActivityFragment from) {
		super.switchingFrom(from);
		MainActivityDelegate a = null;
		if (getContext() != null) a = getActivityDelegate();
		else if ((from instanceof MainActivityFragment m) && (m.getContext() != null)) a = m.getActivityDelegate();
		if (a != null) cover(a, true);
	}

	@Override
	public void switchingTo(@NonNull ActivityFragment to) {
		super.switchingTo(to);
		if (getContext() == null) return;
		cover(getActivityDelegate(), false);
	}

	private void enter() {
		MainActivityDelegate a = getActivityDelegate();
		cover(a, true);
		if (screen != null) screen.onShown();
	}

	private void leave() {
		if (screen != null) screen.onHidden();
		if (getContext() != null) cover(getActivityDelegate(), false);
	}

	/** The control panel fades away (and back) with the tab: the media widget is the player here. */
	private void cover(MainActivityDelegate a, boolean on) {
		covered = on;
		ControlPanelView cp = a.getControlPanel();
		if (cp != null) cp.setCovered(on);
		// The floating buttons have nothing to do here: the widgets are the controls.
		a.setFabsHidden(on);
	}

	@Override
	public boolean onBackPressed() {
		if ((screen != null) && screen.onBack()) return true;
		return super.onBackPressed();
	}

	/** The title bar is gone while Home shows: it fades out, and the next tab's brings its own back. */
	private static final class HomeToolBarMediator implements ToolBarView.Mediator {
		static final HomeToolBarMediator instance = new HomeToolBarMediator();

		@Override
		public void enable(ToolBarView tb, ActivityFragment f) {
			tb.animate().cancel();
			MainActivityDelegate d = MainActivityDelegate.getActivityDelegate(tb.getContext()).peek();
			if ((d != null) && d.isStartupVeil()) {
				// Still hidden behind the start-up veil: no empty bar fading away in the first frames.
				tb.setVisibility(View.GONE);
				return;
			}
			if ((tb.getVisibility() == View.VISIBLE) && tb.isLaidOut()) {
				tb.animate().alpha(0f).setDuration(160).withEndAction(() -> {
					tb.setVisibility(View.GONE);
					tb.setAlpha(1f);
				}).start();
			} else {
				tb.setVisibility(View.GONE);
			}
		}

		@Override
		public void disable(ToolBarView tb) {
			tb.animate().cancel();
			tb.setAlpha(1f);
			ToolBarView.Mediator.super.disable(tb);
		}
	}
}
