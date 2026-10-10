package me.aap.fermata.ui.fragment;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;

import me.aap.fermata.R;
import me.aap.fermata.action.Key;
import me.aap.fermata.ui.view.EffectsUi;

/**
 * Settings &gt; Key bindings &gt; Car mode: the switch, and what the keys do with it on, shown rather
 * than described (a little demo of the outline moving along, and a card per key), so it can be
 * picked up at a glance. See {@link me.aap.fermata.action.CarNav}.
 */
public class CarModeFragment extends MainActivityFragment {
	private static final int DEMO_TILES = 4;
	private static final long DEMO_STEP_MS = 900;
	private Context palette;
	private int textPrimary;
	private int textSecondary;
	private int tileFill;
	private int accent;
	private int onAccent;
	private float density;
	private final GradientDrawable[] demo = new GradientDrawable[DEMO_TILES];
	private final View[] demoViews = new View[DEMO_TILES];
	@Nullable
	private TextView demoLabel;
	private int demoAt;
	private int demoTick;
	@Nullable
	private ValueAnimator demoAnim;
	private final Runnable demoStep = this::demoStep;

	@Override
	public int getFragmentId() {
		return R.id.car_mode_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getResources().getString(R.string.key_car_mode);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		palette = new ContextThemeWrapper(ctx, MusicPlayerFragment.isLightTheme(ctx) ?
				R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
		density = ctx.getResources().getDisplayMetrics().density;
		textPrimary = paletteColor(R.attr.musicTextPrimary);
		textSecondary = paletteColor(R.attr.musicTextSecondary);
		tileFill = paletteColor(R.attr.musicChipFill);
		accent = EffectsUi.accent(ctx);
		onAccent = EffectsUi.onAccent(accent);

		ScrollView root = new ScrollView(palette);
		root.setFillViewport(true);
		root.setClipToPadding(false);
		LinearLayout content = new LinearLayout(palette);
		content.setOrientation(LinearLayout.VERTICAL);
		int pad = dp(16);
		content.setPadding(pad, dp(8), pad, pad);
		root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		content.addView(createHero());
		content.addView(createSwitch(), margins(0, dp(12), 0, 0));

		section(content, R.string.car_mode_keys);
		content.addView(keyCard("▶", R.string.car_mode_next, false));
		content.addView(keyCard("◀", R.string.car_mode_prev, false));
		content.addView(keyCard("▶", R.string.car_mode_hold_next, true));
		content.addView(keyCard("◀", R.string.car_mode_hold_prev, true));
		content.addView(keyCard("▶", R.string.car_mode_sliders, true));
		content.addView(keyCard("◀ ▶", R.string.car_mode_youtube_fullscreen, true));

		section(content, R.string.car_mode_where);
		for (int res : new int[]{R.string.car_mode_where_lists, R.string.car_mode_where_youtube,
				R.string.car_mode_where_queue, R.string.car_mode_where_pickers,
				R.string.car_mode_where_settings}) {
			content.addView(bullet(res));
		}

		TextView foot = text(R.string.car_mode_footnote, 13, textSecondary, false);
		foot.setPadding(dp(4), dp(16), dp(4), 0);
		content.addView(foot);
		return root;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		getActivityDelegate().insetScrollableContent((ViewGroup) view);
	}

	@Override
	public void onResume() {
		super.onResume();
		startDemo();
	}

	@Override
	public void onPause() {
		super.onPause();
		stopDemo();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) stopDemo();
		else startDemo();
	}

	@Override
	public void onDestroyView() {
		stopDemo();
		demoLabel = null;
		super.onDestroyView();
	}

	@Override
	public boolean onBackPressed() {
		getActivityDelegate().showFragment(R.id.settings_fragment, SettingsFragment.SHOW_KEY_BINDINGS);
		return true;
	}

	// ---- Hero, with the demo ----

