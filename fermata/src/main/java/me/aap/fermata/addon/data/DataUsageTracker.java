package me.aap.fermata.addon.data;

import static me.aap.fermata.addon.data.DataUsageStore.CAT_MUSIC;
import static me.aap.fermata.addon.data.DataUsageStore.CAT_OTHER;
import static me.aap.fermata.addon.data.DataUsageStore.CAT_VIDEO;
import static me.aap.fermata.addon.data.DataUsageStore.NET_MOBILE;
import static me.aap.fermata.addon.data.DataUsageStore.NET_OTHER;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Process;
import android.os.SystemClock;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.utils.app.App;
import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Measures how much internet data the app uses, and what for. Android counts every byte each app
 * sends and receives ({@link TrafficStats}, per app, since the phone started): this reads that
 * count every few seconds and books the difference to what was going on meanwhile -- a YouTube
 * video, YouTube in music mode, or anything else -- and to the kind of network it went over.
 * <p>
 * Nothing is measured while the app isn't running, since the app uses no data then; the count
 * the app last saw is kept, though, so the few seconds between the last reading and the app being
 * closed still get booked the next time it starts.
 * <p>
 * Also warns (once per cycle) when usage passes the warning level or the limit set in the Data
 * Usage settings.
 */
public final class DataUsageTracker implements MediaSessionCallback.Listener {
	public static final int CYCLE_DAY = 0;
	public static final int CYCLE_WEEK = 1;
	public static final int CYCLE_MONTH = 2;
	public static final int NETWORKS_ALL = 0;
	public static final int NETWORKS_MOBILE = 1;
	/** The data limit, in GB; 0 is none. */
	public static final Pref<DoubleSupplier> LIMIT_GB = Pref.f("DATA_USAGE_LIMIT_GB", 0f);
	/** Warn once usage reaches this many GB; 0 is never. */
	public static final Pref<DoubleSupplier> WARNING_GB = Pref.f("DATA_USAGE_WARNING_GB", 0f);
	/** What the limit is for: a day, a week or a month ({@code CYCLE_*}). */
	public static final Pref<IntSupplier> CYCLE = Pref.i("DATA_USAGE_CYCLE", CYCLE_MONTH);
	/** The day of the month a monthly cycle starts on, 1 to 28. */
	public static final Pref<IntSupplier> CYCLE_START = Pref.i("DATA_USAGE_CYCLE_START", 1);
	/** What's counted: all networks, or mobile data only ({@code NETWORKS_*}). */
	public static final Pref<IntSupplier> NETWORKS = Pref.i("DATA_USAGE_NETWORKS", NETWORKS_ALL);
	/** When counting started, i.e. the last reset. */
	public static final Pref<LongSupplier> SINCE = Pref.l("DATA_USAGE_SINCE", 0);
	private static final Pref<LongSupplier> LAST_BYTES = Pref.l("DATA_USAGE_LAST_BYTES", -1);
	private static final Pref<LongSupplier> LAST_BOOT = Pref.l("DATA_USAGE_LAST_BOOT", 0);
	private static final Pref<LongSupplier> ALERT_CYCLE = Pref.l("DATA_USAGE_ALERT_CYCLE", 0);
	private static final Pref<IntSupplier> ALERT_LEVEL = Pref.i("DATA_USAGE_ALERT_LEVEL", 0);
	private static final long INTERVAL = 10_000;
	private static final long SAVE_INTERVAL = 60_000;
	private static final long MIN_SAMPLE_GAP = 1000;
	private static final long GB = 1000L * 1000L * 1000L;
	@Nullable
	private static DataUsageTracker instance;

	private final DataUsageStore store = new DataUsageStore();
	private final List<Runnable> listeners = new ArrayList<>(2);
	private final Runnable tick = this::tick;
	private final int uid = Process.myUid();
	private WeakReference<MediaSessionCallback> callback = new WeakReference<>(null);
	private boolean started;
	private boolean supported = true;
	private long lastBytes = -1;
	private long lastSample;
	private long lastSave;
	private int lastCat = CAT_OTHER;
	private int lastNet = NET_OTHER;

	private DataUsageTracker() {
		PreferenceStore ps = prefs();
		if (ps.getLongPref(SINCE) == 0) ps.applyLongPref(SINCE, System.currentTimeMillis());
	}

	/** Main thread only. */
	@NonNull
	public static DataUsageTracker get() {
		DataUsageTracker t = instance;
		if (t == null) instance = t = new DataUsageTracker();
		return t;
	}

	public static PreferenceStore prefs() {
		return FermataApplication.get().getPreferenceStore();
	}

	public DataUsageStore getStore() {
		return store;
	}

