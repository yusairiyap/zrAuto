package me.aap.fermata.ui.home.widget;

import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.spotify.YoutubeSearch;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.ChipFlowLayout;
import me.aap.utils.app.App;

/**
 * A search box for YouTube on Home. What is typed gets YouTube's own suggestions as chips, and the past searches (the same
 * list as the search panel's, see Settings > Up next) when the field is empty. Enter, the search button or a chip opens the
 * YouTube tab with the results in its search panel; the header opens the panel without a search. The chips wrap when the
 * card has room for two rows, else they scroll sideways in one row. A card one unit high is only the search input, and on
 * the car screen (no typing while driving) the input and its icon open the panel's search field. In the smallest size it is
 * only the search icon, which opens the panel.
 */
final class SearchWidget extends HomeWidgetView {
	private static final int SUGGESTIONS = 6;
	private static final long TYPE_DELAY_MS = 250;
	/** Room the chips need for two wrapped rows, below which they go in one scrolling row. */
	private static final int TWO_ROWS_DP = 80;
	/** Narrower than this, the card is only the search icon (the smallest size on a phone is wider). */
	private static final int ICON_BELOW_DP = 150;
	/** One unit high, the chips fit under the input from this width. */
	private static final int CHIPS_IN_STRIP_DP = 260;
	/** The keyboard takes a moment to come up and resize the window: the card is checked again as it does. */
	private static final long[] KEYBOARD_REVEAL_MS = {250, 600, 1000};
	/** The room between the chips and the input. */
	private static final int CHIPS_GAP_DP = 10;

	private LinearLayout column;
	private LinearLayout header;
	private LinearLayout body;
	private ScrollView wrap;
	private ChipFlowLayout wrapChips;
	private HorizontalScrollView row;
	private ChipFlowLayout rowChips;
	private TextView caption;
	private EditText field;
	private LinearLayout pill;
	private final GradientDrawable pillFill = new GradientDrawable();
	private ImageView bigIcon;

	/** Whether the chips are laid out in one scrolling row (true) or wrapped (false). */
	private boolean oneRow;
	/** Whether the chips are on the card at all (not in the smallest size or the one-unit-high size). */
	private boolean chipsShown;
	private List<String> shownQueries = new ArrayList<>();
	private boolean shownPredictions;
	private int generation;
	private final Runnable suggest = this::fetchSuggestions;

	SearchWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		header = header(this::openPanel);
		column.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

		body = new LinearLayout(ctx);
		body.setOrientation(LinearLayout.VERTICAL);

