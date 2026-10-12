package me.aap.fermata.backup;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipOutputStream;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PrefUtils;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.function.Supplier;

/**
 * Writes the app's preferences (settings, folders, playlists...) into a zip, by hand from Settings
 * &gt; Backup and restore, or on a schedule: one alarm at a time, set for the user's interval and
 * hour, fired into {@link BackupReceiver}, which backs up and sets the next one. The zip goes to a
 * folder the user picked (any Storage Access Framework provider that allows a folder) or, without
 * one, to the app's own folder. Old automatic backups beyond the number to keep are deleted.
 */
public final class BackupManager {
	public static final Pref<BooleanSupplier> AUTO_BACKUP = Pref.b("AUTO_BACKUP", false);
	/** Index into {@link #INTERVAL_DAYS}. */
	public static final Pref<IntSupplier> AUTO_BACKUP_INTERVAL = Pref.i("AUTO_BACKUP_INTERVAL", 1);
	/** Hour of the day, 0 to 23. */
	public static final Pref<IntSupplier> AUTO_BACKUP_HOUR = Pref.i("AUTO_BACKUP_HOUR", 3);
	/** Index into {@link #KEEP_COUNT}. */
	public static final Pref<IntSupplier> AUTO_BACKUP_KEEP = Pref.i("AUTO_BACKUP_KEEP", 1);
	public static final Pref<LongSupplier> AUTO_BACKUP_LAST = Pref.l("AUTO_BACKUP_LAST", 0);
	/** When the pending alarm is set for, so a start of the app can tell it was missed. */
	public static final Pref<LongSupplier> AUTO_BACKUP_DUE = Pref.l("AUTO_BACKUP_DUE", 0);
	/** Tree Uri of the folder picked by the user, empty for the app's own folder. */
	public static final Pref<Supplier<String>> AUTO_BACKUP_DIR = Pref.s("AUTO_BACKUP_DIR", "");
	/** What to show for {@link #AUTO_BACKUP_DIR}, worked out once when it's picked. */
	public static final Pref<Supplier<String>> AUTO_BACKUP_DIR_LABEL =
			Pref.s("AUTO_BACKUP_DIR_LABEL", "");
	public static final int[] INTERVAL_DAYS = {1, 3, 7, 30};
	public static final int[] KEEP_COUNT = {3, 5, 10, 20};
	static final String ACTION_RUN = "me.aap.fermata.backup.RUN";
	private static final String PREFIX = "zrAuto_auto_";
	private static final String SUFFIX = ".zip";
	private static final int REQ_CODE = 7301;
	private static final String TAG = "BACKUP";
	private static final AtomicBoolean running = new AtomicBoolean();

	private BackupManager() {
	}

	/** Called once from the application; keeps the alarm in step with the settings. */
	public static void init(Context ctx) {
		PreferenceStore ps = FermataApplication.get().getPreferenceStore();
		ps.addBroadcastListener((s, prefs) -> {
			if (prefs.contains(AUTO_BACKUP) || prefs.contains(AUTO_BACKUP_INTERVAL) ||
					prefs.contains(AUTO_BACKUP_HOUR)) {
				schedule(ctx);
			}
		});

		// The alarm may never have come (the app was stopped, the phone off or the system holding
		// alarms back): the app starting past the time it was due makes the backup now.
		long due = ps.getLongPref(AUTO_BACKUP_DUE);
		long now = System.currentTimeMillis();
		if (ps.getBooleanPref(AUTO_BACKUP) && (due != 0) && (now >= due)) {
			DiagnosticLog.log(TAG, "app started past the backup time, backing up now",
					"due=" + stamp(due), "now=" + stamp(now));
			runScheduled(ctx, "catch-up at start", null);
		} else {
			schedule(ctx);
		}
	}

	/**
	 * The automatic backup: to the destination, then the next alarm. At most one at a time (the
	 * alarm and a start of the app can come together). Off the main thread.
	 *
	 * @param done run when finished, from that thread
	 */
	static void runScheduled(Context ctx, String why, @Nullable Runnable done) {
		PreferenceStore ps = FermataApplication.get().getPreferenceStore();
		if (!ps.getBooleanPref(AUTO_BACKUP)) {
			DiagnosticLog.log(TAG, "skipped (" + why + "), automatic backup is off");
			if (done != null) done.run();
			return;
		}
		if (!running.compareAndSet(false, true)) {
			DiagnosticLog.log(TAG, "skipped (" + why + "), another backup is running");
			if (done != null) done.run();
			return;
		}
		Context app = ctx.getApplicationContext();
		new Thread(() -> {
			try {
				DiagnosticLog.log(TAG, "starting (" + why + ")");
				String name = runBackup(app);
				// A failure waits for the next interval too, rather than retrying every minute.
				if (name == null) ps.applyLongPref(AUTO_BACKUP_LAST, System.currentTimeMillis());
				Log.i("Automatic backup: ", (name != null) ? name : "failed");
			} finally {
				running.set(false);
				schedule(app);
				if (done != null) done.run();
			}
		}, "auto-backup").start();
	}

