package me.aap.fermata.share;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** What a phone offers to another: a name for it, what kind of thing it is, and its entries. */
public final class ShareOffer {
	public static final String KIND_ITEM = "item";
	public static final String KIND_PLAYLIST = "playlist";
	public static final String KIND_FAVORITES = "favorites";
	public static final String KIND_FOLDER = "folder";

	/** The sender's name for itself (the receiver's prompt shows it). */
	public final String sender;
	/** The playlist or folder's name; the receiver's playlist/folder takes it. */
	public final String title;
	public final String kind;
	public final List<ShareEntry> entries;
	/** Items that couldn't be offered (not on this phone, or not readable); sender side only. */
	public int skipped;

	public ShareOffer(String sender, String title, String kind, List<ShareEntry> entries) {
		this.sender = sender;
		this.title = title;
		this.kind = kind;
		this.entries = entries;
	}

	public long totalBytes() {
		long n = 0;
		for (ShareEntry e : entries) n += e.size;
		return n;
	}

	public int fileCount() {
		int n = 0;
		for (ShareEntry e : entries) if (e.size > 0) n++;
		return n;
	}

	public int youtubeCount() {
		int n = 0;
		for (ShareEntry e : entries) if (e.type == ShareEntry.YOUTUBE) n++;
		return n;
	}

	/** Bytes of downloaded YouTube files that {@link #applyDownloads} would add. */
	public long downloadBytes() {
		long n = 0;
		for (ShareEntry e : entries) {
			if ((e.type == ShareEntry.YOUTUBE) && (e.downloadFile != null)) n += e.downloadFile.length();
		}
		return n;
	}

	/**
	 * A copy to send as it is now: what is sent is fixed when the transfer starts, whatever the
	 * Share window does afterwards.
	 */
	public ShareOffer snapshot(boolean includeDownloads) {
		List<ShareEntry> list = new ArrayList<>(entries.size());
		for (ShareEntry e : entries) list.add(e.copy());
		ShareOffer o = new ShareOffer(sender, title, kind, list);
		o.skipped = skipped;
		o.applyDownloads(includeDownloads);
		return o;
	}

	/** Sends the downloaded files of YouTube entries along, or only their ids. */
	public void applyDownloads(boolean include) {
		for (ShareEntry e : entries) {
			if ((e.type != ShareEntry.YOUTUBE) || (e.downloadFile == null)) continue;
			e.size = include ? e.downloadFile.length() : 0;
		}
	}

	String toJson() throws JSONException {
		JSONObject o = new JSONObject();
		o.put("from", sender);
		o.put("title", title);
		o.put("kind", kind);
		JSONArray a = new JSONArray();
		for (ShareEntry e : entries) a.put(e.toJson());
		o.put("entries", a);
		return o.toString();
	}

	@Nullable
	static ShareOffer fromJson(String json) throws JSONException {
		JSONObject o = new JSONObject(json);
		JSONArray a = o.getJSONArray("entries");
		List<ShareEntry> list = new ArrayList<>(a.length());
		for (int i = 0; i < a.length(); i++) list.add(ShareEntry.fromJson(a.getJSONObject(i)));
		return new ShareOffer(o.optString("from", "?"), o.optString("title", "Shared"),
				o.optString("kind", KIND_ITEM), list);
	}
}
