package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import me.aap.fermata.R;
import me.aap.fermata.action.CarNav;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;

/**
 * A panel of the comments' kind for anything else that is used while a video is fullscreen (the audio effects, the captions
 * settings): over fullscreen video on a car screen or a landscape tablet it is a column on the right and the video shrinks
 * beside it (a transform, see {@link VideoView#setSplitShare}, never a resize); elsewhere it is a card in the middle over a
 * scrim. One panel is open at a time, and the comments column makes way for it. The width is the comments' width setting.
 * Its rows are focusable and clickable so the steering wheel's Next / Previous walk them and a long Previous closes it.
 */
public final class SidePanel {
	/** What goes in the panel: made when it opens. */
	public interface Body {
		View create(SidePanel panel);

		default void onClosed() {
		}
	}

	@Nullable
	private static SidePanel open;

	private final MainActivityDelegate a;
	public final Context ctx;
	private final ViewGroup host;
	private final String id;
	private final Body body;
	private final boolean docked;
	private final float density;
	public final int primary;
	public final int secondary;
	public final int chipFill;
	public final int ripple;
	public final int accent;
	public final int onAccent;
	private FrameLayout overlay;
	private FrameLayout card;
	private LinearLayout inner;
	private FrameLayout.LayoutParams cardLp;
	private int dockW;
	private int dockEnd;
	private float progress;
	@Nullable
	private ValueAnimator anim;
	@Nullable
	private VideoView splitView;
	private boolean dismissing;
	@Nullable
	private Object backGuard;

	private final me.aap.utils.pref.PreferenceStore.Pref<me.aap.utils.function.IntSupplier> widthPref;
	private View strip;
	private float stripX;
	private int stripW;

	private SidePanel(MainActivityDelegate a, ViewGroup host, String id, Body body, boolean docked,
								me.aap.utils.pref.PreferenceStore.Pref<me.aap.utils.function.IntSupplier> widthPref) {
		this.widthPref = widthPref;
		this.a = a;
		this.ctx = EffectsUi.palette(a.getContext());
		this.host = host;
		this.id = id;
		this.body = body;
		this.docked = docked;
		this.density = ctx.getResources().getDisplayMetrics().density;
		this.primary = color(R.attr.musicTextPrimary);
		this.secondary = color(R.attr.musicTextSecondary);
		this.chipFill = color(R.attr.musicChipFill);
		this.ripple = color(R.attr.musicChipRipple);
		this.accent = EffectsUi.accent(ctx);
		this.onAccent = EffectsUi.onAccent(accent);
	}

	/** Whether a panel can go beside the video now (fullscreen video, with room for both). */
	public static boolean isVideoUp(MainActivityDelegate a) {
		return a.isVideoMode() && (a.getActiveVideoView() != null);
	}

	/** Opens the panel {@code id}, or closes it when it is the one that is open. */
	public static void toggle(MainActivityDelegate a, String id, @DrawableRes int icon, @StringRes int title,
														me.aap.utils.pref.PreferenceStore.Pref<me.aap.utils.function.IntSupplier> widthPref, Body body) {
		SidePanel o = open;
		if ((o != null) && o.id.equals(id) && dismissOpen()) return;
		dismissOpen();
		YoutubeCommentsView.dismissOpen();
		ViewGroup host = a.findViewById(R.id.main_activity);
		if ((host == null) || (host.getWidth() == 0)) return;
		SidePanel p = new SidePanel(a, host, id, body, shouldDock(a, host), widthPref);
		open = p;
		p.build(icon, title);
		p.armBackGuard();
	}

	/** Whether the panel {@code id} is the one open. */
	public static boolean isOpen(String id) {
		SidePanel p = open;
		return isOpen() && (p != null) && p.id.equals(id) && !p.dismissing;
	}

	public static boolean isOpen() {
		SidePanel p = open;
		return (p != null) && (p.overlay != null) && (p.overlay.getParent() != null);
	}

