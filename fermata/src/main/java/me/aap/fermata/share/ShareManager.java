package me.aap.fermata.share;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.ShareReceiveCard;
import me.aap.fermata.ui.view.TopConfirm;
import me.aap.fermata.ui.view.TopPopup;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.log.Log;

/**
 * Sharing between phones on the same Wi-Fi: lists the phones nearby ({@link ShareDiscovery}),
 * listens for what they send, and runs the transfers either way. Process-wide, so a transfer goes
 * on when the Share window is closed or the app is in the background ({@link ShareService} keeps the
 * process alive and shows the progress notification).
 * <p>
 * The wire format is a plain TCP stream: a header ({@link ShareOffer} as JSON); the receiver's
 * answer (1 to accept, 0 to decline), asked of the person on the other phone; then the bytes of
 * each entry that has any, in order; and the receiver's final 1 once it has stored everything.
 * Either side stops by closing the connection.
 * <p>
 * State is kept on the main thread; the sockets are served by background threads that post back.
 */
public final class ShareManager {
	private static final String MAGIC = "ZRSHARE1";
	private static final int MAX_HEADER = 8 * 1024 * 1024;
	private static final int CONNECT_TIMEOUT = 8000;
	/** The sender waits this long for the person on the other phone to answer. */
	private static final int ANSWER_TIMEOUT = 75000;
	private static final int IO_TIMEOUT = 30000;
	private static final long ASK_SECONDS = 60;
	@Nullable
	private static ShareManager instance;

	public static final class Peer {
		public final String id;
		public final String name;
		final String serviceName;
		final InetAddress host;
		final int port;

		Peer(String id, String name, String serviceName, InetAddress host, int port) {
			this.id = id;
			this.name = name;
			this.serviceName = serviceName;
			this.host = host;
			this.port = port;
		}
	}

	public enum State {WAITING, TRANSFERRING, SAVING, DONE, FAILED, CANCELLED, DECLINED}

	public static final class Transfer {
		private static int counter;
		public final int id = ++counter;
		public final boolean sending;
		/** The other phone's name. */
		public final String peerName;
		public final ShareOffer offer;
		public volatile State state = State.WAITING;
		public volatile long done;
		public volatile long total;
		@Nullable
		public volatile String error;
		@Nullable
		public volatile String current;
		/** What the receiver made of it, once saved; for the done message. */
		@Nullable
		public volatile String result;
		volatile boolean cancelled;
		@Nullable
		volatile Socket socket;
		@Nullable
		volatile View askCard;

		Transfer(boolean sending, String peerName, ShareOffer offer) {
			this.sending = sending;
			this.peerName = peerName;
			this.offer = offer;
			this.total = offer.totalBytes();
		}

		public boolean isActive() {
			return (state == State.WAITING) || (state == State.TRANSFERRING) || (state == State.SAVING);
		}

		/** Stops the transfer; no effect once it is saving or over. */
		public void cancel() {
			if (!isActive() || (state == State.SAVING)) return;
			cancelled = true;
			closeQuietly(socket);
			get().main.post(() -> {
				View c = askCard;
				if (c != null) TopPopup.dismiss(c);
				get().changed(true);
			});
		}
	}

	public interface Listener {
		default void onPeersChanged() {
		}

		default void onTransfersChanged() {
		}
	}

