package me.aap.fermata.addon.fuel;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import java.util.concurrent.CopyOnWriteArrayList;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.ui.activity.MainActivity;
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
 * GPS fixes keep arriving whenever tracking is running, but distance is only ever added to the
 * trip total while the app is actually connected to Android Auto (see {@link
 * #isConnectedToAndroidAuto()}) -- otherwise a phone carried while walking or running (with the
 * app merely open, e.g. for its Info Overlay) would misreport that movement as driving.
 */
public class FuelTracker implements LocationListener {
	/** Fixes worse than this (metres) are too noisy to add to the trip total. */
	private static final float MAX_ACCURACY_M = 30f;
	/** Movement below this (metres) between fixes is treated as GPS jitter while stationary. */
	private static final float MIN_MOVEMENT_M = 5f;
	private static final long MIN_TIME_MS = 3000L;
	private static final float MIN_DISTANCE_M = 5f;

	private static FuelTracker instance;

	private final Context appCtx;
	private final LocationManager locationManager;
	private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
	@Nullable
	private Location lastLocation;
	private boolean tracking;
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

	/** Requests location permission if needed, then starts GPS updates. Safe to call repeatedly. */
	public void start(MainActivityDelegate a) {
		prefs = a.getPrefs();
		if (tracking) return;
		a.getAppActivity().checkPermissions(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
				.onSuccess(result -> startIfGranted());
	}

	private void startIfGranted() {
		if (tracking || (locationManager == null)) return;
		if (ActivityCompat.checkSelfPermission(appCtx, ACCESS_FINE_LOCATION) != PERMISSION_GRANTED) return;
		tracking = true;

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

	public void stop() {
		if (!tracking) return;
		tracking = false;
		try {
			locationManager.removeUpdates(this);
		} catch (SecurityException ignore) {
			// Permission was revoked from under us -- nothing to clean up.
		}
	}

	public boolean isTracking() {
		return tracking;
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

		// Always advance lastLocation, connected or not: skipping it while disconnected would leave
		// a stale fix behind, so the first update after reconnecting would compute a bogus jump
		// spanning however long -- and however far -- the app went unconnected in between.
		Location prev = lastLocation;
		lastLocation = location;
		if (prev == null) return;
		if (!isConnectedToAndroidAuto()) return;

		float dist = prev.distanceTo(location);
		if (dist < MIN_MOVEMENT_M) return;

		if (prefs != null) FuelLogStore.addTripDistanceMeters(prefs, dist);
		for (Runnable r : listeners) r.run();
	}

	/**
	 * True while this app is actually connected to Android Auto -- either running as the native
	 * car activity ({@code MainCarActivity}, auto flavor only) or mirroring the phone UI onto the
	 * car's display -- mirroring the same check {@code MainActivityDelegate.isCarActivity()} does,
	 * but without needing a live {@code ActivityDelegate} (this singleton can outlive any one
	 * Activity instance).
	 */
	private static boolean isConnectedToAndroidAuto() {
		if (FermataApplication.get().isMirroringMode()) return true;
		MainActivity a = MainActivity.getActiveInstance();
		return (a != null) && a.isCarActivity();
	}
}
