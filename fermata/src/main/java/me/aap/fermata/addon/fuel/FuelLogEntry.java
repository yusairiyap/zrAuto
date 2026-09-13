package me.aap.fermata.addon.fuel;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A single refuel record: the distance travelled since the previous refuel, the fuel
 * station/user location (as a nice name, plus its coordinates if known), and when it happened.
 */
public class FuelLogEntry {
	public final long id;
	public float distanceKm;
	public String location;
	public double lat;
	public double lon;
	public long time;

	public FuelLogEntry(long id, float distanceKm, String location, double lat, double lon,
											 long time) {
		this.id = id;
		this.distanceKm = distanceKm;
		this.location = location;
		this.lat = lat;
		this.lon = lon;
		this.time = time;
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
		return o;
	}

	static FuelLogEntry fromJson(JSONObject o) throws JSONException {
		return new FuelLogEntry(o.getLong("id"), (float) o.getDouble("distanceKm"),
				o.optString("location", ""), o.optDouble("lat", Double.NaN),
				o.optDouble("lon", Double.NaN), o.getLong("time"));
	}
}
