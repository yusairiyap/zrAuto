package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;

/**
 * Settings &gt; Search: a text box at the top of the page and the matches under it, live as the text is typed. The matching
 * words are lit in the accent and the list fades in on every change; a tap opens the page the setting is on.
 */
public class SearchSettingsFragment extends ReorderListFragment {
	private static final int MAX = 25;
	@Nullable
	private LinearLayout results;
	@Nullable
	private EditText input;
	private String last = "";
	@Nullable
	private List<SettingsFragment.Indexed> index;
	private final Runnable pendingSearch = () -> show(input == null ? "" : input.getText().toString());

	/** No Search chip on the search page itself. */
	@Override
	public me.aap.utils.ui.view.ToolBarView.Mediator getToolBarMediator() {
		return me.aap.utils.ui.view.ToolBarView.Mediator.BackTitle.instance;
	}

	@Override
	public int getFragmentId() {
		return R.id.search_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.settings_search);
	}

	@Override
	protected List<Row> loadRows() {
		return Collections.emptyList();
	}

	@Override
	protected void saveOrder(List<String> keys) {
	}

	@Override
	protected void setChecked(String key, boolean checked) {
	}

	@Override
	protected void showRowMenu(Row row) {
	}

	@Override
	protected boolean hasReset() {
		return false;
	}

	@Override
	protected void onReset() {
	}

	@Override
	protected int resetTitle() {
		return R.string.settings_search;
	}

	@Override
	protected int resetQuestion() {
		return R.string.settings_search_sub;
	}

	@Override
	protected int hintRes() {
		return R.string.settings_search_sub;
	}

	@Override
	protected void goBack() {
		delegate().showFragment(R.id.settings_fragment);
	}

	@Override
	protected View createExtraHeader() {
		LinearLayout box = new LinearLayout(palette);
		box.setOrientation(LinearLayout.VERTICAL);

		LinearLayout field = new LinearLayout(palette);
		field.setOrientation(LinearLayout.HORIZONTAL);
		field.setGravity(Gravity.CENTER_VERTICAL);
		field.setPadding(dp(16), dp(4), dp(8), dp(4));
		GradientDrawable fill = rounded(tileFill, 16);
		field.setBackground(fill);
		ImageView icon = new ImageView(palette);
		icon.setImageResource(R.drawable.search);
		icon.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
		field.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
		EditText et = new EditText(palette);
		et.setBackground(null);
		et.setSingleLine();
		et.setHint(R.string.settings_search_hint);
		et.setHintTextColor(textSecondary);
		et.setTextColor(textPrimary);
		et.setTextSize(16);
		et.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		et.setPadding(dp(12), dp(12), dp(8), dp(12));
		field.addView(et, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		TextView clear = text(R.string.settings_search_clear, 13, accent, true);
		clear.setPadding(dp(12), dp(10), dp(12), dp(10));
		clear.setVisibility(View.GONE);
		clear.setOnClickListener(v -> et.setText(""));
		field.addView(clear);
		LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		fp.topMargin = dp(6);
		box.addView(field, fp);
		input = et;

		results = new LinearLayout(palette);
		results.setOrientation(LinearLayout.VERTICAL);
		box.addView(results, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		et.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void onTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				clear.setVisibility((s.length() == 0) ? View.GONE : View.VISIBLE);
				// Typing fast searches once after a short pause, not on every letter.
				if (input != null) {
					input.removeCallbacks(pendingSearch);
					input.postDelayed(pendingSearch, 90);
				}
			}
		});
		focusInput();
		// Read the settings once while the keyboard comes up, so the first letters typed find the list ready.
		et.postDelayed(() -> {
			if ((input == et) && (index == null)) index = SettingsFragment.buildSearchIndex(delegate());
		}, 450);
		return box;
	}

	private void show(String text) {
		LinearLayout box = results;
		if (box == null) return;
		String t = text.trim();
		if (t.equals(last)) return;
		last = t;
		box.animate().cancel();
		box.removeAllViews();
		if (t.isEmpty()) {
			box.setAlpha(1f);
			return;
		}
		if (index == null) index = SettingsFragment.buildSearchIndex(delegate());
		List<SettingsFragment.Hit> hits = SettingsFragment.search(index, t.toLowerCase(), MAX);
		if (hits.isEmpty()) {
			TextView none = text(R.string.settings_search_none, 14, textSecondary, false);
			none.setPadding(dp(16), dp(16), dp(16), dp(16));
			box.addView(none);
		}
		for (SettingsFragment.Hit h : hits) box.addView(row(h, t));
		// Fades in again on every change instead of jumping.
		box.setAlpha(0.2f);
		box.animate().alpha(1f).setDuration(160).start();
		scrollToTop();
	}

	private View row(SettingsFragment.Hit h, String needle) {
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(12), dp(12), dp(12));
		row.setBackground(pressable(rounded(tileFill, 16)));
		row.setClickable(true);
		row.setFocusable(true);
		ImageView iv = new ImageView(palette);
		if (h.icon() != 0) iv.setImageResource(h.icon());
		iv.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
		row.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
		LinearLayout labels = new LinearLayout(palette);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.setPadding(dp(14), 0, dp(8), 0);
		TextView title = text(0, 15, textPrimary, true);
		title.setText(lit(h.title(), needle));
		labels.addView(title);
		if (h.sub() != null) {
			TextView sub = text(0, 12.5f, textSecondary, false);
			sub.setText(lit(h.sub(), needle));
			labels.addView(sub);
		}
		TextView path = text(0, 11.5f, accent, false);
		path.setText(h.path());
		labels.addView(path);
		row.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		row.setOnClickListener(v -> h.open().run());
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(10);
		row.setLayoutParams(lp);
		return row;
	}

	/** The text with every place the needle occurs lit in the accent. */
	private CharSequence lit(CharSequence s, String needle) {
		SpannableString sp = new SpannableString(s);
		String low = s.toString().toLowerCase();
		String n = needle.toLowerCase();
		for (int i = low.indexOf(n); i >= 0; i = low.indexOf(n, i + n.length())) {
			sp.setSpan(new ForegroundColorSpan(accent), i, i + n.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			sp.setSpan(new StyleSpan(Typeface.BOLD), i, i + n.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		return sp;
	}

	/**
	 * Puts the cursor in the box and brings the keyboard up, again a moment later and once more after that: the first ask
	 * can come before the window takes the focus or while the page is still sliding in.
	 */
	private void focusInput() {
		EditText et = input;
		if (et == null) return;
		for (long d : new long[]{50, 250, 600}) {
			et.postDelayed(() -> {
				if (input != et) return;
				et.requestFocus();
				// Focusing a field can scroll its row into view: the box is at the very top, so the page stays there.
				scrollToTop();
				var imm = (android.view.inputmethod.InputMethodManager) palette.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
				if (imm != null) imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
				androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.ViewCompat.getWindowInsetsController(et);
				if (c != null) c.show(androidx.core.view.WindowInsetsCompat.Type.ime());
			}, d);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		focusInput();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (!hidden) focusInput();
	}

	@Override
	public void setInput(Object input) {
		super.setInput(input);
		focusInput();
	}

	@Override
	public void onDestroyView() {
		SettingsFragment.clearSearchRoot();
		if (input != null) input.removeCallbacks(pendingSearch);
		index = null;
		results = null;
		input = null;
		last = "";
		super.onDestroyView();
	}
}
