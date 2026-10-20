package me.aap.fermata.addon.ambience;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.ui.view.TopToast;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Plays the ambience: every switched-on {@link AmbienceEffect} is made by {@link AmbienceSynth} on
 * one audio thread, mixed, soft-clipped and streamed through an {@link AudioTrack} of its own. It
 * takes no audio focus, so it simply mixes under whatever else plays (local files, the Music tab,
 * the YouTube page), and the thread only lives while some sound is audible.
 * <p>
 * Nothing starts or stops abruptly. Each effect has a gain that is moved towards 1 or 0 a block at a
 * time (smoothstep-shaped, over the fade time of Settings), so switching one on fades it in, off
 * fades it out, and the thread winds down only after the last fade has finished. Intensity and the
 * master volume are smoothed the same way, so dragging a slider never zippers.
 * <p>
 * Which effects are on is not saved across app starts (the sound must never surprise anyone); how
 * strong each is, the volume and the length are. The length is a timer: when it runs out everything
 * fades away. Calls from the UI are on the main thread; the audio thread only reads volatile flags.
 */
public final class AmbienceEngine {
	public static final Pref<IntSupplier> MASTER = Pref.i("AMBIENCE_MASTER", 70);
	/** Minutes until everything fades away; 0 = no limit. */
	public static final Pref<IntSupplier> LENGTH = Pref.i("AMBIENCE_LENGTH_MIN", 0);
	/** Seconds a sound takes to fade in or out. */
	public static final Pref<IntSupplier> FADE = Pref.i("AMBIENCE_FADE_SEC", 3);
	public static final Pref<BooleanSupplier> FOLLOW = Pref.b("AMBIENCE_FOLLOW", false);
	public static final Pref<BooleanSupplier> NOISY = Pref.b("AMBIENCE_NOISY", true);
	public static final Pref<BooleanSupplier> ANIM_MUSIC = Pref.b("AMBIENCE_ANIM_MUSIC", true);
	public static final Pref<BooleanSupplier> ANIM_TAB = Pref.b("AMBIENCE_ANIM_TAB", true);
	/** The mix that was on last, as effect keys: what the on/off action brings back. */
	public static final Pref<Supplier<String>> LAST_MIX = Pref.s("AMBIENCE_LAST_MIX", "rain");
	/** The lengths the tab offers, in minutes (0 = no limit). */
	public static final int[] LENGTHS = {0, 5, 10, 15, 30, 60, 120, 240, 480};
	/** How long the synthesized voice and the recording take to fade into each other. */
	private static final float SOURCE_FADE_SEC = 3f;

	private static final int SR = AmbienceSynth.SR;
	private static final int BLOCK = 1024;
	private static volatile AmbienceEngine instance;

	public interface FlashListener {
		/** Lightning struck; {@code strength} is 0 to 1 (the nearer, the stronger). Main thread. */
		void onFlash(float strength);
	}

	private static final class Layer {
		final AmbienceEffect fx;
		final AmbienceSynth.Voice voice;
		volatile boolean wanted;
		/** 0 to 1: how strong, from the effect's slider. */
		volatile float levelTarget;
		/** 0 to 1: the sound's own character slider. */
		volatile float optTarget;
		/** The recorded loop's own tone slider, 0 to 1. */
		volatile float toneTarget;
		/** Whether the recording is wanted (it plays once it is ready). */
		volatile boolean recorded;
		final AmbienceSample sample;
		/** 0 plays the synthesized voice, 1 the recording; moves between them over a moment. */
		float mix;
		float tone;
		/** The fade, 0 to 1 (before its curve). Written by the audio thread. */
		volatile float gain;
		/** What a scene should show: the fade (curved) times the strength. */
		volatile float shown;
		float level;
		float opt;

		Layer(AmbienceEffect fx, AmbienceSynth.Voice voice, AmbienceSample sample) {
			this.fx = fx;
			this.voice = voice;
			this.sample = sample;
		}
	}

