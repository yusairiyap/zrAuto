package me.aap.fermata.ui.home.widget;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.ActionFabMediator;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.view.AllInOneMenu;

/**
 * The All in One card's actions, set out on Home as round icons: whatever Settings > Interface >
 * All in One holds (mute, sleep timer, effects, favorites, ...), one tap each, no card to open first.
 */
final class AllInOneWidget extends TileWidget {
	AllInOneWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected int tabId() {
		return R.id.all_in_one_fragment;
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.choice("labels", ctx.getString(R.string.home_opt_labels), 0,
				ctx.getString(R.string.home_labels_auto), ctx.getString(R.string.home_labels_show),
				ctx.getString(R.string.home_labels_hide)));
		l.add(HomeOption.sw("colour", ctx.getString(R.string.home_opt_colourful), true));
		return l;
	}

	@Override
	protected boolean showLabels() {
		int mode = optInt("labels", 0);
		return (mode == 1) || ((mode == 0) && super.showLabels());
	}

	@Override
	protected void fill() {
		MainActivityDelegate a = host.activity();
		boolean colour = optBool("colour", true);
		int i = 0;
		for (Action action : AllInOneMenu.getItems(a)) {
			int tint = colour ? hue(i++) : color(ROLE_ICON);
			flow.addView(tile(ActionFabMediator.iconFor(a, action),
					ctx.getString(ActionFabMediator.labelFor(a, action)), tint, () -> {
						host.run(action);
						// Some flip (mute, favorite): the icon follows once it has happened.
						postDelayed(this::rebuild, 350);
					}));
		}
	}
}
