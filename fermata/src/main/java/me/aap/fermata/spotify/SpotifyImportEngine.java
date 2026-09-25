package me.aap.fermata.spotify;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.media.service.FermataMediaServiceConnection;
import me.aap.fermata.spotify.SpotifyImportModel.Playlist;
import me.aap.fermata.spotify.SpotifyImportModel.Track;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.utils.log.Log;

/**
 * The Spotify import's working state and background work -- loading playlists, matching tracks
 * against YouTube, and the import itself -- independent of the import screen, so it keeps going
 * when the screen is closed or the app is in the background. {@link SpotifyImportService} keeps
 * the process alive (as a foreground service with a progress notification) while there's work;
 * the session is saved with {@link SpotifyImportStore}, so even if the process dies, it resumes
 * where it left off the next time it runs.
 * <p>
 * A process-wide singleton. The model ({@link #getPlaylists()} and everything in it) is only ever
 * touched on the main thread; network calls run on background executors and post their results
 * back.
 */
public final class SpotifyImportEngine {
	public static final int MAX_RESULTS = 10;
	/** Pause between consecutive YouTube searches, to stay well clear of any rate limiting. */
	private static final long SEARCH_DELAY_MS = 400;
	@Nullable
	private static SpotifyImportEngine instance;

	private final Context ctx = FermataApplication.get();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final List<Playlist> playlists = new ArrayList<>();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final AtomicBoolean cancelImport = new AtomicBoolean();
	private final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();
	/** Background matching and the import's own matching -- one YouTube search at a time. */
	private final ExecutorService matchExecutor = Executors.newSingleThreadExecutor();
	/** "Search more", so a tap isn't stuck behind the background matching. */
	private final ExecutorService altExecutor = Executors.newSingleThreadExecutor();
	private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
	private final Runnable saveTask = this::saveNow;
	private volatile boolean stopAutoMatch;
	private boolean matcherRunning;
	private boolean matchingPaused;
	private int fetching;
	@Nullable
	private Playlist priority;
	private boolean importing;
	private boolean saving;
	private int progressDone;
	private int progressTotal;
	@Nullable
	private String progressText;
	@Nullable
	private List<PlanEntry> importPlan;

	public interface Listener {
		/** A single track's match state changed (and with it the progress). */
		default void onTrackChanged(Track t) {
		}

		/** Anything else changed: playlists loaded, progress, import state... */
		default void onChanged() {
		}

		default void onImportFinished(ImportResult result) {
		}
	}

	public static final class ImportResult {
		public static final int CANCELLED = 0;
		public static final int NOTHING_MATCHED = 1;
		public static final int FAILED = 2;
		public static final int DONE = 3;
		public final int status;
		public final int videos;
		public final int playlists;
		public final int skipped;
		@Nullable
		public final String error;

		ImportResult(int status, int videos, int playlists, int skipped, @Nullable String error) {
			this.status = status;
			this.videos = videos;
			this.playlists = playlists;
			this.skipped = skipped;
			this.error = error;
		}

		public String getMessage(Context ctx) {
			return switch (status) {
				case CANCELLED -> ctx.getString(R.string.spotify_import_cancelled);
				case NOTHING_MATCHED -> ctx.getString(R.string.spotify_import_nothing_matched);
				case FAILED -> ctx.getString(R.string.spotify_import_error, error);
				default -> (skipped == 0) ? ctx.getString(R.string.spotify_import_done, videos, playlists) :
						ctx.getString(R.string.spotify_import_done_skipped, videos, playlists, skipped);
			};
		}
	}

	private static final class PlanEntry {
		final Playlist playlist;
		final List<Track> tracks;

		PlanEntry(Playlist playlist, List<Track> tracks) {
			this.playlist = playlist;
			this.tracks = tracks;
		}
	}

	private SpotifyImportEngine() {
		restoreSession();
	}

	/** Main thread only. */
	public static SpotifyImportEngine get() {
		if (instance == null) instance = new SpotifyImportEngine();
		return instance;
	}

	public void addListener(Listener l) {
		listeners.add(l);
	}

	public void removeListener(Listener l) {
		listeners.remove(l);
	}

	/** The live list of playlists; main thread only. Call {@link #changed()} after modifying it. */
	public List<Playlist> getPlaylists() {
		return playlists;
	}

