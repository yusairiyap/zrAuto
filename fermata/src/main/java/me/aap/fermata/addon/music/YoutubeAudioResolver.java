package me.aap.fermata.addon.music;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.log.Log;

/**
 * Resolves a YouTube video id to a direct, audio-only stream URL, so the Music tab can play
 * YouTube through the regular audio engines without loading (or decoding) a single video frame.
 * <p>
 * Uses the same public "InnerTube" {@code player} endpoint YouTube's own apps call, trying a few
 * client identities in turn (YouTube regularly changes which of them hand out plain, unsigned
 * stream URLs), and verifies the chosen URL with a tiny ranged request before returning it -- a
 * stream the servers would refuse is treated the same as no stream, so the next client is tried.
 * Only {@code adaptiveFormats} entries whose mime type is {@code audio/*} are ever considered.
 * <p>
 * All methods block -- call them on a background thread.
 */
final class YoutubeAudioResolver {
	private static final String PLAYER_URL =
			"https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
	private static final int TIMEOUT = 15000;
	private static final long DEFAULT_TTL = 5 * 3600_000L;

	private static final Client[] CLIENTS = {
			new Client("ANDROID_VR", "1.62.27", 28,
					"com.google.android.apps.youtube.vr.oculus/1.62.27 " +
							"(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
					"Oculus", "Quest 3", "Android", "12L", 32),
			new Client("IOS", "20.10.4", 5,
					"com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
					"Apple", "iPhone16,2", "iPhone", "18.3.2.22D82", 0),
			new Client("ANDROID", "20.10.38", 3,
					"com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
					null, null, "Android", "11", 30),
	};
	// Anonymous visitor id from YouTube's own home page, sent with every player request: requests
	// without one are the likeliest to get "Sign in to confirm you're not a bot".
	private static final long VISITOR_TTL = 30 * 60_000L;
	private static final Pattern VISITOR_DATA = Pattern.compile("\"VISITOR_DATA\":\"([^\"]+)\"");
	@Nullable
	private static String visitorData;
	private static long visitorDataTime;

	private YoutubeAudioResolver() {
	}

	static final class Stream {
		/** The InnerTube client the URL was issued to -- see {@link #resolve(String, Set)}. */
		final String client;
		/** That client's User-Agent: the stream is fetched with the same one it was issued to. */
		final String userAgent;
		final String url;
		final String mimeType;
		final long expiresAt;
		@Nullable
		final String title;
		@Nullable
		final String author;
		final long durationMs;

		Stream(String client, String userAgent, String url, String mimeType, long expiresAt,
					 @Nullable String title, @Nullable String author, long durationMs) {
			this.client = client;
			this.userAgent = userAgent;
			this.url = url;
			this.mimeType = mimeType;
			this.expiresAt = expiresAt;
			this.title = title;
			this.author = author;
			this.durationMs = durationMs;
		}

		/** Whether this URL can still be handed to an engine, with a minute of margin. */
		boolean isValid() {
			return System.currentTimeMillis() < (expiresAt - 60_000L);
		}
	}

	static int getClientCount() {
		return CLIENTS.length;
	}

	/**
	 * @param skipClients clients whose streams already failed to play for this video (an engine
	 *                    error, see {@code MusicTrackItem#invalidateSource}) -- tried last, not never,
	 *                    in case the failure was a one-off.
	 */
	static Stream resolve(String videoId, Set<String> skipClients) throws IOException {
		IOException last = null;
		List<Client> order = new ArrayList<>(CLIENTS.length);
		for (Client c : CLIENTS) if (!skipClients.contains(c.name)) order.add(c);
		for (Client c : CLIENTS) if (skipClients.contains(c.name)) order.add(c);

		for (Client c : order) {
			try {
				Stream s = resolve(videoId, c);
				if (s != null) return s;
			} catch (IOException ex) {
				Log.d(ex, "YouTube audio: client ", c.name, " failed for ", videoId);
				DiagnosticLog.log("MUSIC", "yt client failed", c.name, "id=" + videoId, ex);
				last = ex;
			} catch (JSONException ex) {
				Log.d(ex, "YouTube audio: unexpected response from client ", c.name);
				DiagnosticLog.log("MUSIC", "yt bad response", c.name, "id=" + videoId, ex);
				last = new IOException("Unexpected YouTube response", ex);
			}
		}

		throw (last != null) ? last : new IOException("No audio-only stream available");
	}

