package me.aap.fermata.media.engine.stage;

/**
 * The sound stage's signal processing on interleaved stereo float samples: stereo width, a
 * differential surround and a binaural 3D placement. No Android classes in here, so it runs (and is
 * tested) anywhere.
 * <ul>
 * <li><b>Width</b> scales the side (L-R) signal against the mid (L+R) one: 0 = mono, 1 = as
 * recorded, 2 = twice as wide.</li>
 * <li><b>Differential surround</b>, in the spirit of ViPER4Android's: the L-R difference, high-passed
 * (the bass stays in the middle) and delayed by a few milliseconds, is added to the left and
 * subtracted from the right channel. The delayed difference decorrelates the two speakers, which the
 * ear hears as a wider, more enveloping stage.</li>
 * <li><b>3D position</b> renders the left and right channel as two virtual speakers, a few degrees
 * either side of the chosen direction, to the listener's two ears: the far ear hears the sound a
 * fraction of a millisecond later (interaural time difference), quieter and duller (the head's
 * shadow), and a sound from behind loses its highs (the ear's shape). Close to the listener the
 * effect fades out, so dragging to the middle gives the plain stereo back. With Orbit on the
 * direction keeps turning.</li>
 * </ul>
 * Parameters change smoothly across each block, so dragging a control makes no clicks. Not
 * thread-safe: one instance, one audio thread.
 */
public final class StageDsp {
	private static final double HEAD_RADIUS = 0.0875;
	private static final double SOUND_SPEED = 343;
	private static final int DIFF_RING = 1024;
	private static final int EAR_RING = 256;
	private static final double SOFT_KNEE = 0.92;

	private final int sampleRate;
	private final double maxCutoff;
	// Differential surround.
	private final float[] diffRing = new float[DIFF_RING];
	private int diffPos;
	private float diffLp;
	private final float diffHpCoef;
	// 3D: the input of each virtual speaker (left channel, right channel) and one low-pass state per
	// speaker and ear.
	private final float[][] earRing = new float[2][EAR_RING];
	private int earPos;
	private final float[][] earLp = new float[2][2];
	// Parameters at the end of the previous block, and this block's targets.
	private final float[] cur = new float[PER_EAR + 12];
	private final float[] tgt = new float[PER_EAR + 12];
	private boolean primed;
	private double orbitAngle;

	// Indices into cur/tgt.
	private static final int WIDTH = 0;
	private static final int DIFF_K = 1;
	private static final int DIFF_DELAY = 2;
	private static final int WET = 3;
	private static final int WET_GAIN = 4;
	// PER_EAR..PER_EAR+11: per speaker (2) and ear (2), 3 values each: delay, gain, cutoff coefficient;
	// declared before the arrays that use it.
	private static final int PER_EAR = 5;

	public StageDsp(int sampleRate) {
		this.sampleRate = Math.max(8000, sampleRate);
		this.maxCutoff = Math.min(18000, this.sampleRate * 0.45);
		this.diffHpCoef = (float) coef(200);
	}

	public void reset() {
		java.util.Arrays.fill(diffRing, 0);
		for (float[] r : earRing) java.util.Arrays.fill(r, 0);
		for (float[] l : earLp) java.util.Arrays.fill(l, 0);
		diffLp = 0;
		diffPos = 0;
		earPos = 0;
		primed = false;
	}

	private double coef(double cutoffHz) {
		return 1 - Math.exp(-2 * Math.PI * cutoffHz / sampleRate);
	}

