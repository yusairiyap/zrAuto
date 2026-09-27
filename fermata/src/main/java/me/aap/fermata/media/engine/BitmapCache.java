package me.aap.fermata.media.engine;

import static android.content.Context.MODE_PRIVATE;
import static java.util.Collections.emptyList;
import static me.aap.fermata.util.Utils.getLauncherColor;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedNull;
import static me.aap.utils.async.Completed.failed;
import static me.aap.utils.io.FileUtils.getFileExtension;
import static me.aap.utils.net.http.HttpFileDownloader.MAX_AGE;
import static me.aap.utils.security.SecurityUtils.SHA1_DIGEST_LEN;
import static me.aap.utils.security.SecurityUtils.sha1;
import static me.aap.utils.text.TextUtils.appendHexString;
import static me.aap.utils.ui.UiUtils.resizedBitmap;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.provider.FermataContentProvider;
import me.aap.fermata.vfs.FermataVfsManager;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.PromiseQueue;
import me.aap.utils.collection.CollectionUtils;
import me.aap.utils.function.CheckedSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.io.MemOutputStream;
import me.aap.utils.log.Log;
import me.aap.utils.net.http.HttpFileDownloader;
import me.aap.utils.net.http.HttpFileDownloader.Status;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.pref.SharedPreferenceStore;
import me.aap.utils.resource.Rid;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.text.TextBuilder;
import me.aap.utils.ui.UiUtils;

/**
 * @author Andrey Pavlenko
 */
public class BitmapCache {
	/** Best looking thumbnails, the biggest cache. */
	public static final int THUMB_QUALITY_HIGH = 0;
	/** Visually the same, but saved more compactly (the default). */
	public static final int THUMB_QUALITY_BALANCED = 1;
	/** Smaller, lighter thumbnails: the least cache and the least data. */
	public static final int THUMB_QUALITY_SAVER = 2;
	/** How thumbnails are downloaded and stored, one of the THUMB_QUALITY_* values. */
	public static final Pref<IntSupplier> THUMB_QUALITY =
			Pref.i("THUMB_QUALITY", THUMB_QUALITY_BALANCED);
	/** The image cache's size limit, in MB, for each IMAGE_CACHE_LIMIT choice; 0 is no limit. */
	public static final int[] CACHE_LIMITS_MB = {100, 250, 500, 1000, 0};
	/** Index into {@link #CACHE_LIMITS_MB}. */
	public static final Pref<IntSupplier> IMAGE_CACHE_LIMIT = Pref.i("IMAGE_CACHE_LIMIT", 1);
	// Re-checks the cache size after this many newly saved images, besides once at startup.
	private static final int TRIM_EVERY = 50;
	private final File iconsCache;
	private final File imageCache;
	private final String iconsCacheUri;
	private final String imageCacheUri;
	private final SharedPreferences prefs;
	private final Map<String, Ref> cache = new HashMap<>();
	private final ReferenceQueue<Bitmap> refQueue = new ReferenceQueue<>();
	private final PromiseQueue queue = new PromiseQueue(App.get().getExecutor());
	private final Map<String, String> invalidBitmapUris = new ConcurrentHashMap<>();
	/**
	 * Strong references to the most recently used bitmaps, on top of the soft {@link #cache}: soft
	 * references go at the first bit of memory pressure, and then scrolling back up a long list
	 * decodes every thumbnail again, which is what made big playlists stutter.
	 */
	private final LruCache<String, Bitmap> recent = new LruCache<>(recentCacheSizeKb()) {
		@Override
		protected int sizeOf(String key, Bitmap value) {
			return Math.max(1, value.getAllocationByteCount() / 1024);
		}
	};
	private final AtomicInteger savedSinceTrim = new AtomicInteger();

	public BitmapCache() {
		File cache = App.get().getExternalCacheDir();
		if (cache == null) cache = App.get().getCacheDir();
		iconsCache = new File(cache, "icons").getAbsoluteFile();
		imageCache = new File(cache, "images").getAbsoluteFile();
		iconsCacheUri = Uri.fromFile(iconsCache).toString() + '/';
		imageCacheUri = Uri.fromFile(imageCache).toString() + '/';
		prefs = getContext().getSharedPreferences("image-cache", MODE_PRIVATE);
		App.get().getScheduler().schedule(this::trimToLimit, 30, TimeUnit.SECONDS);
	}

