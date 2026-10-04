package me.aap.fermata.ui.fragment;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.utils.async.Completed.completed;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.lib.MediaLib.ExternallyPlayableItem;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.EffectsUi;
import me.aap.fermata.ytdl.DownloadsAddon;
import me.aap.fermata.ytdl.YtDownloadMenu;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.fermata.ytdl.YtDownloads.Entry;
import me.aap.fermata.ytdl.YtDownloads.State;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.view.ImageButton;
import me.aap.utils.ui.view.ToolBarView;

/**
 * The Downloads tab, in two sections: what is being downloaded right now (a card per video that
 * fills up as it arrives, with the transfer rate, Pause/Resume and Cancel), and what's on the phone
 * (cards that play from the file). The cards are drawn like the Favorites and Playlists ones --
 * same backgrounds, the dark gradient over a full-bleed thumbnail in the grid -- and the title bar
 * has the same kind of buttons (view, sort, ...). Drawn in the Music tab's palette, like the Fuel
 * Log and Data Usage tabs.
 */
public class DownloadsFragment extends MainActivityFragment implements YtDownloads.Listener {
	private static final ToolBarView.Mediator TOOL_BAR = new ToolBar();
	private static final int VT_HEADER = 0;
	private static final int VT_PROGRESS = 1;
	private static final int VT_ROW = 2;
	private static final int VT_GRID = 3;
	private static final int VT_EMPTY = 4;
	private static final int HEADER_PROGRESS = 0;
	private static final int HEADER_DONE = 1;
	private static final Object PAYLOAD = new Object();
	private final List<Row> rows = new ArrayList<>();
	private Adapter adapter;
	private GridLayoutManager layout;
	private RecyclerView list;

	@Override
	public int getFragmentId() {
		return R.id.downloads_addon;
	}

	@NonNull
	@Override
	public CharSequence getTitle() {
		return getString(R.string.ytdl_title);
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return TOOL_BAR;
	}

