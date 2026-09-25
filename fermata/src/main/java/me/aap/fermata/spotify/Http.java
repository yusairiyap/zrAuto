package me.aap.fermata.spotify;

import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Minimal blocking HTTP helper for the Spotify import. Deliberately plain
 * {@link HttpURLConnection} rather than {@code me.aap.utils.net.http.HttpConnection}: it needs
 * POST bodies, redirects (Spotify short links) and gzip, all of which HttpURLConnection handles
 * out of the box. Every call must run on a background thread.
 */
final class Http {
	static final String USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
					"Chrome/128.0.0.0 Safari/537.36";
	private static final int TIMEOUT = 20000;
	private static final int MAX_BODY = 8 * 1024 * 1024;

	private Http() {
	}

	static Response get(String url, @Nullable Map<String, String> headers) throws IOException {
		return request(url, null, headers);
	}

	static Response post(String url, String jsonBody, @Nullable Map<String, String> headers)
			throws IOException {
		return request(url, jsonBody, headers);
	}

	private static Response request(String url, @Nullable String body,
																	@Nullable Map<String, String> headers) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();

		try {
			c.setConnectTimeout(TIMEOUT);
			c.setReadTimeout(TIMEOUT);
			c.setInstanceFollowRedirects(true);
			c.setRequestProperty("User-Agent", USER_AGENT);
			c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
			if (headers != null) {
				for (Map.Entry<String, String> e : headers.entrySet()) {
					c.setRequestProperty(e.getKey(), e.getValue());
				}
			}

			if (body != null) {
				byte[] bytes = body.getBytes(UTF_8);
				c.setRequestMethod("POST");
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				c.setFixedLengthStreamingMode(bytes.length);
				try (OutputStream out = c.getOutputStream()) {
					out.write(bytes);
				}
			}

			int code = c.getResponseCode();
			InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
			String text = (in == null) ? "" : readAll(in);
			return new Response(code, c.getURL().toString(), text);
		} finally {
			c.disconnect();
		}
	}

	private static String readAll(InputStream in) throws IOException {
		try (InputStream is = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
			byte[] buf = new byte[16 * 1024];

			for (int n; (n = is.read(buf)) != -1; ) {
				if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
				out.write(buf, 0, n);
				if (out.size() > MAX_BODY) throw new IOException("Response is too large");
			}

			return out.toString("UTF-8");
		}
	}

	static final class Response {
		final int code;
		final String finalUrl;
		final String body;

		Response(int code, String finalUrl, String body) {
			this.code = code;
			this.finalUrl = finalUrl;
			this.body = body;
		}

		boolean isOk() {
			return (code >= 200) && (code < 300);
		}
	}
}
