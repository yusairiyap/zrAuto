package me.aap.fermata.addon.music;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedVoid;

import android.net.Uri;
import android.support.v4.media.MediaMetadataCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.ExtPlayable;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.vfs.VirtualResource;
import me.aap.utils.vfs.generic.GenericFileSystem;

/**
 * One entry of the Music tab's queue, wrapping either:
 * <ul>
 *   <li>a YouTube video ({@code youtube:<videoId>} source id) -- played by the YouTube tab's own
 *   player, with the video held at its lowest quality while it's playing as music (see
 *   {@link MusicPlayer#setYoutubeAudioMode}), so next/prev, the crossfade between songs and the
 *   switch to and from video all behave exactly like YouTube video playback does;</li>
 *   <li>any other library item (a local/network audio or video file, a Favorites/Playlist entry),
 *   resolved by its id and played audio-only by the usual engines, with any video track skipped
 *   (see {@link #isAudioOnlyPlayback()}).</li>
 * </ul>
 * Reports itself as external, so playing it never touches the library's own "last played"
 * bookkeeping; the queue keeps its own (see {@link MusicQueue}).
 */
public class MusicTrackItem extends ExtPlayable {
	static final String YT_PREFIX = "youtube:";
	private final String sourceId;
	@Nullable
	private final String videoId;
	@Nullable
	private volatile PlayableItem source;
	@Nullable
	private FutureSupplier<Void> resolving;
	@Nullable
	private String title;
	@Nullable
	private String artist;
	private long durationMs;
	private long startPos;

	MusicTrackItem(String id, MusicQueue queue, String sourceId, @Nullable PlayableItem source,
								 @Nullable String title, @Nullable String artist, long durationMs) {
		super(id, queue, placeholder(sourceId, source));
		this.sourceId = sourceId;
		this.videoId = sourceId.startsWith(YT_PREFIX) ? sourceId.substring(YT_PREFIX.length()) : null;
		this.source = (videoId == null) ? source : null;
		this.title = title;
		this.artist = artist;
		this.durationMs = durationMs;
	}

	private static VirtualResource placeholder(String sourceId, @Nullable PlayableItem source) {
		if (sourceId.startsWith(YT_PREFIX)) {
			return GenericFileSystem.getInstance()
					.create("https://www.youtube.com/watch?v=" + sourceId.substring(YT_PREFIX.length()));
		}
		if (source != null) return source.getResource();
		return GenericFileSystem.getInstance().create("http://localhost/" + Uri.encode(sourceId));
	}

	static String thumbnailUrl(String videoId) {
		// BitmapCache falls back to hqdefault.jpg by itself for the rare video without this size.
		return "https://img.youtube.com/vi/" + videoId + "/maxresdefault.jpg";
	}

	/** The id this track was created from: {@code youtube:<videoId>}, or a library item id. */
	public String getSourceId() {
		return sourceId;
	}

	/** The YouTube video id this track plays, or null for a library item. */
	@Nullable
	public String getVideoId() {
		return videoId;
	}

	/** The library item this track plays, once resolved; always null for YouTube. */
	@Nullable
	public PlayableItem getSource() {
		return source;
	}

	/**
	 * YouTube tracks report their video's id ({@code youtube:<videoId>}), so the YouTube player
	 * recognizes them as its own queue items -- see {@link MusicPlayer#setYoutubeAudioMode}.
	 */
	@Override
	public String getOrigId() {
		return (videoId != null) ? sourceId : getId();
	}

	/**
	 * The library item "Add to favorites" should act on for this track: the song it plays, never
	 * this queue entry itself (which only exists in the queue). Null if that isn't resolvable yet
	 * (a YouTube video with the YouTube addon disabled).
	 */
	@Nullable
	public PlayableItem getFavoritableItem() {
		if (videoId == null) return source;
		Item i = getLib().getItem(sourceId).peek();
		return (i instanceof PlayableItem) ? (PlayableItem) i : null;
	}

	/** Whether switching this track to video playback means anything (see MusicPlayer). */
	public boolean hasVideo() {
		if (videoId != null) return true;
		PlayableItem src = source;
		return (src != null) && src.isVideo();
	}

