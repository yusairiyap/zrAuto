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
		l.add(pickOption());
		return l;
	}

	@Override
	protected List<Pick> candidates() {
		MainActivityDelegate a = host.activity();
		List<Pick> l = new ArrayList<>();
		for (Action action : actions(a)) {
			l.add(new Pick(action.name(), ActionFabMediator.iconFor(a, action),
					ctx.getString(ActionFabMediator.labelFor(a, action))));
		}
		return l;
	}

	/** The card's own actions, then the All in One chip itself (it opens the card, so it is off until picked). */
	private static List<Action> actions(MainActivityDelegate a) {
		List<Action> l = new ArrayList<>(AllInOneMenu.getItems(a));
		l.add(Action.ALL_IN_ONE);
		return l;
	}

	@Override
	protected boolean defaultPicked(String key) {
		return !Action.ALL_IN_ONE.name().equals(key);
	}

	@Override
	protected int pickHintRes() {
		return R.string.home_pick_items_hint_aio;
	}

	@Override
	protected int labelMode() {
		return optInt("labels", 0);
	}

	@Override
	protected void fill() {
		MainActivityDelegate a = host.activity();
		boolean colour = optBool("colour", true);
		int i = 0;
		for (Action action : actions(a)) {
			if (!isPicked(action.name())) continue;
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
