package me.aap.fermata.ui.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import me.aap.fermata.R;
import me.aap.utils.ui.UiUtils;

/**
 * Lays its children out left to right and wraps to a new line when one doesn't fit: a row of
 * {@linkplain #chip chips} like the Music and Data Usage tabs have, that stays usable on a narrow
 * phone and on the car screen alike.
 */
public class ChipFlowLayout extends ViewGroup {
	private final int gap;

	public ChipFlowLayout(Context ctx) {
		super(ctx);
		gap = UiUtils.toIntPx(ctx, 8);
	}

	@Override
	protected void onMeasure(int widthSpec, int heightSpec) {
		int maxW = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
		int x = 0;
		int y = 0;
		int rowH = 0;
		int usedW = 0;

		for (int i = 0; i < getChildCount(); i++) {
			View c = getChildAt(i);
			if (c.getVisibility() == GONE) continue;
			measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightSpec);
			int w = c.getMeasuredWidth();
			if ((x > 0) && (x + w > maxW)) {
				x = 0;
				y += rowH + gap;
				rowH = 0;
			}
			x += w + gap;
			rowH = Math.max(rowH, c.getMeasuredHeight());
			usedW = Math.max(usedW, x - gap);
		}

		int h = y + rowH + getPaddingTop() + getPaddingBottom();
		setMeasuredDimension(resolveSize(usedW + getPaddingLeft() + getPaddingRight(), widthSpec),
				resolveSize(h, heightSpec));
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		int maxW = r - l - getPaddingLeft() - getPaddingRight();
		int x = 0;
		int y = 0;
		int rowH = 0;

		for (int i = 0; i < getChildCount(); i++) {
			View c = getChildAt(i);
			if (c.getVisibility() == GONE) continue;
			int w = c.getMeasuredWidth();
			if ((x > 0) && (x + w > maxW)) {
				x = 0;
				y += rowH + gap;
				rowH = 0;
			}
			int left = getPaddingLeft() + x;
			int top = getPaddingTop() + y;
			c.layout(left, top, left + w, top + c.getMeasuredHeight());
			x += w + gap;
			rowH = Math.max(rowH, c.getMeasuredHeight());
		}
	}

	/**
	 * A chip in the Music tab's look ({@code palette} is a context with that palette, see
	 * {@link EffectsUi#palette}); {@code primary} fills it with the theme's accent color for the
	 * one action that matters most.
	 */
	public static TextView chip(Context palette, @DrawableRes int icon, CharSequence text,
															boolean primary, Runnable onClick) {
		TextView t = new TextView(palette, null, 0, R.style.MusicChip);
		t.setText(text);
		t.setMinHeight(UiUtils.toIntPx(palette, 40));
		t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
		if (primary) {
			int accent = EffectsUi.accent(palette);
			int on = EffectsUi.onAccent(accent);
			GradientDrawable fill = new GradientDrawable();
			fill.setColor(accent);
			fill.setCornerRadius(UiUtils.toPx(palette, 24));
			t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), fill, null));
			t.setTextColor(on);
			t.setCompoundDrawableTintList(ColorStateList.valueOf(on));
			t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
		}
		t.setOnClickListener(v -> onClick.run());
		return t;
	}

	public static TextView chip(Context palette, @DrawableRes int icon, @StringRes int text,
															boolean primary, Runnable onClick) {
		return chip(palette, icon, palette.getString(text), primary, onClick);
	}
}