	@Nullable
	private static Stream resolve(String videoId, Client c) throws IOException, JSONException {
		JSONObject client = new JSONObject();
		client.put("clientName", c.name);
		client.put("clientVersion", c.version);
		client.put("userAgent", c.userAgent);
		if (c.deviceMake != null) client.put("deviceMake", c.deviceMake);
		if (c.deviceModel != null) client.put("deviceModel", c.deviceModel);
		client.put("osName", c.osName);
		client.put("osVersion", c.osVersion);
		if (c.sdk > 0) client.put("androidSdkVersion", c.sdk);
		client.put("hl", "en");
		client.put("gl", "US");
		client.put("timeZone", "UTC");
		client.put("utcOffsetMinutes", 0);
		String visitor = getVisitorData();
		if (visitor != null) client.put("visitorData", visitor);

		JSONObject body = new JSONObject();
		body.put("context", new JSONObject().put("client", client));
		body.put("videoId", videoId);
		body.put("contentCheckOk", true);
		body.put("racyCheckOk", true);
		body.put("playbackContext", new JSONObject().put("contentPlaybackContext",
				new JSONObject().put("html5Preference", "HTML5_PREF_WANTS")));

		String resp = post(c, body.toString(), visitor);
		JSONObject r = new JSONObject(resp);
		JSONObject status = r.optJSONObject("playabilityStatus");
		String st = (status == null) ? null : status.optString("status");

		if (!"OK".equals(st)) {
			String reason = (status == null) ? null : status.optString("reason");
			Log.d("YouTube audio: client ", c.name, " status ", st, ": ", reason);
			DiagnosticLog.log("MUSIC", "yt status", c.name, "id=" + videoId, st, reason);
			return null;
		}

		JSONObject details = r.optJSONObject("videoDetails");
		String title = (details == null) ? null : emptyToNull(details.optString("title"));
		String author = (details == null) ? null : emptyToNull(details.optString("author"));
		long dur = (details == null) ? 0 : details.optLong("lengthSeconds", 0) * 1000;
		JSONObject sd = r.optJSONObject("streamingData");
		if (sd == null) {
			DiagnosticLog.log("MUSIC", "yt no streaming data", c.name, "id=" + videoId);
			return null;
		}

		Stream s = resolveAdaptive(videoId, c, sd, title, author, dur);
		if (s != null) return s;

		// Some clients' direct stream URLs are refused without a token only YouTube's own apps can
		// make, while their HLS manifest -- which has separate, audio-only renditions -- is not.
		String hls = sd.optString("hlsManifestUrl");
		return hls.isEmpty() ? null : resolveHls(videoId, c, hls, title, author, dur);
	}

	@Nullable
	private static Stream resolveAdaptive(String videoId, Client c, JSONObject sd,
																				@Nullable String title, @Nullable String author,
																				long dur) {
		JSONArray formats = sd.optJSONArray("adaptiveFormats");
		if (formats == null) {
			DiagnosticLog.log("MUSIC", "yt no formats", c.name, "id=" + videoId);
			return null;
		}

		JSONObject best = null;
		long bestScore = Long.MIN_VALUE;

		for (int i = 0, n = formats.length(); i < n; i++) {
			JSONObject f = formats.optJSONObject(i);
			if (f == null) continue;
			String mime = f.optString("mimeType");
			// Only ever audio -- a video format here would defeat the whole point.
			if (!mime.startsWith("audio/")) continue;
			// Signature-ciphered URLs need the web player's JS to decipher -- skip them.
			if (f.optString("url").isEmpty()) continue;

			long score = f.optLong("bitrate", 0);
			// AAC in MP4 is universally supported by MediaPlayer, including older devices' seeking.
			if (mime.startsWith("audio/mp4")) score += 1_000_000_000L;
			// Dynamic-range-compressed duplicates sound flat; prefer the original.
			if (!f.optBoolean("isDrc", false)) score += 2_000_000_000L;
			// Multi-language videos: the original/default audio track first.
			JSONObject track = f.optJSONObject("audioTrack");
			if ((track == null) || track.optBoolean("audioIsDefault", false)) score += 4_000_000_000L;

			if (score > bestScore) {
				bestScore = score;
				best = f;
			}
		}

		if (best == null) {
			DiagnosticLog.log("MUSIC", "yt no plain audio url", c.name, "id=" + videoId,
					"formats=" + formats.length());
			return null;
		}

		String url = best.optString("url");
		String probe = probe(url, c.userAgent, best.optLong("contentLength", 0));
		DiagnosticLog.log("MUSIC", "yt stream", c.name, "id=" + videoId,
				"itag=" + best.optInt("itag"), "mime=" + best.optString("mimeType"),
				"bitrate=" + best.optLong("bitrate"), "probe=" + probe);
		if (!probe.startsWith("ok")) return null;

		long d = best.optLong("approxDurationMs", 0);
		return new Stream(c.name, c.userAgent, url, best.optString("mimeType"), expiresAt(url), title,
				author, (d > 0) ? d : dur);
	}