	/**
	 * The title and artist (for YouTube, the channel) as the player reports them while this track
	 * plays; either may be null to keep what's known. Saved with the queue.
	 */
	public void setInfo(@Nullable String title, @Nullable String artist) {
		boolean changed = false;
		if ((title != null) && !title.isEmpty() && !title.equals(this.title)) {
			this.title = title;
			changed = true;
		}
		if ((artist != null) && !artist.isEmpty() && !artist.equals(this.artist)) {
			this.artist = artist;
			changed = true;
		}
		if (!changed) return;
		reset();
		getParent().trackChanged(this);
	}

	/** The artist, once known. */
	@Nullable
	public String getArtistName() {
		return cleanArtist(artist);
	}

	/**
	 * An artist name as it should be shown: YouTube's auto-generated artist channels are called
	 * {@code "<artist> - Topic"}, which is just the artist.
	 */
	@Nullable
	public static String cleanArtist(@Nullable String artist) {
		if ((artist != null) && artist.endsWith(" - Topic")) {
			return artist.substring(0, artist.length() - " - Topic".length());
		}
		return artist;
	}

	@Nullable
	String getCachedTitle() {
		return title;
	}

	long getCachedDuration() {
		return durationMs;
	}

	/**
	 * Where the next prepare should start from -- consumed by the very first read, which is
	 * {@code MediaSessionCallback#onEnginePrepared()}'s seek (read through the library's
	 * {@code getLastPlayedPosition()}, i.e. {@link #getPositionPref()} below), or the YouTube
	 * player's start time (see {@link MusicPlayer#getYoutubeEngine}).
	 */
	void setStartPosition(long pos) {
		startPos = Math.max(0, pos);
	}

	boolean hasStartPosition() {
		return startPos > 0;
	}

	@Override
	public long getPositionPref() {
		long pos = startPos;
		startPos = 0;
		return pos;
	}

	@Override
	public void setPositionPref(long pos) {
		// Nothing to persist per track -- the queue remembers where it was.
	}

	@Override
	public boolean isVideo() {
		return false;
	}

	@Override
	public boolean isAudioOnlyPlayback() {
		return true;
	}

	@Override
	public boolean isCacheable() {
		return false;
	}

	@Override
	public boolean isSeekable() {
		if (videoId != null) return true;
		PlayableItem src = source;
		return (src == null) || src.isSeekable();
	}

	@NonNull
	@Override
	public VirtualResource getResource() {
		PlayableItem src = source;
		return (src != null) ? src.getResource() : super.getResource();
	}

	@NonNull
	@Override
	public Uri getLocation() {
		PlayableItem src = source;
		return (src != null) ? src.getLocation() : super.getLocation();
	}

	@Override
	public boolean isNetResource() {
		PlayableItem src = source;
		return (src != null) && src.isNetResource();
	}

	@Nullable
	@Override
	public String getUserAgent() {
		PlayableItem src = source;
		return (src != null) ? src.getUserAgent() : null;
	}

	@Override
	public long getOffset() {
		PlayableItem src = source;
		return (src != null) ? src.getOffset() : 0;
	}

	@Nullable
	@Override
	public MediaEngine getMediaEngine(@Nullable MediaEngine current, MediaEngine.Listener listener) {
		return (videoId != null) ? MusicPlayer.getYoutubeEngine(this, current, listener) : null;
	}

	@NonNull
	@Override
	public synchronized FutureSupplier<Void> prepareSource() {
		if ((videoId != null) || (source != null)) return completedVoid();
		if ((resolving != null) && !resolving.isDone()) return resolving;
		return resolving = resolveLocal();
	}

	private FutureSupplier<Void> resolveLocal() {
		return getLib().getItem(sourceId).main().map(i -> {
			if (i instanceof PlayableItem pi) {
				source = pi;
				reset();
			} else {
				Log.w("Music queue source not found: ", sourceId);
			}
			return (Void) null;
		}).ifFail(err -> {
			Log.w(err, "Failed to resolve music queue source ", sourceId);
			return null;
		});
	}

