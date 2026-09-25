package me.aap.fermata.ui.fragment;

import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.spotify.SpotifyClient;
import me.aap.fermata.spotify.SpotifyImportModel;
import me.aap.fermata.spotify.SpotifyImportModel.Playlist;
import me.aap.fermata.spotify.SpotifyImportModel.Track;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.fermata.spotify.SpotifyPlaylistWriter;
import me.aap.fermata.spotify.YoutubeSearch;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ToolBarView;

/**
 * Imports public Spotify playlists as local playlists of matching YouTube videos.
 * <p>
 * The user pastes one or more Spotify links; each playlist is shown as a group (with its cover)
 * that can be opened to pick individual tracks -- everything is selected by default. Playlists
 * with up to {@link SpotifyImportModel#AUTO_MATCH_LIMIT} tracks are matched against YouTube right
 * away, so each track shows the video (thumbnail and title) it would import as; larger ones are
 * matched only during the import, to avoid firing hundreds of searches at YouTube just for a
 * preview. "Search more" on a track lists alternative videos to choose from instead.
 * <p>
 * Import first resolves every remaining match (with progress, cancellable), and only then writes
 * the playlists in one quick local step -- so cancelling never leaves a half-imported playlist
 * behind. See {@link SpotifyClient} and {@link YoutubeSearch} for why no API key is needed.
 * <p>
 * Like the other tabs, list or grid follows the tool bar's list/grid toggle.
 */
public class SpotifyImportFragment extends MainActivityFragment {
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_PLAYLIST = 1;
	private static final int TYPE_TRACK = 2;
	private static final int TYPE_ALT = 3;
	private static final int TYPE_ALT_STATUS = 4;
	private static final int TYPE_EMPTY = 5;
	/** Added to TYPE_PLAYLIST/TYPE_TRACK in grid mode, so list and grid holders never mix. */
	private static final int GRID = 100;
	private static final int MAX_RESULTS = 10;
	/** Pause between consecutive YouTube searches, to stay well clear of any rate limiting. */
	private static final long SEARCH_DELAY_MS = 400;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final List<Playlist> playlists = new ArrayList<>();
	private final List<Row> rows = new ArrayList<>();
	/** Decoded thumbnails, so rebinding a row (which happens on every change) doesn't flicker. */
	private final LruCache<String, Bitmap> images = new LruCache<>(80);
	private final AtomicBoolean cancelImport = new AtomicBoolean();
	/** Playlist fetches. */
	private final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();
	/** Automatic matching and the import's own matching -- one search at a time. */
	private final ExecutorService matchExecutor = Executors.newSingleThreadExecutor();
	/** "Search more", so a tap isn't stuck behind a queue of automatic searches. */
	private final ExecutorService altExecutor = Executors.newSingleThreadExecutor();
	private volatile boolean stopAutoMatch;
	private boolean destroyed;
	@Nullable
	private Playlist current;
	private Adapter adapter;
	@Nullable
	private RecyclerView list;
	private boolean grid;
	// Import progress, shown in the header card.
	private boolean importing;
	private boolean saving;
	private int progressDone;
	private int progressTotal;
	@Nullable
	private String progressText;
	/** The tracks being imported, per playlist, captured when the import started. */
	@Nullable
	private List<PlanEntry> importPlan;

	/**
	 * Opens the import screen, asking for Spotify links straight away if nothing has been loaded
	 * yet. Used by Settings and by the Playlists tab's menus.
	 */
	public static void open(MainActivityDelegate a) {
		ActivityFragment f = a.showFragment(R.id.spotify_import_fragment);
		if ((f instanceof SpotifyImportFragment sf) && sf.playlists.isEmpty()) {
			sf.handler.post(sf::promptForLinks);
		}
	}

