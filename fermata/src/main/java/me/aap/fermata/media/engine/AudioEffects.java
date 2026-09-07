package me.aap.fermata.media.engine;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;
import android.os.Build;

import androidx.annotation.Nullable;

import me.aap.utils.log.Log;

/**
 * @author Andrey Pavlenko
 */
public class AudioEffects {
	private static final byte EQUALIZER = 1;
	private static final byte VIRTUALIZER = 2;
	private static final byte BASS_BOOST = 4;
	private static final byte LOUDNESS_ENHANCER = 8;
	private static final byte PRESET_REVERB = 16;
	private static final byte supported;
	private final Equalizer equalizer;
	private final Virtualizer virtualizer;
	private final BassBoost bassBoost;
	private final LoudnessEnhancer loudnessEnhancer;
	private final PresetReverb presetReverb;

	static {
		byte s = 0;
		for (AudioEffect.Descriptor d : AudioEffect.queryEffects()) {
			if (AudioEffect.EFFECT_TYPE_EQUALIZER.equals(d.type)) s |= EQUALIZER;
			else if (AudioEffect.EFFECT_TYPE_VIRTUALIZER.equals(d.type)) s |= VIRTUALIZER;
			else if (AudioEffect.EFFECT_TYPE_BASS_BOOST.equals(d.type)) s |= BASS_BOOST;
			else if (AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER.equals(d.type)) s |= LOUDNESS_ENHANCER;
			else if (AudioEffect.EFFECT_TYPE_PRESET_REVERB.equals(d.type)) s |= PRESET_REVERB;
		}
		supported = s;
	}

	private AudioEffects(int priority, int audioSessionId) {
		equalizer = supported(EQUALIZER) ? new Equalizer(priority, audioSessionId) : null;
		virtualizer = SDK_INT < VANILLA_ICE_CREAM && supported(VIRTUALIZER) ?
				new Virtualizer(priority, audioSessionId) : null;
		bassBoost = supported(BASS_BOOST) ? new BassBoost(priority, audioSessionId) : null;
		loudnessEnhancer = supported(LOUDNESS_ENHANCER) ? new LoudnessEnhancer(audioSessionId) : null;
		// A "Live Hall" reverb is an auxiliary/send effect, not an insert effect like the others --
		// creating it here just makes it exist on this audioSessionId; actually routing audio to it
		// (attachAuxEffect/AuxEffectInfo + send level) is the engine's job, not this class's. Some
		// devices report EFFECT_TYPE_PRESET_REVERB as available but still fail to actually construct
		// one (real-world platform flakiness, seen especially on emulators/certain audio HALs) -- that
		// failure is isolated here so it can't take the other four (otherwise solid) effects down
		// with it, unlike everything else in this constructor which is allowed to propagate and be
		// retried by create() below.
		presetReverb = supported(PRESET_REVERB) ? tryBuildPresetReverb(priority, audioSessionId) : null;
	}

	@Nullable
	private static PresetReverb tryBuildPresetReverb(int priority, int audioSessionId) {
		try {
			PresetReverb reverb = new PresetReverb(priority, audioSessionId);
			reverb.setPreset(PresetReverb.PRESET_LARGEHALL);
			return reverb;
		} catch (Exception ex) {
			Log.w(ex, "PresetReverb (Live Hall) unavailable on this device");
			return null;
		}
	}

	private static boolean supported(byte type) {
		return (supported & type) != 0;
	}

	/** Whether this device supports any {@code android.media.audiofx} effect type at all. */
	public static boolean isSupported() {
		return supported != 0;
	}

	@Nullable
	public static AudioEffects create(int priority, int audioSessionId) {
		if (supported == 0) return null;

		try {
			return new AudioEffects(priority, audioSessionId);
		} catch (Exception ex) {
			// Sometimes it fails with RuntimeException: AudioEffect: set/get parameter error
			Log.w("Failed to create AudioEffects - retrying...");

			try {
				Thread.sleep(300);
				return new AudioEffects(priority, audioSessionId);
			} catch (Exception ex1) {
				Log.e(ex1, "Failed to create AudioEffects");
				return null;
			}
		}
	}

	@Nullable
	public Equalizer getEqualizer() {
		return equalizer;
	}

	@Nullable
	public Virtualizer getVirtualizer() {
		return virtualizer;
	}

	@Nullable
	public BassBoost getBassBoost() {
		return bassBoost;
	}

	@Nullable
	public LoudnessEnhancer getLoudnessEnhancer() {
		return loudnessEnhancer;
	}

	@Nullable
	public PresetReverb getPresetReverb() {
		return presetReverb;
	}

	public void release() {
		if (equalizer != null) equalizer.release();
		if (virtualizer != null) virtualizer.release();
		if (bassBoost != null) bassBoost.release();
		if (loudnessEnhancer != null) loudnessEnhancer.release();
		if (presetReverb != null) presetReverb.release();
	}
}