		pill = new LinearLayout(ctx);
		pill.setOrientation(LinearLayout.HORIZONTAL);
		pill.setGravity(Gravity.CENTER_VERTICAL);
		pill.setBackground(pillFill);
		pillFill.setCornerRadius(px(21));
		field = new EditText(ctx);
		field.setSingleLine(true);
		field.setBackground(null);
		field.setInputType(InputType.TYPE_CLASS_TEXT);
		field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		field.setHint(R.string.youtube_search_hint);
		field.setTextSize(14);
		field.setPadding(px(14), 0, px(6), 0);
		field.setOnEditorActionListener((v, action, e) -> {
			boolean enter = (e != null) && (e.getKeyCode() == KeyEvent.KEYCODE_ENTER) && (e.getAction() == KeyEvent.ACTION_DOWN);
			if ((action == EditorInfo.IME_ACTION_SEARCH) || (action == EditorInfo.IME_ACTION_DONE) || enter) {
				submit();
				return true;
			}
			return false;
		});
		// On the car screen the field cannot be typed in: a tap goes straight to the panel's search field.
		field.setOnClickListener(v -> {
			if (host.isCar()) openPanel();
		});
		// The keyboard comes up over the bottom of the screen: once it is, the card is scrolled above it.
		field.setOnFocusChangeListener((v, focused) -> {
			if (!focused) return;
			// The window shrinks as the keyboard comes up, a little after; the card is checked again as it does.
			for (long ms : KEYBOARD_REVEAL_MS) postDelayed(this::revealField, ms);
		});
		field.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int st, int c, int a) {
			}

			@Override
			public void onTextChanged(CharSequence s, int st, int b, int c) {
			}

			@Override
			public void afterTextChanged(Editable e) {
				onTyped();
			}
		});
		// Held or dragged in the field, not the card: a long press must not pick the widget up.
		noGrab(field);
		track(field, ROLE_PRIMARY);
		pill.addView(field, new LinearLayout.LayoutParams(0, px(42), 1f));
		ImageView go = iconButton(R.drawable.search, 36, ROLE_ACCENT, R.string.youtube_search_hint, this::submit);
		LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(px(36), px(36));
		glp.setMarginEnd(px(3));
		pill.addView(go, glp);
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(42));
		plp.topMargin = px(4);
		body.addView(pill, plp);

		caption = text(ROLE_SECONDARY, 12, true);
		caption.setText(R.string.home_search_recent);
		caption.setVisibility(GONE);
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		clp.topMargin = px(8);
		body.addView(caption, clp);

		// The chips wrap in a column, or run on one row that scrolls sideways; only one of the two is shown.
		wrapChips = new ChipFlowLayout(ctx);
		wrap = new ScrollView(ctx);
		wrap.setVerticalScrollBarEnabled(false);
		wrap.addView(wrapChips, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		wlp.topMargin = px(CHIPS_GAP_DP);
		body.addView(wrap, wlp);

		rowChips = new ChipFlowLayout(ctx);
		rowChips.setOneRow(true);
		row = new HorizontalScrollView(ctx);
		row.setHorizontalScrollBarEnabled(false);
		row.addView(rowChips, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		rlp.topMargin = px(CHIPS_GAP_DP);
		body.addView(row, rlp);
		column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));

		// The smallest size: the search icon alone, which opens the panel.
		bigIcon = iconButton(R.drawable.search, 44, ROLE_ACCENT, R.string.youtube_search_hint, this::openPanel);
		bigIcon.setVisibility(GONE);
		content.addView(bigIcon, new FrameLayout.LayoutParams(px(44), px(44), Gravity.CENTER));
		onStyleChanged();
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.sw("history", ctx.getString(R.string.home_opt_search_history), true));
		l.add(HomeOption.sw("suggest", ctx.getString(R.string.home_opt_search_suggest), true));
		return l;
	}

	@Override
	protected void onStyleChanged() {
		pillFill.setColor(softFill());
		if (field != null) field.setHintTextColor(color(ROLE_SECONDARY));
		if (pill != null) pill.invalidate();
	}

	@Override
	protected void onBind() {
		applyLayout();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column != null) applyLayout();
	}

	@Override
	protected void onSized(int w, int h) {
		if (column != null) applyLayout();
	}

	/** The card's width in dp, which is what the room for the input and the chips is judged by (it differs by screen density). */
	private int widthDp() {
		return Math.round(widthPx() / ctx.getResources().getDisplayMetrics().density);
	}

	/** The room the chips get below the header and the input, from the card's height in pixels. */
	private int chipRoom() {
		return heightPx() - px(34) - px(46) - px(24);
	}

	private void applyLayout() {
		// Before the card is measured its width is unknown: the full layout until it is.
		boolean measured = widthPx() > 0;
		boolean icon = measured && (widthDp() < ICON_BELOW_DP);
		// One unit high: the input alone, plus the chips in one row when the card is wide enough for them.
		boolean strip = getCellsH() == 1;
		column.setVisibility(icon ? GONE : VISIBLE);
		bigIcon.setVisibility(icon ? VISIBLE : GONE);
		header.setVisibility(strip ? GONE : VISIBLE);
		// Without typing (car screen), the field is only a button for the panel.
		boolean car = host.isCar();
		field.setFocusable(!car);
		field.setFocusableInTouchMode(!car);
		chipsShown = !icon && (!strip || (measured && (widthDp() >= CHIPS_IN_STRIP_DP)));
		oneRow = strip || (chipRoom() < px(TWO_ROWS_DP));
		wrap.setVisibility((chipsShown && !oneRow) ? VISIBLE : GONE);
		row.setVisibility((chipsShown && oneRow) ? VISIBLE : GONE);
		fillChips();
		if (chipsShown && shownQueries.isEmpty() && fieldText().isEmpty()) showHistory();
	}

	/**
	 * Typing into the field on a phone: the window is resized above the keyboard, so if the card reaches below its bottom edge,
	 * the scroller it sits in is moved up by that much (the whole layout moves, the card with it).
	 */
	private void revealField() {
		if (!field.hasFocus()) return;
		Rect visible = new Rect();
		getWindowVisibleDisplayFrame(visible);
		int[] loc = new int[2];
		getLocationOnScreen(loc);
		int over = loc[1] + getHeight() - visible.bottom + px(16);
		if (over <= 0) return;
		android.view.ViewParent p = getParent();
		while ((p != null) && !(p instanceof ScrollView)) p = p.getParent();
		if (p instanceof ScrollView sv) sv.smoothScrollBy(0, over);
	}

	@Override
	protected void onActive() {
		if (column != null && !isCompact() && (getCellsH() > 1) && fieldText().isEmpty()) showHistory();
	}

	private String fieldText() {
		return (field == null) ? "" : field.getText().toString().trim();
	}

	private void onTyped() {
		if (column == null) return;
		removeCallbacks(suggest);
		if (fieldText().isEmpty()) {
			generation++;
			showHistory();
		} else if (optBool("suggest", true)) {
			postDelayed(suggest, TYPE_DELAY_MS);
		}
	}

	private void fetchSuggestions() {
		String q = fieldText();
		if (q.isEmpty()) return;
		int gen = ++generation;
		App.get().getExecutor().submitTask(() -> YoutubeSearch.suggest(q, SUGGESTIONS)).main()
				.onCompletion((found, err) -> {
					if ((gen != generation) || (column == null)) return;
					if ((err != null) || (found == null) || found.isEmpty()) {
						showChips(new ArrayList<>(), false);
						return;
					}
					showChips(found, true);
				});
	}

	private void showHistory() {
		if (!optBool("history", true)) {
			showChips(new ArrayList<>(), false);
			return;
		}
		showChips(MusicPlayer.youtubeSearchHistory(), false);
	}

	private void showChips(List<String> queries, boolean predictions) {
		shownQueries = queries;
		shownPredictions = predictions;
		fillChips();
	}

	/** Builds the chips for what is shown into the layout that is on the card now (wrapped or one row). */
	private void fillChips() {
		ChipFlowLayout target = oneRow ? rowChips : wrapChips;
		forget(wrapChips);
		forget(rowChips);
		wrapChips.removeAllViews();
		rowChips.removeAllViews();
		boolean strip = getCellsH() == 1;
		caption.setVisibility((chipsShown && !strip && !shownPredictions && !shownQueries.isEmpty()) ? VISIBLE : GONE);
		for (String q : shownQueries) {
			// Predictions and past searches are the same chips (no glyph), so they space alike.
			TextView c = chip(0, q, () -> submitQuery(q));
			if (!shownPredictions) {
				c.setOnLongClickListener(v -> {
					MusicPlayer.removeYoutubeSearchHistory(q);
					showHistory();
					return true;
				});
			}
			target.addView(c);
		}
	}

	private void submit() {
		String q = fieldText();
		hideKeyboard();
		if (q.isEmpty()) openPanel();
		else submitQuery(q);
	}

	private void submitQuery(String q) {
		hideKeyboard();
		MusicPlayer.searchYoutube(host.activity(), q);
	}

	private void openPanel() {
		hideKeyboard();
		host.run(Action.YOUTUBE_SEARCH);
	}

	private void hideKeyboard() {
		if (field == null) return;
		InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
		if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
		field.clearFocus();
	}
}
