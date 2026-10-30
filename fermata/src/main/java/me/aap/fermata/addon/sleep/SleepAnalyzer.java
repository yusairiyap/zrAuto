package me.aap.fermata.addon.sleep;

import androidx.annotation.NonNull;

import java.util.List;

import me.aap.utils.pref.PreferenceStore;

/**
 * Works a night out from its movement: which epochs were asleep, when sleep began and ended, the interruptions, an
 * <b>estimate</b> of the stages and the 0 to 100 score. A phone on the bed cannot see sleep stages; the stage estimate is
 * the usual 90 minute cycle model shaped by how still the night was, and every screen says "estimated". Pure functions, no
 * Android calls, so it can be reasoned about (and tested) on its own.
 */
public final class SleepAnalyzer {
	public static final int AWAKE = 0;
	public static final int REM = 1;
	public static final int LIGHT = 2;
	public static final int DEEP = 3;

	/** Smoothed movement above which an epoch counts as awake, by sensitivity (low, normal, high). */
	private static final float[] THRESHOLD = {90f, 55f, 32f};
	/** Epochs of consecutive sleep that start (and of consecutive wake that end) a night: 5 minutes. */
	private static final int SETTLE = 10;
	/** Epochs of wake in a row that count as an interruption: 1.5 minutes. */
	private static final int WAKE_RUN = 3;
	/** Without sensor data, the time to fall asleep is assumed. */
	private static final long ASSUMED_LATENCY_MS = 15 * 60_000L;

	private SleepAnalyzer() {
	}

	/** The settings that shape the analysis. */
	public static final class Settings {
		public int goalMin = 480;
		public int bedMin = 23 * 60;
		public int wakeMin = 7 * 60;
		public int wDuration = 40;
		public int wConsistency = 25;
		public int wLatency = 15;
		public int wInterruptions = 20;
		public int sensitivity = 1;

		public static Settings fromPrefs() {
			PreferenceStore p = SleepPrefs.store();
			Settings s = new Settings();
			s.goalMin = SleepPrefs.goalMin();
			s.bedMin = p.getIntPref(SleepPrefs.BED_MIN);
			s.wakeMin = p.getIntPref(SleepPrefs.WAKE_MIN);
			s.wDuration = p.getIntPref(SleepPrefs.W_DURATION);
			s.wConsistency = p.getIntPref(SleepPrefs.W_CONSISTENCY);
			s.wLatency = p.getIntPref(SleepPrefs.W_LATENCY);
			s.wInterruptions = p.getIntPref(SleepPrefs.W_INTERRUPTIONS);
			s.sensitivity = Math.max(0, Math.min(2, p.getIntPref(SleepPrefs.SENSITIVITY)));
			return s;
		}
	}

	/** One part of the score. */
	public static final class Part {
		/** 0..1: how well this part went. */
		public final float fraction;
		/** The share of the 100 points this part is worth, with the current weights. */
		public final float max;

		Part(float fraction, float max) {
			this.fraction = Math.max(0f, Math.min(1f, fraction));
			this.max = max;
		}

		public float points() {
			return fraction * max;
		}
	}

	/** What a night came to. */
	public static final class Result {
		public final SleepNight night;
		public int epochs;
		/** Per epoch: {@link #AWAKE}, {@link #REM}, {@link #LIGHT} or {@link #DEEP}. */
		public byte[] stage;
		/** Epoch where sleep began and where the last sleep ended (exclusive). */
		public int onset;
		public int finalWake;
		public long inBedMs;
		public long asleepMs;
		public long latencyMs;
		/** Awake time between sleep onset and the final wake. */
		public long wakeAfterOnsetMs;
		public int awakenings;
		public int efficiency;
		/** Share of the sleep per stage, only meaningful with movement data. */
		public long remMs;
		public long lightMs;
		public long deepMs;
		/** Whether the analysis had sensor data (else it assumes). */
		public boolean fromSensors;
		/** Whether the microphone measured the room. */
		public boolean hasNoise;
		/** Average loudness 0..255 of the night. */
		public int noiseAvg;
		public Part duration;
		public Part consistency;
		public Part latency;
		public Part interruptions;
		public int score;
		/** A sample night (the settings' preview) says how far its bed time is from the usual; -1 for a real night. */
		float sampleDeviationMin = -1f;

		Result(SleepNight night) {
			this.night = night;
		}

		/** Bed time (the session's start) and wake time (the last sleep's end), in ms since the epoch. */
		public long bedTime() {
			return night.start;
		}

		public long wakeTime() {
			return night.start + (long) finalWake * SleepNight.EPOCH_MS;
		}
	}