	private final Context ctx = FermataApplication.get();
	private final Handler main = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "share");
		t.setDaemon(true);
		return t;
	});
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final List<Peer> peers = new ArrayList<>();
	private final List<Transfer> transfers = new ArrayList<>();
	private final ShareDiscovery discovery = new ShareDiscovery(ctx);
	private int uiCount;
	/** Screens (the phone's, the car's) currently in front of the user. */
	private int visibleCount;
	private int discoverers;
	@Nullable
	private ServerSocket server;
	private long lastNotify;

	private ShareManager() {
		SharePrefs.store().addBroadcastListener((store, prefs) -> {
			boolean name = prefs.contains(SharePrefs.DEVICE_NAME);
			if (!name && !prefs.contains(SharePrefs.RECEIVE)) return;
			main.post(() -> {
				// A new name is announced afresh.
				if (name) discovery.unregister();
				updateReceiving();
			});
		});
	}

	/** Main thread only. */
	@NonNull
	public static ShareManager get() {
		if (instance == null) instance = new ShareManager();
		return instance;
	}

	public void addListener(Listener l) {
		listeners.add(l);
	}

	public void removeListener(Listener l) {
		listeners.remove(l);
	}

	public List<Peer> getPeers() {
		return peers;
	}

	public List<Transfer> getTransfers() {
		return transfers;
	}

	@Nullable
	public Transfer getActiveTransferTo(Peer p, ShareOffer offer) {
		for (Transfer t : transfers) {
			if (t.sending && t.isActive() && (t.offer == offer) && t.peerName.equals(p.name)) return t;
		}
		return null;
	}

	public boolean isBusy() {
		for (Transfer t : transfers) if (t.isActive()) return true;
		return false;
	}

	public int getActiveCount() {
		int n = 0;
		for (Transfer t : transfers) if (t.isActive()) n++;
		return n;
	}

	// ---- Being found ----

	/** The app's screen came up: from now on this phone can be found, if Settings allow it. */
	public void onUiCreated() {
		uiCount++;
		updateReceiving();
	}

	public void onUiVisible(boolean visible) {
		if (visible) visibleCount++;
		else if (visibleCount > 0) visibleCount--;
	}

	/** Whether the app is in front: what it says in its own cards needs no notification too. */
	public boolean isUiVisible() {
		return visibleCount > 0;
	}

	public void onUiDestroyed() {
		if (uiCount > 0) uiCount--;
		updateReceiving();
	}

	/** Also for a changed setting. */
	public void updateReceiving() {
		boolean want = (uiCount > 0) && SharePrefs.isReceiving();

		if (!want) {
			if (discovery.isRegistered()) discovery.unregister();
			closeServer();
			return;
		}

		if (server == null) startServer();
		ServerSocket s = server;
		if ((s != null) && !discovery.isRegistered()) discovery.register(s.getLocalPort(), SharePrefs.getDeviceName());
	}

	private void startServer() {
		try {
			ServerSocket s = new ServerSocket(0);
			server = s;
			Thread t = new Thread(() -> acceptLoop(s), "share-accept");
			t.setDaemon(true);
			t.start();
		} catch (IOException ex) {
			Log.e(ex, "Share: failed to listen");
			server = null;
		}
	}

	private void closeServer() {
		ServerSocket s = server;
		server = null;
		if (s == null) return;
		try {
			s.close();
		} catch (IOException ignore) {
			// Gone either way.
		}
	}

	private void acceptLoop(ServerSocket s) {
		while (!s.isClosed()) {
			try {
				Socket c = s.accept();
				executor.execute(() -> receive(c));
			} catch (IOException ex) {
				if (!s.isClosed()) Log.w("Share: accept failed: ", ex);
				return;
			}
		}
	}

	// ---- Finding others ----

	/** The Share window opened: look for phones until {@link #stopDiscovery}. */
	public void startDiscovery() {
		if (discoverers++ > 0) return;
		peers.clear();
		discovery.startDiscovery(new ShareDiscovery.Listener() {
			@Override
			public void onPeerFound(Peer peer) {
				for (int i = 0; i < peers.size(); i++) {
					if (peers.get(i).id.equals(peer.id)) {
						peers.set(i, peer);
						notifyPeers();
						return;
					}
				}
				peers.add(peer);
				notifyPeers();
			}

			@Override
			public void onPeerLost(String serviceName) {
				if (peers.removeIf(p -> p.serviceName.equals(serviceName))) notifyPeers();
			}
		});
	}

	public void stopDiscovery() {
		if ((discoverers == 0) || (--discoverers > 0)) return;
		discovery.stopDiscovery();
	}

	private void notifyPeers() {
		for (Listener l : listeners) l.onPeersChanged();
	}

	// ---- Sending ----

	/** Offers {@code offer} to {@code peer}; the transfer runs in the background. */
	@NonNull
	public Transfer send(Peer peer, ShareOffer offer) {
		Transfer t = new Transfer(true, peer.name, offer);
		transfers.add(t);
		trim();
		changed(true);
		ShareService.update(ctx);
		executor.execute(() -> runSend(t, peer));
		return t;
	}

	private void runSend(Transfer t, Peer peer) {
		try (Socket s = new Socket()) {
			t.socket = s;
			s.connect(new InetSocketAddress(peer.host, peer.port), CONNECT_TIMEOUT);
			s.setSoTimeout(ANSWER_TIMEOUT);
			DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536));
			DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
			byte[] head = t.offer.toJson().getBytes(UTF_8);
			out.writeUTF(MAGIC);
			out.writeInt(head.length);
			out.write(head);
			out.flush();

			if (in.readByte() != 1) {
				finish(t, t.cancelled ? State.CANCELLED : State.DECLINED, null);
				return;
			}

			t.state = State.TRANSFERRING;
			changed(true);
			s.setSoTimeout(IO_TIMEOUT);
			byte[] buf = new byte[64 * 1024];

			for (ShareEntry e : t.offer.entries) {
				if (e.size <= 0) continue;
				t.current = e.name;
				try (InputStream is = open(e)) {
					long left = e.size;
					while (left > 0) {
						if (t.cancelled) throw new IOException("Cancelled");
						int n = is.read(buf, 0, (int) Math.min(buf.length, left));
						if (n < 0) throw new IOException("“" + e.name + "” is shorter than it was");
						out.write(buf, 0, n);
						left -= n;
						t.done += n;
						changed(false);
					}
				}
			}

			out.flush();
			t.current = null;
			// The receiver says when it has everything stored.
			t.state = State.SAVING;
			changed(true);
			s.setSoTimeout(ANSWER_TIMEOUT);
			finish(t, (in.readByte() == 1) ? State.DONE : State.FAILED,
					null);
		} catch (Exception ex) {
			if (t.cancelled) {
				finish(t, State.CANCELLED, null);
			} else {
				Log.e(ex, "Share: sending failed");
				finish(t, State.FAILED, (ex.getMessage() != null) ? ex.getMessage() : ex.toString());
			}
		}
	}

	private InputStream open(ShareEntry e) throws IOException {
		File f = (e.file != null) ? e.file : e.downloadFile;
		if (f != null) return new FileInputStream(f);
		Uri u = e.uri;
		InputStream is = (u == null) ? null : ctx.getContentResolver().openInputStream(u);
		if (is == null) throw new IOException("Cannot read " + e.name);
		return is;
	}

	// ---- Receiving ----

	private void receive(Socket s) {
		Transfer t = null;
		File dir = null;

		try {
			s.setSoTimeout(IO_TIMEOUT);
			DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
			DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
			if (!MAGIC.equals(in.readUTF())) return;
			int len = in.readInt();
			if ((len <= 0) || (len > MAX_HEADER)) return;
			byte[] head = new byte[len];
			in.readFully(head);
			ShareOffer offer = ShareOffer.fromJson(new String(head, UTF_8));
			if (offer == null) return;

			if (!SharePrefs.isReceiving() || offer.entries.isEmpty()) {
				out.writeByte(0);
				out.flush();
				return;
			}

			Transfer tr = t = new Transfer(false, offer.sender, offer);
			tr.socket = s;
			main.post(() -> {
				transfers.add(tr);
				trim();
				changed(true);
			});

			if (!ask(tr)) {
				out.writeByte(0);
				out.flush();
				finish(tr, tr.cancelled ? State.CANCELLED : State.DECLINED, null);
				return;
			}

			out.writeByte(1);
			out.flush();
			tr.state = State.TRANSFERRING;
			main.post(() -> {
				ShareService.update(ctx);
				changed(true);
				MainActivityDelegate a = MainActivityDelegate.getUiDelegate();
				if (a != null) ShareReceiveCard.show(a, tr);
			});

			dir = ShareImporter.folderFor(ctx, offer.title);
			Map<Integer, File> received = new HashMap<>();
			byte[] buf = new byte[64 * 1024];

			for (int i = 0; i < offer.entries.size(); i++) {
				ShareEntry e = offer.entries.get(i);
				if (e.size <= 0) continue;
				tr.current = e.name;
				File part = new File(dir, ".part" + i);
				try (OutputStream os = new FileOutputStream(part)) {
					long left = e.size;
					while (left > 0) {
						if (tr.cancelled) throw new IOException("Cancelled");
						int n = in.read(buf, 0, (int) Math.min(buf.length, left));
						if (n < 0) throw new IOException("Connection closed");
						os.write(buf, 0, n);
						left -= n;
						tr.done += n;
						changed(false);
					}
				} catch (IOException ex) {
					//noinspection ResultOfMethodCallIgnored
					part.delete();
					throw ex;
				}
				received.put(i, part);
			}

			tr.current = null;
			tr.state = State.SAVING;
			changed(true);
			File saveDir = dir;
			ShareImporter.save(tr, offer, saveDir, received, (ok, message) -> {
				tr.result = message;
				// The sockets are not for the main thread, where this is called.
				executor.execute(() -> {
					try {
						out.writeByte(ok ? 1 : 0);
						out.flush();
					} catch (IOException ex) {
						Log.w("Share: could not confirm: ", ex);
					} finally {
						closeQuietly(s);
					}
					finish(tr, ok ? State.DONE : State.FAILED, ok ? null : message);
				});
			});
		} catch (Exception ex) {
			if (t != null) {
				if (dir != null) ShareImporter.clearParts(dir);
				if (t.cancelled) finish(t, State.CANCELLED, null);
				else {
					Log.e(ex, "Share: receiving failed");
					finish(t, State.FAILED, (ex.getMessage() != null) ? ex.getMessage() : ex.toString());
				}
			} else {
				DiagnosticLog.log("SHARE", "bad incoming connection:", ex);
			}
			closeQuietly(s);
		}
	}

	/** Asks the person on this phone; false if they decline, or do not answer in a minute. */
	private boolean ask(Transfer t) {
		CompletableFuture<Boolean> answer = new CompletableFuture<>();
		main.post(() -> {
			ShareOffer o = t.offer;
			Context c = ctx;
			String what = c.getResources().getQuantityString(R.plurals.share_items, o.entries.size(),
					o.entries.size());
			long bytes = o.totalBytes();
			if (bytes > 0) what += ", " + android.text.format.Formatter.formatShortFileSize(c, bytes);
			String text = c.getString(R.string.share_incoming_text, o.sender, o.title, what);
			ShareService.notifyIncoming(c, o.sender, o.title);
			MainActivityDelegate a = MainActivityDelegate.getUiDelegate();

			if (a == null) {
				answer.complete(false);
				return;
			}

			t.askCard = TopConfirm.show(a, R.drawable.share, c.getString(R.string.share_incoming_title),
					text, c.getString(R.string.share_accept), c.getString(R.string.share_decline),
					() -> answer.complete(true), () -> answer.complete(false));
		});

		try {
			return answer.get(ASK_SECONDS, TimeUnit.SECONDS) && !t.cancelled;
		} catch (TimeoutException ex) {
			return false;
		} catch (Exception ex) {
			return false;
		} finally {
			main.post(() -> {
				View card = t.askCard;
				if (card != null) TopPopup.dismiss(card);
				ShareService.clearIncoming(ctx);
			});
		}
	}

	// ---- State ----

	private void finish(Transfer t, State state, @Nullable String error) {
		t.error = error;
		t.state = state;
		main.post(() -> {
			changed(true);
			ShareService.onTransferEnded(ctx, t);
		});
	}

	/** At most a few of the finished ones are kept for the Share window to show. */
	private void trim() {
		int ended = 0;
		for (int i = transfers.size() - 1; i >= 0; i--) {
			Transfer t = transfers.get(i);
			if (t.isActive()) continue;
			if (++ended > 6) transfers.remove(i);
		}
	}

	/** Progress is told at most every quarter second; state changes at once. */
	void changed(boolean now) {
		long t = SystemClock.elapsedRealtime();
		if (!now && (t - lastNotify < 250)) return;
		lastNotify = t;
		main.post(() -> {
			for (Listener l : listeners) l.onTransfersChanged();
		});
	}

	private static void closeQuietly(@Nullable Socket s) {
		if (s == null) return;
		try {
			s.close();
		} catch (IOException ignore) {
			// Gone either way.
		}
	}
}
