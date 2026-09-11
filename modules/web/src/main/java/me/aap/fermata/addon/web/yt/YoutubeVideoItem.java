package me.aap.fermata.addon.web.yt;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE;
import static me.aap.fermata.media.pref.MediaPrefs.MEDIA_ENG_YT;
import static me.aap.utils.async.Completed.completed;

import android.net.Uri;
import android.support.v4.media.MediaMetadataCompat;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.media.lib.ExtPlayable;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.vfs.generic.GenericFileSystem;

/**
 * A stable, ID-resolvable YouTube video, so it can be saved in and later resolved back from
 * Favourites/Playlists. Unlike {@code YoutubeMediaEngine.YoutubeItem}, which is a transient
 * now-playing placeholder, this item's id encodes the video id, and it is registered with the
 * media library via {@link YoutubeAddon} ({@code youtube:<videoId>}).
 */
public class YoutubeVideoItem extends ExtPlayable implements MediaLib.ExternallyPlayableItem {
	private final String videoId;

	public YoutubeVideoItem(String videoId, @NonNull BrowsableItem parent) {
		super("youtube:" + videoId, parent,
				GenericFileSystem.getInstance().create(watchUrl(videoId)));
		this.videoId = videoId;
	}

	static String watchUrl(String videoId) {
		return "https://m.youtube.com/watch?v=" + videoId;
	}

	/**
	 * Extracts a video id from a YouTube watch/shorts URL (the page's own URL, not a media
	 * {@code <video>} source), or {@code null} if {@code url} isn't one/doesn't carry one. Shared by
	 * {@link YoutubeFragment#getCurrentVideoId()} and {@link YoutubeMediaEngine}, which uses it to
	 * confirm the page actually navigated to the video its own queue-driven next/prev asked for
	 * (see {@link YoutubeAddon#getQueueItem()}).
	 */
	@Nullable
	static String extractVideoId(@Nullable String url) {
		if (url == null) return null;
		Uri u = Uri.parse(url);
		String id = u.getQueryParameter("v");
		if ((id != null) && !id.isEmpty()) return id;

		String path = u.getPath();
		if ((path != null) && path.startsWith("/shorts/")) {
			String[] seg = path.split("/");
			if ((seg.length >= 3) && !seg[2].isEmpty()) return seg[2];
		}

		return null;
	}

	public String getVideoId() {
		return videoId;
	}

	@Override
	public boolean isVideo() {
		return true;
	}

	@Override
	public boolean isSeekable() {
		return true;
	}

	@Override
	public int getVideoEnginePref() {
		return MEDIA_ENG_YT;
	}

	@IdRes
	@Override
	public int getPlayerFragmentId() {
		return me.aap.fermata.R.id.youtube_fragment;
	}

	@Override
	public void loadInFragment(ActivityFragment fragment) {
		YoutubeAddon addon = AddonManager.get().getAddon(YoutubeAddon.class);
		// Remembers this item (and its real Favorites/Playlist parent) as the playback queue, so
		// YoutubeMediaEngine's next/prev navigate that list in order instead of YouTube's own
		// page-internal next/prev, which knows nothing about it.
		Log.d("YoutubeVideoItem.loadInFragment(): queueItem=", this, " parent=", getParent(),
				" addon=", addon);
		if (addon != null) addon.setQueueItem(this);
		((YoutubeFragment) fragment).loadUrl(watchUrl(videoId));
	}

	@NonNull
	@Override
	public String getName() {
		return cachedTitle();
	}

	private String cachedTitle() {
		YoutubeAddon addon = AddonManager.get().getAddon(YoutubeAddon.class);
		return (addon != null) ? addon.getVideoTitle(videoId) : videoId;
	}

	@NonNull
	@Override
	protected FutureSupplier<MediaMetadataCompat> loadMeta() {
		MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder();
		b.putString(METADATA_KEY_TITLE, cachedTitle());
		b.putString(METADATA_KEY_ALBUM_ART_URI,
				"https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg");
		return completed(b.build());
	}

	@Override
	protected String buildSubtitle(MediaMetadataCompat md, SharedTextBuilder tb) {
		return null;
	}
}
