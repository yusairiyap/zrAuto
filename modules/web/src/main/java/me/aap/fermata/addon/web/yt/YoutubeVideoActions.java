package me.aap.fermata.addon.web.yt;

import static me.aap.utils.async.Completed.completed;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.media.lib.DefaultMediaLib;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ytdl.YtDownloadMenu;
import me.aap.utils.ui.menu.OverlayMenu;

/**
 * The library actions of a video's long-press menu -- Add to favorites, Add to playlist and the
 * downloads -- for a video that is only a thumbnail on screen (the page, the search panel, the
 * bubbles): it isn't in the library yet, so an item is made for it from its id.
 */
final class YoutubeVideoActions {
	private YoutubeVideoActions() {
	}

	/** Adds the entries to {@code b}; nothing if the YouTube addon isn't there. */
	static void addTo(MainActivityDelegate a, OverlayMenu.Builder b, String videoId,
										@Nullable String title) {
		YoutubeAddon addon = AddonManager.get().getAddon(YoutubeAddon.class);
		if ((addon == null) || !(a.getLib() instanceof DefaultMediaLib lib)) return;

		// So the library entry (and the download) shows the title rather than the bare id.
		if ((title != null) && !title.isEmpty()) addon.cacheVideoTitle(videoId, title);
		YoutubeVideoItem item = new YoutubeVideoItem(videoId, addon.getRootItem(lib));
		List<PlayableItem> selection = Collections.singletonList(item);
		// Through the interface: the library's own class doesn't expose these publicly.
		MediaLib.Favorites favorites = lib.getFavorites();

		if (item.isFavoriteItem()) {
			b.addItem(me.aap.fermata.R.id.favorites_remove, me.aap.fermata.R.drawable.favorite_filled,
					me.aap.fermata.R.string.favorites_remove).setHandler(i -> {
				favorites.removeItem(item);
				a.fireBroadcastEvent(me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED);
				return true;
			});
		} else {
			b.addItem(me.aap.fermata.R.id.favorites_add, me.aap.fermata.R.drawable.favorite,
					me.aap.fermata.R.string.favorites_add).setHandler(i -> {
				favorites.addItem(item);
				a.fireBroadcastEvent(me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED);
				return true;
			});
		}

		a.addPlaylistMenu(b, completed(selection));
		YtDownloadMenu.addTo(b, a, selection);
	}
}
