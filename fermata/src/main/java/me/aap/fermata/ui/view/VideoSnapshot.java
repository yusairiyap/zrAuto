package me.aap.fermata.ui.view;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.SurfaceView;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.fermata.ytdl.YtOffline;
import me.aap.utils.vfs.VirtualResource;

/**
 * The SNAPSHOT action: grabs the picture of the video that plays and saves it as a JPEG in Pictures/zrAuto, at the video's own
 * resolution wherever that can be had.
 * <ul>
 * <li>YouTube streamed: the page's {@code <video>} drawn on a canvas ({@link MusicPlayer#youtubeFrame}), so the frame is the
 * stream's size (1080p, 4K ...), not the screen's.</li>
 * <li>A file on the phone or a download: the frame at the playing position read from the file ({@link MediaMetadataRetriever}),
 * again at full size.</li>
 * <li>Anything else (a web stream): the video surface as it is on screen ({@link PixelCopy}).</li>
 * </ul>
 * Saved through MediaStore (Android 10+, shows up in the gallery) or into the app's Pictures folder before that.
 */
public final class VideoSnapshot {
	private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "snapshot");
		t.setDaemon(true);
		return t;
	});
	private static final Handler main = new Handler(Looper.getMainLooper());
	private static final int QUALITY = 97;
	private static long lastAt;

	private VideoSnapshot() {
	}

	public static void take(MainActivityDelegate from) {
		// A double tap on the key must not save the same picture twice.
		long now = android.os.SystemClock.uptimeMillis();
		if (now - lastAt < 700) return;
		lastAt = now;

		MainActivityDelegate a = from.getPlaybackDelegate();
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		PlayableItem item = Action.getFavoritableItem(a);
		if (item == null) item = cb.getCurrentItem();
		if ((eng == null) || (item == null)) {
			TopToast.show(R.drawable.snapshot, R.string.snapshot_none);
			return;
		}
		PlayableItem it = item;
		String name = nameOf(it);

		// YouTube's page, unless a downloaded copy is what plays.
		boolean ytPage = (eng.getId() == MediaPrefs.MEDIA_ENG_YT) && !MusicPlayer.isYoutubeAudioMode();
		if (ytPage) {
			eng.getPosition().main().onCompletion((pos, err) -> MusicPlayer.youtubeFrame(a, jpeg -> {
				if (jpeg != null) save(a, jpeg, name, (pos == null) ? 0 : pos);
				else fromSurface(a, eng, name, (pos == null) ? 0 : pos);
			}));
			return;
		}

		Object local = localSource(it);
		if (local != null) {
			eng.getPosition().main().onCompletion((pos, err) -> {
				long at = (pos == null) ? 0 : pos;
				worker.execute(() -> {
					byte[] jpeg = readFrame(a.getContext(), local, at);
					main.post(() -> {
						if (jpeg != null) save(a, jpeg, name, at);
						else fromSurface(a, eng, name, at);
					});
				});
			});
			return;
		}
		if (!it.isVideo()) {
			TopToast.show(R.drawable.snapshot, R.string.snapshot_none);
			return;
		}
		eng.getPosition().main().onCompletion((pos, err) -> fromSurface(a, eng, name, (pos == null) ? 0 : pos));
	}

	@Nullable
	private static Object localSource(PlayableItem item) {
		String vid = YtDownloads.videoIdOf(item);
		if (vid != null) {
			if (!YtOffline.useLocal(vid)) return null;
			YtDownloads.Entry e = YtDownloads.get().getEntry(vid);
			return ((e != null) && e.video) ? YtDownloads.get().getFile(vid) : null;
		}
		if (!item.isVideo() || item.isStream()) return null;
		return SeekPreview.localSourceOf(item);
	}

	@Nullable
	private static byte[] readFrame(Context ctx, Object src, long ms) {
		MediaMetadataRetriever r = new MediaMetadataRetriever();
		try {
			if (src instanceof File f) r.setDataSource(f.getAbsolutePath());
			else r.setDataSource(ctx, (Uri) src);
			Bitmap bm = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
			if (bm == null) bm = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
			if (bm == null) return null;
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
			bm.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
			bm.recycle();
			return out.toByteArray();
		} catch (Throwable ex) {
			DiagnosticLog.w(LogCat.SNAPSHOT, "frame not read from the file", ex);
			return null;
		} finally {
			try {
				r.release();
			} catch (Throwable ignore) {
			}
		}
	}

	/** The last resort: the video surface as it is on screen. */
	private static void fromSurface(MainActivityDelegate a, MediaEngine eng, String name, long pos) {
		VideoView vv = a.getActiveVideoView();
		SurfaceView sv = (vv == null) ? null : vv.getVideoSurface();
		if ((sv == null) || (sv.getWidth() <= 0) || (sv.getHeight() <= 0) || (Build.VERSION.SDK_INT < 24)) {
			DiagnosticLog.w(LogCat.SNAPSHOT, "no picture to grab", "surface=" + (sv != null));
			TopToast.show(R.drawable.snapshot, R.string.snapshot_failed);
			return;
		}
		Bitmap bm = Bitmap.createBitmap(sv.getWidth(), sv.getHeight(), Bitmap.Config.ARGB_8888);
		try {
			PixelCopy.request(sv, bm, result -> {
				if (result != PixelCopy.SUCCESS) {
					DiagnosticLog.w(LogCat.SNAPSHOT, "surface copy failed", "result=" + result);
					TopToast.show(R.drawable.snapshot, R.string.snapshot_failed);
					return;
				}
				worker.execute(() -> {
					ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
					bm.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
					byte[] jpeg = out.toByteArray();
					main.post(() -> save(a, jpeg, name, pos));
				});
			}, main);
		} catch (Throwable ex) {
			DiagnosticLog.w(LogCat.SNAPSHOT, "surface copy not possible", ex);
			TopToast.show(R.drawable.snapshot, R.string.snapshot_failed);
		}
	}

	private static String nameOf(PlayableItem item) {
		String n;
		try {
			n = item.getName();
		} catch (Throwable ex) {
			n = null;
		}
		if ((n == null) || n.isBlank()) n = "video";
		n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim().replaceAll("\\s+", "_");
		return (n.length() > 60) ? n.substring(0, 60) : n;
	}

	private static void save(MainActivityDelegate a, byte[] jpeg, String name, long posMs) {
		worker.execute(() -> {
			Context ctx = a.getContext();
			String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
			long s = posMs / 1000;
			String file = String.format(Locale.US, "%s_%02d-%02d-%02d_%s.jpg", name, s / 3600, (s / 60) % 60, s % 60, stamp);
			Uri uri = null;
			try {
				if (Build.VERSION.SDK_INT >= 29) {
					ContentResolver cr = ctx.getContentResolver();
					ContentValues v = new ContentValues();
					v.put(MediaStore.Images.Media.DISPLAY_NAME, file);
					v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
					v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/zrAuto");
					v.put(MediaStore.Images.Media.IS_PENDING, 1);
					uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
					if (uri == null) throw new java.io.IOException("MediaStore refused the picture");
					try (OutputStream out = cr.openOutputStream(uri)) {
						out.write(jpeg);
					}
					v.clear();
					v.put(MediaStore.Images.Media.IS_PENDING, 0);
					cr.update(uri, v, null, null);
				} else {
					File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
					if (dir == null) throw new java.io.IOException("no pictures folder");
					File f = new File(dir, file);
					try (FileOutputStream out = new FileOutputStream(f)) {
						out.write(jpeg);
					}
					uri = Uri.fromFile(f);
				}
			} catch (Throwable ex) {
				DiagnosticLog.w(LogCat.SNAPSHOT, "snap not saved", ex);
				main.post(() -> TopToast.show(R.drawable.snapshot, R.string.snapshot_failed));
				return;
			}
			Uri saved = uri;
			int[] size = bounds(jpeg);
			DiagnosticLog.log(LogCat.SNAPSHOT, "snap saved", "file=" + file, "size=" + size[0] + "x" + size[1], "bytes=" + jpeg.length);
			main.post(() -> TopToast.show(R.drawable.snapshot, ctx.getString(R.string.snapshot_saved),
					size[0] + " x " + size[1] + " · " + ctx.getString(R.string.snapshot_saved_to), R.string.snapshot_open, () -> open(ctx, saved)));
		});
	}

	private static int[] bounds(byte[] jpeg) {
		BitmapFactory.Options o = new BitmapFactory.Options();
		o.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
		return new int[]{o.outWidth, o.outHeight};
	}

	private static void open(Context ctx, Uri uri) {
		try {
			Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
			ctx.startActivity(i);
		} catch (Exception ex) {
			DiagnosticLog.w(LogCat.SNAPSHOT, "snap not opened", ex);
		}
	}
}
