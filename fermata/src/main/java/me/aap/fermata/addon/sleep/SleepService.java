package me.aap.fermata.addon.sleep;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.text.format.DateFormat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Date;

import me.aap.fermata.R;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;
import me.aap.utils.log.Log;

/**
 * Keeps a sleep session alive with the screen off: a foreground service that lives exactly as long as
 * {@link SleepTracker}'s session does, with a notification saying since when the night is recorded and a Stop button. The
 * recording itself is the tracker's; this only stops Android from ending the process.
 */
public class SleepService extends Service {
	private static final String CHANNEL_ID = "sleep";
	private static final int NOTIF_ID = 0xA3B2;
	private static final String ACTION_STOP = "me.aap.fermata.sleep.STOP";
	private static boolean running;

	/** Starts the service (main thread). Without it the session still runs while the process lives. */
	static void start(Context ctx) {
		if (running) return;
		try {
			ctx.startForegroundService(new Intent(ctx, SleepService.class));
			running = true;
		} catch (Exception ex) {
			DiagnosticLog.w(LogCat.SLEEP, "service not started", ex);
			Log.e(ex, "Failed to start the Sleep service");
		}
	}

	static void stop(Context ctx) {
		if (!running) return;
		running = false;
		ctx.stopService(new Intent(ctx, SleepService.class));
	}

	@Override
	public void onCreate() {
		super.onCreate();
		running = true;
		createChannel();
	}

	@Override
	public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
		if ((intent != null) && ACTION_STOP.equals(intent.getAction())) {
			SleepTracker.get().stop();
			stopSelf();
			return START_NOT_STICKY;
		}
		try {
			if (Build.VERSION.SDK_INT >= 34) {
				startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
			} else {
				startForeground(NOTIF_ID, buildNotification());
			}
		} catch (Exception ex) {
			DiagnosticLog.w(LogCat.SLEEP, "service not in the foreground", ex);
			Log.e(ex, "Failed to start the Sleep service in the foreground");
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		running = false;
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private Notification buildNotification() {
		long since = SleepTracker.get().getStart();
		String time = DateFormat.getTimeFormat(this).format(new Date((since == 0) ? System.currentTimeMillis() : since));
		Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
		if (open == null) open = new Intent();
		open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		Intent stop = new Intent(this, SleepService.class).setAction(ACTION_STOP);
		return new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.sleep)
				.setContentTitle(getString(R.string.sleep_title))
				.setContentText(SleepTracker.get().isAutoStarted() ? getString(R.string.sleep_notif_auto, time) :
						getString(R.string.sleep_notif_text, time))
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setSilent(true)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.setContentIntent(PendingIntent.getActivity(this, 0, open, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT))
				.addAction(0, getString(R.string.sleep_stop),
						PendingIntent.getService(this, 1, stop, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT))
				.build();
	}

	private void createChannel() {
		NotificationManager nm = getSystemService(NotificationManager.class);
		if ((nm == null) || (nm.getNotificationChannel(CHANNEL_ID) != null)) return;
		NotificationChannel c = new NotificationChannel(CHANNEL_ID, getString(R.string.sleep_title),
				NotificationManager.IMPORTANCE_LOW);
		c.setShowBadge(false);
		nm.createNotificationChannel(c);
	}
}
