package me.aap.fermata.addon.web;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.LoadingCircleView;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.utils.app.App;

/**
 * A brief full-bleed scrim + spinner shown over a WebView's container while
 * {@code WebBrowserFragment.applyPrivateModeProfile()} swaps it for a freshly profile-bound
 * instance, so the swap and the page reload that follows it don't show as a jarring blank flash.
 */
final class ProfileSwitchOverlay {
	private static final long TIMEOUT_MS = 6000;

	private final ViewGroup parent;
	private final View scrim;
	private boolean removed;

	private ProfileSwitchOverlay(ViewGroup parent, View scrim) {
		this.parent = parent;
		this.scrim = scrim;
	}

	static ProfileSwitchOverlay show(ViewGroup parent) {
		Context ctx = parent.getContext();
		FrameLayout scrim = new FrameLayout(ctx);
		scrim.setBackgroundColor(Color.argb(204, 0, 0, 0));
		LoadingCircleView spinner = new LoadingCircleView(ctx);
		FrameLayout.LayoutParams spp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		spp.gravity = Gravity.CENTER;
		scrim.addView(spinner, spp);
		parent.addView(scrim, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		spinner.setLoading(true);
		return new ProfileSwitchOverlay(parent, scrim);
	}

	/** Hides the overlay once the new page finishes loading, or after a timeout, whichever first. */
	void watch(MainActivityDelegate a) {
		MainActivityListener[] listener = new MainActivityListener[1];
		listener[0] = (act, e) -> {
			if ((e & FRAGMENT_CONTENT_CHANGED) != 0) hide(act, listener[0]);
		};
		a.addBroadcastListener(listener[0], FRAGMENT_CONTENT_CHANGED);
		App.get().getHandler().postDelayed(() -> hide(a, listener[0]), TIMEOUT_MS);
	}

	private void hide(MainActivityDelegate a, MainActivityListener listener) {
		if (removed) return;
		removed = true;
		a.removeBroadcastListener(listener);
		parent.removeView(scrim);
	}
}
