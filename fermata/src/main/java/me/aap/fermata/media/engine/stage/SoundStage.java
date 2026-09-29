package me.aap.fermata.media.engine.stage;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import me.aap.fermata.FermataApplication;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * The app-wide "sound stage" settings (stereo width, differential surround, 3D position), kept in
 * the app's preferences. The one source of truth for every player: the ExoPlayer audio processor
 * reads {@link #getParams()} for each block of audio, and the YouTube page gets them pushed as
 * configuration whenever a {@link Listener} is told they changed.
 */
public final class SoundStage {
	private static final Pref<BooleanSupplier> WIDTH_ON = Pref.b("SS_WIDTH_ON", false);
	private static final Pref<IntSupplier> WIDTH = Pref.i("SS_WIDTH", StageParams.WIDTH_DEFAULT);
	private static final Pref<BooleanSupplier> DIFF_ON = Pref.b("SS_DIFF_ON", false);
	private static final Pref<IntSupplier> DIFF_STRENGTH =
			Pref.i("SS_DIFF_STRENGTH", StageParams.DIFF_STRENGTH_DEFAULT);
	private static final Pref<IntSupplier> DIFF_DELAY =
			Pref.i("SS_DIFF_DELAY", StageParams.DIFF_DELAY_DEFAULT);
	private static final Pref<BooleanSupplier> POS_ON = Pref.b("SS_POS_ON", false);
	private static final Pref<IntSupplier> POS_X = Pref.i("SS_POS_X", 0);
	private static final Pref<IntSupplier> POS_Y = Pref.i("SS_POS_Y", 100);
	private static final Pref<IntSupplier> SPREAD = Pref.i("SS_SPREAD", StageParams.SPREAD_DEFAULT);
	private static final Pref<IntSupplier> ORBIT = Pref.i("SS_ORBIT", 0);

	private static SoundStage instance;

	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private volatile StageParams params;

	public interface Listener {
		void onSoundStageChanged(StageParams params);
	}

	private SoundStage() {
		params = load();
	}

	@NonNull
	public static synchronized SoundStage get() {
		if (instance == null) instance = new SoundStage();
		return instance;
	}

	/** The current settings; cheap and safe to call from any thread, e.g. per block of audio. */
	@NonNull
	public StageParams getParams() {
		return params;
	}

	/** Changes the settings: {@code change} maps the current ones to the new ones. */
	public synchronized void update(@NonNull UnaryOperator<StageParams> change) {
		StageParams old = params;
		StageParams p = change.apply(old);
		if (p.equals(old)) return;
		params = p;
		save(p);
		for (Listener l : listeners) l.onSoundStageChanged(p);
	}

	/** Back to no sound stage effects. Keeps the values (so switching a feature on again resumes). */
	public void disableAll() {
		update(p -> p.withWidth(false, p.width).withDiff(false, p.diffStrength, p.diffDelayMs)
				.withPosition(false, p.posX, p.posY, p.spread, p.orbit));
	}

	public void addListener(Listener l) {
		listeners.addIfAbsent(l);
	}

	public void removeListener(Listener l) {
		listeners.remove(l);
	}

	private static PreferenceStore store() {
		return FermataApplication.get().getPreferenceStore();
	}

	private static StageParams load() {
		PreferenceStore s = store();
		return new StageParams(s.getBooleanPref(WIDTH_ON), s.getIntPref(WIDTH),
				s.getBooleanPref(DIFF_ON), s.getIntPref(DIFF_STRENGTH), s.getIntPref(DIFF_DELAY),
				s.getBooleanPref(POS_ON), s.getIntPref(POS_X), s.getIntPref(POS_Y),
				s.getIntPref(SPREAD), s.getIntPref(ORBIT));
	}

	private static void save(StageParams p) {
		PreferenceStore s = store();
		try (PreferenceStore.Edit e = s.editPreferenceStore()) {
			e.setBooleanPref(WIDTH_ON, p.widthOn);
			e.setIntPref(WIDTH, p.width);
			e.setBooleanPref(DIFF_ON, p.diffOn);
			e.setIntPref(DIFF_STRENGTH, p.diffStrength);
			e.setIntPref(DIFF_DELAY, p.diffDelayMs);
			e.setBooleanPref(POS_ON, p.posOn);
			e.setIntPref(POS_X, p.posX);
			e.setIntPref(POS_Y, p.posY);
			e.setIntPref(SPREAD, p.spread);
			e.setIntPref(ORBIT, p.orbit);
		}
	}
}
