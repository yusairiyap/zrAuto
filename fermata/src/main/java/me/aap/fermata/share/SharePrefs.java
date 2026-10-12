package me.aap.fermata.share;

import android.content.ContentResolver;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;

import me.aap.fermata.FermataApplication;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/** Settings of sharing between phones on the same Wi-Fi (Settings > Other > Share). */
public final class SharePrefs {
	/** Show up to other phones as someone they can send to, while zrAuto is open. */
	public static final Pref<BooleanSupplier> RECEIVE = Pref.b("SHARE_RECEIVE", true);
	/** The name other phones see; empty for the phone's own name. */
	public static final Pref<Supplier<String>> DEVICE_NAME = Pref.s("SHARE_DEVICE_NAME", "");

	private SharePrefs() {
	}

	public static PreferenceStore store() {
		return FermataApplication.get().getPreferenceStore();
	}

	public static boolean isReceiving() {
		return store().getBooleanPref(RECEIVE);
	}

	/** The name this phone goes by in the Share window of other phones. */
	@NonNull
	public static String getDeviceName() {
		String n = store().getStringPref(DEVICE_NAME);
		if ((n != null) && !n.trim().isEmpty()) return n.trim();

		try {
			ContentResolver cr = FermataApplication.get().getContentResolver();
			String s = Settings.Global.getString(cr, "device_name");
			if ((s != null) && !s.trim().isEmpty()) return s.trim();
		} catch (Exception ignore) {
			// Falls through to the model name.
		}

		String model = Build.MODEL;
		return ((model == null) || model.isEmpty()) ? "Android" : model;
	}
}
