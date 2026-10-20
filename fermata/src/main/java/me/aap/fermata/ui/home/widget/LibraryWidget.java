package me.aap.fermata.ui.home.widget;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
		column.addView(list, ll);

		emptyText = text(ROLE_SECONDARY, 13, false);
		emptyText.setText(emptyRes());
		emptyText.setGravity(Gravity.CENTER);
		emptyText.setVisibility(GONE);
		column.addView(emptyText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		content.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		built = true;
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
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		super.onSizeChanged(w, h, ow, oh);
		if (!built) return;
		int before = capacity;
		updateCapacity();
		if (before != capacity) render();
	}

	private void updateCapacity() {
		int avail = getHeight() - px(28) - px(34) - px(8);
		capacity = Math.max(1, avail / px(48));
	}

	@Override
	protected void onActive() {
		act().addBroadcastListener(contentListener, ActivityListener.FRAGMENT_CONTENT_CHANGED);
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

	protected final void refresh(boolean force) {
		if (loading) return;
		if (!force && ((SystemClock.uptimeMillis() - loadedAt) < STALE_MS)) return;
		loading = true;
		fetch(act(), (r, n) -> {
			loading = false;
			loadedAt = SystemClock.uptimeMillis();
			rows = r;
			total = n;
			render();
		});
	}

	private void render() {
		if (!built) return;
		list.removeAllViews();
		count.setText(total > 0 ? String.valueOf(total) : "");
		boolean none = rows.isEmpty();
		emptyText.setVisibility(none && !isCompact() ? VISIBLE : GONE);
		list.setVisibility(none || isCompact() ? GONE : VISIBLE);
		firstLine.setVisibility((!none && isCompact()) ? VISIBLE : GONE);
		if (none) return;
		if (isCompact()) {
			firstLine.setText(rows.get(0).title());
			return;
		}
		int cols = (getCellsW() >= 4) ? 2 : 1;
		int shown = Math.min(rows.size(), capacity * cols);
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
