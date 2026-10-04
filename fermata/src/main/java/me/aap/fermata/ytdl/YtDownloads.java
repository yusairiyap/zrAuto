package me.aap.fermata.ytdl;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.log.Log;

/**
 * Keeps YouTube videos on the phone so they play with no connection: a queue of downloads that
 * runs one video at a time in the background (see {@link YtDownloadService}, which shows the
 * notification and keeps the process alive), can be paused and resumed -- by the notification, or
 * from the menus -- and carries on where it stopped, since what's already on disk is only ever
 * appended to.
 * <p>
 * A download is either the audio alone ({@code .m4a}) or the picture and sound ({@code .mp4},
 * fetched as two streams and joined on the phone). What's finished is listed in {@link #isDownloaded}
 * and played by {@link YtOffline}.
 */
public final class YtDownloads {
	public static final String ID_PREFIX = "youtube:";
	/** Googlevideo slows a single long request down; chunks keep it fast and make resuming trivial. */
	private static final long CHUNK = 8L * 1024 * 1024;
	private static final int MAX_ATTEMPTS = 4;
	private static final int STOP_NONE = 0;
	private static final int STOP_PAUSE = 1;
	private static final int STOP_CANCEL = 2;
	private static final YtDownloads instance = new YtDownloads();

	public enum State {QUEUED, DOWNLOADING, PAUSED, DONE, FAILED}

	public static final class Entry {
		public final String videoId;
		@Nullable
		public volatile String title;
		@Nullable
		public volatile String artist;
		public volatile long durationMs;
		public final boolean video;
		public volatile State state = State.QUEUED;
		/** Downloaded so far, of {@link #total} (0 until the streams are known). */
		public volatile long bytes;
		public volatile long total;
		/** Bytes per second, smoothed; 0 when not downloading. */
		public volatile long speed;
		@Nullable
		public volatile String error;
		@Nullable
		volatile String fileName;

		Entry(String videoId, boolean video) {
			this.videoId = videoId;
			this.video = video;
		}

		public String getDisplayTitle() {
			String t = title;
			return ((t == null) || t.isEmpty()) ? videoId : t;
		}
	}

	public static final class Request {
		final String videoId;
		@Nullable
		final String title;
		final boolean video;

		public Request(String videoId, @Nullable String title, boolean video) {
			this.videoId = videoId;
			this.title = title;
			this.video = video;
		}
	}

	/** Called on the main thread whenever a download's state or progress changes. */
	public interface Listener {
		void onDownloadsChanged();
	}

