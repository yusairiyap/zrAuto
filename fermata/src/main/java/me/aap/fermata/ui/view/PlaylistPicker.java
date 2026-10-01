package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.support.v4.media.MediaDescriptionCompat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * Picks a playlist: a card in the look of the Music tab's "more" menu (same panel, same pills and
 * the same slide up), with a row per playlist -- its cover and size -- and "Create playlist" first.
 * Every "Add to playlist" and "Move to playlist" in the app goes through it, so they all look and
 * behave the same. An overlay view rather than a dialog, since Android Auto's window context
 * doesn't allow dialog windows.
 */
public final class PlaylistPicker {
	public interface Callback {
		/** "Create playlist" was picked. */
		void onCreate();

		void onPick(Playlist playlist);
	}

	private static PlaylistPicker open;

	private final Context ctx;
	private final ViewGroup host;
	private final float density;
	private final int primary;
	private final int secondary;
	private final int chipFill;
	private final int ripple;
	private final int accent;
	private final int onAccent;
	private FrameLayout overlay;
	private FrameLayout card;
	private boolean dismissing;

	private PlaylistPicker(Context ctx, ViewGroup host) {
		this.ctx = ctx;
		this.host = host;
		this.density = ctx.getResources().getDisplayMetrics().density;
		this.primary = color(R.attr.musicTextPrimary);
		this.secondary = color(R.attr.musicTextSecondary);
		this.chipFill = color(R.attr.musicChipFill);
		this.ripple = color(R.attr.musicChipRipple);
		this.accent = EffectsUi.accent(ctx);
		this.onAccent = EffectsUi.onAccent(accent);
	}

	/**
	 * Shows the picker over the whole app.
	 *
	 * @param create whether "Create playlist" is offered
	 */
	public static void show(@NonNull MainActivityDelegate a, @StringRes int title,
													@DrawableRes int icon, @NonNull List<Playlist> playlists, boolean create,
													@NonNull Callback cb) {
		dismissOpen();
		ViewGroup host = a.findViewById(R.id.main_activity);
		if (host == null) {
			Log.e("No view to show the playlist picker over");
			return;
		}
		Context pc = EffectsUi.palette(a.getContext());
		PlaylistPicker p = new PlaylistPicker(pc, host);
		open = p;
		p.build(a, title, icon, playlists, create, cb);
	}

	/** Closes the picker if it's open (for the back button); returns whether it was. */
	public static boolean dismissOpen() {
		PlaylistPicker p = open;
		if ((p == null) || (p.overlay == null) || (p.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		p.dismiss();
		return true;
	}

	private void build(MainActivityDelegate a, int titleRes, int icon, List<Playlist> playlists,
										 boolean create, Callback cb) {
		overlay = new FrameLayout(ctx);
		overlay.setClickable(true);
		overlay.setFocusable(false);
		overlay.setElevation(dp(30));
		overlay.setBackgroundColor(0x55000000);
		overlay.setOnClickListener(v -> dismiss());
		overlay.setAlpha(0f);

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(16), dp(16), dp(16), dp(16));

		// Title row.
		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(8), dp(2), dp(8), dp(10));
		ImageView hi = new ImageView(ctx);
		hi.setImageResource(icon);
		hi.setImageTintList(ColorStateList.valueOf(accent));
		header.addView(hi, new LinearLayout.LayoutParams(dp(26), dp(26)));
		TextView title = text(ctx.getString(titleRes), 18, primary, true);
		title.setPadding(dp(10), 0, 0, 0);
		header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		root.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// The rows scroll when there are many.
		LinearLayout rows = new LinearLayout(ctx);
		rows.setOrientation(LinearLayout.VERTICAL);
		if (create) {
			rows.addView(createRow(() -> {
				dismissNow();
				cb.onCreate();
			}), rowParams());
		}
		for (Playlist pl : playlists) {
			rows.addView(playlistRow(pl, () -> {
				dismissNow();
				cb.onPick(pl);
			}), rowParams());
		}
		ScrollView scroll = new ScrollView(ctx);
		scroll.setVerticalScrollBarEnabled(false);
		scroll.addView(rows, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		root.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		TextView cancel = pill(ctx.getString(R.string.cancel));
		cancel.setOnClickListener(v -> dismiss());
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(46));
		clp.topMargin = dp(10);
		clp.setMarginStart(dp(4));
		clp.setMarginEnd(dp(4));
		root.addView(cancel, clp);

		card = new FrameLayout(ctx);
		card.setClickable(true);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(color(R.attr.musicPanelFill));
		bg.setCornerRadius(dp(28));
		card.setBackground(bg);
		card.setElevation(dp(12));
		card.addView(root, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		int hostW = host.getWidth();
		int hostH = host.getHeight();
		int[] side = new int[2];
		a.computeSideInsets(host, side);
		int w = Math.min(dp(420), hostW - dp(32) - side[0] - side[1]);
		// As tall as its rows need, up to most of the screen (the rows scroll beyond that).
		int rowsH = dp(10 + 26 + 10 + 46 + 32) + (playlists.size() + (create ? 1 : 0)) * dp(72);
		int h = Math.min(rowsH, Math.round(hostH * 0.78f));
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
		lp.leftMargin = side[0];
		lp.rightMargin = side[1];
		overlay.addView(card, lp);
		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		card.setTranslationY(dp(120));
		card.setAlpha(0f);
		card.animate().translationY(0f).alpha(1f).setDuration(260)
				.setInterpolator(new DecelerateInterpolator(1.6f)).start();
		overlay.animate().alpha(1f).setDuration(220).start();
	}

	private LinearLayout.LayoutParams rowParams() {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(68));
		lp.setMargins(dp(4), dp(4), dp(4), dp(4));
		return lp;
	}

