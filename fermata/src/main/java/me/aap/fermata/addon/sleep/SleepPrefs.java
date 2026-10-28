package me.aap.fermata.addon.sleep;

import me.aap.fermata.FermataApplication;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/** The Sleep addon's settings (Settings &gt; Addons &gt; Sleep, {@code SleepSettingsFragment}). */
public final class SleepPrefs {
	/** How the night is recorded. */
	public static final int MODE_TIMES = 0;
	public static final int MODE_MOVEMENT = 1;
	public static final int MODE_MOVEMENT_SOUND = 2;
	/** A tablet on a stand: the room's sound stands in for movement, since nothing moves with the sleeper. */
	public static final int MODE_STAND = 3;

	/** How long the user wants to sleep, in minutes. */
	public static final Pref<IntSupplier> GOAL_MIN = Pref.i("SLEEP_GOAL_MIN", 8 * 60);
	/** The usual bedtime and wake time, in minutes after midnight. */
	public static final Pref<IntSupplier> BED_MIN = Pref.i("SLEEP_BED_MIN", 23 * 60);
	public static final Pref<IntSupplier> WAKE_MIN = Pref.i("SLEEP_WAKE_MIN", 7 * 60);
	/** The weights of the score's four parts (relative to each other). */
	public static final Pref<IntSupplier> W_DURATION = Pref.i("SLEEP_W_DURATION", 40);
	public static final Pref<IntSupplier> W_CONSISTENCY = Pref.i("SLEEP_W_CONSISTENCY", 25);
	public static final Pref<IntSupplier> W_LATENCY = Pref.i("SLEEP_W_LATENCY", 15);
	public static final Pref<IntSupplier> W_INTERRUPTIONS = Pref.i("SLEEP_W_INTERRUPTIONS", 20);
	public static final Pref<IntSupplier> MODE = Pref.i("SLEEP_MODE", MODE_MOVEMENT);
	/** 0 = low, 1 = normal, 2 = high: how easily movement counts as being awake. */
	/** The screen being on counts as being awake (glancing at the phone at night). */
	public static final Pref<BooleanSupplier> SCREEN_AWAKE = Pref.b("SLEEP_SCREEN_AWAKE", true);
	public static final Pref<IntSupplier> SENSITIVITY = Pref.i("SLEEP_SENSITIVITY", 1);
	/** A session ends by itself after this many hours. */
	public static final Pref<IntSupplier> AUTO_STOP_H = Pref.i("SLEEP_AUTO_STOP_H", 12);
	/** Starting a session also starts the last ambience mix, which fades out after {@link #AMBIENCE_MIN} minutes. */
	public static final Pref<BooleanSupplier> AMBIENCE_START = Pref.b("SLEEP_AMBIENCE_START", true);
	public static final Pref<IntSupplier> AMBIENCE_MIN = Pref.i("SLEEP_AMBIENCE_MIN", 30);
	/** Stopping the session also stops the ambience. */
	public static final Pref<BooleanSupplier> AMBIENCE_STOP = Pref.b("SLEEP_AMBIENCE_STOP", true);
	public static final Pref<BooleanSupplier> ANIMATE = Pref.b("SLEEP_ANIMATE", true);
	/** A reminder before the usual bedtime: on, and how many minutes before. */
	public static final Pref<BooleanSupplier> REMINDER = Pref.b("SLEEP_REMINDER", false);
	public static final Pref<IntSupplier> REMINDER_LEAD = Pref.i("SLEEP_REMINDER_LEAD", 30);
	/** The session starts by itself at the usual bedtime (once the screen is off) and ends in the morning, no button needed. */
	public static final Pref<BooleanSupplier> AUTO = Pref.b("SLEEP_AUTO", true);
	/** The bedtime (a time in ms) the automatic start last dealt with, so one night starts once. */
	public static final Pref<LongSupplier> LAST_AUTO = Pref.l("SLEEP_LAST_AUTO", 0L);
	/** The first-visit guide has been shown. */
	public static final Pref<BooleanSupplier> GUIDE_DONE = Pref.b("SLEEP_GUIDE_DONE", false);
	/** The start of the session that is running (0 = none): how a killed app finds out it had one. */
	public static final Pref<LongSupplier> ACTIVE_START = Pref.l("SLEEP_ACTIVE_START", 0L);

	private SleepPrefs() {
	}

	public static PreferenceStore store() {
		return FermataApplication.get().getPreferenceStore();
	}

	public static int goalMin() {
		return Math.max(60, store().getIntPref(GOAL_MIN));
	}

	public static boolean animate() {
		return store().getBooleanPref(ANIMATE);
	}
}
