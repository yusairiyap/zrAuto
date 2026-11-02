package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * A sign-in page in a card over the app (a modal window instead of a tab): a plain WebView that
 * shares the app's cookie jar, so the account it signs in is the one the YouTube tab then uses.
 * It closes by itself once the page has come back to {@code doneHost} after leaving the sign-in
 * pages, and calls {@code onDone} so the caller can reload.
 */
public final class WebLoginCard {
	@Nullable
	private static WebLoginCard open;
	private final MainActivityDelegate a;
	private final Context ctx;
	private final ViewGroup host;
	private final String url;
	private final String title;
	private final String doneHost;
	private final String userAgent;
	private final int icon;
	private final Runnable onDone;
	private FrameLayout overlay;
	private FrameLayout card;
	private WebView web;
	private boolean dismissing;
	private boolean leftLogin;

	private WebLoginCard(MainActivityDelegate a, Context palette, ViewGroup host, String title, @DrawableRes int icon,
											 String url, String doneHost, @Nullable String userAgent, Runnable onDone) {
		this.a = a;
		this.ctx = palette;
		this.host = host;
		this.title = title;
		this.icon = icon;
		this.url = url;
		this.doneHost = doneHost;
		this.userAgent = userAgent;
		this.onDone = onDone;
	}

	public static void show(@NonNull MainActivityDelegate a, @NonNull String title, @DrawableRes int icon,
													@NonNull String url, @NonNull String doneHost, @Nullable String userAgent,
													@NonNull Runnable onDone) {
		dismissOpen();
		ViewGroup host = a.findViewById(R.id.main_activity);
		if (host == null) {
			Log.e("No view to show the sign-in window over");
			return;
		}
		WebLoginCard c = new WebLoginCard(a, EffectsUi.palette(a.getContext()), host, title, icon, url, doneHost,
				userAgent, onDone);
		open = c;
		c.build();
	}

	/** Closes the window if it is open (for the back button; a page that can go back goes back); returns whether it was. */
	public static boolean dismissOpen() {
		WebLoginCard c = open;
		if ((c == null) || (c.overlay == null) || (c.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		if ((c.web != null) && c.web.canGoBack()) c.web.goBack();
		else c.dismiss();
		return true;
	}

	private int dp(float v) {
		return Math.round(v * ctx.getResources().getDisplayMetrics().density);
	}

	private int color(int attr) {
		TypedValue tv = new TypedValue();
		ctx.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
	private void build() {
		int primary = color(R.attr.musicTextPrimary);
		int chipFill = color(R.attr.musicChipFill);
		int accent = EffectsUi.accent(ctx);

		overlay = new FrameLayout(ctx);
		overlay.setClickable(true);
		overlay.setElevation(dp(30));
		overlay.setBackgroundColor(0x66000000);
		overlay.setAlpha(0f);
		me.aap.fermata.action.CarNav.markModalScope(overlay, this::dismiss);

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(8), dp(12), dp(8), dp(8));

		LinearLayout header = new LinearLayout(ctx);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(12), 0, dp(4), dp(6));
		ImageView hi = new ImageView(ctx);
		hi.setImageResource(icon);
		hi.setImageTintList(ColorStateList.valueOf(accent));
		header.addView(hi, new LinearLayout.LayoutParams(dp(24), dp(24)));
		TextView t = new TextView(ctx);
		t.setText(title);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setTextColor(primary);
		t.setSingleLine(true);
		t.setPadding(dp(10), 0, 0, 0);
		header.addView(t, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		ImageButton close = new ImageButton(ctx);
		close.setImageResource(me.aap.utils.R.drawable.close);
		close.setImageTintList(ColorStateList.valueOf(primary));
		GradientDrawable cb = new GradientDrawable();
		cb.setShape(GradientDrawable.OVAL);
		cb.setColor(chipFill);
		close.setBackground(cb);
		close.setContentDescription(ctx.getString(R.string.close));
		close.setOnClickListener(v -> dismiss());
		header.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
		root.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		FrameLayout page = new FrameLayout(ctx);
		GradientDrawable pb = new GradientDrawable();
		pb.setColor(0xFFFFFFFF);
		pb.setCornerRadius(dp(18));
		page.setBackground(pb);
		page.setClipToOutline(true);
		page.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

		web = new WebView(ctx);
		WebSettings s = web.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setSupportMultipleWindows(false);
		if (userAgent != null) s.setUserAgentString(userAgent);
		CookieManager cm = CookieManager.getInstance();
		cm.setAcceptCookie(true);
		cm.setAcceptThirdPartyCookies(web, true);
		ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
		bar.setMax(100);
		bar.setProgressTintList(ColorStateList.valueOf(accent));
		web.setWebChromeClient(new WebChromeClient() {
			@Override
			public void onProgressChanged(WebView view, int p) {
				bar.setProgress(p);
				bar.setVisibility((p >= 100) ? View.GONE : View.VISIBLE);
			}
		});
		web.setWebViewClient(new WebViewClient() {
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
				return false;
			}

			@Override
			public void onPageStarted(WebView view, String u, Bitmap favicon) {
				check(u);
			}

			@Override
			public void onPageFinished(WebView view, String u) {
				CookieManager.getInstance().flush();
				check(u);
			}
		});
		page.addView(web, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		page.addView(bar, new FrameLayout.LayoutParams(MATCH_PARENT, dp(3), Gravity.TOP));
		root.addView(page, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		card = new FrameLayout(ctx);
		card.setClickable(true);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(color(R.attr.musicPanelFill));
		bg.setCornerRadius(dp(26));
		card.setBackground(MenuStyle.panel(card, color(R.attr.musicPanelFill), bg.getCornerRadius()));
		card.setElevation(dp(12));
		card.addView(root, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		overlay.addView(card, OverlayCard.params(a, host, dp(900)));
		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		card.setTranslationY(dp(120));
		card.setAlpha(0f);
		card.animate().translationY(0f).alpha(1f).setDuration(260)
				.setInterpolator(new DecelerateInterpolator(1.6f)).start();
		overlay.animate().alpha(1f).setDuration(220).start();
		web.loadUrl(url);
	}

	/** Sign-in pages are on Google's own hosts; coming back to the site after them means it is done. */
	private void check(@Nullable String u) {
		if (u == null) return;
		String h = Uri.parse(u).getHost();
		if (h == null) return;
		if (h.endsWith("accounts.google.com") || h.endsWith("accounts.youtube.com")) {
			leftLogin = true;
			return;
		}
		if (leftLogin && h.endsWith(doneHost) && !dismissing) {
			CookieManager.getInstance().flush();
			dismiss();
			onDone.run();
		}
	}

	private void dismiss() {
		if (dismissing) return;
		dismissing = true;
		overlay.animate().alpha(0f).setDuration(180).start();
		card.animate().translationY(dp(120)).alpha(0f).setDuration(200)
				.setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(this::remove).start();
	}

	private void remove() {
		if (web != null) {
			web.stopLoading();
			web.destroy();
			web = null;
		}
		host.removeView(overlay);
		if (open == this) open = null;
	}
}
