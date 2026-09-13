package me.aap.fermata.addon.fuel;

import static java.nio.charset.StandardCharsets.UTF_8;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.async.Completed.failed;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.io.IoUtils;
import me.aap.utils.log.Log;
import me.aap.utils.net.http.HttpConnection;
import me.aap.utils.net.http.HttpStatusCode;

/**
 * Resolves a GPS position to a short, human-readable place name (e.g. a fuel station's name, or
 * failing that its street/town) for the Refuel dialog and the fuel log's entry list. Uses the free
 * OpenStreetMap Nominatim reverse-geocoding API -- no API key or account needed, unlike Google's
 * Geocoding API -- rather than Android's built-in {@code Geocoder}, whose backend isn't guaranteed
 * present on every device (e.g. some Android Auto host units lack Google Play services).
 */
public class ReverseGeocoder {
	private static final String USER_AGENT = "zrAuto-FuelLog/1.0 (+https://github.com/yusairiyap/zrauto)";

	public static FutureSupplier<String> reverseGeocode(double lat, double lon) {
		Promise<String> p = new Promise<>();
		HttpConnection.connect(o -> {
			o.url(String.format(Locale.US,
					"https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1",
					lat, lon));
			o.userAgent = USER_AGENT;
		}, (resp, err) -> {
			if (err != null) {
				p.completeExceptionally(err);
				return failed(err);
			} else if (resp.getStatusCode() != HttpStatusCode.OK) {
				IOException ex = new IOException("Reverse geocoding failed: " + resp.getReason());
				p.completeExceptionally(ex);
				return failed(ex);
			}

			return resp.getPayload((bb, perr) -> {
				try {
					if (perr != null) return failed(perr);
					bb = IoUtils.getFrom(bb);
					String jsonString = new String(bb.array(), bb.arrayOffset(), bb.remaining(), UTF_8);
					p.complete(extractName(new JSONObject(jsonString)));
				} catch (JSONException ex) {
					p.completeExceptionally(ex);
					return failed(ex);
				}
				return completedVoid();
			});
		});

		return p.main().ifFail(fail -> {
			Log.d("Reverse geocoding failed: ", fail);
			return "";
		});
	}

	private static String extractName(JSONObject json) {
		JSONObject addr = json.optJSONObject("address");
		if (addr != null) {
			for (String key : new String[]{"amenity", "shop", "fuel", "office", "building", "road"}) {
				String v = addr.optString(key, "");
				if (!v.isEmpty()) return v;
			}
		}

		String display = json.optString("display_name", "");
		if (!display.isEmpty()) return display.split(",")[0].trim();
		return "";
	}

	private ReverseGeocoder() {}
}
