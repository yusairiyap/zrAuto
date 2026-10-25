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
import me.aap.fermata.ui.home.HomePins;
import me.aap.fermata.ui.home.HomePlay;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.utils.async.FutureSupplier;

/**
 * The first playlists with their sizes; a tap opens one, its button plays it. A playlist holding the song that plays is
 * filled with the accent. Right after the app starts the playlist of the song last played is already open, with that song marked.
 */
final class PlaylistsWidget extends LibraryWidget {
	/**
	 * Kept for the life of the app, by the widget's id: Home makes a card again now and then (its layout read
	 * again), and a new card used to forget the playlist that was open in it and show all of them, with no back
	 * chip. Which playlist is open in each card, and the cards that already opened the last played song's
	 * playlist by themselves (backing out of it sticks).
	 */
	private static final java.util.Map<String, String> OPEN = new java.util.HashMap<>();
	private static final Set<String> OPENED_LAST = new HashSet<>();

	PlaylistsWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected boolean supportsQueueOnPlay() {
		return true;
	}

	@Override
	protected boolean supportsFullWidth() {
		return true;
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
	protected boolean supportsPins() {
		return true;
	}

	@Override
	protected boolean isPinnedRow(Row r) {
		return (r.art() instanceof Playlist pl) && HomePins.isPinned(act().getPrefs(), pl.getId());
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

	/** Ids of the playlists that hold the playing song, as last worked out (the rows are drawn from this at once). */
	private Set<String> holding = new HashSet<>();
	/** What {@link #holding} was worked out for: the playing song(s) and the library's change count. */
	private String holdingKey = "";
	private boolean scanning;

	@Override
	protected void fetch(MainActivityDelegate a, FetchDone done) {
		FutureSupplier<List<Playlist>> f = a.getLib().getPlaylists().getChildren();
		f.main().onCompletion((all, err) -> {
			List<Playlist> lists = (all == null) ? null : pinnedFirst(a, all);
			if (lists == null) {
				focusRow = -1;
				done.done(new ArrayList<>(), 0);
				return;
			}
			// The rows are made and shown at once, with the playlists known to hold the playing song filled; looking through
			// the playlists for it (hundreds of them can mean reading as many lists) goes on behind, and redraws when it finds
			// something different. Waiting for it first left the card empty for as long.
			List<Row> rows = new ArrayList<>();
			int n = Math.min(lists.size(), 100);
			int first = -1;
			for (int i = 0; i < n; i++) {
				Playlist pl = lists.get(i);
				boolean now = holding.contains(pl.getId());
				if (now && (first < 0)) first = i;
				rows.add(new Row(pl.getName(), null, pl, () -> open(pl),
						() -> {
							HomePlay.list(a, pl);
							queueOnPlay();
						}, set -> pl.getUnsortedChildren().main().onSuccess(c -> {
					if (c != null) set.accept(ctx.getString(R.string.home_tracks, c.size()));
				}), now));
			}
			focusRow = first;
			done.done(rows, lists.size());
			if (!reopen(lists)) openLast(lists, first);
			scanHolders(a, lists);
		});
	}

	/** The pinned playlists first (the newest pin on top), the rest in the library's order. */
	private static List<Playlist> pinnedFirst(MainActivityDelegate a, List<Playlist> all) {
		List<String> pins = HomePins.ids(a.getPrefs());
		if (pins.isEmpty()) return all;
		List<Playlist> out = new ArrayList<>(all.size());
		for (String id : pins) {
			for (Playlist pl : all) {
				if (id.equals(pl.getId())) {
					out.add(pl);
					break;
				}
			}
		}
		for (Playlist pl : all) {
			if (!pins.contains(pl.getId())) out.add(pl);
		}
		return out;
	}

	/** Right after the app starts the playlist of the last played song is opened, once per widget. */
	private void openLast(List<Playlist> lists, int first) {
		String key = getItem().id;
		if (OPENED_LAST.contains(key) || (first < 0) || !optBool("openlast", true) || isCompact()) return;
		OPENED_LAST.add(key);
		open(lists.get(first));
	}

	/** Opens a playlist in the card, remembered for a card made again (see {@link #OPEN}). */
	private void open(Playlist pl) {
		OPEN.put(getItem().id, pl.getId());
		drill(pl.getName(), (a2, d2) -> songs(pl, a2, d2));
	}

	/** The card was made again with a playlist open in it before: that playlist again. */
	private boolean reopen(List<Playlist> lists) {
		if (isDrilled()) return false;
		String id = OPEN.get(getItem().id);
		if (id == null) return false;
		for (Playlist pl : lists) {
			if (id.equals(pl.getId())) {
				open(pl);
				return true;
			}
		}
		// Deleted meanwhile.
		OPEN.remove(getItem().id);
		return false;
	}

	@Override
	protected void onBackOut() {
		OPEN.remove(getItem().id);
	}

	/** Looks through the playlists, one after the other, for those holding the playing song, unless that is known already. */
	private void scanHolders(MainActivityDelegate a, List<Playlist> lists) {
		Set<String> ids = playingIds(a);
		List<String> sorted = new ArrayList<>(ids);
		java.util.Collections.sort(sorted);
		String key = sorted + "|" + me.aap.fermata.media.lib.ItemContainer.changeCount();
		if (scanning || key.equals(holdingKey)) return;
		if (ids.isEmpty()) {
			holdingKey = key;
			if (!holding.isEmpty()) {
				holding = new HashSet<>();
				refresh(true);
			}
			return;
		}
		scanning = true;
		holders(lists, 0, ids, new HashSet<>(), found -> {
			scanning = false;
			// Left unfinished (the card went off screen): nothing is concluded, the next read looks again.
			if (found == null) return;
			holdingKey = key;
			if (!found.equals(holding)) {
				holding = found;
				refresh(true);
			}
		});
	}

	/** Reads the playlists in turn (the ones read before come straight from memory): calls back with the ids of those holding the song. */
	private void holders(List<Playlist> lists, int i, Set<String> ids, Set<String> found, Consumer<Set<String>> done) {
		if (!isActive()) {
			done.accept(null);
			return;
		}
		if (i >= Math.min(lists.size(), 100)) {
			done.accept(found);
			return;
		}
		Playlist pl = lists.get(i);
		pl.getUnsortedChildren().main().onCompletion((items, err) -> {
			if (items != null) {
				for (Item it : items) {
					if ((it instanceof PlayableItem p) && ids.contains(p.getOrigId())) {
						found.add(pl.getId());
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
				Runnable play = () -> {
					HomePlay.tapped(a, p);
					queueOnPlay();
				};
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
