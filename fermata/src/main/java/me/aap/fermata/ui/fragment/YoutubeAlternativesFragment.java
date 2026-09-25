package me.aap.fermata.ui.fragment;

import static me.aap.utils.async.Completed.completedVoid;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.VideoTitleCache;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Favorites;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.fermata.spotify.YoutubeSearch;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * "Search alternative videos" for a YouTube video already in Favorites or a playlist -- the same
 * lookup the Spotify import's "Search more" does, searched by the video's title. Tap a result to
 * replace the video in place (same position), or its play button to preview it first.
 */
public class YoutubeAlternativesFragment extends MainActivityFragment {
	private static final String YT_PREFIX = "youtube:";
	private static final int MAX_RESULTS = 15;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final LruCache<String, Bitmap> images = new LruCache<>(40);
	private final List<Video> results = new ArrayList<>();
	@Nullable
	private PlayableItem item;
	private boolean searching;
	private boolean failed;
	private boolean replacing;
	private boolean destroyed;
	private int generation;
	private Adapter adapter;

	/** A YouTube video in Favorites or a playlist: the items this screen can work on. */
	public static boolean isSupported(Item i) {
		if (!(i instanceof PlayableItem pi)) return false;
		String id = pi.getOrigId();
		if ((id == null) || !id.startsWith(YT_PREFIX)) return false;
		BrowsableItem p = pi.getParent();
		return (p instanceof Playlist) || (p instanceof Favorites);
	}

	public static void open(MainActivityDelegate a, PlayableItem item) {
		ActivityFragment f = a.showFragment(R.id.youtube_alternatives_fragment);
		if (f instanceof YoutubeAlternativesFragment yf) yf.setItem(item);
	}

