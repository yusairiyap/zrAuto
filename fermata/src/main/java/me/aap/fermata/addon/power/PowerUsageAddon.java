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
			if (p.getName().startsWith("POWER_SAVER_")) {
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

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		PreferenceStore ps = PowerUsageTracker.prefs();

		// Power saver first: it is what most people come here to set.
		set.addIntPref(o -> {
			o.store = ps;
			o.pref = PowerSaver.AUTO_LEVEL;
			o.title = R.string.power_pref_saver_auto_level;
			o.subtitle = R.string.power_pref_saver_auto_level_sub;
			o.seekMin = 0;
			o.seekMax = 50;
			o.seekScale = 1;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = ps;
			o.pref = PowerSaver.ON_CAR;
			o.title = R.string.power_pref_saver_car;
			o.subtitle = R.string.power_pref_saver_car_sub;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = ps;
			o.pref = PowerSaver.FOLLOW_SYSTEM;
			o.title = R.string.power_pref_saver_system;
			o.subtitle = R.string.power_pref_saver_system_sub;
			o.visibility = visibility.copy();
		});
		set.addIntPref(o -> {
			o.store = ps;
			o.pref = PowerSaver.BRIGHTNESS;
			o.title = R.string.power_pref_saver_brightness;
			o.subtitle = R.string.power_pref_saver_brightness_sub;
			o.seekMin = PowerSaver.MIN_BRIGHTNESS;
			o.seekMax = PowerSaver.MAX_BRIGHTNESS;
			o.seekScale = 1;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = ps;
			o.pref = PowerSaver.CAR_SCREEN;
			o.title = R.string.power_pref_saver_car_screen;
			o.subtitle = R.string.power_pref_saver_car_screen_sub;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = ps;
			o.pref = PowerSaver.CAP_REFRESH;
			o.title = R.string.power_pref_saver_refresh;
			o.subtitle = R.string.power_pref_saver_refresh_sub;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = ps;
			o.pref = PowerSaver.PAUSE_DOWNLOADS;
			o.title = R.string.power_pref_saver_downloads;
			o.subtitle = R.string.power_pref_saver_downloads_sub;
			o.visibility = visibility.copy();
		});

		// How the battery is read.
		set.addListPref(o -> {
			o.store = ps;
			o.pref = PowerUsageTracker.LOG_INTERVAL;
			o.title = R.string.power_pref_log_interval;
			o.subtitle = R.string.power_pref_log_interval_sub;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.power_log_15, R.string.power_log_30, R.string.power_log_60};
			o.visibility = visibility.copy();
		});
		set.addListPref(o -> {
			o.store = ps;
			o.pref = PowerUsageTracker.UNITS;
			o.title = R.string.power_pref_units;
			o.subtitle = R.string.power_pref_units_sub;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.power_units_auto, R.string.power_units_micro,
					R.string.power_units_milli};
			o.visibility = visibility.copy();
		});
		set.addListPref(o -> {
			o.store = ps;
			o.pref = PowerUsageTracker.SIGN;
			o.title = R.string.power_pref_sign;
			o.subtitle = R.string.power_pref_sign_sub;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.power_sign_auto, R.string.power_sign_normal,
					R.string.power_sign_inverted};
			o.visibility = visibility.copy();
		});

		// The Info Overlay item: the same prefs as under Settings > Interface > Info overlay.
		MainActivityPrefs mp = MainActivityPrefs.get();
		set.addBooleanPref(o -> {
			o.store = mp;
			o.pref = MainActivityPrefs.INFO_OVERLAY_SHOW_POWER;
			o.title = R.string.info_overlay_show_power;
			o.subtitle = R.string.power_overlay_sub;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = mp;
			o.pref = MainActivityPrefs.INFO_OVERLAY_SHOW_POWER_ICON;
			o.title = R.string.info_overlay_show_power_icon;
			o.visibility = visibility.copy();
		});

		set.addButton(o -> {
			o.title = R.string.power_pref_reset_session;
			o.subtitle = R.string.power_pref_reset_session_sub;
			o.visibility = visibility.copy();
			o.onClick = () -> {
				PowerUsageTracker.get().resetSession();
				TopToast.show(R.drawable.power_usage, R.string.power_reset_session_done);
			};
		});
		set.addButton(o -> {
			o.title = R.string.power_pref_reset_history;
			o.subtitle = R.string.power_pref_reset_history_sub;
			o.visibility = visibility.copy();
			o.onClick = () -> {
				try {
					UiUtils.showQuestion(ctx, R.string.power_pref_reset_history,
							R.string.power_reset_history_question, R.drawable.power_usage).onSuccess(v -> {
						PowerUsageTracker.get().reset();
						TopToast.show(R.drawable.power_usage, R.string.power_reset_history_done);
					});
				} catch (Exception err) {
					// A dialog that fails to inflate must not take the whole app down with it.
					DiagnosticLog.log("DIALOG", "failed to show power reset dialog:", err);
					Log.e(err, "Failed to show the power usage reset dialog");
				}
			};
		});
	}
}
