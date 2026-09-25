package me.aap.fermata.spotify;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.spotify.SpotifyImportModel.Playlist;
import me.aap.fermata.spotify.SpotifyImportModel.Track;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.utils.log.Log;

/**
 * Saves the import screen's session -- the playlists added to the import, their tracks and
 * selections, every YouTube match found so far -- so that matching a huge playlist, which can take
 * a long while, picks up where it left off after the app is closed or killed, instead of starting
 * over. Playlists only being looked at from the picker aren't saved.
 * <p>
 * {@link #toJson} runs on the main thread (the model is main-thread only); {@link #write} and
 * {@link #load} do file I/O and may run anywhere.
 */
public final class SpotifyImportStore {
	private static final String FILE = "spotify_import_session.json";
	private static final int VERSION = 1;

	private SpotifyImportStore() {
	}

	public static final class Session {
		public final List<Playlist> playlists;
		public final boolean matchingPaused;

		public Session(List<Playlist> playlists, boolean matchingPaused) {
			this.playlists = playlists;
			this.matchingPaused = matchingPaused;
		}
	}

	private static File file(Context ctx) {
		return new File(ctx.getFilesDir(), FILE);
	}

	/** Main thread: a snapshot of the session, or null if there's nothing worth keeping. */
	@Nullable
	public static String toJson(List<Playlist> playlists, boolean matchingPaused) {
		try {
			JSONArray pls = new JSONArray();

			for (Playlist pl : playlists) {
				if (!pl.included) continue;
				JSONObject p = new JSONObject();
				p.put("ref", pl.ref);
				p.put("name", pl.name);
				p.put("renamed", pl.renamed);
				p.putOpt("owner", pl.owner);
				p.putOpt("cover", pl.coverUrl);
				p.put("full", pl.fullList);
				p.put("loaded", pl.state == Playlist.STATE_LOADED);
				JSONArray tracks = new JSONArray();

				for (Track t : pl.tracks) {
					JSONObject j = new JSONObject();
					j.put("t", t.title);
					j.put("a", t.artists);
					j.put("d", t.durationMs);
					j.putOpt("img", t.imageUrl);
					if (!t.selected) j.put("off", true);
					// An interrupted search is simply redone after a restart.
					int state = (t.matchState == Track.MATCH_SEARCHING) ? Track.MATCH_NONE : t.matchState;
					if (state != Track.MATCH_NONE) j.put("s", state);
					if (t.match != null) j.put("m", video(t.match));
					if (t.userPicked) j.put("pick", true);
					tracks.put(j);
				}

				p.put("tracks", tracks);
				pls.put(p);
			}

			if (pls.length() == 0) return null;
			JSONObject root = new JSONObject();
			root.put("v", VERSION);
			root.put("paused", matchingPaused);
			root.put("playlists", pls);
			return root.toString();
		} catch (JSONException ex) {
			Log.e(ex, "Failed to serialize the Spotify import session");
			return null;
		}
	}

	/** Writes {@code json} (from {@link #toJson}), or deletes the session if it's null. */
	public static void write(Context ctx, @Nullable String json) {
		File f = file(ctx);

		if (json == null) {
			//noinspection ResultOfMethodCallIgnored
			f.delete();
			return;
		}

		File tmp = new File(f.getPath() + ".tmp");
		try (FileOutputStream out = new FileOutputStream(tmp)) {
			out.write(json.getBytes(UTF_8));
			out.getFD().sync();
		} catch (IOException ex) {
			Log.e(ex, "Failed to save the Spotify import session");
			return;
		}
		// Atomic replace: a crash mid-write never leaves a truncated session behind.
		if (!tmp.renameTo(f)) Log.e("Failed to save the Spotify import session: rename failed");
	}

	public static boolean exists(Context ctx) {
		return file(ctx).isFile();
	}

	@Nullable
	public static Session load(Context ctx) {
		File f = file(ctx);
		if (!f.isFile()) return null;

		try (FileInputStream in = new FileInputStream(f)) {
			byte[] data = new byte[(int) f.length()];
			int off = 0;
			for (int n; (off < data.length) && ((n = in.read(data, off, data.length - off)) != -1); ) {
				off += n;
			}
			JSONObject root = new JSONObject(new String(data, 0, off, UTF_8));
			JSONArray pls = root.getJSONArray("playlists");
			List<Playlist> playlists = new ArrayList<>(pls.length());

			for (int i = 0; i < pls.length(); i++) {
				JSONObject p = pls.getJSONObject(i);
				Playlist pl = new Playlist(p.getString("ref"));
				pl.name = p.optString("name");
				pl.renamed = p.optBoolean("renamed");
				pl.owner = str(p, "owner");
				pl.coverUrl = str(p, "cover");
				pl.fullList = p.optBoolean("full");
				JSONArray tracks = p.optJSONArray("tracks");

				if (tracks != null) {
					for (int n = 0; n < tracks.length(); n++) {
						JSONObject j = tracks.getJSONObject(n);
						Track t = new Track(j.optString("t"), j.optString("a"), j.optLong("d", -1));
						t.imageUrl = str(j, "img");
						t.selected = !j.optBoolean("off");
						t.matchState = j.optInt("s", Track.MATCH_NONE);
						JSONObject m = j.optJSONObject("m");
						if (m != null) t.match = video(m);
						t.userPicked = j.optBoolean("pick");
						// A failed search gets another go after a restart.
						if ((t.match == null) && (t.matchState == Track.MATCH_FAILED)) {
							t.matchState = Track.MATCH_NONE;
						}
						pl.tracks.add(t);
					}
				}

				pl.state = (p.optBoolean("loaded") && !pl.tracks.isEmpty()) ? Playlist.STATE_LOADED :
						Playlist.STATE_LOADING;
				playlists.add(pl);
			}

			return new Session(playlists, root.optBoolean("paused"));
		} catch (Exception ex) {
			Log.e(ex, "Failed to restore the Spotify import session");
			return null;
		}
	}

	private static JSONObject video(Video v) throws JSONException {
		JSONObject j = new JSONObject();
		j.put("id", v.videoId);
		j.put("t", v.title);
		j.putOpt("c", v.channel);
		j.putOpt("dt", v.durationText);
		j.put("d", v.durationMs);
		return j;
	}

	private static Video video(JSONObject j) {
		return new Video(j.optString("id"), j.optString("t"), str(j, "c"), str(j, "dt"),
				j.optLong("d", -1));
	}

	@Nullable
	private static String str(JSONObject o, String key) {
		if (o.isNull(key)) return null;
		String s = o.optString(key);
		return s.isEmpty() ? null : s;
	}
}
