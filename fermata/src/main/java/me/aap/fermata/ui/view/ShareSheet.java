package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.aap.fermata.R;
import me.aap.fermata.share.ShareManager;
import me.aap.fermata.share.ShareOffer;
import me.aap.fermata.share.ShareSource;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * The Share window: a card in the look of the playlist picker, listing the phones nearby that have
 * zrAuto open on the same Wi-Fi. Tapping one sends what is being shared; its row then shows the
 * progress, with a button to cancel. The window can be closed meanwhile ("Hide"): the transfer
 * goes on in the background with a notification. Also offers the YouTube links through Android's
 * own share menu. An overlay view rather than a dialog, so it works on the car screen too.
 */
public final class ShareSheet implements ShareManager.Listener {
	private static ShareSheet open;

	private final MainActivityDelegate a;
	private final Context ctx;
	private final ViewGroup host;
	private final ShareOffer offer;
	private final ShareManager manager = ShareManager.get();
	private final float density;
	private final int primary;
	private final int secondary;
	private final int chipFill;
	private final int ripple;
	private final int accent;
	private final int onAccent;
	/** Rows by the id of the phone, in the order they showed up. */
	private final Map<String, Row> rows = new LinkedHashMap<>();
	/** What this window sent, by the id of the phone. */
	private final Map<String, ShareManager.Transfer> sent = new HashMap<>();
	private boolean includeDownloads = true;
	private FrameLayout overlay;
	private FrameLayout card;
	private LinearLayout list;
	private TextView empty;
	private TextView closeButton;
	private TextView downloadsChip;
	private boolean dismissing;

	private static final class Row {
		final String peerId;
		String name;
		final View view;
		final TextView title;
		final TextView status;
		final ProgressBar bar;
		final ImageButton cancel;

		Row(String peerId, String name, View view, TextView title, TextView status, ProgressBar bar,
				ImageButton cancel) {
			this.peerId = peerId;
			this.name = name;
			this.view = view;
			this.title = title;
			this.status = status;
			this.bar = bar;
			this.cancel = cancel;
		}
	}

	private ShareSheet(MainActivityDelegate a, Context palette, ViewGroup host, ShareOffer offer) {
		this.a = a;
		this.ctx = palette;
		this.host = host;
		this.offer = offer;
		this.density = palette.getResources().getDisplayMetrics().density;
		this.primary = color(R.attr.musicTextPrimary);
		this.secondary = color(R.attr.musicTextSecondary);
		this.chipFill = color(R.attr.musicChipFill);
		this.ripple = color(R.attr.musicChipRipple);
		this.accent = EffectsUi.accent(palette);
		this.onAccent = EffectsUi.onAccent(accent);
	}

	/** Shows the Share window for {@code offer} over the whole app. */
	public static void show(@NonNull MainActivityDelegate a, @NonNull ShareOffer offer) {
		dismissOpen();
		ViewGroup host = a.findViewById(R.id.main_activity);
		if (host == null) {
			Log.e("No view to show the share window over");
			return;
		}

		if (offer.entries.isEmpty()) {
			TopToast.show(R.drawable.share, R.string.share_nothing);
			return;
		}

		ShareSheet s = new ShareSheet(a, EffectsUi.palette(a.getContext()), host, offer);
		open = s;
		s.build();
	}

	/** Shares {@code item}: itself if it is playable, else everything in it. */
	public static void share(@NonNull MainActivityDelegate a, @NonNull me.aap.fermata.media.lib.MediaLib.Item item) {
		ShareSource.create(a.getContext(), item).main().onCompletion((o, err) -> {
			if ((err != null) || (o == null)) {
				if (err != null) Log.e(err, "Failed to prepare the share");
				TopToast.show(R.drawable.share, R.string.share_nothing);
				return;
			}
			show(a, o);
		});
	}

	/** Shares picked items of a list, calling the share {@code title}. */
	public static void shareItems(@NonNull MainActivityDelegate a, @NonNull String kind,
																@NonNull String title,
																@NonNull List<me.aap.fermata.media.lib.MediaLib.PlayableItem> items) {
		show(a, ShareSource.create(a.getContext(), kind, title, items));
	}

