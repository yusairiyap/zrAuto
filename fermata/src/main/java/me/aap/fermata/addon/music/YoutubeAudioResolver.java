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
	// Neutral UA for the verification probe: the engines that later stream the URL (MediaPlayer,
	// ExoPlayer, VLC) don't send the InnerTube client's UA either, so a URL that only works with it
	// must fail the probe too.
	private static final String PROBE_UA =
			"Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
					"Chrome/128.0.0.0 Mobile Safari/537.36";
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
			new Client("ANDROID", "19.44.38", 3,
					"com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
					null, null, "Android", "11", 30),
	};

	private YoutubeAudioResolver() {
	}

	static final class Stream {
		final String url;
		final String mimeType;
		final long expiresAt;
		@Nullable
		final String title;
		@Nullable
		final String author;
		final long durationMs;

		Stream(String url, String mimeType, long expiresAt, @Nullable String title,
					 @Nullable String author, long durationMs) {
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

	static Stream resolve(String videoId) throws IOException {
		IOException last = null;

		for (Client c : CLIENTS) {
			try {
				Stream s = resolve(videoId, c);
				if (s != null) return s;
			} catch (IOException ex) {
				Log.d(ex, "YouTube audio: client ", c.name, " failed for ", videoId);
				last = ex;
			} catch (JSONException ex) {
				Log.d(ex, "YouTube audio: unexpected response from client ", c.name);
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

		JSONObject body = new JSONObject();
		body.put("context", new JSONObject().put("client", client));
		body.put("videoId", videoId);
		body.put("contentCheckOk", true);
		body.put("racyCheckOk", true);
		body.put("playbackContext", new JSONObject().put("contentPlaybackContext",
				new JSONObject().put("html5Preference", "HTML5_PREF_WANTS")));

		String resp = post(c, body.toString());
		JSONObject r = new JSONObject(resp);
		JSONObject status = r.optJSONObject("playabilityStatus");
		String st = (status == null) ? null : status.optString("status");

		if (!"OK".equals(st)) {
			Log.d("YouTube audio: client ", c.name, " status ", st, ": ",
					(status == null) ? null : status.optString("reason"));
			return null;
		}

		JSONObject sd = r.optJSONObject("streamingData");
		JSONArray formats = (sd == null) ? null : sd.optJSONArray("adaptiveFormats");
		if (formats == null) return null;

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

		if (best == null) return null;

		String url = best.getString("url");
		if (!probe(url)) {
			Log.d("YouTube audio: stream from client ", c.name, " was refused, trying the next one");
			return null;
		}

		JSONObject details = r.optJSONObject("videoDetails");
		String title = (details == null) ? null : emptyToNull(details.optString("title"));
		String author = (details == null) ? null : emptyToNull(details.optString("author"));
		long dur = best.optLong("approxDurationMs", 0);
		if ((dur <= 0) && (details != null)) dur = details.optLong("lengthSeconds", 0) * 1000;

		return new Stream(url, best.optString("mimeType"), expiresAt(url), title, author, dur);
	}

	private static long expiresAt(String url) {
		try {
			String exp = Uri.parse(url).getQueryParameter("expire");
			if (exp != null) return Long.parseLong(exp) * 1000;
		} catch (Exception ignore) {
		}
		return System.currentTimeMillis() + DEFAULT_TTL;
	}

	@Nullable
	private static String emptyToNull(String s) {
		return ((s == null) || s.isEmpty()) ? null : s;
	}

	private static String post(Client c, String json) throws IOException {
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

	/** Fetches the first two bytes of the stream -- enough to know the servers will serve it. */
	private static boolean probe(String url) {
		HttpURLConnection con = null;

		try {
			con = (HttpURLConnection) new URL(url).openConnection();
			con.setConnectTimeout(TIMEOUT);
			con.setReadTimeout(TIMEOUT);
			con.setInstanceFollowRedirects(true);
			con.setRequestProperty("User-Agent", PROBE_UA);
			con.setRequestProperty("Range", "bytes=0-1");
			int code = con.getResponseCode();
			return (code == 200) || (code == 206);
		} catch (IOException ex) {
			Log.d(ex, "YouTube audio: probe failed");
			return false;
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
