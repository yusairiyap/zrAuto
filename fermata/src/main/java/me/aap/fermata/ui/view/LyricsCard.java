package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;

import me.aap.fermata.R;
import me.aap.fermata.action.CarNav;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ytcaptions.CaptionsHub;
import me.aap.fermata.ytcaptions.YtCaptions;
import me.aap.utils.log.Log;

/**
 * The lyrics (the playing YouTube video's captions) in a card of their own, in the look of the
 * timer and the playlist picker: the same panel, scrim and slide up, over the whole app. Tapping a
 * line plays from there. Opened by the Lyrics action (All in One, a floating button, a key), by a
 * tap on the lyrics over the Music tab's cover, and by the Music tab's Lyrics chip when held.
 */
public final class LyricsCard implements CaptionsHub.Listener {
	private static LyricsCard open;

	private final MainActivityDelegate a;
	private final Context ctx;
	private final ViewGroup host;
	private final float density;
	private final int primary;
	private final int secondary;
	private final int chipFill;
	private final int ripple;
	private final int accent;
	private FrameLayout overlay;
	private FrameLayout card;
	private TextView subtitle;
	private boolean dismissing;

	private LyricsCard(MainActivityDelegate a, Context ctx, ViewGroup host) {
		this.a = a;
		this.ctx = ctx;
		this.host = host;
		this.density = ctx.getResources().getDisplayMetrics().density;
		this.primary = color(R.attr.musicTextPrimary);
		this.secondary = color(R.attr.musicTextSecondary);
		this.chipFill = color(R.attr.musicChipFill);
		this.ripple = color(R.attr.musicChipRipple);
		this.accent = EffectsUi.accent(ctx);
	}

	/**
	 * The Lyrics action (All in One, a floating button, a key): on the Music tab it shows or hides
	 * the lyrics over the cover, like the tab's own Lyrics chip; anywhere else it opens the card.
	 */
	public static void action(@NonNull MainActivityDelegate a) {
		// Over fullscreen video the same button is the captions switch.
		if (a.isVideoMode()) {
			CaptionsOverlayView.toggleAction(a);
			return;
		}
		var f = a.getActiveFragment();
		if ((f != null) && (f.getFragmentId() == R.id.music_addon)) {
			boolean on = !a.getPrefs().getBooleanPref(MainActivityPrefs.LYRICS_ON);
			if (on && CaptionsHub.get().isUnavailable(a)) {
				TopToast.show(R.drawable.lyrics, R.string.lyrics_unavailable);
				return;
			}
			a.getPrefs().applyBooleanPref(MainActivityPrefs.LYRICS_ON, on);
			TopToast.show(R.drawable.lyrics, on ? R.string.lyrics_on : R.string.lyrics_off);
			return;
		}
		if (CaptionsHub.get().isUnavailable(a)) {
			TopToast.show(R.drawable.lyrics, R.string.lyrics_unavailable);
			return;
		}
		toggle(a);
	}

