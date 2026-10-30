package me.aap.fermata.backup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;

/**
 * The scheduled backup's alarm, and the device boot (which drops every alarm), see
 * {@link BackupManager}.
 */
public class BackupReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context ctx, Intent intent) {
		String action = (intent == null) ? null : intent.getAction();
		Context app = ctx.getApplicationContext();

		if (BackupManager.ACTION_RUN.equals(action)) {
			PendingResult res = goAsync();
			BackupManager.runScheduled(app, "alarm", res::finish);
		} else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
			DiagnosticLog.log(LogCat.BACKUP, "device booted");
			BackupManager.schedule(app);
		}
	}
}
