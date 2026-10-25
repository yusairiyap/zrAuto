package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.Glass;
import me.aap.fermata.ui.view.GlassStylePreview;

/**
 * Settings > Interface > UI style (ui/view/Glass): a live preview of the style now set on top, the
 * styles as small previews to pick from (the way Home's arrangements are picked), then the style's
 * values to tune and where the glass goes.
 */
public class GlassSettingsFragment extends ReorderListFragment {

	@Override
	public int getFragmentId() {
		return R.id.glass_settings_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.glass_settings);
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

	@Override
	protected void onReset() {
		try (var e = ps().editPreferenceStore()) {
			e.removePref(MainActivityPrefs.GLASS_STYLE);
			e.removePref(MainActivityPrefs.GLASS_BLUR);
			e.removePref(MainActivityPrefs.GLASS_TINT);
			e.removePref(MainActivityPrefs.GLASS_SATURATION);
			e.removePref(MainActivityPrefs.GLASS_GRAIN);
			e.removePref(MainActivityPrefs.GLASS_RIM);
			e.removePref(MainActivityPrefs.GLASS_BARS);
			e.removePref(MainActivityPrefs.GLASS_HOME);
			e.removePref(MainActivityPrefs.GLASS_MENUS);
			e.removePref(MainActivityPrefs.GLASS_QUALITY);
			e.removePref(MainActivityPrefs.GLASS_WEB);
		}
		for (Runnable r : syncers) r.run();
	}

	@Override
	protected int resetTitle() {
		return R.string.glass_reset;
	}

	@Override
	protected int resetQuestion() {
		return R.string.glass_reset_question;
	}

	@Override
	protected int hintRes() {
		return R.string.glass_hint;
	}

	@Override
	protected void goBack() {
		delegate().showFragment(R.id.settings_fragment);
	}

	// ---- The page ----

