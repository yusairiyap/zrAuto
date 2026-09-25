package me.aap.fermata.spotify;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import me.aap.fermata.spotify.SpotifyImportModel.Track;
import me.aap.fermata.spotify.SpotifyImportModel.Video;

/**
 * YouTube search without an API key.
 * <p>
 * The YouTube Data API is free but needs a key, and a search costs 100 of the default 10,000
 * daily quota units -- only ~100 searches a day, fewer than one mid-sized playlist. This instead
 * calls the same public "InnerTube" search endpoint youtube.com's own web page uses (no key, no
 * quota), falling back to parsing the regular results page if that request is rejected. Thumbnails
 * come straight from {@code i.ytimg.com} and cost nothing either.
 * <p>
 * All methods block -- call them on a background thread.
 */
public final class YoutubeSearch {
	private static final String CLIENT_VERSION = "2.20250101.00.00";
	/** InnerTube search filter: type = video. */
	private static final String VIDEOS_ONLY = "EgIQAQ==";
	private static final String[] UNWANTED = {
			"live", "cover", "karaoke", "instrumental", "remix", "reaction", "8d", "slowed", "sped up",
			"nightcore", "tutorial", "lesson"
	};

	private YoutubeSearch() {
	}

	public static List<Video> search(String query, int max) throws IOException {
		List<Video> result;

		try {
			result = searchInnerTube(query, max);
		} catch (IOException | JSONException ex) {
			result = null;
		}

		if ((result == null) || result.isEmpty()) {
			try {
				result = searchHtml(query, max);
			} catch (JSONException ex) {
				throw new IOException("Unexpected YouTube response", ex);
			}
		}

		return result;
	}

	/**
	 * Searches for {@code t} and returns the results ordered best match first: YouTube's own
	 * relevance order, nudged towards videos whose length matches the Spotify track and away from
	 * live/cover/karaoke-style variants the track title doesn't itself ask for.
	 */
	public static List<Video> searchTrack(Track t, int max) throws IOException {
		List<Video> list = search(t.searchQuery(), max);
		if (list.size() < 2) return list;

		Map<Video, Integer> score = new HashMap<>();
		String trackTitle = t.title.toLowerCase(Locale.ROOT);
		String artist = t.firstArtist().toLowerCase(Locale.ROOT);

		for (int i = 0; i < list.size(); i++) {
			Video v = list.get(i);
			String title = v.title.toLowerCase(Locale.ROOT);
			String channel = (v.channel == null) ? "" : v.channel.toLowerCase(Locale.ROOT);
			int s = -i * 2;

			if ((t.durationMs > 0) && (v.durationMs > 0)) {
				long diff = Math.abs(t.durationMs - v.durationMs);
				if (diff <= 5000) s += 6;
				else if (diff <= 15000) s += 3;
				else if (diff > 60000) s -= 4;
			}

			for (String w : UNWANTED) {
				if (title.contains(w) && !trackTitle.contains(w)) s -= 5;
			}

			if (title.contains(trackTitle)) s += 2;
			if (!artist.isEmpty() && (channel.contains(artist) || channel.endsWith("- topic"))) s += 3;
			score.put(v, s);
		}

		List<Video> sorted = new ArrayList<>(list);
		sorted.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
		return sorted;
	}

