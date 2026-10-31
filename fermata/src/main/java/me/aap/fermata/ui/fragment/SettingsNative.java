package me.aap.fermata.ui.fragment;

import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * What the code-built settings pages hold, for the settings search (the old list pages are searched through their own tree).
 * One line per row: its title and line, and the page it is on.
 */
final class SettingsNative {
	private SettingsNative() {
	}

	private record Row(@StringRes int title, @StringRes int sub, @DrawableRes int icon, @StringRes int path, int page) {
	}

	private static final int THEME = R.id.theme_settings_fragment;
	private static final int LAYOUT = R.id.layout_settings_fragment;
	private static final int NETWORK = R.id.network_settings_fragment;
	private static final int PRIVACY = R.id.privacy_settings_fragment;

	private static final Row[] ROWS = {
			new Row(R.string.theme, R.string.theme_settings_sub, R.drawable.view_grid, R.string.appearance_prefs, THEME),
			new Row(R.string.art_accent, R.string.art_accent_sub, R.drawable.view_grid, R.string.theme, THEME),
			new Row(R.string.network_offline_mode, R.string.network_offline_mode_sub, R.drawable.wifi, R.string.network_settings, NETWORK),
			new Row(R.string.offline_on_stall, R.string.offline_on_stall_sub, R.drawable.wifi, R.string.network_settings, NETWORK),
			new Row(R.string.offline_stall_seconds, R.string.offline_stall_seconds_sub, R.drawable.wifi, R.string.network_settings, NETWORK),
			new Row(R.string.network_data_usage, R.string.network_data_usage_sub, R.drawable.wifi, R.string.network_settings, NETWORK),
			new Row(R.string.nav_tabs_prefs, R.string.prefs_sub_nav_tabs, R.drawable.view_grid, R.string.layout_settings, LAYOUT),
			new Row(R.string.secondary_fab_prefs, R.string.prefs_sub_fab, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.all_in_one, R.string.all_in_one_sub, R.drawable.view_grid, R.string.layout_settings, LAYOUT),
			new Row(R.string.info_overlay_prefs, R.string.prefs_sub_info_overlay, R.drawable.about, R.string.layout_settings, LAYOUT),
			new Row(R.string.hide_bars, R.string.hide_bars_sub, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.scroll_hide_bars, R.string.scroll_hide_bars_sub, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.fullscreen_mode, 0, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.show_pg_up_down, 0, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.tool_bar_size, 0, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.control_panel_size, 0, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.text_icon_size, 0, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.icon_size, 0, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.nav_bar_pos, R.string.nav_bar_pos_hint, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.nav_bar_size, 0, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.show_track_icons, 0, R.drawable.fab, R.string.layout_settings, LAYOUT),
			new Row(R.string.up_next_prefs, 0, R.drawable.up_next, R.string.layout_settings, LAYOUT),
			new Row(R.string.search_history, R.string.search_history_sub, R.drawable.up_next, R.string.layout_settings, LAYOUT),
			new Row(R.string.yt_search_library_pref, R.string.yt_search_library_pref_sub, R.drawable.up_next, R.string.layout_settings, LAYOUT),
			new Row(R.string.yt_panel_car_keyboard, R.string.yt_panel_car_keyboard_sub, R.drawable.up_next, R.string.layout_settings, LAYOUT),
			new Row(R.string.yt_search_tap_queues, R.string.yt_search_tap_queues_sub, R.drawable.up_next, R.string.layout_settings, LAYOUT),
			new Row(R.string.yt_separate_panels, R.string.yt_separate_panels_sub, R.drawable.up_next, R.string.layout_settings, LAYOUT),
			new Row(R.string.up_next_max, R.string.up_next_max_sub, R.drawable.up_next, R.string.layout_settings, LAYOUT),
			new Row(R.string.up_next_list_preview, R.string.up_next_list_preview_sub, R.drawable.up_next, R.string.layout_settings, LAYOUT),
			new Row(R.string.clear_browsing_data, R.string.clear_browsing_data_sub, R.drawable.private_mode, R.string.private_mode_prefs, PRIVACY),
			new Row(R.string.private_mode_clear_now, R.string.private_mode_clear_now_sub, R.drawable.private_mode, R.string.private_mode_prefs, PRIVACY),
			new Row(R.string.private_mode, R.string.private_mode_sub, R.drawable.private_mode, R.string.private_mode_prefs, PRIVACY),
			new Row(R.string.private_mode_always, R.string.private_mode_always_sub, R.drawable.private_mode, R.string.private_mode_prefs, PRIVACY),
			new Row(R.string.private_mode_block_trackers, R.string.private_mode_block_trackers_sub, R.drawable.private_mode, R.string.private_mode_prefs, PRIVACY),
			new Row(R.string.private_mode_block_3rd_party_cookies, R.string.private_mode_block_3rd_party_cookies_sub, R.drawable.private_mode, R.string.private_mode_prefs, PRIVACY),
	};

	static void addAll(MainActivityDelegate a, List<SettingsFragment.Hit> out) {
		Context ctx = a.getContext();
		for (Row r : ROWS) {
			String title = ctx.getString(r.title());
			String sub = (r.sub() == 0) ? null : ctx.getString(r.sub());
			int page = r.page();
			out.add(new SettingsFragment.Hit(ctx.getString(r.path()), title, sub, r.icon(), () ->
					a.showFragment(page, (page == LAYOUT) ? (Object) a.isCarActivity() : null)));
		}
	}
}