	@Override
	public int getFragmentId() {
		return R.id.spotify_import_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return (current != null) ? current.name : getString(R.string.spotify_import);
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return ImportToolBarMediator.instance;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.spotify_import_fragment, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		MainActivityDelegate a = getActivityDelegate();
		list = (RecyclerView) view;
		adapter = new Adapter();
		list.setAdapter(adapter);
		applyLayout(a.getPrefs().getGridViewPref(a));
		// Every scrollable screen has to reserve room for the translucent tool bar/nav bar drawn
		// over it itself -- see MainActivityDelegate.insetScrollableContent.
		a.insetScrollableContent(list);
		list.setClipChildren(false);
		rebuild();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden || (list == null)) return;
		// The grid toggle may have been flipped on another tab in the meantime.
		MainActivityDelegate a = getActivityDelegate();
		boolean g = a.getPrefs().getGridViewPref(a);
		if (g != grid) applyLayout(g);
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		list = null;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		destroyed = true;
		cancelImport.set(true);
		stopAutoMatch = true;
		fetchExecutor.shutdownNow();
		matchExecutor.shutdownNow();
		altExecutor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
		images.evictAll();
	}

	@Override
	public boolean onBackPressed() {
		if (current != null) {
			openPlaylist(null);
			return true;
		}
		return super.onBackPressed();
	}

	void applyLayout(boolean grid) {
		this.grid = grid;
		RecyclerView list = this.list;
		if (list == null) return;
		int spans = grid ? Math.max(2, getResources().getConfiguration().screenWidthDp / 180) : 1;
		GridLayoutManager lm = new GridLayoutManager(requireContext(), spans);
		lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
			@Override
			public int getSpanSize(int position) {
				if (position >= rows.size()) return spans;
				int type = rows.get(position).type;
				return ((type == TYPE_PLAYLIST) || (type == TYPE_TRACK)) ? 1 : spans;
			}
		});
		list.setLayoutManager(lm);
		adapter.notifyDataSetChanged();
	}

	private void openPlaylist(@Nullable Playlist pl) {
		current = pl;
		rebuild();
		if (list != null) list.scrollToPosition(0);
		getActivityDelegate().fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
	}

	// ---- Loading playlists ----

	private void promptForLinks() {
		if (destroyed || !isAdded()) return;
		Context ctx = requireContext();
		MainActivityDelegate a = getActivityDelegate();
		EditText text = a.createEditText(ctx);
		text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
				InputType.TYPE_TEXT_VARIATION_URI);
		text.setSingleLine(false);
		text.setMinLines(2);
		text.setMaxLines(6);
		text.setHint(R.string.spotify_import_link_hint);
		String clip = getClipboardText(ctx);
		if ((clip != null) && !SpotifyClient.extractRefs(clip).isEmpty()) text.setText(clip);

		a.createDialogBuilder(ctx)
				.setTitle(R.drawable.playlist_import, R.string.spotify_import)
				.setView(text)
				.setNegativeButton(android.R.string.cancel, (d, i) -> d.dismiss())
				.setPositiveButton(R.string.spotify_import_load,
						(d, i) -> addLinks(text.getText().toString()))
				.show();
	}

	@Nullable
	private static String getClipboardText(Context ctx) {
		try {
			ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = (cm == null) ? null : cm.getPrimaryClip();
			if ((clip == null) || (clip.getItemCount() == 0)) return null;
			CharSequence t = clip.getItemAt(0).getText();
			return (t == null) ? null : t.toString();
		} catch (Exception ex) {
			return null;
		}
	}

	private void addLinks(String text) {
		List<String> refs = SpotifyClient.extractRefs(text);

		if (refs.isEmpty()) {
			UiUtils.showAlert(requireContext(), R.string.spotify_import_no_links);
			return;
		}

		for (String ref : refs) {
			boolean exists = false;
			for (Playlist p : playlists) {
				if (p.ref.equals(ref)) {
					exists = true;
					break;
				}
			}
			if (exists) continue;
			Playlist pl = new Playlist(ref);
			playlists.add(pl);
			fetch(pl);
		}

		rebuild();
	}

	private void fetch(Playlist pl) {
		pl.state = Playlist.STATE_LOADING;
		pl.error = null;
		fetchExecutor.execute(() -> {
			Playlist tmp = new Playlist(pl.ref);
			Exception err = null;

			try {
				SpotifyClient.fetch(tmp);
			} catch (Exception ex) {
				Log.e(ex, "Failed to load Spotify playlist ", pl.ref);
				err = ex;
			}

			Exception fail = err;
			post(() -> {
				if (fail != null) {
					pl.state = Playlist.STATE_FAILED;
					pl.error = (fail.getMessage() != null) ? fail.getMessage() : fail.toString();
				} else if (tmp.tracks.isEmpty()) {
					pl.name = tmp.name;
					pl.state = Playlist.STATE_FAILED;
					pl.error = getString(R.string.spotify_import_no_tracks);
				} else {
					pl.name = tmp.name;
					pl.owner = tmp.owner;
					pl.coverUrl = tmp.coverUrl;
					pl.tracks.clear();
					pl.tracks.addAll(tmp.tracks);
					pl.state = Playlist.STATE_LOADED;
					if (pl.isAutoMatch()) autoMatch(pl);
				}
				rebuild();
			});
		});
	}

	// ---- YouTube matching ----

	/** Matches every not-yet-matched track of a small playlist, one search at a time. */
	private void autoMatch(Playlist pl) {
		List<Track> tracks = new ArrayList<>();
		for (Track t : pl.tracks) {
			if ((t.match == null) && (t.matchState == Track.MATCH_NONE)) tracks.add(t);
		}
		if (tracks.isEmpty()) return;

		matchExecutor.execute(() -> {
			for (Track t : tracks) {
				if (stopAutoMatch || Thread.currentThread().isInterrupted()) return;
				post(() -> {
					if (t.match == null) {
						t.matchState = Track.MATCH_SEARCHING;
						refreshTrack(t);
					}
				});
				searchTrack(t, false);
				if (!sleep()) return;
			}
		});
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
		post(() -> {
			applySearchResult(t, result);
			if (countProgress) progressDone++;
			refreshTrack(t);
			if (countProgress) refreshHeader();
		});
	}

	private void applySearchResult(Track t, @Nullable List<Video> result) {
		if (result == null) {
			if (t.match == null) t.matchState = Track.MATCH_FAILED;
			return;
		}

		if (t.alternatives == null) t.alternatives = result;
		if (t.match == null) t.match = result.isEmpty() ? null : result.get(0);
		t.matchState = (t.match != null) ? Track.MATCH_FOUND : Track.MATCH_NOT_FOUND;
	}

	private void onSearchMore(Track t) {
		if (importing) return;

		if (t.altExpanded) {
			t.altExpanded = false;
		} else {
			t.altExpanded = true;

			if ((t.alternatives == null) && !t.altSearching) {
				t.altSearching = true;
				altExecutor.execute(() -> {
					List<Video> found = null;
					try {
						found = YoutubeSearch.searchTrack(t, MAX_RESULTS);
					} catch (Exception ex) {
						Log.e(ex, "YouTube search failed: ", t.searchQuery());
					}
					List<Video> result = found;
					post(() -> {
						t.altSearching = false;
						// Only lists the choices -- nothing is picked on the user's behalf here.
						t.alternatives = (result != null) ? result : Collections.emptyList();
						rebuild();
					});
				});
			}
		}

		rebuild();
	}

	private void onAlternativeSelected(Track t, Video v) {
		if (importing) return;
		t.match = v;
		t.userPicked = true;
		t.matchState = Track.MATCH_FOUND;
		t.selected = true;
		t.altExpanded = false;
		rebuild();
	}

	// ---- Import ----

	private void startImport() {
		if (importing) return;
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
					} else {
						toResolve.add(t);
					}
				}
			}

			plan.add(new PlanEntry(pl, tracks));
		}

		if (plan.isEmpty()) {
			UiUtils.showToast(requireContext(), R.string.spotify_import_nothing_selected);
			return;
		}

		importPlan = plan;
		importing = true;
		saving = false;
		progressDone = 0;
		progressTotal = toResolve.size();
		progressText = null;
		cancelImport.set(false);
		stopAutoMatch = true;
		rebuild();

		matchExecutor.execute(() -> {
			try {
				for (int i = 0; i < toResolve.size(); i++) {
					if (cancelImport.get() || Thread.currentThread().isInterrupted()) return;
					Track t = toResolve.get(i);
					int n = i + 1;
					post(() -> {
						if (cancelImport.get()) return;
						progressText = getString(R.string.spotify_import_matching, n, progressTotal,
								t.displayName());
						if (t.match == null) t.matchState = Track.MATCH_SEARCHING;
						refreshTrack(t);
						refreshHeader();
					});
					searchTrack(t, true);
					if ((i < toResolve.size() - 1) && !sleep()) return;
				}
			} finally {
				post(this::finishImport);
			}
		});
	}

	private void cancelImport() {
		if (!importing || saving) return;
		cancelImport.set(true);
		progressText = getString(R.string.spotify_import_cancelling);
		refreshHeader();
	}

	private void finishImport() {
		List<PlanEntry> plan = importPlan;

		if (cancelImport.get() || (plan == null)) {
			endImport();
			// Matches found so far are kept for the next attempt; the library itself is untouched.
			for (Playlist pl : playlists) {
				for (Track t : pl.tracks) {
					if (t.matchState == Track.MATCH_SEARCHING) t.matchState = Track.MATCH_NONE;
				}
			}
			if (!destroyed) UiUtils.showToast(requireContext(), R.string.spotify_import_cancelled);
			resumeAutoMatch();
			rebuild();
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
			endImport();
			UiUtils.showAlert(requireContext(), R.string.spotify_import_nothing_matched);
			rebuild();
			return;
		}

		saving = true;
		progressText = getString(R.string.spotify_import_saving);
		refreshHeader();
		int nPlaylists = entries.size();
		int nSkipped = skipped;
		MainActivityDelegate a = getActivityDelegate();

		SpotifyPlaylistWriter.write(a.getLib(), entries).main().onCompletion((count, err) -> {
			endImport();
			if (destroyed) return;
			Context ctx = requireContext();

			if (err != null) {
				Log.e(err, "Spotify import failed");
				String msg = (err.getMessage() != null) ? err.getMessage() : err.toString();
				UiUtils.showAlert(ctx, getString(R.string.spotify_import_error, msg));
			} else {
				int n = (count != null) ? count : 0;
				UiUtils.showInfo(ctx, (nSkipped == 0) ?
						getString(R.string.spotify_import_done, n, nPlaylists) :
						getString(R.string.spotify_import_done_skipped, n, nPlaylists, nSkipped));
				MediaLibFragment f = a.getMediaLibFragment(R.id.playlists_fragment);
				if (f != null) f.getAdapter().reload();
			}

			resumeAutoMatch();
			rebuild();
		});
	}

	private void endImport() {
		importing = false;
		saving = false;
		importPlan = null;
		progressText = null;
	}

	private void resumeAutoMatch() {
		stopAutoMatch = false;
		for (Playlist pl : playlists) {
			if ((pl.state == Playlist.STATE_LOADED) && pl.isAutoMatch()) autoMatch(pl);
		}
	}

	// ---- Selection ----

	private void toggleAll() {
		if (importing) return;

		if (current != null) {
			current.setAllSelected(!current.isAllSelected());
		} else {
			boolean all = isEverythingSelected();
			for (Playlist pl : playlists) pl.setAllSelected(!all);
		}

		rebuild();
	}

	private boolean isEverythingSelected() {
		boolean any = false;
		for (Playlist pl : playlists) {
			if (pl.state != Playlist.STATE_LOADED) continue;
			any = true;
			if (!pl.isAllSelected()) return false;
		}
		return any;
	}

	private int getTotalSelected() {
		int n = 0;
		for (Playlist pl : playlists) {
			if (pl.state == Playlist.STATE_LOADED) n += pl.getSelectedCount();
		}
		return n;
	}

	// ---- Helpers ----

	private void post(Runnable r) {
		handler.post(() -> {
			if (!destroyed) r.run();
		});
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

	private void rebuild() {
		rows.clear();
		rows.add(new Row(TYPE_HEADER, null, null, null));

		if (current == null) {
			if (playlists.isEmpty()) rows.add(new Row(TYPE_EMPTY, null, null, null));
			for (Playlist pl : playlists) rows.add(new Row(TYPE_PLAYLIST, pl, null, null));
		} else {
			for (Track t : current.tracks) {
				rows.add(new Row(TYPE_TRACK, current, t, null));
				if (!t.altExpanded) continue;

				if (t.altSearching || (t.alternatives == null) || t.alternatives.isEmpty()) {
					rows.add(new Row(TYPE_ALT_STATUS, current, t, null));
				} else {
					for (Video v : t.alternatives) rows.add(new Row(TYPE_ALT, current, t, v));
				}
			}
		}

		if (adapter != null) adapter.notifyDataSetChanged();
	}

	private void refreshHeader() {
		if (adapter != null) adapter.notifyItemChanged(0);
	}

	private void refreshTrack(Track t) {
		if (adapter == null) return;
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			if ((r.track == t) && (r.type == TYPE_TRACK)) {
				adapter.notifyItemChanged(i);
				return;
			}
		}
		// Not visible (another playlist is open): its group row shows no per-track state anyway.
	}

	private void loadImage(ImageView v, @Nullable String url, @DrawableRes int placeholder) {
		v.setTag(url);
		Bitmap cached = (url == null) ? null : images.get(url);

		if (cached != null) {
			v.setImageBitmap(cached);
			return;
		}

		v.setImageResource(placeholder);
		if (url == null) return;

		FermataApplication.get().getBitmapCache().getBitmap(v.getContext(), url, false, false).main()
				.onSuccess(bm -> {
					if (bm == null) return;
					images.put(url, bm);
					if (url.equals(v.getTag())) v.setImageBitmap(bm);
				});
	}

	private static final class Row {
		final int type;
		@Nullable
		final Playlist playlist;
		@Nullable
		final Track track;
		@Nullable
		final Video video;

		Row(int type, @Nullable Playlist playlist, @Nullable Track track, @Nullable Video video) {
			this.type = type;
			this.playlist = playlist;
			this.track = track;
			this.video = video;
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

	private static final class Holder extends RecyclerView.ViewHolder {
		@Nullable
		final ImageView check;
		@Nullable
		final ImageView thumb;
		@Nullable
		final ProgressBar thumbProgress;
		@Nullable
		final TextView title;
		@Nullable
		final TextView subtitle;
		@Nullable
		final TextView detail;
		@Nullable
		final View searchMore;

		Holder(View v) {
			super(v);
			check = v.findViewById(R.id.si_check);
			thumb = v.findViewById(R.id.si_thumb);
			thumbProgress = v.findViewById(R.id.si_thumb_progress);
			title = v.findViewById(R.id.si_title);
			subtitle = v.findViewById(R.id.si_subtitle);
			detail = v.findViewById(R.id.si_detail);
			searchMore = v.findViewById(R.id.si_search_more);
		}

		/** The card in grid mode, the row itself in list mode. */
		View clickTarget() {
			View card = itemView.findViewById(R.id.si_card);
			return (card != null) ? card : itemView;
		}
	}

	private final class Adapter extends RecyclerView.Adapter<Holder> {

		@Override
		public int getItemCount() {
			return rows.size();
		}

		@Override
		public int getItemViewType(int position) {
			int type = rows.get(position).type;
			return (grid && ((type == TYPE_PLAYLIST) || (type == TYPE_TRACK))) ? type + GRID : type;
		}

		@NonNull
		@Override
		public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			LayoutInflater inf = LayoutInflater.from(parent.getContext());
			int layout;

			switch (viewType) {
				case TYPE_HEADER:
					layout = R.layout.spotify_import_header;
					break;
				case TYPE_PLAYLIST + GRID:
				case TYPE_TRACK + GRID:
					layout = R.layout.spotify_import_grid_item;
					break;
				case TYPE_ALT:
					layout = R.layout.spotify_import_alt_item;
					break;
				case TYPE_ALT_STATUS:
				case TYPE_EMPTY:
					return new Holder(createMessageView(parent.getContext()));
				default:
					layout = R.layout.spotify_import_list_item;
			}

			return new Holder(inf.inflate(layout, parent, false));
		}

		private View createMessageView(Context ctx) {
			TextView t = new com.google.android.material.textview.MaterialTextView(ctx);
			int pad = UiUtils.toIntPx(ctx, 16);
			t.setPadding(UiUtils.toIntPx(ctx, 64), pad / 2, pad, pad / 2);
			t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			t.setId(R.id.si_title);
			return t;
		}

		@Override
		public void onBindViewHolder(@NonNull Holder h, int position) {
			Row r = rows.get(position);

			switch (r.type) {
				case TYPE_HEADER -> bindHeader(h);
				case TYPE_PLAYLIST -> bindPlaylist(h, r.playlist);
				case TYPE_TRACK -> bindTrack(h, r.playlist, r.track);
				case TYPE_ALT -> bindAlt(h, r.track, r.video);
				case TYPE_ALT_STATUS -> {
					TextView t = (TextView) h.itemView;
					t.setText(r.track.altSearching ? R.string.spotify_import_searching :
							R.string.spotify_import_no_results);
				}
				case TYPE_EMPTY -> {
					TextView t = (TextView) h.itemView;
					t.setText(R.string.spotify_import_empty);
				}
			}
		}

		private void bindHeader(Holder h) {
			View v = h.itemView;
			TextView title = v.findViewById(R.id.si_header_title);
			TextView summary = v.findViewById(R.id.si_header_summary);
			TextView note = v.findViewById(R.id.si_header_note);
			View progressGroup = v.findViewById(R.id.si_progress_group);
			ProgressBar progress = v.findViewById(R.id.si_progress_bar);
			TextView progressLabel = v.findViewById(R.id.si_progress_text);
			TextView selectAll = v.findViewById(R.id.si_select_all);
			View addLink = v.findViewById(R.id.si_add_link);
			TextView importBtn = v.findViewById(R.id.si_import);
			int total = getTotalSelected();

			if (current != null) {
				title.setText(current.name);
				summary.setText(getString(R.string.spotify_import_selected,
						current.getSelectedCount(), current.tracks.size()));
				note.setVisibility(current.isAutoMatch() ? View.GONE : View.VISIBLE);
				note.setText(getString(R.string.spotify_import_large_note,
						SpotifyImportModel.AUTO_MATCH_LIMIT));
				selectAll.setText(current.isAllSelected() ? R.string.unselect_all : R.string.select_all);
				addLink.setVisibility(View.GONE);
			} else {
				int loaded = 0;
				for (Playlist pl : playlists) if (pl.state == Playlist.STATE_LOADED) loaded++;
				title.setText(R.string.spotify_import);
				summary.setText(getString(R.string.spotify_import_summary, loaded, total));
				note.setVisibility(View.VISIBLE);
				note.setText(R.string.spotify_import_intro);
				selectAll.setText(isEverythingSelected() ? R.string.unselect_all : R.string.select_all);
				addLink.setVisibility(View.VISIBLE);
			}

			selectAll.setEnabled(!importing);
			addLink.setEnabled(!importing);
			selectAll.setOnClickListener(b -> toggleAll());
			addLink.setOnClickListener(b -> promptForLinks());

			if (importing) {
				progressGroup.setVisibility(View.VISIBLE);
				progress.setIndeterminate(saving || (progressTotal == 0));
				progress.setMax(Math.max(1, progressTotal));
				progress.setProgress(progressDone);
				progressLabel.setText((progressText != null) ? progressText :
						getString(R.string.spotify_import_preparing));
				importBtn.setText(android.R.string.cancel);
				importBtn.setEnabled(!saving && !cancelImport.get());
				importBtn.setOnClickListener(b -> cancelImport());
			} else {
				progressGroup.setVisibility(View.GONE);
				importBtn.setText(getString(R.string.spotify_import_import, total));
				importBtn.setEnabled(total > 0);
				importBtn.setOnClickListener(b -> startImport());
			}
		}

		private void bindPlaylist(Holder h, Playlist pl) {
			Context ctx = h.itemView.getContext();
			boolean loaded = pl.state == Playlist.STATE_LOADED;
			setText(h.title, pl.name.isEmpty() ? pl.ref : pl.name);
			setText(h.subtitle, (pl.state == Playlist.STATE_FAILED) ?
					ctx.getString(R.string.spotify_import_retry) : pl.owner);

			switch (pl.state) {
				case Playlist.STATE_LOADING -> setText(h.detail, ctx.getString(R.string.spotify_import_loading));
				case Playlist.STATE_FAILED -> setText(h.detail,
						ctx.getString(R.string.spotify_import_failed, pl.error));
				default -> setText(h.detail, ctx.getString(R.string.spotify_import_selected,
						pl.getSelectedCount(), pl.tracks.size()));
			}

			if (h.thumb != null) loadImage(h.thumb, pl.coverUrl, R.drawable.playlist);
			if (h.thumbProgress != null) {
				h.thumbProgress.setVisibility(loaded || (pl.state == Playlist.STATE_FAILED) ?
						View.GONE : View.VISIBLE);
			}
			if (h.searchMore != null) h.searchMore.setVisibility(View.GONE);

			if (h.check != null) {
				h.check.setVisibility(loaded ? View.VISIBLE : View.INVISIBLE);
				int sel = pl.getSelectedCount();
				h.check.setImageResource((sel == 0) ? me.aap.utils.R.drawable.check_box_blank :
						me.aap.utils.R.drawable.check_box);
				// Partly selected: a dimmed check.
				h.check.setAlpha(((sel > 0) && (sel < pl.tracks.size())) ? 0.5f : 1f);
				h.check.setOnClickListener(v -> {
					if (importing || !loaded) return;
					pl.setAllSelected(!pl.isAllSelected());
					rebuild();
				});
			}

			h.clickTarget().setOnClickListener(v -> {
				if (pl.state == Playlist.STATE_LOADED) {
					openPlaylist(pl);
				} else if ((pl.state == Playlist.STATE_FAILED) && !importing) {
					fetch(pl);
					rebuild();
				}
			});
		}

		private void bindTrack(Holder h, Playlist pl, Track t) {
			Context ctx = h.itemView.getContext();
			Video m = t.match;
			setText(h.title, (m != null) ? m.title : t.title);
			setText(h.subtitle, t.displayName());

			String detail;
			switch (t.matchState) {
				case Track.MATCH_SEARCHING -> detail = ctx.getString(R.string.spotify_import_searching);
				case Track.MATCH_NOT_FOUND -> detail = ctx.getString(R.string.spotify_import_not_found);
				case Track.MATCH_FAILED -> detail = ctx.getString(R.string.spotify_import_search_failed);
				default -> {
					if (m != null) detail = videoDetail(m);
					else detail = ctx.getString(R.string.spotify_import_match_on_import);
				}
			}
			setText(h.detail, detail);

			// Large playlists don't load YouTube thumbnails, except for a video the user picked.
			boolean showThumb = (m != null) && (pl.isAutoMatch() || t.userPicked);
			if (h.thumb != null) {
				loadImage(h.thumb, showThumb ? m.thumbnailUrl() : null, R.drawable.audiotrack);
			}
			if (h.thumbProgress != null) {
				h.thumbProgress.setVisibility((t.matchState == Track.MATCH_SEARCHING) ?
						View.VISIBLE : View.GONE);
			}

			if (h.check != null) {
				h.check.setVisibility(View.VISIBLE);
				h.check.setAlpha(1f);
				h.check.setImageResource(t.selected ? me.aap.utils.R.drawable.check_box :
						me.aap.utils.R.drawable.check_box_blank);
				h.check.setOnClickListener(v -> toggleTrack(t));
			}

			if (h.searchMore instanceof TextView sm) {
				sm.setVisibility(View.VISIBLE);
				sm.setText(t.altExpanded ? R.string.spotify_import_hide_more :
						R.string.spotify_import_search_more);
				sm.setEnabled(!importing);
				sm.setOnClickListener(v -> onSearchMore(t));
			}

			h.clickTarget().setOnClickListener(v -> toggleTrack(t));
		}

		private void toggleTrack(Track t) {
			if (importing) return;
			t.selected = !t.selected;
			rebuild();
		}

		private void bindAlt(Holder h, Track t, Video v) {
			setText(h.title, v.title);
			setText(h.detail, videoDetail(v));
			if (h.thumb != null) loadImage(h.thumb, v.thumbnailUrl(), R.drawable.video);
			boolean chosen = (t.match != null) && t.match.videoId.equals(v.videoId);
			if (h.check != null) h.check.setVisibility(chosen ? View.VISIBLE : View.INVISIBLE);
			h.itemView.setOnClickListener(x -> onAlternativeSelected(t, v));
		}

		private String videoDetail(Video v) {
			if ((v.channel != null) && (v.durationText != null)) {
				return v.channel + " • " + v.durationText;
			}
			return (v.channel != null) ? v.channel : ((v.durationText != null) ? v.durationText : "");
		}

		private void setText(@Nullable TextView t, @Nullable CharSequence text) {
			if (t == null) return;
			t.setText(text);
			t.setVisibility(((text == null) || (text.length() == 0)) ? View.GONE : View.VISIBLE);
		}
	}

	/** Back button, title, and the same list/grid toggle the media tabs have. */
	private static final class ImportToolBarMediator implements ToolBarView.Mediator.BackTitle {
		static final ImportToolBarMediator instance = new ImportToolBarMediator();

		@Override
		public void enable(ToolBarView tb, ActivityFragment f) {
			ToolBarView.Mediator.BackTitle.super.enable(tb, f);
			MainActivityDelegate a = MainActivityDelegate.get(tb.getContext());
			boolean grid = a.getPrefs().getGridViewPref(a);
			addButton(tb, grid ? R.drawable.view_list : R.drawable.view_grid, this::onGridClick,
					R.id.tool_grid);
		}

		private void onGridClick(View v) {
			MainActivityDelegate a = MainActivityDelegate.get(v.getContext());
			boolean grid = !a.getPrefs().getGridViewPref(a);
			a.getPrefs().setGridViewPref(a, grid);
			((ImageView) v).setImageResource(grid ? R.drawable.view_list : R.drawable.view_grid);
			if (a.getActiveFragment() instanceof SpotifyImportFragment f) f.applyLayout(grid);
		}
	}
}