	/**
	 * Processes {@code frames} stereo frames from {@code in} to {@code out} (they may be the same
	 * array and offset).
	 */
	public void process(float[] in, int inOff, float[] out, int outOff, int frames, StageParams p) {
		if (frames <= 0) return;
		computeTargets(p, frames);
		if (!primed) {
			System.arraycopy(tgt, 0, cur, 0, tgt.length);
			primed = true;
		}

		boolean wet = p.posOn && (tgt[WET] > 0.001f || cur[WET] > 0.001f);
		boolean diff = p.diffOn && ((tgt[DIFF_K] > 0.0001f) || (cur[DIFF_K] > 0.0001f));
		float inv = 1f / frames;

		for (int i = 0; i < frames; i++) {
			float t = (i + 1) * inv;
			float l = in[inOff + 2 * i];
			float r = in[inOff + 2 * i + 1];

			// Width: mid/side.
			float width = cur[WIDTH] + (tgt[WIDTH] - cur[WIDTH]) * t;
			if (p.widthOn && width != 1f) {
				float m = (l + r) * 0.5f;
				float s = (l - r) * 0.5f * width;
				l = m + s;
				r = m - s;
			}

			// Differential surround.
			if (diff) {
				float k = cur[DIFF_K] + (tgt[DIFF_K] - cur[DIFF_K]) * t;
				float dly = cur[DIFF_DELAY] + (tgt[DIFF_DELAY] - cur[DIFF_DELAY]) * t;
				float d = l - r;
				diffRing[diffPos & (DIFF_RING - 1)] = d;
				float dd = tap(diffRing, diffPos, dly, DIFF_RING - 1);
				diffLp += diffHpCoef * (dd - diffLp);
				float side = (dd - diffLp) * k;
				float norm = 1f / (1f + 0.5f * k);
				l = (l + side) * norm;
				r = (r - side) * norm;
				diffPos++;
			}

			// 3D position.
			if (wet) {
				float w = cur[WET] + (tgt[WET] - cur[WET]) * t;
				float g = cur[WET_GAIN] + (tgt[WET_GAIN] - cur[WET_GAIN]) * t;
				earRing[0][earPos & (EAR_RING - 1)] = l;
				earRing[1][earPos & (EAR_RING - 1)] = r;
				float earL = 0;
				float earR = 0;
				for (int s = 0; s < 2; s++) {
					for (int e = 0; e < 2; e++) {
						int b = PER_EAR + (s * 2 + e) * 3;
						float dly = cur[b] + (tgt[b] - cur[b]) * t;
						float gain = cur[b + 1] + (tgt[b + 1] - cur[b + 1]) * t;
						float a = cur[b + 2] + (tgt[b + 2] - cur[b + 2]) * t;
						float x = tap(earRing[s], earPos, dly, EAR_RING - 1);
						float lp = earLp[s][e] += a * (x - earLp[s][e]);
						if (e == 0) earL += lp * gain;
						else earR += lp * gain;
					}
				}
				earPos++;
				l = l * (1 - w) + earL * g * w;
				r = r * (1 - w) + earR * g * w;
			}

			out[outOff + 2 * i] = soft(l);
			out[outOff + 2 * i + 1] = soft(r);
		}

		System.arraycopy(tgt, 0, cur, 0, tgt.length);
	}

	/** The sample {@code delay} (fractional) samples before the newest one, linearly interpolated. */
	private static float tap(float[] ring, int pos, float delay, int mask) {
		int whole = (int) delay;
		float frac = delay - whole;
		float a = ring[(pos - whole) & mask];
		float b = ring[(pos - whole - 1) & mask];
		return a + (b - a) * frac;
	}

	private static float soft(float x) {
		float a = Math.abs(x);
		if (a <= SOFT_KNEE) return x;
		float over = (float) Math.tanh((a - SOFT_KNEE) / (1 - SOFT_KNEE)) * (float) (1 - SOFT_KNEE);
		return Math.copySign((float) SOFT_KNEE + over, x);
	}

	private void computeTargets(StageParams p, int frames) {
		tgt[WIDTH] = p.widthOn ? p.width / 100f : 1f;
		tgt[DIFF_K] = p.diffOn ? p.diffStrength / 100f * 0.7f : 0f;
		tgt[DIFF_DELAY] = Math.min(DIFF_RING - 4, p.diffDelayMs * sampleRate / 1000f);

		if (!p.posOn) {
			tgt[WET] = 0f;
			return;
		}

		if (p.orbit > 0) {
			orbitAngle += 2 * Math.PI * (p.orbit / 10.0) / 60.0 * frames / sampleRate;
			if (orbitAngle > 2 * Math.PI) orbitAngle -= 2 * Math.PI;
		} else {
			orbitAngle = 0;
		}

		double dist = p.distance();
		// The effect fades in over the first third of the way out from the listener.
		double x = Math.max(0, Math.min(1, (dist - 0.03) / 0.3));
		tgt[WET] = (float) (x * x * (3 - 2 * x));
		tgt[WET_GAIN] = (float) (0.85 / (1 + 0.5 * dist));

		double az = Math.toRadians(p.azimuthDegrees()) + orbitAngle;
		double spread = Math.toRadians(p.spread);
		for (int s = 0; s < 2; s++) {
			double a = az + (s == 0 ? -spread : spread);
			double sin = Math.sin(a);
			double cos = Math.cos(a);
			double lateral = Math.abs(sin);
			double rear = Math.max(0, -cos);
			double lat = Math.asin(Math.min(1, lateral));
			double itd = HEAD_RADIUS / SOUND_SPEED * (lat + Math.sin(lat)) * sampleRate;
			double rearGain = 1 - 0.2 * rear;
			int near = (sin >= 0) ? 1 : 0;

			for (int e = 0; e < 2; e++) {
				int b = PER_EAR + (s * 2 + e) * 3;
				boolean isNear = (e == near);
				double fc = maxCutoff * Math.pow(0.55, rear);
				if (!isNear) fc *= Math.pow(0.1, lateral);
				tgt[b] = isNear ? 0f : (float) Math.min(EAR_RING - 4, itd);
				tgt[b + 1] = (float) (rearGain * (isNear ? 1 + 0.25 * lateral :
						Math.pow(10, -7.0 * lateral / 20)));
				tgt[b + 2] = (float) coef(fc);
			}
		}
	}
}
