package me.aap.fermata.addon.ambience;

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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import me.aap.fermata.R;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;
import me.aap.utils.log.Log;

/**
 * Keeps the ambience alive with the screen off and the app in the background: a foreground service
 * of its own (media playback) that lives exactly as long as {@link AmbienceEngine}'s audio thread
 * does, with a notification naming what plays and a "Stop all" button. The sound itself is the
 * engine's; this only stops Android from ending the process.
 */
public class AmbienceService extends Service {
	private static final String CHANNEL_ID = "ambience";
	private static final int NOTIF_ID = 0xA3B1;
	private static final String ACTION_STOP = "me.aap.fermata.ambience.STOP";
	private static boolean running;
	private final Runnable changed = this::updateNotification;

	/** Starts the service (main thread). Without it the ambience still plays while the process lives. */
	static void start(Context ctx) {
		if (running) return;
		try {
			ctx.startForegroundService(new Intent(ctx, AmbienceService.class));
			running = true;
		} catch (Exception ex) {
			// Only possible with the app in the background and nothing else keeping it alive.
			DiagnosticLog.w(LogCat.AMBIENCE, "service not started", ex);
			Log.e(ex, "Failed to start the Ambience service");
		}
	}

	static void stop(Context ctx) {
		if (!running) return;
		running = false;
		ctx.stopService(new Intent(ctx, AmbienceService.class));
	}

	@Override
	public void onCreate() {
		super.onCreate();
		running = true;
		createChannel();
		AmbienceEngine.get().addListener(changed);
	}

	@Override
	public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
		if ((intent != null) && ACTION_STOP.equals(intent.getAction())) {
			AmbienceEngine.get().stopAll();
		}
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
			} else {
				startForeground(NOTIF_ID, buildNotification());
			}
		} catch (Exception ex) {
			DiagnosticLog.w(LogCat.AMBIENCE, "service not in the foreground", ex);
			Log.e(ex, "Failed to start the Ambience service in the foreground");
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		running = false;
		AmbienceEngine.get().removeListener(changed);
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private void updateNotification() {
		NotificationManager nm = getSystemService(NotificationManager.class);
		if (nm == null) return;
		try {
			nm.notify(NOTIF_ID, buildNotification());
		} catch (Exception ex) {
			Log.e(ex, "Failed to update the Ambience notification");
		}
	}

	private Notification buildNotification() {
		AmbienceEngine e = AmbienceEngine.get();
		String names = e.describe(this);
		String text = e.isHeld() ? getString(R.string.ambience_notif_paused) :
				names.isEmpty() ? getString(R.string.ambience_stopped) : names;
		Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
		if (open == null) open = new Intent();
		open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		Intent stop = new Intent(this, AmbienceService.class).setAction(ACTION_STOP);
		return new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ambience)
				.setContentTitle(getString(R.string.ambience_title))
				.setContentText(text)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setSilent(true)
				.setCategory(NotificationCompat.CATEGORY_TRANSPORT)
				.setContentIntent(PendingIntent.getActivity(this, 0, open, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT))
				.addAction(0, getString(R.string.ambience_stop_all),
						PendingIntent.getService(this, 1, stop, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT))
				.build();
	}

	private void createChannel() {
		NotificationManager nm = getSystemService(NotificationManager.class);
		if ((nm == null) || (nm.getNotificationChannel(CHANNEL_ID) != null)) return;
		NotificationChannel c = new NotificationChannel(CHANNEL_ID, getString(R.string.ambience_title),
				NotificationManager.IMPORTANCE_LOW);
		c.setShowBadge(false);
		nm.createNotificationChannel(c);
	}
}