	public boolean isMatchingPaused() {
		return matchingPaused;
	}

	public boolean isImporting() {
		return importing;
	}

	public boolean isSaving() {
		return saving;
	}

	public boolean isCancelling() {
		return cancelImport.get();
	}

	public int getProgressDone() {
		return progressDone;
	}

	public int getProgressTotal() {
		return progressTotal;
	}

	@Nullable
	public String getProgressText() {
		return progressText;
	}

	/** Whether there's background work that {@link SpotifyImportService} should keep alive. */
	public boolean isBusy() {
		return importing || matcherRunning || (fetching > 0);
	}

	/** The playlist whose tracks are matched first (the one open on screen), or null. */
	public void setPriority(@Nullable Playlist pl) {
		priority = pl;
	}

	/** Something in the model changed (selection, rename...): save and tell listeners. */
	public void changed() {
		scheduleSave();
		for (Listener l : listeners) l.onChanged();
	}

	private void trackChanged(Track t) {
		for (Listener l : listeners) l.onTrackChanged(t);
	}

	private void busyChanged() {
		SpotifyImportService.update(ctx);
	}

	// ---- Playlists ----

	@Nullable
	public Playlist findPlaylist(String ref) {
		for (Playlist p : playlists) {
			if (p.ref.equals(ref)) return p;
		}
		return null;
	}

	/** Adds a playlist and loads it; if it's already there but only browsed, includes it. */
	public void addPlaylist(String ref, String name, @Nullable String coverUrl, boolean included) {
		Playlist existing = findPlaylist(ref);

		if (existing != null) {
			if (included && !existing.included) {
				existing.included = true;
				ensureMatcher();
				changed();
			}
			return;
		}

		Playlist pl = new Playlist(ref);
		pl.name = name;
		pl.coverUrl = coverUrl;
		pl.included = included;
		playlists.add(pl);
		fetch(pl);
		changed();
	}

	public void include(Playlist pl) {
		if (pl.included) return;
		pl.included = true;
		ensureMatcher();
		changed();
	}

	public void remove(Playlist pl) {
		if (playlists.remove(pl)) changed();
	}

	/** Drops playlists that were only opened from the picker to look at. */
	public void removeBrowseOnly() {
		if (playlists.removeIf(p -> !p.included)) changed();
	}

	public void fetch(Playlist pl) {
		pl.state = Playlist.STATE_LOADING;
		pl.error = null;
		fetching++;
		busyChanged();

		fetchExecutor.execute(() -> {
			Playlist tmp = new Playlist(pl.ref);
			tmp.name = pl.name;
			Exception err = null;

			try {
				SpotifyClient.fetch(tmp);
			} catch (Exception ex) {
				Log.e(ex, "Failed to load Spotify playlist ", pl.ref);
				err = ex;
			}

			Exception fail = err;
			handler.post(() -> {
				fetching--;

				if (fail instanceof SpotifyAuth.AuthException) {
					pl.state = Playlist.STATE_FAILED;
					pl.error = ctx.getString(R.string.spotify_login_required);
				} else if (fail != null) {
					pl.state = Playlist.STATE_FAILED;
					pl.error = (fail.getMessage() != null) ? fail.getMessage() : fail.toString();
				} else if (tmp.tracks.isEmpty()) {
					if (!pl.renamed) pl.name = tmp.name;
					pl.state = Playlist.STATE_FAILED;
					pl.error = ctx.getString(R.string.spotify_import_no_tracks);
				} else {
					if (!pl.renamed) pl.name = tmp.name;
					pl.owner = tmp.owner;
					if (tmp.coverUrl != null) pl.coverUrl = tmp.coverUrl;
					pl.fullList = tmp.fullList;
					pl.tracks.clear();
					pl.tracks.addAll(tmp.tracks);
					pl.state = Playlist.STATE_LOADED;
					ensureMatcher();
				}

				changed();
				busyChanged();
			});
		});
	}

	// ---- Background matching ----

	public void setMatchingPaused(boolean paused) {
		matchingPaused = paused;
		if (!paused) ensureMatcher();
		changed();
	}

