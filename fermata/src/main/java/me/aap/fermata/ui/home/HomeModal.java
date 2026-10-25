package me.aap.fermata.ui.home;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.action.CarNav;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.OverlayCard;

/**
 * A card over the whole app for Home's pickers and settings (the widget list, a widget's options
 * when it is too small to show them itself, the background): a dimmed scrim, a rounded panel with a
 * title and a close button, and the content scrolling inside it. One at a time; Back closes it.
 */
public final class HomeModal {
	@Nullable
	private static HomeModal open;

	private final ViewGroup parent;
	private final FrameLayout overlay;
	private final FrameLayout card;
	private final Runnable onDismiss;
	private boolean dismissing;

	private HomeModal(HomeHost host, ViewGroup parent, CharSequence title, @DrawableRes int icon,
										View body, @Nullable Runnable onDismiss) {
		this.parent = parent;
		this.onDismiss = onDismiss;
		HomePalette p = host.palette();
		android.content.Context ctx = p.ctx;

		overlay = new FrameLayout(ctx);
		overlay.setClickable(true);
		overlay.setElevation(HomeUi.dp(ctx, 30));
		overlay.setBackgroundColor(0x66000000);
		overlay.setOnClickListener(v -> dismiss());
		CarNav.markModalScope(overlay, this::dismiss);
		overlay.setAlpha(0f);

		LinearLayout col = new LinearLayout(ctx);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setPadding(HomeUi.dp(ctx, 16), HomeUi.dp(ctx, 14), HomeUi.dp(ctx, 16), HomeUi.dp(ctx, 16));

		LinearLayout head = new LinearLayout(ctx);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		FrameLayout disc = new FrameLayout(ctx);
		disc.setBackground(HomeUi.oval(p.accent));
		ImageView ic = new ImageView(ctx);
		ic.setImageResource(icon);
		ic.setImageTintList(ColorStateList.valueOf(p.onAccent));
		disc.addView(ic, new FrameLayout.LayoutParams(HomeUi.dp(ctx, 18), HomeUi.dp(ctx, 18), Gravity.CENTER));
		head.addView(disc, new LinearLayout.LayoutParams(HomeUi.dp(ctx, 34), HomeUi.dp(ctx, 34)));
		TextView t = new TextView(ctx);
		t.setText(title);
		t.setTextColor(p.textPrimary);
		t.setTextSize(18);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setSingleLine(true);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		tlp.setMarginStart(HomeUi.dp(ctx, 10));
		head.addView(t, tlp);
		ImageView close = new ImageView(ctx);
		close.setImageResource(me.aap.utils.R.drawable.close);
		close.setImageTintList(ColorStateList.valueOf(p.icon));
		int pad = HomeUi.dp(ctx, 8);
		close.setPadding(pad, pad, pad, pad);
		close.setBackground(HomeUi.pressable(p.chipRipple, 0, HomeUi.dp(ctx, 20)));
		close.setContentDescription(ctx.getString(R.string.close));
		close.setClickable(true);
		close.setFocusable(true);
		close.setOnClickListener(v -> dismiss());
		head.addView(close, new LinearLayout.LayoutParams(HomeUi.dp(ctx, 40), HomeUi.dp(ctx, 40)));
		col.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		ScrollView sv = new ScrollView(ctx);
		sv.setVerticalScrollBarEnabled(false);
		sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
		sv.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		slp.topMargin = HomeUi.dp(ctx, 8);
		col.addView(sv, slp);

		card = new FrameLayout(ctx);
		// A card over the app (on main_activity, outside the tab): glass like the other modal cards when the UI style puts
		// glass on menus and dialogs (MenuStyle#panel), with no shadow under it then.
		card.setBackground(me.aap.fermata.ui.view.MenuStyle.panel(card, (p.panelFill & 0xFFFFFF) | 0xFF000000,
				HomeUi.dp(ctx, 28)));
		card.setClickable(true);
		if (!me.aap.fermata.ui.view.Glass.onMenus()) card.setElevation(HomeUi.dp(ctx, 12));
		card.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		overlay.addView(card);
	}

	/**
	 * Shows {@code body} in a card. It is as tall as its content needs, up to most of the room the
	 * tab has.
	 */
	@Nullable
	public static HomeModal show(HomeHost host, CharSequence title, @DrawableRes int icon, View body,
												@Nullable Runnable onDismiss) {
		dismissOpen();
		MainActivityDelegate a = host.activity();
		ViewGroup parent = a.findViewById(R.id.main_activity);
		if (parent == null) return null;
		HomeModal m = new HomeModal(host, parent, title, icon, body, onDismiss);
		open = m;
		parent.addView(m.overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		// Needs the host's size: laid out first, then the card is sized to its content.
		m.overlay.post(() -> m.place(a));
		m.overlay.animate().alpha(1f).setDuration(200).start();
		return m;
	}

	private void place(MainActivityDelegate a) {
		if (dismissing || (overlay.getParent() == null)) return;
		FrameLayout.LayoutParams lp = OverlayCard.params(a, parent, 100000);
		View col = card.getChildAt(0);
		col.measure(View.MeasureSpec.makeMeasureSpec(
						Math.max(1, lp.width), View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		lp = OverlayCard.params(a, parent, col.getMeasuredHeight());
		card.setLayoutParams(lp);
		card.setTranslationY(HomeUi.dp(card.getContext(), 120));
		card.setAlpha(0f);
		card.animate().translationY(0f).alpha(1f).setDuration(260)
				.setInterpolator(new DecelerateInterpolator(1.6f)).start();
		CarNav.selectFirst(a, overlay);
	}

	void dismiss() {
		if (dismissing) return;
		dismissing = true;
		if (open == this) open = null;
		overlay.animate().alpha(0f).setDuration(180).start();
		card.animate().translationY(HomeUi.dp(card.getContext(), 120)).alpha(0f).setDuration(200)
				.setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
				.withEndAction(() -> parent.removeView(overlay)).start();
		if (onDismiss != null) onDismiss.run();
	}

	/** Closes the card if one is open (for the back button); returns whether it was. */
	public static boolean dismissOpen() {
		HomeModal m = open;
		if (m == null) return false;
		if (m.overlay.getParent() == null) {
			open = null;
			return false;
		}
		m.dismiss();
		return true;
	}

	/** Closes it at once, for whatever comes next. */
	public static void dismissNow() {
		HomeModal m = open;
		if (m == null) return;
		open = null;
		m.dismissing = true;
		m.overlay.animate().cancel();
		m.card.animate().cancel();
		m.parent.removeView(m.overlay);
	}
}
