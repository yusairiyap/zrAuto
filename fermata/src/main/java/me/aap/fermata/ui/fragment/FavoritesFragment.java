package me.aap.fermata.ui.fragment;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Favorites;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.FavoritesPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.MediaItemMenuHandler;
import me.aap.fermata.ui.view.MediaItemWrapper;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;

import static java.util.Objects.requireNonNull;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.collection.CollectionUtils.filterMap;

/**
 * @author Andrey Pavlenko
 */
public class FavoritesFragment extends MediaLibFragment {

	@Override
	protected ListAdapter createAdapter(FermataServiceUiBinder b) {
		return new FavoritesAdapter(getMainActivity(), b.getLib().getFavorites());
	}

	@Override
	protected boolean playsAsMusicInMusicMode() {
		return true;
	}

	@Override
	public int getFragmentId() {
		return R.id.favorites_fragment;
	}

	@Override
	public CharSequence getFragmentTitle() {
		return getResources().getString(R.string.favorites);
	}

	@Override
	public void contributeToNavBarMenu(OverlayMenu.Builder builder) {
		super.contributeToNavBarMenu(builder);
		FavoritesAdapter a = getAdapter();

		if (a.getListView().isSelectionActive() && a.hasSelectable() && a.hasSelected()) {
			OverlayMenu.Builder b = builder.withSelectionHandler(this::navBarMenuItemSelected);
			b.addItem(R.id.favorites_remove, R.drawable.favorite_filled, R.string.favorites_remove);
			getMainActivity().addPlaylistMenu(b, completed(a.getSelectedItems()));
		}
	}

	/** Like a playlist's: Select, last in a favorite's long-press menu, starts selection mode with it. */
	@Override
	public void contributeToContextMenu(OverlayMenu.Builder builder, MediaItemMenuHandler handler) {
		super.contributeToContextMenu(builder, handler);
		if (!(handler.getItem() instanceof PlayableItem pi)) return;
		if (getAdapter().getListView().isSelectionActive()) return;
		builder.addItem(R.id.playlist_select_item, me.aap.utils.R.drawable.check_box, R.string.select)
				.setHandler(i -> {
					startSelection(pi);
					return true;
				});
	}

	/** Enters multi-select with {@code first} already selected. */
	private void startSelection(PlayableItem first) {
		ListAdapter a = getAdapter();
		a.getListView().select(true);
		for (MediaItemWrapper w : a.getList()) {
			if (w.getItem() == first) {
				w.setSelected(true, true);
				break;
			}
		}
		a.getListView().notifySelectionChanged();
	}

	protected boolean navBarMenuItemSelected(OverlayMenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.favorites_remove) {
			requireNonNull(getLib()).getFavorites().removeItems(filterMap(getAdapter().getList(),
					MediaItemWrapper::isSelected, (i, w, l) -> l.add((PlayableItem) w.getItem()),
					ArrayList::new));
			discardSelection();
			getAdapter().setParent(getAdapter().getParent());
			return true;
		}
		return super.navBarMenuItemSelected(item);
	}

	@Override
	protected boolean isSupportedItem(Item i) {
		return getFavorites().isFavoriteItemId(i.getId());
	}

	private Favorites getFavorites() {
		return getLib().getFavorites();
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		FavoritesAdapter a = getAdapter();
		if (!a.isCallbackCall() && prefs.contains(FavoritesPrefs.FAVORITES)) {
			// A bulk reorder reloads once itself when done; otherwise keep any selection alive.
			if (!a.reordering) a.reloadKeepSelection();
		} else {
			super.onPreferenceChanged(store, prefs);
		}
	}

	// ---- Selection panel: the same one playlists have ----

	@Nullable
	private SelectionPanel selectionPanel;

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		selectionPanel = new SelectionPanel(this, R.string.playlist_add, new SelectionPanel.Actions() {
			@Override
			public void moveSelected(boolean toTop) {
				getAdapter().moveSelected(toTop);
			}

			@Override
			public void playlistAction(View anchor) {
				getMainActivity().showAddToPlaylistDialog(getAdapter().getSelectedItems());
			}

			@Override
			public void removeSelected() {
				List<PlayableItem> sel = getAdapter().getSelectedItems();
				if (sel.isEmpty()) return;
				getFavorites().removeItems(sel);
				discardSelection();
				getAdapter().setParent(getAdapter().getParent());
			}
		});
		getListView().setSelectionListener(v -> updateSelectionPanel());
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) hideSelectionPanel();
		else updateSelectionPanel();
	}

	@Override
	public void onDestroyView() {
		hideSelectionPanel();
		selectionPanel = null;
		super.onDestroyView();
	}

	private void updateSelectionPanel() {
		ListAdapter a = getAdapter();
		if ((a == null) || (selectionPanel == null)) return;
		selectionPanel.update(a.getListView().isSelectionActive());
	}

	private void hideSelectionPanel() {
		if (selectionPanel != null) selectionPanel.hide(false);
	}

	private class FavoritesAdapter extends ListAdapter {

		FavoritesAdapter(MainActivityDelegate activity, BrowsableItem parent) {
			super(activity, parent);
		}

		@Override
		protected void onItemDismiss(int position) {
			getFavorites().removeItem(position);
			super.onItemDismiss(position);
		}

		@Override
		protected boolean onItemMove(int fromPosition, int toPosition) {
			getFavorites().moveItem(fromPosition, toPosition);
			return super.onItemMove(fromPosition, toPosition);
		}

		/**
		 * Reordered only in selection mode (the toolbar's Select), and only while shown unsorted: a
		 * long press in the normal view is always the item's menu, never a drag fighting it -- unless
		 * a tap opens the menu (Settings), which leaves the long press free for dragging.
		 */
		@Override
		public boolean isLongPressDragEnabled() {
			return super.isLongPressDragEnabled() && isCustomOrder() &&
					(isSelectionActive() || tapOpensMenu());
		}

		@Override
		public boolean isDragOnlyInSelection() {
			return true;
		}

		@Override
		protected boolean isReorderable() {
			return true;
		}

		@Override
		protected FutureSupplier<Void> moveInModel(int from, int to) {
			return getFavorites().moveItem(from, to);
		}
	}
}