	private View createRow(Runnable onClick) {
		LinearLayout row = baseRow(onClick);
		FrameLayout disc = new FrameLayout(ctx);
		GradientDrawable d = new GradientDrawable();
		d.setShape(GradientDrawable.OVAL);
		d.setColor(accent);
		disc.setBackground(d);
		ImageView iv = new ImageView(ctx);
		iv.setImageResource(R.drawable.playlist_add);
		iv.setImageTintList(ColorStateList.valueOf(onAccent));
		disc.addView(iv, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
		row.addView(disc, new LinearLayout.LayoutParams(dp(52), dp(52)));
		TextView name = text(ctx.getString(R.string.playlist_create), 16, primary, true);
		name.setPadding(dp(14), 0, 0, 0);
		row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		return row;
	}

	private View playlistRow(Playlist pl, Runnable onClick) {
		LinearLayout row = baseRow(onClick);

		ImageView cover = new ImageView(ctx);
		cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
		cover.setBackgroundColor(chipFill);
		cover.setImageResource(R.drawable.playlist);
		cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		cover.setImageTintList(ColorStateList.valueOf(secondary));
		cover.setClipToOutline(true);
		cover.setOutlineProvider(new ViewOutlineProvider() {
			@Override
			public void getOutline(View v, Outline o) {
				o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
			}
		});
		row.addView(cover, new LinearLayout.LayoutParams(dp(52), dp(52)));

		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.setPadding(dp(14), 0, dp(8), 0);
		TextView name = text(pl.getName(), 16, primary, true);
		name.setSingleLine(true);
		name.setEllipsize(android.text.TextUtils.TruncateAt.END);
		TextView sub = text("", 12, secondary, false);
		sub.setSingleLine(true);
		sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
		texts.addView(name);
		texts.addView(sub);
		row.addView(texts, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		// Its cover and size, once they've been read: the rows are there at once.
		pl.getMediaDescription().main().onSuccess(md -> {
			if (md.getSubtitle() != null) sub.setText(md.getSubtitle());
			Uri uri = md.getIconUri();
			if ((uri == null) || "android.resource".equals(uri.getScheme())) return;
			pl.getLib().getBitmap(uri.toString(), true, true).main().onSuccess(bm -> {
				if ((bm == null) || (cover.getParent() == null)) return;
				cover.setImageTintList(null);
				cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
				cover.setImageBitmap(bm);
			});
		});
		return row;
	}

	private LinearLayout baseRow(Runnable onClick) {
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(10), dp(8), dp(10), dp(8));
		row.setBackground(pressable(chipFill, 20));
		row.setClickable(true);
		row.setFocusable(true);
		row.setOnClickListener(v -> onClick.run());
		return row;
	}

	// ---------------------------------------------------------------------------------------------

	private void dismiss() {
		if (dismissing) return;
		dismissing = true;
		overlay.animate().alpha(0f).setDuration(180).start();
		card.animate().translationY(dp(120)).alpha(0f).setDuration(200)
				.setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(this::remove).start();
	}

	/** Closes at once, for whatever the choice opens next. */
	private void dismissNow() {
		dismissing = true;
		overlay.animate().cancel();
		card.animate().cancel();
		remove();
	}

	private void remove() {
		host.removeView(overlay);
		if (open == this) open = null;
	}

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

	/** A rounded fill with a ripple, and an accent outline when focused (D-pad / TV). */
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