	/** Analyses {@code night}; {@code before} are the nights that came earlier, oldest first (for the consistency). */
	@NonNull
	public static Result analyze(SleepNight night, List<SleepNight> before, Settings st) {
		Result r = new Result(night);
		int n = Math.max(1, night.epochs());
		r.epochs = n;
		r.stage = new byte[n];
		r.inBedMs = night.durationMs();
		r.fromSensors = night.hasMovement();
		r.hasNoise = night.hasNoise();
		if (r.hasNoise) {
			long sum = 0;
			for (byte b : night.noise) sum += (b & 0xFF);
			r.noiseAvg = (int) (sum / night.noise.length);
		}

		boolean[] awake = new boolean[n];
		if (r.fromSensors) {
			float[] sm = smooth(night.move, n);
			float t = THRESHOLD[st.sensitivity];
			for (int i = 0; i < n; i++) {
				awake[i] = sm[i] > t || ((night.flags != null) && (i < night.flags.length) &&
						((night.flags[i] & SleepNight.FLAG_AWAKE) != 0));
			}
		} else {
			int lead = (int) Math.min(n - 1, ASSUMED_LATENCY_MS / SleepNight.EPOCH_MS);
			for (int i = 0; i < lead; i++) awake[i] = true;
			// The screen was on, or "I'm awake" was pressed: that counts without any sensor.
			if (night.flags != null) {
				for (int i = 0; (i < n) && (i < night.flags.length); i++) {
					if ((night.flags[i] & SleepNight.FLAG_AWAKE) != 0) awake[i] = true;
				}
			}
		}

		// Sleep begins with the first run of SETTLE epochs asleep and ends where the last such run ends.
		int onset = -1;
		int run = 0;
		for (int i = 0; i < n; i++) {
			run = awake[i] ? 0 : run + 1;
			if (run >= SETTLE) {
				onset = i - SETTLE + 1;
				break;
			}
		}
		int finalWake = -1;
		run = 0;
		for (int i = n - 1; i >= 0; i--) {
			run = awake[i] ? 0 : run + 1;
			if (run >= SETTLE) {
				finalWake = i + SETTLE;
				break;
			}
		}
		if ((onset < 0) || (finalWake <= onset)) {
			// Not a night: nothing that counts as sleep.
			r.onset = n;
			r.finalWake = n;
			r.latencyMs = r.inBedMs;
			scoreIt(r, before, st);
			return r;
		}
		r.onset = onset;
		r.finalWake = Math.min(n, finalWake);
		r.latencyMs = onset * SleepNight.EPOCH_MS;

		int asleep = 0;
		int wakeRun = 0;
		long waso = 0;
		int awakenings = 0;
		for (int i = onset; i < r.finalWake; i++) {
			if (awake[i]) {
				wakeRun++;
				waso += SleepNight.EPOCH_MS;
				if (wakeRun == WAKE_RUN) awakenings++;
			} else {
				wakeRun = 0;
				asleep++;
			}
		}
		r.asleepMs = asleep * SleepNight.EPOCH_MS;
		r.wakeAfterOnsetMs = waso;
		r.awakenings = awakenings;
		long span = (long) (r.finalWake - onset) * SleepNight.EPOCH_MS;
		r.efficiency = (span <= 0) ? 0 : (int) Math.round(100.0 * r.asleepMs / (span + r.latencyMs));

		stages(r, awake);
		scoreIt(r, before, st);
		return r;
	}

	/** A made-up night for the settings' preview: 6 h 40 m asleep after 25 minutes, two awakenings, a bedtime 40 minutes off. */
	@NonNull
	public static Result sample(Settings st) {
		SleepNight n = new SleepNight(0L);
		n.end = 7 * 3600_000L + 30 * 60_000L;
		Result r = new Result(n);
		r.epochs = 900;
		r.fromSensors = true;
		r.latencyMs = 25 * 60_000L;
		r.asleepMs = (6 * 60 + 40) * 60_000L;
		r.awakenings = 2;
		r.wakeAfterOnsetMs = 14 * 60_000L;
		r.inBedMs = n.durationMs();
		r.sampleDeviationMin = 40f;
		scoreIt(r, null, st);
		return r;
	}

