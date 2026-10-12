package me.aap.fermata.backup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import me.aap.fermata.FermataApplication;
import me.aap.utils.log.Log;

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
			// Off the main thread: writing the zip, possibly into a cloud provider's folder.
			new Thread(() -> {
				try {
					if (FermataApplication.get().getPreferenceStore()
							.getBooleanPref(BackupManager.AUTO_BACKUP)) {
						String name = BackupManager.runBackup(app);
						Log.i("Automatic backup: ", (name != null) ? name : "failed");
						// A failure waits for the next interval too, rather than retrying every minute.
						if (name == null) {
							FermataApplication.get().getPreferenceStore().applyLongPref(
									BackupManager.AUTO_BACKUP_LAST, System.currentTimeMillis());
						}
					}
				} finally {
					// Even after a failure, so it tries again at the next interval.
					BackupManager.schedule(app);
					res.finish();
				}
			}, "auto-backup").start();
		} else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
			BackupManager.schedule(app);
		}
	}
}
