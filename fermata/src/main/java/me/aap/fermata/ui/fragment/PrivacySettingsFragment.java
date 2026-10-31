package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.view.View;
import android.widget.LinearLayout;

import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;

/**
 * Privacy, in the look of the Home and Power settings: what clears browsing data (each asks first, since it cannot be undone) and
 * Private Mode with the options that go with it, which stay in view (dimmed) while it is off instead of appearing only when it is on.
 */
public class PrivacySettingsFragment extends ReorderListFragment {

	@Override
	public int settingsEntryTitle() {
		return R.string.private_mode_prefs;
	}

	@Override
	public int getFragmentId() {
		return R.id.privacy_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.private_mode_prefs);
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
		var p = delegate().getPrefs();
		try (var e = p.editPreferenceStore()) {
			e.removePref(MainActivityPrefs.PRIVATE_MODE_ALWAYS);
			e.removePref(MainActivityPrefs.PRIVATE_MODE_BLOCK_TRACKERS);
			e.removePref(MainActivityPrefs.PRIVATE_MODE_BLOCK_3RD_PARTY_COOKIES);
		}
		for (Runnable r : syncers) r.run();
	}

	@Override
	protected int resetTitle() {
		return R.string.privacy_settings_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.privacy_settings_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.privacy_settings_hint;
	}

	@Override
	protected void goBack() {
		delegate().showFragment(R.id.settings_fragment);
	}

	@Override
	protected View createExtraHeader() {
		LinearLayout box = new LinearLayout(palette);
		box.setOrientation(LinearLayout.VERTICAL);
		var p = delegate().getPrefs();

		// What changes something at once comes first, and asks first.
		section(box, R.string.privacy_section_data);
		addActionRow(box, R.string.clear_browsing_data, R.string.clear_browsing_data_sub, R.drawable.private_mode, () ->
				UiUtils.showQuestion(delegate().getContext(), R.string.clear_browsing_data_confirm_title,
						R.string.clear_browsing_data_confirm, R.drawable.private_mode).onSuccess(v -> {
					Log.i("Clear browsing data: confirmed");
					p.requestNormalDataClear();
					TopToast.show(R.drawable.private_mode, R.string.browsing_data_cleared);
				}));
		addActionRow(box, R.string.private_mode_clear_now, R.string.private_mode_clear_now_sub, R.drawable.private_mode, () ->
				UiUtils.showQuestion(delegate().getContext(), R.string.private_mode_clear_confirm_title,
						R.string.private_mode_clear_confirm, R.drawable.private_mode).onSuccess(v -> {
					Log.i("Clear private browsing data now: confirmed");
					p.requestPrivateDataClear();
					TopToast.show(R.drawable.private_mode, R.string.private_mode_cleared);
				}));

		section(box, R.string.private_mode);
		addSwitchRow(box, R.string.private_mode, R.string.private_mode_sub,
				() -> p.getBooleanPref(MainActivityPrefs.PRIVATE_MODE_ENABLED),
				on -> p.applyBooleanPref(MainActivityPrefs.PRIVATE_MODE_ENABLED, on), null);
		java.util.function.BooleanSupplier on = () -> p.getBooleanPref(MainActivityPrefs.PRIVATE_MODE_ENABLED);
		addSwitchRow(box, R.string.private_mode_always, R.string.private_mode_always_sub,
				() -> p.getBooleanPref(MainActivityPrefs.PRIVATE_MODE_ALWAYS),
				v -> p.applyBooleanPref(MainActivityPrefs.PRIVATE_MODE_ALWAYS, v), on);
		addSwitchRow(box, R.string.private_mode_block_trackers, R.string.private_mode_block_trackers_sub,
				() -> p.getBooleanPref(MainActivityPrefs.PRIVATE_MODE_BLOCK_TRACKERS),
				v -> p.applyBooleanPref(MainActivityPrefs.PRIVATE_MODE_BLOCK_TRACKERS, v), on);
		addSwitchRow(box, R.string.private_mode_block_3rd_party_cookies, R.string.private_mode_block_3rd_party_cookies_sub,
				() -> p.getBooleanPref(MainActivityPrefs.PRIVATE_MODE_BLOCK_3RD_PARTY_COOKIES),
				v -> p.applyBooleanPref(MainActivityPrefs.PRIVATE_MODE_BLOCK_3RD_PARTY_COOKIES, v), on);

		for (Runnable r : syncers) r.run();
		box.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return box;
	}
}