	private View createHero() {
		LinearLayout card = new LinearLayout(palette);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setGravity(Gravity.CENTER_HORIZONTAL);
		int p = dp(20);
		card.setPadding(p, p, p, p);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(ColorUtils.setAlphaComponent(accent, 0x1F));
		bg.setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 0x66));
		bg.setCornerRadius(dp(24));
		card.setBackground(bg);

		TextView title = text(R.string.car_mode_title, 22, textPrimary, true);
		title.setGravity(Gravity.CENTER);
		card.addView(title);
		TextView sub = text(R.string.car_mode_tagline, 14, textSecondary, false);
		sub.setGravity(Gravity.CENTER);
		sub.setPadding(0, dp(4), 0, dp(16));
		card.addView(sub);

		// Four tiles with the outline moving along, then a "hold" flash on the last: what it looks like.
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER);
		for (int i = 0; i < DEMO_TILES; i++) {
			View t = new View(palette);
			GradientDrawable g = new GradientDrawable();
			g.setCornerRadius(dp(12));
			g.setColor(tileFill);
			t.setBackground(g);
			demo[i] = g;
			demoViews[i] = t;
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
			lp.setMargins(dp(5), 0, dp(5), 0);
			row.addView(t, lp);
		}
		card.addView(row);

		demoLabel = text(0, 13, accent, true);
		demoLabel.setGravity(Gravity.CENTER);
		demoLabel.setPadding(0, dp(12), 0, 0);
		card.addView(demoLabel);
		return card;
	}

	private void startDemo() {
		if ((demoLabel == null) || isHidden()) return;
		stopDemo();
		demoAt = -1;
		demoTick = 0;
		demoLabel.post(demoStep);
	}

	private void stopDemo() {
		if (demoLabel != null) demoLabel.removeCallbacks(demoStep);
		if (demoAnim != null) demoAnim.cancel();
		demoAnim = null;
	}

	/** ▶ ▶ ▶ along the row, then a hold on the last one, then back to the start. */
	private void demoStep() {
		TextView label = demoLabel;
		if (label == null) return;
		int tick = demoTick++ % (DEMO_TILES + 1);
		if (tick < DEMO_TILES) {
			select(tick);
			label.setText((tick == 0) ? getString(R.string.car_mode_demo_first) :
					getString(R.string.car_mode_demo_next));
		} else {
			flash(demoAt);
			label.setText(R.string.car_mode_demo_hold);
		}
		label.postDelayed(demoStep, (tick < DEMO_TILES) ? DEMO_STEP_MS : DEMO_STEP_MS * 2);
	}

	private void select(int i) {
		for (int j = 0; j < DEMO_TILES; j++) {
			demo[j].setStroke((j == i) ? dp(3) : 0, accent);
			demo[j].setColor((j == i) ? ColorUtils.setAlphaComponent(accent, 0x30) : tileFill);
		}
		demoAt = i;
		View v = demoViews[i];
		v.setScaleX(0.9f);
		v.setScaleY(0.9f);
		v.animate().scaleX(1f).scaleY(1f).setDuration(260)
				.setInterpolator(new OvershootInterpolator(2f)).start();
	}

	private void flash(int i) {
		if (i < 0) return;
		GradientDrawable g = demo[i];
		ArgbEvaluator argb = new ArgbEvaluator();
		int from = accent;
		int to = ColorUtils.setAlphaComponent(accent, 0x30);
		if (demoAnim != null) demoAnim.cancel();
		demoAnim = ValueAnimator.ofFloat(0f, 1f);
		demoAnim.setDuration(700);
		demoAnim.addUpdateListener(a -> g.setColor((int) argb.evaluate((float) a.getAnimatedValue(),
				from, to)));
		demoAnim.start();
	}

	// ---- Switch ----

	private View createSwitch() {
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(12), dp(12), dp(12));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(tileFill);
		bg.setCornerRadius(dp(16));
		row.setBackground(bg);

		TextView label = text(R.string.car_mode_enable, 16, textPrimary, true);
		row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		SwitchCompat sw = new SwitchCompat(palette);
		sw.setChecked(Key.getPrefs().getBooleanPref(Key.CAR_MODE));
		sw.setOnCheckedChangeListener((b, on) -> Key.getPrefs().applyBooleanPref(Key.CAR_MODE, on));
		row.addView(sw);
		row.setOnClickListener(v -> sw.toggle());
		return row;
	}

	// ---- Cards ----

	/** A key glyph in a pill (a "hold" one filled) and what it does. */
	private View keyCard(String glyph, @StringRes int what, boolean hold) {
		LinearLayout row = new LinearLayout(palette);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(12), dp(10), dp(12), dp(10));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(tileFill);
		bg.setCornerRadius(dp(16));
		row.setBackground(bg);

		TextView key = new TextView(palette);
		key.setText(hold ? getString(R.string.car_mode_hold, glyph) : glyph);
		key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		key.setTypeface(Typeface.DEFAULT_BOLD);
		key.setGravity(Gravity.CENTER);
		key.setSingleLine(true);
		key.setPadding(dp(10), dp(6), dp(10), dp(6));
		GradientDrawable kb = new GradientDrawable();
		kb.setCornerRadius(dp(100));
		if (hold) {
			kb.setColor(accent);
			key.setTextColor(onAccent);
		} else {
			kb.setColor(0);
			kb.setStroke(dp(2), accent);
			key.setTextColor(accent);
		}
		key.setBackground(kb);
		// One width for every key, the longest's ("Hold ◀ ▶"): the descriptions all line up.
		row.addView(key, new LinearLayout.LayoutParams(dp(116), ViewGroup.LayoutParams.WRAP_CONTENT));

		TextView t = text(what, 15, textPrimary, false);
		t.setPadding(dp(14), 0, 0, 0);
		row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		row.setLayoutParams(margins(0, dp(6), 0, 0));
		return row;
	}

	private View bullet(@StringRes int res) {
		TextView t = text(res, 14, textPrimary, false);
		t.setText("•  " + getString(res));
		t.setPadding(dp(8), dp(4), dp(4), dp(4));
		return t;
	}

	private void section(LinearLayout content, @StringRes int title) {
		TextView t = text(title, 13, accent, true);
		t.setAllCaps(true);
		t.setLetterSpacing(0.08f);
		t.setPadding(dp(4), dp(20), 0, dp(4));
		content.addView(t);
	}

	// ---- Helpers ----

	private TextView text(@StringRes int res, float sp, int color, boolean bold) {
		TextView t = new TextView(palette);
		if (res != 0) t.setText(res);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.setMargins(l, t, r, b);
		return lp;
	}

	private int paletteColor(int attr) {
		TypedValue tv = new TypedValue();
		palette.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	private int dp(int v) {
		return Math.round(v * density);
	}
}
