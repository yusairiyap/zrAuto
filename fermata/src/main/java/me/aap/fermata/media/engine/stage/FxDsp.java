package me.aap.fermata.media.engine.stage;

import java.util.Arrays;

/**
 * The YouTube equalizer's signal chain on interleaved stereo float samples, so the native player can
 * sound like the YouTube page does: the 10-band equalizer (peaking filters, Q 1), Bass boost (a low
 * shelf at 200 Hz), the Virtualizer (a Haas-effect widener) and the Live Hall reverb (the Freeverb
 * comb and allpass network, as the page's script builds it), followed by the sound stage
 * ({@link StageDsp}) and a soft limiter. The values mirror {@code youtube_equalizer.js}; the page's
 * "rich" convolution reverb has no counterpart here, the algorithmic one is used for both.
 * No Android classes in here. Not thread-safe: one instance, one audio thread.
 */
public final class FxDsp {
	private static final double BASS_HZ = 200;
	private static final double[] COMB_MS = {55.68, 59.27, 63.71, 67.65, 70.93, 74.38, 77.68, 80.67};
	private static final double SPREAD_MS = 0.52;
	private static final double[] ALLPASS_MS = {12.61, 10.0, 7.73, 5.10};
	private static final float ALLPASS_G = 0.5f;
	private static final float COMB_NORM = (float) (1 / Math.sqrt(COMB_MS.length));
	private static final double DAMP_MAX_HZ = 5500;
	private static final double DAMP_MIN_HZ = 1400;
	private static final double MAX_FEEDBACK = 0.9;
	private static final float REVERB_WET_SCALE = 0.35f;
	private static final int VIRT_RING = 8192;
	private static final float LIMIT_KNEE = 0.89f;

	private final int sampleRate;
	private final StageDsp stage;
	private final Biquad[][] eq = new Biquad[2][FxParams.NUM_BANDS];
	private final Biquad[] bass = {new Biquad(), new Biquad()};
	private final float[] virtRing = new float[VIRT_RING];
	private int virtPos;
	private final Reverb[] reverb;
	private FxParams applied;
	private boolean eqActive;
	private boolean bassActive;
	private float virtStrength;
	private float reverbGain;

	public FxDsp(int sampleRate) {
		this.sampleRate = Math.max(8000, sampleRate);
		this.stage = new StageDsp(this.sampleRate);
		for (Biquad[] ch : eq) {
			for (int i = 0; i < ch.length; i++) ch[i] = new Biquad();
		}
		reverb = new Reverb[]{new Reverb(0), new Reverb(SPREAD_MS)};
	}

	public void reset() {
		stage.reset();
		for (Biquad[] ch : eq) for (Biquad b : ch) b.clear();
		for (Biquad b : bass) b.clear();
		Arrays.fill(virtRing, 0);
		virtPos = 0;
		for (Reverb r : reverb) r.clear();
	}

	/** Processes {@code frames} stereo frames of {@code x} in place. */
	public void process(float[] x, int frames, FxParams fx, StageParams st) {
		if (frames <= 0) return;
		if (fx != applied) configure(fx);

		boolean pre = eqActive || bassActive || (virtStrength > 0);
		boolean wet = reverbGain > 0;
		if (pre) pre(x, frames);
		if (st.isActive()) stage.process(x, 0, x, 0, frames, st);
		if (wet) reverb(x, frames);
		if (pre || wet) limit(x, frames);
	}

