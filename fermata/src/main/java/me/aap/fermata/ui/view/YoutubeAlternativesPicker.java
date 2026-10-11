package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.utils.async.Completed.completedVoid;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
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
import me.aap.fermata.action.Action;
import me.aap.fermata.action.CarNav;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.VideoTitleCache;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.lib.FileItem;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Favorites;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.fermata.spotify.SpotifyPlaylistWriter;
import me.aap.fermata.spotify.YoutubeSearch;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MediaLibFragment;
import me.aap.fermata.ui.fragment.SpotifyImportFragment;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;

/**
 * "Search alternative videos": finds other YouTube videos for a song or video by its title, in a
 * card in the look of the playlist picker (same panel, pills and slide up), over the whole app.
 * <p>
 * The video the item has now is always the first row (marked Current), the search results follow.
 * Two ways in, which differ in what a row does:
 * <ul>
 *   <li>From the item's own menu: tap a row to replace the video in its Favorites or playlist (at
 *   the same position), its ▶ plays it first.</li>
 *   <li>From the Alternative video action (a floating button, the All in One card, a key), for what
 *   is playing: a tap only plays the row, its ✓ button sets it as the video of the item.</li>
 * </ul>
 * Where the item is in no Favorites or playlist (a downloaded video, a local file) there is nothing to
 * replace: a tap plays the row. Car mode (steering wheel keys) never replaces: previous / next move
 * through the rows, long next plays the highlighted one, long previous goes back to what plays.
 */
public final class YoutubeAlternativesPicker {
	private static final String YT_PREFIX = "youtube:";
	private static final int MAX_RESULTS = 15;
	private static YoutubeAlternativesPicker open;

	private final MainActivityDelegate a;
	private final Context ctx;
	private final ViewGroup host;
	private final PlayableItem item;
	private final boolean fromMenu;
	private final boolean canReplace;
	private final boolean car;
	private final float density;
	private final int primary;
	private final int secondary;
	private final int chipFill;
	private final int ripple;
	private final int accent;
	private final int onAccent;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final LruCache<String, Bitmap> images = new LruCache<>(40);
	/** The current video first (when the item has one), then what the search found. */
	private final List<Video> rows = new ArrayList<>();
	@Nullable
	private Video current;
	private FrameLayout overlay;
	private FrameLayout card;
	private RecyclerView list;
	private Adapter adapter;
	private LinearLayout statusRow;
	private ProgressBar progress;
	private TextView status;
	private TextView searchAgain;
	private boolean dismissing;
	private boolean destroyed;
	private boolean searching;
	private boolean failed;
	private boolean replacing;
	private int generation;

	private YoutubeAlternativesPicker(MainActivityDelegate a, Context ctx, ViewGroup host,
																		PlayableItem item, boolean fromMenu) {
		this.a = a;
		this.ctx = ctx;
		this.host = host;
		this.item = item;
		this.fromMenu = fromMenu;
		this.canReplace = isYoutubeInList(item);
		this.car = a.isCarActivity();
		this.density = ctx.getResources().getDisplayMetrics().density;
		this.primary = color(R.attr.musicTextPrimary);
		this.secondary = color(R.attr.musicTextSecondary);
		this.chipFill = color(R.attr.musicChipFill);
		this.ripple = color(R.attr.musicChipRipple);
		this.accent = EffectsUi.accent(ctx);
		this.onAccent = EffectsUi.onAccent(accent);
	}

	// ---------------------------------------------------------------------------------------------
	// Entry points

	/** A YouTube video in Favorites or a playlist: the items whose video can be replaced. */
	public static boolean isYoutubeInList(Item i) {
		if (!(i instanceof PlayableItem pi)) return false;
		String id = pi.getOrigId();
		if ((id == null) || !id.startsWith(YT_PREFIX)) return false;
		BrowsableItem p = pi.getParent();
		return (p instanceof Playlist) || (p instanceof Favorites);
	}

	/** Items alternatives can be searched for: YouTube videos (downloaded ones too) and local files. */
	public static boolean canSearch(Item i) {
		if (!(i instanceof PlayableItem pi)) return false;
		String id = pi.getOrigId();
		return ((id != null) && id.startsWith(YT_PREFIX)) || (pi instanceof FileItem);
	}

	/**
	 * Shows the picker over the whole app.
	 *
	 * @param fromMenu opened from the item's own menu (a tap replaces its video), else for what plays
	 */
	public static void show(@NonNull MainActivityDelegate a, @NonNull PlayableItem item,
													boolean fromMenu) {
		dismissOpen();
		ViewGroup host = a.findViewById(R.id.main_activity);
		if (host == null) {
			Log.e("No view to show the alternative videos over");
			return;
		}
		Context pc = EffectsUi.palette(a.getContext());
		YoutubeAlternativesPicker p = new YoutubeAlternativesPicker(a, pc, host, item, fromMenu);
		open = p;
		p.build();
		p.search();
	}

