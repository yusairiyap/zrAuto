package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicQueue;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.fermata.spotify.YoutubeSearch;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;

/**
 * The YouTube tab's search results and Up next queue, shown natively over the page.
 * <p>
 * Searching used to navigate the page itself to YouTube's results, which tore down the player and
 * stopped whatever was playing -- on Android Auto that meant silence for as long as it took to
 * find the next video. This panel is a plain view laid over the WebView instead: the page (and
 * its video) is never touched, never hidden and never paused, so playback carries on underneath
 * while the user browses results. The search itself is the same key-less InnerTube lookup the
 * Spotify import uses ({@link YoutubeSearch}).
 * <p>
 * Tapping a result plays it now; its Up next button puts it at the front of the queue (see
 * {@link YoutubeAddon#getUpNext()}), which plays before the current Favorites/Playlist continues --
 * the list's next few entries are previewed, dimmed, right below the queue to show exactly that.
 * <p>
 * On a wide screen (landscape, a tablet, the car) results and the queue sit side by side; on a
 * narrow one they share a single list, queue first. Every change animates (DiffUtil), and the
 * panel itself slides in/out -- see {@link #slideIn()}/{@link #slideOut(Runnable)}.
 */
@SuppressLint("ViewConstructor")
final class YoutubeSearchPanel extends FrameLayout implements MediaSessionCallback.Listener {
	private static final int MAX_RESULTS = 25;
	/** Results shown before "Show more" in the single-column (phone) layout. */
	private static final int COLLAPSED_RESULTS = 3;
	private static final long SLIDE_MS = 240;
	/** Shared: one search at a time is plenty, and a panel recreated with its fragment reuses it. */
	private static final ExecutorService executor = Executors.newSingleThreadExecutor();
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_NOTE = 1;
	private static final int TYPE_ACTION = 2;
	private static final int TYPE_VIDEO = 3;
	private static final int TYPE_HISTORY = 4;
	private static final int KIND_RESULT = 0;
	private static final int KIND_UP_NEXT = 1;
	private static final int KIND_LIST = 2;
	private static final int KIND_LIBRARY = 3;
	/** Favorites/Playlist entries matching a search, shown above YouTube's own results. */
	private static final int MAX_LIBRARY_RESULTS = 12;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final LruCache<String, Bitmap> images = new LruCache<>(80);
	private final List<Video> results = new ArrayList<>();
	private final List<PlayableItem> listItems = new ArrayList<>();
	private final List<PlayableItem> libraryResults = new ArrayList<>();
	private final YoutubeFragment fragment;
	private final YoutubeAddon addon;
	private final RecyclerView mainList;
	private final RecyclerView sideList;
	private final Adapter mainAdapter = new Adapter();
	private final Adapter sideAdapter = new Adapter();
	private final Runnable queueListener = () -> handler.post(this::onQueueChanged);
	// Colors picked for contrast against this panel's own background, rather than taken from the
	// theme's text attributes: several of the app's themes remap those for their toolbar/nav bar
	// surfaces, which left the rows here unreadable (white on white) on the light themes.
	private final int textPrimary;
	private final int textSecondary;
	@Nullable
	private String query;
	private boolean searching;
	private boolean failed;
	private int generation;
	private int listGeneration;
	@Nullable
	private String listName;
	private boolean listShuffled;
	private boolean split;
	/** "Show more" was tapped for the current results -- see {@link #refresh()}. */
	private boolean resultsExpanded;
	/** How far the panel is unrolled, 0..1 -- see {@link #slideIn()}. */
	private float revealFraction;
	@Nullable
	private ValueAnimator reveal;

