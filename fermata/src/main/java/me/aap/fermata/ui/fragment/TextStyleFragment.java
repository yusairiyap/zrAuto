package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.CaptionStyle;
import me.aap.fermata.ui.view.CaptionsOverlayView;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.fermata.ui.view.LyricsView;
import me.aap.fermata.ui.view.SwitchStyle;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;

/**
 * Settings &gt; Captions and Settings &gt; Lyrics: a live preview that stays on top (what changes
 * is drawn by the very views that draw the real thing, so it can't drift apart) over cards of
 * options in the look of the All in One page: switches, sliders, rows of chips and of colour
 * swatches. Every change is applied at once; Reset puts the page's options back.
 */
public abstract class TextStyleFragment extends MainActivityFragment {
	private Context palette;
	private int textPrimary;
	private int textSecondary;
	private int tileFill;
	private int panelFill;
	private int ripple;
	private int accent;
	private int onAccent;
	private float density;
	private final List<Runnable> syncers = new ArrayList<>();
	private final List<Runnable> resetters = new ArrayList<>();
	@Nullable
	private PreferenceStore.Listener prefListener;
	@Nullable
	private View veil;
	@Nullable
	private ScrollView scrollView;
	@Nullable
	private View previewView;
	@Nullable
	private android.view.ViewTreeObserver.OnPreDrawListener previewSync;

	/** Captions over fullscreen video when true, else the lyrics. */
	protected abstract boolean forCaptions();

	@Override
	public CharSequence getTitle() {
		return getString(forCaptions() ? R.string.captions : R.string.lyrics);
	}

	@Override
	public boolean onBackPressed() {
		getActivityDelegate().showFragment(R.id.settings_fragment);
		return true;
	}

