package me.aap.fermata.addon.power;

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
 * The power usage record: charge drawn from the battery per hour (kept for about two months, for
 * the Day view) and per day (kept for three years), split by what the app was doing ({@code CAT_*})
 * and whether the phone was connected to Android Auto ({@code CTX_*}), together with how long that
 * lasted, so a category's total can be read as an average draw too. The charge that came in while
 * plugged in is kept alongside. Saved to a small text file in the app's files dir, one bucket per
 * line, like the data usage record.
 * <p>
 * Charge is kept in µAh and time in milliseconds; both only ever count the moments the app sampled
 * the battery, i.e. while it was open, playing or connected to a car.
 */
public final class PowerUsageStore {
	/** A video playing (YouTube or a file). */
	public static final int CAT_VIDEO = 0;
	/** Sound only: the Music tab, YouTube as music, an audio file. */
	public static final int CAT_MUSIC = 1;
	/** The app open or connected to a car, but nothing playing. */
	public static final int CAT_IDLE = 2;
	public static final int CATS = 3;
	/** On the phone alone. */
	public static final int CTX_PHONE = 0;
	/** While connected to Android Auto. */
	public static final int CTX_CAR = 1;
	static final int CTXS = 2;
	private static final int SLOTS = CATS * CTXS;
	// A bucket: SLOTS of µAh drawn, SLOTS of milliseconds spent drawing, then the µAh and the
	// milliseconds spent charging.
	private static final int B_USED = 0;
	private static final int B_TIME = SLOTS;
	private static final int B_CHARGED = 2 * SLOTS;
	private static final int B_CHARGE_TIME = 2 * SLOTS + 1;
	private static final int BUCKET = 2 * SLOTS + 2;
	private static final int KEEP_HOURS_DAYS = 62;
	private static final int KEEP_DAYS = 3 * 366;
	private static final String FILE_NAME = "power_usage.txt";

	private final TreeMap<Long, long[]> hours = new TreeMap<>();
	private final TreeMap<Long, long[]> days = new TreeMap<>();
	private final AtomicFile file;
	private boolean dirty;
	private boolean saving;

	PowerUsageStore() {
		file = new AtomicFile(new File(FermataApplication.get().getFilesDir(), FILE_NAME));
		load();
	}

	/**
	 * Books a stretch of {@code ms} milliseconds at {@code time} during which the battery gave
	 * {@code microAh} (positive) or took {@code -microAh} (charging).
	 */
	synchronized void add(long time, int cat, int ctx, long microAh, long ms, boolean charging) {
		if (ms <= 0) return;
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(time);
		add(bucket(hours, hourKey(c)), cat, ctx, microAh, ms, charging);
		add(bucket(days, dayKey(c)), cat, ctx, microAh, ms, charging);
		dirty = true;
	}

	private static void add(long[] b, int cat, int ctx, long microAh, long ms, boolean charging) {
		if (charging) {
			b[B_CHARGED] += Math.max(0, microAh);
			b[B_CHARGE_TIME] += ms;
		} else {
			int slot = cat * CTXS + ctx;
			b[B_USED + slot] += Math.max(0, microAh);
			b[B_TIME + slot] += ms;
		}
	}

	private static long[] bucket(TreeMap<Long, long[]> m, long key) {
		long[] b = m.get(key);
		if (b == null) m.put(key, b = new long[BUCKET]);
		return b;
	}

	/** Forgets everything recorded so far. */
	synchronized void clear() {
		hours.clear();
		days.clear();
		dirty = true;
	}

	/** One period's figures, as {@link #hour}, {@link #days} and {@link #total} return them. */
	public static final class Usage {
		/** µAh drawn per category. */
		public final long[] used = new long[CATS];
		/** Milliseconds spent drawing per category. */
		public final long[] time = new long[CATS];
		/** The same, while connected to Android Auto only. */
		public final long carUsed;
		public final long carTime;
		/** The same, on the phone alone. */
		public final long phoneUsed;
		public final long phoneTime;
		public final long charged;
		public final long chargeTime;

		Usage(long[] sum) {
			long cu = 0, ct = 0, pu = 0, pt = 0;
			for (int cat = 0; cat < CATS; cat++) {
				for (int ctx = 0; ctx < CTXS; ctx++) {
					int slot = cat * CTXS + ctx;
					used[cat] += sum[B_USED + slot];
					time[cat] += sum[B_TIME + slot];
					if (ctx == CTX_CAR) {
						cu += sum[B_USED + slot];
						ct += sum[B_TIME + slot];
					} else {
						pu += sum[B_USED + slot];
						pt += sum[B_TIME + slot];
					}
				}
			}
			carUsed = cu;
			carTime = ct;
			phoneUsed = pu;
			phoneTime = pt;
			charged = sum[B_CHARGED];
			chargeTime = sum[B_CHARGE_TIME];
		}

		public long totalUsed() {
			return sum(used);
		}

		public long totalTime() {
			return sum(time);
		}
	}

	public synchronized Usage hour(long hourKey) {
		long[] b = hours.get(hourKey);
		return new Usage((b == null) ? new long[BUCKET] : b);
	}

	/** Everything from {@code fromDay} to {@code toDay}, both included. */
	public synchronized Usage days(long fromDay, long toDay) {
		long[] sum = new long[BUCKET];
		if (fromDay <= toDay) {
			for (long[] b : days.subMap(fromDay, true, toDay, true).values()) addTo(sum, b);
		}
		return new Usage(sum);
	}

	/** Everything recorded (since the last reset). */
	public synchronized Usage total() {
		long[] sum = new long[BUCKET];
		for (long[] b : days.values()) addTo(sum, b);
		return new Usage(sum);
	}

	private static void addTo(long[] sum, long[] b) {
		for (int i = 0; i < BUCKET; i++) sum[i] += b[i];
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

	private synchronized void load() {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(file.openRead(),
				StandardCharsets.UTF_8))) {
			for (String line = r.readLine(); line != null; line = r.readLine()) {
				String[] f = line.trim().split(" ");
				if (f.length != BUCKET + 2) continue;
				TreeMap<Long, long[]> m = "H".equals(f[0]) ? hours : "D".equals(f[0]) ? days : null;
				if (m == null) continue;
				long[] b = new long[BUCKET];
				for (int i = 0; i < BUCKET; i++) b[i] = Long.parseLong(f[i + 2]);
				m.put(Long.parseLong(f[1]), b);
			}
		} catch (java.io.FileNotFoundException ignore) {
			// Nothing recorded yet.
		} catch (Exception ex) {
			Log.e(ex, "Failed to read power usage");
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
			StringBuilder sb = new StringBuilder((hours.size() + days.size()) * 96);
			append(sb, "H", hours);
			append(sb, "D", days);
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
			Log.e(ex, "Failed to save power usage");
		}
	}

	private void prune() {
		Calendar c = Calendar.getInstance();
		c.add(Calendar.DAY_OF_YEAR, -KEEP_HOURS_DAYS);
		hours.headMap(hourKey(c)).clear();
		c = Calendar.getInstance();
		c.add(Calendar.DAY_OF_YEAR, -KEEP_DAYS);
		days.headMap(dayKey(c)).clear();
	}

	private static void append(StringBuilder sb, String type, @NonNull TreeMap<Long, long[]> m) {
		for (Map.Entry<Long, long[]> e : m.entrySet()) {
			sb.append(type).append(' ').append(e.getKey());
			for (long v : e.getValue()) sb.append(' ').append(v);
			sb.append('\n');
		}
	}
}
