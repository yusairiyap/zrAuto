package me.aap.fermata.media.service;

import androidx.annotation.Nullable;

/**
 * A pending resume of an item that plays in its own player (a YouTube video in the YouTube tab),
 * where the media session can't just prepare an engine: where that player should start from,
 * and whether it should hold paused once it has loaded ("where you left off, ready to play") or
 * carry on playing (the user pressed play). Set by whoever starts the resume, read by the
 * player; keyed by the item's {@link me.aap.fermata.media.lib.MediaLib.PlayableItem#getOrigId()
 * original id} (e.g. {@code youtube:<videoId>}).
 */
public final class PlaybackResume {
	@Nullable
	private static String id;
	private static long position;
	private static boolean pause;

	private PlaybackResume() {
	}

	public static synchronized void set(String origId, long pos, boolean pauseOnStart) {
		id = origId;
		position = Math.max(pos, 0);
		pause = pauseOnStart;
	}

	/** Where the player should start {@code origId} from; 0 if there's no resume for it. */
	public static synchronized long getStartPosition(String origId) {
		return origId.equals(id) ? position : 0;
	}

	/**
	 * Called by the player once {@code origId} has started: whether to pause it right away (the
	 * resume was only a load). Ends the resume either way.
	 */
	public static synchronized boolean takePauseOnStart(String origId) {
		if (!origId.equals(id)) return false;
		boolean p = pause;
		id = null;
		position = 0;
		pause = false;
		return p;
	}
}
