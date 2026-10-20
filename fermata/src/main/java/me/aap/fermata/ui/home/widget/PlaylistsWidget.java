package me.aap.fermata.ui.home.widget;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.utils.async.FutureSupplier;

/** The first playlists with their sizes; a tap opens one, its button plays it. */
final class PlaylistsWidget extends LibraryWidget {
	PlaylistsWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected int tabId() {
		return R.id.playlists_fragment;
	}

	@Override
	protected int emptyRes() {
		return R.string.home_playlists_empty;
	}

	@Override
	protected void fetch(MainActivityDelegate a, FetchDone done) {
		FutureSupplier<List<Playlist>> f = a.getLib().getPlaylists().getChildren();
		f.main().onCompletion((lists, err) -> {
			List<Row> rows = new ArrayList<>();
			if (lists == null) {
				done.done(rows, 0);
				return;
			}
			int n = Math.min(lists.size(), 12);
			for (int i = 0; i < n; i++) {
				Playlist pl = lists.get(i);
				rows.add(new Row(pl.getName(), null, pl, () -> {
					open(R.id.playlists_fragment);
					a.goToItem(pl);
				}, () -> pl.getFirstPlayable().main().onSuccess(first -> {
					if (first != null) a.getMediaServiceBinder().playItem(first);
				}), sub -> pl.getUnsortedChildren().main().onSuccess(c -> {
					if (c != null) sub.setText(ctx.getString(R.string.home_tracks, c.size()));
				})));
			}
			done.done(rows, lists.size());
		});
	}
}
