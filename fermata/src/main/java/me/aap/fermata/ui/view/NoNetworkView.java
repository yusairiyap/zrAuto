package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.util.NetworkState;

/**
 * The one "no network" placeholder: a big crossed-out signal over soft pulsing rings, what is wrong, three things to check and
 * two buttons. Every screen that needs the internet (YouTube tab, browser tab, Spotify import, the Home cards that read the
 * network) shows this same view, so they look alike. It covers its host while the connection is down, fades in and out by
 * itself with {@link NetworkState}, and runs {@code onRestored} when the connection comes back so the screen can load again.
 * <p>
 * {@link #attach} adds it to a host that stacks its children ({@link FrameLayout} and the like); {@link #compact} is the same
 * thing for a Home card, with the graphic smaller and the hints left out.
 */
public final class NoNetworkView extends FrameLayout implements NetworkState.Listener {
	private final boolean compact;
	@Nullable
	private final Runnable onRestored;
	private final Rings rings;
	private final int primary;
	private final int secondary;
	private final int accent;
	private final android.widget.TextView title;
	private final ImageView icon;
	private final FrameLayout art;
	private int ringColor;
	private TextView msgView;
	private TextView retryBtn;
	@Nullable
	private ShownListener shownListener;

	/** Told when the placeholder goes up or down ({@code animate}: a change now, not the first state). */
	public interface ShownListener {
		void onShown(boolean shown, boolean animate);
	}

	public void setShownListener(@Nullable ShownListener l) {
		shownListener = l;
		if (l != null) l.onShown(!NetworkState.isOnline(), false);
	}

	private NoNetworkView(Context ctx, boolean compact, @Nullable Runnable onRestored) {
		super(ctx);
		this.compact = compact;
		this.onRestored = onRestored;
		// The window theme's own text colours: the Music palette attributes are not set on every tab (the YouTube tab drew
		// its words in the background's colour).
		primary = color(android.R.attr.textColorPrimary);
		secondary = color(android.R.attr.textColorSecondary);
		accent = EffectsUi.accent(ctx);
		ringColor = accent;
		setClickable(true);
		setFocusable(false);
		// Opaque enough to hide the empty screen behind, but the theme's own background shows through the edges.
		// On a card the card's own look shows through (no fill of its own); a whole screen gets the theme's background.
		if (!compact) setBackgroundColor(color(android.R.attr.colorBackground));
		LinearLayout col = new LinearLayout(ctx);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setGravity(Gravity.CENTER_HORIZONTAL);
		int pad = dp(compact ? 8 : 24);
		col.setPadding(pad, pad, pad, pad);

		int size = dp(compact ? 72 : 150);
		art = new FrameLayout(ctx);
		rings = new Rings(ctx);
		art.addView(rings, new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		icon = new ImageView(ctx);
		icon.setImageResource(R.drawable.network_off);
		icon.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
		icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
		art.addView(icon, new LayoutParams(Math.round(size * 0.5f), Math.round(size * 0.5f), Gravity.CENTER));
		col.addView(art, new LinearLayout.LayoutParams(size, size));

		title = text(R.string.no_network_title, compact ? 13 : 22, compact ? secondary : primary, true);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		tlp.topMargin = dp(compact ? 4 : 16);
		col.addView(title, tlp);

		if (!compact) {
			TextView msg = text(R.string.no_network_message, 14, secondary, false);
			msgView = msg;
			LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
			mlp.topMargin = dp(6);
			col.addView(msg, mlp);
			LinearLayout hints = new LinearLayout(ctx);
			hints.setOrientation(LinearLayout.VERTICAL);
			hints.addView(hint(R.drawable.wifi, R.string.no_network_hint_wifi));
			hints.addView(hint(R.drawable.network_off, R.string.no_network_hint_airplane));
			hints.addView(hint(R.drawable.network_weak, R.string.no_network_hint_signal));
			LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
			hlp.topMargin = dp(18);
			col.addView(hints, hlp);

			LinearLayout buttons = new LinearLayout(ctx);
			buttons.setOrientation(LinearLayout.HORIZONTAL);
			buttons.setGravity(Gravity.CENTER);
			buttons.addView(button(R.string.no_network_settings, false, v -> openSettings()));
			retryBtn = (TextView) button(R.string.no_network_retry, true, v -> retry(v));
			buttons.addView(retryBtn);
			LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
			blp.topMargin = dp(22);
			col.addView(buttons, blp);
		}
		addView(col, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER));
		setVisibility(NetworkState.isOnline() ? GONE : VISIBLE);
		setAlpha(NetworkState.isOnline() ? 0f : 1f);
	}

	/** A card's own colour for the quiet look: grey words and a faint picture, so it does not shout over the widgets. */
	public void tint(int fg) {
		title.setTextColor(fg);
		icon.setImageTintList(android.content.res.ColorStateList.valueOf(fg));
		ringColor = fg;
		rings.invalidate();
	}

