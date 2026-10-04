package me.aap.fermata.ytdl;

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
import java.nio.charset.StandardCharsets;

/**
 * Finds the media streams of a YouTube video, the same key-less "InnerTube" way the rest of the app
 * talks to YouTube ({@code YoutubeSearch}): the {@code player} endpoint, asked as the YouTube VR
 * app, answers with plain (not signature-ciphered) stream URLs that can be fetched straight away.
 * <p>
 * YouTube changes what this endpoint accepts from time to time; when downloads suddenly start
 * failing with "no downloadable stream", {@link #CLIENT_VERSION} is the first thing to look at.
 * <p>
 * All methods block -- call them on a background thread.
 */
final class YtStreamResolver {
	static final String CLIENT_VERSION = "1.62.27";
	private static final String PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
	private static final String USER_AGENT = "com.google.android.apps.youtube.vr.oculus/" +
			CLIENT_VERSION + " (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

	private YtStreamResolver() {
	}

	/** One downloadable stream. */
	static final class Stream {
		final String url;
		final long length;
		final int height;
		final int bitrate;

		Stream(String url, long length, int height, int bitrate) {
			this.url = url;
			this.length = length;
			this.height = height;
			this.bitrate = bitrate;
		}
	}

	static final class Result {
		@Nullable
		String title;
		@Nullable
		String author;
		long durationMs;
		/** AAC in an MP4 container ({@code .m4a}). */
		Stream audio;
		/** H.264 in an MP4 container, no sound; null when only audio was asked for. */
		@Nullable
		Stream video;
	}

	/**
	 * @param wantVideo whether a picture is wanted as well
	 * @param maxHeight the tallest picture to take (lines)
	 */
	static Result resolve(String videoId, boolean wantVideo, int maxHeight) throws IOException {
		try {
			JSONObject resp = new JSONObject(post(videoId));
			JSONObject ps = resp.optJSONObject("playabilityStatus");
			String status = (ps != null) ? ps.optString("status") : "";
			if (!"OK".equals(status)) {
				String reason = (ps != null) ? ps.optString("reason") : "";
				throw new IOException(reason.isEmpty() ? ("Not playable: " + status) : reason);
			}

			JSONObject sd = resp.optJSONObject("streamingData");
			JSONArray formats = (sd != null) ? sd.optJSONArray("adaptiveFormats") : null;
			if (formats == null) throw new IOException("No downloadable stream");

			Result r = new Result();
			JSONObject vd = resp.optJSONObject("videoDetails");
			if (vd != null) {
				r.title = vd.optString("title", null);
				r.author = vd.optString("author", null);
				r.durationMs = vd.optLong("lengthSeconds", 0) * 1000;
			}

			for (int i = 0; i < formats.length(); i++) {
				JSONObject f = formats.optJSONObject(i);
				if (f == null) continue;
				String url = f.optString("url", null);
				long len = f.optLong("contentLength", 0);
				if ((url == null) || (len <= 0)) continue;
				String mime = f.optString("mimeType");
				int bitrate = f.optInt("bitrate", 0);
				int height = f.optInt("height", 0);

				if (mime.startsWith("audio/mp4")) {
					if ((r.audio == null) || (bitrate > r.audio.bitrate)) {
						r.audio = new Stream(url, len, 0, bitrate);
					}
				} else if (wantVideo && mime.startsWith("video/mp4") && mime.contains("avc1") &&
						(height > 0) && (height <= maxHeight)) {
					Stream v = r.video;
					if ((v == null) || (height > v.height) ||
							((height == v.height) && (bitrate > v.bitrate))) {
						r.video = new Stream(url, len, height, bitrate);
					}
				}
			}

			if (r.audio == null) throw new IOException("No downloadable stream");
			if (wantVideo && (r.video == null)) throw new IOException("No downloadable video stream");
			return r;
		} catch (JSONException ex) {
			throw new IOException("Unexpected YouTube response", ex);
		}
	}

	private static String post(String videoId) throws IOException, JSONException {
		JSONObject client = new JSONObject()
				.put("clientName", "ANDROID_VR")
				.put("clientVersion", CLIENT_VERSION)
				.put("deviceMake", "Oculus")
				.put("deviceModel", "Quest 3")
				.put("osName", "Android")
				.put("osVersion", "12L")
				.put("androidSdkVersion", 32)
				.put("hl", "en");
		JSONObject body = new JSONObject()
				.put("context", new JSONObject().put("client", client))
				.put("videoId", videoId)
				.put("contentCheckOk", true)
				.put("racyCheckOk", true);

		HttpURLConnection c = (HttpURLConnection) new URL(PLAYER_URL).openConnection();
		try {
			c.setConnectTimeout(15_000);
			c.setReadTimeout(20_000);
			c.setRequestMethod("POST");
			c.setDoOutput(true);
			c.setRequestProperty("Content-Type", "application/json");
			c.setRequestProperty("User-Agent", USER_AGENT);
			c.setRequestProperty("X-YouTube-Client-Name", "28");
			c.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
			try (OutputStream out = c.getOutputStream()) {
				out.write(body.toString().getBytes(StandardCharsets.UTF_8));
			}

			int code = c.getResponseCode();
			if (code != 200) throw new IOException("YouTube answered HTTP " + code);
			try (InputStream in = c.getInputStream()) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
				byte[] buf = new byte[16 * 1024];
				for (int n; (n = in.read(buf)) != -1; ) bos.write(buf, 0, n);
				return new String(bos.toByteArray(), StandardCharsets.UTF_8);
			}
		} finally {
			c.disconnect();
		}
	}

	/** The request headers a stream URL expects. */
	static void applyStreamHeaders(HttpURLConnection c) {
		c.setRequestProperty("User-Agent", USER_AGENT);
	}
}
