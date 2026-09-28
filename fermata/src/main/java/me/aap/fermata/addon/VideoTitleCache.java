package me.aap.fermata.addon;

import androidx.annotation.Nullable;

import java.util.Map;

import me.aap.fermata.media.lib.MediaLib.PlayableItem;

/**
 * Implemented by the YouTube addon (which lives in the on-demand {@code web} module, so the
 * {@code fermata} module can't reference it directly): lets the Spotify import pre-seed the
 * titles its {@code youtube:<videoId>} playlist entries display, without first playing each video,
 * and remembers the rest of what a Favorites/Playlist entry shows (channel as the artist, album,
 * duration) -- see {@link #recordAddedItem}.
 */
public interface VideoTitleCache {
	String YOUTUBE_ADDON_CLASS = "me.aap.fermata.addon.web.yt.YoutubeAddon";
	String YOUTUBE_ID_PREFIX = "youtube:";

	/** videoId to title; written in one go. */
	void cacheVideoTitles(Map<String, String> titles);

	/**
	 * videoId to what's known about it; written in one go. Only the known (non-null, positive
	 * duration) fields are written, so a later, less complete source never erases an earlier one.
	 */
	void cacheVideoInfo(Map<String, VideoInfo> info);

	/**
	 * A {@code youtube:<videoId>} item is about to be added to Favorites or a Playlist: keeps the
	 * channel/duration the player reported while playing it, if it did.
	 */
	void recordAddedVideo(String videoId);

	final class VideoInfo {
		@Nullable
		public final String title;
		/** For YouTube: the channel name. */
		@Nullable
		public final String artist;
		@Nullable
		public final String album;
		/** -1 if unknown. */
		public final long durationMs;

		public VideoInfo(@Nullable String title, @Nullable String artist, @Nullable String album,
										 long durationMs) {
			this.title = title;
			this.artist = artist;
			this.album = album;
			this.durationMs = durationMs;
		}
	}

	/**
	 * Called by Favorites/Playlists right before an item is added, see {@link #recordAddedVideo}.
	 * Before, not after: the list entry reads the item's metadata as soon as it's created.
	 */
	static void beforeItemAdded(PlayableItem i) {
		String id = i.getOrigId();
		if ((id == null) || !id.startsWith(YOUTUBE_ID_PREFIX)) return;
		String videoId = id.substring(YOUTUBE_ID_PREFIX.length());
		if (videoId.isEmpty()) return;
		for (FermataAddon a : AddonManager.get().getAddons()) {
			if (!(a instanceof VideoTitleCache c)) continue;
			c.recordAddedVideo(videoId);
			return;
		}
	}
}