	/**
	 * Starts the background matcher if it isn't running: it matches every not-yet-matched track of
	 * every included playlist, one YouTube search at a time with a pause in between, taking the
	 * {@linkplain #setPriority priority} playlist's tracks first.
	 */
	public void ensureMatcher() {
		if (matcherRunning || importing || stopAutoMatch || matchingPaused) return;
		if (!hasPendingTrack()) return;
		matcherRunning = true;
		busyChanged();

		matchExecutor.execute(() -> {
			try {
				while (!stopAutoMatch && !Thread.currentThread().isInterrupted()) {
					Track t = callOnMain(this::takeNextTrack);
					if (t == null) break;
					searchTrack(t, false);
					if (!sleep()) break;
				}
			} finally {
				handler.post(() -> {
					matcherRunning = false;
					// Playlists may have been added while it was winding down.
					ensureMatcher();
					busyChanged();
					for (Listener l : listeners) l.onChanged();
				});
			}
		});
	}

	private boolean hasPendingTrack() {
		for (Playlist pl : playlists) {
			if (!pl.included || (pl.state != Playlist.STATE_LOADED)) continue;
			for (Track t : pl.tracks) {
				if ((t.match == null) && (t.matchState == Track.MATCH_NONE)) return true;
			}
		}
		return false;
	}

	/** Main thread: picks and marks the next track to match, or null if none. */
	@Nullable
	private Track takeNextTrack() {
		if (stopAutoMatch || matchingPaused) return null;
		Playlist p = priority;
		Track t = ((p != null) && p.included && playlists.contains(p)) ? nextPending(p) : null;

		if (t == null) {
			for (Playlist pl : playlists) {
				if (pl.included && (pl.state == Playlist.STATE_LOADED) &&
						((t = nextPending(pl)) != null)) break;
			}
		}

		if (t != null) {
			t.matchState = Track.MATCH_SEARCHING;
			trackChanged(t);
		}

		return t;
	}

	@Nullable
	private static Track nextPending(Playlist pl) {
		for (Track t : pl.tracks) {
			if ((t.match == null) && (t.matchState == Track.MATCH_NONE)) return t;
		}
		return null;
	}

	/** {@code {matched or given up, all}} over the included, loaded playlists. */
	public int[] getMatchProgress() {
		int all = 0;
		int done = 0;
		for (Playlist pl : playlists) {
			if (!pl.included || (pl.state != Playlist.STATE_LOADED)) continue;
			all += pl.tracks.size();
			for (Track t : pl.tracks) {
				if ((t.matchState != Track.MATCH_NONE) && (t.matchState != Track.MATCH_SEARCHING)) done++;
			}
		}
		return new int[]{done, all};
	}

	/** Blocking; runs on a background executor and posts the result. */
	private void searchTrack(Track t, boolean countProgress) {
		List<Video> found = null;

		try {
			found = YoutubeSearch.searchTrack(t, MAX_RESULTS);
		} catch (Exception ex) {
			Log.e(ex, "YouTube search failed: ", t.searchQuery());
		}

		List<Video> result = found;
		handler.post(() -> {
			applySearchResult(t, result);
			if (countProgress) progressDone++;
			scheduleSave();
			trackChanged(t);
		});
	}

	private static void applySearchResult(Track t, @Nullable List<Video> result) {
		if (result == null) {
			if (t.match == null) t.matchState = Track.MATCH_FAILED;
			return;
		}

		if (t.alternatives == null) t.alternatives = result;
		if (t.match == null) t.match = result.isEmpty() ? null : result.get(0);
		t.matchState = (t.match != null) ? Track.MATCH_FOUND : Track.MATCH_NOT_FOUND;
	}

	/** Looks up the "Search more" alternatives for {@code t}, if not already known. */
	public void searchMore(Track t) {
		if ((t.alternatives != null) || t.altSearching) return;
		t.altSearching = true;

		altExecutor.execute(() -> {
			List<Video> found = null;
			try {
				found = YoutubeSearch.searchTrack(t, MAX_RESULTS);
			} catch (Exception ex) {
				Log.e(ex, "YouTube search failed: ", t.searchQuery());
			}
			List<Video> result = found;
			handler.post(() -> {
				t.altSearching = false;
				t.alternatives = (result != null) ? result : Collections.emptyList();
				if ((t.match == null) && (result != null) && !result.isEmpty()) {
					t.match = result.get(0);
					t.matchState = Track.MATCH_FOUND;
				}
				changed();
			});
		});
	}

