package me.aap.fermata.addon.web.yt;

import androidx.annotation.StringRes;

/**
 * Fixed 10-band gain curves for the YouTube Web Audio equalizer. Unlike the native
 * {@link android.media.audiofx.Equalizer}, there's no hardware/OS preset table to query here, so
 * the curves are baked in, reusing the preset name strings already used by the native equalizer UI.
 */
final class YoutubeEqualizerPresets {
	static final int NUM_BANDS = 10;
	static final int[] CENTER_FREQ_HZ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
	static final short BAND_MIN = -1200;
	static final short BAND_MAX = 1200;

	@StringRes
	static final int[] PRESET_NAMES = {
			me.aap.fermata.R.string.eq_flat,
			me.aap.fermata.R.string.eq_normal,
			me.aap.fermata.R.string.eq_classical,
			me.aap.fermata.R.string.eq_dance,
			me.aap.fermata.R.string.eq_folk,
			me.aap.fermata.R.string.eq_heavy_metal,
			me.aap.fermata.R.string.eq_hip_hop,
			me.aap.fermata.R.string.eq_jazz,
			me.aap.fermata.R.string.eq_pop,
			me.aap.fermata.R.string.eq_rock
	};

	// Gain per band in dB, in the same order as CENTER_FREQ_HZ.
	private static final int[][] PRESETS_DB = {
			{0, 0, 0, 0, 0, 0, 0, 0, 0, 0},       // Flat
			{1, 1, 0, 0, 0, 0, 0, 0, 1, 1},       // Normal
			{0, 0, 0, 0, 0, 0, -2, -2, -3, -4},   // Classical
			{6, 4, 1, 0, 0, -3, -4, -4, 0, 0},    // Dance
			{2, 2, 1, 0, -1, -1, 0, 1, 2, 2},     // Folk
			{4, 3, 2, 0, 2, 4, 5, 3, 2, 2},        // Heavy Metal
			{5, 4, 2, 1, -1, -1, 1, 0, 2, 3},      // Hip Hop
			{3, 2, 1, 1, -1, -1, 0, 1, 2, 3},      // Jazz
			{-1, 2, 4, 4, 2, -1, -1, -1, -1, -1},  // Pop
			{4, 3, 2, 0, -1, -1, 0, 2, 3, 4},      // Rock
	};

	static final int[][] PRESETS;

	static {
		PRESETS = new int[PRESETS_DB.length][NUM_BANDS];
		for (int p = 0; p < PRESETS_DB.length; p++) {
			for (int b = 0; b < NUM_BANDS; b++) PRESETS[p][b] = PRESETS_DB[p][b] * 100;
		}
	}

	private YoutubeEqualizerPresets() {
	}
}
