package me.aap.fermata.ui.home.widget;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeImages;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeSkeleton;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.YoutubeCommentsView;
import me.aap.fermata.ytcomments.YtComments;
import me.aap.fermata.ytcomments.YtComments.Comment;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.utils.app.App;

/**
 * The top comments of the video that plays, as a short list: who wrote each, when, how many liked it, and the start of the
 * text. The header or any comment opens the full comments card, as the comments action does. Loaded only while the card is on
 * screen, and again when the song changes, with the same placeholders as the YouTube suggestions. Newest instead of Top is an
 * option.
 */
final class CommentsWidget extends HomeWidgetView {
	private static final long FRESH_MS = 10 * 60_000L;
	private static final long CHANGE_DELAY_MS = 2500;
	private static final int ROW_DP = 64;

	private LinearLayout column;
	private LinearLayout list;
	private TextView message;
	private HomeSkeleton skeleton;
	/** The song changed (or a refresh was asked for): what shows is out of date until the new comments are in. */
	private boolean stale;
	private List<Comment> comments = new ArrayList<>();
	/** The video and order the comments in {@link #comments} are for. */
	private String loadedFor = "";
	private long loadedAt;
	private boolean loading;
	private int generation;
	private int capacity = 2;
	private final Runnable reload = () -> load(false);
	private final FermataServiceUiBinder.Listener itemListener = new FermataServiceUiBinder.Listener() {
		@Override
		public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
			// Seeking, pausing and the like announce the same song again: only another video matters.
			post(() -> {
				if (videoId().equals(loadedFor.split("/")[0])) return;
				removeCallbacks(reload);
				postDelayed(reload, CHANGE_DELAY_MS);
				stale = true;
				render();
			});
		}
	};

	CommentsWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		column.addView(header(this::openComments), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

		list = new LinearLayout(ctx);
		list.setOrientation(LinearLayout.VERTICAL);
		LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		ll.topMargin = px(4);
		column.addView(list, ll);

		skeleton = new HomeSkeleton(ctx);
		skeleton.setVisibility(GONE);
		skeleton.setColor(softFill());
		LinearLayout.LayoutParams sk = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		sk.topMargin = px(4);
		column.addView(skeleton, sk);

		message = text(ROLE_SECONDARY, 13, false);
		message.setGravity(Gravity.CENTER);
		message.setVisibility(GONE);
		column.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.choice("sort", ctx.getString(R.string.home_opt_comments_sort), 0,
				ctx.getString(R.string.yt_comments_sort_top), ctx.getString(R.string.yt_comments_sort_new)));
		l.add(HomeOption.sw("avatars", ctx.getString(R.string.home_opt_avatars), true));
		return l;
	}

	@Override
	protected void onOptionChanged(String key) {
		if (!"sort".equals(key) && !"avatars".equals(key)) return;
		if (column == null) return;
		if ("sort".equals(key)) {
			stale = true;
			load(true);
		} else {
			render();
		}
	}

	@Override
	protected void onStyleChanged() {
		if (skeleton != null) skeleton.setColor(softFill());
	}

	@Override
	protected void onBind() {
		render();
		if (isActive()) load(false);
	}

	@Override
	protected void onResized(int w, int h) {
		if (column != null) {
			updateCapacity();
			render();
		}
	}

	@Override
	protected void onSized(int w, int h) {
		if (column == null) return;
		int before = capacity;
		updateCapacity();
		if (before != capacity) render();
	}

	private void updateCapacity() {
		int avail = heightPx() - px(34) - px(8);
		capacity = isCompact() ? 1 : Math.max(1, avail / px(ROW_DP));
	}

	@Override
	protected void onActive() {
		host.activity().getMediaServiceBinder().addBroadcastListener(itemListener);
		load(false);
	}

	@Override
	protected void onInactive() {
		host.activity().getMediaServiceBinder().removeBroadcastListener(itemListener);
		removeCallbacks(reload);
	}

	private void openComments() {
		YoutubeCommentsView.showForCurrent(host.activity());
	}

	/** The video that plays (empty when none has a YouTube id). */
	private String videoId() {
		String id = YtDownloads.videoIdOf(Action.getFavoritableItem(host.activity()));
		return (id == null) ? "" : id;
	}

	private void load(boolean force) {
		if (column == null) return;
		if (!MusicPlayer.hasYoutube()) {
			showMessage(R.string.home_yt_off);
			return;
		}
		String id = videoId();
		if (id.isEmpty()) {
			comments = new ArrayList<>();
			loadedFor = "";
			showMessage(R.string.yt_comments_nothing);
			return;
		}
		boolean newest = optInt("sort", 0) == 1;
		String key = id + "/" + (newest ? "new" : "top");
		boolean fresh = key.equals(loadedFor) && ((SystemClock.uptimeMillis() - loadedAt) < FRESH_MS);
		if (loading || (!force && fresh && !comments.isEmpty())) {
			// The same video as before: what shows is not out of date after all.
			if (!loading && stale) {
				stale = false;
				render();
			}
			return;
		}
		loading = true;
		loadedFor = key;
		int gen = ++generation;
		render();
		App.get().getExecutor().submitTask(() -> fetch(id, newest)).main()
				.onCompletion((page, err) -> {
					loading = false;
					if (gen != generation) return;
					loadedAt = SystemClock.uptimeMillis();
					stale = false;
					if (err != null) {
						loadedFor = "";
						if (comments.isEmpty()) showMessage(R.string.yt_comments_failed);
						else render();
					} else if (page.disabled) {
						comments = new ArrayList<>();
						showMessage(R.string.yt_comments_off);
					} else if (page.comments.isEmpty()) {
						comments = new ArrayList<>();
						showMessage(R.string.yt_comments_empty);
					} else {
						comments = page.comments;
						render();
					}
				});
	}

	/** The first page of comments, or the page of the newest ones (the same as the comments card's Newest). */
	private static YtComments.Page fetch(String id, boolean newest) throws IOException {
		YtComments.Page p = YtComments.load(id);
		if (newest && !p.disabled && (p.sortNew != null)) p = YtComments.more(p.sortNew);
		return p;
	}

	private void showMessage(int res) {
		list.setVisibility(GONE);
		skeleton.setVisibility(GONE);
		message.setVisibility(VISIBLE);
		message.setText(res);
	}

	private void render() {
		if (column == null) return;
		boolean wait = MusicPlayer.hasYoutube() && (stale || (loading && comments.isEmpty()));
		if (wait) {
			// Placeholder rows stand in for the comments until they are in.
			list.removeAllViews();
			list.setVisibility(GONE);
			message.setVisibility(GONE);
			skeleton.setRows(ROW_DP, optBool("avatars", true) ? 30 : 0, 30, 1);
			if (skeleton.getVisibility() != VISIBLE) {
				skeleton.setVisibility(VISIBLE);
				if (host.animate()) {
					skeleton.setAlpha(0f);
					skeleton.animate().alpha(1f).setDuration(200).start();
				}
			}
			return;
		}
		skeleton.setVisibility(GONE);
		list.removeAllViews();
		if (comments.isEmpty()) return;
		message.setVisibility(GONE);
		list.setVisibility(VISIBLE);
		boolean avatars = optBool("avatars", true) && !isCompact();
		int shown = Math.min(comments.size(), capacity);
		for (int i = 0; i < shown; i++) {
			View rv = row(comments.get(i), avatars);
			list.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(ROW_DP)));
			if (host.animate() && isShown()) {
				rv.setAlpha(0f);
				rv.animate().alpha(1f).setDuration(220).setStartDelay(Math.min(i, 6) * 30L).start();
			}
		}
	}

	private View row(Comment c, boolean avatars) {
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setBackground(HomeUi.pressable(ripple(), 0, px(14)));
		row.setPadding(px(2), px(3), px(4), px(3));
		row.setClickable(true);
		row.setFocusable(true);
		row.setContentDescription(c.text);
		row.setOnClickListener(v -> openComments());
		if (avatars && (c.avatarUrl != null)) {
			ImageView av = HomeImages.thumb(ctx, 30, 30, 15, softFill());
			HomeImages.loadUri(host.activity(), av, c.avatarUrl);
			LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(px(30), px(30));
			alp.setMarginEnd(px(10));
			row.addView(av, alp);
		}
		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		TextView name = text(ROLE_PRIMARY, 12.5f, true);
		name.setSingleLine(true);
		name.setEllipsize(android.text.TextUtils.TruncateAt.END);
		name.setText(c.creator ? c.author + "  ✓" : c.author);
		texts.addView(name);
		StringBuilder meta = new StringBuilder(c.published);
		if (!c.likes.isEmpty()) meta.append("  ·  ").append(c.likes);
		TextView when = text(ROLE_SECONDARY, 11, false);
		when.setSingleLine(true);
		when.setEllipsize(android.text.TextUtils.TruncateAt.END);
		when.setText(meta);
		texts.addView(when);
		TextView body = text(ROLE_PRIMARY, 13, false);
		body.setMaxLines(isCompact() ? 3 : 2);
		body.setEllipsize(android.text.TextUtils.TruncateAt.END);
		body.setText(c.text);
		texts.addView(body);
		row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		return row;
	}
}
