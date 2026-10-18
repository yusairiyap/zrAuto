package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.action.CarNav;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ytcomments.YtComments;
import me.aap.fermata.ytcomments.YtComments.Comment;
import me.aap.fermata.ytcomments.YtComments.Page;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.utils.log.Log;

/**
 * The comments of the YouTube video that plays, in a card in the look of the playlist picker and
 * the All in One card (same panel, pills and slide up), over the whole app: the commenter's picture,
 * name and time, the text, its likes, and a button to open the replies under it, which load inline.
 * <p>
 * Signed in to YouTube in the app's browser, a box at the bottom posts a comment, and each comment's
 * Reply button turns it into a reply to that one. Signed out, there is no box and no Reply button:
 * the comments are only read. Car mode (steering wheel keys) moves through the comments and the
 * reply buttons like any other card, and never offers writing (there is no keyboard to write with).
 */
public final class YoutubeCommentsView {
	private static final int TYPE_COMMENT = 0;
	private static final int TYPE_MORE_REPLIES = 1;
	private static final int TYPE_FOOTER = 2;
	/** Longer than this (characters) shows a "Read more" until it's tapped. */
	private static final int LONG_TEXT = 200;
	private static YoutubeCommentsView open;

	private final MainActivityDelegate a;
	private final Context ctx;
	private final ViewGroup host;
	private String videoId;
	private String videoTitle;
	/** Beside fullscreen video (a big screen, the car screen) rather than over everything. */
	private final boolean docked;
	private final boolean car;
	private final float density;
	private final int primary;
	private final int secondary;
	private final int chipFill;
	private final int ripple;
	private final int accent;
	private final int onAccent;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newFixedThreadPool(2);
	private final LruCache<String, Bitmap> images = new LruCache<>(60);
	private final List<Row> rows = new ArrayList<>();
	private FrameLayout overlay;
	private FrameLayout card;
	private FrameLayout.LayoutParams cardLp;
	private RecyclerView list;
	private CommentsSkeletonView skeleton;
	private Adapter adapter;
	private TextView titleView;
	private TextView videoLine;
	// The video's views and likes, right under its title.
	private TextView statsLine;
	private LinearLayout sortRow;
	private TextView sortTop;
	private TextView sortNew;
	private LinearLayout statusRow;
	private ProgressBar progress;
	private TextView status;
	private LinearLayout compose;
	private LinearLayout replyingRow;
	private TextView replyingTo;
	private EditText input;
	private View sendButton;
	private ViewTreeObserver.OnGlobalLayoutListener keyboardListener;
	private int fullH;
	private int baseBottom;
	/** The overlay's height when it was first laid out: a smaller one later is a resized window. */
	private int openHeight;

	private boolean dismissing;
	private boolean destroyed;
	private boolean loadingMore;
	private boolean moreFailed;
	private boolean posting;
	/** Signed in to YouTube in the app's browser, read once when the card opens. */
	private boolean loggedIn;
	private int generation;
	// What the first batch told.
	@Nullable
	private String nextToken;
	@Nullable
	private String createParams;
	@Nullable
	private String sortTopToken;
	@Nullable
	private String sortNewToken;
	@Nullable
	private String count;
	private boolean sortingNew;
	/** The comment the box replies to, null for a new comment. */
	@Nullable
	private Row replyTo;

	/** A line of the list: a comment, the button for more replies under one, or the list's end. */
	private static final class Row {
		final int type;
		@Nullable
		final Comment c;
		/** The comment a reply is under. */
		@Nullable
		final Row parent;
		boolean textOpen;
		// Of a comment: its replies.
		boolean repliesOpen;
		boolean repliesLoading;
		boolean repliesLoaded;
		@Nullable
		String repliesNext;
		/** The replies loaded so far, shown while {@link #repliesOpen}. */
		final List<Row> replyRows = new ArrayList<>();
		/** How many rows of them are in the list now. */
		int shown;
		@Nullable
		private Row more;

		Row(int type, @Nullable Comment c, @Nullable Row parent) {
			this.type = type;
			this.c = c;
			this.parent = parent;
			if ((c != null) && (parent == null)) repliesNext = c.repliesToken;
		}

		Row moreRow() {
			if (more == null) more = new Row(TYPE_MORE_REPLIES, null, this);
			return more;
		}
	}

	private YoutubeCommentsView(MainActivityDelegate a, Context ctx, ViewGroup host, String videoId,
															String videoTitle, boolean docked) {
		this.a = a;
		this.ctx = ctx;
		this.host = host;
		this.videoId = videoId;
		this.videoTitle = videoTitle;
		this.docked = docked;
		this.car = a.isCarActivity();
		this.density = ctx.getResources().getDisplayMetrics().density;
		this.primary = color(R.attr.musicTextPrimary);
		this.secondary = color(R.attr.musicTextSecondary);
		this.chipFill = color(R.attr.musicChipFill);
		this.ripple = color(R.attr.musicChipRipple);
		this.accent = EffectsUi.accent(ctx);
		this.onAccent = EffectsUi.onAccent(accent);
	}

	// ---------------------------------------------------------------------------------------------
	// Opening

	/** The YouTube comments action: the comments of what plays. */
	public static void showForCurrent(@NonNull MainActivityDelegate a) {
		// Beside the video the floating button is still there to press: again closes the column.
		YoutubeCommentsView o = open;
		if ((o != null) && o.docked && dismissOpen()) return;
		PlayableItem pi = Action.getFavoritableItem(a);
		String id = YtDownloads.videoIdOf(pi);
		if (id == null) {
			TopToast.show(R.drawable.comments, R.string.yt_comments_nothing);
			return;
		}
		// Posted: this also runs from a menu that is still closing, over the same overlay.
		new Handler(Looper.getMainLooper()).post(() -> show(a, id, pi.getName()));
	}

	public static void show(@NonNull MainActivityDelegate a, @NonNull String videoId,
													@NonNull String title) {
		dismissOpen();
		ViewGroup host = a.findViewById(R.id.main_activity);
		if ((host == null) || (host.getWidth() == 0)) {
			Log.e("No view to show the comments over");
			return;
		}
		YoutubeCommentsView v = new YoutubeCommentsView(a, EffectsUi.palette(a.getContext()), host,
				videoId, title, shouldDock(a, host));
		open = v;
		v.build();
	}