	private final Handler main = new Handler(Looper.getMainLooper());
	private final Object lock = new Object();
	private final Layer[] layers;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private final List<FlashListener> flashListeners = new CopyOnWriteArrayList<>();
	private boolean running;
	private volatile boolean held;
	private long endAt;
	/** The timer's time left while the ambience is held back (it waits too), or -1. */
	private long pausedLeft = -1;
	private boolean noisyRegistered;
	private final Runnable timerEnd = this::onTimerEnd;
	private final BroadcastReceiver noisy = new BroadcastReceiver() {
		@Override
		public void onReceive(Context ctx, Intent intent) {
			if (prefs().getBooleanPref(NOISY) && anyOn()) {
				DiagnosticLog.log("AMBIENCE", "output changed, fading out");
				stopAll();
			}
		}
	};

	private AmbienceEngine() {
		AmbienceEffect[] all = AmbienceEffect.values();
		layers = new Layer[all.length];
		for (int i = 0; i < all.length; i++) {
			layers[i] = new Layer(all[i], AmbienceSynth.create(all[i], this::postFlash),
					AmbienceSamples.get().voice(all[i]));
			layers[i].levelTarget = intensityOf(all[i]) / 100f;
			layers[i].level = layers[i].levelTarget;
			layers[i].optTarget = optionOf(all[i]) / 100f;
			layers[i].opt = layers[i].optTarget;
			layers[i].toneTarget = toneOf(all[i]) / 100f;
			layers[i].tone = layers[i].toneTarget;
			layers[i].recorded = all[i].realOnly || prefs().getBooleanPref(all[i].recorded);
		}
	}

	public static AmbienceEngine get() {
		AmbienceEngine e = instance;
		if (e == null) {
			synchronized (AmbienceEngine.class) {
				e = instance;
				if (e == null) instance = e = new AmbienceEngine();
			}
		}
		return e;
	}

	public static PreferenceStore prefs() {
		return FermataApplication.get().getPreferenceStore();
	}

	// ---------------------------------------------------------------------------------------------
	// State
	// ---------------------------------------------------------------------------------------------

	public boolean isOn(AmbienceEffect e) {
		return layers[e.ordinal()].wanted;
	}

	public boolean anyOn() {
		for (Layer l : layers) if (l.wanted) return true;
		return false;
	}

	/** The effect is on and not held back: its sound is (or is about to be) heard. */
	public boolean isPlaying(AmbienceEffect e) {
		return layers[e.ordinal()].wanted && !held;
	}

	/** Something is on and not held back. */
	public boolean isAnyPlaying() {
		return !held && anyOn();
	}

	/** Effects are on but waiting for the playback to resume (see {@link #setHeld}). */
	public boolean isHeld() {
		return held && anyOn();
	}

	/** What a scene shows for the effect now, 0 to 1: moves with the fades and the intensity. */
	public float getVisual(AmbienceEffect e) {
		return layers[e.ordinal()].shown;
	}

	/** How far the effect's fade has come, 0 (silent) to 1 (full), shaped like the sound's own. */
	public float getFade(AmbienceEffect e) {
		return visualCurve(layers[e.ordinal()].gain);
	}

	/** The picture leads the sound: it is fully there at half the fade, so a switch looks instant. */
	private static float visualCurve(float g) {
		return curve(Math.min(1f, g * 2f));
	}

	/** The sound's character slider, 0 to 100 (50 is how it was designed). */
	public int getOption(AmbienceEffect e) {
		return optionOf(e);
	}

	private static int optionOf(AmbienceEffect e) {
		return Math.max(0, Math.min(100, prefs().getIntPref(e.option)));
	}

	/** The recorded loop's tone slider, 0 to 100 (50 leaves the recording as it is). */
	public int getTone(AmbienceEffect e) {
		return toneOf(e);
	}

	private static int toneOf(AmbienceEffect e) {
		return Math.max(0, Math.min(100, prefs().getIntPref(e.tone)));
	}

	/** Whether the recorded loop is wanted for the effect (it may still be on its way). */
	public boolean isRecorded(AmbienceEffect e) {
		return layers[e.ordinal()].recorded;
	}

