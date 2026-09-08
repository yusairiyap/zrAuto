package me.aap.fermata.addon.web.yt;

import static android.view.View.GONE;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.RIGHT;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.widget.EditText;
import android.widget.ImageButton;

import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.WebToolBarMediator;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ToolBarView;

/**
 * Visible, car-friendly toolbar for the YouTube tab: reuses the standard web browser toolbar
 * (back/forward, address/search bar, bookmarks) and adds a "home" button, so it is easy to
 * target with the Android Auto DPAD cursor instead of being fully hidden.
 */
public class YoutubeToolBarMediator extends WebToolBarMediator {
	private static final YoutubeToolBarMediator instance = new YoutubeToolBarMediator();

	public static YoutubeToolBarMediator getInstance() {
		return instance;
	}

	@Override
	public void enable(ToolBarView tb, ActivityFragment f) {
		super.enable(tb, f);
		YoutubeFragment yt = (YoutubeFragment) f;
		addButton(tb, R.drawable.browser_home, v -> yt.loadUrl(YoutubeFragment.DEFAULT_URL),
				R.id.browser_home, RIGHT);
		addButton(tb, me.aap.fermata.R.drawable.favorite, v -> yt.showFavoritesMenu(),
				me.aap.fermata.R.id.favorites, RIGHT);
		refreshFavoriteButton(tb, yt);
		addButton(tb, me.aap.fermata.R.drawable.playlist, v -> yt.showPlaylistsMenu(),
				me.aap.fermata.R.id.playlists, RIGHT);

		// Unlike the plain Browser tab, this field only ever shows the current video's title (see
		// refreshAddressBarTitle() below) -- it isn't a navigable address the user would type into,
		// so the inherited EditText's editable look and actual editability don't apply here. Made
		// non-focusable so it can never gain focus (nothing to type into, no focus-state chrome),
		// and its background dropped entirely -- the outline was still showing with only the
		// non-focusable change: it's baked into the field's *resting* background
		// (createAddress()'s setBackgroundResource(tool_bar_edittext_bg)), not conditional on
		// focus, so removing focusability alone couldn't have suppressed it.
		EditText addr = tb.findViewById(R.id.browser_addr);
		if (addr != null) {
			addr.setFocusable(false);
			addr.setFocusableInTouchMode(false);
			addr.setClickable(false);
			addr.setLongClickable(false);
			addr.setCursorVisible(false);
			addr.setBackground(null);
		}

		// The "clear text" (X) button next to the address field only makes sense for an editable
		// address a user is about to type a new one into -- meaningless (and, since the field
		// above is no longer editable, inert) once it's just a read-only title.
		ImageButton clear = tb.findViewById(R.id.browser_addr_clear);
		if (clear != null) clear.setVisibility(GONE);

		// super.enable() just set the raw URL as the address text; replace it with the video title
		// (or "YouTube") as soon as it's available.
		YoutubeWebView wv = yt.getWebView();
		if (wv != null) wv.refreshAddressBarTitle();
	}

	@Override
	public void onActivityEvent(ToolBarView tb, ActivityDelegate a, long e) {
		super.onActivityEvent(tb, a, e);
		// Fired on every page navigation (FermataWebClient#onPageFinished()) and, via
		// YoutubeFragment#notifyFavoritesChanged(), right after the current video is added to or
		// removed from favorites through the menu this button opens -- either way, whether it
		// should show filled or outline can have changed.
		if ((e == FRAGMENT_CONTENT_CHANGED) && (a.getActiveFragment() instanceof YoutubeFragment yt)) {
			refreshFavoriteButton(tb, yt);
		}
	}

	private void refreshFavoriteButton(ToolBarView tb, YoutubeFragment yt) {
		ImageButton b = tb.findViewById(me.aap.fermata.R.id.favorites);
		if (b != null) {
			b.setImageResource(yt.isCurrentVideoFavorite()
					? me.aap.fermata.R.drawable.favorite_filled : me.aap.fermata.R.drawable.favorite);
		}
	}
}
