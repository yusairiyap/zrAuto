package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.view.View;
import android.widget.LinearLayout;

import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.util.NetworkState;
import me.aap.fermata.ytdl.OfflineFallback;
import me.aap.utils.pref.PreferenceStore;

/**
 * Playback &gt; Network and offline, in the look of the Home and Power settings: offline mode by hand, what
 * happens when a stream stalls and how long to wait for it, and a shortcut to the mobile data limit.
 */
public class NetworkSettingsFragment extends ReorderListFragment {

	/** The category in the list beside the page (a wide window) that this page belongs to. */
	@Override
	public int settingsEntryTitle() {
		return R.string.network_settings;
	}

	@Override
	public int getFragmentId() {
		return R.id.network_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.network_settings);
	}

	@Override
	protected List<Row> loadRows() {
		return Collections.emptyList();
	}

	@Override
	protected void saveOrder(List<String> keys) {
	}

	@Override
	protected void setChecked(String key, boolean checked) {
	}

	@Override
	protected void showRowMenu(Row row) {
	}

	@Override
	protected void onReset() {
		PreferenceStore p = OfflineFallback.prefs();
		try (var e = p.editPreferenceStore()) {
			e.removePref(OfflineFallback.ON_STALL);
			e.removePref(OfflineFallback.STALL_SECONDS);
		}
		for (Runnable r : syncers) r.run();
	}

	@Override
	protected int resetTitle() {
		return R.string.network_settings_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.network_settings_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.network_settings_hint;
	}

	@Override
	protected void goBack() {
		delegate().showFragment(R.id.settings_fragment);
	}

	@Override
	protected View createExtraHeader() {
		LinearLayout box = new LinearLayout(palette);
		box.setOrientation(LinearLayout.VERTICAL);
		PreferenceStore p = OfflineFallback.prefs();

		section(box, R.string.network_section_mode);
		addSwitchRow(box, R.string.network_offline_mode, R.string.network_offline_mode_sub,
				NetworkState::isForcedOffline, NetworkState::setForcedOffline, null);

		section(box, R.string.network_section_stall);
		addSwitchRow(box, R.string.offline_on_stall, R.string.offline_on_stall_sub,
				() -> p.getBooleanPref(OfflineFallback.ON_STALL),
				on -> p.applyBooleanPref(OfflineFallback.ON_STALL, on), null);
		addValueSliderRow(box, R.string.offline_stall_seconds, R.string.offline_stall_seconds_sub, 3, 60,
				() -> p.getIntPref(OfflineFallback.STALL_SECONDS),
				v -> p.applyIntPref(OfflineFallback.STALL_SECONDS, v),
				v -> getString(R.string.network_seconds, v), null);

		for (Runnable r : syncers) r.run();
	}

	@Override
	protected int resetTitle() {
		return R.string.network_settings_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.network_settings_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.network_settings_hint;
	}

	@Override
	protected void goBack() {
		delegate().showFragment(R.id.settings_fragment);
	}

	@Override
	protected View createExtraHeader() {
		LinearLayout box = new LinearLayout(palette);
		box.setOrientation(LinearLayout.VERTICAL);
		PreferenceStore p = OfflineFallback.prefs();

		section(box, R.string.network_section_mode);
		addSwitchRow(box, R.string.network_offline_mode, R.string.network_offline_mode_sub,
				NetworkState::isForcedOffline, NetworkState::setForcedOffline, null);

		section(box, R.string.network_section_stall);
		addSwitchRow(box, R.string.offline_on_stall, R.string.offline_on_stall_sub,
				() -> p.getBooleanPref(OfflineFallback.ON_STALL),
				on -> p.applyBooleanPref(OfflineFallback.ON_STALL, on), null);
		addValueSliderRow(box, R.string.offline_stall_seconds, R.string.offline_stall_seconds_sub, 3, 60,
				() -> p.getIntPref(OfflineFallback.STALL_SECONDS),
				v -> p.applyIntPref(OfflineFallback.STALL_SECONDS, v),
				v -> getString(R.string.network_seconds, v), null);

		section(box, R.string.network_section_data);
		addActionRow(box, R.string.network_data_usage, R.string.network_data_usage_sub, R.drawable.wifi,
				() -> delegate().showFragment(R.id.data_usage_addon));

		for (Runnable r : syncers) r.run();
		box.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return box;
	}
}
