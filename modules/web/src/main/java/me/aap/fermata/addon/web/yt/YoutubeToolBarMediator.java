package me.aap.fermata.addon.web.yt;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.RIGHT;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.annotation.SuppressLint;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.WebToolBarMediator;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ytdl.DownloadsAddon;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ImageButton;
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
	/** The field's text is being set here, not typed -- not something to predict searches for. */
	private boolean settingText;

	public static YoutubeToolBarMediator getInstance() {
		return instance;
	}

	/** It adds its own Home button, in another place. */
	@Override
	protected boolean hasHomeButton() {
		return false;
	}

	@Override
	protected boolean mergesWithTabs() {
		return false;
	}

	@SuppressLint("ClickableViewAccessibility") // the touch listener never consumes the event
	@Override
	public void enable(ToolBarView tb, ActivityFragment f) {
		super.enable(tb, f);
		YoutubeFragment yt = (YoutubeFragment) f;
		// Labels double as the entries of the toolbar's "more" menu on a narrow screen (see
		// ToolBarView#onMeasure()); the priorities decide which go there first.
		ImageButton home = addButton(tb, R.drawable.browser_home,
				v -> yt.loadUrl(YoutubeFragment.DEFAULT_URL), R.id.browser_home, RIGHT);
		home.setContentDescription(tb.getContext().getString(me.aap.fermata.R.string.youtube_home));
		ImageButton favBtn = addButton(tb, me.aap.fermata.R.drawable.favorite,
				v -> yt.toggleCurrentVideoFavorite(), me.aap.fermata.R.id.favorites, RIGHT);
		favBtn.setContentDescription(tb.getContext().getString(me.aap.fermata.R.string.favorites));
		favBtn.setToolBarPriority(1);
		// A tap now toggles the current video directly; long-press keeps the old menu around for
		// browsing to other favorited videos, since nothing else on this toolbar reaches that list.
		favBtn.setOnLongClickListener(v -> {
			yt.showFavoritesMenu();
			return true;
		});
		refreshFavoriteButton(tb, yt);
		ImageButton pl = addButton(tb, me.aap.fermata.R.drawable.playlist, v -> yt.showPlaylistsMenu(),
				me.aap.fermata.R.id.playlists, RIGHT);
		pl.setContentDescription(tb.getContext().getString(me.aap.fermata.R.string.playlists));
		// Opens/closes the search panel with the Up next queue, without having to type anything.
		// Never moves into the "more" menu: it's what closes the panel again.
		ImageButton upNext = addButton(tb, me.aap.fermata.R.drawable.up_next,
				v -> yt.toggleSearchPanel(), me.aap.fermata.R.id.youtube_up_next, RIGHT);
		upNext.setContentDescription(tb.getContext().getString(me.aap.fermata.R.string.youtube_up_next));
		upNext.setToolBarPriority(Integer.MAX_VALUE);
		// Downloads the video on screen (asks what as); a ticked icon once it's on the phone, and
		// then it opens the Downloads tab. Hidden by the Downloads addon's own setting.
		if (DownloadsAddon.isYoutubeToolbarButtonShown()) {
			ImageButton dl = addButton(tb, me.aap.fermata.R.drawable.download,
					v -> yt.downloadCurrentVideo(), me.aap.fermata.R.id.ytdl_toolbar_button, RIGHT);
			dl.setContentDescription(tb.getContext().getString(me.aap.fermata.R.string.ytdl_download));
			dl.setToolBarPriority(0);
			refreshDownloadButton(tb, yt);
		}
		// Rarely toggled: the first to make room.
		if (tb.findViewById(me.aap.fermata.R.id.private_mode) instanceof ImageButton pm) {
			pm.setToolBarPriority(-1);
		}
		// The browser's bookmarks don't mean much here -- Favorites/Playlists (above) are this tab's.
		View bookmarks = tb.findViewById(me.aap.fermata.R.id.bookmarks);
		if (bookmarks != null) bookmarks.setVisibility(GONE);

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
				// Android Auto's keyboard takes the focus off the field while it types into it: putting
				// the title back then left the title in the field, with the query typed in front of it.
				if (!focused && !isCarInputActive(addr)) endSearchInput(addr);
			});
			// On touch down, i.e. before the click that opens the keyboard (the car keyboard on
			// Android Auto starts from whatever text is in the field). Not on focus: focus alone
			// also arrives from just moving a rotary controller across the toolbar.
			addr.setOnTouchListener((v, e) -> {
				if (e.getActionMasked() == MotionEvent.ACTION_DOWN) beginSearchInput(yt, addr);
				return false;
			});
			// What's typed goes to the panel for YouTube's predictions. Once per field: enable() runs
			// again every time the tab comes back, on the same field.
			if (addr.getTag(R.id.browser_addr) == null) {
				TextWatcher w = new TextWatcher() {
					@Override
					public void beforeTextChanged(CharSequence s, int start, int count, int after) {
					}

					@Override
					public void onTextChanged(CharSequence s, int start, int before, int count) {
					}

					@Override
					public void afterTextChanged(Editable s) {
						if (!editing || settingText) return;
						if ((addr.getParent() instanceof ToolBarView bar) &&
								(bar.getActiveFragment() instanceof YoutubeFragment ytf)) {
							ytf.onSearchTyping(s.toString());
						}
					}
				};
				addr.setTag(R.id.browser_addr, w);
				addr.addTextChangedListener(w);
			}
		}

		// Right after the field: a search button that turns into an X while searching (the field
		// being typed into, or the panel open) -- see refreshClearButton(). The X clears the text and
		// the results and closes the panel; the search button starts a search.
		ImageButton clear = tb.findViewById(R.id.browser_addr_clear);
		if (clear != null) {
			clear.setToolBarPriority(Integer.MAX_VALUE);
			clear.setOnClickListener(v -> {
				if (!editing && !yt.isSearchPanelShown()) {
					yt.startSearch();
					return;
				}
				yt.clearSearch();
				if (addr != null) {
					if (editing) addr.setText("");
					InputMethodManager imm = addr.getContext().getSystemService(InputMethodManager.class);
					if (imm != null) imm.hideSoftInputFromWindow(addr.getWindowToken(), 0);
					addr.clearFocus();
					endSearchInput(addr);
				}
				yt.hideSearchPanel();
			});
		}
		refreshClearButton(tb, yt);

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
			refreshDownloadButton(tb, yt);
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
		setText(t, (q != null) ? q : "");
		t.selectAll();
		yt.onSearchTyping("");
		// Tapping the title is starting a search: the panel comes down with it (just the search part
		// when it's set to open on its own).
		yt.showSearchPanel(true);
		if (t.getParent() instanceof ToolBarView tb) refreshClearButton(tb, yt);
	}

	private void endSearchInput(EditText t) {
		if (!editing) return;
		editing = false;
		setText(t, title);
		if ((t.getParent() instanceof ToolBarView tb) &&
				(tb.getActiveFragment() instanceof YoutubeFragment yt)) {
			// Done typing (submitted, or walked away): the predictions give way to past searches.
			yt.onSearchTyping("");
			refreshClearButton(tb, yt);
		}
	}

	/** Sets the field's text without it counting as typing -- see the TextWatcher in enable(). */
	private void setText(EditText t, CharSequence text) {
		settingText = true;
		try {
			t.setText(text);
		} finally {
			settingText = false;
		}
	}

	/** Whether Android Auto's own keyboard is typing into a field right now. */
	private static boolean isCarInputActive(View v) {
		MainActivityDelegate a = MainActivityDelegate.get(v.getContext());
		return (a != null) && a.isCarActivity() && a.getAppActivity().isInputActive();
	}

	/** See the search/clear button in {@link #enable}. */
	void refreshClearButton(ToolBarView tb, YoutubeFragment yt) {
		if (!(tb.findViewById(R.id.browser_addr_clear) instanceof ImageButton b)) return;
		boolean searching = editing || yt.isSearchPanelShown();
		setSearchLayout(tb, searching);
		b.setVisibility(VISIBLE);
		b.setImageResource(searching ? R.drawable.clear : me.aap.fermata.R.drawable.search);
		b.setContentDescription(tb.getContext().getString(searching ?
				me.aap.fermata.R.string.youtube_clear_search : me.aap.fermata.R.string.search));
	}

	/** Hidden while searching or the Up next panel is open, see {@link #setSearchLayout}. */
	private static final int[] SEARCH_HIDDEN_IDS = {me.aap.fermata.R.id.private_mode,
			R.id.browser_home, me.aap.fermata.R.id.favorites, me.aap.fermata.R.id.playlists,
			me.aap.fermata.R.id.ytdl_toolbar_button};
	private boolean searchLayout;

	/**
	 * While searching (the field being typed into, or the search/Up next panel open), the page
	 * buttons step aside and the field stretches out to the X, which then sits right next to the
	 * Up next button -- animated, so the field visibly grows/shrinks rather than jumping.
	 */
	private void setSearchLayout(ToolBarView tb, boolean searching) {
		boolean changed = false;
		for (int id : SEARCH_HIDDEN_IDS) {
			View v = tb.findViewById(id);
			if (v == null) continue;
			int vis = searching ? GONE : VISIBLE;
			int cur = (v instanceof ImageButton ib) ? ib.getRequestedVisibility() : v.getVisibility();
			if (cur == vis) continue;
			if (!changed && tb.isLaidOut() && (searchLayout != searching)) {
				AutoTransition t = new AutoTransition();
				t.setDuration(220);
				t.setInterpolator(new DecelerateInterpolator());
				TransitionManager.beginDelayedTransition(tb, t);
			}
			changed = true;
			v.setVisibility(vis);
		}
		searchLayout = searching;
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
		// Already editing (e.g. the car keyboard left the field in edit mode): start over empty
		// rather than keeping whatever was typed or left there before.
		if (editing) {
			setText(t, "");
			yt.onSearchTyping("");
		} else {
			beginSearchInput(yt, t);
		}
		t.requestFocus();
		if (a.isCarActivity()) {
			t.performClick();
		} else {
			InputMethodManager imm = t.getContext().getSystemService(InputMethodManager.class);
			if (imm != null) imm.showSoftInput(t, InputMethodManager.SHOW_IMPLICIT);
		}
	}

	private void refreshDownloadButton(ToolBarView tb, YoutubeFragment yt) {
		ImageButton b = tb.findViewById(me.aap.fermata.R.id.ytdl_toolbar_button);
		if (b != null) {
			b.setImageResource(yt.isCurrentVideoDownloaded()
					? me.aap.fermata.R.drawable.download_done : me.aap.fermata.R.drawable.download);
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
