package me.aap.fermata.ui.home.widget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomePlay;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.utils.async.FutureSupplier;

/**
 * The first playlists with their sizes; a tap opens one, its button plays it. A playlist holding the song that plays is
 * filled with the accent. Right after the app starts the playlist of the song last played is already open, with that song marked.
 */
final class PlaylistsWidget extends LibraryWidget {
	/** The playlist of the last played song is opened once per widget: backing out of it sticks. */
	private boolean openedLast;

	PlaylistsWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected boolean supportsMenu() {
		return true;
	}

	@Override
	protected List<HomeOption> moreOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.sw("openlast", ctx.getString(R.string.home_opt_open_playlist), true));
		return l;
	}

	@Override
	protected int tabId() {
		return R.id.playlists_fragment;
	}

	@Override
	protected boolean watchesPlaying() {
		return true;
	}

	@Override
	protected int emptyRes() {
		return R.string.home_playlists_empty;
	}

	@Override
	protected boolean marksPlayingOnTop() {
		return true;
	}

	@Override
	protected void fetch(MainActivityDelegate a, FetchDone done) {
		FutureSupplier<List<Playlist>> f = a.getLib().getPlaylists().getChildren();
		f.main().onCompletion((lists, err) -> {
			if (lists == null) {
				focusRow = -1;
				done.done(new ArrayList<>(), 0);
				return;
			}
			Set<String> ids = playingIds(a);
			// The playlists holding the playing song are filled with the accent, like the playing row of a list of songs.
			holders(lists, 0, ids, new HashSet<>(), holding -> {
				List<Row> rows = new ArrayList<>();
				int n = Math.min(lists.size(), 100);
				int first = -1;
				for (int i = 0; i < n; i++) {
					Playlist pl = lists.get(i);
					boolean now = holding.contains(i);
					if (now && (first < 0)) first = i;
					rows.add(new Row(pl.getName(), null, pl, () -> drill(pl.getName(), (a2, d2) -> songs(pl, a2, d2)),
							() -> HomePlay.list(a, pl), set -> pl.getUnsortedChildren().main().onSuccess(c -> {
						if (c != null) set.accept(ctx.getString(R.string.home_tracks, c.size()));
					}), now));
				}
				focusRow = first;
				done.done(rows, lists.size());
				// Right after the app starts the playlist of the last played song is opened, once per widget.
				if (!openedLast && optBool("openlast", true) && !isCompact() && (first >= 0)) {
					openedLast = true;
					Playlist pl = lists.get(first);
					drill(pl.getName(), (a2, d2) -> songs(pl, a2, d2));
				}
			});
		});
	}

	/** Looks through the playlists, one after the other, for those holding the playing song: calls back with their positions. */
	private void holders(List<Playlist> lists, int i, Set<String> ids, Set<Integer> found, Consumer<Set<Integer>> done) {
		if (ids.isEmpty() || (i >= Math.min(lists.size(), 100))) {
			done.accept(found);
			return;
		}
		lists.get(i).getUnsortedChildren().main().onCompletion((items, err) -> {
			if (items != null) {
				for (Item it : items) {
					if ((it instanceof PlayableItem p) && ids.contains(p.getOrigId())) {
						found.add(i);
						break;
					}
				}
			}
			holders(lists, i + 1, ids, found, done);
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
			int n = Math.min(items.size(), 300);
			Set<String> playing = playingIds(a);
			int at = -1;
			for (int i = 0; i < n; i++) {
				if (!(items.get(i) instanceof PlayableItem p)) continue;
				String artist = (p instanceof MusicTrackItem t) ? t.getArtistName() : null;
				Runnable play = () -> HomePlay.tapped(a, p);
				boolean now = (at < 0) && playing.contains(p.getOrigId());
				if (now) at = rows.size();
				// A tap on the row plays it already: no play button inside an opened playlist (the list's own rows keep theirs).
				rows.add(new Row(p.getName(), artist, p, play, null, null, now));
			}
			focusRow = at;
			done.done(rows, items.size());
		});
	}
}
