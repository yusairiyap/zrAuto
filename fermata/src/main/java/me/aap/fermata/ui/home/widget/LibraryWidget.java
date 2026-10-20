package me.aap.fermata.ui.home.widget;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeImages;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeSkeleton;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.activity.ActivityListener;

/**
 * A card listing the first few things of the library -- favorites, playlists, bookmarks -- as rows
 * with a picture. How many rows show follows the card's height, and a wide card makes two columns
 * of them. The subclasses only say where the rows come from.
 */
abstract class LibraryWidget extends HomeWidgetView {
	/** One row: what it is called, a second line, a picture, and what tapping it (or its button) does. */
	protected record Row(CharSequence title, @Nullable CharSequence sub, @Nullable MediaLib.Item art,
											 Runnable click, @Nullable Runnable play,
											 @Nullable Consumer<TextView> subFill) {}

	private static final long STALE_MS = 15_000;
	private LinearLayout column;
	private TextView count;
	private LinearLayout list;
	private TextView emptyText;
	private TextView firstLine;
	private ProgressBar busy;
	/** The row to scroll to when the list is drawn (the queue's playing song), -1 for none. */
	protected int focusRow = -1;
	private ScrollView listScroll;
	private HomeSkeleton skeleton;
	private TextView backChip;
	/** Set while something is opened inside the card: the rows are its contents, a chip leads back. */
	@Nullable
	private FetchSource drillSource;
	private int fetchGen;
	/** What the rows on screen were made from, so an unchanged refresh doesn't rebuild (and blink) them. */
	private String shownSignature = "";
	private List<Row> rows = new ArrayList<>();
	private int total;
	private int capacity = 3;
	private boolean built;
	private long loadedAt;
	private boolean loading;
	private final ActivityListener contentListener = new ActivityListener() {
		@Override
		public void onActivityEvent(ActivityDelegate a, long e) {
			if (e == FRAGMENT_CONTENT_CHANGED) refresh(true);
		}
	};

	protected LibraryWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	/** Collects the rows; calls {@code done} on the main thread with them and how many there are in all. */
	protected abstract void fetch(MainActivityDelegate a, FetchDone done);

	/** The tab the header button opens. */
	protected abstract int tabId();

	@StringRes
	protected abstract int emptyRes();

	protected interface FetchDone {
		void done(List<Row> rows, int total);
	}

	/** Where the rows of something opened inside the card (a playlist's songs) come from. */
	protected interface FetchSource {
		void fetch(MainActivityDelegate a, FetchDone done);
	}

