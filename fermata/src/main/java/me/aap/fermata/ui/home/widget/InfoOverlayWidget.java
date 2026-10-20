package me.aap.fermata.ui.home.widget;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.fermata.addon.power.PowerUsageAddon;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.utils.pref.PreferenceStore.Pref;

import me.aap.utils.function.BooleanSupplier;

/**
 * The info overlay's items (clock, battery, temperature, power, data, distance...) as round
 * switches: lit when the overlay shows the item, dim when not. A tap flips it, and the overlay
 * follows at once; the header opens the page that orders and styles them.
 */
final class InfoOverlayWidget extends TileWidget {
	private static final String[] KEYS = {"clock", "battery", "temp", "power", "data_usage", "data_remaining",
			"data_session", "downloaded", "distance"};

	InfoOverlayWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected int tabId() {
		return R.id.info_overlay_fragment;
	}

	private static boolean offered(String key) {
		return switch (key) {
			case "power" -> PowerUsageAddon.isEnabled();
			case "data_usage", "data_remaining", "data_session" -> DataUsageWidget.enabled();
			case "distance" -> FuelWidget.enabled();
			default -> true;
		};
	}

	private static Pref<BooleanSupplier> prefOf(String key) {
		return switch (key) {
			case "downloaded" -> MainActivityPrefs.INFO_OVERLAY_SHOW_DOWNLOADED;
			case "clock" -> MainActivityPrefs.INFO_OVERLAY_SHOW_CLOCK;
			case "battery" -> MainActivityPrefs.INFO_OVERLAY_SHOW_BATTERY_PCT;
			case "temp" -> MainActivityPrefs.INFO_OVERLAY_SHOW_BATTERY_TEMP;
			case "distance" -> MainActivityPrefs.INFO_OVERLAY_SHOW_DISTANCE;
			case "data_usage" -> MainActivityPrefs.INFO_OVERLAY_SHOW_DATA_USAGE;
			case "data_session" -> MainActivityPrefs.INFO_OVERLAY_SHOW_DATA_SESSION;
			case "power" -> MainActivityPrefs.INFO_OVERLAY_SHOW_POWER;
			default -> MainActivityPrefs.INFO_OVERLAY_SHOW_DATA_REMAINING;
		};
	}

	/** The pref as the screen it runs on has it: the car's twin on the car screen. */
	@SuppressWarnings("unchecked")
	private Pref<BooleanSupplier> prefFor(String key) {
		Pref<BooleanSupplier> p = prefOf(key);
		MainActivityDelegate a = host.activity();
		if (!BuildConfig.AUTO || !a.isCarActivity()) return p;
		Pref<?> twin = MainActivityPrefs.infoAA(p);
		return (twin == null) ? p : (Pref<BooleanSupplier>) twin;
	}

	private static int iconOf(String key) {
		return switch (key) {
			case "downloaded" -> R.drawable.download;
			case "clock" -> R.drawable.clock;
			case "battery" -> R.drawable.battery;
			case "temp" -> R.drawable.thermometer;
			case "distance" -> R.drawable.distance;
			case "data_usage" -> R.drawable.data_usage;
			case "data_session" -> R.drawable.car;
			case "power" -> R.drawable.power_usage;
			default -> R.drawable.data_remaining;
		};
	}

	private static int nameOf(String key) {
		return switch (key) {
			case "downloaded" -> R.string.info_item_downloaded;
			case "clock" -> R.string.info_item_clock;
			case "battery" -> R.string.info_item_battery;
			case "temp" -> R.string.info_item_temp;
			case "distance" -> R.string.info_item_distance;
			case "data_usage" -> R.string.info_item_data_usage;
			case "data_session" -> R.string.info_item_data_session;
			case "power" -> R.string.info_item_power;
			default -> R.string.info_item_data_remaining;
		};
	}

	@Override
	protected void fill() {
		MainActivityPrefs prefs = host.activity().getPrefs();
		int i = 0;
		for (String key : KEYS) {
			if (!offered(key)) continue;
			Pref<BooleanSupplier> p = prefFor(key);
			boolean on = prefs.getBooleanPref(p);
			int tint = on ? hue(i) : color(ROLE_TERTIARY);
			flow.addView(tile(iconOf(key), ctx.getString(nameOf(key)), tint, () -> {
				prefs.applyBooleanPref(p, !prefs.getBooleanPref(p));
				rebuild();
			}));
			i++;
		}
	}
}
