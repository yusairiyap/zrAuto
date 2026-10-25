package me.aap.fermata.util;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;

/**
 * How the app is starting, and how it last crashed, kept where a person can read it when the screen stays blank: the Downloads folder
 * ({@code zrAuto_start_log.txt}, written through MediaStore, so it shows in every file manager and needs no permission; older Android
 * keeps it in the app's own files). Whatever the in-app Diagnostic log would have said is useless then, since it can't be opened.
 * Short: the last 30 KB.
 */
public final class StartLog {
	private static final String NAME = "zrAuto_start_log.txt";
	private static final int MAX = 30_000;
	private static final StringBuilder buf = new StringBuilder();
	private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "start-log");
		t.setDaemon(true);
		return t;
	});
	private static final SimpleDateFormat stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

	private StartLog() {
	}

	private static volatile boolean enabled;

	/**
	 * Whether ordinary lines are kept: only while the app has not been through its first start (the welcome wizard not done), where a
	 * blank screen is the risk. A normal start writes nothing; a crash is always written.
	 */
	public static void setEnabled(boolean on) {
		enabled = on;
	}

	/** Adds a line; written soon, off the calling thread. */
	public static void add(String msg) {
		if (!enabled) return;
		String text = append(msg);
		io.execute(() -> write(text));
	}

	/** Adds a line and writes it before returning: for a crash, after which the process may be gone at once. */
	public static void addNow(String msg) {
		write(append(msg));
	}

	private static synchronized String append(String msg) {
		synchronized (stamp) {
			buf.append(stamp.format(new Date())).append(' ');
		}
		buf.append(msg).append('\n');
		if (buf.length() > MAX) buf.delete(0, buf.length() - MAX);
		return buf.toString();
	}

	private static void write(String text) {
		try {
			Context ctx = FermataApplication.get();
			byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
			if (Build.VERSION.SDK_INT >= 29) {
				ContentResolver cr = ctx.getContentResolver();
				Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
				Uri uri = null;
				try (Cursor c = cr.query(coll, new String[]{MediaStore.Downloads._ID},
						MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{NAME}, null)) {
					if ((c != null) && c.moveToFirst()) uri = ContentUris.withAppendedId(coll, c.getLong(0));
				}
				if (uri == null) {
					ContentValues v = new ContentValues();
					v.put(MediaStore.Downloads.DISPLAY_NAME, NAME);
					v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
					v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
					uri = cr.insert(coll, v);
				}
				if (uri == null) return;
				try (OutputStream o = cr.openOutputStream(uri, "wt")) {
					if (o != null) o.write(bytes);
				}
			} else {
				File dir = ctx.getExternalFilesDir(null);
				if (dir == null) return;
				try (FileOutputStream o = new FileOutputStream(new File(dir, NAME), false)) {
					o.write(bytes);
				}
			}
		} catch (Throwable ignore) {
			// Best effort: this must never be the thing that fails.
		}
	}
}
