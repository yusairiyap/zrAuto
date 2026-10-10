package me.aap.fermata.ui.fragment;

import static me.aap.utils.async.Completed.completed;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.media.lib.MediaLib.Playlists;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.pref.PlaylistPrefs;
import me.aap.fermata.media.pref.PlaylistsPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.spotify.SpotifyPlaylistSync;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.MediaItemMenuHandler;
import me.aap.fermata.ytdl.YtDownloadMenu;
import me.aap.fermata.ui.view.MediaItemListView;
import me.aap.fermata.ui.view.MediaItemView;
import me.aap.fermata.ui.view.MediaItemViewHolder;
import me.aap.fermata.ui.view.MediaItemWrapper;
import me.aap.fermata.ui.view.ModalProgressPopup;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
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
	protected boolean playsAsMusicInMusicMode() {
		return true;
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

		if (a.getListView().isSelectionActive() && a.hasSelected() &&
				(a.getParent() instanceof Playlist)) {
			addSelectionActions(b);
		} else if (a.getParent() instanceof Playlist) {
			// An open playlist, nothing selected: all of it.
			List<PlayableItem> all = new ArrayList<>();
			for (MediaItemWrapper w : a.getList()) {
				if (w.getItem() instanceof PlayableItem pi) all.add(pi);
			}
			YtDownloadMenu.addTo(b, getMainActivity(), all);
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
		YtDownloadMenu.addTo(b, getMainActivity(), a.getSelectedItems());
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
		if (handler.getItem() instanceof Playlist pl) {
			builder.addItem(R.id.spotify_sync, R.drawable.refresh, R.string.spotify_sync)
					.setHandler(i -> {
						syncWithSpotify(pl);
						return true;
					});
			builder.addItem(R.id.spotify_import, R.drawable.playlist_import, R.string.spotify_import)
					.setHandler(i -> {
						SpotifyImportFragment.open(getMainActivity());
						return true;
					});
		}
	}

	/**
	 * Adds the songs of the Spotify playlist of the same name that aren't here yet, at the top --
	 * see {@link SpotifyPlaylistSync}. Quick when there's nothing new; otherwise a modal card
	 * shows what's going on, with Cancel, once it takes more than a moment.
	 */
	private void syncWithSpotify(Playlist pl) {
		MainActivityDelegate a = getMainActivity();
		Context ctx = requireContext();
		String name = pl.getName();
		ModalProgressPopup[] popup = {null};
		SpotifyPlaylistSync[] sync = {null};
		String[] last = {ctx.getString(R.string.spotify_sync_reading)};
		int[] prog = {0, 0};
		boolean[] finished = {false};

		Runnable showPopup = () -> {
			if (finished[0] || (popup[0] != null)) return;
			popup[0] = ModalProgressPopup.show(a, R.drawable.playlist_import,
					ctx.getString(R.string.spotify_sync_title, name), () -> {
						if (sync[0] != null) sync[0].cancel();
					});
			if (popup[0] != null) popup[0].setProgress(last[0], prog[0], prog[1]);
		};

		sync[0] = SpotifyPlaylistSync.start(getLib(), pl, new SpotifyPlaylistSync.Callback() {
			@Override
			public void onProgress(String text, int done, int total) {
				last[0] = text;
				prog[0] = done;
				prog[1] = total;
				if (popup[0] != null) popup[0].setProgress(text, done, total);
			}

			@Override
			public void onFinished(SpotifyPlaylistSync.Result r) {
				finished[0] = true;
				a.getHandler().removeCallbacks(showPopup);
				String msg = r.getMessage(ctx, name);
				boolean ok = (r.status == SpotifyPlaylistSync.Result.DONE) ||
						(r.status == SpotifyPlaylistSync.Result.UP_TO_DATE);
				if (popup[0] != null) popup[0].showResult(msg, ok);
				else UiUtils.showToast(ctx, msg);
				if (r.added > 0) {
					MediaLibFragment f = a.getMediaLibFragment(R.id.playlists_fragment);
					if (f != null) f.reload();
				}
			}
		});
		a.getHandler().postDelayed(showPopup, 500);
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
		a.getListView().notifySelectionChanged();
	}

	// ---- Selection panel ----

	@Nullable
	private SelectionPanel selectionPanel;

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		selectionPanel = new SelectionPanel(this, R.string.playlist_move, new SelectionPanel.Actions() {
			@Override
			public void moveSelected(boolean toTop) {
				PlaylistsFragment.this.moveSelected(toTop);
			}

			@Override
			public void playlistAction(View anchor) {
				PlaylistsAdapter a = getAdapter();
				if (a.getParent() instanceof Playlist pl) {
					MainActivityDelegate m = getMainActivity();
					m.showMoveToPlaylistDialog(m.getContextMenu(), pl, a.getSelectedItems());
				}
			}

			@Override
			public void removeSelected() {
				PlaylistsAdapter a = getAdapter();
				if (a.getParent() instanceof Playlist pl) {
					getMainActivity().removeFromPlaylist(pl, a.getSelectedItems());
				} else if (a.getParent() instanceof Playlists pls) {
					removeSelectedPlaylists(pls);
				}
			}

			@Override
			public boolean hasPlaylistAction() {
				return getAdapter().getParent() instanceof Playlist;
			}
		});
		getListView().setSelectionListener(v -> updateSelectionPanel());
	}

	/** Opening this tab goes to what's playing in it, highlighted. */
	@Override
	protected boolean revealsPlaying() {
		return true;
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) hideSelectionPanel(false);
		else updateSelectionPanel();
	}

	@Override
	public void onDestroyView() {
		hideSelectionPanel(false);
		selectionPanel = null;
		super.onDestroyView();
	}

	private void updateSelectionPanel() {
		PlaylistsAdapter a = getAdapter();
		if ((a == null) || (selectionPanel == null)) return;
		BrowsableItem p = a.getParent();
		selectionPanel.update(a.getListView().isSelectionActive() &&
				((p instanceof Playlist) || (p instanceof Playlists)));
	}

	/** The list of playlists: removes the selected ones, once the user confirms. */
	private void removeSelectedPlaylists(Playlists pls) {
		List<Playlist> sel = new ArrayList<>();
		for (MediaItemWrapper w : getAdapter().getList()) {
			if (w.isSelected() && (w.getItem() instanceof Playlist pl)) sel.add(pl);
		}
		if (sel.isEmpty()) return;
		UiUtils.showQuestion(requireContext(), getString(R.string.playlist_remove),
				getResources().getQuantityString(R.plurals.playlists_remove_confirm, sel.size(),
						sel.size()), null).onSuccess(v -> {
			pls.removeItems(sel);
			discardSelection();
		});
	}

	private void hideSelectionPanel(boolean animate) {
		if (selectionPanel != null) selectionPanel.hide(animate);
	}

	/** Moves the selected items, in their current order, to the top or the end of the list. */
	private void moveSelected(boolean toTop) {
		getAdapter().moveSelected(toTop);
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
			// A bulk reorder reloads once itself when done; otherwise keep any selection alive.
			if (!a.reordering) a.reloadKeepSelection();
		} else if (prefs.contains(PlaylistPrefs.PLAYLIST_ITEMS)) {
			// A bulk reorder reloads once itself when done; otherwise keep any selection alive.
			if (!a.reordering) a.reloadKeepSelection();
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

		/**
		 * Dragging edits the playlist's (or the list of playlists') own order, so only while it's shown
		 * unsorted -- and only in selection mode (the toolbar's Select), so that a long press in the
		 * normal view is always the item's menu, never a drag fighting it (on the car screen
		 * especially).
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
			BrowsableItem p = getParent();
			return (p instanceof Playlist) || (p instanceof Playlists);
		}

		@Nullable
		@Override
		protected FutureSupplier<Void> moveInModel(int from, int to) {
			BrowsableItem p = getParent();
			if (p instanceof Playlist pl) return pl.moveItem(from, to);
			if (p instanceof Playlists pls) return pls.moveItem(from, to);
			return null;
		}
	}
}