	private void configure(FxParams fx) {
		applied = fx;

		eqActive = false;
		for (int i = 0; i < FxParams.NUM_BANDS; i++) {
			double db = fx.eqOn ? fx.bands[i] / 100.0 : 0;
			double hz = FxParams.BAND_HZ[i];
			boolean on = (Math.abs(db) > 0.01) && (hz < sampleRate * 0.45);
			for (Biquad[] ch : eq) {
				if (on) ch[i].peaking(sampleRate, hz, 1.0, db);
				else ch[i].off();
			}
			eqActive |= on;
		}

		double bassDb = fx.bassOn ? fx.bass * 18.0 / 1000 : 0;
		bassActive = bassDb > 0.01;
		for (Biquad b : bass) {
			if (bassActive) b.lowShelf(sampleRate, BASS_HZ, bassDb);
			else b.off();
		}

		virtStrength = fx.virtOn ? fx.virt / 1000f : 0f;

		reverbGain = (fx.reverbOn ? fx.reverb / 1000f : 0f) * REVERB_WET_SCALE;
		for (Reverb r : reverb) r.setDuration(fx.reverbMs / 1000.0);
	}

	private void pre(float[] x, int frames) {
		float s = virtStrength;
		float delay = (float) ((0.005 + 0.03 * 0.67 * s) * sampleRate);

		for (int i = 0; i < frames; i++) {
			float l = x[2 * i];
			float r = x[2 * i + 1];

			if (eqActive) {
				for (int b = 0; b < FxParams.NUM_BANDS; b++) {
					if (!eq[0][b].on) continue;
					l = eq[0][b].run(l);
					r = eq[1][b].run(r);
				}
			}

			if (bassActive) {
				l = bass[0].run(l);
				r = bass[1].run(r);
			}

			if (s > 0) {
				virtRing[virtPos & (VIRT_RING - 1)] = r;
				float rd = tap(virtRing, virtPos, delay);
				virtPos++;
				l = l + 0.3f * s * rd;
				r = (1 - 0.5f * s) * r + 0.5f * s * rd;
			}

			x[2 * i] = l;
			x[2 * i + 1] = r;
		}
	}

	private void reverb(float[] x, int frames) {
		float g = reverbGain;
		for (int i = 0; i < frames; i++) {
			float l = x[2 * i];
			float r = x[2 * i + 1];
			x[2 * i] = l + reverb[0].process(l) * g;
			x[2 * i + 1] = r + reverb[1].process(r) * g;
		}
	}

	private static void limit(float[] x, int frames) {
		for (int i = 0, n = frames * 2; i < n; i++) {
			float v = x[i];
			float a = Math.abs(v);
			if (a > LIMIT_KNEE) {
				float over = (float) Math.tanh((a - LIMIT_KNEE) / (1 - LIMIT_KNEE)) * (1 - LIMIT_KNEE);
				x[i] = Math.copySign(LIMIT_KNEE + over, v);
			}
		}
	}

	/** The sample {@code delay} (fractional) samples before the newest one, linearly interpolated. */
	private static float tap(float[] ring, int pos, float delay) {
		int whole = (int) delay;
		float frac = delay - whole;
		int mask = ring.length - 1;
		float a = ring[(pos - whole) & mask];
		float b = ring[(pos - whole - 1) & mask];
		return a + (b - a) * frac;
	}

	/** One biquad section (RBJ cookbook), transposed direct form II. */
	private static final class Biquad {
		boolean on;
		private double b0 = 1;
		private double b1;
		private double b2;
		private double a1;
		private double a2;
		private double z1;
		private double z2;

		void off() {
			on = false;
			clear();
		}

		void clear() {
			z1 = 0;
			z2 = 0;
		}

		void peaking(int sr, double hz, double q, double db) {
			double a = Math.pow(10, db / 40);
			double w = 2 * Math.PI * hz / sr;
			double alpha = Math.sin(w) / (2 * q);
			double cos = Math.cos(w);
			set(1 + alpha * a, -2 * cos, 1 - alpha * a, 1 + alpha / a, -2 * cos, 1 - alpha / a);
		}

		void lowShelf(int sr, double hz, double db) {
			double a = Math.pow(10, db / 40);
			double w = 2 * Math.PI * hz / sr;
			double cos = Math.cos(w);
			// Shelf slope 1.
			double alpha = Math.sin(w) / 2 * Math.sqrt(2);
			double sq = 2 * Math.sqrt(a) * alpha;
			set(a * ((a + 1) - (a - 1) * cos + sq), 2 * a * ((a - 1) - (a + 1) * cos),
					a * ((a + 1) - (a - 1) * cos - sq), (a + 1) + (a - 1) * cos + sq,
					-2 * ((a - 1) + (a + 1) * cos), (a + 1) + (a - 1) * cos - sq);
		}

