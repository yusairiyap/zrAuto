package me.aap.fermata.ui.home.widget;

import android.text.format.DateUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.media.pref.PlayableItemPrefs;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.utils.async.FutureSupplier;

/**
 * The bookmarks set on tracks and videos (at a time in them): of what plays now first, then of the
 * favorites and the playlists. A tap plays from the bookmark.
 */
final class BookmarksWidget extends LibraryWidget {
	private static final int SCAN_MAX = 300;

	BookmarksWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected int tabId() {
		return R.id.favorites_fragment;
	}

	@Override
	protected int emptyRes() {
		return R.string.home_bookmarks_empty;
	}

	@Override
	protected void fetch(MainActivityDelegate a, FetchDone done) {
		MediaLib lib = a.getLib();
		List<PlayableItem> pool = new ArrayList<>();
		PlayableItem now = me.aap.fermata.action.Action.getFavoritableItem(a);
		if (now != null) pool.add(now);
		lib.getFavorites().getPlayableChildren(false).main().onCompletion((fav, e1) -> {
			if (fav != null) pool.addAll(fav);
			FutureSupplier<List<Playlist>> pls = lib.getPlaylists().getChildren();
			pls.main().onCompletion((lists, e2) -> {
				if ((lists == null) || lists.isEmpty()) {
					finish(a, pool, done);
					return;
				}
				int[] left = {lists.size()};
				for (Playlist p : lists) {
					p.getPlayableChildren(false).main().onCompletion((items, e3) -> {
						if (items != null) pool.addAll(items);
						if (--left[0] == 0) finish(a, pool, done);
					});
				}
			});
		});
	}

	private void finish(MainActivityDelegate a, List<PlayableItem> pool, FetchDone done) {
		List<Row> rows = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int total = 0;
		int scanned = 0;
		for (PlayableItem pi : pool) {
			if (++scanned > SCAN_MAX) break;
			if (!seen.add(pi.getId())) continue;
			String[] marks;
			try {
				marks = pi.getPrefs().getBookmarks();
			} catch (RuntimeException ex) {
				continue;
			}
			for (String bm : marks) {
				total++;
				if (rows.size() >= 60) continue;
				String name = PlayableItemPrefs.bookmarkName(bm);
				int time = PlayableItemPrefs.bookmarkTime(bm);
				CharSequence sub = DateUtils.formatElapsedTime(time) + " · " + pi.getName();
				rows.add(new Row(name, sub, pi, () -> a.getMediaServiceBinder().playItem(pi, time * 1000L),
						null, null));
			}
		}
		done.done(rows, total);
	}
}
