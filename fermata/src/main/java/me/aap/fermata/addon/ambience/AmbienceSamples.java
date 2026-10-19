package me.aap.fermata.addon.ambience;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.log.Log;

/**
 * The recorded loops of the Ambience addon: real CC0 recordings (built by the "Ambience sounds"
 * workflow from {@code tools/ambience/sounds.json}) that are downloaded the first time they are
 * wanted, kept in the app's files and played by {@link AmbienceSample} instead of the synthesized
 * voice, when the user picks "Recorded" on a card.
 * <p>
 * {@code manifest.json} in the release says which sounds exist, how big they are and their hash;
 * it is cached, so a sound that was downloaded stays usable offline, and asked for again at most
 * every few hours. A file is checked against its hash, decoded once with MediaCodec into PCM kept
 * in memory (a minute of stereo is about 11 MB) and dropped again when the user goes back to the
 * synthesized one. All of it runs on one background thread; listeners are told on the main thread.
 */
public final class AmbienceSamples {
	/** The release the workflow publishes to. */
	static final String BASE = "https://github.com/yusairiyap/zrAuto/releases/download/ambience-sounds/";
	private static final long MANIFEST_EVERY_MS = 6 * 3600_000L;
	private static final int MAX_SECONDS = 120;
	private static volatile AmbienceSamples instance;

	public enum State {NONE, DOWNLOADING, LOADING, READY, FAILED}

	private static final class Entry {
		String file, sha256;
		long bytes;
		volatile State state = State.NONE;
		volatile int progress;
		final AmbienceSample voice = new AmbienceSample();
	}

