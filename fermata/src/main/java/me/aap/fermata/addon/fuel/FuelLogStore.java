package me.aap.fermata.addon.fuel;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Persists the Fuel Log's entries and the current (not-yet-refuelled) trip distance in the app's
 * {@link PreferenceStore}, following the same house style as {@code FavoritesPrefs} rather than
 * pulling in a database dependency this project doesn't otherwise use -- entries are serialized
 * as a small JSON array under a single string preference.
 */
public class FuelLogStore {
	public static final Pref<Supplier<String>> ENTRIES = Pref.s("FUEL_LOG_ENTRIES", "[]");
	/** Distance accumulated by GPS tracking since the last refuel, in metres. */
	public static final Pref<DoubleSupplier> TRIP_DISTANCE_M = Pref.f("FUEL_LOG_TRIP_DISTANCE_M", 0f);

	public static List<FuelLogEntry> getEntries(PreferenceStore prefs) {
		String json = prefs.getStringPref(ENTRIES);
		List<FuelLogEntry> list = new ArrayList<>();
		try {
			JSONArray arr = new JSONArray(json);
			for (int i = 0; i < arr.length(); i++) {
				list.add(FuelLogEntry.fromJson(arr.getJSONObject(i)));
			}
		} catch (JSONException ex) {
			Log.e(ex, "Failed to parse fuel log entries");
		}
		list.sort(Comparator.comparingLong((FuelLogEntry e) -> e.time).reversed());
		return list;
	}

	private static void setEntries(PreferenceStore prefs, List<FuelLogEntry> entries) {
		JSONArray arr = new JSONArray();
		for (FuelLogEntry e : entries) {
			try {
				arr.put(e.toJson());
			} catch (JSONException ex) {
				Log.e(ex, "Failed to serialize fuel log entry");
			}
		}
		prefs.applyStringPref(ENTRIES, arr.toString());
	}

	public static void addEntry(PreferenceStore prefs, FuelLogEntry entry) {
		List<FuelLogEntry> entries = getEntries(prefs);
		entries.add(entry);
		setEntries(prefs, entries);
	}

	public static void updateEntry(PreferenceStore prefs, FuelLogEntry entry) {
		List<FuelLogEntry> entries = getEntries(prefs);
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).id == entry.id) {
				entries.set(i, entry);
				break;
			}
		}
		setEntries(prefs, entries);
	}

	public static void removeEntry(PreferenceStore prefs, long id) {
		List<FuelLogEntry> entries = getEntries(prefs);
		entries.removeIf(e -> e.id == id);
		setEntries(prefs, entries);
	}

	@Nullable
	public static FuelLogEntry getLastEntry(PreferenceStore prefs) {
		List<FuelLogEntry> entries = getEntries(prefs);
		return entries.isEmpty() ? null : entries.get(0);
	}

	public static double getTripDistanceKm(PreferenceStore prefs) {
		return prefs.getFloatPref(TRIP_DISTANCE_M) / 1000.0;
	}

	public static void addTripDistanceMeters(PreferenceStore prefs, float meters) {
		float total = prefs.getFloatPref(TRIP_DISTANCE_M) + meters;
		prefs.applyFloatPref(TRIP_DISTANCE_M, total);
	}

	public static void resetTripDistance(PreferenceStore prefs) {
		prefs.applyFloatPref(TRIP_DISTANCE_M, 0f);
	}

	private FuelLogStore() {}
}
