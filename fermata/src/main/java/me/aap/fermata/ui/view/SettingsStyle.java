package me.aap.fermata.ui.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import me.aap.fermata.R;
import me.aap.utils.pref.PreferenceView;

/**
 * The one look every settings page has: the rows of the PreferenceSet pages (this class), the Home, Power and other option
 * pages ({@code ReorderListFragment}) and the category list of the tablet layout ({@code SettingsSplit}) are all rounded
 * tiles in the Music tab's palette, bold titles over a quieter line, icons and ticks in the accent.
 */
public final class SettingsStyle {
	private SettingsStyle() {
	}

	/** The colours of the current theme, resolved from the Music palette. */
	public static final class Colors {
		public final int textPrimary;
		public final int textSecondary;
		public final int tileFill;
		public final int ripple;
		public final int accent;
		public final int onAccent;
		public final float density;

		public Colors(Context ctx) {
			Context pc = EffectsUi.palette(ctx);
			textPrimary = resolve(pc, R.attr.musicTextPrimary);
			textSecondary = resolve(pc, R.attr.musicTextSecondary);
			tileFill = resolve(pc, R.attr.musicChipFill);
			ripple = resolve(pc, R.attr.musicChipRipple);
			accent = EffectsUi.accent(ctx);
			onAccent = EffectsUi.onAccent(accent);
			density = ctx.getResources().getDisplayMetrics().density;
		}

		public int dp(float v) {
			return Math.round(v * density);
		}

		public GradientDrawable rounded(int color, float radiusDp) {
			GradientDrawable d = new GradientDrawable();
			d.setColor(color);
			d.setCornerRadius(dp(radiusDp));
			return d;
		}

		/** Whether the theme is light (dark text). */
		public boolean light() {
			return androidx.core.graphics.ColorUtils.calculateLuminance(textPrimary | 0xFF000000) < 0.5;
		}

		/**
		 * A row's tile: the plain fill, or with the glass UI style a faint sheen with a hairline rim (see
		 * {@link Glass#tile}); a picked one is tinted with the accent and outlined, the same width either way.
		 */
		public GradientDrawable tile(float radiusDp, boolean picked) {
			// Follows the UI style as it changes, gliding (Glass#tile).
			return Glass.tile(tileFill, dp(radiusDp), light(), picked, accent, density);
		}

		/** A rounded fill with a ripple over it when pressed. */
		public RippleDrawable pressable(GradientDrawable fill) {
			GradientDrawable mask = new GradientDrawable();
			mask.setColor(0xFF000000);
			mask.setCornerRadius(fill.getCornerRadius());
			return new RippleDrawable(ColorStateList.valueOf(ripple), fill, mask);
		}
	}

	private static int resolve(Context pc, int attr) {
		TypedValue tv = new TypedValue();
		pc.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	/** The sliders of a row in the accent, as the option pages' (the control panel's) are. */
	private static void tintSliders(android.view.View v, Colors c) {
		if (v instanceof android.widget.SeekBar sb) {
			sb.setProgressTintList(ColorStateList.valueOf(c.accent));
			sb.setThumbTintList(ColorStateList.valueOf(c.accent));
			sb.setSecondaryProgressTintList(ColorStateList.valueOf(c.textSecondary));
			sb.setProgressBackgroundTintList(ColorStateList.valueOf((c.textSecondary & 0xFFFFFF) | 0x55000000));
			Glass.knob(sb);
		} else if (v instanceof android.view.ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) tintSliders(g.getChildAt(i), c);
		}
	}

	/** Restyles one row of a PreferenceSet page; set as {@link PreferenceView#setRowStyler}. */
	public static void apply(PreferenceView v, PreferenceView.Opts o) {
		Colors c = new Colors(v.getContext());
		v.setBackground(c.pressable(c.tile(16, false)));
		v.setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12));

		// The same sizes as the option pages built in code and the category list: 15sp over 12.5sp.
		TextView title = v.getTitleView();
		title.setTextColor(c.textPrimary);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		TextView sub = v.getSubtitleView();
		sub.setTextColor(c.textSecondary);
		sub.setTypeface(Typeface.DEFAULT);
		sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
		tintSliders(v, c);
		if (o.carOnly) {
			// "Car screen" after the title, small and in the accent: this setting only does something on Android Auto.
			android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(title.getText());
			int from = sb.length() + 2;
			sb.append("  ").append(v.getContext().getString(me.aap.fermata.R.string.settings_car_only));
			sb.setSpan(new android.text.style.RelativeSizeSpan(0.72f), from, sb.length(), 0);
			sb.setSpan(new android.text.style.ForegroundColorSpan(c.accent), from, sb.length(), 0);
			sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), from, sb.length(), 0);
			title.setText(sb);
		}

		ImageView icon = v.getIconView();
		if (icon.getVisibility() == android.view.View.VISIBLE) icon.setImageTintList(ColorStateList.valueOf(c.accent));

		android.view.View value = v.findViewById(me.aap.utils.R.id.pref_value);
		if (value instanceof ImageView chevron) {
			chevron.setImageTintList(ColorStateList.valueOf(c.textSecondary));
		} else if (value instanceof CheckBox box) {
			box.setButtonTintList(ColorStateList.valueOf(c.accent));
		} else if (value instanceof TextView t && !(value instanceof CompoundButton)) {
			t.setTextColor(c.accent);
		}
	}
}
