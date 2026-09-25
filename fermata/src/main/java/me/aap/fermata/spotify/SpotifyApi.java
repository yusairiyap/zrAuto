package me.aap.fermata.spotify;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.spotify.SpotifyImportModel.Playlist;
import me.aap.fermata.spotify.SpotifyImportModel.Track;

/**
 * The official Spotify Web API, signed in as the user (see {@link SpotifyAuth}). Compared with the
 * keyless embed page ({@link SpotifyClient}) this lists the user's own playlists (private ones
 * too) and Liked Songs, and returns complete track lists instead of just the first ~100.
 * <p>
 * Since Spotify's February 2026 changes an app in development mode may read the items of a
 * playlist only if the user owns or collaborates on it -- {@link #fetch} reports any other
 * playlist as {@link NotAccessibleException}, and the caller falls back to the embed page.
 * <p>
 * All methods block -- call them on a background thread.
 */
public final class SpotifyApi {
	/** The pseudo-ref of the user's Liked Songs. */
	public static final String LIKED_SONGS = "liked";
	private static final String API = "https://api.spotify.com/v1";
	/** A safety net against endless paging. */
	private static final int MAX_TRACKS = 5000;

	private SpotifyApi() {
	}

	/** An entry of the "My playlists" picker. */
	public static final class PlaylistInfo {
		public final String ref;
		public final String name;
		@Nullable
		public final String owner;
		public final int total;
		/** Owned or collaborative: readable through the API in full. */
		public final boolean full;

		PlaylistInfo(String ref, String name, @Nullable String owner, int total, boolean full) {
			this.ref = ref;
			this.name = name;
			this.owner = owner;
			this.total = total;
			this.full = full;
		}
	}

	public static final class NotAccessibleException extends IOException {
		NotAccessibleException(String msg) {
			super(msg);
		}
	}

	/** The signed-in user's playlists, owned and followed; Liked Songs isn't included. */
	public static List<PlaylistInfo> listMyPlaylists() throws IOException {
		try {
			String me = get(API + "/me").optString("id");
			List<PlaylistInfo> list = new ArrayList<>();

			for (String url = API + "/me/playlists?limit=50"; url != null; ) {
				JSONObject page = get(url);
				JSONArray items = page.optJSONArray("items");

				if (items != null) {
					for (int i = 0; i < items.length(); i++) {
						JSONObject p = items.optJSONObject(i);
						if (p == null) continue;
						String id = p.optString("id");
						if (id.isEmpty()) continue;
						JSONObject owner = p.optJSONObject("owner");
						String ownerId = (owner == null) ? "" : owner.optString("id");
						String ownerName = str(owner, "display_name");
						boolean full = me.equals(ownerId) || p.optBoolean("collaborative");
						list.add(new PlaylistInfo("playlist/" + id, p.optString("name", id), ownerName,
								total(p), full));
					}
				}

				url = next(page);
			}

			return list;
		} catch (JSONException ex) {
			throw new IOException("Unexpected Spotify response", ex);
		}
	}

	/** Loads a playlist ({@code playlist/<id>}), album ({@code album/<id>}) or Liked Songs. */
	public static void fetch(Playlist pl) throws IOException {
		try {
			pl.tracks.clear();

			if (LIKED_SONGS.equals(pl.ref)) {
				if (pl.name.isEmpty()) pl.name = "Liked Songs";
				readTracks(pl, API + "/me/tracks?limit=50");
			} else if (pl.ref.startsWith("playlist/")) {
				String id = pl.ref.substring("playlist/".length());
				JSONObject p = get(API + "/playlists/" + id + "?fields=name,owner(display_name),images");
				pl.name = p.optString("name", pl.name);
				JSONObject owner = p.optJSONObject("owner");
				pl.owner = str(owner, "display_name");
				pl.coverUrl = image(p);
				readTracks(pl, API + "/playlists/" + id + "/items?limit=50");
			} else if (pl.ref.startsWith("album/")) {
				String id = pl.ref.substring("album/".length());
				JSONObject a = get(API + "/albums/" + id);
				pl.name = a.optString("name", pl.name);
				pl.owner = artists(a);
				pl.coverUrl = image(a);
				readTracks(pl, API + "/albums/" + id + "/tracks?limit=50");
			} else {
				throw new IOException("Unsupported Spotify reference: " + pl.ref);
			}
		} catch (JSONException ex) {
			throw new IOException("Unexpected Spotify response", ex);
		}
	}

