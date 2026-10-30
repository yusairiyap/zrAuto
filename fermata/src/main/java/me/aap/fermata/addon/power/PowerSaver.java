package me.aap.fermata.addon.power;

import android.content.Context;
import android.os.PowerManager;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.data.DataSessionLimit;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.TopToast;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.function.BooleanSupplier;

/**
 * Power saver: a mode that trades a little looks for battery. What it does, all of it undone when
 * it ends:
 * <ul>
 * <li><b>The phone's screen</b> is the biggest consumer: while it is on, the app's window is held
 * at a brightness cap (a slider in Settings), and while Android Auto is connected, where the phone
 * screen is not looked at, at its very lowest.</li>
 * <li><b>The refresh rate</b> of the phone's screen is held at 60 Hz instead of 90 or 120.</li>
 * <li><b>Downloads</b> of YouTube videos are paused (they run the radio and the CPU for minutes) and
 * carry on when it ends.</li>
 * <li>The tracker itself <b>reads half as often</b> in the background.</li>
 * </ul>
 * It starts when asked (the Power tab, a floating button, a key, All in One), automatically below a
 * battery level while unplugged, when Android Auto connects, or when Android's own Battery Saver is
 * on, as chosen in Settings; turning it off while one of those holds keeps it off until that
 * condition has passed.
 */
public final class PowerSaver {
	/** Turned on by hand. */
	public static final Pref<BooleanSupplier> MANUAL = Pref.b("POWER_SAVER_ON", false);
	/** Starts by itself at this battery level or below while unplugged; 0 is never. */
	public static final Pref<IntSupplier> AUTO_LEVEL = Pref.i("POWER_SAVER_AUTO_LEVEL", 20);
	/** Starts by itself while the phone is connected to Android Auto. */
	public static final Pref<BooleanSupplier> ON_CAR = Pref.b("POWER_SAVER_ON_CAR", false);
	/** Starts by itself while Android's own Battery Saver is on. */
	public static final Pref<BooleanSupplier> FOLLOW_SYSTEM = Pref.b("POWER_SAVER_FOLLOW_SYSTEM", true);
	/** Holds the phone screen's brightness down; off leaves the brightness alone (the rest still applies). */
	public static final Pref<BooleanSupplier> DIM_SCREEN = Pref.b("POWER_SAVER_DIM", true);
	/** The phone screen's brightness cap while it is on, in percent. */
	public static final Pref<IntSupplier> BRIGHTNESS = Pref.i("POWER_SAVER_BRIGHTNESS", 30);
	/** The phone screen at its lowest while Android Auto is connected. */
	public static final Pref<BooleanSupplier> CAR_SCREEN = Pref.b("POWER_SAVER_CAR_SCREEN", true);
	/** 60 Hz at most on the phone's screen. */
	public static final Pref<BooleanSupplier> CAP_REFRESH = Pref.b("POWER_SAVER_REFRESH", true);
	public static final Pref<BooleanSupplier> PAUSE_DOWNLOADS = Pref.b("POWER_SAVER_DOWNLOADS", true);
	/** The cap is never lower than this, or a dark phone could not be read at all. */
	public static final int MIN_BRIGHTNESS = 5;
	public static final int MAX_BRIGHTNESS = 80;
	@Nullable
	private static PowerSaver instance;

	private final Set<MainActivityDelegate> screens =
			java.util.Collections.newSetFromMap(new WeakHashMap<>());
	private final List<Runnable> listeners = new ArrayList<>(2);
	private boolean active;
	// The user turned it off while an automatic reason held: off until that has passed.
	private boolean suppressed;
	private int level = -1;
	private boolean pluggedIn;
	private int reason = R.string.power_saver_reason_manual;
	private boolean downloadsPausedByUs;

	private PowerSaver() {
	}

	@NonNull
	public static PowerSaver get() {
		PowerSaver s = instance;
		if (s == null) instance = s = new PowerSaver();
		return s;
	}

	private static PreferenceStore prefs() {
		return PowerUsageTracker.prefs();
	}

	public boolean isActive() {
		return active;
	}

	/** Why it is on, a string resource. */
	public int getReason() {
		return reason;
	}

	public void addListener(Runnable l) {
		if (!listeners.contains(l)) listeners.add(l);
	}

	public void removeListener(Runnable l) {
		listeners.remove(l);
	}

	/** The user turns it on or off. */
	public void setActive(boolean on) {
		PreferenceStore ps = prefs();
		if (on) {
			suppressed = false;
			ps.applyBooleanPref(MANUAL, true);
		} else {
			suppressed = autoReason() != 0;
			ps.applyBooleanPref(MANUAL, false);
		}
		evaluate(true);
	}

	/** Flips it, with a card at the top saying so. */
	public void toggle() {
		setActive(!active);
	}

	/** A screen of the app was created (it keeps the mode's brightness and refresh rate while it lives). */
	public void attach(MainActivityDelegate a) {
		screens.add(a);
		evaluate(false);
		apply(a);
	}

