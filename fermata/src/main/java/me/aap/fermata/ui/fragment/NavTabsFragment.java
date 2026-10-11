package me.aap.fermata.ui.fragment;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;

/**
 * Settings &gt; Interface &gt; Navigation tabs: the nav bar's tabs in their order, to drag into a
 * new one. Each has a switch (shown in the bar or hidden) and a menu, to hide it or make it the tab the
 * app opens on.
 */
public class NavTabsFragment extends ReorderListFragment {

	@Override
	public int getFragmentId() {
		return R.id.nav_tabs_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.nav_tabs_prefs);
	}

	private MainActivityPrefs prefs() {
		return delegate().getPrefs();
	}

	@Override
	protected List<Row> loadRows() {
		Context ctx = delegate().getContext();
		MainActivityPrefs prefs = prefs();
		Set<String> hidden = NavBarMediator.getHiddenTabs(prefs);
		String start = prefs.getShowAddonOnStartPref();
		List<Row> rows = new ArrayList<>();
		for (String name : NavBarMediator.getTabs(prefs)) {
			boolean shown = !hidden.contains(name);
			String state = ctx.getString(shown ? R.string.nav_tab_shown : R.string.nav_tab_hidden);
			if (name.equals(start)) state += " · " + ctx.getString(R.string.nav_tab_opens_on_start);
			rows.add(new Row(name, NavBarMediator.getTabIcon(name), NavBarMediator.getTabTitle(ctx, name),
					state, shown));
		}
		return rows;
	}

	@Override
	protected void saveOrder(List<String> keys) {
		NavBarMediator.setTabOrder(prefs(), keys);
	}

	@Override
	protected void setChecked(String key, boolean checked) {
		NavBarMediator.setTabHidden(prefs(), key, !checked);
	}

	@Override
	protected void showRowMenu(Row row) {
		MainActivityDelegate a = delegate();
		MainActivityPrefs prefs = prefs();
		String name = row.key;
		boolean hidden = !row.checked;
		boolean start = name.equals(prefs.getShowAddonOnStartPref());
		a.getContextMenu().show(b -> {
			b.setTitle(row.title.toString());
			b.addItem(R.id.nav_tab_visibility,
					hidden ? me.aap.utils.R.drawable.check_box_blank : me.aap.utils.R.drawable.check_box,
					hidden ? R.string.nav_tab_show : R.string.nav_tab_hide).setHandler(i -> {
				NavBarMediator.setTabHidden(prefs, name, !hidden);
				reload();
				return true;
			});
			b.addItem(R.id.nav_open_on_start, start ? R.drawable.bookmark_filled : R.drawable.bookmark,
					start ? R.string.remove_open_on_start : R.string.set_open_on_start).setHandler(i -> {
				prefs.setShowAddonOnStartPref(start ? null : name);
				reload();
				return true;
			});
		});
	}

	@Override
	protected void onReset() {
		NavBarMediator.resetTabs(prefs());
	}

	@Override
	protected int resetTitle() {
		return R.string.nav_tabs_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.nav_tabs_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.nav_tabs_hint;
	}

	@Override
	protected void goBack() {
		MainActivityDelegate a = delegate();
		a.showFragment(R.id.settings_fragment, a.isCarActivity() ? SettingsFragment.SHOW_AA_INTERFACE :
				SettingsFragment.SHOW_INTERFACE);
	}
}
