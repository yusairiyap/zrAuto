package me.aap.fermata.addon.fuel;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import java.util.concurrent.CopyOnWriteArrayList;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * Tracks distance travelled since the last refuel using the phone's GPS, the same way a car's own
 * trip odometer would -- accumulating {@link Location#distanceTo} between consecutive fixes into
 * {@link FuelLogStore#TRIP_DISTANCE_M} so it survives app restarts. One instance is shared for the
 * whole app process (started lazily the first time the Fuel Log tab is opened, per {@link
 * FuelLogFragment}), following the same on-demand permission-request pattern as {@code
 * modules/poi}'s {@code Voyageur}, rather than requesting location access at every app launch.
 * <p>
 * GPS itself is only ever actually running while the app is connected to Android Auto (see {@link
 * #isConnectedToAndroidAuto()}) -- there's no push notification for that transition anywhere in
 * this codebase, so a lightweight poll ({@link #CONNECTION_POLL_MS}) checks it and starts/stops
 * the location updates accordingly, instead of running GPS continuously and merely discarding
 * fixes taken while disconnected (which would burn battery for no reason whenever the app is just
 * open on the phone, e.g. for its Info Overlay, without actually driving with Android Auto).
 */
public class FuelTracker implements LocationListener {
	/** Fixes worse than this (metres) are too noisy to add to the trip total. */
	private static final float MAX_ACCURACY_M = 30f;
	/** Movement below this (metres) between fixes is treated as GPS jitter while stationary. */
	private static final float MIN_MOVEMENT_M = 5f;
	private static final long MIN_TIME_MS = 3000L;
	private static final float MIN_DISTANCE_M = 5f;
	/** How often to re-check the Android Auto connection state and start/stop GPS accordingly. */
	private static final long CONNECTION_POLL_MS = 15_000L;

	private static FuelTracker instance;

	private final Context appCtx;
	private final LocationManager locationManager;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable connectionWatcher = this::checkConnection;
	private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
	@Nullable
	private Location lastLocation;
	/** Whether the connection-watching poll loop is armed (from {@link #start} onward). */
	private boolean watching;
	/** Whether GPS updates are actually being requested right now (i.e. currently connected). */
	private boolean gpsActive;
	private boolean hasPermission;
	/**
	 * The preference store to accumulate distance into, captured from the {@link
	 * MainActivityDelegate} passed to {@link #start} rather than re-derived from {@link #appCtx}
	 * later -- {@code appCtx} is an application context so it can be held for this singleton's
	 * whole lifetime without leaking an Activity, but {@code MainActivityDelegate.get(Context)}
	 * only resolves contexts that wrap an actual Activity, which an application context never does.
	 */
	@Nullable
	private PreferenceStore prefs;

	private FuelTracker(Context ctx) {
		appCtx = ctx.getApplicationContext();
		locationManager = (LocationManager) appCtx.getSystemService(Context.LOCATION_SERVICE);
	}

	public static synchronized FuelTracker get(Context ctx) {
		if (instance == null) instance = new FuelTracker(ctx);
		return instance;
	}

	/** Requests location permission if needed, then arms the connection watcher. Safe to call
	 * repeatedly (e.g. every time the Fuel Log tab is opened or the Info Overlay refreshes). */
	public void start(MainActivityDelegate a) {
		prefs = a.getPrefs();
		if (watching) return;
		watching = true;
		// onCompletion, not onSuccess: MainCarActivity#checkPermissions() returns a FAILED future
		// outright (the Android Auto SDK has no way to show a permission dialog on the car's screen),
		// so with onSuccess the callback below simply never ran there -- checkConnection() was never
		// called, the poll loop was never armed, and since `watching` had already been latched true
		// no later start() call could retry it either. That alone meant trip distance never moved at
		// all while actually connected to Android Auto, which is the only time it is supposed to.
		// The request is still worth making (it's the phone UI's chance to actually grant it); its
		// outcome just isn't what decides anything -- checkConnection() re-reads the real granted
		// state from the package manager on every poll.
		a.getAppActivity().checkPermissions(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
				.onCompletion((result, fail) -> checkConnection());
	}

	/** Stops GPS (if running) and the connection watcher entirely -- used when the Fuel Log addon
	 * itself is disabled from Settings. */
	public void stop() {
		handler.removeCallbacks(connectionWatcher);
		watching = false;
		stopGps();
	}

	private void checkConnection() {
		handler.removeCallbacks(connectionWatcher);
		if (!watching) return;

		// Re-read on every poll rather than latched once in start(): the permission can be granted
		// (or revoked) from the phone's own UI long after this singleton was first started, and on
		// the car activity there is no permission dialog at all, so a one-shot read taken at start
		// time would have pinned this to false for the whole process lifetime.
		hasPermission =
				ActivityCompat.checkSelfPermission(appCtx, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
						|| ActivityCompat.checkSelfPermission(appCtx, ACCESS_COARSE_LOCATION) ==
						PERMISSION_GRANTED;
		boolean connected = hasPermission && isConnectedToAndroidAuto();
		if (connected && !gpsActive) {
			startGps();
			logTripEvent(FuelLogEntry.Type.TRIP_START);
		} else if (!connected && gpsActive) {
			// Captured before stopGps() clears lastLocation, so "Trip Ended" gets the location the
			// car was actually at, not "unknown".
			logTripEvent(FuelLogEntry.Type.TRIP_END);
			stopGps();
		}

		handler.postDelayed(connectionWatcher, CONNECTION_POLL_MS);
	}

	/**
	 * Auto-inserts a Trip Started/Trip Ended {@link FuelLogEntry} at an Android Auto
	 * connect/disconnect transition, carrying the odometer-since-last-refuel distance at that
	 * moment and the best location currently known -- same fields a manual refuel entry carries,
	 * just without resetting the trip odometer. The location name is filled in asynchronously
	 * (reverse geocoding is a network call) so logging the event itself never waits on it.
	 */
	private void logTripEvent(FuelLogEntry.Type type) {
		if (prefs == null) return;

		PreferenceStore p = prefs;
		Location loc = (lastLocation != null) ? lastLocation : getBestLastKnownLocation();
		long now = System.currentTimeMillis();
		FuelLogEntry entry = new FuelLogEntry(now, (float) FuelLogStore.getTripDistanceKm(p), "",
				(loc != null) ? loc.getLatitude() : Double.NaN,
				(loc != null) ? loc.getLongitude() : Double.NaN, now, type);
		FuelLogStore.addEntry(p, entry);
		notifyListeners();

		if (loc != null) {
			ReverseGeocoder.reverseGeocode(loc.getLatitude(), loc.getLongitude()).onSuccess(name -> {
				entry.location = name;
				FuelLogStore.updateEntry(p, entry);
				notifyListeners();
			});
		}
	}

	/** A location fix that doesn't require waiting for a fresh GPS update -- used for "Trip
	 * Started" since GPS has often only just been (re)requested and {@link #lastLocation} is still
	 * null at that instant. */
	@Nullable
	private Location getBestLastKnownLocation() {
		if ((locationManager == null) || !hasPermission) return null;
		Location best = null;
		for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
			try {
				Location l = locationManager.getLastKnownLocation(provider);
				if ((l != null) && ((best == null) || (l.getTime() > best.getTime()))) best = l;
			} catch (SecurityException | IllegalArgumentException ignore) {
				// Provider unavailable or permission revoked -- fall through to the next one.
			}
		}
		return best;
	}

	private void notifyListeners() {
		for (Runnable r : listeners) r.run();
	}

	private void startGps() {
		if (gpsActive || (locationManager == null)) return;
		gpsActive = true;

		try {
			locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_MS,
					MIN_DISTANCE_M, this);
		} catch (IllegalArgumentException | SecurityException ex) {
			Log.d("GPS provider unavailable for fuel tracking: ", ex);
		}
		try {
			locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_TIME_MS,
					MIN_DISTANCE_M, this);
		} catch (IllegalArgumentException | SecurityException ex) {
			Log.d("Network location provider unavailable for fuel tracking: ", ex);
		}
	}

	private void stopGps() {
		if (!gpsActive) return;
		gpsActive = false;
		// The next reconnect should measure fresh from wherever the car actually is, not resume
		// from a fix taken possibly hours and miles away from the last disconnect.
		lastLocation = null;
		try {
			locationManager.removeUpdates(this);
		} catch (SecurityException ignore) {
			// Permission was revoked from under us -- nothing to clean up.
		}
	}

	/** True while GPS updates are actually being requested right now (i.e. connected to Android
	 * Auto), not merely while the connection watcher is armed. */
	public boolean isTracking() {
		return gpsActive;
	}

	@Nullable
	public Location getLastLocation() {
		return lastLocation;
	}

	public void addListener(Runnable r) {
		listeners.add(r);
	}

	public void removeListener(Runnable r) {
		listeners.remove(r);
	}

	@Override
	public void onLocationChanged(Location location) {
		if (location.getAccuracy() > MAX_ACCURACY_M) {
			lastLocation = location;
			return;
		}

		Location prev = lastLocation;
		lastLocation = location;
		if (prev == null) return;

		float dist = prev.distanceTo(location);
		if (dist < MIN_MOVEMENT_M) return;

		if (prefs != null) FuelLogStore.addTripDistanceMeters(prefs, dist);
		notifyListeners();
	}

	/**
	 * True while this app is actually connected to Android Auto -- either running as the native
	 * car activity ({@code MainCarActivity}, auto flavor only) or mirroring the phone UI onto the
	 * car's display.
	 * <p>
	 * This used to ask {@code MainActivity.getActiveInstance().isCarActivity()}, which can never be
	 * true: the projected screen is {@code MainCarActivity}, a completely separate class that isn't
	 * a {@code MainActivity} (nor even an {@code Activity}) at all, and {@code
	 * MainActivity#isCarActivity()} is hardcoded to return false. So the whole condition collapsed
	 * to "mirroring mode only" and GPS was never started on a real Android Auto head unit -- the
	 * trip odometer simply sat at whatever it was. {@link MainActivityDelegate#isCarActivityActive()}
	 * is tracked from the delegate's own create/destroy, which both Activities forward into, so it
	 * is correct for either one without needing a live {@code ActivityDelegate} lookup here (this
	 * singleton can outlive any one Activity instance).
	 */
	private static boolean isConnectedToAndroidAuto() {
		return FermataApplication.get().isMirroringMode() ||
				MainActivityDelegate.isCarActivityActive();
	}
}
