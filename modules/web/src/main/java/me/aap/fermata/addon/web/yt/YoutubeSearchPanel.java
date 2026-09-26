package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.spotify.SpotifyImportModel.Video;
import me.aap.fermata.spotify.YoutubeSearch;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;

/**
 * The YouTube tab's search results and Up next queue, shown natively over the page.
 * <p>
 * Searching used to navigate the page itself to YouTube's results, which tore down the player and
 * stopped whatever was playing -- on Android Auto that meant silence for as long as it took to
 * find the next video. This panel is a plain view laid over the WebView instead: the page (and
 * its video) is never touched, never hidden and never paused, so playback carries on underneath
 * while the user browses results. The search itself is the same key-less InnerTube lookup the
 * Spotify import uses ({@link YoutubeSearch}).
 * <p>
 * Tapping a result plays it now; its queue button puts it at the front of Up next (see
 * {@link YoutubeAddon#getUpNext()}), which plays before the current Favorites/Playlist continues.
 */
@SuppressLint("ViewConstructor")
final class YoutubeSearchPanel extends FrameLayout {
	private static final int MAX_RESULTS = 25;
	/** Shared: one search at a time is plenty, and a panel recreated with its fragment reuses it. */
	private static final ExecutorService executor = Executors.newSingleThreadExecutor();
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_NOTE = 1;
	private static final int TYPE_ACTION = 2;
	private static final int TYPE_VIDEO = 3;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final LruCache<String, Bitmap> images = new LruCache<>(60);
	private final List<Row> rows = new ArrayList<>();
	private final List<Video> results = new ArrayList<>();
	private final YoutubeFragment fragment;
	private final YoutubeAddon addon;
	private final Adapter adapter = new Adapter();
	private final Runnable upNextListener = () -> handler.post(this::refresh);
	@Nullable
	private String query;
	private boolean searching;
	private boolean failed;
	private int generation;

