package me.aap.fermata.spotify;

import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.failed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.VideoTitleCache;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.media.lib.MediaLib.Playlists;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.utils.async.Async;
import me.aap.utils.async.FutureSupplier;

/**
 * Writes the resolved import into the local media library: one local playlist per Spotify
 * playlist, holding {@code youtube:<videoId>} entries (the same stable items "Add to playlist"
 * creates for a YouTube video, so the watch URL and thumbnail are derived from the id), with each
 * video's title pre-seeded into the YouTube addon's title cache.
 * <p>
 * Runs on the main thread, and only after every network lookup has finished -- so cancelling the
 * import at any point before this leaves the library untouched.
 */
public final class SpotifyPlaylistWriter {

	private SpotifyPlaylistWriter() {
	}

	public static final class Entry {
		final String name;
		final List<Video> videos;

		public Entry(String name, List<Video> videos) {
			this.name = name;
			this.videos = videos;
		}
	}

	/** @return the number of videos written. */
	public static FutureSupplier<Integer> write(MediaLib lib, List<Entry> entries) {
		Map<String, String> titles = new LinkedHashMap<>();
		for (Entry e : entries) {
			for (Video v : e.videos) titles.put(v.videoId, v.title);
		}

		// youtube:<id> items are resolved by the YouTube addon, so it must be installed first.
		return AddonManager.get().getOrInstallAddon(VideoTitleCache.YOUTUBE_ADDON_CLASS).main()
				.then(addon -> {
					if (addon instanceof VideoTitleCache) ((VideoTitleCache) addon).cacheVideoTitles(titles);
					int[] count = {0};
					return Async.forEach(e -> writeEntry(lib, e).main().onSuccess(n -> count[0] += n),
							entries).map(v -> count[0]);
				});
	}

	private static FutureSupplier<Integer> writeEntry(MediaLib lib, Entry e) {
		if (e.videos.isEmpty()) return completed(0);

		return findOrCreate(lib.getPlaylists(), sanitizeName(e.name)).main().then(pl -> {
			List<PlayableItem> items = new ArrayList<>(e.videos.size());
			return Async.forEach(v -> lib.getItem("youtube:" + v.videoId).main().onSuccess(i -> {
						if (i instanceof PlayableItem) items.add((PlayableItem) i);
					}), e.videos)
					.then(v -> {
						if (items.isEmpty()) {
							return failed(new IllegalStateException("YouTube videos could not be resolved"));
						}
						return pl.addItems(items);
					}).map(v -> items.size());
		});
	}

	/**
	 * Re-importing the same Spotify playlist adds into the existing local playlist of that name
	 * instead of failing on the duplicate name; videos already in it are skipped.
	 */
	private static FutureSupplier<Playlist> findOrCreate(Playlists playlists, String name) {
		return playlists.getUnsortedChildren().main().then(list -> {
			for (Item i : list) {
				if ((i instanceof Playlist) && name.equals(((Playlist) i).getName())) {
					return completed((Playlist) i);
				}
			}
			return playlists.addItem(name);
		});
	}

	/** Local playlist names must be non-empty and can't contain '/'. */
	static String sanitizeName(String name) {
		String n = name.replace('/', '∕').trim();
		return n.isEmpty() ? "Spotify" : n;
	}
}
