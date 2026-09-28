package me.aap.fermata.ui.view;

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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;

import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * A banner at the top of the playback screen saying streaming stopped because of the network --
 * the connection dropped or is too slow to keep up -- rather than leaving the driver to guess why
 * it went quiet. The same banner as the data warning ({@code DataUsageAlerts}, same layout, place
 * and slide-in), right under the title bar, or at the very top over fullscreen video. Not a
 * dialog: nothing else is blocked, and it goes away by itself as soon as playback picks up again
 * ({@link #dismiss()}). Its one action plays the music queue's tracks stored on the phone when
 * there are any, else tries again; the X dismisses it.
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
	 * browsing -- the only time the banner is worth interrupting for.
	 *
	 * @param fullscreenVideo whether the caller's player is showing its video fullscreen
	 */
	public static boolean isPlaybackScreen(MainActivityDelegate a, boolean fullscreenVideo) {
		if (fullscreenVideo || a.isVideoMode()) return true;
		ActivityFragment f = a.getActiveFragment();
		return (f != null) && (f.getFragmentId() == R.id.music_addon);
	}

	/**
	 * Shows the banner over {@code a}'s screen (the phone's or the car's), replacing one already up.
	 *
	 * @param retry what "Try again" does
	 */
	public static void show(MainActivityDelegate a, @Nullable Runnable retry) {
		dismiss();
		View main = a.findViewById(R.id.main_activity);
		if (!(main instanceof ConstraintLayout root)) return;

		Context ctx = root.getContext();
		boolean online = isOnline(ctx);
		boolean offlineTracks = MusicPlayer.hasOfflineTrack(a);
		DiagnosticLog.log("NETWORK", online ? "playback stalled (slow network)" :
				"playback stalled (no connection)", "offlineTracks=" + offlineTracks);

		View b = LayoutInflater.from(ctx).inflate(R.layout.data_usage_banner, root, false);
		ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(
				ConstraintLayout.LayoutParams.MATCH_CONSTRAINT, ConstraintLayout.LayoutParams.WRAP_CONTENT);
		lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
		// Under the title bar; at the very top when it's hidden (fullscreen video).
		lp.topToBottom = R.id.tool_bar;
		lp.matchConstraintMaxWidth = UiUtils.toIntPx(ctx, 600);
		int m = UiUtils.toIntPx(ctx, 8);
		lp.setMargins(m, m, m, 0);
		b.setLayoutParams(lp);
		b.setElevation(UiUtils.toIntPx(ctx, 26));

		// Offline: the limit's red, as serious as it gets; slow: the warning's amber.
		int bg = ContextCompat.getColor(ctx, online ? R.color.data_usage_warning : R.color.data_usage_limit);
		int fg = online ? 0xFF1A1A1A : 0xFFFFFFFF;
		b.setBackgroundTintList(ColorStateList.valueOf(bg));
		ImageView icon = b.findViewById(R.id.data_usage_banner_icon);
		icon.setImageResource(online ? R.drawable.network_weak : R.drawable.network_off);
		icon.setImageTintList(ColorStateList.valueOf(bg));
		icon.setBackgroundTintList(ColorStateList.valueOf(fg));
		TextView title = b.findViewById(R.id.data_usage_banner_title);
		title.setTextColor(fg);
		title.setText(online ? R.string.network_issue_slow_title : R.string.network_issue_offline_title);
		TextView text = b.findViewById(R.id.data_usage_banner_text);
		text.setTextColor(fg);
		text.setAlpha(0.85f);
		text.setMaxLines(3);
		text.setText(offlineTracks ? R.string.network_issue_message_offline_tracks :
				R.string.network_issue_message);

		TextView action = b.findViewById(R.id.data_usage_banner_action);
		action.setBackgroundTintList(ColorStateList.valueOf(fg));
		action.setTextColor(bg);
		if (offlineTracks) {
			action.setText(R.string.network_issue_play_offline);
			action.setOnClickListener(v -> {
				dismiss();
				MusicPlayer.playOfflineTrack(a);
			});
		} else if (retry != null) {
			action.setText(R.string.network_issue_retry);
			action.setOnClickListener(v -> {
				dismiss();
				retry.run();
			});
		} else {
			action.setVisibility(View.GONE);
		}

		ImageButton close = b.findViewById(R.id.data_usage_banner_close);
		close.setImageResource(me.aap.utils.R.drawable.close);
		close.setImageTintList(ColorStateList.valueOf(fg));
		close.setContentDescription(ctx.getString(R.string.network_issue_dismiss));
		close.setOnClickListener(v -> dismiss());

		root.addView(b);
		b.setAlpha(0f);
		b.setTranslationY(-UiUtils.toIntPx(ctx, 48));
		b.setScaleX(0.96f);
		b.setScaleY(0.96f);
		b.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420)
				.setInterpolator(new OvershootInterpolator(1.4f)).start();
		shown = new WeakReference<>(b);
	}

	/** Takes the banner down, if it's up -- playback carried on, or the user dismissed it. */
	public static void dismiss() {
		View v = shown.get();
		shown = new WeakReference<>(null);
		if ((v == null) || !(v.getParent() instanceof ViewGroup g)) return;
		v.animate().cancel();
		v.animate().alpha(0f).translationY(-UiUtils.toIntPx(v.getContext(), 32)).setDuration(220)
				.setInterpolator(new DecelerateInterpolator()).withEndAction(() -> g.removeView(v))
				.start();
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
