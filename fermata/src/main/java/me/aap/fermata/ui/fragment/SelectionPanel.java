package me.aap.fermata.ui.fragment;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.MediaItemWrapper;
import me.aap.utils.ui.UiUtils;

/**
 * The floating panel shown while items of a Playlist or Favorites are selected: count, Move to
 * top, Move to end, a playlist action, Remove, and close (ends the selection). Shared by
 * {@link PlaylistsFragment} and {@link FavoritesFragment}; what each button does is the host's.
 */
final class SelectionPanel {
	private final MediaLibFragment fragment;
	private final Actions actions;
	@StringRes
	private final int playlistLabel;
	@Nullable
	private View panel;

	interface Actions {
		/** Moves the selected items, in their current order, to the top or the end of the list. */
		void moveSelected(boolean toTop);

		/** The third button: Move to playlist (a playlist), Add to playlist (Favorites). */
		void playlistAction(View anchor);

		void removeSelected();

		/** Whether the third button (see {@link #playlistAction}) applies to what's shown. */
		default boolean hasPlaylistAction() {
			return true;
		}
	}

	SelectionPanel(MediaLibFragment fragment, @StringRes int playlistLabel, Actions actions) {
		this.fragment = fragment;
		this.playlistLabel = playlistLabel;
		this.actions = actions;
	}

	/**
	 * Shows (or updates) the panel while {@code active}, else hides it. Shown for the whole of
	 * selection mode, from the moment it starts, even with nothing selected yet (the actions are
	 * just disabled then).
	 */
	void update(boolean active) {
		MediaLibFragment.ListAdapter a = fragment.getAdapter();
		if ((a == null) || (fragment.getView() == null)) return;

		if (!active || fragment.isHidden()) {
			hide(true);
			return;
		}

		// The count is the model's; bring the visible checkboxes in line with it too, in case a
		// recycled row still shows an earlier state.
		int n = 0;
		for (MediaItemWrapper w : a.getList()) {
			if (w.isSelected()) n++;
			w.refreshViewCheckbox();
		}

		View p = (panel != null) ? panel : create();
		if (p == null) return;
		((TextView) p.findViewById(R.id.selection_panel_count))
				.setText(fragment.getString(R.string.selection_count, n));
		p.findViewById(R.id.selection_panel_move)
				.setVisibility(actions.hasPlaylistAction() ? View.VISIBLE : View.GONE);
		boolean enabled = n > 0;
		for (int id : new int[]{R.id.selection_panel_top, R.id.selection_panel_end,
				R.id.selection_panel_move, R.id.selection_panel_remove}) {
			View b = p.findViewById(id);
			b.setEnabled(enabled);
			b.setAlpha(enabled ? 1f : 0.4f);
		}
		position(p);
		// Again once laid out: the nav bar/control panel/FAB positions may only be known then.
		p.post(() -> {
			if (panel == p) position(p);
		});
	}

	@Nullable
	private View create() {
		FrameLayout content = findHost();
		if (content == null) return null;
		Context ctx = fragment.requireContext();

		View p = LayoutInflater.from(ctx).inflate(R.layout.playlist_selection_panel, content, false);
		((TextView) p.findViewById(R.id.selection_panel_move_label)).setText(playlistLabel);
		p.findViewById(R.id.selection_panel_close).setOnClickListener(v -> fragment.discardSelection());
		p.findViewById(R.id.selection_panel_top).setOnClickListener(v -> actions.moveSelected(true));
		p.findViewById(R.id.selection_panel_end).setOnClickListener(v -> actions.moveSelected(false));
		p.findViewById(R.id.selection_panel_move).setOnClickListener(actions::playlistAction);
		p.findViewById(R.id.selection_panel_remove).setOnClickListener(v -> actions.removeSelected());

		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM);
		content.addView(p, lp);
		panel = p;

		// Slides up and fades in.
		p.setAlpha(0f);
		p.setTranslationY(UiUtils.toPx(ctx, 120));
		p.animate().alpha(1f).translationY(0f).setDuration(220)
				.setInterpolator(new DecelerateInterpolator()).start();
		return p;
	}

	/**
	 * A full-window FrameLayout to float the panel in: the window's content frame, or failing
	 * that (the Android Auto car screen's window is set up differently) its root, or the highest
	 * FrameLayout above the list.
	 */
	@Nullable
	private FrameLayout findHost() {
		View view = fragment.requireView();
		View root = view.getRootView();
		View c = root.findViewById(android.R.id.content);
		if (c instanceof FrameLayout f) return f;
		if (root instanceof FrameLayout f) return f;
		FrameLayout host = null;
		for (ViewParent p = view.getParent(); p != null; p = p.getParent()) {
			if (p instanceof FrameLayout f) host = f;
		}
		return host;
	}

	/** Above the bottom nav bar and the control panel, whichever are showing. */
	private void position(View p) {
		MainActivityDelegate a = fragment.getMainActivity();
		Context ctx = fragment.getContext();
		if (ctx == null) return;
		int side = UiUtils.toIntPx(ctx, 12);
		int gap = UiUtils.toIntPx(ctx, 12);
		int bottom = gap;

		// Clear whatever sits over the bottom of the host (nav bar, control panel), measured on
		// screen, so it works whether they're inside the host or laid out next to it.
		if (p.getParent() instanceof View host) {
			int[] hLoc = new int[2];
			host.getLocationOnScreen(hLoc);
			int hostBottom = hLoc[1] + host.getHeight();
			for (View v : new View[]{a.getNavBar(), a.getControlPanel()}) {
				if ((v == null) || !v.isShown() || (v.getHeight() == 0)) continue;
				if ((v == a.getNavBar()) && !a.getNavBar().isBottom()) continue;
				int[] loc = new int[2];
				v.getLocationOnScreen(loc);
				// Only bars across the lower part of the host count (not, say, a side nav bar).
				if ((loc[1] < hostBottom) && (loc[1] > hLoc[1] + host.getHeight() / 2)) {
					bottom = Math.max(bottom, hostBottom - loc[1] + gap);
				}
			}
		}

		// Leave the floating button(s) uncovered: stop short of their column, on whichever side
		// they are, instead of hiding them.
		int left = side;
		int right = side;
		if (p.getParent() instanceof View content) {
			int[] cLoc = new int[2];
			content.getLocationOnScreen(cLoc);
			int width = content.getWidth();
			int fabLeft = Integer.MAX_VALUE;
			int fabRight = Integer.MIN_VALUE;

			java.util.List<View> fabs = new java.util.ArrayList<>();
			fabs.add(a.getFloatingButton());
			java.util.Collections.addAll(fabs, a.getExtraFloatingButtons());
			for (View fab : fabs) {
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

		FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p.getLayoutParams();

		if ((lp.bottomMargin != bottom) || (lp.leftMargin != left) || (lp.rightMargin != right)) {
			lp.setMargins(left, 0, right, bottom);
			p.setLayoutParams(lp);
		}
	}

	void hide(boolean animate) {
		View p = panel;
		if (p == null) return;
		panel = null;
		p.animate().cancel();

		if (animate) {
			p.animate().alpha(0f).translationY(UiUtils.toPx(p.getContext(), 120))
					.setDuration(180).setInterpolator(new AccelerateInterpolator())
					.withEndAction(() -> removeFromParent(p)).start();
		} else {
			removeFromParent(p);
		}
	}

	private static void removeFromParent(View v) {
		if (v.getParent() instanceof ViewGroup g) g.removeView(v);
	}
}
