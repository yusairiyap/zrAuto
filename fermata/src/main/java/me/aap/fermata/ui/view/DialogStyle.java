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

		// The panel is the menu's own card the dialog sits in (so there's one rounded surface, not a
		// card in a card): recoloured while the dialog is up, put back after.
		int panelColor = color(ctx, R.attr.musicPanelFill);
		float radius = 28 * density;
		final boolean car = me.aap.fermata.ui.activity.MainActivityDelegate.get(dv.getContext())
				.isCarActivity();
		dv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			private View menu;
			private Drawable was;
			private float lift;
			private android.view.ViewTreeObserver.OnPreDrawListener keyboardSync;

			@Override
			public void onViewAttachedToWindow(View v) {
				for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
					if (p instanceof me.aap.utils.ui.menu.OverlayMenuView m) {
						menu = m;
						break;
					}
				}
				if (menu == null) return;
				was = menu.getBackground();
				GradientDrawable g = new GradientDrawable();
				g.setColor(panelColor);
				g.setCornerRadius(radius);
				menu.setBackground(g);

				// Rises into place while the menu fades in.
				menu.setScaleX(0.92f);
				menu.setScaleY(0.92f);
				menu.setTranslationY(24 * density);
				menu.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(280)
						.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
				dv.setOnDismissStart(() -> {
					View m = menu;
					if (m == null) return;
					m.animate().cancel();
					m.animate().scaleX(0.94f).scaleY(0.94f).translationY(16 * density).setDuration(200)
							.setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f)).start();
				});

				// Out of the keyboard's way: lifted when it would cover the card.
				if (!car) {
					keyboardSync = () -> {
						liftAboveKeyboard(v);
						return true;
					};
					v.getViewTreeObserver().addOnPreDrawListener(keyboardSync);
					// The keyboard up at once when there's a field to type into.
					EditText field = firstField(v);
					if (field != null) {
						v.postDelayed(() -> {
							field.requestFocus();
							android.view.inputmethod.InputMethodManager imm = field.getContext()
									.getSystemService(android.view.inputmethod.InputMethodManager.class);
							if (imm != null) imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
						}, 120);
					}
				}
			}

			/** Eases the menu up by however much the keyboard covers of it, and back down. */
			private void liftAboveKeyboard(View v) {
				if (menu == null) return;
				androidx.core.view.WindowInsetsCompat wi = androidx.core.view.ViewCompat
						.getRootWindowInsets(v);
				int ime = (wi == null) ? 0 : wi.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
				float target = 0;
				if (ime > 0) {
					int[] loc = new int[2];
					menu.getLocationInWindow(loc);
					float bottom = loc[1] - menu.getTranslationY() + menu.getHeight();
					float room = v.getRootView().getHeight() - ime - 12 * density;
					target = Math.max(0f, bottom - room);
					// Never past the top of the screen.
					target = Math.min(target, Math.max(0f, loc[1] - menu.getTranslationY() - 8 * density));
				}
				if (Math.abs(target - lift) < 0.5f) {
					if (lift != target) {
						lift = target;
						menu.setTranslationY(-lift);
					}
					return;
				}
				lift += (target - lift) * 0.25f;
				menu.setTranslationY(-lift);
				menu.postInvalidateOnAnimation();
			}

			@Override
			public void onViewDetachedFromWindow(View v) {
				v.removeOnAttachStateChangeListener(this);
				if (keyboardSync != null) v.getViewTreeObserver().removeOnPreDrawListener(keyboardSync);
				if (menu != null) {
					menu.animate().cancel();
					menu.setBackground(was);
					menu.setScaleX(1f);
					menu.setScaleY(1f);
					menu.setTranslationY(0f);
				}
				menu = null;
			}
		});
		int pad = Math.round(8 * density);
		dv.setPadding(pad, pad, pad, pad);

		// Room between the parts: title, message or field, buttons.
		int gap = Math.round(12 * density);
		for (int id : new int[]{com.google.android.material.R.id.topPanel,
				com.google.android.material.R.id.contentPanel, com.google.android.material.R.id.customPanel}) {
			View p = dv.findViewById(id);
			if ((p != null) && (p.getVisibility() == View.VISIBLE)) {
				p.setPadding(p.getPaddingLeft() + gap / 2, p.getPaddingTop(), p.getPaddingRight() + gap / 2,
						p.getPaddingBottom() + gap);
			}
		}
		View buttonPanel = dv.findViewById(com.google.android.material.R.id.buttonPanel);
		if (buttonPanel != null) {
			buttonPanel.setPadding(buttonPanel.getPaddingLeft() + gap / 2, gap / 2,
					buttonPanel.getPaddingRight() + gap / 2, buttonPanel.getPaddingBottom() + gap / 2);
		}

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
			if (b.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
				// Apart from one another.
				mlp.setMargins(Math.round(6 * density), 0, Math.round(6 * density), 0);
				b.setLayoutParams(mlp);
			}
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

	private static EditText firstField(View v) {
		if (v instanceof EditText e) return e;
		if (v instanceof ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) {
				EditText e = firstField(g.getChildAt(i));
				if (e != null) return e;
			}
		}
		return null;
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
