package me.aap.fermata.ytdl;

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
import me.aap.fermata.ytdl.YtDownloads.Entry;
import me.aap.utils.log.Log;

/**
 * Keeps {@link YtDownloads} running while the app is in the background or closed: a foreground
 * service with a progress notification (Pause/Resume and Cancel in it). It stops itself when the
 * queue has nothing left to fetch; paused downloads stay listed in a plain notification whose
 * Resume starts the service again.
 */
public class YtDownloadService extends Service implements YtDownloads.Listener {
	private static final String CHANNEL_ID = "youtube_downloads";
	private static final int NOTIF_ID = 0x5910;
	private static final int REPORT_NOTIF_ID = 0x5911;
	private static final String ACTION_PAUSE = "me.aap.fermata.ytdl.PAUSE";
	private static final String ACTION_RESUME = "me.aap.fermata.ytdl.RESUME";
	private static final String ACTION_CANCEL = "me.aap.fermata.ytdl.CANCEL";
	private boolean foreground;
	private YtDownloads downloads;

	/** Starts the service (it stops by itself). Main thread. */
	static void start(Context ctx) {
		try {
			ctx.startForegroundService(new Intent(ctx, YtDownloadService.class));
		} catch (Exception ex) {
			// E.g. ForegroundServiceStartNotAllowedException with the app in the background: the
			// queue still runs for as long as the process lives, and what's on disk is kept.
			Log.e(ex, "Failed to start the YouTube download service");
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		createChannel();
		downloads = YtDownloads.get();
		downloads.addListener(this);
	}

	@Override
	public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
		String action = (intent != null) ? intent.getAction() : null;
		if (ACTION_PAUSE.equals(action)) downloads.pauseAll();
		else if (ACTION_RESUME.equals(action)) downloads.resumeAll();
		else if (ACTION_CANCEL.equals(action)) downloads.cancelAll();

		// startForegroundService() demands startForeground() in return, busy or not.
		try {
			Notification n = buildOngoing();
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
			} else {
				startForeground(NOTIF_ID, n);
			}
			foreground = true;
		} catch (Exception ex) {
			Log.e(ex, "Failed to start the YouTube download service in the foreground");
		}

		onDownloadsChanged();
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		downloads.removeListener(this);
	}

	/** Android 15+: data sync services get at most 6 hours a day. Pause (progress is kept), stop. */
	@Override
	public void onTimeout(int startId, int fgsType) {
		downloads.pauseAll();
		stopSelf();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onDownloadsChanged() {
		NotificationManager nm = getSystemService(NotificationManager.class);
		if (nm == null) return;

		if (downloads.isBusy()) {
			try {
				nm.notify(NOTIF_ID, buildOngoing());
			} catch (Exception ex) {
				Log.e(ex, "Failed to update the YouTube download notification");
			}
			return;
		}

		// Nothing left to fetch: say how it went, leaving a Resume behind for what's paused.
		if (!foreground) return;
		stopForeground(STOP_FOREGROUND_REMOVE);
		foreground = false;
		report(nm);
		downloads.batchReported();
		stopSelf();
	}

	private void report(NotificationManager nm) {
		int done = downloads.getBatchDone();
		int failed = downloads.getBatchFailed();
		int paused = 0;
		for (Entry e : downloads.snapshot()) {
			if (e.state == YtDownloads.State.PAUSED) paused++;
		}

		NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.download)
				.setAutoCancel(true)
				.setOnlyAlertOnce(true)
				.setSilent(true)
				.setContentIntent(openIntent());

		if (paused > 0) {
			b.setContentTitle(getString(R.string.ytdl_notif_paused))
					.setContentText(getString(R.string.ytdl_notif_paused_text, paused))
					.addAction(0, getString(R.string.ytdl_resume), serviceIntent(ACTION_RESUME, 2))
					.addAction(0, getString(R.string.ytdl_cancel), serviceIntent(ACTION_CANCEL, 3));
		} else if (done > 0 || failed > 0) {
			b.setContentTitle(getString((failed > 0) && (done == 0) ? R.string.ytdl_notif_failed :
							R.string.ytdl_notif_done))
					.setContentText((failed > 0) ?
							getString(R.string.ytdl_notif_done_failed_text, done, failed) :
							getString(R.string.ytdl_notif_done_text, done));
			if (failed > 0) {
				b.addAction(0, getString(R.string.ytdl_resume), serviceIntent(ACTION_RESUME, 2));
			}
		} else {
			// Cancelled: nothing to report.
			nm.cancel(REPORT_NOTIF_ID);
			return;
		}

		try {
			nm.notify(REPORT_NOTIF_ID, b.build());
		} catch (Exception ex) {
			Log.e(ex, "Failed to post the YouTube download notification");
		}
	}

	private Notification buildOngoing() {
		NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.download)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setSilent(true)
				.setContentIntent(openIntent());
		Entry cur = downloads.getCurrent();
		int total = downloads.getBatchTotal();

		if (total > 1) {
			b.setContentTitle(getString(R.string.ytdl_notif_title,
					Math.min(total, downloads.getBatchDone() + downloads.getBatchFailed() + 1), total));
		} else {
			b.setContentTitle(getString(R.string.ytdl_notif_title_one));
		}

		if (cur != null) {
			b.setContentText(cur.getDisplayTitle());
			long t = cur.total;
			if (t > 0) {
				int shift = 0;
				while ((t >> shift) > Integer.MAX_VALUE) shift++;
				b.setProgress((int) (t >> shift), (int) (cur.bytes >> shift), false);
			} else {
				b.setProgress(0, 0, true);
			}
		} else {
			b.setProgress(0, 0, true);
		}

		b.addAction(0, getString(R.string.ytdl_pause), serviceIntent(ACTION_PAUSE, 1));
		b.addAction(0, getString(R.string.ytdl_cancel), serviceIntent(ACTION_CANCEL, 3));
		return b.build();
	}

	private PendingIntent serviceIntent(String action, int requestCode) {
		Intent i = new Intent(this, YtDownloadService.class);
		i.setAction(action);
		return PendingIntent.getForegroundService(this, requestCode, i,
				FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT);
	}

	@Nullable
	private PendingIntent openIntent() {
		Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
		if (i == null) return null;
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		return PendingIntent.getActivity(this, 0, i, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT);
	}

	private void createChannel() {
		NotificationManager nm = getSystemService(NotificationManager.class);
		if ((nm == null) || (nm.getNotificationChannel(CHANNEL_ID) != null)) return;
		NotificationChannel c = new NotificationChannel(CHANNEL_ID, getString(R.string.ytdl_channel),
				NotificationManager.IMPORTANCE_LOW);
		c.setShowBadge(false);
		nm.createNotificationChannel(c);
	}
}
