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

	/** Sets, moves or cancels the one pending alarm to match the settings. */
	public static void schedule(Context ctx) {
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

	/** The alarm went off: remind, and set tomorrow's. */
	static void remind(Context ctx) {
		try {
			if (SleepAddon.isEnabled() && !SleepTracker.get().isActive()) post(ctx);
		} finally {
			schedule(ctx);
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
