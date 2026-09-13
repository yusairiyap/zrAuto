package me.aap.fermata.addon.fuel;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.ui.fragment.FuelLogFragment;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * Registers the Fuel Log as a nav-bar tab -- built directly into the {@code fermata} module (like
 * {@code TranslateAddon}/{@code SubGenAddon}) rather than as its own Gradle module, since it needs
 * no heavy or optional dependency that would benefit from on-demand delivery.
 */
@Keep
public class FuelLogAddon implements FermataFragmentAddon {
	private static final AddonInfo info = FermataAddon.findAddonInfo(FuelLogAddon.class.getName());

	@Override
	public int getAddonId() {
		return R.id.fuel_log_addon;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new FuelLogFragment();
	}

	@Override
	public void stop() {
		// Disabling the addon (via its Settings > Addons > Fuel Log > Enable toggle) should also
		// stop the GPS trip-distance tracking it started -- otherwise it would keep running
		// invisibly even with the tab gone.
		FuelTracker.get(FermataApplication.get()).stop();
	}
}
