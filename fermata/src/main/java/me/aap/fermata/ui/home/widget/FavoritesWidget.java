package me.aap.fermata.ui.home.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomePlay;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeWidgetType;

/** The first (or the latest added) favorites; a tap plays one. */
final class FavoritesWidget extends LibraryWidget {
	FavoritesWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected int tabId() {
		return R.id.favorites_fragment;
	}

	@Override
	protected int emptyRes() {
		return R.string.home_favorites_empty;
	}

	@Override
	protected List<HomeOption> moreOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.choice("order", ctx.getString(R.string.home_opt_order), 0,
				ctx.getString(R.string.home_order_first), ctx.getString(R.string.home_order_latest)));
		return l;
	}

	@Override
	protected void fetch(MainActivityDelegate a, FetchDone done) {
		a.getLib().getFavorites().getPlayableChildren(false).main().onCompletion((items, err) -> {
			List<Row> rows = new ArrayList<>();
			if (items == null) {
				done.done(rows, 0);
				return;
			}
			List<PlayableItem> l = new ArrayList<>(items);
			if (optInt("order", 0) == 1) Collections.reverse(l);
			int n = Math.min(l.size(), 60);
			for (int i = 0; i < n; i++) {
				PlayableItem pi = l.get(i);
				rows.add(new Row(pi.getName(), null, pi, () -> HomePlay.tapped(a, pi), null, null));
			}
			done.done(rows, items.size());
		});
	}
}
