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
import me.aap.fermata.media.engine.MediaEngine;
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
	private static final String ID_PREFIX = "youtube:";
	private final String videoId;

	public YoutubeVideoItem(String videoId, @NonNull BrowsableItem parent) {
		super(ID_PREFIX + videoId, parent,
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

	/**
	 * Extracts the YouTube video id {@code item} represents, or {@code null} if it doesn't represent
	 * one at all. Works for a raw {@code YoutubeVideoItem} and for a Favorites/Playlist entry wrapping
	 * one alike ({@link MediaLib.PlayableItem#getOrigId()} resolves through an exported wrapper to
	 * the underlying original's id either way), which is what makes it possible to tell, from a
	 * sibling {@code getNextPlayable()}/{@code getPrevPlayable()} resolved against a real
	 * Favorites/Playlist, whether that sibling is a YouTube video at all -- those siblings are always
	 * exported wrappers, never {@code YoutubeVideoItem} instances directly, so an {@code instanceof}
	 * check alone would never match.
	 */
	@Nullable
	static String extractYoutubeVideoId(@Nullable MediaLib.PlayableItem item) {
		if (item == null) return null;
		String id = item.getOrigId();
		return ((id != null) && id.startsWith(ID_PREFIX)) ? id.substring(ID_PREFIX.length()) : null;
	}

	public String getVideoId() {
		return videoId;
	}

	// MediaEngineManager#createEngine() calls this (via ExportedItem.ExportedExternallyPlayableItem's
	// delegating override -- see there) to decide which engine plays this item. Reached only for
	// MediaSessionCallback's own automatic next/prev/end-of-video advance -- a direct tap in the UI
	// goes through loadInFragment() instead and never calls this at all. Without this override (the
	// interface default always returns null), that automatic path has no way to know a live
	// YoutubeMediaEngine already showing this exact page is the only thing that can actually play
	// another YouTube video, and falls back to treating this item's resource (the watch-page URL) as
	// a literal media source for whatever generic engine getVideoEnginePref() would otherwise select
	// -- which fails outright, since that URL is an HTML page, not a media stream. Returning null
	// when there's no live YoutubeMediaEngine yet preserves default engine selection, same as
	// YoutubeMediaEngine.YoutubeItem's own override of this method.
	@Nullable
	@Override
	public MediaEngine getMediaEngine(@Nullable MediaEngine current, MediaEngine.Listener listener) {
		return (current instanceof YoutubeMediaEngine) ? current : null;
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
	public void loadInFragment(ActivityFragment fragment, MediaLib.PlayableItem self) {
		YoutubeAddon addon = AddonManager.get().getAddon(YoutubeAddon.class);
		// Remembers self (not "this") as the playback queue: for a Favorites/Playlist entry, self is
		// the exported wrapper the user actually tapped -- its getParent() is that real container,
		// unlike "this" (the underlying original ExportedItem always delegates to), whose parent is
		// the unrelated internal "youtube" root. See ExternallyPlayableItem#loadInFragment()'s
		// contract. Lets YoutubeMediaEngine's next/prev navigate the actual list in order instead of
		// YouTube's own page-internal next/prev, which knows nothing about any of this.
		//
		// Also arms pendingVideoId with this same video: without it, YoutubeMediaEngine#playing()'s
		// unrequested-transition check (see YoutubeAddon#getPendingVideoId()) sees the page move from
		// whatever was playing before (if anything) to this one, finds the queue item already set
		// (just above), and -- with nothing here to say this transition was itself requested --
		// mistakes this ordinary tap-to-play for "the previous video just ended", skipping straight
		// past the video the user actually tapped to whatever the queue's own next item is.
		Log.d("YoutubeVideoItem.loadInFragment(): queueItem=", self, " parent=", self.getParent(),
				" addon=", addon);
		if (addon != null) {
			addon.setQueueItem(self);
			addon.setPendingVideoId(videoId);
		}
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
