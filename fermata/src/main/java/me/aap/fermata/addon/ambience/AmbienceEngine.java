package me.aap.fermata.addon.ambience;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
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
	public static final int[] LENGTHS = {0, 15, 30, 60, 120, 240, 480};

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
		/** The fade, 0 to 1 (before its curve). Written by the audio thread. */
		volatile float gain;
		/** What a scene should show: the fade (curved) times the strength. */
		volatile float shown;
		float level;

		Layer(AmbienceEffect fx, AmbienceSynth.Voice voice) {
			this.fx = fx;
			this.voice = voice;
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
			layers[i] = new Layer(all[i], AmbienceSynth.create(all[i], this::postFlash));
			layers[i].levelTarget = intensityOf(all[i]) / 100f;
			layers[i].level = layers[i].levelTarget;
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
		return curve(layers[e.ordinal()].gain);
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
		Layer ly = layers[e.ordinal()];
		if (ly.wanted == on) return;
		boolean wasAny = anyOn();
		ly.wanted = on;
		if (on) {
			// Switching a sound on is a clear wish to hear it, whatever the playback does.
			held = false;
			ly.levelTarget = intensityOf(e) / 100f;
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
		DiagnosticLog.log("AMBIENCE", e.key, on ? "on" : "off");
		notifyChanged();
	}

	public void toggle(AmbienceEffect e) {
		setOn(e, !isOn(e));
	}

	/** Fades everything out. */
	public void stopAll() {
		boolean any = anyOn();
		for (Layer l : layers) l.wanted = false;
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
				if (e != null) {
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
		if (!h && anyOn()) kick();
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
		int min = getLengthMinutes();
		if ((min <= 0) || !anyOn()) {
			endAt = 0;
			return;
		}
		long ms = min * 60_000L;
		endAt = SystemClock.elapsedRealtime() + ms;
		main.postDelayed(timerEnd, ms);
	}

	private void cancelTimer() {
		main.removeCallbacks(timerEnd);
		endAt = 0;
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
		Thread t = new Thread(this::run, "ambience");
		t.start();
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

			float[] ml = new float[BLOCK], mr = new float[BLOCK];
			float[] vl = new float[BLOCK], vr = new float[BLOCK];
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
					float g1 = want ? Math.min(1f, g0 + step) : Math.max(0f, g0 - step);
					ly.gain = g1;
					ly.level += (ly.levelTarget - ly.level) * 0.2f;
					if ((g0 == 0f) && (g1 == 0f)) {
						ly.shown = 0f;
						continue;
					}
					ly.voice.render(vl, vr, BLOCK, ly.level);
					float a0 = curve(g0);
					float a1 = curve(g1);
					float lv = 0.3f + 0.7f * ly.level;
					for (int i = 0; i < BLOCK; i++) {
						float a = (a0 + (a1 - a0) * i / BLOCK) * lv;
						ml[i] += vl[i] * a;
						mr[i] += vr[i] * a;
					}
					ly.shown = a1 * lv;
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
			main.post(this::notifyChanged);
		}
	}

	private float masterTarget() {
		float m = getMaster() / 100f;
		return m * m;
	}
}