	private final Object lock = new Object();
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final Set<String> done = ConcurrentHashMap.newKeySet();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final Handler main = new Handler(Looper.getMainLooper());
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "yt-download");
		t.setDaemon(true);
		return t;
	});
	private boolean loaded;
	private boolean workerRunning;
	@Nullable
	private Entry current;
	private volatile int stop = STOP_NONE;
	private long lastNotify;
	/** Of the downloads queued since the queue was last empty: how many, and how they ended. */
	private int batchTotal;
	private int batchDone;
	private int batchFailed;

	private YtDownloads() {
	}

	@NonNull
	public static YtDownloads get() {
		return instance;
	}

	/** The YouTube video id an item plays, or null if it isn't a YouTube video. */
	@Nullable
	public static String videoIdOf(@Nullable PlayableItem i) {
		if (i == null) return null;
		String id = i.getOrigId();
		return ((id != null) && id.startsWith(ID_PREFIX) && (id.length() > ID_PREFIX.length())) ?
				id.substring(ID_PREFIX.length()) : null;
	}

	// ---------------------------------------------------------------------------------------------
	// Queries

	/** Whether the video is on the phone, complete. Cheap: safe to call while drawing. */
	public boolean isDownloaded(@Nullable String videoId) {
		if (videoId == null) return false;
		ensureLoaded();
		return done.contains(videoId);
	}

	/** The finished download's file, or null if there's none. */
	@Nullable
	public File getFile(@Nullable String videoId) {
		if (videoId == null) return null;
		synchronized (lock) {
			ensureLoadedLocked();
			Entry e = entries.get(videoId);
			if ((e == null) || (e.state != State.DONE) || (e.fileName == null)) return null;
			File f = new File(dir(), e.fileName);
			return f.isFile() ? f : null;
		}
	}

	@Nullable
	public Entry getEntry(@Nullable String videoId) {
		if (videoId == null) return null;
		synchronized (lock) {
			ensureLoadedLocked();
			return entries.get(videoId);
		}
	}

	/** The queue and what's finished, in the order they were added. */
	public List<Entry> snapshot() {
		synchronized (lock) {
			ensureLoadedLocked();
			return new ArrayList<>(entries.values());
		}
	}

	/** The videos that are on the phone, complete, in the order they were added. */
	public List<Entry> getDownloaded() {
		List<Entry> l = new ArrayList<>();
		for (Entry e : snapshot()) {
			if (e.state == State.DONE) l.add(e);
		}
		return l;
	}

	/** Whether something is downloading or waiting its turn. */
	public boolean isBusy() {
		synchronized (lock) {
			ensureLoadedLocked();
			for (Entry e : entries.values()) {
				if ((e.state == State.QUEUED) || (e.state == State.DOWNLOADING)) return true;
			}
			return false;
		}
	}

	/** Whether something was paused (or failed) and could carry on. */
	public boolean hasResumable() {
		synchronized (lock) {
			ensureLoadedLocked();
			for (Entry e : entries.values()) {
				if ((e.state == State.PAUSED) || (e.state == State.FAILED)) return true;
			}
			return false;
		}
	}

	public int getBatchTotal() {
		return batchTotal;
	}

	public int getBatchDone() {
		return batchDone;
	}

	public int getBatchFailed() {
		return batchFailed;
	}

	/** The video being fetched right now, if any. */
	@Nullable
	public Entry getCurrent() {
		return current;
	}

	public void addListener(Listener l) {
		listeners.add(l);
	}

	public void removeListener(Listener l) {
		listeners.remove(l);
	}

	// ---------------------------------------------------------------------------------------------
	// Commands

	/** Queues the videos; those already on the phone are skipped. Returns how many were added. */
	public int enqueue(Collection<Request> requests) {
		Context ctx = FermataApplication.get();
		int added = 0;
		synchronized (lock) {
			ensureLoadedLocked();
			boolean wasBusy = isBusyLocked();
			for (Request r : requests) {
				Entry old = entries.get(r.videoId);
				if (old != null) {
					if ((old.state == State.QUEUED) || (old.state == State.DOWNLOADING)) continue;
					if (old.video == r.video) {
						if (old.state == State.DONE) continue;
						// Paused or failed: carries on from what's already on disk.
						old.state = State.QUEUED;
						old.error = null;
						added++;
						continue;
					}
					removeLocked(old);
				}
				Entry e = new Entry(r.videoId, r.video);
				e.title = r.title;
				entries.put(r.videoId, e);
				added++;
			}
			if (added == 0) return 0;
			if (!wasBusy) resetBatchLocked();
			batchTotal += added;
			saveLocked();
		}
		changed(true);
		YtDownloadService.start(ctx);
		kick();
		return added;
	}

	/** Pauses everything that is downloading or waiting; "resume" carries on. */
	public void pauseAll() {
		synchronized (lock) {
			for (Entry e : entries.values()) {
				if (e.state == State.QUEUED) e.state = State.PAUSED;
			}
			if (current != null) stop = STOP_PAUSE;
			saveLocked();
		}
		changed(true);
	}

	public void pause(String videoId) {
		synchronized (lock) {
			Entry e = entries.get(videoId);
			if (e == null) return;
			if (e == current) stop = STOP_PAUSE;
			else if (e.state == State.QUEUED) e.state = State.PAUSED;
			saveLocked();
		}
		changed(true);
	}

	/** Puts everything paused or failed back in the queue. */
	public void resumeAll() {
		int n = 0;
		synchronized (lock) {
			boolean wasBusy = isBusyLocked();
			if (!wasBusy) resetBatchLocked();
			for (Entry e : entries.values()) {
				if ((e.state == State.PAUSED) || (e.state == State.FAILED)) {
					e.state = State.QUEUED;
					e.error = null;
					n++;
				}
			}
			if (n == 0) return;
			batchTotal += n;
			saveLocked();
		}
		changed(true);
		YtDownloadService.start(FermataApplication.get());
		kick();
	}

	public void resume(String videoId) {
		synchronized (lock) {
			Entry e = entries.get(videoId);
			if ((e == null) || ((e.state != State.PAUSED) && (e.state != State.FAILED))) return;
			if (!isBusyLocked()) resetBatchLocked();
			e.state = State.QUEUED;
			e.error = null;
			batchTotal++;
			saveLocked();
		}
		changed(true);
		YtDownloadService.start(FermataApplication.get());
		kick();
	}

	/** Stops and forgets everything that isn't finished. */
	public void cancelAll() {
		synchronized (lock) {
			if (current != null) stop = STOP_CANCEL;
			for (Entry e : new ArrayList<>(entries.values())) {
				if ((e.state != State.DONE) && (e != current)) removeLocked(e);
			}
			resetBatchLocked();
			saveLocked();
		}
		changed(true);
	}

	/** Cancels a download that isn't finished, or deletes the finished one's file. */
	public void remove(String videoId) {
		synchronized (lock) {
			Entry e = entries.get(videoId);
			if (e == null) return;
			if (e == current) stop = STOP_CANCEL;
			else removeLocked(e);
			saveLocked();
		}
		changed(true);
	}

	// ---------------------------------------------------------------------------------------------
	// Listeners

	private void changed(boolean now) {
		long t = SystemClock.elapsedRealtime();
		if (!now && (t - lastNotify < 500)) return;
		lastNotify = t;
		main.post(() -> {
			for (Listener l : listeners) l.onDownloadsChanged();
		});
	}

	// ---------------------------------------------------------------------------------------------
	// The queue

	private void kick() {
		synchronized (lock) {
			if (workerRunning) return;
			workerRunning = true;
		}
		worker.execute(this::runQueue);
	}

	private void runQueue() {
		try {
			for (; ; ) {
				Entry e;
				synchronized (lock) {
					e = null;
					for (Entry c : entries.values()) {
						if (c.state == State.QUEUED) {
							e = c;
							break;
						}
					}
					if (e == null) {
						workerRunning = false;
						current = null;
						break;
					}
					current = e;
					stop = STOP_NONE;
					e.state = State.DOWNLOADING;
					e.error = null;
				}
				changed(true);
				process(e);
			}
		} catch (Throwable ex) {
			Log.e(ex, "The YouTube download queue crashed");
			synchronized (lock) {
				workerRunning = false;
				current = null;
			}
		}
		changed(true);
	}

	private void process(Entry e) {
		DiagnosticLog.log("YTDL", "start", "id=" + e.videoId, "video=" + e.video);
		IOException failure = null;

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				download(e);
				finish(e, State.DONE, null);
				return;
			} catch (StopException ex) {
				finish(e, (ex.reason == STOP_CANCEL) ? null : State.PAUSED, null);
				return;
			} catch (IOException ex) {
				failure = ex;
				Log.w(ex, "YouTube download failed (attempt ", attempt, "): ", e.videoId);
				if (!sleepUnlessStopped(attempt * 3000L)) {
					finish(e, (stop == STOP_CANCEL) ? null : State.PAUSED, null);
					return;
				}
			}
		}

		finish(e, State.FAILED, (failure != null) ? failure.getMessage() : "Download failed");
	}

	/** @param state the new state, or null to forget the video */
	private void finish(Entry e, @Nullable State state, @Nullable String error) {
		synchronized (lock) {
			e.speed = 0;
			if (state == null) {
				removeLocked(e);
			} else {
				e.state = state;
				e.error = error;
				if (state == State.DONE) {
					e.bytes = e.total;
					done.add(e.videoId);
					batchDone++;
				} else if (state == State.FAILED) {
					batchFailed++;
				}
			}
			current = null;
			stop = STOP_NONE;
			saveLocked();
		}
		DiagnosticLog.log("YTDL", "end", "id=" + e.videoId, "state=" + state, "error=" + error);
		changed(true);
	}

	private boolean sleepUnlessStopped(long ms) {
		long end = SystemClock.elapsedRealtime() + ms;
		while (SystemClock.elapsedRealtime() < end) {
			if (stop != STOP_NONE) return false;
			try {
				Thread.sleep(250);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return stop == STOP_NONE;
	}

	// ---------------------------------------------------------------------------------------------
	// Fetching

	private static final class StopException extends IOException {
		final int reason;

		StopException(int reason) {
			super("stopped");
			this.reason = reason;
		}
	}

	/** The stream's address has expired (they last a few hours): ask YouTube for a new one. */
	private static final class ExpiredException extends IOException {
		ExpiredException() {
			super("The stream address expired");
		}
	}

	private void download(Entry e) throws IOException {
		File dir = dir();
		File audioPart = new File(dir, e.videoId + ".audio.part");
		File videoPart = new File(dir, e.videoId + ".video.part");
		String outName = e.videoId + (e.video ? ".mp4" : ".m4a");

		for (int expired = 0; ; expired++) {
			checkStop();
			YtStreamResolver.Result r = YtStreamResolver.resolve(e.videoId, e.video, DownloadsAddon.getMaxVideoHeight());
			if ((e.title == null) || e.title.isEmpty()) e.title = r.title;
			if (e.artist == null) e.artist = r.author;
			if (e.durationMs <= 0) e.durationMs = r.durationMs;
			e.total = r.audio.length + ((r.video != null) ? r.video.length : 0);
			changed(true);

			try {
				fetch(e, r.audio, audioPart, 0);
				if (r.video != null) fetch(e, r.video, videoPart, r.audio.length);
			} catch (ExpiredException ex) {
				if (expired >= 2) throw ex;
				continue;
			}
			break;
		}

		File out = new File(dir, outName);
		File tmp = new File(dir, outName + ".tmp");
		try {
			if (e.video) {
				mux(videoPart, audioPart, tmp);
				audioPart.delete();
				videoPart.delete();
			} else if (!audioPart.renameTo(tmp)) {
				throw new IOException("Failed to store the download");
			}
			Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} finally {
			tmp.delete();
		}
		e.fileName = outName;
	}

	private void checkStop() throws StopException {
		int s = stop;
		if (s != STOP_NONE) throw new StopException(s);
	}

	private void fetch(Entry e, YtStreamResolver.Stream s, File part, long base) throws IOException {
		long have = part.length();
		if (have > s.length) {
			part.delete();
			have = 0;
		}

		byte[] buf = new byte[32 * 1024];
		long winStart = SystemClock.elapsedRealtime();
		long winBytes = 0;

		while (have < s.length) {
			checkStop();
			long before = have;
			long end = Math.min(have + CHUNK - 1, s.length - 1);
			HttpURLConnection c = (HttpURLConnection) new URL(s.url).openConnection();
			try {
				c.setConnectTimeout(15_000);
				c.setReadTimeout(20_000);
				YtStreamResolver.applyStreamHeaders(c);
				c.setRequestProperty("Range", "bytes=" + have + '-' + end);
				int code = c.getResponseCode();
				if ((code == 403) || (code == 410)) throw new ExpiredException();
				// 200: the server ignored the range and sends it all, fine from the start.
				if ((code != 206) && !((code == 200) && (have == 0))) {
					throw new IOException("YouTube answered HTTP " + code);
				}

				try (InputStream in = c.getInputStream();
						 FileOutputStream out = new FileOutputStream(part, true)) {
					for (int n; (n = in.read(buf)) != -1; ) {
						checkStop();
						out.write(buf, 0, n);
						have += n;
						e.bytes = base + have;
						winBytes += n;
						long now = SystemClock.elapsedRealtime();
						if (now - winStart >= 1000) {
							long inst = winBytes * 1000 / (now - winStart);
							e.speed = (e.speed == 0) ? inst : (long) (e.speed * 0.6 + inst * 0.4);
							winStart = now;
							winBytes = 0;
						}
						changed(false);
					}
				}
			} finally {
				c.disconnect();
			}

			if (have == before) throw new IOException("The download made no progress");
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Joining picture and sound

	private static void mux(File video, File audio, File out) throws IOException {
		MediaExtractor vx = new MediaExtractor();
		MediaExtractor ax = new MediaExtractor();
		MediaMuxer mm = null;
		boolean started = false;

		try {
			vx.setDataSource(video.getPath());
			ax.setDataSource(audio.getPath());
			int vt = findTrack(vx, "video/");
			int at = findTrack(ax, "audio/");
			if ((vt < 0) || (at < 0)) throw new IOException("The download has no picture or no sound");

			mm = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			int mv = mm.addTrack(vx.getTrackFormat(vt));
			int ma = mm.addTrack(ax.getTrackFormat(at));
			vx.selectTrack(vt);
			ax.selectTrack(at);
			mm.start();
			started = true;

			ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
			MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
			// Whichever stream is behind goes next, so the two stay interleaved in the file.
			for (; ; ) {
				long vTime = vx.getSampleTime();
				long aTime = ax.getSampleTime();
				if ((vTime < 0) && (aTime < 0)) break;
				boolean takeVideo = (aTime < 0) || ((vTime >= 0) && (vTime <= aTime));
				MediaExtractor ex = takeVideo ? vx : ax;
				buf.clear();
				int n = ex.readSampleData(buf, 0);
				if (n < 0) {
					ex.unselectTrack(takeVideo ? vt : at);
					continue;
				}
				info.set(0, n, ex.getSampleTime(), ex.getSampleFlags());
				mm.writeSampleData(takeVideo ? mv : ma, buf, info);
				ex.advance();
			}
		} catch (RuntimeException ex) {
			throw new IOException("Failed to join the picture and sound", ex);
		} finally {
			if (mm != null) {
				try {
					if (started) mm.stop();
				} catch (RuntimeException ignored) {
				}
				mm.release();
			}
			vx.release();
			ax.release();
		}
	}

	private static int findTrack(MediaExtractor x, String mimePrefix) {
		for (int i = 0; i < x.getTrackCount(); i++) {
			String mime = x.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
			if ((mime != null) && mime.startsWith(mimePrefix)) return i;
		}
		return -1;
	}

	// ---------------------------------------------------------------------------------------------
	// Storage

	private static File dir() {
		Context ctx = FermataApplication.get();
		File base = ctx.getExternalFilesDir(null);
		if (base == null) base = ctx.getFilesDir();
		File d = new File(base, "youtube");
		if (!d.isDirectory() && !d.mkdirs()) Log.w("Failed to create ", d);
		return d;
	}

	private static File indexFile() {
		return new File(dir(), "downloads.json");
	}

	private void ensureLoaded() {
		synchronized (lock) {
			ensureLoadedLocked();
		}
	}

	private void ensureLoadedLocked() {
		if (loaded) return;
		loaded = true;

		try {
			File f = indexFile();
			if (!f.isFile()) return;
			String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			JSONArray a = new JSONArray(s);

			for (int i = 0; i < a.length(); i++) {
				JSONObject o = a.getJSONObject(i);
				Entry e = new Entry(o.getString("id"), o.optBoolean("video"));
				e.title = o.optString("title", null);
				e.artist = o.optString("artist", null);
				e.durationMs = o.optLong("dur");
				e.bytes = o.optLong("bytes");
				e.total = o.optLong("total");
				e.fileName = o.optString("file", null);
				State st = State.valueOf(o.optString("state", State.PAUSED.name()));
				// Whatever was running when the app died picks up from what's on disk once resumed.
				if ((st == State.DOWNLOADING) || (st == State.QUEUED)) st = State.PAUSED;
				if (st == State.DONE) {
					if ((e.fileName == null) || !new File(dir(), e.fileName).isFile()) continue;
					done.add(e.videoId);
				}
				e.state = st;
				entries.put(e.videoId, e);
			}
		} catch (Exception ex) {
			Log.e(ex, "Failed to read the YouTube downloads index");
		}
	}

	private void saveLocked() {
		try {
			JSONArray a = new JSONArray();
			for (Entry e : entries.values()) {
				JSONObject o = new JSONObject();
				o.put("id", e.videoId);
				o.put("video", e.video);
				o.put("title", e.title);
				o.put("artist", e.artist);
				o.put("dur", e.durationMs);
				o.put("bytes", e.bytes);
				o.put("total", e.total);
				o.put("file", e.fileName);
				o.put("state", e.state.name());
				a.put(o);
			}
			File f = indexFile();
			File tmp = new File(f.getPath() + ".tmp");
			Files.write(tmp.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
			Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception ex) {
			Log.e(ex, "Failed to save the YouTube downloads index");
		}
	}

	private void removeLocked(Entry e) {
		entries.remove(e.videoId);
		done.remove(e.videoId);
		File d = dir();
		new File(d, e.videoId + ".audio.part").delete();
		new File(d, e.videoId + ".video.part").delete();
		if (e.fileName != null) new File(d, e.fileName).delete();
	}

	private boolean isBusyLocked() {
		for (Entry e : entries.values()) {
			if ((e.state == State.QUEUED) || (e.state == State.DOWNLOADING)) return true;
		}
		return false;
	}

	private void resetBatchLocked() {
		batchTotal = 0;
		batchDone = 0;
		batchFailed = 0;
	}

	/** Called by the service once it has told the user how the batch went. */
	void batchReported() {
		synchronized (lock) {
			if (!isBusyLocked()) resetBatchLocked();
		}
	}

	/** The file as a URI the player can open. */
	@Nullable
	public Uri getUri(@Nullable String videoId) {
		File f = getFile(videoId);
		return (f == null) ? null : Uri.fromFile(f);
	}
}
