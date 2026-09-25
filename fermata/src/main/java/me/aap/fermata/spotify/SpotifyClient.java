package me.aap.fermata.spotify;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.aap.fermata.spotify.SpotifyImportModel.Playlist;
import me.aap.fermata.spotify.SpotifyImportModel.Track;

/**
 * Reads a public Spotify playlist (or album) without any API key or account.
 * <p>
 * The official Spotify Web API is no longer a free option: since February 2026 a development-mode
 * app only works while its owner holds a Premium subscription, and it needs a registered client
 * id/secret. Instead this reads the public <em>embed</em> page
 * ({@code open.spotify.com/embed/playlist/<id>}, the same widget any web page can embed), which
 * ships its data as server-rendered JSON. Limitations: only public playlists work, and the embed
 * lists at most the first ~100 tracks of a playlist.
 * <p>
 * All methods block -- call them on a background thread.
 */
public final class SpotifyClient {
	private static final Pattern REF = Pattern.compile(
			"(?:open\\.spotify\\.com/(?:intl-[A-Za-z_-]+/)?(?:embed/)?|spotify:)" +
					"(playlist|album)[/:]([A-Za-z0-9]{22})");
	private static final Pattern SHORT_LINK =
			Pattern.compile("https?://(?:spotify\\.link|spoti\\.fi)/[A-Za-z0-9_-]+");
	private static final Pattern JSON_SCRIPT = Pattern.compile(
			"<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL);

	private SpotifyClient() {
	}

	/**
	 * Every Spotify playlist/album reference found in {@code text} (URLs, {@code spotify:} URIs or
	 * short links, any separator), de-duplicated and in order. Short links are returned as-is and
	 * resolved by {@link #fetch}.
	 */
	public static List<String> extractRefs(String text) {
		Set<String> refs = new LinkedHashSet<>();
		Matcher m = REF.matcher(text);
		while (m.find()) refs.add(m.group(1) + '/' + m.group(2));
		m = SHORT_LINK.matcher(text);
		while (m.find()) refs.add(m.group());
		return new ArrayList<>(refs);
	}

	/** Loads the playlist/album {@code pl.ref} points to into {@code pl} (name, cover, tracks). */
	public static void fetch(Playlist pl) throws IOException {
		String ref = pl.ref;

		if (ref.startsWith("http")) {
			Http.Response r = Http.get(ref, null);
			List<String> resolved = extractRefs(r.finalUrl + ' ' + r.body);
			if (resolved.isEmpty() || resolved.get(0).startsWith("http")) {
				throw new IOException("Unsupported Spotify link: " + ref);
			}
			ref = resolved.get(0);
		}

		Http.Response r = Http.get("https://open.spotify.com/embed/" + ref, null);
		if (!r.isOk()) throw new IOException("Spotify returned HTTP " + r.code);

		JSONObject entity = findEntity(r.body);
		if (entity == null) throw new IOException("Playlist not found or not public");

		pl.name = firstNonEmpty(entity.optString("name"), entity.optString("title"), ref);
		pl.owner = emptyToNull(normalize(entity.optString("subtitle")));
		pl.coverUrl = findCover(entity);
		pl.tracks.clear();

		JSONArray list = entity.optJSONArray("trackList");
		if (list == null) return;

		for (int i = 0; i < list.length(); i++) {
			JSONObject t = list.optJSONObject(i);
			if (t == null) continue;
			String title = normalize(t.optString("title"));
			if (title.isEmpty()) continue;
			String artists = normalize(t.optString("subtitle"));
			long duration = t.optLong("duration", -1);
			pl.tracks.add(new Track(title, artists, duration));
		}
	}

	@Nullable
	private static JSONObject findEntity(String html) {
		Matcher m = JSON_SCRIPT.matcher(html);

		while (m.find()) {
			try {
				JSONObject e = findWithTrackList(new JSONObject(m.group(1)), 0);
				if (e != null) return e;
			} catch (JSONException ignore) {
			}
		}

		return null;
	}

	/** Depth-first search for the object carrying the {@code trackList} array. */
	@Nullable
	private static JSONObject findWithTrackList(Object o, int depth) {
		if (depth > 12) return null;

		if (o instanceof JSONObject) {
			JSONObject j = (JSONObject) o;
			if (j.optJSONArray("trackList") != null) return j;

			for (Iterator<String> it = j.keys(); it.hasNext(); ) {
				JSONObject r = findWithTrackList(j.opt(it.next()), depth + 1);
				if (r != null) return r;
			}
		} else if (o instanceof JSONArray) {
			JSONArray a = (JSONArray) o;
			for (int i = 0; i < a.length(); i++) {
				JSONObject r = findWithTrackList(a.opt(i), depth + 1);
				if (r != null) return r;
			}
		}

		return null;
	}

	@Nullable
	private static String findCover(JSONObject entity) {
		JSONObject cover = entity.optJSONObject("coverArt");
		String url = (cover != null) ? largest(cover.optJSONArray("sources"), "width") : null;
		if (url != null) return url;
		JSONObject vi = entity.optJSONObject("visualIdentity");
		return (vi != null) ? largest(vi.optJSONArray("image"), "maxWidth") : null;
	}

	@Nullable
	private static String largest(@Nullable JSONArray images, String widthKey) {
		if (images == null) return null;
		String url = null;
		int best = -1;

		for (int i = 0; i < images.length(); i++) {
			JSONObject img = images.optJSONObject(i);
			if (img == null) continue;
			String u = img.optString("url");
			int w = img.optInt(widthKey, 0);
			if (!u.isEmpty() && (w > best)) {
				best = w;
				url = u;
			}
		}

		return url;
	}

	private static String normalize(@Nullable String s) {
		return (s == null) ? "" : s.replace(' ', ' ').trim();
	}

	@Nullable
	private static String emptyToNull(String s) {
		return s.isEmpty() ? null : s;
	}

	private static String firstNonEmpty(String... values) {
		for (String v : values) {
			if ((v != null) && !v.trim().isEmpty()) return v.trim();
		}
		return "";
	}
}