	private static void readTracks(Playlist pl, String firstPage) throws IOException, JSONException {
		for (String url = firstPage; (url != null) && (pl.tracks.size() < MAX_TRACKS); ) {
			JSONObject page = get(url);
			JSONArray items = page.optJSONArray("items");
			if (items == null) break;

			for (int i = 0; i < items.length(); i++) {
				JSONObject o = items.optJSONObject(i);
				if (o == null) continue;
				// Playlist/library entries wrap the track ("item" since Feb 2026, "track" before);
				// album tracks are the track objects themselves.
				JSONObject t = o.optJSONObject("item");
				if (t == null) t = o.optJSONObject("track");
				if (t == null) t = o;
				String type = t.optString("type", "track");
				if (!"track".equals(type)) continue; // podcast episodes etc.
				String name = t.optString("name");
				if (name.isEmpty()) continue;
				String artists = artists(t);
				pl.tracks.add(new Track(name, (artists == null) ? "" : artists,
						t.optLong("duration_ms", -1)));
			}

			url = next(page);
		}
	}

	private static JSONObject get(String url) throws IOException, JSONException {
		Http.Response r = request(url, false);

		if (r.code == 401) {
			r = request(url, true); // Token revoked/expired early: refresh once.
			if (r.code == 401) {
				SpotifyAuth.logout();
				throw new SpotifyAuth.AuthException();
			}
		}

		if (r.code == 429) {
			try {
				Thread.sleep(Math.min(Math.max(r.retryAfter, 1), 10) * 1000L);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted", ex);
			}
			r = request(url, false);
		}

		if ((r.code == 403) || (r.code == 404)) {
			throw new NotAccessibleException("Spotify returned HTTP " + r.code);
		}

		if (!r.isOk()) throw new IOException("Spotify returned HTTP " + r.code);
		return new JSONObject(r.body);
	}

	private static Http.Response request(String url, boolean refresh) throws IOException {
		String token = SpotifyAuth.getAccessToken(refresh);
		return Http.get(url, Collections.singletonMap("Authorization", "Bearer " + token));
	}

	@Nullable
	private static String next(JSONObject page) {
		String next = page.optString("next");
		return (next.isEmpty() || "null".equals(next)) ? null : next;
	}

	private static int total(JSONObject playlist) {
		// "items" since Feb 2026, "tracks" before.
		JSONObject o = playlist.optJSONObject("items");
		if (o == null) o = playlist.optJSONObject("tracks");
		return (o == null) ? -1 : o.optInt("total", -1);
	}

	@Nullable
	private static String image(JSONObject o) {
		JSONArray images = o.optJSONArray("images");
		if ((images == null) || (images.length() == 0)) return null;
		JSONObject img = images.optJSONObject(0); // Spotify lists the largest first.
		return str(img, "url");
	}

	/** A string value, or null if absent, JSON null or empty. */
	@Nullable
	private static String str(@Nullable JSONObject o, String key) {
		if ((o == null) || o.isNull(key)) return null;
		String v = o.optString(key);
		return v.isEmpty() ? null : v;
	}

	@Nullable
	private static String artists(JSONObject o) {
		JSONArray a = o.optJSONArray("artists");
		if (a == null) return null;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < a.length(); i++) {
			JSONObject ar = a.optJSONObject(i);
			if (ar == null) continue;
			String n = ar.optString("name");
			if (n.isEmpty()) continue;
			if (sb.length() > 0) sb.append(", ");
			sb.append(n);
		}
		return (sb.length() == 0) ? null : sb.toString();
	}
}
