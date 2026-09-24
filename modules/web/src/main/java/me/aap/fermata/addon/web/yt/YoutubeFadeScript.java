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
 * Loads the {@code youtube_fade.js} content script (smooth volume fades on play/pause/stop/video
 * switch), mirroring {@link YoutubeEqualizerScript}'s script-loading pattern.
 */
final class YoutubeFadeScript {
	private static String script;

	private YoutubeFadeScript() {
	}

	static String getScript(Context ctx) {
		String s = script;
		if (s != null) return s;

		try (InputStream in = ctx.getResources().openRawResource(R.raw.youtube_fade);
				 ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024)) {
			byte[] buf = new byte[4096];
			for (int n = in.read(buf); n != -1; n = in.read(buf)) {
				out.write(buf, 0, n);
			}
			return script = new String(out.toByteArray(), UTF_8);
		} catch (Resources.NotFoundException | IOException ex) {
			Log.e(ex, "Failed to load Fade script");
			return script = "";
		}
	}
}
