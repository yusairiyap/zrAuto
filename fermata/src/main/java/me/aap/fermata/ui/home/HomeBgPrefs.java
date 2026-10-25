package me.aap.fermata.ui.home;

import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore.Pref;

/** The Home background's preferences of the phone and of the car screen (each has its own set and its own picture). */
public final class HomeBgPrefs {
	private HomeBgPrefs() {
	}

	public static Pref<IntSupplier> version(boolean car) {
		return car ? MainActivityPrefs.HOME_BG_VERSION_AA : MainActivityPrefs.HOME_BG_VERSION;
	}

	public static Pref<IntSupplier> dim(boolean car) {
		return car ? MainActivityPrefs.HOME_BG_DIM_AA : MainActivityPrefs.HOME_BG_DIM;
	}

	public static Pref<IntSupplier> blur(boolean car) {
		return car ? MainActivityPrefs.HOME_BG_BLUR_AA : MainActivityPrefs.HOME_BG_BLUR;
	}

	public static Pref<IntSupplier> zoom(boolean car) {
		return car ? MainActivityPrefs.HOME_BG_ZOOM_AA : MainActivityPrefs.HOME_BG_ZOOM;
	}

	public static Pref<BooleanSupplier> hq(boolean car) {
		return car ? MainActivityPrefs.HOME_BG_HQ_AA : MainActivityPrefs.HOME_BG_HQ;
	}

	public static Pref<IntSupplier> source(boolean car) {
		return car ? MainActivityPrefs.HOME_BG_SOURCE_AA : MainActivityPrefs.HOME_BG_SOURCE;
	}
}
