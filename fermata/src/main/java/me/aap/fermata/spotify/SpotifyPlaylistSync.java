package me.aap.fermata.spotify;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.spotify.SpotifyImportModel.Track;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.function.Supplier;

/**
 * Brings a local playlist up to date with the Spotify playlist of the same name (or the one it was
 * imported from): Spotify tracks that aren't in it yet are matched on YouTube and added at the
 * top, in their Spotify order. Tracks already there are left alone, and nothing is removed.
 * <p>
 * Which Spotify tracks a playlist already has is remembered per Spotify playlist (see
 * {@link #remember}) -- recorded by the import too, so a playlist imported and later synced only
 * gets what's new. A playlist imported before that was remembered falls back to comparing titles.
 * <p>
 * Network work runs on a background thread; the callback is always called on the main thread.
 * Cancelling before the matches are written leaves the playlist untouched.
 */
public final class SpotifyPlaylistSync {
	private static final long SEARCH_DELAY_MS = 300;
	/** Flat {local playlist name, Spotify ref} pairs. */
	private static final Pref<Supplier<String[]>> SYNC_REFS = Pref.sa("SPOTIFY_SYNC_REFS");
	private static final ExecutorService executor = Executors.newSingleThreadExecutor();

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final AtomicBoolean cancelled = new AtomicBoolean();
	private final Callback callback;
	private boolean writing;

	public interface Callback {
		/** @param total 0 while the amount of work isn't known yet */
		void onProgress(String text, int done, int total);

		void onFinished(Result result);
	}

	public static final class Result {
		public static final int DONE = 0;
		public static final int UP_TO_DATE = 1;
		public static final int CANCELLED = 2;
		public static final int NOT_FOUND = 3;
		public static final int LOGIN_REQUIRED = 4;
		public static final int FAILED = 5;

		public final int status;
		public final int added;
		/** New Spotify tracks no YouTube video was found for; tried again on the next sync. */
		public final int missing;
		@Nullable
		public final String error;

		Result(int status, int added, int missing, @Nullable String error) {
			this.status = status;
			this.added = added;
			this.missing = missing;
			this.error = error;
		}

		public String getMessage(Context ctx, String name) {
			return switch (status) {
				case DONE -> {
					String s = ctx.getResources().getQuantityString(R.plurals.spotify_sync_added, added, added);
					yield (missing > 0) ? s + " " + ctx.getString(R.string.spotify_sync_missing, missing) : s;
				}
				case UP_TO_DATE -> (missing > 0) ?
						ctx.getString(R.string.spotify_sync_up_to_date) + " " +
								ctx.getString(R.string.spotify_sync_missing, missing) :
						ctx.getString(R.string.spotify_sync_up_to_date);
				case CANCELLED -> ctx.getString(R.string.spotify_sync_cancelled);
				case NOT_FOUND -> ctx.getString(R.string.spotify_sync_not_found, name);
				case LOGIN_REQUIRED -> ctx.getString(R.string.spotify_sync_login);
				default -> ctx.getString(R.string.spotify_sync_failed, String.valueOf(error));
			};
		}
	}

	private SpotifyPlaylistSync(Callback callback) {
		this.callback = callback;
	}

	/** Starts syncing {@code local}; see the class description. */
	public static SpotifyPlaylistSync start(MediaLib lib, Playlist local, Callback callback) {
		SpotifyPlaylistSync s = new SpotifyPlaylistSync(callback);
		s.run(lib, local);
		return s;
	}

	/** Stops as soon as possible; no effect once the matches are being written. */
	public void cancel() {
		if (!writing) cancelled.set(true);
	}

	public boolean isCancellable() {
		return !writing && !cancelled.get();
	}

