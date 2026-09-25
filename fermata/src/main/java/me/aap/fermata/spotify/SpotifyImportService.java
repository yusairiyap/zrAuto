package me.aap.fermata.spotify;

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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import me.aap.fermata.R;
import me.aap.fermata.spotify.SpotifyImportModel.Track;
import me.aap.utils.log.Log;

/**
 * Keeps the Spotify import's matching and importing ({@link SpotifyImportEngine}) running while
 * the app is in the background or closed: a foreground service with a progress notification
 * (tap to open the import screen, "Stop" to pause matching or cancel an import). It stops itself
 * as soon as the engine has nothing left to do.
 * <p>
 * If Android kills the process anyway, the service is restarted (START_STICKY) and the engine
 * resumes from its saved session.
 */
public class SpotifyImportService extends Service implements SpotifyImportEngine.Listener {
	private static final String CHANNEL_ID = "spotify_import";
	private static final int NOTIF_ID = 0x5907;
	private static final int DONE_NOTIF_ID = 0x5908;
	private static final String ACTION_STOP = "me.aap.fermata.spotify.STOP";
	/** Opens the import screen; see MainActivityDelegate.handleIntent. */
	public static final Uri OPEN_URI = Uri.parse("zrauto://spotify-import");
	private static boolean running;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable updateTask = this::updateNotification;
	private long lastUpdate;
	/** startForeground() has been called: only then may the service stop itself. */
	private boolean foreground;
	@Nullable
	private SpotifyImportEngine engine;

	/** Starts or stops the service to match whether the engine has work. Main thread. */
	static void update(Context ctx) {
		SpotifyImportEngine e = SpotifyImportEngine.get();
		if (running || !e.isBusy()) return;

		try {
			Intent i = new Intent(ctx, SpotifyImportService.class);
			ctx.startForegroundService(i);
			running = true;
		} catch (Exception ex) {
			// E.g. ForegroundServiceStartNotAllowedException when this happens with the app in
			// the background: the work carries on for as long as the process lives, and the saved
			// session covers the rest.
			Log.e(ex, "Failed to start the Spotify import service");
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		running = true;
		createChannel();
		engine = SpotifyImportEngine.get();
		engine.addListener(this);
	}

	@Override
	public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
		SpotifyImportEngine e = engine;
		if (e == null) return START_NOT_STICKY;

		if ((intent != null) && ACTION_STOP.equals(intent.getAction())) {
			if (e.isImporting()) e.cancelImport();
			else e.setMatchingPaused(true);
		}

		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(NOTIF_ID, buildNotification(e),
						ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
			} else {
				startForeground(NOTIF_ID, buildNotification(e));
			}
			foreground = true;
		} catch (Exception ex) {
			Log.e(ex, "Failed to start the Spotify import service in the foreground");
		}

		// Restarted by the system after the process was killed: the engine has just restored
		// the session and resumes on its own; if there turns out to be nothing to do, stop.
		handler.postDelayed(this::stopIfIdle, 3000);
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		running = false;
		handler.removeCallbacksAndMessages(null);
		SpotifyImportEngine e = engine;
		if (e != null) {
			e.removeListener(this);
			e.saveNow();
		}
	}

	/** Android 15+: data sync services get at most 6 hours a day. Pause and save, then stop. */
	@Override
	public void onTimeout(int startId, int fgsType) {
		SpotifyImportEngine e = engine;
		if (e != null) {
			if (e.isImporting()) e.cancelImport();
			else e.setMatchingPaused(true);
		}
		stopSelf();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onTrackChanged(Track t) {
		scheduleUpdate();
	}

	@Override
	public void onChanged() {
		scheduleUpdate();
		stopIfIdle();
	}

	@Override
	public void onImportFinished(SpotifyImportEngine.ImportResult result) {
		// Leaves a lasting "done" notification, since the app may not be on screen to say so.
		NotificationManager nm = getSystemService(NotificationManager.class);
		if (nm == null) return;
		Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.playlist_import)
				.setContentTitle(getString(R.string.spotify_import))
				.setContentText(result.getMessage(this))
				.setStyle(new NotificationCompat.BigTextStyle().bigText(result.getMessage(this)))
				.setContentIntent(openIntent())
				.setAutoCancel(true)
				.build();
		try {
			nm.notify(DONE_NOTIF_ID, n);
		} catch (Exception ex) {
			Log.e(ex, "Failed to post the Spotify import notification");
		}
	}

	private void stopIfIdle() {
		SpotifyImportEngine e = engine;
		// Stopping before startForeground() was called would crash the app (the system requires
		// it after startForegroundService()); onStartCommand re-checks shortly after it anyway.
		if (!foreground || ((e != null) && e.isBusy())) return;
		stopForeground(STOP_FOREGROUND_REMOVE);
		stopSelf();
		running = false;
	}

	/** At most about once a second: every search result is an update. */
	private void scheduleUpdate() {
		long wait = 1000 - (SystemClock.uptimeMillis() - lastUpdate);
		handler.removeCallbacks(updateTask);
		if (wait <= 0) updateNotification();
		else handler.postDelayed(updateTask, wait);
	}

	private void updateNotification() {
		SpotifyImportEngine e = engine;
		if ((e == null) || !e.isBusy()) return;
		lastUpdate = SystemClock.uptimeMillis();
		NotificationManager nm = getSystemService(NotificationManager.class);
		if (nm == null) return;
		try {
			nm.notify(NOTIF_ID, buildNotification(e));
		} catch (Exception ex) {
			Log.e(ex, "Failed to update the Spotify import notification");
		}
	}

	private Notification buildNotification(SpotifyImportEngine e) {
		NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.playlist_import)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setSilent(true)
				.setContentIntent(openIntent());
		String text;

		if (e.isImporting()) {
			b.setContentTitle(getString(R.string.spotify_notif_importing));
			text = (e.getProgressText() != null) ? e.getProgressText() :
					getString(R.string.spotify_import_preparing);
			b.setProgress(Math.max(1, e.getProgressTotal()), e.getProgressDone(),
					e.isSaving() || (e.getProgressTotal() == 0));
			if (!e.isSaving()) {
				b.addAction(0, getString(android.R.string.cancel), stopIntent());
			}
		} else {
			int[] p = e.getMatchProgress();
			b.setContentTitle(getString(R.string.spotify_notif_matching));
			text = (p[1] > 0) ? getString(R.string.spotify_matching_bg, p[0], p[1]) :
					getString(R.string.spotify_import_loading);
			b.setProgress(Math.max(1, p[1]), p[0], p[1] == 0);
			b.addAction(0, getString(R.string.spotify_matching_stop), stopIntent());
		}

		b.setContentText(text);
		return b.build();
	}

	private PendingIntent openIntent() {
		Intent i = new Intent(Intent.ACTION_VIEW, OPEN_URI);
		i.setPackage(getPackageName());
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		return PendingIntent.getActivity(this, 0, i, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT);
	}

	private PendingIntent stopIntent() {
		Intent i = new Intent(this, SpotifyImportService.class);
		i.setAction(ACTION_STOP);
		return PendingIntent.getService(this, 1, i, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT);
	}

	private void createChannel() {
		NotificationManager nm = getSystemService(NotificationManager.class);
		if ((nm == null) || (nm.getNotificationChannel(CHANNEL_ID) != null)) return;
		NotificationChannel c = new NotificationChannel(CHANNEL_ID,
				getString(R.string.spotify_notif_channel), NotificationManager.IMPORTANCE_LOW);
		c.setShowBadge(false);
		nm.createNotificationChannel(c);
	}
}
