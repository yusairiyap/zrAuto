package me.aap.fermata.ytdl;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
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
		/** The tallest picture it was asked for, in lines; 0 for audio only. */
		public final int height;
		public volatile State state = State.QUEUED;
		/** Downloaded so far, of {@link #total} (0 until the streams are known). */
		public volatile long bytes;
		public volatile long total;
		/** The picture's actual height once known (a refused stream steps down to a lower one); 0 before. */
		public volatile int gotHeight;
		/** Bytes per second, smoothed; 0 when not downloading. */
		public volatile long speed;
		@Nullable
		public volatile String error;
		@Nullable
		volatile String fileName;

		Entry(String videoId, int height) {
			this.videoId = videoId;
			this.height = height;
			this.video = height > 0;
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
		final int height;

		/** @param height the tallest picture to take, in lines; 0 for the audio alone */
		public Request(String videoId, @Nullable String title, int height) {
			this.videoId = videoId;
			this.title = title;
			this.height = height;
		}
	}

	/** Called on the main thread whenever a download's state or progress changes. */
	public interface Listener {
		void onDownloadsChanged();
	}

	private final Object lock = new Object();
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final Set<String> done = ConcurrentHashMap.newKeySet();
	// Of those, the ones with a picture: read without the lock, as the list rows and the player ask.
	private final Set<String> doneVideo = ConcurrentHashMap.newKeySet();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final Handler main = new Handler(Looper.getMainLooper());
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "yt-download");
		t.setDaemon(true);
		return t;
	});
	private volatile boolean loaded;
	private boolean workerRunning;
	@Nullable
	private Entry current;
	private volatile int stop = STOP_NONE;
	private volatile long lastNotify;
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

	/** Whether the video is on the phone, complete, with its picture (not just the sound). */
	public boolean isVideoDownloaded(@Nullable String videoId) {
		if (videoId == null) return false;
		ensureLoaded();
		return doneVideo.contains(videoId);
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

	private final android.util.LruCache<String, android.graphics.Bitmap> frames =
			new android.util.LruCache<>(3);

	/** The video id of a YouTube thumbnail address ({@code .../vi/<id>/...}), or null. */
	@Nullable
	public static String thumbnailVideoId(@Nullable String url) {
		if (url == null) return null;
		int i = url.indexOf("/vi/");
		if (i < 0) return null;
		int from = i + 4;
		int to = url.indexOf('/', from);
		return (to > from) ? url.substring(from, to) : null;
	}

	/**
	 * A picture out of the downloaded video's file, for when its thumbnail can't be fetched (no
	 * connection): the cover of the media card, say. Null for sound only or no file. Blocks while
	 * reading the file: not for the main thread.
	 */
	@Nullable
	public android.graphics.Bitmap frameOf(@Nullable String videoId) {
		File f = getFile(videoId);
		Entry e = getEntry(videoId);
		if ((f == null) || (e == null) || !e.video) return null;
		android.graphics.Bitmap c = frames.get(videoId);
		if (c != null) return c;

		MediaMetadataRetriever r = new MediaMetadataRetriever();
		try {
			r.setDataSource(f.getPath());
			long at = Math.min(Math.max(e.durationMs, 0) / 5, 15000) * 1000;
			android.graphics.Bitmap bm =
					r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
			if (bm == null) return null;
			int max = 1024;
			if (bm.getWidth() > max) {
				bm = android.graphics.Bitmap.createScaledBitmap(bm, max, bm.getHeight() * max / bm.getWidth(),
						true);
			}
			frames.put(videoId, bm);
			return bm;
		} catch (Exception ex) {
			Log.w(ex, "Failed to read a picture out of ", f);
			return null;
		} finally {
			try {
				r.release();
			} catch (Exception ignored) {
			}
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

	/** Whether the video is queued, downloading, paused or failed -- anything but finished. */
	public boolean isActive(@Nullable String videoId) {
		Entry e = getEntry(videoId);
		return (e != null) && (e.state != State.DONE);
	}

	/** Whether the video is downloading or waiting its turn. */
	public boolean isRunning(@Nullable String videoId) {
		Entry e = getEntry(videoId);
		return (e != null) && ((e.state == State.QUEUED) || (e.state == State.DOWNLOADING));
	}

	/** Removes every finished download (the files too); what is still downloading carries on. */
	public void clearDownloaded() {
		synchronized (lock) {
			for (Entry e : new ArrayList<>(entries.values())) {
				if (e.state == State.DONE) removeLocked(e);
			}
			saveLocked();
		}
		changed(true);
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
					if (old.height == r.height) {
						if (old.state == State.DONE) continue;
						// Paused or failed: carries on from what's already on disk.
						old.state = State.QUEUED;
						old.error = null;
						added++;
						continue;
					}
					removeLocked(old);
				}
				Entry e = new Entry(r.videoId, r.height);
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
				try {
					process(e);
				} catch (Throwable ex) {
					// Whatever it was, this one is over -- never left "downloading" for good.
					Log.e(ex, "The YouTube download crashed: ", e.videoId);
					finish(e, State.FAILED, "Download failed");
				}
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
		DiagnosticLog.log("YTDL", "start", "id=" + e.videoId, "height=" + e.height);
		IOException failure = null;

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			long before = e.bytes;
			try {
				download(e);
				finish(e, State.DONE, null);
				return;
			} catch (StopException ex) {
				finish(e, (ex.reason == STOP_CANCEL) ? null : State.PAUSED, null);
				return;
			} catch (YtStreamResolver.BlockedException ex) {
				// Asking again straight away only digs the hole deeper.
				finish(e, State.FAILED, ex.getMessage());
				return;
			} catch (IOException ex) {
				failure = ex;
				Log.w(ex, "YouTube download failed (attempt ", attempt, "): ", e.videoId);
				// The attempts are for failing in a row: one that got further than the last starts anew.
				if (e.bytes > before) attempt = 0;
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
			// Cancelled just as it finished: the user asked for it gone.
			if ((state == State.DONE) && (stop == STOP_CANCEL)) state = null;
			if (state == null) {
				removeLocked(e);
			} else {
				e.state = state;
				e.error = error;
				if (state == State.DONE) {
					e.bytes = e.total;
					done.add(e.videoId);
					if (e.video) doneVideo.add(e.videoId);
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

	/** YouTube refused to send this stream (HTTP 403/410): another app, or another quality, may be let through. */
	static final class RefusedException extends IOException {
		RefusedException() {
			super("YouTube refused to send the video. Try a lower quality or try again later");
		}
	}

	private void download(Entry e) throws IOException {
		File dir = dir();
		File audioPart = new File(dir, e.videoId + ".audio.part");
		File videoPart = new File(dir, e.videoId + ".video.part");
		String outName = e.videoId + (e.video ? ".mp4" : ".m4a");

		int client = 0;
		int height = e.height;
		boolean combined = false;
		// A refused stream that had some of its bytes gets one more try as it is (the address may just
		// have expired or the network changed) before it is given up on.
		boolean retriedSame = false;

		resolving:
		for (; ; ) {
			checkStop();
			YtStreamResolver.Result r = YtStreamResolver.resolve(e.videoId, e.video, height, client);
			if ((e.title == null) || e.title.isEmpty()) e.title = r.title;
			if (e.artist == null) e.artist = r.author;
			if (e.durationMs <= 0) e.durationMs = r.durationMs;

			// What this answer offers, best first: the picture and the sound as two streams (any
			// quality), or one 360p file with both (the one YouTube still hands out freely).
			boolean adaptive = (r.audio != null) && (!e.video || (r.video != null));
			for (int pass = adaptive ? 0 : 1; pass < 2; pass++) {
				if ((pass == 1) && (r.combined == null)) break;
				combined = pass == 1;

				try {
					if (!combined) {
						e.total = r.audio.length + ((r.video != null) ? r.video.length : 0);
						e.gotHeight = (r.video != null) ? r.video.height : 0;
						changed(true);
						fetch(e, r, r.audio, audioPart, 0);
						if (r.video != null) fetch(e, r, r.video, videoPart, r.audio.length);
					} else {
						YtStreamResolver.Stream st = YtStreamResolver.withLength(r.combined, r.userAgent);
						e.total = st.length;
						e.gotHeight = e.video ? st.height : 0;
						changed(true);
						fetch(e, r, st, videoPart, 0);
					}
					break resolving;
				} catch (RefusedException ex) {
					DiagnosticLog.log("YTDL", "stream refused", "id=" + e.videoId, "client=" + r.client,
							"combined=" + combined, "height=" + e.gotHeight);
					if (!retriedSame && ((audioPart.length() > 0) || (videoPart.length() > 0))) {
						retriedSame = true;
						client = r.client;
						continue resolving;
					}
					retriedSame = false;
					// What was fetched belongs to the stream that is being given up on.
					deletePart(audioPart);
					deletePart(videoPart);
					e.bytes = 0;
				}
			}

			// The next app first (they are let through differently); with none left, a lower
			// picture, which is often fetchable when the high one isn't.
			if (r.client + 1 < YtStreamResolver.CLIENT_COUNT) {
				client = r.client + 1;
			} else if (e.video && (lowerHeight(height) > 0)) {
				height = lowerHeight(height);
				client = 0;
			} else {
				throw new RefusedException();
			}
		}

		File out = new File(dir, outName);
		File tmp = new File(dir, outName + ".tmp");
		try {
			if (combined) {
				if (e.video) {
					if (!videoPart.renameTo(tmp)) throw new IOException("Failed to store the download");
					deletePart(videoPart);
				} else {
					// Only the sound of the file with both, copied as it is.
					extractAudio(videoPart, tmp);
					deletePart(videoPart);
				}
			} else if (e.video) {
				mux(videoPart, audioPart, tmp);
				deletePart(audioPart);
				deletePart(videoPart);
			} else if (!audioPart.renameTo(tmp)) {
				throw new IOException("Failed to store the download");
			} else {
				deletePart(audioPart);
			}
			verifyPlayable(tmp, e);
			Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			// Parts that can't be joined would fail the same way every time: fetched again instead.
			if (!(ex instanceof StopException)) {
				deletePart(audioPart);
				deletePart(videoPart);
				e.bytes = 0;
			}
			throw ex;
		} finally {
			tmp.delete();
		}
		e.fileName = outName;
		saveThumbnail(e.videoId);
	}

	private File thumbFile(String videoId) {
		return new File(dir(), videoId + ".jpg");
	}

	/**
	 * Keeps the video's thumbnail beside the file, while there is a connection: its cover (the media
	 * card, the Music tab) then needs none. Best effort.
	 */
	private void saveThumbnail(String videoId) {
		for (String name : new String[]{"maxresdefault.jpg", "hqdefault.jpg"}) {
			java.net.HttpURLConnection c = null;
			File tmp = new File(dir(), videoId + ".jpg.tmp");
			try {
				c = (java.net.HttpURLConnection) new java.net.URL(
						"https://img.youtube.com/vi/" + videoId + "/" + name).openConnection();
				c.setConnectTimeout(8000);
				c.setReadTimeout(8000);
				if (c.getResponseCode() != 200) continue;
				try (java.io.InputStream in = c.getInputStream()) {
					Files.copy(in, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				if (tmp.length() > 0) {
					Files.move(tmp.toPath(), thumbFile(videoId).toPath(),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					return;
				}
			} catch (Exception ex) {
				Log.w(ex, "Failed to save the thumbnail of ", videoId);
			} finally {
				tmp.delete();
				if (c != null) c.disconnect();
			}
		}
	}

	/**
	 * The cover of a downloaded video without a connection: its saved thumbnail, else a picture out
	 * of the file. Blocks while reading: not for the main thread.
	 */
	@Nullable
	public android.graphics.Bitmap localArt(@Nullable String videoId) {
		if (videoId == null) return null;
		File t = thumbFile(videoId);
		if (t.isFile() && (getEntry(videoId) != null)) {
			android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(t.getPath());
			if (bm != null) return bm;
		}
		return frameOf(videoId);
	}

	/**
	 * A file that will not play (parts of two streams appended, a join that went wrong) is found
	 * out now, while it can still be fetched again, not when it stalls the player: it must have a
	 * length, close to the video's own.
	 */
	private static void verifyPlayable(File f, Entry e) throws IOException {
		MediaMetadataRetriever r = new MediaMetadataRetriever();
		try {
			r.setDataSource(f.getPath());
			String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
			long ms = (d == null) ? 0 : Long.parseLong(d);
			if (ms <= 0) throw new IOException("The download is damaged");
			if ((e.durationMs > 0) && (Math.abs(ms - e.durationMs) > Math.max(5000, e.durationMs / 5))) {
				throw new IOException("The download is damaged");
			}
		} catch (RuntimeException ex) {
			throw new IOException("The download is damaged", ex);
		} finally {
			try {
				r.release();
			} catch (Exception ignored) {
			}
		}
	}

	/** Deletes a part file and the note of which stream it is of. */
	private static void deletePart(File part) {
		part.delete();
		new File(part.getPath() + ".len").delete();
	}

	private static long readLong(File f) {
		try {
			return Long.parseLong(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
		} catch (Exception ex) {
			return -1;
		}
	}

	private static void writeLong(File f, long v) {
		try {
			Files.write(f.toPath(), Long.toString(v).getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			Log.w(ex, "Failed to write ", f);
		}
	}

	/** The next picture quality below {@code height}, or 0 if there is none. */
	private static int lowerHeight(int height) {
		int lower = 0;
		for (int q : DownloadsAddon.QUALITIES) {
			if (q < height) lower = q;
		}
		return lower;
	}

	private void checkStop() throws StopException {
		int s = stop;
		if (s != STOP_NONE) throw new StopException(s);
	}

	private void fetch(Entry e, YtStreamResolver.Result r, YtStreamResolver.Stream s, File part,
										long base) throws IOException {
		long have = part.length();
		// What is on disk goes on only with the very stream it came from: the length written beside
		// it says which (a new answer may pick another client or quality).
		File meta = new File(part.getPath() + ".len");
		if ((have > 0) && ((readLong(meta) != s.length) || (have > s.length))) {
			deletePart(part);
			have = 0;
		}
		if (have == 0) writeLong(meta, s.length);

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
				YtStreamResolver.applyStreamHeaders(c, r.userAgent);
				c.setRequestProperty("Range", "bytes=" + have + '-' + end);
				int code = c.getResponseCode();
				if ((code == 403) || (code == 410)) {
					// What the server said, for the diagnostic log (never the address: it carries tokens).
					DiagnosticLog.log("YTDL", "stream HTTP " + code, "id=" + e.videoId,
							"host=" + new URL(s.url).getHost(), "len=" + s.length, "from=" + have);
					throw new RefusedException();
				}
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
			MediaFormat vf = vx.getTrackFormat(vt);
			MediaFormat af = ax.getTrackFormat(at);
			int mv = mm.addTrack(vf);
			int ma = mm.addTrack(af);
			vx.selectTrack(vt);
			ax.selectTrack(at);
			mm.start();
			started = true;

			// Big enough for the largest sample the streams say they have (a 4K key frame).
			int cap = 2 * 1024 * 1024;
			if (vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
				cap = Math.max(cap, vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
			}
			if (af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
				cap = Math.max(cap, af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
			}
			ByteBuffer buf = ByteBuffer.allocate(cap);
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

	/** Copies the sound track of {@code src} into a file of its own, without re-encoding it. */
	private static void extractAudio(File src, File out) throws IOException {
		MediaExtractor x = new MediaExtractor();
		MediaMuxer mm = null;
		boolean started = false;

		try {
			x.setDataSource(src.getPath());
			int t = findTrack(x, "audio/");
			if (t < 0) throw new IOException("The download has no sound");
			mm = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			int mt = mm.addTrack(x.getTrackFormat(t));
			x.selectTrack(t);
			mm.start();
			started = true;

			ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
			MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
			for (; ; ) {
				buf.clear();
				int n = x.readSampleData(buf, 0);
				if (n < 0) break;
				info.set(0, n, x.getSampleTime(), x.getSampleFlags());
				mm.writeSampleData(mt, buf, info);
				x.advance();
			}
		} catch (RuntimeException ex) {
			throw new IOException("Failed to take the sound out of the download", ex);
		} finally {
			if (mm != null) {
				try {
					if (started) mm.stop();
				} catch (RuntimeException ignored) {
				}
				mm.release();
			}
			x.release();
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
		// Without the lock once loaded: this is asked while lists are drawn, and the worker holds the
		// lock while it writes the index.
		if (loaded) return;
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
				// One bad entry must not lose the others: the index is rewritten from what was read.
				try {
					JSONObject o = a.getJSONObject(i);
					Entry e = new Entry(o.getString("id"),
							o.optInt("height", o.optBoolean("video") ? 480 : 0));
					e.gotHeight = o.optInt("got");
					e.title = o.optString("title", null);
					e.artist = o.optString("artist", null);
					e.durationMs = o.optLong("dur");
					e.bytes = o.optLong("bytes");
					e.total = o.optLong("total");
					e.fileName = o.optString("file", null);
					State st;
					try {
						st = State.valueOf(o.optString("state", State.PAUSED.name()));
					} catch (IllegalArgumentException ex) {
						st = State.PAUSED;
					}
					// Whatever was running when the app died picks up from what's on disk once resumed.
					if ((st == State.DOWNLOADING) || (st == State.QUEUED)) st = State.PAUSED;
					if (st == State.DONE) {
						if ((e.fileName == null) || !new File(dir(), e.fileName).isFile()) continue;
						done.add(e.videoId);
						if (e.video) doneVideo.add(e.videoId);
					}
					e.state = st;
					entries.put(e.videoId, e);
				} catch (Exception ex) {
					Log.e(ex, "Skipped an entry of the YouTube downloads index");
				}
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
				o.put("height", e.height);
				o.put("got", e.gotHeight);
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
		doneVideo.remove(e.videoId);
		File d = dir();
		deletePart(new File(d, e.videoId + ".audio.part"));
		deletePart(new File(d, e.videoId + ".video.part"));
		if (e.fileName != null) new File(d, e.fileName).delete();
		new File(d, e.videoId + ".jpg").delete();
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
