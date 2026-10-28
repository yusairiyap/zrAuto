package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.addon.ambience.AmbienceAddon;
import me.aap.fermata.addon.sleep.SleepAnalyzer;
import me.aap.fermata.addon.sleep.SleepInsights;
import me.aap.fermata.addon.sleep.SleepPrefs;
import me.aap.fermata.addon.sleep.SleepReminder;
import me.aap.fermata.addon.sleep.SleepTracker;
import me.aap.fermata.ui.view.SleepScoreView;
import me.aap.fermata.ui.view.SettingsStyle;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.UiUtils;

/**
 * The Sleep settings, in the look of the Home and Power settings: a live preview of the score on a made-up night at the top that
 * moves as the goal and the weights are changed, then the goal and usual times, the weights of the score's four parts, how the night
 * is recorded, the link to the Ambience sounds and the history. Opened from the Sleep tab's chip and from Settings &gt; Addons &gt; Sleep.
 */
public class SleepSettingsFragment extends ReorderListFragment {
	public static final String FROM_ADDON = "addon";
	public static final String FROM_TAB = "tab";
	private boolean fromTab;

	@Override
	public int getFragmentId() {
		return R.id.sleep_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.sleep_settings);
	}

	@Override
	public void setInput(Object input) {
		fromTab = FROM_TAB.equals(input);
	}

	private static PreferenceStore ps() {
		return SleepPrefs.store();
	}

	// ---- No rows: the page is all options ----

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
		PreferenceStore p = ps();
		try (var e = p.editPreferenceStore()) {
			e.removePref(SleepPrefs.GOAL_MIN);
			e.removePref(SleepPrefs.BED_MIN);
			e.removePref(SleepPrefs.WAKE_MIN);
			e.removePref(SleepPrefs.W_DURATION);
			e.removePref(SleepPrefs.W_CONSISTENCY);
			e.removePref(SleepPrefs.W_LATENCY);
			e.removePref(SleepPrefs.W_INTERRUPTIONS);
			e.removePref(SleepPrefs.MODE);
			e.removePref(SleepPrefs.SENSITIVITY);
			e.removePref(SleepPrefs.SCREEN_AWAKE);
			e.removePref(SleepPrefs.AUTO_STOP_H);
			e.removePref(SleepPrefs.REMINDER);
			e.removePref(SleepPrefs.REMINDER_LEAD);
			e.removePref(SleepPrefs.AMBIENCE_START);
			e.removePref(SleepPrefs.AMBIENCE_MIN);
			e.removePref(SleepPrefs.AMBIENCE_STOP);
			e.removePref(SleepPrefs.ANIMATE);
		}
		SleepReminder.reschedule();
		for (Runnable r : syncers) r.run();
	}

	@Override
	protected int resetTitle() {
		return R.string.sleep_settings_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.sleep_settings_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.sleep_settings_hint;
	}

	@Override
	protected void goBack() {
		if (fromTab) {
			delegate().showFragment(R.id.sleep_addon);
		} else {
			delegate().showFragment(R.id.settings_fragment, SettingsFragment.addonSettings("sleep"));
		}
	}

	// ---- The page ----

	@Nullable
	@Override
	protected View createExtraHeader() {
		LinearLayout box = new LinearLayout(palette);
		box.setOrientation(LinearLayout.VERTICAL);
		PreferenceStore p = ps();

		// The preview: a made-up night scored with the settings below, moving as they change.
		LinearLayout card = new LinearLayout(palette);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(dp(16), dp(14), dp(16), dp(12));
		card.setBackground(rounded(tileFill, 16));
		TextView t = text(R.string.sleep_preview_title, 13, accent, true);
		t.setAllCaps(true);
		t.setLetterSpacing(0.08f);
		card.addView(t);
		SleepScoreView preview = new SleepScoreView(palette, new SettingsStyle.Colors(requireContext()));
		card.addView(preview, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		card.addView(text(R.string.sleep_preview_sub, 12, textSecondary, false));
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		plp.topMargin = dp(10);
		box.addView(card, plp);
		syncers.add(() -> preview.setResult(SleepAnalyzer.sample(SleepAnalyzer.Settings.fromPrefs()),
				getString(R.string.sleep_preview_caption)));

		section(box, R.string.sleep_section_goal);
		addValueSliderRow(box, R.string.sleep_set_goal, R.string.sleep_set_goal_sub, 10, 20,
				() -> p.getIntPref(SleepPrefs.GOAL_MIN) / 30, v -> p.applyIntPref(SleepPrefs.GOAL_MIN, v * 30),
				v -> SleepInsights.duration(v * 30 * 60_000L), null);
		addValueSliderRow(box, R.string.sleep_set_bed, R.string.sleep_set_bed_sub, 0, 95,
				() -> p.getIntPref(SleepPrefs.BED_MIN) / 15, v -> {
					p.applyIntPref(SleepPrefs.BED_MIN, v * 15);
					SleepReminder.reschedule();
				},
				v -> SleepInsights.minuteOfDay(requireContext(), v * 15), null);
		addValueSliderRow(box, R.string.sleep_set_wake, R.string.sleep_set_wake_sub, 0, 95,
				() -> p.getIntPref(SleepPrefs.WAKE_MIN) / 15, v -> p.applyIntPref(SleepPrefs.WAKE_MIN, v * 15),
				v -> SleepInsights.minuteOfDay(requireContext(), v * 15), null);

		section(box, R.string.sleep_section_weights);
		weight(box, R.string.sleep_part_duration, SleepPrefs.W_DURATION);
		weight(box, R.string.sleep_part_regular, SleepPrefs.W_CONSISTENCY);
		weight(box, R.string.sleep_part_latency, SleepPrefs.W_LATENCY);
		weight(box, R.string.sleep_part_interruptions, SleepPrefs.W_INTERRUPTIONS);

		section(box, R.string.sleep_section_record);
		addChoiceRow(box, R.string.sleep_set_mode, R.string.sleep_set_mode_sub,
				new int[]{R.string.sleep_mode_times_only, R.string.sleep_mode_movement, R.string.sleep_mode_sound,
						R.string.sleep_mode_stand},
				() -> p.getIntPref(SleepPrefs.MODE), i -> p.applyIntPref(SleepPrefs.MODE, i));
		addChoiceRow(box, R.string.sleep_set_sensitivity, R.string.sleep_set_sensitivity_sub,
				new int[]{R.string.sleep_sens_low, R.string.sleep_sens_normal, R.string.sleep_sens_high},
				() -> p.getIntPref(SleepPrefs.SENSITIVITY), i -> p.applyIntPref(SleepPrefs.SENSITIVITY, i),
				() -> p.getIntPref(SleepPrefs.MODE) != SleepPrefs.MODE_TIMES);
		addSwitchRow(box, R.string.sleep_set_screen, R.string.sleep_set_screen_sub,
				() -> p.getBooleanPref(SleepPrefs.SCREEN_AWAKE),
				on -> p.applyBooleanPref(SleepPrefs.SCREEN_AWAKE, on), null);
		addValueSliderRow(box, R.string.sleep_set_auto_stop, R.string.sleep_set_auto_stop_sub, 4, 16,
				() -> p.getIntPref(SleepPrefs.AUTO_STOP_H), v -> p.applyIntPref(SleepPrefs.AUTO_STOP_H, v),
				v -> getString(R.string.sleep_hours, v), null);

		section(box, R.string.sleep_section_reminder);
		addSwitchRow(box, R.string.sleep_set_reminder, R.string.sleep_set_reminder_sub,
				() -> p.getBooleanPref(SleepPrefs.REMINDER), on -> {
					p.applyBooleanPref(SleepPrefs.REMINDER, on);
					SleepReminder.reschedule();
				}, null);
		addValueSliderRow(box, R.string.sleep_set_lead, R.string.sleep_set_lead_sub, 0, 12,
				() -> p.getIntPref(SleepPrefs.REMINDER_LEAD) / 5, v -> {
					p.applyIntPref(SleepPrefs.REMINDER_LEAD, v * 5);
					SleepReminder.reschedule();
				}, v -> getString(R.string.sleep_lead_at, v * 5,
						SleepInsights.minuteOfDay(requireContext(),
								Math.floorMod(p.getIntPref(SleepPrefs.BED_MIN) - v * 5, 1440))),
				() -> p.getBooleanPref(SleepPrefs.REMINDER));

		section(box, R.string.sleep_section_ambience);
		java.util.function.BooleanSupplier ambienceOn = AmbienceAddon::isEnabled;
		addSwitchRow(box, R.string.sleep_set_amb_start, R.string.sleep_set_amb_start_sub,
				() -> p.getBooleanPref(SleepPrefs.AMBIENCE_START),
				on -> p.applyBooleanPref(SleepPrefs.AMBIENCE_START, on), ambienceOn);
		addValueSliderRow(box, R.string.sleep_set_amb_min, R.string.sleep_set_amb_min_sub, 0, 12,
				() -> p.getIntPref(SleepPrefs.AMBIENCE_MIN) / 5, v -> p.applyIntPref(SleepPrefs.AMBIENCE_MIN, v * 5),
				v -> (v == 0) ? getString(R.string.sleep_no_limit) : getString(R.string.sleep_minutes_short, v * 5),
				() -> ambienceOn.getAsBoolean() && p.getBooleanPref(SleepPrefs.AMBIENCE_START));
		addSwitchRow(box, R.string.sleep_set_amb_stop, R.string.sleep_set_amb_stop_sub,
				() -> p.getBooleanPref(SleepPrefs.AMBIENCE_STOP),
				on -> p.applyBooleanPref(SleepPrefs.AMBIENCE_STOP, on), ambienceOn);

		section(box, R.string.sleep_section_look);
		addSwitchRow(box, R.string.sleep_set_animate, R.string.sleep_set_animate_sub,
				() -> p.getBooleanPref(SleepPrefs.ANIMATE), on -> p.applyBooleanPref(SleepPrefs.ANIMATE, on), null);

		section(box, R.string.sleep_section_history);
		addActionRow(box, R.string.sleep_set_clear, R.string.sleep_set_clear_sub, R.drawable.delete,
				() -> UiUtils.showQuestion(requireContext(), R.string.sleep_set_clear, R.string.sleep_clear_question,
						R.drawable.sleep).onSuccess(ok -> {
					SleepTracker.get().getStore().clear();
					TopToast.show(R.drawable.sleep, R.string.sleep_cleared);
				}));
		return box;
	}

	/** A weight of the score: 0 to 100, relative to the other three. */
	private void weight(LinearLayout box, int title, PreferenceStore.Pref<me.aap.utils.function.IntSupplier> pref) {
		PreferenceStore p = ps();
		addValueSliderRow(box, title, R.string.sleep_set_weight_sub, 0, 100,
				() -> p.getIntPref(pref), v -> p.applyIntPref(pref, v), v -> String.valueOf(v), null);
	}
}