	private void run(MediaLib lib, Playlist local) {
		Context ctx = FermataApplication.get();
		String name = local.getName();
		progress(ctx.getString(R.string.spotify_sync_reading), 0, 0);

		local.getUnsortedChildren().main().onCompletion((children, err) -> {
			if (err != null) {
				finish(new Result(Result.FAILED, 0, 0, String.valueOf(err.getMessage())));
				return;
			}
			List<String> localNames = new ArrayList<>(children.size());
			for (Item i : children) localNames.add(i.getName().toLowerCase(Locale.ROOT));
			String storedRef = getRef(name);
			Set<String> storedKeys = (storedRef == null) ? new LinkedHashSet<>() :
					new LinkedHashSet<>(Arrays.asList(SpotifyPrefs.store().getStringArrayPref(keysPref(storedRef))));
			executor.execute(() -> sync(ctx, lib, local, name, localNames, storedRef, storedKeys));
		});
	}

	/** On the background thread. */
	private void sync(Context ctx, MediaLib lib, Playlist local, String name,
										List<String> localNames, @Nullable String storedRef, Set<String> storedKeys) {
		try {
			String ref = (storedRef != null) ? storedRef : findRef(name);
			if (ref == null) {
				boolean account = SpotifyPrefs.isAccountSource() && SpotifyAuth.isLoggedIn();
				post(new Result(account ? Result.NOT_FOUND : Result.LOGIN_REQUIRED, 0, 0, null));
				return;
			}
			if (cancelled.get()) {
				post(new Result(Result.CANCELLED, 0, 0, null));
				return;
			}

			SpotifyImportModel.Playlist sp = new SpotifyImportModel.Playlist(ref);
			sp.name = name;
			SpotifyClient.fetch(sp);

			Set<String> known = storedKeys;
			// Imported before the tracks were remembered: tell by the titles instead.
			boolean byTitle = known.isEmpty();
			List<Track> added = new ArrayList<>();

			for (Track t : sp.tracks) {
				String key = key(t);
				if (known.contains(key)) continue;
				if (byTitle && hasTitle(localNames, t.title)) known.add(key);
				else added.add(t);
			}

			if (added.isEmpty()) {
				handler.post(() -> {
					saveKeys(ref, known);
					saveRef(name, ref);
				});
				post(new Result(Result.UP_TO_DATE, 0, 0, null));
				return;
			}

			List<Video> videos = new ArrayList<>(added.size());
			Map<String, Track> byVideo = new HashMap<>();
			int missing = 0;

			for (int i = 0; i < added.size(); i++) {
				if (cancelled.get()) {
					post(new Result(Result.CANCELLED, 0, 0, null));
					return;
				}
				Track t = added.get(i);
				progress(ctx.getString(R.string.spotify_sync_finding, i + 1, added.size(),
						t.displayName()), i, added.size());
				List<Video> found = null;
				try {
					found = YoutubeSearch.searchTrack(t, 3);
				} catch (Exception ex) {
					Log.e(ex, "Spotify sync: YouTube search failed: ", t.searchQuery());
				}
				Video v = ((found == null) || found.isEmpty()) ? null : found.get(0);
				if ((v == null) || byVideo.containsKey(v.videoId)) {
					if (v == null) missing++;
				} else {
					videos.add(v);
					byVideo.put(v.videoId, t);
				}
				if (i < added.size() - 1) Thread.sleep(SEARCH_DELAY_MS);
			}

			int nMissing = missing;
			handler.post(() -> write(ctx, lib, local, name, ref, known, videos, byVideo, nMissing));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			post(new Result(Result.CANCELLED, 0, 0, null));
		} catch (SpotifyAuth.AuthException ex) {
			post(new Result(Result.LOGIN_REQUIRED, 0, 0, null));
		} catch (Exception ex) {
			Log.e(ex, "Spotify sync failed: ", name);
			String msg = (ex.getMessage() != null) ? ex.getMessage() : ex.toString();
			post(new Result(Result.FAILED, 0, 0, msg));
		}
	}

