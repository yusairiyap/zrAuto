package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.UiUtils;

/**
 * A modal card for a longer task (e.g. a Spotify sync): a title, what's happening right now, a
 * progress bar and Cancel, over a dimmed screen that takes no touches. When the task is done the
 * card shows the outcome with Close, and goes away by itself after a few seconds. The look of the
 * other modal cards (the playlist picker, Home's cards): the Music palette's panel (glass with the
 * UI style's glass on menus and dialogs, {@link MenuStyle#panel}), an icon in an accent disc, a close
 * button, and it rises into place. On the phone's and Android Auto's screen alike.
 */
public final class ModalProgressPopup {
	private static final long RESULT_MS = 4000;
	private static final int OK = 0xFF1ED760;
	private static final int WARN = 0xFFFFC857;
	private final View scrim;
	private final LinearLayout card;
	private final TextView text;
	private final ProgressBar bar;
	private final LinearLayout chips;
	private final View disc;
	private final SettingsStyle.Colors colors;
	private boolean dismissed;

	private ModalProgressPopup(View scrim, LinearLayout card, View disc, TextView text,
														 ProgressBar bar, LinearLayout chips, SettingsStyle.Colors colors) {
		this.scrim = scrim;
		this.card = card;
		this.disc = disc;
		this.text = text;
		this.bar = bar;
		this.chips = chips;
		this.colors = colors;
	}

	/** @return null if there's no screen to show it on */
	@Nullable
	public static ModalProgressPopup show(MainActivityDelegate a, @DrawableRes int iconRes,
																				CharSequence title, @Nullable Runnable cancel) {
		return show(a, iconRes, title, cancel, 0, null);
	}

