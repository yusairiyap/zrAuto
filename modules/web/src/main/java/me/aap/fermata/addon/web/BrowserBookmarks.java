package me.aap.fermata.addon.web;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.util.LruCache;
import android.view.View;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.utils.function.Consumer;
import me.aap.utils.log.Log;

/**
 * The bookmark cards of the browser's home page: the ordered bookmark list kept by
 * {@link WebBrowserAddon}, plus a screenshot of each site to show on its card. The screenshots are
 * small JPEGs of the top of the page, taken when a bookmarked site finishes loading (or when the
 * bookmark is added from the page), and cached in memory while the home page is on screen.
 */
final class BrowserBookmarks {
	/** The width the screenshots are saved at: plenty for a card, small enough to keep many. */
	private static final int THUMB_SIZE = 512;
	private static final ExecutorService io = Executors.newSingleThreadExecutor();
	private static final LruCache<String, Bitmap> cache = new LruCache<>(24);
	private static final android.os.Handler main = new android.os.Handler(
			android.os.Looper.getMainLooper());

	static final class Item {
		final String url;
		final String name;

		Item(String url, String name) {
			this.url = url;
			this.name = name;
		}

		/** "example.com" for "https://www.example.com/some/page". */
		String host() {
			return hostOf(url);
		}
	}

	private BrowserBookmarks() {
	}

	static List<Item> list(WebBrowserAddon addon) {
		List<Item> l = new ArrayList<>();
		for (Map.Entry<String, String> e : addon.getBookmarks().entrySet()) {
			l.add(new Item(e.getKey(), e.getValue()));
		}
		return l;
	}

	static boolean contains(WebBrowserAddon addon, @Nullable String url) {
		return (url != null) && addon.getBookmarks().containsKey(url);
	}

	static String hostOf(@Nullable String url) {
		if (url == null) return "";
		String h = Uri.parse(url).getHost();
		if (h == null) return url;
		return h.startsWith("www.") ? h.substring(4) : h;
	}

	/**
	 * Whether two addresses are the same page for a bookmark's sake: ignoring the scheme, "www.", a
	 * trailing slash and any fragment, so a bookmarked "google.com" matches where it redirected to.
	 */
	static boolean sameSite(String bookmark, String page) {
		return key(bookmark).equals(key(page));
	}

	private static String key(String url) {
		Uri u = Uri.parse(normalizeUrl(url));
		String path = (u.getPath() == null) ? "" : u.getPath();
		if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		String q = u.getQuery();
		return hostOf(u.toString()).toLowerCase(java.util.Locale.ROOT) + path + ((q == null) ? "" : "?" + q);
	}

	/** {@code url} with a scheme, so "example.com" typed into a form can be loaded. */
	static String normalizeUrl(String url) {
		String u = url.trim();
		return ((u.indexOf("://") == -1) && !u.isEmpty()) ? "https://" + u : u;
	}

	private static File dir() {
		File d = new File(FermataApplication.get().getFilesDir(), "web_thumbs");
		if (!d.isDirectory() && !d.mkdirs()) Log.w("Failed to create ", d);
		return d;
	}

	private static File file(String url) {
		long h = 1125899906842597L;
		for (int i = 0; i < url.length(); i++) h = 31 * h + url.charAt(i);
		return new File(dir(), Long.toHexString(h) + ".jpg");
	}

	/** Forgets a removed bookmark's screenshot. */
	static void deleteThumbnail(String url) {
		cache.remove(url);
		io.execute(() -> {
			File f = file(url);
			if (f.exists() && !f.delete()) Log.w("Failed to delete ", f);
		});
	}

	/** Carries the screenshot over to a bookmark whose URL was edited. */
	static void renameThumbnail(String oldUrl, String newUrl) {
		if (oldUrl.equals(newUrl)) return;
		cache.remove(oldUrl);
		cache.remove(newUrl);
		io.execute(() -> {
			File from = file(oldUrl);
			File to = file(newUrl);
			if (from.exists() && !from.renameTo(to)) Log.w("Failed to rename ", from);
		});
	}

	/** The saved screenshot for {@code url}, from memory if it's there; null while it loads. */
	@Nullable
	static Bitmap cached(String url) {
		return cache.get(url);
	}

	/** Loads the screenshot of {@code url} off the main thread; {@code cb} gets null if none. */
	static void load(String url, Consumer<Bitmap> cb) {
		Bitmap b = cache.get(url);
		if (b != null) {
			cb.accept(b);
			return;
		}
		io.execute(() -> {
			Bitmap bm = null;
			File f = file(url);
			if (f.isFile()) {
				try {
					bm = BitmapFactory.decodeFile(f.getAbsolutePath());
				} catch (Throwable ex) {
					Log.e(ex, "Failed to decode ", f);
				}
			}
			Bitmap res = bm;
			if (res != null) cache.put(url, res);
			main.post(() -> cb.accept(res));
		});
	}

