package me.aap.fermata.addon.web.yt;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.addon.web.PrivateProfile;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.function.Consumer;
import me.aap.utils.log.Log;

/**
 * Reads the videos YouTube suggests to the user -- the ones on its home feed -- by loading the
 * mobile home page in a WebView nobody sees. The WebView shares its cookies with the YouTube tab
 * (or with its Private Mode profile, while that's on), so a signed-in user gets their own feed and
 * anyone else gets YouTube's general one; there is no API key or extra login involved.
 * <p>
 * The videos are read straight out of the page's own data ({@code ytInitialData}), falling back to
 * the links on the rendered page, and the page is scrolled a few times when it has fewer videos
 * than asked for. Must be used on the main thread.
 */
final class YoutubeFeed {
	static final class Video {
		final String id;
		final String title;
		@Nullable
		final String channel;

		Video(String id, String title, @Nullable String channel) {
			this.id = id;
			this.title = title;
			this.channel = channel;
		}

		/** The frame YouTube itself shows for the video, 16:9 without any bars. */
		String thumbnailUrl() {
			return "https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg";
		}

		String fallbackThumbnailUrl() {
			return "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
		}
	}

	interface Callback {
		/** The feed's videos, in the order YouTube listed them; empty if it couldn't be read. */
		void onFeed(List<Video> videos);
	}

	private static final String HOME = "https://m.youtube.com/";
	private static final int MAX_ATTEMPTS = 6;
	private static final long ATTEMPT_MS = 1400;
	private static final long TIMEOUT_MS = 30000;

	/**
	 * Collects video ids and titles from the page's data, or, if that isn't there, from its links.
	 * Returns them as a JSON array of {id, title, channel}.
	 */
	private static final String SCRAPE_JS = """
			(function() {
			  var out = [], seen = {};
			  function txt(o) {
			    if (!o) return '';
			    if (typeof o === 'string') return o;
			    if (o.simpleText) return o.simpleText;
			    if (o.content) return o.content;
			    if (o.runs) return o.runs.map(function(r) { return r.text; }).join('');
			    return '';
			  }
			  function add(id, title, channel) {
			    if (!id || !/^[\\w-]{11}$/.test(id) || seen[id]) return;
			    title = (title || '').replace(/\\s+/g, ' ').trim();
			    if (title.length < 2) return;
			    seen[id] = 1;
			    out.push({id: id, title: title, channel: (channel || '').trim()});
			  }
			  function walk(o, d) {
			    if (!o || d > 60 || typeof o !== 'object') return;
			    if (Array.isArray(o)) {
			      for (var i = 0; i < o.length; i++) walk(o[i], d + 1);
			      return;
			    }
			    var vr = o.videoRenderer || o.videoWithContextRenderer || o.compactVideoRenderer ||
			        o.gridVideoRenderer;
			    if (vr && vr.videoId) {
			      add(vr.videoId, txt(vr.title) || txt(vr.headline),
			          txt(vr.ownerText) || txt(vr.shortBylineText) || txt(vr.longBylineText));
			      return;
			    }
			    var l = o.lockupViewModel;
			    if (l && l.contentId && l.contentType === 'LOCKUP_CONTENT_TYPE_VIDEO') {
			      var md = l.metadata && l.metadata.lockupMetadataViewModel;
			      var ch = '';
			      try {
			        ch = txt(md.metadata.contentMetadataViewModel.metadataRows[0].metadataParts[0].text);
			      } catch (e) {}
			      add(l.contentId, md ? txt(md.title) : '', ch);
			      return;
			    }
			    for (var k in o) walk(o[k], d + 1);
			  }
			  try { walk(window.ytInitialData, 0); } catch (e) {}
			  try {
			    var links = document.querySelectorAll('a[href*="/watch?v="]');
			    for (var i = 0; i < links.length; i++) {
			      var m = /[?&]v=([\\w-]{11})/.exec(links[i].getAttribute('href') || '');
			      if (!m) continue;
			      add(m[1], links[i].getAttribute('aria-label') || links[i].getAttribute('title') ||
			          links[i].textContent, '');
			    }
			  } catch (e) {}
			  return JSON.stringify(out);
			})()""";

	private final Context ctx;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Map<String, Video> found = new LinkedHashMap<>();
	@Nullable
	private WebView web;
	@Nullable
	private Callback callback;
	private int max;
	private int attempt;
	private boolean probing;

	YoutubeFeed(Context ctx) {
		this.ctx = ctx;
	}

	boolean isRunning() {
		return web != null;
	}

