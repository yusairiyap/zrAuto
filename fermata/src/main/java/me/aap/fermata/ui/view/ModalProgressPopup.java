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
import android.view.animation.OvershootInterpolator;
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
 * card shows the outcome with Close, and goes away by itself after a few seconds. Same look as
 * {@link NetworkIssuePopup}, on the phone's and Android Auto's screen alike.
 */
public final class ModalProgressPopup {
	private static final long RESULT_MS = 4000;
	private final View scrim;
	private final LinearLayout card;
	private final TextView text;
	private final ProgressBar bar;
	private final LinearLayout chips;
	private final ImageView icon;
	private boolean dismissed;

	private ModalProgressPopup(View scrim, LinearLayout card, ImageView icon, TextView text,
														 ProgressBar bar, LinearLayout chips) {
		this.scrim = scrim;
		this.card = card;
		this.icon = icon;
		this.text = text;
		this.bar = bar;
		this.chips = chips;
	}

	/** @return null if there's no screen to show it on */
	@Nullable
	public static ModalProgressPopup show(MainActivityDelegate a, @DrawableRes int iconRes,
																				CharSequence title, @Nullable Runnable cancel) {
		View body = a.getBody();
		if (body == null) return null;
		View root = body.getRootView();
		View content = root.findViewById(android.R.id.content);
		FrameLayout host = (content instanceof FrameLayout f) ? f :
				(root instanceof FrameLayout f) ? f : null;
		if (host == null) return null;

		Context ctx = host.getContext();
		int fg = 0xFFFFFFFF;
		int fg2 = 0xB3FFFFFF;

		FrameLayout scrim = new FrameLayout(ctx);
		scrim.setBackgroundColor(0x99000000);
		scrim.setClickable(true); // Modal: nothing underneath reacts meanwhile.
		scrim.setFocusable(true);
		scrim.setElevation(UiUtils.toPx(ctx, 40));

		LinearLayout card = new LinearLayout(ctx);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setClickable(true);
		int pad = UiUtils.toIntPx(ctx, 20);
		card.setPadding(pad, pad, pad, UiUtils.toIntPx(ctx, 14));
		GradientDrawable shape = new GradientDrawable();
		shape.setColor(0xF0141418);
		shape.setCornerRadius(UiUtils.toPx(ctx, 24));
		shape.setStroke(UiUtils.toIntPx(ctx, 1), 0x33FFFFFF);
		card.setBackground(shape);

		LinearLayout head = new LinearLayout(ctx);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		card.addView(head, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		ImageView icon = new ImageView(ctx);
		icon.setImageResource(iconRes);
		icon.setImageTintList(ColorStateList.valueOf(0xFF1ED760));
		int is = UiUtils.toIntPx(ctx, 28);
		LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(is, is);
		ilp.setMarginEnd(UiUtils.toIntPx(ctx, 14));
		head.addView(icon, ilp);

		TextView t = new TextView(ctx);
		t.setText(title);
		t.setTextColor(fg);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		head.addView(t, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		TextView msg = new TextView(ctx);
		msg.setTextColor(fg2);
		msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		msg.setMaxLines(3);
		LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		mlp.topMargin = UiUtils.toIntPx(ctx, 12);
		card.addView(msg, mlp);

		ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
		bar.setIndeterminate(true);
		bar.setProgressTintList(ColorStateList.valueOf(0xFF1ED760));
		bar.setIndeterminateTintList(ColorStateList.valueOf(0xFF1ED760));
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		plp.topMargin = UiUtils.toIntPx(ctx, 10);
		card.addView(bar, plp);

		LinearLayout chips = new LinearLayout(ctx);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		clp.topMargin = UiUtils.toIntPx(ctx, 12);
		card.addView(chips, clp);

		ModalProgressPopup p = new ModalProgressPopup(scrim, card, icon, msg, bar, chips);
		if (cancel != null) {
			NetworkIssuePopup.addChip(chips, R.drawable.close_small, R.string.cancel, fg, false,
					v -> cancel.run()).requestFocus();
		}

		int maxW = UiUtils.toIntPx(ctx, 480);
		int w = Math.min(maxW, Math.max(0, host.getWidth() - UiUtils.toIntPx(ctx, 32)));
		scrim.addView(card, new FrameLayout.LayoutParams((w > 0) ? w : MATCH_PARENT, WRAP_CONTENT,
				Gravity.CENTER));
		host.addView(scrim, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		scrim.setAlpha(0f);
		scrim.animate().alpha(1f).setDuration(180).start();
		card.setScaleX(0.9f);
		card.setScaleY(0.9f);
		card.animate().scaleX(1f).scaleY(1f).setDuration(320)
				.setInterpolator(new OvershootInterpolator(1.4f)).start();
		return p;
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
		if (dismissed) return;
		text.setText(msg);
		text.setTextColor(0xFFFFFFFF);
		bar.setVisibility(View.GONE);
		icon.setImageTintList(ColorStateList.valueOf(ok ? 0xFF1ED760 : 0xFFFFC857));
		chips.removeAllViews();
		NetworkIssuePopup.addChip(chips, R.drawable.close_small, R.string.close, 0xFFFFFFFF, true,
				v -> dismiss()).requestFocus();
		scrim.postDelayed(this::dismiss, RESULT_MS);
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