	/** The card, or no card if it's showing already. */
	public static void toggle(@NonNull MainActivityDelegate a) {
		if (dismissOpen()) return;
		// Posted: this also runs from a menu that is still closing, over the same overlay.
		new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> show(a));
	}

	public static void show(@NonNull MainActivityDelegate a) {
		dismissOpen();
		ViewGroup host = a.findViewById(R.id.main_activity);
		if ((host == null) || (host.getWidth() == 0)) {
			Log.e("No view to show the lyrics over");
			return;
		}
		LyricsCard c = new LyricsCard(a, EffectsUi.palette(a.getContext()), host);
		open = c;
		c.build();
	}

	/** Closes the card if it's open (for the back button); returns whether it was. */
	public static boolean dismissOpen() {
		LyricsCard c = open;
		if ((c == null) || (c.overlay == null) || (c.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		c.dismiss();
		return true;
	}

	private void build() {
		overlay = new FrameLayout(ctx);
		overlay.setClickable(true);
		overlay.setFocusable(false);
		overlay.setElevation(dp(30));
		overlay.setBackgroundColor(0x55000000);
		overlay.setOnClickListener(v -> dismiss());
		// Car mode: long previous closes.
		CarNav.markModalScope(overlay, this::dismiss);
		overlay.setAlpha(0f);

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(16), dp(16), dp(16), dp(12));

		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(8), dp(2), dp(4), dp(2));
		ImageView hi = new ImageView(ctx);
		hi.setImageResource(R.drawable.lyrics);
		hi.setImageTintList(ColorStateList.valueOf(accent));
		header.addView(hi, new LinearLayout.LayoutParams(dp(26), dp(26)));
		TextView title = text(ctx.getString(R.string.lyrics), 18, primary, true);
		title.setPadding(dp(10), 0, 0, 0);
		header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		ImageView close = new ImageView(ctx);
		close.setImageResource(R.drawable.close_small);
		close.setImageTintList(ColorStateList.valueOf(secondary));
		close.setScaleType(ImageView.ScaleType.CENTER);
		close.setBackground(pressable(0, 22));
		close.setClickable(true);
		close.setFocusable(true);
		close.setOnClickListener(v -> dismiss());
		header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
		root.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		subtitle = text("", 13, secondary, false);
		subtitle.setSingleLine(true);
		subtitle.setEllipsize(TextUtils.TruncateAt.END);
		subtitle.setPadding(dp(8), dp(2), dp(8), dp(6));
		root.addView(subtitle, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		LyricsView lyrics = new LyricsView(ctx, a, secondary, primary, false);
		lyrics.setLineListener(i -> CaptionsHub.get().seekTo(i));
		// A failed load is tried again with a tap.
		lyrics.setOnClickListener(v -> {
			if (CaptionsHub.get().getState() == CaptionsHub.State.FAILED) CaptionsHub.get().invalidate();
		});
		root.addView(lyrics, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		// Shows the lyrics over the Music tab's cover too, or not.
		TextView cover = pill(coverLabel());
		cover.setOnClickListener(v -> {
			boolean on = !a.getPrefs().getBooleanPref(MainActivityPrefs.LYRICS_ON);
			a.getPrefs().applyBooleanPref(MainActivityPrefs.LYRICS_ON, on);
			cover.setText(coverLabel());
		});
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(44));
		clp.topMargin = dp(8);
		clp.setMarginStart(dp(4));
		clp.setMarginEnd(dp(4));
		root.addView(cover, clp);

		card = new FrameLayout(ctx);
		card.setClickable(true);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(color(R.attr.musicPanelFill));
		bg.setCornerRadius(dp(28));
		card.setBackground(bg);
		card.setElevation(dp(12));
		card.addView(root, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		FrameLayout.LayoutParams lp = OverlayCard.params(a, host, Integer.MAX_VALUE);
		overlay.addView(card, lp);
		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		card.setTranslationY(dp(120));
		card.setAlpha(0f);
		card.animate().translationY(0f).alpha(1f).setDuration(260)
				.setInterpolator(new DecelerateInterpolator(1.6f)).start();
		overlay.animate().alpha(1f).setDuration(220).start();

		CaptionsHub.get().acquire(a, this);
		onCaptionsState(CaptionsHub.get());
	}

	private String coverLabel() {
		boolean on = a.getPrefs().getBooleanPref(MainActivityPrefs.LYRICS_ON);
		return ctx.getString(on ? R.string.lyrics_cover_hide : R.string.lyrics_cover_show);
	}

	@Override
	public void onCaptionsState(CaptionsHub hub) {
		if (subtitle == null) return;
		StringBuilder sb = new StringBuilder(hub.getTitle());
		YtCaptions.Track t = hub.getTrack();
		if ((t != null) && (hub.getState() == CaptionsHub.State.READY)) {
			if (sb.length() > 0) sb.append(" · ");
			sb.append(t.name);
			if (t.generated) sb.append(" (").append(ctx.getString(R.string.captions_auto_generated)).append(')');
		}
		subtitle.setText(sb);
	}

	private void dismiss() {
		if (dismissing) return;
		dismissing = true;
		CaptionsHub.get().release(this);
		overlay.animate().alpha(0f).setDuration(180).start();
		card.animate().translationY(dp(120)).alpha(0f).setDuration(200)
				.setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(() -> {
					host.removeView(overlay);
					if (open == this) open = null;
				}).start();
	}

	// ---------------------------------------------------------------------------------------------
	// Look

	private TextView text(String s, float sp, int color, boolean bold) {
		TextView t = new TextView(ctx);
		t.setText(s);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private TextView pill(String label) {
		TextView t = text(label, 14, primary, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setBackground(pressable(chipFill, 24));
		t.setClickable(true);
		t.setFocusable(true);
		return t;
	}

	private Drawable pressable(int fill, float radiusDp) {
		StateListDrawable states = new StateListDrawable();
		states.addState(new int[]{android.R.attr.state_focused}, shape(fill, radiusDp, accent, 2));
		states.addState(new int[]{}, shape(fill, radiusDp, 0, 0));
		return new RippleDrawable(ColorStateList.valueOf(ripple), states,
				shape(0xFF000000, radiusDp, 0, 0));
	}

	private GradientDrawable shape(int fill, float radiusDp, int stroke, int strokeDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(fill);
		d.setCornerRadius(dp(radiusDp));
		if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
		return d;
	}

	private int color(@AttrRes int attr) {
		TypedValue tv = new TypedValue();
		ctx.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	private int dp(float v) {
		return Math.round(v * density);
	}
}