	public void detach(MainActivityDelegate a) {
		screens.remove(a);
	}

	/** The battery level and whether it is plugged in: from the tracker, every few seconds. */
	void onBattery(int levelPct, boolean plugged) {
		level = levelPct;
		pluggedIn = plugged;
		evaluate(false);
	}

	/** The string resource of the automatic reason that holds right now, or 0. */
	private int autoReason() {
		PreferenceStore ps = prefs();
		int at = ps.getIntPref(AUTO_LEVEL);
		if ((at > 0) && (level >= 0) && (level <= at) && !pluggedIn) return R.string.power_saver_reason_low;
		if (ps.getBooleanPref(ON_CAR) && DataSessionLimit.isCarConnected()) return R.string.power_saver_reason_car;
		if (ps.getBooleanPref(FOLLOW_SYSTEM) && isSystemSaverOn()) return R.string.power_saver_reason_system;
		return 0;
	}

	private static boolean isSystemSaverOn() {
		try {
			PowerManager pm = (PowerManager) FermataApplication.get().getSystemService(Context.POWER_SERVICE);
			return (pm != null) && pm.isPowerSaveMode();
		} catch (Exception ex) {
			return false;
		}
	}

	/** Works out whether it should be on, and applies a change. */
	public void evaluate(boolean announce) {
		int auto = autoReason();
		if (auto == 0) suppressed = false;
		boolean manual = prefs().getBooleanPref(MANUAL);
		boolean want = manual || ((auto != 0) && !suppressed);
		int why = manual ? R.string.power_saver_reason_manual : auto;
		boolean changed = want != active;
		if (!changed && (why == reason || !want)) {
			// Nothing new, but Android Auto may have connected or left: the cap on the window follows.
			if (active) applyScreens();
			if (announce) notifyListeners();
			return;
		}
		active = want;
		reason = why;
		DiagnosticLog.log(LogCat.POWER, "saver changed", "state=" + (active ? "on" : "off"), "reason=" + (active ? FermataApplication.get().getString(why) : "-"));
		applyAll();
		if (changed || announce) {
			TopToast.show(R.drawable.power_saver, active ? R.string.power_saver_on : R.string.power_saver_off);
		}
		notifyListeners();
	}

	private void notifyListeners() {
		for (Runnable l : new ArrayList<>(listeners)) l.run();
	}

	// ---------------------------------------------------------------------------------------------
	// What it does

	private void applyAll() {
		applyScreens();
		applyDownloads();
	}

	private void applyScreens() {
		for (MainActivityDelegate a : new ArrayList<>(screens)) apply(a);
	}

	/** The refresh rate the app's window asks for while the mode is on or off (0: the system's choice). */
	public float preferredRefreshRate() {
		return (active && prefs().getBooleanPref(CAP_REFRESH)) ? 60f : 0f;
	}

	/** The brightness the app's window is held to, 0 to 1; -1 when the mode leaves it alone. */
	public float brightnessCap() {
		if (!active) return WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
		PreferenceStore ps = prefs();
		if (ps.getBooleanPref(CAR_SCREEN) && DataSessionLimit.isCarConnected()) return 0.01f;
		if (!ps.getBooleanPref(DIM_SCREEN)) return WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
		int pct = Math.max(MIN_BRIGHTNESS, Math.min(MAX_BRIGHTNESS, ps.getIntPref(BRIGHTNESS)));
		return pct / 100f;
	}

	/** Puts the mode on (or off) the window of {@code a}: the phone's screen, never the car's. */
	public void apply(MainActivityDelegate a) {
		try {
			if (a.isCarActivity()) return;
			Window w = a.getWindow();
			if (w == null) return;
			WindowManager.LayoutParams lp = w.getAttributes();
			float brightness = brightnessCap();
			float rate = preferredRefreshRate();
			if ((lp.screenBrightness == brightness) && (lp.preferredRefreshRate == rate)) return;
			lp.screenBrightness = brightness;
			lp.preferredRefreshRate = rate;
			w.setAttributes(lp);
		} catch (Exception ex) {
			DiagnosticLog.w(LogCat.POWER, "saver could not change the window", ex);
		}
	}

	private void applyDownloads() {
		try {
			YtDownloads d = YtDownloads.get();
			if (active && prefs().getBooleanPref(PAUSE_DOWNLOADS)) {
				if (d.isBusy()) {
					d.pauseAll();
					downloadsPausedByUs = true;
				}
			} else if (downloadsPausedByUs) {
				downloadsPausedByUs = false;
				d.resumeAll();
			}
		} catch (Exception ex) {
			DiagnosticLog.w(LogCat.POWER, "saver could not pause the downloads", ex);
		}
	}

	/** The addon was switched off: whatever the mode changed is put back. */
	public void shutdown() {
		suppressed = false;
		if (!active) return;
		active = false;
		applyAll();
		notifyListeners();
	}

	/** The settings changed while it is on: the window follows. */
	public void settingsChanged() {
		if (active) applyAll();
	}
}
