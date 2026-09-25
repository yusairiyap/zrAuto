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

import me.aap.fermata.media.lib.ExtPlayable;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.CheckedSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.vfs.VirtualResource;
import me.aap.utils.vfs.generic.GenericFileSystem;

/**
 * One entry of the Music tab's queue -- always played audio-only, whatever it wraps:
 * <ul>
 *   <li>a YouTube video ({@code youtube:<videoId>} source id), whose audio-only stream URL is
 *   fetched right before playback (see {@link #prepareSource()} and {@link YoutubeAudioResolver});
 *   no video frame, and no web page, is ever loaded for it;</li>
 *   <li>any other library item (a local/network audio or video file, a Favorites/Playlist entry),
 *   resolved by its id and streamed through the same engines as usual, with any video track
 *   skipped (see {@link #isAudioOnlyPlayback()}).</li>
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
	private volatile YoutubeAudioResolver.Stream stream;
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
			return GenericFileSystem.getInstance().create(watchUrl(sourceId.substring(YT_PREFIX.length())));
		}
		if (source != null) return source.getResource();
		return GenericFileSystem.getInstance().create("http://localhost/" + Uri.encode(sourceId));
	}

	static String watchUrl(String videoId) {
		return "https://www.youtube.com/watch?v=" + videoId;
	}

	static String thumbnailUrl(String videoId) {
		// BitmapCache falls back to hqdefault.jpg by itself for the rare video without this size.
		return "https://img.youtube.com/vi/" + videoId + "/maxresdefault.jpg";
	}

	/**
	 * The id this track was created from: {@code youtube:<videoId>}, or a library item id.
	 */
	public String getSourceId() {
		return sourceId;
	}

	/** The YouTube video id this track plays the audio of, or null for a library item. */
	@Nullable
	public String getVideoId() {
		return videoId;
	}

	/** The library item this track plays, once resolved; always null for YouTube. */
	@Nullable
	public PlayableItem getSource() {
		return source;
	}

	/** Whether switching this track to video playback means anything (see MusicPlayer). */
	public boolean hasVideo() {
		if (videoId != null) return true;
		PlayableItem src = source;
		return (src != null) && src.isVideo();
	}

	/** The artist (YouTube: the channel), once known. */
	@Nullable
	public String getArtistName() {
		return artist;
	}

	@Nullable
	String getCachedTitle() {
		return title;
	}

	@Nullable
	String getCachedArtist() {
		return artist;
	}

	long getCachedDuration() {
		return durationMs;
	}

	/**
	 * Where the next prepare should start from -- consumed by the very first read, which is
	 * {@code MediaSessionCallback#onEnginePrepared()}'s seek (read through the library's
	 * {@code getLastPlayedPosition()}, i.e. {@link #getPositionPref()} below).
	 */
	void setStartPosition(long pos) {
		startPos = Math.max(0, pos);
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
		if (videoId != null) {
			YoutubeAudioResolver.Stream s = stream;
			return Uri.parse((s != null) ? s.url : watchUrl(videoId));
		}
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

	@NonNull
	@Override
	public synchronized FutureSupplier<Void> prepareSource() {
		if (videoId != null) {
			YoutubeAudioResolver.Stream s = stream;
			if ((s != null) && s.isValid()) return completedVoid();
		} else if (source != null) {
			return completedVoid();
		}

		if ((resolving != null) && !resolving.isDone()) return resolving;
		FutureSupplier<Void> r = (videoId != null) ? resolveYoutube(videoId) : resolveLocal();
		resolving = r;
		return r;
	}

	/** Reuses another track's already resolved stream for the same video (no second fetch). */
	void copyStreamFrom(MusicTrackItem t) {
		if ((videoId == null) || !videoId.equals(t.videoId) || (stream != null)) return;
		YoutubeAudioResolver.Stream s = t.stream;
		if ((s == null) || !s.isValid()) return;
		stream = s;
		if (t.title != null) title = t.title;
		if (t.artist != null) artist = t.artist;
		if (t.durationMs > 0) durationMs = t.durationMs;
		setMeta(completed(buildYoutubeMeta(videoId)));
	}

	/** Whether {@link #prepareSource()} would have to go to the network for this track. */
	boolean needsNetworkResolve() {
		if (videoId == null) return false;
		YoutubeAudioResolver.Stream s = stream;
		return (s == null) || !s.isValid();
	}

	private FutureSupplier<Void> resolveYoutube(String vid) {
		CheckedSupplier<YoutubeAudioResolver.Stream, Throwable> task =
				() -> YoutubeAudioResolver.resolve(vid);
		FutureSupplier<YoutubeAudioResolver.Stream> f = App.get().execute(task);
		return f.main().map(s -> {
			stream = s;
			if (s.title != null) title = s.title;
			if (s.author != null) artist = s.author;
			if (s.durationMs > 0) durationMs = s.durationMs;
			setMeta(completed(buildYoutubeMeta(vid)));
			getParent().trackUpdated(this);
			return (Void) null;
		}).ifFail(err -> {
			Log.w(err, "Failed to resolve an audio-only stream for YouTube video ", vid);
			getParent().trackFailed(this, err);
			return null;
		});
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
		if (videoId != null) return completed(buildYoutubeMeta(videoId));

		// Never a network round trip here: the queue's getChildren() loads every track's metadata,
		// and a local item's id resolves straight out of the library.
		FutureSupplier<Void> resolve = (source == null) ? prepareSource() : completedVoid();
		return resolve.then(v -> {
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

	private MediaMetadataCompat buildYoutubeMeta(String vid) {
		MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder();
		b.putString(METADATA_KEY_TITLE, (title != null) ? title : vid);
		if (artist != null) b.putString(METADATA_KEY_ARTIST, artist);
		if (durationMs > 0) b.putLong(METADATA_KEY_DURATION, durationMs);
		b.putString(METADATA_KEY_ALBUM_ART_URI, thumbnailUrl(vid));
		return b.build();
	}

	private MediaMetadataCompat buildCachedMeta() {
		MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder();
		b.putString(METADATA_KEY_TITLE, getName());
		if (artist != null) b.putString(METADATA_KEY_ARTIST, artist);
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
}