	/** Whether this device reports the app's data use at all (a few very old ones don't). */
	public boolean isSupported() {
		return supported;
	}

	/** Starts measuring, if not yet; and follows what {@code cb} plays, to tell what data is for. */
	public void start(@Nullable MediaSessionCallback cb) {
		if ((cb != null) && (callback.get() != cb)) {
			MediaSessionCallback old = callback.get();
			if (old != null) old.removeBroadcastListener(this);
			callback = new WeakReference<>(cb);
			cb.addBroadcastListener(this);
		}
		if (started) return;
		started = true;
		resume();
		App.get().getHandler().postDelayed(tick, INTERVAL);
	}

	/** Stops measuring (the addon was disabled). */
	public void stop() {
		if (!started) return;
		sample();
		started = false;
		App.get().getHandler().removeCallbacks(tick);
		MediaSessionCallback cb = callback.get();
		if (cb != null) cb.removeBroadcastListener(this);
		callback = new WeakReference<>(null);
		save();
	}

	/** Takes a reading now and saves the record, e.g. when the app goes to the background. */
	public void flush() {
		if (!started) return;
		sample();
		save();
	}

	/** Forgets all usage recorded so far, and starts counting again from now. */
	public void reset() {
		sample();
		store.clear();
		PreferenceStore ps = prefs();
		try (PreferenceStore.Edit e = ps.editPreferenceStore()) {
			e.setLongPref(SINCE, System.currentTimeMillis());
			e.setLongPref(ALERT_CYCLE, 0);
			e.setIntPref(ALERT_LEVEL, 0);
		}
		save();
		notifyListeners();
	}

	/** Called on the main thread after each reading that found new usage. */
	public void addListener(Runnable l) {
		if (!listeners.contains(l)) listeners.add(l);
	}

	public void removeListener(Runnable l) {
		listeners.remove(l);
	}

