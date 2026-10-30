package me.aap.fermata.util;

import android.os.Handler;
import android.os.Looper;

/**
 * One readable trail for "a song ended, what played next", in the {@link LogCat#PLAY} category. Every line of one
 * transition carries the same {@code seq} and {@code after} (ms since the song ended), so a run reads top to bottom:
 * <pre>
 * PLAY song ended        | seq=7 how=page engine=YoutubeMediaEngine id=abc size=1080x1920 shown=false
 * PLAY resolving next    | seq=7 after=3ms item=... playNext=true
 * PLAY next resolved     | seq=7 after=140ms next=...
 * PLAY next started      | seq=7 after=1620ms engine=YoutubeMediaEngine item=...
 * </pre>
 * A transition that never reaches "next started" ends in {@code PLAY next did not start} (a warning, after
 * {@link #WATCH_MS}), which is the line to look for when a song ends and nothing follows. The messages are fixed
 * phrases; add a step with {@link #step} rather than inventing a new wording at a call site.
 */
public final class PlayLog {
	/** How long a transition may stay open before it is reported as not started. */
	public static final long WATCH_MS = 8000;
	/** An end reported again this soon after the first belongs to the same transition. */
	private static final long SAME_END_MS = 3000;

	private static final Handler handler = new Handler(Looper.getMainLooper());
	private static volatile int seq;
	private static volatile boolean open;
	private static volatile long beganAt;

	private PlayLog() {
	}

	/**
	 * A song reached its end (or was taken for it). Returns the transition's number. A second report of the same end
	 * (the page's script and then the session's callback) joins the open transition and only adds a line.
	 */
	public static synchronized int begin(String how, Object... details) {
		long now = System.currentTimeMillis();
		if (open && (now - beganAt < SAME_END_MS)) {
			DiagnosticLog.log(LogCat.PLAY, "song ended again", join(head(), details, "how=" + how));
			return seq;
		}
		final int n = ++seq;
		open = true;
		beganAt = now;
		DiagnosticLog.log(LogCat.PLAY, "song ended", join(new Object[]{"seq=" + n}, details, "how=" + how));
		handler.postDelayed(() -> {
			if (open && (seq == n)) {
				DiagnosticLog.w(LogCat.PLAY, "next did not start", "seq=" + n, "after=" + (System.currentTimeMillis() - beganAt) + "ms");
			}
		}, WATCH_MS);
		return n;
	}

	/** Whether a transition is waiting for its next song. */
	public static boolean isOpen() {
		return open;
	}

	/** The open transition's number (0 before the first). */
	public static int current() {
		return seq;
	}

	/** One step of the open transition. Ignored when none is open, so a plain play leaves no PLAY lines. */
	public static void step(String message, Object... details) {
		if (!open) return;
		DiagnosticLog.log(LogCat.PLAY, message, join(head(), details));
	}

	/** Something went wrong in the open transition and was handled or retried. */
	public static void warn(String message, Object... details) {
		if (!open) return;
		DiagnosticLog.w(LogCat.PLAY, message, join(head(), details));
	}

	/** The next song is playing: the transition is complete. */
	public static synchronized void started(String engine, Object item) {
		if (!open) return;
		open = false;
		DiagnosticLog.log(LogCat.PLAY, "next started", join(head(beganAt), new Object[]{"engine=" + engine, "item=" + item}));
	}

	/** The transition ended without a next song, on purpose (play next off, end of the list, an error already reported). */
	public static synchronized void finish(String why, Object... details) {
		if (!open) return;
		open = false;
		DiagnosticLog.log(LogCat.PLAY, "stopped after the song", join(head(beganAt), details, "why=" + why));
	}

	private static Object[] head() {
		return head(beganAt);
	}

	private static Object[] head(long since) {
		return new Object[]{"seq=" + seq, "after=" + (System.currentTimeMillis() - since) + "ms"};
	}

	private static Object[] join(Object[] a, Object[] b, Object... c) {
		Object[] r = new Object[a.length + b.length + c.length];
		System.arraycopy(a, 0, r, 0, a.length);
		System.arraycopy(b, 0, r, a.length, b.length);
		System.arraycopy(c, 0, r, a.length + b.length, c.length);
		return r;
	}
}
