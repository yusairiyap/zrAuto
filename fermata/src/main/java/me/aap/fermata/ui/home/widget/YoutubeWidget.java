package me.aap.fermata.ui.home.widget;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.fermata.spotify.YoutubeSearch;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.SpotifyImportFragment;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeImages;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeSkeleton;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.utils.app.App;

/**
 * Videos YouTube suggests: found by searching for what plays now (or a fixed taste picked in the
 * options), as rows with their thumbnails. A tap plays one -- in the player that is playing, if
 * there is one. Searched only while the card is on screen, and again when the song changes.
 */
final class YoutubeWidget extends HomeWidgetView {
	private static final long FRESH_MS = 10 * 60_000L;
	private static final long CHANGE_DELAY_MS = 2500;
	private static final String[] TASTES = {"", "trending music", "lofi chill music", "top hits playlist"};

	private LinearLayout column;
	private LinearLayout list;
	private TextView message;
	private ProgressBar busy;
	private HomeSkeleton skeleton;
	/** The song changed (or a refresh was asked for): what shows is out of date until the new search is in. */
	private boolean stale;
	private List<Video> videos = new ArrayList<>();
	private String lastQuery = "";
	private long loadedAt;
	private boolean loading;
	private int generation;
	private int capacity = 3;
	private final Runnable reload = () -> load(false);
	private final FermataServiceUiBinder.Listener itemListener = new FermataServiceUiBinder.Listener() {
		@Override
		public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
			if (optInt("source", 0) != 0) return;
			// Seeking, pausing and the like announce the same song again: only another song matters.
			post(() -> {
				if (query().equals(lastQuery)) return;
				removeCallbacks(reload);
				postDelayed(reload, CHANGE_DELAY_MS);
				stale = true;
				render();
			});
		}
	};

	YoutubeWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		LinearLayout head = header(() -> open(R.id.youtube_fragment));
		ImageView refresh = iconButton(R.drawable.refresh, 30, ROLE_SECONDARY, R.string.home_refresh,
				() -> {
					stale = true;
					render();
					load(true);
				});
		head.addView(refresh, Math.max(0, head.getChildCount() - 1));
		column.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

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
		busy = spinner(28);
		busy.setVisibility(GONE);
		FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER);
		blp.bottomMargin = px(26);
		content.addView(busy, blp);
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.choice("source", ctx.getString(R.string.home_opt_based_on), 0,
				ctx.getString(R.string.home_yt_playing), ctx.getString(R.string.home_yt_trending),
				ctx.getString(R.string.home_yt_chill), ctx.getString(R.string.home_yt_hits)));
		l.add(HomeOption.sw("thumbs", ctx.getString(R.string.home_opt_artwork), true));
		return l;
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
		int avail = heightPx() - px(28) - px(34) - px(8);
		capacity = Math.max(1, avail / px(54));
	}

	@Override
	protected void onActive() {
		host.activity().getMediaServiceBinder().addBroadcastListener(itemListener);
		// Right after the app starts, the network read waits until the start is over (see HomeHost#isSettled).
		if (!host.isSettled()) host.whenSettled(() -> {
			if (isActive()) load(false);
		});
		else load(false);
	}

	@Override
	protected void onInactive() {
		host.activity().getMediaServiceBinder().removeBroadcastListener(itemListener);
		removeCallbacks(reload);
	}

	/** What to search for. */
	private String query() {
		int source = optInt("source", 0);
		if (source > 0) return TASTES[Math.min(source, TASTES.length - 1)];
		MainActivityDelegate a = host.activity();
		PlayableItem pi = Action.getFavoritableItem(a);
		if (pi == null) pi = a.getMediaSessionCallback().getResumeItem();
		String name = (pi == null) ? "" : pi.getName();
		// A video that was never opened is named by its id: nothing to search by.
		if (name.matches("[A-Za-z0-9_-]{11}")) name = "";
		name = name.replaceFirst("\\.[A-Za-z0-9]{2,4}$", "").trim();
		// A title not known yet (it is read a moment after the song starts) is not a new search.
		if (name.isEmpty()) return lastQuery.isEmpty() ? TASTES[1] : lastQuery;
		return name;
	}

	private void load(boolean force) {
		if (column == null) return;
		if (!MusicPlayer.hasYoutube()) {
			showMessage(R.string.home_yt_off);
			return;
		}
		String q = query();
		boolean fresh = q.equals(lastQuery) && ((SystemClock.uptimeMillis() - loadedAt) < FRESH_MS);
		if (loading || (!force && fresh && !videos.isEmpty())) {
			// The same search as before: what shows is not out of date after all.
			if (!loading && stale) {
				stale = false;
				render();
			}
			return;
		}
		loading = true;
		lastQuery = q;
		int gen = ++generation;
		render();
		App.get().getExecutor().submitTask(() -> YoutubeSearch.search(q, 12)).main()
				.onCompletion((found, err) -> {
					loading = false;
					if (gen != generation) return;
					loadedAt = SystemClock.uptimeMillis();
					stale = false;
					if ((found == null) || found.isEmpty()) {
						if (videos.isEmpty()) showMessage((err != null) ? R.string.home_yt_failed : R.string.home_yt_none);
						else render();
					} else {
						videos = found;
						render();
					}
					// The song changed again while this was searching.
					if (isActive() && (optInt("source", 0) == 0) && !q.equals(query())) {
						stale = true;
						render();
						removeCallbacks(reload);
						postDelayed(reload, CHANGE_DELAY_MS);
					}
				});
	}

	private void showMessage(int res) {
		list.setVisibility(GONE);
		skeleton.setVisibility(GONE);
		busy.setVisibility(GONE);
		message.setVisibility(VISIBLE);
		message.setText(res);
		message.setGravity(Gravity.CENTER);
	}

	private void render() {
		if (column == null) return;
		boolean wait = MusicPlayer.hasYoutube() && (stale || (loading && videos.isEmpty()));
		if (wait) {
			// Placeholder rows (a spinner in a strip) stand in for the list until the search is in.
			list.removeAllViews();
			list.setVisibility(GONE);
			message.setVisibility(GONE);
			boolean strip = isCompact();
			busy.setVisibility(strip ? VISIBLE : GONE);
			if (!strip) {
				skeleton.setRows(54, optBool("thumbs", true) ? 80 : 0, 45, (getCellsW() >= 4) ? 2 : 1);
				if (skeleton.getVisibility() != VISIBLE) {
					skeleton.setVisibility(VISIBLE);
					if (host.animate()) {
						skeleton.setAlpha(0f);
						skeleton.animate().alpha(1f).setDuration(200).start();
					}
				}
			}
			return;
		}
		skeleton.setVisibility(GONE);
		busy.setVisibility(GONE);
		list.removeAllViews();
		if (videos.isEmpty()) return;
		message.setVisibility(GONE);
		list.setVisibility(VISIBLE);
		boolean thumbs = optBool("thumbs", true);
		int cols = (getCellsW() >= 4) ? 2 : 1;
		int shown = Math.min(videos.size(), (isCompact() ? 1 : capacity) * cols);
		LinearLayout line = null;
		for (int i = 0; i < shown; i++) {
			if ((i % cols) == 0) {
				line = new LinearLayout(ctx);
				list.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(54)));
			}
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
			if ((i % cols) > 0) lp.setMarginStart(px(10));
			View rv = row(videos.get(i), thumbs);
			line.addView(rv, lp);
			if (host.animate() && isShown()) {
				rv.setAlpha(0f);
				rv.animate().alpha(1f).setDuration(220).setStartDelay(Math.min(i, 6) * 30L).start();
			}
		}
	}

	private View row(Video v, boolean thumbs) {
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setBackground(HomeUi.pressable(ripple(), 0, px(14)));
		row.setPadding(px(2), px(3), px(4), px(3));
		row.setClickable(true);
		row.setFocusable(true);
		row.setContentDescription(v.title);
		row.setOnClickListener(x -> play(v));
		if (thumbs && !isCompact()) {
			ImageView th = HomeImages.thumb(ctx, 80, 45, 9, softFill());
			HomeImages.loadUri(host.activity(), th, v.thumbnailUrl());
			LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(80), px(45));
			tlp.setMarginEnd(px(10));
			row.addView(th, tlp);
		}
		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		TextView t = text(ROLE_PRIMARY, 13, true);
		t.setMaxLines(2);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		t.setText(v.title);
		texts.addView(t);
		if (v.channel != null) {
			TextView c = text(ROLE_SECONDARY, 11.5f, false);
			c.setSingleLine(true);
			c.setEllipsize(android.text.TextUtils.TruncateAt.END);
			c.setText(v.channel + ((v.durationText != null) ? " · " + v.durationText : ""));
			texts.addView(c);
		}
		row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		return row;
	}

	private void play(Video v) {
		MainActivityDelegate a = host.activity();
		// Played where it is: in the player that has the page (music or video as that is going), and the
		// screen stays on Home. Only without the YouTube addon the video opens as a link.
		if (MusicPlayer.playYoutubeQuietly(a, v.videoId, v.title)) return;
		SpotifyImportFragment.previewVideo(a, v);
	}
}
