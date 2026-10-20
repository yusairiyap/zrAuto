package me.aap.fermata.media.engine;

import android.content.Context;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import me.aap.fermata.R;

/**
 * The few audio effects a card can switch without the full effects screen: the equalizer (its bands
 * to show, its preset to step), bass boost, the virtualizer, and a fourth that is the reverb for
 * YouTube's own equalizer and the loudness boost for the platform's. One of these is made for the
 * player that plays (see {@link #of}) or for YouTube's, which keeps its settings whether or not it plays.
 */
public interface EffectsControls {
	int EQ = 0;
	int BASS = 1;
	int VIRT = 2;
	int EXTRA = 3;

	/** Whether this kind of effect exists here at all. */
	boolean has(int kind);

	boolean isOn(int kind);

	void setOn(int kind, boolean on);

	@StringRes
	int label(int kind);

	/** The equalizer's bands as heights from 0 to 1 (null: none to show). */
	@Nullable
	float[] bands();

	/** The name of the equalizer preset in use, null for a manual curve. */
	@Nullable
	String preset(Context ctx);

	/** Steps to the next equalizer preset. */
	void nextPreset();

	/** The platform effects of {@code fx}, or null when the player has none. */
	@Nullable
	static EffectsControls of(@Nullable AudioEffects fx) {
		return (fx == null) ? null : new Platform(fx);
	}

	/** Controls for the audio effects of Android itself. */
	final class Platform implements EffectsControls {
		private final AudioEffects fx;

		Platform(AudioEffects fx) {
			this.fx = fx;
		}

		@Nullable
		private AudioEffect effect(int kind) {
			return switch (kind) {
				case EQ -> fx.getEqualizer();
				case BASS -> fx.getBassBoost();
				case VIRT -> fx.getVirtualizer();
				default -> fx.getLoudnessEnhancer();
			};
		}

		@Override
		public boolean has(int kind) {
			return effect(kind) != null;
		}

		@Override
		public boolean isOn(int kind) {
			try {
				AudioEffect e = effect(kind);
				return (e != null) && e.getEnabled();
			} catch (RuntimeException ex) {
				return false;
			}
		}

		@Override
		public void setOn(int kind, boolean on) {
			try {
				AudioEffect e = effect(kind);
				if (e != null) e.setEnabled(on);
			} catch (RuntimeException ex) {
				me.aap.utils.log.Log.w(ex, "Could not switch an audio effect");
			}
		}

		@Override
		public int label(int kind) {
			return switch (kind) {
				case EQ -> R.string.equalier;
				case BASS -> R.string.bass_boost;
				case VIRT -> R.string.virtualizer;
				default -> R.string.vol_boost;
			};
		}

		@Nullable
		@Override
		public float[] bands() {
			try {
				Equalizer eq = fx.getEqualizer();
				if (eq == null) return null;
				short n = eq.getNumberOfBands();
				short[] range = eq.getBandLevelRange();
				float span = Math.max(1, range[1] - range[0]);
				float[] lv = new float[n];
				for (short i = 0; i < n; i++) lv[i] = (eq.getBandLevel(i) - range[0]) / span;
				return lv;
			} catch (RuntimeException ex) {
				return null;
			}
		}

		@Nullable
		@Override
		public String preset(Context ctx) {
			try {
				Equalizer eq = fx.getEqualizer();
				if (eq == null) return null;
				short cur = eq.getCurrentPreset();
				return (cur >= 0) ? eq.getPresetName(cur) : null;
			} catch (RuntimeException ex) {
				return null;
			}
		}

		@Override
		public void nextPreset() {
			try {
				Equalizer eq = fx.getEqualizer();
				if (eq == null) return;
				short n = eq.getNumberOfPresets();
				if (n <= 0) return;
				short cur = eq.getCurrentPreset();
				eq.usePreset((short) ((cur + 1) % n));
			} catch (RuntimeException ex) {
				me.aap.utils.log.Log.w(ex, "Could not change the equalizer preset");
			}
		}
	}
}
