package me.aap.fermata.ui.fragment;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.widget.NestedScrollView;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.addon.ambience.AmbienceEffect;
import me.aap.fermata.addon.ambience.AmbienceEngine;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.AmbienceCardView;
import me.aap.fermata.ui.view.AmbienceHeroView;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.fermata.ui.view.SwitchStyle;
import me.aap.utils.ui.view.ToolBarView;

/**
 * The Ambience tab (see {@link AmbienceEngine}): a sky at the top that shows the weather of
 * everything that is on, with a Start / Stop all button and the timer; a card with the master
 * volume and how long the sound should last; and a card for each sound with its own scene and
 * intensity slider. Everything is built in code, in the Music tab's palette like the Power usage
 * tab, and the cards slide in one after the other when the tab opens.
 */
public class AmbienceFragment extends MainActivityFragment implements ToolBarChip.Host {
	// The settings chip, in the title bar in landscape and on the car screen (like Data usage's).
	private static final ToolBarChip.Mediator TOOL_BAR = new ToolBarChip.Mediator(
			R.id.ambience_toolbar_chip, R.drawable.settings, R.string.ambience_settings);
	private static final ArgbEvaluator ARGB = new ArgbEvaluator();
	private final AmbienceEngine engine = AmbienceEngine.get();
	private final Runnable changed = this::syncControls;
	private final List<TextView> chips = new ArrayList<>();
	private final List<AmbienceCardView> cards = new ArrayList<>();
	private Context palette;
	private float dp;
	private int accent, onAccent, textPrimary, textSecondary, chipFill, rowFill;
	private NestedScrollView scroll;
	private LinearLayout content;
	private AmbienceHeroView hero;
	private LinearLayout controls;
	private LinearLayout grid;
	private LinearLayout settingsRow;
	private SwitchCompat followSwitch;
	private boolean settingSwitch;
	private SeekBar masterBar;
	private TextView masterValue;
	private int columns;
	private boolean settingBar;

	@Override
	public int getFragmentId() {
		return R.id.ambience_addon;
	}

	@NonNull
	@Override
	public CharSequence getTitle() {
		return getString(R.string.ambience_title);
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return TOOL_BAR;
	}

	@Override
	public void onToolBarChipClick() {
		getActivityDelegate().showFragment(R.id.settings_fragment, SettingsFragment.addonSettings("ambience"));
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		palette = new ContextThemeWrapper(ctx, MusicPlayerFragment.isLightTheme(ctx) ?
				R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
		dp = ctx.getResources().getDisplayMetrics().density;
		accent = EffectsUi.accent(palette);
		onAccent = EffectsUi.onAccent(accent);
		textPrimary = paletteColor(R.attr.musicTextPrimary);
		textSecondary = paletteColor(R.attr.musicTextSecondary);
		chipFill = paletteColor(R.attr.musicChipFill);
		rowFill = paletteColor(R.attr.musicRowFill);

		scroll = new NestedScrollView(ctx);
		scroll.setFillViewport(true);
		scroll.setClipToPadding(false);
		content = new LinearLayout(palette);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setClipChildren(false);
		content.setClipToPadding(false);
		// Room under the last row, so the floating buttons can be pressed with the tab scrolled to the end.
		content.setPadding(px(16), px(12), px(16), px(104));
		scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		hero = new AmbienceHeroView(ctx);
		hero.setOnTimerClick(this::nextLength);
		content.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(190)));

