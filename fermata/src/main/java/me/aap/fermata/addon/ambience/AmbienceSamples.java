package me.aap.fermata.addon.ambience;

import android.content.res.AssetManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.log.Log;

/**
 * The recorded loops of the Ambience addon: real CC0 recordings that ship inside the app
 * ({@code assets/ambience/}, a minute of Opus is about half a megabyte) and are played by
 * {@link AmbienceSample} instead of the synthesized voice when the user picks "Recorded" on a card.
 * They are made from files downloaded by hand with {@code tools/ambience/build_sounds.py}, which
 * also writes {@code manifest.json}, the list of what is there and its credits. An effect with no
 * entry there simply has no choice to make.
 * <p>
 * A recording is decoded once with MediaCodec into PCM kept in memory (a minute of stereo is about
 * 11 MB) when it is first wanted, on a background thread, and dropped again when the user goes back
 * to the synthesized voice. Listeners are told on the main thread.
 */
public final class AmbienceSamples {
	private static final int MAX_SECONDS = 120;
	private static volatile AmbienceSamples instance;

	public enum State {NONE, LOADING, READY, FAILED}

	private static final class Entry {
		String file;
		volatile State state = State.NONE;
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

	private AmbienceSamples() {
		readManifest();
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

	private synchronized Entry entry(String key) {
		Entry en = entries.get(key);
		if (en == null) {
			en = new Entry();
			entries.put(key, en);
		}
		return en;
	}

	private void readManifest() {
		try (InputStream in = FermataApplication.get().getAssets().open("ambience/manifest.json")) {
			java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
			byte[] b = new byte[4096];
			for (int n; (n = in.read(b)) > 0; ) bo.write(b, 0, n);
			JSONObject sounds = new JSONObject(bo.toString("UTF-8")).getJSONObject("sounds");
			for (java.util.Iterator<String> it = sounds.keys(); it.hasNext(); ) {
				String key = it.next();
				entry(key).file = sounds.getJSONObject(key).getString("file");
			}
		} catch (java.io.FileNotFoundException none) {
			// No recordings in this build.
		} catch (Exception ex) {
			DiagnosticLog.log("AMBIENCE", "manifest unreadable", ex);
		}
	}

	/** Whether a recording exists for the effect. */
	public synchronized boolean has(AmbienceEffect e) {
		Entry en = entries.get(e.key);
		return (en != null) && (en.file != null);
	}

	public synchronized State getState(AmbienceEffect e) {
		Entry en = entries.get(e.key);
		return (en == null) ? State.NONE : en.state;
	}

	public boolean isReady(AmbienceEffect e) {
		return getState(e) == State.READY;
	}

	/** The voice that plays the recording; silent until it is {@link State#READY}. */
	AmbienceSample voice(AmbienceEffect e) {
		return entry(e.key).voice;
	}

	// ---------------------------------------------------------------------------------------------
	// Loading one
	// ---------------------------------------------------------------------------------------------

	/** Decodes the recording in the background (nothing happens when it is loaded or loading). */
	public void ensure(AmbienceEffect e) {
		Entry en;
		synchronized (this) {
			en = entries.get(e.key);
			if ((en == null) || (en.file == null)) return;
			if ((en.state == State.READY) || (en.state == State.LOADING)) return;
			en.state = State.LOADING;
		}
		changed();
		Entry entry = en;
		io.execute(() -> {
			try {
				// The decoder wants a file: the asset is copied out once (it is small).
				File dir = new File(FermataApplication.get().getCacheDir(), "ambience");
				dir.mkdirs();
				File f = new File(dir, entry.file);
				AssetManager am = FermataApplication.get().getAssets();
				try (InputStream in = am.open("ambience/" + entry.file);
						 FileOutputStream out = new FileOutputStream(f)) {
					byte[] b = new byte[16384];
					for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
				}
				Decoded d = decode(f);
				entry.voice.set(d.pcm, d.channels, d.rate);
				entry.state = State.READY;
				DiagnosticLog.log("AMBIENCE", "recording ready", e.key, d.pcm.length / d.channels, "frames");
			} catch (Throwable ex) {
				entry.state = State.FAILED;
				DiagnosticLog.log("AMBIENCE", "recording failed", e.key, ex);
				Log.e(ex, "Failed to load the ambience recording ", e.key);
			}
			changed();
		});
	}

	/** Frees the decoded sound. */
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
