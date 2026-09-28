package me.aap.fermata.media.engine;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether playback is stalled waiting for data right now, for the UI only: the Music tab's play
 * button and the video's own spinner. Deliberately not the media session's state, which stays
 * "playing" through a mid-playback stall (Android Auto, the notification and the stall detection
 * all rely on that). Only reported after a short moment, so a seek or a quick hiccup never
 * flickers a spinner.
 */
public final class BufferingIndicator {
	private static final long SHOW_DELAY_MS = 350;
	private static final Handler handler = new Handler(Looper.getMainLooper());
	private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private static final Runnable show = () -> apply(true);
	private static boolean buffering;

	private BufferingIndicator() {
	}

	/** Any thread. */
	public static void setBuffering(boolean b) {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			handler.post(() -> setBuffering(b));
			return;
		}
		handler.removeCallbacks(show);
		if (!b) apply(false);
		else if (!buffering) handler.postDelayed(show, SHOW_DELAY_MS);
	}

	public static boolean isBuffering() {
		return buffering;
	}

	/** Called on the main thread whenever {@link #isBuffering()} changes. */
	public static void addListener(Runnable l) {
		listeners.add(l);
	}

	public static void removeListener(Runnable l) {
		listeners.remove(l);
	}

	private static void apply(boolean b) {
		if (buffering == b) return;
		buffering = b;
		for (Runnable l : listeners) l.run();
	}
}