	@NonNull
	@Override
	public MusicQueue getParent() {
		return (MusicQueue) super.getParent();
	}

	@NonNull
	@Override
	public String getName() {
		if (title != null) return title;
		PlayableItem src = source;
		if (src != null) return src.getName();
		return (videoId != null) ? videoId : sourceId;
	}

	@NonNull
	@Override
	protected FutureSupplier<MediaMetadataCompat> loadMeta() {
		if (videoId != null) {
			MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder();
			b.putString(METADATA_KEY_TITLE, getName());
			if (artist != null) b.putString(METADATA_KEY_ARTIST, getArtistName());
			if (durationMs > 0) b.putLong(METADATA_KEY_DURATION, durationMs);
			b.putString(METADATA_KEY_ALBUM_ART_URI, thumbnailUrl(videoId));
			return completed(b.build());
		}

		// Never a network round trip here: the queue's getChildren() loads every track's metadata,
		// and a local item's id resolves straight out of the library.
		return prepareSource().then(v -> {
			PlayableItem src = source;
			if (src == null) return completed(buildCachedMeta());
			return src.getMediaData().map(md -> {
				String t = md.getString(METADATA_KEY_TITLE);
				String a = md.getString(METADATA_KEY_ARTIST);
				if ((t != null) && !t.trim().isEmpty()) title = t;
				else if (title == null) title = src.getName();
				if ((a != null) && !a.isEmpty()) artist = a;
				long d = md.getLong(METADATA_KEY_DURATION);
				if (d > 0) durationMs = d;
				MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder(md);
				b.putString(METADATA_KEY_TITLE, title);
				return b.build();
			});
		});
	}

	private MediaMetadataCompat buildCachedMeta() {
		MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder();
		b.putString(METADATA_KEY_TITLE, getName());
		if (artist != null) b.putString(METADATA_KEY_ARTIST, getArtistName());
		if (durationMs > 0) b.putLong(METADATA_KEY_DURATION, durationMs);
		return b.build();
	}

	/** The album art URI to show for this track, or null to use the media's embedded art. */
	@Nullable
	public String getArtUri() {
		return (videoId != null) ? thumbnailUrl(videoId) : null;
	}

	@NonNull
	@Override
	public FutureSupplier<Void> setDuration(long duration) {
		// Only this track's own metadata -- the base implementation would also record it in the
		// library's metadata database under this (queue-only) id.
		if (duration <= 0) return completedVoid();
		durationMs = duration;
		return getMediaData().map(md -> {
			MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder(md);
			b.putLong(METADATA_KEY_DURATION, duration);
			setMeta(completed(b.build()));
			return (Void) null;
		});
	}

	@Override
	protected FutureSupplier<String> buildTitle() {
		// The queue's own order is what matters -- no "1. ", "2. " sequence numbers.
		return buildTitle(0, getParent().getPrefs());
	}

	@Override
	protected FutureSupplier<String> buildTitle(int seqNum, BrowsableItemPrefs parentPrefs) {
		return getMediaData().map(md -> {
			String t = md.getString(METADATA_KEY_TITLE);
			return ((t == null) || t.isEmpty()) ? getName() : t;
		});
	}

	@Override
	protected String buildSubtitle(MediaMetadataCompat md, SharedTextBuilder tb) {
		String a = md.getString(METADATA_KEY_ARTIST);
		return (a != null) ? a : "";
	}

	@NonNull
	@Override
	public FutureSupplier<PlayableItem> getNextPlayable() {
		return getParent().getPlayable(this, true);
	}

	@NonNull
	@Override
	public FutureSupplier<PlayableItem> getPrevPlayable() {
		return getParent().getPlayable(this, false);
	}

	@Override
	public boolean equals(Object o) {
		return (o instanceof Item) && getId().equals(((Item) o).getId());
	}

	@Override
	public int hashCode() {
		return getId().hashCode();
	}

	/** Shown in playback error messages ("Failed to play ...") -- the song, not the queue id. */
	@NonNull
	@Override
	public String toString() {
		return getName();
	}
}
