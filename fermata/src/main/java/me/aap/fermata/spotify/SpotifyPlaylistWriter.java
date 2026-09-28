package me.aap.fermata.spotify;

import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.failed;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.VideoTitleCache;
import me.aap.fermata.addon.VideoTitleCache.VideoInfo;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.media.lib.MediaLib.Playlists;
import me.aap.fermata.spotify.SpotifyImportModel.Track;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.utils.async.Async;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.Function;

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
		/** videoId to the Spotify track it was matched for, if known. */
		final Map<String, Track> tracks;
		/** The Spotify playlist it came from, remembered for a later sync; null if unknown. */
		@Nullable
		final String ref;

		public Entry(String name, List<Video> videos) {
			this(name, videos, Collections.emptyMap(), null);
		}

		public Entry(String name, List<Video> videos, Map<String, Track> tracks,
								 @Nullable String ref) {
			this.name = name;
			this.videos = videos;
			this.tracks = tracks;
			this.ref = ref;
		}
	}

	/**
	 * What a playlist entry shows besides its title: the channel as the artist (the track's
	 * Spotify artists if the channel isn't known), the Spotify album and the video's duration.
	 */
	public static VideoInfo videoInfo(Video v, @Nullable Track t) {
		String artist = v.channel;
		if (((artist == null) || artist.isEmpty()) && (t != null)) artist = t.artists;
		long dur = (v.durationMs > 0) ? v.durationMs : ((t != null) ? t.durationMs : -1);
		return new VideoInfo(v.title, artist, (t != null) ? t.album : null, dur);
	}

	/** @return the number of videos written. */
	public static FutureSupplier<Integer> write(MediaLib lib, List<Entry> entries) {
		return write(lib, entries, e -> findOrCreate(lib.getPlaylists(), sanitizeName(e.name)));
	}

	/**
	 * Adds {@code e}'s videos into {@code pl} -- at the top, as a block in their Spotify order
	 * (playlists put new entries first). Used by {@link SpotifyPlaylistSync}.
	 */
	public static FutureSupplier<Integer> writeInto(MediaLib lib, Playlist pl, Entry e) {
		return write(lib, Collections.singletonList(e), x -> completed(pl));
	}

	private static FutureSupplier<Integer> write(MediaLib lib, List<Entry> entries,
																							 Function<Entry, FutureSupplier<Playlist>> target) {
		Map<String, String> titles = new LinkedHashMap<>();
		Map<String, VideoInfo> info = new LinkedHashMap<>();
		for (Entry e : entries) {
			for (Video v : e.videos) {
				titles.put(v.videoId, v.title);
				info.put(v.videoId, videoInfo(v, e.tracks.get(v.videoId)));
			}
		}

		// youtube:<id> items are resolved by the YouTube addon, so it must be installed first.
		return AddonManager.get().getOrInstallAddon(VideoTitleCache.YOUTUBE_ADDON_CLASS).main()
				.then(addon -> {
					if (addon instanceof VideoTitleCache c) {
						c.cacheVideoTitles(titles);
						c.cacheVideoInfo(info);
					}
					int[] count = {0};
					return Async.forEach(e -> writeEntry(lib, e, target).main().onSuccess(n -> {
						count[0] += n;
						SpotifyPlaylistSync.remember(e);
					}), entries).map(v -> count[0]);
				});
	}

	private static FutureSupplier<Integer> writeEntry(MediaLib lib, Entry e,
																										Function<Entry, FutureSupplier<Playlist>> target) {
		if (e.videos.isEmpty()) return completed(0);

		return target.apply(e).main().then(pl -> {
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
