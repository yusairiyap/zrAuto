package me.aap.fermata.ui.home.widget;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.ui.fragment.NavBarMediator;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.utils.log.Log;

/**
 * Every page the navigation bar offers (folders, favorites, playlists, the addons' tabs, in the
 * order and with the hiding chosen in Settings) as round icons, plus Settings. Handy where the bar
 * itself is hidden, or just to jump from Home.
 */
final class PagesWidget extends TileWidget {
	PagesWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected int tabId() {
		return 0;
	}

	@Override
	protected int labelMode() {
		return optInt("labels", 0);
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.choice("labels", ctx.getString(R.string.home_opt_labels), 0,
				ctx.getString(R.string.home_labels_auto), ctx.getString(R.string.home_labels_show),
				ctx.getString(R.string.home_labels_hide)));
		l.add(HomeOption.sw("colour", ctx.getString(R.string.home_opt_colourful), true));
		l.add(pickOption());
		return l;
	}

	private static final String SETTINGS_KEY = "settings";

	/** Every page of the bar (the hidden ones too) and Settings. */
	@Override
	protected List<Pick> candidates() {
		List<Pick> l = new ArrayList<>();
		for (String name : NavBarMediator.getTabs(host.activity().getPrefs())) {
			if ("home".equals(name) || (fragmentId(name) == 0)) continue;
			l.add(new Pick(name, NavBarMediator.getTabIcon(name), NavBarMediator.getTabTitle(ctx, name)));
		}
		l.add(new Pick(SETTINGS_KEY, R.drawable.settings, ctx.getString(R.string.settings)));
		return l;
	}

	/** Until something is picked: the pages the bar shows, and Settings (the old switches still count). */
	@Override
	protected boolean defaultPicked(String key) {
		if (SETTINGS_KEY.equals(key)) return optBool("settings", true);
		return optBool("hidden", false)
				|| !NavBarMediator.getHiddenTabs(host.activity().getPrefs()).contains(key);
	}

	@Override
	protected int pickHintRes() {
		return R.string.home_pick_items_hint_pages;
	}

	@Override
	protected void fill() {
		var prefs = host.activity().getPrefs();
		List<String> tabs = NavBarMediator.getTabs(prefs);
		boolean colour = optBool("colour", true);
		int i = 0;
		for (String name : tabs) {
			if ("home".equals(name) || !isPicked(name)) continue;
			int id = fragmentId(name);
			if (id == 0) continue;
			int tint = colour ? hue(i) : color(ROLE_ICON);
			flow.addView(tile(NavBarMediator.getTabIcon(name), NavBarMediator.getTabTitle(ctx, name), tint,
					() -> open(id)));
			i++;
		}
		if (isPicked(SETTINGS_KEY)) {
			flow.addView(tile(R.drawable.settings, ctx.getString(R.string.settings),
					colour ? hue(i) : color(ROLE_ICON), () -> open(R.id.settings_fragment)));
		}
	}

	private static int fragmentId(String name) {
		int id = NavBarMediator.nameToFragmentId(name);
		if (id != 0) return id;
		try {
			AddonManager m = AddonManager.get();
			FermataAddon a = (m == null) ? null : m.getAddon(name);
			if (a instanceof FermataFragmentAddon f) return f.getFragmentId();
		} catch (RuntimeException ex) {
			Log.e(ex, "Home: no page for ", name);
		}
		return 0;
	}
}