	/**
	 * Picks the audio-only rendition out of an HLS master playlist ({@code #EXT-X-MEDIA} with
	 * {@code TYPE=AUDIO}; the muxed video variants are never used) and checks that its first
	 * segment can actually be fetched.
	 */
	@Nullable
	private static Stream resolveHls(String videoId, Client c, String masterUrl,
																	 @Nullable String title, @Nullable String author, long dur) {
		try {
			String master = get(masterUrl, c.userAgent);
			String bestUri = null;
			int bestScore = Integer.MIN_VALUE;

			for (String line : master.split("\n")) {
				line = line.trim();
				if (!line.startsWith("#EXT-X-MEDIA:") || !line.contains("TYPE=AUDIO")) continue;
				String uri = attr(line, "URI");
				if (uri == null) continue;
				int score = 0;
				String group = attr(line, "GROUP-ID");
				// 234 is the higher-bitrate AAC rendition, 233 the lower one.
				if ("234".equals(group)) score += 10;
				if (line.contains("DEFAULT=YES")) score += 100;
				if (score > bestScore) {
					bestScore = score;
					bestUri = new URL(new URL(masterUrl), uri).toString();
				}
			}

			if (bestUri == null) {
				DiagnosticLog.log("MUSIC", "yt hls has no audio rendition", c.name, "id=" + videoId);
				return null;
			}

			String media = get(bestUri, c.userAgent);
			String segment = null;
			for (String line : media.split("\n")) {
				line = line.trim();
				if (!line.isEmpty() && !line.startsWith("#")) {
					segment = new URL(new URL(bestUri), line).toString();
					break;
				}
			}

			String probe = (segment == null) ? "no segments" : probeRange(segment, c.userAgent, 0);
			DiagnosticLog.log("MUSIC", "yt hls audio", c.name, "id=" + videoId, "probe=" + probe);
			if (!probe.startsWith("ok")) return null;
			return new Stream(c.name, c.userAgent, bestUri, "application/x-mpegURL",
					expiresAt(bestUri), title, author, dur);
		} catch (IOException ex) {
			DiagnosticLog.log("MUSIC", "yt hls failed", c.name, "id=" + videoId, ex);
			return null;
		}
	}

	@Nullable
	private static String attr(String line, String name) {
		Matcher m = Pattern.compile("(?:^|[:,])" + name + "=(\"([^\"]*)\"|([^,]*))").matcher(line);
		if (!m.find()) return null;
		return (m.group(2) != null) ? m.group(2) : m.group(3);
	}

	@Nullable
	private static synchronized String getVisitorData() {
		long now = System.currentTimeMillis();
		if ((visitorData != null) && (now - visitorDataTime < VISITOR_TTL)) return visitorData;

		try {
			String page = get("https://www.youtube.com/?hl=en&persist_hl=1",
					"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
							"Chrome/128.0.0.0 Safari/537.36");
			Matcher m = VISITOR_DATA.matcher(page);
			if (m.find()) {
				visitorData = m.group(1);
				visitorDataTime = now;
			} else {
				DiagnosticLog.log("MUSIC", "yt no visitor data on the home page");
			}
		} catch (IOException ex) {
			DiagnosticLog.log("MUSIC", "yt visitor data failed", ex);
		}

		return visitorData;
	}

	private static String get(String url, String userAgent) throws IOException {
		HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();

		try {
			con.setConnectTimeout(TIMEOUT);
			con.setReadTimeout(TIMEOUT);
			con.setInstanceFollowRedirects(true);
			con.setRequestProperty("User-Agent", userAgent);
			// Skips the EU cookie-consent interstitial, which has no visitor data in it.
			con.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+");
			int code = con.getResponseCode();
			if (code >= 400) throw new IOException("HTTP " + code);
			return read(con.getInputStream());
		} finally {
			con.disconnect();
		}
	}

