package me.aap.fermata.ui.fragment;

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static me.aap.fermata.media.engine.MediaEngine.NO_SUBTITLES;
import static me.aap.fermata.media.pref.BrowsableItemPrefs.SORT_MASK_NAME_RND;
import static me.aap.fermata.ui.activity.MainActivityPrefs.getGridViewPrefKey;
import static me.aap.fermata.ui.activity.MainActivityPrefs.hasGridViewPref;
import static me.aap.fermata.ui.activity.MainActivityPrefs.hasTextIconSizePref;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedNull;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.collection.CollectionUtils.filterMap;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.ArchiveItem;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.EpgItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.StreamItem;
import me.aap.fermata.media.lib.SearchFolder;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.activity.VoiceCommand;
import me.aap.fermata.ui.view.BodyLayout;
import me.aap.fermata.ui.view.MediaItemListView;
import me.aap.fermata.ui.view.MediaItemListViewAdapter;
import me.aap.fermata.ui.view.MediaItemMenuHandler;
import me.aap.fermata.ui.view.MediaItemView;
import me.aap.fermata.ui.view.MediaItemViewHolder;
import me.aap.fermata.ui.view.MediaItemWrapper;
import me.aap.utils.app.App;
import me.aap.utils.async.Async;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.BooleanConsumer;
import me.aap.utils.function.Function;
import me.aap.utils.holder.Holder;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
public abstract class MediaLibFragment extends MainActivityFragment implements MainActivityListener,
		PreferenceStore.Listener, FermataServiceUiBinder.Listener, ToolBarView.Listener {
	private ListAdapter adapter;
	private boolean noScroll;
	private int scrollPosition;
	/**
	 * Where the list was scrolled to when the user left this tab (another tab shown, e.g. the
	 * YouTube player after tapping a video), and for which folder: put back exactly on return,
	 * rather than jumping to the playing or last played entry.
	 */
	@Nullable
	private Parcelable savedListState;
	@Nullable
	private String savedListParentId;
	private Item clicked;

	protected abstract ListAdapter createAdapter(FermataServiceUiBinder b);

	public abstract CharSequence getFragmentTitle();

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return ToolBarMediator.instance;
	}

	@Override
	public CharSequence getTitle() {
		ListAdapter adapter = getAdapter();
		if (adapter == null) return getFragmentTitle();
		BrowsableItem parent = adapter.getParent();
		if ((parent != null) && (parent.getParent() != null)) return parent.getName();
		else return getFragmentTitle();
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.media_items_list_view, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
			MediaItemListView v = getListView();
			PreferenceStore ap = a.getPrefs();
			FermataServiceUiBinder b = a.getMediaServiceBinder();
			adapter = createAdapter(b);
			ItemTouchHelper h = new ItemTouchHelper(adapter.getItemTouchCallback());
			adapter.setItemTouchHelper(h);
			adapter.setListView(v);
			v.setAdapter(adapter);
			h.attachToRecyclerView(v);
			ap.addBroadcastListener(v);
			ap.addBroadcastListener(this);
			a.addBroadcastListener(this);
			a.getToolBar().addBroadcastListener(this);
			b.getLib().getPrefs().addBroadcastListener(this);
			b.addBroadcastListener(this);
			Log.d("MediaLibFragment view created: ", this);
		});
	}

	@Override
	public void onDestroyView() {
		saveListState();
		scrollPosition = -1;
		cleanUp(getMainActivity());
		super.onDestroyView();
		Log.d("MediaLibFragment view destroyed: ", this);
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		scrollToPosition();
	}

	private void cleanUp(MainActivityDelegate a) {
		Log.d("Cleaning up fragment: ", this);
		MediaItemListView v = (MediaItemListView) getView();
		PreferenceStore ap = a.getPrefs();
		FermataServiceUiBinder b = a.getMediaServiceBinder();
		ap.removeBroadcastListener(this);
		a.removeBroadcastListener(this);
		a.getToolBar().removeBroadcastListener(this);
		b.getLib().getPrefs().removeBroadcastListener(this);
		b.removeBroadcastListener(this);
		if (v != null) ap.removeBroadcastListener(v);
		if (adapter != null) adapter.onDestroy();
	}

	@NonNull
	public MainActivityDelegate getMainActivity() {
		return MainActivityDelegate.get(getContext());
	}

	@NonNull
	public FutureSupplier<MainActivityDelegate> getMainActivityDelegate() {
		return MainActivityDelegate.getActivityDelegate(getContext());
	}

	@NonNull
	public MediaLib getLib() {
		return getMainActivity().getLib();
	}

	@Override
	public boolean isRootPage() {
		ListAdapter a = getAdapter();
		if (a == null) return true;
		BrowsableItem p = a.getParent();
		return (p == null) || (p.getParent() == null);
	}

	public void openItem(BrowsableItem folder) {
		if (isHidden()) return;
		ListAdapter a = getAdapter();
		if (!folder.equals(a.getParent())) a.setParent(folder);
	}

	public void revealItem(Item i) {
		if (isHidden()) return;
		ListAdapter a = getAdapter();
		BrowsableItem p = i.getParent();
		if (p == null) return;
		if (!p.equals(a.getParent())) a.setParent(p);
		// Make sure the list is loaded
		p.getChildren().main(getMainActivity().getHandler()).onSuccess(l -> getListView().focusTo(i));
	}

	/**
	 * Whether opening this tab goes to what's playing in it (into its playlist) and highlights it:
	 * Favorites and Playlists.
	 */
	protected boolean revealsPlaying() {
		return false;
	}

	@Override
	public void switchingFrom(@Nullable me.aap.utils.ui.fragment.ActivityFragment from) {
		super.switchingFrom(from);
		// Posted: this runs before the switch is committed (and before a new tab has its view).
		if (revealsPlaying() && (from != this)) {
			getMainActivityDelegate().onSuccess(a -> a.post(this::revealPlaying));
		}
	}

	/**
	 * See {@link #revealsPlaying()}. What's playing may not be this tab's own item even when it's in
	 * it: a Music tab queue track plays the song it was queued from, YouTube plays its own video
	 * item. So the session's item and the song behind it are tried first, then this tab is searched
	 * for an entry of the same song (the open playlist first).
	 */
	private void revealPlaying() {
		if (isHidden() || (getView() == null) || (adapter == null)) return;
		MainActivityDelegate a = getMainActivity();
		BrowsableItem root = getAdapter().getRoot();
		if (root == null) return;

		List<PlayableItem> cands = new ArrayList<>(3);
		PlayableItem cur = a.getMediaSessionCallback().getCurrentItem();
		if (cur != null) cands.add(cur);
		if (cur instanceof me.aap.fermata.addon.music.MusicTrackItem t) {
			if (t.getSource() != null) cands.add(t.getSource());
		}
		PlayableItem fav = me.aap.fermata.action.Action.getFavoritableItem(a);
		if (fav != null) cands.add(fav);

		Set<String> ids = new HashSet<>();
		for (PlayableItem c : cands) {
			if (root.equals(c.getRoot())) {
				revealAndHighlight(a, c);
				return;
			}
			ids.add(c.getOrigId());
		}
		if (cur instanceof me.aap.fermata.addon.music.MusicTrackItem t) ids.add(t.getSourceId());
		if (ids.isEmpty()) return;

		root.getUnsortedChildren().main(a.getHandler()).onSuccess(children -> {
			List<BrowsableItem> folders = new ArrayList<>();
			for (Item c : children) {
				if ((c instanceof PlayableItem p) && ids.contains(p.getOrigId())) {
					revealAndHighlight(a, p);
					return;
				}
				if (c instanceof BrowsableItem b) folders.add(b);
			}
			// The open playlist first: the same song may be in several.
			BrowsableItem open = getAdapter().getParent();
			if ((open != null) && folders.remove(open)) folders.add(0, open);
			searchFolders(a, folders, 0, ids);
		});
	}

	/** One folder (playlist) at a time, until an entry of the playing song turns up. */
	private void searchFolders(MainActivityDelegate a, List<BrowsableItem> folders, int idx,
														 Set<String> ids) {
		if ((idx >= folders.size()) || isHidden()) return;
		folders.get(idx).getUnsortedChildren().main(a.getHandler()).onSuccess(children -> {
			for (Item c : children) {
				if ((c instanceof PlayableItem p) && ids.contains(p.getOrigId())) {
					revealAndHighlight(a, p);
					return;
				}
			}
			searchFolders(a, folders, idx + 1, ids);
		});
	}

	private void revealAndHighlight(MainActivityDelegate a, PlayableItem i) {
		BrowsableItem p = i.getParent();
		if (p == null) return;
		ListAdapter ad = getAdapter();
		if (!p.equals(ad.getParent())) ad.setParent(p);
		p.getChildren().main(a.getHandler()).onSuccess(l -> highlightWhenListed(a, i, 10));
	}

	/** The list fills in asynchronously after a folder change: retried until the item is in it. */
	private void highlightWhenListed(MainActivityDelegate a, Item i, int tries) {
		if (isHidden() || (getView() == null)) return;
		int pos = indexOf(getAdapter().getList(), i);
		if (pos < 0) {
			if (tries > 0) a.postDelayed(() -> highlightWhenListed(a, i, tries - 1), 100);
			return;
		}
		MediaItemListView lv = getListView();
		lv.scrollToPosition(pos, false);
		a.postDelayed(() -> me.aap.fermata.action.CarNav.highlightRow(a, lv, pos), 120);
	}

	public boolean onBackPressed() {
		MainActivityDelegate ad = getMainActivity();
		BodyLayout b = ad.getBody();

		if (b.isVideoMode()) {
			// Out of fullscreen, back to this list (a native fullscreen player leaves its own first).
			ad.exitVideoMode();
			return true;
		}

		ListAdapter a = getAdapter();
		BrowsableItem oldParent = a.getParent();
		if (oldParent == null) return false;
		BrowsableItem newParent = oldParent.getParent();
		if (newParent == null) return false;
		a.setParent(newParent);
		ad.post(() -> {
			revealItem(oldParent);
			// Car mode carries on from the folder (playlist) just left, outlined.
			if (me.aap.fermata.action.CarNav.isEnabled()) highlightWhenListed(ad, oldParent, 10);
		});
		return true;
	}

	@Override
	public void onRefresh(BooleanConsumer refreshing) {
		reload().onCompletion((r, f) -> refreshing.accept(false));
	}

	public FutureSupplier<?> reload() {
		discardSelection();
		return getAdapter().reload();
	}

	public FutureSupplier<?> refresh() {
		getLib().getVfsManager().clearCache();
		return getAdapter().getParent().refresh().main().thenRun(this::reload);
	}

	public void rescan() {
		getLib().getVfsManager().clearCache();
		getAdapter().getParent().rescan().main().thenRun(this::reload);
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) saveListState();
		else if (!restoreListState()) scrollToPosition();
	}

	private void saveListState() {
		View v = getView();
		ListAdapter a = adapter;
		if (!(v instanceof MediaItemListView lv) || (a == null) || (a.getParent() == null)) return;
		RecyclerView.LayoutManager lm = lv.getLayoutManager();
		if (lm == null) return;
		savedListState = lm.onSaveInstanceState();
		savedListParentId = a.getParent().getId();
	}

	/** Puts the list back where the user left it, if it's still showing the same folder. */
	private boolean restoreListState() {
		Parcelable st = savedListState;
		String id = savedListParentId;
		savedListState = null;
		savedListParentId = null;
		View v = getView();
		ListAdapter a = adapter;
		if ((st == null) || !(v instanceof MediaItemListView lv) || (a == null) ||
				(a.getParent() == null) || !a.getParent().getId().equals(id)) {
			return false;
		}
		RecyclerView.LayoutManager lm = lv.getLayoutManager();
		if (lm == null) return false;
		lm.onRestoreInstanceState(st);
		scrollPosition = -1;
		return true;
	}

	@Override
	public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
		scrollPosition = -1;
		ListAdapter a = getAdapter();
		BrowsableItem p = a.getParent();
		if (p == null) return;
		List<MediaItemWrapper> list = a.getList();

		if ((oldItem != null) && p.equals(oldItem.getParent())) {
			if ((newItem != null) && isSupportedItem(newItem)) {
				BrowsableItem newParent = newItem.getParent();

				if (p.equals(newParent)) {
					scrollPosition = indexOf(list, newItem);
				} else {
					a.setParent(newParent);
					scrollPosition = indexOf(a.getList(), newItem);
				}
			} else {
				scrollPosition = indexOf(list, oldItem);
			}
		}

		if (!isHidden()) {
			scrollToPosition();
			a.getListView().refreshState();
		}
	}

	@Override
	public void onDurationChanged(PlayableItem i) {
		for (MediaItemWrapper w : getAdapter().getList()) {
			if (i.equals(w.getItem())) {
				MediaItemView v = w.getView();
				if (v != null) v.refresh();
				break;
			}
		}
	}

	@Override
	public void contributeToNavBarMenu(OverlayMenu.Builder builder) {
		super.contributeToNavBarMenu(builder);

		OverlayMenu.Builder b = builder.withSelectionHandler(this::navBarMenuItemSelected);
		if (isRefreshSupported()) b.addItem(R.id.refresh, R.drawable.refresh, R.string.refresh);
		if (isRescanSupported()) b.addItem(R.id.rescan, R.drawable.loading, R.string.rescan);

		ListAdapter a = getAdapter();
		if (!a.hasSelectable()) return;

		if (a.getListView().isSelectionActive()) {
			b.addItem(R.id.nav_select_all, me.aap.utils.R.drawable.check_box, R.string.select_all);
			b.addItem(R.id.nav_unselect_all, me.aap.utils.R.drawable.check_box_blank,
					R.string.unselect_all);
		} else {
			b.addItem(R.id.nav_select, me.aap.utils.R.drawable.check_box, R.string.select);
		}
	}

	public void contributeToContextMenu(OverlayMenu.Builder builder, MediaItemMenuHandler handler) {
	}

	protected boolean navBarMenuItemSelected(OverlayMenuItem item) {
		int itemId = item.getItemId();

		if (itemId == R.id.nav_select || itemId == R.id.nav_select_all) {
			getAdapter().getListView().select(true);
			return true;
		} else if (itemId == R.id.nav_unselect_all) {
			getAdapter().getListView().select(false);
			return true;
		} else if (itemId == R.id.refresh) {
			refresh();
			return true;
		} else if (itemId == R.id.rescan) {
			rescan();
			return true;
		} else if (itemId == R.id.favorites_add) {
			requireNonNull(getLib()).getFavorites().addItems(filterMap(getAdapter().getList(),
					MediaItemWrapper::isSelected, (i, w, l) -> {
						if (w.getItem() instanceof PlayableItem p) l.add(p);
					},
					ArrayList::new));
			discardSelection();
			MediaLibFragment f = getMainActivity().getMediaLibFragment(R.id.favorites_fragment);
			if (f != null) f.reload();
			return true;
		}

		return false;
	}

	protected boolean isRefreshSupported() {
		return false;
	}

	protected boolean isRescanSupported() {
		return false;
	}

	public boolean isGridSupported() {
		return true;
	}

	public int getSupportedSortOpts() {
		BrowsableItem p = getAdapter().getParent();
		return (p == null) ? SORT_MASK_NAME_RND : p.getSupportedSortOpts();
	}

	protected boolean isSupportedItem(Item i) {
		return false;
	}

	/** Whether tapping an entry plays it as music while the Music tab is playing (see onClick). */
	protected boolean playsAsMusicInMusicMode() {
		return false;
	}

	@SuppressWarnings("unchecked")
	public <A extends ListAdapter> A getAdapter() {
		return (A) adapter;
	}

	@NonNull
	public MediaItemListView getListView() {
		return (MediaItemListView) requireView();
	}

	public void discardSelection() {
		getAdapter().getListView().discardSelection();
	}

	private void scrollToPosition() {
		getMainActivityDelegate().onSuccess(a -> a.post(() -> {
			int pos = scrollPosition;
			if (pos == -1) return;
			getListView().smoothScrollToPosition(pos);
		}));
	}

	private static final Set<PreferenceStore.Pref<?>> reloadOnPrefChange =
			new HashSet<>(Arrays.asList(
					BrowsableItemPrefs.TITLE_SEQ_NUM,
					BrowsableItemPrefs.TITLE_NAME,
					BrowsableItemPrefs.TITLE_FILE_NAME,
					BrowsableItemPrefs.SUBTITLE_NAME,
					BrowsableItemPrefs.SUBTITLE_FILE_NAME,
					BrowsableItemPrefs.SUBTITLE_ALBUM,
					BrowsableItemPrefs.SUBTITLE_ARTIST,
					BrowsableItemPrefs.SUBTITLE_DURATION,
					BrowsableItemPrefs.SORT_BY,
					BrowsableItemPrefs.SORT_DESC
			));

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (e == ACTIVITY_DESTROY) {
			cleanUp(a);
		}
	}

	@Override
	public void onToolBarEvent(ToolBarView tb, byte event) {
		try {
			noScroll = true;
			adapter.setFilter(tb.getFilter().getText().toString());
		} finally {
			noScroll = false;
		}
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		MainActivityDelegate a = getMainActivity();
		boolean viewChanged = hasGridViewPref(a, prefs);

		if (viewChanged || hasTextIconSizePref(getMainActivity(), prefs) ||
				prefs.contains(MainActivityPrefs.LIST_ITEM_SIZE)) {
			MediaItemListView list = (MediaItemListView) getView();

			if (list != null) {
				Context ctx = getContext();
				boolean grid = a.getPrefs().getGridViewPref(a);
				float size = a.getPrefs().getTextIconSizePref(a);

				for (MediaItemWrapper w : getAdapter().getList()) {
					MediaItemView v = w.getView();
					if (v == null) continue;
					if (viewChanged) v.applyLayout(ctx, grid, size);
					else v.setSize(ctx, grid, size);
				}
			}

			return;
		}

		BrowsableItem p = getAdapter().getParent();
		if (p == null) return;

		if (prefs.contains(BrowsableItemPrefs.SHOW_TRACK_ICONS)) {
			getAdapter().reload();
			return;
		}

		if (!store.equals(p.getPrefs())) return;
		if (prefs.contains(BrowsableItemPrefs.SHOW_TRACK_ICONS)) p.getRoot().updateTitles();
		if (!Collections.disjoint(reloadOnPrefChange, prefs)) getAdapter().reload();
	}

	@Override
	public boolean isVideoModeSupported() {
		return true;
	}

	@Override
	public boolean isVoiceCommandsSupported() {
		return true;
	}

	public FutureSupplier<BrowsableItem> findFolder(String name) {
		Holder<BrowsableItem> found = new Holder<>();
		return findFolder(getAdapter().getRoot(), name, found);
	}

	private FutureSupplier<BrowsableItem> findFolder(BrowsableItem folder, String name,
																									 Holder<BrowsableItem> found) {
		if (found.value != null) return completed(found.value);
		return folder.getUnsortedChildren().then(list -> {
			List<BrowsableItem> folders = new ArrayList<>();

			for (Item i : list) {
				if (!(i instanceof BrowsableItem) || (i instanceof StreamItem)) continue;
				if (name.equalsIgnoreCase(i.getName())) return completed(found.value = (BrowsableItem) i);
				else folders.add((BrowsableItem) i);
			}

			if (folders.isEmpty()) return completedNull();
			Iterator<BrowsableItem> it = folders.iterator();
			return Async.iterate(() -> it.hasNext() ? findFolder(it.next(), name, found) : null);
		});
	}

	public void openFolder(BrowsableItem folder) {
		getAdapter().setParent(folder, true, true);
	}

	public void play() {
		playFolder(getAdapter().getParent());
	}

	public void playFolder(BrowsableItem folder) {
		openFolder(folder);
		MainActivityDelegate a = getMainActivity();
		folder.getLastPlayedItem()
				.then(last -> (last == null) ? folder.getFirstPlayable() : completed(last))
				.main(a.getHandler())
				.onSuccess(p -> {
					if (p == null) return;
					a.getMediaServiceBinder().playItem(p);
					a.goToItem(p);
				});
	}

	@Override
	public void voiceCommand(VoiceCommand cmd) {
		BrowsableItem parent = getAdapter().getParent();
		if (parent == null) return;
		MainActivityDelegate a = getMainActivity();
		FermataServiceUiBinder b = a.getMediaServiceBinder();
		Function<List<PlayableItem>, BrowsableItem> ps = items -> {
			PlayableItem cur = b.getCurrentItem();
			return ((cur == null) || !items.contains(cur)) ? parent : cur.getParent();
		};

		boolean play = cmd.isPlay();
		SearchFolder.search(cmd.getQuery(), ps).main(a.getHandler()).onSuccess(f -> {
			if (f == null) return;
			List<PlayableItem> items = f.getItemsFound();
			if (items.isEmpty()) return;
			PlayableItem first = items.get(0);
			if (play) b.playItem(first);
			if (items.size() == 1) a.goToItem(first);
			else getAdapter().setParent(f);
			if (!play) a.post(() -> getListView().focusTo(first));
		});
	}

	public class ListAdapter extends MediaItemListViewAdapter {
		/** A bulk reorder is running: its own reload follows, the per-move ones are skipped. */
		boolean reordering;
		/** The dragged item is one of several selected ones: they all follow it on drop. */
		private boolean multiDrag;

		public ListAdapter(MainActivityDelegate activity, BrowsableItem parent) {
			super(activity);
			// The tab's first load is no user action, but a long one (a big playlist) should still
			// show the loading indicator -- which only appears if it takes a noticeable moment.
			activity.setContentLoading(super.setParent(parent, false));
		}

		@Override
		public FutureSupplier<?> setParent(BrowsableItem parent, boolean userAction) {
			BrowsableItem old = getParent();
			FutureSupplier<?> f = super.setParent(parent, userAction);
			// Another folder: car mode's next press starts from its first row.
			if (!Objects.equals(old, parent)) me.aap.fermata.action.CarNav.listChanged(getListView());
			return f;
		}

		/** See MainActivityPrefs#getTapOpensMenuPref(). */
		protected boolean tapOpensMenu() {
			MainActivityDelegate a = getMainActivity();
			return a.getPrefs().getTapOpensMenuPref(a);
		}

		/** Whether this list's own order (a playlist's, Favorites') can be edited from here. */
		protected boolean isReorderable() {
			return false;
		}

		/** Moves an item of the list's model; only called when {@link #isReorderable()}. */
		@Nullable
		protected FutureSupplier<Void> moveInModel(int from, int to) {
			return null;
		}

		/** Not sorted: the list shows its own order, which moves can edit. */
		boolean isCustomOrder() {
			BrowsableItem p = getParent();
			return (p == null) || (p.getPrefs().getSortByPref() == BrowsableItemPrefs.SORT_BY_NONE);
		}

		@Override
		protected void onDragStarted(@NonNull RecyclerView.ViewHolder vh) {
			super.onDragStarted(vh);
			multiDrag = false;
			MediaItemWrapper dragged = (vh instanceof MediaItemViewHolder h) ? h.getItemWrapper() : null;
			if ((dragged == null) || !dragged.isSelected() || !getListView().isSelectionActive() ||
					!isReorderable() || (getSelectedItems().size() < 2)) {
				return;
			}
			multiDrag = true;
			// The others fade while dragging: they'll be gathered around the dragged one on drop.
			for (MediaItemWrapper w : getList()) {
				MediaItemView v = w.getView();
				if ((w != dragged) && w.isSelected() && (v != null)) v.animate().alpha(0.35f).start();
			}
		}

		@Override
		protected void onDragEnded(@NonNull RecyclerView.ViewHolder vh) {
			super.onDragEnded(vh);
			if (!multiDrag) return;
			multiDrag = false;
			for (MediaItemWrapper w : getList()) {
				MediaItemView v = w.getView();
				if (v != null) v.animate().alpha(1f).start();
			}

			MediaItemWrapper dragged = (vh instanceof MediaItemViewHolder h) ? h.getItemWrapper() : null;
			if ((dragged == null) || !isReorderable()) return;
			List<MediaItemWrapper> sel = new ArrayList<>();
			for (MediaItemWrapper w : getList()) if (w.isSelected()) sel.add(w);
			List<MediaItemWrapper> order = new ArrayList<>(getList().size());

			for (MediaItemWrapper w : getList()) {
				if (w == dragged) order.addAll(sel); // The whole selection, in its existing order.
				else if (!w.isSelected()) order.add(w);
			}

			applyOrder(order);
		}

		/** Moves the selected items, in their current order, to the top or the end of the list. */
		void moveSelected(boolean toTop) {
			if (!isReorderable()) return;
			if (!isCustomOrder()) {
				UiUtils.showToast(getMainActivity().getContext(), R.string.playlist_sorted_hint);
				return;
			}
			List<MediaItemWrapper> sel = new ArrayList<>();
			List<MediaItemWrapper> rest = new ArrayList<>();
			for (MediaItemWrapper w : getList()) (w.isSelected() ? sel : rest).add(w);
			if (sel.isEmpty()) return;
			List<MediaItemWrapper> order = new ArrayList<>(getList().size());
			if (toTop) {
				order.addAll(sel);
				order.addAll(rest);
			} else {
				order.addAll(rest);
				order.addAll(sel);
			}
			applyOrder(order);
		}

		/**
		 * Reorders the list's model to {@code order} (a permutation of the current list) with the
		 * fewest moves, then reloads once, keeping the selection.
		 */
		void applyOrder(List<MediaItemWrapper> order) {
			List<MediaItemWrapper> cur = new ArrayList<>(getList());
			List<int[]> moves = new ArrayList<>();

			for (int to = 0; to < order.size(); to++) {
				int from = cur.indexOf(order.get(to));
				if (from > to) {
					moves.add(new int[]{from, to});
					cur.add(to, cur.remove(from));
				}
			}

			if (moves.isEmpty()) return;
			reordering = true;
			Async.forEach(m -> {
				FutureSupplier<Void> f = moveInModel(m[0], m[1]);
				return (f != null) ? f : completedVoid();
			}, moves).main().onCompletion((v, err) -> {
				reordering = false;
				if (err != null) Log.e(err, "Failed to reorder the list");
				reloadKeepSelection();
			});
		}

		/** Reloads the list; if items are selected, they stay selected. */
		void reloadKeepSelection() {
			MediaItemListView lv = getListView();
			if (!lv.isSelectionActive()) {
				reload();
				return;
			}
			Set<Item> sel = new HashSet<>();
			for (MediaItemWrapper w : getList()) if (w.isSelected()) sel.add(w.getItem());
			setParent(getParent(), false).main().onSuccess(v -> {
				for (MediaItemWrapper w : getList()) {
					if (sel.contains(w.getItem())) w.setSelected(true, true);
				}
				lv.notifySelectionChanged();
			});
		}

		@Override
		@SuppressLint("MissingSuperCall")
		public FutureSupplier<?> setParent(BrowsableItem parent, boolean userAction) {
			return setParent(parent, userAction, true);
		}

		public FutureSupplier<?> setParent(BrowsableItem parent, boolean userAction, boolean scroll) {
			BrowsableItem prev = super.getParent();
			boolean same = parent == prev;
			MediaItemListView list = scroll && same ? getListView() : null;
			int scrollPos = (list != null) ? list.getScrollPosition() : 0;
			FutureSupplier<?> set = super.setParent(parent, userAction);

			if (!isHidden() && !noScroll) {
				getMainActivity().fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);

				if (scroll && (parent != null)) {
					if (!same && set.isDone()) {
						scrollToPrev(prev);
					} else {
						scrollPosition = scrollPos;
						scrollToPosition();
						if (!same) set.onSuccess(v -> scrollToPrev(prev));
					}
				}
			}

			return set;
		}

		private void scrollToPrev(BrowsableItem prev) {
			int idx = indexOf(getList(), prev);
			if (idx != -1) scrollPosition = idx;
			else if (scrollPosition == -1) scrollPosition = 0;
			scrollToPosition();
		}

		@Override
		public void onClick(View v) {
			MediaItemView mi = (MediaItemView) v;

			if (getListView().isSelectionActive()) {
				MediaItemWrapper w = mi.getItemWrapper();
				if ((w != null) && w.isSelectionSupported()) {
					w.setSelected(!w.isSelected(), true);
					// The selection panel's count (and actions) follow every change.
					getListView().notifySelectionChanged();
				}
				return;
			}

			discardSelection();

			// Set to open the menu on a tap (Settings > Interface): Play is its first entry.
			if ((mi.getItem() instanceof PlayableItem) && tapOpensMenu()) {
				clicked = null;
				mi.showItemMenu();
				return;
			}

			if (mi.getItem() instanceof PlayableItem i) {
				if (!i.isVideo()) {
					MainActivityDelegate a = getActivityDelegate();
					MediaEngine eng = a.getMediaSessionCallback().getEngine();
					if ((eng != null) && (eng.getSource() == i) &&
							(eng.getCurrentSubtitles() != NO_SUBTITLES)) {
						a.showFragment(R.id.subtitles_fragment);
						clicked = null;
						playTapped(i, true);
						return;
					}
				}

				if (i instanceof StreamItem) {
					if (clicked == i) {
						clicked = null;
						openEpg((StreamItem) i);
					} else {
						clicked = i;
						App.get().getHandler().postDelayed(() -> {
							boolean same = clicked == i;
							clicked = null;
							if (same) playTapped(i, true);
						}, 300);
					}
				} else if (i instanceof ArchiveItem) {
					clicked = null;
					if (!((ArchiveItem) i).isExpired()) playTapped(i, true);
				} else {
					clicked = null;
					playTapped(i, true);
				}
			} else {
				clicked = null;
				super.onClick(v);
			}
		}

		public void openEpg(StreamItem i) {
			setParent(i, true, false).thenRun(() -> {
				long time = System.currentTimeMillis();
				List<MediaItemWrapper> l = getAdapter().getList();
				int pos = 0;

				for (MediaItemWrapper w : l) {
					if (w.getItem() instanceof EpgItem e) {
						if ((e.getStartTime() <= time) && (e.getEndTime() > time)) {
							scrollPosition = pos;
							getMainActivityDelegate().onSuccess(a -> a.post(() -> {
								if (getParent() != i) return;
								getListView().smoothScrollToPosition(scrollPosition);
								getListView().focusTo(e);
							}));
							break;
						}
					}
					pos++;
				}
			});
		}

		/**
		 * Plays {@code i} the way tapping it does. {@code allowMusic}: false for the context menu's
		 * "Play as video", which plays it normally even while music mode is on.
		 */
		public void playTapped(PlayableItem i, boolean allowMusic) {
			var a = getMainActivity();

			// The Music tab is what's playing: a Favorites/Playlist entry tapped now plays as music
			// too, from its list, and the Music tab comes up -- instead of dropping out of music mode
			// into the video player.
			if (allowMusic && playsAsMusicInMusicMode() && !(i instanceof StreamItem) &&
					MusicPlayer.isMusicModeActive(a)) {
				MusicPlayer.play(a, i, true);
				return;
			}

			if (i instanceof MediaLib.ExternallyPlayableItem ext) {
				// On the car's screen while Android Auto is connected -- one player, one session.
				a.playExternally(ext, i);
				return;
			}

			var cur = a.getCurrentPlayable();
			if (Objects.equals(cur, i) && (allowMusic || !MusicPlayer.isMusicModeActive(a))) return;
			a.getBody().playItem(i);
			getAdapter().getListView().refreshState();
			if (i.isVideo()) return;

			var eng = a.getMediaSessionCallback().getEngine();
			if ((eng != null) && (eng.getSource() == i) &&
					(eng.getCurrentSubtitles() != NO_SUBTITLES)) {
				a.showFragment(R.id.subtitles_fragment);
			}
		}

		@Override
		protected void setChildren(List<? extends Item> children) {
			super.setChildren(children);
			if (noScroll) return;
			// The view was recreated while away: back to where the user had scrolled to.
			if (!isHidden() && (savedListState != null)) {
				MediaItemListView lv = getListView();
				lv.post(() -> {
					if (!restoreListState()) scrollToPosition();
				});
				return;
			}
			BrowsableItem p = getParent();
			PlayableItem current = getMainActivityDelegate()
					.mapIfNotNull(MainActivityDelegate::getCurrentPlayable).peek();

			if ((current != null) && current.getParent().equals(p)) {
				scrollPosition = indexOf(getList(), current);
				if (!isHidden()) scrollToPosition();
			} else {
				p.getLastPlayedItem().main().onSuccess(last -> {
					scrollPosition = (last != null) ? indexOf(getList(), last) : 0;
					if (scrollPosition == -1) scrollPosition = 0;
					if (!isHidden()) scrollToPosition();
				});
			}
		}

	}

	private static int indexOf(List<MediaItemWrapper> list, Item item) {
		int size = list.size();
		for (int i = 0; i < size; i++) {
			if (item.equals(list.get(i).getItem())) return i;
		}
		return -1;
	}
}