	private static int recentCacheSizeKb() {
		long max = Runtime.getRuntime().maxMemory() / 1024;
		return (int) Math.max(8 * 1024, Math.min(max / 8, 64 * 1024));
	}

	private static PreferenceStore settings() {
		return FermataApplication.get().getPreferenceStore();
	}

	private static int thumbQuality() {
		return settings().getIntPref(THUMB_QUALITY);
	}

	/** JPEG quality the resized thumbnails are saved with. */
	private static int jpegQuality() {
		return switch (thumbQuality()) {
			case THUMB_QUALITY_HIGH -> 92;
			case THUMB_QUALITY_SAVER -> 70;
			default -> 82;
		};
	}

	public boolean isResourceImageAvailable(Uri uri) {
		try (AssetFileDescriptor afd = openResource(getContext(), uri, 0)) {
			return afd.getLength() > 0;
		} catch (Exception ex) {
			return false;
		}
	}

	public ParcelFileDescriptor openResourceImage(Uri uri) throws FileNotFoundException {
		Context ctx = getContext();
		int size = getIconSize(ctx);
		String iconUri = toIconUri(uri.toString(), size);
		File iconFile = new File(iconsCache, iconUri.substring(iconsCacheUri.length()));

		if (iconFile.isFile()) {
			return ctx.getContentResolver().openFileDescriptor(Uri.parse(iconUri), "r");
		} else if (ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())) {
			loadBitmap(ctx, uri.toString(), iconUri, true, size);
			return ctx.getContentResolver().openFileDescriptor(Uri.parse(iconUri), "r");
		} else {
			return openResource(ctx, uri, 0).getParcelFileDescriptor();
		}
	}

	@NonNull
	public FutureSupplier<Bitmap> getBitmap(Context ctx, String uri, boolean cache, boolean resize) {
		String orig = FermataContentProvider.getOrigUri(uri);
		String u = (orig == null) ? uri : orig;
		int size;
		String iconUri;
		Bitmap bm;

		if (resize) {
			size = getIconSize(ctx);
			iconUri = toIconUri(u, size);
			bm = getCachedBitmap(iconUri);
		} else {
			size = 0;
			iconUri = null;
			bm = getCachedBitmap(u);
		}

		if (bm != null) return completed(bm);

		if (u.startsWith("http://") || u.startsWith("https://")) {
			if (iconUri == null) return loadHttpBitmap(u, null, 0);
			// A list thumbnail: the small resized copy saved from an earlier download is all it needs,
			// no need to check the original with the server, let alone decode it at full size again.
			String src = (thumbQuality() == THUMB_QUALITY_SAVER) ? youtubeSmall(u) : u;
			File iconFile = new File(iconsCache, iconUri.substring(iconsCacheUri.length()));
			if (!iconFile.isFile()) return loadHttpBitmap(src, iconUri, size);
			// Decoded in parallel, not through the one-at-a-time queue: a screenful of cards at once.
			FutureSupplier<Bitmap> f = App.get().getExecutor().submitTask(() -> loadIconFile(iconFile,
					iconUri));
			return f.then(b -> (b != null) ? completed(b) : loadHttpBitmap(src, iconUri, size));
		}

		return queue.enqueue(() -> loadBitmap(ctx, u, iconUri, cache, size));
	}

	@Nullable
	private Bitmap loadIconFile(File f, String iconUri) {
		Bitmap bm = getCachedBitmap(iconUri);
		if (bm != null) return bm;
		bm = BitmapFactory.decodeFile(f.getPath());
		if (bm == null) {
			//noinspection ResultOfMethodCallIgnored
			f.delete();
			return null;
		}
		touch(f);
		return cacheBitmap(iconUri, bm);
	}

	/** Marks a cached file as recently used, so trimming the cache removes it last. */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	private static void touch(File f) {
		long now = System.currentTimeMillis();
		// Not on every single use: once a day is plenty for picking what to drop first.
		if (now - f.lastModified() > 24 * 3600_000L) f.setLastModified(now);
	}

	@Nullable
	private Bitmap getCachedBitmap(String uri) {
		Bitmap recentBm = recent.get(uri);
		if (recentBm != null) return recentBm;

		synchronized (cache) {
			clearRefs();
			Ref r = cache.get(uri);

			if (r != null) {
				Bitmap bm = r.get();
				if (bm != null) {
					recent.put(uri, bm);
					return bm;
				} else {
					cache.remove(uri);
				}
			}

			return null;
		}
	}

	private Bitmap loadBitmap(Context ctx, String uri, String iconUri, boolean cache, int size) {
		Bitmap bm;

		if (iconUri != null) {
			bm = getCachedBitmap(iconUri);
			if (bm != null) return bm;
			File iconFile = new File(iconsCache, iconUri.substring(iconsCacheUri.length()));
			if (iconFile.isFile()) bm = loadBitmap(ctx, iconUri, cache ? uri : null, 0);
			if (bm != null) return bm;
			bm = loadBitmap(ctx, uri, cache ? iconUri : null, size);
			if (cache && (bm != null)) saveIcon(bm, iconFile);
		} else {
			bm = getCachedBitmap(uri);
			if (bm != null) return bm;
			bm = loadBitmap(ctx, uri, cache ? uri : null, size);
		}

		return bm;
	}

	private Bitmap loadBitmap(Context ctx, String uri, String cacheUri, int size) {
		try {
			Bitmap bm = null;
			Uri u = Uri.parse(uri);
			String scheme = u.getScheme();
			if (scheme == null) return null;

			switch (scheme) {
				case "file":
					try (ParcelFileDescriptor fd = ctx.getContentResolver().openFileDescriptor(u, "r")) {
						if (fd != null) bm = BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor());
					}
					break;
				case ContentResolver.SCHEME_ANDROID_RESOURCE:
					Resources res = ctx.getResources();
					String[] s = uri.split("/");
					int id = res.getIdentifier(s[s.length - 1], s[s.length - 2], ctx.getPackageName());
					if (id == 0) id = res.getIdentifier(s[s.length - 1], s[s.length - 2], s[s.length - 3]);
					if (id == 0) {
						Log.e("Resource, ", uri, " not found!");
						break;
					}
					Drawable d = ResourcesCompat.getDrawable(res, id, ctx.getTheme());
					if (d != null) {
						int color = (d instanceof VectorDrawable) ? getLauncherColor() : Color.WHITE;
						var dims = UiUtils.adjustDims(d.getIntrinsicWidth(), d.getIntrinsicHeight(), size);
						bm = UiUtils.drawBitmap(d, Color.TRANSPARENT, color, dims[0], dims[1]);
						size = 0;
					}
					break;
				case "content":
					bm = loadContentBitmap(ctx, u, size);
					size = 0;
					break;
				default:
					FermataVfsManager vfs = getVfsManager();
					if (vfs.isSupportedScheme(scheme))
						bm = loadUriBitmap(vfs.getHttpRid(Rid.create(u)).toString());
			}

			if (bm == null) return null;
			if (size != 0) bm = resizedBitmap(bm, size);
			return (cacheUri != null) ? cacheBitmap(cacheUri, bm) : bm;
		} catch (Exception ex) {
			Log.d(ex, "Failed to load bitmap: ", uri);
			return null;
		}
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	private FutureSupplier<Bitmap> loadHttpBitmap(String uri, String cacheUri, int size) {
		String ytFallback = youtubeFallback(uri);
		return downloadImage(uri).then(s -> {
			if (s == null) {
				return (ytFallback != null) ? loadHttpBitmap(ytFallback, cacheUri, size) : completedNull();
			}
			try {
				Bitmap bm = decodeSampled(s, size);

				// A video that was never available above 720p has no maxresdefault.jpg: YouTube
				// answers with its tiny grey "..." placeholder (120x90) instead. Use hqdefault.jpg,
				// which every video has.
				if ((bm != null) && (ytFallback != null) && (bm.getWidth() <= 120)) {
					File f = s.getLocalFile();
					if (f != null) f.delete();
					invalidBitmapUris.put(uri, uri);
					return loadHttpBitmap(ytFallback, cacheUri, size);
				}

				if (bm == null) {
					File f = s.getLocalFile();
					if (f != null) f.delete();
					invalidBitmapUris.put(uri, uri);
					return failed(new IOException("Failed to decode image: " + uri));
				} else {
					if (isLetterboxedYoutube(uri)) bm = cropLetterbox(bm);
					if (size != 0) bm = resizedBitmap(bm, size);
					if (cacheUri != null) {
						bm = cacheBitmap(cacheUri, bm);
						// Only resized thumbnails have an icon file; the next time, that's all that's read.
						if (size != 0) saveIconAsync(bm, cacheUri);
					}
					return completed(bm);
				}
			} catch (Exception ex) {
				invalidBitmapUris.put(uri, uri);
				return failed(ex);
			}
		});
	}

	/**
	 * Decodes a downloaded image, at no more than about twice {@code size} (0: at full size): the
	 * full-size decode of a 1280x720 YouTube thumbnail allocates ~3.5 MB just to be shrunk right
	 * away, and a long list does that dozens of times in a row.
	 */
	@Nullable
	private static Bitmap decodeSampled(Status s, int size) throws IOException {
		if (size <= 0) {
			try (InputStream is = s.getFileStream(true)) {
				return BitmapFactory.decodeStream(is);
			}
		}
		BitmapFactory.Options o = new BitmapFactory.Options();
		o.inJustDecodeBounds = true;
		try (InputStream is = s.getFileStream(true)) {
			BitmapFactory.decodeStream(is, null, o);
		}
		int sample = 1;
		int w = o.outWidth;
		int h = o.outHeight;
		// Never below 121px wide either: that's how YouTube's placeholder image is told apart.
		while ((w > 0) && (h > 0) && (Math.max(w, h) / (sample * 2) >= size) &&
				(w / (sample * 2) > 120)) {
			sample *= 2;
		}
		o = new BitmapFactory.Options();
		o.inSampleSize = sample;
		try (InputStream is = s.getFileStream(true)) {
			return BitmapFactory.decodeStream(is, null, o);
		}
	}

	private static boolean isLetterboxedYoutube(String uri) {
		return uri.endsWith("/hqdefault.jpg") &&
				(uri.contains("ytimg.com/") || uri.contains("img.youtube.com/"));
	}

	/**
	 * YouTube's hqdefault.jpg is a 4:3 canvas with the 16:9 frame letterboxed inside it: cut the
	 * black bars off, or they show on every cropped card.
	 */
	private static Bitmap cropLetterbox(Bitmap bm) {
		int w = bm.getWidth();
		int h = bm.getHeight();
		if ((w <= 0) || (w * 3 != h * 4)) return bm;
		int ch = w * 9 / 16;
		try {
			return Bitmap.createBitmap(bm, 0, (h - ch) / 2, w, ch);
		} catch (Throwable ex) {
			return bm;
		}
	}

	/** In Data saver mode: a list thumbnail from hqdefault.jpg, a fraction of maxresdefault.jpg. */
	private static String youtubeSmall(String uri) {
		String fb = youtubeFallback(uri);
		return (fb != null) ? fb : uri;
	}

	private void saveIconAsync(Bitmap bm, String iconUri) {
		if (!iconUri.startsWith(iconsCacheUri)) return;
		File f = new File(iconsCache, iconUri.substring(iconsCacheUri.length()));
		App.get().getExecutor().submitTask(() -> {
			if (!f.isFile()) saveIcon(bm, f);
		});
	}

	/** For a YouTube maxresdefault.jpg thumbnail: the hqdefault.jpg one, which always exists. */
	@Nullable
	private static String youtubeFallback(String uri) {
		if (!uri.endsWith("/maxresdefault.jpg")) return null;
		if (!uri.contains("ytimg.com/") && !uri.contains("img.youtube.com/")) return null;
		return uri.substring(0, uri.length() - "maxresdefault.jpg".length()) + "hqdefault.jpg";
	}

	/**
	 * Forgets everything cached for {@code uri} -- in memory, the downloaded file and the resized
	 * icon -- so the next request downloads it afresh. Used by "Refresh thumbnail".
	 */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	public void invalidate(Context ctx, String uri) {
		String iconUri = toIconUri(uri, getIconSize(ctx));
		recent.remove(uri);
		recent.remove(iconUri);
		synchronized (cache) {
			cache.remove(uri);
			cache.remove(iconUri);
		}
		invalidBitmapUris.remove(uri);
		toImageFile(uri).delete();
		if (iconUri.startsWith(iconsCacheUri)) {
			new File(iconsCache, iconUri.substring(iconsCacheUri.length())).delete();
		}
	}

	public FutureSupplier<Status> downloadImage(String uri) {
		if (invalidBitmapUris.containsKey(uri)) {
			Log.d("Invalid bitmap uri: ", uri);
			return completedNull();
		}

		String path;

		try (SharedTextBuilder b = SharedTextBuilder.get()) {
			b.append("/X/");
			appendHexString(b, sha1(uri));
			b.setCharAt(1, b.charAt(3));
			b.append('.').append(getFileExtension(uri, "img"));
			path = b.toString();
		}

		File dst = toImageFile(uri);
		ImagePrefs ip = new ImagePrefs(prefs, path);
		HttpFileDownloader d = new HttpFileDownloader();
		d.setReturnExistingOnFail(true);
		return d.download(uri, dst, ip).onFailure(ex -> {
			Log.d(ex, "Failed to download image: ", uri);
			invalidBitmapUris.put(uri, uri);
		});
	}

	/** The file an {@link #addImage} image for {@code uri} was saved to, if it exists. */
	@Nullable
	public Uri getAddedImage(String uri) {
		File f = toImageFile(uri);
		return f.isFile() ? Uri.fromFile(f) : null;
	}

	public synchronized FutureSupplier<Uri> addImage(String uri,
																									 CheckedSupplier<Bitmap, Exception> s) {
		File f = toImageFile(uri);
		if (f.isFile()) return completed(Uri.fromFile(f));

		return queue.enqueue(() -> {
			synchronized (BitmapCache.this) {
				File dir = f.getParentFile();
				if (dir != null) //noinspection ResultOfMethodCallIgnored
					dir.mkdirs();
				if (!f.isFile()) {
					try (OutputStream out = new FileOutputStream(f)) {
						CompressFormat fmt =
								(f.getName().endsWith(".png")) ? CompressFormat.PNG : CompressFormat.JPEG;
						s.get().compress(fmt, 100, out);
					} catch (Exception ex) {
						Log.e(ex, "Failed to save image: ", f);
					}
				}
				return Uri.fromFile(f);
			}
		});
	}

	private Bitmap loadContentBitmap(Context ctx, Uri u, int size) throws IOException {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ContentResolver cr = ctx.getContentResolver();
			if (size == 0) size = getIconSize(ctx);
			return cr.loadThumbnail(u, new Size(size, size), null);
		} else {
			try (AssetFileDescriptor afd = openResource(ctx, u, size)) {
				return (afd == null) ? null : BitmapFactory.decodeFileDescriptor(afd.getFileDescriptor());
			}
		}
	}

	private AssetFileDescriptor openResource(Context ctx, Uri u, int size)
			throws FileNotFoundException {
		if (size == 0) size = getIconSize(ctx);
		ContentResolver cr = ctx.getContentResolver();
		Bundle opts = new Bundle();
		opts.putParcelable(ContentResolver.EXTRA_SIZE, new Point(size, size));
		return cr.openTypedAssetFileDescriptor(u, "image/*", opts, null);
	}

	private Bitmap loadUriBitmap(String uri) throws IOException {
		try (InputStream in = new URL(uri).openStream()) {
			return BitmapFactory.decodeStream(in);
		}
	}

	private Bitmap cacheBitmap(String uri, Bitmap bm) {
		synchronized (cache) {
			clearRefs();
			Ref ref = new Ref(uri, bm, refQueue);
			Ref cachedRef = CollectionUtils.putIfAbsent(cache, uri, ref);

			if (cachedRef != null) {
				Bitmap cached = cachedRef.get();
				if (cached != null) {
					recent.put(uri, cached);
					return cached;
				}
				cache.put(uri, ref);
			}

			recent.put(uri, bm);
			return bm;
		}
	}

	private static int getIconSize(Context ctx) {
		// Data saver: two thirds the size, still sharp enough for a list or grid card.
		return ((thumbQuality() == THUMB_QUALITY_SAVER) ? 2 : 3) * smallIconSize(ctx);
	}

	private static int smallIconSize(Context ctx) {
		return switch (ctx.getResources().getConfiguration().densityDpi) {
			case DisplayMetrics.DENSITY_LOW -> 32;
			case DisplayMetrics.DENSITY_MEDIUM -> 48;
			case DisplayMetrics.DENSITY_HIGH -> 72;
			case DisplayMetrics.DENSITY_XHIGH -> 96;
			case DisplayMetrics.DENSITY_XXHIGH -> 144;
			default -> 192;
		};
	}

	String getImageUri(byte[] hash, TextBuilder tb) {
		tb.setLength(0);
		tb.append(imageCacheUri);
		int len = tb.length();
		appendHexString(tb.append("X/"), hash, 0, SHA1_DIGEST_LEN).append(".jpg");
		tb.setCharAt(len, tb.charAt(len + 2));
		return tb.toString().intern();
	}

	synchronized byte[] saveBitmap(Bitmap bm, TextBuilder tb) {
		if (bm == null) return null;

		try {
			MemOutputStream mos = new MemOutputStream(bm.getByteCount());
			if (!bm.compress(CompressFormat.JPEG, 100, mos)) return null;

			byte[] content = mos.trimBuffer();
			MessageDigest md = MessageDigest.getInstance("sha-1");
			md.update(content);
			byte[] digest = md.digest();
			int pos = tb.length();
			appendHexString(tb.append("X/"), digest).append(".jpg");
			tb.setCharAt(pos, tb.charAt(pos + 2));
			File f = new File(imageCache, tb.substring(pos));

			if (!f.isFile()) {
				File dir = f.getParentFile();
				if (dir != null) //noinspection ResultOfMethodCallIgnored
					dir.mkdirs();
				try (OutputStream os = new FileOutputStream(f)) {
					os.write(content);
				}
			}

			return digest;
		} catch (Exception ex) {
			Log.e(ex, "Failed to save image");
			return null;
		}
	}

	private synchronized void saveIcon(Bitmap bm, File f) {
		File p = f.getParentFile();
		if (p != null) //noinspection ResultOfMethodCallIgnored
			p.mkdirs();

		try (OutputStream out = new FileOutputStream(f)) {
			bm.compress(CompressFormat.JPEG, jpegQuality(), out);
		} catch (Exception ex) {
			Log.e(ex, "Failed to save icon: ", f);
		}

		if (savedSinceTrim.incrementAndGet() >= TRIM_EVERY) {
			savedSinceTrim.set(0);
			App.get().getExecutor().submitTask(this::trimToLimit);
		}
	}

	/** The size, in bytes, of what {@link #clearCache} and {@link #trimToLimit} may delete. */
	public long getCacheSize() {
		long total = 0;
		for (File f : listDeletable()) total += f.length();
		return total;
	}

	/**
	 * Deletes every downloaded image and resized thumbnail: they're downloaded or resized again
	 * when next shown. Album art extracted from local files and generated playlist covers stay,
	 * since there's nothing to make them again from. Returns the number of bytes freed.
	 */
	public synchronized long clearCache() {
		recent.evictAll();
		synchronized (cache) {
			cache.clear();
		}
		long freed = 0;
		for (File f : listDeletable()) {
			long len = f.length();
			if (f.delete()) freed += len;
		}
		invalidBitmapUris.clear();
		cleanUpPrefs();
		return freed;
	}

	/** Deletes the least recently used cached images while the cache is over its size limit. */
	public synchronized void trimToLimit() {
		try {
			int idx = settings().getIntPref(IMAGE_CACHE_LIMIT);
			if ((idx < 0) || (idx >= CACHE_LIMITS_MB.length)) return;
			long limit = CACHE_LIMITS_MB[idx] * 1024L * 1024L;
			if (limit <= 0) return;
			List<File> files = listDeletable();
			long total = 0;
			long[] sizes = new long[files.size()];
			long[] times = new long[files.size()];
			for (int i = 0; i < sizes.length; i++) {
				File f = files.get(i);
				total += sizes[i] = f.length();
				times[i] = f.lastModified();
			}
			if (total <= limit) return;
			Integer[] order = new Integer[sizes.length];
			for (int i = 0; i < order.length; i++) order[i] = i;
			Arrays.sort(order, (a, b) -> Long.compare(times[a], times[b]));
			// Down to 80% of the limit, so it's not trimmed again after the next few downloads.
			long target = limit * 4 / 5;
			for (Integer i : order) {
				if (total <= target) break;
				if (files.get(i).delete()) total -= sizes[i];
			}
			Log.i("Image cache trimmed to ", total / 1024, " KB");
			cleanUpPrefs();
		} catch (Throwable ex) {
			Log.e(ex, "Failed to trim the image cache");
		}
	}

	/** Resized thumbnails, plus the images downloaded from the web (those with download prefs). */
	private List<File> listDeletable() {
		List<File> files = new ArrayList<>();
		listFiles(iconsCache, files);
		Set<String> downloaded = new HashSet<>();
		for (String k : prefs.getAll().keySet()) {
			int idx = k.lastIndexOf('#');
			if (idx > 0) downloaded.add(k.substring(0, idx));
		}
		for (String path : downloaded) {
			File f = new File(imageCache, path);
			if (f.isFile()) files.add(f);
		}
		return files;
	}

	private static void listFiles(File dir, List<File> out) {
		File[] ls = dir.listFiles();
		if (ls == null) return;
		for (File f : ls) {
			if (f.isDirectory()) listFiles(f, out);
			else out.add(f);
		}
	}

	private String toIconUri(String imageUri, int size) {
		try (SharedTextBuilder tb = SharedTextBuilder.get()) {
			tb.append(iconsCacheUri).append(size).append("/X/");
			int len = tb.length();

			if (imageUri.startsWith(imageCacheUri)) {
				tb.append(imageUri.substring(imageCacheUri.length()));
			} else if (imageUri.startsWith(iconsCacheUri)) {
				tb.append(imageUri.substring(iconsCacheUri.length()));
			} else {
				appendHexString(tb, sha1(imageUri)).append(".jpg");
			}

			tb.setCharAt(len - 2, tb.charAt(len));
			return tb.toString();
		}
	}

	private File toImageFile(String uri) {
		String path;
		try (SharedTextBuilder b = SharedTextBuilder.get()) {
			b.append(imageCache);
			int idx = b.length();
			b.append("/X/");
			appendHexString(b, sha1(uri));
			b.setCharAt(idx + 1, b.charAt(idx + 3));
			b.append('.').append(getFileExtension(uri, "img"));
			path = b.toString();
		}
		return new File(path);
	}

	private void clearRefs() {
		for (Ref r = (Ref) refQueue.poll(); r != null; r = (Ref) refQueue.poll()) {
			CollectionUtils.remove(cache, r.key, r);
		}
	}

	private static final class Ref extends SoftReference<Bitmap> {
		final Object key;

		public Ref(String key, Bitmap value, ReferenceQueue<Bitmap> q) {
			super(value, q);
			this.key = key;
		}
	}

	public void cleanUpPrefs() {
		SharedPreferences.Editor edit = prefs.edit();
		boolean removed = false;
		for (String k : new ArrayList<>(prefs.getAll().keySet())) {
			int idx = k.lastIndexOf('#');
			if ((idx <= 0) || (idx == k.length() - 1)) continue;
			File f = new File(imageCache, k.substring(0, idx));
			if (f.exists()) continue;
			Log.i("Image file does not exist - removing preference key ", k);
			edit.remove(k);
			removed = true;
		}
		if (removed) edit.apply();
	}

	private FermataApplication getContext() {
		return FermataApplication.get();
	}

	private FermataVfsManager getVfsManager() {
		return getContext().getVfsManager();
	}

	private static final class ImagePrefs implements SharedPreferenceStore {
		private static final int IMAGE_MAX_AGE = 7 * 24 * 3600;
		private final SharedPreferences prefs;
		private final String id;

		private ImagePrefs(SharedPreferences prefs, String id) {
			this.prefs = prefs;
			this.id = id;
		}

		@Override
		public String getPreferenceKey(Pref<?> pref) {
			return id + '#' + pref.getName();
		}

		@Override
		public Collection<ListenerRef<Listener>> getBroadcastEventListeners() {
			return emptyList();
		}

		@NonNull
		@Override
		public SharedPreferences getSharedPreferences() {
			return prefs;
		}

		@Override
		public int getIntPref(Pref<? extends IntSupplier> pref) {
			if (pref.equals(MAX_AGE)) return IMAGE_MAX_AGE;
			return SharedPreferenceStore.super.getIntPref(pref);
		}
	}
}