	/** Closes the panel if one is open (Back); returns whether it was. */
	public static boolean dismissOpen() {
		SidePanel p = open;
		if ((p == null) || (p.overlay == null) || (p.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		if (p.dismissing) p.dismissFast();
		else p.dismiss();
		return true;
	}

	/** Fullscreen video is left or the window shrinks to a picture: gone at once. */
	public static void onVideoLeft() {
		SidePanel p = open;
		if ((p != null) && p.docked) p.dismissFast();
	}

	public static void onPictureInPicture() {
		SidePanel p = open;
		if (p != null) p.dismissFast();
	}

	private static boolean shouldDock(MainActivityDelegate a, ViewGroup host) {
		if (!isVideoUp(a)) return false;
		if (!a.getPrefs().getBooleanPref(MainActivityPrefs.COMMENTS_SPLIT)) return false;
		if (a.isCarActivity()) return true;
		float d = host.getResources().getDisplayMetrics().density;
		int w = host.getWidth();
		int h = host.getHeight();
		return (w > h) && (Math.min(w, h) >= Math.round(480 * d));
	}

	// ---------------------------------------------------------------------------------------------

	private void build(@DrawableRes int icon, @StringRes int title) {
		overlay = new FrameLayout(ctx);
		overlay.setFocusable(false);
		if (docked) {
			// No scrim: whatever is not the column is the video, which keeps its taps. Below the control panel and the
			// floating buttons (10dp), above the video (5dp), as the comments column.
			overlay.setClickable(false);
			overlay.setElevation(dp(7));
		} else {
			overlay.setClickable(true);
			overlay.setElevation(dp(30));
			overlay.setBackgroundColor(0x55000000);
			overlay.setOnClickListener(v -> dismiss());
		}
		CarNav.markModalScope(overlay, this::dismiss);
		overlay.setAlpha(0f);

		card = new FrameLayout(ctx);
		inner = new LinearLayout(ctx);
		inner.setOrientation(LinearLayout.VERTICAL);
		inner.setPadding(dp(14), dp(12), dp(10), dp(8));
		card.addView(inner, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		card.setClickable(true);
		float radius = dp(docked ? 22 : 28);
		card.setBackground(MenuStyle.panel(card, MenuStyle.panelColor(ctx), radius));
		card.setElevation(dp(docked ? 4 : 12));

		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(8), dp(2), dp(4), dp(6));
		ImageView hi = new ImageView(ctx);
		hi.setImageResource(icon);
		hi.setImageTintList(ColorStateList.valueOf(accent));
		header.addView(hi, new LinearLayout.LayoutParams(dp(26), dp(26)));
		TextView t = text(ctx.getString(title), 18, primary, true);
		t.setPadding(dp(10), 0, 0, 0);
		t.setSingleLine(true);
		header.addView(t, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		ImageView close = new ImageView(ctx);
		close.setImageResource(R.drawable.close_small);
		close.setImageTintList(ColorStateList.valueOf(secondary));
		close.setScaleType(ImageView.ScaleType.CENTER);
		close.setBackground(pressable(0, 22));
		close.setClickable(true);
		close.setFocusable(true);
		close.setOnClickListener(v -> dismiss());
		header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
		inner.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		View content = body.create(this);
		inner.addView(content, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		overlay.addView(card, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		host.addView(overlay, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		layoutCard();
		if (docked) addStrip();
		if (docked) openDock();
		else {
			overlay.animate().alpha(1f).setDuration(180).start();
			card.setTranslationY(dp(60));
			card.animate().translationY(0f).setDuration(240).setInterpolator(new DecelerateInterpolator(1.6f)).start();
		}
	}

	private void layoutCard() {
		int[] in = systemInsets();
		int pct = Math.max(20, Math.min(60, a.getPrefs().getIntPref(widthPref)));
		if (docked) {
			dockEnd = in[2] + dp(10);
			int top = in[1] + dp(10);
			int bottom = in[3] + dp(10);
			int max = Math.round(host.getWidth() * 0.6f);
			dockW = Math.max(Math.min(dp(260), max), Math.min(Math.round(host.getWidth() * pct / 100f), max));
			cardLp = new FrameLayout.LayoutParams(dockW, Math.max(dp(160), host.getHeight() - top - bottom),
					Gravity.END | Gravity.TOP);
			cardLp.topMargin = top;
			cardLp.bottomMargin = bottom;
			cardLp.setMarginEnd(dockEnd);
		} else {
			int w = Math.min(host.getWidth() - dp(32), dp(520));
			int h = Math.min(host.getHeight() - in[1] - in[3] - dp(48), dp(640));
			cardLp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
		}
		card.setLayoutParams(cardLp);
	}

	/** The strip on the column's left edge that drags it wider or narrower, as the comments' column has; the width is kept for the next time. */
	private void addStrip() {
		strip = new FrameLayout(ctx);
		View pill = new View(ctx);
		GradientDrawable pd = new GradientDrawable();
		pd.setColor((secondary & 0x00FFFFFF) | 0x77000000);
		pd.setCornerRadius(dp(2));
		pill.setBackground(pd);
		FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(4), dp(48), Gravity.START | Gravity.CENTER_VERTICAL);
		plp.setMarginStart(dp(6));
		((FrameLayout) strip).addView(pill, plp);
		strip.setContentDescription(ctx.getString(R.string.yt_comments_resize));
		strip.setOnTouchListener((v, e) -> {
			switch (e.getActionMasked()) {
				case android.view.MotionEvent.ACTION_DOWN -> {
					stripX = e.getRawX();
					stripW = dockW;
					v.getParent().requestDisallowInterceptTouchEvent(true);
					pd.setColor(accent);
					return true;
				}
				case android.view.MotionEvent.ACTION_MOVE -> {
					int max = Math.round(host.getWidth() * 0.6f);
					setDockWidth(Math.max(Math.min(dp(260), max), Math.min(stripW - Math.round(e.getRawX() - stripX), max)));
					return true;
				}
				case android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
					pd.setColor((secondary & 0x00FFFFFF) | 0x77000000);
					int pct = Math.round(dockW * 100f / Math.max(1, host.getWidth()));
					a.getPrefs().applyIntPref(widthPref, Math.max(20, Math.min(60, pct)));
					return true;
				}
				default -> {
					return false;
				}
			}
		});
		// Inside the card, on its left edge, where the comments column has it.
		card.addView(strip, new FrameLayout.LayoutParams(dp(26), MATCH_PARENT, Gravity.START));
	}

	private void setDockWidth(int w) {
		if ((w == dockW) || (cardLp == null)) return;
		dockW = w;
		cardLp.width = w;
		card.setLayoutParams(cardLp);
		VideoView vv = splitView;
		int hostW = host.getWidth();
		if ((vv != null) && (hostW > 0)) vv.setSplitShare(((dockW + dockEnd + dp(10)) / (float) hostW) * progress);
	}

	private int[] systemInsets() {
		int[] r = new int[4];
		androidx.core.view.WindowInsetsCompat wi = androidx.core.view.ViewCompat.getRootWindowInsets(host);
		if (wi == null) return r;
		androidx.core.graphics.Insets in = wi.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() |
				androidx.core.view.WindowInsetsCompat.Type.displayCutout());
		r[0] = in.left;
		r[1] = in.top;
		r[2] = in.right;
		r[3] = in.bottom;
		return r;
	}

	private void openDock() {
		splitView = a.getActiveVideoView();
		overlay.setAlpha(1f);
		card.setAlpha(0f);
		card.setTranslationX(dockW + dockEnd);
		animateDock(0f, 1f, 360, new DecelerateInterpolator(1.8f), null);
	}

	private void animateDock(float from, float to, long ms, android.animation.TimeInterpolator ip, @Nullable Runnable end) {
		if (anim != null) anim.cancel();
		ValueAnimator va = ValueAnimator.ofFloat(from, to);
		va.setDuration(ms);
		va.setInterpolator(ip);
		va.addUpdateListener(an -> {
			progress = (float) an.getAnimatedValue();
			card.setTranslationX((1f - progress) * (dockW + dockEnd));
			card.setAlpha(Math.min(1f, progress * 1.6f));
			VideoView vv = splitView;
			int hostW = host.getWidth();
			if ((vv != null) && (hostW > 0)) vv.setSplitShare(((dockW + dockEnd + dp(10)) / (float) hostW) * progress);
		});
		if (end != null) {
			va.addListener(new android.animation.AnimatorListenerAdapter() {
				private boolean cancelled;

				@Override
				public void onAnimationCancel(android.animation.Animator animation) {
					cancelled = true;
				}

				@Override
				public void onAnimationEnd(android.animation.Animator animation) {
					if (!cancelled) end.run();
				}
			});
		}
		anim = va;
		va.start();
	}

	private void dismiss() {
		if (dismissing) return;
		dismissing = true;
		if (docked) {
			animateDock(progress, 0f, 280, new AccelerateInterpolator(1.4f), this::remove);
			return;
		}
		overlay.animate().alpha(0f).setDuration(180).start();
		card.animate().translationY(dp(120)).alpha(0f).setDuration(200)
				.setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(this::remove).start();
	}

	private void dismissFast() {
		if (dismissing && (overlay.getParent() == null)) return;
		dismissing = true;
		if (anim != null) anim.cancel();
		if (splitView != null) splitView.setSplitShare(0f);
		splitView = null;
		if (overlay.getParent() == null) {
			remove();
			return;
		}
		overlay.animate().cancel();
		card.animate().cancel();
		card.setTranslationX(0f);
		overlay.animate().alpha(0f).setDuration(140).withEndAction(this::remove).start();
	}

	private void remove() {
		if (splitView != null) splitView.setSplitShare(0f);
		splitView = null;
		host.removeView(overlay);
		disarmBackGuard();
		if (open == this) open = null;
		body.onClosed();
	}

	/** Back goes to the newest back callback of its priority, and a fullscreen WebView registers its own: this one is asked first. */
	private void armBackGuard() {
		if ((android.os.Build.VERSION.SDK_INT < 33) || (backGuard != null)) return;
		try {
			android.view.Window w = a.getWindow();
			if (w == null) return;
			android.window.OnBackInvokedCallback cb = SidePanel::dismissOpen;
			w.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb);
			backGuard = cb;
		} catch (RuntimeException ex) {
			me.aap.utils.log.Log.e(ex, "Failed to guard Back for a panel");
		}
	}

	private void disarmBackGuard() {
		Object g = backGuard;
		backGuard = null;
		if ((g == null) || (android.os.Build.VERSION.SDK_INT < 33)) return;
		try {
			android.view.Window w = a.getWindow();
			if (w != null) w.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) g);
		} catch (RuntimeException ex) {
			me.aap.utils.log.Log.e(ex, "Failed to release the Back guard of a panel");
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Pieces for the bodies

	public TextView text(String s, float sp, int color, boolean bold) {
		TextView t = new TextView(ctx);
		t.setText(s);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	/** A row that is a button: rounded fill, ripple, an accent outline when the steering wheel is on it. */
	public LinearLayout row(CharSequence label, @Nullable CharSequence value, Runnable onClick) {
		LinearLayout r = new LinearLayout(ctx);
		r.setOrientation(LinearLayout.HORIZONTAL);
		r.setGravity(Gravity.CENTER_VERTICAL);
		r.setPadding(dp(14), dp(10), dp(14), dp(10));
		r.setMinimumHeight(dp(48));
		r.setBackground(pressable(chipFill, 14));
		r.setClickable(true);
		r.setFocusable(true);
		r.setOnClickListener(v -> onClick.run());
		TextView l = text(label.toString(), 15, primary, true);
		r.addView(l, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		if (value != null) {
			TextView v = text(value.toString(), 13, secondary, false);
			v.setSingleLine(true);
			r.addView(v, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		}
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.bottomMargin = dp(6);
		r.setLayoutParams(lp);
		return r;
	}

	/** A section heading. */
	public TextView heading(String s) {
		TextView t = text(s, 13, secondary, true);
		t.setPadding(dp(6), dp(10), dp(6), dp(4));
		return t;
	}

	public Drawable pressable(int fill, float radiusDp) {
		StateListDrawable states = new StateListDrawable();
		states.addState(new int[]{android.R.attr.state_focused}, shape(fill, radiusDp, accent, 2));
		states.addState(new int[]{}, shape(fill, radiusDp, 0, 0));
		return new RippleDrawable(ColorStateList.valueOf(ripple), states, shape(0xFF000000, radiusDp, 0, 0));
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

	public int dp(float v) {
		return Math.round(v * density);
	}

	public MainActivityDelegate activity() {
		return a;
	}
}
