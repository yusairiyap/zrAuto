package me.aap.utils.ui.fragment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import me.aap.utils.function.BooleanConsumer;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.view.FloatingButton;
import me.aap.utils.ui.view.NavBarView;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
public abstract class ActivityFragment extends Fragment {

	public abstract int getFragmentId();

	public CharSequence getTitle() {
		return "";
	}

	public ToolBarView.Mediator getToolBarMediator() {
		return ToolBarView.Mediator.BackTitle.instance;
	}

	public NavBarView.Mediator getNavBarMediator() {
		return NavBarView.Mediator.instance;
	}

	public FloatingButton.Mediator getFloatingButtonMediator() {
		return FloatingButton.Mediator.Back.instance;
	}

	/**
	 * How long the crossfade into this tab lasts when it is switched to (the tab it replaces fades out in the same
	 * time at most). A tab that is mostly background (Home) wants it short, or the old tab shows through it.
	 */
	public long getFadeInMillis() {
		return 200L;
	}

	/**
	 * How long the tab it replaces fades out alone before this one starts to fade in (0: they cross, the default). A tab
	 * that is mostly background (Home) fades in only once the old one is gone, or the old tab shows through it.
	 */
	public long getFadeInDelayMillis() {
		return 0L;
	}

	public boolean isRootPage() {
		return true;
	}

	public boolean onBackPressed() {
		return false;
	}

	public void onRefresh(BooleanConsumer refreshing) {
	}

	public boolean canScrollUp() {
		return true;
	}

	public void navBarItemReselected(int itemId) {
	}

	public ActivityDelegate getActivityDelegate() {
		return ActivityDelegate.get(getContext());
	}

	public void switchingFrom(@Nullable ActivityFragment currentFragment) {
	}

	public void switchingTo(@NonNull ActivityFragment newFragment) {
	}

	public void setInput(Object input) {
		throw new UnsupportedOperationException();
	}
}
