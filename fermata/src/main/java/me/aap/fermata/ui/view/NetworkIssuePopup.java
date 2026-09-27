package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.lang.ref.WeakReference;

import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.ui.UiUtils;

/**
 * A card in the middle of the screen saying streaming stopped because of the network -- the
 * connection dropped or is too slow to keep up -- rather than leaving the driver to guess why the
 * music went quiet. Offers to try again and, when the music queue has tracks stored on the phone,
 * to carry on with those. Goes away by itself as soon as playback picks up again
 * ({@link #dismiss()}), and shows once per stall.
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

		int bg = resolveColor(ctx, android.R.attr.colorBackground, Color.BLACK);
		boolean light = isLight(bg);
		int fg = light ? 0xDE000000 : 0xFFFFFFFF;
		int fg2 = light ? 0x99000000 : 0xB3FFFFFF;

		// Full-screen scrim, so the card reads as a dialog; tapping it dismisses.
		FrameLayout scrim = new FrameLayout(ctx);
		scrim.setBackgroundColor(0x66000000);
		scrim.setClickable(true);
		scrim.setOnClickListener(v -> dismiss());
		scrim.setElevation(UiUtils.toPx(ctx, 24));

		LinearLayout card = new LinearLayout(ctx);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setClickable(true); // Taps on the card itself don't dismiss.
		int pad = UiUtils.toIntPx(ctx, 20);
		card.setPadding(pad, pad, pad, UiUtils.toIntPx(ctx, 8));
		GradientDrawable shape = new GradientDrawable();
		shape.setColor((bg & 0x00FFFFFF) | 0xF5000000);
		shape.setCornerRadius(UiUtils.toPx(ctx, 24));
		card.setBackground(shape);
		card.setElevation(UiUtils.toPx(ctx, 12));

		TextView title = new TextView(ctx);
		title.setText(online ? R.string.network_issue_slow_title : R.string.network_issue_offline_title);
		title.setTextColor(fg);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		card.addView(title, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		TextView msg = new TextView(ctx);
		msg.setText(offlineTracks ? R.string.network_issue_message_offline_tracks :
				R.string.network_issue_message);
		msg.setTextColor(fg2);
		msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		mlp.topMargin = UiUtils.toIntPx(ctx, 8);
		card.addView(msg, mlp);

		LinearLayout buttons = new LinearLayout(ctx);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.setGravity(Gravity.END);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		blp.topMargin = UiUtils.toIntPx(ctx, 12);
		card.addView(buttons, blp);

		addButton(buttons, R.string.network_issue_dismiss, fg, v -> dismiss());
		if (retry != null) {
			addButton(buttons, R.string.network_issue_retry, fg, v -> {
				dismiss();
				retry.run();
			});
		}
		if (offlineTracks) {
			addButton(buttons, R.string.network_issue_play_offline, fg, v -> {
				dismiss();
				MusicPlayer.playOfflineTrack(a);
			});
		}

		int maxW = UiUtils.toIntPx(ctx, 460);
		int w = Math.min(maxW, Math.max(0, host.getWidth() - UiUtils.toIntPx(ctx, 32)));
		FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams((w > 0) ? w : MATCH_PARENT,
				WRAP_CONTENT, Gravity.CENTER);
		scrim.addView(card, clp);
		host.addView(scrim, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		scrim.setAlpha(0f);
		scrim.animate().alpha(1f).setDuration(180).start();
		shown = new WeakReference<>(scrim);
	}

	/** Takes the card down, if it's up -- playback carried on, or the user dismissed it. */
	public static void dismiss() {
		View v = shown.get();
		shown = new WeakReference<>(null);
		if ((v != null) && (v.getParent() instanceof ViewGroup g)) g.removeView(v);
	}

	public static boolean isShown() {
		View v = shown.get();
		return (v != null) && (v.getParent() != null);
	}

	private static void addButton(LinearLayout parent, @StringRes int text, int color,
																View.OnClickListener l) {
		Context ctx = parent.getContext();
		TextView b = new TextView(ctx);
		b.setText(text);
		b.setAllCaps(true);
		b.setTypeface(Typeface.DEFAULT_BOLD);
		b.setTextColor(color);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		b.setGravity(Gravity.CENTER);
		b.setMinHeight(UiUtils.toIntPx(ctx, 48));
		int p = UiUtils.toIntPx(ctx, 14);
		b.setPadding(p, 0, p, 0);
		b.setFocusable(true);
		b.setClickable(true);
		TypedValue tv = new TypedValue();
		if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true) &&
				(tv.resourceId != 0)) {
			b.setBackgroundResource(tv.resourceId);
		}
		b.setOnClickListener(l);
		parent.addView(b, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
	}

	private static boolean isLight(int color) {
		double r = Color.red(color) / 255.0;
		double g = Color.green(color) / 255.0;
		double b = Color.blue(color) / 255.0;
		return (0.299 * r + 0.587 * g + 0.114 * b) > 0.6;
	}

	private static int resolveColor(Context ctx, int attr, int dflt) {
		TypedValue tv = new TypedValue();
		if (!ctx.getTheme().resolveAttribute(attr, tv, true)) return dflt;
		if ((tv.type >= TypedValue.TYPE_FIRST_COLOR_INT) && (tv.type <= TypedValue.TYPE_LAST_COLOR_INT))
			return tv.data;
		if (tv.resourceId != 0) return ctx.getColor(tv.resourceId);
		return dflt;
	}
}
