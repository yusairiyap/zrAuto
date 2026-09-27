package me.aap.fermata.addon.data;

import android.util.AtomicFile;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Map;
import java.util.TreeMap;

import me.aap.fermata.FermataApplication;
import me.aap.utils.app.App;
import me.aap.utils.log.Log;

/**
 * The data usage record: bytes per hour (kept for about two months, for the Day view) and per day
 * (kept for three years), each split by what used them ({@code CAT_*}) and over which kind of
 * network ({@code NET_*}). Saved to a small text file in the app's files dir, one bucket per line,
 * so it survives app updates and restarts and costs next to nothing to read back.
 */
public final class DataUsageStore {
	/** YouTube videos, played as video. */
	public static final int CAT_VIDEO = 0;
	/** YouTube played in music mode (the Music tab, "Play as music"). */
	public static final int CAT_MUSIC = 1;
	/** Everything else the app downloads: thumbnails, browsing, other streams. */
	public static final int CAT_OTHER = 2;
	public static final int CATS = 3;
	public static final int NET_MOBILE = 0;
	public static final int NET_OTHER = 1;
	static final int NETS = 2;
	private static final int SLOTS = CATS * NETS;
	private static final int KEEP_HOURS_DAYS = 62;
	private static final int KEEP_DAYS = 3 * 366;
	private static final String FILE_NAME = "data_usage.txt";

	private final TreeMap<Long, long[]> hours = new TreeMap<>();
	private final TreeMap<Long, long[]> days = new TreeMap<>();
	// Milliseconds of playback per category ({@code CAT_*}), by hour and by day: what the bytes
	// above bought, so a category's usage can be read against how long it was actually played.
	private final TreeMap<Long, long[]> playHours = new TreeMap<>();
	private final TreeMap<Long, long[]> playDays = new TreeMap<>();
	private final AtomicFile file;
	private boolean dirty;
	private boolean saving;

	DataUsageStore() {
		file = new AtomicFile(new File(FermataApplication.get().getFilesDir(), FILE_NAME));
		load();
	}

	/** Adds {@code bytes} used at {@code time} to its hour and day. */
	synchronized void add(long time, int cat, int net, long bytes) {
		if (bytes <= 0) return;
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(time);
		int slot = cat * NETS + net;
		bucket(hours, hourKey(c))[slot] += bytes;
		bucket(days, dayKey(c))[slot] += bytes;
		dirty = true;
	}

	/** Adds {@code ms} of playback in category {@code cat} at {@code time} to its hour and day. */
	synchronized void addPlayTime(long time, int cat, long ms) {
		if ((ms <= 0) || (cat < 0) || (cat >= CATS)) return;
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(time);
		bucket(playHours, hourKey(c), CATS)[cat] += ms;
		bucket(playDays, dayKey(c), CATS)[cat] += ms;
		dirty = true;
	}

	private static long[] bucket(TreeMap<Long, long[]> m, long key) {
		return bucket(m, key, SLOTS);
	}

	private static long[] bucket(TreeMap<Long, long[]> m, long key, int size) {
		long[] b = m.get(key);
		if (b == null) m.put(key, b = new long[size]);
		return b;
	}

	/** Milliseconds played per category during that hour. */
	public synchronized long[] playTimeHour(long hourKey) {
		long[] b = playHours.get(hourKey);
		return (b == null) ? new long[CATS] : b.clone();
	}

	/** Milliseconds played per category from {@code fromDay} to {@code toDay}, both included. */
	public synchronized long[] playTimeDays(long fromDay, long toDay) {
		long[] sum = new long[CATS];
		if (fromDay > toDay) return sum;
		for (long[] b : playDays.subMap(fromDay, true, toDay, true).values()) {
			for (int i = 0; i < CATS; i++) sum[i] += b[i];
		}
		return sum;
	}

	/** Milliseconds played per category, all time (since the last reset). */
	public synchronized long[] playTimeTotal() {
		long[] sum = new long[CATS];
		for (long[] b : playDays.values()) {
			for (int i = 0; i < CATS; i++) sum[i] += b[i];
		}
		return sum;
	}

	/** Forgets everything recorded so far. */
	synchronized void clear() {
		hours.clear();
		days.clear();
		playHours.clear();
		playDays.clear();
		dirty = true;
	}

	/** Bytes per category ({@code CAT_*}) used during that hour. */
	public synchronized long[] hour(long hourKey, boolean mobileOnly) {
		return byCategory(hours.get(hourKey), mobileOnly);
	}

	/** Bytes per category used from {@code fromDay} to {@code toDay}, both included. */
	public synchronized long[] days(long fromDay, long toDay, boolean mobileOnly) {
		long[] sum = new long[CATS];
		if (fromDay > toDay) return sum;
		for (long[] b : days.subMap(fromDay, true, toDay, true).values()) {
			addByCategory(sum, b, mobileOnly);
		}
		return sum;
	}

