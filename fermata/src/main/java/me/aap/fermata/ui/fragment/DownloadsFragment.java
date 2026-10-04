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
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
import me.aap.utils.ui.menu.OverlayMenu;

/**
 * The Downloads tab, in two sections: what is being downloaded right now (a card per video that
 * fills up as it arrives, with the transfer rate, Pause/Resume and Cancel), and what's on the phone
 * (a list or grid of cards that play from the file). Drawn in the Music tab's palette, like the
 * Fuel Log and Data Usage tabs.
 */
public class DownloadsFragment extends MainActivityFragment implements YtDownloads.Listener {
	private static final int VT_HEADER = 0;
	private static final int VT_PROGRESS = 1;
	private static final int VT_ROW = 2;
	private static final int VT_GRID = 3;
	private static final int VT_EMPTY = 4;
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

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		Context palette = new ContextThemeWrapper(ctx, MusicPlayerFragment.isLightTheme(ctx) ?
				R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
		RecyclerView rv = new RecyclerView(palette);
		rv.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		rv.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
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
		boolean grid = DownloadsAddon.isGrid();
		builder.addItem(R.id.ytdl_view_toggle, grid ? R.drawable.playlist : R.drawable.view_grid,
				grid ? R.string.ytdl_view_list : R.string.ytdl_view_grid).setHandler(i -> {
			DownloadsAddon.setGrid(!grid);
			updateSpan();
			rebuild();
			return true;
		});
		YtDownloadMenu.addControls(builder, YtDownloads.get());
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

	private static final int HEADER_PROGRESS = 0;
	private static final int HEADER_DONE = 1;

	private void rebuild() {
		YtDownloads d = YtDownloads.get();
		List<Entry> progress = new ArrayList<>();
		List<Entry> done = new ArrayList<>();
		for (Entry e : d.snapshot()) {
			if (e.state == State.DONE) done.add(e);
			else progress.add(e);
		}
		// Newest first.
		Collections.reverse(done);

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
			same = (a.type == b.type) && java.util.Objects.equals(a.id, b.id);
		}

		rows.clear();
		rows.addAll(n);
		// The same cards: only the numbers moved, so they are rebound in place -- no flicker.
		if (same) adapter.notifyItemRangeChanged(0, rows.size(), PAYLOAD);
		else adapter.notifyDataSetChanged();
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
		final int onAccent;

		Palette(Context ctx) {
			primary = color(ctx, R.attr.musicTextPrimary);
			secondary = color(ctx, R.attr.musicTextSecondary);
			fill = color(ctx, R.attr.musicChipFill);
			ripple = color(ctx, R.attr.musicChipRipple);
			accent = EffectsUi.accent(ctx);
			onAccent = EffectsUi.onAccent(accent);
		}

		private static int color(Context ctx, @AttrRes int attr) {
			TypedValue tv = new TypedValue();
			ctx.getTheme().resolveAttribute(attr, tv, true);
			return tv.data;
		}
	}

	/** A card whose left part, up to {@link #setFraction}, is filled with the accent color. */
	private static final class Card extends FrameLayout {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path clip = new Path();
		private final RectF rect = new RectF();
		private final float radius;
		private float fraction;

		Card(Context ctx, int fill, int accent, float radius) {
			super(ctx);
			this.radius = radius;
			paint.setColor((accent & 0x00FFFFFF) | 0x55000000);
			GradientDrawable bg = new GradientDrawable();
			bg.setColor(fill);
			bg.setCornerRadius(radius);
			setBackground(bg);
			setWillNotDraw(false);
			setClickable(true);
			setFocusable(true);
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

	private final class Holder extends RecyclerView.ViewHolder {
		TextView title;
		TextView subtitle;
		TextView status;
		ImageView thumb;
		ImageView pause;
		ImageView cancel;
		TextView act1;
		TextView act2;
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
			switch (type) {
				case VT_HEADER:
					return header(ctx);
				case VT_EMPTY:
					return empty(ctx);
				case VT_GRID:
					return gridCard(ctx);
				default:
					return rowCard(ctx, type == VT_PROGRESS);
			}
		}

		@Override
		public void onBindViewHolder(@NonNull Holder h, int position) {
			Row r = rows.get(position);
			YtDownloads d = YtDownloads.get();
			Context ctx = h.itemView.getContext();

			if (r.type == VT_HEADER) {
				bindHeader(h, Integer.parseInt(r.id), d);
				return;
			}
			if (r.type == VT_EMPTY) return;

			Entry e = d.getEntry(r.id);
			if (e == null) return;
			h.title.setText(e.getDisplayTitle());
			loadImage(h.thumb, "https://i.ytimg.com/vi/" + e.videoId + "/mqdefault.jpg");

			if (r.type == VT_PROGRESS) {
				bindProgress(h, e, d, ctx);
			} else {
				StringBuilder sb = new StringBuilder();
				String artist = e.artist;
				if ((artist != null) && !artist.isEmpty()) sb.append(artist).append(" • ");
				sb.append(ctx.getString(e.video ? R.string.ytdl_kind_video : R.string.ytdl_kind_audio));
				if (e.total > 0) sb.append(" • ").append(Formatter.formatShortFileSize(ctx, e.total));
				h.subtitle.setText(sb);
				h.card.setOnClickListener(v -> play(e.videoId));
				h.card.setOnLongClickListener(v -> {
					showMenu(e.videoId);
					return true;
				});
			}
		}

		private void bindHeader(Holder h, int which, YtDownloads d) {
			Context ctx = h.itemView.getContext();
			if (which == HEADER_PROGRESS) {
				h.title.setText(R.string.ytdl_section_progress);
				boolean busy = d.isBusy();
				h.act1.setVisibility(View.VISIBLE);
				h.act1.setText(busy ? R.string.ytdl_pause_all : R.string.ytdl_resume_all);
				h.act1.setOnClickListener(v -> {
					if (d.isBusy()) d.pauseAll();
					else d.resumeAll();
				});
				h.act2.setVisibility(View.VISIBLE);
				h.act2.setText(R.string.ytdl_cancel_all);
				h.act2.setOnClickListener(v -> d.cancelAll());
			} else {
				h.title.setText(R.string.ytdl_section_done);
				long bytes = 0;
				for (Entry e : d.getDownloaded()) bytes += e.total;
				h.act2.setVisibility(View.GONE);
				if (bytes > 0) {
					h.act1.setVisibility(View.VISIBLE);
					h.act1.setText(ctx.getString(R.string.ytdl_storage,
							Formatter.formatShortFileSize(ctx, bytes)));
					h.act1.setOnClickListener(null);
					h.act1.setClickable(false);
				} else {
					h.act1.setVisibility(View.GONE);
				}
			}
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
			h.subtitle.setText(e.video ? R.string.ytdl_kind_video : R.string.ytdl_kind_audio);
			h.pause.setImageResource(running ? R.drawable.pause : R.drawable.play);
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

		private ImageView thumb(Context ctx) {
			ImageView v = new ImageView(ctx);
			v.setScaleType(ImageView.ScaleType.CENTER_CROP);
			GradientDrawable ph = new GradientDrawable();
			ph.setColor(0x33808080);
			v.setBackground(ph);
			float r = dp(ctx, 10);
			v.setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View view, Outline o) {
					o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
				}
			});
			v.setClipToOutline(true);
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
			h.act1 = chip(ctx);
			h.act2 = chip(ctx);
			l.addView(h.act1);
			l.addView(h.act2);
			return h;
		}

