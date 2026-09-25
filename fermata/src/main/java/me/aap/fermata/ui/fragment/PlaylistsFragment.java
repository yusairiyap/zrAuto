package me.aap.fermata.ui.fragment;

import static me.aap.utils.async.Completed.completed;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.media.lib.MediaLib.Playlists;
import me.aap.fermata.media.pref.PlaylistPrefs;
import me.aap.fermata.media.pref.PlaylistsPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.MediaItemMenuHandler;
import me.aap.fermata.ui.view.MediaItemWrapper;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;

/**
 * @author Andrey Pavlenko
 */
public class PlaylistsFragment extends MediaLibFragment {

	@Override
	protected ListAdapter createAdapter(FermataServiceUiBinder b) {
		return new PlaylistsAdapter(getMainActivity(), b.getLib().getPlaylists());
	}

	@Override
	public int getFragmentId() {
		return R.id.playlists_fragment;
	}

	@Override
	public CharSequence getFragmentTitle() {
		return getResources().getString(R.string.playlists);
	}

	@Override
	public void navBarItemReselected(int itemId) {
		getAdapter().setParent(getLib().getPlaylists());
	}

	@Override
	public void contributeToNavBarMenu(OverlayMenu.Builder builder) {
		super.contributeToNavBarMenu(builder);
		PlaylistsAdapter a = getAdapter();

		OverlayMenu.Builder b = builder.withSelectionHandler(this::navBarMenuItemSelected);

		if (a.getListView().isSelectionActive() && a.hasSelected()) {
			if (a.getParent() instanceof Playlist) {
				addSelectionActions(b);
			} else {
				b.addItem(R.id.favorites_add, R.drawable.favorite, R.string.favorites_add);
			}
		}

		b.addItem(R.id.spotify_import, R.drawable.playlist_import, R.string.spotify_import);
	}

	/**
	 * Actions for the selected items of a playlist: favorites, add to / move to another playlist,
	 * remove. Shared by the nav bar menu and the long-press menu while selecting.
	 */
	private void addSelectionActions(OverlayMenu.Builder b) {
		PlaylistsAdapter a = getAdapter();
		if (!(a.getParent() instanceof Playlist pl)) return;
		// Own ids and handlers: the long-press menu also has the single-item versions of these.
		b.addItem(R.id.playlist_selected_favorites, R.drawable.favorite,
				R.string.playlist_selected_favorites).setHandler(i -> {
			getLib().getFavorites().addItems(a.getSelectedItems());
			discardSelection();
			MediaLibFragment f = getMainActivity().getMediaLibFragment(R.id.favorites_fragment);
			if (f != null) f.reload();
			return true;
		});
		getMainActivity().addPlaylistMenu(b, completed(a.getSelectedItems()));
		b.addItem(R.id.playlist_move, R.drawable.playlist_move, R.string.playlist_move_selected)
				.setHandler(i -> {
					getMainActivity().showMoveToPlaylistDialog(i.getMenu(), pl, a.getSelectedItems());
					return true;
				});
		b.addItem(R.id.playlist_selected_remove, R.drawable.playlist_remove,
				R.string.playlist_selected_remove).setHandler(i -> {
			getMainActivity().removeFromPlaylist(pl, a.getSelectedItems());
			return true;
		});
	}

	@Override
	public void contributeToContextMenu(OverlayMenu.Builder builder, MediaItemMenuHandler handler) {
		super.contributeToContextMenu(builder, handler);
		PlaylistsAdapter a = getAdapter();

		if ((handler.getItem() instanceof PlayableItem pi) && (a.getParent() instanceof Playlist)) {
			if (a.getListView().isSelectionActive() && a.hasSelected()) {
				// While selecting, the long-press menu offers the bulk actions too.
				addSelectionActions(builder);
			} else {
				builder.addItem(R.id.playlist_select_item, me.aap.utils.R.drawable.check_box,
						R.string.select).setHandler(i -> {
					startSelection(pi);
					return true;
				});
			}
		}

		// Long-pressing a playlist offers the import too, next to Rename/Remove.
		if (handler.getItem() instanceof Playlist) {
			builder.addItem(R.id.spotify_import, R.drawable.playlist_import, R.string.spotify_import)
					.setHandler(i -> {
						SpotifyImportFragment.open(getMainActivity());
						return true;
					});
		}
	}

	public boolean navBarMenuItemSelected(OverlayMenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.spotify_import) {
			SpotifyImportFragment.open(getMainActivity());
			return true;
		} else if (itemId == R.id.playlist_remove_item) {
			getMainActivity().removeFromPlaylist((Playlist) getAdapter().getParent(), getAdapter().getSelectedItems());
			return true;
		}
		return super.navBarMenuItemSelected(item);
	}

	/** Enters multi-select with {@code first} already selected. */
	private void startSelection(PlayableItem first) {
		PlaylistsAdapter a = getAdapter();
		a.getListView().select(true);
		for (MediaItemWrapper w : a.getList()) {
			if (w.getItem() == first) {
				w.setSelected(true, true);
				break;
			}
		}
		UiUtils.showToast(requireContext(), R.string.playlist_select_hint);
	}

	@Override
	protected boolean isSupportedItem(MediaLib.Item i) {
		return getPlaylists().isPlaylistsItemId(i.getId());
	}

	private Playlists getPlaylists() {
		return getLib().getPlaylists();
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		PlaylistsAdapter a = getAdapter();
		if (a.isCallbackCall() || (a.getParent() == null)) return;

		if (prefs.contains(PlaylistsPrefs.PLAYLIST_IDS) && (a.getParent() == getLib().getPlaylists())) {
			a.reload();
		} else if (prefs.contains(PlaylistPrefs.PLAYLIST_ITEMS)) {
			a.reload();
		} else {
			super.onPreferenceChanged(store, prefs);
		}
	}

	private class PlaylistsAdapter extends ListAdapter {

		PlaylistsAdapter(MainActivityDelegate activity, BrowsableItem parent) {
			super(activity, parent);
		}

		@Override
		protected void onItemDismiss(int position) {
			BrowsableItem p = getParent();
			if (p instanceof Playlist) ((Playlist) p).removeItem(position);
			else ((Playlists) p).removeItem(position);
			super.onItemDismiss(position);
		}

		@Override
		protected boolean onItemMove(int fromPosition, int toPosition) {
			BrowsableItem p = getParent();
			if (p instanceof Playlist) ((Playlist) p).moveItem(fromPosition, toPosition);
			else ((Playlists) p).moveItem(fromPosition, toPosition);
			return super.onItemMove(fromPosition, toPosition);
		}
	}
}
