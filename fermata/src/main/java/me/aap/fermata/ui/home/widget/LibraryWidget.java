package me.aap.fermata.ui.home.widget;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.home.HomeArt;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeImages;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeSkeleton;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.activity.ActivityListener;

/**
 * A card listing the first few things of the library -- favorites, playlists, bookmarks -- as rows
 * with a picture. How many rows show follows the card's height, and a wide card makes two columns
 * of them. The subclasses only say where the rows come from.
 */
abstract class LibraryWidget extends HomeWidgetView {
	/**
	 * One row: what it is called, a second line, a picture, and what tapping it (or its button) does.
	 * {@code subFill} gets a setter for the second line, for text that is only known later; {@code
	 * current} marks the row that plays now with a filled background.
	 */
	protected record Row(CharSequence title, @Nullable CharSequence sub, @Nullable MediaLib.Item art,
											 Runnable click, @Nullable Runnable play,
											 @Nullable Consumer<Consumer<CharSequence>> subFill, boolean current) {
		Row(CharSequence title, @Nullable CharSequence sub, @Nullable MediaLib.Item art, Runnable click,
				@Nullable Runnable play, @Nullable Consumer<Consumer<CharSequence>> subFill) {
			this(title, sub, art, click, play, subFill, false);
		}

		/** What a row is, for telling the same row in an older and a newer list. */
		Object key() {
			return (art != null) ? art : title;
		}
	}

