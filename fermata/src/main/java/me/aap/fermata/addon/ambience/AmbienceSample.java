package me.aap.fermata.addon.ambience;

/**
 * A recorded loop (see {@link AmbienceSamples}) as a {@link AmbienceSynth.Voice}: the decoded PCM
 * played round and round at the mixer's rate (linear interpolation, the file is 48 kHz), starting
 * at a random place so it is never the same twice. The loop's seam was crossfaded when the file
 * was built. The sound's slider is a tilt: warm rolls the highs off, bright lifts them, the middle
 * leaves the recording as it is.
 */
final class AmbienceSample extends AmbienceSynth.Voice {
	private static final float C_TILT = AmbienceSynth.coef(2500);
	private volatile short[] pcm;
	private volatile int channels = 2;
	private volatile int rate = 48000;
	private double pos;
	private boolean started;
	private float lpL, lpR;

	boolean isReady() {
		return pcm != null;
	}

	void set(short[] data, int ch, int sampleRate) {
		channels = ch;
		rate = sampleRate;
		started = false;
		pcm = data;
	}

	void clear() {
		pcm = null;
	}

	@Override
	void render(float[] l, float[] r, int n, float lv, float opt) {
		short[] d = pcm;
		int ch = channels;
		int frames = (d == null) ? 0 : d.length / ch;
		if (frames < 2) {
			java.util.Arrays.fill(l, 0, n, 0f);
			java.util.Arrays.fill(r, 0, n, 0f);
			return;
		}
		if (!started) {
			pos = Math.random() * frames;
			started = true;
		}
		double step = rate / (double) AmbienceSynth.SR;
		float amp = (0.5f + 0.5f * lv) / 32768f;
		float t = (opt - 0.5f) * 2f;
		float low = 1f - 0.6f * t, high = 1f + 0.8f * t;
		for (int i = 0; i < n; i++) {
			int i0 = (int) pos;
			float f = (float) (pos - i0);
			int i1 = (i0 + 1 >= frames) ? 0 : i0 + 1;
			float sl, sr;
			if (ch == 1) {
				sl = sr = d[i0] + (d[i1] - d[i0]) * f;
			} else {
				sl = d[i0 * ch] + (d[i1 * ch] - d[i0 * ch]) * f;
				sr = d[i0 * ch + 1] + (d[i1 * ch + 1] - d[i0 * ch + 1]) * f;
			}
			lpL += C_TILT * (sl - lpL);
			lpR += C_TILT * (sr - lpR);
			l[i] = (lpL * low + (sl - lpL) * high) * amp;
			r[i] = (lpR * low + (sr - lpR) * high) * amp;
			pos += step;
			if (pos >= frames) pos -= frames;
		}
	}
}
