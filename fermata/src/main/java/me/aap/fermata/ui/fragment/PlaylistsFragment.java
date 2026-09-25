package me.aap.fermata.ui.fragment;

import static me.aap.utils.async.Completed.completed;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import me.aap.fermata.media.pref.PlaylistPrefs;
import me.aap.fermata.media.pref.PlaylistsPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.MediaItemMenuHandler;
import me.aap.fermata.ui.view.MediaItemListView;
import me.aap.fermata.ui.view.MediaItemView;
import me.aap.fermata.ui.view.MediaItemViewHolder;
import me.aap.fermata.ui.view.MediaItemWrapper;
import me.aap.utils.async.Async;
import me.aap.utils.log.Log;
import me.aap.utils.ui.view.NavBarView;
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

	// ---- Selection panel ----

	@Nullable
	private View selectionPanel;

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		getListView().setSelectionListener(v -> updateSelectionPanel());
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
		super.onDestroyView();
	}

	/**
	 * The floating panel shown while items of a playlist are selected: count, Move to top, Move to
	 * end, Move to playlist, Remove, and close (ends the selection).
	 */
	private void updateSelectionPanel() {
		PlaylistsAdapter a = getAdapter();
		if ((a == null) || (getView() == null)) return;
		int n = a.getListView().isSelectionActive() && (a.getParent() instanceof Playlist) ?
				a.getSelectedItems().size() : 0;

		if ((n == 0) || isHidden()) {
			hideSelectionPanel(true);
			return;
		}

		View panel = selectionPanel;
		if (panel == null) panel = createSelectionPanel();
		if (panel == null) return;
		((TextView) panel.findViewById(R.id.selection_panel_count))
				.setText(getString(R.string.selection_count, n));
		positionSelectionPanel(panel);
	}

	@Nullable
	private View createSelectionPanel() {
		View root = requireView().getRootView();
		View c = root.findViewById(android.R.id.content);
		if (!(c instanceof FrameLayout content)) return null;

		View panel = LayoutInflater.from(requireContext())
				.inflate(R.layout.playlist_selection_panel, content, false);
		panel.findViewById(R.id.selection_panel_close).setOnClickListener(v -> discardSelection());
		panel.findViewById(R.id.selection_panel_top).setOnClickListener(v -> moveSelected(true));
		panel.findViewById(R.id.selection_panel_end).setOnClickListener(v -> moveSelected(false));
		panel.findViewById(R.id.selection_panel_move).setOnClickListener(v -> {
			PlaylistsAdapter a = getAdapter();
			if (a.getParent() instanceof Playlist pl) {
				MainActivityDelegate m = getMainActivity();
				m.showMoveToPlaylistDialog(m.getContextMenu(), pl, a.getSelectedItems());
			}
		});
		panel.findViewById(R.id.selection_panel_remove).setOnClickListener(v -> {
			PlaylistsAdapter a = getAdapter();
			if (a.getParent() instanceof Playlist pl) {
				getMainActivity().removeFromPlaylist(pl, a.getSelectedItems());
			}
		});

		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM);
		content.addView(panel, lp);
		selectionPanel = panel;

		// Slides up and fades in.
		panel.setAlpha(0f);
		panel.setTranslationY(UiUtils.toPx(requireContext(), 120));
		panel.animate().alpha(1f).translationY(0f).setDuration(220)
				.setInterpolator(new DecelerateInterpolator()).start();
		return panel;
	}

	/** Above the bottom nav bar and the control panel, whichever are showing. */
	private void positionSelectionPanel(View panel) {
		MainActivityDelegate a = getMainActivity();
		Context ctx = requireContext();
		int side = UiUtils.toIntPx(ctx, 12);
		int bottom = UiUtils.toIntPx(ctx, 12);
		NavBarView nb = a.getNavBar();
		if ((nb != null) && (nb.getVisibility() == View.VISIBLE) && nb.isBottom()) bottom += nb.getHeight();
		View cp = a.getControlPanel();
		if ((cp != null) && (cp.getVisibility() == View.VISIBLE)) bottom += cp.getHeight();

		// Leave the floating button(s) uncovered: stop short of their column, on whichever side
		// they are, instead of hiding them.
		int left = side;
		int right = side;
		if (panel.getParent() instanceof View content) {
			int[] cLoc = new int[2];
			content.getLocationOnScreen(cLoc);
			int width = content.getWidth();
			int fabLeft = Integer.MAX_VALUE;
			int fabRight = Integer.MIN_VALUE;

			for (View fab : new View[]{a.getFloatingButton(), a.getFloatingButton2(),
					a.getFloatingButton3()}) {
				if ((fab == null) || !fab.isShown() || (fab.getWidth() == 0)) continue;
				int[] loc = new int[2];
				fab.getLocationOnScreen(loc);
				fabLeft = Math.min(fabLeft, loc[0] - cLoc[0]);
				fabRight = Math.max(fabRight, loc[0] - cLoc[0] + fab.getWidth());
			}

			if ((fabLeft != Integer.MAX_VALUE) && (width > 0)) {
				if ((fabLeft + fabRight) / 2 > width / 2) right = Math.max(side, width - fabLeft + side);
				else left = Math.max(side, fabRight + side);
			}
		}

		FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();

		if ((lp.bottomMargin != bottom) || (lp.leftMargin != left) || (lp.rightMargin != right)) {
			lp.setMargins(left, 0, right, bottom);
			panel.setLayoutParams(lp);
		}
	}

	private void hideSelectionPanel(boolean animate) {
		View panel = selectionPanel;
		if (panel == null) return;
		selectionPanel = null;
		panel.animate().cancel();

		if (animate) {
			panel.animate().alpha(0f).translationY(UiUtils.toPx(panel.getContext(), 120))
					.setDuration(180).setInterpolator(new AccelerateInterpolator())
					.withEndAction(() -> removeFromParent(panel)).start();
		} else {
			removeFromParent(panel);
		}
	}

	private static void removeFromParent(View v) {
		if (v.getParent() instanceof ViewGroup g) g.removeView(v);
	}

	/** Moves the selected items, in their current order, to the top or the end of the playlist. */
	private void moveSelected(boolean toTop) {
		PlaylistsAdapter a = getAdapter();
		if (!(a.getParent() instanceof Playlist pl)) return;
		List<MediaItemWrapper> sel = new ArrayList<>();
		List<MediaItemWrapper> rest = new ArrayList<>();
		for (MediaItemWrapper w : a.getList()) (w.isSelected() ? sel : rest).add(w);
		if (sel.isEmpty()) return;
		List<MediaItemWrapper> order = new ArrayList<>(a.getList().size());
		if (toTop) {
			order.addAll(sel);
			order.addAll(rest);
		} else {
			order.addAll(rest);
			order.addAll(sel);
		}
		a.applyOrder(pl, order);
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

		/** A bulk reorder is running: its own reload follows, the per-move ones are skipped. */
		boolean reordering;
		/** The dragged item is one of several selected ones: they all follow it on drop. */
		private boolean multiDrag;

		@Override
		protected void onDragStarted(@NonNull RecyclerView.ViewHolder vh) {
			super.onDragStarted(vh);
			multiDrag = false;
			MediaItemWrapper dragged = (vh instanceof MediaItemViewHolder h) ? h.getItemWrapper() : null;
			if ((dragged == null) || !dragged.isSelected() || !getListView().isSelectionActive() ||
					!(getParent() instanceof Playlist) || (getSelectedItems().size() < 2)) {
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
			if ((dragged == null) || !(getParent() instanceof Playlist pl)) return;
			List<MediaItemWrapper> sel = new ArrayList<>();
			for (MediaItemWrapper w : getList()) if (w.isSelected()) sel.add(w);
			List<MediaItemWrapper> order = new ArrayList<>(getList().size());

			for (MediaItemWrapper w : getList()) {
				if (w == dragged) order.addAll(sel); // The whole selection, in its existing order.
				else if (!w.isSelected()) order.add(w);
			}

			applyOrder(pl, order);
		}

		/**
		 * Reorders the playlist to {@code order} (a permutation of the current list) with the
		 * fewest moves, then reloads once, keeping the selection.
		 */
		void applyOrder(Playlist pl, List<MediaItemWrapper> order) {
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
			Async.forEach(m -> pl.moveItem(m[0], m[1]), moves).main().onCompletion((v, err) -> {
				reordering = false;
				if (err != null) Log.e(err, "Failed to reorder the playlist");
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
			Set<MediaLib.Item> sel = new HashSet<>();
			for (MediaItemWrapper w : getList()) if (w.isSelected()) sel.add(w.getItem());
			setParent(getParent(), false).main().onSuccess(v -> {
				for (MediaItemWrapper w : getList()) {
					if (sel.contains(w.getItem())) w.setSelected(true, true);
				}
				lv.notifySelectionChanged();
			});
		}
	}
}