	/** Lets a downloaded video play fullscreen from this tab, like a video from a list. */
	@Override
	public boolean isVideoModeSupported() {
		return true;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		Context palette = new ContextThemeWrapper(ctx, MusicPlayerFragment.isLightTheme(ctx) ?
				R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
		RecyclerView rv = new RecyclerView(palette);
		rv.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		rv.setVerticalScrollBarEnabled(true);
		return rv;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		list = (RecyclerView) view;
		layout = new GridLayoutManager(requireContext(), 1);
		layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
			@Override
			public int getSpanSize(int position) {
				if ((position < 0) || (position >= rows.size())) return layout.getSpanCount();
				return (rows.get(position).type == VT_GRID) ? 1 : layout.getSpanCount();
			}
		});
		list.setLayoutManager(layout);
		DefaultItemAnimator an = new DefaultItemAnimator();
		an.setSupportsChangeAnimations(false);
		list.setItemAnimator(an);
		adapter = new Adapter();
		list.setAdapter(adapter);
		// Reserves room under the translucent title bar, as every scrollable tab does.
		getActivityDelegate().insetScrollableContent(list);
		list.setClipChildren(false);
		list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if ((r - l) != (or - ol)) updateSpan();
		});
		updateSpan();
	}

	@Override
	public void onResume() {
		super.onResume();
		YtDownloads.get().addListener(this);
		rebuild();
	}

	@Override
	public void onPause() {
		super.onPause();
		YtDownloads.get().removeListener(this);
	}

	@Override
	public void onDownloadsChanged() {
		if (isAdded() && (adapter != null)) rebuild();
	}

	@Override
	public void contributeToNavBarMenu(OverlayMenu.Builder builder) {
		super.contributeToNavBarMenu(builder);
		YtDownloadMenu.addControls(builder, YtDownloads.get());
	}

	// ---------------------------------------------------------------------------------------------
	// Title bar

	/** Back, title and the buttons the Favorites and Playlists tabs have: view, sort; plus the downloads' own. */
	private static final class ToolBar implements ToolBarView.Mediator.BackTitle {
		@Override
		public void enable(ToolBarView tb, ActivityFragment f) {
			ToolBarView.Mediator.BackTitle.super.enable(tb, f);
			label(addButton(tb, DownloadsAddon.isGrid() ? R.drawable.playlist : R.drawable.view_grid,
					v -> with(v, DownloadsFragment::toggleView), R.id.ytdl_tool_view),
					R.string.view, 0);
			label(addButton(tb, R.drawable.sort, v -> with(v, DownloadsFragment::showSortMenu),
					R.id.ytdl_tool_sort), R.string.sort_by, 1);
			label(addButton(tb, R.drawable.pause, v -> YtDownloadMenu.togglePause(),
					R.id.ytdl_tool_pause), R.string.ytdl_pause_all, 2);
			label(addButton(tb, R.drawable.download_remove, v -> with(v, DownloadsFragment::confirmClear),
					R.id.ytdl_tool_clear), R.string.ytdl_clear, 3);
			if (f instanceof DownloadsFragment d) d.refreshToolBar();
		}

		private static void label(ImageButton b, @StringRes int label, int priority) {
			b.setContentDescription(b.getContext().getString(label));
			b.setToolBarPriority(priority);
		}

		private static void with(View v, java.util.function.Consumer<DownloadsFragment> c) {
			ActivityFragment f = MainActivityDelegate.get(v.getContext()).getActiveFragment();
			if (f instanceof DownloadsFragment d) c.accept(d);
		}
	}

	/** Icons and visibility that follow what's downloading and what's on the phone. */
	private void refreshToolBar() {
		if (!isAdded()) return;
		ToolBarView tb = getActivityDelegate().getToolBar();
		YtDownloads d = YtDownloads.get();
		ImageButton view = tb.findViewById(R.id.ytdl_tool_view);
		if (view != null) {
			view.setImageResource(DownloadsAddon.isGrid() ? R.drawable.playlist : R.drawable.view_grid);
		}
		ImageButton pause = tb.findViewById(R.id.ytdl_tool_pause);
		if (pause != null) {
			boolean busy = d.isBusy();
			pause.setImageResource(busy ? R.drawable.pause : R.drawable.download);
			pause.setVisibility((busy || d.hasResumable()) ? View.VISIBLE : View.GONE);
			pause.setContentDescription(getString(busy ? R.string.ytdl_pause_all : R.string.ytdl_resume_all));
		}
		View clear = tb.findViewById(R.id.ytdl_tool_clear);
		if (clear != null) clear.setVisibility(d.getDownloaded().isEmpty() ? View.GONE : View.VISIBLE);
	}

	private void toggleView() {
		DownloadsAddon.setGrid(!DownloadsAddon.isGrid());
		updateSpan();
		rebuild();
	}

	private void showSortMenu() {
		MainActivityDelegate a = getActivityDelegate();
		a.getToolBarMenu().show(b -> {
			int sort = DownloadsAddon.getSort();
			b.setSelectionHandler(item -> {
				int id = item.getItemId();
				if (id == R.id.ytdl_sort_date) DownloadsAddon.setSort(DownloadsAddon.SORT_DATE);
				else if (id == R.id.ytdl_sort_name) DownloadsAddon.setSort(DownloadsAddon.SORT_NAME);
				else if (id == R.id.ytdl_sort_size) DownloadsAddon.setSort(DownloadsAddon.SORT_SIZE);
				else return false;
				rebuild();
				return true;
			});
			b.addItem(R.id.ytdl_sort_date, R.string.ytdl_sort_date)
					.setChecked(sort == DownloadsAddon.SORT_DATE, true);
			b.addItem(R.id.ytdl_sort_name, R.string.ytdl_sort_name)
					.setChecked(sort == DownloadsAddon.SORT_NAME, true);
			b.addItem(R.id.ytdl_sort_size, R.string.ytdl_sort_size)
					.setChecked(sort == DownloadsAddon.SORT_SIZE, true);
		});
	}

	private void confirmClear() {
		if (YtDownloads.get().getDownloaded().isEmpty()) return;
		UiUtils.showQuestion(requireContext(), R.string.ytdl_clear_title, R.string.ytdl_clear_question,
				R.drawable.download_remove).onSuccess(v -> YtDownloads.get().clearDownloaded());
	}

	// ---------------------------------------------------------------------------------------------
	// Data

	private static final class Row {
		final int type;
		@Nullable
		final String id;

		Row(int type, @Nullable String id) {
			this.type = type;
			this.id = id;
		}
	}

	private void rebuild() {
		YtDownloads d = YtDownloads.get();
		List<Entry> progress = new ArrayList<>();
		List<Entry> done = new ArrayList<>();
		for (Entry e : d.snapshot()) {
			if (e.state == State.DONE) done.add(e);
			else progress.add(e);
		}

		switch (DownloadsAddon.getSort()) {
			case DownloadsAddon.SORT_NAME ->
					done.sort((a, b) -> a.getDisplayTitle().compareToIgnoreCase(b.getDisplayTitle()));
			case DownloadsAddon.SORT_SIZE -> done.sort((a, b) -> Long.compare(b.total, a.total));
			// Newest first.
			default -> Collections.reverse(done);
		}

		List<Row> n = new ArrayList<>();
		if (!progress.isEmpty()) {
			n.add(new Row(VT_HEADER, String.valueOf(HEADER_PROGRESS)));
			for (Entry e : progress) n.add(new Row(VT_PROGRESS, e.videoId));
		}
		n.add(new Row(VT_HEADER, String.valueOf(HEADER_DONE)));
		if (done.isEmpty()) {
			n.add(new Row(VT_EMPTY, null));
		} else {
			int t = DownloadsAddon.isGrid() ? VT_GRID : VT_ROW;
			for (Entry e : done) n.add(new Row(t, e.videoId));
		}

		boolean same = n.size() == rows.size();
		for (int i = 0; same && (i < n.size()); i++) {
			Row a = n.get(i);
			Row b = rows.get(i);
			same = (a.type == b.type) && Objects.equals(a.id, b.id);
		}

		rows.clear();
		rows.addAll(n);
		// The same cards: only the numbers moved, so they are rebound in place -- no flicker.
		if (same) adapter.notifyItemRangeChanged(0, rows.size(), PAYLOAD);
		else adapter.notifyDataSetChanged();
		refreshToolBar();
	}

	private void updateSpan() {
		if ((layout == null) || (list == null)) return;
		int span = 1;
		if (DownloadsAddon.isGrid()) {
			int w = list.getWidth();
			if (w == 0) w = getResources().getDisplayMetrics().widthPixels;
			span = Math.max(2, w / UiUtils.toIntPx(requireContext(), 170));
		}
		if (layout.getSpanCount() != span) layout.setSpanCount(span);
	}

	// ---------------------------------------------------------------------------------------------
	// Actions

	private void play(String videoId) {
		MainActivityDelegate a = getActivityDelegate();
		a.getLib().getItem(YtDownloads.ID_PREFIX + videoId).main().onSuccess(it -> {
			if (!(it instanceof PlayableItem pi)) return;
			if (MusicPlayer.isMusicModeActive(a)) MusicPlayer.play(a, pi, true);
			else if (pi instanceof ExternallyPlayableItem ext) a.playExternally(ext, pi);
		});
	}

	private void showMenu(String videoId) {
		MainActivityDelegate a = getActivityDelegate();
		a.getLib().getItem(YtDownloads.ID_PREFIX + videoId).main().onSuccess(it -> {
			if (!(it instanceof PlayableItem pi)) return;
			a.getContextMenu().show(b -> {
				b.setTitle(pi.getName());
				b.addItem(R.id.item_play, R.drawable.play, R.string.play).setHandler(i -> {
					play(videoId);
					return true;
				});
				if (pi.isFavoriteItem()) {
					b.addItem(R.id.favorites_remove, R.drawable.favorite_filled,
							R.string.favorites_remove).setHandler(i -> {
						pi.getLib().getFavorites().removeItem(pi);
						return true;
					});
				} else {
					b.addItem(R.id.favorites_add, R.drawable.favorite, R.string.favorites_add)
							.setHandler(i -> {
								pi.getLib().getFavorites().addItem(pi);
								return true;
							});
				}
				a.addPlaylistMenu(b, completed(Collections.singletonList(pi)));
				YtDownloadMenu.addTo(b, a, Collections.singletonList(pi));
			});
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Views

	private static final class Palette {
		final int primary;
		final int secondary;
		final int fill;
		final int ripple;
		final int accent;

		Palette(Context ctx) {
			primary = color(ctx, R.attr.musicTextPrimary);
			secondary = color(ctx, R.attr.musicTextSecondary);
			fill = color(ctx, R.attr.musicChipFill);
			ripple = color(ctx, R.attr.musicChipRipple);
			accent = EffectsUi.accent(ctx);
		}

		private static int color(Context ctx, @AttrRes int attr) {
			TypedValue tv = new TypedValue();
			ctx.getTheme().resolveAttribute(attr, tv, true);
			return tv.data;
		}
	}

	/**
	 * A card in the look of a Favorites/Playlists one ({@code bg} is the very background those
	 * use) whose left part, up to {@link #setFraction}, is filled with the accent color.
	 */
	private static final class Card extends FrameLayout {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path clip = new Path();
		private final RectF rect = new RectF();
		private final float radius;
		private float fraction;

		Card(Context ctx, @DrawableRes int bg, int accent, float radius) {
			super(ctx);
			this.radius = radius;
			paint.setColor((accent & 0x00FFFFFF) | 0x66000000);
			setBackground(ContextCompat.getDrawable(ctx, bg));
			setWillNotDraw(false);
			setClickable(true);
			setFocusable(true);
			setClipToOutline(true);
			setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View v, Outline o) {
					o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
				}
			});
		}

		void setFraction(float f) {
			f = Math.max(0f, Math.min(1f, f));
			if (f == fraction) return;
			fraction = f;
			invalidate();
		}

		@Override
		protected void onDraw(@NonNull Canvas c) {
			super.onDraw(c);
			if (fraction <= 0f) return;
			rect.set(0, 0, getWidth(), getHeight());
			clip.reset();
			clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
			int save = c.save();
			c.clipPath(clip);
			c.drawRect(0, 0, getWidth() * fraction, getHeight(), paint);
			c.restoreToCount(save);
		}
	}

	/** An image that is as tall as it is wide. */
	private static final class SquareImage extends ImageView {
		SquareImage(Context ctx) {
			super(ctx);
		}

		@Override
		protected void onMeasure(int w, int h) {
			int width = MeasureSpec.getSize(w);
			super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
					MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
		}
	}

	private static final class Holder extends RecyclerView.ViewHolder {
		TextView title;
		TextView subtitle;
		TextView status;
		ImageView thumb;
		ImageView pause;
		ImageView cancel;
		TextView chip1;
		TextView chip2;
		TextView info;
		TextView quality;
		Card card;

		Holder(View v) {
			super(v);
		}
	}

	private final class Adapter extends RecyclerView.Adapter<Holder> {
		private Palette pal;

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
		public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
			Context ctx = parent.getContext();
			if (pal == null) pal = new Palette(ctx);
			return switch (type) {
				case VT_HEADER -> header(ctx);
				case VT_EMPTY -> empty(ctx);
				case VT_GRID -> gridCard(ctx);
				default -> rowCard(ctx, type == VT_PROGRESS);
			};
		}

		@Override
		public void onBindViewHolder(@NonNull Holder h, int position) {
			Row r = rows.get(position);
			YtDownloads d = YtDownloads.get();
			Context ctx = h.itemView.getContext();

			if (r.type == VT_HEADER) {
				bindHeader(h, Integer.parseInt(Objects.requireNonNull(r.id)), d);
				return;
			}
			if (r.type == VT_EMPTY) return;

			Entry e = d.getEntry(r.id);
			if (e == null) return;
			h.title.setText(e.getDisplayTitle());
			// "720p", or "Audio": a small chip, over the thumbnail's corner in the grid, at the right in a row.
			h.quality.setText(e.video ? (shownHeight(e) + "p") : ctx.getString(R.string.ytdl_kind_audio));
			loadImage(h.thumb, "https://i.ytimg.com/vi/" + e.videoId + "/mqdefault.jpg");

			if (r.type == VT_PROGRESS) {
				bindProgress(h, e, d, ctx);
			} else {
				StringBuilder sb = new StringBuilder();
				if (e.durationMs > 0) {
					sb.append(time(e.durationMs));
					if (e.total > 0) sb.append(" • ");
				}
				if (e.total > 0) sb.append(Formatter.formatShortFileSize(ctx, e.total));
				h.subtitle.setText(sb);
				h.card.setOnClickListener(v -> play(e.videoId));
				h.card.setOnLongClickListener(v -> {
					showMenu(e.videoId);
					return true;
				});
			}
		}

		private int shownHeight(Entry e) {
			return (e.gotHeight > 0) ? e.gotHeight : e.height;
		}

		private String time(long ms) {
			long s = ms / 1000;
			long h = s / 3600;
			return (h > 0) ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, (s / 60) % 60, s % 60) :
					String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60);
		}

		private void bindHeader(Holder h, int which, YtDownloads d) {
			if (which == HEADER_PROGRESS) {
				h.title.setText(R.string.ytdl_section_progress);
				h.info.setVisibility(View.GONE);
				boolean busy = d.isBusy();
				setChip(h.chip1, busy ? R.drawable.pause : R.drawable.download,
						busy ? R.string.ytdl_pause_all : R.string.ytdl_resume_all);
				h.chip1.setOnClickListener(v -> YtDownloadMenu.togglePause());
				setChip(h.chip2, me.aap.utils.R.drawable.close, R.string.ytdl_cancel_all);
				h.chip2.setOnClickListener(v -> d.cancelAll());
			} else {
				h.title.setText(R.string.ytdl_section_done);
				long bytes = 0;
				List<Entry> done = d.getDownloaded();
				for (Entry e : done) bytes += e.total;
				Context ctx = h.itemView.getContext();
				if (bytes > 0) {
					h.info.setVisibility(View.VISIBLE);
					h.info.setText(Formatter.formatShortFileSize(ctx, bytes));
				} else {
					h.info.setVisibility(View.GONE);
				}
				h.chip2.setVisibility(View.GONE);
				if (done.isEmpty()) {
					h.chip1.setVisibility(View.GONE);
				} else {
					setChip(h.chip1, R.drawable.download_remove, R.string.ytdl_clear);
					h.chip1.setOnClickListener(v -> confirmClear());
				}
			}
		}

		private void setChip(TextView c, @DrawableRes int icon, @StringRes int text) {
			Context ctx = c.getContext();
			c.setVisibility(View.VISIBLE);
			// Just the icon where the words would crowd the title out (a phone held upright).
			boolean narrow = ctx.getResources().getConfiguration().screenWidthDp < 600;
			c.setText(narrow ? "" : ctx.getString(text));
			c.setContentDescription(ctx.getString(text));
			c.setCompoundDrawablePadding(narrow ? 0 : dp(ctx, 6));
			c.setPadding(dp(ctx, narrow ? 11 : 10), 0, dp(ctx, narrow ? 11 : 14), 0);
			c.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
			c.setCompoundDrawableTintList(ColorStateList.valueOf(pal.primary));
		}

		private void bindProgress(Holder h, Entry e, YtDownloads d, Context ctx) {
			float f = (e.total > 0) ? ((float) e.bytes / e.total) : 0f;
			h.card.setFraction(f);
			boolean running = (e.state == State.DOWNLOADING) || (e.state == State.QUEUED);
			String status;

			switch (e.state) {
				case DOWNLOADING:
					StringBuilder sb = new StringBuilder();
					if (e.speed > 0) sb.append(Formatter.formatShortFileSize(ctx, e.speed)).append("/s");
					if (e.total > 0) {
						if (sb.length() > 0) sb.append(" • ");
						sb.append(Math.round(f * 100)).append("% • ").append(ctx.getString(
								R.string.ytdl_progress_format, Formatter.formatShortFileSize(ctx, e.bytes),
								Formatter.formatShortFileSize(ctx, e.total)));
					}
					status = sb.toString();
					break;
				case QUEUED:
					status = ctx.getString(R.string.ytdl_waiting);
					break;
				case FAILED:
					status = (e.error != null) ? e.error : ctx.getString(R.string.ytdl_failed);
					break;
				default:
					status = ctx.getString(R.string.ytdl_paused) +
							((e.total > 0) ? (" • " + Math.round(f * 100) + '%') : "");
					break;
			}

			h.status.setText(status);
			h.subtitle.setVisibility(View.GONE);
			h.pause.setImageResource(running ? R.drawable.pause : R.drawable.play);
			h.pause.setContentDescription(ctx.getString(running ? R.string.ytdl_pause : R.string.ytdl_resume));
			h.pause.setOnClickListener(v -> {
				if (running) d.pause(e.videoId);
				else d.resume(e.videoId);
			});
			h.cancel.setOnClickListener(v -> d.remove(e.videoId));
			h.card.setOnClickListener(v -> {
				if (!running) d.resume(e.videoId);
			});
		}

		// -----------------------------------------------------------------------------------------

		private int dp(Context ctx, int v) {
			return UiUtils.toIntPx(ctx, v);
		}

		private TextView text(Context ctx, int sp, int color, boolean bold) {
			TextView t = new TextView(ctx);
			t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
			t.setTextColor(color);
			if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
			return t;
		}

		private ImageView icon(Context ctx, @DrawableRes int res) {
			ImageView v = new ImageView(ctx);
			v.setImageResource(res);
			v.setImageTintList(ColorStateList.valueOf(pal.primary));
			int p = dp(ctx, 10);
			v.setPadding(p, p, p, p);
			v.setClickable(true);
			v.setFocusable(true);
			return v;
		}

		private ImageView thumbView(Context ctx) {
			ImageView v = new ImageView(ctx);
			v.setScaleType(ImageView.ScaleType.CENTER_CROP);
			v.setBackgroundColor(0x33808080);
			return v;
		}

		private Holder header(Context ctx) {
			LinearLayout l = new LinearLayout(ctx);
			l.setOrientation(LinearLayout.HORIZONTAL);
			l.setGravity(Gravity.CENTER_VERTICAL);
			l.setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 12), dp(ctx, 6));
			l.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			Holder h = new Holder(l);
			h.title = text(ctx, 18, pal.primary, true);
			l.addView(h.title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
			h.info = text(ctx, 13, pal.secondary, false);
			h.info.setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0);
			l.addView(h.info);
			h.chip1 = chip(ctx);
			h.chip2 = chip(ctx);
			l.addView(h.chip1);
			l.addView(h.chip2);
			return h;
		}

		/** A pill with an icon, in the look of the Music tab's chips. */
		private TextView chip(Context ctx) {
			TextView t = text(ctx, 13, pal.primary, true);
			t.setGravity(Gravity.CENTER_VERTICAL);
			t.setMaxLines(1);
			t.setCompoundDrawablePadding(dp(ctx, 6));
			t.setPadding(dp(ctx, 10), 0, dp(ctx, 14), 0);
			t.setMinHeight(dp(ctx, 36));
			GradientDrawable bg = new GradientDrawable();
			bg.setCornerRadius(dp(ctx, 18));
			bg.setColor(pal.fill);
			t.setBackground(bg);
			t.setClickable(true);
			t.setFocusable(true);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
			lp.setMarginStart(dp(ctx, 8));
			t.setLayoutParams(lp);
			return t;
		}

		/** The small dark pill with the quality in it, readable over any thumbnail. */
		private TextView qualityChip(Context ctx) {
			TextView t = text(ctx, 11, 0xFFFFFFFF, true);
			t.setSingleLine(true);
			t.setGravity(Gravity.CENTER);
			t.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
			GradientDrawable bg = new GradientDrawable();
			bg.setCornerRadius(dp(ctx, 10));
			bg.setColor(0xB0000000);
			t.setBackground(bg);
			return t;
		}

		private Holder empty(Context ctx) {
			TextView t = text(ctx, 14, pal.secondary, false);
			t.setText(R.string.ytdl_empty);
			t.setGravity(Gravity.CENTER);
			t.setPadding(dp(ctx, 32), dp(ctx, 24), dp(ctx, 32), dp(ctx, 24));
			t.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			return new Holder(t);
		}

		/** A full-width card, like a list row: thumbnail on the left, texts, (in progress) buttons. */
		private Holder rowCard(Context ctx, boolean progress) {
			Card card = new Card(ctx, R.drawable.media_item_list_bg, pal.accent, dp(ctx, 22));
			RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			lp.setMargins(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4));
			card.setLayoutParams(lp);

			LinearLayout l = new LinearLayout(ctx);
			l.setOrientation(LinearLayout.HORIZONTAL);
			l.setGravity(Gravity.CENTER_VERTICAL);
			card.addView(l, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

			Holder h = new Holder(card);
			h.card = card;
			h.thumb = thumbView(ctx);
			// As tall as a list row, the full height of the card.
			l.addView(h.thumb, new LinearLayout.LayoutParams(dp(ctx, 84), dp(ctx, progress ? 96 : 84)));

			LinearLayout col = new LinearLayout(ctx);
			col.setOrientation(LinearLayout.VERTICAL);
			col.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
			LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			cp.setMarginStart(dp(ctx, 14));
			cp.setMarginEnd(dp(ctx, 6));
			l.addView(col, cp);

			h.title = text(ctx, 15, pal.primary, true);
			h.title.setMaxLines(2);
			h.title.setEllipsize(TextUtils.TruncateAt.END);
			col.addView(h.title);
			h.subtitle = text(ctx, 13, pal.secondary, false);
			h.subtitle.setMaxLines(1);
			h.subtitle.setEllipsize(TextUtils.TruncateAt.END);
			col.addView(h.subtitle);

			h.quality = qualityChip(ctx);
			LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
			qp.setMarginEnd(dp(ctx, progress ? 2 : 16));
			l.addView(h.quality, qp);

			if (progress) {
				h.status = text(ctx, 13, pal.primary, false);
				h.status.setMaxLines(2);
				h.status.setEllipsize(TextUtils.TruncateAt.END);
				LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
				sp.topMargin = dp(ctx, 2);
				col.addView(h.status, sp);

				h.pause = icon(ctx, R.drawable.pause);
				l.addView(h.pause, new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)));
				h.cancel = icon(ctx, me.aap.utils.R.drawable.close);
				h.cancel.setContentDescription(ctx.getString(R.string.ytdl_cancel));
				LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44));
				xp.setMarginEnd(dp(ctx, 8));
				l.addView(h.cancel, xp);
			}
			return h;
		}

		/**
		 * A grid cell, like a Favorites/Playlists one: the thumbnail fills the card and the title
		 * and details sit on the dark gradient over its bottom.
		 */
		private Holder gridCard(Context ctx) {
			Card card = new Card(ctx, R.drawable.media_item_bg, pal.accent, dp(ctx, 12));
			RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			lp.setMargins(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
			card.setLayoutParams(lp);

			Holder h = new Holder(card);
			h.card = card;
			ImageView img = new SquareImage(ctx);
			img.setScaleType(ImageView.ScaleType.CENTER_CROP);
			img.setBackgroundColor(0x33808080);
			h.thumb = img;
			card.addView(img, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

			View scrim = new View(ctx);
			scrim.setBackgroundResource(R.drawable.media_item_grid_scrim);
			card.addView(scrim, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

			h.quality = qualityChip(ctx);
			FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
					Gravity.TOP | Gravity.END);
			qp.setMargins(0, dp(ctx, 8), dp(ctx, 8), 0);
			card.addView(h.quality, qp);

			LinearLayout col = new LinearLayout(ctx);
			col.setOrientation(LinearLayout.VERTICAL);
			col.setPadding(dp(ctx, 10), 0, dp(ctx, 10), dp(ctx, 10));
			FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT,
					Gravity.BOTTOM);
			card.addView(col, cp);

			h.title = text(ctx, 14, 0xFFFFFFFF, false);
			h.title.setMaxLines(2);
			h.title.setEllipsize(TextUtils.TruncateAt.END);
			h.title.setShadowLayer(3f, 0f, 1f, 0xB0000000);
			col.addView(h.title);
			h.subtitle = text(ctx, 12, 0xFFFFFFFF, false);
			h.subtitle.setMaxLines(1);
			h.subtitle.setEllipsize(TextUtils.TruncateAt.END);
			h.subtitle.setShadowLayer(3f, 0f, 1f, 0xB0000000);
			col.addView(h.subtitle);
			return h;
		}
	}

	private static void loadImage(ImageView v, String url) {
		if (url.equals(v.getTag())) return;
		v.setTag(url);
		v.setImageDrawable(null);
		FermataApplication.get().getBitmapCache().getBitmap(v.getContext(), url, false, false).main()
				.onSuccess(bm -> {
					if ((bm != null) && url.equals(v.getTag())) v.setImageBitmap(bm);
				});
	}
}