		private TextView chip(Context ctx) {
			TextView t = text(ctx, 13, pal.secondary, false);
			t.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
			t.setClickable(true);
			t.setFocusable(true);
			t.setMaxLines(1);
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

		/** A full-width card: thumbnail on the left, texts, and (in progress) the buttons. */
		private Holder rowCard(Context ctx, boolean progress) {
			Card card = new Card(ctx, pal.fill, pal.accent, dp(ctx, 16));
			RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			lp.setMargins(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4));
			card.setLayoutParams(lp);
			card.setForeground(null);

			LinearLayout l = new LinearLayout(ctx);
			l.setOrientation(LinearLayout.HORIZONTAL);
			l.setGravity(Gravity.CENTER_VERTICAL);
			l.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10));
			card.addView(l, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

			Holder h = new Holder(card);
			h.card = card;
			h.thumb = thumb(ctx);
			l.addView(h.thumb, new LinearLayout.LayoutParams(dp(ctx, 112), dp(ctx, 63)));

			LinearLayout col = new LinearLayout(ctx);
			col.setOrientation(LinearLayout.VERTICAL);
			LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			cp.setMarginStart(dp(ctx, 12));
			cp.setMarginEnd(dp(ctx, 4));
			l.addView(col, cp);

			h.title = text(ctx, 15, pal.primary, true);
			h.title.setMaxLines(2);
			h.title.setEllipsize(TextUtils.TruncateAt.END);
			col.addView(h.title);
			h.subtitle = text(ctx, 13, pal.secondary, false);
			h.subtitle.setMaxLines(1);
			h.subtitle.setEllipsize(TextUtils.TruncateAt.END);
			col.addView(h.subtitle);

			if (progress) {
				h.status = text(ctx, 13, pal.primary, false);
				h.status.setMaxLines(2);
				h.status.setEllipsize(TextUtils.TruncateAt.END);
				LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
				sp.topMargin = dp(ctx, 2);
				col.addView(h.status, sp);

				h.pause = icon(ctx, R.drawable.pause);
				h.pause.setContentDescription(ctx.getString(R.string.ytdl_pause));
				l.addView(h.pause, new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)));
				h.cancel = icon(ctx, me.aap.utils.R.drawable.close);
				h.cancel.setContentDescription(ctx.getString(R.string.ytdl_cancel));
				l.addView(h.cancel, new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)));
			}
			return h;
		}

		/** A grid cell: the thumbnail on top, the texts under it. */
		private Holder gridCard(Context ctx) {
			Card card = new Card(ctx, pal.fill, pal.accent, dp(ctx, 16));
			RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			lp.setMargins(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
			card.setLayoutParams(lp);

			LinearLayout col = new LinearLayout(ctx);
			col.setOrientation(LinearLayout.VERTICAL);
			col.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 10));
			card.addView(col, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

			Holder h = new Holder(card);
			h.card = card;
			h.thumb = new ImageView(ctx) {
				@Override
				protected void onMeasure(int w, int hh) {
					// Always 16:9.
					int width = MeasureSpec.getSize(w);
					super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
							MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
				}
			};
			h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
			GradientDrawable ph = new GradientDrawable();
			ph.setColor(0x33808080);
			h.thumb.setBackground(ph);
			float r = dp(ctx, 10);
			h.thumb.setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View view, Outline o) {
					o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
				}
			});
			h.thumb.setClipToOutline(true);
			col.addView(h.thumb, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

			h.title = text(ctx, 14, pal.primary, true);
			h.title.setMaxLines(2);
			h.title.setEllipsize(TextUtils.TruncateAt.END);
			LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
			tp.topMargin = dp(ctx, 8);
			col.addView(h.title, tp);
			h.subtitle = text(ctx, 12, pal.secondary, false);
			h.subtitle.setMaxLines(2);
			h.subtitle.setEllipsize(TextUtils.TruncateAt.END);
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
