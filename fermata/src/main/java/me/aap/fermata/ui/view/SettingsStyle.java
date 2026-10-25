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

	/** Restyles one row of a PreferenceSet page; set as {@link PreferenceView#setRowStyler}. */
	public static void apply(PreferenceView v, PreferenceView.Opts o) {
		Colors c = new Colors(v.getContext());
		v.setBackground(c.pressable(c.rounded(c.tileFill, 16)));
		v.setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12));

		TextView title = v.getTitleView();
		title.setTextColor(c.textPrimary);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		TextView sub = v.getSubtitleView();
		sub.setTextColor(c.textSecondary);
		sub.setTypeface(Typeface.DEFAULT);

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
