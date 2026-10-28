package me.aap.fermata.addon.sleep;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.ambience.AmbienceAddon;
import me.aap.fermata.addon.ambience.AmbienceEffect;
import me.aap.fermata.addon.ambience.AmbienceEngine;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * Records a night: from "Start sleep" to "Stop" (or the automatic stop) it sums how much the phone moves in every
 * {@link SleepNight#EPOCH_SEC} seconds (the accelerometer, a few readings a second, resting values ignored) and, when the
 * microphone is allowed, samples how loud the room is once an epoch. Nothing is kept of the sound but that one number;
 * <b>no audio is ever stored or sent</b>. The session lives in a foreground service ({@link SleepService}) with a partial
 * wake lock, so the readings go on with the screen off; the night is saved to {@link SleepStore} every few minutes, and a
 * night whose app was killed is finished at the next start ({@link #recoverDraft}).
 * <p>
 * Starting a session can also start the last ambience mix with a length (Settings &gt; Sleep), and the sounds that played
 * are noted with the night so the tab can tell whether they helped.
 */
public final class SleepTracker implements SensorEventListener {
	/** A session shorter than this is not a night. */
	static final long MIN_NIGHT_MS = 20 * 60_000L;
	private static final int MAX_EPOCHS = 24 * 3600 / SleepNight.EPOCH_SEC;
	private static final int DRAFT_EVERY = 10;
	/** Differences between two readings below this (m/s²) are the sensor's noise. */
	private static final float DEAD_ZONE = 0.06f;
	/** Sum of the differences in an epoch to the 0..255 movement value. */
	private static final float MOVE_SCALE = 6f;
	private static SleepTracker instance;

	private final SleepStore store = new SleepStore();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private final Set<String> mix = new LinkedHashSet<>();
	private final Handler main = new Handler(android.os.Looper.getMainLooper());
	@Nullable
	private HandlerThread thread;
	@Nullable
	private Handler work;
	@Nullable
	private PowerManager.WakeLock wakeLock;
	@Nullable
	private SensorManager sensors;
	@Nullable
	private SleepNight night;
	private byte[] moveBuf = new byte[0];
	private byte[] noiseBuf = new byte[0];
	private byte[] flagBuf = new byte[0];
	private int count;
	private boolean useMove;
	private boolean useNoise;
	private volatile boolean pendingAwake;
	private volatile int liveMove;
	private volatile int liveNoise;
	// Work-thread state.
	private float accum;
	private float lastMag;
	private boolean haveLast;
	private long nextTick;
	// Battery use through the night: the sum of the currents read while drawing, and how many readings.
	private float drawSum;
	private int drawN;
	private int chargeN;
	private int levelStart = -1;

	private SleepTracker() {
	}

	public static synchronized SleepTracker get() {
		if (instance == null) {
			instance = new SleepTracker();
			instance.recoverDraft();
		}
		return instance;
	}

	public SleepStore getStore() {
		return store;
	}

	public synchronized boolean isActive() {
		return night != null;
	}

	public synchronized long getStart() {
		return (night == null) ? 0 : night.start;
	}

	/** The movement of the last finished epoch, 0..255. */
	public int getLiveMovement() {
		return liveMove;
	}

	public int getLiveNoise() {
		return liveNoise;
	}

	public synchronized int getEpochCount() {
		return count;
	}

	public synchronized boolean isRecordingMovement() {
		return useMove;
	}

	public synchronized boolean isRecordingSound() {
		return useNoise;
	}

	public void addListener(Runnable l) {
		listeners.add(l);
	}

	public void removeListener(Runnable l) {
		listeners.remove(l);
	}

	private void changed() {
		main.post(() -> {
			for (Runnable l : listeners) l.run();
		});
	}

	/** A copy of the night as recorded so far (ending now), or null when there is no session. */
	@Nullable
	public synchronized SleepNight snapshot() {
		if (night == null) return null;
		SleepNight n = night.copy();
		n.end = System.currentTimeMillis();
		if (useMove) n.move = Arrays.copyOf(moveBuf, count);
		if (useNoise) n.noise = Arrays.copyOf(noiseBuf, count);
		n.flags = Arrays.copyOf(flagBuf, count);
		n.levelStart = levelStart;
		n.levelEnd = batteryLevel();
		n.drawSamples = drawN;
		n.chargeSamples = chargeN;
		n.drawMa10 = (drawN == 0) ? 0 : Math.round(drawSum / drawN * 10f);
		return n;
	}

	public static boolean hasMicPermission() {
		return ContextCompat.checkSelfPermission(FermataApplication.get(), Manifest.permission.RECORD_AUDIO) ==
				PackageManager.PERMISSION_GRANTED;
	}

	// ---------------------------------------------------------------------------------------------
	// Start and stop

	/** Starts a session. Does nothing when one runs. */
	public void start() {
		Context ctx = FermataApplication.get();
		PreferenceStore p = SleepPrefs.store();
		synchronized (this) {
			if (night != null) return;
			int mode = p.getIntPref(SleepPrefs.MODE);
			sensors = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
			Sensor acc = (sensors == null) ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
			useMove = (mode >= SleepPrefs.MODE_MOVEMENT) && (acc != null);
			useNoise = useMove && (mode >= SleepPrefs.MODE_MOVEMENT_SOUND) && hasMicPermission();
			night = new SleepNight(System.currentTimeMillis());
			moveBuf = new byte[MAX_EPOCHS];
			noiseBuf = new byte[MAX_EPOCHS];
			flagBuf = new byte[MAX_EPOCHS];
			count = 0;
			mix.clear();
			pendingAwake = false;
			liveMove = 0;
			liveNoise = 0;
			accum = 0;
			haveLast = false;
			drawSum = 0;
			drawN = 0;
			chargeN = 0;
			levelStart = batteryLevel();
			p.applyLongPref(SleepPrefs.ACTIVE_START, night.start);
			thread = new HandlerThread("sleep-tracker");
			thread.start();
			work = new Handler(thread.getLooper());
			try {
				PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
				if (pm != null) {
					wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zrAuto:sleep");
					wakeLock.acquire((p.getIntPref(SleepPrefs.AUTO_STOP_H) + 1) * 3600_000L);
				}
			} catch (Exception ex) {
				DiagnosticLog.w(LogCat.SLEEP, "wake lock not taken", ex);
			}
			if (useMove) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL, work);
			nextTick = SystemClock.uptimeMillis() + SleepNight.EPOCH_MS;
			work.postAtTime(this::tick, null, nextTick);
			DiagnosticLog.log(LogCat.SLEEP, "session started", "mode=" + mode, "movement=" + useMove,
					"sound=" + useNoise, "sensitivity=" + p.getIntPref(SleepPrefs.SENSITIVITY));
		}
		SleepService.start(ctx);
		linkAmbience(p);
		changed();
	}

	/** Ends the session and keeps the night; returns it, or null when none ran or it was too short. */
	@Nullable
	public SleepNight stop() {
		SleepNight done;
		Context ctx = FermataApplication.get();
		PreferenceStore p = SleepPrefs.store();
		synchronized (this) {
			if (night == null) return null;
			if (work != null) work.removeCallbacksAndMessages(null);
			if (sensors != null) sensors.unregisterListener(this);
			if (thread != null) thread.quitSafely();
			thread = null;
			work = null;
			try {
				if ((wakeLock != null) && wakeLock.isHeld()) wakeLock.release();
			} catch (Exception ex) {
				DiagnosticLog.w(LogCat.SLEEP, "wake lock not released", ex);
			}
			wakeLock = null;
			done = snapshot();
			night = null;
			count = 0;
			p.applyLongPref(SleepPrefs.ACTIVE_START, 0L);
		}
		store.deleteDraft();
		SleepService.stop(ctx);
		if (p.getBooleanPref(SleepPrefs.AMBIENCE_STOP) && AmbienceAddon.isEnabled()) {
			AmbienceEngine e = AmbienceEngine.get();
			if (e.anyOn()) e.stopAll();
		}
		if ((done == null) || (done.durationMs() < MIN_NIGHT_MS)) {
			DiagnosticLog.log(LogCat.SLEEP, "session too short, not kept",
					"minutes=" + ((done == null) ? 0 : done.durationMs() / 60_000L));
			changed();
			return null;
		}
		store.add(done);
		SleepAnalyzer.Result r = SleepAnalyzer.analyze(done, store.all(), SleepAnalyzer.Settings.fromPrefs());
		DiagnosticLog.log(LogCat.SLEEP, "session ended", "minutes=" + done.durationMs() / 60_000L,
				"asleepMinutes=" + r.asleepMs / 60_000L, "latencyMinutes=" + r.latencyMs / 60_000L,
				"awakenings=" + r.awakenings, "score=" + r.score, "epochs=" + done.epochs(),
				"mix=" + (done.mix.isEmpty() ? "-" : done.mix), "avgMa=" + done.drawMa(),
				"levelStart=" + done.levelStart, "levelEnd=" + done.levelEnd,
				"drawReadings=" + done.drawSamples, "chargeReadings=" + done.chargeSamples);
		changed();
		return done;
	}

	/** "I'm awake": the epoch it is made in counts as awake. */
	public void markAwake() {
		pendingAwake = true;
		DiagnosticLog.log(LogCat.SLEEP, "marked awake");
		changed();
	}

	/** Brings the last mix of the Ambience tab back for the session, fading out after the chosen length. */
	private void linkAmbience(PreferenceStore p) {
		if (!p.getBooleanPref(SleepPrefs.AMBIENCE_START) || !AmbienceAddon.isEnabled()) return;
		try {
			AmbienceEngine e = AmbienceEngine.get();
			if (!e.anyOn()) e.resumeLast();
			int want = p.getIntPref(SleepPrefs.AMBIENCE_MIN);
			if (want > 0) {
				int best = AmbienceEngine.LENGTHS[1];
				for (int l : AmbienceEngine.LENGTHS) {
					if ((l > 0) && (Math.abs(l - want) < Math.abs(best - want))) best = l;
				}
				e.setLengthMinutes(best);
			}
			noteMix();
		} catch (Exception ex) {
			DiagnosticLog.w(LogCat.SLEEP, "ambience not started", ex);
		}
	}

	private void noteMix() {
		if (!AmbienceAddon.isEnabled()) return;
		synchronized (this) {
			if (night == null) return;
			for (AmbienceEffect fx : AmbienceEngine.get().getActive()) mix.add(fx.key);
			night.mix = String.join(",", mix);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Recording (the tracker's own thread)

	@Override
	public void onSensorChanged(SensorEvent ev) {
		float x = ev.values[0];
		float y = ev.values[1];
		float z = ev.values[2];
		float mag = (float) Math.sqrt(x * x + y * y + z * z);
		if (haveLast) {
			float d = Math.abs(mag - lastMag);
			if (d > DEAD_ZONE) accum += d;
		}
		lastMag = mag;
		haveLast = true;
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

	private void tick() {
		Handler w = work;
		if (w == null) return;
		int move = Math.min(255, Math.round(accum * MOVE_SCALE));
		accum = 0;
		int noise = 0;
		if (useNoise) noise = sampleNoise();
		boolean awake = pendingAwake;
		pendingAwake = false;
		float ma = me.aap.fermata.addon.power.PowerUsageTracker.get().readNowMa();
		boolean full;
		synchronized (this) {
			if (night == null) return;
			if (count < MAX_EPOCHS) {
				moveBuf[count] = (byte) move;
				noiseBuf[count] = (byte) noise;
				flagBuf[count] = (byte) (awake ? SleepNight.FLAG_AWAKE : 0);
				count++;
			}
			liveMove = move;
			liveNoise = noise;
			if (!Float.isNaN(ma)) {
				if (ma < -1f) {
					drawSum += -ma;
					drawN++;
				} else if (ma > 1f) {
					chargeN++;
				}
			}
			full = (count >= MAX_EPOCHS) ||
					(System.currentTimeMillis() - night.start >=
							SleepPrefs.store().getIntPref(SleepPrefs.AUTO_STOP_H) * 3600_000L);
		}
		main.post(this::noteMix);
		if (full) {
			DiagnosticLog.log(LogCat.SLEEP, "automatic stop");
			main.post(this::stop);
			return;
		}
		if (count % DRAFT_EVERY == 0) {
			SleepNight s = snapshot();
			if (s != null) store.saveDraft(s);
		}
		changed();
		nextTick += SleepNight.EPOCH_MS;
		long now = SystemClock.uptimeMillis();
		if (nextTick < now) nextTick = now + 1000;
		w.postAtTime(this::tick, null, nextTick);
	}

	/** The battery level in percent, or -1. */
	private static int batteryLevel() {
		android.content.Intent i = FermataApplication.get().registerReceiver(null,
				new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
		if (i == null) return -1;
		int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
		int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
		return ((level >= 0) && (scale > 0)) ? Math.round(level * 100f / scale) : -1;
	}

	/** One second of the room's sound, only its loudness: 0 (silent) to 255. The audio itself is dropped at once. */
	private int sampleNoise() {
		AudioRecord rec = null;
		try {
			int rate = 8000;
			int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
			if (min <= 0) return 0;
			rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
					AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
			if (rec.getState() != AudioRecord.STATE_INITIALIZED) return 0;
			rec.startRecording();
			short[] buf = new short[rate / 2];
			int n = rec.read(buf, 0, buf.length);
			if (n <= 0) return 0;
			double sum = 0;
			for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
			double rms = Math.sqrt(sum / n);
			double db = 20 * Math.log10(Math.max(rms, 1) / 32768.0);
			return (int) Math.max(0, Math.min(255, (db + 75) / 55 * 255));
		} catch (Exception ex) {
			Log.d(ex, "Sleep: the microphone could not be read");
			return 0;
		} finally {
			if (rec != null) {
				try {
					rec.stop();
				} catch (Exception ignore) {
					// Not started.
				}
				rec.release();
			}
		}
	}

	/** A night whose app was killed: kept up to the last time it was saved. */
	private void recoverDraft() {
		PreferenceStore p = SleepPrefs.store();
		if (p.getLongPref(SleepPrefs.ACTIVE_START) == 0L) return;
		p.applyLongPref(SleepPrefs.ACTIVE_START, 0L);
		SleepNight d = store.readDraft();
		store.deleteDraft();
		if ((d == null) || (d.durationMs() < MIN_NIGHT_MS)) return;
		d.recovered = true;
		store.add(d);
		DiagnosticLog.log(LogCat.SLEEP, "unfinished night recovered", "minutes=" + d.durationMs() / 60_000L);
	}
}
