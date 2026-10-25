package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.UiUtils;

/**
 * A yes/no question as a {@link TopPopup} card, in the look of {@link TopToast}: it stays until
 * answered (swiping it away counts as "no"). For a question that comes up in passing, such as
 * adding a video that a playlist already has, where a dialog in the middle of the screen is too
 * much; works on the car screen too.
 */
public final class TopConfirm {
	private static final int BG = 0xFF263238;
	private static final int FG = 0xFFFFFFFF;

	private TopConfirm() {
	}

	/**
	 * @param onYes run when "yes" is tapped
	 * @param onNo  run when "no" is tapped or the card is swiped away; may be null
	 */
	@Nullable
	public static android.view.View show(@NonNull MainActivityDelegate a, @DrawableRes int icon,
													@NonNull CharSequence title, @NonNull CharSequence text,
													@NonNull CharSequence yes, @NonNull CharSequence no,
													@NonNull Runnable onYes, @Nullable Runnable onNo) {
		Context ctx = a.getContext();
		boolean[] answered = {false};
		LinearLayout card = new LinearLayout(ctx);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setBackgroundResource(R.drawable.data_usage_banner_bg);
		card.setClickable(true);
		card.setElevation(UiUtils.toIntPx(ctx, 8));
		TopPopup.styleCard(card, BG);
		int pad = UiUtils.toIntPx(ctx, 12);
		card.setPadding(pad, pad, pad, UiUtils.toIntPx(ctx, 8));

		LinearLayout top = new LinearLayout(ctx);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.setGravity(Gravity.CENTER_VERTICAL);
		card.addView(top, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		ImageView iv = new ImageView(ctx);
		iv.setImageResource(icon);
		iv.setBackgroundResource(R.drawable.data_usage_dot);
		iv.setImageTintList(ColorStateList.valueOf(BG));
		iv.setBackgroundTintList(ColorStateList.valueOf(FG));
		int p = UiUtils.toIntPx(ctx, 7);
		iv.setPadding(p, p, p, p);
		int is = UiUtils.toIntPx(ctx, 36);
		top.addView(iv, new LinearLayout.LayoutParams(is, is));

		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		tlp.setMarginStart(UiUtils.toIntPx(ctx, 12));
		top.addView(texts, tlp);

		TextView t = new TextView(ctx);
		t.setText(title);
		t.setTextColor(FG);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setSingleLine(true);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		texts.addView(t, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		TextView tx = new TextView(ctx);
		tx.setText(text);
		tx.setTextColor(FG);
		tx.setAlpha(0.85f);
		tx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		tx.setMaxLines(3);
		tx.setEllipsize(android.text.TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		xlp.topMargin = UiUtils.toIntPx(ctx, 1);
		texts.addView(tx, xlp);

		LinearLayout buttons = new LinearLayout(ctx);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.setGravity(Gravity.END);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		blp.topMargin = UiUtils.toIntPx(ctx, 8);
		card.addView(buttons, blp);

		TextView noB = button(ctx, no, false);
		TextView yesB = button(ctx, yes, true);
		buttons.addView(noB);
		buttons.addView(yesB);

		noB.setOnClickListener(v -> {
			if (answered[0]) return;
			answered[0] = true;
			TopPopup.dismiss(card);
			if (onNo != null) onNo.run();
		});
		yesB.setOnClickListener(v -> {
			if (answered[0]) return;
			answered[0] = true;
			TopPopup.dismiss(card);
			onYes.run();
		});

		boolean shown = TopPopup.show(a, card, () -> {
			if (answered[0]) return;
			answered[0] = true;
			if (onNo != null) onNo.run();
		});
		if (!shown) {
			// No screen to put it on: the safe answer.
			answered[0] = true;
			if (onNo != null) onNo.run();
			return null;
		}
		yesB.requestFocus();
		return card;
	}

	private static TextView button(Context ctx, CharSequence label, boolean primary) {
		TextView b = new TextView(ctx);
		b.setText(label);
		b.setGravity(Gravity.CENTER);
		b.setSingleLine(true);
		b.setFocusable(true);
		b.setClickable(true);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		b.setTypeface(Typeface.DEFAULT_BOLD);
		b.setBackgroundResource(R.drawable.data_usage_pill_bg);
		b.setBackgroundTintList(ColorStateList.valueOf(primary ? FG : 0x33FFFFFF));
		b.setTextColor(primary ? BG : FG);
		int h = UiUtils.toIntPx(ctx, 14);
		b.setPadding(h + h / 2, 0, h + h / 2, 0);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, UiUtils.toIntPx(ctx, 36));
		lp.setMarginStart(UiUtils.toIntPx(ctx, 8));
		b.setLayoutParams(lp);
		return b;
	}
}
