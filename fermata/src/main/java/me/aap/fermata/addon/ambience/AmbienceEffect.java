package me.aap.fermata.addon.ambience;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import me.aap.fermata.R;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * The sounds of the Ambience tab. Each is made on the fly by {@link AmbienceSynth} (no audio files
 * to ship, and a loop that never repeats), with the colours its card and scene are drawn in.
 * Besides the intensity every sound has one "character" slider of its own (the card's tune page:
 * rain's surface, thunder's distance, the fire's crackle, ...) with the words for its two ends.
 * Persisted by {@link #key}, never by position.
 */
public enum AmbienceEffect {
	RAIN("rain", R.string.ambience_rain, R.string.ambience_rain_sub, R.drawable.ambience_rain,
			0xFF5B9BFF, 0xFF132447, 70,
			R.string.ambience_opt_rain, R.string.ambience_opt_rain_lo, R.string.ambience_opt_rain_hi),
	WINDOW("window", R.string.ambience_window, R.string.ambience_window_sub, R.drawable.ambience_window,
			0xFFA9C4E6, 0xFF1B2433, 50,
			R.string.ambience_opt_window, R.string.ambience_opt_window_lo, R.string.ambience_opt_window_hi),
	THUNDER("thunder", R.string.ambience_thunder, R.string.ambience_thunder_sub,
			R.drawable.ambience_thunder, 0xFFB69CFF, 0xFF1D1636, 55,
			R.string.ambience_opt_thunder, R.string.ambience_opt_thunder_lo, R.string.ambience_opt_thunder_hi),
	FIRE("fire", R.string.ambience_fire, R.string.ambience_fire_sub, R.drawable.ambience_fire,
			0xFFFF8A3D, 0xFF3A1708, 60,
			R.string.ambience_opt_fire, R.string.ambience_opt_fire_lo, R.string.ambience_opt_fire_hi),
	WIND("wind", R.string.ambience_wind, R.string.ambience_wind_sub, R.drawable.ambience_wind,
			0xFF7FD6C8, 0xFF0E2D2D, 55,
			R.string.ambience_opt_wind, R.string.ambience_opt_wind_lo, R.string.ambience_opt_wind_hi),
	OCEAN("ocean", R.string.ambience_ocean, R.string.ambience_ocean_sub, R.drawable.ambience_ocean,
			0xFF3CC3E8, 0xFF08293D, 60,
			R.string.ambience_opt_ocean, R.string.ambience_opt_ocean_lo, R.string.ambience_opt_ocean_hi),
	STREAM("stream", R.string.ambience_stream, R.string.ambience_stream_sub,
			R.drawable.ambience_stream, 0xFF6EE0B0, 0xFF0B2E26, 55,
			R.string.ambience_opt_stream, R.string.ambience_opt_stream_lo, R.string.ambience_opt_stream_hi),
	DROPS("drops", R.string.ambience_drops, R.string.ambience_drops_sub, R.drawable.ambience_drops,
			0xFF9AE0F5, 0xFF0A2431, 50,
			R.string.ambience_opt_drops, R.string.ambience_opt_drops_lo, R.string.ambience_opt_drops_hi),
	BUBBLES("bubbles", R.string.ambience_bubbles, R.string.ambience_bubbles_sub,
			R.drawable.ambience_bubbles, 0xFF5EEAD4, 0xFF042F35, 55,
			R.string.ambience_opt_bubbles, R.string.ambience_opt_bubbles_lo, R.string.ambience_opt_bubbles_hi),
	NIGHT("night", R.string.ambience_night, R.string.ambience_night_sub, R.drawable.ambience_night,
			0xFFF2D675, 0xFF151A3A, 50,
			R.string.ambience_opt_night, R.string.ambience_opt_night_lo, R.string.ambience_opt_night_hi),
	BIRDS("birds", R.string.ambience_birds, R.string.ambience_birds_sub, R.drawable.ambience_birds,
			0xFFA6E86B, 0xFF1B3314, 50,
			R.string.ambience_opt_birds, R.string.ambience_opt_birds_lo, R.string.ambience_opt_birds_hi),
	CAFE("cafe", R.string.ambience_cafe, R.string.ambience_cafe_sub, R.drawable.ambience_cafe,
			0xFFD9A066, 0xFF2E1D10, 55,
			R.string.ambience_opt_cafe, R.string.ambience_opt_cafe_lo, R.string.ambience_opt_cafe_hi);

	public final String key;
	@StringRes
	public final int name;
	@StringRes
	public final int subtitle;
	@DrawableRes
	public final int icon;
	/** The bright colour: icon, glow, particles. */
	public final int tint;
	/** The deep colour the card's background fades through. */
	public final int deep;
	/** How strong it is the first time (0 to 100), then whatever the user left it at. */
	public final int defLevel;
	public final Pref<IntSupplier> intensity;
	/** The sound's own character slider (0 to 100, 50 is how it was designed): its name and its two ends. */
	public final Pref<IntSupplier> option;
	/** Whether the recorded loop (see {@link AmbienceSamples}) is wanted instead of the synthesized voice. */
	public final Pref<BooleanSupplier> recorded;
	/** The tone slider of the recorded loop (0 to 100, 50 leaves the recording as it is). */
	public final Pref<IntSupplier> tone;
	@StringRes
	public final int optName;
	@StringRes
	public final int optLow;
	@StringRes
	public final int optHigh;

	AmbienceEffect(String key, int name, int subtitle, int icon, int tint, int deep, int defLevel,
								 int optName, int optLow, int optHigh) {
		this.key = key;
		this.name = name;
		this.subtitle = subtitle;
		this.icon = icon;
		this.tint = tint;
		this.deep = deep;
		this.defLevel = defLevel;
		this.intensity = Pref.i("AMBIENCE_LEVEL_" + key.toUpperCase(), defLevel);
		this.option = Pref.i("AMBIENCE_OPT_" + key.toUpperCase(), 50);
		this.recorded = Pref.b("AMBIENCE_SRC_" + key.toUpperCase(), false);
		this.tone = Pref.i("AMBIENCE_TONE_" + key.toUpperCase(), 50);
		this.optName = optName;
		this.optLow = optLow;
		this.optHigh = optHigh;
	}

	public static AmbienceEffect byKey(String key) {
		for (AmbienceEffect e : values()) if (e.key.equals(key)) return e;
		return null;
	}
}