	private static String read(InputStream is) throws IOException {
		try (InputStream in = is) {
			ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
			byte[] buf = new byte[16 * 1024];
			for (int n; (n = in.read(buf)) != -1; ) {
				out.write(buf, 0, n);
				if (out.size() > (4 * 1024 * 1024)) throw new IOException("Response is too large");
			}
			return out.toString("UTF-8");
		}
	}

	private static long expiresAt(String url) {
		try {
			String exp = Uri.parse(url).getQueryParameter("expire");
			if (exp != null) return Long.parseLong(exp) * 1000;
			Matcher m = Pattern.compile("/expire/(\\d+)").matcher(url);
			if (m.find()) return Long.parseLong(m.group(1)) * 1000;
		} catch (Exception ignore) {
		}
		return System.currentTimeMillis() + DEFAULT_TTL;
	}

	@Nullable
	private static String emptyToNull(String s) {
		return ((s == null) || s.isEmpty()) ? null : s;
	}

	private static String post(Client c, String json, @Nullable String visitor) throws IOException {
		HttpURLConnection con = (HttpURLConnection) new URL(PLAYER_URL).openConnection();

		try {
			byte[] bytes = json.getBytes(UTF_8);
			con.setConnectTimeout(TIMEOUT);
			con.setReadTimeout(TIMEOUT);
			con.setRequestMethod("POST");
			con.setDoOutput(true);
			con.setRequestProperty("Content-Type", "application/json");
			con.setRequestProperty("User-Agent", c.userAgent);
			con.setRequestProperty("X-YouTube-Client-Name", String.valueOf(c.id));
			con.setRequestProperty("X-YouTube-Client-Version", c.version);
			con.setRequestProperty("Origin", "https://www.youtube.com");
			if (visitor != null) con.setRequestProperty("X-Goog-Visitor-Id", visitor);
			con.setFixedLengthStreamingMode(bytes.length);
			try (OutputStream out = con.getOutputStream()) {
				out.write(bytes);
			}

			int code = con.getResponseCode();
			if (code >= 400) throw new IOException("HTTP " + code);

			try (InputStream in = con.getInputStream()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
				byte[] buf = new byte[16 * 1024];
				for (int n; (n = in.read(buf)) != -1; ) {
					out.write(buf, 0, n);
					if (out.size() > (4 * 1024 * 1024)) throw new IOException("Response is too large");
				}
				return out.toString("UTF-8");
			}
		} finally {
			con.disconnect();
		}
	}

	/**
	 * Fetches two bytes at the start of the stream and two in the middle, with the same User-Agent
	 * the engines will use -- some refusals only kick in past the first chunk. Returns "ok ..." or
	 * the reason it failed, for the diagnostic log.
	 */
	private static String probe(String url, String userAgent, long length) {
		String first = probeRange(url, userAgent, 0);
		if (!first.startsWith("ok") || (length < 1_000_000)) return first;
		String mid = probeRange(url, userAgent, length / 2);
		return mid.startsWith("ok") ? ("ok " + first.substring(2).trim() + ',' +
				mid.substring(2).trim()) : ("mid " + mid);
	}

	private static String probeRange(String url, String userAgent, long from) {
		HttpURLConnection con = null;

		try {
			con = (HttpURLConnection) new URL(url).openConnection();
			con.setConnectTimeout(TIMEOUT);
			con.setReadTimeout(TIMEOUT);
			con.setInstanceFollowRedirects(true);
			con.setRequestProperty("User-Agent", userAgent);
			con.setRequestProperty("Range", "bytes=" + from + '-' + (from + 1));
			int code = con.getResponseCode();
			return ((code == 200) || (code == 206)) ? ("ok " + code) : ("http " + code);
		} catch (IOException ex) {
			Log.d(ex, "YouTube audio: probe failed");
			return "error " + ex;
		} finally {
			if (con != null) con.disconnect();
		}
	}

	private static final class Client {
		final String name;
		final String version;
		final int id;
		final String userAgent;
		@Nullable
		final String deviceMake;
		@Nullable
		final String deviceModel;
		final String osName;
		final String osVersion;
		final int sdk;

		Client(String name, String version, int id, String userAgent, @Nullable String deviceMake,
					 @Nullable String deviceModel, String osName, String osVersion, int sdk) {
			this.name = name;
			this.version = version;
			this.id = id;
			this.userAgent = userAgent;
			this.deviceMake = deviceMake;
			this.deviceModel = deviceModel;
			this.osName = osName;
			this.osVersion = osVersion;
			this.sdk = sdk;
		}
	}
}