	/**
	 * As {@link #show(MainActivityDelegate, int, CharSequence, Runnable)}, with one more button
	 * before Cancel ({@code extraText} and {@code extra}) that takes itself away once tapped.
	 */
	@Nullable
	public static ModalProgressPopup show(MainActivityDelegate a, @DrawableRes int iconRes,
																				CharSequence title, @Nullable Runnable cancel,
																				@androidx.annotation.StringRes int extraText,
																				@Nullable Runnable extra) {
		View body = a.getBody();
		if (body == null) return null;
		View root = body.getRootView();
		View content = root.findViewById(android.R.id.content);
		FrameLayout host = (content instanceof FrameLayout f) ? f :
				(root instanceof FrameLayout f) ? f : null;
		if (host == null) return null;

		Context ctx = host.getContext();
		SettingsStyle.Colors c = new SettingsStyle.Colors(ctx);

		FrameLayout scrim = new FrameLayout(ctx);
		scrim.setBackgroundColor(0x66000000);
		scrim.setClickable(true); // Modal: nothing underneath reacts meanwhile.
		scrim.setFocusable(true);
		scrim.setElevation(UiUtils.toPx(ctx, 40));

		LinearLayout card = new LinearLayout(ctx);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setClickable(true);
		int pad = c.dp(18);
		card.setPadding(pad, c.dp(14), pad, c.dp(14));
		card.setBackground(MenuStyle.panel(card, MenuStyle.panelColor(ctx), c.dp(28)));
		if (!Glass.onMenus()) card.setElevation(c.dp(12));

		LinearLayout head = new LinearLayout(ctx);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		card.addView(head, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		FrameLayout disc = new FrameLayout(ctx);
		GradientDrawable dd = new GradientDrawable();
		dd.setShape(GradientDrawable.OVAL);
		dd.setColor(c.accent);
		disc.setBackground(dd);
		ImageView icon = new ImageView(ctx);
		icon.setImageResource(iconRes);
		icon.setImageTintList(ColorStateList.valueOf(c.onAccent));
		disc.addView(icon, new FrameLayout.LayoutParams(c.dp(18), c.dp(18), Gravity.CENTER));
		LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(c.dp(34), c.dp(34));
		ilp.setMarginEnd(c.dp(12));
		head.addView(disc, ilp);

		TextView t = new TextView(ctx);
		t.setText(title);
		t.setTextColor(c.textPrimary);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setMaxLines(2);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		head.addView(t, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		ImageView close = new ImageView(ctx);
		close.setImageResource(me.aap.utils.R.drawable.close);
		close.setImageTintList(ColorStateList.valueOf(c.textPrimary));
		int cp = c.dp(9);
		close.setPadding(cp, cp, cp, cp);
		close.setBackground(pressable(c, 0, c.dp(20)));
		close.setContentDescription(ctx.getString(R.string.close));
		close.setClickable(true);
		head.addView(close, new LinearLayout.LayoutParams(c.dp(40), c.dp(40)));

		TextView msg = new TextView(ctx);
		msg.setTextColor(c.textSecondary);
		msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		msg.setMaxLines(3);
		LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		mlp.topMargin = c.dp(12);
		mlp.setMarginStart(c.dp(4));
		mlp.setMarginEnd(c.dp(4));
		card.addView(msg, mlp);

		ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
		bar.setIndeterminate(true);
		bar.setProgressTintList(ColorStateList.valueOf(c.accent));
		bar.setIndeterminateTintList(ColorStateList.valueOf(c.accent));
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		plp.topMargin = c.dp(10);
		plp.setMarginStart(c.dp(4));
		plp.setMarginEnd(c.dp(4));
		card.addView(bar, plp);

		LinearLayout chips = new LinearLayout(ctx);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		clp.topMargin = c.dp(12);
		card.addView(chips, clp);

		ModalProgressPopup p = new ModalProgressPopup(scrim, card, disc, msg, bar, chips, c);
		// The close button cancels while it runs (the outcome then shows), and closes the card once it is done.
		close.setOnClickListener(v -> {
			if ((cancel != null) && (p.bar.getVisibility() == View.VISIBLE)) cancel.run();
			else p.dismiss();
		});
		if ((extra != null) && (extraText != 0)) {
			p.addChip(R.drawable.done, extraText, false, v -> {
				v.setVisibility(View.GONE);
				extra.run();
			});
		}
		if (cancel != null) {
			p.addChip(R.drawable.close_small, R.string.cancel, false, v -> cancel.run()).requestFocus();
		}

		int maxW = c.dp(480);
		int w = Math.min(maxW, Math.max(0, host.getWidth() - c.dp(32)));
		scrim.addView(card, new FrameLayout.LayoutParams((w > 0) ? w : MATCH_PARENT, WRAP_CONTENT,
				Gravity.CENTER));
		host.addView(scrim, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		scrim.setAlpha(0f);
		scrim.animate().alpha(1f).setDuration(220).start();
		card.setTranslationY(c.dp(120));
		card.setAlpha(0f);
		card.animate().translationY(0f).alpha(1f).setDuration(260)
				.setInterpolator(new DecelerateInterpolator(1.6f)).start();
		return p;
	}

	/** A pill button in the card's colours: the accent for the main one, the settings tile's fill otherwise. */
	private TextView addChip(@DrawableRes int iconRes, @androidx.annotation.StringRes int label, boolean primary,
													 View.OnClickListener l) {
		Context ctx = chips.getContext();
		SettingsStyle.Colors c = colors;
		int fg = primary ? c.onAccent : c.textPrimary;
		TextView b = new TextView(ctx);
		b.setText(label);
		b.setTypeface(Typeface.DEFAULT_BOLD);
		b.setTextColor(fg);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		b.setGravity(Gravity.CENTER_VERTICAL);
		b.setMinHeight(c.dp(44));
		b.setMaxLines(1);
		b.setPadding(c.dp(14), 0, c.dp(16), 0);
		android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(ctx, iconRes);
		if (d != null) {
			d = d.mutate();
			d.setBounds(0, 0, c.dp(18), c.dp(18));
			d.setTint(fg);
			b.setCompoundDrawablesRelative(d, null, null, null);
			b.setCompoundDrawablePadding(c.dp(8));
		}
		b.setBackground(pressable(c, primary ? c.accent : c.tileFill, c.dp(22)));
		b.setClickable(true);
		b.setFocusable(true);
		b.setOnClickListener(l);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		lp.setMarginStart(c.dp(8));
		chips.addView(b, lp);
		return b;
	}

	private static android.graphics.drawable.Drawable pressable(SettingsStyle.Colors c, int fill, float radius) {
		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(radius);
		bg.setColor(fill);
		GradientDrawable mask = new GradientDrawable();
		mask.setCornerRadius(radius);
		mask.setColor(0xFF000000);
		return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(c.ripple), bg, mask);
	}

	/** @param total 0 for "don't know yet" (an indeterminate bar) */
	public void setProgress(CharSequence msg, int done, int total) {
		if (dismissed) return;
		text.setText(msg);
		if (total <= 0) {
			bar.setIndeterminate(true);
		} else {
			bar.setIndeterminate(false);
			bar.setMax(total);
			bar.setProgress(done, true);
		}
	}

	/** Swaps the progress for the outcome and a Close button; dismisses itself shortly after. */
	public void showResult(CharSequence msg, boolean ok) {
		showResult(msg, ok, null);
	}

	/**
	 * As {@link #showResult(CharSequence, boolean)}, also listing {@code items} (e.g. the songs a
	 * sync added) in a scrolling area. With a list the card stays until it is closed, since it
	 * takes longer than a few seconds to read.
	 */
	public void showResult(CharSequence msg, boolean ok, @Nullable java.util.List<String> items) {
		if (dismissed) return;
		boolean list = (items != null) && !items.isEmpty();
		if (list) {
			Context ctx = card.getContext();
			LinearLayout col = new LinearLayout(ctx);
			col.setOrientation(LinearLayout.VERTICAL);
			int gap = UiUtils.toIntPx(ctx, 3);
			for (String it : items) {
				TextView row = new TextView(ctx);
				row.setText("♪  " + it);
				row.setTextColor(colors.textPrimary);
				row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
				row.setSingleLine(true);
				row.setEllipsize(android.text.TextUtils.TruncateAt.END);
				row.setPadding(0, gap, 0, gap);
				col.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			}
			android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
			sv.setVerticalScrollBarEnabled(false);
			sv.addView(col, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			int maxH = UiUtils.toIntPx(ctx, 220);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			lp.topMargin = UiUtils.toIntPx(ctx, 10);
			// Tall lists scroll within a fixed window.
			sv.setLayoutParams(lp);
			col.measure(View.MeasureSpec.makeMeasureSpec(UiUtils.toIntPx(ctx, 420), View.MeasureSpec.AT_MOST),
					View.MeasureSpec.UNSPECIFIED);
			if (col.getMeasuredHeight() > maxH) lp.height = maxH;
			card.addView(sv, card.indexOfChild(bar) + 1);
		}
		text.setText(msg);
		text.setTextColor(colors.textPrimary);
		bar.setVisibility(View.GONE);
		if (disc.getBackground() instanceof GradientDrawable g) g.setColor(ok ? OK : WARN);
		chips.removeAllViews();
		addChip(R.drawable.close_small, R.string.close, true, v -> dismiss()).requestFocus();
		if (!list) scrim.postDelayed(this::dismiss, RESULT_MS);
	}

	public void dismiss() {
		if (dismissed) return;
		dismissed = true;
		scrim.animate().cancel();
		card.animate().scaleX(0.95f).scaleY(0.95f).setDuration(150).start();
		scrim.animate().alpha(0f).setDuration(150).setInterpolator(new DecelerateInterpolator())
				.withEndAction(() -> {
					if (scrim.getParent() instanceof ViewGroup g) g.removeView(scrim);
				}).start();
	}
}
