package me.aap.fermata.util;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.app.App;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * An opt-in, user-readable event trace for diagnosing things that can only be reproduced in the
 * car -- most immediately an Android Auto display takeover (a reversing/360 camera, or the car's
 * own system briefly taking the projected screen) leaving playback stopped.
 * <p>
 * Deliberately separate from {@link Log}, which mirrors everything to logcat and to the app-wide
 * log file: that needs a cable and a laptop to read, which is exactly what isn't available mid
 * drive. This one records only the handful of events that matter for such an investigation, is
 * off unless the user turns it on in Settings, and can be read, copied and shared from inside the
 * app afterwards.
 * <p>
 * Every entry also goes to {@link Log#i} so an adb capture, when one is possible, still lines the
 * two up.
 *
 * <h3>Threading</h3>
 * {@link #log} is safe from any thread and does no I/O on the caller: entries land in a bounded
 * in-memory ring buffer synchronously (so {@link #dump()} is instant and always complete for this
 * process) and are mirrored to a file on a background thread purely so a trace survives the app
 * being killed between the drive and the user getting round to reading it.
 */
public final class DiagnosticLog {
	/**
	 * Entries kept. A takeover investigation needs the minutes around the event, not hours, and
	 * everything here has to stay comfortably within what a clipboard/share intent can carry.
	 */
	private static final int MAX_ENTRIES = 1000;
	private static final String FILE_NAME = "zrauto-diagnostics.log";
	private static final long TOAST_MIN_INTERVAL_MS = 700L;
	private static final ArrayDeque<String> entries = new ArrayDeque<>(MAX_ENTRIES);
	private static final SimpleDateFormat stamp =
			new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);
	private static volatile boolean enabled;
	private static volatile boolean toasts;
	private static long lastToastAt;
	/**
	 * Held in a field rather than passed as a bare lambda: {@code EventBroadcaster} keeps its
	 * listeners as {@link java.lang.ref.WeakReference}s, so a listener nothing else references is
	 * collected at the first GC -- which would leave the Settings toggle silently doing nothing
	 * after a while, the worst possible failure mode for a switch someone flips before a drive.
	 */
	@SuppressWarnings("FieldCanBeLocal")
	private static PreferenceStore.Listener prefsListener;

	private DiagnosticLog() {
	}

	/**
	 * Picks up the current preference values and, if logging is already on, reloads whatever a
	 * previous run left behind so the user doesn't lose a trace to an app restart. Call once at
	 * application startup; re-reads the prefs on every change afterwards.
	 */
	public static void init() {
		MainActivityPrefs prefs = MainActivityPrefs.get();
		applyPrefs(prefs);
		prefsListener = (store, changed) -> {
			if (changed.contains(MainActivityPrefs.DEBUG_LOG_ENABLED) ||
					changed.contains(MainActivityPrefs.DEBUG_LOG_TOASTS)) {
				applyPrefs(MainActivityPrefs.get());
			}
		};
		prefs.addBroadcastListener(prefsListener);
	}

	private static void applyPrefs(MainActivityPrefs prefs) {
		boolean on = prefs.getBooleanPref(MainActivityPrefs.DEBUG_LOG_ENABLED);
		toasts = on && prefs.getBooleanPref(MainActivityPrefs.DEBUG_LOG_TOASTS);
		if (on == enabled) return;
		enabled = on;
		if (!on) return;
		// Turning it on mid-session shouldn't discard an earlier run's trace, and the very first
		// enable has nothing to load -- both are just "read the file if there is one".
		App.get().execute(() -> {
			List<String> previous = readFile();
			synchronized (entries) {
				if (entries.isEmpty() && !previous.isEmpty()) {
					for (String e : previous) {
						if (entries.size() >= MAX_ENTRIES) entries.pollFirst();
						entries.addLast(e);
					}
				}
			}
			log("DIAG", "logging enabled");
		});
	}

	public static boolean isEnabled() {
		return enabled;
	}

	/**
	 * Records one event. {@code event} is a short stable label to scan/grep for ("FOCUS", "YT",
	 * "LIFECYCLE"); {@code details} are appended space-separated. A no-op unless the user turned
	 * logging on, so call sites can stay unconditional.
	 */
	public static void log(String event, Object... details) {
		if (!enabled) return;
		StringBuilder sb = new StringBuilder(64);
		synchronized (stamp) {
			sb.append(stamp.format(new Date()));
		}
		sb.append(' ').append(event);
		for (Object d : details) sb.append(' ').append(d);
		String line = sb.toString();
		Log.i("[diag] ", line);

		synchronized (entries) {
			if (entries.size() >= MAX_ENTRIES) entries.pollFirst();
			entries.addLast(line);
		}
		App.get().execute(() -> appendToFile(line));
	}

	/**
	 * Records an event and, when the user also enabled event toasts, puts it on screen -- the point
	 * being to know an interruption happened while it is happening, without staring at a log. Rate
	 * limited, since a toast queue that outlives the event it describes is worse than no toast.
	 */
	public static void logAndToast(String event, Object... details) {
		log(event, details);
		if (!toasts) return;
		long now = SystemClock.elapsedRealtime();
		synchronized (DiagnosticLog.class) {
			if ((now - lastToastAt) < TOAST_MIN_INTERVAL_MS) return;
			lastToastAt = now;
		}
		StringBuilder sb = new StringBuilder(event);
		for (Object d : details) sb.append(' ').append(d);
		String msg = sb.toString();
		App.get().getHandler().post(() -> {
			try {
				Context ctx = FermataApplication.get();
				Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
			} catch (Exception err) {
				Log.d(err, "Failed to show diagnostic toast");
			}
		});
	}

	/** Everything recorded so far, oldest first, newline separated. Empty string if nothing. */
	@NonNull
	public static String dump() {
		List<String> copy;
		synchronized (entries) {
			if (entries.isEmpty()) return "";
			copy = new ArrayList<>(entries);
		}
		StringBuilder sb = new StringBuilder(copy.size() * 64);
		for (String e : copy) sb.append(e).append('\n');
		return sb.toString();
	}

	public static int size() {
		synchronized (entries) {
			return entries.size();
		}
	}

	public static void clear() {
		synchronized (entries) {
			entries.clear();
		}
		App.get().execute(() -> {
			File f = file();
			if ((f != null) && f.isFile() && !f.delete()) Log.d("Failed to delete ", f);
		});
	}

	@Nullable
	private static File file() {
		try {
			File dir = FermataApplication.get().getFilesDir();
			return (dir == null) ? null : new File(dir, FILE_NAME);
		} catch (Exception err) {
			Log.d(err, "Failed to resolve the diagnostic log file");
			return null;
		}
	}

	private static void appendToFile(String line) {
		File f = file();
		if (f == null) return;
		try (FileWriter w = new FileWriter(f, true)) {
			w.write(line);
			w.write('\n');
		} catch (IOException err) {
			Log.d(err, "Failed to append to the diagnostic log file");
			return;
		}
		// The in-memory buffer is already bounded; this keeps the on-disk mirror from growing past
		// roughly the same horizon across a long series of sessions. Rewriting from the buffer (not
		// trimming the file in place) keeps the two consistent.
		try {
			if (f.length() <= (long) MAX_ENTRIES * 128L) return;
			List<String> copy;
			synchronized (entries) {
				copy = new ArrayList<>(entries);
			}
			try (FileWriter w = new FileWriter(f, false)) {
				for (String e : copy) {
					w.write(e);
					w.write('\n');
				}
			}
		} catch (IOException err) {
			Log.d(err, "Failed to trim the diagnostic log file");
		}
	}

	@NonNull
	private static List<String> readFile() {
		File f = file();
		List<String> lines = new ArrayList<>();
		if ((f == null) || !f.isFile()) return lines;
		try (BufferedReader r = new BufferedReader(new FileReader(f))) {
			for (String l = r.readLine(); l != null; l = r.readLine()) {
				if (!l.isEmpty()) lines.add(l);
			}
		} catch (IOException err) {
			Log.d(err, "Failed to read the diagnostic log file");
		}
		int extra = lines.size() - MAX_ENTRIES;
		return (extra > 0) ? new ArrayList<>(lines.subList(extra, lines.size())) : lines;
	}
}