	/** On the main thread. */
	private void write(Context ctx, MediaLib lib, Playlist local, String name, String ref,
										 Set<String> known, List<Video> videos, Map<String, Track> byVideo,
										 int missing) {
		if (cancelled.get()) {
			finish(new Result(Result.CANCELLED, 0, 0, null));
			return;
		}
		// Already there, as far as the next sync is concerned.
		saveKeys(ref, known);
		saveRef(name, ref);

		if (videos.isEmpty()) {
			finish(new Result(Result.UP_TO_DATE, 0, missing, null));
			return;
		}

		writing = true;
		progress(ctx.getString(R.string.spotify_sync_saving), 0, 0);
		SpotifyPlaylistWriter.Entry e = new SpotifyPlaylistWriter.Entry(name, videos, byVideo, ref);
		SpotifyPlaylistWriter.writeInto(lib, local, e).main().onCompletion((n, err) -> {
			if (err != null) {
				Log.e(err, "Spotify sync: failed to write ", name);
				String msg = (err.getMessage() != null) ? err.getMessage() : err.toString();
				finish(new Result(Result.FAILED, 0, 0, msg));
			} else {
				finish(new Result(Result.DONE, (n != null) ? n : 0, missing, null));
			}
		});
	}

	/** On the background thread: the signed-in user's playlist of that name, if any. */
	@Nullable
	private static String findRef(String name) throws Exception {
		if (!SpotifyPrefs.isAccountSource() || !SpotifyAuth.isLoggedIn()) return null;
		for (SpotifyApi.PlaylistInfo pi : SpotifyApi.listMyPlaylists()) {
			if (SpotifyPlaylistWriter.sanitizeName(pi.name).equalsIgnoreCase(name)) return pi.ref;
		}
		return null;
	}

	private static boolean hasTitle(List<String> localNames, String title) {
		String t = title.toLowerCase(Locale.ROOT).trim();
		// Too short to tell anything apart by.
		if (t.length() < 4) return false;
		for (String n : localNames) {
			if (n.contains(t)) return true;
		}
		return false;
	}

	private void progress(String text, int done, int total) {
		handler.post(() -> {
			if (!cancelled.get() || writing) callback.onProgress(text, done, total);
		});
	}

	private void post(Result r) {
		handler.post(() -> finish(r));
	}

	private void finish(Result r) {
		writing = false;
		callback.onFinished(cancelled.get() && (r.status != Result.DONE) ?
				new Result(Result.CANCELLED, 0, 0, null) : r);
	}

	// ---- What's already synced ----

	static String key(Track t) {
		return (t.title.trim() + '|' + t.firstArtist()).toLowerCase(Locale.ROOT);
	}

	private static Pref<Supplier<String[]>> keysPref(String ref) {
		return Pref.sa("SPOTIFY_SYNC_KEYS_" + ref.replace('/', '_'));
	}

	/** Called by the import, and by the sync itself, for the tracks just written. Main thread. */
	static void remember(SpotifyPlaylistWriter.Entry e) {
		if (e.ref == null) return;
		Set<String> keys = new LinkedHashSet<>(Arrays.asList(
				SpotifyPrefs.store().getStringArrayPref(keysPref(e.ref))));
		for (Track t : e.tracks.values()) keys.add(key(t));
		saveKeys(e.ref, keys);
		saveRef(SpotifyPlaylistWriter.sanitizeName(e.name), e.ref);
	}

	private static void saveKeys(String ref, Set<String> keys) {
		SpotifyPrefs.store().applyStringArrayPref(keysPref(ref), keys.toArray(new String[0]));
	}

	@Nullable
	private static String getRef(String name) {
		String[] a = SpotifyPrefs.store().getStringArrayPref(SYNC_REFS);
		for (int i = 0; i < a.length - 1; i += 2) {
			if (a[i].equals(name)) return a[i + 1];
		}
		return null;
	}

	private static void saveRef(String name, String ref) {
		PreferenceStore store = SpotifyPrefs.store();
		String[] a = store.getStringArrayPref(SYNC_REFS);
		List<String> l = new ArrayList<>(a.length + 2);
		for (int i = 0; i < a.length - 1; i += 2) {
			if (a[i].equals(name)) continue;
			l.add(a[i]);
			l.add(a[i + 1]);
		}
		l.add(name);
		l.add(ref);
		store.applyStringArrayPref(SYNC_REFS, l.toArray(new String[0]));
	}
}
