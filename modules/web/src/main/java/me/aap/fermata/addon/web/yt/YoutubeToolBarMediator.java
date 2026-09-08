package me.aap.fermata.addon.web.yt;

import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.RIGHT;

import android.widget.EditText;

import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.WebToolBarMediator;
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
		addButton(tb, me.aap.fermata.R.drawable.playlist, v -> yt.showPlaylistsMenu(),
				me.aap.fermata.R.id.playlists, RIGHT);

		// Unlike the plain Browser tab, this field only ever shows the current video's title (see
		// refreshAddressBarTitle() below) -- it isn't a navigable address the user would type into,
		// so the inherited EditText's editable look (the focus/selection border a
		// TextInputEditText draws, which setBackgroundResource() above doesn't suppress -- it's
		// drawn for the focused state, not part of the resting background) and actual editability
		// don't apply here. Making it non-focusable removes both: nothing to focus, nothing to
		// show a focus border for, nothing to type into.
		EditText addr = tb.findViewById(R.id.browser_addr);
		if (addr != null) {
			addr.setFocusable(false);
			addr.setFocusableInTouchMode(false);
			addr.setClickable(false);
			addr.setLongClickable(false);
			addr.setCursorVisible(false);
		}

		// super.enable() just set the raw URL as the address text; replace it with the video title
		// (or "YouTube") as soon as it's available.
		YoutubeWebView wv = yt.getWebView();
		if (wv != null) wv.refreshAddressBarTitle();
	}
}