	@Override
	public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
		// Book what was used so far to what was playing so far, before what's playing changes.
		if (started && (SystemClock.elapsedRealtime() - lastSample >= MIN_SAMPLE_GAP)) sample();
	}

	private void tick() {
		if (!started) return;
		sample();
		if (SystemClock.elapsedRealtime() - lastSave >= SAVE_INTERVAL) save();
		App.get().getHandler().postDelayed(tick, INTERVAL);
	}

	/**
	 * The very first reading: carries on from the count saved before the app last closed, if the
	 * phone hasn't restarted since (the count starts over from 0 when it does).
	 */
	private void resume() {
		long bytes = readBytes();
		if (bytes < 0) return;
		PreferenceStore ps = prefs();
		long saved = ps.getLongPref(LAST_BYTES);
		long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
		boolean sameBoot = Math.abs(ps.getLongPref(LAST_BOOT) - boot) < 60_000;
		if (sameBoot && (saved >= 0) && (bytes > saved)) {
			store.add(System.currentTimeMillis(), CAT_OTHER, currentNetwork(), bytes - saved);
		}
		lastBytes = bytes;
		lastSample = SystemClock.elapsedRealtime();
		lastCat = currentCategory();
		lastNet = currentNetwork();
	}

	private void sample() {
		long bytes = readBytes();
		lastSample = SystemClock.elapsedRealtime();
		if (bytes < 0) return;
		long used = (lastBytes >= 0) ? (bytes - lastBytes) : 0;
		lastBytes = bytes;
		// Less than last time: the count started over (not while the app is running, normally).
		if (used > 0) store.add(System.currentTimeMillis(), lastCat, lastNet, used);
		lastCat = currentCategory();
		lastNet = currentNetwork();

		if (used > 0) {
			checkAlerts();
			notifyListeners();
		}
	}

	private long readBytes() {
		long rx = TrafficStats.getUidRxBytes(uid);
		long tx = TrafficStats.getUidTxBytes(uid);

		if ((rx == TrafficStats.UNSUPPORTED) || (tx == TrafficStats.UNSUPPORTED)) {
			supported = false;
			return -1;
		}

		supported = true;
		return rx + tx;
	}

	private void save() {
		lastSave = SystemClock.elapsedRealtime();
		store.saveAsync();
		if (lastBytes < 0) return;
		try (PreferenceStore.Edit e = prefs().editPreferenceStore()) {
			e.setLongPref(LAST_BYTES, lastBytes);
			e.setLongPref(LAST_BOOT, System.currentTimeMillis() - SystemClock.elapsedRealtime());
		}
	}

	private void notifyListeners() {
		for (Runnable l : new ArrayList<>(listeners)) l.run();
	}

	/** What the app is using data for right now. */
	private int currentCategory() {
		MediaSessionCallback cb = callback.get();
		if (cb == null) return CAT_OTHER;
		MediaEngine eng = cb.getEngine();
		if ((eng == null) || (eng.getId() != MediaPrefs.MEDIA_ENG_YT) || (eng.getSource() == null)) {
			return CAT_OTHER;
		}
		return MusicPlayer.isYoutubeAudioMode() ? CAT_MUSIC : CAT_VIDEO;
	}

	private static int currentNetwork() {
		try {
			ConnectivityManager cm = (ConnectivityManager) FermataApplication.get()
					.getSystemService(Context.CONNECTIVITY_SERVICE);
			if (cm == null) return NET_OTHER;
			Network n = cm.getActiveNetwork();
			NetworkCapabilities c = (n == null) ? null : cm.getNetworkCapabilities(n);
			return ((c != null) && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) ?
					NET_MOBILE : NET_OTHER;
		} catch (Exception ex) {
			return NET_OTHER;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Cycle, warning and limit
	// ---------------------------------------------------------------------------------------------

	public static boolean isMobileOnly() {
		return prefs().getIntPref(NETWORKS) == NETWORKS_MOBILE;
	}

	/** The data limit in bytes, 0 if none. */
	public static long getLimit() {
		return Math.round(prefs().getFloatPref(LIMIT_GB) * GB);
	}

	/** The warning level in bytes, 0 if none. */
	public static long getWarning() {
		return Math.round(prefs().getFloatPref(WARNING_GB) * GB);
	}

	/** The current limit cycle: its first moment and the first moment of the next one. */
	public static Calendar[] getCycle() {
		Calendar from = Calendar.getInstance();
		from.set(Calendar.HOUR_OF_DAY, 0);
		from.set(Calendar.MINUTE, 0);
		from.set(Calendar.SECOND, 0);
		from.set(Calendar.MILLISECOND, 0);
		Calendar to = (Calendar) from.clone();

		switch (prefs().getIntPref(CYCLE)) {
			case CYCLE_DAY -> to.add(Calendar.DAY_OF_YEAR, 1);
			case CYCLE_WEEK -> {
				while (from.get(Calendar.DAY_OF_WEEK) != from.getFirstDayOfWeek()) {
					from.add(Calendar.DAY_OF_YEAR, -1);
				}
				to = (Calendar) from.clone();
				to.add(Calendar.DAY_OF_YEAR, 7);
			}
			default -> {
				int start = Math.max(1, Math.min(28, prefs().getIntPref(CYCLE_START)));
				if (from.get(Calendar.DAY_OF_MONTH) < start) from.add(Calendar.MONTH, -1);
				from.set(Calendar.DAY_OF_MONTH, start);
				to = (Calendar) from.clone();
				to.add(Calendar.MONTH, 1);
			}
		}

		return new Calendar[]{from, to};
	}

	/** Bytes per category used in the current limit cycle, counted as the settings say. */
	public long[] getCycleUsage() {
		Calendar[] c = getCycle();
		Calendar last = (Calendar) c[1].clone();
		last.add(Calendar.DAY_OF_YEAR, -1);
		return store.days(DataUsageStore.dayKey(c[0]), DataUsageStore.dayKey(last), isMobileOnly());
	}

	/** What's left of the limit this cycle (never below 0), or -1 without a limit. */
	public long getRemaining() {
		long limit = getLimit();
		if (limit <= 0) return -1;
		return Math.max(0, limit - DataUsageStore.sum(getCycleUsage()));
	}

	private void checkAlerts() {
		long limit = getLimit();
		long warning = getWarning();
		if ((limit <= 0) && (warning <= 0)) return;
		long used = DataUsageStore.sum(getCycleUsage());
		int level = ((limit > 0) && (used >= limit)) ? 2 : ((warning > 0) && (used >= warning)) ? 1 : 0;
		if (level == 0) return;

		PreferenceStore ps = prefs();
		long cycle = getCycle()[0].getTimeInMillis();
		int alerted = (ps.getLongPref(ALERT_CYCLE) == cycle) ? ps.getIntPref(ALERT_LEVEL) : 0;
		if (level <= alerted) return;

		try (PreferenceStore.Edit e = ps.editPreferenceStore()) {
			e.setLongPref(ALERT_CYCLE, cycle);
			e.setIntPref(ALERT_LEVEL, level);
		}

		Context ctx = FermataApplication.get();
		String amount = Formatter.formatShortFileSize(ctx, used);
		String msg = (level == 2) ? ctx.getString(R.string.data_usage_limit_reached, amount) :
				ctx.getString(R.string.data_usage_warning_reached, amount);
		Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
	}
}
