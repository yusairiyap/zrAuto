package me.aap.fermata.addon.fuel;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A single Fuel Log record: either a refuel (the distance travelled since the previous refuel) or
 * an auto-logged trip boundary (Android Auto connected/disconnected) -- either way it carries the
 * odometer-since-last-refuel distance, the user's location (as a nice name, plus its coordinates
 * if known), and when it happened.
 */
public class FuelLogEntry {
	/** What kind of event this entry records. */
	public enum Type {
		REFUEL, TRIP_START, TRIP_END
	}

	public final long id;
	public float distanceKm;
	public String location;
	public double lat;
	public double lon;
	public long time;
	public final Type type;

	public FuelLogEntry(long id, float distanceKm, String location, double lat, double lon,
											 long time) {
		this(id, distanceKm, location, lat, lon, time, Type.REFUEL);
	}

	public FuelLogEntry(long id, float distanceKm, String location, double lat, double lon,
											 long time, Type type) {
		this.id = id;
		this.distanceKm = distanceKm;
		this.location = location;
		this.lat = lat;
		this.lon = lon;
		this.time = time;
		this.type = type;
	}

	public boolean hasLocation() {
		return !Double.isNaN(lat) && !Double.isNaN(lon);
	}

	JSONObject toJson() throws JSONException {
		JSONObject o = new JSONObject();
		o.put("id", id);
		o.put("distanceKm", distanceKm);
		o.put("location", location);
		if (hasLocation()) {
			o.put("lat", lat);
			o.put("lon", lon);
		}
		o.put("time", time);
		o.put("type", type.name());
		return o;
	}

	static FuelLogEntry fromJson(JSONObject o) throws JSONException {
		Type type;
		try {
			type = Type.valueOf(o.optString("type", Type.REFUEL.name()));
		} catch (IllegalArgumentException ex) {
			// Unknown/corrupted value -- treat unrecognized entries as plain refuels rather than
			// failing to load the whole list.
			type = Type.REFUEL;
		}
		return new FuelLogEntry(o.getLong("id"), (float) o.getDouble("distanceKm"),
				o.optString("location", ""), o.optDouble("lat", Double.NaN),
				o.optDouble("lon", Double.NaN), o.getLong("time"), type);
	}
}