	// ---- Import ----

	public int getTotalSelected() {
		int n = 0;
		for (Playlist pl : playlists) {
			if (pl.included && (pl.state == Playlist.STATE_LOADED)) n += pl.getSelectedCount();
		}
		return n;
	}

	/** What Import would bring in: every selected track, or only matched ones once stopped. */
	public int getImportCount() {
		if (!matchingPaused) return getTotalSelected();
		int n = 0;
		for (Playlist pl : playlists) {
			if (!pl.included || (pl.state != Playlist.STATE_LOADED)) continue;
			for (Track t : pl.tracks) {
				if (t.selected && ((t.match != null) ||
						((t.alternatives != null) && !t.alternatives.isEmpty()))) n++;
			}
		}
		return n;
	}

	/**
	 * Matches whatever selected tracks are still unmatched (with progress, cancellable), then
	 * writes the playlists in one quick local step -- so cancelling never leaves a half-imported
	 * playlist behind.
	 *
	 * @return false if nothing is selected.
	 */
	public boolean startImport() {
		if (importing) return true;
		List<PlanEntry> plan = new ArrayList<>();
		List<Track> toResolve = new ArrayList<>();

		for (Playlist pl : playlists) {
			if (!pl.hasSelection()) continue;
			List<Track> tracks = new ArrayList<>();

			for (Track t : pl.tracks) {
				if (!t.selected) continue;
				tracks.add(t);

				if (t.match == null) {
					// A "Search more" lookup already has the answer -- no need to ask YouTube again.
					if ((t.alternatives != null) && !t.alternatives.isEmpty()) {
						t.match = t.alternatives.get(0);
						t.matchState = Track.MATCH_FOUND;
					} else if (!matchingPaused) {
						toResolve.add(t);
					} // else: matching was stopped, so it's skipped as unmatched.
				}
			}

			plan.add(new PlanEntry(pl, tracks));
		}

		if (plan.isEmpty()) return false;

		importPlan = plan;
		importing = true;
		saving = false;
		progressDone = 0;
		progressTotal = toResolve.size();
		progressText = null;
		cancelImport.set(false);
		stopAutoMatch = true; // The import does its own matching; the background matcher yields.
		busyChanged();
		changed();

		// Queued behind the background matcher, which stops after its current search.
		matchExecutor.execute(() -> {
			try {
				for (int i = 0; i < toResolve.size(); i++) {
					if (cancelImport.get() || Thread.currentThread().isInterrupted()) return;
					Track t = toResolve.get(i);
					int n = i + 1;
					boolean[] skip = {false};
					callOnMain(() -> {
						// Matched meanwhile by the background matcher's last search.
						if (t.match != null) {
							skip[0] = true;
							progressDone++;
							trackChanged(t);
							return null;
						}
						progressText = ctx.getString(R.string.spotify_import_matching, n, progressTotal,
								t.displayName());
						t.matchState = Track.MATCH_SEARCHING;
						trackChanged(t);
						return null;
					});
					if (skip[0]) continue;
					searchTrack(t, true);
					if ((i < toResolve.size() - 1) && !sleep()) return;
				}
			} finally {
				handler.post(this::finishImport);
			}
		});

		return true;
	}

	public void cancelImport() {
		if (!importing || saving) return;
		cancelImport.set(true);
		progressText = ctx.getString(R.string.spotify_import_cancelling);
		changed();
	}

