package me.aap.fermata.ui.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.AttrRes;

import com.google.android.material.button.MaterialButton;

import me.aap.fermata.R;
import me.aap.utils.ui.view.DialogView;

/**
 * Gives every dialog of the app (confirmations, name inputs, alerts) the look of the playlist
 * picker and the Music tab's menus: the same panel, big pill buttons with the main one in the
 * accent colour, and rounded input fields. Applied to each {@link DialogView} as it's built, see
 * {@link DialogView#setStyler}.
 */
public final class DialogStyle {
	private DialogStyle() {
	}

	public static void apply(DialogView dv) {
		Context ctx = EffectsUi.palette(dv.getContext());
		float density = ctx.getResources().getDisplayMetrics().density;
		int primary = color(ctx, R.attr.musicTextPrimary);
		int secondary = color(ctx, R.attr.musicTextSecondary);
		int chipFill = color(ctx, R.attr.musicChipFill);
		int ripple = color(ctx, R.attr.musicChipRipple);
		int accent = EffectsUi.accent(ctx);
		int onAccent = EffectsUi.onAccent(accent);

		GradientDrawable panel = new GradientDrawable();
		panel.setColor(color(ctx, R.attr.musicPanelFill));
		panel.setCornerRadius(28 * density);
		dv.setBackground(panel);
		int pad = Math.round(8 * density);
		dv.setPadding(pad, pad, pad, pad);

		View divider = dv.findViewById(com.google.android.material.R.id.titleDividerNoCustom);
		if (divider != null) divider.setVisibility(View.GONE);

		TextView title = dv.findViewById(com.google.android.material.R.id.alertTitle);
		if (title != null) {
			title.setTextColor(primary);
			title.setTypeface(Typeface.DEFAULT_BOLD);
			title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		}
		ImageView icon = dv.findViewById(android.R.id.icon);
		if (icon != null) icon.setImageTintList(ColorStateList.valueOf(accent));
		TextView message = dv.findViewById(android.R.id.message);
		if (message != null) {
			message.setTextColor(primary);
			message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		}

		for (int id : new int[]{android.R.id.button1, android.R.id.button2, android.R.id.button3}) {
			if (!(dv.findViewById(id) instanceof Button b) || (b.getVisibility() != View.VISIBLE)) continue;
			boolean main = (id == android.R.id.button1);
			int fill = main ? accent : chipFill;
			b.setAllCaps(false);
			b.setTypeface(Typeface.DEFAULT_BOLD);
			b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
			b.setTextColor(main ? onAccent : primary);
			b.setMinHeight(Math.round(46 * density));
			b.setMinWidth(Math.round(96 * density));
			if (b instanceof MaterialButton mb) {
				mb.setInsetTop(0);
				mb.setInsetBottom(0);
				mb.setCornerRadius(Math.round(24 * density));
				mb.setBackgroundTintList(ColorStateList.valueOf(fill));
				mb.setRippleColor(ColorStateList.valueOf(ripple));
				mb.setStrokeWidth(0);
			} else {
				b.setBackground(pressable(fill, ripple, accent, density));
			}
		}

		View custom = dv.findViewById(com.google.android.material.R.id.custom);
		if (custom instanceof ViewGroup g) styleFields(g, primary, secondary, chipFill, density);
	}

	/** Text fields inside the dialog's own view: rounded, filled, roomy. */
	private static void styleFields(ViewGroup g, int primary, int secondary, int fill, float density) {
		for (int i = 0, n = g.getChildCount(); i < n; i++) {
			View v = g.getChildAt(i);
			if (v instanceof EditText e) {
				GradientDrawable bg = new GradientDrawable();
				bg.setColor(fill);
				bg.setCornerRadius(18 * density);
				e.setBackground(bg);
				e.setTextColor(primary);
				e.setHintTextColor((secondary & 0x00FFFFFF) | 0x99000000);
				e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
				int p = Math.round(14 * density);
				e.setPadding(p, p, p, p);
			} else if (v instanceof ViewGroup child) {
				styleFields(child, primary, secondary, fill, density);
			}
		}
	}

	private static Drawable pressable(int fill, int ripple, int accent, float density) {
		StateListDrawable states = new StateListDrawable();
		states.addState(new int[]{android.R.attr.state_focused}, shape(fill, accent, 2, density));
		states.addState(new int[]{}, shape(fill, 0, 0, density));
		return new RippleDrawable(ColorStateList.valueOf(ripple), states, shape(0xFF000000, 0, 0, density));
	}

	private static GradientDrawable shape(int fill, int stroke, int strokeDp, float density) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(fill);
		d.setCornerRadius(24 * density);
		if (strokeDp > 0) d.setStroke(Math.round(strokeDp * density), stroke);
		return d;
	}

	private static int color(Context ctx, @AttrRes int attr) {
		TypedValue tv = new TypedValue();
		ctx.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}
}
