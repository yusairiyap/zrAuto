package me.aap.fermata.addon.web.yt;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
import static android.view.View.GONE;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.RIGHT;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.annotation.SuppressLint;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;

import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.WebToolBarMediator;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.UiUtils;
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
	/** What the field shows while it isn't being typed into -- the current video's title. */
	private String title = "";
	/** The field holds a search being typed, not the title -- see beginSearchInput(). */
	private boolean editing;

	public static YoutubeToolBarMediator getInstance() {
		return instance;
	}

	@SuppressLint("ClickableViewAccessibility") // the touch listener never consumes the event
	@Override
	public void enable(ToolBarView tb, ActivityFragment f) {
		super.enable(tb, f);
		YoutubeFragment yt = (YoutubeFragment) f;
		addButton(tb, R.drawable.browser_home, v -> yt.loadUrl(YoutubeFragment.DEFAULT_URL),
				R.id.browser_home, RIGHT);
		ImageButton favBtn = addButton(tb, me.aap.fermata.R.drawable.favorite,
				v -> yt.toggleCurrentVideoFavorite(), me.aap.fermata.R.id.favorites, RIGHT);
		// A tap now toggles the current video directly; long-press keeps the old menu around for
		// browsing to other favorited videos, since nothing else on this toolbar reaches that list.
		favBtn.setOnLongClickListener(v -> {
			yt.showFavoritesMenu();
			return true;
		});
		refreshFavoriteButton(tb, yt);
		addButton(tb, me.aap.fermata.R.drawable.playlist, v -> yt.showPlaylistsMenu(),
				me.aap.fermata.R.id.playlists, RIGHT);
		// Opens/closes the search panel with the Up next queue, without having to type anything.
		addButton(tb, me.aap.fermata.R.drawable.queue_music, v -> yt.toggleSearchPanel(),
				me.aap.fermata.R.id.youtube_up_next, RIGHT);

		// The field shows the current video's title (see setAddress() below) and doubles as the search
		// box: tapping/selecting it swaps the title for the last search (or the "Search YouTube"
		// hint), and submitting searches in YoutubeSearchPanel -- natively, over the page, so the
		// video keeps playing -- rather than navigating the page to YouTube's own results as the
		// plain Browser tab's address bar does. No outline at rest, so it still reads as a title.
		editing = false;
		EditText addr = tb.findViewById(R.id.browser_addr);
		if (addr != null) {
			addr.setBackground(null);
			addr.setHint(me.aap.fermata.R.string.youtube_search_hint);
			addr.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
			// Replaces the inherited listener, which would load the text as a URL/page search. Also
			// what Android Auto's own keyboard reaches on submit -- see CarEditText#onEditorAction().
			addr.setOnKeyListener((v, keyCode, event) -> onSearchKey(yt, addr, keyCode, event));
			addr.setOnFocusChangeListener((v, focused) -> {
				if (!focused) endSearchInput(addr);
			});
			// On touch down, i.e. before the click that opens the keyboard (the car keyboard on
			// Android Auto starts from whatever text is in the field). Not on focus: focus alone
			// also arrives from just moving a rotary controller across the toolbar.
			addr.setOnTouchListener((v, e) -> {
				if (e.getActionMasked() == MotionEvent.ACTION_DOWN) beginSearchInput(yt, addr);
				return false;
			});
		}

		// Nothing to clear: the field is emptied (or prefilled with the last search) when tapped.
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
		// removed from favorites (a direct tap on this button, or "Add"/"Remove" from the
		// long-press menu) -- either way, whether it should show filled or outline can have changed.
		if ((e == FRAGMENT_CONTENT_CHANGED) && (a.getActiveFragment() instanceof YoutubeFragment yt)) {
			refreshFavoriteButton(tb, yt);
		}
	}

	/** Keeps the title for when the field isn't being typed into, instead of clobbering a query. */
	@Override
	public void setAddress(ToolBarView tb, String addr) {
		title = (addr != null) ? addr : "";
		EditText et = tb.findViewById(R.id.browser_addr);
		if ((et != null) && !editing) et.setText(title);
	}

	/** Title out, last search (or the hint) in -- once per edit. */
	private void beginSearchInput(YoutubeFragment yt, EditText t) {
		if (editing) return;
		editing = true;
		String q = yt.getLastSearchQuery();
		t.setText((q != null) ? q : "");
		t.selectAll();
	}

	private void endSearchInput(EditText t) {
		if (!editing) return;
		editing = false;
		t.setText(title);
	}

	private boolean onSearchKey(YoutubeFragment yt, EditText t, int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

		switch (keyCode) {
			case KEYCODE_ENTER:
			case KEYCODE_NUMPAD_ENTER:
				submitSearch(yt, t);
				return true;
			case KEYCODE_DPAD_CENTER:
				// Left to the default click, which is what opens text input (the car keyboard on
				// Android Auto) -- submitting here would make the field impossible to type into with a
				// rotary controller once it holds a previous query.
				beginSearchInput(yt, t);
				return false;
			default:
				return UiUtils.dpadFocusHelper(t, keyCode, event);
		}
	}

	private void submitSearch(YoutubeFragment yt, EditText t) {
		String q = t.getText().toString().trim();
		if (q.isEmpty()) return;
		// A pasted link is still opened as before; anything else is a search. Deliberately not
		// Uri#getScheme(): a query like "Artist: Song" parses as having the scheme "Artist".
		if (q.startsWith("http://") || q.startsWith("https://")) yt.loadUrl(q);
		else yt.search(q);
		InputMethodManager imm = t.getContext().getSystemService(InputMethodManager.class);
		if (imm != null) imm.hideSoftInputFromWindow(t.getWindowToken(), 0);
		t.clearFocus();
		// clearFocus() alone doesn't always drop focus (in touch mode it can land right back on this,
		// the first focusable view), so put the title back explicitly.
		endSearchInput(t);
	}

	/**
	 * Puts the cursor in the search field -- opening the car keyboard on Android Auto (a click, see
	 * {@code MainCarActivity#createEditText}), the soft keyboard otherwise.
	 */
	void focusSearchField(MainActivityDelegate a) {
		EditText t = a.getToolBar().findViewById(R.id.browser_addr);
		if ((t == null) || !(a.getActiveFragment() instanceof YoutubeFragment yt)) return;
		beginSearchInput(yt, t);
		t.requestFocus();
		if (a.isCarActivity()) {
			t.performClick();
		} else {
			InputMethodManager imm = t.getContext().getSystemService(InputMethodManager.class);
			if (imm != null) imm.showSoftInput(t, InputMethodManager.SHOW_IMPLICIT);
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