	/** Bytes per category used, all time (since the last reset). */
	public synchronized long[] total(boolean mobileOnly) {
		long[] sum = new long[CATS];
		for (long[] b : days.values()) addByCategory(sum, b, mobileOnly);
		return sum;
	}

	/** Whether the Day view can split {@code dayKey} by hour (hours are only kept for a while). */
	public synchronized boolean hasHours(long dayKey) {
		Long first = hours.isEmpty() ? null : hours.firstKey();
		return (first != null) && (first / 100 <= dayKey);
	}

	/** The first day anything was recorded on, or 0. */
	public synchronized long firstDay() {
		return days.isEmpty() ? 0 : days.firstKey();
	}

	private static long[] byCategory(long[] b, boolean mobileOnly) {
		long[] r = new long[CATS];
		if (b != null) addByCategory(r, b, mobileOnly);
		return r;
	}

	private static void addByCategory(long[] sum, long[] b, boolean mobileOnly) {
		for (int cat = 0; cat < CATS; cat++) {
			sum[cat] += b[cat * NETS + NET_MOBILE];
			if (!mobileOnly) sum[cat] += b[cat * NETS + NET_OTHER];
		}
	}

	public static long hourKey(Calendar c) {
		return dayKey(c) * 100 + c.get(Calendar.HOUR_OF_DAY);
	}

	public static long dayKey(Calendar c) {
		return c.get(Calendar.YEAR) * 10000L + (c.get(Calendar.MONTH) + 1) * 100L +
				c.get(Calendar.DAY_OF_MONTH);
	}

	public static long sum(long[] v) {
		long s = 0;
		for (long l : v) s += l;
		return s;
	}

	// ---------------------------------------------------------------------------------------------
	// Persistence
	// ---------------------------------------------------------------------------------------------

	private synchronized void load() {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(file.openRead(),
				StandardCharsets.UTF_8))) {
			for (String line = r.readLine(); line != null; line = r.readLine()) {
				String[] f = line.trim().split(" ");
				if ((f.length == CATS + 2) && ("TH".equals(f[0]) || "TD".equals(f[0]))) {
					long[] b = new long[CATS];
					for (int i = 0; i < CATS; i++) b[i] = Long.parseLong(f[i + 2]);
					("TH".equals(f[0]) ? playHours : playDays).put(Long.parseLong(f[1]), b);
					continue;
				}
				if (f.length != SLOTS + 2) continue;
				TreeMap<Long, long[]> m = "H".equals(f[0]) ? hours : "D".equals(f[0]) ? days : null;
				if (m == null) continue;
				long[] b = new long[SLOTS];
				for (int i = 0; i < SLOTS; i++) b[i] = Long.parseLong(f[i + 2]);
				m.put(Long.parseLong(f[1]), b);
			}
		} catch (java.io.FileNotFoundException ignore) {
			// Nothing recorded yet.
		} catch (Exception ex) {
			Log.e(ex, "Failed to read data usage");
		}
	}

	/** Writes the record out in the background, if anything changed since it was last saved. */
	void saveAsync() {
		synchronized (this) {
			if (!dirty || saving) return;
			saving = true;
		}
		App.get().getExecutor().submitTask(this::save);
	}

	private void save() {
		String text;

		synchronized (this) {
			saving = false;
			if (!dirty) return;
			dirty = false;
			prune();
			StringBuilder sb = new StringBuilder((hours.size() + days.size()) * 48);
			append(sb, "H", hours);
			append(sb, "D", days);
			append(sb, "TH", playHours);
			append(sb, "TD", playDays);
			text = sb.toString();
		}

		FileOutputStream out = null;
		try {
			out = file.startWrite();
			Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
			w.write(text);
			w.flush();
			file.finishWrite(out);
		} catch (Exception ex) {
			if (out != null) file.failWrite(out);
			synchronized (this) {
				dirty = true;
			}
			Log.e(ex, "Failed to save data usage");
		}
	}

	private void prune() {
		Calendar c = Calendar.getInstance();
		c.add(Calendar.DAY_OF_YEAR, -KEEP_HOURS_DAYS);
		hours.headMap(hourKey(c)).clear();
		playHours.headMap(hourKey(c)).clear();
		c = Calendar.getInstance();
		c.add(Calendar.DAY_OF_YEAR, -KEEP_DAYS);
		days.headMap(dayKey(c)).clear();
		playDays.headMap(dayKey(c)).clear();
	}

	private static void append(StringBuilder sb, String type, @NonNull TreeMap<Long, long[]> m) {
		for (Map.Entry<Long, long[]> e : m.entrySet()) {
			sb.append(type).append(' ').append(e.getKey());
			for (long v : e.getValue()) sb.append(' ').append(v);
			sb.append('\n');
		}
	}
}