	/** Starts reading the feed; {@code cb} gets the first {@code max} videos, once. */
	@SuppressLint("SetJavaScriptEnabled")
	void start(int max, Callback cb) {
		cancel();
		this.max = max;
		this.callback = cb;
		found.clear();
		attempt = 0;
		probing = false;

		WebView w;
		try {
			w = new WebView(ctx);
			if (PrivateProfile.isSupported()) {
				WebViewCompat.setProfile(w, PrivateProfile.currentName(MainActivityPrefs.get()));
			}
		} catch (Throwable ex) {
			// No (or a broken) WebView provider on this device.
			Log.e(ex, "Failed to create the feed WebView");
			finish();
			return;
		}
		web = w;
		WebSettings s = w.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setBlockNetworkImage(true);
		s.setMediaPlaybackRequiresUserGesture(true);
		// A detached WebView is laid out at nothing at all, which the page's own scripts can take as
		// a reason to render nothing.
		w.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
				android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY));
		w.layout(0, 0, 1080, 1920);
		w.setWebViewClient(new WebViewClient() {
			@Override
			public void onPageFinished(WebView view, String url) {
				if ((url == null) || probing) return;
				if (url.contains("consent.")) {
					// The EU consent page: nothing to read until it's been answered on the YouTube tab.
					finish();
					return;
				}
				if (url.contains("youtube.com")) {
					probing = true;
					main.postDelayed(YoutubeFeed.this::probe, 700);
				}
			}

			@Override
			public void onReceivedError(WebView view, WebResourceRequest request,
																	android.webkit.WebResourceError error) {
				if (request.isForMainFrame()) finish();
			}
		});
		main.postDelayed(this::finish, TIMEOUT_MS);
		w.loadUrl(HOME);
	}

	private void probe() {
		WebView w = web;
		if (w == null) return;
		w.evaluateJavascript(SCRAPE_JS, value -> {
			if (web == null) return;
			merge(value);
			if ((found.size() >= max) || (++attempt >= MAX_ATTEMPTS)) {
				finish();
				return;
			}
			// More of the feed loads as the page is scrolled.
			web.evaluateJavascript("window.scrollBy(0, document.documentElement.clientHeight * 3)", null);
			main.postDelayed(this::probe, ATTEMPT_MS);
		});
	}

	private void merge(@Nullable String value) {
		if ((value == null) || value.equals("null")) return;
		try {
			// evaluateJavascript() hands back the string result as a quoted, escaped JSON string.
			Object o = new JSONTokener(value).nextValue();
			JSONArray a = new JSONArray(o.toString());
			for (int i = 0; i < a.length(); i++) {
				JSONObject j = a.getJSONObject(i);
				String id = j.optString("id");
				String title = j.optString("title");
				if (id.isEmpty() || title.isEmpty() || found.containsKey(id)) continue;
				String ch = j.optString("channel");
				found.put(id, new Video(id, title, ch.isEmpty() ? null : ch));
			}
		} catch (Exception ex) {
			Log.e(ex, "Failed to read the YouTube feed");
		}
	}

	private void finish() {
		main.removeCallbacksAndMessages(null);
		WebView w = web;
		web = null;
		if (w != null) {
			w.stopLoading();
			w.destroy();
		}
		Callback cb = callback;
		callback = null;
		if (cb == null) return;
		List<Video> l = new ArrayList<>(found.values());
		cb.onFeed((l.size() > max) ? new ArrayList<>(l.subList(0, max)) : l);
	}

	/** Abandons a read in progress: the callback is not called. */
	void cancel() {
		callback = null;
		main.removeCallbacksAndMessages(null);
		WebView w = web;
		web = null;
		if (w != null) {
			w.stopLoading();
			w.destroy();
		}
	}

	// ---------------------------------------------------------------- thumbnails

	private static final ExecutorService io = Executors.newFixedThreadPool(3);
	private static final Handler mainHandler = new Handler(Looper.getMainLooper());
	private static final LruCache<String, Bitmap> thumbs = new LruCache<>(24 * 1024 * 1024) {
		@Override
		protected int sizeOf(String key, Bitmap b) {
			return b.getByteCount();
		}
	};

	@Nullable
	static Bitmap cachedThumbnail(Video v) {
		return thumbs.get(v.id);
	}

	/**
	 * Loads {@code v}'s thumbnail off the main thread, and hands it to {@code cb} on it: null if it
	 * couldn't be loaded. The full-size frame first, YouTube's smaller one for a video without.
	 */
	static void loadThumbnail(@NonNull Video v, Consumer<Bitmap> cb) {
		Bitmap b = thumbs.get(v.id);
		if (b != null) {
			cb.accept(b);
			return;
		}
		io.execute(() -> {
			Bitmap bm = download(v.thumbnailUrl());
			if (bm == null) bm = download(v.fallbackThumbnailUrl());
			Bitmap res = bm;
			if (res != null) thumbs.put(v.id, res);
			mainHandler.post(() -> cb.accept(res));
		});
	}

	@Nullable
	private static Bitmap download(String url) {
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL(url).openConnection();
			c.setConnectTimeout(8000);
			c.setReadTimeout(10000);
			if (c.getResponseCode() != 200) return null;
			try (InputStream in = c.getInputStream()) {
				BitmapFactory.Options o = new BitmapFactory.Options();
				// Half size, and no alpha: a card is never bigger than that, and many are kept at once.
				o.inSampleSize = 2;
				o.inPreferredConfig = Bitmap.Config.RGB_565;
				return BitmapFactory.decodeStream(in, null, o);
			}
		} catch (Throwable ex) {
			return null;
		} finally {
			if (c != null) c.disconnect();
		}
	}
}