	protected final MainActivityDelegate act() {
		return host.activity();
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		LinearLayout head = header(() -> open(tabId()));
		count = text(ROLE_TERTIARY, 12.5f, false);
		count.setPadding(0, 0, px(4), 0);
		head.addView(count, Math.max(0, head.getChildCount() - 1));
		column.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

		backChip = chip(me.aap.utils.R.drawable.back, "", this::backOut);
		backChip.setVisibility(GONE);
		LinearLayout.LayoutParams bc = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34));
		bc.topMargin = px(4);
		column.addView(backChip, bc);

		firstLine = text(ROLE_SECONDARY, 13, false);
		firstLine.setSingleLine(true);
		firstLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
		firstLine.setVisibility(GONE);
		LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		fl.topMargin = px(4);
		column.addView(firstLine, fl);

		list = new LinearLayout(ctx);
		list.setOrientation(LinearLayout.VERTICAL);
		LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		ll.topMargin = px(4);
		// Long lists scroll inside the card; the top and bottom melt away while there is more.
		listScroll = new ScrollView(ctx);
		listScroll.setVerticalScrollBarEnabled(false);
		listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
		listScroll.setVerticalFadingEdgeEnabled(true);
		listScroll.setFadingEdgeLength(px(22));
		listScroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		LinearLayout.LayoutParams lsl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		lsl.topMargin = px(4);
		column.addView(listScroll, lsl);

		emptyText = text(ROLE_SECONDARY, 13, false);
		emptyText.setText(emptyRes());
		emptyText.setGravity(Gravity.CENTER);
		emptyText.setVisibility(GONE);
		column.addView(emptyText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		skeleton = new HomeSkeleton(ctx);
		skeleton.setVisibility(GONE);
		skeleton.setColor(softFill());
		LinearLayout.LayoutParams sk = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		sk.topMargin = px(4);
		column.addView(skeleton, sk);

		busy = spinner(28);
		busy.setVisibility(GONE);
		FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER);
		blp.topMargin = px(18);

		content.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		content.addView(busy, blp);
		built = true;
	}

	/** A list longer than the card scrolls, and keeps the page from taking that drag. */
	@Override
	protected int gestures() {
		return ((listScroll != null) && (list.getHeight() > listScroll.getHeight())) ? GESTURE_V : 0;
	}

	@Override
	protected void onBind() {
		render();
		if (isActive()) refresh(true);
	}

	@Override
	protected void onResized(int w, int h) {
		if (built) {
			updateCapacity();
			render();
		}
	}

	@Override
	protected void onSized(int w, int h) {
		if (!built) return;
		int before = capacity;
		updateCapacity();
		if (before != capacity) render();
	}

	private void updateCapacity() {
		int avail = heightPx() - px(28) - px(34) - px(8) - ((drillSource != null) ? px(38) : 0);
		capacity = Math.max(1, avail / px(48));
	}

	@Override
	protected void onActive() {
		act().addBroadcastListener(contentListener, ActivityListener.FRAGMENT_CONTENT_CHANGED);
		// Drawn again whenever it comes back on screen, whatever happened to it while away.
		shownSignature = "";
		render();
		refresh(false);
	}

	@Override
	protected void onInactive() {
		act().removeBroadcastListener(contentListener);
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.sw("art", ctx.getString(R.string.home_opt_artwork), true));
		l.addAll(moreOptions());
		return l;
	}

	protected List<HomeOption> moreOptions() {
		return new ArrayList<>();
	}

	/** Opens something inside the card: its rows replace the list, under a chip that goes back. */
	protected final void drill(CharSequence title, FetchSource source) {
		drillSource = source;
		backChip.setText(title);
		showBack(true);
		reset();
	}

	private void backOut() {
		if (drillSource == null) return;
		drillSource = null;
		showBack(false);
		reset();
	}

	private void showBack(boolean on) {
		if (host.animate() && isShown()) {
			android.transition.AutoTransition t = new android.transition.AutoTransition();
			t.setDuration(240);
			android.transition.TransitionManager.beginDelayedTransition(column, t);
		}
		backChip.setVisibility(on ? VISIBLE : GONE);
	}

	/** What was shown is not what is asked for now: placeholders until the other rows are in. */
	private void reset() {
		rows = new ArrayList<>();
		total = 0;
		shownSignature = "";
		updateCapacity();
		refresh(true);
		render();
	}

	protected final void refresh(boolean force) {
		if (loading && !force) return;
		if (!force && ((SystemClock.uptimeMillis() - loadedAt) < STALE_MS)) return;
		loading = true;
		int gen = ++fetchGen;
		if (built) updateBusy();
		FetchSource src = (drillSource != null) ? drillSource : this::fetch;
		src.fetch(act(), (r, n) -> {
			if (gen != fetchGen) return;
			loading = false;
			loadedAt = SystemClock.uptimeMillis();
			rows = r;
			total = n;
			render();
		});
	}

	/** Placeholder rows (a spinner in a strip) show while there is nothing to show yet. */
	private void updateBusy() {
		boolean on = loading && rows.isEmpty();
		boolean rowsWait = on && !isCompact();
		if ((skeleton.getVisibility() == VISIBLE) != rowsWait) {
			skeleton.setVisibility(rowsWait ? VISIBLE : GONE);
			if (rowsWait && host.animate()) {
				skeleton.setAlpha(0f);
				skeleton.animate().alpha(1f).setDuration(200).start();
			}
		}
		boolean spin = on && isCompact();
		if ((busy.getVisibility() == VISIBLE) != spin) busy.setVisibility(spin ? VISIBLE : GONE);
		if (rowsWait) skeleton.setRows(48, optBool("art", true) ? 40 : 0, 40, (getCellsW() >= 4) ? 2 : 1);
	}

	@Override
	protected void onStyleChanged() {
		if (skeleton != null) skeleton.setColor(softFill());
	}

	private void render() {
		if (!built) return;
		updateBusy();
		boolean none = rows.isEmpty();
		int cols = (getCellsW() >= 4) ? 2 : 1;
		// Drawn again only when something it shows changed: the rows blinked on every refresh.
		StringBuilder sig = new StringBuilder();
		sig.append(total).append('|').append(isCompact()).append('|').append(cols).append('|').append(capacity)
				.append('|').append(optBool("art", true)).append('|').append(loading && none)
				.append('|').append(drillSource != null);
		for (Row r : rows) sig.append('|').append(r.title()).append(r.sub());
		String signature = sig.toString();
		if (signature.equals(shownSignature)) return;
		shownSignature = signature;

		forget(list);
		list.removeAllViews();
		count.setText(total > 0 ? String.valueOf(total) : "");
		emptyText.setVisibility(none && !isCompact() && !loading ? VISIBLE : GONE);
		listScroll.setVisibility(none || isCompact() ? GONE : VISIBLE);
		firstLine.setVisibility((!none && isCompact()) ? VISIBLE : GONE);
		if (none) return;
		if (isCompact()) {
			firstLine.setText(rows.get(0).title());
			return;
		}
		int shown = rows.size();
		boolean art = optBool("art", true);
		LinearLayout line = null;
		for (int i = 0; i < shown; i++) {
			if ((i % cols) == 0) {
				line = new LinearLayout(ctx);
				line.setOrientation(LinearLayout.HORIZONTAL);
				list.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)));
			}
			View v = rowView(rows.get(i), art);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
			if ((i % cols) > 0) lp.setMarginStart(px(10));
			line.addView(v, lp);
			// Rows come in with a short fade, so a refresh doesn't pop.
			if (host.animate()) {
				v.setAlpha(0f);
				v.animate().alpha(1f).setDuration(220).setStartDelay(Math.min(i, 6) * 30L).start();
			}
		}
		if ((focusRow >= 0) && (list.getChildCount() > 0)) {
			View target = list.getChildAt(Math.min(list.getChildCount() - 1, focusRow / cols));
			listScroll.post(() -> listScroll.smoothScrollTo(0, Math.max(0, target.getTop() - px(48))));
		}
	}

	private View rowView(Row r, boolean art) {
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);

		LinearLayout main = new LinearLayout(ctx);
		main.setOrientation(LinearLayout.HORIZONTAL);
		main.setGravity(Gravity.CENTER_VERTICAL);
		main.setBackground(HomeUi.pressable(ripple(), 0, px(14)));
		main.setClickable(true);
		main.setFocusable(true);
		main.setOnClickListener(v -> r.click().run());
		main.setContentDescription(r.title());
		row.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

		if (art) {
			ImageView th = HomeImages.thumb(ctx, 40, 40, 10, softFill());
			if (r.art() != null) HomeImages.loadItem(act(), th, r.art());
			LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(40), px(40));
			tlp.setMarginEnd(px(10));
			main.addView(th, tlp);
		}
		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.setGravity(Gravity.CENTER_VERTICAL);
		TextView t = text(ROLE_PRIMARY, 13.5f, true);
		t.setText(r.title());
		t.setSingleLine(true);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		texts.addView(t);
		if ((r.sub() != null) || (r.subFill() != null)) {
			TextView s = text(ROLE_SECONDARY, 12, false);
			s.setSingleLine(true);
			s.setEllipsize(android.text.TextUtils.TruncateAt.END);
			if (r.sub() != null) s.setText(r.sub());
			if (r.subFill() != null) r.subFill().accept(s);
			texts.addView(s);
		}
		main.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		if (r.play() != null) {
			ImageView p = iconButton(R.drawable.play, 36, ROLE_ICON, R.string.action_play, r.play());
			row.addView(p);
		}
		return row;
	}
}