	/** Picks the recording (downloading it first when needed) or the synthesized voice. */
	public void setRecorded(AmbienceEffect e, boolean on) {
		if (e.realOnly) return;
		prefs().applyBooleanPref(e.recorded, on);
		layers[e.ordinal()].recorded = on;
		if (on) {
			AmbienceSamples.get().ensure(e);
		} else {
			// The recording stays in memory until the slow crossfade to the synthesized voice is over.
			Layer ly = layers[e.ordinal()];
			main.postDelayed(() -> {
				if (!ly.recorded) AmbienceSamples.get().release(e);
			}, (long) ((SOURCE_FADE_SEC + 2f) * 1000));
		}
		notifyChanged();
	}

	/** Whether the effect can be played at all: sounds that only exist as a recording need that recording. */
	public boolean isAvailable(AmbienceEffect e) {
		return !e.realOnly || AmbienceSamples.get().has(e);
	}

	/** Whether any sound has both a synthesized voice and a recording to choose between. */
	public boolean hasSourceChoice() {
		for (AmbienceEffect e : AmbienceEffect.values()) {
			if (!e.realOnly && AmbienceSamples.get().has(e)) return true;
		}
		return false;
	}

	/** 0 when every sound with a choice is synthesized, 1 when every one is real, 2 when mixed. */
	public int getSourceMode() {
		boolean synth = false, real = false;
		for (AmbienceEffect e : AmbienceEffect.values()) {
			if (e.realOnly || !AmbienceSamples.get().has(e)) continue;
			if (layers[e.ordinal()].recorded) real = true;
			else synth = true;
		}
		return (synth && real) ? 2 : real ? 1 : 0;
	}

	/** Every sound with a choice to its recording or its synthesized voice at once (each card can be changed again). */
	public void setAllRecorded(boolean on) {
		for (AmbienceEffect e : AmbienceEffect.values()) {
			if (e.realOnly || !AmbienceSamples.get().has(e)) continue;
			prefs().applyBooleanPref(e.recorded, on);
			layers[e.ordinal()].recorded = on;
			if (on) AmbienceSamples.get().ensure(e);
			else {
				Layer ly = layers[e.ordinal()];
				main.postDelayed(() -> {
					if (!ly.recorded) AmbienceSamples.get().release(e);
				}, (long) ((SOURCE_FADE_SEC + 2f) * 1000));
			}
		}
		notifyChanged();
	}

	public int getIntensity(AmbienceEffect e) {
		return intensityOf(e);
	}

	private static int intensityOf(AmbienceEffect e) {
		return Math.max(5, Math.min(100, prefs().getIntPref(e.intensity)));
	}

	public int getMaster() {
		return Math.max(0, Math.min(100, prefs().getIntPref(MASTER)));
	}

	public int getLengthMinutes() {
		return Math.max(0, prefs().getIntPref(LENGTH));
	}

	/** Milliseconds until the timer fades everything out, or -1 when there is no limit. */
	public long getRemainingMs() {
		if (pausedLeft >= 0) return pausedLeft;
		return (endAt == 0) ? -1 : Math.max(0, endAt - SystemClock.elapsedRealtime());
	}

	public List<AmbienceEffect> getActive() {
		List<AmbienceEffect> l = new ArrayList<>();
		for (Layer ly : layers) if (ly.wanted) l.add(ly.fx);
		return l;
	}

	/** The names of what is on, "Rain · Thunder", or an empty string. */
	public String describe(Context ctx) {
		StringBuilder sb = new StringBuilder();
		for (Layer ly : layers) {
			if (!ly.wanted) continue;
			if (sb.length() > 0) sb.append(" · ");
			sb.append(ctx.getString(ly.fx.name));
		}
		return sb.toString();
	}

	public void addListener(Runnable l) {
		if (!listeners.contains(l)) listeners.add(l);
	}

	public void removeListener(Runnable l) {
		listeners.remove(l);
	}

	public void addFlashListener(FlashListener l) {
		if (!flashListeners.contains(l)) flashListeners.add(l);
	}