	YoutubeSearchPanel(Context ctx, YoutubeFragment fragment, YoutubeAddon addon) {
		super(ctx);
		this.fragment = fragment;
		this.addon = addon;
		// Mostly opaque: the rows must stay readable over a playing video, but the page underneath
		// showing through faintly makes it obvious nothing was closed or stopped.
		int bg = resolveColor(ctx, android.R.attr.colorBackground, Color.BLACK);
		setBackgroundColor((bg & 0x00FFFFFF) | 0xF2000000);
		boolean light = isLight(bg);
		textPrimary = light ? 0xDE000000 : 0xFFFFFFFF;
		textSecondary = light ? 0x99000000 : 0xB3FFFFFF;
		// Swallow touches, so nothing reaches the page underneath.
		setClickable(true);

		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		addView(row, new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		MainActivityDelegate a = MainActivityDelegate.get(ctx);
		mainList = createList(ctx, a, mainAdapter);
		sideList = createList(ctx, a, sideAdapter);
		row.addView(mainList, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));
		row.addView(sideList, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));
		sideList.setVisibility(GONE);
		refresh();
	}

	private static RecyclerView createList(Context ctx, MainActivityDelegate a, Adapter adapter) {
		RecyclerView list = new RecyclerView(ctx);
		list.setLayoutManager(new LinearLayoutManager(ctx));
		list.setAdapter(adapter);
		// Rows animate in/out/moving (the default item animator); a changed row is just rebound in
		// place rather than cross-faded, which would flicker every refresh.
		if (list.getItemAnimator() instanceof SimpleItemAnimator sia) {
			sia.setSupportsChangeAnimations(false);
		}
		// Same as every scrollable screen: reserve room for the translucent tool/nav bars and the
		// control panel, which are all drawn over this.
		a.insetScrollableContent(list);
		return list;
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		addon.addUpNextListener(queueListener);
		MainActivityDelegate.get(getContext()).getMediaSessionCallback().addBroadcastListener(this);
		reloadList();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		addon.removeUpNextListener(queueListener);
		MainActivityDelegate.get(getContext()).getMediaSessionCallback()
				.removeBroadcastListener(this);
		handler.removeCallbacksAndMessages(null);
		generation++;
		listGeneration++;
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		// Not during layout: switching lists' visibility requests another one.
		handler.post(this::updateSplit);
	}

	/**
	 * Side by side (results left, queue right) wherever there's room for two columns of rows: on the
	 * car screen, in landscape, and on large screens in either orientation.
	 */
	private void updateSplit() {
		int w = getWidth();
		if (w == 0) return;
		float dp = w / getResources().getDisplayMetrics().density;
		boolean s = MainActivityDelegate.get(getContext()).isCarActivity() || (dp >= 720) ||
				((w > getHeight()) && (dp >= 560));
		if (s == split) return;
		split = s;
		sideList.setVisibility(s ? VISIBLE : GONE);
		// Start the other layout from scratch rather than animating every row across lists.
		mainAdapter.rows.clear();
		sideAdapter.rows.clear();
		//noinspection NotifyDataSetChanged
		mainAdapter.notifyDataSetChanged();
		//noinspection NotifyDataSetChanged
		sideAdapter.notifyDataSetChanged();
		refresh();
	}

	/**
	 * Unrolls the panel downwards from under the toolbar.
	 * <p>
	 * A reveal (an animated clip), not a slide: the lists' padding that keeps rows clear of the
	 * toolbar and the control panel is computed from where they are on screen (see
	 * MainActivityDelegate#insetScrollableContent), so moving the panel itself mid-animation had
	 * that padding computed against the off-screen start position -- the rows showed up halfway down
	 * and then jumped to the top once the slide ended.
	 */
	void slideIn() {
		setVisibility(VISIBLE);
		animateReveal(1f, null);
		reloadList();
	}

	/** Rolls the panel back up under the toolbar, then hides it and runs {@code done}. */
	void slideOut(@Nullable Runnable done) {
		animateReveal(0f, () -> {
			setVisibility(GONE);
			if (done != null) done.run();
		});
	}

	private void animateReveal(float to, @Nullable Runnable done) {
		ValueAnimator old = reveal;
		reveal = null;
		if (old != null) old.cancel();
		ValueAnimator va = ValueAnimator.ofFloat(revealFraction, to);
		va.setDuration(SLIDE_MS);
		va.setInterpolator(new DecelerateInterpolator());
		va.addUpdateListener(an -> {
			revealFraction = (float) an.getAnimatedValue();
			applyReveal();
		});
		va.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				if (reveal != va) return; // cancelled, superseded by a newer one
				reveal = null;
				revealFraction = to;
				applyReveal();
				if (done != null) done.run();
			}
		});
		reveal = va;
		applyReveal();
		va.start();
	}

	private void applyReveal() {
		if (revealFraction >= 1f) {
			setClipBounds(null);
			setAlpha(1f);
			return;
		}
		int h = getHeight();
		setClipBounds(new Rect(0, 0, getWidth(), (int) (h * revealFraction)));
		setAlpha(0.4f + 0.6f * revealFraction);
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);
		// The first reveal can start before the panel has a size to clip to.
		if (changed && (revealFraction < 1f)) applyReveal();
	}

	@Nullable
	String getQuery() {
		return query;
	}

	void search(String q) {
		q = q.trim();
		if (q.isEmpty()) return;
		String search = q;
		int gen = ++generation;
		query = q;
		resultsExpanded = false;
		searching = true;
		failed = false;
		results.clear();
		libraryResults.clear();
		refresh();

		addon.addSearchHistory(q);
		searchLibrary(q, gen);

		executor.execute(() -> {
			List<Video> found = null;
			try {
				found = YoutubeSearch.search(search, MAX_RESULTS);
			} catch (Exception ex) {
				Log.e(ex, "YouTube search failed: ", search);
			}
			List<Video> result = found;
			handler.post(() -> {
				if (gen != generation) return;
				searching = false;
				failed = (result == null);
				results.clear();
				if (result != null) results.addAll(result);
				refresh();
			});
		});
	}

	/** Drops the last search and its results (the toolbar's clear button, the search icon). */
	void clearSearch() {
		generation++;
		query = null;
		searching = false;
		failed = false;
		resultsExpanded = false;
		results.clear();
		libraryResults.clear();
		refresh();
	}

	/**
	 * Finds Favorites and Playlist entries whose title matches {@code q} -- so something already
	 * saved can be queued (Play next) or played from right here, without leaving what's playing.
	 * Lists are read one after another, stopping at {@link #MAX_LIBRARY_RESULTS}.
	 */
	private void searchLibrary(String q, int gen) {
		MediaLib lib = MainActivityDelegate.get(getContext()).getLib();
		String needle = q.toLowerCase(Locale.ROOT);
		List<BrowsableItem> sources = new ArrayList<>();
		sources.add(lib.getFavorites());
		lib.getPlaylists().getChildren().main().onCompletion((pls, err) -> {
			if (gen != generation) return;
			if (pls != null) {
				for (Item i : pls) {
					if (i instanceof BrowsableItem b) sources.add(b);
				}
			}
			collectLibrary(sources, 0, needle, new ArrayList<>(), new HashSet<>(), gen);
		});
	}

	private void collectLibrary(List<BrowsableItem> sources, int idx, String needle,
															List<PlayableItem> found, Set<String> seen, int gen) {
		if (gen != generation) return;
		if ((idx >= sources.size()) || (found.size() >= MAX_LIBRARY_RESULTS)) {
			libraryResults.clear();
			libraryResults.addAll(found);
			refresh();
			return;
		}
		sources.get(idx).getPlayableChildren(false).main().onCompletion((list, err) -> {
			if (list != null) {
				for (PlayableItem pi : list) {
					if (found.size() >= MAX_LIBRARY_RESULTS) break;
					String vid = videoIdOf(pi);
					String name = libraryTitle(pi, vid);
					if (!name.toLowerCase(Locale.ROOT).contains(needle)) continue;
					if (seen.add((vid != null) ? vid : pi.getId())) found.add(pi);
				}
			}
			collectLibrary(sources, idx + 1, needle, found, seen, gen);
		});
	}

	private String libraryTitle(PlayableItem pi, @Nullable String videoId) {
		String name = pi.getName();
		if ((videoId != null) && ((name == null) || name.isEmpty() || name.equals(videoId))) {
			name = addon.getVideoTitle(videoId);
		}
		return (name == null) ? "" : name;
	}

	/**
	 * "Play next" for a Favorites/Playlist entry: into the music queue right behind the playing
	 * track when playing as music (a queued track is just moved there), else to the front of Up
	 * next.
	 */
	private void playNext(PlayableItem pi, @Nullable String videoId, String title) {
		MainActivityDelegate a = MainActivityDelegate.get(getContext());
		Context ctx = getContext();
		if (pi instanceof MusicTrackItem t) {
			if (MusicPlayer.playNext(a, t)) {
				UiUtils.showToast(ctx, me.aap.fermata.R.string.youtube_added_play_next, title);
			}
			return;
		}
		if (MusicPlayer.isMusicModeActive(a) && MusicPlayer.queueAfterCurrent(a, pi, true)) {
			UiUtils.showToast(ctx, me.aap.fermata.R.string.youtube_added_play_next, title);
			return;
		}
		if (videoId != null) fragment.queueVideo(videoId, title, true);
	}

	private void onQueueChanged() {
		reloadList();
	}

	/**
	 * Any playback transition (next video, skip, a list entry starting) can move what's queued and
	 * which list entry comes next; the queue item and Up next listeners alone didn't catch every one
	 * of them, leaving the panel showing the previous video's view. Coalesced: a skip reports
	 * several states in quick succession.
	 */
	@Override
	public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
		handler.removeCallbacks(stateReload);
		handler.postDelayed(stateReload, 300);
	}

	private final Runnable stateReload = this::reloadList;

	/**
	 * Loads the next few entries of the Favorites/Playlist the current video was played from (see
	 * {@link YoutubeAddon#getQueueItem()}): what plays once Up next runs dry. With the list's
	 * Shuffle on, what comes next isn't known ahead, so only the list's name is shown.
	 */
	void reloadList() {
		int gen = ++listGeneration;
		PlayableItem q = addon.getQueueItem();
		BrowsableItem parent = (q != null) ? q.getParent() : null;

		int max = Math.max(1, Math.min(10,
				MainActivityPrefs.get().getIntPref(MainActivityPrefs.UP_NEXT_LIST_PREVIEW)));

		// Playing as music (or back to video from it): the list is the Music tab's queue. It's an
		// "external" folder -- so the check below would skip it -- but its play order is known
		// exactly, shuffled or not, so it's previewed as it will really play.
		if (parent instanceof MusicQueue mq) {
			List<MusicTrackItem> order = mq.getPlayOrder();
			int idx = mq.indexInPlayOrder(q);
			boolean repeat = mq.getPrefs().getRepeatPref();
			listItems.clear();
			listName = mq.getName();
			listShuffled = false;
			if (idx != -1) {
				for (int i = 1; i <= max; i++) {
					int j = idx + i;
					if (j >= order.size()) {
						if (!repeat) break;
						j %= order.size();
					}
					if (j == idx) break;
					listItems.add(order.get(j));
				}
			}
			refresh();
			return;
		}

		if ((q == null) || (parent == null) || parent.isExternal()) {
			listItems.clear();
			listName = null;
			refresh();
			return;
		}

		boolean shuffle = parent.getPrefs().getShufflePref();
		boolean repeat = parent.getPrefs().getRepeatPref();
		String name = parent.getName();

		parent.getPlayableChildren(false).main().onCompletion((list, err) -> {
			if (gen != listGeneration) return;
			if (err != null) Log.d(err, "Failed to load the Up next list preview");
			listItems.clear();
			listName = name;
			listShuffled = shuffle;

			if ((list != null) && !shuffle) {
				int idx = -1;
				String id = q.getId();
				for (int i = 0; i < list.size(); i++) {
					if (id.equals(list.get(i).getId())) {
						idx = i;
						break;
					}
				}
				if (idx != -1) {
					for (int i = 1; i <= max; i++) {
						int j = idx + i;
						if (j >= list.size()) {
							if (!repeat) break;
							j %= list.size();
						}
						if (j == idx) break;
						listItems.add(list.get(j));
					}
				}
			}

			refresh();
		});
	}

	/** Rebuilds the rows -- only what changed is animated, see {@link Adapter#submit}. */
	void refresh() {
		List<Row> queueRows = new ArrayList<>();
		List<Row> searchRows = new ArrayList<>();
		buildQueueRows(queueRows);

		if (split) {
			buildSearchRows(searchRows, Integer.MAX_VALUE);
			mainAdapter.submit(searchRows);
			sideAdapter.submit(queueRows);
		} else {
			// One narrow column: the first few results, then the queue -- both in sight without
			// scrolling past a long result list; "Show more" on the results header expands it.
			buildSearchRows(searchRows, resultsExpanded ? Integer.MAX_VALUE : COLLAPSED_RESULTS);
			searchRows.addAll(queueRows);
			mainAdapter.submit(searchRows);
			sideAdapter.submit(Collections.emptyList());
		}
	}

	private void buildQueueRows(List<Row> rows) {
		Context ctx = getContext();
		List<String> upNext = addon.getUpNext();
		boolean hasList = (listName != null) && (listShuffled || !listItems.isEmpty());
		// On a narrow screen an empty queue isn't worth the space; the car/wide layout keeps the
		// column, with a hint, so it's clear where queued videos go.
		if (upNext.isEmpty() && !hasList && !split) return;

		String title = upNext.isEmpty() ? ctx.getString(me.aap.fermata.R.string.youtube_up_next) :
				ctx.getString(me.aap.fermata.R.string.youtube_up_next_count, upNext.size());
		rows.add(Row.header("h:queue", title, upNext.isEmpty() ? null :
				ctx.getString(me.aap.fermata.R.string.youtube_up_next_clear), addon::clearUpNext));

		for (String id : upNext) rows.add(Row.upNext(id, addon.getVideoTitle(id)));
		if (upNext.isEmpty() && !hasList) {
			rows.add(Row.note("n:queue_empty", ctx.getString(me.aap.fermata.R.string.youtube_up_next_empty)));
		}

		if (hasList) {
			// "Then from" under queued videos -- they come first; with nothing queued the list is
			// simply what's playing.
			boolean queued = !upNext.isEmpty();
			int label = listShuffled ?
					(queued ? me.aap.fermata.R.string.youtube_then_shuffled :
							me.aap.fermata.R.string.youtube_playing_shuffled) :
					(queued ? me.aap.fermata.R.string.youtube_then_from :
							me.aap.fermata.R.string.youtube_playing_from);
			rows.add(Row.note("n:list", ctx.getString(label, listName)));
			for (PlayableItem pi : listItems) rows.add(Row.listItem(pi, queued));
		}
	}

	private void buildSearchRows(List<Row> rows, int limit) {
		Context ctx = getContext();

		// Past searches, as chips right below the search field -- see YoutubeAddon#getSearchHistory().
		List<String> history = addon.getSearchHistory();
		if (!history.isEmpty()) rows.add(Row.history(history));

		if (query == null) return;

		if (!libraryResults.isEmpty()) {
			rows.add(Row.header("h:library",
					ctx.getString(me.aap.fermata.R.string.youtube_search_library), null, null));
			for (PlayableItem pi : libraryResults) {
				String vid = videoIdOf(pi);
				rows.add(Row.libraryItem(pi, vid, libraryTitle(pi, vid)));
			}
		}

		boolean more = !searching && !failed && (results.size() > COLLAPSED_RESULTS) &&
				(resultsExpanded || (limit < results.size()));
		rows.add(Row.header("h:results",
				ctx.getString(me.aap.fermata.R.string.youtube_search_results, query),
				more ? ctx.getString(resultsExpanded ? me.aap.fermata.R.string.youtube_show_less :
						me.aap.fermata.R.string.youtube_show_more) : null,
				more ? () -> {
					resultsExpanded = !resultsExpanded;
					refresh();
				} : null));
		if (searching) {
			rows.add(Row.note("n:searching", ctx.getString(me.aap.fermata.R.string.youtube_searching)));
		} else if (failed) {
			rows.add(Row.note("n:failed", ctx.getString(me.aap.fermata.R.string.youtube_search_failed)));
			String q = query;
			rows.add(Row.action("a:retry", ctx.getString(me.aap.fermata.R.string.search), () -> search(q)));
		} else if (results.isEmpty()) {
			rows.add(Row.note("n:none", ctx.getString(me.aap.fermata.R.string.youtube_search_no_results)));
		} else {
			for (int i = 0, n = Math.min(limit, results.size()); i < n; i++) {
				rows.add(Row.result(results.get(i)));
			}
		}
	}

	private void loadImage(ImageView v, @Nullable String url) {
		Object tag = v.getTag();
		v.setTag(url);
		if (url == null) {
			v.setImageDrawable(null);
			return;
		}
		Bitmap cached = images.get(url);
		if (cached != null) {
			v.setImageBitmap(cached);
			return;
		}
		if (url.equals(tag)) return;
		v.setImageDrawable(null);
		FermataApplication.get().getBitmapCache().getBitmap(v.getContext(), url, false, false).main()
				.onSuccess(bm -> {
					if (bm == null) return;
					images.put(url, bm);
					if (url.equals(v.getTag())) v.setImageBitmap(bm);
				});
	}

	@Nullable
	private static String thumbnailUrl(@Nullable String videoId) {
		return (videoId == null) ? null : "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
	}

	@Nullable
	private static String videoIdOf(PlayableItem pi) {
		if (pi instanceof MusicTrackItem t) return t.getVideoId();
		return YoutubeVideoItem.extractYoutubeVideoId(pi);
	}

	private static boolean isLight(int color) {
		double r = Color.red(color) / 255.0;
		double g = Color.green(color) / 255.0;
		double b = Color.blue(color) / 255.0;
		return (0.299 * r + 0.587 * g + 0.114 * b) > 0.6;
	}

	private static int resolveColor(Context ctx, int attr, int dflt) {
		TypedValue tv = new TypedValue();
		if (!ctx.getTheme().resolveAttribute(attr, tv, true)) return dflt;
		if ((tv.type >= TypedValue.TYPE_FIRST_COLOR_INT) && (tv.type <= TypedValue.TYPE_LAST_COLOR_INT))
			return tv.data;
		if (tv.resourceId != 0) return ctx.getColor(tv.resourceId);
		return dflt;
	}

	private static final class Row {
		final int type;
		/** Identity across refreshes, for DiffUtil -- see {@link Adapter#submit}. */
		final String key;
		@Nullable
		final String text;
		@Nullable
		final String chip;
		@Nullable
		final Runnable action;
		final int kind;
		@Nullable
		final String videoId;
		@Nullable
		final Video video;
		@Nullable
		final PlayableItem item;
		/** Shown dimmed: a list entry that only plays after the queued videos. */
		boolean dim;
		/** {@link #TYPE_HISTORY}'s searches. */
		List<String> queries = Collections.emptyList();

		private Row(int type, String key, @Nullable String text, @Nullable String chip,
								@Nullable Runnable action, int kind, @Nullable String videoId,
								@Nullable Video video, @Nullable PlayableItem item) {
			this.type = type;
			this.key = key;
			this.text = text;
			this.chip = chip;
			this.action = action;
			this.kind = kind;
			this.videoId = videoId;
			this.video = video;
			this.item = item;
		}

		static Row header(String key, String text, @Nullable String chip, @Nullable Runnable action) {
			return new Row(TYPE_HEADER, key, text, chip, action, 0, null, null, null);
		}

		/** The chip row of past searches; {@code text} carries them for the content comparison. */
		static Row history(List<String> queries) {
			Row r = new Row(TYPE_HISTORY, "c:history", String.join("\n", queries), null, null, 0, null,
					null, null);
			r.queries = queries;
			return r;
		}

		static Row note(String key, String text) {
			return new Row(TYPE_NOTE, key, text, null, null, 0, null, null, null);
		}

		static Row action(String key, String text, Runnable action) {
			return new Row(TYPE_ACTION, key, text, null, action, 0, null, null, null);
		}

		static Row upNext(String videoId, String title) {
			return new Row(TYPE_VIDEO, "u:" + videoId, title, null, null, KIND_UP_NEXT, videoId, null,
					null);
		}

		static Row result(Video v) {
			return new Row(TYPE_VIDEO, "r:" + v.videoId, v.title, null, null, KIND_RESULT, v.videoId, v,
					null);
		}

		static Row listItem(PlayableItem pi, boolean dim) {
			Row r = new Row(TYPE_VIDEO, "l:" + pi.getId(), pi.getName(), null, null, KIND_LIST,
					videoIdOf(pi), null, pi);
			r.dim = dim;
			return r;
		}

		static Row libraryItem(PlayableItem pi, @Nullable String videoId, String title) {
			return new Row(TYPE_VIDEO, "b:" + pi.getId(), title, null, null, KIND_LIBRARY, videoId,
					null, pi);
		}

		boolean sameContent(Row o) {
			return (type == o.type) && (kind == o.kind) && (dim == o.dim) &&
					Objects.equals(text, o.text) && Objects.equals(chip, o.chip);
		}
	}

	private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		final List<Row> rows = new ArrayList<>();

		/** Swaps in {@code newRows}, animating only the rows that were added, removed or moved. */
		void submit(List<Row> newRows) {
			List<Row> old = new ArrayList<>(rows);
			DiffUtil.DiffResult d = DiffUtil.calculateDiff(new DiffUtil.Callback() {
				@Override
				public int getOldListSize() {
					return old.size();
				}

				@Override
				public int getNewListSize() {
					return newRows.size();
				}

				@Override
				public boolean areItemsTheSame(int o, int n) {
					return old.get(o).key.equals(newRows.get(n).key);
				}

				@Override
				public boolean areContentsTheSame(int o, int n) {
					return old.get(o).sameContent(newRows.get(n));
				}
			});
			// Rows inserted above the first visible one (the queue section appearing, a video queued at
			// the front) would otherwise land just above the viewport -- the list keeps its current
			// first row pinned -- and only show up after scrolling. Stay at the top if already there.
			RecyclerView rv = recyclerView;
			boolean atTop = (rv != null) &&
					(rv.getLayoutManager() instanceof LinearLayoutManager lm) &&
					(lm.findFirstCompletelyVisibleItemPosition() <= 0);
			rows.clear();
			rows.addAll(newRows);
			d.dispatchUpdatesTo(this);
			if (atTop) rv.scrollToPosition(0);
		}

		@Nullable
		private RecyclerView recyclerView;

		@Override
		public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
			recyclerView = rv;
		}

		@Override
		public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
			if (recyclerView == rv) recyclerView = null;
		}

		@Override
		public int getItemCount() {
			return rows.size();
		}

		@Override
		public int getItemViewType(int position) {
			return rows.get(position).type;
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			Context ctx = parent.getContext();
			View v;

			if (viewType == TYPE_VIDEO) {
				v = LayoutInflater.from(ctx).inflate(me.aap.fermata.R.layout.spotify_import_alt_item,
						parent, false);
				int p = (int) UiUtils.toPx(ctx, 12);
				v.setPaddingRelative(p, v.getPaddingTop(), p, v.getPaddingBottom());
				v.findViewById(me.aap.fermata.R.id.si_check).setVisibility(GONE);
				v.findViewById(me.aap.fermata.R.id.si_preview).setFocusable(true);
				((TextView) v.findViewById(me.aap.fermata.R.id.si_title)).setTextColor(textPrimary);
				((TextView) v.findViewById(me.aap.fermata.R.id.si_detail)).setTextColor(textSecondary);
				((ImageView) v.findViewById(me.aap.fermata.R.id.si_preview))
						.setImageTintList(ColorStateList.valueOf(textPrimary));
			} else if (viewType == TYPE_HEADER) {
				v = createHeader(ctx);
			} else if (viewType == TYPE_HISTORY) {
				HorizontalScrollView sv = new HorizontalScrollView(ctx);
				sv.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
				sv.setHorizontalScrollBarEnabled(false);
				LinearLayout chips = new LinearLayout(ctx);
				chips.setId(me.aap.fermata.R.id.si_title);
				chips.setOrientation(LinearLayout.HORIZONTAL);
				int h = (int) UiUtils.toPx(ctx, 16);
				int vp = (int) UiUtils.toPx(ctx, 8);
				chips.setPadding(h, vp, h, vp);
				sv.addView(chips, new HorizontalScrollView.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
				v = sv;
			} else {
				TextView t = new TextView(ctx);
				t.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
				int h = (int) UiUtils.toPx(ctx, 16);
				int vp = (int) UiUtils.toPx(ctx, 6);
				t.setPadding(h, vp, h, vp);

				if (viewType == TYPE_ACTION) {
					t.setTypeface(Typeface.DEFAULT_BOLD);
					t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
					t.setTextColor(textPrimary);
					t.setMinHeight((int) UiUtils.toPx(ctx, 48));
					t.setGravity(Gravity.CENTER_VERTICAL);
					t.setFocusable(true);
					t.setClickable(true);
					setSelectableBackground(t);
				} else {
					t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
					t.setTextColor(textSecondary);
				}

				v = t;
			}

			return new RecyclerView.ViewHolder(v) {
			};
		}

		/** A section title, with an optional chip (e.g. "Clear") lined up with the rows' buttons. */
		private View createHeader(Context ctx) {
			LinearLayout l = new LinearLayout(ctx);
			l.setOrientation(LinearLayout.HORIZONTAL);
			l.setGravity(Gravity.CENTER_VERTICAL);
			l.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			int h = (int) UiUtils.toPx(ctx, 16);
			int end = (int) UiUtils.toPx(ctx, 12);
			int vp = (int) UiUtils.toPx(ctx, 10);
			l.setPaddingRelative(h, vp, end, vp);

			TextView title = new TextView(ctx);
			title.setId(me.aap.fermata.R.id.si_title);
			title.setTypeface(Typeface.DEFAULT_BOLD);
			title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
			title.setTextColor(textPrimary);
			l.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

			TextView chip = createChip(ctx);
			chip.setId(me.aap.fermata.R.id.si_detail);
			l.addView(chip, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
			return l;
		}

		private TextView createChip(Context ctx) {
			TextView chip = new TextView(ctx);
			chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
			chip.setTypeface(Typeface.DEFAULT_BOLD);
			chip.setTextColor(textPrimary);
			chip.setGravity(Gravity.CENTER);
			int cp = (int) UiUtils.toPx(ctx, 14);
			chip.setPadding(cp, 0, cp, 0);
			chip.setMinHeight((int) UiUtils.toPx(ctx, 32));
			chip.setMinWidth((int) UiUtils.toPx(ctx, 40));
			GradientDrawable bg = new GradientDrawable();
			bg.setCornerRadius(UiUtils.toPx(ctx, 16));
			bg.setStroke((int) UiUtils.toPx(ctx, 1), textSecondary);
			bg.setColor(Color.TRANSPARENT);
			chip.setBackground(bg);
			chip.setFocusable(true);
			chip.setClickable(true);
			chip.setMaxLines(1);
			return chip;
		}

		private void setSelectableBackground(View v) {
			TypedValue tv = new TypedValue();
			if (v.getContext().getTheme()
					.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) &&
					(tv.resourceId != 0)) {
				v.setBackgroundResource(tv.resourceId);
			}
		}

		/** The row's button as "Play next" for a library/list entry, where that's possible. */
		private void bindPlayNext(ImageView button, PlayableItem pi, @Nullable String videoId,
															String title) {
			MainActivityDelegate a = MainActivityDelegate.get(button.getContext());
			boolean can = (pi instanceof MusicTrackItem) || (videoId != null) ||
					MusicPlayer.isMusicModeActive(a);
			if (!can) {
				button.setVisibility(GONE);
				button.setOnClickListener(null);
				return;
			}
			button.setVisibility(VISIBLE);
			button.setImageResource(me.aap.fermata.R.drawable.playlist_add);
			button.setContentDescription(button.getContext()
					.getString(me.aap.fermata.R.string.youtube_play_next));
			button.setOnClickListener(x -> playNext(pi, videoId, title));
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
			Row r = rows.get(position);
			View v = h.itemView;

			if (r.type == TYPE_HISTORY) {
				LinearLayout chips = v.findViewById(me.aap.fermata.R.id.si_title);
				chips.removeAllViews();
				Context ctx = v.getContext();
				int gap = (int) UiUtils.toPx(ctx, 8);
				for (String q : r.queries) {
					TextView c = createChip(ctx);
					c.setText(q);
					c.setOnClickListener(x -> fragment.searchFromHistory(q));
					c.setOnLongClickListener(x -> {
						addon.removeSearchHistory(q);
						return true;
					});
					LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
					lp.setMarginEnd(gap);
					chips.addView(c, lp);
				}
				return;
			}

			if (r.type == TYPE_HEADER) {
				TextView title = v.findViewById(me.aap.fermata.R.id.si_title);
				TextView chip = v.findViewById(me.aap.fermata.R.id.si_detail);
				title.setText(r.text);
				Runnable a = r.action;
				if ((r.chip != null) && (a != null)) {
					chip.setVisibility(VISIBLE);
					chip.setText(r.chip);
					chip.setOnClickListener(x -> a.run());
				} else {
					chip.setVisibility(GONE);
					chip.setOnClickListener(null);
				}
				return;
			}

			if (r.type != TYPE_VIDEO) {
				TextView t = (TextView) v;
				t.setText(r.text);
				if (r.type == TYPE_ACTION) {
					Runnable a = r.action;
					t.setOnClickListener((a == null) ? null : x -> a.run());
				}
				return;
			}

			TextView title = v.findViewById(me.aap.fermata.R.id.si_title);
			TextView detail = v.findViewById(me.aap.fermata.R.id.si_detail);
			ImageView button = v.findViewById(me.aap.fermata.R.id.si_preview);
			ImageView thumb = v.findViewById(me.aap.fermata.R.id.si_thumb);
			Context ctx = v.getContext();
			title.setText(r.text);
			// Only the list preview, and only below queued videos: it's what plays *after* them.
			// Set on the row's contents, never on the row itself -- the item animator fades rows in
			// and out through that same alpha, and whichever of the two wrote last used to win,
			// leaving recycled rows stuck half-faded.
			float a = r.dim ? 0.55f : 1f;
			title.setAlpha(a);
			detail.setAlpha(a);
			thumb.setAlpha(a);

			switch (r.kind) {
				case KIND_RESULT -> {
					Video video = Objects.requireNonNull(r.video);
					String d = (video.channel != null) ? video.channel : "";
					if (video.durationText != null) d = d.isEmpty() ? video.durationText :
							(d + " • " + video.durationText);
					detail.setText(d);
					loadImage(thumb, video.thumbnailUrl());
					button.setVisibility(VISIBLE);
					button.setImageResource(me.aap.fermata.R.drawable.playlist_add);
					button.setContentDescription(ctx.getString(me.aap.fermata.R.string.youtube_play_next));
					button.setOnClickListener(x -> fragment.queueVideo(video.videoId, video.title, true));
					v.setOnClickListener(x -> fragment.playVideoNow(video.videoId, video.title));
					v.setOnLongClickListener(x -> {
						fragment.showVideoActions(video.videoId, video.title);
						return true;
					});
				}
				case KIND_UP_NEXT -> {
					String id = Objects.requireNonNull(r.videoId);
					detail.setText(me.aap.fermata.R.string.youtube_queued);
					loadImage(thumb, thumbnailUrl(id));
					button.setVisibility(VISIBLE);
					button.setImageResource(me.aap.fermata.R.drawable.playlist_remove);
					button.setContentDescription(ctx.getString(me.aap.fermata.R.string.youtube_up_next_remove));
					button.setOnClickListener(x -> addon.removeUpNext(id));
					v.setOnClickListener(x -> fragment.playVideoNow(id, null));
					v.setOnLongClickListener(null);
				}
				case KIND_LIBRARY -> {
					PlayableItem pi = Objects.requireNonNull(r.item);
					String t = (r.text != null) ? r.text : "";
					detail.setText(ctx.getString(me.aap.fermata.R.string.youtube_from_list,
							pi.getParent().getName()));
					loadImage(thumb, thumbnailUrl(r.videoId));
					bindPlayNext(button, pi, r.videoId, t);
					v.setOnClickListener(x -> fragment.playFromList(pi));
					v.setOnLongClickListener(null);
				}
				default -> {
					PlayableItem pi = Objects.requireNonNull(r.item);
					String t = (r.text != null) ? r.text : "";
					detail.setText(ctx.getString(me.aap.fermata.R.string.youtube_from_list,
							(listName != null) ? listName : ""));
					loadImage(thumb, thumbnailUrl(r.videoId));
					// Already next: nothing to move.
					if (listItems.indexOf(pi) == 0) {
						button.setVisibility(GONE);
						button.setOnClickListener(null);
					} else {
						bindPlayNext(button, pi, r.videoId, t);
					}
					v.setOnClickListener(x -> fragment.playFromList(pi));
					v.setOnLongClickListener(null);
				}
			}
		}
	}
}