	/** Closes the window if it's open (for the back button); returns whether it was. */
	public static boolean dismissOpen() {
		ShareSheet s = open;
		if ((s == null) || (s.overlay == null) || (s.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		s.dismiss();
		return true;
	}

	private void build() {
		overlay = new FrameLayout(ctx);
		overlay.setClickable(true);
		overlay.setFocusable(false);
		overlay.setElevation(dp(30));
		overlay.setBackgroundColor(0x55000000);
		overlay.setOnClickListener(v -> dismiss());
		// Car mode: the steering wheel moves through the phones, long previous closes.
		me.aap.fermata.action.CarNav.markModalScope(overlay, this::dismiss);
		overlay.setAlpha(0f);

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(16), dp(16), dp(16), dp(16));

		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(8), dp(2), dp(8), dp(4));
		ImageView hi = new ImageView(ctx);
		hi.setImageResource(R.drawable.share);
		hi.setImageTintList(ColorStateList.valueOf(accent));
		header.addView(hi, new LinearLayout.LayoutParams(dp(26), dp(26)));
		TextView title = text(ctx.getString(R.string.share), 18, primary, true);
		title.setPadding(dp(10), 0, 0, 0);
		header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		root.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		TextView summary = text(summary(), 13, secondary, false);
		summary.setPadding(dp(10), 0, dp(10), dp(10));
		summary.setMaxLines(3);
		root.addView(summary, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// Chips: the links through Android's own share menu, and whether downloads go along.
		ChipFlowLayout chips = new ChipFlowLayout(ctx);
		String links = ShareSource.linkText(offer);
		if (links != null) {
			chips.addView(ChipFlowLayout.chip(ctx, R.drawable.link, R.string.share_as_link, false,
					() -> shareAsLink(links)));
		}
		if (offer.downloadBytes() > 0) {
			downloadsChip = ChipFlowLayout.chip(ctx, R.drawable.download, downloadsLabel(), false, () -> {
				includeDownloads = !includeDownloads;
				downloadsChip.setText(downloadsLabel());
				downloadsChip.setAlpha(includeDownloads ? 1f : 0.55f);
				summary.setText(summary());
			});
			chips.addView(downloadsChip);
		}
		if (chips.getChildCount() > 0) {
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			clp.setMargins(dp(4), 0, dp(4), dp(10));
			root.addView(chips, clp);
		}

		TextView nearby = text(ctx.getString(R.string.share_nearby), 12, secondary, true);
		nearby.setAllCaps(true);
		nearby.setPadding(dp(10), dp(4), dp(10), dp(6));
		root.addView(nearby, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		list = new LinearLayout(ctx);
		list.setOrientation(LinearLayout.VERTICAL);
		empty = text(ctx.getString(R.string.share_searching), 14, secondary, false);
		empty.setPadding(dp(10), dp(8), dp(10), dp(8));
		list.addView(empty);
		ScrollView scroll = new ScrollView(ctx);
		scroll.setVerticalScrollBarEnabled(false);
		scroll.addView(list, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		root.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		closeButton = pill(ctx.getString(R.string.close));
		closeButton.setOnClickListener(v -> dismiss());
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(46));
		blp.topMargin = dp(10);
		blp.setMarginStart(dp(4));
		blp.setMarginEnd(dp(4));
		root.addView(closeButton, blp);

		card = new FrameLayout(ctx);
		card.setClickable(true);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(color(R.attr.musicPanelFill));
		bg.setCornerRadius(dp(28));
		card.setBackground(bg);
		card.setElevation(dp(12));
		card.addView(root, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		int h = dp(16 + 26 + 4 + 40 + 56 + 10 + 46 + 40) + 3 * dp(76);
		overlay.addView(card, OverlayCard.params(a, host, h));
		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		card.setTranslationY(dp(120));
		card.setAlpha(0f);
		card.animate().translationY(0f).alpha(1f).setDuration(260)
				.setInterpolator(new DecelerateInterpolator(1.6f)).start();
		overlay.animate().alpha(1f).setDuration(220).start();

		manager.addListener(this);
		manager.startDiscovery();
		refresh();
	}

	private String summary() {
		String what = ctx.getResources().getQuantityString(R.plurals.share_items, offer.entries.size(),
				offer.entries.size());
		// The offer is made with the downloads in; without them it is that much less.
		long bytes = offer.totalBytes() - (includeDownloads ? 0 : offer.downloadBytes());
		StringBuilder sb = new StringBuilder(offer.title).append(" · ").append(what);
		if (bytes > 0) sb.append(" · ").append(Formatter.formatShortFileSize(ctx, bytes));
		if (offer.skipped > 0) {
			sb.append('\n').append(ctx.getString(R.string.share_skipped, offer.skipped));
		}
		return sb.toString();
	}

	private String downloadsLabel() {
		return includeDownloads ?
				ctx.getString(R.string.share_with_downloads, Formatter.formatShortFileSize(ctx, offer.downloadBytes())) :
				ctx.getString(R.string.share_without_downloads);
	}

	private void shareAsLink(String links) {
		if (a.isCarActivity()) {
			TopToast.show(R.drawable.share, R.string.share_link_on_phone);
			return;
		}

		try {
			Intent send = new Intent(Intent.ACTION_SEND);
			send.setType("text/plain");
			send.putExtra(Intent.EXTRA_SUBJECT, offer.title);
			send.putExtra(Intent.EXTRA_TEXT, links);
			Intent chooser = Intent.createChooser(send, ctx.getString(R.string.share_as_link));
			chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			a.getContext().startActivity(chooser);
		} catch (Exception ex) {
			Log.e(ex, "Failed to open the share menu");
		}
	}

	// ---- Rows ----

	@Override
	public void onPeersChanged() {
		refresh();
	}

	@Override
	public void onTransfersChanged() {
		refresh();
	}

	/** Brings the rows up to date with the phones found and the transfers started from here. */
	private void refresh() {
		if (dismissing || (list == null)) return;
		List<ShareManager.Peer> peers = manager.getPeers();

		for (ShareManager.Peer p : peers) {
			Row r = rows.get(p.id);
			if (r == null) {
				r = createRow(p);
				rows.put(p.id, r);
				list.addView(r.view, rowParams());
			}
			r.name = p.name;
			r.title.setText(p.name);
		}

		boolean anyActive = false;

		for (Row r : new java.util.ArrayList<>(rows.values())) {
			ShareManager.Transfer t = sent.get(r.peerId);
			boolean listed = false;
			for (ShareManager.Peer p : peers) {
				if (p.id.equals(r.peerId)) {
					listed = true;
					break;
				}
			}

			// A phone that went out of sight keeps its row while it is still being sent to.
			if (!listed && ((t == null) || !t.isActive())) {
				rows.remove(r.peerId);
				list.removeView(r.view);
				continue;
			}

			bind(r, t);
			if ((t != null) && t.isActive()) anyActive = true;
		}

		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
		closeButton.setText(anyActive ? R.string.share_hide : R.string.close);
	}

	private Row createRow(ShareManager.Peer p) {
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(10), dp(8), dp(6), dp(8));
		row.setBackground(pressable(chipFill, 20));
		row.setClickable(true);
		row.setFocusable(true);

		FrameLayout disc = new FrameLayout(ctx);
		GradientDrawable d = new GradientDrawable();
		d.setShape(GradientDrawable.OVAL);
		d.setColor(accent);
		disc.setBackground(d);
		ImageView iv = new ImageView(ctx);
		iv.setImageResource(R.drawable.wifi);
		iv.setImageTintList(ColorStateList.valueOf(onAccent));
		disc.addView(iv, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
		row.addView(disc, new LinearLayout.LayoutParams(dp(48), dp(48)));

		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.setPadding(dp(14), 0, dp(8), 0);
		TextView name = text(p.name, 16, primary, true);
		name.setSingleLine(true);
		name.setEllipsize(android.text.TextUtils.TruncateAt.END);
		TextView status = text("", 12, secondary, false);
		status.setSingleLine(true);
		status.setEllipsize(android.text.TextUtils.TruncateAt.END);
		ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
		bar.setMax(1000);
		bar.setProgressTintList(ColorStateList.valueOf(accent));
		bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
		bar.setVisibility(View.GONE);
		texts.addView(name);
		texts.addView(status);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(6));
		blp.topMargin = dp(4);
		texts.addView(bar, blp);
		row.addView(texts, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		ImageButton cancel = new ImageButton(ctx);
		cancel.setImageResource(me.aap.utils.R.drawable.close);
		cancel.setImageTintList(ColorStateList.valueOf(primary));
		cancel.setBackground(pressable(0, 20));
		cancel.setContentDescription(ctx.getString(R.string.cancel));
		cancel.setVisibility(View.GONE);
		row.addView(cancel, new LinearLayout.LayoutParams(dp(40), dp(40)));

		Row r = new Row(p.id, p.name, row, name, status, bar, cancel);
		row.setOnClickListener(v -> sendTo(p.id));
		cancel.setOnClickListener(v -> {
			ShareManager.Transfer t = sent.get(p.id);
			if (t != null) t.cancel();
		});
		return r;
	}

	private void bind(Row r, @Nullable ShareManager.Transfer t) {
		if (t == null) {
			r.status.setText(R.string.share_tap_to_send);
			r.bar.setVisibility(View.GONE);
			r.cancel.setVisibility(View.GONE);
			return;
		}

		switch (t.state) {
			case WAITING -> {
				r.status.setText(ctx.getString(R.string.share_waiting_for, r.name));
				r.bar.setIndeterminate(true);
				r.bar.setVisibility(View.VISIBLE);
				r.cancel.setVisibility(View.VISIBLE);
			}
			case TRANSFERRING -> {
				long total = t.total;
				long done = t.done;
				r.bar.setIndeterminate(total <= 0);
				if (total > 0) r.bar.setProgress((int) Math.min(1000, done * 1000 / total));
				r.bar.setVisibility(View.VISIBLE);
				r.cancel.setVisibility(View.VISIBLE);
				String s = (total > 0) ? (done * 100 / total) + "%  ·  " +
						Formatter.formatShortFileSize(ctx, done) + " / " + Formatter.formatShortFileSize(ctx, total) :
						ctx.getString(R.string.share_working);
				if (t.current != null) s += "  ·  " + t.current;
				r.status.setText(s);
			}
			case SAVING -> {
				r.status.setText(R.string.share_saving);
				r.bar.setIndeterminate(true);
				r.bar.setVisibility(View.VISIBLE);
				r.cancel.setVisibility(View.GONE);
			}
			case DONE -> {
				r.status.setText(ctx.getResources().getQuantityString(R.plurals.share_sent_files,
						t.offer.entries.size(), t.offer.entries.size()));
				r.bar.setVisibility(View.GONE);
				r.cancel.setVisibility(View.GONE);
			}
			case DECLINED -> {
				r.status.setText(R.string.share_declined_short);
				r.bar.setVisibility(View.GONE);
				r.cancel.setVisibility(View.GONE);
			}
			case CANCELLED -> {
				r.status.setText(R.string.share_cancelled_short);
				r.bar.setVisibility(View.GONE);
				r.cancel.setVisibility(View.GONE);
			}
			default -> {
				r.status.setText(ctx.getString(R.string.share_failed_short, String.valueOf(t.error)));
				r.bar.setVisibility(View.GONE);
				r.cancel.setVisibility(View.GONE);
			}
		}
	}

	private void sendTo(String peerId) {
		ShareManager.Transfer old = sent.get(peerId);
		if ((old != null) && old.isActive()) return;

		for (ShareManager.Peer p : manager.getPeers()) {
			if (!p.id.equals(peerId)) continue;
			sent.put(peerId, manager.send(p, offer.snapshot(includeDownloads)));
			refresh();
			return;
		}
	}

	private LinearLayout.LayoutParams rowParams() {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.setMargins(dp(4), dp(4), dp(4), dp(4));
		return lp;
	}

	// ---------------------------------------------------------------------------------------------

	private void dismiss() {
		if (dismissing) return;
		dismissing = true;
		manager.removeListener(this);
		manager.stopDiscovery();
		overlay.animate().alpha(0f).setDuration(180).start();
		card.animate().translationY(dp(120)).alpha(0f).setDuration(200)
				.setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(this::remove).start();
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