	private void finishImport() {
		List<PlanEntry> plan = importPlan;

		if (cancelImport.get() || (plan == null)) {
			// Matches found so far are kept for the next attempt; the library itself is untouched.
			for (Playlist pl : playlists) {
				for (Track t : pl.tracks) {
					if (t.matchState == Track.MATCH_SEARCHING) t.matchState = Track.MATCH_NONE;
				}
			}
			endImport(new ImportResult(ImportResult.CANCELLED, 0, 0, 0, null));
			return;
		}

		List<SpotifyPlaylistWriter.Entry> entries = new ArrayList<>(plan.size());
		int skipped = 0;

		for (PlanEntry e : plan) {
			List<Video> videos = new ArrayList<>(e.tracks.size());
			Set<String> ids = new LinkedHashSet<>();

			for (Track t : e.tracks) {
				if (t.match == null) skipped++;
				else if (ids.add(t.match.videoId)) videos.add(t.match);
			}

			if (!videos.isEmpty()) entries.add(new SpotifyPlaylistWriter.Entry(e.playlist.name, videos));
		}

		if (entries.isEmpty()) {
			endImport(new ImportResult(ImportResult.NOTHING_MATCHED, 0, 0, skipped, null));
			return;
		}

		saving = true;
		progressText = ctx.getString(R.string.spotify_import_saving);
		changed();
		int nPlaylists = entries.size();
		int nSkipped = skipped;

		// Through the media service's own library -- the same one the UI shows -- so this works
		// with or without the app on screen.
		FermataMediaServiceConnection.connect(null).main().onCompletion((con, cerr) -> {
			if (cerr != null) {
				Log.e(cerr, "Failed to connect to the media service");
				endImport(new ImportResult(ImportResult.FAILED, 0, 0, 0, String.valueOf(cerr.getMessage())));
				return;
			}

			if (con.getMediaSessionCallback() == null) {
				endImport(new ImportResult(ImportResult.FAILED, 0, 0, 0, "Media service unavailable"));
				return;
			}

			SpotifyPlaylistWriter.write(con.getMediaSessionCallback().getMediaLib(), entries).main()
					.onCompletion((count, err) -> {
						con.disconnect();

						if (err != null) {
							Log.e(err, "Spotify import failed");
							String msg = (err.getMessage() != null) ? err.getMessage() : err.toString();
							endImport(new ImportResult(ImportResult.FAILED, 0, 0, 0, msg));
							return;
						}

						// Done with: they're local playlists now. Also stops the background matcher
						// from carrying on with their unselected tracks, and clears them from the
						// saved session.
						for (PlanEntry e : plan) playlists.remove(e.playlist);
						endImport(new ImportResult(ImportResult.DONE, (count != null) ? count : 0,
								nPlaylists, nSkipped, null));
					});
		});
	}

	private void endImport(ImportResult result) {
		importing = false;
		saving = false;
		importPlan = null;
		progressText = null;
		stopAutoMatch = false;
		ensureMatcher();
		busyChanged();
		changed();
		for (Listener l : listeners) l.onImportFinished(result);
	}

	// ---- Helpers ----

	/** Runs {@code c} on the main thread and waits for its result; null if interrupted. */
	@Nullable
	private <T> T callOnMain(java.util.concurrent.Callable<T> c) {
		FutureTask<T> task = new FutureTask<>(c);
		handler.post(task);
		try {
			return task.get();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException ex) {
			Log.e(ex, "Spotify import: main thread task failed");
			return null;
		}
	}

	/** @return false if interrupted. */
	private static boolean sleep() {
		try {
			Thread.sleep(SEARCH_DELAY_MS);
			return true;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	// ---- Session persistence ----

	/**
	 * Brings back the last session (see {@link SpotifyImportStore}): matches found so far are kept
	 * and the background matcher carries on with the rest. Playlists whose track list hadn't
	 * loaded are reloaded.
	 */
	private void restoreSession() {
		SpotifyImportStore.Session session = SpotifyImportStore.load(ctx);
		if (session == null) return;
		matchingPaused = session.matchingPaused;
		playlists.addAll(session.playlists);

		// Posted: this runs inside get(), and these may call back into it via the service.
		handler.post(() -> {
			for (Playlist pl : playlists) {
				if (pl.state != Playlist.STATE_LOADED) fetch(pl);
			}
			ensureMatcher();
		});
	}

	/** Whether there's a saved session with work left to do, without loading the engine. */
	public static boolean hasSavedWork(Context ctx) {
		return SpotifyImportStore.exists(ctx);
	}

	public void scheduleSave() {
		handler.removeCallbacks(saveTask);
		handler.postDelayed(saveTask, 2000);
	}

	public void saveNow() {
		handler.removeCallbacks(saveTask);
		String json = SpotifyImportStore.toJson(playlists, matchingPaused);
		saveExecutor.execute(() -> SpotifyImportStore.write(ctx, json));
	}
}
