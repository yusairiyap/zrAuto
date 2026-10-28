package me.aap.fermata.addon.sleep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The bedtime reminder's alarm and its Start button, and the device boot (which drops every alarm); see {@link SleepReminder}. */
public class SleepReminderReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context ctx, Intent intent) {
		String action = (intent == null) ? null : intent.getAction();
		Context app = ctx.getApplicationContext();
		if (SleepReminder.ACTION_REMIND.equals(action)) {
			SleepReminder.remind(app);
		} else if (SleepReminder.ACTION_START.equals(action)) {
			SleepReminder.startFromNotification(app);
		} else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
			SleepReminder.schedule(app);
		}
	}
}
