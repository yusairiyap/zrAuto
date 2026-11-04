package me.aap.fermata.ui.transition;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.text.format.DateFormat;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import me.aap.fermata.R;

/** The little live facts the transition screen can show: the time and date, the battery, the network. */
public final class TransitionFacts {
	private TransitionFacts() {
	}

	/** The time (0), the date (1) or both (2). */
	public static String clock(Context ctx, int opt) {
		Date now = new Date();
		String time = DateFormat.getTimeFormat(ctx).format(now);
		String date = DateFormat.getMediumDateFormat(ctx).format(now);
		return switch (opt) {
			case 1 -> date;
			case 2 -> date + "  " + time;
			default -> time;
		};
	}

	/** The keys chosen for an info row, in order, only the known ones. */
	public static List<String> chosen(@Nullable String csv) {
		List<String> out = new ArrayList<>();
		if (csv == null) return out;
		for (String k : csv.split(",")) {
			k = k.trim();
			if (k.isEmpty() || out.contains(k)) continue;
			for (String known : TransitionConfig.INFO_KEYS) {
				if (known.equals(k)) {
					out.add(k);
					break;
				}
			}
		}
		return out;
	}

	/** The chosen facts one after the other, or null when there are none to show. */
	@Nullable
	public static String info(Context ctx, @Nullable String csv) {
		StringBuilder sb = new StringBuilder();
		for (String k : chosen(csv)) {
			String v = value(ctx, k);
			if (v == null) continue;
			if (sb.length() > 0) sb.append("   •   ");
			sb.append(v);
		}
		return (sb.length() == 0) ? null : sb.toString();
	}

	@Nullable
	private static String value(Context ctx, String key) {
		switch (key) {
			case "time":
				return clock(ctx, 0);
			case "date":
				return clock(ctx, 1);
			case "battery":
			case "temp": {
				Intent b = ctx.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
				if (b == null) return null;
				if (key.equals("temp")) {
					int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
					return (t == Integer.MIN_VALUE) ? null : String.format(java.util.Locale.getDefault(), "%.1f°C", t / 10f);
				}
				int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
				int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
				if ((level < 0) || (scale <= 0)) return null;
				boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
				return (level * 100 / scale) + "%" + (plugged ? " ⚡" : "");
			}
			case "network": {
				try {
					ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
					NetworkCapabilities nc = (cm == null) ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
					if (nc == null) return ctx.getString(R.string.tr_info_offline);
					if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return ctx.getString(R.string.tr_info_wifi);
					if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return ctx.getString(R.string.tr_info_mobile);
					return ctx.getString(R.string.tr_info_online);
				} catch (RuntimeException ex) {
					return null;
				}
			}
			default:
				return null;
		}
	}

	/** The name of an info key in the settings. */
	public static int nameRes(String key) {
		return switch (key) {
			case "time" -> R.string.tr_info_time;
			case "date" -> R.string.tr_info_date;
			case "battery" -> R.string.tr_info_battery;
			case "temp" -> R.string.tr_info_temp;
			default -> R.string.tr_info_network;
		};
	}
}