	private MainActivityPrefs prefs() {
		return getActivityDelegate().getPrefs();
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		palette = EffectsUi.palette(ctx);
		density = ctx.getResources().getDisplayMetrics().density;
		textPrimary = color(R.attr.musicTextPrimary);
		textSecondary = color(R.attr.musicTextSecondary);
		tileFill = color(R.attr.musicChipFill);
		panelFill = color(R.attr.musicPanelFill);
		ripple = color(R.attr.musicChipRipple);
		accent = EffectsUi.accent(ctx);
		onAccent = EffectsUi.onAccent(accent);
		syncers.clear();
		resetters.clear();

		FrameLayout root = new FrameLayout(palette);

		// Like every scrollable page: the scroll view itself reserves the room the bars take (it is
		// what insetScrollableContent pads, so it ends above the control panel and the bars hide as
		// it scrolls). The preview does not scroll away: it floats over it, at the top of that room,
		// with a gap of its own height left at the head of the content.
		ScrollView scroll = new ScrollView(palette);
		scroll.setVerticalScrollBarEnabled(false);
		scrollView = scroll;
		LinearLayout col = new LinearLayout(palette);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setPadding(dp(16), dp(4), dp(16), dp(16));
		int previewH = dp(forCaptions() ? 170 : 210);
		col.addView(new View(palette), new LinearLayout.LayoutParams(MATCH_PARENT, previewH + dp(12)));
		scroll.addView(col, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		root.addView(scroll, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		if (forCaptions()) buildCaptionRows(col);
		else buildLyricsRows(col);
		addReset(col);

		View preview = forCaptions() ? captionsPreview() : lyricsPreview();
		preview.setElevation(dp(4));
		FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(MATCH_PARENT, previewH, Gravity.TOP);
		plp.setMargins(dp(16), 0, dp(16), 0);
		root.addView(preview, plp);
		previewView = preview;
		// Where the scroll view's top padding ends, checked before every frame (it changes as the
		// bars move), moved with a translation so nothing is laid out again.
		previewSync = () -> {
			float ty = scroll.getPaddingTop() + dp(8);
			if (preview.getTranslationY() != ty) preview.setTranslationY(ty);
			return true;
		};
		root.getViewTreeObserver().addOnPreDrawListener(previewSync);

		prefListener = (store, changed) -> {
			for (Runnable r : syncers) r.run();
			if (veil != null) applyVeil();
		};
		prefs().addBroadcastListener(prefListener);
		for (Runnable r : syncers) r.run();
		return root;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		if (scrollView != null) getActivityDelegate().insetScrollableContent(scrollView);
	}

	@Override
	public void onDestroyView() {
		if (prefListener != null) prefs().removeBroadcastListener(prefListener);
		prefListener = null;
		if ((previewSync != null) && (getView() != null) && getView().getViewTreeObserver().isAlive()) {
			getView().getViewTreeObserver().removeOnPreDrawListener(previewSync);
		}
		previewSync = null;
		previewView = null;
		scrollView = null;
		veil = null;
		syncers.clear();
		resetters.clear();
		super.onDestroyView();
	}

	// ---------------------------------------------------------------------------------------------
	// Previews

	/** A stand-in for a picture: a dusk-coloured gradient the text is drawn over. */
	private FrameLayout previewFrame() {
		FrameLayout f = new FrameLayout(palette);
		GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
				new int[]{0xFF5B6B8F, 0xFF2A3350, 0xFF11151F});
		bg.setCornerRadius(dp(22));
		f.setBackground(bg);
		f.setClipToOutline(true);
		f.setOutlineProvider(new ViewOutlineProvider() {
			@Override
			public void getOutline(View v, Outline o) {
				o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22));
			}
		});
		return f;
	}

	private View captionsPreview() {
		FrameLayout f = previewFrame();
		f.addView(CaptionsOverlayView.preview(palette, getString(R.string.captions_preview)),
				new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		return f;
	}

	private View lyricsPreview() {
		FrameLayout f = previewFrame();
		veil = new View(palette);
		f.addView(veil, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		applyVeil();
		LyricsView lv = new LyricsView(palette, getActivityDelegate(), 0xFFFFFFFF, 0xFFFFFFFF, true);
		lv.setInteractive(false);
		List<String> lines = new ArrayList<>();
		lines.add(getString(R.string.lyrics_preview_1));
		lines.add(getString(R.string.lyrics_preview_2));
		lines.add(getString(R.string.lyrics_preview_3));
		lines.add(getString(R.string.lyrics_preview_4));
		lines.add(getString(R.string.lyrics_preview_5));
		lv.setPreview(lines, 1);
		f.addView(lv, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		return f;
	}

	private void applyVeil() {
		int pct = Math.max(0, Math.min(90, prefs().getIntPref(MainActivityPrefs.LYRICS_VEIL)));
		if (veil != null) veil.setBackgroundColor(Math.round(255 * pct / 100f) << 24);
	}

	// ---------------------------------------------------------------------------------------------
	// The pages

	private void buildCaptionRows(LinearLayout col) {
		section(col, R.string.captions_section_source);
		switchRow(col, R.string.captions_show, R.string.captions_show_sub, MainActivityPrefs.CAPTIONS_ON);
		sourceRows(col);

		section(col, R.string.captions_section_text);
		chipsRow(col, R.string.caption_font, names(CaptionStyle.FONT_NAMES), MainActivityPrefs.CAPTION_FONT, 0);
		sliderRow(col, R.string.caption_size, MainActivityPrefs.CAPTION_SIZE, 50, 300, 10, "%");
		switchRow(col, R.string.caption_bold, 0, MainActivityPrefs.CAPTION_BOLD);
		switchRow(col, R.string.caption_italic, 0, MainActivityPrefs.CAPTION_ITALIC);
		swatchRow(col, R.string.caption_color, MainActivityPrefs.CAPTION_COLOR, false);
		sliderRow(col, R.string.caption_opacity, MainActivityPrefs.CAPTION_OPACITY, 10, 100, 5, "%");

		section(col, R.string.captions_section_edge);
		chipsRow(col, R.string.caption_edge, names(CaptionStyle.EDGE_NAMES), MainActivityPrefs.CAPTION_EDGE, 0);
		swatchRow(col, R.string.caption_edge_color, MainActivityPrefs.CAPTION_EDGE_COLOR, false);
		sliderRow(col, R.string.caption_edge_strength, MainActivityPrefs.CAPTION_EDGE_STRENGTH, 0, 100, 5, "%");

		section(col, R.string.captions_section_box);
		swatchRow(col, R.string.caption_bg_color, MainActivityPrefs.CAPTION_BG_COLOR, false);
		sliderRow(col, R.string.caption_bg_opacity, MainActivityPrefs.CAPTION_BG_OPACITY, 0, 100, 5, "%");

		section(col, R.string.caption_position);
		chipsRow(col, R.string.caption_position, names(CaptionStyle.POSITION_NAMES), MainActivityPrefs.CAPTION_POSITION, 0);
		sliderRow(col, R.string.caption_margin, MainActivityPrefs.CAPTION_MARGIN, 0, 40, 1, "%");
	}

	private void buildLyricsRows(LinearLayout col) {
		section(col, R.string.captions_section_source);
		switchRow(col, R.string.lyrics_show, R.string.lyrics_show_sub, MainActivityPrefs.LYRICS_ON);
		sourceRows(col);

		section(col, R.string.captions_section_text);
		chipsRow(col, R.string.caption_font, names(CaptionStyle.FONT_NAMES), MainActivityPrefs.LYRICS_FONT, 0);
		sliderRow(col, R.string.lyrics_size, MainActivityPrefs.LYRICS_SIZE, 60, 200, 10, "%");
		switchRow(col, R.string.caption_bold, 0, MainActivityPrefs.LYRICS_BOLD);
		switchRow(col, R.string.caption_italic, 0, MainActivityPrefs.LYRICS_ITALIC);
		chipsRow(col, R.string.lyrics_align, new String[]{getString(R.string.lyrics_align_center),
				getString(R.string.lyrics_align_left)}, MainActivityPrefs.LYRICS_ALIGN, 0);
		swatchRow(col, R.string.lyrics_highlight, MainActivityPrefs.LYRICS_COLOR, true);

		section(col, R.string.lyrics_section_cover);
		sliderRow(col, R.string.lyrics_veil, MainActivityPrefs.LYRICS_VEIL, 0, 90, 5, "%");
	}

	/** The language and the auto-generated switch: one pair, shared by the lyrics and the captions. */
	private void sourceRows(LinearLayout col) {
		String[] codes = {"", "en", "ms", "id", "ar", "es", "fr", "de", "pt", "ru", "tr", "hi", "ja", "ko", "zh"};
		List<String> list = new ArrayList<>(List.of(codes));
		String cur = prefs().getStringPref(MainActivityPrefs.CAPTIONS_LANG);
		if (!list.contains(cur)) list.add(cur);
		String[] labels = new String[list.size()];
		for (int i = 0; i < labels.length; i++) {
			String c = list.get(i);
			labels[i] = c.isEmpty() ? getString(R.string.captions_lang_phone) :
					new Locale(c).getDisplayLanguage(Locale.getDefault());
		}
		stringChipsRow(col, R.string.captions_lang, labels, list.toArray(new String[0]),
				MainActivityPrefs.CAPTIONS_LANG);
		switchRow(col, R.string.captions_auto, R.string.captions_auto_sub, MainActivityPrefs.CAPTIONS_AUTO);
	}

	private String[] names(@StringRes int[] ids) {
		String[] r = new String[ids.length];
		for (int i = 0; i < r.length; i++) r[i] = getString(ids[i]);
		return r;
	}

	private void addReset(LinearLayout col) {
		TextView reset = text(getString(R.string.captions_reset), 15, textPrimary, true);
		reset.setGravity(Gravity.CENTER);
		reset.setSingleLine(true);
		reset.setBackground(pressable(rounded(tileFill, 24)));
		reset.setClickable(true);
		reset.setFocusable(true);
		reset.setOnClickListener(v -> UiUtils.showQuestion(requireContext(), getString(R.string.captions_reset),
				getString(R.string.captions_reset_question), requireContext().getDrawable(forCaptions() ? R.drawable.captions : R.drawable.lyrics))
				.onSuccess(ok -> {
					for (Runnable r : resetters) r.run();
				}));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(48));
		lp.topMargin = dp(20);
		col.addView(reset, lp);
	}

	// ---------------------------------------------------------------------------------------------
	// Rows

	private LinearLayout card(LinearLayout parent) {
		LinearLayout c = new LinearLayout(palette);
		c.setOrientation(LinearLayout.VERTICAL);
		c.setBackground(rounded(tileFill, 16));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.topMargin = dp(10);
		parent.addView(c, lp);
		return c;
	}

	private void section(LinearLayout col, @StringRes int title) {
		TextView t = text(getString(title), 13, accent, true);
		t.setAllCaps(true);
		t.setLetterSpacing(0.08f);
		t.setPadding(dp(4), dp(18), 0, dp(0));
		col.addView(t);
	}

	private void switchRow(LinearLayout col, @StringRes int title, @StringRes int sub,
												 Pref<? extends BooleanSupplier> pref) {
		LinearLayout row = card(col);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(12), dp(12), dp(12));
		row.setBackground(pressable(rounded(tileFill, 16)));
		row.setClickable(true);
		row.setFocusable(true);
		LinearLayout labels = new LinearLayout(palette);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.addView(text(getString(title), 15, textPrimary, true));
		if (sub != 0) labels.addView(text(getString(sub), 12.5f, textSecondary, false));
		row.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		SwitchCompat sw = new SwitchCompat(palette);
		SwitchStyle.apply(sw, palette);
		row.addView(sw);
		boolean[] syncing = {false};
		sw.setOnCheckedChangeListener((b, on) -> {
			if (!syncing[0]) prefs().applyBooleanPref(pref, on);
		});
		row.setOnClickListener(v -> sw.toggle());
		syncers.add(() -> {
			boolean on = prefs().getBooleanPref(pref);
			if (sw.isChecked() == on) return;
			syncing[0] = true;
			sw.setChecked(on);
			syncing[0] = false;
		});
		resetters.add(() -> prefs().applyBooleanPref(pref, pref.getDefaultValue().getAsBoolean()));
	}

	private void sliderRow(LinearLayout col, @StringRes int title, Pref<? extends IntSupplier> pref,
												 int min, int max, int step, String unit) {
		LinearLayout row = card(col);
		row.setPadding(dp(16), dp(12), dp(16), dp(6));
		LinearLayout top = new LinearLayout(palette);
		top.addView(text(getString(title), 15, textPrimary, true), new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		TextView value = text("", 14, textSecondary, true);
		top.addView(value);
		row.addView(top);
		SeekBar bar = newSeekBar();
		bar.setMax((max - min) / step);
		row.addView(bar, new LinearLayout.LayoutParams(MATCH_PARENT, dp(36)));
		boolean[] syncing = {false};
		bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
				int v = min + progress * step;
				value.setText(v + unit);
				if (fromUser && !syncing[0]) prefs().applyIntPref(pref, v);
			}

			@Override
			public void onStartTrackingTouch(SeekBar s) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar s) {
			}
		});
		syncers.add(() -> {
			int v = Math.max(min, Math.min(max, prefs().getIntPref(pref)));
			int progress = Math.round((v - min) / (float) step);
			if (bar.getProgress() != progress) {
				syncing[0] = true;
				bar.setProgress(progress);
				syncing[0] = false;
			}
			value.setText((min + progress * step) + unit);
		});
		resetters.add(() -> prefs().applyIntPref(pref, pref.getDefaultValue().getAsInt()));
	}

	private SeekBar newSeekBar() {
		Context ctx = requireContext();
		TypedValue tv = new TypedValue();
		if (ctx.getTheme().resolveAttribute(R.attr.appSeekBarStyle, tv, true) && (tv.resourceId != 0)) {
			return new SeekBar(ctx, null, 0, tv.resourceId);
		}
		return new SeekBar(ctx);
	}

	/** Chips in a row that scrolls sideways, the chosen one filled with the accent; {@code first} offsets the index. */
	private void chipsRow(LinearLayout col, @StringRes int title, String[] labels,
												Pref<? extends IntSupplier> pref, int first) {
		List<TextView> chips = chipsCard(col, title, labels, i -> prefs().applyIntPref(pref, first + i));
		syncers.add(() -> markChips(chips, prefs().getIntPref(pref) - first));
		resetters.add(() -> prefs().applyIntPref(pref, pref.getDefaultValue().getAsInt()));
	}

	private void stringChipsRow(LinearLayout col, @StringRes int title, String[] labels, String[] values,
															Pref<? extends Supplier<String>> pref) {
		List<TextView> chips = chipsCard(col, title, labels, i -> prefs().applyStringPref(pref, values[i]));
		syncers.add(() -> {
			String cur = prefs().getStringPref(pref);
			int sel = -1;
			for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) sel = i;
			markChips(chips, sel);
		});
		resetters.add(() -> prefs().applyStringPref(pref, pref.getDefaultValue().get()));
	}

	private List<TextView> chipsCard(LinearLayout col, @StringRes int title, String[] labels,
																	 java.util.function.IntConsumer onPick) {
		LinearLayout row = card(col);
		row.setPadding(dp(16), dp(12), dp(8), dp(12));
		row.addView(text(getString(title), 15, textPrimary, true));
		HorizontalScrollView hs = new HorizontalScrollView(palette);
		hs.setHorizontalScrollBarEnabled(false);
		hs.setClipToPadding(false);
		LinearLayout chips = new LinearLayout(palette);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		hs.addView(chips, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		hlp.topMargin = dp(8);
		row.addView(hs, hlp);
		List<TextView> list = new ArrayList<>();
		for (int i = 0; i < labels.length; i++) {
			TextView c = text(labels[i], 14, textPrimary, true);
			c.setGravity(Gravity.CENTER);
			c.setSingleLine(true);
			c.setPadding(dp(16), 0, dp(16), 0);
			GradientDrawable fill = rounded(panelFill, 18);
			c.setBackground(pressable(fill));
			c.setTag(fill);
			c.setClickable(true);
			c.setFocusable(true);
			int idx = i;
			c.setOnClickListener(v -> onPick.accept(idx));
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(36));
			clp.setMarginEnd(dp(8));
			chips.addView(c, clp);
			list.add(c);
		}
		return list;
	}

	private void markChips(List<TextView> chips, int selected) {
		for (int i = 0; i < chips.size(); i++) {
			TextView c = chips.get(i);
			boolean sel = i == selected;
			((GradientDrawable) c.getTag()).setColor(sel ? accent : panelFill);
			c.setTextColor(sel ? onAccent : textPrimary);
		}
	}

	/**
	 * Colour swatches: round, the chosen one ringed. With {@code auto} the first is "Auto" (follow
	 * the theme, stored as 0) and the colours are stored as their index + 1; without it as the index.
	 */
	private void swatchRow(LinearLayout col, @StringRes int title, Pref<? extends IntSupplier> pref,
												 boolean auto) {
		LinearLayout row = card(col);
		row.setPadding(dp(16), dp(12), dp(8), dp(12));
		row.addView(text(getString(title), 15, textPrimary, true));
		HorizontalScrollView hs = new HorizontalScrollView(palette);
		hs.setHorizontalScrollBarEnabled(false);
		LinearLayout line = new LinearLayout(palette);
		line.setOrientation(LinearLayout.HORIZONTAL);
		hs.addView(line, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		hlp.topMargin = dp(8);
		row.addView(hs, hlp);
		int off = auto ? 1 : 0;
		int n = CaptionStyle.COLORS.length + off;
		List<View> dots = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int value = i;
			FrameLayout dot = new FrameLayout(palette);
			GradientDrawable d = new GradientDrawable();
			d.setShape(GradientDrawable.OVAL);
			if (auto && (i == 0)) {
				d.setColor(panelFill);
				TextView a = text(getString(R.string.lyrics_color_auto), 11, textPrimary, true);
				a.setGravity(Gravity.CENTER);
				dot.addView(a, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
			} else {
				d.setColor(CaptionStyle.COLORS[i - off] | 0xFF000000);
			}
			dot.setBackground(d);
			dot.setClickable(true);
			dot.setFocusable(true);
			dot.setContentDescription(auto && (i == 0) ? getString(R.string.lyrics_color_auto) :
					getString(CaptionStyle.COLOR_NAMES[i - off]));
			dot.setOnClickListener(v -> prefs().applyIntPref(pref, value));
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
			lp.setMarginEnd(dp(10));
			line.addView(dot, lp);
			dots.add(dot);
		}
		syncers.add(() -> {
			int cur = prefs().getIntPref(pref);
			for (int i = 0; i < dots.size(); i++) {
				GradientDrawable d = (GradientDrawable) dots.get(i).getBackground();
				if (i == cur) d.setStroke(dp(3), accent);
				else d.setStroke(dp(1), 0x44888888);
			}
		});
		resetters.add(() -> prefs().applyIntPref(pref, pref.getDefaultValue().getAsInt()));
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers

	private TextView text(CharSequence s, float sp, int color, boolean bold) {
		TextView t = new TextView(palette);
		t.setText(s);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private RippleDrawable pressable(GradientDrawable fill) {
		GradientDrawable mask = new GradientDrawable();
		mask.setColor(0xFF000000);
		mask.setCornerRadius(fill.getCornerRadius());
		return new RippleDrawable(ColorStateList.valueOf(ripple), fill, mask);
	}

	private GradientDrawable rounded(int color, int radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(color);
		d.setCornerRadius(dp(radiusDp));
		return d;
	}

	private int color(int attr) {
		TypedValue tv = new TypedValue();
		palette.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	private int dp(int v) {
		return Math.round(v * density);
	}
}
