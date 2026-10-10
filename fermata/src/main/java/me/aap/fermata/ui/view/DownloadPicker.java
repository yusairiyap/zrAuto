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
import android.view.View;
import android.view.ViewGroup;
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

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ytdl.DownloadsAddon;
import me.aap.utils.log.Log;

/**
 * "Download as": a card in the look of {@link PlaylistPicker} (same panel, rows and slide up)
 * with a row for the audio alone and one per picture quality. The one place every Download in the
 * app goes through. An overlay view rather than a dialog, since Android Auto's window context
 * doesn't allow dialog windows.
 */
public final class DownloadPicker {
	public interface Callback {
		/** @param height the tallest picture to take, in lines; 0 for the audio alone */
		void onPick(int height);
	}

	private static DownloadPicker open;

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

	private DownloadPicker(Context ctx, ViewGroup host) {
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
	 * @param name what is being downloaded, shown under the title ("3 videos", a video's title)
	 */
	public static void show(@NonNull MainActivityDelegate a, @NonNull CharSequence name,
													@NonNull Callback cb) {
		dismissOpen();
		ViewGroup host = a.findViewById(R.id.main_activity);
		if (host == null) {
			Log.e("No view to show the download picker over");
			return;
		}
		DownloadPicker p = new DownloadPicker(EffectsUi.palette(a.getContext()), host);
		open = p;
		p.build(a, name, cb);
	}

	/** Closes the picker if it's open (for the back button); returns whether it was. */
	public static boolean dismissOpen() {
		DownloadPicker p = open;
		if ((p == null) || (p.overlay == null) || (p.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		p.dismiss();
		return true;
	}

	private void build(MainActivityDelegate a, CharSequence name, Callback cb) {
		overlay = new FrameLayout(ctx);
		overlay.setClickable(true);
		overlay.setFocusable(false);
		overlay.setElevation(dp(30));
		overlay.setBackgroundColor(0x55000000);
		overlay.setOnClickListener(v -> dismiss());
		// Car mode: the steering wheel moves through the rows, long previous closes.
		me.aap.fermata.action.CarNav.markModalScope(overlay, this::dismiss);
		overlay.setAlpha(0f);

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(16), dp(16), dp(16), dp(16));

		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(8), dp(2), dp(8), dp(10));
		ImageView hi = new ImageView(ctx);
		hi.setImageResource(R.drawable.download);
		hi.setImageTintList(ColorStateList.valueOf(accent));
		header.addView(hi, new LinearLayout.LayoutParams(dp(26), dp(26)));
		LinearLayout titles = new LinearLayout(ctx);
		titles.setOrientation(LinearLayout.VERTICAL);
		titles.setPadding(dp(10), 0, 0, 0);
		titles.addView(text(ctx.getString(R.string.ytdl_download_as), 18, primary, true));
		TextView sub = text(name.toString(), 12, secondary, false);
		sub.setSingleLine(true);
		sub.setEllipsize(TextUtils.TruncateAt.END);
		titles.addView(sub);
		header.addView(titles, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		root.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		LinearLayout rows = new LinearLayout(ctx);
		rows.setOrientation(LinearLayout.VERTICAL);
		int def = DownloadsAddon.getMaxVideoHeight();
		rows.addView(row(R.drawable.music, ctx.getString(R.string.ytdl_kind_audio),
				ctx.getString(R.string.ytdl_quality_audio_sub), false, () -> pick(cb, 0)), rowParams());
		for (int h : DownloadsAddon.QUALITIES) {
			String sd = (h >= 720) ? ctx.getString(R.string.ytdl_quality_hd) :
					ctx.getString(R.string.ytdl_quality_sd);
			rows.addView(row(R.drawable.video, h + "p", sd, h == def, () -> pick(cb, h)), rowParams());
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

		int rowsH = dp(10 + 26 + 10 + 46 + 32) + (1 + DownloadsAddon.QUALITIES.length) * dp(72);
		FrameLayout.LayoutParams lp = OverlayCard.params(a, host, rowsH);
		overlay.addView(card, lp);
		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		card.setTranslationY(dp(120));
		card.setAlpha(0f);
		card.animate().translationY(0f).alpha(1f).setDuration(260)
				.setInterpolator(new DecelerateInterpolator(1.6f)).start();
		overlay.animate().alpha(1f).setDuration(220).start();
	}

	private void pick(Callback cb, int height) {
		dismissNow();
		cb.onPick(height);
	}

	private LinearLayout.LayoutParams rowParams() {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(68));
		lp.setMargins(dp(4), dp(4), dp(4), dp(4));
		return lp;
	}

	private View row(@DrawableRes int icon, String name, String detail, boolean highlighted,
									 Runnable onClick) {
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(10), dp(8), dp(10), dp(8));
		row.setBackground(pressable(chipFill, 20));
		row.setClickable(true);
		row.setFocusable(true);
		row.setOnClickListener(v -> onClick.run());

		FrameLayout disc = new FrameLayout(ctx);
		GradientDrawable d = new GradientDrawable();
		d.setShape(GradientDrawable.OVAL);
		d.setColor(highlighted ? accent : 0x33808080);
		disc.setBackground(d);
		ImageView iv = new ImageView(ctx);
		iv.setImageResource(icon);
		iv.setImageTintList(ColorStateList.valueOf(highlighted ? onAccent : primary));
		disc.addView(iv, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
		row.addView(disc, new LinearLayout.LayoutParams(dp(52), dp(52)));

		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.setPadding(dp(14), 0, dp(8), 0);
		TextView n = text(name, 16, primary, true);
		n.setSingleLine(true);
		TextView s = text(detail, 12, secondary, false);
		s.setSingleLine(true);
		s.setEllipsize(TextUtils.TruncateAt.END);
		texts.addView(n);
		texts.addView(s);
		row.addView(texts, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		return row;
	}

	private void dismiss() {
		if (dismissing) return;
		dismissing = true;
		overlay.animate().alpha(0f).setDuration(180).start();
		card.animate().translationY(dp(120)).alpha(0f).setDuration(200)
				.setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(this::remove).start();
	}

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
