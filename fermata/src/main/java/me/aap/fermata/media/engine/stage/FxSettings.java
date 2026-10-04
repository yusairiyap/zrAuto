package me.aap.fermata.media.engine.stage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * The YouTube equalizer's settings as the native player needs them. They live where the YouTube
 * addon keeps them (its own preference store: the very values its Effects screen edits), so a
 * downloaded video sounds the way the same video does on YouTube, and moving a slider changes
 * either at once. The addon hands its store over with {@link #attach}; until then there are no
 * effects.
 */
public final class FxSettings implements PreferenceStore.Listener {
	// The addon's own names and defaults.
	private static final Pref<BooleanSupplier> EQ_ENABLED = Pref.b("YT_EQ_ENABLED", false);
	private static final Pref<Supplier<int[]>> EQ_BANDS = Pref.ia("YT_EQ_BANDS", () -> null);
	private static final Pref<BooleanSupplier> BASS_ENABLED = Pref.b("YT_BASS_ENABLED", false);
	private static final Pref<IntSupplier> BASS_STRENGTH = Pref.i("YT_BASS_STRENGTH", 0);
	private static final Pref<BooleanSupplier> VIRT_ENABLED = Pref.b("YT_VIRT_ENABLED", false);
	private static final Pref<IntSupplier> VIRT_STRENGTH = Pref.i("YT_VIRT_STRENGTH", 0);
	private static final Pref<BooleanSupplier> REVERB_ENABLED = Pref.b("YT_REVERB_ENABLED", false);
	private static final Pref<IntSupplier> REVERB_STRENGTH = Pref.i("YT_REVERB_STRENGTH", 0);
	private static final Pref<IntSupplier> REVERB_DURATION = Pref.i("YT_REVERB_DURATION", 2500);

	private static final FxSettings instance = new FxSettings();

	@Nullable
	private PreferenceStore store;
	private volatile FxParams params = FxParams.OFF;

	private FxSettings() {
	}

	@NonNull
	public static FxSettings get() {
		return instance;
	}

	/** The current settings; cheap and safe to call from any thread, e.g. per block of audio. */
	@NonNull
	public FxParams getParams() {
		return params;
	}

	/** Reads the settings from {@code s} from now on, and follows them as they change. */
	public synchronized void attach(@NonNull PreferenceStore s) {
		if (store == s) return;
		if (store != null) store.removeBroadcastListener(this);
		store = s;
		s.addBroadcastListener(this);
		reload();
	}

	@Override
	public void onPreferenceChanged(PreferenceStore s, List<Pref<?>> prefs) {
		reload();
	}

	private synchronized void reload() {
		PreferenceStore s = store;
		if (s == null) return;
		params = new FxParams(s.getBooleanPref(EQ_ENABLED), s.getIntArrayPref(EQ_BANDS),
				s.getBooleanPref(BASS_ENABLED), s.getIntPref(BASS_STRENGTH),
				s.getBooleanPref(VIRT_ENABLED), s.getIntPref(VIRT_STRENGTH),
				s.getBooleanPref(REVERB_ENABLED), s.getIntPref(REVERB_STRENGTH),
				s.getIntPref(REVERB_DURATION));
	}
}
