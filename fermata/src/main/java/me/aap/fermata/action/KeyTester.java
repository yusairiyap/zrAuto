package me.aap.fermata.action;

import android.view.KeyEvent;

import androidx.annotation.Nullable;

/**
 * The hook the key binding tester (Settings &gt; Key bindings &gt; Key tester) listens on. While a
 * listener is set, every key event the app sees (from the activity and from the media session, which
 * is where a car's steering wheel buttons usually land) and every play/pause/next/... command a
 * controller sends (Android Auto, a Bluetooth head unit) is reported to it first, and whatever it
 * consumes never reaches the key bindings or the player.
 */
public final class KeyTester {
	@Nullable
	private static Listener listener;
	/**
	 * The same event can reach the tester twice (the tester's own view sees it while dispatching,
	 * then the activity's key handler): it's reported once, the second time just gets the same answer.
	 */
	private static long lastTime = -1;
	private static int lastCode;
	private static int lastAction;
	private static int lastRepeat;
	private static boolean lastConsumed;

	private KeyTester() {
	}

	public static boolean isActive() {
		return listener != null;
	}

	public static void setListener(@Nullable Listener l) {
		listener = l;
		lastTime = -1;
		KeyEventHandler.reset();
	}

	/**
	 * @param session whether it came through the media session (a media button) rather than the
	 *                activity's window
	 * @return true to consume the event
	 */
	public static boolean onKeyEvent(KeyEvent e, boolean session) {
		Listener l = listener;
		if (l == null) return false;
		// Compared by value: the framework recycles KeyEvent objects.
		if ((e.getEventTime() == lastTime) && (e.getKeyCode() == lastCode) &&
				(e.getAction() == lastAction) && (e.getRepeatCount() == lastRepeat)) {
			return lastConsumed;
		}
		lastTime = e.getEventTime();
		lastCode = e.getKeyCode();
		lastAction = e.getAction();
		lastRepeat = e.getRepeatCount();
		return lastConsumed = l.onKeyEvent(e, session);
	}

	/** @return true to consume (block) the command */
	public static boolean onTransport(Transport t) {
		Listener l = listener;
		return (l != null) && l.onTransport(t);
	}

	/**
	 * A media session command, as Android Auto or a head unit sends it: some send the steering
	 * wheel's buttons as these instead of as key events, and those then skip the key bindings unless
	 * {@link Key#BIND_TRANSPORT} is on.
	 */
	public enum Transport {
		PLAY(Key.MEDIA_PLAY),
		PAUSE(Key.MEDIA_PAUSE),
		STOP(Key.MEDIA_STOP),
		NEXT(Key.MEDIA_NEXT),
		PREV(Key.MEDIA_PREVIOUS),
		FF(Key.MEDIA_FAST_FORWARD),
		RW(Key.MEDIA_REWIND);

		public final Key key;

		Transport(Key key) {
			this.key = key;
		}
	}

	public interface Listener {
		boolean onKeyEvent(KeyEvent e, boolean session);

		boolean onTransport(Transport t);
	}
}
