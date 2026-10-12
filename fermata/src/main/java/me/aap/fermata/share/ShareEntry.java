package me.aap.fermata.share;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * One thing in a share: a YouTube video (just its id and title, plus the downloaded file if it is
 * sent along) or a media file whose bytes follow in the stream.
 */
public final class ShareEntry {
	public static final int YOUTUBE = 0;
	public static final int FILE = 1;

	public final int type;
	/** The YouTube video id; null for a file. */
	@Nullable
	public final String videoId;
	/** The title of the video, or the file's name. */
	public final String name;
	@Nullable
	public final String artist;
	public final long durationMs;
	/** A downloaded video (with a picture) rather than just its sound. */
	public final boolean video;
	/** The downloaded picture's height; 0 for sound only. */
	public final int height;
	/** How many bytes follow in the stream for this entry; 0 when there is nothing to send. */
	public long size;

	// What the sender needs to read the bytes, never sent.
	/** A downloaded YouTube video's file; sent only if the sender asks for downloads to go too. */
	@Nullable
	public File downloadFile;
	@Nullable
	public File file;
	@Nullable
	public Uri uri;

	public ShareEntry(int type, @Nullable String videoId, String name, @Nullable String artist,
										long durationMs, boolean video, int height, long size) {
		this.type = type;
		this.videoId = videoId;
		this.name = name;
		this.artist = artist;
		this.durationMs = durationMs;
		this.video = video;
		this.height = height;
		this.size = size;
	}

	ShareEntry copy() {
		ShareEntry e = new ShareEntry(type, videoId, name, artist, durationMs, video, height, size);
		e.downloadFile = downloadFile;
		e.file = file;
		e.uri = uri;
		return e;
	}

	JSONObject toJson() throws JSONException {
		JSONObject o = new JSONObject();
		o.put("t", type);
		o.putOpt("id", videoId);
		o.put("n", name);
		o.putOpt("a", artist);
		o.put("d", durationMs);
		o.put("v", video);
		o.put("h", height);
		o.put("s", size);
		return o;
	}

	static ShareEntry fromJson(JSONObject o) {
		String id = o.isNull("id") ? null : o.optString("id", null);
		String artist = o.isNull("a") ? null : o.optString("a", null);
		return new ShareEntry(o.optInt("t", FILE), id, o.optString("n"), artist, o.optLong("d", -1),
				o.optBoolean("v"), o.optInt("h"), Math.max(0, o.optLong("s")));
	}
}
