package me.aap.fermata.addon.sleep;

import static android.os.Build.VERSION.SDK_INT;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * The bedtime reminder: one alarm a day, the usual bedtime less the chosen lead (Settings &gt; Sleep &gt; Reminder), that posts a
 * notification with a Start sleep button. Like the scheduled backup it is one inexact alarm, set again from each run, from the
 * device boot ({@link SleepReminderReceiver}) and whenever the settings change, so it needs no special permission and a change of
 * time just replaces it. Nothing is posted while a session already runs or when the addon is off.
 */
public final class SleepReminder {
	static final String ACTION_REMIND = "me.aap.fermata.sleep.REMIND";
	static final String ACTION_START = "me.aap.fermata.sleep.START";
	static final String ACTION_AUTO = "me.aap.fermata.sleep.AUTO";
	/** How long after the usual bedtime the automatic start keeps looking for a moment when the screen is off. */
	private static final long AUTO_WINDOW_MS = 120 * 60_000L;
	private static final long AUTO_RETRY_MS = 10 * 60_000L;
	private static final int REQ_AUTO = 0x5EEB;
	private static final String CHANNEL_ID = "sleep_reminder";
	private static final int NOTIF_ID = 0xA3B3;
	private static final int REQ_ALARM = 0x5EE9;
	private static final int REQ_START = 0x5EEA;

	private SleepReminder() {
	}

	/** The next time the reminder is due after {@code now}. */
	static long nextDue(PreferenceStore p, long now) {
		int bed = p.getIntPref(SleepPrefs.BED_MIN);
		int lead = Math.max(0, p.getIntPref(SleepPrefs.REMINDER_LEAD));
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(now);
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		c.add(Calendar.MINUTE, bed - lead);
		while (c.getTimeInMillis() <= now + 30_000L) c.add(Calendar.DAY_OF_MONTH, 1);
		return c.getTimeInMillis();
	}

	/** Sets, moves or cancels both alarms (the reminder and the automatic start) to match the settings. */
	public static void schedule(Context ctx) {
		scheduleReminder(ctx);
		scheduleAuto(ctx);
	}

