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
	/** What Android's callbacks say (a network with a validated internet connection), and what a real try says. */
	private static volatile boolean callbackOnline = true;
	private static volatile boolean probeDown;
	private static long lastProbe;
	private static final java.util.concurrent.ExecutorService prober = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "net-probe");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
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
			callbackOnline = query(cm);
			online = callbackOnline;
			told = online;
			// Android can call a network validated while nothing gets through (a Wi-Fi without internet, a dead mobile link):
			// one real try at the start.
			if (online) probeNow();
			cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
				@Override
				public void onAvailable(@NonNull Network n) {
					callbackChanged(query(cm));
				}

				@Override
				public void onLost(@NonNull Network n) {
					callbackChanged(query(cm));
				}

				@Override
				public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) {
					callbackChanged(has(c));
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

	private static void callbackChanged(boolean now) {
		callbackOnline = now;
		// A network came back: look for real, so a stale "down" from an earlier failure clears at once.
		if (now && probeDown) probeNow();
		recompute();
	}

	private static void recompute() {
		update(callbackOnline && !probeDown);
	}

	/**
	 * Something that needs the internet just failed (a lookup, a stall, a page that would not load): checks for real whether
	 * anything gets through, since Android's own answer can lag or be wrong. At most once every 4 s; nothing when already known down.
	 */
	public static void hintFailure() {
		long now = android.os.SystemClock.uptimeMillis();
		if (!told || (now - lastProbe < 4000)) return;
		lastProbe = now;
		probeNow();
	}

	private static void probeNow() {
		prober.execute(() -> {
			boolean ok = tcp("1.1.1.1", 443) || tcp("8.8.8.8", 53) || resolves("www.youtube.com");
			main.post(() -> {
				if (probeDown != !ok) DiagnosticLog.log(LogCat.NETWORK, "reachability probe", "reachable=" + ok);
				probeDown = !ok;
				main.removeCallbacks(reprobe);
				// Down: tried again every few seconds until something gets through.
				if (!ok) main.postDelayed(reprobe, 8000);
				recompute();
			});
		});
	}

	private static final Runnable reprobe = NetworkState::probeNow;

	private static boolean tcp(String host, int port) {
		try (java.net.Socket sk = new java.net.Socket()) {
			sk.connect(new java.net.InetSocketAddress(host, port), 2000);
			return true;
		} catch (Exception ex) {
			return false;
		}
	}

	private static boolean resolves(String host) {
		try {
			return java.net.InetAddress.getByName(host) != null;
		} catch (Exception ex) {
			return false;
		}
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
		// The top popup holds one card: the stall popup (which has a button) is never replaced by "No connection", and
		// it is taken down when the connection comes back, before "Connection restored" shows.
		boolean stallUp = me.aap.fermata.ui.view.NetworkIssuePopup.isShown();
		if (now) {
			if (stallUp) me.aap.fermata.ui.view.NetworkIssuePopup.dismiss();
			if (lostOnce) TopToast.show(R.drawable.wifi, R.string.network_restored);
		} else {
			lostOnce = true;
			// While a stream plays, the stall popup (about the same news, with the downloaded fallback) follows if it really stops:
			// two cards for one event were noise. The toast is for when nothing streams.
			if (!stallUp && !streamPlaying()) TopToast.show(R.drawable.network_off, R.string.network_lost_toast);
		}
		for (Listener l : listeners) {
			try {
				l.onNetworkChanged(now);
			} catch (Throwable ex) {
				DiagnosticLog.w(LogCat.NETWORK, "network listener failed", ex);
			}
		}
	};

	private static boolean streamPlaying() {
		try {
			var a = me.aap.fermata.ui.activity.MainActivityDelegate.getUiDelegate();
			if (a == null) return false;
			var cb = a.getMediaSessionCallback();
			var eng = cb.getEngine();
			return cb.isPlaying() && (eng != null) && !OfflineFallback.isOffline(eng.getSource());
		} catch (Throwable ex) {
			return false;
		}
	}

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
