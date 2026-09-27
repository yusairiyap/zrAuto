package me.aap.fermata.addon.data;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataActivityAddon;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.addon.FermataMediaServiceAddon;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.fragment.DataUsageFragment;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PrefCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * The Data Usage tab: how much internet data the app has used, split into YouTube videos, YouTube
 * in music mode and everything else, with a data limit and warning (see
 * {@link DataUsageTracker}). Built into the {@code fermata} module like the Fuel Log.
 * <p>
 * Measuring starts with the app (the activity or the playback service, whichever comes first),
 * not with the tab being opened, so usage is counted whether or not anyone looks at it.
 */
@Keep
public class DataUsageAddon implements FermataFragmentAddon, FermataActivityAddon,
		FermataMediaServiceAddon {
	private static final AddonInfo info = FermataAddon.findAddonInfo(DataUsageAddon.class.getName());
	// The warning/limit banner and Tap to continue cover of each open screen (phone, car).
	private final java.util.Map<MainActivityDelegate, DataUsageAlerts> alerts =
			new java.util.HashMap<>();

	@Override
	public int getAddonId() {
		return R.id.data_usage_addon;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new DataUsageFragment();
	}

	@Override
	public void onActivityCreate(MainActivityDelegate a) {
		DataUsageTracker.get().start(a.getMediaSessionCallback());
		DataUsageAlerts old = alerts.put(a, DataUsageAlerts.attach(a));
		if (old != null) old.detach();
	}

	@Override
	public void onActivityDestroy(MainActivityDelegate a) {
		DataUsageAlerts al = alerts.remove(a);
		if (al != null) al.detach();
	}

	@Override
	public void onActivityPause(MainActivityDelegate a) {
		DataUsageTracker.get().flush();
	}

	@Override
	public void onServiceCreate(MediaSessionCallback cb) {
		DataUsageTracker.get().start(cb);
	}

	@Override
	public void onServiceDestroy(MediaSessionCallback cb) {
		DataUsageTracker.get().flush();
	}

	@Override
	public void stop() {
		for (DataUsageAlerts al : alerts.values()) al.detach();
		alerts.clear();
		DataUsageTracker.get().stop();
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		PreferenceStore ps = DataUsageTracker.prefs();
		set.addListPref(o -> {
			o.store = ps;
			o.pref = DataUsageTracker.NETWORKS;
			o.title = R.string.data_usage_networks;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.data_usage_networks_all, R.string.data_usage_networks_mobile};
			o.visibility = visibility.copy();
		});
		set.addFloatPref(o -> {
			o.store = ps;
			o.pref = DataUsageTracker.LIMIT_GB;
			o.title = R.string.data_usage_limit_gb;
			o.subtitle = R.string.data_usage_limit_gb_sub;
			// Typed in a box: a slider can't hit 0.25 GB, nor go past a fixed maximum.
			o.showProgress = false;
			o.inputBox = true;
			o.ems = 4;
			o.visibility = visibility.copy();
		});
		set.addFloatPref(o -> {
			o.store = ps;
			o.pref = DataUsageTracker.WARNING_GB;
			o.title = R.string.data_usage_warning_gb;
			o.subtitle = R.string.data_usage_warning_gb_sub;
			// Typed in a box: a slider can't hit 0.25 GB, nor go past a fixed maximum.
			o.showProgress = false;
			o.inputBox = true;
			o.ems = 4;
			o.visibility = visibility.copy();
		});
		set.addListPref(o -> {
			o.store = ps;
			o.pref = DataUsageTracker.CYCLE;
			o.title = R.string.data_usage_cycle;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.data_usage_cycle_day, R.string.data_usage_cycle_week,
					R.string.data_usage_cycle_month};
			o.visibility = visibility.copy();
		});
		set.addIntPref(o -> {
			o.store = ps;
			o.pref = DataUsageTracker.CYCLE_START;
			o.title = R.string.data_usage_cycle_start;
			o.seekMin = 1;
			o.seekMax = 28;
			o.visibility = visibility.copy().and(new PrefCondition<>(ps, DataUsageTracker.CYCLE,
					p -> ps.getIntPref(p) == DataUsageTracker.CYCLE_MONTH));
		});

		// The Info Overlay items: the same prefs as under Settings > Interface > Info overlay (the
		// activity's own store, so the overlays showing them follow a change straight away).
		MainActivityPrefs mp = MainActivityPrefs.get();
		set.addBooleanPref(o -> {
			o.store = mp;
			o.pref = MainActivityPrefs.INFO_OVERLAY_SHOW_DATA_USAGE;
			o.title = R.string.info_overlay_show_data_usage;
			o.subtitle = R.string.data_usage_overlay_sub;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = mp;
			o.pref = MainActivityPrefs.INFO_OVERLAY_SHOW_DATA_REMAINING;
			o.title = R.string.info_overlay_show_data_remaining;
			o.subtitle = R.string.data_usage_overlay_remaining_sub;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = mp;
			o.pref = MainActivityPrefs.INFO_OVERLAY_SHOW_DATA_ICON;
			o.title = R.string.info_overlay_show_data_icon;
			o.visibility = visibility.copy();
		});

		set.addButton(o -> {
			o.title = R.string.data_usage_reset;
			o.subtitle = R.string.data_usage_reset_sub;
			o.visibility = visibility.copy();
			o.onClick = () -> UiUtils.showQuestion(ctx, R.string.data_usage_reset,
					R.string.data_usage_reset_question, R.drawable.data_usage).onSuccess(v -> {
				DataUsageTracker.get().reset();
				UiUtils.showInfo(ctx, R.string.data_usage_reset_done);
			});
		});
	}
}
