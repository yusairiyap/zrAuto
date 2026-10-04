package me.aap.fermata.media.engine.stage;

import java.util.Arrays;

/**
 * An immutable snapshot of the YouTube equalizer's settings -- the 10-band equalizer, Bass boost,
 * Virtualizer and Live Hall reverb -- in the units its screen stores them in. Plain Java, so the DSP
 * ({@link FxDsp}) reads it without touching preferences.
 */
public final class FxParams {
	public static final int NUM_BANDS = 10;
	public static final int[] BAND_HZ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
	public static final FxParams OFF = new FxParams(false, new int[NUM_BANDS], false, 0, false, 0,
			false, 0, 2500);

	public final boolean eqOn;
	/** Gain per band in hundredths of a dB (-1200..1200). */
	public final int[] bands;
	public final boolean bassOn;
	/** 0..1000; 1000 is +18 dB. */
	public final int bass;
	public final boolean virtOn;
	/** 0..1000. */
	public final int virt;
	public final boolean reverbOn;
	/** 0..1500, a thousandth of the wet level each. */
	public final int reverb;
	/** How long the hall rings, 300..3000 ms. */
	public final int reverbMs;

	public FxParams(boolean eqOn, int[] bands, boolean bassOn, int bass, boolean virtOn, int virt,
									boolean reverbOn, int reverb, int reverbMs) {
		this.eqOn = eqOn;
		int[] b = new int[NUM_BANDS];
		if (bands != null) System.arraycopy(bands, 0, b, 0, Math.min(bands.length, NUM_BANDS));
		this.bands = b;
		this.bassOn = bassOn;
		this.bass = clamp(bass, 0, 1000);
		this.virtOn = virtOn;
		this.virt = clamp(virt, 0, 1000);
		this.reverbOn = reverbOn;
		this.reverb = clamp(reverb, 0, 1500);
		this.reverbMs = clamp(reverbMs, 300, 3000);
	}

	/** Whether anything here changes the sound. */
	public boolean isActive() {
		return eqOn || (bassOn && (bass > 0)) || (virtOn && (virt > 0)) || (reverbOn && (reverb > 0));
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof FxParams p)) return false;
		return (eqOn == p.eqOn) && Arrays.equals(bands, p.bands) && (bassOn == p.bassOn) &&
				(bass == p.bass) && (virtOn == p.virtOn) && (virt == p.virt) &&
				(reverbOn == p.reverbOn) && (reverb == p.reverb) && (reverbMs == p.reverbMs);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(bands) * 31 + reverbMs;
	}
}
