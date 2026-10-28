package me.aap.fermata.addon.data;

import static me.aap.fermata.addon.data.DataUsageStore.NET_MOBILE;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.text.format.Formatter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.TopConfirm;
import me.aap.fermata.ui.view.TopToast;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;
import me.aap.fermata.ytdl.OfflineFallback;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * A data limit per drive: a session starts when Android Auto connects and ends (and is forgotten)
 * when it disconnects. What {@link DataUsageTracker} books meanwhile counts toward the session
 * (only mobile data when the Data Usage settings count mobile data only). Past the limit, a card
 * offers to play what's stored on the phone instead ({@link OfflineFallback}); nothing is paused.
 * <p>
 * Kept in the preferences, so a session survives the app being restarted during the drive. The
 * connection is looked at with each of the tracker's readings (every 10 s) and when a screen of
 * the app opens.
 */
public final class DataSessionLimit {
	public static final int MIN_MB = 50;
	public static final int MAX_MB = 3000;
	public static final int STEP_MB = 50;
	/** Whether the session limit is on. */
	public static final Pref<BooleanSupplier> ENABLED = Pref.b("DATA_SESSION_LIMIT", false);
	/** The limit per session, in MB. */
	public static final Pref<IntSupplier> LIMIT_MB = Pref.i("DATA_SESSION_LIMIT_MB", 300);
	private static final Pref<BooleanSupplier> ACTIVE = Pref.b("DATA_SESSION_ACTIVE", false);
	private static final Pref<LongSupplier> USED = Pref.l("DATA_SESSION_USED", 0);
	private static final Pref<BooleanSupplier> ALERTED = Pref.b("DATA_SESSION_ALERTED", false);
	private static final long MB = 1000L * 1000L;
	@Nullable
	private static DataSessionLimit instance;
	private final List<Runnable> listeners = new ArrayList<>(2);
	private long used;
	private boolean active;
	private long lastSave;

	private DataSessionLimit() {
		PreferenceStore ps = prefs();
		active = ps.getBooleanPref(ACTIVE);
		used = ps.getLongPref(USED);
	}

	/** Main thread only. */
	@NonNull
	public static DataSessionLimit get() {
		DataSessionLimit l = instance;
		if (l == null) instance = l = new DataSessionLimit();
		return l;
	}

	private static PreferenceStore prefs() {
		return DataUsageTracker.prefs();
	}

	public static boolean isEnabled() {
		return prefs().getBooleanPref(ENABLED);
	}

	/** The limit in bytes. */
	public static long getLimit() {
		int mb = Math.max(MIN_MB, Math.min(MAX_MB, prefs().getIntPref(LIMIT_MB)));
		return mb * MB;
	}

	/** Whether a session is going on: the limit is on and Android Auto is connected. */
	public boolean isActive() {
		return active && isEnabled();
	}

	/** Bytes used in this session. */
	public long getUsed() {
		return used;
	}

	public long getRemaining() {
		return Math.max(0, getLimit() - used);
	}

	/** 0 to 1. */
	public float getFraction() {
		long lim = getLimit();
		return (lim <= 0) ? 0f : Math.min(1f, used / (float) lim);
	}

	public void addListener(Runnable l) {
		if (!listeners.contains(l)) listeners.add(l);
	}

	public void removeListener(Runnable l) {
		listeners.remove(l);
	}

	private void notifyListeners() {
		for (Runnable l : new ArrayList<>(listeners)) l.run();
	}

	/**
	 * Whether the phone is connected to Android Auto: the car screen of the app, mirroring, or the
	 * phone in car mode (Android Auto turns it on while projecting).
	 */
	public static boolean isCarConnected() {
		FermataApplication app = FermataApplication.get();
		if (app.isMirroringMode() || MainActivityDelegate.isCarActivityActive()) return true;
		try {
			UiModeManager m = (UiModeManager) app.getSystemService(Context.UI_MODE_SERVICE);
			return (m != null) && (m.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR);
		} catch (Exception ex) {
			return false;
		}
	}

	/** Starts or ends the session as Android Auto connects or disconnects; true if it did. */
	public boolean poll() {
		boolean want = isEnabled() && isCarConnected();
		if (want == active) return false;
		active = want;
		used = 0;
		try (PreferenceStore.Edit e = prefs().editPreferenceStore()) {
			e.setBooleanPref(ACTIVE, want);
			e.setLongPref(USED, 0);
			e.setBooleanPref(ALERTED, false);
		}
		DiagnosticLog.log(LogCat.DATA, "session changed", "state=" + (want ? "started" : "ended"), "limit=" + (getLimit() / MB) + "MB");
		if (want) announce(true);
		notifyListeners();
		return true;
	}

	/** The tracker booked {@code bytes} over a network of kind {@code net}. */
	void onUsage(long bytes, int net) {
		if (!isActive() || (bytes <= 0)) return;
		if (DataUsageTracker.isMobileOnly() && (net != NET_MOBILE)) return;
		used += bytes;
		long now = android.os.SystemClock.elapsedRealtime();
		if (now - lastSave > 30_000) {
			lastSave = now;
			prefs().applyLongPref(USED, used);
		}
		notifyListeners();
		if ((used >= getLimit()) && !prefs().getBooleanPref(ALERTED)) {
			prefs().applyBooleanPref(ALERTED, true);
			prefs().applyLongPref(USED, used);
			DiagnosticLog.log(LogCat.DATA, "session limit reached", "used=" + used);
			MainActivityDelegate a = MainActivityDelegate.getUiDelegate();
			if (a != null) showLimitReached(a);
		}
	}

	/** Saves the count now (the app goes to the background). */
	void flush() {
		if (active) prefs().applyLongPref(USED, used);
	}

	/** A screen of the app opened: says the session limit is on, if a session is going on. */
	public void onAppOpened(MainActivityDelegate a) {
		if (!poll() && isActive()) announce(false);
	}

	private void announce(boolean started) {
		Context ctx = FermataApplication.get();
		String limit = Formatter.formatShortFileSize(ctx, getLimit());
		String msg = started ? ctx.getString(R.string.data_session_started, limit) :
				ctx.getString(R.string.data_session_running, Formatter.formatShortFileSize(ctx, used), limit);
		TopToast.show(R.drawable.data_usage, msg);
	}

	/** Past the limit: offers what's stored on the phone, which uses no data. */
	public void showLimitReached(MainActivityDelegate a) {
		Context ctx = a.getContext();
		String limit = Formatter.formatShortFileSize(ctx, getLimit());
		if (!OfflineFallback.hasOffline(a)) {
			TopToast.show(R.drawable.data_usage, ctx.getString(R.string.data_session_reached_nothing, limit));
			return;
		}
		TopConfirm.show(a, R.drawable.data_usage, ctx.getString(R.string.data_session_reached_title),
				ctx.getString(R.string.data_session_reached_text, limit),
				ctx.getString(R.string.data_session_play_downloaded), ctx.getString(R.string.data_session_keep),
				() -> OfflineFallback.playDownloaded(a, () -> TopToast.show(R.drawable.download,
						R.string.ytdl_not_downloaded)), null);
	}
}
