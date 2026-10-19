package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import me.aap.fermata.R;
import me.aap.fermata.addon.power.PowerSaver;
import me.aap.fermata.addon.power.PowerUsageTracker;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.UiUtils;

/**
 * The Power settings, in the look of the floating button settings: one page with two parts, picked
 * with the two pills at the top. <b>Power saver</b> holds when it starts by itself and what it does
 * (dimming the screen is one optional thing among them); <b>Power usage</b> holds how the battery
 * is read (the live and the background rhythm, the unit, which way charging points), the info
 * overlay and the history. Opened from the Power tab's chips (on the part they belong to) and from
 * Settings &gt; Addons &gt; Power usage.
 */
public class PowerSettingsFragment extends ReorderListFragment {
	/** The inputs: which part to show, and whether Back goes to the Power tab (else to Settings). */
	public static final String SAVER = "saver";
	public static final String USAGE = "usage";
	public static final String SAVER_FROM_TAB = "saver@tab";
	public static final String USAGE_FROM_TAB = "usage@tab";
	private boolean saverPart;
	private boolean fromTab;
	@Nullable
	private LinearLayout saverBox;
	@Nullable
	private LinearLayout usageBox;
	@Nullable
	private TextView saverTab;
	@Nullable
	private TextView usageTab;

	@Override
	public int getFragmentId() {
		return R.id.power_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.power_settings);
	}

	@Override
	public void setInput(Object input) {
		String s = (input instanceof String str) ? str : USAGE;
		saverPart = s.startsWith(SAVER);
		fromTab = s.endsWith("@tab");
		if (getView() != null) showPart();
	}

	private static PreferenceStore ps() {
		return PowerUsageTracker.prefs();
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
			e.removePref(PowerSaver.AUTO_LEVEL);
			e.removePref(PowerSaver.ON_CAR);
			e.removePref(PowerSaver.FOLLOW_SYSTEM);
			e.removePref(PowerSaver.DIM_SCREEN);
			e.removePref(PowerSaver.BRIGHTNESS);
			e.removePref(PowerSaver.CAR_SCREEN);
			e.removePref(PowerSaver.CAP_REFRESH);
			e.removePref(PowerSaver.PAUSE_DOWNLOADS);
			e.removePref(PowerUsageTracker.LIVE_INTERVAL);
			e.removePref(PowerUsageTracker.LOG_INTERVAL);
			e.removePref(PowerUsageTracker.UNITS);
			e.removePref(PowerUsageTracker.SIGN);
		}
		for (Runnable r : syncers) r.run();
	}

	@Override
	protected int resetTitle() {
		return R.string.power_settings_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.power_settings_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.power_settings_hint;
	}

	@Override
	protected void goBack() {
		if (fromTab) {
			delegate().showFragment(R.id.power_usage_addon);
		} else {
			delegate().showFragment(R.id.settings_fragment, SettingsFragment.addonSettings("power_usage"));
		}
	}

	// ---- The two parts ----

	@Override
	protected View createExtraHeader() {
		LinearLayout h = new LinearLayout(palette);
		h.setOrientation(LinearLayout.VERTICAL);

		LinearLayout tabs = new LinearLayout(palette);
		tabs.setOrientation(LinearLayout.HORIZONTAL);
		saverTab = tab(R.string.power_settings_tab_saver, R.drawable.power_saver, true);
		usageTab = tab(R.string.power_settings_tab_usage, R.drawable.power_usage, false);
		LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		l1.setMarginEnd(dp(4));
		LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		l2.setMarginStart(dp(4));
		tabs.addView(saverTab, l1);
		tabs.addView(usageTab, l2);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		tlp.topMargin = dp(6);
		h.addView(tabs, tlp);

		saverBox = new LinearLayout(palette);
		saverBox.setOrientation(LinearLayout.VERTICAL);
		buildSaver(saverBox);
		usageBox = new LinearLayout(palette);
		usageBox.setOrientation(LinearLayout.VERTICAL);
		buildUsage(usageBox);
		h.addView(saverBox, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		h.addView(usageBox, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		showPart();
		return h;
	}

	private TextView tab(@StringRes int label, @DrawableRes int icon, boolean saver) {
		TextView t = text(label, 14, textPrimary, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
		t.setCompoundDrawablePadding(dp(8));
		t.setPadding(dp(12), dp(13), dp(12), dp(13));
		GradientDrawable fill = rounded(tileFill, 16);
		t.setTag(R.id.power_settings_fragment, fill);
		t.setBackground(pressable(fill));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> {
			saverPart = saver;
			showPart();
		});
		return t;
	}

	/** Shows the part picked, and lights its pill. */
	private void showPart() {
		if ((saverBox == null) || (usageBox == null) || (saverTab == null) || (usageTab == null)) return;
		saverBox.setVisibility(saverPart ? View.VISIBLE : View.GONE);
		usageBox.setVisibility(saverPart ? View.GONE : View.VISIBLE);
		light(saverTab, saverPart);
		light(usageTab, !saverPart);
	}

	private void light(TextView t, boolean on) {
		GradientDrawable fill = (GradientDrawable) t.getTag(R.id.power_settings_fragment);
		fill.setColor(on ? accent : tileFill);
		t.setTextColor(on ? onAccent : textPrimary);
		t.setCompoundDrawableTintList(ColorStateList.valueOf(on ? onAccent : textSecondary));
	}

	@Override
	public void onDestroyView() {
		saverBox = null;
		usageBox = null;
		saverTab = null;
		usageTab = null;
		super.onDestroyView();
	}

	private void buildSaver(LinearLayout box) {
		PreferenceStore p = ps();
		section(box, R.string.power_section_starts);
		addValueSliderRow(box, R.string.power_set_level, R.string.power_set_level_sub, 0, 50,
				() -> p.getIntPref(PowerSaver.AUTO_LEVEL), v -> p.applyIntPref(PowerSaver.AUTO_LEVEL, v),
				v -> (v == 0) ? getString(R.string.power_set_off) : getString(R.string.power_set_percent, v), null);
		addSwitchRow(box, R.string.power_set_car, R.string.power_set_car_sub,
				() -> p.getBooleanPref(PowerSaver.ON_CAR), on -> p.applyBooleanPref(PowerSaver.ON_CAR, on), null);
		addSwitchRow(box, R.string.power_set_system, R.string.power_set_system_sub,
				() -> p.getBooleanPref(PowerSaver.FOLLOW_SYSTEM),
				on -> p.applyBooleanPref(PowerSaver.FOLLOW_SYSTEM, on), null);

		section(box, R.string.power_section_does);
		addSwitchRow(box, R.string.power_set_dim, R.string.power_set_dim_sub,
				() -> p.getBooleanPref(PowerSaver.DIM_SCREEN), on -> p.applyBooleanPref(PowerSaver.DIM_SCREEN, on),
				null);
		addValueSliderRow(box, R.string.power_set_brightness, R.string.power_set_brightness_sub,
				PowerSaver.MIN_BRIGHTNESS, PowerSaver.MAX_BRIGHTNESS,
				() -> p.getIntPref(PowerSaver.BRIGHTNESS), v -> p.applyIntPref(PowerSaver.BRIGHTNESS, v),
				v -> getString(R.string.power_set_percent, v), () -> p.getBooleanPref(PowerSaver.DIM_SCREEN));
		addSwitchRow(box, R.string.power_set_car_screen, R.string.power_set_car_screen_sub,
				() -> p.getBooleanPref(PowerSaver.CAR_SCREEN),
				on -> p.applyBooleanPref(PowerSaver.CAR_SCREEN, on), null);
		addSwitchRow(box, R.string.power_set_refresh, R.string.power_set_refresh_sub,
				() -> p.getBooleanPref(PowerSaver.CAP_REFRESH),
				on -> p.applyBooleanPref(PowerSaver.CAP_REFRESH, on), null);
		addSwitchRow(box, R.string.power_set_downloads, R.string.power_set_downloads_sub,
				() -> p.getBooleanPref(PowerSaver.PAUSE_DOWNLOADS),
				on -> p.applyBooleanPref(PowerSaver.PAUSE_DOWNLOADS, on), null);
	}

	private void buildUsage(LinearLayout box) {
		PreferenceStore p = ps();
		section(box, R.string.power_section_readings);
		addChoiceRow(box, R.string.power_set_live, R.string.power_set_live_sub,
				new int[]{R.string.power_live_500, R.string.power_live_1000, R.string.power_live_2000,
						R.string.power_live_5000},
				() -> p.getIntPref(PowerUsageTracker.LIVE_INTERVAL),
				i -> p.applyIntPref(PowerUsageTracker.LIVE_INTERVAL, i));
		addChoiceRow(box, R.string.power_set_background, R.string.power_set_background_sub,
				new int[]{R.string.power_bg_15, R.string.power_bg_30, R.string.power_bg_60},
				() -> p.getIntPref(PowerUsageTracker.LOG_INTERVAL),
				i -> p.applyIntPref(PowerUsageTracker.LOG_INTERVAL, i));
		addChoiceRow(box, R.string.power_set_units, R.string.power_set_units_sub,
				new int[]{R.string.power_unit_auto, R.string.power_unit_micro, R.string.power_unit_milli},
				() -> p.getIntPref(PowerUsageTracker.UNITS), i -> p.applyIntPref(PowerUsageTracker.UNITS, i));
		addChoiceRow(box, R.string.power_set_sign, R.string.power_set_sign_sub,
				new int[]{R.string.power_sign_auto_short, R.string.power_sign_pos, R.string.power_sign_neg},
				() -> p.getIntPref(PowerUsageTracker.SIGN), i -> p.applyIntPref(PowerUsageTracker.SIGN, i));

		section(box, R.string.power_section_overlay);
		addActionRow(box, R.string.power_set_overlay, R.string.power_set_overlay_sub, R.drawable.power_usage,
				() -> delegate().showFragment(R.id.info_overlay_fragment));

		section(box, R.string.power_section_history);
		addActionRow(box, R.string.power_set_reset_session, R.string.power_set_reset_session_sub,
				R.drawable.refresh, () -> {
					PowerUsageTracker.get().resetSession();
					TopToast.show(R.drawable.power_usage, R.string.power_reset_session_done);
				});
		addActionRow(box, R.string.power_set_reset_history, R.string.power_set_reset_history_sub,
				R.drawable.delete, () -> UiUtils.showQuestion(requireContext(), R.string.power_set_reset_history,
						R.string.power_reset_history_question, R.drawable.power_usage).onSuccess(ok -> {
					PowerUsageTracker.get().reset();
					TopToast.show(R.drawable.power_usage, R.string.power_reset_history_done);
				}));
	}

	// ---- Rows of options ----

	/** A slider with its title, a line under it and the value on the right, from {@code min} to {@code max}. */
	private void addValueSliderRow(LinearLayout parent, @StringRes int title, @StringRes int sub, int min,
																 int max, IntSupplier get, IntConsumer set, IntFunction<String> format,
																 @Nullable BooleanSupplier enabled) {
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.VERTICAL);
		row.setPadding(dp(16), dp(12), dp(16), dp(6));
		row.setBackground(rounded(tileFill, 16));
		LinearLayout top = new LinearLayout(palette);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.addView(text(title, 15, textPrimary, true), new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		TextView value = text(0, 14, textSecondary, true);
		top.addView(value);
		row.addView(top);
		row.addView(text(sub, 12.5f, textSecondary, false));
		SeekBar bar = newSeekBar();
		bar.setMax(max - min);
		bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
				value.setText(format.apply(min + progress));
				if (fromUser) {
					set.accept(min + progress);
					for (Runnable r : syncers) r.run();
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar s) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar s) {
			}
		});
		row.addView(bar, new LinearLayout.LayoutParams(MATCH_PARENT, dp(36)));
		syncers.add(() -> {
			boolean usable = (enabled == null) || enabled.getAsBoolean();
			int progress = Math.max(0, Math.min(max - min, get.getAsInt() - min));
			if (bar.getProgress() != progress) bar.setProgress(progress);
			else value.setText(format.apply(min + progress));
			bar.setEnabled(usable);
			row.setAlpha(usable ? 1f : 0.45f);
		});
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(10);
		parent.addView(row, lp);
	}

	/** A title and a line under it over a row of options, the picked one filled with the accent. */
	private void addChoiceRow(LinearLayout parent, @StringRes int title, @StringRes int sub, int[] labels,
														IntSupplier get, IntConsumer set) {
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.VERTICAL);
		row.setPadding(dp(16), dp(12), dp(16), dp(14));
		row.setBackground(rounded(tileFill, 16));
		row.addView(text(title, 15, textPrimary, true));
		TextView st = text(sub, 12.5f, textSecondary, false);
		st.setPadding(0, 0, 0, dp(10));
		row.addView(st);
		LinearLayout chips = new LinearLayout(palette);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		TextView[] views = new TextView[labels.length];
		Runnable refresh = () -> {
			int sel = get.getAsInt();
			for (int i = 0; i < views.length; i++) {
				boolean on = i == sel;
				views[i].setTextColor(on ? onAccent : textPrimary);
				((GradientDrawable) views[i].getTag(R.id.power_settings_fragment))
						.setColor(on ? accent : 0x1F808080);
			}
		};
		for (int i = 0; i < labels.length; i++) {
			int fi = i;
			TextView t = text(labels[i], 13.5f, textPrimary, true);
			t.setGravity(Gravity.CENTER);
			t.setSingleLine(true);
			t.setPadding(dp(6), dp(10), dp(6), dp(10));
			GradientDrawable fill = rounded(0x1F808080, 14);
			t.setTag(R.id.power_settings_fragment, fill);
			t.setBackground(pressable(fill));
			t.setClickable(true);
			t.setFocusable(true);
			t.setOnClickListener(v -> {
				set.accept(fi);
				for (Runnable r : syncers) r.run();
			});
			views[i] = t;
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins((i == 0) ? 0 : dp(3), 0, (i == labels.length - 1) ? 0 : dp(3), 0);
			chips.addView(t, lp);
		}
		row.addView(chips);
		syncers.add(refresh);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(10);
		parent.addView(row, lp);
	}

	/** A row that does something when tapped: an icon, a title with a line under it, and a chevron. */
	private void addActionRow(LinearLayout parent, @StringRes int title, @StringRes int sub, @DrawableRes int icon,
														Runnable click) {
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(12), dp(12), dp(12));
		row.setBackground(pressable(rounded(tileFill, 16)));
		row.setClickable(true);
		row.setFocusable(true);
		ImageView iv = new ImageView(palette);
		iv.setImageResource(icon);
		iv.setImageTintList(ColorStateList.valueOf(accent));
		row.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
		LinearLayout labels = new LinearLayout(palette);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.setPadding(dp(14), 0, dp(8), 0);
		labels.addView(text(title, 15, textPrimary, true));
		labels.addView(text(sub, 12.5f, textSecondary, false));
		row.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		ImageView chevron = new ImageView(palette);
		chevron.setImageResource(me.aap.utils.R.drawable.chevron_right);
		chevron.setImageTintList(ColorStateList.valueOf(textSecondary));
		row.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
		row.setOnClickListener(v -> click.run());
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(10);
		parent.addView(row, lp);
	}
}
