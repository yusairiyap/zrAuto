package me.aap.fermata.addon.power;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import java.util.List;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataActivityAddon;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.addon.FermataMediaServiceAddon;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.fragment.PowerSettingsFragment;
import me.aap.fermata.ui.fragment.PowerUsageFragment;
import me.aap.fermata.ui.view.TopToast;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.log.Log;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * The Power Usage tab: the live battery current like the Ampere app, its lowest and highest this
 * session, a trend of the charge drawn per hour and day split by what the app was doing, and the
 * power saver mode (see {@link PowerUsageTracker} and {@link PowerSaver}). Built into the
 * {@code fermata} module like the Data Usage tab.
 * <p>
 * Measuring starts with the app (the activity or the playback service, whichever comes first),
 * not with the tab being opened, so the history fills whether or not anyone looks at it.
 */
@Keep
public class PowerUsageAddon implements FermataFragmentAddon, FermataActivityAddon,
		FermataMediaServiceAddon, PreferenceStore.Listener {
	private static final AddonInfo info = FermataAddon.findAddonInfo(PowerUsageAddon.class.getName());
	private boolean listening;

	@Override
	public int getAddonId() {
		return R.id.power_usage_addon;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new PowerUsageFragment();
	}

	/** Whether the addon is on: its actions only work then. */
	public static boolean isEnabled() {
		me.aap.fermata.addon.AddonManager m = me.aap.fermata.addon.AddonManager.get();
		return (m != null) && (m.getAddon(PowerUsageAddon.class) != null);
	}

	private void listen() {
		if (listening) return;
		listening = true;
		PowerUsageTracker.prefs().addBroadcastListener(this);
	}

	/** A power saver setting changed: what the mode holds follows at once. */
	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		for (PreferenceStore.Pref<?> p : prefs) {
			String name = p.getName();
			if (name.equals(PowerUsageTracker.LIVE_INTERVAL.getName()) ||
					name.equals(PowerUsageTracker.LOG_INTERVAL.getName())) {
				PowerUsageTracker.get().intervalChanged();
				return;
			}
			if (name.startsWith("POWER_SAVER_")) {
				PowerSaver.get().settingsChanged();
				PowerSaver.get().evaluate(false);
				return;
			}
		}
	}

	@Override
	public void onActivityCreate(MainActivityDelegate a) {
		listen();
		PowerUsageTracker.get().start(a.getMediaSessionCallback());
		PowerSaver.get().attach(a);
	}

	@Override
	public void onActivityDestroy(MainActivityDelegate a) {
		PowerSaver.get().detach(a);
		PowerUsageTracker.get().setScreenVisible(a, false);
	}

	@Override
	public void onActivityResume(MainActivityDelegate a) {
		PowerUsageTracker.get().setScreenVisible(a, true);
		// Whether Android Auto is connected may have changed since: the brightness cap follows.
		PowerSaver.get().apply(a);
	}

	@Override
	public void onActivityPause(MainActivityDelegate a) {
		PowerUsageTracker.get().setScreenVisible(a, false);
		PowerUsageTracker.get().flush();
	}

	@Override
	public void onServiceCreate(MediaSessionCallback cb) {
		listen();
		PowerUsageTracker.get().start(cb);
	}

	@Override
	public void onServiceDestroy(MediaSessionCallback cb) {
		PowerUsageTracker.get().flush();
	}

	@Override
	public void stop() {
		if (listening) {
			listening = false;
			PowerUsageTracker.prefs().removeBroadcastListener(this);
		}
		PowerSaver.get().shutdown();
		PowerUsageTracker.get().stop();
	}

	/**
	 * Under Settings > Addons > Power usage: two doors to the Power settings page (see
	 * {@link PowerSettingsFragment}), one for each part, instead of one long list. The Info Overlay
	 * item is set under Interface > Info overlay (and linked from the usage part), not here.
	 */
	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		set.addButton(o -> {
			o.title = R.string.power_settings_tab_saver;
			o.subtitle = R.string.power_addon_saver_sub;
			o.icon = R.drawable.power_saver;
			o.visibility = visibility.copy();
			o.onClick = () -> MainActivityDelegate.get(ctx).showFragment(R.id.power_settings_fragment,
					PowerSettingsFragment.SAVER);
		});
		set.addButton(o -> {
			o.title = R.string.power_settings_tab_usage;
			o.subtitle = R.string.power_addon_usage_sub;
			o.icon = R.drawable.power_usage;
			o.visibility = visibility.copy();
			o.onClick = () -> MainActivityDelegate.get(ctx).showFragment(R.id.power_settings_fragment,
					PowerSettingsFragment.USAGE);
		});
	}
}
