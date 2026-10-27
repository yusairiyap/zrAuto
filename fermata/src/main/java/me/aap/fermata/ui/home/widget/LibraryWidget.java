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
import androidx.recyclerview.widget.ItemTouchHelper;
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
import me.aap.fermata.ui.view.MediaItemMenuHandler;
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
	private int rowModeShown;
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
	/** The library's change count when the list was last asked for. */
	private int seenChanges = -1;
	/** What the rows on screen were made from, so an unchanged refresh doesn't rebuild (and blink) them. */
	private String shownSignature = "";
	private List<Row> rows = new ArrayList<>();
	private int total;
	private int capacity = 3;
	private boolean built;
	private long loadedAt;
	private boolean loading;
	/** The list is fading out for going back; its rows are swapped while it is hidden and fade in after. */
	private boolean backing;
	private final FermataServiceUiBinder.Listener playingListener = new FermataServiceUiBinder.Listener() {
		@Override
		public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
			post(() -> {
				if ((drillSource != null) || marksPlayingOnTop()) refresh(true);
			});
		}
	};
	/** Favorites, a playlist or the list of playlists changed (from a menu, a tab, anywhere): the rows are read again at once. */
	private final Runnable libraryListener = () -> post(this::libraryChanged);
	private final Runnable readAgain = () -> refresh(true);

	/** Many adds in a row (an import) read the list once, a moment after the last, not once per song. */
	private void libraryChanged() {
		removeCallbacks(readAgain);
		postDelayed(readAgain, 120);
	}
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

	/** Whether changes to Favorites and the playlists redraw this list (not the queue's: it has its own listener). */
	protected boolean followsLibrary() {
		return true;
	}

	/**
	 * Whether a long press on a row opens the same menu as in the real tab (the option "Long-press menu", on unless
	 * switched off). While it is on, a long press on a row no longer picks the card up and starts edit mode; in edit
	 * mode a hold still picks the card up.
	 */
	protected boolean supportsMenu() {
		return false;
	}

	/**
	 * Whether a touch on the card on the car screen gives it the Next/Previous keys (the option "Control with car keys", on unless
	 * switched off): the rows are outlined and walked through with short presses, long Next opens the row (a playlist) or plays it,
	 * long Previous goes up one level, and from the outermost list gives the keys back to the user's own bindings.
	 */
	protected boolean supportsCarKeys() {
		return true;
	}

	private boolean carKeysOn() {
		return supportsCarKeys() && optBool("carKeys", true);
	}

	/** The card the car keys are on right now (one at a time). */
	@Nullable
	private static LibraryWidget carFocused;
	/** The list was replaced (opened a playlist, went back): the outline goes to its first row once it is in. */
	private boolean carReselect;

	@Override
	public boolean dispatchTouchEvent(android.view.MotionEvent e) {
		if ((e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) && built && carKeysOn() && !host.isEditing() &&
				me.aap.fermata.action.CarNav.isEnabled()) {
			takeCarKeys();
		}
		return super.dispatchTouchEvent(e);
	}

	private void takeCarKeys() {
		if (carFocused == this) return;
		if (carFocused != null) carFocused.releaseCarKeys();
		carFocused = this;
		me.aap.fermata.action.CarNav.markModalScope(listView, this::carBack);
		listView.post(() -> {
			if (carFocused == this) me.aap.fermata.action.CarNav.selectFirst(act(), listView);
		});
	}

	/** Long Previous: up out of an opened list, else the keys go back to their usual bindings. */
	private void carBack() {
		if (drillSource != null) {
			backOut();
			return;
		}
		releaseCarKeys();
	}

	private void releaseCarKeys() {
		if (carFocused == this) carFocused = null;
		carReselect = false;
		if (listView != null) me.aap.fermata.action.CarNav.release(listView);
	}

	private boolean menuOn() {
		return supportsMenu() && optBool("menu", true);
	}

	/**
	 * Whether the rows can be moved with their drag handle (the option "Drag handle"), with {@link #onReordered} saying
	 * where the row went. A long press never picks a row up to move it: it opens the row's menu (or edit mode elsewhere).
	 */
	protected boolean supportsReorder() {
		return false;
	}

	/** Whether the rows on show are the card's own list, which may be changed (not a list opened inside the card). */
	protected boolean rowsEditable() {
		return drillSource == null;
	}

	/** The default of the option "Drag handle". */
	protected boolean handleByDefault() {
		return true;
	}

	protected boolean reorderOn() {
		return supportsReorder() && rowsEditable();
	}

	/** A row was dropped at a new place: {@code from} and {@code to} are positions in the rows as fetched. */
	protected void onReordered(int from, int to) {
	}

	/** Whether playing from the card can bring a Queue widget stacked with it to the top (an option). */
	protected boolean supportsQueueOnPlay() {
		return false;
	}

	/** After something is played from the card: the Queue of its stack comes to the top, if the option says so. */
	protected final void queueOnPlay() {
		if (supportsQueueOnPlay() && optBool("queueOnPlay", false)) bringStackTo("queue");
	}

	/** Whether the card can show its rows one per line across its whole width (an option). */
	protected boolean supportsFullWidth() {
		return false;
	}

	/** Whether each row has a remove button on its left (the option "Remove button"; see {@link #removeByDefault}). */
	protected boolean supportsRemove() {
		return false;
	}

	/** The default of the option "Remove button". */
	protected boolean removeByDefault() {
		return true;
	}

	/** Whether the card has pinned rows: the option "Hide pinned icon" (under "Hide downloaded icon") and a thin line between them and the rest. */
	protected boolean supportsPins() {
		return false;
	}

	/** Whether the row is one the user pinned to the top (a small pin at the end of the row, unless "Hide pinned icon" is on). */
	protected boolean isPinnedRow(Row r) {
		return false;
	}

	/** The option "Hide pinned icon". */
	private boolean pinHidden() {
		return optBool("hidePinned", false);
	}

	/** The option "Hide downloaded icon": the small badge on rows whose song is on the phone stays off. */
	private boolean badgeHidden() {
		return optBool("hideDownloaded", false);
	}

	protected boolean removeOn() {
		return supportsRemove() && rowsEditable() && optBool("remove", removeByDefault());
	}

	/** A row's remove button was pressed: {@code position} is its place in the rows as fetched, {@code row} the row itself. */
	protected void onRemoved(int position, Row row) {
	}

	/** The grip on the right of each row, which picks the row up at once (the option "Drag handle"; needs reordering on). */
	private boolean handleOn() {
		return reorderOn() && optBool("handle", handleByDefault());
	}

	private ItemTouchHelper touchHelper;
	private boolean dragging;
	private boolean refreshAfterDrag;
	private int dragFrom = -1;
	private int dragTo = -1;

	private final class ReorderCallback extends ItemTouchHelper.Callback {
		@Override
		public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
			int dirs = !reorderOn() ? 0 : (cols > 1) ? (ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT |
					ItemTouchHelper.RIGHT) : (ItemTouchHelper.UP | ItemTouchHelper.DOWN);
			return makeMovementFlags(dirs, 0);
		}

		@Override
		public boolean isLongPressDragEnabled() {
			// Moved with the drag handle only: the long press is the row's menu.
			return false;
		}

		@Override
		public boolean isItemViewSwipeEnabled() {
			return false;
		}

		@Override
		public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
			int f = from.getAdapterPosition();
			int t = to.getAdapterPosition();
			if ((f == RecyclerView.NO_POSITION) || (t == RecyclerView.NO_POSITION) || (f >= rows.size()) || (t >= rows.size())) {
				return false;
			}
			if (dragFrom == -1) dragFrom = f;
			dragTo = t;
			rows.add(t, rows.remove(f));
			adapter.notifyItemMoved(f, t);
			return true;
		}

		@Override
		public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
		}

		@Override
		public void onSelectedChanged(@Nullable RecyclerView.ViewHolder vh, int state) {
			super.onSelectedChanged(vh, state);
			if (state == ItemTouchHelper.ACTION_STATE_DRAG) {
				dragging = true;
				adapter.adopt(rows);
				listView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
				if (vh != null) vh.itemView.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();
			}
		}

		@Override
		public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
			super.clearView(rv, vh);
			vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
			int f = dragFrom;
			int t = dragTo;
			dragFrom = dragTo = -1;
			dragging = false;
			// Committed once the drop is done, not on every step (the Music tab does the same).
			if ((f != -1) && (t != -1) && (f != t)) onReordered(f, t);
			if (refreshAfterDrag || (f != -1)) {
				refreshAfterDrag = false;
				post(() -> refresh(true));
			}
		}
	}

	/** The row's own long-press menu, the one the library tab shows for that item. */
	protected void showMenu(Row r) {
		if (r.art() == null) return;
		MainActivityDelegate a = act();
		try {
			// Whatever the menu did (removed from Favorites, added to a playlist, queued...) shows in the cards at once: the
			// library gives no notice of its own, so every list on Home is read again when the menu closes, and once more
			// shortly after for changes that finish in the background.
			new MediaItemMenuHandler(a.getContextMenu(), r.art(), true).onClosed(() -> {
				a.fireBroadcastEvent(ActivityListener.FRAGMENT_CONTENT_CHANGED);
				postDelayed(() -> a.fireBroadcastEvent(ActivityListener.FRAGMENT_CONTENT_CHANGED), 450);
			}).show();
		} catch (RuntimeException ex) {
			me.aap.fermata.util.DiagnosticLog.log("HOME", "row menu failed", String.valueOf(ex));
		}
	}

	/** Whether the top-level rows are songs too, so a song change redraws them (not only an opened playlist). */
	protected boolean marksPlayingOnTop() {
		return false;
	}

	/**
	 * A song row of Favorites or a playlist, with its artist (the channel for a YouTube video) on the second line, as the
	 * Queue card has it: what the item already knows (its loaded metadata, the YouTube addon's stored info), else
	 * "YouTube" for a video until its metadata is read, which then fills the line in.
	 */
	protected static Row songRow(PlayableItem p, Runnable click, @Nullable Runnable play, boolean current) {
		String artist = artistOf(p);
		Consumer<Consumer<CharSequence>> fill = null;
		if (artist == null) {
			if (YtDownloads.videoIdOf(p) != null) artist = "YouTube";
			fill = set -> p.getMediaData().main().onSuccess(m -> {
				String a = (m == null) ? null : me.aap.fermata.addon.music.MusicTrackItem.cleanArtist(
						m.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST));
				if ((a != null) && !a.isEmpty()) set.accept(a);
			});
		}
		return new Row(p.getName(), artist, p, click, play, fill, current);
	}

	@Nullable
	private static String artistOf(PlayableItem p) {
		if (p instanceof me.aap.fermata.addon.music.MusicTrackItem t) {
			String a = t.getArtistName();
			if ((a != null) && !a.isEmpty()) return a;
		}
		android.support.v4.media.MediaMetadataCompat md = p.getMediaData().peek();
		if (md != null) {
			String a = me.aap.fermata.addon.music.MusicTrackItem.cleanArtist(
					md.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST));
			if ((a != null) && !a.isEmpty()) return a;
		}
		String vid = YtDownloads.videoIdOf(p);
		if (vid != null) {
			for (me.aap.fermata.addon.FermataAddon ad : me.aap.fermata.addon.AddonManager.get().getAddons()) {
				if (!(ad instanceof me.aap.fermata.addon.VideoTitleCache c)) continue;
				me.aap.fermata.addon.VideoTitleCache.VideoInfo vi = c.getCachedVideoInfo(vid);
				String a = me.aap.fermata.addon.music.MusicTrackItem.cleanArtist(vi != null ? vi.artist : null);
				if ((a != null) && !a.isEmpty()) return a;
				break;
			}
		}
		return null;
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
		if (host.animate()) {
			// Rows that leave fade out and the ones below slide up into the gap (a removed queue song, a favorite taken out);
			// rows that change in place are not animated, which made every refresh blink.
			androidx.recyclerview.widget.DefaultItemAnimator ia = new androidx.recyclerview.widget.DefaultItemAnimator();
			ia.setSupportsChangeAnimations(false);
			ia.setAddDuration(200);
			ia.setRemoveDuration(220);
			ia.setMoveDuration(240);
			listView.setItemAnimator(ia);
		} else {
			listView.setItemAnimator(null);
		}
		listView.setNestedScrollingEnabled(false);
		listView.setVerticalScrollBarEnabled(false);
		listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
		listView.setVerticalFadingEdgeEnabled(true);
		listView.setFadingEdgeLength(px(22));
		listView.setItemViewCacheSize(4);
		listView.addItemDecoration(new PinSplitDecoration());
		listView.getRecycledViewPool().setMaxRecycledViews(0, 24);
		if (supportsReorder()) {
			touchHelper = new ItemTouchHelper(new ReorderCallback());
			touchHelper.attachToRecyclerView(listView);
		}
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
		if (isActive()) {
			refresh(true);
		} else if (rows.isEmpty()) {
			// A card waiting in a stack (or off screen) reads its list a moment after it was made, so the rows and their
			// pictures are there already when it is swiped to, instead of loading in front of the finger.
			removeCallbacks(prefetch);
			postDelayed(prefetch, 700);
		}
	}

	private final Runnable prefetch = () -> {
		if (built && !isActive() && rows.isEmpty() && !loading) refresh(false);
	};

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
		if (followsLibrary()) me.aap.fermata.media.lib.ItemContainer.addContentListener(libraryListener);
		if (watchesPlaying()) act().getMediaServiceBinder().addBroadcastListener(playingListener);
		// Back on screen: the playing song is brought into view again.
		lastFocusKey = null;
		render();
		// Changes made while Home was away (in a tab) are not waited for until the list is old enough to be read again;
		// a list that nothing has changed since it was read keeps its rows (a long one is read from the library again
		// only when it has to be: coming back to Home and swiping to a card showed it late).
		boolean changed = !followsLibrary() || (me.aap.fermata.media.lib.ItemContainer.changeCount() != seenChanges) || rows.isEmpty();
		refresh(changed);
	}

	@Override
	protected void onInactive() {
		releaseCarKeys();
		act().removeBroadcastListener(contentListener);
		YtDownloads.get().removeListener(downloadsListener);
		me.aap.fermata.media.lib.ItemContainer.removeContentListener(libraryListener);
		removeCallbacks(readAgain);
		removeCallbacks(refreshBadges);
		if (watchesPlaying()) act().getMediaServiceBinder().removeBroadcastListener(playingListener);
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.sw("art", ctx.getString(R.string.home_opt_artwork), true));
		if (supportsMenu()) l.add(HomeOption.sw("menu", ctx.getString(R.string.home_opt_row_menu), true));
		if (supportsReorder()) l.add(HomeOption.sw("handle", ctx.getString(R.string.home_opt_row_handle), handleByDefault()));
		if (supportsRemove()) {
			l.add(HomeOption.sw("remove", ctx.getString(R.string.home_opt_row_remove), removeByDefault()));
		}
		l.add(HomeOption.sw("hideDownloaded", ctx.getString(R.string.home_opt_hide_downloaded), false));
		if (supportsPins()) l.add(HomeOption.sw("hidePinned", ctx.getString(R.string.home_opt_hide_pinned), false));
		if (supportsCarKeys()) l.add(HomeOption.sw("carKeys", ctx.getString(R.string.home_opt_car_keys), true));
		if (supportsFullWidth()) l.add(HomeOption.sw("full", ctx.getString(R.string.home_opt_row_full), false));
		if (supportsQueueOnPlay()) {
			l.add(HomeOption.sw("queueOnPlay", ctx.getString(R.string.home_opt_queue_on_play), false));
		}
		l.addAll(moreOptions());
		return l;
	}

	protected List<HomeOption> moreOptions() {
		return new ArrayList<>();
	}

	/** Opens something inside the card: its rows replace the list, under a chip that goes back. */
	protected final void drill(CharSequence title, FetchSource source) {
		drillSource = source;
		carReselect = carFocused == this;
		backChip.setText(title);
		showBack(true);
		reset();
	}

	private void backOut() {
		if (drillSource == null) return;
		drillSource = null;
		onBackOut();
		carReselect = carFocused == this;
		if (!host.animate() || !isShown() || !built) {
			showBack(false);
			reset();
			return;
		}
		// The rows stay until the playlists are read (no placeholder in between). The list fades out, and only then the back
		// chip leaves and the list goes back to its top, all while it is hidden: before, the list jumped while visible (the
		// chip collapsing moved it, and it kept the folder's scroll).
		backing = true;
		focusRow = -1;
		lastFocusKey = null;
		listView.animate().cancel();
		listView.animate().alpha(0f).setDuration(120).withEndAction(() -> {
			backing = false;
			// The rows and the chip change while the list is hidden; the chip collapses without an animation, which would
			// slide the list while it fades back in.
			render();
			// Something opened again meanwhile (a card made again reopening its playlist): its chip stays.
			showBack(drillSource != null, false);
			if (!loading) showRowsAgain();
		}).start();
		refresh(true);
	}

	/** The card went back out of what was opened in it (its back chip, the car's long Previous). */
	protected void onBackOut() {
	}

	/** Something is opened inside the card (see {@link #drill}). */
	protected final boolean isDrilled() {
		return drillSource != null;
	}

	private void showRowsAgain() {
		listView.animate().cancel();
		listView.animate().alpha(1f).setDuration(160).start();
	}

	private void showBack(boolean on) {
		showBack(on, true);
	}

	private void showBack(boolean on, boolean animate) {
		if (animate && host.animate() && isShown()) {
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
		seenChanges = me.aap.fermata.media.lib.ItemContainer.changeCount();
		int gen = ++fetchGen;
		if (built) updateBusy();
		FetchSource src = (drillSource != null) ? drillSource : this::fetch;
		src.fetch(act(), (r, n) -> {
			if (gen != fetchGen) return;
			loading = false;
			// Rows are being moved under a finger: the new list is read once the drop is done.
			if (dragging) {
				refreshAfterDrag = true;
				return;
			}
			loadedAt = SystemClock.uptimeMillis();
			rows = r;
			total = n;
			// Going back: the rows are set once the list has faded out (see backOut), never while it is still visible.
			if (!backing) render();
			// Back to the playlists: once they are read and the list is hidden, it fades in.
			if (!backing && (listView.getAlpha() < 1f)) showRowsAgain();
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
		// Wide cards split their rows into two columns, unless the card asks for full-width rows.
		int newCols = ((getCellsW() >= 4) && !(supportsFullWidth() && optBool("full", false))) ? 2 : 1;
		boolean art = optBool("art", true);
		// Bit 2 used to be "reorder on": it changes when a playlist opens (its rows can be moved, the top list not), which
		// relaid the card out on every open and back. The drag handle (bit 8) is what shows, so that is the one counted.
		int rowMode = (menuOn() ? 1 : 0) | (removeOn() ? 4 : 0) | (handleOn() ? 8 : 0) |
				(badgeHidden() ? 16 : 0) | (pinHidden() ? 32 : 0);
		// Drawn again only when something it shows changed: the rows blinked on every refresh.
		StringBuilder sig = new StringBuilder();
		sig.append(total).append('|').append(isCompact()).append('|').append(newCols).append('|')
				.append(art).append('|').append(rowMode).append('|').append(loading && none).append('|').append(drillSource != null);
		for (Row r : rows) sig.append('|').append(r.title()).append(r.sub()).append(r.current()).append(isPinnedRow(r));
		String signature = sig.toString();
		if (signature.equals(shownSignature)) {
			// Nothing to redraw, but the adapter must hold the list the rows are now taken from: a row picked up or removed is
			// moved in that list, and with the old one still there the rows were rebound from a list that had not moved.
			adapter.adopt(rows);
			return;
		}
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
		boolean full = (newCols != cols) || (art != artOn) || (rowMode != rowModeShown) || wasEmpty;
		cols = newCols;
		artOn = art;
		rowModeShown = rowMode;
		glm.setSpanCount(cols);
		int lead = 0;
		if (supportsPins() && (drillSource == null)) {
			while ((lead < rows.size()) && isPinnedRow(rows.get(lead))) lead++;
		}
		setPinSplit(((lead > 0) && (lead < rows.size()) && (cols == 1)) ? lead - 1 : -1);
		adapter.set(rows, full);
		if (carReselect) {
			carReselect = false;
			listView.post(() -> {
				if (carFocused == this) me.aap.fermata.action.CarNav.selectFirst(act(), listView);
			});
		}
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

		/** Takes {@code now} as the rows without telling anyone: they look the same as the ones held. */
		void adopt(List<Row> now) {
			data = now;
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

	/** The position of the last pinned row when unpinned ones follow it (a thin line is drawn under it), else -1. */
	private int pinSplit = -1;
	/** How much of that line shows: it fades in when it comes. */
	private float pinSplitAlpha = 1f;
	private android.animation.ValueAnimator pinSplitAnim;

	private void setPinSplit(int pos) {
		if (pos == pinSplit) return;
		boolean appears = (pinSplit < 0) && (pos >= 0);
		pinSplit = pos;
		if (pinSplitAnim != null) pinSplitAnim.cancel();
		if (appears && host.animate()) {
			pinSplitAlpha = 0f;
			pinSplitAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
			pinSplitAnim.setStartDelay(160);
			pinSplitAnim.setDuration(300);
			pinSplitAnim.addUpdateListener(an -> {
				pinSplitAlpha = (Float) an.getAnimatedValue();
				listView.invalidate();
			});
			pinSplitAnim.start();
		} else {
			pinSplitAlpha = 1f;
		}
		listView.invalidateItemDecorations();
	}

	/**
	 * A tiny inline separator between the pinned rows and the others: a short hairline in the gap under the last pinned row. It is
	 * drawn on the row's own bottom edge and alpha, so it moves and fades with the row while the list animates.
	 */
	private final class PinSplitDecoration extends RecyclerView.ItemDecoration {
		private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

		@Override
		public void getItemOffsets(android.graphics.Rect out, View view, RecyclerView parent, RecyclerView.State state) {
			if ((pinSplit >= 0) && (parent.getChildAdapterPosition(view) == pinSplit)) out.bottom = px(8);
		}

		@Override
		public void onDraw(android.graphics.Canvas c, RecyclerView parent, RecyclerView.State state) {
			if (pinSplit < 0) return;
			for (int i = 0, n = parent.getChildCount(); i < n; i++) {
				View v = parent.getChildAt(i);
				if (parent.getChildAdapterPosition(v) != pinSplit) continue;
				float y = v.getBottom() + v.getTranslationY() + px(4);
				float a = v.getAlpha() * pinSplitAlpha;
				paint.setColor(color(ROLE_SECONDARY));
				paint.setAlpha(Math.round(70 * a));
				float inset = px(26);
				c.drawRoundRect(inset, y - px(0.5f), parent.getWidth() - inset, y + px(0.5f), px(1), px(1), paint);
			}
		}
	}

	private final class RowHolder extends RecyclerView.ViewHolder {
		private final LinearLayout main;
		private final ImageView thumb;
		private final TextView title;
		private final TextView sub;
		private final ImageView playBtn;
		/** Left of the row: takes it out of the list (the queue). */
		private final ImageView removeBtn;
		/** Right of the row: pressed down it picks the row up to move it at once. */
		private final ImageView handle;
		/** A small download icon at the end of the row of a song that is on the phone: part of the row, no chip of its own. */
		private final ImageView badge;
		/** A small pin at the end of a pinned playlist's row. */
		private final ImageView pinBadge;
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
			removeBtn = iconButton(R.drawable.home_row_remove, 32, ROLE_SECONDARY, R.string.home_row_remove, () -> {
				int pos = getAdapterPosition();
				if ((pos == RecyclerView.NO_POSITION) || (pos >= rows.size())) return;
				// Gone from the card at once; the real list follows when the source reports the change.
				adapter.adopt(rows);
				Row gone = rows.remove(pos);
				adapter.notifyItemRemoved(pos);
				if (cols > 1) adapter.notifyItemRangeChanged(pos, rows.size() - pos);
				if (total > 0) count.setText(String.valueOf(total - 1));
				onRemoved(pos, gone);
			});
			removeBtn.setVisibility(GONE);
			removeBtn.setTag(R.id.home_no_grab, Boolean.TRUE);
			// Car keys walk through the rows only; the row's own buttons are for fingers.
			me.aap.fermata.action.CarNav.skip(removeBtn);
			root.addView(removeBtn);
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

			badge = new ImageView(ctx);
			badge.setImageResource(R.drawable.download);
			badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
			badge.setVisibility(GONE);
			LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(18), px(18));
			blp.setMarginStart(px(6));
			blp.setMarginEnd(px(10));
			main.addView(badge, blp);

			pinBadge = new ImageView(ctx);
			pinBadge.setImageResource(R.drawable.pin);
			pinBadge.setScaleType(ImageView.ScaleType.FIT_CENTER);
			pinBadge.setVisibility(GONE);
			pinBadge.setContentDescription(ctx.getString(R.string.playlist_pinned));
			LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(px(18), px(18));
			plp.setMarginEnd(px(10));
			main.addView(pinBadge, plp);

			playBtn = iconButton(R.drawable.play, 36, ROLE_ICON, R.string.action_play, () -> {
				if ((row != null) && (row.play() != null)) row.play().run();
			});
			me.aap.fermata.action.CarNav.skip(playBtn);
			root.addView(playBtn);

			handle = iconButton(R.drawable.drag_handle, 32, ROLE_SECONDARY, R.string.home_row_drag, () -> {
			});
			handle.setVisibility(GONE);
			handle.setTag(R.id.home_no_grab, Boolean.TRUE);
			me.aap.fermata.action.CarNav.skip(handle);
			handle.setOnTouchListener((v, e) -> {
				if ((e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) && (touchHelper != null) && reorderOn()) {
					listView.getParent().requestDisallowInterceptTouchEvent(true);
					touchHelper.startDrag(this);
					return true;
				}
				return false;
			});
			root.addView(handle);
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
				badge.setImageTintList(android.content.res.ColorStateList.valueOf(color(ROLE_SECONDARY)));
				pinBadge.setImageTintList(android.content.res.ColorStateList.valueOf(color(ROLE_SECONDARY)));
			}
			main.setContentDescription(r.title());
			// A long press on the row opens its menu: it is marked so the card does not take that press for picking itself up.
			boolean menu = menuOn() && (r.art() != null);
			main.setTag(R.id.home_no_grab, menu ? Boolean.TRUE : null);
			int bs = narrow ? px(28) : px(32);
			for (ImageView b : new ImageView[]{removeBtn, handle}) {
				ViewGroup.LayoutParams bl = b.getLayoutParams();
				if (bl.width != bs) {
					bl.width = bs;
					bl.height = bs;
					b.setLayoutParams(bl);
					int bp = Math.round(bs * 0.26f);
					b.setPadding(bp, bp, bp, bp);
				}
			}
			removeBtn.setVisibility(removeOn() ? VISIBLE : GONE);
			handle.setVisibility(handleOn() ? VISIBLE : GONE);
			main.setOnLongClickListener(menu ? v -> {
				showMenu(r);
				return true;
			} : null);
			main.setLongClickable(menu);
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

		/** Shown when the row's song is on the phone, at the end of the row itself (so the playing row's fill runs under it). */
		void updateBadge() {
			Row r = row;
			boolean on = (r != null) && !badgeHidden() && isDownloaded(r);
			badge.setVisibility(on ? VISIBLE : GONE);
			pinBadge.setVisibility((r != null) && !pinHidden() && isPinnedRow(r) ? VISIBLE : GONE);
			if (on) badge.setContentDescription(ctx.getString(R.string.home_downloaded));
		}
	}
}