	private static void scheduleReminder(Context ctx) {
		AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
		if (am == null) return;
		PendingIntent pi = alarmIntent(ctx);
		PreferenceStore p = SleepPrefs.store();
		if (!p.getBooleanPref(SleepPrefs.REMINDER) || !SleepAddon.isEnabled()) {
			am.cancel(pi);
			return;
		}
		long at = nextDue(p, System.currentTimeMillis());
		try {
			if (SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
			else am.set(AlarmManager.RTC_WAKEUP, at, pi);
			DiagnosticLog.log(LogCat.SLEEP, "reminder set", "at=" + SleepInsights.clock(ctx, at));
		} catch (Exception ex) {
			Log.e(ex, "Failed to schedule the sleep reminder");
			DiagnosticLog.w(LogCat.SLEEP, "reminder not set", ex);
		}
	}

	/** The most recent usual bedtime that is not after {@code now}, in ms. */
	static long lastBedtime(PreferenceStore p, long now) {
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(now);
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		c.add(Calendar.MINUTE, p.getIntPref(SleepPrefs.BED_MIN));
		if (c.getTimeInMillis() > now) c.add(Calendar.DAY_OF_MONTH, -1);
		return c.getTimeInMillis();
	}

	/**
	 * The automatic start: one alarm at the usual bedtime, and then every ten minutes for two hours until the screen is off (a
	 * phone or tablet still in use is not a sleeper). Once it has started a session for a bedtime, or the two hours are over, it
	 * waits for the next one.
	 */
	static void scheduleAuto(Context ctx) {
		AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
		if (am == null) return;
		PendingIntent pi = autoIntent(ctx);
		PreferenceStore p = SleepPrefs.store();
		if (!p.getBooleanPref(SleepPrefs.AUTO) || !SleepAddon.isEnabled()) {
			am.cancel(pi);
			return;
		}
		long now = System.currentTimeMillis();
		long last = lastBedtime(p, now);
		boolean waiting = (now < last + AUTO_WINDOW_MS) && (p.getLongPref(SleepPrefs.LAST_AUTO) < last);
		long at = waiting ? now + AUTO_RETRY_MS : nextBedtime(p, now);
		try {
			if (SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
			else am.set(AlarmManager.RTC_WAKEUP, at, pi);
			DiagnosticLog.d(LogCat.SLEEP, "automatic start set", "at=" + SleepInsights.clock(ctx, at), "waiting=" + waiting);
		} catch (Exception ex) {
			DiagnosticLog.w(LogCat.SLEEP, "automatic start not set", ex);
		}
	}

	/**
	 * When the automatic recording will start next: the usual bedtime, or now while its window is open and no session has
	 * run for it; -1 when automatic recording is off or the addon is.
	 */
	public static long nextAutoStart() {
		PreferenceStore p = SleepPrefs.store();
		if (!p.getBooleanPref(SleepPrefs.AUTO) || !SleepAddon.isEnabled()) return -1L;
		long now = System.currentTimeMillis();
		long last = lastBedtime(p, now);
		boolean waiting = (now < last + AUTO_WINDOW_MS) && (p.getLongPref(SleepPrefs.LAST_AUTO) < last);
		return waiting ? now : nextBedtime(p, now);
	}

	private static long nextBedtime(PreferenceStore p, long now) {
		long last = lastBedtime(p, now);
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(last);
		do {
			c.add(Calendar.DAY_OF_MONTH, 1);
		} while (c.getTimeInMillis() <= now + 30_000L);
		return c.getTimeInMillis();
	}

	/** The automatic start's alarm went off. */
	static void auto(Context ctx) {
		try {
			PreferenceStore p = SleepPrefs.store();
			if (!SleepAddon.isEnabled() || !p.getBooleanPref(SleepPrefs.AUTO)) return;
			long now = System.currentTimeMillis();
			long last = lastBedtime(p, now);
			SleepTracker t = SleepTracker.get();
			if ((now < last + AUTO_WINDOW_MS) && (p.getLongPref(SleepPrefs.LAST_AUTO) < last) && !t.isActive()) {
				android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
				if ((pm != null) && !pm.isInteractive()) {
					DiagnosticLog.log(LogCat.SLEEP, "automatic start");
					t.startAuto();
				}
			}
		} finally {
			scheduleAuto(ctx);
		}
	}

	private static PendingIntent autoIntent(Context ctx) {
		Intent i = new Intent(ctx, SleepReminderReceiver.class).setAction(ACTION_AUTO);
		return PendingIntent.getBroadcast(ctx, REQ_AUTO, i, PendingIntent.FLAG_UPDATE_CURRENT |
				PendingIntent.FLAG_IMMUTABLE);
	}

	/** The alarm went off: remind, and set tomorrow's. */
	static void remind(Context ctx) {
		try {
			if (SleepAddon.isEnabled() && !SleepTracker.get().isActive()) post(ctx);
		} finally {
			scheduleReminder(ctx);
		}
	}

	/** The notification's button: starts the session. */
	static void startFromNotification(Context ctx) {
		NotificationManager nm = ctx.getSystemService(NotificationManager.class);
		if (nm != null) nm.cancel(NOTIF_ID);
		if (SleepAddon.isEnabled()) SleepTracker.get().start();
	}

	private static void post(Context ctx) {
		NotificationManager nm = ctx.getSystemService(NotificationManager.class);
		if (nm == null) return;
		if (nm.getNotificationChannel(CHANNEL_ID) == null) {
			NotificationChannel c = new NotificationChannel(CHANNEL_ID, ctx.getString(R.string.sleep_reminder_channel),
					NotificationManager.IMPORTANCE_DEFAULT);
			c.setShowBadge(false);
			nm.createNotificationChannel(c);
		}
		PreferenceStore p = SleepPrefs.store();
		int lead = p.getIntPref(SleepPrefs.REMINDER_LEAD);
		String bed = SleepInsights.minuteOfDay(ctx, p.getIntPref(SleepPrefs.BED_MIN));
		Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
		if (open == null) open = new Intent();
		open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		Intent start = new Intent(ctx, SleepReminderReceiver.class).setAction(ACTION_START);
		int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
		Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
				.setSmallIcon(R.drawable.sleep)
				.setContentTitle(ctx.getString(R.string.sleep_reminder_title))
				.setContentText((lead > 0) ? ctx.getString(R.string.sleep_reminder_text, lead, bed) :
						ctx.getString(R.string.sleep_reminder_text_now, bed))
				.setAutoCancel(true)
				.setCategory(NotificationCompat.CATEGORY_REMINDER)
				.setContentIntent(PendingIntent.getActivity(ctx, 0, open, flags))
				.addAction(0, ctx.getString(R.string.sleep_start), PendingIntent.getBroadcast(ctx, REQ_START, start, flags))
				.build();
		try {
			nm.notify(NOTIF_ID, n);
			DiagnosticLog.log(LogCat.SLEEP, "reminder posted");
		} catch (Exception ex) {
			DiagnosticLog.w(LogCat.SLEEP, "reminder not posted", ex);
		}
	}

	private static PendingIntent alarmIntent(Context ctx) {
		Intent i = new Intent(ctx, SleepReminderReceiver.class).setAction(ACTION_REMIND);
		return PendingIntent.getBroadcast(ctx, REQ_ALARM, i, PendingIntent.FLAG_UPDATE_CURRENT |
				PendingIntent.FLAG_IMMUTABLE);
	}

	/** Reads the settings again, from anywhere (the settings page, the addon being switched). */
	public static void reschedule() {
		schedule(FermataApplication.get());
	}
}
