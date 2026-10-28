package me.aap.fermata.addon.sleep;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.SleepFragment;
import me.aap.fermata.ui.fragment.SleepSettingsFragment;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * The Sleep tab: records a night from the phone on the bed (its motion, and optionally the loudness of the room), scores it, draws
 * it and gives tips that include the Ambience sounds (see {@link SleepTracker}, {@link SleepAnalyzer}). Built into the
 * {@code fermata} module like the Ambience tab.
 */
@Keep
public class SleepAddon implements FermataFragmentAddon {
	private static final AddonInfo info = FermataAddon.findAddonInfo(SleepAddon.class.getName());

	@Override
	public int getAddonId() {
		return R.id.sleep_addon;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new SleepFragment();
	}

	/** Whether the addon is on: its actions only work then. */
	public static boolean isEnabled() {
		AddonManager m = AddonManager.get();
		return (m != null) && (m.getAddon(SleepAddon.class) != null);
	}

	/** Finishes a night an earlier run of the app left unfinished. */
	@Override
	public void start() {
		SleepTracker.get();
	}

	@Override
	public void stop() {
		SleepTracker t = SleepTracker.get();
		if (t.isActive()) t.stop();
	}

	/** Under Settings > Addons > Sleep: a door to the Sleep settings page. */
	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		set.addButton(o -> {
			o.title = R.string.sleep_settings;
			o.subtitle = R.string.sleep_settings_sub;
			o.icon = R.drawable.sleep;
			o.visibility = visibility.copy();
			o.onClick = () -> MainActivityDelegate.get(ctx).showFragment(R.id.sleep_settings_fragment,
					SleepSettingsFragment.FROM_ADDON);
		});
	}
}