	@Override
	public int getFragmentId() {
		return R.id.youtube_alternatives_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.youtube_alternatives);
	}

	@Override
	public void setInput(Object input) {
		if (input instanceof PlayableItem pi) setItem(pi);
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
		RecyclerView list = (RecyclerView) view;
		list.setLayoutManager(new LinearLayoutManager(requireContext()));
		adapter = new Adapter();
		list.setAdapter(adapter);
		if (list.getItemAnimator() instanceof SimpleItemAnimator sia) {
			sia.setSupportsChangeAnimations(false);
		}
		// Same as every scrollable screen: reserve room for the translucent tool/nav bars.
		getActivityDelegate().insetScrollableContent(list);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		destroyed = true;
		executor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
		images.evictAll();
	}

	private void setItem(PlayableItem item) {
		this.item = item;
		results.clear();
		failed = false;
		replacing = false;
		search();
	}

	private String currentVideoId() {
		PlayableItem i = item;
		String id = (i == null) ? null : i.getOrigId();
		return ((id != null) && id.startsWith(YT_PREFIX)) ? id.substring(YT_PREFIX.length()) : "";
	}

	private String query() {
		PlayableItem i = item;
		if (i == null) return "";
		String name = i.getName();
		// Before its page was ever loaded, a video's "name" is just its id: nothing to search by.
		return name.equals(currentVideoId()) ? "" : name;
	}

	private void search() {
		String query = query();
		String currentId = currentVideoId();
		int gen = ++generation;
		searching = !query.isEmpty();
		failed = query.isEmpty();
		refresh();
		if (query.isEmpty()) return;

		executor.execute(() -> {
			List<Video> found = null;
			try {
				found = YoutubeSearch.search(query, MAX_RESULTS + 1);
			} catch (Exception ex) {
				Log.e(ex, "YouTube search failed: ", query);
			}
			List<Video> result = found;
			handler.post(() -> {
				if (destroyed || (gen != generation)) return;
				searching = false;
				failed = (result == null);
				results.clear();
				if (result != null) {
					for (Video v : result) {
						if (!v.videoId.equals(currentId) && (results.size() < MAX_RESULTS)) results.add(v);
					}
				}
				refresh();
			});
		});
	}

	private void refresh() {
		if (adapter != null) adapter.notifyDataSetChanged();
	}

	/**
	 * Replaces {@link #item} with {@code v} at the same position of its Favorites/playlist, then
	 * goes back there. If {@code v} is already in that list, the old entry is just removed.
	 */
	private void replaceWith(Video v) {
		PlayableItem old = item;
		if ((old == null) || replacing) return;
		BrowsableItem parent = old.getParent();
		MainActivityDelegate a = getActivityDelegate();
		Context ctx = requireContext();
		replacing = true;
		refresh();

		AddonManager.get().getOrInstallAddon(VideoTitleCache.YOUTUBE_ADDON_CLASS).main()
				.onCompletion((addon, err) -> {
					if (addon instanceof VideoTitleCache c) {
						c.cacheVideoTitles(Collections.singletonMap(v.videoId, v.title));
					}
					a.getLib().getItem(YT_PREFIX + v.videoId).main().onCompletion((ni, e) -> {
						if (!(ni instanceof PlayableItem np) || (parent == null)) {
							replaceFailed(ctx, (e != null) ? e : err);
							return;
						}
						parent.getUnsortedChildren().main().onCompletion((children, e2) -> {
							if (children == null) {
								replaceFailed(ctx, e2);
								return;
							}
							int idx = children.indexOf(old);
							boolean present = false;
							for (Item c : children) {
								if ((c instanceof PlayableItem p) && (YT_PREFIX + v.videoId).equals(p.getOrigId())) {
									present = true;
									break;
								}
							}
							replaceIn(parent, old, np, idx, present).main().onCompletion((r, e3) -> {
								if (e3 != null) {
									replaceFailed(ctx, e3);
									return;
								}
								UiUtils.showToast(ctx, R.string.youtube_alternative_replaced, v.title);
								reloadLists(a);
								a.backToNavFragment();
							});
						});
					});
				});
	}

	/** New items go to the top of both Favorites and playlists, so it's moved back into place. */
	private static FutureSupplier<Void> replaceIn(BrowsableItem parent, PlayableItem old,
																								PlayableItem np, int idx, boolean present) {
		List<PlayableItem> oldList = Collections.singletonList(old);

		if (parent instanceof Playlist pl) {
			if (present) return pl.removeItems(oldList);
			return pl.addItems(Collections.singletonList(np))
					.then(x -> pl.removeItems(oldList))
					.then(x -> (idx > 0) ? pl.moveItem(0, idx) : completedVoid());
		} else if (parent instanceof Favorites fav) {
			if (present) return fav.removeItem(old);
			return fav.addItem(np)
					.then(x -> fav.removeItem(old))
					.then(x -> (idx > 0) ? fav.moveItem(0, idx) : completedVoid());
		}

		return completedVoid();
	}

	private void replaceFailed(Context ctx, @Nullable Throwable err) {
		replacing = false;
		refresh();
		if (err != null) Log.e(err, "Failed to replace the YouTube video");
		UiUtils.showAlert(ctx, R.string.youtube_alternative_failed);
	}

	private static void reloadLists(MainActivityDelegate a) {
		MediaLibFragment f = a.getMediaLibFragment(R.id.playlists_fragment);
		if (f != null) f.getAdapter().reload();
		f = a.getMediaLibFragment(R.id.favorites_fragment);
		if (f != null) f.reload();
	}

	private void loadImage(ImageView v, String url) {
		Object tag = v.getTag();
		v.setTag(url);
		Bitmap cached = images.get(url);
		if (cached != null) {
			v.setImageTintList(null);
			v.setImageBitmap(cached);
			return;
		}
		if (url.equals(tag)) return;
		v.setImageResource(R.drawable.video);
		v.setImageTintList(SpotifyImportFragment.placeholderTint(v.getContext()));
		FermataApplication.get().getBitmapCache().getBitmap(v.getContext(), url, false, false).main()
				.onSuccess(bm -> {
					if (bm == null) return;
					images.put(url, bm);
					if (url.equals(v.getTag())) {
						v.setImageTintList(null);
						v.setImageBitmap(bm);
					}
				});
	}

	private static final int TYPE_HEADER = 0;
	private static final int TYPE_VIDEO = 1;

	private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

		@Override
		public int getItemCount() {
			return 1 + results.size();
		}

		@Override
		public int getItemViewType(int position) {
			return (position == 0) ? TYPE_HEADER : TYPE_VIDEO;
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			int layout = (viewType == TYPE_HEADER) ? R.layout.spotify_import_header :
					R.layout.spotify_import_alt_item;
			View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
			return new RecyclerView.ViewHolder(v) {
			};
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
			if (position == 0) bindHeader(h.itemView);
			else bindVideo(h.itemView, results.get(position - 1));
		}

		private void bindHeader(View v) {
			PlayableItem i = item;
			TextView title = v.findViewById(R.id.si_header_title);
			TextView summary = v.findViewById(R.id.si_header_summary);
			TextView note = v.findViewById(R.id.si_header_note);
			View progressGroup = v.findViewById(R.id.si_progress_group);
			ProgressBar progress = v.findViewById(R.id.si_progress_bar);
			TextView progressText = v.findViewById(R.id.si_progress_text);
			TextView searchAgain = v.findViewById(R.id.si_import);
			v.findViewById(R.id.si_select_all).setVisibility(View.GONE);
			v.findViewById(R.id.si_add_link).setVisibility(View.GONE);
			v.findViewById(R.id.si_my_playlists).setVisibility(View.GONE);
			v.findViewById(R.id.si_progress_action).setVisibility(View.GONE);

			title.setText((i != null) ? i.getName() : "");
			BrowsableItem p = (i != null) ? i.getParent() : null;
			summary.setText(getString(R.string.youtube_alternatives_in,
					(p != null) ? p.getName() : ""));
			note.setVisibility(View.VISIBLE);
			note.setText(failed ? (query().isEmpty() ? R.string.youtube_alternatives_no_title :
					R.string.spotify_import_search_failed) :
					(!searching && results.isEmpty()) ? R.string.spotify_import_no_results :
							R.string.youtube_alternatives_hint);

			progressGroup.setVisibility((searching || replacing) ? View.VISIBLE : View.GONE);
			progress.setIndeterminate(true);
			progressText.setText(replacing ? R.string.youtube_alternative_replacing :
					R.string.spotify_import_searching);

			searchAgain.setText(R.string.youtube_alternatives_search_again);
			searchAgain.setEnabled(!searching && !replacing && !query().isEmpty());
			searchAgain.setOnClickListener(b -> search());
		}

		private void bindVideo(View v, Video video) {
			TextView title = v.findViewById(R.id.si_title);
			TextView detail = v.findViewById(R.id.si_detail);
			title.setText(video.title);
			String d = (video.channel != null) ? video.channel : "";
			if (video.durationText != null) d = d.isEmpty() ? video.durationText :
					(d + " • " + video.durationText);
			detail.setText(d);
			loadImage(v.findViewById(R.id.si_thumb), video.thumbnailUrl());
			v.findViewById(R.id.si_check).setVisibility(View.INVISIBLE);
			v.findViewById(R.id.si_preview).setOnClickListener(x ->
					SpotifyImportFragment.previewVideo(getActivityDelegate(), video));
			v.setOnClickListener(x -> replaceWith(video));
			v.setOnLongClickListener(x -> {
				SpotifyImportFragment.previewVideo(getActivityDelegate(), video);
				return true;
			});
		}
	}
}
