package me.aap.fermata.addon;

import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * @author Andrey Pavlenko
 */
public interface FermataActivityAddon extends FermataAddon {

	default void onActivityCreate(MainActivityDelegate a) {
	}

	default void onActivityDestroy(MainActivityDelegate a) {
	}

	default void onActivityResume(MainActivityDelegate a) {
	}

	default void onActivityPause(MainActivityDelegate a) {
	}

	/**
	 * Fired when the Activity's window focus changes. On Android Auto builds this is the best
	 * available signal for a display takeover (e.g. a car's camera overlay briefly taking the
	 * screen) that doesn't route through the normal onActivityPause()/onActivityResume() pair.
	 */
	default void onActivityWindowFocusChanged(MainActivityDelegate a, boolean hasFocus) {
	}
}