	/**
	 * Whether the comments go beside the video: only over fullscreen video, only when Settings allow
	 * it, and only where there's room for a video and a column of comments next to each other (the
	 * car screen, or a landscape tablet); a phone gets the card over the video as before.
	 */
	private static boolean shouldDock(MainActivityDelegate a, ViewGroup host) {
		if (!a.isVideoMode() || (a.getActiveVideoView() == null)) return false;
		if (!a.getPrefs().getBooleanPref(MainActivityPrefs.COMMENTS_SPLIT)) return false;
		if (a.isCarActivity()) return true;
		float density = host.getResources().getDisplayMetrics().density;
		int w = host.getWidth();
		int h = host.getHeight();
		return (w > h) && (Math.min(w, h) >= Math.round(480 * density));
	}

	/**
	 * Fullscreen video is being left (a tab change, Back, the page leaving fullscreen): the column
	 * goes with it at once, fading out as the video does, rather than sliding away on its own clock
	 * over whatever comes next.
	 */
	public static void onVideoLeft() {
		YoutubeCommentsView v = open;
		if ((v != null) && v.docked) v.dismissFast();
	}

	/** Picture-in-picture: no comments, docked or over everything, in the small window. */
	public static void onPictureInPicture() {
		YoutubeCommentsView v = open;
		if (v != null) v.dismissFast();
	}