	YoutubeSearchPanel(Context ctx, YoutubeFragment fragment, YoutubeAddon addon) {
		super(ctx);
		this.fragment = fragment;
		this.addon = addon;
		// Mostly opaque: the rows must stay readable over a playing video, but the page underneath
		// showing through faintly makes it obvious nothing was closed or stopped.
		int bg = resolveColor(ctx, android.R.attr.colorBackground, Color.BLACK);
		setBackgroundColor((bg & 0x00FFFFFF) | 0xF2000000);
		// Swallow touches, so nothing reaches the page underneath.
		setClickable(true);

		RecyclerView list = new RecyclerView(ctx);
		list.setLayoutManager(new LinearLayoutManager(ctx));
		list.setAdapter(adapter);
		addView(list, new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		// Same as every scrollable screen: reserve room for the translucent tool/nav bars and the
		// control panel, which are all drawn over this.
		MainActivityDelegate.get(ctx).insetScrollableContent(list);
		refresh();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		addon.addUpNextListener(upNextListener);
		refresh();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		addon.removeUpNextListener(upNextListener);
		handler.removeCallbacksAndMessages(null);
		generation++;
	}

	@Nullable
	String getQuery() {
		return query;
	}

	void search(String q) {
		q = q.trim();
		if (q.isEmpty()) return;
		String search = q;
		int gen = ++generation;
		query = q;
		searching = true;
		failed = false;
		results.clear();
		refresh();

		executor.execute(() -> {
			List<Video> found = null;
			try {
				found = YoutubeSearch.search(search, MAX_RESULTS);
			} catch (Exception ex) {
				Log.e(ex, "YouTube search failed: ", search);
			}
			List<Video> result = found;
			handler.post(() -> {
				if (gen != generation) return;
				searching = false;
				failed = (result == null);
				results.clear();
				if (result != null) results.addAll(result);
				refresh();
			});
		});
	}

	/** Rebuilds the rows: Up next (if anything is queued) first, then the search results. */
	void refresh() {
		Context ctx = getContext();
		rows.clear();
		List<String> upNext = addon.getUpNext();

		if (!upNext.isEmpty()) {
			rows.add(Row.header(ctx.getString(me.aap.fermata.R.string.youtube_up_next_count,
					upNext.size())));
			rows.add(Row.note(ctx.getString(me.aap.fermata.R.string.youtube_up_next_hint)));
			for (String id : upNext) rows.add(Row.upNext(id));
			rows.add(Row.action(ctx.getString(me.aap.fermata.R.string.youtube_up_next_clear),
					addon::clearUpNext));
		}

		if (query == null) {
			rows.add(Row.note(ctx.getString(me.aap.fermata.R.string.youtube_search_intro)));
		} else {
			rows.add(Row.header(ctx.getString(me.aap.fermata.R.string.youtube_search_results, query)));
			if (searching) {
				rows.add(Row.note(ctx.getString(me.aap.fermata.R.string.youtube_searching)));
			} else if (failed) {
				rows.add(Row.note(ctx.getString(me.aap.fermata.R.string.youtube_search_failed)));
				String q = query;
				rows.add(Row.action(ctx.getString(me.aap.fermata.R.string.search), () -> search(q)));
			} else if (results.isEmpty()) {
				rows.add(Row.note(ctx.getString(me.aap.fermata.R.string.youtube_search_no_results)));
			} else {
				for (Video v : results) rows.add(Row.result(v));
			}
		}

		//noinspection NotifyDataSetChanged -- a handful of rows, rebuilt wholesale.
		adapter.notifyDataSetChanged();
	}

	private void loadImage(ImageView v, String url) {
		Object tag = v.getTag();
		v.setTag(url);
		Bitmap cached = images.get(url);
		if (cached != null) {
			v.setImageBitmap(cached);
			return;
		}
		if (url.equals(tag)) return;
		v.setImageDrawable(null);
		FermataApplication.get().getBitmapCache().getBitmap(v.getContext(), url, false, false).main()
				.onSuccess(bm -> {
					if (bm == null) return;
					images.put(url, bm);
					if (url.equals(v.getTag())) v.setImageBitmap(bm);
				});
	}

	private static String thumbnailUrl(String videoId) {
		return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
	}

	private static int resolveColor(Context ctx, int attr, int dflt) {
		TypedValue tv = new TypedValue();
		if (!ctx.getTheme().resolveAttribute(attr, tv, true)) return dflt;
		if ((tv.type >= TypedValue.TYPE_FIRST_COLOR_INT) && (tv.type <= TypedValue.TYPE_LAST_COLOR_INT))
			return tv.data;
		if (tv.resourceId != 0) return ctx.getColor(tv.resourceId);
		return dflt;
	}

	private static final class Row {
		final int type;
		@Nullable
		final String text;
		@Nullable
		final Runnable action;
		@Nullable
		final String upNextId;
		@Nullable
		final Video video;

		private Row(int type, @Nullable String text, @Nullable Runnable action,
								@Nullable String upNextId, @Nullable Video video) {
			this.type = type;
			this.text = text;
			this.action = action;
			this.upNextId = upNextId;
			this.video = video;
		}

		static Row header(String text) {
			return new Row(TYPE_HEADER, text, null, null, null);
		}

		static Row note(String text) {
			return new Row(TYPE_NOTE, text, null, null, null);
		}

		static Row action(String text, Runnable action) {
			return new Row(TYPE_ACTION, text, action, null, null);
		}

		static Row upNext(String videoId) {
			return new Row(TYPE_VIDEO, null, null, videoId, null);
		}

		static Row result(Video v) {
			return new Row(TYPE_VIDEO, null, null, null, v);
		}
	}

	private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

		@Override
		public int getItemCount() {
			return rows.size();
		}

		@Override
		public int getItemViewType(int position) {
			return rows.get(position).type;
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			Context ctx = parent.getContext();
			View v;

			if (viewType == TYPE_VIDEO) {
				v = LayoutInflater.from(ctx).inflate(me.aap.fermata.R.layout.spotify_import_alt_item,
						parent, false);
				int p = (int) UiUtils.toPx(ctx, 12);
				v.setPaddingRelative(p, v.getPaddingTop(), p, v.getPaddingBottom());
				v.findViewById(me.aap.fermata.R.id.si_check).setVisibility(GONE);
				v.findViewById(me.aap.fermata.R.id.si_preview).setFocusable(true);
			} else {
				TextView t = new TextView(ctx);
				t.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
				int h = (int) UiUtils.toPx(ctx, 16);
				int vp = (int) UiUtils.toPx(ctx, (viewType == TYPE_HEADER) ? 12 : 6);
				t.setPadding(h, vp, h, vp);

				if (viewType == TYPE_HEADER) {
					t.setTypeface(Typeface.DEFAULT_BOLD);
					t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
				} else if (viewType == TYPE_ACTION) {
					t.setTypeface(Typeface.DEFAULT_BOLD);
					t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
					t.setTextColor(resolveColor(ctx, android.R.attr.colorAccent, Color.CYAN));
					t.setMinHeight((int) UiUtils.toPx(ctx, 48));
					t.setGravity(android.view.Gravity.CENTER_VERTICAL);
					t.setFocusable(true);
					t.setClickable(true);
					TypedValue tv = new TypedValue();
					if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
							&& (tv.resourceId != 0)) {
						t.setBackgroundResource(tv.resourceId);
					}
				} else {
					t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
					t.setAlpha(0.75f);
				}

				v = t;
			}

			return new RecyclerView.ViewHolder(v) {
			};
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
			Row r = rows.get(position);

			if (r.type != TYPE_VIDEO) {
				TextView t = (TextView) h.itemView;
				t.setText(r.text);
				if (r.type == TYPE_ACTION) {
					Runnable a = r.action;
					t.setOnClickListener((a == null) ? null : x -> a.run());
				}
				return;
			}

			View v = h.itemView;
			TextView title = v.findViewById(me.aap.fermata.R.id.si_title);
			TextView detail = v.findViewById(me.aap.fermata.R.id.si_detail);
			ImageView button = v.findViewById(me.aap.fermata.R.id.si_preview);
			Context ctx = v.getContext();

			if (r.video != null) {
				Video video = r.video;
				title.setText(video.title);
				String d = (video.channel != null) ? video.channel : "";
				if (video.durationText != null) d = d.isEmpty() ? video.durationText :
						(d + " • " + video.durationText);
				detail.setText(d);
				loadImage(v.findViewById(me.aap.fermata.R.id.si_thumb), video.thumbnailUrl());
				button.setImageResource(me.aap.fermata.R.drawable.queue_music);
				button.setContentDescription(ctx.getString(me.aap.fermata.R.string.youtube_play_next));
				button.setOnClickListener(x -> fragment.queueVideo(video.videoId, video.title, true));
				v.setOnClickListener(x -> fragment.playVideoNow(video.videoId, video.title));
				v.setOnLongClickListener(x -> {
					fragment.showVideoActions(video.videoId, video.title);
					return true;
				});
			} else {
				String id = r.upNextId;
				if (id == null) return;
				String name = addon.getVideoTitle(id);
				title.setText(name);
				detail.setText(me.aap.fermata.R.string.youtube_up_next);
				loadImage(v.findViewById(me.aap.fermata.R.id.si_thumb), thumbnailUrl(id));
				button.setImageResource(me.aap.fermata.R.drawable.playlist_remove);
				button.setContentDescription(ctx.getString(me.aap.fermata.R.string.youtube_up_next_remove));
				button.setOnClickListener(x -> addon.removeUpNext(id));
				v.setOnClickListener(x -> fragment.playVideoNow(id, null));
				v.setOnLongClickListener(null);
			}
		}
	}
}