		private void set(double nb0, double nb1, double nb2, double a0, double na1, double na2) {
			b0 = nb0 / a0;
			b1 = nb1 / a0;
			b2 = nb2 / a0;
			a1 = na1 / a0;
			a2 = na2 / a0;
			on = true;
		}

		float run(float x) {
			double y = b0 * x + z1;
			z1 = b1 * x - a1 * y + z2;
			z2 = b2 * x - a2 * y;
			// Through silence the state decays toward the denormal range, where ARM slows to a crawl.
			if (Math.abs(z1) < 1e-20) z1 = 0;
			if (Math.abs(z2) < 1e-20) z2 = 0;
			return (float) y;
		}
	}

	/** One channel of the hall: 8 damped combs in parallel into 4 allpasses in series (Freeverb). */
	private final class Reverb {
		private final float[][] comb = new float[COMB_MS.length][];
		private final int[] combPos = new int[COMB_MS.length];
		private final float[] feedback = new float[COMB_MS.length];
		private final float[] lowPass = new float[COMB_MS.length];
		private final double[] combSeconds = new double[COMB_MS.length];
		private final float[][] allpass = new float[ALLPASS_MS.length][];
		private final int[] allpassPos = new int[ALLPASS_MS.length];
		private float damp;

		Reverb(double spreadMs) {
			for (int i = 0; i < comb.length; i++) {
				double ms = COMB_MS[i] + spreadMs;
				combSeconds[i] = ms / 1000;
				comb[i] = new float[Math.max(2, (int) Math.round(ms * sampleRate / 1000))];
			}
			for (int i = 0; i < allpass.length; i++) {
				allpass[i] = new float[Math.max(2, (int) Math.round(ALLPASS_MS[i] * sampleRate / 1000))];
			}
		}

		void clear() {
			for (float[] c : comb) Arrays.fill(c, 0);
			for (float[] a : allpass) Arrays.fill(a, 0);
			Arrays.fill(lowPass, 0);
			Arrays.fill(combPos, 0);
			Arrays.fill(allpassPos, 0);
		}

		/** How long the hall rings (RT60); a longer one is also a darker one, as in a real hall. */
		void setDuration(double rt60) {
			double t = Math.max(0, Math.min(1, (rt60 - 0.3) / (3.0 - 0.3)));
			double hz = DAMP_MAX_HZ - (DAMP_MAX_HZ - DAMP_MIN_HZ) * t;
			damp = (float) (1 - Math.exp(-2 * Math.PI * hz / sampleRate));
			for (int i = 0; i < feedback.length; i++) {
				double fb = Math.pow(0.001, combSeconds[i] / Math.max(0.05, rt60));
				feedback[i] = (float) Math.min(MAX_FEEDBACK, fb);
			}
		}

		float process(float x) {
			float sum = 0;

			for (int i = 0; i < comb.length; i++) {
				float[] c = comb[i];
				int p = combPos[i];
				float y = c[p];
				float lp = lowPass[i] + damp * (y - lowPass[i]);
				lowPass[i] = (Math.abs(lp) < 1e-18f) ? 0f : lp;
				c[p] = x + feedback[i] * lowPass[i];
				combPos[i] = (p + 1 == c.length) ? 0 : (p + 1);
				sum += y;
			}

			float v = sum * COMB_NORM;

			for (int j = 0; j < allpass.length; j++) {
				float[] a = allpass[j];
				int p = allpassPos[j];
				float delayed = a[p];
				a[p] = v + ALLPASS_G * delayed;
				v = -ALLPASS_G * v + delayed;
				allpassPos[j] = (p + 1 == a.length) ? 0 : (p + 1);
			}

			return v;
		}
	}
}