	/** Movement smoothed over five epochs (a turn over is seen in the epochs around it as well). */
	private static float[] smooth(byte[] move, int n) {
		float[] w = {0.1f, 0.2f, 0.4f, 0.2f, 0.1f};
		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			float s = 0;
			float wt = 0;
			for (int k = -2; k <= 2; k++) {
				int j = i + k;
				if ((j < 0) || (j >= n) || (j >= move.length)) continue;
				s += w[k + 2] * (move[j] & 0xFF);
				wt += w[k + 2];
			}
			out[i] = (wt == 0) ? 0 : s / wt;
		}
		return out;
	}

	/**
	 * The stage estimate: a 90 minute cycle from sleep onset (light, then deep early in the night, then light, then REM that
	 * grows through the night), where a restless stretch can only be light and a very still one favours deep.
	 */
	private static void stages(Result r, boolean[] awake) {
		SleepNight night = r.night;
		float[] sm = r.fromSensors ? smooth(night.move, r.epochs) : null;
		long deep = 0, rem = 0, light = 0;
		int cycleEpochs = (int) (90 * 60_000L / SleepNight.EPOCH_MS);
		for (int i = 0; i < r.epochs; i++) {
			if ((i < r.onset) || (i >= r.finalWake) || awake[i]) {
				r.stage[i] = AWAKE;
				continue;
			}
			int since = i - r.onset;
			int cycle = since / cycleEpochs;
			float phase = (since % cycleEpochs) / (float) cycleEpochs;
			float deepShare = (cycle == 0) ? 0.40f : (cycle == 1) ? 0.30f : (cycle == 2) ? 0.15f : 0.05f;
			float remShare = Math.min(0.35f, 0.10f + 0.07f * cycle);
			int stage;
			if (phase < 0.08f) stage = LIGHT;
			else if (phase < 0.08f + deepShare) stage = DEEP;
			else if (phase >= 1f - remShare) stage = REM;
			else stage = LIGHT;
			if (sm != null) {
				if ((stage != LIGHT) && sm[i] > 14f) stage = LIGHT;
				else if ((stage == LIGHT) && (phase > 0.08f) && sm[i] < 1.5f && cycle < 2 && phase < 0.5f) stage = DEEP;
			}
			r.stage[i] = (byte) stage;
			if (stage == DEEP) deep += SleepNight.EPOCH_MS;
			else if (stage == REM) rem += SleepNight.EPOCH_MS;
			else light += SleepNight.EPOCH_MS;
		}
		r.deepMs = deep;
		r.remMs = rem;
		r.lightMs = light;
	}

	private static void scoreIt(Result r, List<SleepNight> before, Settings st) {
		float wd = Math.max(0, st.wDuration);
		float wc = Math.max(0, st.wConsistency);
		float wl = Math.max(0, st.wLatency);
		float wi = Math.max(0, st.wInterruptions);
		float total = wd + wc + wl + wi;
		if (total <= 0) {
			wd = wc = wl = wi = 25f;
			total = 100f;
		}
		float k = 100f / total;

		// Duration: the goal is full marks, a lot over it costs a little.
		float ratio = (st.goalMin <= 0) ? 0 : (r.asleepMs / 60_000f) / st.goalMin;
		float dur = (ratio <= 1f) ? ratio : (ratio <= 1.25f) ? 1f : Math.max(0.6f, 1f - (ratio - 1.25f));
		r.duration = new Part(dur, wd * k);

		// Consistency: how far bed time and wake time are from the last nights' (else from the usual times).
		float dev = (r.sampleDeviationMin >= 0f) ? r.sampleDeviationMin : consistencyDeviationMin(r, before, st);
		r.consistency = new Part(1f - dev / 120f, wc * k);

		// Time to fall asleep: 15 minutes or less is full marks, 75 or more is none.
		float latMin = r.latencyMs / 60_000f;
		r.latency = new Part(1f - (latMin - 15f) / 60f, wl * k);

		// Interruptions: each awakening and the time awake after falling asleep take points.
		float asleepMin = Math.max(1f, r.asleepMs / 60_000f);
		float penalty = r.awakenings * 0.12f + 2f * (r.wakeAfterOnsetMs / 60_000f) / asleepMin;
		r.interruptions = new Part(1f - penalty, wi * k);

		float sum = r.duration.points() + r.consistency.points() + r.latency.points() + r.interruptions.points();
		r.score = Math.max(0, Math.min(100, Math.round(sum)));
		if (r.asleepMs <= 0) r.score = 0;
	}

	/** Average minutes bed time and wake time are away from their reference. */
	static float consistencyDeviationMin(Result r, List<SleepNight> before, Settings st) {
		int bed = minuteOfDay(r.bedTime());
		int wake = minuteOfDay(r.wakeTime());
		int refBed = st.bedMin;
		int refWake = st.wakeMin;
		int used = 0;
		if (before != null) {
			// The previous six nights, with their own bed and wake times.
			double bx = 0, by = 0, wx = 0, wy = 0;
			for (int i = before.size() - 1; (i >= 0) && (used < 6); i--) {
				SleepNight b = before.get(i);
				if (b.start >= r.night.start) continue;
				double ba = minuteOfDay(b.start) / 1440.0 * 2 * Math.PI;
				double wa = minuteOfDay(b.end) / 1440.0 * 2 * Math.PI;
				bx += Math.cos(ba);
				by += Math.sin(ba);
				wx += Math.cos(wa);
				wy += Math.sin(wa);
				used++;
			}
			if (used >= 2) {
				refBed = angleToMinute(Math.atan2(by, bx));
				refWake = angleToMinute(Math.atan2(wy, wx));
			}
		}
		return (circular(bed, refBed) + circular(wake, refWake)) / 2f;
	}

	static int minuteOfDay(long time) {
		java.util.Calendar c = java.util.Calendar.getInstance();
		c.setTimeInMillis(time);
		return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
	}

	private static int angleToMinute(double a) {
		double m = a / (2 * Math.PI) * 1440.0;
		if (m < 0) m += 1440.0;
		return (int) Math.round(m) % 1440;
	}

	/** The distance between two times of day, going whichever way round is shorter. */
	static float circular(int a, int b) {
		int d = Math.abs(a - b) % 1440;
		return Math.min(d, 1440 - d);
	}

	/** The wording of a score. */
	public static int grade(int score) {
		return (score >= 85) ? 3 : (score >= 70) ? 2 : (score >= 55) ? 1 : 0;
	}
}