		// The settings chip: here when the screen is narrow, in the title bar when it is wide.
		settingsRow = new LinearLayout(palette);
		settingsRow.setGravity(Gravity.CENTER);
		TextView chip = new TextView(palette, null, 0, R.style.MusicChip);
		chip.setText(R.string.ambience_settings);
		chip.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.settings, 0, 0, 0);
		chip.setCompoundDrawableTintList(ColorStateList.valueOf(textPrimary));
		chip.setOnClickListener(v -> onToolBarChipClick());
		settingsRow.addView(chip);
		LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		srlp.topMargin = px(14);
		content.addView(settingsRow, srlp);

		controls = buildControls();
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		clp.topMargin = px(14);
		content.addView(controls, clp);

		TextView sounds = new TextView(palette);
		sounds.setText(R.string.ambience_sounds);
		sounds.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		sounds.setTypeface(Typeface.DEFAULT_BOLD);
		sounds.setTextColor(accent);
		sounds.setAllCaps(true);
		sounds.setLetterSpacing(0.08f);
		sounds.setPadding(px(4), px(22), 0, px(8));
		content.addView(sounds);

		grid = new LinearLayout(palette);
		grid.setOrientation(LinearLayout.VERTICAL);
		grid.setClipChildren(false);
		content.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		for (AmbienceEffect e : AmbienceEffect.values()) cards.add(new AmbienceCardView(ctx, e));
		columns = 0;
		buildGrid(2);
		return scroll;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		MainActivityDelegate a = getActivityDelegate();
		// tool_bar/nav_bar are drawn over the fragment: the list has to keep clear of them itself.
		a.insetScrollableContent(scroll);
		// Not during layout: changing the columns requests another one.
		scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (((r - l) != (or - ol)) || ((b - t) != (ob - ot))) v.post(() -> updateLayout(v));
		});
		syncControls();
		updateSettingsChip();
		// Everything slides in one after the other, the cards last.
		List<View> parts = new ArrayList<>();
		parts.add(hero);
		parts.add(settingsRow);
		parts.add(controls);
		for (int i = 0; i < grid.getChildCount(); i++) {
			ViewGroup row = (ViewGroup) grid.getChildAt(i);
			for (int k = 0; k < row.getChildCount(); k++) parts.add(row.getChildAt(k));
		}
		for (View v : parts) {
			v.setAlpha(0f);
			v.setTranslationY(px(28));
		}
		scroll.post(() -> {
			int i = 0;
			for (View v : parts) {
				v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i++).setDuration(420)
						.setInterpolator(new DecelerateInterpolator(2f)).start();
			}
		});
	}

	@Override
	public void onResume() {
		super.onResume();
		engine.addListener(changed);
		me.aap.fermata.addon.ambience.AmbienceSamples.get().refresh();
		syncControls();
	}

	@Override
	public void onPause() {
		super.onPause();
		engine.removeListener(changed);
	}

	@Override
	public void onDestroyView() {
		engine.removeListener(changed);
		cards.clear();
		chips.clear();
		super.onDestroyView();
	}

	private int px(float v) {
		return Math.round(v * dp);
	}

	private int paletteColor(int attr) {
		TypedValue tv = new TypedValue();
		palette.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	// ---------------------------------------------------------------------------------------------
	// Volume and length
	// ---------------------------------------------------------------------------------------------

	private LinearLayout buildControls() {
		LinearLayout card = new LinearLayout(palette);
		card.setOrientation(LinearLayout.VERTICAL);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(rowFill);
		bg.setCornerRadius(px(24));
		card.setBackground(bg);
		card.setPadding(px(18), px(16), px(18), px(16));

		// Volume
		LinearLayout vol = new LinearLayout(palette);
		vol.setGravity(Gravity.CENTER_VERTICAL);
		ImageView vi = new ImageView(palette);
		vi.setImageResource(R.drawable.volume_up);
		vi.setImageTintList(ColorStateList.valueOf(accent));
		vol.addView(vi, new LinearLayout.LayoutParams(px(22), px(22)));
		TextView vt = label(R.string.ambience_volume, 15, textPrimary, true);
		vt.setPadding(px(10), 0, 0, 0);
		vol.addView(vt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		masterValue = label(0, 14, textSecondary, false);
		vol.addView(masterValue);
		card.addView(vol);
		masterBar = newSeekBar();
		masterBar.setMax(100);
		masterBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
				masterValue.setText(getString(R.string.ambience_percent, progress));
				if (fromUser && !settingBar) engine.setMaster(progress);
			}

			@Override
			public void onStartTrackingTouch(SeekBar s) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar s) {
			}
		});
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, px(36));
		blp.topMargin = px(4);
		card.addView(masterBar, blp);

		// Length
		LinearLayout len = new LinearLayout(palette);
		len.setGravity(Gravity.CENTER_VERTICAL);
		ImageView li = new ImageView(palette);
		li.setImageResource(R.drawable.timer);
		li.setImageTintList(ColorStateList.valueOf(accent));
		len.addView(li, new LinearLayout.LayoutParams(px(22), px(22)));
		TextView lt = label(R.string.ambience_length, 15, textPrimary, true);
		lt.setPadding(px(10), 0, 0, 0);
		len.addView(lt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		llp.topMargin = px(14);
		card.addView(len, llp);

		HorizontalScrollView hs = new HorizontalScrollView(palette);
		hs.setHorizontalScrollBarEnabled(false);
		hs.setClipToPadding(false);
		LinearLayout row = new LinearLayout(palette);
		row.setGravity(Gravity.CENTER_VERTICAL);
		for (int minutes : AmbienceEngine.LENGTHS) {
			TextView chip = label(0, 14, textPrimary, true);
			chip.setText(AmbienceHeroView.lengthLabel(palette, minutes));
			chip.setGravity(Gravity.CENTER);
			chip.setPadding(px(16), 0, px(16), 0);
			chip.setBackground(new GradientDrawable());
			((GradientDrawable) chip.getBackground()).setCornerRadius(px(20));
			chip.setClickable(true);
			chip.setFocusable(true);
			chip.setTag(minutes);
			chip.setOnClickListener(v -> engine.setLengthMinutes(minutes));
			LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
			p.setMarginEnd(px(8));
			row.addView(chip, p);
			chips.add(chip);
		}
		hs.addView(row);
		LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		hlp.topMargin = px(10);
		card.addView(hs, hlp);
		TextView hint = label(0, 12.5f, textSecondary, false);
		hint.setTag("hint");
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		tlp.topMargin = px(8);
		card.addView(hint, tlp);

		// Pause with playback: here, beside the length it belongs with, as well as in the settings.
		LinearLayout follow = new LinearLayout(palette);
		follow.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout ft = new LinearLayout(palette);
		ft.setOrientation(LinearLayout.VERTICAL);
		ft.addView(label(R.string.ambience_set_follow, 15, textPrimary, true));
		TextView fsub = label(R.string.ambience_follow_hint, 12.5f, textSecondary, false);
		ft.addView(fsub);
		follow.addView(ft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		followSwitch = new SwitchCompat(palette);
		SwitchStyle.apply(followSwitch, palette);
		followSwitch.setChecked(AmbienceEngine.prefs().getBooleanPref(AmbienceEngine.FOLLOW));
		followSwitch.setOnCheckedChangeListener((b, on) -> {
			if (!settingSwitch) AmbienceEngine.prefs().applyBooleanPref(AmbienceEngine.FOLLOW, on);
		});
		follow.addView(followSwitch);
		LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		flp.topMargin = px(16);
		card.addView(follow, flp);
		return card;
	}

	private TextView label(int res, float sp, int color, boolean bold) {
		TextView t = new TextView(palette);
		if (res != 0) t.setText(res);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	/** A seek bar in the style of the control panel's (same track thickness, thumb and colours). */
	private SeekBar newSeekBar() {
		Context ctx = requireContext();
		TypedValue tv = new TypedValue();
		SeekBar bar;
		if (ctx.getTheme().resolveAttribute(R.attr.appSeekBarStyle, tv, true) && (tv.resourceId != 0)) {
			bar = new SeekBar(ctx, null, 0, tv.resourceId);
		} else {
			bar = new SeekBar(ctx);
		}
		bar.setPadding(px(12), 0, px(12), 0);
		return bar;
	}

	private void nextLength() {
		int cur = engine.getLengthMinutes();
		int[] all = AmbienceEngine.LENGTHS;
		int idx = 0;
		for (int i = 0; i < all.length; i++) if (all[i] == cur) idx = i;
		engine.setLengthMinutes(all[(idx + 1) % all.length]);
	}

	private void syncControls() {
		if (masterBar == null) return;
		int m = engine.getMaster();
		if (masterBar.getProgress() != m) {
			settingBar = true;
			masterBar.setProgress(m);
			settingBar = false;
		}
		masterValue.setText(getString(R.string.ambience_percent, m));
		int len = engine.getLengthMinutes();
		for (TextView chip : chips) styleChip(chip, ((Integer) chip.getTag()) == len);
		boolean follow = AmbienceEngine.prefs().getBooleanPref(AmbienceEngine.FOLLOW);
		if ((followSwitch != null) && (followSwitch.isChecked() != follow)) {
			settingSwitch = true;
			followSwitch.setChecked(follow);
			settingSwitch = false;
		}
		View hint = controls.findViewWithTag("hint");
		if (hint instanceof TextView h) {
			h.setText(len == 0 ? R.string.ambience_length_hint_forever : R.string.ambience_length_hint);
		}
	}

	/** Fades a chip to its selected or unselected colours (nothing happens when it already is). */
	private void styleChip(TextView chip, boolean selected) {
		Object cur = chip.getTag(R.id.ambience_addon);
		if ((cur instanceof Boolean b) && (b == selected)) return;
		boolean first = cur == null;
		chip.setTag(R.id.ambience_addon, selected);
		GradientDrawable bg = (GradientDrawable) chip.getBackground();
		int toFill = selected ? accent : chipFill;
		int toText = selected ? onAccent : textPrimary;
		if (first || !chip.isAttachedToWindow()) {
			bg.setColor(toFill);
			chip.setTextColor(toText);
			return;
		}
		int fromFill = selected ? chipFill : accent;
		int fromText = selected ? textPrimary : onAccent;
		ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
		va.setDuration(220);
		va.addUpdateListener(an -> {
			float f = an.getAnimatedFraction();
			bg.setColor((Integer) ARGB.evaluate(f, fromFill, toFill));
			chip.setTextColor((Integer) ARGB.evaluate(f, fromText, toText));
		});
		va.start();
		if (selected) {
			chip.setScaleX(0.9f);
			chip.setScaleY(0.9f);
			chip.animate().scaleX(1f).scaleY(1f).setDuration(260).start();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Layout
	// ---------------------------------------------------------------------------------------------

	private void updateLayout(View root) {
		int w = root.getWidth();
		if ((w == 0) || (getContext() == null)) return;
		updateSettingsChip();
		float wdp = w / dp;
		int cols = (wdp >= 900) ? 4 : (wdp >= 600) ? 3 : 2;
		if (cols != columns) buildGrid(cols);
		// A shorter sky where the screen is short (landscape, the car).
		int hdp = Math.round(root.getHeight() / dp);
		int want = px(hdp < 420 ? 150 : 190);
		ViewGroup.LayoutParams lp = hero.getLayoutParams();
		if (lp.height != want) {
			lp.height = want;
			hero.setLayoutParams(lp);
		}
	}

	/** The settings chip: in the title bar in landscape and on the car screen, else in here. */
	private void updateSettingsChip() {
		if (settingsRow != null) ToolBarChip.update(this, R.id.ambience_toolbar_chip, settingsRow);
	}

	private void buildGrid(int cols) {
		columns = cols;
		for (AmbienceCardView c : cards) {
			if (c.getParent() instanceof ViewGroup p) p.removeView(c);
		}
		grid.removeAllViews();
		LinearLayout row = null;
		for (int i = 0; i < cards.size(); i++) {
			if (i % cols == 0) {
				row = new LinearLayout(palette);
				row.setClipChildren(false);
				LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
				if (i > 0) rlp.topMargin = px(12);
				grid.addView(row, rlp);
			}
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
					ViewGroup.LayoutParams.MATCH_PARENT, 1f);
			if (i % cols != 0) lp.setMarginStart(px(12));
			row.addView(cards.get(i), lp);
		}
		// The last row keeps the others' width.
		int rest = (cols - cards.size() % cols) % cols;
		for (int i = 0; i < rest; i++) {
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
			lp.setMarginStart(px(12));
			row.addView(new View(palette), lp);
		}
	}
}
