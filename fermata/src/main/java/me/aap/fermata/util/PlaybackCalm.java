package me.aap.fermata.util;

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
	/** Uptime the song began playing (or played again after buffering), 0 while it is not playing. */
	private static volatile long playingSince;
	/** Starting, skipping or buffering: nothing extra until it plays. */
	private static volatile boolean starting;

	private PlaybackCalm() {
	}

	public static void onState(int state) {
		switch (state) {
			case PlaybackStateCompat.STATE_PLAYING -> {
				if (playingSince == 0) playingSince = SystemClock.uptimeMillis();
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
}