	private static final long STALE_MS = 15_000;
	private LinearLayout column;
	private TextView count;
	private RecyclerView listView;
	private GridLayoutManager glm;
	private final RowAdapter adapter = new RowAdapter();
	private int cols = 1;
	private boolean artOn = true;
	private int styleEpoch;
	@Nullable
	private Object lastFocusKey;
	private TextView emptyText;
	private TextView firstLine;
	private ProgressBar busy;
	/** The row to scroll to when the list is drawn (the queue's playing song), -1 for none. */
	protected int focusRow = -1;
	private HomeSkeleton skeleton;
	private TextView backChip;
	/** Set while something is opened inside the card: the rows are its contents, a chip leads back. */
	@Nullable
	private FetchSource drillSource;
	private int fetchGen;
	/** What the rows on screen were made from, so an unchanged refresh doesn't rebuild (and blink) them. */
	private String shownSignature = "";
	private List<Row> rows = new ArrayList<>();
	private int total;
	private int capacity = 3;
	private boolean built;
	private long loadedAt;
	private boolean loading;
	private final FermataServiceUiBinder.Listener playingListener = new FermataServiceUiBinder.Listener() {
		@Override
		public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
			post(() -> {
				if ((drillSource != null) || marksPlayingOnTop()) refresh(true);
			});
		}
	};
	private final Runnable refreshBadges = () -> {
		if (!built) return;
		for (int i = 0, n = listView.getChildCount(); i < n; i++) {
			if (listView.getChildViewHolder(listView.getChildAt(i)) instanceof RowHolder h) h.updateBadge();
		}
	};
	/** A download finished or was removed: the rows on screen show or lose their small badge. */
	private final YtDownloads.Listener downloadsListener = () -> {
		removeCallbacks(refreshBadges);
		postDelayed(refreshBadges, 400);
	};
	private final ActivityListener contentListener = new ActivityListener() {
		@Override
		public void onActivityEvent(ActivityDelegate a, long e) {
			if (e == FRAGMENT_CONTENT_CHANGED) refresh(true);
		}
	};

	protected LibraryWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	/** Collects the rows; calls {@code done} on the main thread with them and how many there are in all. */
	protected abstract void fetch(MainActivityDelegate a, FetchDone done);

	/** The tab the header button opens. */
	protected abstract int tabId();

	@StringRes
	protected abstract int emptyRes();

	/** Whether the list marks the playing song and follows it (favorites, the songs of an opened playlist). */
	protected boolean watchesPlaying() {
		return false;
	}

	/** Whether the top-level rows are songs too, so a song change redraws them (not only an opened playlist). */
	protected boolean marksPlayingOnTop() {
		return false;
	}

	/**
	 * The ids the playing song goes by: the session's item, the queue entry's source and what Add to
	 * favorites would act on. A row is the playing one when its {@code getOrigId()} is in the set. With
	 * nothing loaded yet (the app has just started) it is the song last played, the one the media card shows.
	 */
	protected static Set<String> playingIds(MainActivityDelegate a) {
		Set<String> ids = new HashSet<>();
		try {
			addIds(ids, a.getMediaSessionCallback().getCurrentItem());
			addIds(ids, me.aap.fermata.action.Action.getFavoritableItem(a));
			if (HomeArt.isIdle(a)) addIds(ids, HomeArt.displayItem(a));
		} catch (RuntimeException ignore) {
			// An item that is going away: nothing is marked this time.
		}
		ids.remove(null);
		return ids;
	}

	private static void addIds(Set<String> ids, @Nullable PlayableItem pi) {
		if (pi == null) return;
		ids.add(pi.getOrigId());
		if (pi instanceof me.aap.fermata.addon.music.MusicTrackItem t) {
			ids.add(t.getSourceId());
			PlayableItem fav = t.getFavoritableItem();
			if (fav != null) ids.add(fav.getOrigId());
		}
	}

	/** Whether {@code r} is a song that is on the phone (a downloaded YouTube video), for the row's small badge. */
	private static boolean isDownloaded(Row r) {
		return (r.art() instanceof PlayableItem p) && YtDownloads.get().isDownloaded(YtDownloads.videoIdOf(p));
	}

	protected interface FetchDone {
		void done(List<Row> rows, int total);
	}

	/** Where the rows of something opened inside the card (a playlist's songs) come from. */
	protected interface FetchSource {
		void fetch(MainActivityDelegate a, FetchDone done);
	}

	protected final MainActivityDelegate act() {
		return host.activity();
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		LinearLayout head = header(() -> open(tabId()));
		count = text(ROLE_TERTIARY, 12.5f, false);
		count.setPadding(px(8), 0, px(4), 0);
		head.addView(count, Math.max(0, head.getChildCount() - 1));
		column.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

		backChip = chip(me.aap.utils.R.drawable.back, "", this::backOut);
		backChip.setVisibility(GONE);
		LinearLayout.LayoutParams bc = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34));
		bc.topMargin = px(4);
		column.addView(backChip, bc);

		firstLine = text(ROLE_SECONDARY, 13, false);
		firstLine.setSingleLine(true);
		firstLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
		firstLine.setVisibility(GONE);
		LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		fl.topMargin = px(4);
		column.addView(firstLine, fl);

		// Rows are made only for what is on screen and recycled as the list scrolls, so a queue of
		// hundreds costs no more than a few rows, and coming back to Home draws nothing again.
		listView = new RecyclerView(ctx);
		glm = new GridLayoutManager(ctx, 1);
		listView.setLayoutManager(glm);
		listView.setAdapter(adapter);
		listView.setItemAnimator(null);
		listView.setNestedScrollingEnabled(false);
		listView.setVerticalScrollBarEnabled(false);
		listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
		listView.setVerticalFadingEdgeEnabled(true);
		listView.setFadingEdgeLength(px(22));
		listView.setItemViewCacheSize(4);
		listView.getRecycledViewPool().setMaxRecycledViews(0, 24);
		LinearLayout.LayoutParams lsl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		lsl.topMargin = px(4);
		column.addView(listView, lsl);

		emptyText = text(ROLE_SECONDARY, 13, false);
		emptyText.setText(emptyRes());
		emptyText.setGravity(Gravity.CENTER);
		emptyText.setVisibility(GONE);
		column.addView(emptyText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		skeleton = new HomeSkeleton(ctx);
		skeleton.setVisibility(GONE);
		skeleton.setColor(softFill());
		LinearLayout.LayoutParams sk = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		sk.topMargin = px(4);
		column.addView(skeleton, sk);

		busy = spinner(28);
		busy.setVisibility(GONE);
		FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER);
		blp.topMargin = px(18);

		content.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		content.addView(busy, blp);
		built = true;
	}

	/** A list longer than the card scrolls, and keeps the page from taking that drag. */
	@Override
	protected int gestures() {
		return ((listView != null) && (listView.canScrollVertically(1) || listView.canScrollVertically(-1))) ?
				GESTURE_V : 0;
	}

	@Override
	protected void onBind() {
		render();
		if (isActive()) refresh(true);
	}

	@Override
	protected void onResized(int w, int h) {
		if (built) {
			updateCapacity();
			render();
		}
	}

	@Override
	protected void onSized(int w, int h) {
		if (!built) return;
		int before = capacity;
		updateCapacity();
		if (before != capacity) render();
	}

	private void updateCapacity() {
		int avail = heightPx() - px(28) - px(34) - px(8) - ((drillSource != null) ? px(38) : 0);
		capacity = Math.max(1, avail / px(48));
	}

	@Override
	protected void onActive() {
		act().addBroadcastListener(contentListener, ActivityListener.FRAGMENT_CONTENT_CHANGED);
		YtDownloads.get().addListener(downloadsListener);
		if (watchesPlaying()) act().getMediaServiceBinder().addBroadcastListener(playingListener);
		// Back on screen: the playing song is brought into view again.
		lastFocusKey = null;
		render();
		refresh(false);
	}

	@Override
	protected void onInactive() {
		act().removeBroadcastListener(contentListener);
		YtDownloads.get().removeListener(downloadsListener);
		removeCallbacks(refreshBadges);
		if (watchesPlaying()) act().getMediaServiceBinder().removeBroadcastListener(playingListener);
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.sw("art", ctx.getString(R.string.home_opt_artwork), true));
		l.addAll(moreOptions());
		return l;
	}

	protected List<HomeOption> moreOptions() {
		return new ArrayList<>();
	}

	/** Opens something inside the card: its rows replace the list, under a chip that goes back. */
	protected final void drill(CharSequence title, FetchSource source) {
		drillSource = source;
		backChip.setText(title);
		showBack(true);
		reset();
	}

	private void backOut() {
		if (drillSource == null) return;
		drillSource = null;
		showBack(false);
		reset();
	}

	private void showBack(boolean on) {
		if (host.animate() && isShown()) {
			android.transition.AutoTransition t = new android.transition.AutoTransition();
			t.setDuration(240);
			android.transition.TransitionManager.beginDelayedTransition(column, t);
		}
		backChip.setVisibility(on ? VISIBLE : GONE);
	}

	/** What was shown is not what is asked for now: placeholders until the other rows are in. */
	private void reset() {
		rows = new ArrayList<>();
		total = 0;
		focusRow = -1;
		lastFocusKey = null;
		shownSignature = "";
		updateCapacity();
		refresh(true);
		render();
	}

	protected final void refresh(boolean force) {
		if (loading && !force) return;
		if (!force && ((SystemClock.uptimeMillis() - loadedAt) < STALE_MS)) return;
		loading = true;
		int gen = ++fetchGen;
		if (built) updateBusy();
		FetchSource src = (drillSource != null) ? drillSource : this::fetch;
		src.fetch(act(), (r, n) -> {
			if (gen != fetchGen) return;
			loading = false;
			loadedAt = SystemClock.uptimeMillis();
			rows = r;
			total = n;
			render();
		});
	}

	/** Placeholder rows (a spinner in a strip) show while there is nothing to show yet. */
	private void updateBusy() {
		boolean on = loading && rows.isEmpty();
		boolean rowsWait = on && !isCompact();
		if ((skeleton.getVisibility() == VISIBLE) != rowsWait) {
			skeleton.setVisibility(rowsWait ? VISIBLE : GONE);
			if (rowsWait && host.animate()) {
				skeleton.setAlpha(0f);
				skeleton.animate().alpha(1f).setDuration(200).start();
			}
		}
		boolean spin = on && isCompact();
		if ((busy.getVisibility() == VISIBLE) != spin) busy.setVisibility(spin ? VISIBLE : GONE);
		if (rowsWait) skeleton.setRows(48, optBool("art", true) ? 40 : 0, 40, (getCellsW() >= 4) ? 2 : 1);
	}

	@Override
	protected void onStyleChanged() {
		if (skeleton != null) skeleton.setColor(softFill());
		styleEpoch++;
		if (built) adapter.notifyDataSetChanged();
	}

	private void render() {
		if (!built) return;
		updateBusy();
		boolean none = rows.isEmpty();
		int newCols = (getCellsW() >= 4) ? 2 : 1;
		boolean art = optBool("art", true);
		// Drawn again only when something it shows changed: the rows blinked on every refresh.
		StringBuilder sig = new StringBuilder();
		sig.append(total).append('|').append(isCompact()).append('|').append(newCols).append('|')
				.append(art).append('|').append(loading && none).append('|').append(drillSource != null);
		for (Row r : rows) sig.append('|').append(r.title()).append(r.sub()).append(r.current());
		String signature = sig.toString();
		if (signature.equals(shownSignature)) return;
		shownSignature = signature;

		count.setText(total > 0 ? String.valueOf(total) : "");
		emptyText.setVisibility(none && !isCompact() && !loading ? VISIBLE : GONE);
		boolean listShown = !(none || isCompact());
		listView.setVisibility(listShown ? VISIBLE : GONE);
		firstLine.setVisibility((!none && isCompact()) ? VISIBLE : GONE);
		if (none) {
			adapter.set(new ArrayList<>(), true);
			return;
		}
		if (isCompact()) {
			firstLine.setText(rows.get(0).title());
			return;
		}
		boolean wasEmpty = adapter.getItemCount() == 0;
		boolean full = (newCols != cols) || (art != artOn) || wasEmpty;
		cols = newCols;
		artOn = art;
		glm.setSpanCount(cols);
		adapter.set(rows, full);
		if (wasEmpty && host.animate()) {
			listView.animate().cancel();
			listView.setAlpha(0f);
			listView.animate().alpha(1f).setDuration(220).start();
		}
		scrollToPlaying();
	}

	/** Brings the playing row into view, once per song: never while the finger is on the list. */
	private void scrollToPlaying() {
		if ((focusRow < 0) || (focusRow >= rows.size())) return;
		Object key = rows.get(focusRow).key();
		if (key.equals(lastFocusKey)) return;
		lastFocusKey = key;
		listView.post(() -> {
			if (listView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
			int target = Math.max(0, focusRow - cols);
			int first = glm.findFirstVisibleItemPosition();
			if ((first == RecyclerView.NO_POSITION) || (Math.abs(first - target) > 14) || !isShown()) {
				glm.scrollToPositionWithOffset(target, 0);
				return;
			}
			LinearSmoothScroller sm = new LinearSmoothScroller(ctx) {
				@Override
				protected int getVerticalSnapPreference() {
					return SNAP_TO_START;
				}
			};
			sm.setTargetPosition(target);
			glm.startSmoothScroll(sm);
		});
	}

	private final class RowAdapter extends RecyclerView.Adapter<RowHolder> {
		private List<Row> data = new ArrayList<>();

		@Override
		public int getItemCount() {
			return data.size();
		}

		@Override
		public RowHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			return new RowHolder();
		}

		@Override
		public void onBindViewHolder(RowHolder h, int position) {
			h.bind(data.get(position), position);
		}

		/** The new rows; only what differs from the old ones is rebound (a full reset when {@code full}). */
		void set(List<Row> now, boolean full) {
			List<Row> old = data;
			if (full) {
				data = now;
				notifyDataSetChanged();
				return;
			}
			DiffUtil.DiffResult dr = DiffUtil.calculateDiff(new DiffUtil.Callback() {
				@Override
				public int getOldListSize() {
					return old.size();
				}

				@Override
				public int getNewListSize() {
					return now.size();
				}

				@Override
				public boolean areItemsTheSame(int o, int n) {
					return Objects.equals(old.get(o).key(), now.get(n).key());
				}

				@Override
				public boolean areContentsTheSame(int o, int n) {
					Row a = old.get(o);
					Row b = now.get(n);
					return Objects.equals(a.title(), b.title()) && Objects.equals(a.sub(), b.sub()) &&
							(a.current() == b.current());
				}
			}, false);
			data = now;
			dr.dispatchUpdatesTo(this);
		}
	}

	private final class RowHolder extends RecyclerView.ViewHolder {
		private final LinearLayout main;
		private final ImageView thumb;
		private final TextView title;
		private final TextView sub;
		private final ImageView playBtn;
		/** A small "Downloaded" badge on the right of a song that is on the phone. */
		private final TextView badge;
		@Nullable
		private Row row;
		private int paintedEpoch = -1;
		private boolean paintedCurrent;

		RowHolder() {
			super(new LinearLayout(ctx));
			LinearLayout root = (LinearLayout) itemView;
			root.setOrientation(LinearLayout.HORIZONTAL);
			root.setGravity(Gravity.CENTER_VERTICAL);
			root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)));

			main = new LinearLayout(ctx);
			main.setOrientation(LinearLayout.HORIZONTAL);
			main.setGravity(Gravity.CENTER_VERTICAL);
			main.setClickable(true);
			main.setFocusable(true);
			main.setOnClickListener(v -> {
				if (row != null) row.click().run();
			});
			root.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

			thumb = HomeImages.thumb(ctx, 40, 40, 10, softFill());
			LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(40), px(40));
			tlp.setMarginEnd(px(10));
			tlp.setMarginStart(px(4));
			main.addView(thumb, tlp);

			LinearLayout texts = new LinearLayout(ctx);
			texts.setOrientation(LinearLayout.VERTICAL);
			texts.setGravity(Gravity.CENTER_VERTICAL);
			title = text(ROLE_PRIMARY, 13.5f, true);
			title.setSingleLine(true);
			title.setEllipsize(android.text.TextUtils.TruncateAt.END);
			texts.addView(title);
			sub = text(ROLE_SECONDARY, 12, false);
			sub.setSingleLine(true);
			sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
			texts.addView(sub);
			main.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

			badge = new TextView(ctx);
			badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f);
			badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
			badge.setSingleLine(true);
			badge.setGravity(Gravity.CENTER_VERTICAL);
			badge.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.download, 0, 0, 0);
			badge.setVisibility(GONE);
			LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(22));
			blp.setMarginStart(px(6));
			blp.setMarginEnd(px(2));
			root.addView(badge, blp);

			playBtn = iconButton(R.drawable.play, 36, ROLE_ICON, R.string.action_play, () -> {
				if ((row != null) && (row.play() != null)) row.play().run();
			});
			root.addView(playBtn);
		}

		void bind(Row r, int position) {
			row = r;
			// A card two cells wide (a phone): a smaller picture and button leave the name some room.
			boolean narrow = widthPx() < px(230);
			int ts = narrow ? px(34) : px(40);
			ViewGroup.LayoutParams tl = thumb.getLayoutParams();
			if (tl.width != ts) {
				tl.width = ts;
				tl.height = ts;
				thumb.setLayoutParams(tl);
			}
			int ps = narrow ? px(30) : px(36);
			ViewGroup.LayoutParams pl = playBtn.getLayoutParams();
			if (pl.width != ps) {
				pl.width = ps;
				pl.height = ps;
				playBtn.setLayoutParams(pl);
				int pad = Math.round(ps * 0.26f);
				playBtn.setPadding(pad, pad, pad, pad);
			}
			// A gap between the two columns, from the rows' own padding.
			int gap = (cols > 1) ? px(5) : 0;
			itemView.setPadding(((position % cols) == 0) ? 0 : gap, 0, ((position % cols) == cols - 1) ? 0 : gap, 0);
			if ((paintedEpoch != styleEpoch) || (paintedCurrent != r.current())) {
				paintedEpoch = styleEpoch;
				paintedCurrent = r.current();
				// The playing song is filled with the card's accent, softly, instead of carrying a marker.
				int fill = r.current() ? HomeUi.alpha(color(ROLE_ACCENT), 0.28f) : 0;
				main.setBackground(HomeUi.pressable(ripple(), fill, px(14)));
				thumb.setBackground(HomeUi.round(softFill(), px(10)));
				badge.setBackground(HomeUi.round(softFill(), px(11)));
				badge.setTextColor(color(ROLE_SECONDARY));
				badge.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color(ROLE_SECONDARY)));
			}
			main.setContentDescription(r.title());
			title.setText(r.title());
			if (artOn) {
				thumb.setVisibility(VISIBLE);
				if (r.art() == null) {
					thumb.setTag(R.id.home_image_item, null);
					thumb.setImageDrawable(null);
				} else if (thumb.getTag(R.id.home_image_item) != r.art()) {
					HomeImages.loadItem(act(), thumb, r.art());
				}
			} else {
				thumb.setVisibility(GONE);
			}
			if ((r.sub() != null) || (r.subFill() != null)) {
				sub.setVisibility(VISIBLE);
				sub.setText((r.sub() != null) ? r.sub() : "");
				if (r.subFill() != null) r.subFill().accept(txt -> {
					if (row == r) sub.setText(txt);
				});
			} else {
				sub.setVisibility(GONE);
			}
			playBtn.setVisibility((r.play() != null) ? VISIBLE : GONE);
			updateBadge();
		}

		/** Shown when the row's song is on the phone: the word on a wide card, only the icon on a narrow one. */
		void updateBadge() {
			Row r = row;
			boolean on = (r != null) && isDownloaded(r);
			badge.setVisibility(on ? VISIBLE : GONE);
			if (!on) return;
			boolean narrow = widthPx() < px(300);
			badge.setText(narrow ? "" : ctx.getString(R.string.home_downloaded));
			badge.setContentDescription(ctx.getString(R.string.home_downloaded));
			int padH = narrow ? px(5) : px(8);
			badge.setPadding(padH, 0, padH, 0);
			badge.setCompoundDrawablePadding(narrow ? 0 : px(4));
		}
	}
}
