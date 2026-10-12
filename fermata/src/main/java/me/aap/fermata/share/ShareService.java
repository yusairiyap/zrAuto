package me.aap.fermata.share;

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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.log.Log;

/**
 * Keeps a Share transfer going while the app is in the background: a foreground service with a
 * progress notification ("Cancel" stops the transfers), gone as soon as nothing is being
 * transferred. The transfers themselves are {@link ShareManager}'s.
 */
public class ShareService extends Service implements ShareManager.Listener {
	private static final String CHANNEL_ID = "share";
	private static final String CHANNEL_REQUEST_ID = "share_request";
	private static final int NOTIF_ID = 0x5910;
	private static final int REQUEST_NOTIF_ID = 0x5911;
	private static final int DONE_NOTIF_BASE = 0x5920;
	private static final String ACTION_CANCEL = "me.aap.fermata.share.CANCEL";
	private static boolean running;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable updateTask = this::updateNotification;
	private long lastUpdate;
	private boolean foreground;

	/** Starts the service while there is a transfer. Main thread. */
	static void update(Context ctx) {
		if (running || !ShareManager.get().isBusy()) return;

		try {
			ctx.startForegroundService(new Intent(ctx, ShareService.class));
			running = true;
		} catch (Exception ex) {
			// Only possible with the app in the background; the transfer carries on for as long as
			// the process lives.
			Log.e(ex, "Failed to start the Share service");
		}
	}

	/** A transfer ended: say how it went. */
	static void onTransferEnded(Context ctx, ShareManager.Transfer t) {
		String msg = endMessage(ctx, t);
		if (msg == null) return;
		boolean ok = t.state == ShareManager.State.DONE;
		MainActivityDelegate a = MainActivityDelegate.getUiDelegate();

		if (a != null) {
			TopToast.show(ok ? R.drawable.share : R.drawable.close_small, msg);
		}

		// With the app open the card above says it all.
		if (ShareManager.get().isUiVisible()) return;

		if ((t.state == ShareManager.State.DONE) || (t.state == ShareManager.State.FAILED)) {
			NotificationManager nm = ctx.getSystemService(NotificationManager.class);
			if (nm == null) return;
			createChannels(ctx, nm);
			Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
					.setSmallIcon(R.drawable.share)
					.setContentTitle(ctx.getString(R.string.share))
					.setContentText(msg)
					.setStyle(new NotificationCompat.BigTextStyle().bigText(msg))
					.setContentIntent(openIntent(ctx))
					.setAutoCancel(true)
					.build();
			try {
				nm.notify(DONE_NOTIF_BASE + (t.id % 16), n);
			} catch (Exception ex) {
				Log.e(ex, "Failed to post the Share notification");
			}
		}
	}

	@Nullable
	private static String endMessage(Context ctx, ShareManager.Transfer t) {
		String title = t.offer.title;
		return switch (t.state) {
			case DONE -> t.sending ? ctx.getResources().getQuantityString(R.plurals.share_sent_n,
					t.offer.entries.size(), t.offer.entries.size(), t.peerName) :
					ctx.getString(R.string.share_received, title, (t.result != null) ? t.result : "");
			case FAILED -> ctx.getString(R.string.share_failed, title, String.valueOf(t.error));
			case DECLINED -> t.sending ? ctx.getString(R.string.share_declined, t.peerName) : null;
			case CANCELLED -> ctx.getString(R.string.share_cancelled, title);
			default -> null;
		};
	}

	/** Someone wants to send something: a notification, in case the app isn't in front. */
	static void notifyIncoming(Context ctx, String sender, String title) {
		if (ShareManager.get().isUiVisible()) return;
		NotificationManager nm = ctx.getSystemService(NotificationManager.class);
		if (nm == null) return;
		createChannels(ctx, nm);
		Notification n = new NotificationCompat.Builder(ctx, CHANNEL_REQUEST_ID)
				.setSmallIcon(R.drawable.share)
				.setContentTitle(ctx.getString(R.string.share_incoming_title))
				.setContentText(ctx.getString(R.string.share_incoming_notif, sender, title))
				.setContentIntent(openIntent(ctx))
				.setTimeoutAfter(60000)
				.setAutoCancel(true)
				.build();
		try {
			nm.notify(REQUEST_NOTIF_ID, n);
		} catch (Exception ex) {
			Log.e(ex, "Failed to post the Share request notification");
		}
	}

	static void clearIncoming(Context ctx) {
		NotificationManager nm = ctx.getSystemService(NotificationManager.class);
		if (nm != null) nm.cancel(REQUEST_NOTIF_ID);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		running = true;
		createChannels(this, getSystemService(NotificationManager.class));
		ShareManager.get().addListener(this);
	}

