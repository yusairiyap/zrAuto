package me.aap.fermata.ui.home.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
	protected boolean supportsQueueOnPlay() {
		return true;
	}

	@Override
	protected boolean supportsFullWidth() {
		return true;
	}

	@Override
	protected int tabId() {
		return R.id.favorites_fragment;
	}

	@Override
	protected boolean watchesPlaying() {
		return true;
	}

	@Override
	protected boolean supportsMenu() {
		return true;
	}

	/** A drag handle and a remove button, both off unless switched on. */
	@Override
	protected boolean supportsReorder() {
		return true;
	}

	@Override
	protected boolean handleByDefault() {
		return false;
	}

	@Override
	protected boolean supportsRemove() {
		return true;
	}

	@Override
	protected boolean removeByDefault() {
		return false;
	}

	/** Positions as the favorites are listed, which is the library's order or its reverse ("Latest first"). */
	private boolean rowsReversed;
	private int libraryCount;

	/** The place in the library of the row at {@code pos}. */
	private int libraryIndex(int pos) {
		return rowsReversed ? libraryCount - 1 - pos : pos;
	}

	@Override
	protected void onRemoved(int position, Row row) {
		if (row.art() instanceof PlayableItem p) act().getLib().getFavorites().removeItem(p);
	}

	@Override
	protected void onReordered(int from, int to) {
		act().getLib().getFavorites().moveItem(libraryIndex(from), libraryIndex(to));
	}

	@Override
	protected boolean marksPlayingOnTop() {
		return true;
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
			rowsReversed = optInt("order", 0) == 1;
			libraryCount = items.size();
			if (rowsReversed) Collections.reverse(l);
			int n = Math.min(l.size(), 300);
			Set<String> playing = playingIds(a);
			int at = -1;
			for (int i = 0; i < n; i++) {
				PlayableItem pi = l.get(i);
				boolean now = (at < 0) && playing.contains(pi.getOrigId());
				if (now) at = i;
				rows.add(songRow(pi, () -> {
					HomePlay.tapped(a, pi);
					queueOnPlay();
				}, null, now));
			}
			focusRow = at;
			done.done(rows, items.size());
		});
	}
}