	private static List<Video> searchInnerTube(String query, int max)
			throws IOException, JSONException {
		JSONObject client = new JSONObject();
		client.put("clientName", "WEB");
		client.put("clientVersion", CLIENT_VERSION);
		client.put("hl", "en");
		JSONObject ctx = new JSONObject();
		ctx.put("client", client);
		JSONObject body = new JSONObject();
		body.put("context", ctx);
		body.put("query", query);
		body.put("params", VIDEOS_ONLY);

		Map<String, String> headers = new HashMap<>();
		headers.put("X-YouTube-Client-Name", "1");
		headers.put("X-YouTube-Client-Version", CLIENT_VERSION);
		headers.put("Origin", "https://www.youtube.com");
		headers.put("Cookie", "SOCS=CAI; CONSENT=YES+1");

		Http.Response r = Http.post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
				body.toString(), headers);
		if (!r.isOk()) throw new IOException("YouTube returned HTTP " + r.code);
		return collect(new JSONObject(r.body), max);
	}

	private static List<Video> searchHtml(String query, int max) throws IOException, JSONException {
		Map<String, String> headers = new HashMap<>();
		headers.put("Cookie", "SOCS=CAI; CONSENT=YES+1");
		Http.Response r = Http.get("https://www.youtube.com/results?sp=EgIQAQ%253D%253D&search_query=" +
				URLEncoder.encode(query, "UTF-8"), headers);
		if (!r.isOk()) throw new IOException("YouTube returned HTTP " + r.code);

		String html = r.body;
		int idx = html.indexOf("ytInitialData");
		if (idx == -1) throw new IOException("Unexpected YouTube response");
		idx = html.indexOf('{', idx);
		if (idx == -1) throw new IOException("Unexpected YouTube response");
		int end = html.indexOf(";</script>", idx);
		if (end == -1) throw new IOException("Unexpected YouTube response");
		return collect(new JSONObject(html.substring(idx, end)), max);
	}

	private static List<Video> collect(JSONObject root, int max) {
		List<Video> out = new ArrayList<>();
		collect(root, out, new HashSet<>(), max, 0);
		return out;
	}

	private static void collect(Object o, List<Video> out, Set<String> seen, int max, int depth) {
		if ((out.size() >= max) || (depth > 40)) return;

		if (o instanceof JSONObject) {
			JSONObject j = (JSONObject) o;
			JSONObject vr = j.optJSONObject("videoRenderer");

			if (vr != null) {
				Video v = parseVideoRenderer(vr);
				if ((v != null) && seen.add(v.videoId)) out.add(v);
				return;
			}

			for (Iterator<String> it = j.keys(); it.hasNext(); ) {
				collect(j.opt(it.next()), out, seen, max, depth + 1);
			}
		} else if (o instanceof JSONArray) {
			JSONArray a = (JSONArray) o;
			for (int i = 0; (i < a.length()) && (out.size() < max); i++) {
				collect(a.opt(i), out, seen, max, depth + 1);
			}
		}
	}

	@Nullable
	private static Video parseVideoRenderer(JSONObject vr) {
		String id = vr.optString("videoId");
		if (id.isEmpty()) return null;
		String title = text(vr.optJSONObject("title"));
		if (title.isEmpty()) return null;
		String channel = text(vr.optJSONObject("ownerText"));
		if (channel.isEmpty()) channel = text(vr.optJSONObject("longBylineText"));
		String len = text(vr.optJSONObject("lengthText"));
		return new Video(id, title, channel.isEmpty() ? null : channel, len.isEmpty() ? null : len,
				parseDuration(len));
	}

	/** Text of a {@code {"simpleText": ...}} or {@code {"runs": [{"text": ...}, ...]}} object. */
	private static String text(@Nullable JSONObject o) {
		if (o == null) return "";
		String s = o.optString("simpleText");
		if (!s.isEmpty()) return s;
		JSONArray runs = o.optJSONArray("runs");
		if (runs == null) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < runs.length(); i++) {
			JSONObject r = runs.optJSONObject(i);
			if (r != null) sb.append(r.optString("text"));
		}
		return sb.toString();
	}

	/** "4:05" / "1:02:03" to millis, -1 if not parseable. */
	private static long parseDuration(String s) {
		if (s.isEmpty()) return -1;
		long total = 0;

		for (String p : s.split(":")) {
			try {
				total = total * 60 + Integer.parseInt(p.trim());
			} catch (NumberFormatException ex) {
				return -1;
			}
		}

		return total * 1000;
	}
}