	/**
	 * Takes a screenshot of the top of what {@code web} is showing: a square, which is what a card
	 * shows. Null while the view isn't on screen, or if it renders blank (a page that hasn't
	 * painted yet).
	 */
	@Nullable
	static Bitmap snapshot(View web) {
		int w = web.getWidth();
		int h = web.getHeight();
		if ((w <= 0) || (h <= 0) || !web.isShown()) return null;

		try {
			float scale = THUMB_SIZE / (float) w;
			int sh = Math.max(1, Math.round(h * scale));
			Bitmap shot = Bitmap.createBitmap(THUMB_SIZE, sh, Bitmap.Config.RGB_565);
			Canvas c = new Canvas(shot);
			c.scale(scale, scale);
			web.draw(c);
			int side = Math.min(shot.getWidth(), shot.getHeight());
			Bitmap square = Bitmap.createBitmap(shot, 0, 0, side, side);
			return isBlank(square) ? null : square;
		} catch (Throwable ex) {
			Log.e(ex, "Failed to take a screenshot");
			return null;
		}
	}

	/** Saves {@code shot} as the card screenshot of {@code url}. */
	static void save(String url, Bitmap shot) {
		cache.put(url, shot);
		write(file(url), shot, null);
	}

	private static File lastFile() {
		return new File(dir(), "last.jpg");
	}

	/** Saves {@code shot} as the last visited site's, the home page background's default. */
	static void saveLast(Bitmap shot) {
		write(lastFile(), shot, null);
	}

	/** The last visited site's screenshot, null if there isn't one; {@code cb} runs on main. */
	static void loadLast(Consumer<Bitmap> cb) {
		loadFile(lastFile(), cb);
	}

	private static File imageFile() {
		return new File(dir(), "home_bg.img");
	}

	private static File imageUrlFile() {
		return new File(dir(), "home_bg.url");
	}

	/**
	 * The picture at {@code url} for the home page background, downloaded once and kept until the
	 * URL changes. {@code cb} runs on the main thread, with null if it can't be fetched.
	 */
	static void loadImage(String url, Consumer<Bitmap> cb) {
		io.execute(() -> {
			Bitmap bm = null;
			try {
				File img = imageFile();
				File marker = imageUrlFile();
				String saved = null;
				if (marker.isFile()) {
					try (java.io.BufferedReader r = new java.io.BufferedReader(
							new java.io.FileReader(marker))) {
						saved = r.readLine();
					}
				}
				if (!url.equals(saved) || !img.isFile()) {
					java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url)
							.openConnection();
					c.setConnectTimeout(8000);
					c.setReadTimeout(12000);
					try (java.io.InputStream in = c.getInputStream();
							 FileOutputStream out = new FileOutputStream(img)) {
						byte[] buf = new byte[8192];
						long total = 0;
						for (int n; (n = in.read(buf)) != -1; ) {
							total += n;
							// Nobody's background is bigger than this: refuse rather than fill the disk.
							if (total > 12L * 1024 * 1024) throw new IOException("Image too large");
							out.write(buf, 0, n);
						}
					} finally {
						c.disconnect();
					}
					try (java.io.FileWriter w = new java.io.FileWriter(marker)) {
						w.write(url);
					}
				}
				BitmapFactory.Options o = new BitmapFactory.Options();
				o.inSampleSize = 2;
				bm = BitmapFactory.decodeFile(img.getAbsolutePath(), o);
			} catch (Throwable ex) {
				Log.e(ex, "Failed to load the home page background ", url);
			}
			Bitmap res = bm;
			main.post(() -> cb.accept(res));
		});
	}

	private static void loadFile(File f, Consumer<Bitmap> cb) {
		io.execute(() -> {
			Bitmap bm = null;
			if (f.isFile()) {
				try {
					bm = BitmapFactory.decodeFile(f.getAbsolutePath());
				} catch (Throwable ex) {
					Log.e(ex, "Failed to decode ", f);
				}
			}
			Bitmap res = bm;
			main.post(() -> cb.accept(res));
		});
	}

	private static void write(File f, Bitmap bm, @Nullable Runnable done) {
		io.execute(() -> {
			try (FileOutputStream out = new FileOutputStream(f)) {
				bm.compress(Bitmap.CompressFormat.JPEG, 82, out);
			} catch (IOException ex) {
				Log.e(ex, "Failed to save ", f);
			}
			if (done != null) main.post(done);
		});
	}

	/** Whether the picture is one flat colour, as a page that hasn't rendered yet gives. */
	private static boolean isBlank(Bitmap b) {
		int w = b.getWidth();
		int h = b.getHeight();
		int first = b.getPixel(w / 2, h / 2);
		for (int i = 1; i < 8; i++) {
			if (b.getPixel(w * i / 8, h * i / 8) != first) return false;
			if (b.getPixel(w * (8 - i) / 8, h * i / 8) != first) return false;
		}
		return true;
	}
}
