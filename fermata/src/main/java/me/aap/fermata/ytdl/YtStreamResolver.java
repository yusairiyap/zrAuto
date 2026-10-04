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
 * talks to YouTube ({@code YoutubeSearch}): the {@code player} endpoint, asked as one of YouTube's
 * own apps, answers with plain (not signature-ciphered) stream URLs that can be fetched straight
 * away.
 * <p>
 * YouTube answers "Sign in to confirm you're not a bot" to a client it has decided to distrust,
 * for a while or for some videos only -- so the apps are tried one after the other (the VR app,
 * the TV embedded player, the iPhone app) until one of them is let through. The visitor id YouTube
 * hands out is kept and sent with the next request, as a real app's session would.
 * <p>
 * YouTube changes what this endpoint accepts from time to time; when downloads suddenly start
 * failing with "no downloadable stream", the client versions below are the first thing to look at.
 * <p>
 * All methods block -- call them on a background thread.
 */
final class YtStreamResolver {
	private static final String PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
	private static volatile String visitorData;

	private YtStreamResolver() {
	}

	/** The way one of YouTube's apps introduces itself. */
	private static final class Client {
		final String name;
		final String version;
		final String headerId;
		final String userAgent;
		final String extra;

		Client(String name, String version, String headerId, String userAgent, String extra) {
			this.name = name;
			this.version = version;
			this.headerId = headerId;
			this.userAgent = userAgent;
			this.extra = extra;
		}
	}

	private static final Client[] CLIENTS = {
			new Client("ANDROID_VR", "1.62.27", "28",
					"com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; " +
							"eureka-user Build/SQ3A.220605.009.A1) gzip",
					"\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\",\"osName\":\"Android\"," +
							"\"osVersion\":\"12L\",\"androidSdkVersion\":32"),
			new Client("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "85",
					"Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
							"Version/15.4 Safari/605.1.15",
					"\"clientScreen\":\"EMBED\""),
			new Client("IOS", "20.10.4", "5",
					"com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
					"\"deviceMake\":\"Apple\",\"deviceModel\":\"iPhone16,2\",\"osName\":\"iPhone\"," +
							"\"osVersion\":\"18.3.2.22D82\""),
	};

	static final int CLIENT_COUNT = CLIENTS.length;

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
		/** What the streams' server expects to be told. */
		String userAgent;
		/** Which of the apps answered, an index into the list tried in order. */
		int client;
	}

	/** YouTube refused to talk to us ("Sign in to confirm you're not a bot"): trying again soon won't help. */
	static final class BlockedException extends IOException {
		BlockedException(String msg) {
			super(msg);
		}
	}

	/**
	 * @param wantVideo whether a picture is wanted as well
	 * @param maxHeight the tallest picture to take (lines)
	 */
	static Result resolve(String videoId, boolean wantVideo, int maxHeight, int firstClient)
			throws IOException {
		IOException first = null;
		boolean blocked = false;

		for (int i = firstClient; i < CLIENTS.length; i++) {
			Client c = CLIENTS[i];
			try {
				Result r = resolve(c, videoId, wantVideo, maxHeight);
				r.client = i;
				return r;
			} catch (BlockedException ex) {
				blocked = true;
				if (first == null) first = ex;
			} catch (IOException ex) {
				if (first == null) first = ex;
			}
		}

		if (blocked) {
			throw new BlockedException("YouTube asked to confirm you're not a bot. Try again later");
		}
		throw (first != null) ? first : new IOException("No downloadable stream");
	}

	private static Result resolve(Client c, String videoId, boolean wantVideo, int maxHeight)
			throws IOException {
		try {
			JSONObject resp = new JSONObject(post(c, videoId));
			JSONObject rc = resp.optJSONObject("responseContext");
			String vd0 = (rc != null) ? rc.optString("visitorData", null) : null;
			if ((vd0 != null) && !vd0.isEmpty()) visitorData = vd0;

			JSONObject ps = resp.optJSONObject("playabilityStatus");
			String status = (ps != null) ? ps.optString("status") : "";
			if (!"OK".equals(status)) {
				String reason = (ps != null) ? ps.optString("reason") : "";
				if (reason.toLowerCase().contains("not a bot") || reason.toLowerCase().contains("sign in")) {
					throw new BlockedException(reason);
				}
				throw new IOException(reason.isEmpty() ? ("Not playable: " + status) : reason);
			}

			JSONObject sd = resp.optJSONObject("streamingData");
			JSONArray formats = (sd != null) ? sd.optJSONArray("adaptiveFormats") : null;
			if (formats == null) throw new IOException("No downloadable stream");

			Result r = new Result();
			r.userAgent = c.userAgent;
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

	private static String post(Client c, String videoId) throws IOException, JSONException {
		JSONObject client = new JSONObject("{" + c.extra + "}")
				.put("clientName", c.name)
				.put("clientVersion", c.version)
				.put("hl", "en");
		String vd = visitorData;
		if (vd != null) client.put("visitorData", vd);

		JSONObject context = new JSONObject().put("client", client);
		if ("TVHTML5_SIMPLY_EMBEDDED_PLAYER".equals(c.name)) {
			context.put("thirdParty", new JSONObject()
					.put("embedUrl", "https://www.youtube.com/watch?v=" + videoId));
		}
		JSONObject body = new JSONObject()
				.put("context", context)
				.put("videoId", videoId)
				.put("contentCheckOk", true)
				.put("racyCheckOk", true);

		HttpURLConnection h = (HttpURLConnection) new URL(PLAYER_URL).openConnection();
		try {
			h.setConnectTimeout(15_000);
			h.setReadTimeout(20_000);
			h.setRequestMethod("POST");
			h.setDoOutput(true);
			h.setRequestProperty("Content-Type", "application/json");
			h.setRequestProperty("User-Agent", c.userAgent);
			h.setRequestProperty("X-YouTube-Client-Name", c.headerId);
			h.setRequestProperty("X-YouTube-Client-Version", c.version);
			if (vd != null) h.setRequestProperty("X-Goog-Visitor-Id", vd);
			try (OutputStream out = h.getOutputStream()) {
				out.write(body.toString().getBytes(StandardCharsets.UTF_8));
			}

			int code = h.getResponseCode();
			if (code != 200) throw new IOException("YouTube answered HTTP " + code);
			try (InputStream in = h.getInputStream()) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
				byte[] buf = new byte[16 * 1024];
				for (int n; (n = in.read(buf)) != -1; ) bos.write(buf, 0, n);
				return new String(bos.toByteArray(), StandardCharsets.UTF_8);
			}
		} finally {
			h.disconnect();
		}
	}

	/** The request headers a stream URL expects. */
	static void applyStreamHeaders(HttpURLConnection c, String userAgent) {
		c.setRequestProperty("User-Agent", userAgent);
	}
}
