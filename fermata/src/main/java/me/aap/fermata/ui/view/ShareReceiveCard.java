package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import me.aap.fermata.R;
import me.aap.fermata.share.ShareManager;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.UiUtils;

/**
 * The card at the top of the app while something shared from another phone is coming in: what
 * and from whom, a progress bar, and Cancel. Same look as {@link TopToast}; it takes itself down
 * when the transfer is over (swiping it away only hides it, the transfer goes on and the
 * notification keeps showing it).
 */
public final class ShareReceiveCard implements ShareManager.Listener {
	private static final int BG = 0xFF263238;
	private static final int FG = 0xFFFFFFFF;
	private final ShareManager.Transfer transfer;
	private final LinearLayout card;
	private final TextView text;
	private final ProgressBar bar;
	private final TextView cancel;
	private boolean closed;

	private ShareReceiveCard(Context ctx, ShareManager.Transfer t) {
		transfer = t;
		card = new LinearLayout(ctx);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setBackgroundResource(R.drawable.data_usage_banner_bg);
		card.setBackgroundTintList(ColorStateList.valueOf(BG));
		card.setClickable(true);
		card.setElevation(UiUtils.toIntPx(ctx, 8));
		int pad = UiUtils.toIntPx(ctx, 12);
		card.setPadding(pad, pad, pad, UiUtils.toIntPx(ctx, 8));

		LinearLayout top = new LinearLayout(ctx);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.setGravity(Gravity.CENTER_VERTICAL);
		card.addView(top, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		ImageView iv = new ImageView(ctx);
		iv.setImageResource(R.drawable.share);
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

		TextView title = new TextView(ctx);
		title.setText(ctx.getString(R.string.share_notif_receiving, t.offer.title, t.peerName));
		title.setTextColor(FG);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		title.setSingleLine(true);
		title.setEllipsize(android.text.TextUtils.TruncateAt.END);
		texts.addView(title, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		text = new TextView(ctx);
		text.setTextColor(FG);
		text.setAlpha(0.85f);
		text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		text.setSingleLine(true);
		text.setEllipsize(android.text.TextUtils.TruncateAt.END);
		texts.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		cancel = new TextView(ctx);
		cancel.setText(R.string.cancel);
		cancel.setGravity(Gravity.CENTER);
		cancel.setSingleLine(true);
		cancel.setFocusable(true);
		cancel.setClickable(true);
		cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		cancel.setTypeface(Typeface.DEFAULT_BOLD);
		cancel.setBackgroundResource(R.drawable.data_usage_pill_bg);
		cancel.setBackgroundTintList(ColorStateList.valueOf(FG));
		cancel.setTextColor(BG);
		int h = UiUtils.toIntPx(ctx, 20);
		cancel.setPadding(h, 0, h, 0);
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(WRAP_CONTENT, UiUtils.toIntPx(ctx, 36));
		clp.setMarginStart(UiUtils.toIntPx(ctx, 8));
		top.addView(cancel, clp);

		bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
		bar.setMax(1000);
		bar.setProgressTintList(ColorStateList.valueOf(FG));
		bar.setIndeterminateTintList(ColorStateList.valueOf(FG));
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, UiUtils.toIntPx(ctx, 6));
		blp.topMargin = UiUtils.toIntPx(ctx, 10);
		blp.bottomMargin = UiUtils.toIntPx(ctx, 2);
		card.addView(bar, blp);

		cancel.setOnClickListener(v -> transfer.cancel());
		update();
	}

	/** Shows the card for an incoming transfer, if there is a screen to show it on. */
	public static void show(MainActivityDelegate a, ShareManager.Transfer t) {
		ShareReceiveCard c = new ShareReceiveCard(a.getContext(), t);
		ShareManager m = ShareManager.get();
		boolean shown = TopPopup.show(a, c.card, () -> {
			c.closed = true;
			m.removeListener(c);
		});
		if (shown) m.addListener(c);
	}

	@Override
	public void onTransfersChanged() {
		if (closed) return;
		if (!transfer.isActive()) {
			closed = true;
			ShareManager.get().removeListener(this);
			TopPopup.dismiss(card);
			return;
		}
		update();
	}

	private void update() {
		Context ctx = card.getContext();
		long total = transfer.total;
		long done = transfer.done;
		boolean saving = transfer.state == ShareManager.State.SAVING;

		if (saving) {
			text.setText(R.string.share_saving);
			bar.setIndeterminate(true);
		} else if (total > 0) {
			bar.setIndeterminate(false);
			bar.setProgress((int) Math.min(1000, done * 1000 / total));
			text.setText((done * 100 / total) + "%  ·  " + Formatter.formatShortFileSize(ctx, done) + " / " +
					Formatter.formatShortFileSize(ctx, total));
		} else {
			bar.setIndeterminate(true);
			text.setText(R.string.share_working);
		}

		cancel.setVisibility(saving ? android.view.View.GONE : android.view.View.VISIBLE);
	}
}
