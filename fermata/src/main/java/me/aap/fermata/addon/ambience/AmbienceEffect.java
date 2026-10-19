package me.aap.fermata.addon.ambience;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import me.aap.fermata.R;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * The sounds of the Ambience tab. Each is made on the fly by {@link AmbienceSynth} (no audio files
 * to ship, and a loop that never repeats), with the colours its card and scene are drawn in.
 * Persisted by {@link #key}, never by position.
 */
public enum AmbienceEffect {
	RAIN("rain", R.string.ambience_rain, R.string.ambience_rain_sub, R.drawable.ambience_rain,
			0xFF5B9BFF, 0xFF132447, 70),
	THUNDER("thunder", R.string.ambience_thunder, R.string.ambience_thunder_sub,
			R.drawable.ambience_thunder, 0xFFB69CFF, 0xFF1D1636, 55),
	FIRE("fire", R.string.ambience_fire, R.string.ambience_fire_sub, R.drawable.ambience_fire,
			0xFFFF8A3D, 0xFF3A1708, 60),
	WIND("wind", R.string.ambience_wind, R.string.ambience_wind_sub, R.drawable.ambience_wind,
			0xFF7FD6C8, 0xFF0E2D2D, 55),
	OCEAN("ocean", R.string.ambience_ocean, R.string.ambience_ocean_sub, R.drawable.ambience_ocean,
			0xFF3CC3E8, 0xFF08293D, 60),
	STREAM("stream", R.string.ambience_stream, R.string.ambience_stream_sub,
			R.drawable.ambience_stream, 0xFF6EE0B0, 0xFF0B2E26, 55),
	NIGHT("night", R.string.ambience_night, R.string.ambience_night_sub, R.drawable.ambience_night,
			0xFFF2D675, 0xFF151A3A, 50),
	BIRDS("birds", R.string.ambience_birds, R.string.ambience_birds_sub, R.drawable.ambience_birds,
			0xFFA6E86B, 0xFF1B3314, 50);

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
	public final Pref<IntSupplier> intensity;

	AmbienceEffect(String key, int name, int subtitle, int icon, int tint, int deep, int defLevel) {
		this.key = key;
		this.name = name;
		this.subtitle = subtitle;
		this.icon = icon;
		this.tint = tint;
		this.deep = deep;
		this.intensity = Pref.i("AMBIENCE_LEVEL_" + key.toUpperCase(), defLevel);
	}

	public static AmbienceEffect byKey(String key) {
		for (AmbienceEffect e : values()) if (e.key.equals(key)) return e;
		return null;
	}
}
