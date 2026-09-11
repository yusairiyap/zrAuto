package me.aap.fermata.ui.fragment;

import static android.view.View.FOCUS_DOWN;
import static android.view.View.FOCUS_LEFT;
import static android.view.View.FOCUS_RIGHT;
import static android.view.View.FOCUS_UP;
import static me.aap.fermata.BuildConfig.VERSION_CODE;
import static me.aap.fermata.BuildConfig.VERSION_NAME;
import static me.aap.utils.collection.CollectionUtils.newLinkedHashSet;
import static me.aap.utils.ui.UiUtils.isVisible;
import static me.aap.utils.ui.UiUtils.showInfo;
import static me.aap.utils.ui.view.NavBarItem.create;
import static me.aap.utils.ui.view.NavBarView.POSITION_LEFT;
import static me.aap.utils.ui.view.NavBarView.POSITION_RIGHT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.widget.TextViewCompat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.BodyLayout;
import me.aap.fermata.ui.view.ControlPanelView;
import me.aap.fermata.ui.view.MediaItemListView;
import me.aap.fermata.util.Utils;
import me.aap.utils.collection.CollectionUtils;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.fragment.GenericFragment;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.ui.menu.OverlayMenuItemView;
import me.aap.utils.ui.view.NavBarItem;
import me.aap.utils.ui.view.NavBarView;
import me.aap.utils.ui.view.NavButtonView;
import me.aap.utils.ui.view.PrefNavBarMediator;
import me.aap.utils.ui.view.ScalableTextView;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
public class NavBarMediator extends PrefNavBarMediator
		implements AddonManager.Listener, OverlayMenu.SelectionHandler {
	private static final String UPSTREAM_FERMATA_VERSION = "1.0.0 (268)";
	private static final Pref<Supplier<String[]>> PREF_B =
			Pref.sa("NAV_BAR_ITEMS_B", (String[]) null);
	private static final Pref<Supplier<String[]>> PREF_L =
			Pref.sa("NAV_BAR_ITEMS_L", (String[]) null);
	private static final Pref<Supplier<String[]>> PREF_R =
			Pref.sa("NAV_BAR_ITEMS_R", (String[]) null);

	@Override
	protected Collection<NavBarItem> getItems(NavBarView nb) {
		int max = nb.suggestItemCount() - 1;
		Collection<String> names = getLayout(nb);
		List<NavBarItem> items = new ArrayList<>(names.size());
		AddonManager amgr = getAddonManager();
		Context ctx = nb.getContext();

		for (String name : names) {
			switch (name) {
				case "folders":
					items.add(
							create(ctx, R.id.folders_fragment, me.aap.utils.R.drawable.folder, R.string.folders,
									items.size() < max));
					continue;
				case "favorites":
					items.add(
							create(ctx, R.id.favorites_fragment, R.drawable.favorite_filled, R.string.favorites,
									items.size() < max));
					continue;
				case "playlists":
					items.add(create(ctx, R.id.playlists_fragment, R.drawable.playlist, R.string.playlists,
							items.size() < max));
					continue;
				case "menu":
					items.add(create(ctx, R.id.menu, me.aap.utils.R.drawable.menu, R.string.menu,
							items.size() < max));
					continue;
			}

			FermataAddon a = amgr.getAddon(name);
			if (a instanceof FermataFragmentAddon) {
				AddonInfo ai = a.getInfo();
				items.add(create(ctx, a.getAddonId(), ai.icon, ai.addonName, items.size() < max));
				continue;
			}
			Log.e("Unknown NavBarItem name: ", name);
		}

		return items;
	}

	@Override
	protected boolean canSwap(NavBarView nb) {
		return true;
	}

	@Override
	protected boolean swap(NavBarView nb, @IdRes int id1, @IdRes int id2) {
		List<String> names = new ArrayList<>(getLayout(nb));
		String name1 = idToName(id1);
		String name2 = idToName(id2);
		int idx1 = names.indexOf(name1);
		int idx2 = names.indexOf(name2);

		if ((idx1 != -1) && (idx2 != -1)) {
			Collections.swap(names, idx1, idx2);
			getPreferenceStore(nb).applyStringArrayPref(getPref(nb), names.toArray(new String[0]));
			return true;
		} else {
			Log.e("Unable to swap ", name1, " and ", name2);
			return false;
		}
	}

	@Override
	protected void contributeItemMenu(NavBarView nb, NavButtonView btn, OverlayMenu.Builder b,
																		 ColorStateList tint) {
		int id = btn.getId();
		if (id == R.id.menu) return;

		String name = idToName(id);
		MainActivityDelegate a = MainActivityDelegate.get(nb.getContext());
		boolean isStart = name.equals(a.getPrefs().getShowAddonOnStartPref());
		int icon = isStart ? R.drawable.bookmark_filled : R.drawable.bookmark;
		int title = isStart ? R.string.remove_open_on_start : R.string.set_open_on_start;
		OverlayMenuItemView item =
				(OverlayMenuItemView) b.addItem(R.id.nav_open_on_start, icon, title);
		item.setHandler(i -> {
			a.getPrefs().setShowAddonOnStartPref(isStart ? null : name);
			return true;
		});
		item.setTextColor(tint);
		TextViewCompat.setCompoundDrawableTintList(item, tint);
	}

	@Override
	public void enable(NavBarView nb, ActivityFragment f) {
		super.enable(nb, f);
		FermataApplication.get().getAddonManager().addBroadcastListener(this);
	}

	@Override
	public void disable(NavBarView nb) {
		super.disable(nb);
		FermataApplication.get().getAddonManager().removeBroadcastListener(this);
	}

	@Override
	public void onAddonChanged(AddonManager mgr, AddonInfo info, boolean installed) {
		NavBarView nb = navBar;
		if (nb != null) reload(nb);
	}

	@Override
	protected PreferenceStore getPreferenceStore(NavBarView nb) {
		return MainActivityDelegate.get(nb.getContext()).getPrefs();
	}

	@Override
	protected Pref<Supplier<String[]>> getPref(NavBarView nb) {
		switch (nb.getPosition()) {
			default:
				return PREF_B;
			case POSITION_LEFT:
				return PREF_L;
			case POSITION_RIGHT:
				return PREF_R;
		}
	}

	@Override
	public void itemSelected(View item, int id, ActivityDelegate a) {
		if (id == R.id.menu) {
			showMenu(MainActivityDelegate.get(item.getContext()));
		} else {
			super.itemSelected(item, id, a);
		}
	}

	@Override
	protected boolean extItemSelected(OverlayMenuItem item) {
		if (item.getItemId() == R.id.menu) {
			NavButtonView.Ext ext = getExtButton();

			if ((ext != null) && !ext.isSelected()) {
				NavBarItem i = item.getData();
				setExtButton(null, i);
			}

			showMenu(MainActivityDelegate.get(item.getContext()));
			return true;
		} else {
			return super.extItemSelected(item);
		}
	}

	@Override
	public void itemReselected(View item, int id, ActivityDelegate a) {
		BodyLayout b = ((MainActivityDelegate) a).getBody();
		if (b.isVideoMode()) b.setMode(BodyLayout.Mode.BOTH);
		else super.itemReselected(item, id, a);
	}

	@Nullable
	@Override
	public View focusSearch(NavBarView nb, View focused, int direction) {
		if (direction == FOCUS_UP) {
			if (!nb.isBottom()) return null;
			Context ctx = nb.getContext();
			ControlPanelView p = MainActivityDelegate.get(ctx).getControlPanel();
			return isVisible(p) ? p.focusSearch() : MediaItemListView.focusSearchLast(ctx, focused);
		} else if (direction == FOCUS_DOWN) {
			if (!nb.isBottom()) return null;
			Context ctx = nb.getContext();
			ToolBarView tb = MainActivityDelegate.get(ctx).getToolBar();
			if (isVisible(tb)) return tb.focusSearch();
		} else if (direction == FOCUS_RIGHT) {
			if (nb.isLeft()) return MediaItemListView.focusSearchActive(nb.getContext(), focused);
		} else if (direction == FOCUS_LEFT) {
			if (nb.isRight()) return MediaItemListView.focusSearchActive(nb.getContext(), focused);
		}

		return null;
	}

	@Override
	public void showMenu(NavBarView nb) {
		showMenu(MainActivityDelegate.get(nb.getContext()));
	}

	public void showMenu(MainActivityDelegate a) {
		OverlayMenu menu = a.findViewById(R.id.nav_menu_view);
		menu.show(b -> {
			b.setSelectionHandler(this);

			if (a.hasCurrent())
				b.addItem(R.id.nav_got_to_current, R.drawable.go_to_current, R.string.got_to_current);

			ActivityFragment f = a.getActiveFragment();
			if (f instanceof MainActivityFragment) ((MainActivityFragment) f).contributeToNavBarMenu(b);

			b.addItem(R.id.nav_about, R.drawable.about, R.string.about);
			b.addItem(R.id.settings_fragment, R.drawable.settings, R.string.settings);
			b.addItem(R.id.nav_exit, R.drawable.exit, a.isCarActivityNotMirror() ? R.string.restart : R.string.exit);
		});
	}

	@Override
	public boolean menuItemSelected(OverlayMenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.nav_got_to_current) {
			MainActivityDelegate.get(item.getContext()).goToCurrent();
			return true;
		} else if (itemId == R.id.nav_about) {
			MainActivityDelegate a = MainActivityDelegate.get(item.getContext());
			if (!(a.showFragment(me.aap.utils.R.id.generic_fragment) instanceof GenericFragment f))
				return false;
			f.setTitle(item.getContext().getString(R.string.about));
			f.setContentProvider(g -> {
				Context ctx = g.getContext();
				LinearLayout container = new LinearLayout(ctx);
				container.setOrientation(LinearLayout.VERTICAL);
				container.setGravity(Gravity.CENTER_HORIZONTAL);

				ImageView icon = new ImageView(ctx);
				icon.setImageResource(R.drawable.launcher);
				int iconSize = UiUtils.toIntPx(ctx, 96);
				LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
				int iconPad = UiUtils.toIntPx(ctx, 16);
				iconParams.setMargins(0, iconPad, 0, iconPad);
				icon.setLayoutParams(iconParams);
				container.addView(icon);

				ScalableTextView v = new ScalableTextView(ctx);
				String url = "https://github.com/yusairiyap/zrAuto";
				String html = ctx.getString(R.string.about_html, VERSION_NAME, VERSION_CODE, url)
						+ ctx.getString(R.string.about_fork_html, UPSTREAM_FERMATA_VERSION);
				int pad = UiUtils.toIntPx(ctx, 10);
				v.setPadding(pad, pad, pad, pad);
				v.setText(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY));
				v.setOnClickListener(t -> openUrl(t.getContext(), url));
				container.addView(v);

				NestedScrollView scroll = new NestedScrollView(ctx);
				scroll.setLayoutParams(new LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
				scroll.addView(container);
				g.addView(scroll);
				// GenericFragment's own root (g) never insets itself against tool_bar/control_panel/
				// nav_bar -- unlike MediaItemListView/the Settings list, which each request this inset
				// from their own constructor, this content is built by the caller instead, so it has to
				// be requested here, on the actual scrollable view, the same way those do.
				a.insetScrollableContent(scroll);
			});
			return true;
		} else if (itemId == R.id.settings_fragment) {
			MainActivityDelegate.get(item.getContext()).showFragment(R.id.settings_fragment);
			return true;
		} else if (itemId == R.id.nav_exit) {
			MainActivityDelegate a = MainActivityDelegate.get(item.getContext());
			a.finish();
			if (a.isCarActivityNotMirror()) {
				a.getHandler().postDelayed(() -> System.exit(0), 500);
			}
			return true;
		}
		return false;
	}

	private static void openUrl(Context ctx, String url) {
		if (!Utils.openUrl(ctx, url)) showInfo(ctx, R.string.use_phone_for_donation);
	}

	private Collection<String> getLayout(NavBarView nb) {
		AddonManager amgr = FermataApplication.get().getAddonManager();
		Set<String> names = newLinkedHashSet(BuildConfig.ADDONS.length + 4);
		String[] pref = getPreferenceStore(nb).getStringArrayPref(getPref(nb));
		CollectionUtils.addAll(names, pref);
		names.add("folders");
		names.add("favorites");
		names.add("playlists");
		for (AddonInfo ai : BuildConfig.ADDONS) {
			FermataAddon a = amgr.getAddon(ai.className);
			if (a instanceof FermataFragmentAddon) names.add(ai.className);
		}
		names.add("menu");
		return names;
	}

	/**
	 * Maps a built-in nav-bar item's stable name (as stored by
	 * {@link MainActivityPrefs#getShowAddonOnStartPref()}) to its fragment id, or {@code 0} if
	 * {@code name} isn't one of the built-in items (e.g. it's an addon class name instead).
	 */
	@IdRes
	public static int nameToFragmentId(String name) {
		switch (name) {
			case "folders":
				return R.id.folders_fragment;
			case "favorites":
				return R.id.favorites_fragment;
			case "playlists":
				return R.id.playlists_fragment;
			default:
				return 0;
		}
	}

	private static String idToName(@IdRes int id) {
		if (id == R.id.folders_fragment) return "folders";
		else if (id == R.id.favorites_fragment) return "favorites";
		else if (id == R.id.playlists_fragment) return "playlists";
		else if (id == R.id.menu) return "menu";

		AddonManager amgr = getAddonManager();
		for (AddonInfo ai : BuildConfig.ADDONS) {
			FermataAddon a = amgr.getAddon(ai.className);
			if ((a instanceof FermataFragmentAddon) && (a.getAddonId() == id)) return ai.className;
		}

		Log.e("Unknown NavBarItem id: ", id);
		return String.valueOf(id);
	}

	private static AddonManager getAddonManager() {
		return FermataApplication.get().getAddonManager();
	}
}