	@Override
	public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
		ShareManager m = ShareManager.get();

		if ((intent != null) && ACTION_CANCEL.equals(intent.getAction())) {
			for (ShareManager.Transfer t : m.getTransfers()) t.cancel();
		}

		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(NOTIF_ID, buildNotification(m), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
			} else {
				startForeground(NOTIF_ID, buildNotification(m));
			}
			foreground = true;
		} catch (Exception ex) {
			Log.e(ex, "Failed to start the Share service in the foreground");
		}

		handler.postDelayed(this::stopIfIdle, 2000);
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		running = false;
		handler.removeCallbacksAndMessages(null);
		ShareManager.get().removeListener(this);
	}

	/** Android 15+: data sync services get a limited time; stop the transfers rather than crash. */
	@Override
	public void onTimeout(int startId, int fgsType) {
		for (ShareManager.Transfer t : ShareManager.get().getTransfers()) t.cancel();
		stopSelf();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onTransfersChanged() {
		long wait = 1000 - (SystemClock.uptimeMillis() - lastUpdate);
		handler.removeCallbacks(updateTask);
		if (wait <= 0) updateNotification();
		else handler.postDelayed(updateTask, wait);
		stopIfIdle();
	}

	private void stopIfIdle() {
		if (!foreground || ShareManager.get().isBusy()) return;
		stopForeground(STOP_FOREGROUND_REMOVE);
		stopSelf();
		running = false;
	}

	private void updateNotification() {
		ShareManager m = ShareManager.get();
		if (!m.isBusy()) return;
		lastUpdate = SystemClock.uptimeMillis();
		NotificationManager nm = getSystemService(NotificationManager.class);
		if (nm == null) return;
		try {
			nm.notify(NOTIF_ID, buildNotification(m));
		} catch (Exception ex) {
			Log.e(ex, "Failed to update the Share notification");
		}
	}

	private Notification buildNotification(ShareManager m) {
		NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.share)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setSilent(true)
				.setContentIntent(openIntent(this))
				.addAction(0, getString(android.R.string.cancel), cancelIntent());

		long done = 0;
		long total = 0;
		int active = 0;
		ShareManager.Transfer first = null;

		for (ShareManager.Transfer t : m.getTransfers()) {
			if (!t.isActive()) continue;
			if (first == null) first = t;
			active++;
			done += t.done;
			total += t.total;
		}

		if (first == null) {
			b.setContentTitle(getString(R.string.share));
		} else if (active > 1) {
			b.setContentTitle(getString(R.string.share_notif_many, active));
		} else {
			b.setContentTitle(getString(first.sending ? R.string.share_notif_sending :
					R.string.share_notif_receiving, first.offer.title, first.peerName));
			String text;
			if (first.state == ShareManager.State.WAITING) {
				text = getString(R.string.share_waiting);
			} else if (first.state == ShareManager.State.SAVING) {
				text = getString(R.string.share_saving);
			} else if (first.current != null) {
				text = first.current;
			} else {
				text = getString(R.string.share_working);
			}
			b.setContentText(text);
		}

		if (total > 0) {
			int max = 1000;
			b.setProgress(max, (int) Math.min(max, done * max / total), false);
			if (active == 1) {
				b.setSubText(Formatter.formatShortFileSize(this, done) + " / " +
						Formatter.formatShortFileSize(this, total));
			}
		} else {
			b.setProgress(0, 0, true);
		}

		return b.build();
	}

	private static PendingIntent openIntent(Context ctx) {
		Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
		if (i == null) i = new Intent();
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		return PendingIntent.getActivity(ctx, 0, i, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT);
	}

	private PendingIntent cancelIntent() {
		Intent i = new Intent(this, ShareService.class);
		i.setAction(ACTION_CANCEL);
		return PendingIntent.getService(this, 1, i, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT);
	}

	private static void createChannels(Context ctx, @Nullable NotificationManager nm) {
		if (nm == null) return;
		if (nm.getNotificationChannel(CHANNEL_ID) == null) {
			NotificationChannel c = new NotificationChannel(CHANNEL_ID,
					ctx.getString(R.string.share_notif_channel), NotificationManager.IMPORTANCE_LOW);
			c.setShowBadge(false);
			nm.createNotificationChannel(c);
		}
		if (nm.getNotificationChannel(CHANNEL_REQUEST_ID) == null) {
			NotificationChannel c = new NotificationChannel(CHANNEL_REQUEST_ID,
					ctx.getString(R.string.share_notif_request_channel), NotificationManager.IMPORTANCE_HIGH);
			nm.createNotificationChannel(c);
		}
	}
}
