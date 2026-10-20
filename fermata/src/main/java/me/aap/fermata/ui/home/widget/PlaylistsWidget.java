package me.aap.fermata.ui.home.widget;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
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
			int n = Math.min(lists.size(), 60);
			for (int i = 0; i < n; i++) {
				Playlist pl = lists.get(i);
				rows.add(new Row(pl.getName(), null, pl, () -> drill(pl.getName(), (a2, d2) -> songs(pl, a2, d2)),
						() -> pl.getFirstPlayable().main().onSuccess(first -> {
					if (first != null) a.getMediaServiceBinder().playItem(first);
				}), sub -> pl.getUnsortedChildren().main().onSuccess(c -> {
					if (c != null) sub.setText(ctx.getString(R.string.home_tracks, c.size()));
				})));
			}
			done.done(rows, lists.size());
		});
	}

	/** The songs of an opened playlist: a tap plays one right here. */
	private void songs(Playlist pl, MainActivityDelegate a, FetchDone done) {
		pl.getUnsortedChildren().main().onCompletion((items, err) -> {
			List<Row> rows = new ArrayList<>();
			if (items == null) {
				done.done(rows, 0);
				return;
			}
			int n = Math.min(items.size(), 120);
			for (int i = 0; i < n; i++) {
				if (!(items.get(i) instanceof PlayableItem p)) continue;
				String artist = (p instanceof MusicTrackItem t) ? t.getArtistName() : null;
				Runnable play = () -> a.getMediaServiceBinder().playItem(p);
				rows.add(new Row(p.getName(), artist, p, play, play, null));
			}
			done.done(rows, items.size());
		});
	}
}
