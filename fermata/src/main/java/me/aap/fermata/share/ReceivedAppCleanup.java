package me.aap.fermata.share;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;
import me.aap.utils.app.App;
import me.aap.utils.log.Log;

/**
 * Deletes app files received over Wi-Fi (see {@link ShareImporter#offerInstall}) once they have
 * been installed, to give the storage back. Installing an update restarts the app, so this runs at
 * every start: a received file that is byte for byte the installed app's own APK is the one that was
 * installed. Anything else (not installed yet, "Later" tapped, another version) is left alone.
 */
public final class ReceivedAppCleanup {

	private ReceivedAppCleanup() {
	}

	/** Looks in the background; cheap when nothing was received (one folder listing). */
	public static void run(Context ctx) {
		Context app = ctx.getApplicationContext();
		App.get().execute(() -> {
			try {
				clean(app);
			} catch (Throwable ex) {
				Log.e(ex, "Share: received app cleanup failed");
			}
		});
	}

	private static void clean(Context ctx) {
		String src = ctx.getApplicationInfo().sourceDir;
		if (src == null) return;
		File installed = new File(src);
		if (!installed.isFile()) return;

		for (File base : new File[]{ctx.getExternalFilesDir("Shared"), new File(ctx.getFilesDir(), "Shared")}) {
			if ((base == null) || !base.isDirectory()) continue;
			File[] dirs = base.listFiles(File::isDirectory);
			if (dirs == null) continue;

			for (File dir : dirs) {
				File[] apks = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".apk"));
				if (apks == null) continue;

				for (File apk : apks) {
					if (!sameContent(apk, installed)) continue;
					long size = apk.length();
					if (!apk.delete()) continue;
					DiagnosticLog.log(LogCat.SHARE, "installed app file deleted", "file=" + apk, "size=" + size);
					// The share's own folder, left empty by that.
					String[] left = dir.list();
					//noinspection ResultOfMethodCallIgnored
					if ((left != null) && (left.length == 0)) dir.delete();
				}
			}
		}
	}

	/** Same length first (different builds almost never match), then every byte. */
	private static boolean sameContent(File a, File b) {
		if (a.length() != b.length()) return false;
		byte[] ba = new byte[64 * 1024];
		byte[] bb = new byte[64 * 1024];

		try (InputStream ia = new FileInputStream(a); InputStream ib = new FileInputStream(b)) {
			for (; ; ) {
				int na = readFully(ia, ba);
				int nb = readFully(ib, bb);
				if (na != nb) return false;
				if (na <= 0) return true;
				for (int i = 0; i < na; i++) {
					if (ba[i] != bb[i]) return false;
				}
			}
		} catch (IOException ex) {
			Log.w(ex, "Share: cannot compare ", a);
			return false;
		}
	}

	private static int readFully(InputStream in, byte[] buf) throws IOException {
		int n = 0;
		while (n < buf.length) {
			int r = in.read(buf, n, buf.length - n);
			if (r < 0) break;
			n += r;
		}
		return n;
	}
}
