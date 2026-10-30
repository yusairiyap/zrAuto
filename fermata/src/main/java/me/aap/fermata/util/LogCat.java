package me.aap.fermata.util;

/**
 * The categories of the {@link DiagnosticLog}: one short UPPER_CASE word for the part of the app a line comes from.
 * Every {@code DiagnosticLog} call takes one of these constants, never a literal, so the set stays small and the
 * Diagnostic log screen's category filter stays readable. Add a constant here (and a line to the table below) when a
 * new area needs its own; do not invent a category at a call site.
 * <p>
 * <table>
 * <caption>Categories</caption>
 * <tr><td>{@link #DIAG}</td><td>the log itself: logging switched on, the app / device / memory header</td></tr>
 * <tr><td>{@link #CRASH}</td><td>an uncaught exception, with its stack (always recorded)</td></tr>
 * <tr><td>{@link #STATE}</td><td>activity lifecycle, screen, start-up timings</td></tr>
 * <tr><td>{@link #HOST}</td><td>Android Auto host: car activity and car service lifecycle</td></tr>
 * <tr><td>{@link #TRANSPORT}</td><td>play, pause, next, previous, a song ending, double tap seeks</td></tr>
 * <tr><td>{@link #ENGINE}</td><td>the local players (ExoPlayer), stalls and their recovery</td></tr>
 * <tr><td>{@link #PLAY}</td><td>what happens between a song ending and the next one playing (see {@link PlayLog}), one {@code seq} per transition</td></tr>
 * <tr><td>{@link #YT}</td><td>the YouTube page and its engine (including the page's own trace and comments)</td></tr>
 * <tr><td>{@link #YTDL}</td><td>YouTube downloads and the offline copy</td></tr>
 * <tr><td>{@link #MUSIC}</td><td>the Music tab's player and queue</td></tr>
 * <tr><td>{@link #META}</td><td>session metadata and album art published to the system</td></tr>
 * <tr><td>{@link #NOTIF}</td><td>the media notification</td></tr>
 * <tr><td>{@link #FOCUS}</td><td>audio focus and noisy-output changes</td></tr>
 * <tr><td>{@link #INTERRUPT}</td><td>interruptions (calls, car camera) and the resume after them</td></tr>
 * <tr><td>{@link #RESUME}</td><td>restoring what played last</td></tr>
 * <tr><td>{@link #TIMER}</td><td>the sleep timer</td></tr>
 * <tr><td>{@link #NETWORK}</td><td>stalls on a slow or lost connection, the offline fallback</td></tr>
 * <tr><td>{@link #CAPTIONS}</td><td>captions and lyrics look-ups</td></tr>
 * <tr><td>{@link #SEEK}</td><td>seek previews and the seek bar</td></tr>
 * <tr><td>{@link #ART}</td><td>covers and video frames</td></tr>
 * <tr><td>{@link #BODY}</td><td>the body layout: modes, edge fades, video surfaces, page blur</td></tr>
 * <tr><td>{@link #VIDEO}</td><td>fullscreen video and the web page's fullscreen</td></tr>
 * <tr><td>{@link #BACK}</td><td>the Back gesture and who consumed it</td></tr>
 * <tr><td>{@link #HOME}</td><td>the Home tab and its widgets</td></tr>
 * <tr><td>{@link #AIO}</td><td>the All in One card</td></tr>
 * <tr><td>{@link #CARNAV}</td><td>car-mode key navigation</td></tr>
 * <tr><td>{@link #WELCOME}</td><td>the welcome wizard</td></tr>
 * <tr><td>{@link #AMBIENCE}</td><td>the ambience mixer</td></tr>
 * <tr><td>{@link #POWER}</td><td>power usage and the power saver</td></tr>
 * <tr><td>{@link #DATA}</td><td>data usage and the Android Auto session limit</td></tr>
 * <tr><td>{@link #SHARE}</td><td>sharing over Wi-Fi</td></tr>
 * <tr><td>{@link #BACKUP}</td><td>backup and restore</td></tr>
 * <tr><td>{@link #UI}</td><td>dialogs and other view failures</td></tr>
 * <tr><td>{@link #SLEEP}</td><td>the Sleep addon: sessions, epochs, scores</td></tr>
 * </table>
 * <p>
 * Every category the user ever sees comes from here, but the filter only lists the ones that have lines.
 */
public final class LogCat {
	public static final String DIAG = "DIAG";
	public static final String CRASH = "CRASH";
	public static final String STATE = "STATE";
	public static final String HOST = "HOST";
	public static final String TRANSPORT = "TRANSPORT";
	public static final String ENGINE = "ENGINE";
	public static final String PLAY = "PLAY";
	public static final String YT = "YT";
	public static final String YTDL = "YTDL";
	public static final String MUSIC = "MUSIC";
	public static final String META = "META";
	public static final String NOTIF = "NOTIF";
	public static final String FOCUS = "FOCUS";
	public static final String INTERRUPT = "INTERRUPT";
	public static final String RESUME = "RESUME";
	public static final String TIMER = "TIMER";
	public static final String NETWORK = "NETWORK";
	public static final String CAPTIONS = "CAPTIONS";
	public static final String SEEK = "SEEK";
	public static final String ART = "ART";
	public static final String BODY = "BODY";
	public static final String VIDEO = "VIDEO";
	public static final String BACK = "BACK";
	public static final String HOME = "HOME";
	public static final String AIO = "AIO";
	public static final String CARNAV = "CARNAV";
	public static final String WELCOME = "WELCOME";
	public static final String AMBIENCE = "AMBIENCE";
	public static final String POWER = "POWER";
	public static final String DATA = "DATA";
	public static final String SHARE = "SHARE";
	public static final String BACKUP = "BACKUP";
	public static final String UI = "UI";
	public static final String SLEEP = "SLEEP";

	private LogCat() {
	}
}
