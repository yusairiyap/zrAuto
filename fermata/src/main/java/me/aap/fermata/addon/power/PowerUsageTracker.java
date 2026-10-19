package me.aap.fermata.addon.power;

import static me.aap.fermata.addon.power.PowerUsageStore.CAT_IDLE;
import static me.aap.fermata.addon.power.PowerUsageStore.CAT_MUSIC;
import static me.aap.fermata.addon.power.PowerUsageStore.CAT_VIDEO;
import static me.aap.fermata.addon.power.PowerUsageStore.CTX_CAR;
import static me.aap.fermata.addon.power.PowerUsageStore.CTX_PHONE;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.data.DataSessionLimit;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.app.App;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Reads the phone's battery gauge the way the Ampere app does: the current flowing out of (or,
 * while charging, into) the battery, {@link BatteryManager#BATTERY_PROPERTY_CURRENT_NOW}, plus
 * voltage, temperature and level from the battery broadcast. From those it keeps
 * <ul>
 * <li>the live figure (smoothed a little) and a short history of it, for the Power tab and the
 * Info Overlay item;</li>
 * <li>this session's lowest, highest and average reading, and the charge drawn since it began
 * (reset from the Power tab);</li>
 * <li>the long record, {@link PowerUsageStore}: charge drawn per hour and day, split by what the
 * app was doing (video, music, idle) and whether Android Auto was connected.</li>
 * </ul>
 * Android does not tell an app what the other apps use, so every figure is the whole phone's
 * while zrAuto was running: compare the categories to see what costs the most.
 * <p>
 * It costs next to nothing to run: a reading is one quick system call. With nothing on screen
 * asking for the live figure it reads every 15 to 60 seconds (Settings) and only while the app is
 * open, playing or connected to a car; the timer is a plain handler message, so it never wakes a
 * sleeping phone. A tab or overlay showing the live figure makes it read once a second.
 */
public final class PowerUsageTracker implements MediaSessionCallback.Listener {
	public static final int UNITS_AUTO = 0;
	public static final int UNITS_MICRO = 1;
	public static final int UNITS_MILLI = 2;
	public static final int SIGN_AUTO = 0;
	public static final int SIGN_NORMAL = 1;
	public static final int SIGN_INVERTED = 2;
	/** Which unit the phone reports the current in ({@code UNITS_*}); automatic works for nearly all. */
	public static final Pref<IntSupplier> UNITS = Pref.i("POWER_UNITS", UNITS_AUTO);
	/** Whether charging reads positive ({@code SIGN_*}); a few phones report it the other way round. */
	public static final Pref<IntSupplier> SIGN = Pref.i("POWER_SIGN", SIGN_AUTO);
	/** How often the background reading is taken: an index into {@link #LOG_SECONDS}. */
	public static final Pref<IntSupplier> LOG_INTERVAL = Pref.i("POWER_LOG_INTERVAL", 1);
	public static final int[] LOG_SECONDS = {15, 30, 60};
	/** How often the live reading is taken (the tab, the info overlay): an index into {@link #LIVE_MS}. */
	public static final Pref<IntSupplier> LIVE_INTERVAL = Pref.i("POWER_LIVE_INTERVAL", 1);
	public static final int[] LIVE_MS = {500, 1000, 2000, 5000};
	/** When counting started, i.e. the last reset. */
	public static final Pref<LongSupplier> SINCE = Pref.l("POWER_SINCE", 0);
	// What the first readings taught about this phone: 1000 for µA, 1 for mA (0: not known yet), and
	// which way charging points (1 or -1, 0: not known yet).
	private static final Pref<IntSupplier> LEARNED_DIV = Pref.i("POWER_LEARNED_DIV", 0);
	private static final Pref<IntSupplier> LEARNED_SIGN = Pref.i("POWER_LEARNED_SIGN", 0);
	// The battery's size in mAh as the gauge showed it over time (0: not known yet); what "heavy" is
	// judged against, see getLoad().
	private static final Pref<IntSupplier> LEARNED_CAPACITY = Pref.i("POWER_LEARNED_CAPACITY", 0);
	/** A phone's battery when this one's size isn't known yet. */
	private static final int TYPICAL_CAPACITY_MAH = 4500;
	/** Drawing the whole battery in this many hours or more is light use. */
	public static final float LIGHT_HOURS = 10f;
	/** Drawing it in less than this many hours is heavy use. */
	public static final float HEAVY_HOURS = 4f;
	private static final long IDLE_INTERVAL = 60_000;
	private static final long SAVE_INTERVAL = 60_000;
	private static final int GRAPH_SIZE = 120;
	private static final IntentFilter BATTERY_FILTER = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
	@Nullable
	private static PowerUsageTracker instance;

	private final PowerUsageStore store = new PowerUsageStore();
	private final List<Runnable> listeners = new ArrayList<>(2);
	private final Set<Object> visibleScreens = new HashSet<>(2);
	private final Runnable tick = this::tick;
	private WeakReference<MediaSessionCallback> callback = new WeakReference<>(null);
	private boolean started;
	private boolean supported = true;
	private long lastSample;
	private long lastSave;
	private int lastCat = CAT_IDLE;
	private int lastCtx = CTX_PHONE;
	// The reading in milliamperes, smoothed; positive is charging.
	private float current;
	private boolean haveCurrent;
	// How hard the phone has been drawing from the battery (and how fast it has been charging) over
	// the last ten minutes or so, in mA (0: not known yet): what the time left is worked out from, so
	// it doesn't jump about with every reading.
	private float drawEma;
	private float chargeEma;
	private int voltageMv;
	private int tempTenths = Integer.MIN_VALUE;
	private int levelPct = -1;
	private int plugged;
	private boolean charging;
	private boolean full;
	private long chargeCounter;
	private int signVotes;
	private int signVote;
	// Session figures.
	private long sessionStart;
	private float sessionMin;
	private float sessionMax;
	private double sessionSum;
	private int sessionCount;
	private long sessionDrawn;
	private long sessionCharged;
	private final float[] graph = new float[GRAPH_SIZE];
	private int graphSize;
	private int graphHead;

	private PowerUsageTracker() {
		PreferenceStore ps = prefs();
		if (ps.getLongPref(SINCE) == 0) ps.applyLongPref(SINCE, System.currentTimeMillis());
		resetSession();
	}

	/** Main thread only. */
	@NonNull
	public static PowerUsageTracker get() {
		PowerUsageTracker t = instance;
		if (t == null) instance = t = new PowerUsageTracker();
		return t;
	}

	public static PreferenceStore prefs() {
		return FermataApplication.get().getPreferenceStore();
	}

	public PowerUsageStore getStore() {
		return store;
	}

	// ---------------------------------------------------------------------------------------------
	// Running

	/** Starts measuring, if not yet; and follows what {@code cb} plays, to tell what the power is for. */
	public void start(@Nullable MediaSessionCallback cb) {
		if ((cb != null) && (callback.get() != cb)) {
			MediaSessionCallback old = callback.get();
			if (old != null) old.removeBroadcastListener(this);
			callback = new WeakReference<>(cb);
			cb.addBroadcastListener(this);
		}
		if (started) return;
		started = true;
		lastSample = SystemClock.elapsedRealtime();
		lastCat = currentCategory();
		lastCtx = currentContext();
		App.get().getHandler().post(tick);
	}

	/** Stops measuring (the addon was disabled). */
	public void stop() {
		if (!started) return;
		started = false;
		App.get().getHandler().removeCallbacks(tick);
		MediaSessionCallback cb = callback.get();
		if (cb != null) cb.removeBroadcastListener(this);
		callback = new WeakReference<>(null);
		save();
	}

	public boolean isStarted() {
		return started;
	}

	/** A screen of the app is on show (or not): reading is worth it while one is. */
	public void setScreenVisible(Object screen, boolean visible) {
		boolean changed = visible ? visibleScreens.add(screen) : visibleScreens.remove(screen);
		if (changed && started) reschedule();
	}

	/** Saves the record now, e.g. when the app goes to the background. */
	public void flush() {
		if (!started) return;
		sample();
		save();
	}

	/** Forgets all usage recorded so far, and starts counting again from now. */
	public void reset() {
		store.clear();
		prefs().applyLongPref(SINCE, System.currentTimeMillis());
		save();
		notifyListeners();
	}

	/** Starts this session's lowest / highest / average readings and charge over. */
	public void resetSession() {
		sessionStart = System.currentTimeMillis();
		sessionMin = Float.MAX_VALUE;
		sessionMax = -Float.MAX_VALUE;
		sessionSum = 0;
		sessionCount = 0;
		sessionDrawn = 0;
		sessionCharged = 0;
		notifyListeners();
	}

	/**
	 * Called on the main thread after each reading. While anyone listens the reading is taken every
	 * second (a tab or overlay is showing it); with no one it is the slow background one.
	 */
	public void addListener(Runnable l) {
		if (listeners.contains(l)) return;
		listeners.add(l);
		if (started && (listeners.size() == 1)) reschedule();
	}

	public void removeListener(Runnable l) {
		if (listeners.remove(l) && started && listeners.isEmpty()) reschedule();
	}

	private void reschedule() {
		App.get().getHandler().removeCallbacks(tick);
		App.get().getHandler().post(tick);
	}

	@Override
	public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
		// Book what was drawn so far to what was going on so far, before that changes.
		if (started && (SystemClock.elapsedRealtime() - lastSample >= 1000)) sample();
		if (started && !isLive()) reschedule();
	}

	/** An interval was changed in Settings: the next reading is taken on the new rhythm. */
	public void intervalChanged() {
		if (started) reschedule();
	}

	private boolean isLive() {
		return !listeners.isEmpty();
	}

	/** The live reading's interval in ms, from Settings. */
	private long liveInterval() {
		int idx = Math.max(0, Math.min(LIVE_MS.length - 1, prefs().getIntPref(LIVE_INTERVAL)));
		return LIVE_MS[idx];
	}

	/** Whether there is any reason to read the battery now: someone watching, or the app at work. */
	private boolean isAppActive() {
		if (isLive() || !visibleScreens.isEmpty()) return true;
		MediaSessionCallback cb = callback.get();
		return ((cb != null) && cb.isPlaying()) || DataSessionLimit.isCarConnected();
	}

	private long backgroundInterval() {
		int idx = Math.max(0, Math.min(LOG_SECONDS.length - 1, prefs().getIntPref(LOG_INTERVAL)));
		long ms = LOG_SECONDS[idx] * 1000L;
		return PowerSaver.get().isActive() ? ms * 2 : ms;
	}

	private void tick() {
		if (!started) return;
		boolean active = isAppActive();
		if (active) sample();
		else readBattery();
		PowerSaver.get().onBattery(levelPct, plugged != 0);
		if (SystemClock.elapsedRealtime() - lastSave >= SAVE_INTERVAL) save();
		App.get().getHandler().postDelayed(tick,
				isLive() ? liveInterval() : active ? backgroundInterval() : IDLE_INTERVAL);
	}

	// ---------------------------------------------------------------------------------------------
	// Reading

	/** Whether the phone reports the battery current at all. */
	public boolean isSupported() {
		return supported;
	}

	private void sample() {
		long now = SystemClock.elapsedRealtime();
		long dt = now - lastSample;
		lastSample = now;
		readBattery();

		BatteryManager bm = (BatteryManager) FermataApplication.get().getSystemService(Context.BATTERY_SERVICE);
		int raw = (bm == null) ? Integer.MIN_VALUE : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
		if (raw == Integer.MIN_VALUE) {
			if (supported) DiagnosticLog.log("POWER", "current not reported by this phone");
			supported = false;
			return;
		}
		supported = true;
		long cc = (bm == null) ? 0 : bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
		chargeCounter = (cc == Long.MIN_VALUE) ? 0 : cc;

		float ma = toMilliAmps(raw);
		haveCurrent = true;
		// A little smoothing (over about two seconds, whatever the interval): the raw gauge jumps
		// about from one reading to the next.
		float step = Math.max(200, Math.min(10_000, dt));
		float fast = 1f - (float) Math.exp(-step / 2000f);
		current = (graphSize == 0) ? ma : current + (ma - current) * fast;
		// Time-weighted, so a long gap counts for what it was (but never for more than two minutes).
		float slow = 1f - (float) Math.exp(-Math.min(dt, 120_000) / 600_000f);
		if (ma < 0) drawEma = (drawEma <= 0) ? -ma : drawEma + (-ma - drawEma) * slow;
		else if (ma > 0) chargeEma = (chargeEma <= 0) ? ma : chargeEma + (ma - chargeEma) * slow;
		learnCapacity();
		charging = current > 0;
		graph[graphHead] = current;
		graphHead = (graphHead + 1) % GRAPH_SIZE;
		graphSize = Math.min(GRAPH_SIZE, graphSize + 1);

		if (graphSize >= 3) {
			sessionMin = Math.min(sessionMin, current);
			sessionMax = Math.max(sessionMax, current);
		}
		sessionSum += current;
		sessionCount++;

		// Book the stretch since the last reading, unless the phone slept through it.
		long maxGap = Math.max(5000, 3 * (isLive() ? liveInterval() : backgroundInterval()));
		if ((dt > 0) && (dt <= maxGap)) {
			long microAh = Math.round(Math.abs(ma) * dt / 3600.0);
			boolean drawing = ma < 0;
			store.add(System.currentTimeMillis(), lastCat, lastCtx, microAh, dt, !drawing);
			if (drawing) sessionDrawn += microAh;
			else sessionCharged += microAh;
		}
		lastCat = currentCategory();
		lastCtx = currentContext();
		notifyListeners();
	}

	private void readBattery() {
		Intent i = FermataApplication.get().registerReceiver(null, BATTERY_FILTER);
		if (i == null) return;
		int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
		int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
		levelPct = ((level >= 0) && (scale > 0)) ? Math.round(level * 100f / scale) : -1;
		voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
		tempTenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
		plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
		int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
		full = status == BatteryManager.BATTERY_STATUS_FULL;
		statusCharging = status == BatteryManager.BATTERY_STATUS_CHARGING;
		statusDischarging = status == BatteryManager.BATTERY_STATUS_DISCHARGING;
	}

	private boolean statusCharging;
	private boolean statusDischarging;

	/** The phone's reading as milliamperes, charging positive. */
	private float toMilliAmps(int raw) {
		PreferenceStore ps = prefs();
		int units = ps.getIntPref(UNITS);
		float div;
		if (units == UNITS_MICRO) {
			div = 1000f;
		} else if (units == UNITS_MILLI) {
			div = 1f;
		} else {
			int learned = ps.getIntPref(LEARNED_DIV);
			if (learned == 0) {
				// Decided while the screen is on, when the phone draws more than 20 mA in any unit.
				int abs = Math.abs(raw);
				if (isInteractive() && (abs >= 20_000)) {
					learned = 1000;
				} else if (isInteractive() && (abs > 0) && (abs <= 8_000)) {
					learned = 1;
				}
				if (learned != 0) {
					ps.applyIntPref(LEARNED_DIV, learned);
					DiagnosticLog.log("POWER", "current unit", (learned == 1000) ? "uA" : "mA", "raw=" + raw);
				}
			}
			div = (learned != 0) ? learned : (Math.abs(raw) >= 20_000 ? 1000f : 1f);
		}
		float ma = raw / div;

		int sign = ps.getIntPref(SIGN);
		if (sign == SIGN_NORMAL) return ma;
		if (sign == SIGN_INVERTED) return -ma;
		return autoSign(ma);
	}

	/**
	 * Which way is charging: from the battery status when the phone says charging or discharging
	 * (three readings in a row that agree make it known for good), and before that by the status
	 * alone, so a phone that reports it backwards is right from the first moment.
	 */
	private float autoSign(float ma) {
		PreferenceStore ps = prefs();
		int learned = ps.getIntPref(LEARNED_SIGN);
		if ((learned == 0) && (Math.abs(ma) > 100) && (statusCharging || statusDischarging)) {
			int vote = (statusCharging == (ma > 0)) ? 1 : -1;
			if (vote == signVote) signVotes++;
			else {
				signVote = vote;
				signVotes = 1;
			}
			if (signVotes >= 3) {
				ps.applyIntPref(LEARNED_SIGN, vote);
				DiagnosticLog.log("POWER", "charging reads", (vote == 1) ? "positive" : "negative");
				learned = vote;
			}
		}
		if (learned != 0) return ma * learned;
		if (statusCharging) return Math.abs(ma);
		if (statusDischarging) return -Math.abs(ma);
		return ma;
	}

	private static boolean isInteractive() {
		PowerManager pm = (PowerManager) FermataApplication.get().getSystemService(Context.POWER_SERVICE);
		return (pm == null) || pm.isInteractive();
	}

	private int currentCategory() {
		MediaSessionCallback cb = callback.get();
		if ((cb == null) || !cb.isPlaying()) return CAT_IDLE;
		MediaEngine eng = cb.getEngine();
		if (eng == null) return CAT_IDLE;
		if (eng.getId() == MediaPrefs.MEDIA_ENG_YT) {
			return MusicPlayer.isYoutubeAudioMode() ? CAT_MUSIC : CAT_VIDEO;
		}
		PlayableItem src = eng.getSource();
		return ((src != null) && src.isVideo()) ? CAT_VIDEO : CAT_MUSIC;
	}

	private static int currentContext() {
		return DataSessionLimit.isCarConnected() ? CTX_CAR : CTX_PHONE;
	}

	private void save() {
		lastSave = SystemClock.elapsedRealtime();
		store.saveAsync();
	}

	private void notifyListeners() {
		for (Runnable l : new ArrayList<>(listeners)) l.run();
	}

	// ---------------------------------------------------------------------------------------------
	// What the tab and the overlay show

	/** Whether there is a reading to show yet. */
	public boolean hasCurrent() {
		return haveCurrent;
	}

	/** The current in mA, smoothed; positive is charging, negative is the phone drawing from the battery. */
	public float getCurrent() {
		return current;
	}

	public boolean isCharging() {
		return haveCurrent && (current > 0);
	}

	/** Whether the battery is full (and plugged in). */
	public boolean isFull() {
		return full;
	}

	public boolean isPlugged() {
		return plugged != 0;
	}

	public int getLevel() {
		return levelPct;
	}

	public int getVoltageMv() {
		return voltageMv;
	}

	/** In tenths of °C, or {@code Integer.MIN_VALUE}. */
	public int getTemperatureTenths() {
		return tempTenths;
	}

	/** The power in watts (always positive), or -1 without a voltage. */
	public float getWatts() {
		if (!haveCurrent || (voltageMv <= 0)) return -1f;
		return Math.abs(current) * voltageMv / 1_000_000f;
	}

	/** This session's lowest reading in mA (the most the phone drew from the battery), or 0. */
	public float getSessionMin() {
		return (sessionCount >= 3) ? sessionMin : 0f;
	}

	public float getSessionMax() {
		return (sessionCount >= 3) ? sessionMax : 0f;
	}

	public float getSessionAverage() {
		return (sessionCount > 0) ? (float) (sessionSum / sessionCount) : 0f;
	}

	public boolean hasSession() {
		return sessionCount >= 3;
	}

	public long getSessionStart() {
		return sessionStart;
	}

	/** Charge drawn from the battery this session, µAh. */
	public long getSessionDrawn() {
		return sessionDrawn;
	}

	public long getSessionCharged() {
		return sessionCharged;
	}

	/** The last readings, oldest first, one per live reading. */
	public float[] getGraph() {
		float[] r = new float[graphSize];
		int start = (graphHead - graphSize + GRAPH_SIZE) % GRAPH_SIZE;
		for (int i = 0; i < graphSize; i++) r[i] = graph[(start + i) % GRAPH_SIZE];
		return r;
	}

	/**
	 * How long the battery lasts at the draw of the last ten minutes or so, steadied over time rather
	 * than following each reading (negative: until it is full while charging), in milliseconds; -1 if
	 * it can't be told.
	 */
	public long getEstimateMs() {
		if (!haveCurrent || (chargeCounter <= 0) || (levelPct <= 0)) return -1;
		boolean draining = current < 0;
		float ma = draining ? drawEma : chargeEma;
		if (ma < 20) return -1;
		long capacity = Math.round(chargeCounter * 100.0 / levelPct);
		long ms = draining ? Math.round(chargeCounter / 1000.0 / ma * 3_600_000.0) :
				Math.round(Math.max(0, capacity - chargeCounter) / 1000.0 / ma * 3_600_000.0);
		// To the nearest five minutes once it is over an hour: the digits don't flicker for nothing.
		long step = (ms >= 3_600_000L) ? 300_000L : 60_000L;
		ms = Math.max(step, Math.round(ms / (double) step) * step);
		return draining ? ms : -ms;
	}

	/** Keeps the battery's size up to date from the gauge's charge counter and level. */
	private void learnCapacity() {
		if ((chargeCounter <= 0) || (levelPct < 15)) return;
		float mah = chargeCounter * 100f / levelPct / 1000f;
		if ((mah < 500) || (mah > 40_000)) return;
		PreferenceStore ps = prefs();
		int known = ps.getIntPref(LEARNED_CAPACITY);
		int now = (known <= 0) ? Math.round(mah) : Math.round(known * 0.9f + mah * 0.1f);
		if (Math.abs(now - known) * 50 > Math.max(known, 1)) ps.applyIntPref(LEARNED_CAPACITY, now);
	}

	/** The battery's size in mAh: as learned from the gauge, else a typical phone's. */
	public int getCapacityMah() {
		int known = prefs().getIntPref(LEARNED_CAPACITY);
		return (known > 0) ? known : TYPICAL_CAPACITY_MAH;
	}

	/** Whether the battery's size is known (else {@link #getCapacityMah} is a guess). */
	public boolean isCapacityKnown() {
		return prefs().getIntPref(LEARNED_CAPACITY) > 0;
	}

	/**
	 * How hard the phone is working the battery right now, 0 and up: 0 for nothing (or charging),
	 * 1 when it would be flat in {@link #HEAVY_HOURS} hours at this rate. Judged against the battery's
	 * own size, so a tablet drawing 700 mA is as light as a phone drawing 300.
	 */
	public float getLoad() {
		if (!haveCurrent || (current >= 0)) return 0f;
		float hours = getCapacityMah() / Math.max(1f, -current);
		return HEAVY_HOURS / Math.max(0.01f, hours);
	}

	/** What the app is doing now, {@code CAT_*}. */
	public int getCategory() {
		return currentCategory();
	}

	public boolean isCar() {
		return currentContext() == CTX_CAR;
	}
}