	/** The Alternative video action: for what plays now (or played last). */
	public static void showForCurrent(@NonNull MainActivityDelegate a) {
		PlayableItem pi = Action.getFavoritableItem(a);
		if ((pi == null) || !canSearch(pi)) {
			TopToast.show(R.drawable.search, R.string.youtube_alt_nothing);
			return;
		}
		// Posted: this also runs from a menu that is still closing, over the same overlay.
		new Handler(Looper.getMainLooper()).post(() -> show(a, pi, false));
	}

	/** Closes the picker if it's open (for the back button); returns whether it was. */
	public static boolean dismissOpen() {
		YoutubeAlternativesPicker p = open;
		if ((p == null) || (p.overlay == null) || (p.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		p.dismiss();
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// The card

	private void build() {
		overlay = new FrameLayout(ctx);
		overlay.setClickable(true);
		overlay.setFocusable(false);
		overlay.setElevation(dp(30));
		overlay.setBackgroundColor(0x55000000);
		overlay.setOnClickListener(v -> dismiss());
		// Car mode: the steering wheel moves through the rows; long previous goes back to what plays.
		CarNav.markModalScope(overlay, () -> {
			dismiss();
			a.getControlPanel().openNowPlaying();
		});
		overlay.setAlpha(0f);

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(16), dp(16), dp(16), dp(16));

		// Title row.
		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(8), dp(2), dp(8), dp(2));
		ImageView hi = new ImageView(ctx);
		hi.setImageResource(R.drawable.search);
		hi.setImageTintList(ColorStateList.valueOf(accent));
		header.addView(hi, new LinearLayout.LayoutParams(dp(26), dp(26)));
		TextView title = text(ctx.getString(R.string.youtube_alternatives), 18, primary, true);
		title.setPadding(dp(10), 0, 0, 0);
		header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		root.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// What it is for, and what a tap does.
		TextView name = text(item.getName(), 14, primary, true);
		name.setSingleLine(true);
		name.setEllipsize(TextUtils.TruncateAt.END);
		name.setPadding(dp(8), dp(6), dp(8), 0);
		root.addView(name, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		TextView hint = text(ctx.getString(hintRes()), 12, secondary, false);
		hint.setPadding(dp(8), dp(2), dp(8), dp(6));
		root.addView(hint, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		statusRow = new LinearLayout(ctx);
		statusRow.setOrientation(LinearLayout.HORIZONTAL);
		statusRow.setGravity(Gravity.CENTER_VERTICAL);
		statusRow.setPadding(dp(8), dp(2), dp(8), dp(6));
		progress = new ProgressBar(ctx);
		progress.setIndeterminate(true);
		statusRow.addView(progress, new LinearLayout.LayoutParams(dp(18), dp(18)));
		status = text("", 13, secondary, false);
		status.setPadding(dp(8), 0, 0, 0);
		statusRow.addView(status, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		root.addView(statusRow, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// The rows scroll.
		list = new RecyclerView(ctx);
		list.setLayoutManager(new LinearLayoutManager(ctx));
		list.setVerticalScrollBarEnabled(false);
		list.setOverScrollMode(View.OVER_SCROLL_NEVER);
		adapter = new Adapter();
		list.setAdapter(adapter);
		if (list.getItemAnimator() instanceof SimpleItemAnimator sia) {
			sia.setSupportsChangeAnimations(false);
		}
		root.addView(list, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		LinearLayout buttons = new LinearLayout(ctx);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		searchAgain = pill(ctx.getString(R.string.youtube_alternatives_search_again));
		searchAgain.setOnClickListener(v -> search());
		// Pointless with the steering wheel, and risky to land on.
		CarNav.skip(searchAgain);
		TextView close = pill(ctx.getString(R.string.close));
		close.setOnClickListener(v -> dismiss());
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(46), 1f);
		blp.setMargins(dp(4), 0, dp(4), 0);
		buttons.addView(searchAgain, blp);
		buttons.addView(close, blp);
		LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		bp.topMargin = dp(10);
		root.addView(buttons, bp);

		card = new FrameLayout(ctx);
		card.setClickable(true);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(color(R.attr.musicPanelFill));
		bg.setCornerRadius(dp(28));
		card.setBackground(bg);
		card.setElevation(dp(12));
		card.addView(root, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		// As tall as there is room for: the card keeps its size while the results come in.
		FrameLayout.LayoutParams lp = OverlayCard.params(a, host, Integer.MAX_VALUE);
		overlay.addView(card, lp);
		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		card.setTranslationY(dp(120));
		card.setAlpha(0f);
		card.animate().translationY(0f).alpha(1f).setDuration(260)
				.setInterpolator(new DecelerateInterpolator(1.6f)).start();
		overlay.animate().alpha(1f).setDuration(220).start();
		refresh();
	}

	private int hintRes() {
		if (car) return R.string.youtube_alt_hint_car;
		if (!canReplace) return R.string.youtube_alt_hint_play;
		return fromMenu ? R.string.youtube_alt_hint_replace : R.string.youtube_alt_hint_set;
	}

	// ---------------------------------------------------------------------------------------------
	// Searching

	private String currentVideoId() {
		String id = item.getOrigId();
		return ((id != null) && id.startsWith(YT_PREFIX)) ? id.substring(YT_PREFIX.length()) : "";
	}

	private String query() {
		String name = item.getName();
		// Before its page was ever loaded, a video's "name" is just its id: nothing to search by.
		if (name.equals(currentVideoId())) return "";
		// A local file is named after its file: the extension isn't part of the title.
		if (item instanceof FileItem) name = name.replaceFirst("\\.[A-Za-z0-9]{2,4}$", "");
		return name.trim();
	}

	private void search() {
		if (searching || replacing || destroyed) return;
		String query = query();
		String currentId = currentVideoId();
		int gen = ++generation;
		searching = !query.isEmpty();
		failed = query.isEmpty();
		rows.clear();
		// The video it has now is there at once, before anything is found.
		if (!currentId.isEmpty()) {
			current = new Video(currentId, item.getName(), null, null, -1);
			rows.add(current);
		} else {
			current = null;
		}
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
				rows.clear();
				if (result != null) onFound(result, currentId);
				else if (current != null) rows.add(current);
				refresh();
				// Car mode: the first row is highlighted from the start.
				list.post(() -> {
					if (!destroyed && !rows.isEmpty()) CarNav.highlightRow(a, list, 0);
				});
			});
		});
	}

	/** The current video first (as the search knows it: with its channel and length), then the rest. */
	private void onFound(List<Video> found, String currentId) {
		int limit = MAX_RESULTS;
		if (!currentId.isEmpty()) {
			Video cur = null;
			for (Video v : found) {
				if (v.videoId.equals(currentId)) {
					cur = v;
					break;
				}
			}
			// What the item is called is the title to show: the search's may differ in wording.
			current = (cur != null) ? new Video(currentId, item.getName(), cur.channel, cur.durationText,
					cur.durationMs) : current;
			if (current != null) {
				rows.add(current);
				limit++;
			}
		}
		for (Video v : found) {
			if (!v.videoId.equals(currentId) && (rows.size() < limit)) rows.add(v);
		}
	}

	private boolean isCurrent(Video v) {
		return (current != null) && v.videoId.equals(current.videoId);
	}

	/** The rows and the line above them, as the state is. */
	private void refresh() {
		boolean busy = searching || replacing;
		boolean onlyCurrent = rows.size() <= ((current != null) ? 1 : 0);
		String s = null;
		if (replacing) s = ctx.getString(R.string.youtube_alternative_replacing);
		else if (searching) s = ctx.getString(R.string.spotify_import_searching);
		else if (failed) {
			s = ctx.getString(query().isEmpty() ? R.string.youtube_alternatives_no_title :
					R.string.spotify_import_search_failed);
		} else if (onlyCurrent) s = ctx.getString(R.string.spotify_import_no_results);
		statusRow.setVisibility((s == null) ? View.GONE : View.VISIBLE);
		progress.setVisibility(busy ? View.VISIBLE : View.GONE);
		status.setText(s);
		status.setPadding(busy ? dp(8) : 0, 0, 0, 0);
		searchAgain.setEnabled(!busy && !query().isEmpty());
		searchAgain.setAlpha(searchAgain.isEnabled() ? 1f : 0.5f);
		adapter.notifyDataSetChanged();
	}

	// ---------------------------------------------------------------------------------------------
	// What the rows do

	private void play(Video v) {
		SpotifyImportFragment.previewVideo(a, v);
	}

	/** A tap on a row. */
	private void onRowTap(Video v) {
		if (replacing) return;
		if (car || !canReplace || !fromMenu) {
			play(v);
		} else if (isCurrent(v)) {
			dismiss();
		} else {
			replaceWith(v);
		}
	}

	/**
	 * Replaces {@link #item} with {@code v} at the same position of its Favorites/playlist. If
	 * {@code v} is already in that list, the old entry is just removed.
	 */
	private void replaceWith(Video v) {
		PlayableItem old = item;
		if (replacing) return;
		BrowsableItem parent = old.getParent();
		Context c = a.getContext();
		replacing = true;
		refresh();

		AddonManager.get().getOrInstallAddon(VideoTitleCache.YOUTUBE_ADDON_CLASS).main()
				.onCompletion((addon, err) -> {
					if (addon instanceof VideoTitleCache vc) {
						vc.cacheVideoTitles(Collections.singletonMap(v.videoId, v.title));
						// The replacement keeps its channel/duration in the playlist too.
						vc.cacheVideoInfo(Collections.singletonMap(v.videoId,
								SpotifyPlaylistWriter.videoInfo(v, null)));
					}
					a.getLib().getItem(YT_PREFIX + v.videoId).main().onCompletion((ni, e) -> {
						if (!(ni instanceof PlayableItem np) || (parent == null)) {
							replaceFailed(c, (e != null) ? e : err);
							return;
						}
						parent.getUnsortedChildren().main().onCompletion((children, e2) -> {
							if (children == null) {
								replaceFailed(c, e2);
								return;
							}
							int idx = children.indexOf(old);
							boolean present = false;
							for (Item ch : children) {
								if ((ch instanceof PlayableItem p) && (YT_PREFIX + v.videoId).equals(p.getOrigId())) {
									present = true;
									break;
								}
							}
							replaceIn(parent, old, np, idx, present).main().onCompletion((r, e3) -> {
								if (e3 != null) {
									replaceFailed(c, e3);
									return;
								}
								reloadLists(a);
								dismissNow();
								TopToast.show(R.drawable.search, R.string.youtube_alternative_replaced, v.title);
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

	private void replaceFailed(Context c, @Nullable Throwable err) {
		replacing = false;
		if (!destroyed) refresh();
		if (err != null) Log.e(err, "Failed to replace the YouTube video");
		UiUtils.showAlert(c, R.string.youtube_alternative_failed);
	}

	private static void reloadLists(MainActivityDelegate a) {
		MediaLibFragment f = a.getMediaLibFragment(R.id.playlists_fragment);
		if (f != null) f.getAdapter().reload();
		f = a.getMediaLibFragment(R.id.favorites_fragment);
		if (f != null) f.reload();
	}

	// ---------------------------------------------------------------------------------------------
	// Rows

	private final class Adapter extends RecyclerView.Adapter<RowHolder> {

		@Override
		public int getItemCount() {
			return rows.size();
		}

		@NonNull
		@Override
		public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new RowHolder();
		}

		@Override
		public void onBindViewHolder(@NonNull RowHolder h, int position) {
			h.bind(rows.get(position));
		}
	}

	private final class RowHolder extends RecyclerView.ViewHolder {
		final LinearLayout row;
		final ImageView thumb;
		final TextView title;
		final TextView detail;
		final TextView tag;
		final FrameLayout action;
		final ImageView actionIcon;

		RowHolder() {
			super(new LinearLayout(ctx));
			row = (LinearLayout) itemView;
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(dp(8), dp(8), dp(8), dp(8));
			row.setClickable(true);
			row.setFocusable(true);
			RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			lp.setMargins(dp(4), dp(4), dp(4), dp(4));
			row.setLayoutParams(lp);

			thumb = new ImageView(ctx);
			thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
			thumb.setClipToOutline(true);
			thumb.setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View v, Outline o) {
					o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
				}
			});
			row.addView(thumb, new LinearLayout.LayoutParams(dp(96), dp(54)));

			LinearLayout texts = new LinearLayout(ctx);
			texts.setOrientation(LinearLayout.VERTICAL);
			texts.setPadding(dp(12), 0, dp(8), 0);
			tag = text(ctx.getString(R.string.youtube_alt_current), 10.5f, accent, true);
			tag.setAllCaps(true);
			tag.setLetterSpacing(0.06f);
			title = text("", 14, primary, true);
			title.setMaxLines(2);
			title.setEllipsize(TextUtils.TruncateAt.END);
			detail = text("", 12, secondary, false);
			detail.setSingleLine(true);
			detail.setEllipsize(TextUtils.TruncateAt.END);
			texts.addView(tag);
			texts.addView(title);
			texts.addView(detail);
			row.addView(texts, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

			action = new FrameLayout(ctx);
			actionIcon = new ImageView(ctx);
			action.addView(actionIcon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
			action.setClickable(true);
			action.setFocusable(true);
			row.addView(action, new LinearLayout.LayoutParams(dp(44), dp(44)));
		}

		void bind(Video v) {
			boolean cur = isCurrent(v);
			row.setBackground(pressable(cur ? ColorUtils.setAlphaComponent(accent, 0x30) : chipFill, 20));
			title.setText(MusicTrackItem.titleWithoutArtist(v.title, v.channel));
			// The channel shows right under it: not in front of the title too.
			String d = (v.channel != null) ? v.channel : "";
			if (v.durationText != null) d = d.isEmpty() ? v.durationText : (d + " • " + v.durationText);
			detail.setText(d);
			detail.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
			tag.setVisibility(cur ? View.VISIBLE : View.GONE);
			loadImage(thumb, v.thumbnailUrl());

			row.setOnClickListener(x -> onRowTap(v));
			// The button: ▶ (the first tap replaces) when opened from the item's menu, ✓ (the first
			// tap only plays) when opened for what plays. The steering wheel has neither; nothing to
			// replace where the item is in no list.
			boolean preview = canReplace && fromMenu;
			boolean set = canReplace && !fromMenu;
			boolean show = !car && (preview || (set && !cur));
			action.setVisibility(show ? View.VISIBLE : View.GONE);
			if (!show) {
				action.setOnClickListener(null);
				row.setOnLongClickListener(null);
				return;
			}
			if (preview) {
				action.setBackground(pressable(chipFill, 22));
				actionIcon.setImageResource(R.drawable.play);
				actionIcon.setImageTintList(ColorStateList.valueOf(primary));
				action.setContentDescription(ctx.getString(R.string.youtube_alt_play));
				action.setOnClickListener(x -> play(v));
				row.setOnLongClickListener(x -> {
					play(v);
					return true;
				});
			} else {
				action.setBackground(pressable(accent, 22));
				actionIcon.setImageResource(me.aap.utils.R.drawable.check);
				actionIcon.setImageTintList(ColorStateList.valueOf(onAccent));
				action.setContentDescription(ctx.getString(R.string.youtube_alt_set));
				action.setOnClickListener(x -> {
					if (!replacing) replaceWith(v);
				});
				row.setOnLongClickListener(null);
			}
		}
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
		v.setBackgroundColor(chipFill);
		v.setImageDrawable(null);
		FermataApplication.get().getBitmapCache().getBitmap(ctx, url, false, false).main()
				.onSuccess(bm -> {
					if ((bm == null) || destroyed) return;
					images.put(url, bm);
					if (url.equals(v.getTag())) {
						v.setImageTintList(null);
						v.setImageBitmap(bm);
					}
				});
	}

	// ---------------------------------------------------------------------------------------------
	// Closing

	private void dismiss() {
		if (dismissing) return;
		dismissing = true;
		cleanUp();
		overlay.animate().alpha(0f).setDuration(180).start();
		card.animate().translationY(dp(120)).alpha(0f).setDuration(200)
				.setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(this::remove).start();
	}

	/** Closes at once, for whatever comes next. */
	private void dismissNow() {
		dismissing = true;
		cleanUp();
		overlay.animate().cancel();
		card.animate().cancel();
		remove();
	}

	private void cleanUp() {
		destroyed = true;
		executor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
	}

	private void remove() {
		host.removeView(overlay);
		images.evictAll();
		if (open == this) open = null;
	}

	// ---------------------------------------------------------------------------------------------
	// Look

	private TextView text(String s, float sp, int color, boolean bold) {
		TextView t = new TextView(ctx);
		t.setText(s);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private TextView pill(String label) {
		TextView t = text(label, 14, primary, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setBackground(pressable(chipFill, 24));
		t.setClickable(true);
		t.setFocusable(true);
		return t;
	}

	/** A rounded fill with a ripple, and an accent outline when focused (D-pad / TV). */
	private Drawable pressable(int fill, float radiusDp) {
		StateListDrawable states = new StateListDrawable();
		states.addState(new int[]{android.R.attr.state_focused}, shape(fill, radiusDp, accent, 2));
		states.addState(new int[]{}, shape(fill, radiusDp, 0, 0));
		return new RippleDrawable(ColorStateList.valueOf(ripple), states,
				shape(0xFF000000, radiusDp, 0, 0));
	}

	private GradientDrawable shape(int fill, float radiusDp, int stroke, int strokeDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(fill);
		d.setCornerRadius(dp(radiusDp));
		if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
		return d;
	}

	private int color(@AttrRes int attr) {
		TypedValue tv = new TypedValue();
		ctx.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	private int dp(float v) {
		return Math.round(v * density);
	}
}
