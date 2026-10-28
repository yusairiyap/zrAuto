package me.aap.fermata.util;

import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import android.support.v4.media.session.PlaybackStateCompat;

/**
 * Whether playback is steady: the song playing has been playing for a few seconds without going back to buffering.
 * Work that only makes things nicer (a video frame for the cover, captions fetched ahead, metadata look-ups) waits for it
 * on its background thread, so the first seconds of a song, where a stutter is heard most, have the CPU and the network
 * to themselves. Fed by {@code MediaSessionCallback#setPlaybackState}.
 */
public final class PlaybackCalm {
	/** How long a song must have played without a break before extra work starts. */
	public static final long STEADY_MS = 3000;
	private static final long POLL_MS = 250;
	/**
	 * How long a song plays before the UI's own reaction to it (Home's cover and its blurred background, the lists marking
	 * the playing song) is made: that burst of decoding, blurring and cross-fades landed on the song's first moment, where
	 * the stutter is heard, and only on screens that react to it (never in fullscreen video). 0 takes the hold out.
	 */
	public static final long START_QUIET_MS = 1500;
	/** A start that never comes to playing does not hold the UI back longer than this. */
	private static final long START_QUIET_MAX_MS = 8000;
	/** For the Diagnostic log: how busy the app was in the first seconds of a song (main thread, garbage collection). */
	private static final long LOAD_WATCH_MS = 3000;
	private static final long LOAD_PING_MS = 100;
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static int loadGen;
	/** Uptime the song began playing (or played again after buffering), 0 while it is not playing. */
	private static volatile long playingSince;
	/** Starting, skipping or buffering: nothing extra until it plays. */
	private static volatile boolean starting;

	private PlaybackCalm() {
	}

	public static void onState(int state) {
		switch (state) {
			case PlaybackStateCompat.STATE_PLAYING -> {
				if (playingSince == 0) {
					playingSince = SystemClock.uptimeMillis();
					if (starting) MAIN.post(PlaybackCalm::watchLoad);
				}
				starting = false;
			}
			case PlaybackStateCompat.STATE_BUFFERING, PlaybackStateCompat.STATE_CONNECTING,
					 PlaybackStateCompat.STATE_SKIPPING_TO_NEXT, PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS,
					 PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM -> {
				playingSince = 0;
				starting = true;
			}
			default -> {
				playingSince = 0;
				starting = false;
			}
		}
	}

	/** True when nothing is starting: playing steadily for {@link #STEADY_MS}, or not playing at all. */
	public static boolean isSteady() {
		if (starting) return false;
		long since = playingSince;
		return (since == 0) || ((SystemClock.uptimeMillis() - since) >= STEADY_MS);
	}

	/**
	 * Waits (on a background thread, never the main one) until {@link #isSteady()}, at most {@code maxMs}. Returns at once on
	 * the main thread.
	 */
	public static void awaitSteady(long maxMs) {
		if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return;
		long end = SystemClock.uptimeMillis() + maxMs;
		while (!isSteady() && (SystemClock.uptimeMillis() < end)) {
			try {
				Thread.sleep(POLL_MS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/**
	 * Runs {@code r} on the main thread once the song starting now has played {@link #START_QUIET_MS} (at once when nothing is
	 * starting). The caller checks that what it was for still holds.
	 */
	public static void afterStart(Runnable r) {
		afterStart(r, SystemClock.uptimeMillis());
	}

	private static void afterStart(Runnable r, long askedAt) {
		long now = SystemClock.uptimeMillis();
		long left;
		if (starting) left = POLL_MS;
		else if (playingSince == 0) left = 0;
		else left = START_QUIET_MS - (now - playingSince);
		if ((left <= 0) || (now - askedAt >= START_QUIET_MAX_MS)) {
			if (Looper.myLooper() == Looper.getMainLooper()) r.run();
			else MAIN.post(r);
			return;
		}
		MAIN.postDelayed(() -> afterStart(r, askedAt), Math.min(left, POLL_MS));
	}

	/**
	 * Measures the first {@link #LOAD_WATCH_MS} of a song: how late a 100 ms tick on the main thread came at worst (a long
	 * block there) and how many garbage collections ran, blocking ones with their time. One {@code STATE start load} line
	 * per song: a stutter heard with a big number here is the app's own work; with none, it is the page or the network.
	 */
	private static void watchLoad() {
		int gen = ++loadGen;
		long t0 = SystemClock.uptimeMillis();
		long gc0 = gcStat("art.gc.gc-count");
		long bc0 = gcStat("art.gc.blocking-gc-count");
		long bt0 = gcStat("art.gc.blocking-gc-time");
		long[] late = {0, 0};
		Runnable[] tick = new Runnable[1];
		long[] due = {t0 + LOAD_PING_MS};
		tick[0] = () -> {
			if (gen != loadGen) return;
			long now = SystemClock.uptimeMillis();
			long l = now - due[0];
			if (l > late[0]) late[0] = l;
			if (l > 50) late[1]++;
			if (now - t0 < LOAD_WATCH_MS) {
				due[0] = now + LOAD_PING_MS;
				MAIN.postDelayed(tick[0], LOAD_PING_MS);
				return;
			}
			long gc1 = gcStat("art.gc.gc-count");
			long bc1 = gcStat("art.gc.blocking-gc-count");
			long bt1 = gcStat("art.gc.blocking-gc-time");
			DiagnosticLog.log(LogCat.STATE, "start load", "mainLateMax=" + late[0] + "ms", "over50=" + late[1], "gc=" + diff(gc0, gc1), "blockingGc=" + diff(bc0, bc1), "blockingMs=" + diff(bt0, bt1));
		};
		MAIN.postDelayed(tick[0], LOAD_PING_MS);
	}

	private static String diff(long a, long b) {
		return ((a < 0) || (b < 0)) ? "?" : String.valueOf(b - a);
	}

	private static long gcStat(String name) {
		try {
			String v = Debug.getRuntimeStat(name);
			return (v == null) ? -1 : Long.parseLong(v);
		} catch (Exception ex) {
			return -1;
		}
	}
}