	@Override
	protected View createExtraHeader() {
		MainActivityPrefs p = ps();
		LinearLayout h = new LinearLayout(palette);
		h.setOrientation(LinearLayout.VERTICAL);

		LinearLayout big = new LinearLayout(palette);
		big.setOrientation(LinearLayout.VERTICAL);
		big.setPadding(dp(8), dp(12), dp(8), dp(12));
		big.setBackground(rounded(tileFill, 16));
		big.addView(new GlassStylePreview(palette, p, -1, true), new LinearLayout.LayoutParams(MATCH_PARENT, dp(260)));
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		blp.topMargin = dp(8);
		h.addView(big, blp);
		if (!Glass.realBlur()) {
			TextView note = text(R.string.glass_no_blur, 13, textSecondary, false);
			note.setPadding(dp(4), dp(10), dp(4), 0);
			h.addView(note);
		}

		section(h, R.string.glass_sec_style);
		addStyleCards(h);
		TextView tuned = text(R.string.glass_tuned, 12.5f, textSecondary, false);
		tuned.setPadding(dp(4), dp(6), dp(4), 0);
		h.addView(tuned);
		syncers.add(() -> tuned.setVisibility(
				(p.getIntPref(MainActivityPrefs.GLASS_STYLE) != Glass.NORMAL) && !Glass.isPresetValues(p) ?
						View.VISIBLE : View.GONE));

		java.util.function.BooleanSupplier isGlass = () -> p.getIntPref(MainActivityPrefs.GLASS_STYLE) != Glass.NORMAL;
		section(h, R.string.glass_sec_tune);
		addValueSliderRow(h, R.string.glass_blur, R.string.glass_blur_sub, 0, 48,
				() -> p.getIntPref(MainActivityPrefs.GLASS_BLUR), v -> p.applyIntPref(MainActivityPrefs.GLASS_BLUR, v),
				v -> (v == 0) ? getString(R.string.glass_blur_off) : getString(R.string.glass_dp, v), isGlass);
		addValueSliderRow(h, R.string.glass_tint, R.string.glass_tint_sub, 0, 100,
				() -> p.getIntPref(MainActivityPrefs.GLASS_TINT), v -> p.applyIntPref(MainActivityPrefs.GLASS_TINT, v),
				v -> getString(R.string.power_set_percent, v), isGlass);
		addValueSliderRow(h, R.string.glass_saturation, R.string.glass_saturation_sub, 50, 200,
				() -> p.getIntPref(MainActivityPrefs.GLASS_SATURATION),
				v -> p.applyIntPref(MainActivityPrefs.GLASS_SATURATION, v),
				v -> getString(R.string.power_set_percent, v), isGlass);
		addSwitchRow(h, R.string.glass_grain, R.string.glass_grain_sub,
				() -> p.getBooleanPref(MainActivityPrefs.GLASS_GRAIN),
				on -> p.applyBooleanPref(MainActivityPrefs.GLASS_GRAIN, on), isGlass);
		addSwitchRow(h, R.string.glass_rim, R.string.glass_rim_sub,
				() -> p.getBooleanPref(MainActivityPrefs.GLASS_RIM),
				on -> p.applyBooleanPref(MainActivityPrefs.GLASS_RIM, on), isGlass);
		addRimParts(h, () -> isGlass.getAsBoolean() && p.getBooleanPref(MainActivityPrefs.GLASS_RIM));
		if (Glass.realBlur()) {
			addChoiceRow(h, R.string.glass_quality, R.string.glass_quality_sub,
					new int[]{R.string.glass_quality_high, R.string.glass_quality_balanced, R.string.glass_quality_saver},
					() -> p.getIntPref(MainActivityPrefs.GLASS_QUALITY), i -> p.applyIntPref(MainActivityPrefs.GLASS_QUALITY, i),
					isGlass);
		}

		section(h, R.string.glass_sec_where);
		addSwitchRow(h, R.string.glass_on_bars, R.string.glass_on_bars_sub,
				() -> p.getBooleanPref(MainActivityPrefs.GLASS_BARS),
				on -> p.applyBooleanPref(MainActivityPrefs.GLASS_BARS, on), isGlass);
		addSwitchRow(h, R.string.glass_on_menus, R.string.glass_on_menus_sub,
				() -> p.getBooleanPref(MainActivityPrefs.GLASS_MENUS),
				on -> p.applyBooleanPref(MainActivityPrefs.GLASS_MENUS, on), isGlass);
		addSwitchRow(h, R.string.glass_on_home, R.string.glass_on_home_sub,
				() -> p.getBooleanPref(MainActivityPrefs.GLASS_HOME),
				on -> p.applyBooleanPref(MainActivityPrefs.GLASS_HOME, on), isGlass);
		addSwitchRow(h, R.string.glass_on_tiles, R.string.glass_on_tiles_sub,
				() -> p.getBooleanPref(MainActivityPrefs.GLASS_TILES),
				on -> p.applyBooleanPref(MainActivityPrefs.GLASS_TILES, on), isGlass);
		// The experimental switch last, on its own.
		if (Glass.realBlur()) {
			addSwitchRow(h, R.string.glass_web, R.string.glass_web_sub,
					() -> p.getBooleanPref(MainActivityPrefs.GLASS_WEB),
					on -> p.applyBooleanPref(MainActivityPrefs.GLASS_WEB, on), isGlass);
		}
		for (Runnable r : syncers) r.run();
		return h;
	}

	@SuppressWarnings("unchecked")
	private static final me.aap.utils.pref.PreferenceStore.Pref<me.aap.utils.function.BooleanSupplier>[] RIM_PREFS =
			new me.aap.utils.pref.PreferenceStore.Pref[]{MainActivityPrefs.GLASS_RIM_TOOLBAR,
					MainActivityPrefs.GLASS_RIM_NAV, MainActivityPrefs.GLASS_RIM_FAB, MainActivityPrefs.GLASS_RIM_MENUS,
					MainActivityPrefs.GLASS_RIM_HOME, MainActivityPrefs.GLASS_RIM_VIDEO, MainActivityPrefs.GLASS_RIM_TILES};
	private static final int[][] RIM_TEXTS = {
			{R.string.glass_rim_toolbar, R.string.glass_rim_toolbar_sub},
			{R.string.glass_rim_nav, R.string.glass_rim_nav_sub},
			{R.string.glass_rim_fab, R.string.glass_rim_fab_sub},
			{R.string.glass_rim_menus, R.string.glass_rim_menus_sub},
			{R.string.glass_rim_home, R.string.glass_rim_home_sub},
			{R.string.glass_rim_video, R.string.glass_rim_video_sub},
			{R.string.glass_rim_tiles, R.string.glass_rim_tiles_sub},
	};

