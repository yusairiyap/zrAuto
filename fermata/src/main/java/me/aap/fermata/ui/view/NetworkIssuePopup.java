package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;

import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * A translucent card over the playback screen saying streaming stopped because of the network --
 * the connection dropped or is too slow to keep up -- rather than leaving the driver to guess why
 * it went quiet. Not a dialog: nothing else is blocked, the rest of the screen stays usable, and it
 * goes away by itself as soon as playback picks up again ({@link #dismiss()}). Its actions are
 * chips: try again, dismiss and, when the music queue has tracks stored on the phone, carry on
 * with those.
 * <p>
 * Only over playback -- see {@link #isPlaybackScreen} -- never while the user is browsing.
 */
public final class NetworkIssuePopup {
	private static WeakReference<View> shown = new WeakReference<>(null);

	private NetworkIssuePopup() {
	}

	/** Whether the phone has a network connection that reaches the internet at all. */
	public static boolean isOnline(Context ctx) {
		try {
			ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
			if (cm == null) return true;
			Network n = cm.getActiveNetwork();
			NetworkCapabilities c = (n == null) ? null : cm.getNetworkCapabilities(n);
			return (c != null) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
		} catch (Exception ex) {
			return true;
		}
	}

	/**
	 * Whether what's on screen is playback (fullscreen video, or the Music tab) rather than
	 * browsing -- the only time the card is worth interrupting for.
	 *
	 * @param fullscreenVideo whether the caller's player is showing its video fullscreen
	 */
	public static boolean isPlaybackScreen(MainActivityDelegate a, boolean fullscreenVideo) {
		if (fullscreenVideo || a.isVideoMode()) return true;
		ActivityFragment f = a.getActiveFragment();
		return (f != null) && (f.getFragmentId() == R.id.music_addon);
	}

	/**
	 * Shows the card over {@code a}'s screen (the phone's or the car's), replacing one already up.
	 *
	 * @param retry what "Try again" does
	 */
	public static void show(MainActivityDelegate a, @Nullable Runnable retry) {
		dismiss();
		View body = a.getBody();
		if (body == null) return;
		View root = body.getRootView();
		View content = root.findViewById(android.R.id.content);
		FrameLayout host = (content instanceof FrameLayout f) ? f :
				(root instanceof FrameLayout f) ? f : null;
		if (host == null) return;

		Context ctx = host.getContext();
		boolean online = isOnline(ctx);
		boolean offlineTracks = MusicPlayer.hasOfflineTrack(a);
		DiagnosticLog.log("NETWORK", online ? "playback stalled (slow network)" :
				"playback stalled (no connection)", "offlineTracks=" + offlineTracks);

		// Always light text on a dark glass card: it sits over video, whatever the theme.
		int fg = 0xFFFFFFFF;
		int fg2 = 0xB3FFFFFF;

		LinearLayout card = new LinearLayout(ctx);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setClickable(true); // Taps on the card don't reach the video underneath.
		int pad = UiUtils.toIntPx(ctx, 18);
		card.setPadding(pad, pad, pad, UiUtils.toIntPx(ctx, 14));
		GradientDrawable shape = new GradientDrawable();
		shape.setColor(0xB3141418);
		shape.setCornerRadius(UiUtils.toPx(ctx, 22));
		shape.setStroke(UiUtils.toIntPx(ctx, 1), 0x33FFFFFF);
		card.setBackground(shape);
		card.setElevation(UiUtils.toPx(ctx, 32));

		LinearLayout head = new LinearLayout(ctx);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		card.addView(head, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// The connection's state, at a glance: a weak signal, or none at all.
		ImageView icon = new ImageView(ctx);
		icon.setImageResource(online ? R.drawable.network_weak : R.drawable.network_off);
		icon.setImageTintList(ColorStateList.valueOf(online ? 0xFFFFC857 : 0xFFFF6B6B));
		int is = UiUtils.toIntPx(ctx, 30);
		LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(is, is);
		ilp.setMarginEnd(UiUtils.toIntPx(ctx, 14));
		head.addView(icon, ilp);

		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		head.addView(texts, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		TextView title = new TextView(ctx);
		title.setText(online ? R.string.network_issue_slow_title : R.string.network_issue_offline_title);
		title.setTextColor(fg);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		texts.addView(title, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		TextView msg = new TextView(ctx);
		msg.setText(offlineTracks ? R.string.network_issue_message_offline_tracks :
				R.string.network_issue_message);
		msg.setTextColor(fg2);
		msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		mlp.topMargin = UiUtils.toIntPx(ctx, 2);
		texts.addView(msg, mlp);

		LinearLayout chips = new LinearLayout(ctx);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		blp.topMargin = UiUtils.toIntPx(ctx, 14);
		card.addView(chips, blp);

		if (offlineTracks) {
			addChip(chips, R.drawable.music, R.string.network_issue_play_offline, fg, true, v -> {
				dismiss();
				MusicPlayer.playOfflineTrack(a);
			});
		}
		if (retry != null) {
			addChip(chips, R.drawable.refresh, R.string.network_issue_retry, fg, !offlineTracks, v -> {
				dismiss();
				retry.run();
			});
		}
		addChip(chips, R.drawable.close_small, R.string.network_issue_dismiss, fg, false,
				v -> dismiss());

		int maxW = UiUtils.toIntPx(ctx, 520);
		int w = Math.min(maxW, Math.max(0, host.getWidth() - UiUtils.toIntPx(ctx, 32)));
		// Not a dialog: only the card itself takes touches; the rest of the screen stays live.
		FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams((w > 0) ? w : MATCH_PARENT,
				WRAP_CONTENT, Gravity.CENTER);
		host.addView(card, clp);

		card.setAlpha(0f);
		card.setScaleX(0.94f);
		card.setScaleY(0.94f);
		card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
				.setInterpolator(new DecelerateInterpolator()).start();
		shown = new WeakReference<>(card);
	}

	/** Takes the card down, if it's up -- playback carried on, or the user dismissed it. */
	public static void dismiss() {
		View v = shown.get();
		shown = new WeakReference<>(null);
		if ((v == null) || !(v.getParent() instanceof ViewGroup g)) return;
		v.animate().cancel();
		v.animate().alpha(0f).setDuration(150).withEndAction(() -> g.removeView(v)).start();
	}

	public static boolean isShown() {
		View v = shown.get();
		return (v != null) && (v.getParent() != null);
	}

	/** A pill-shaped chip with an icon; {@code primary} is filled, the others outlined. */
	static TextView addChip(LinearLayout parent, @DrawableRes int icon, @StringRes int text,
															int color, boolean primary, View.OnClickListener l) {
		Context ctx = parent.getContext();
		TextView c = new TextView(ctx);
		c.setText(text);
		c.setTypeface(Typeface.DEFAULT_BOLD);
		c.setTextColor(color);
		c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		c.setGravity(Gravity.CENTER_VERTICAL);
		c.setMinHeight(UiUtils.toIntPx(ctx, 44));
		c.setMaxLines(1);
		int hp = UiUtils.toIntPx(ctx, 14);
		c.setPadding(UiUtils.toIntPx(ctx, 12), 0, hp, 0);
		Drawable d = ContextCompat.getDrawable(ctx, icon);
		if (d != null) {
			d = d.mutate();
			int s = UiUtils.toIntPx(ctx, 18);
			d.setBounds(0, 0, s, s);
			d.setTint(color);
			c.setCompoundDrawablesRelative(d, null, null, null);
			c.setCompoundDrawablePadding(UiUtils.toIntPx(ctx, 8));
		}
		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(UiUtils.toPx(ctx, 22));
		if (primary) {
			bg.setColor(0x40FFFFFF);
		} else {
			bg.setColor(0x00000000);
			bg.setStroke(UiUtils.toIntPx(ctx, 1), 0x66FFFFFF);
		}
		c.setBackground(bg);
		c.setForeground(selectable(ctx));
		c.setFocusable(true);
		c.setClickable(true);
		c.setOnClickListener(l);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		lp.setMarginStart(UiUtils.toIntPx(ctx, 8));
		parent.addView(c, lp);
		return c;
	}

	@Nullable
	private static Drawable selectable(Context ctx) {
		TypedValue tv = new TypedValue();
		if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true) &&
				(tv.resourceId != 0)) {
			return ContextCompat.getDrawable(ctx, tv.resourceId);
		}
		return null;
	}
}
