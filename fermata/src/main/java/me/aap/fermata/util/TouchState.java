package me.aap.fermata.util;

import android.os.SystemClock;
import android.view.MotionEvent;

/**
 * Whether a finger is on the screen (or just left it, a fling may still be running). Fed by
 * {@code MainActivity#dispatchTouchEvent}; work that can wait (Home's first list reads, a placeholder's shimmer) holds
 * back while it says busy, so a swipe gets the frames to itself.
 */
public final class TouchState {
	/** After the finger leaves, how long a fling or a settle animation is still counted as the user's. */
	private static final long AFTER_UP_MS = 350;
	private static volatile boolean down;
	private static volatile long upAt;

	private TouchState() {
	}

	public static void onEvent(MotionEvent e) {
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> down = true;
			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				down = false;
				upAt = SystemClock.uptimeMillis();
			}
			default -> {
			}
		}
	}

	public static boolean isBusy() {
		return down || ((SystemClock.uptimeMillis() - upAt) < AFTER_UP_MS);
	}
}