	public void removeFlashListener(FlashListener l) {
		flashListeners.remove(l);
	}

	/** A setting that scenes depend on changed: everything listening looks again. */
	public void refresh() {
		notifyChanged();
	}

	private void notifyChanged() {
		for (Runnable l : listeners) l.run();
	}

	/** From the audio thread. */
	private void postFlash(float strength) {
		main.post(() -> {
			for (FlashListener l : flashListeners) l.onFlash(strength);
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Control (main thread)
	// ---------------------------------------------------------------------------------------------

	public void setOn(AmbienceEffect e, boolean on) {
		if (on && !isAvailable(e)) return;
		Layer ly = layers[e.ordinal()];
		if (ly.wanted == on) return;
		boolean wasAny = anyOn();
		ly.wanted = on;
		if (on) {
			// Switching a sound on is a clear wish to hear it, whatever the playback does.
			if (held) {
				held = false;
				resumeTimer();
			}
			ly.levelTarget = intensityOf(e) / 100f;
			// A recording that is wanted but not loaded yet: the synthesized voice plays meanwhile.
			if (ly.recorded) AmbienceSamples.get().ensure(e);
			kick();
		}
		boolean any = anyOn();
		if (any) {
			saveMix();
			if (!wasAny) {
				scheduleTimer();
				registerNoisy();
			}
		} else {
			cancelTimer();
			unregisterNoisy();
		}
		if (!on) scheduleSampleRelease(e);
		DiagnosticLog.log("AMBIENCE", e.key, on ? "on" : "off");
		notifyChanged();
	}

	/**
	 * A recording is decoded into memory (about 11 MB a minute): once its sound has faded out and
	 * stayed off a while, the memory goes back. Switching it on again loads it afresh, with the
	 * synthesized voice playing meanwhile.
	 */
	private void scheduleSampleRelease(AmbienceEffect e) {
		Layer ly = layers[e.ordinal()];
		if (!ly.recorded) return;
		long wait = (Math.max(1, Math.min(10, prefs().getIntPref(FADE))) + 5) * 1000L;
		main.postDelayed(() -> {
			if (!ly.wanted && ly.recorded) AmbienceSamples.get().release(e);
		}, wait);
	}

	public void toggle(AmbienceEffect e) {
		setOn(e, !isOn(e));
	}

	/** Fades everything out. */
	public void stopAll() {
		boolean any = anyOn();
		for (Layer l : layers) {
			boolean was = l.wanted;
			l.wanted = false;
			if (was) scheduleSampleRelease(l.fx);
		}
		cancelTimer();
		unregisterNoisy();
		if (any) notifyChanged();
	}

	/** Brings the mix that was on last back (the rain when there is none). */
	public void resumeLast() {
		String mix = prefs().getStringPref(LAST_MIX);
		boolean any = false;
		if ((mix != null) && !mix.isEmpty()) {
			for (String k : mix.split(",")) {
				AmbienceEffect e = AmbienceEffect.byKey(k.trim());
				if ((e != null) && isAvailable(e)) {
					setOn(e, true);
					any = true;
				}
			}
		}
		if (!any) setOn(AmbienceEffect.RAIN, true);
	}

	/** On when off, off when on: the key binding's and the tile's action. */
	public void toggleAll() {
		if (anyOn()) stopAll();
		else resumeLast();
	}

	public void setIntensity(AmbienceEffect e, int pct) {
		pct = Math.max(5, Math.min(100, pct));
		prefs().applyIntPref(e.intensity, pct);
		layers[e.ordinal()].levelTarget = pct / 100f;
	}

	public void setOption(AmbienceEffect e, int pct) {
		pct = Math.max(0, Math.min(100, pct));
		prefs().applyIntPref(e.option, pct);
		layers[e.ordinal()].optTarget = pct / 100f;
	}

	public void setTone(AmbienceEffect e, int pct) {
		pct = Math.max(0, Math.min(100, pct));
		prefs().applyIntPref(e.tone, pct);
		layers[e.ordinal()].toneTarget = pct / 100f;
	}

	/** Back to how the sound was designed: its first intensity and the middle of its character sliders. */
	public void resetEffect(AmbienceEffect e) {
		setIntensity(e, e.defLevel);
		setOption(e, 50);
		setTone(e, 50);
	}

	public void setMaster(int pct) {
		prefs().applyIntPref(MASTER, Math.max(0, Math.min(100, pct)));
	}

	public void setLengthMinutes(int minutes) {
		prefs().applyIntPref(LENGTH, Math.max(0, minutes));
		if (anyOn()) scheduleTimer();
		notifyChanged();
	}

	/**
	 * The playback paused (or went on): with "Pause with playback" the ambience follows, fading out
	 * while it waits and back in on resume. Switching a sound on clears it again.
	 */
	public void setHeld(boolean h) {
		if (held == h) return;
		held = h;
		DiagnosticLog.log("AMBIENCE", h ? "held back: the playback paused" : "released: the playback went on");
		if (h) {
			// Nothing is heard, so the timer waits too instead of running out in silence.
			if (endAt != 0) {
				pausedLeft = Math.max(0, endAt - SystemClock.elapsedRealtime());
				endAt = 0;
				main.removeCallbacks(timerEnd);
			}
		} else {
			resumeTimer();
			if (anyOn()) kick();
		}
		notifyChanged();
	}

	private void saveMix() {
		StringBuilder sb = new StringBuilder();
		for (Layer l : layers) {
			if (!l.wanted) continue;
			if (sb.length() > 0) sb.append(',');
			sb.append(l.fx.key);
		}
		if (sb.length() > 0) prefs().applyStringPref(LAST_MIX, sb.toString());
	}

	private void scheduleTimer() {
		main.removeCallbacks(timerEnd);
		pausedLeft = -1;
		int min = getLengthMinutes();
		if ((min <= 0) || !anyOn()) {
			endAt = 0;
			return;
		}
		long ms = min * 60_000L;
		if (held) {
			endAt = 0;
			pausedLeft = ms;
			return;
		}
		endAt = SystemClock.elapsedRealtime() + ms;
		main.postDelayed(timerEnd, ms);
	}

	private void cancelTimer() {
		main.removeCallbacks(timerEnd);
		endAt = 0;
		pausedLeft = -1;
	}

	/** The playback went on: the timer carries on from where it waited. */
	private void resumeTimer() {
		if (pausedLeft < 0) return;
		long ms = pausedLeft;
		pausedLeft = -1;
		endAt = SystemClock.elapsedRealtime() + ms;
		main.postDelayed(timerEnd, ms);
	}

	private void onTimerEnd() {
		endAt = 0;
		if (!anyOn()) return;
		DiagnosticLog.log("AMBIENCE", "timer ended");
		stopAll();
		TopToast.show(R.drawable.ambience, R.string.ambience_timer_done);
	}

	private void registerNoisy() {
		if (noisyRegistered) return;
		try {
			ContextCompat.registerReceiver(FermataApplication.get(), noisy,
					new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
					ContextCompat.RECEIVER_NOT_EXPORTED);
			noisyRegistered = true;
		} catch (Throwable ex) {
			DiagnosticLog.log("AMBIENCE", "noisy receiver failed", ex);
		}
	}

	private void unregisterNoisy() {
		if (!noisyRegistered) return;
		noisyRegistered = false;
		try {
			FermataApplication.get().unregisterReceiver(noisy);
		} catch (Throwable ignore) {
		}
	}

	/** Switches the addon off: everything ends at once, nothing left behind. */
	public void shutdown() {
		for (Layer l : layers) l.wanted = false;
		held = false;
		cancelTimer();
		unregisterNoisy();
		main.removeCallbacksAndMessages(null);
		abandonFocus();
	}

	// ---------------------------------------------------------------------------------------------
	// Audio thread
	// ---------------------------------------------------------------------------------------------

	private boolean wantsAny() {
		if (held) return false;
		for (Layer l : layers) if (l.wanted) return true;
		return false;
	}

	/** Starts the audio thread unless it runs; it ends itself once everything has faded out. */
	private void kick() {
		synchronized (lock) {
			if (running) return;
			running = true;
		}
		AmbienceService.start(FermataApplication.get());
		requestFocus();
		Thread t = new Thread(this::run, "ambience");
		t.start();
	}

	@androidx.annotation.Nullable
	private AudioFocusRequest focus;

	/**
	 * Audio focus, for the car: with Android Auto connected, a stream from an app that holds no focus at all is
	 * not played through the car (the card and notification said "playing" and nothing was heard). It is only
	 * asked for when the app's own player is not playing, since that one holds the focus itself, and as the
	 * "may duck" kind, so another app's music goes quieter underneath instead of stopping; any later change of
	 * focus is ignored, the ambience simply mixes on.
	 */
	private void requestFocus() {
		if ((focus != null) || AmbienceAddon.isMusicPlaying()) return;
		try {
			AudioManager am = FermataApplication.get().getSystemService(AudioManager.class);
			if (am == null) return;
			AudioFocusRequest r = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
					.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
							.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
					.setWillPauseWhenDucked(false)
					.setOnAudioFocusChangeListener(change -> {
					}).build();
			int res = am.requestAudioFocus(r);
			DiagnosticLog.log("AMBIENCE", "audio focus", "result=" + res);
			if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = r;
		} catch (RuntimeException ex) {
			DiagnosticLog.log("AMBIENCE", "audio focus failed", ex);
		}
	}

	private void abandonFocus() {
		AudioFocusRequest r = focus;
		focus = null;
		if (r == null) return;
		try {
			AudioManager am = FermataApplication.get().getSystemService(AudioManager.class);
			if (am != null) am.abandonAudioFocusRequest(r);
		} catch (RuntimeException ignore) {
		}
	}

	private static float curve(float g) {
		return g * g * (3f - 2f * g);
	}

	private static short toPcm(float x) {
		if (x > 3f) x = 3f;
		else if (x < -3f) x = -3f;
		// A tanh-like soft clip: unity gain for small signals, never past full scale.
		float y = x * (27f + x * x) / (27f + 9f * x * x);
		return (short) (y * 30000f);
	}

	private void run() {
		Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
		AudioTrack track = null;
		// Set once the loop has given up the "running" flag itself (a new thread may own it by now).
		boolean clean = false;
		try {
			int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO,
					AudioFormat.ENCODING_PCM_16BIT);
			int bytes = Math.max(min, BLOCK * 4 * 3);
			track = new AudioTrack.Builder()
					.setAudioAttributes(new AudioAttributes.Builder()
							.setUsage(AudioAttributes.USAGE_MEDIA)
							.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
					.setAudioFormat(new AudioFormat.Builder().setSampleRate(SR)
							.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
							.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
					.setBufferSizeInBytes(bytes)
					.setTransferMode(AudioTrack.MODE_STREAM)
					.build();
			track.play();
			DiagnosticLog.log("AMBIENCE", "track playing", "state=" + track.getPlayState(),
					"device=" + ((track.getRoutedDevice() == null) ? "none" : track.getRoutedDevice().getType()));

			float[] ml = new float[BLOCK], mr = new float[BLOCK];
			float[] vl = new float[BLOCK], vr = new float[BLOCK];
			float[] wl = new float[BLOCK], wr = new float[BLOCK];
			short[] out = new short[BLOCK * 2];
			float master = masterTarget();
			int silent = 0;
			while (true) {
				int fadeSec = Math.max(1, Math.min(10, prefs().getIntPref(FADE)));
				float step = BLOCK / (float) (SR * fadeSec);
				boolean audible = false;
				java.util.Arrays.fill(ml, 0f);
				java.util.Arrays.fill(mr, 0f);
				boolean hold = held;
				for (Layer ly : layers) {
					boolean want = ly.wanted && !hold;
					float g0 = ly.gain;
					// A recording still loading while the sound starts: wait in silence instead of playing the
					// synthesized voice first and crossfading, and start straight on the recording.
					if (want && (g0 == 0f) && ly.recorded && !ly.sample.isReady()
							&& (AmbienceSamples.get().getState(ly.fx) == AmbienceSamples.State.LOADING)) {
						ly.mix = 1f;
						ly.shown = 0f;
						audible = true;
						continue;
					}
					float g1 = want ? Math.min(1f, g0 + step) : Math.max(0f, g0 - step);
					ly.gain = g1;
					ly.level += (ly.levelTarget - ly.level) * 0.2f;
					ly.opt += (ly.optTarget - ly.opt) * 0.2f;
					ly.tone += (ly.toneTarget - ly.tone) * 0.2f;
					if ((g0 == 0f) && (g1 == 0f)) {
						ly.shown = 0f;
						continue;
					}
					// The synthesized voice, the recording, or a quick crossfade between them.
					float m0 = ly.mix;
					float mixT = (ly.recorded && ly.sample.isReady()) ? 1f : 0f;
					float mixStep = BLOCK / (SR * SOURCE_FADE_SEC);
					float m1 = m0 + Math.max(-mixStep, Math.min(mixStep, mixT - m0));
					ly.mix = m1;
					float[] srcL = vl, srcR = vr;
					if ((m0 < 1f) || (m1 < 1f)) ly.voice.render(vl, vr, BLOCK, ly.level, ly.opt);
					if ((m0 > 0f) || (m1 > 0f)) {
						ly.sample.render(wl, wr, BLOCK, ly.level, ly.tone);
						if ((m0 >= 1f) && (m1 >= 1f)) {
							srcL = wl;
							srcR = wr;
						} else {
							for (int i = 0; i < BLOCK; i++) {
								// Equal power: the two voices are unrelated, so no dip in the middle.
								float m = (m0 + (m1 - m0) * i / BLOCK) * 1.5707964f;
								float gs = (float) Math.cos(m), gr = (float) Math.sin(m);
								vl[i] = vl[i] * gs + wl[i] * gr;
								vr[i] = vr[i] * gs + wr[i] * gr;
							}
						}
					}
					float a0 = curve(g0);
					float a1 = curve(g1);
					float lv = 0.3f + 0.7f * ly.level;
					for (int i = 0; i < BLOCK; i++) {
						float a = (a0 + (a1 - a0) * i / BLOCK) * lv;
						ml[i] += srcL[i] * a;
						mr[i] += srcR[i] * a;
					}
					ly.shown = visualCurve(g1) * lv;
					audible = true;
				}
				float m0 = master;
				master += (masterTarget() - master) * 0.25f;
				for (int i = 0; i < BLOCK; i++) {
					float m = m0 + (master - m0) * i / BLOCK;
					out[2 * i] = toPcm(ml[i] * m);
					out[2 * i + 1] = toPcm(mr[i] * m);
				}
				int w = track.write(out, 0, out.length);
				if (w < 0) {
					DiagnosticLog.log("AMBIENCE", "write failed", w);
					break;
				}
				if (audible) {
					silent = 0;
				} else if (++silent > 2) {
					synchronized (lock) {
						if (!wantsAny()) {
							running = false;
							clean = true;
							return;
						}
					}
				}
			}
		} catch (Throwable ex) {
			DiagnosticLog.log("AMBIENCE", "audio failed", ex);
			main.post(() -> {
				stopAll();
				TopToast.show(R.drawable.ambience, R.string.ambience_unsupported);
			});
		} finally {
			if (!clean) {
				synchronized (lock) {
					running = false;
				}
				for (Layer ly : layers) {
					ly.gain = 0f;
					ly.shown = 0f;
				}
			}
			if (track != null) {
				try {
					track.stop();
				} catch (Throwable ignore) {
				}
				track.release();
			}
			main.post(() -> {
				notifyChanged();
				boolean again;
				synchronized (lock) {
					again = running;
				}
				if (!again) abandonFocus();
				// Not when a new thread has started meanwhile: that one needs the service.
				if (!again) AmbienceService.stop(FermataApplication.get());
			});
		}
	}

	private float masterTarget() {
		float m = getMaster() / 100f;
		return m * m;
	}
}
