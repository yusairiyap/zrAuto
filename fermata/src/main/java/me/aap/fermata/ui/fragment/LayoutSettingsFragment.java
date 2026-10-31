package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Settings &gt; Layout and navigation, in the look of the Home settings: the bars (sizes, hiding, scrolling), the navigation bar,
 * the floating buttons, All in One, the Info overlay, the navigation tabs and Up next / search. With Android Auto built in, the two
 * pills at the top pick the phone's or the car screen's own twin of every setting; rows that only exist for the car are marked.
 * Opened with {@code Boolean} input: true for the car screen's side.
 */
public class LayoutSettingsFragment extends ReorderListFragment {
	private boolean car;
	private LinearLayout carOnly;

	/** The category in the list beside the page (a wide window) that this page belongs to. */
	@Override
	public int settingsEntryTitle() {
		return R.string.layout_settings;
	}

	@Override
	public int getFragmentId() {
		return R.id.layout_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.layout_settings);
	}

	@Override
	public void setInput(Object input) {
		if (input instanceof Boolean b) car = BuildConfig.AUTO && b;
		if (getView() != null) {
			for (Runnable r : syncers) r.run();
			showCarOnly();
		}
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
			for (Pref<?> x : new Pref<?>[]{MainActivityPrefs.HIDE_BARS, MainActivityPrefs.FULLSCREEN,
					MainActivityPrefs.SHOW_PG_UP_DOWN, MainActivityPrefs.NAV_BAR_POS, MainActivityPrefs.NAV_BAR_SIZE,
					MainActivityPrefs.TOOL_BAR_SIZE, MainActivityPrefs.CONTROL_PANEL_SIZE,
					MainActivityPrefs.TEXT_ICON_SIZE, MainActivityPrefs.ICON_SIZE, MainActivityPrefs.SCROLL_HIDE_BARS}) {
				e.removePref(x);
			}
			if (BuildConfig.AUTO) {
				for (Pref<?> x : new Pref<?>[]{MainActivityPrefs.HIDE_BARS_AA, MainActivityPrefs.FULLSCREEN_AA,
						MainActivityPrefs.SHOW_PG_UP_DOWN_AA, MainActivityPrefs.NAV_BAR_POS_AA,
						MainActivityPrefs.NAV_BAR_SIZE_AA, MainActivityPrefs.TOOL_BAR_SIZE_AA,
						MainActivityPrefs.CONTROL_PANEL_SIZE_AA, MainActivityPrefs.TEXT_ICON_SIZE_AA,
						MainActivityPrefs.ICON_SIZE_AA, MainActivityPrefs.SCROLL_HIDE_BARS_AA,
						MainActivityPrefs.TAP_ACTION_AA, MainActivityPrefs.USE_DPAD_CURSOR}) {
					e.removePref(x);
				}
			}
		}
		for (Runnable r : syncers) r.run();
	}

	@Override
	protected int resetTitle() {
		return R.string.layout_settings_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.layout_settings_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.layout_settings_hint;
	}

	@Override
	protected void goBack() {
		delegate().showFragment(R.id.settings_fragment);
	}

	// Each pair is the phone's pref and its Android Auto twin, picked by the pill.
	private <S> Pref<S> pick(Pref<S> phone, Pref<S> aa) {
		return (car && (aa != null)) ? aa : phone;
	}

	@Override
	protected View createExtraHeader() {
		LinearLayout box = new LinearLayout(palette);
		box.setOrientation(LinearLayout.VERTICAL);
		var p = delegate().getPrefs();
		if (!BuildConfig.AUTO) car = false;
		else if (delegate().isCarActivity()) car = true;

		if (BuildConfig.AUTO) {
			LinearLayout pills = new LinearLayout(palette);
			pills.setOrientation(LinearLayout.HORIZONTAL);
			TextView[] tabs = new TextView[2];
			int[] labels = {R.string.settings_screen_phone, R.string.settings_screen_car};
			Runnable light = () -> {
				for (int i = 0; i < 2; i++) {
					boolean on = (i == 1) == car;
					tabs[i].setTextColor(on ? onAccent : textPrimary);
					((GradientDrawable) tabs[i].getTag(R.id.settings_row_tag)).setColor(on ? accent : tileFill);
				}
			};
			for (int i = 0; i < 2; i++) {
				boolean carTab = i == 1;
				TextView t = text(labels[i], 14, textPrimary, true);
				t.setGravity(Gravity.CENTER);
				t.setPadding(dp(12), dp(13), dp(12), dp(13));
				GradientDrawable fill = rounded(tileFill, 16);
				t.setTag(R.id.settings_row_tag, fill);
				t.setBackground(pressable(fill));
				t.setClickable(true);
				t.setFocusable(true);
				t.setOnClickListener(v -> {
					car = carTab;
					light.run();
					for (Runnable r : syncers) r.run();
					showCarOnly();
				});
				tabs[i] = t;
				LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
				lp.setMargins(carTab ? dp(4) : 0, dp(6), carTab ? 0 : dp(4), 0);
				pills.addView(t, lp);
			}
			light.run();
			box.addView(pills, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		}

		section(box, R.string.layout_section_bars);
		addSwitchRow(box, R.string.hide_bars, R.string.hide_bars_sub,
				() -> p.getBooleanPref(pick(MainActivityPrefs.HIDE_BARS, MainActivityPrefs.HIDE_BARS_AA)),
				on -> p.applyBooleanPref(pick(MainActivityPrefs.HIDE_BARS, MainActivityPrefs.HIDE_BARS_AA), on), null);
		addSwitchRow(box, R.string.scroll_hide_bars, R.string.scroll_hide_bars_sub,
				() -> p.getBooleanPref(pick(MainActivityPrefs.SCROLL_HIDE_BARS, MainActivityPrefs.SCROLL_HIDE_BARS_AA)),
				on -> p.applyBooleanPref(pick(MainActivityPrefs.SCROLL_HIDE_BARS, MainActivityPrefs.SCROLL_HIDE_BARS_AA), on),
				null);
		addSwitchRow(box, R.string.fullscreen_mode, 0,
				() -> p.getBooleanPref(pick(MainActivityPrefs.FULLSCREEN, MainActivityPrefs.FULLSCREEN_AA)),
				on -> p.applyBooleanPref(pick(MainActivityPrefs.FULLSCREEN, MainActivityPrefs.FULLSCREEN_AA), on), null);
		addSwitchRow(box, R.string.show_pg_up_down, 0,
				() -> p.getBooleanPref(pick(MainActivityPrefs.SHOW_PG_UP_DOWN, MainActivityPrefs.SHOW_PG_UP_DOWN_AA)),
				on -> p.applyBooleanPref(pick(MainActivityPrefs.SHOW_PG_UP_DOWN, MainActivityPrefs.SHOW_PG_UP_DOWN_AA), on),
				null);
		size(box, R.string.tool_bar_size, MainActivityPrefs.TOOL_BAR_SIZE, MainActivityPrefs.TOOL_BAR_SIZE_AA);
		size(box, R.string.control_panel_size, MainActivityPrefs.CONTROL_PANEL_SIZE, MainActivityPrefs.CONTROL_PANEL_SIZE_AA);
		size(box, R.string.text_icon_size, MainActivityPrefs.TEXT_ICON_SIZE, MainActivityPrefs.TEXT_ICON_SIZE_AA);
		size(box, R.string.icon_size, MainActivityPrefs.ICON_SIZE, MainActivityPrefs.ICON_SIZE_AA);

		section(box, R.string.nav_bar_nav);
		addChoiceRow(box, R.string.nav_bar_pos, R.string.nav_bar_pos_sub,
				new int[]{R.string.nav_bar_pos_bottom, R.string.nav_bar_pos_left, R.string.nav_bar_pos_right},
				() -> p.getIntPref(pick(MainActivityPrefs.NAV_BAR_POS, MainActivityPrefs.NAV_BAR_POS_AA)),
				v -> p.applyIntPref(pick(MainActivityPrefs.NAV_BAR_POS, MainActivityPrefs.NAV_BAR_POS_AA), v));
		size(box, R.string.nav_bar_size, MainActivityPrefs.NAV_BAR_SIZE, MainActivityPrefs.NAV_BAR_SIZE_AA);
		addActionRow(box, R.string.nav_tabs_prefs, R.string.prefs_sub_nav_tabs, R.drawable.view_grid,
				() -> delegate().showFragment(R.id.nav_tabs_fragment));

		section(box, R.string.layout_section_buttons);
		addActionRow(box, R.string.secondary_fab_prefs, R.string.prefs_sub_fab, R.drawable.fab,
				() -> delegate().showFragment(R.id.fab_settings_fragment, car));
		addActionRow(box, R.string.all_in_one, R.string.all_in_one_sub, R.drawable.view_grid,
				() -> delegate().showFragment(R.id.all_in_one_fragment, car));
		addActionRow(box, R.string.info_overlay_prefs, R.string.prefs_sub_info_overlay, R.drawable.about,
				() -> delegate().showFragment(R.id.info_overlay_fragment, car));

		section(box, R.string.layout_section_lists);
		var media = delegate().getMediaServiceBinder().getLib().getPrefs();
		addSwitchRow(box, R.string.show_track_icons, 0,
				() -> media.getBooleanPref(me.aap.fermata.media.pref.BrowsableItemPrefs.SHOW_TRACK_ICONS),
				on -> media.applyBooleanPref(me.aap.fermata.media.pref.BrowsableItemPrefs.SHOW_TRACK_ICONS, on),
				null);

		section(box, R.string.up_next_prefs);
		upNextSwitch(box, MainActivityPrefs.SEARCH_HISTORY_ENABLED, R.string.search_history, R.string.search_history_sub);
		upNextSwitch(box, MainActivityPrefs.YT_SEARCH_LIBRARY, R.string.yt_search_library_pref, R.string.yt_search_library_pref_sub);
		upNextSwitch(box, MainActivityPrefs.YT_PANEL_CAR_KEYBOARD, R.string.yt_panel_car_keyboard, R.string.yt_panel_car_keyboard_sub);
		upNextSwitch(box, MainActivityPrefs.YT_SEARCH_TAP_QUEUES, R.string.yt_search_tap_queues, R.string.yt_search_tap_queues_sub);
		upNextSwitch(box, MainActivityPrefs.YT_SEPARATE_PANELS, R.string.yt_separate_panels, R.string.yt_separate_panels_sub);
		addValueSliderRow(box, R.string.up_next_max, R.string.up_next_max_sub, 1, 50,
				() -> p.getIntPref(MainActivityPrefs.UP_NEXT_MAX), v -> p.applyIntPref(MainActivityPrefs.UP_NEXT_MAX, v),
				String::valueOf, null);
		addValueSliderRow(box, R.string.up_next_list_preview, R.string.up_next_list_preview_sub, 1, 10,
				() -> p.getIntPref(MainActivityPrefs.UP_NEXT_LIST_PREVIEW),
				v -> p.applyIntPref(MainActivityPrefs.UP_NEXT_LIST_PREVIEW, v), String::valueOf, null);

		if (BuildConfig.AUTO) {
			carOnly = new LinearLayout(palette);
			carOnly.setOrientation(LinearLayout.VERTICAL);
			section(carOnly, R.string.layout_section_car_only);
			addChoiceRow(carOnly, R.string.tap_action, R.string.tap_action_sub,
					new int[]{R.string.tap_action_play, R.string.tap_action_menu},
					() -> p.getIntPref(MainActivityPrefs.TAP_ACTION_AA),
					v -> p.applyIntPref(MainActivityPrefs.TAP_ACTION_AA, v));
			addSwitchRow(carOnly, R.string.use_dpad_cursor, 0,
					() -> p.getBooleanPref(MainActivityPrefs.USE_DPAD_CURSOR),
					on -> p.applyBooleanPref(MainActivityPrefs.USE_DPAD_CURSOR, on), null);
			box.addView(carOnly, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			showCarOnly();
		}
		for (Runnable r : syncers) r.run();
		return box;
	}

	private void showCarOnly() {
		if (carOnly != null) carOnly.setVisibility(car ? View.VISIBLE : View.GONE);
	}

	private void size(LinearLayout box, int title, Pref<DoubleSupplier> phone, Pref<DoubleSupplier> aa) {
		var p = delegate().getPrefs();
		addSliderRow(box, title, 10, 40, 0.05f, () -> p.getFloatPref(pick(phone, aa)),
				v -> p.applyFloatPref(pick(phone, aa), (float) v));
	}

	private void upNextSwitch(LinearLayout box, Pref<BooleanSupplier> pref, int title, int sub) {
		var p = delegate().getPrefs();
		addSwitchRow(box, title, sub, () -> p.getBooleanPref(pref), on -> p.applyBooleanPref(pref, on), null);
	}

	@Override
	public void onDestroyView() {
		carOnly = null;
		super.onDestroyView();
	}
}
