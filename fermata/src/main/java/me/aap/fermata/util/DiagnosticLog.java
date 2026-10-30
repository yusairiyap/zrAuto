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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.app.App;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * An opt-in, user-readable event trace for diagnosing things that can only be reproduced in the car, most immediately an
 * Android Auto display takeover (a reversing/360 camera, or the car's own system briefly taking the projected screen)
 * leaving playback stopped.
 * <p>
 * Deliberately separate from {@link Log}, which mirrors everything to logcat and to the app-wide log file: that needs a
 * cable and a laptop to read, which is exactly what isn't available mid drive. This one records only the events that
 * matter for such an investigation, is off unless the user turns it on in Settings, and can be read, filtered, copied and
 * shared from inside the app afterwards (Settings &gt; Other &gt; Diagnostics, or the main menu).
 * <p>
 * Every entry also goes to {@link Log#i} so an adb capture, when one is possible, still lines the two up.
 *
 * <h3>The one line format (follow it for every new call)</h3>
 * <pre>
 * MM-dd HH:mm:ss.SSS L/CATEGORY message | key=value key=value
 * 10-28 14:03:22.123 I/YT playing | id=dQw4w9WgXcQ title="Never Gonna Give You Up"
 * </pre>
 * <ul>
 * <li><b>L</b> is the level: {@code D} debug (noisy detail), {@code I} information (something happened), {@code W}
 * warning (something went wrong and was handled or retried), {@code E} error (it did not work). Use {@link #d},
 * {@link #log} (information), {@link #w} or {@link #e}.</li>
 * <li><b>CATEGORY</b> is a {@link LogCat} constant, never a literal.</li>
 * <li><b>message</b> is a short phrase in lower case that says what happened and <em>never contains data that changes</em>
 * (no ids, positions, names): the same event always has the same message, so it can be searched for and counted.</li>
 * <li><b>details</b> are {@code "key=" + value} strings after it, one per fact, keys in camelCase with no spaces. A value
 * with spaces is quoted for you. A {@link Throwable} passed as a detail becomes {@code error=Class: message}. Anything
 * that is not a {@code key=value} string is kept as {@code note=...} so nothing is lost, but do not rely on that.</li>
 * <li>A stack trace (a crash) follows the line, each of its lines starting with a tab.</li>
 * </ul>
 * Example call: {@code DiagnosticLog.log(LogCat.YT, "playing", "id=" + id, "title=" + title)}; a failure:
 * {@code DiagnosticLog.w(LogCat.YTDL, "size estimate failed", "id=" + id, ex)}.
 *
 * <h3>Threading</h3>
 * {@link #log} is safe from any thread and does no I/O on the caller: entries land in a bounded in-memory ring buffer
 * synchronously (so {@link #snapshot()} is instant and always complete for this process) and are mirrored to a file on a
 * background thread purely so a trace survives the app being killed between the drive and the user getting round to
 * reading it.
 */
public final class DiagnosticLog {
	/**
	 * Entries kept. A takeover investigation needs the minutes around the event, not hours, and everything here has to
	 * stay comfortably within what a clipboard/share intent can carry.
	 */
	private static final int MAX_ENTRIES = 1000;
	private static final String FILE_NAME = "zrauto-diagnostics.log";
	private static final long TOAST_MIN_INTERVAL_MS = 700L;
	private static final ArrayDeque<Entry> entries = new ArrayDeque<>(MAX_ENTRIES);
	private static final SimpleDateFormat stamp =
			new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);
	private static final SimpleDateFormat fullStamp =
			new SimpleDateFormat("yyyy MM-dd HH:mm:ss.SSS", Locale.ROOT);
	/** {@code 10-28 14:03:22.123 I/YT playing | id=x}: time, level, category, then the rest. */
	private static final Pattern LINE =
			Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ([DIWE])/([A-Z0-9_]+) ?(.*)$");
	/** The shape before the format existed: {@code 10-28 14:03:22.123 EVENT rest}. */
	private static final Pattern LEGACY =
			Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ([A-Z0-9_]+) ?(.*)$");
	private static final Pattern KEY_VALUE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]*=.*", Pattern.DOTALL);
	private static final String DETAILS_SEPARATOR = " | ";
	private static volatile boolean enabled;
	private static volatile boolean toasts;
	private static long lastToastAt;
	/**
	 * Held in a field rather than passed as a bare lambda: {@code EventBroadcaster} keeps its listeners as
	 * {@link java.lang.ref.WeakReference}s, so a listener nothing else references is collected at the first GC, which
	 * would leave the Settings toggle silently doing nothing after a while, the worst possible failure mode for a switch
	 * someone flips before a drive.
	 */
	@SuppressWarnings("FieldCanBeLocal")
	private static PreferenceStore.Listener prefsListener;

	private DiagnosticLog() {
	}

	// ---------------------------------------------------------------------------------------------
	// Entries

	/** The level of an entry, least to most serious. */
	public enum Level {
		DEBUG('D'), INFO('I'), WARN('W'), ERROR('E');

		public final char letter;

		Level(char letter) {
			this.letter = letter;
		}

		static Level of(char c) {
			for (Level l : values()) if (l.letter == c) return l;
			return INFO;
		}
	}

	/** One recorded line: when, how serious, from where, what happened and the facts around it. */
	public static final class Entry {
		/** Milliseconds since the epoch (0 when a line from the file could not be dated). */
		public final long time;
		public final Level level;
		public final String category;
		public final String message;
		/** The {@code key=value} part, space separated; empty when there is none. */
		public final String details;
		/** Further lines (a stack trace), or empty. Each line without its leading tab. */
		public final String extra;
		private final String clock;

		Entry(long time, String clock, Level level, String category, String message, String details, String extra) {
			this.time = time;
			this.clock = clock;
			this.level = level;
			this.category = category;
			this.message = message;
			this.details = details;
			this.extra = extra;
		}

		/** The time as it is written in the line: {@code MM-dd HH:mm:ss.SSS}. */
		public String clock() {
			return clock;
		}

		/** The entry as it is written to the file and exported, with any stack after it, tab indented. */
		public String toLine() {
			StringBuilder sb = new StringBuilder(96 + details.length() + extra.length());
			sb.append(clock).append(' ').append(level.letter).append('/').append(category).append(' ').append(message);
			if (!details.isEmpty()) sb.append(DETAILS_SEPARATOR).append(details);
			if (!extra.isEmpty()) {
				for (String l : extra.split("\n")) sb.append("\n\t").append(l);
			}
			return sb.toString();
		}

		@NonNull
		@Override
		public String toString() {
			return toLine();
		}

		/** Whether the text is in the category, message, details or stack (case insensitive). */
		public boolean matches(String needleLower) {
			return category.toLowerCase(Locale.ROOT).contains(needleLower) ||
					message.toLowerCase(Locale.ROOT).contains(needleLower) ||
					details.toLowerCase(Locale.ROOT).contains(needleLower) ||
					extra.toLowerCase(Locale.ROOT).contains(needleLower);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Set up

	/**
	 * Installs a crash-capturing hook that runs ahead of whatever handler was previously registered (chaining to it
	 * afterward, so existing crash behavior, the system's own "app has stopped" dialog, or this app's own
	 * {@code ActivityDelegate.uncaughtException()} on the paths that install one, is unaffected). Call once, as early as
	 * possible in {@code Application.onCreate()}: {@link #logCrash} needs nothing set up first, so this can run before
	 * {@link #init()}.
	 * <p>
	 * Unlike {@link #log}, a crash is captured unconditionally, whether or not the user ever turned on diagnostic
	 * logging: a crash is exactly the kind of rare, high-value event this feature exists to catch, and someone hitting
	 * one is unlikely to have had the toggle on beforehand (they weren't investigating anything yet, the crash is what
	 * tells them to start).
	 */
	public static void installCrashHandler() {
		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((t, err) -> {
			logCrash(t, err);
			if (previous != null) previous.uncaughtException(t, err);
		});
	}

	/**
	 * Picks up the current preference values and, if logging is already on, reloads whatever a previous run left behind
	 * so the user doesn't lose a trace to an app restart. Call once at application startup; re-reads the prefs on every
	 * change afterwards.
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
		// Turning it on mid-session shouldn't discard an earlier run's trace, and the very first enable has nothing to
		// load: both are just "read the file if there is one".
		App.get().execute(() -> {
			List<Entry> previous = readFile();
			synchronized (entries) {
				if (entries.isEmpty() && !previous.isEmpty()) {
					for (Entry e : previous) {
						if (entries.size() >= MAX_ENTRIES) entries.pollFirst();
						entries.addLast(e);
					}
				}
			}
			log(LogCat.DIAG, "logging enabled");
			logEnvironment();
		});
	}

	/** What this run is: the build, the device, the memory it has, the head of every trace. */
	private static void logEnvironment() {
		try {
			Runtime rt = Runtime.getRuntime();
			log(LogCat.DIAG, "app", "version=" + me.aap.fermata.BuildConfig.VERSION_NAME,
					"code=" + me.aap.fermata.BuildConfig.VERSION_CODE,
					"flavor=" + (me.aap.fermata.BuildConfig.AUTO ? "auto" : "phone"),
					"debug=" + me.aap.fermata.BuildConfig.D);
			log(LogCat.DIAG, "device", "model=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
					"android=" + android.os.Build.VERSION.RELEASE, "sdk=" + android.os.Build.VERSION.SDK_INT,
					"abi=" + ((android.os.Build.SUPPORTED_ABIS.length > 0) ? android.os.Build.SUPPORTED_ABIS[0] : "?"));
			android.content.Context ctx = me.aap.fermata.FermataApplication.get();
			android.app.ActivityManager am =
					(android.app.ActivityManager) ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
			android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
			log(LogCat.DIAG, "resources", "heapMax=" + (rt.maxMemory() >> 20) + "MB",
					"heapUsed=" + ((rt.totalMemory() - rt.freeMemory()) >> 20) + "MB",
					"memClass=" + ((am == null) ? "?" : am.getMemoryClass() + "MB"),
					"screen=" + dm.widthPixels + "x" + dm.heightPixels, "dpi=" + dm.densityDpi,
					"cpus=" + rt.availableProcessors());
		} catch (Throwable ex) {
			w(LogCat.DIAG, "environment unavailable", ex);
		}
	}

	public static boolean isEnabled() {
		return enabled;
	}

	/** The running build as one line: {@code zrAuto 1.2.3 (code 45), phone build}. Shown in the log's screen and export. */
	@NonNull
	public static String appVersion() {
		return "zrAuto " + me.aap.fermata.BuildConfig.VERSION_NAME + " (code " + me.aap.fermata.BuildConfig.VERSION_CODE +
				"), " + (me.aap.fermata.BuildConfig.AUTO ? "Android Auto build" : "phone build") +
				(me.aap.fermata.BuildConfig.D ? ", debug" : "");
	}

	// ---------------------------------------------------------------------------------------------
	// Writing

	/** Records an information entry (something happened). See the class comment for the line format. */
	public static void log(String category, String message, Object... details) {
		add(Level.INFO, category, message, details);
	}

	/** Records a debug entry: detail that is only interesting while chasing a specific problem. */
	public static void d(String category, String message, Object... details) {
		add(Level.DEBUG, category, message, details);
	}

	/** Records a warning: something went wrong and was handled, skipped or retried. */
	public static void w(String category, String message, Object... details) {
		add(Level.WARN, category, message, details);
	}

	/** Records an error: something did not work and stays broken. */
	public static void e(String category, String message, Object... details) {
		add(Level.ERROR, category, message, details);
	}

	private static void add(Level level, String category, String message, Object[] details) {
		if (!enabled) return;
		Entry e = build(level, category, message, details);
		Log.i("[diag] ", e.toLine());
		append(e);
	}

	private static Entry build(Level level, String category, String message, Object[] details) {
		long now = System.currentTimeMillis();
		String clock;
		synchronized (stamp) {
			clock = stamp.format(new Date(now));
		}
		return new Entry(now, clock, level, category(category), oneLine(message), details(details), "");
	}

	private static void append(Entry e) {
		synchronized (entries) {
			if (entries.size() >= MAX_ENTRIES) entries.pollFirst();
			entries.addLast(e);
		}
		App.get().execute(() -> appendToFile(e));
	}

	/**
	 * Records an event and, when the user also enabled event toasts, puts it on screen, the point being to know an
	 * interruption happened while it is happening, without staring at a log. Rate limited, since a toast queue that
	 * outlives the event it describes is worse than no toast. Recorded as a warning.
	 */
	public static void logAndToast(String category, String message, Object... details) {
		w(category, message, details);
		if (!toasts) return;
		long now = SystemClock.elapsedRealtime();
		synchronized (DiagnosticLog.class) {
			if ((now - lastToastAt) < TOAST_MIN_INTERVAL_MS) return;
			lastToastAt = now;
		}
		StringBuilder sb = new StringBuilder(category).append(' ').append(message);
		String d = details(details);
		if (!d.isEmpty()) sb.append(' ').append(d);
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

	/**
	 * Records an uncaught exception, always, regardless of {@link #isEnabled()} (see {@link #installCrashHandler}), and,
	 * unlike {@link #log}, writes the file synchronously and inline rather than handing off to the app's executor: the
	 * process may not survive long enough after this returns for a queued background task to ever run, and a crash that
	 * isn't actually on disk by the time the process dies defeats the entire point of capturing it. Must never itself
	 * throw: that would replace the real crash with this method's, and could still leave the real one unreported if it
	 * happens before the chained previous handler runs.
	 */
	public static void logCrash(Thread thread, Throwable err) {
		try {
			long now = System.currentTimeMillis();
			String clock;
			synchronized (stamp) {
				clock = stamp.format(new Date(now));
			}
			Log.e(err, "Uncaught exception in thread ", thread);
			Entry e = new Entry(now, clock, Level.ERROR, LogCat.CRASH, "uncaught exception",
					details(new Object[]{"thread=" + thread.getName(), "type=" + err.getClass().getName()}),
					android.util.Log.getStackTraceString(err).trim());
			synchronized (entries) {
				if (entries.size() >= MAX_ENTRIES) entries.pollFirst();
				entries.addLast(e);
			}
			appendToFile(e);
		} catch (Throwable ignore) {
			// See the doc comment: this must never add a second failure on top of the real one.
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Formatting

	/** The category as written: upper case letters, digits and underscores only, never empty. */
	private static String category(String c) {
		if ((c == null) || c.isEmpty()) return "MISC";
		StringBuilder sb = new StringBuilder(c.length());
		for (int i = 0; i < c.length(); i++) {
			char ch = Character.toUpperCase(c.charAt(i));
			sb.append(((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) ? ch : '_');
		}
		return sb.toString();
	}

	private static String oneLine(@Nullable String s) {
		if ((s == null) || s.isEmpty()) return "-";
		return s.replace('\r', ' ').replace('\n', ' ').trim();
	}

	/** The details as {@code key=value} tokens separated by spaces (see the class comment). */
	private static String details(Object[] details) {
		if ((details == null) || (details.length == 0)) return "";
		StringBuilder sb = new StringBuilder(details.length * 16);
		for (Object d : details) {
			if (d == null) continue;
			String token;
			if (d instanceof Throwable t) {
				token = "error=" + value(t.getClass().getSimpleName() + ": " + t.getMessage());
			} else {
				String s = String.valueOf(d);
				if (s.isEmpty()) continue;
				if (KEY_VALUE.matcher(s).matches()) {
					int eq = s.indexOf('=');
					token = s.substring(0, eq + 1) + value(s.substring(eq + 1));
				} else {
					token = "note=" + value(s);
				}
			}
			if (sb.length() > 0) sb.append(' ');
			sb.append(token);
		}
		return sb.toString();
	}

	/** A value on one line; quoted when it holds a space or a quote, so a line can be split into tokens again. */
	private static String value(String v) {
		String s = v.replace('\r', ' ').replace('\n', ' ');
		if (s.isEmpty()) return "\"\"";
		if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') return s;
		if ((s.indexOf(' ') < 0) && (s.indexOf('"') < 0)) return s;
		return '"' + s.replace('"', '\'') + '"';
	}

	// ---------------------------------------------------------------------------------------------
	// Reading

	/** A copy of everything recorded so far, oldest first. */
	@NonNull
	public static List<Entry> snapshot() {
		synchronized (entries) {
			return new ArrayList<>(entries);
		}
	}

	/** How many lines each category has in {@code from}, in alphabetical order. */
	@NonNull
	public static TreeMap<String, Integer> countByCategory(List<Entry> from) {
		TreeMap<String, Integer> m = new TreeMap<>();
		for (Entry e : from) m.merge(e.category, 1, Integer::sum);
		return m;
	}

	/** Everything recorded so far, oldest first, newline separated. Empty string if nothing. */
	@NonNull
	public static String dump() {
		List<Entry> copy = snapshot();
		StringBuilder sb = new StringBuilder(copy.size() * 64);
		for (Entry e : copy) sb.append(e.toLine()).append('\n');
		return sb.toString();
	}

	/**
	 * What the Copy and Share buttons produce: a header that says which build wrote it, when it was exported, what
	 * filter was on and how the lines are laid out (so whoever reads it, a person or an AI session, needs nothing else),
	 * then {@code lines}, oldest first.
	 */
	@NonNull
	public static String export(List<Entry> lines, int total, String filterText) {
		StringBuilder sb = new StringBuilder(lines.size() * 80 + 400);
		sb.append("zrAuto diagnostic log\n");
		sb.append("app: ").append(appVersion()).append('\n');
		sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
				.append(", android ").append(android.os.Build.VERSION.RELEASE)
				.append(" (sdk ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
		synchronized (stamp) {
			sb.append("exported: ").append(fullStamp.format(new Date())).append('\n');
		}
		sb.append("filter: ").append(filterText).append('\n');
		sb.append("entries: ").append(lines.size()).append(" of ").append(total).append('\n');
		sb.append("format: MM-dd HH:mm:ss.SSS L/CATEGORY message | key=value (L = D debug, I info, W warning, E error)\n");
		sb.append("---\n");
		for (Entry e : lines) sb.append(e.toLine()).append('\n');
		return sb.toString();
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

	// ---------------------------------------------------------------------------------------------
	// The file

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

	private static void appendToFile(Entry entry) {
		File f = file();
		if (f == null) return;
		try (FileWriter w = new FileWriter(f, true)) {
			w.write(entry.toLine());
			w.write('\n');
		} catch (IOException err) {
			Log.d(err, "Failed to append to the diagnostic log file");
			return;
		}
		// The in-memory buffer is already bounded; this keeps the on-disk mirror from growing past roughly the same
		// horizon across a long series of sessions. Rewriting from the buffer (not trimming the file in place) keeps
		// the two consistent.
		try {
			// Well past what is kept, then down to half of it: a rewrite is not paid on every line.
			if (f.length() <= (long) MAX_ENTRIES * 256L) return;
			List<Entry> copy;
			synchronized (entries) {
				List<Entry> all = new ArrayList<>(entries);
				copy = all.subList(Math.max(0, all.size() - MAX_ENTRIES / 2), all.size());
			}
			try (FileWriter w = new FileWriter(f, false)) {
				for (Entry e : copy) {
					w.write(e.toLine());
					w.write('\n');
				}
			}
		} catch (IOException err) {
			Log.d(err, "Failed to trim the diagnostic log file");
		}
	}

	/** The entries a previous run left, lines of an older shape included (as information, category from the first word). */
	@NonNull
	private static List<Entry> readFile() {
		File f = file();
		List<Entry> out = new ArrayList<>();
		if ((f == null) || !f.isFile()) return out;
		int year = Calendar.getInstance().get(Calendar.YEAR);
		try (BufferedReader r = new BufferedReader(new FileReader(f))) {
			StringBuilder extra = null;
			String[] head = null;
			for (String l = r.readLine(); ; l = r.readLine()) {
				boolean continuation = (l != null) && l.startsWith("\t");
				if ((head != null) && !continuation) {
					out.add(parse(year, head, (extra == null) ? "" : extra.toString()));
					head = null;
					extra = null;
				}
				if (l == null) break;
				if (l.isEmpty()) continue;
				if (continuation) {
					if (head != null) {
						if (extra == null) extra = new StringBuilder();
						else extra.append('\n');
						extra.append(l, 1, l.length());
					}
					continue;
				}
				Matcher m = LINE.matcher(l);
				if (m.matches()) {
					head = new String[]{m.group(1), m.group(2), m.group(3), m.group(4)};
					continue;
				}
				Matcher lm = LEGACY.matcher(l);
				if (lm.matches()) {
					head = new String[]{lm.group(1), "I", lm.group(2), lm.group(3)};
				} else {
					head = new String[]{"00-00 00:00:00.000", "I", "MISC", l};
				}
			}
		} catch (IOException err) {
			Log.d(err, "Failed to read the diagnostic log file");
		}
		int extra = out.size() - MAX_ENTRIES;
		return (extra > 0) ? new ArrayList<>(out.subList(extra, out.size())) : out;
	}

	private static Entry parse(int year, String[] head, String extra) {
		long time = 0;
		try {
			synchronized (stamp) {
				Date d = fullStamp.parse(year + " " + head[0]);
				if (d != null) time = d.getTime();
			}
		} catch (Exception ignore) {
			// Undated: sorts first, still shown.
		}
		String rest = head[3];
		String message = rest;
		String details = "";
		int sep = rest.indexOf(DETAILS_SEPARATOR);
		if (sep >= 0) {
			message = rest.substring(0, sep);
			details = rest.substring(sep + DETAILS_SEPARATOR.length());
		}
		return new Entry(time, head[0], Level.of(head[1].charAt(0)), head[2], message, details, extra);
	}
}
