package me.aap.fermata.addon.fuel;

import static me.aap.utils.async.Completed.completed;

import android.content.Context;
import android.location.Location;

import androidx.annotation.StringRes;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;

/**
 * The "Refuel" modal -- shows the distance travelled since the last refuel (editable before
 * committing) and the current location's nice name (reverse-geocoded, also editable), then stores
 * a new {@link FuelLogEntry} and resets the trip odometer. Reused by both the Fuel Log tab's own
 * Refuel button and the {@code Action.REFUEL} FAB action, so it doesn't require navigating to the
 * tab first. Also reused (via {@link #edit}) for correcting an existing entry's distance/location.
 */
public class FuelRefuelDialog {

	public static void show(MainActivityDelegate a) {
		show(a, () -> {});
	}

	/** Same as {@link #show(MainActivityDelegate)}, plus a callback once the entry is saved -- used
	 * by the Fuel Log tab's own Refuel button so its overview card/list refresh immediately instead
	 * of waiting for the next incidental update (e.g. the next GPS fix). */
	public static void show(MainActivityDelegate a, Runnable onDone) {
		Context ctx = a.getContext();
		float distanceKm = (float) FuelLogStore.getTripDistanceKm(a.getPrefs());
		Location loc = FuelTracker.get(ctx).getLastLocation();
		FutureSupplier<String> nameFuture = (loc != null) ?
				ReverseGeocoder.reverseGeocode(loc.getLatitude(), loc.getLongitude()) : completed("");

		nameFuture.onCompletion((name, err) -> {
			String initialName = ((err == null) && (name != null)) ? name : "";
			openFields(ctx, R.string.fuel_log_refuel, distanceKm, initialName, (distance, location) -> {
				long now = System.currentTimeMillis();
				FuelLogEntry entry = new FuelLogEntry(now, distance, location,
						(loc != null) ? loc.getLatitude() : Double.NaN,
						(loc != null) ? loc.getLongitude() : Double.NaN, now);
				FuelLogStore.addEntry(a.getPrefs(), entry);
				FuelLogStore.resetTripDistance(a.getPrefs());
				UiUtils.showToast(ctx, R.string.fuel_log_refuel_done);
				onDone.run();
			});
		});
	}

	/** Lets the user correct an existing entry's distance/location -- its date/time is unchanged. */
	public static void edit(MainActivityDelegate a, FuelLogEntry entry, Runnable onDone) {
		Context ctx = a.getContext();
		openFields(ctx, R.string.fuel_log_edit_entry, entry.distanceKm, entry.location,
				(distance, location) -> {
					entry.distanceKm = distance;
					entry.location = location;
					FuelLogStore.updateEntry(a.getPrefs(), entry);
					onDone.run();
				});
	}

	private interface OnConfirmed {
		void confirmed(float distanceKm, String location);
	}

	private static void openFields(Context ctx, @StringRes int title, float distanceKm,
																	String initialLocation, OnConfirmed onConfirmed) {
		Pref<DoubleSupplier> distancePref = Pref.f("distanceKm", distanceKm);
		Pref<Supplier<String>> locationPref = Pref.s("location", initialLocation);

		UiUtils.queryPrefs(ctx, title, (store, set) -> {
			set.addFloatPref(o -> {
				o.store = store;
				o.pref = distancePref;
				o.title = R.string.fuel_log_distance_km;
				o.seekMin = 0;
				o.seekMax = 20000;
				o.scale = 0.1f;
			});
			set.addStringPref(o -> {
				o.store = store;
				o.pref = locationPref;
				o.title = R.string.fuel_log_location;
			});
		}, null).onSuccess(
				store -> onConfirmed.confirmed(store.getFloatPref(distancePref), store.getStringPref(locationPref)));
	}

	private FuelRefuelDialog() {}
}
