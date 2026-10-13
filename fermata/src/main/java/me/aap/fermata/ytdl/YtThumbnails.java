package me.aap.fermata.ytdl;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.VideoTitleCache;
import me.aap.fermata.addon.VideoTitleCache.VideoInfo;
import me.aap.fermata.media.engine.BitmapCache;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.fermata.spotify.YoutubeSearch;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MediaLibFragment;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.log.Log;
import me.aap.utils.ui.menu.OverlayMenu;

/**
 * "Refresh thumbnail" for YouTube videos in lists: drops every cached copy of their thumbnails so
 * they are downloaded afresh, and looks up the channel and length of those that don't have them
 * yet (as the import from Spotify and "search alternatives" record them), so the rows show the
 * artist and duration too. One place for a single item, a selection and a whole list.
 */
public final class YtThumbnails {
	private static final long LOOKUP_DELAY_MS = 400;
	private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "yt-thumbnails");
		t.setDaemon(true);
		return t;
	});

	private YtThumbnails() {
	}

	/** Adds "Refresh thumbnail(s)" for the YouTube videos among {@code items}; nothing if none. */
	public static void addTo(OverlayMenu.Builder b, MainActivityDelegate a,
													 List<? extends PlayableItem> items) {
		if (videoIds(items).isEmpty()) return;
		b.addItem(R.id.youtube_refresh_thumbnail, R.drawable.refresh, R.string.youtube_refresh_thumbnail)
				.setHandler(i -> {
					refresh(a, new ArrayList<>(items));
					return true;
				});
	}

	private static Map<String, PlayableItem> videoIds(List<? extends PlayableItem> items) {
		Map<String, PlayableItem> m = new LinkedHashMap<>();
		for (PlayableItem pi : items) {
			String id = YtDownloads.videoIdOf(pi);
			if (id != null) m.putIfAbsent(id, pi);
		}
		return m;
	}

	/** Main thread. */
	public static void refresh(MainActivityDelegate a, List<? extends PlayableItem> items) {
		Map<String, PlayableItem> videos = videoIds(items);
		if (videos.isEmpty()) return;
		Context ctx = a.getContext();
		BitmapCache bc = FermataApplication.get().getBitmapCache();

		for (Map.Entry<String, PlayableItem> e : videos.entrySet()) {
			String base = "https://img.youtube.com/vi/" + e.getKey() + '/';
			bc.invalidate(ctx, base + "maxresdefault.jpg");
			bc.invalidate(ctx, base + "hqdefault.jpg");
			bc.invalidate(ctx, "https://i.ytimg.com/vi/" + e.getKey() + "/mqdefault.jpg");
		}
		refreshMeta(items); // Drops the cached description and metadata (artist, duration).
		reloadList(a);
		TopToast.show(R.drawable.refresh, ctx.getString(R.string.youtube_refreshing_n, videos.size()));

		AddonManager.get().getOrInstallAddon(VideoTitleCache.YOUTUBE_ADDON_CLASS).main()
				.onCompletion((addon, err) -> {
					if (!(addon instanceof VideoTitleCache cache)) return;
					Map<String, String> lookups = new LinkedHashMap<>();

					for (Map.Entry<String, PlayableItem> e : videos.entrySet()) {
						VideoInfo vi = cache.getCachedVideoInfo(e.getKey());
						if ((vi != null) && (vi.artist != null) && (vi.durationMs > 0)) continue;
						lookups.put(e.getKey(), e.getValue().getName());
					}

					if (lookups.isEmpty()) return;
					executor.execute(() -> lookUp(a, cache, lookups, items));
				});
	}

	/** On the background thread. */
	private static void lookUp(MainActivityDelegate a, VideoTitleCache cache, Map<String, String> lookups,
														 List<? extends PlayableItem> items) {
		Map<String, VideoInfo> found = new LinkedHashMap<>();
		boolean first = true;

		for (Map.Entry<String, String> e : lookups.entrySet()) {
			if (!first) {
				try {
					Thread.sleep(LOOKUP_DELAY_MS);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			first = false;
			Video v = find(e.getKey(), e.getKey());
			if (v == null) v = find(e.getKey(), e.getValue());
			// Searching can miss (an id is a poor query); the channel is also known from the
			// video's own page data, the way it is when a video is played before it's favorited.
			if (v == null) v = YoutubeSearch.lookUpById(e.getKey());
			if (v == null) continue;
			found.put(e.getKey(), new VideoInfo(v.title, v.channel, null, v.durationMs));
		}

		a.getHandler().post(() -> {
			if (!found.isEmpty()) {
				cache.cacheVideoInfo(found);
				refreshMeta(items);
				reloadList(a);
			}
			TopToast.show(R.drawable.refresh, a.getContext().getString(R.string.youtube_refreshed, found.size()));
		});
	}

	/** The video {@code id}, found by searching for {@code query} (its own id works well). */
	@Nullable
	private static Video find(String id, @Nullable String query) {
		if ((query == null) || query.isEmpty()) return null;

		try {
			for (Video v : YoutubeSearch.search(query, 8)) {
				if (id.equals(v.videoId)) return v;
			}
		} catch (Exception ex) {
			Log.e(ex, "Failed to look up the YouTube video ", id);
		}

		return null;
	}

	/** Metadata is read once per item and kept: it has to be dropped to show what was just found. */
	private static void refreshMeta(List<? extends PlayableItem> items) {
		for (PlayableItem pi : items) {
			pi.refreshMetadata();
			pi.updateTitles();
		}
	}

	private static void reloadList(MainActivityDelegate a) {
		MediaLibFragment f = a.getActiveMediaLibFragment();
		if (f != null) f.reloadKeepScroll();
	}
}
