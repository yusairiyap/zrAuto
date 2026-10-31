package me.aap.fermata.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.view.TopToast;
import me.aap.fermata.ytdl.OfflineFallback;

/**
 * The one place that knows whether the phone reaches the internet. Every screen that needs it (list rows, Home cards, the
 * YouTube and browser tabs, the Spotify import) asks {@link #isOnline()} and listens to {@link #addListener}, so they all
 * grey out, show the same "no network" placeholder and come back together. Changes are told to the user once with a top
 * popup ("No connection", "Connection restored").
 */
public final class NetworkState {
	/** Called on the main thread when the connection is lost or comes back. */
	public interface Listener {
		void onNetworkChanged(boolean online);
	}

	private static final long CONFIRM_MS = 1500;
	private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private static final Handler main = new Handler(Looper.getMainLooper());
	private static volatile boolean online = true;
	/** What listeners were last told; the popup follows it, not every blip. */
	private static boolean told = true;
	private static boolean started;
	private static boolean lostOnce;

	private NetworkState() {
	}

	/** Starts following the network; called once from the application. */
	public static void init(Context ctx) {
		if (started) return;
		started = true;
		try {
			ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
			if (cm == null) return;
			online = query(cm);
			told = online;
			cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
				@Override
				public void onAvailable(@NonNull Network n) {
					update(query(cm));
				}

				@Override
				public void onLost(@NonNull Network n) {
					update(query(cm));
				}

				@Override
				public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) {
					update(has(c));
				}
			});
		} catch (Throwable ex) {
			DiagnosticLog.w(LogCat.NETWORK, "network monitor not started", ex);
		}
	}

	private static boolean query(ConnectivityManager cm) {
		try {
			Network n = cm.getActiveNetwork();
			NetworkCapabilities c = (n == null) ? null : cm.getNetworkCapabilities(n);
			return (c != null) && has(c);
		} catch (Throwable ex) {
			return true;
		}
	}

	private static boolean has(NetworkCapabilities c) {
		return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
				c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
	}

	private static void update(boolean now) {
		if (online == now) return;
		online = now;
		// A change has to hold for a moment (a handover from Wi-Fi to mobile data flickers) before anyone is told.
		main.removeCallbacks(confirm);
		main.postDelayed(confirm, CONFIRM_MS);
	}

	private static final Runnable confirm = () -> {
		boolean now = online;
		if (now == told) return;
		told = now;
		DiagnosticLog.log(LogCat.NETWORK, now ? "connection restored" : "connection lost");
		if (now) {
			if (lostOnce) TopToast.show(R.drawable.wifi, R.string.network_restored);
		} else {
			lostOnce = true;
			TopToast.show(R.drawable.network_off, R.string.network_lost_toast);
		}
		for (Listener l : listeners) {
			try {
				l.onNetworkChanged(now);
			} catch (Throwable ex) {
				DiagnosticLog.w(LogCat.NETWORK, "network listener failed", ex);
			}
		}
	};

	/** Whether the internet is reachable now (the value listeners were last told, once a change has settled). */
	public static boolean isOnline() {
		return told;
	}

	public static void addListener(Listener l) {
		if (!listeners.contains(l)) listeners.add(l);
	}

	public static void removeListener(Listener l) {
		listeners.remove(l);
	}

	/**
	 * Whether {@code i} cannot be played right now because the network is down and it is not stored on the phone. A list
	 * or Home row of such an item is greyed out and does nothing but say so.
	 */
	public static boolean isUnavailable(@Nullable Object i) {
		return !told && (i instanceof PlayableItem p) && !OfflineFallback.isOffline(p);
	}

	/** Tells the user why a row did nothing. */
	public static void toastUnavailable() {
		TopToast.show(R.drawable.network_off, R.string.network_item_unavailable);
	}
}