	@Override
	protected void onMeasure(int wSpec, int hSpec) {
		// A card's picture is sized before the first frame, from what the card gives (not after a later layout): about half of
		// its short side, between a small and a big placeholder. The icon is set by size, not by padding, so it never comes out tiny.
		if (compact) {
			int w = MeasureSpec.getSize(wSpec);
			int h = MeasureSpec.getSize(hSpec);
			if ((w > 0) && (h > 0)) {
				int s = Math.max(dp(48), Math.min(dp(150), Math.round(Math.min(w, h) * 0.5f)));
				ViewGroup.LayoutParams lp = art.getLayoutParams();
				if (lp.width != s) {
					lp.width = s;
					lp.height = s;
					art.setLayoutParams(lp);
					FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Math.round(s * 0.5f), Math.round(s * 0.5f), Gravity.CENTER);
					icon.setLayoutParams(ilp);
				}
			}
		}
		super.onMeasure(wSpec, hSpec);
	}

	/** Puts the placeholder over {@code host} (a layout that stacks its children). Shown only while there is no connection. */
	public static NoNetworkView attach(ViewGroup host, @Nullable Runnable onRestored) {
		NoNetworkView v = new NoNetworkView(host.getContext(), false, onRestored);
		host.addView(v, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		v.setElevation(host.getContext().getResources().getDisplayMetrics().density * 6);
		return v;
	}

	/** The small kind for a Home card: icon, title, no hints. */
	public static NoNetworkView compact(ViewGroup host, @Nullable Runnable onRestored) {
		NoNetworkView v = new NoNetworkView(host.getContext(), true, onRestored);
		host.addView(v, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		return v;
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		NetworkState.addListener(this);
		// The state may have moved while this was away.
		show(!NetworkState.isOnline(), false);
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		NetworkState.removeListener(this);
		rings.stop();
	}

	@Override
	public void onNetworkChanged(boolean online) {
		show(!online, true);
		if (online && (onRestored != null)) postDelayed(onRestored, 350);
	}

	/** Switched on by hand (a flight): says so, and the button goes online instead of asking again. */
	private void applyMode() {
		boolean forced = NetworkState.isForcedOffline();
		title.setText(forced ? R.string.no_network_offline_title : R.string.no_network_title);
		if (msgView != null) msgView.setText(forced ? R.string.no_network_offline_message : R.string.no_network_message);
		if (retryBtn != null) retryBtn.setText(forced ? R.string.no_network_go_online : R.string.no_network_retry);
	}

	private void show(boolean on, boolean animate) {
		applyMode();
		if (shownListener != null) shownListener.onShown(on, animate);
		animate().cancel();
		if (on) {
			setVisibility(VISIBLE);
			rings.start();
			if (animate) animate().alpha(1f).setDuration(220).start();
			else setAlpha(1f);
		} else if (animate) {
			animate().alpha(0f).setDuration(320).withEndAction(() -> {
				setVisibility(GONE);
				rings.stop();
			}).start();
		} else {
			setAlpha(0f);
			setVisibility(GONE);
			rings.stop();
		}
	}

	private void retry(View button) {
		if (NetworkState.isForcedOffline()) {
			NetworkState.setForcedOffline(false);
			return;
		}
		if (NetworkState.isOnline()) {
			show(false, true);
			if (onRestored != null) onRestored.run();
			return;
		}
		me.aap.fermata.ui.view.TopToast.show(R.drawable.network_off, R.string.no_network_checking);
	}

	private void openSettings() {
		try {
			Intent i = new Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			getContext().startActivity(i);
		} catch (Exception ex) {
			try {
				getContext().startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
			} catch (Exception ignore) {
			}
		}
	}

	private View hint(int icon, int text) {
		LinearLayout r = new LinearLayout(getContext());
		r.setOrientation(LinearLayout.HORIZONTAL);
		r.setGravity(Gravity.CENTER_VERTICAL);
		ImageView i = new ImageView(getContext());
		i.setImageResource(icon);
		i.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
		r.addView(i, new LinearLayout.LayoutParams(dp(20), dp(20)));
		TextView t = text(text, 13.5f, secondary, false);
		t.setGravity(Gravity.START);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		lp.setMarginStart(dp(10));
		r.addView(t, lp);
		r.setPadding(0, dp(4), 0, dp(4));
		return r;
	}

	private View button(int text, boolean filled, OnClickListener l) {
		TextView b = text(text, 14, filled ? EffectsUi.onAccent(accent) : primary, true);
		b.setMinHeight(dp(44));
		b.setGravity(Gravity.CENTER);
		b.setPadding(dp(20), 0, dp(20), 0);
		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(dp(22));
		if (filled) bg.setColor(accent);
		else bg.setStroke(dp(1), (secondary & 0x00FFFFFF) | 0x66000000);
		b.setBackground(bg);
		b.setClickable(true);
		b.setFocusable(true);
		b.setOnClickListener(l);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		lp.setMargins(dp(6), 0, dp(6), 0);
		b.setLayoutParams(lp);
		return b;
	}

	private TextView text(int res, float sp, int color, boolean bold) {
		TextView t = new TextView(getContext());
		t.setText(res);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		t.setGravity(Gravity.CENTER);
		if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
		return t;
	}

	private int dp(float v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}

	private int color(@AttrRes int attr) {
		TypedValue tv = new TypedValue();
		getContext().getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	/**
	 * Three faint rings behind the icon. Drawn once and still: an animation here invalidated the view every frame, and a tab
	 * with glass bars redraws (and blurs) its bars for each such frame, which made the app lag while offline.
	 */
	private final class Rings extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

		Rings(Context ctx) {
			super(ctx);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(dp(2));
		}

		void start() {
		}

		void stop() {
		}

		@Override
		protected void onDraw(Canvas c) {
			float cx = getWidth() / 2f;
			float cy = getHeight() / 2f;
			float max = Math.min(cx, cy);
			for (int i = 0; i < 3; i++) {
				paint.setColor((ringColor & 0x00FFFFFF) | ((70 - i * 20) << 24));
				c.drawCircle(cx, cy, max * (0.5f + 0.25f * i), paint);
			}
		}
	}
}
