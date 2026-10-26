package me.aap.fermata.ui.home.widget;

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
 * YouTube tab with the results in its search panel; the header opens the panel without a search. In the smallest size it is
 * only the search icon, which opens the panel.
 */
final class SearchWidget extends HomeWidgetView {
	private static final int SUGGESTIONS = 6;
	private static final long TYPE_DELAY_MS = 250;

	private LinearLayout column;
	private LinearLayout body;
	private ScrollView scroll;
	private ChipFlowLayout chips;
	private TextView caption;
	private EditText field;
	private LinearLayout pill;
	private GradientDrawable pillFill = new GradientDrawable();
	private FrameLayout content;
	private ImageView bigIcon;
	private int generation;
	private final Runnable suggest = this::fetchSuggestions;

	SearchWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		this.content = content;
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		column.addView(header(this::openPanel), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

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

		chips = new ChipFlowLayout(ctx);
		scroll = new ScrollView(ctx);
		scroll.setVerticalScrollBarEnabled(false);
		scroll.addView(chips, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		body.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
		if (pillFill == null) return;
		pillFill.setColor(softFill());
		if (field != null) field.setHintTextColor(color(ROLE_SECONDARY));
		if (pill != null) pill.invalidate();
	}

	@Override
	protected void onBind() {
		layoutFor();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column != null) layoutFor();
	}

	private void layoutFor() {
		boolean strip = isCompact();
		column.setVisibility(strip ? GONE : VISIBLE);
		bigIcon.setVisibility(strip ? VISIBLE : GONE);
		if (!strip) showIdle();
	}

	@Override
	protected void onActive() {
		if (column != null && !isCompact()) showIdle();
	}

	/** Nothing typed: the past searches (when the option is on), else nothing. */
	private void showIdle() {
		if (fieldText().isEmpty()) showHistory();
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
		List<String> past = MusicPlayer.youtubeSearchHistory();
		showChips(past, false);
	}

	/** The chips for {@code queries}: predictions (search icons) or past searches (long press removes one). */
	private void showChips(List<String> queries, boolean predictions) {
		forget(chips);
		chips.removeAllViews();
		caption.setVisibility((!predictions && !queries.isEmpty()) ? VISIBLE : GONE);
		for (String q : queries) {
			TextView c = chip(predictions ? R.drawable.search : 0, q, () -> submitQuery(q));
			if (!predictions) {
				c.setOnLongClickListener(v -> {
					MusicPlayer.removeYoutubeSearchHistory(q);
					showHistory();
					return true;
				});
			}
			chips.addView(c);
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
