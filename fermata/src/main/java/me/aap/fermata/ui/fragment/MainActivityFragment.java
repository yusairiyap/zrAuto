package me.aap.fermata.ui.fragment;

import android.content.Context;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;

import com.google.android.play.core.splitcompat.SplitCompat;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.VoiceCommand;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.view.FloatingButton;

/**
 * @author Andrey Pavlenko
 */
public abstract class MainActivityFragment extends ActivityFragment {

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		SplitCompat.install(context);
	}

	@Override
	public MainActivityDelegate getActivityDelegate() {
		return (MainActivityDelegate) super.getActivityDelegate();
	}

	@Override
	public NavBarMediator getNavBarMediator() {
		return getActivityDelegate().getNavBarMediator();
	}

	/**
	 * With a left/right nav bar, body_layout pads every tab clear of the floating nav pill (see
	 * MainActivityDelegate#syncSideNavInset). A tab returning true here extends its own background
	 * out under the pill past that padding itself (body_layout doesn't clip it there), so the pill
	 * doesn't lay the usual edge fade over it either.
	 */
	public boolean drawsBehindSideNavBar() {
		return false;
	}

	@Override
	public FloatingButton.Mediator getFloatingButtonMediator() {
		return FloatingButtonMediator.instance;
	}

	@CallSuper
	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) discardSelection();
	}

	@Override
	public boolean onBackPressed() {
		discardSelection();
		return super.onBackPressed();
	}

	public void contributeToNavBarMenu(OverlayMenu.Builder builder) {
	}

	public void discardSelection() {
	}

	public boolean isVideoModeSupported() {
		return false;
	}

	public boolean isVoiceCommandsSupported() {
		return false;
	}

	public boolean startVoiceAssistant() {
		return false;
	}

	public void voiceCommand(VoiceCommand cmd) {
	}

	/**
	 * The user is leaving the app (home, recents) while this is the active fragment -- see
	 * {@code MainActivity#onUserLeaveHint()}. The last chance to enter picture-in-picture.
	 */
	public void onUserLeaveHint() {
	}

	/**
	 * A touch went down outside the text field being typed into (screen coordinates) -- see
	 * {@code MainActivity#dispatchTouchEvent}. Followed by {@link #onTapOutsideTextField()} once the
	 * tap has been delivered and the keyboard closed.
	 */
	public void onTouchDownOutsideTextField(float x, float y) {
	}

	/** See {@link #onTouchDownOutsideTextField}. */
	public void onTapOutsideTextField() {
	}
}
