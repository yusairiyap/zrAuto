package me.aap.fermata.addon.web.yt;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.Context;
import android.content.res.Resources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import me.aap.fermata.addon.web.R;
import me.aap.utils.log.Log;

/**
 * Loads the {@code youtube_equalizer.js} content script and builds the JSON config pushed to
 * {@code window.FermataEqualizer.configure()}, mirroring {@link YoutubeSponsorBlock}'s
 * script-loading/config-JSON pattern.
 */
final class YoutubeEqualizerScript {
	private static String script;

	private YoutubeEqualizerScript() {
	}

	static String getScript(Context ctx) {
		String s = script;
		if (s != null) return s;

		try (InputStream in = ctx.getResources().openRawResource(R.raw.youtube_equalizer);
				 ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024)) {
			byte[] buf = new byte[4096];
			for (int n = in.read(buf); n != -1; n = in.read(buf)) {
				out.write(buf, 0, n);
			}
			return script = new String(out.toByteArray(), UTF_8);
		} catch (Resources.NotFoundException | IOException ex) {
			Log.e(ex, "Failed to load Equalizer script");
			return script = "";
		}
	}

	static String getConfigJson(YoutubeAddon addon) {
		int[] bands = addon.eqBands();
		StringBuilder sb = new StringBuilder(256);
		sb.append("{\"eqEnabled\":").append(addon.eqEnabled()).append(",\"bands\":[");

		for (int i = 0; i < bands.length; i++) {
			if (i != 0) sb.append(',');
			sb.append(bands[i] / 100f);
		}

		sb.append("],\"bassEnabled\":").append(addon.bassEnabled())
				.append(",\"bassGain\":").append(addon.bassStrength() * 18f / 1000f)
				.append(",\"virtEnabled\":").append(addon.virtEnabled())
				.append(",\"virtStrength\":").append(addon.virtStrength() / 1000f)
				.append(",\"reverbEnabled\":").append(addon.reverbEnabled())
				.append(",\"reverbStrength\":").append(addon.reverbStrength() / 1000f)
				.append(",\"reverbDuration\":").append(addon.reverbDuration() / 1000f)
				.append(",\"reverbEngine\":\"").append((addon.reverbEngine() == 1) ? "convolution" : "smooth")
				.append("\"}");
		return sb.toString();
	}
}
