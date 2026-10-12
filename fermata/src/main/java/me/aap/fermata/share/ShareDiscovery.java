package me.aap.fermata.share;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Random;

import me.aap.utils.log.Log;

/**
 * Finds the other phones running zrAuto on the same Wi-Fi, and makes this one findable: the
 * system's service discovery (NSD, mDNS), which needs no permission and no account. Everything is
 * called on, and reports on, the main thread.
 */
@SuppressWarnings("deprecation")
final class ShareDiscovery {
	private static final String TYPE = "_zrauto-share._tcp.";
	private static final String TYPE_MARK = "_zrauto-share";

	interface Listener {
		void onPeerFound(ShareManager.Peer peer);

		void onPeerLost(String serviceName);
	}

	/** Tells this phone's own announcement from other phones'. */
	final String instanceId = String.format("%08x", new Random().nextInt());
	private final NsdManager nsd;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Deque<NsdServiceInfo> queue = new ArrayDeque<>();
	@Nullable
	private NsdManager.RegistrationListener registration;
	@Nullable
	private NsdManager.DiscoveryListener discovery;
	@Nullable
	private Listener listener;
	private boolean resolving;

	ShareDiscovery(Context ctx) {
		nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
	}

	boolean isRegistered() {
		return registration != null;
	}

	boolean isDiscovering() {
		return discovery != null;
	}

	void register(int port, String deviceName) {
		if ((nsd == null) || (registration != null)) return;
		NsdServiceInfo info = new NsdServiceInfo();
		info.setServiceName("zrAuto-" + instanceId);
		info.setServiceType(TYPE);
		info.setPort(port);
		info.setAttribute("id", instanceId);
		info.setAttribute("n", deviceName);

		NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
			@Override
			public void onRegistrationFailed(NsdServiceInfo i, int code) {
				Log.w("Share: failed to announce this phone: ", code);
				main.post(() -> {
					if (registration == this) registration = null;
				});
			}

			@Override
			public void onUnregistrationFailed(NsdServiceInfo i, int code) {
				Log.w("Share: failed to stop announcing this phone: ", code);
			}

			@Override
			public void onServiceRegistered(NsdServiceInfo i) {
			}

			@Override
			public void onServiceUnregistered(NsdServiceInfo i) {
			}
		};

		try {
			registration = l;
			nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
		} catch (Exception ex) {
			Log.e(ex, "Share: failed to announce this phone");
			registration = null;
		}
	}

	void unregister() {
		NsdManager.RegistrationListener l = registration;
		registration = null;
		if ((l == null) || (nsd == null)) return;

		try {
			nsd.unregisterService(l);
		} catch (Exception ex) {
			Log.w("Share: failed to stop announcing this phone: ", ex);
		}
	}

	void startDiscovery(Listener listener) {
		if ((nsd == null) || (discovery != null)) return;
		this.listener = listener;

		NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
			@Override
			public void onStartDiscoveryFailed(String type, int code) {
				Log.w("Share: failed to look for phones: ", code);
				main.post(() -> {
					if (discovery == this) discovery = null;
				});
			}

			@Override
			public void onStopDiscoveryFailed(String type, int code) {
				Log.w("Share: failed to stop looking for phones: ", code);
			}

			@Override
			public void onDiscoveryStarted(String type) {
			}

			@Override
			public void onDiscoveryStopped(String type) {
			}

			@Override
			public void onServiceFound(NsdServiceInfo info) {
				String t = info.getServiceType();
				if ((t == null) || !t.contains(TYPE_MARK)) return;
				main.post(() -> {
					if (discovery != this) return;
					queue.add(info);
					resolveNext();
				});
			}

			@Override
			public void onServiceLost(NsdServiceInfo info) {
				String name = info.getServiceName();
				main.post(() -> {
					Listener ls = ShareDiscovery.this.listener;
					if ((discovery == this) && (ls != null)) ls.onPeerLost(name);
				});
			}
		};

		try {
			discovery = l;
			nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l);
		} catch (Exception ex) {
			Log.e(ex, "Share: failed to look for phones");
			discovery = null;
		}
	}

	void stopDiscovery() {
		NsdManager.DiscoveryListener l = discovery;
		discovery = null;
		listener = null;
		queue.clear();
		if ((l == null) || (nsd == null)) return;

		try {
			nsd.stopServiceDiscovery(l);
		} catch (Exception ex) {
			Log.w("Share: failed to stop looking for phones: ", ex);
		}
	}

	/** One at a time: the system refuses a second resolve while one is running. */
	private void resolveNext() {
		if (resolving || (nsd == null)) return;
		NsdServiceInfo next = queue.poll();
		if (next == null) return;
		resolving = true;

		try {
			nsd.resolveService(next, new NsdManager.ResolveListener() {
				@Override
				public void onResolveFailed(NsdServiceInfo info, int code) {
					main.post(() -> {
						resolving = false;
						resolveNext();
					});
				}

				@Override
				public void onServiceResolved(NsdServiceInfo info) {
					main.post(() -> {
						resolving = false;
						report(info);
						resolveNext();
					});
				}
			});
		} catch (Exception ex) {
			Log.w("Share: failed to resolve a phone: ", ex);
			resolving = false;
			main.post(this::resolveNext);
		}
	}

	private void report(NsdServiceInfo info) {
		Listener ls = listener;
		InetAddress host = info.getHost();
		if ((ls == null) || (host == null) || (info.getPort() <= 0)) return;

		Map<String, byte[]> attrs = info.getAttributes();
		String id = attr(attrs, "id");
		if ((id == null) || id.equals(instanceId)) return;
		String name = attr(attrs, "n");
		ls.onPeerFound(new ShareManager.Peer(id, (name == null) ? info.getServiceName() : name,
				info.getServiceName(), host, info.getPort()));
	}

	@Nullable
	private static String attr(@Nullable Map<String, byte[]> attrs, String key) {
		byte[] v = (attrs == null) ? null : attrs.get(key);
		return (v == null) ? null : new String(v, UTF_8);
	}
}
