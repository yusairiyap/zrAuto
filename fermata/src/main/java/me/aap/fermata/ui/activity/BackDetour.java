package me.aap.fermata.ui.activity;

import androidx.annotation.Nullable;

import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * A page that sends the user somewhere else in the app (the wizard's "All keys and their settings",
 * the Power settings' link to the Info overlay) says so here, so that Back from there goes back to
 * the page that sent them, instead of wherever that screen's own Back would go.
 * <p>
 * It holds for as long as the screen it sent them to is the one showing: Back there runs
 * {@code back}; if they went somewhere else meanwhile (another tab), the detour is over and
 * {@code cancel} runs once, so whatever was waiting can let go.
 */
public final class BackDetour {
	private static int targetId;
	@Nullable
	private static Runnable back;
	@Nullable
	private static Runnable cancel;

	private BackDetour() {
	}

	/**
	 * Call just before showing the fragment {@code target}.
	 *
	 * @param back   puts the caller back on screen
	 * @param cancel the caller is not coming back (may be null)
	 */
	public static void start(int target, Runnable back, @Nullable Runnable cancel) {
		clear(true);
		targetId = target;
		BackDetour.back = back;
		BackDetour.cancel = cancel;
	}

	/** Called by the activity's Back: true if it took the press and went back to the caller. */
	public static boolean onBack(MainActivityDelegate a) {
		Runnable r = back;
		if (r == null) return false;
		ActivityFragment f = a.getActiveFragment();
		if ((f == null) || (f.getFragmentId() != targetId)) {
			clear(true);
			return false;
		}
		clear(false);
		r.run();
		return true;
	}

	private static void clear(boolean runCancel) {
		Runnable c = cancel;
		back = null;
		cancel = null;
		if (runCancel && (c != null)) c.run();
	}
}