	/**
	 * "Where the rim light shows": a row that opens and closes the switches for each part (closed at first),
	 * indented under the rim light switch. The switches are usable while the rim light itself is on.
	 */
	private void addRimParts(LinearLayout parent, java.util.function.BooleanSupplier usable) {
		MainActivityPrefs p = ps();
		LinearLayout head = new LinearLayout(palette);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		head.setPadding(dp(16), dp(12), dp(16), dp(12));
		head.setBackground(pressable(rounded(tileFill, 16)));
		head.setClickable(true);
		head.setFocusable(true);
		LinearLayout labels = new LinearLayout(palette);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.addView(text(R.string.glass_rim_where, 15, textPrimary, true));
		TextView count = text(0, 12.5f, textSecondary, false);
		labels.addView(count);
		head.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		android.widget.ImageView chevron = new android.widget.ImageView(palette);
		chevron.setImageResource(R.drawable.expand);
		chevron.setImageTintList(android.content.res.ColorStateList.valueOf(textSecondary));
		// The icon points up: turned down while closed.
		chevron.setRotation(180f);
		head.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
		LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		hlp.topMargin = dp(10);
		parent.addView(head, hlp);

		LinearLayout body = new LinearLayout(palette);
		body.setOrientation(LinearLayout.VERTICAL);
		body.setPadding(dp(16), 0, 0, 0);
		body.setVisibility(View.GONE);
		for (int i = 0; i < RIM_PREFS.length; i++) {
			var pref = RIM_PREFS[i];
			addSwitchRow(body, RIM_TEXTS[i][0], RIM_TEXTS[i][1], () -> p.getBooleanPref(pref),
					on -> p.applyBooleanPref(pref, on), usable);
		}
		parent.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		head.setOnClickListener(v -> {
			boolean open = body.getVisibility() != View.VISIBLE;
			if (getView() instanceof android.view.ViewGroup root) {
				android.transition.TransitionManager.beginDelayedTransition(root, new android.transition.AutoTransition()
						.setDuration(220));
			}
			body.setVisibility(open ? View.VISIBLE : View.GONE);
			chevron.animate().rotation(open ? 0f : 180f).setDuration(220).start();
		});
		syncers.add(() -> {
			int on = 0;
			for (var pref : RIM_PREFS) if (p.getBooleanPref(pref)) on++;
			count.setText(getString(R.string.glass_rim_where_count, on, RIM_PREFS.length));
			labels.setAlpha(usable.getAsBoolean() ? 1f : 0.45f);
		});
	}

	/** The four styles as small phones, the one set filled with the accent. */
	private void addStyleCards(LinearLayout parent) {
		MainActivityPrefs p = ps();
		int[] labels = {R.string.glass_style_normal, R.string.glass_style_frosted, R.string.glass_style_clear,
				R.string.glass_style_acrylic};
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		GradientDrawable[] fills = new GradientDrawable[labels.length];
		TextView[] names = new TextView[labels.length];
		for (int i = 0; i < labels.length; i++) {
			int style = i;
			LinearLayout cell = new LinearLayout(palette);
			cell.setOrientation(LinearLayout.VERTICAL);
			cell.setGravity(Gravity.CENTER_HORIZONTAL);
			cell.setPadding(dp(6), dp(8), dp(6), dp(10));
			fills[i] = rounded(tileFill, 16);
			cell.setBackground(pressable(fills[i]));
			cell.setClickable(true);
			cell.setFocusable(true);
			cell.setContentDescription(getString(labels[i]));
			cell.addView(new GlassStylePreview(palette, p, style, false),
					new LinearLayout.LayoutParams(MATCH_PARENT, dp(150)));
			TextView t = text(labels[i], 13, textPrimary, true);
			t.setGravity(Gravity.CENTER);
			t.setSingleLine(true);
			t.setPadding(0, dp(6), 0, 0);
			names[i] = t;
			cell.addView(t);
			cell.setOnClickListener(v -> {
				Glass.applyStyle(p, style);
				for (Runnable r : syncers) r.run();
			});
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins((i == 0) ? 0 : dp(3), 0, (i == labels.length - 1) ? 0 : dp(3), 0);
			row.addView(cell, lp);
		}
		syncers.add(() -> {
			int sel = p.getIntPref(MainActivityPrefs.GLASS_STYLE);
			for (int i = 0; i < fills.length; i++) {
				fills[i].setColor((i == sel) ? accent : tileFill);
				names[i].setTextColor((i == sel) ? onAccent : textPrimary);
			}
		});
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(2);
		parent.addView(row, lp);
	}
}