	private final Handler main = new Handler(Looper.getMainLooper());
	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ambience-samples");
		t.setDaemon(true);
		return t;
	});
	private final Map<String, Entry> entries = new HashMap<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private long manifestAt;
	private boolean fetching;

	private AmbienceSamples() {
		readManifest(manifestFile());
	}

	public static AmbienceSamples get() {
		AmbienceSamples s = instance;
		if (s == null) {
			synchronized (AmbienceSamples.class) {
				s = instance;
				if (s == null) instance = s = new AmbienceSamples();
			}
		}
		return s;
	}

	private static File dir() {
		File d = new File(FermataApplication.get().getFilesDir(), "ambience");
		d.mkdirs();
		return d;
	}

	private static File manifestFile() {
		return new File(dir(), "manifest.json");
	}

	public void addListener(Runnable l) {
		if (!listeners.contains(l)) listeners.add(l);
	}

	public void removeListener(Runnable l) {
		listeners.remove(l);
	}

	private void changed() {
		main.post(() -> {
			for (Runnable l : listeners) l.run();
		});
	}

	// ---------------------------------------------------------------------------------------------
	// What there is
	// ---------------------------------------------------------------------------------------------

	/** Whether a recording exists for the effect (it is in the manifest). */
	public synchronized boolean has(AmbienceEffect e) {
		Entry en = entries.get(e.key);
		return (en != null) && (en.file != null);
	}

	public synchronized State getState(AmbienceEffect e) {
		Entry en = entries.get(e.key);
		return (en == null) ? State.NONE : en.state;
	}

	public synchronized int getProgress(AmbienceEffect e) {
		Entry en = entries.get(e.key);
		return (en == null) ? 0 : en.progress;
	}

	public boolean isReady(AmbienceEffect e) {
		return getState(e) == State.READY;
	}

	/** "2.1 MB", or an empty string. */
	public synchronized String getSizeText(AmbienceEffect e) {
		Entry en = entries.get(e.key);
		return (en == null) ? "" : Formatter.formatShortFileSize(FermataApplication.get(), en.bytes);
	}

	/** The voice that plays the recording; silent until it is {@link State#READY}. */
	synchronized AmbienceSample voice(AmbienceEffect e) {
		Entry en = entries.get(e.key);
		if (en == null) {
			en = new Entry();
			entries.put(e.key, en);
		}
		return en.voice;
	}

	private synchronized void readManifest(File f) {
		try {
			if (!f.isFile()) return;
			JSONObject sounds = new JSONObject(new String(readAll(f), "UTF-8")).getJSONObject("sounds");
			for (java.util.Iterator<String> it = sounds.keys(); it.hasNext(); ) {
				String key = it.next();
				JSONObject o = sounds.getJSONObject(key);
				Entry en = entries.get(key);
				if (en == null) {
					en = new Entry();
					entries.put(key, en);
				}
				en.file = o.getString("file");
				en.bytes = o.getLong("bytes");
				en.sha256 = o.getString("sha256");
			}
		} catch (Exception ex) {
			DiagnosticLog.log("AMBIENCE", "manifest unreadable", ex);
		}
	}

	private static byte[] readAll(File f) throws IOException {
		try (InputStream in = new FileInputStream(f)) {
			java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
			byte[] b = new byte[8192];
			for (int n; (n = in.read(b)) > 0; ) bo.write(b, 0, n);
			return bo.toByteArray();
		}
	}

	/** Reads the manifest again from the release (not more often than every few hours). */
	public void refresh() {
		synchronized (this) {
			if (fetching || (SystemClock.elapsedRealtime() - manifestAt < MANIFEST_EVERY_MS) && manifestAt != 0) return;
			fetching = true;
		}
		io.execute(() -> {
			try {
				File tmp = new File(dir(), "manifest.json.part");
				download(BASE + "manifest.json", tmp, null, 0);
				File f = manifestFile();
				if (!tmp.renameTo(f)) throw new IOException("rename");
				readManifest(f);
				synchronized (this) {
					manifestAt = SystemClock.elapsedRealtime();
				}
				changed();
			} catch (Exception ex) {
				// Offline, or nothing published yet: what is cached stays.
				DiagnosticLog.log("AMBIENCE", "manifest not fetched", ex.getMessage());
			} finally {
				synchronized (this) {
					fetching = false;
				}
			}
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Getting one
	// ---------------------------------------------------------------------------------------------

	/** Makes the recording ready: loads it when it is downloaded, downloads it first when not. */
	public void ensure(AmbienceEffect e) {
		Entry en;
		synchronized (this) {
			en = entries.get(e.key);
			if ((en == null) || (en.file == null)) return;
			if ((en.state == State.READY) || (en.state == State.DOWNLOADING) || (en.state == State.LOADING)) return;
			en.state = State.DOWNLOADING;
			en.progress = 0;
		}
		changed();
		Entry entry = en;
		io.execute(() -> {
			try {
				File f = new File(dir(), entry.file);
				if (!isValid(f, entry)) {
					File part = new File(dir(), entry.file + ".part");
					download(BASE + entry.file, part, entry, entry.bytes);
					if (!hash(part).equals(entry.sha256)) throw new IOException("hash mismatch");
					if (!part.renameTo(f)) throw new IOException("rename");
				}
				entry.state = State.LOADING;
				changed();
				Decoded d = decode(f);
				entry.voice.set(d.pcm, d.channels, d.rate);
				entry.state = State.READY;
				DiagnosticLog.log("AMBIENCE", "recording ready", e.key, d.pcm.length / d.channels, "frames");
			} catch (Throwable ex) {
				entry.state = State.FAILED;
				DiagnosticLog.log("AMBIENCE", "recording failed", e.key, ex);
				Log.e(ex, "Failed to get the ambience recording ", e.key);
			}
			changed();
		});
	}

	/** Frees the decoded sound (the file stays). */
	public void release(AmbienceEffect e) {
		Entry en;
		synchronized (this) {
			en = entries.get(e.key);
		}
		if ((en == null) || (en.state != State.READY)) return;
		en.voice.clear();
		en.state = State.NONE;
		changed();
	}

	private boolean isValid(File f, Entry en) throws Exception {
		return f.isFile() && (f.length() == en.bytes) && hash(f).equals(en.sha256);
	}

	private static String hash(File f) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		try (InputStream in = new FileInputStream(f)) {
			byte[] b = new byte[16384];
			for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n);
		}
		StringBuilder sb = new StringBuilder();
		for (byte x : md.digest()) sb.append(String.format(Locale.US, "%02x", x));
		return sb.toString();
	}

	private void download(String url, File to, @Nullable Entry progress, long total) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(15000);
		c.setReadTimeout(30000);
		c.setInstanceFollowRedirects(true);
		try {
			int code = c.getResponseCode();
			if (code != 200) throw new IOException("HTTP " + code);
			long done = 0;
			int last = -1;
			try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
				byte[] b = new byte[16384];
				for (int n; (n = in.read(b)) > 0; ) {
					out.write(b, 0, n);
					done += n;
					if ((progress != null) && (total > 0)) {
						int p = (int) Math.min(99, done * 100 / total);
						if (p != last) {
							last = p;
							progress.progress = p;
							changed();
						}
					}
				}
			}
		} finally {
			c.disconnect();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Decoding
	// ---------------------------------------------------------------------------------------------

	private static final class Decoded {
		short[] pcm;
		int channels, rate;
	}

	/** The whole file as 16-bit PCM (at most {@link #MAX_SECONDS} of it). */
	private static Decoded decode(File f) throws IOException {
		MediaExtractor ex = new MediaExtractor();
		MediaCodec codec = null;
		try {
			ex.setDataSource(f.getAbsolutePath());
			MediaFormat fmt = null;
			for (int i = 0; i < ex.getTrackCount(); i++) {
				MediaFormat tf = ex.getTrackFormat(i);
				String mime = tf.getString(MediaFormat.KEY_MIME);
				if ((mime != null) && mime.startsWith("audio/")) {
					ex.selectTrack(i);
					fmt = tf;
					break;
				}
			}
			if (fmt == null) throw new IOException("no audio track");
			codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
			codec.configure(fmt, null, null, 0);
			codec.start();
			int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
			int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
			short[] buf = new short[rate * channels * 20];
			int len = 0;
			MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
			boolean inDone = false, outDone = false;
			while (!outDone) {
				if (!inDone) {
					int ii = codec.dequeueInputBuffer(10000);
					if (ii >= 0) {
						ByteBuffer ib = codec.getInputBuffer(ii);
						int n = ex.readSampleData(ib, 0);
						if (n < 0) {
							codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							inDone = true;
						} else {
							codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
							ex.advance();
						}
					}
				}
				int oi = codec.dequeueOutputBuffer(info, 10000);
				if (oi >= 0) {
					ByteBuffer ob = codec.getOutputBuffer(oi);
					if ((ob != null) && (info.size > 0)) {
						ob.position(info.offset);
						ob.limit(info.offset + info.size);
						ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
						int cnt = sb.remaining();
						if (len + cnt > buf.length) {
							if (len + cnt > MAX_SECONDS * rate * channels) throw new IOException("recording too long");
							buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, len + cnt));
						}
						sb.get(buf, len, cnt);
						len += cnt;
					}
					codec.releaseOutputBuffer(oi, false);
					if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
				} else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					MediaFormat of = codec.getOutputFormat();
					channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
					rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
				}
			}
			Decoded d = new Decoded();
			d.channels = channels;
			d.rate = rate;
			d.pcm = java.util.Arrays.copyOf(buf, len - len % channels);
			if (d.pcm.length < channels * rate) throw new IOException("recording too short");
			return d;
		} finally {
			if (codec != null) {
				try {
					codec.stop();
				} catch (Exception ignore) {
				}
				codec.release();
			}
			ex.release();
		}
	}
}