	private static String stamp(long t) {
		return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date(t));
	}

	/**
	 * Whether a preferences file goes into a backup: all of them, except the thumbnail cache index
	 * and the Spotify login (a refresh token has no business sitting in a zip in the cloud; signing
	 * in again after a restore is the price).
	 */
	public static boolean includeFile(File f) {
		String n = f.getName();
		return !n.equals("image-cache.xml") && !n.equals("spotify_auth.xml");
	}

	/** Writes every preferences file into {@code os} as a zip. False if there's nothing to write. */
	public static boolean writeBackup(Context ctx, OutputStream os) throws IOException {
		File prefsDir = PrefUtils.getSharedPrefsFile(ctx, "fermata").getParentFile();
		File[] files = (prefsDir == null) ? null : prefsDir.listFiles(BackupManager::includeFile);
		if ((files == null) || (files.length == 0)) return false;

		List<String> names = new ArrayList<>(files.length);
		for (File pf : files) {
			String n = pf.getName();
			if (n.endsWith(".xml")) names.add(n.substring(0, n.length() - 4));
		}

		// Not closing the stream the caller gave: finish() writes the zip's end without closing it.
		ZipOutputStream zos = (SDK_INT >= N) ? new ZipOutputStream(os, UTF_8) : new ZipOutputStream(os);
		PrefUtils.exportSharedPrefs(ctx, names, zos);
		zos.finish();
		zos.flush();
		return true;
	}

	/** The file name of a manual backup made now, without the extension. */
	public static String manualName() {
		return "zrAuto_prefs_" + new SimpleDateFormat("ddMMyy", Locale.getDefault()).format(new Date());
	}

	/** The folder automatic backups go to, for display: the picked one, else the app's own. */
	@NonNull
	public static String getDestinationName(Context ctx) {
		PreferenceStore ps = FermataApplication.get().getPreferenceStore();
		if (!ps.getStringPref(AUTO_BACKUP_DIR).isEmpty()) {
			String label = ps.getStringPref(AUTO_BACKUP_DIR_LABEL);
			if (!label.isEmpty()) return label;
		}
		File f = localDir(ctx);
		return (f != null) ? f.getAbsolutePath() : "";
	}

	/** Remembers the folder picked for automatic backups, with a name to show for it. */
	public static void setDestination(Context ctx, Uri tree) {
		PreferenceStore ps = FermataApplication.get().getPreferenceStore();
		String label = null;
		try {
			DocumentFile d = DocumentFile.fromTreeUri(ctx, tree);
			if (d != null) label = d.getName();
		} catch (Exception ex) {
			Log.e(ex, "Failed to read the backup folder's name");
		}
		if ((label == null) || label.isEmpty()) label = Uri.decode(String.valueOf(tree.getLastPathSegment()));
		String auth = String.valueOf(tree.getAuthority());
		if (auth.contains("com.google.android.apps.docs")) label = "Google Drive / " + label;
		ps.applyStringPref(AUTO_BACKUP_DIR_LABEL, label);
		ps.applyStringPref(AUTO_BACKUP_DIR, tree.toString());
	}

	/**
	 * Backs up now to the automatic backup destination.
	 *
	 * @return the name of the file written, or null if it failed
	 */
	@Nullable
	public static String runBackup(Context ctx) {
		PreferenceStore ps = FermataApplication.get().getPreferenceStore();
		String name = PREFIX + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) +
				SUFFIX;
		int keep = KEEP_COUNT[clampIdx(ps.getIntPref(AUTO_BACKUP_KEEP), KEEP_COUNT.length)];

		try {
			String dir = ps.getStringPref(AUTO_BACKUP_DIR);
			DocumentFile tree = dir.isEmpty() ? null : DocumentFile.fromTreeUri(ctx, Uri.parse(dir));

			if ((tree != null) && tree.canWrite()) {
				DocumentFile f = tree.createFile("application/zip", name);
				if (f == null) throw new IOException("Failed to create " + name);
				try (OutputStream os = ctx.getContentResolver().openOutputStream(f.getUri())) {
					if ((os == null) || !writeBackup(ctx, os)) {
						f.delete();
						return null;
					}
				}
				prune(tree, keep);
			} else {
				if (!dir.isEmpty()) {
					DiagnosticLog.log(TAG, "picked folder not writable, using the app's own folder",
							"dir=" + dir);
				}
				File d = localDir(ctx);
				if (d == null) return null;
				File f = new File(d, name);
				try (OutputStream os = new java.io.FileOutputStream(f)) {
					if (!writeBackup(ctx, os)) {
						//noinspection ResultOfMethodCallIgnored
						f.delete();
						return null;
					}
				}
				prune(d, keep);
			}

			ps.applyLongPref(AUTO_BACKUP_LAST, System.currentTimeMillis());
			DiagnosticLog.log(TAG, "saved", name, "keep=" + keep);
			return name;
		} catch (Exception ex) {
			Log.e(ex, "Automatic backup failed");
			DiagnosticLog.log(TAG, "failed", String.valueOf(ex));
			return null;
		}
	}

	private static void prune(DocumentFile tree, int keep) {
		List<DocumentFile> old = new ArrayList<>();
		for (DocumentFile f : tree.listFiles()) {
			String n = f.getName();
			if ((n != null) && n.startsWith(PREFIX) && n.endsWith(SUFFIX)) old.add(f);
		}
		// The time is in the name, so by name is by age.
		Collections.sort(old, (a, b) -> String.valueOf(b.getName()).compareTo(String.valueOf(a.getName())));
		for (int i = keep; i < old.size(); i++) old.get(i).delete();
	}

	private static void prune(File dir, int keep) {
		File[] files = dir.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(SUFFIX));
		if ((files == null) || (files.length <= keep)) return;
		java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
		for (int i = keep; i < files.length; i++) {
			//noinspection ResultOfMethodCallIgnored
			files[i].delete();
		}
	}

	@Nullable
	private static File localDir(Context ctx) {
		File base = ctx.getExternalFilesDir(null);
		if (base == null) base = ctx.getFilesDir();
		File d = new File(base, "backups");
		return (d.isDirectory() || d.mkdirs()) ? d : null;
	}

	private static int clampIdx(int i, int len) {
		return Math.max(0, Math.min(len - 1, i));
	}

	/** Sets, moves or cancels the one pending alarm to match the settings. */
	public static void schedule(Context ctx) {
		AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
		if (am == null) return;
		PendingIntent pi = pendingIntent(ctx);
		PreferenceStore ps = FermataApplication.get().getPreferenceStore();

		if (!ps.getBooleanPref(AUTO_BACKUP)) {
			am.cancel(pi);
			ps.applyLongPref(AUTO_BACKUP_DUE, 0);
			DiagnosticLog.log(TAG, "automatic backup off, alarm cancelled");
			return;
		}

		long at = nextRun(ps, System.currentTimeMillis());
		ps.applyLongPref(AUTO_BACKUP_DUE, at);
		DiagnosticLog.log(TAG, "alarm set for " + stamp(at),
				"last=" + ((ps.getLongPref(AUTO_BACKUP_LAST) == 0) ? "never" :
						stamp(ps.getLongPref(AUTO_BACKUP_LAST))));
		try {
			// Inexact: it may run a little late, which a backup doesn't mind, and needs no special
			// permission. Not a repeating alarm: the next one is set from each run, so a change of
			// interval or hour just replaces it.
			if (SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi);
			else am.set(AlarmManager.RTC, at, pi);
		} catch (Exception ex) {
			Log.e(ex, "Failed to schedule the automatic backup");
			DiagnosticLog.log(TAG, "failed to set the alarm", String.valueOf(ex));
		}
	}

	static long nextRun(PreferenceStore ps, long now) {
		long last = ps.getLongPref(AUTO_BACKUP_LAST);
		int hour = Math.max(0, Math.min(23, ps.getIntPref(AUTO_BACKUP_HOUR)));
		int days = INTERVAL_DAYS[clampIdx(ps.getIntPref(AUTO_BACKUP_INTERVAL), INTERVAL_DAYS.length)];
		Calendar c = Calendar.getInstance();

		if (last == 0) {
			// Never backed up: the next time the clock reaches the chosen hour.
			c.setTimeInMillis(now);
			c.set(Calendar.MINUTE, 0);
			c.set(Calendar.SECOND, 0);
			c.set(Calendar.MILLISECOND, 0);
			c.set(Calendar.HOUR_OF_DAY, hour);
			if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_YEAR, 1);
			return c.getTimeInMillis();
		}

		c.setTimeInMillis(last);
		c.add(Calendar.DAY_OF_YEAR, days);
		c.set(Calendar.HOUR_OF_DAY, hour);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		// Overdue (the phone was off): soon, not all at once with whatever else wakes up.
		return Math.max(c.getTimeInMillis(), now + 60_000L);
	}

	private static PendingIntent pendingIntent(Context ctx) {
		Intent i = new Intent(ctx, BackupReceiver.class).setAction(ACTION_RUN);
		int flags = PendingIntent.FLAG_UPDATE_CURRENT |
				((SDK_INT >= 23) ? PendingIntent.FLAG_IMMUTABLE : 0);
		return PendingIntent.getBroadcast(ctx, REQ_CODE, i, flags);
	}
}
