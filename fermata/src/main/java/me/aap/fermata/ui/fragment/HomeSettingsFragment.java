package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.home.HomeBgPrefs;
import me.aap.fermata.ui.home.HomeLayoutPreview;
import me.aap.fermata.ui.home.HomeScreen;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;

/**
 * The Home settings, in the look of the Power settings: what is the same everywhere (opening on Home, how
 * the cards look, the background, the greeting's words) is listed first, outside any group; what is each
 * screen's own (its widgets, how they are laid out, the greeting and info overlay switches) sits under
 * two pills, one for the phone and one for Android Auto.
 */
public class HomeSettingsFragment extends ReorderListFragment {
	/** The inputs: which screen's part to show, and whether Back goes to Home (else to Settings). */
	public static final String PHONE = "phone";
	public static final String AA = "aa";
	public static final String PHONE_FROM_HOME = "phone@home";
	public static final String AA_FROM_HOME = "aa@home";
	private boolean aaPart;
	private boolean fromHome;
	@Nullable
	private LinearLayout phoneBox;
	@Nullable
	private LinearLayout aaBox;
	@Nullable
	private TextView phoneTab;
	@Nullable
	private TextView aaTab;

	@Override
	public int settingsEntryTitle() {
		return R.string.home;
	}

	@Override
	public int getFragmentId() {
		return R.id.home_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.home_settings);
	}

	@Override
	public void setInput(Object input) {
		String s = (input instanceof String str) ? str : PHONE;
		aaPart = s.startsWith(AA);
		fromHome = s.endsWith("@home");
		if (getView() != null) showPart();
	}

	private MainActivityPrefs ps() {
		return delegate().getPrefs();
	}

	// ---- No rows: the page is all options ----

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

	/** Back to how a fresh install has the settings; the widgets themselves have their own reset buttons. */
	@Override
	protected void onReset() {
		try (var e = ps().editPreferenceStore()) {
			e.removePref(MainActivityPrefs.HOME_OPEN_ON_START);
			e.removePref(MainActivityPrefs.HOME_AUTO_MUSIC);
			e.removePref(MainActivityPrefs.HOME_ANIMATE);
			for (boolean car : new boolean[]{false, true}) {
				e.removePref(HomeBgPrefs.source(car));
				e.removePref(HomeBgPrefs.dim(car));
				e.removePref(HomeBgPrefs.blur(car));
				e.removePref(HomeBgPrefs.zoom(car));
			}
			e.removePref(MainActivityPrefs.HOME_GREET_MORNING);
			e.removePref(MainActivityPrefs.HOME_GREET_AFTERNOON);
			e.removePref(MainActivityPrefs.HOME_GREET_EVENING);
			e.removePref(MainActivityPrefs.HOME_GREET_NIGHT);
			e.removePref(MainActivityPrefs.HOME_GREETING);
			e.removePref(MainActivityPrefs.HOME_ARRANGE);
			e.removePref(MainActivityPrefs.HOME_COLUMNS);
			e.removePref(MainActivityPrefs.HOME_GAP);
			e.removePref(MainActivityPrefs.HOME_SCALE);
			e.removePref(MainActivityPrefs.HOME_PADDING);
			e.removePref(MainActivityPrefs.HOME_TOP_OVERLAY);
			e.removePref(MainActivityPrefs.HOME_TOP_OVERLAY_HIDE);
			e.removePref(MainActivityPrefs.HOME_GREETING_AA);
			e.removePref(MainActivityPrefs.HOME_ARRANGE_AA);
			e.removePref(MainActivityPrefs.HOME_COLUMNS_AA);
			e.removePref(MainActivityPrefs.HOME_GAP_AA);
			e.removePref(MainActivityPrefs.HOME_SCALE_AA);
			e.removePref(MainActivityPrefs.HOME_PADDING_AA);
			e.removePref(MainActivityPrefs.HOME_TOP_OVERLAY_AA);
			e.removePref(MainActivityPrefs.HOME_TOP_OVERLAY_HIDE_AA);
		}
		for (Runnable r : syncers) r.run();
	}

	@Override
	protected int resetTitle() {
		return R.string.home_settings_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.home_settings_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.home_settings_hint;
	}

	@Override
	protected void goBack() {
		if (fromHome) delegate().showFragment(R.id.home_fragment);
		else delegate().showFragment(R.id.settings_fragment);
	}

	// ---- The page ----

	@Override
	protected View createExtraHeader() {
		LinearLayout h = new LinearLayout(palette);
		h.setOrientation(LinearLayout.VERTICAL);
		buildGeneral(h);

		section(h, R.string.home_sec_screens);
		LinearLayout tabs = new LinearLayout(palette);
		tabs.setOrientation(LinearLayout.HORIZONTAL);
		phoneTab = tab(R.string.home_settings_tab_phone, R.drawable.home_page, false);
		aaTab = tab(R.string.home_settings_tab_aa, R.drawable.car, true);
		LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		l1.setMarginEnd(dp(4));
		LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		l2.setMarginStart(dp(4));
		tabs.addView(phoneTab, l1);
		tabs.addView(aaTab, l2);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		tlp.topMargin = dp(2);
		h.addView(tabs, tlp);

		phoneBox = new LinearLayout(palette);
		phoneBox.setOrientation(LinearLayout.VERTICAL);
		buildScreen(phoneBox, false);
		aaBox = new LinearLayout(palette);
		aaBox.setOrientation(LinearLayout.VERTICAL);
		buildScreen(aaBox, true);
		h.addView(phoneBox, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		h.addView(aaBox, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		showPart();
		return h;
	}

	private TextView tab(@StringRes int label, @DrawableRes int icon, boolean aa) {
		TextView t = text(label, 14, textPrimary, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
		t.setCompoundDrawablePadding(dp(8));
		t.setPadding(dp(12), dp(13), dp(12), dp(13));
		GradientDrawable fill = rounded(tileFill, 16);
		t.setTag(R.id.home_settings_fragment, fill);
		t.setBackground(pressable(fill));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> {
			aaPart = aa;
			showPart();
		});
		return t;
	}

	private void showPart() {
		if ((phoneBox == null) || (aaBox == null) || (phoneTab == null) || (aaTab == null)) return;
		phoneBox.setVisibility(aaPart ? View.GONE : View.VISIBLE);
		aaBox.setVisibility(aaPart ? View.VISIBLE : View.GONE);
		light(phoneTab, !aaPart);
		light(aaTab, aaPart);
	}

	private void light(TextView t, boolean on) {
		GradientDrawable fill = (GradientDrawable) t.getTag(R.id.home_settings_fragment);
		fill.setColor(on ? accent : tileFill);
		t.setTextColor(on ? onAccent : textPrimary);
		t.setCompoundDrawableTintList(ColorStateList.valueOf(on ? onAccent : textSecondary));
	}

	@Override
	public void onDestroyView() {
		phoneBox = null;
		aaBox = null;
		phoneTab = null;
		aaTab = null;
		super.onDestroyView();
	}

	/** What holds on every screen: listed first, with no group around it. */
	private void buildGeneral(LinearLayout box) {
		MainActivityPrefs p = ps();
		section(box, R.string.home_sec_general);
		addSwitchRow(box, R.string.home_pref_open_on_start, R.string.home_pref_open_on_start_sub,
				() -> p.getBooleanPref(MainActivityPrefs.HOME_OPEN_ON_START),
				on -> p.applyBooleanPref(MainActivityPrefs.HOME_OPEN_ON_START, on), null);
		addSwitchRow(box, R.string.home_pref_auto_music, R.string.home_pref_auto_music_sub,
				() -> p.getBooleanPref(MainActivityPrefs.HOME_AUTO_MUSIC),
				on -> p.applyBooleanPref(MainActivityPrefs.HOME_AUTO_MUSIC, on), null);
		addSwitchRow(box, R.string.home_pref_animate, R.string.home_pref_animate_sub,
				() -> p.getBooleanPref(MainActivityPrefs.HOME_ANIMATE),
				on -> p.applyBooleanPref(MainActivityPrefs.HOME_ANIMATE, on), null);
		addValueSliderRow(box, R.string.home_pref_rotate, R.string.home_pref_rotate_sub, 0, 60,
				() -> p.getIntPref(MainActivityPrefs.HOME_STACK_ROTATE), v -> p.applyIntPref(MainActivityPrefs.HOME_STACK_ROTATE, v),
				v -> (v == 0) ? getString(R.string.home_rotate_off) : getString(R.string.home_rotate_secs, v), null);
		// Shows nothing picked while some widget has a look of its own; picking one sets every widget.
		addChoiceRow(box, R.string.home_pref_style, R.string.home_pref_style_sub,
				new int[]{R.string.home_style_solid, R.string.home_style_glass, R.string.home_style_colour},
				() -> HomeScreen.styleShown(p), i -> HomeScreen.setAllStyles(p, i));
		addValueSliderRow(box, R.string.home_pref_card_alpha, R.string.home_pref_card_alpha_sub, 0, 100,
				() -> p.getIntPref(MainActivityPrefs.HOME_CARD_ALPHA), v -> p.applyIntPref(MainActivityPrefs.HOME_CARD_ALPHA, v),
				v -> getString(R.string.power_set_percent, v), null);

		section(box, R.string.home_sec_greeting_words);
		addTextRow(box, R.string.home_pref_greet_morning, R.string.home_greet_morning, MainActivityPrefs.HOME_GREET_MORNING);
		addTextRow(box, R.string.home_pref_greet_afternoon, R.string.home_greet_afternoon, MainActivityPrefs.HOME_GREET_AFTERNOON);
		addTextRow(box, R.string.home_pref_greet_evening, R.string.home_greet_evening, MainActivityPrefs.HOME_GREET_EVENING);
		addTextRow(box, R.string.home_pref_greet_night, R.string.home_greet_night, MainActivityPrefs.HOME_GREET_NIGHT);
	}

	/** One screen's own settings: the greeting, how its widgets are laid out, its top overlay, its widgets. */
	private void buildScreen(LinearLayout box, boolean aa) {
		MainActivityPrefs p = ps();
		Pref<BooleanSupplier> greeting = aa ? MainActivityPrefs.HOME_GREETING_AA : MainActivityPrefs.HOME_GREETING;
		Pref<IntSupplier> arrange = aa ? MainActivityPrefs.HOME_ARRANGE_AA : MainActivityPrefs.HOME_ARRANGE;
		Pref<IntSupplier> columns = aa ? MainActivityPrefs.HOME_COLUMNS_AA : MainActivityPrefs.HOME_COLUMNS;
		Pref<IntSupplier> gap = aa ? MainActivityPrefs.HOME_GAP_AA : MainActivityPrefs.HOME_GAP;
		Pref<BooleanSupplier> overlay = aa ? MainActivityPrefs.HOME_TOP_OVERLAY_AA : MainActivityPrefs.HOME_TOP_OVERLAY;
		Pref<BooleanSupplier> overlayHide = aa ? MainActivityPrefs.HOME_TOP_OVERLAY_HIDE_AA :
				MainActivityPrefs.HOME_TOP_OVERLAY_HIDE;
		Pref<BooleanSupplier> editButton = aa ? MainActivityPrefs.HOME_EDIT_BUTTON_AA : MainActivityPrefs.HOME_EDIT_BUTTON;

		// What the screen looks like with these settings, drawn live: it follows every option below.
		section(box, R.string.home_sec_preview);
		HomeLayoutPreview live = new HomeLayoutPreview(palette, p, aa, -1);
		live.setBackground(rounded(tileFill, 16));
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(aa ? 200 : 250));
		plp.topMargin = dp(2);
		box.addView(live, plp);

		section(box, R.string.home_sec_layout);
		addArrangeCards(box, aa, arrange);
		// Only for Freeflow (the row is greyed out in the other layouts).
		Pref<BooleanSupplier> snap = aa ? MainActivityPrefs.HOME_SNAP_GRID_AA : MainActivityPrefs.HOME_SNAP_GRID;
		addSwitchRow(box, R.string.home_pref_snap, R.string.home_pref_snap_sub,
				() -> p.getBooleanPref(snap), on -> p.applyBooleanPref(snap, on),
				() -> p.getIntPref(arrange) == 3);
		addValueSliderRow(box, R.string.home_pref_columns, R.string.home_pref_columns_sub, 0, 8,
				() -> p.getIntPref(columns), v -> p.applyIntPref(columns, v),
				v -> (v == 0) ? getString(R.string.home_opt_auto) : String.valueOf(v), null);
		addValueSliderRow(box, R.string.home_pref_gap, R.string.home_pref_gap_sub, 4, 20,
				() -> p.getIntPref(gap), v -> p.applyIntPref(gap, v), String::valueOf, null);
		Pref<IntSupplier> scale = aa ? MainActivityPrefs.HOME_SCALE_AA : MainActivityPrefs.HOME_SCALE;
		Pref<IntSupplier> padding = aa ? MainActivityPrefs.HOME_PADDING_AA : MainActivityPrefs.HOME_PADDING;
		addValueSliderRow(box, R.string.home_pref_scale, R.string.home_pref_scale_sub, 40, 150,
				() -> p.getIntPref(scale), v -> p.applyIntPref(scale, v),
				v -> getString(R.string.power_set_percent, v), null);
		addValueSliderRow(box, R.string.home_pref_padding, R.string.home_pref_padding_sub, -1, 48,
				() -> p.getIntPref(padding), v -> p.applyIntPref(padding, v),
				v -> (v < 0) ? getString(R.string.home_opt_auto) : getString(R.string.home_pad_value, v), null);

		// The picture, dimming and blur are each screen's own (and each arrangement of it remembers its own, see HomeModel).
		section(box, R.string.home_sec_background);
		addChoiceRow(box, R.string.home_pref_bg_source, R.string.home_pref_bg_source_sub,
				new int[]{R.string.home_bg_source_picture, R.string.home_bg_source_cover},
				() -> p.getIntPref(HomeBgPrefs.source(aa)),
				i -> p.applyIntPref(HomeBgPrefs.source(aa), i));
		addActionRow(box, R.string.home_bg_choose, R.string.home_pref_bg_sub, R.drawable.home_image,
				() -> HomeScreen.pickBackground(delegate(), aa));
		addActionRow(box, R.string.home_bg_remove, R.string.home_bg_remove_sub, R.drawable.delete,
				() -> HomeScreen.clearBackground(delegate(), aa));
		addValueSliderRow(box, R.string.home_bg_dim, R.string.home_bg_dim_sub, 0, 80,
				() -> p.getIntPref(HomeBgPrefs.dim(aa)), v -> p.applyIntPref(HomeBgPrefs.dim(aa), v),
				v -> getString(R.string.power_set_percent, v), null);
		addValueSliderRow(box, R.string.home_bg_zoom, R.string.home_bg_zoom_sub, 100, 300,
				() -> p.getIntPref(HomeBgPrefs.zoom(aa)), v -> p.applyIntPref(HomeBgPrefs.zoom(aa), v),
				v -> getString(R.string.power_set_percent, v), null);
		addValueSliderRow(box, R.string.home_bg_blur, R.string.home_bg_blur_sub, 0, 100,
				() -> p.getIntPref(HomeBgPrefs.blur(aa)), v -> p.applyIntPref(HomeBgPrefs.blur(aa), v),
				v -> (v == 0) ? getString(R.string.home_bg_blur_off) : getString(R.string.power_set_percent, v), null);

		section(box, R.string.home_sec_header);
		addSwitchRow(box, R.string.home_pref_greeting, R.string.home_pref_greeting_sub,
				() -> p.getBooleanPref(greeting), on -> p.applyBooleanPref(greeting, on), null);
		addSwitchRow(box, R.string.home_pref_edit_button, R.string.home_pref_edit_button_sub,
				() -> p.getBooleanPref(editButton), on -> p.applyBooleanPref(editButton, on), null);
		addSwitchRow(box, R.string.home_pref_top_overlay, R.string.home_pref_top_overlay_sub,
				() -> p.getBooleanPref(overlay), on -> p.applyBooleanPref(overlay, on), null);
		addSwitchRow(box, R.string.home_pref_top_overlay_hide, R.string.home_pref_top_overlay_hide_sub,
				() -> p.getBooleanPref(overlayHide), on -> p.applyBooleanPref(overlayHide, on), null);

		section(box, R.string.home_sec_widgets);
		// Each screen's widgets are edited on that screen: its edit mode, with its own chips.
		if (aa == delegate().isCarActivity()) {
			addActionRow(box, R.string.home_pref_edit, R.string.home_pref_edit_sub, R.drawable.edit, () -> {
				HomeScreen.editOnNextShow();
				delegate().showFragment(R.id.home_fragment);
			});
		} else {
			TextView note = text(aa ? R.string.home_set_edit_on_car : R.string.home_set_edit_on_phone, 13, textSecondary, false);
			note.setPadding(dp(4), dp(8), dp(4), dp(2));
			box.addView(note);
		}
		if (aa) {
			addActionRow(box, R.string.home_pref_aa_copy, R.string.home_pref_aa_copy_sub, R.drawable.car,
					() -> UiUtils.showQuestion(requireContext(), R.string.home_pref_aa_copy, R.string.home_pref_aa_copy_question,
							R.drawable.car).onSuccess(v -> {
						String mine = p.getStringPref(MainActivityPrefs.HOME_LAYOUT);
						if (mine == null) p.removePref(MainActivityPrefs.HOME_LAYOUT_AA);
						else p.applyStringPref(MainActivityPrefs.HOME_LAYOUT_AA, mine);
						TopToast.show(R.drawable.car, R.string.home_pref_aa_copy_done);
					}));
			addActionRow(box, R.string.home_pref_aa_reset, R.string.home_pref_aa_reset_sub, R.drawable.refresh,
					() -> UiUtils.showQuestion(requireContext(), R.string.home_pref_aa_reset, R.string.home_pref_reset_question,
							R.drawable.car).onSuccess(v -> {
						p.removePref(MainActivityPrefs.HOME_LAYOUT_AA);
						TopToast.show(R.drawable.car, R.string.home_pref_aa_reset_done);
					}));
		} else {
			addActionRow(box, R.string.home_pref_reset, R.string.home_pref_reset_sub, R.drawable.refresh,
					() -> UiUtils.showQuestion(requireContext(), R.string.home_pref_reset, R.string.home_pref_reset_question,
							R.drawable.home_page).onSuccess(v -> {
						p.removePref(MainActivityPrefs.HOME_LAYOUT);
						TopToast.show(R.drawable.home_page, R.string.home_pref_reset_done);
					}));
		}
	}

	/**
	 * The ways the cards can be laid out, each drawn as a small picture of the screen laid out that way (the car's display
	 * for Android Auto): a tap on one picks it, the picked one is outlined in the accent.
	 */
	private void addArrangeCards(LinearLayout parent, boolean aa, Pref<IntSupplier> arrange) {
		MainActivityPrefs p = ps();
		TextView title = text(R.string.home_pref_arrange, 15, textPrimary, true);
		title.setPadding(dp(4), dp(12), 0, 0);
		parent.addView(title);
		TextView sub = text(R.string.home_pref_arrange_sub, 12.5f, textSecondary, false);
		sub.setPadding(dp(4), 0, 0, dp(8));
		parent.addView(sub);
		int[] labels = {R.string.home_arrange_auto, R.string.home_arrange_down, R.string.home_arrange_side, R.string.home_arrange_free};
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		GradientDrawable[] fills = new GradientDrawable[labels.length];
		for (int i = 0; i < labels.length; i++) {
			int mode = i;
			LinearLayout cell = new LinearLayout(palette);
			cell.setOrientation(LinearLayout.VERTICAL);
			cell.setGravity(Gravity.CENTER_HORIZONTAL);
			cell.setPadding(dp(6), dp(8), dp(6), dp(10));
			fills[i] = rounded(tileFill, 16);
			cell.setBackground(pressable(fills[i]));
			cell.setClickable(true);
			cell.setFocusable(true);
			cell.addView(new HomeLayoutPreview(palette, p, aa, mode),
					new LinearLayout.LayoutParams(MATCH_PARENT, dp(aa ? 84 : 150)));
			TextView t = text(labels[i], 13, textPrimary, true);
			t.setGravity(Gravity.CENTER);
			t.setSingleLine(true);
			t.setPadding(0, dp(6), 0, 0);
			cell.addView(t, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			cell.setOnClickListener(v -> {
				p.applyIntPref(arrange, mode);
				for (Runnable rr : syncers) rr.run();
			});
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins((i == 0) ? 0 : dp(3), 0, (i == labels.length - 1) ? 0 : dp(3), 0);
			row.addView(cell, lp);
		}
		syncers.add(() -> {
			int sel = p.getIntPref(arrange);
			for (int i = 0; i < fills.length; i++) {
				boolean on = i == sel;
				fills[i].setStroke(on ? dp(2) : 0, accent);
				fills[i].setColor(on ? androidx.core.graphics.ColorUtils.blendARGB(tileFill, accent, 0.14f) : tileFill);
			}
		});
		parent.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
	}

	/** A row showing a text setting (the default words in grey while it is empty); a tap asks for new ones. */
	private void addTextRow(LinearLayout parent, @StringRes int title, @StringRes int hint, Pref<Supplier<String>> pref) {
		MainActivityPrefs p = ps();
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(12), dp(16), dp(12));
		row.setBackground(pressable(rounded(tileFill, 16)));
		row.setClickable(true);
		row.setFocusable(true);
		row.addView(text(title, 15, textPrimary, true), new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		TextView value = text(0, 14, accent, true);
		value.setSingleLine(true);
		value.setEllipsize(android.text.TextUtils.TruncateAt.END);
		value.setMaxWidth(dp(190));
		row.addView(value);
		syncers.add(() -> {
			String mine = p.getStringPref(pref);
			boolean set = (mine != null) && !mine.trim().isEmpty();
			value.setText(set ? mine : getString(hint));
			value.setAlpha(set ? 1f : 0.55f);
		});
		row.setOnClickListener(v -> {
			String mine = p.getStringPref(pref);
			UiUtils.queryText(requireContext(), title, R.drawable.home_page, (mine != null) ? mine : "")
					.onSuccess(t -> {
						if (t == null) return;
						if (t.trim().isEmpty()) p.removePref(pref);
						else p.applyStringPref(pref, t.trim());
						for (Runnable r : syncers) r.run();
					});
		});
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(10);
		parent.addView(row, lp);
	}
}