	/** Closes the card if it's open (for the back button); returns whether it was. */
	public static boolean dismissOpen() {
		YoutubeCommentsView v = open;
		if ((v == null) || (v.overlay == null) || (v.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		v.dismiss();
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// The card

	private void build() {
		loggedIn = YtComments.isLoggedIn();
		overlay = new FrameLayout(ctx);
		overlay.setFocusable(false);
		if (docked) {
			// No scrim and no click of its own: whatever isn't the column is the video, which keeps
			// its taps. Below the control panel and the floating buttons (10dp) so those stay usable
			// over the column, above the video (5dp).
			overlay.setClickable(false);
			overlay.setElevation(dp(7));
		} else {
			overlay.setClickable(true);
			overlay.setElevation(dp(30));
			overlay.setBackgroundColor(0x55000000);
			overlay.setOnClickListener(v -> {
				if (input != null) hideKeyboard();
				dismiss();
			});
		}
		// Car mode: the steering wheel moves through the comments; long previous closes.
		CarNav.markModalScope(overlay, this::dismiss);
		overlay.setAlpha(0f);

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(16), dp(16), dp(16), dp(12));

		// Title row: icon, "Comments · 1,234", close.
		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(8), dp(2), dp(4), dp(2));
		ImageView hi = new ImageView(ctx);
		hi.setImageResource(R.drawable.comments);
		hi.setImageTintList(ColorStateList.valueOf(accent));
		header.addView(hi, new LinearLayout.LayoutParams(dp(26), dp(26)));
		titleView = text(ctx.getString(R.string.yt_comments), 18, primary, true);
		titleView.setPadding(dp(10), 0, 0, 0);
		titleView.setSingleLine(true);
		titleView.setEllipsize(TextUtils.TruncateAt.END);
		header.addView(titleView, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		ImageView close = new ImageView(ctx);
		close.setImageResource(R.drawable.close_small);
		close.setImageTintList(ColorStateList.valueOf(secondary));
		close.setScaleType(ImageView.ScaleType.CENTER);
		close.setBackground(pressable(0, 22));
		close.setClickable(true);
		close.setFocusable(true);
		close.setOnClickListener(v -> {
			if (input != null) hideKeyboard();
			dismiss();
		});
		header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
		root.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// What it is for.
		TextView name = text(videoTitle, 13, secondary, false);
		videoLine = name;
		name.setSingleLine(true);
		name.setEllipsize(TextUtils.TruncateAt.END);
		name.setPadding(dp(8), dp(2), dp(8), dp(6));
		root.addView(name, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		statsLine = text("", 13, secondary, false);
		statsLine.setSingleLine(true);
		statsLine.setPadding(dp(8), 0, dp(8), dp(6));
		statsLine.setVisibility(View.GONE);
		root.addView(statsLine, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// Top / Newest, once the first batch has said how to get them.
		sortRow = new LinearLayout(ctx);
		sortRow.setOrientation(LinearLayout.HORIZONTAL);
		sortRow.setPadding(dp(4), 0, dp(4), dp(6));
		sortTop = pill(ctx.getString(R.string.yt_comments_sort_top));
		sortNew = pill(ctx.getString(R.string.yt_comments_sort_new));
		sortTop.setOnClickListener(v -> sortBy(false));
		sortNew.setOnClickListener(v -> sortBy(true));
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(36));
		slp.setMarginEnd(dp(8));
		sortTop.setPadding(dp(16), 0, dp(16), 0);
		sortNew.setPadding(dp(16), 0, dp(16), 0);
		sortRow.addView(sortTop, slp);
		sortRow.addView(sortNew, slp);
		sortRow.setVisibility(View.GONE);
		root.addView(sortRow, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		statusRow = new LinearLayout(ctx);
		statusRow.setOrientation(LinearLayout.HORIZONTAL);
		statusRow.setGravity(Gravity.CENTER_VERTICAL);
		statusRow.setPadding(dp(8), dp(2), dp(8), dp(6));
		progress = new ProgressBar(ctx);
		progress.setIndeterminate(true);
		statusRow.addView(progress, new LinearLayout.LayoutParams(dp(18), dp(18)));
		status = text(ctx.getString(R.string.yt_comments_loading), 13, secondary, false);
		status.setPadding(dp(8), 0, 0, 0);
		statusRow.addView(status, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		root.addView(statusRow, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// The comments scroll.
		list = new RecyclerView(ctx);
		list.setLayoutManager(new LinearLayoutManager(ctx));
		list.setVerticalScrollBarEnabled(false);
		list.setOverScrollMode(View.OVER_SCROLL_NEVER);
		adapter = new Adapter();
		list.setAdapter(adapter);
		if (list.getItemAnimator() instanceof SimpleItemAnimator sia) {
			sia.setSupportsChangeAnimations(false);
		}
		list.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
				if (dy > 0) maybeLoadMore();
			}
		});
		// The first batch's placeholder sits in the list's place: rows the shape of comments,
		// shimmering until the real ones arrive.
		FrameLayout listHolder = new FrameLayout(ctx);
		listHolder.addView(list, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		skeleton = new CommentsSkeletonView(ctx, secondary);
		skeleton.setVisibility(View.GONE);
		listHolder.addView(skeleton, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		root.addView(listHolder, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		// The box for writing, only where it can be used (see updateCompose()).
		if (!car) {
			compose = buildCompose();
			compose.setVisibility(View.GONE);
			root.addView(compose, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		}

		card = new FrameLayout(ctx);
		card.setClickable(true);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(color(R.attr.musicPanelFill));
		bg.setCornerRadius(dp(docked ? 22 : 28));
		card.setBackground(bg);
		card.setElevation(dp(docked ? 4 : 12));
		card.addView(root, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		if (docked) {
			buildDock();
			card.addView(buildDockHandle(), new FrameLayout.LayoutParams(dp(26), MATCH_PARENT,
					Gravity.START));
		} else {
			// As tall as there is room for: the card keeps its size while the comments come in.
			cardLp = OverlayCard.params(a, host, Integer.MAX_VALUE);
			fullH = cardLp.height;
			baseBottom = cardLp.bottomMargin;
		}
		overlay.addView(card, cardLp);
		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		if (!car) {
			keyboardListener = this::onLayoutChanged;
			overlay.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);
		}

		if (docked) {
			openDock();
		} else {
			card.setTranslationY(dp(120));
			card.setAlpha(0f);
			card.animate().translationY(0f).alpha(1f).setDuration(260)
					.setInterpolator(new DecelerateInterpolator(1.6f)).start();
			overlay.animate().alpha(1f).setDuration(220).start();
		}
		loadFirst(null);
	}

	/** The "Replying to ..." line and the box with its send button. */
	private LinearLayout buildCompose() {
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(0, dp(8), 0, 0);

		replyingRow = new LinearLayout(ctx);
		replyingRow.setOrientation(LinearLayout.HORIZONTAL);
		replyingRow.setGravity(Gravity.CENTER_VERTICAL);
		replyingRow.setPadding(dp(12), 0, dp(4), 0);
		replyingTo = text("", 12.5f, accent, true);
		replyingTo.setSingleLine(true);
		replyingTo.setEllipsize(TextUtils.TruncateAt.END);
		replyingRow.addView(replyingTo, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		ImageView cancel = new ImageView(ctx);
		cancel.setImageResource(R.drawable.close_small);
		cancel.setImageTintList(ColorStateList.valueOf(secondary));
		cancel.setScaleType(ImageView.ScaleType.CENTER);
		cancel.setBackground(pressable(0, 18));
		cancel.setClickable(true);
		cancel.setFocusable(true);
		cancel.setOnClickListener(v -> setReplyTo(null));
		replyingRow.addView(cancel, new LinearLayout.LayoutParams(dp(36), dp(36)));
		replyingRow.setVisibility(View.GONE);
		box.addView(replyingRow, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		LinearLayout line = new LinearLayout(ctx);
		line.setOrientation(LinearLayout.HORIZONTAL);
		line.setGravity(Gravity.BOTTOM);

		input = new EditText(ctx);
		input.setHint(R.string.yt_comments_add);
		input.setHintTextColor(secondary);
		input.setTextColor(primary);
		input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
				InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
		input.setMaxLines(4);
		input.setMinHeight(dp(44));
		input.setPadding(dp(16), dp(10), dp(16), dp(10));
		input.setBackground(shape(chipFill, 22, 0, 0));
		input.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int st, int c, int af) {
			}

			@Override
			public void onTextChanged(CharSequence s, int st, int b, int c) {
			}

			@Override
			public void afterTextChanged(Editable e) {
				updateSend();
			}
		});
		LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		line.addView(input, ilp);

		FrameLayout send = new FrameLayout(ctx);
		GradientDrawable disc = new GradientDrawable();
		disc.setShape(GradientDrawable.OVAL);
		disc.setColor(accent);
		send.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), disc, null));
		ImageView si = new ImageView(ctx);
		si.setImageResource(R.drawable.comment_send);
		si.setImageTintList(ColorStateList.valueOf(onAccent));
		send.addView(si, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
		send.setClickable(true);
		send.setFocusable(true);
		send.setContentDescription(ctx.getString(R.string.yt_comments_send));
		send.setOnClickListener(v -> send());
		sendButton = send;
		LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(44), dp(44));
		sp.setMarginStart(dp(8));
		line.addView(send, sp);
		updateSend();

		box.addView(line, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return box;
	}

	// ---------------------------------------------------------------------------------------------
	// Loading

	private void setStatus(boolean busy, @Nullable CharSequence text) {
		// Loading the first batch: the placeholder rows instead of a spinner above an empty card.
		boolean placeholder = busy && rows.isEmpty() && (skeleton != null);
		showSkeleton(placeholder);
		if (placeholder) {
			statusRow.setVisibility(View.GONE);
			return;
		}
		if (text == null) {
			statusRow.setVisibility(View.GONE);
			return;
		}
		statusRow.setVisibility(View.VISIBLE);
		progress.setVisibility(busy ? View.VISIBLE : View.GONE);
		status.setText(text);
		status.setPadding(busy ? dp(8) : 0, 0, 0, 0);
	}

	/** The first batch, top comments (or the newest, by {@code sortToken}). */
	private void loadFirst(@Nullable String sortToken) {
		int gen = ++generation;
		rows.clear();
		replyTo = null;
		nextToken = null;
		loadingMore = false;
		moreFailed = false;
		adapter.notifyDataSetChanged();
		setStatus(true, ctx.getString(R.string.yt_comments_loading));
		updateCompose();

		executor.execute(() -> {
			try {
				Page p = (sortToken == null) ? YtComments.load(videoId) : YtComments.more(sortToken);
				handler.post(() -> {
					if (destroyed || (gen != generation)) return;
					onFirst(p);
				});
			} catch (Exception ex) {
				Log.e(ex, "Failed to load the comments of ", videoId);
				handler.post(() -> {
					if (destroyed || (gen != generation)) return;
					showFailed();
				});
			}
		});
	}

	private void showFailed() {
		setStatus(false, ctx.getString(R.string.yt_comments_failed));
		statusRow.setClickable(true);
		statusRow.setFocusable(true);
		statusRow.setBackground(pressable(chipFill, 14));
		status.setText(ctx.getString(R.string.yt_comments_failed) + " — " +
				ctx.getString(R.string.yt_comments_retry));
		statusRow.setOnClickListener(v -> {
			statusRow.setOnClickListener(null);
			statusRow.setClickable(false);
			statusRow.setBackground(null);
			loadFirst(sortingNew ? sortNewToken : null);
		});
	}

	private void onFirst(Page p) {
		statusRow.setOnClickListener(null);
		statusRow.setClickable(false);
		statusRow.setBackground(null);
		setStats(p.views, p.likes);
		if (p.disabled) {
			setStatus(false, ctx.getString(R.string.yt_comments_off));
			return;
		}
		if (p.count != null) count = p.count;
		if (p.createParams != null) createParams = p.createParams;
		if (p.sortTop != null) sortTopToken = p.sortTop;
		if (p.sortNew != null) sortNewToken = p.sortNew;
		titleView.setText((count == null) ? ctx.getString(R.string.yt_comments) :
				ctx.getString(R.string.yt_comments) + "  ·  " + count);
		updateSortRow();
		for (Comment c : p.comments) rows.add(new Row(TYPE_COMMENT, c, null));
		nextToken = p.next;
		adapter.notifyDataSetChanged();
		setStatus(false, rows.isEmpty() ? ctx.getString(R.string.yt_comments_empty) : null);
		updateCompose();
		list.post(this::maybeLoadMore);
	}

	private void sortBy(boolean newest) {
		String token = newest ? sortNewToken : sortTopToken;
		if ((sortingNew == newest) || (token == null)) return;
		sortingNew = newest;
		updateSortRow();
		loadFirst(token);
	}

	private void updateSortRow() {
		boolean has = (sortTopToken != null) && (sortNewToken != null);
		sortRow.setVisibility(has ? View.VISIBLE : View.GONE);
		styleSort(sortTop, !sortingNew);
		styleSort(sortNew, sortingNew);
	}

	private void styleSort(TextView t, boolean selected) {
		t.setTextColor(selected ? onAccent : primary);
		t.setBackground(selected ? pressable(accent, 18) : pressable(chipFill, 18));
	}

	private void maybeLoadMore() {
		if (destroyed || loadingMore || (nextToken == null) || moreFailed) return;
		LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
		if (lm == null) return;
		if (lm.findLastVisibleItemPosition() < adapter.getItemCount() - 4) return;
		loadMore();
	}

	private void loadMore() {
		String token = nextToken;
		if ((token == null) || loadingMore) return;
		int gen = generation;
		loadingMore = true;
		moreFailed = false;
		adapter.notifyItemInserted(rows.size());
		executor.execute(() -> {
			try {
				Page p = YtComments.more(token);
				handler.post(() -> {
					if (destroyed || (gen != generation)) return;
					int footer = rows.size();
					loadingMore = false;
					int start = rows.size();
					for (Comment c : p.comments) rows.add(new Row(TYPE_COMMENT, c, null));
					nextToken = p.next;
					adapter.notifyItemRemoved(footer);
					adapter.notifyItemRangeInserted(start, rows.size() - start);
					list.post(this::maybeLoadMore);
				});
			} catch (Exception ex) {
				Log.e(ex, "Failed to load more comments of ", videoId);
				handler.post(() -> {
					if (destroyed || (gen != generation)) return;
					loadingMore = false;
					moreFailed = true;
					adapter.notifyItemChanged(rows.size());
				});
			}
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Replies

	private void toggleReplies(Row p) {
		p.repliesOpen = !p.repliesOpen;
		if (p.repliesOpen && !p.repliesLoaded && (p.repliesNext != null)) loadReplies(p);
		else syncReplies(p);
	}

	private void loadReplies(Row p) {
		String token = p.repliesNext;
		if ((token == null) || p.repliesLoading) return;
		int gen = generation;
		p.repliesLoading = true;
		syncReplies(p);
		executor.execute(() -> {
			try {
				Page page = YtComments.more(token);
				handler.post(() -> {
					if (destroyed || (gen != generation)) return;
					p.repliesLoading = false;
					p.repliesLoaded = true;
					for (Comment c : page.comments) p.replyRows.add(new Row(TYPE_COMMENT, c, p));
					p.repliesNext = page.next;
					syncReplies(p);
				});
			} catch (Exception ex) {
				Log.e(ex, "Failed to load replies of ", videoId);
				handler.post(() -> {
					if (destroyed || (gen != generation)) return;
					p.repliesLoading = false;
					TopToast.show(R.drawable.comments, R.string.yt_comments_failed);
					syncReplies(p);
				});
			}
		});
	}

	/** Puts the replies of {@code p} in the list (or takes them out) as it is now. */
	private void syncReplies(Row p) {
		int idx = rows.indexOf(p);
		if (idx < 0) return;
		if (p.shown > 0) {
			for (int k = 0; k < p.shown; k++) rows.remove(idx + 1);
			adapter.notifyItemRangeRemoved(idx + 1, p.shown);
			p.shown = 0;
		}
		if (p.repliesOpen) {
			List<Row> group = new ArrayList<>(p.replyRows);
			if (p.repliesNext != null) group.add(p.moreRow());
			rows.addAll(idx + 1, group);
			p.shown = group.size();
			adapter.notifyItemRangeInserted(idx + 1, group.size());
		}
		adapter.notifyItemChanged(idx);
	}

	// ---------------------------------------------------------------------------------------------
	// Writing

	/** Whether this card can write: signed in, the video takes comments, and not on the car screen. */
	private boolean canWrite() {
		return !car && loggedIn && (createParams != null);
	}

	private void updateCompose() {
		if (compose == null) return;
		compose.setVisibility(canWrite() ? View.VISIBLE : View.GONE);
		// The Reply buttons follow the sign-in too.
		adapter.notifyDataSetChanged();
	}

	private void setReplyTo(@Nullable Row target) {
		if (replyingRow == null) return;
		replyTo = target;
		if (target == null) {
			replyingRow.setVisibility(View.GONE);
			input.setHint(R.string.yt_comments_add);
			return;
		}
		replyingRow.setVisibility(View.VISIBLE);
		replyingTo.setText(ctx.getString(R.string.yt_comments_replying_to, target.c.author));
		input.setHint(R.string.yt_comments_reply_hint);
		input.requestFocus();
		InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
	}

	private void updateSend() {
		if (sendButton == null) return;
		boolean usable = !posting && (input != null) && (input.getText().toString().trim().length() > 0);
		sendButton.setEnabled(usable);
		sendButton.setAlpha(usable ? 1f : 0.4f);
	}

	private void send() {
		if (posting || (input == null)) return;
		String text = input.getText().toString().trim();
		if (text.isEmpty()) return;
		Row target = replyTo;
		String params = (target != null) ? target.c.replyParams : createParams;
		if (params == null) {
			me.aap.fermata.util.DiagnosticLog.log("YTCOMMENTS", "no params to post with", (target == null) ? "comment" : target.c.id);
			TopToast.show(R.drawable.comments, R.string.yt_comments_no_reply);
			return;
		}
		posting = true;
		updateSend();
		executor.execute(() -> {
			try {
				Comment c = (target != null) ? YtComments.postReply(params, text) :
						YtComments.post(params, text);
				handler.post(() -> onPosted(target, c, text));
			} catch (Exception ex) {
				Log.e(ex, "Failed to post a comment on ", videoId);
				handler.post(() -> {
					if (destroyed) return;
					posting = false;
					updateSend();
					TopToast.show(R.drawable.comments, R.string.yt_comments_post_failed);
				});
			}
		});
	}

	private void onPosted(@Nullable Row target, @Nullable Comment posted, String text) {
		if (destroyed) return;
		posting = false;
		input.setText("");
		hideKeyboard();
		setReplyTo(null);
		updateSend();
		boolean reply = (target != null);
		Comment shown = (posted != null) ? posted :
				YtComments.mine(ctx.getString(R.string.yt_comments_you), text, reply);
		TopToast.show(R.drawable.comments, reply ? R.string.yt_comments_reply_posted :
				R.string.yt_comments_posted);

		if (!reply) {
			rows.add(0, new Row(TYPE_COMMENT, shown, null));
			adapter.notifyItemInserted(0);
			list.scrollToPosition(0);
			setStatus(false, null);
			return;
		}
		// Under the comment it answers, opened to show it.
		target.replyRows.add(0, new Row(TYPE_COMMENT, shown, target));
		target.repliesOpen = true;
		if (target.repliesNext == null) target.repliesLoaded = true;
		syncReplies(target);
	}

	private void hideKeyboard() {
		InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
		if ((imm != null) && (input != null)) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
		if (input != null) input.clearFocus();
	}

	/**
	 * Keeps the card above the soft keyboard, which covers the bottom of the window without
	 * resizing it: the card gives up as much height as the keyboard takes.
	 */
	private void onLayoutChanged() {
		if (destroyed || (overlay == null) || (cardLp == null)) return;
		int ovH = overlay.getHeight();
		if (ovH <= 0) return;
		if (openHeight == 0) openHeight = ovH;
		// The keyboard's height, from the visible frame and from the insets: windows differ in which
		// of the two tells (a resized window shows it in neither, only in its own smaller height).
		Rect r = new Rect();
		overlay.getWindowVisibleDisplayFrame(r);
		int[] loc = new int[2];
		overlay.getLocationOnScreen(loc);
		int kb = Math.max(0, (loc[1] + ovH) - r.bottom);
		androidx.core.view.WindowInsetsCompat wi = androidx.core.view.ViewCompat.getRootWindowInsets(overlay);
		boolean ime = (wi != null) && wi.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
		if (ime) kb = Math.max(kb, wi.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom);
		// Anything small is just the system navigation bar.
		if (!ime && (kb < ovH * 0.15f)) kb = 0;
		boolean typing = (kb > 0) || ime;
		// A window already shrunk for the keyboard has made room for it: only the rest is ours.
		int lift = Math.max(0, kb - Math.max(0, openHeight - ovH));
		int gravity;
		int bottom;
		if (typing) {
			// Right on top of the keyboard.
			gravity = Gravity.BOTTOM | (docked ? Gravity.END : Gravity.CENTER_HORIZONTAL);
			bottom = lift + dp(8);
		} else {
			gravity = docked ? (Gravity.END | Gravity.TOP) : Gravity.CENTER;
			bottom = baseBottom;
		}
		int room = ovH - cardLp.topMargin - bottom;
		int h = Math.min(fullH, Math.max(dp(160), room));
		if ((cardLp.height != h) || (cardLp.bottomMargin != bottom) || (cardLp.gravity != gravity)) {
			cardLp.height = h;
			cardLp.bottomMargin = bottom;
			cardLp.gravity = gravity;
			card.setLayoutParams(cardLp);
		}
		// While writing, only the comments and the box matter: the rest gives its room up.
		if (videoLine != null) videoLine.setVisibility(typing ? View.GONE : View.VISIBLE);
		if (statsLine != null) {
			statsLine.setVisibility((typing || (statsLine.length() == 0)) ? View.GONE : View.VISIBLE);
		}
		if (sortRow != null) sortRow.setVisibility(typing ? View.GONE :
				((sortTopToken != null) && (sortNewToken != null)) ? View.VISIBLE : View.GONE);
	}

	// ---------------------------------------------------------------------------------------------
	// The rows

	private static final class Holder extends RecyclerView.ViewHolder {
		LinearLayout line;
		ImageView avatar;
		TextView name;
		TextView meta;
		TextView body;
		TextView readMore;
		LinearLayout likeBox;
		TextView likes;
		TextView reply;
		TextView replies;
		// More replies / the end of the list.
		TextView pill;
		ProgressBar spinner;

		Holder(View v) {
			super(v);
		}
	}

	private final class Adapter extends RecyclerView.Adapter<Holder> {
		@Override
		public int getItemCount() {
			return rows.size() + ((loadingMore || moreFailed) ? 1 : 0);
		}

		@Override
		public int getItemViewType(int position) {
			return (position >= rows.size()) ? TYPE_FOOTER : rows.get(position).type;
		}

		@NonNull
		@Override
		public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
			return switch (type) {
				case TYPE_COMMENT -> commentHolder();
				case TYPE_MORE_REPLIES -> pillHolder(false);
				default -> pillHolder(true);
			};
		}

		@Override
		public void onBindViewHolder(@NonNull Holder h, int position) {
			if (position >= rows.size()) {
				bindFooter(h);
				return;
			}
			Row row = rows.get(position);
			if (row.type == TYPE_COMMENT) bindComment(h, row);
			else bindMoreReplies(h, row);
		}
	}

	private Holder commentHolder() {
		LinearLayout line = new LinearLayout(ctx);
		line.setOrientation(LinearLayout.HORIZONTAL);
		line.setPadding(dp(8), dp(8), dp(8), dp(8));
		line.setClickable(true);
		line.setFocusable(true);
		line.setBackground(pressable(0, 14));
		line.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		Holder h = new Holder(line);
		h.line = line;

		h.avatar = new ImageView(ctx);
		h.avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
		h.avatar.setClipToOutline(true);
		h.avatar.setOutlineProvider(new ViewOutlineProvider() {
			@Override
			public void getOutline(View v, Outline o) {
				o.setOval(0, 0, v.getWidth(), v.getHeight());
			}
		});
		line.addView(h.avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));

		LinearLayout col = new LinearLayout(ctx);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setPadding(dp(12), 0, 0, 0);
		line.addView(col, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		h.name = text("", 13.5f, primary, true);
		h.name.setSingleLine(true);
		h.name.setEllipsize(TextUtils.TruncateAt.END);
		col.addView(h.name);
		h.meta = text("", 11.5f, secondary, false);
		h.meta.setSingleLine(true);
		h.meta.setEllipsize(TextUtils.TruncateAt.END);
		col.addView(h.meta);

		h.body = text("", 14.5f, primary, false);
		h.body.setPadding(0, dp(6), 0, 0);
		col.addView(h.body);
		h.readMore = text(ctx.getString(R.string.yt_comments_read_more), 12.5f, accent, true);
		h.readMore.setPadding(0, dp(2), 0, 0);
		col.addView(h.readMore);

		LinearLayout actions = new LinearLayout(ctx);
		actions.setOrientation(LinearLayout.HORIZONTAL);
		actions.setGravity(Gravity.CENTER_VERTICAL);
		actions.setPadding(0, dp(8), 0, 0);
		col.addView(actions);

		h.likeBox = new LinearLayout(ctx);
		h.likeBox.setOrientation(LinearLayout.HORIZONTAL);
		h.likeBox.setGravity(Gravity.CENTER_VERTICAL);
		ImageView thumb = new ImageView(ctx);
		thumb.setImageResource(R.drawable.thumb_up);
		thumb.setImageTintList(ColorStateList.valueOf(secondary));
		h.likeBox.addView(thumb, new LinearLayout.LayoutParams(dp(16), dp(16)));
		h.likes = text("", 12.5f, secondary, false);
		h.likes.setPadding(dp(6), 0, 0, 0);
		h.likeBox.addView(h.likes);
		LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		llp.setMarginEnd(dp(14));
		actions.addView(h.likeBox, llp);

		h.reply = smallPill(ctx.getString(R.string.yt_comments_reply));
		h.replies = smallPill("");
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(32));
		plp.setMarginEnd(dp(8));
		actions.addView(h.reply, plp);
		actions.addView(h.replies, plp);
		// Writing and finding the small buttons is not for the steering wheel.
		if (car) CarNav.skip(h.reply);
		return h;
	}

	private Holder pillHolder(boolean footer) {
		LinearLayout line = new LinearLayout(ctx);
		line.setOrientation(LinearLayout.HORIZONTAL);
		line.setGravity(Gravity.CENTER_VERTICAL);
		line.setPadding(dp(8), dp(6), dp(8), dp(6));
		line.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		Holder h = new Holder(line);
		h.line = line;
		if (footer) {
			h.spinner = new ProgressBar(ctx);
			h.spinner.setIndeterminate(true);
			LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(18), dp(18));
			sp.setMarginEnd(dp(8));
			line.addView(h.spinner, sp);
		}
		h.pill = smallPill("");
		line.addView(h.pill, new LinearLayout.LayoutParams(WRAP_CONTENT, dp(34)));
		return h;
	}

	private void bindComment(Holder h, Row row) {
		Comment c = row.c;
		boolean reply = (row.parent != null);
		// A reply sits further in, with a smaller picture.
		h.line.setPadding(dp(reply ? 56 : 8), dp(8), dp(8), dp(8));
		LinearLayout.LayoutParams ap = (LinearLayout.LayoutParams) h.avatar.getLayoutParams();
		int size = dp(reply ? 30 : 40);
		if ((ap.width != size) || (ap.height != size)) {
			ap.width = size;
			ap.height = size;
			h.avatar.setLayoutParams(ap);
		}
		if (c.avatarUrl != null) {
			loadImage(h.avatar, c.avatarUrl);
		} else {
			h.avatar.setTag(null);
			h.avatar.setImageDrawable(null);
			h.avatar.setBackgroundColor(chipFill);
		}

		String who = c.author.isEmpty() ? "" : c.author;
		if (c.creator) who += "  ✓ " + ctx.getString(R.string.yt_comments_creator);
		h.name.setText(who);
		StringBuilder meta = new StringBuilder();
		if (c.pinned) meta.append(ctx.getString(R.string.yt_comments_pinned)).append("  ·  ");
		meta.append(c.published);
		if (c.hearted) meta.append("  ♥");
		h.meta.setText(meta);

		h.body.setText(c.text);
		boolean longText = c.text.length() > LONG_TEXT;
		if (longText && !row.textOpen) {
			h.body.setMaxLines(5);
			h.body.setEllipsize(TextUtils.TruncateAt.END);
			h.readMore.setVisibility(View.VISIBLE);
		} else {
			h.body.setMaxLines(Integer.MAX_VALUE);
			h.body.setEllipsize(null);
			h.readMore.setVisibility(View.GONE);
		}
		h.line.setOnClickListener(v -> {
			if (!longText) return;
			row.textOpen = !row.textOpen;
			int pos = h.getBindingAdapterPosition();
			if (pos >= 0) adapter.notifyItemChanged(pos);
		});

		h.likeBox.setVisibility(c.likes.isEmpty() ? View.GONE : View.VISIBLE);
		h.likes.setText(c.likes);

		// Replying to a reply replies to the comment it's under.
		Row target = reply ? row.parent : row;
		boolean canReply = canWrite();
		h.reply.setVisibility(canReply ? View.VISIBLE : View.GONE);
		h.reply.setOnClickListener(v -> setReplyTo(target));

		boolean hasReplies = !reply && ((row.c.repliesToken != null) || row.repliesOpen);
		h.replies.setVisibility(hasReplies ? View.VISIBLE : View.GONE);
		if (hasReplies) {
			h.replies.setText(row.repliesLoading ? "…" : row.repliesOpen ?
					ctx.getString(R.string.yt_comments_replies_hide) :
					ctx.getString(R.string.yt_comments_replies_n,
							c.replyCount.isEmpty() ? "" : c.replyCount).trim());
			h.replies.setOnClickListener(v -> {
				if (!row.repliesLoading) toggleReplies(row);
			});
		}
	}

	private void bindMoreReplies(Holder h, Row row) {
		h.line.setPadding(dp(56), dp(2), dp(8), dp(2));
		Row p = row.parent;
		h.pill.setText(p.repliesLoading ? "…" : ctx.getString(R.string.yt_comments_replies_more));
		h.pill.setOnClickListener(v -> loadReplies(p));
	}

	private void bindFooter(Holder h) {
		h.line.setPadding(dp(8), dp(6), dp(8), dp(6));
		if (moreFailed) {
			h.spinner.setVisibility(View.GONE);
			h.pill.setVisibility(View.VISIBLE);
			h.pill.setText(R.string.yt_comments_retry);
			h.pill.setOnClickListener(v -> {
				moreFailed = false;
				adapter.notifyItemChanged(rows.size());
				loadMore();
			});
		} else {
			h.spinner.setVisibility(View.VISIBLE);
			h.pill.setVisibility(View.VISIBLE);
			h.pill.setText(R.string.yt_comments_more);
			h.pill.setOnClickListener(null);
			h.pill.setClickable(false);
			h.pill.setFocusable(false);
			h.pill.setBackground(null);
		}
	}

	private void loadImage(ImageView v, String url) {
		Object tag = v.getTag();
		v.setTag(url);
		Bitmap cached = images.get(url);
		if (cached != null) {
			v.setBackground(null);
			v.setImageBitmap(cached);
			return;
		}
		if (url.equals(tag) && (v.getDrawable() != null)) return;
		v.setBackgroundColor(chipFill);
		v.setImageDrawable(null);
		FermataApplication.get().getBitmapCache().getBitmap(ctx, url, false, false).main()
				.onSuccess(bm -> {
					if ((bm == null) || destroyed) return;
					images.put(url, bm);
					if (url.equals(v.getTag())) {
						v.setBackground(null);
						v.setImageBitmap(bm);
					}
				});
	}

	// ---------------------------------------------------------------------------------------------
	// Beside the video

	/** The column's width and its margins to the screen's edges, pixels. */
	private int dockW;
	private int dockEnd;
	private int dockTop;
	private int dockBottom;
	/** 0 (closed) to 1 (open): drives the column sliding in and the video making room for it. */
	private float dockProgress;
	/** The video view that has been shrunk to make room (the one that was fullscreen at the time). */
	@Nullable
	private VideoView splitView;
	@Nullable
	private ValueAnimator dockAnim;
	private final Runnable dockWatch = this::watchDock;
	@Nullable
	private FermataServiceUiBinder.Listener binderListener;
	private int notInVideo;

	private int[] systemInsets() {
		int[] r = new int[4];
		androidx.core.view.WindowInsetsCompat wi = androidx.core.view.ViewCompat.getRootWindowInsets(host);
		if (wi == null) return r;
		androidx.core.graphics.Insets in = wi.getInsets(
				androidx.core.view.WindowInsetsCompat.Type.systemBars() |
						androidx.core.view.WindowInsetsCompat.Type.displayCutout());
		r[0] = in.left;
		r[1] = in.top;
		r[2] = in.right;
		r[3] = in.bottom;
		return r;
	}

	private int clampDock(int w) {
		int max = Math.round(host.getWidth() * 0.6f);
		return Math.max(Math.min(dp(260), max), Math.min(w, max));
	}

	/** The column's size and place, from Settings' width: full height on the right, a gap around it. */
	private void buildDock() {
		int[] in = systemInsets();
		dockEnd = in[2] + dp(10);
		dockTop = in[1] + dp(10);
		dockBottom = in[3] + dp(10);
		int pct = Math.max(20, Math.min(60,
				a.getPrefs().getIntPref(MainActivityPrefs.COMMENTS_SPLIT_PERCENT)));
		dockW = clampDock(Math.round(host.getWidth() * pct / 100f));
		int h = Math.max(dp(160), host.getHeight() - dockTop - dockBottom);
		cardLp = new FrameLayout.LayoutParams(dockW, h, Gravity.END | Gravity.TOP);
		cardLp.topMargin = dockTop;
		cardLp.bottomMargin = dockBottom;
		cardLp.setMarginEnd(dockEnd);
		fullH = Integer.MAX_VALUE;
		baseBottom = dockBottom;
	}

	/**
	 * The strip along the column's left edge that drags it wider or narrower: the video follows the
	 * finger (it is only a transform, see {@link VideoView#setSplitShare}) and the width is kept for
	 * the next time (Settings' "Comments width").
	 */
	private View buildDockHandle() {
		FrameLayout strip = new FrameLayout(ctx);
		View pill = new View(ctx);
		GradientDrawable pd = new GradientDrawable();
		pd.setColor((secondary & 0x00FFFFFF) | 0x77000000);
		pd.setCornerRadius(dp(2));
		pill.setBackground(pd);
		FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(4), dp(48),
				Gravity.START | Gravity.CENTER_VERTICAL);
		plp.setMarginStart(dp(6));
		strip.addView(pill, plp);
		strip.setContentDescription(ctx.getString(R.string.yt_comments_resize));
		strip.setOnTouchListener(new View.OnTouchListener() {
			private float startX;
			private int startW;

			@Override
			public boolean onTouch(View v, android.view.MotionEvent e) {
				switch (e.getActionMasked()) {
					case android.view.MotionEvent.ACTION_DOWN -> {
						startX = e.getRawX();
						startW = dockW;
						v.getParent().requestDisallowInterceptTouchEvent(true);
						pd.setColor(accent);
						return true;
					}
					case android.view.MotionEvent.ACTION_MOVE -> {
						setDockWidth(clampDock(startW - Math.round(e.getRawX() - startX)));
						return true;
					}
					case android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
						pd.setColor((secondary & 0x00FFFFFF) | 0x77000000);
						int pct = Math.round(dockW * 100f / Math.max(1, host.getWidth()));
						a.getPrefs().applyIntPref(MainActivityPrefs.COMMENTS_SPLIT_PERCENT,
								Math.max(20, Math.min(60, pct)));
						return true;
					}
					default -> {
						return false;
					}
				}
			}
		});
		return strip;
	}

	private void setDockWidth(int w) {
		if ((w == dockW) || (cardLp == null) || destroyed) return;
		dockW = w;
		cardLp.width = w;
		card.setLayoutParams(cardLp);
		applyVideoSplit();
	}

	/** Makes the video as wide as the room the column leaves it, as far as it has come in. */
	private void applyVideoSplit() {
		VideoView vv = splitView;
		int hostW = host.getWidth();
		if ((vv == null) || (hostW <= 0)) return;
		float taken = dockW + dockEnd + dp(10);
		vv.setSplitShare((taken / hostW) * dockProgress);
	}

	private void openDock() {
		splitView = a.getActiveVideoView();
		// The column itself fades in as it slides; the (transparent) layer around it is just there.
		overlay.setAlpha(1f);
		card.setAlpha(0f);
		card.setTranslationX(dockW + dockEnd);
		animateDock(0f, 1f, 360, new DecelerateInterpolator(1.8f), null);
		notInVideo = 0;
		startDockWatch();
	}

	private void closeDock() {
		animateDock(dockProgress, 0f, 280, new AccelerateInterpolator(1.4f), this::remove);
	}

	private void animateDock(float from, float to, long ms, android.animation.TimeInterpolator ip,
													 @Nullable Runnable end) {
		if (dockAnim != null) dockAnim.cancel();
		ValueAnimator anim = ValueAnimator.ofFloat(from, to);
		anim.setDuration(ms);
		anim.setInterpolator(ip);
		anim.addUpdateListener(an -> {
			dockProgress = (float) an.getAnimatedValue();
			card.setTranslationX((1f - dockProgress) * (dockW + dockEnd));
			card.setAlpha(Math.min(1f, dockProgress * 1.6f));
			applyVideoSplit();
		});
		if (end != null) {
			anim.addListener(new android.animation.AnimatorListenerAdapter() {
				private boolean cancelled;

				@Override
				public void onAnimationCancel(android.animation.Animator animation) {
					cancelled = true;
				}

				@Override
				public void onAnimationEnd(android.animation.Animator animation) {
					if (!cancelled) end.run();
				}
			});
		}
		dockAnim = anim;
		anim.start();
	}

	/**
	 * While beside the video: follows it. Another video (the next one, a playlist moving on) gets its
	 * comments in the same column, which stays; leaving fullscreen closes the column; a different
	 * video view taking over (a downloaded video's file after the YouTube page) takes the shrunk
	 * picture's place.
	 */
	private void startDockWatch() {
		binderListener = new FermataServiceUiBinder.Listener() {
			@Override
			public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
				// Posted and delayed: the page reports the new video's identity a moment later.
				handler.removeCallbacks(dockWatch);
				handler.postDelayed(dockWatch, 400);
			}
		};
		a.getMediaServiceBinder().addBroadcastListener(binderListener);
		handler.postDelayed(dockWatch, 1000);
	}

	private void stopDockWatch() {
		handler.removeCallbacks(dockWatch);
		if (binderListener != null) {
			a.getMediaServiceBinder().removeBroadcastListener(binderListener);
			binderListener = null;
		}
	}

	private void watchDock() {
		if (destroyed || dismissing) return;
		handler.removeCallbacks(dockWatch);
		if (!a.isVideoMode()) {
			// A page switching videos can drop out of fullscreen for a moment: only a second miss counts.
			if (++notInVideo >= 2) {
				dismiss();
				return;
			}
		} else {
			notInVideo = 0;
			VideoView now = a.getActiveVideoView();
			if ((now != null) && (now != splitView)) {
				if (splitView != null) splitView.setSplitShare(0f);
				splitView = now;
				applyVideoSplit();
			}
			PlayableItem pi = Action.getFavoritableItem(a);
			String id = YtDownloads.videoIdOf(pi);
			if ((id != null) && !id.equals(videoId)) switchVideo(id, pi.getName());
		}
		handler.postDelayed(dockWatch, 1000);
	}

	/** The column stays and loads the comments of the video that came on. */
	private void switchVideo(String id, String title) {
		videoId = id;
		videoTitle = title;
		videoLine.setText(title);
		setStats(-1, -1);
		titleView.setText(R.string.yt_comments);
		count = null;
		createParams = null;
		sortTopToken = null;
		sortNewToken = null;
		sortingNew = false;
		updateSortRow();
		if (input != null) {
			hideKeyboard();
			input.setText("");
		}
		loadFirst(null);
	}

	/** Shows or hides the placeholder rows, the real list fading in as they go. */
	private void showSkeleton(boolean show) {
		CommentsSkeletonView sk = skeleton;
		if (sk == null) return;
		if (show) {
			sk.animate().cancel();
			list.animate().cancel();
			sk.setAlpha(1f);
			sk.setVisibility(View.VISIBLE);
			list.setAlpha(1f);
		} else if (sk.getVisibility() == View.VISIBLE) {
			sk.animate().alpha(0f).setDuration(160).withEndAction(() -> sk.setVisibility(View.GONE)).start();
			list.setAlpha(0f);
			list.animate().alpha(1f).setDuration(240).start();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Closing

	private void dismiss() {
		if (dismissing) return;
		dismissing = true;
		cleanUp();
		if (docked) {
			closeDock();
			return;
		}
		overlay.animate().alpha(0f).setDuration(180).start();
		card.animate().translationY(dp(120)).alpha(0f).setDuration(200)
				.setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(this::remove).start();
	}

	/**
	 * Gone now: the video is no longer shrunk (it is leaving anyway) and the column fades out in
	 * the time the video's own fade takes, with no slide.
	 */
	private void dismissFast() {
		if (dismissing && (overlay != null) && (overlay.getParent() == null)) return;
		dismissing = true;
		cleanUp();
		if (dockAnim != null) dockAnim.cancel();
		if (splitView != null) splitView.setSplitShare(0f);
		splitView = null;
		View o = overlay;
		if ((o == null) || (o.getParent() == null)) {
			remove();
			return;
		}
		o.animate().cancel();
		card.animate().cancel();
		card.setTranslationX(0f);
		o.animate().alpha(0f).setDuration(140).withEndAction(this::remove).start();
	}

	private void cleanUp() {
		destroyed = true;
		stopDockWatch();
		executor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
		if ((keyboardListener != null) && overlay.getViewTreeObserver().isAlive()) {
			overlay.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardListener);
		}
	}

	private void remove() {
		if (splitView != null) splitView.setSplitShare(0f);
		splitView = null;
		host.removeView(overlay);
		images.evictAll();
		if (open == this) open = null;
	}

	// ---------------------------------------------------------------------------------------------
	// Look

	/** "👁 1.2M  👍 34K" under the title; what YouTube doesn't say is left out. */
	private void setStats(long views, long likes) {
		if (statsLine == null) return;
		android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
		if (views >= 0) appendStat(sb, R.drawable.visibility, YtComments.shortCount(views));
		if (likes >= 0) appendStat(sb, R.drawable.thumb_up, YtComments.shortCount(likes));
		statsLine.setText(sb);
		statsLine.setVisibility((sb.length() == 0) ? View.GONE : View.VISIBLE);
	}

	private void appendStat(android.text.SpannableStringBuilder sb, int icon, String value) {
		android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(ctx, icon);
		if (sb.length() > 0) sb.append("    ");
		if (d != null) {
			d = d.mutate();
			int size = Math.round(statsLine.getTextSize() * 1.1f);
			d.setBounds(0, 0, size, size);
			d.setTint(statsLine.getCurrentTextColor());
			int at = sb.length();
			sb.append("\u00a0");
			sb.setSpan(new android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM),
					at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			sb.append(' ');
		}
		sb.append(value);
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

	private TextView smallPill(String label) {
		TextView t = text(label, 12.5f, primary, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setPadding(dp(14), 0, dp(14), 0);
		t.setBackground(pressable(chipFill, 16));
		t.setClickable(true);
		t.setFocusable(true);
		return t;
	}

	/** A rounded fill with a ripple, and an accent outline when focused (D-pad / TV / steering wheel). */
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
