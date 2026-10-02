package me.aap.fermata.ui.view;

import static android.os.Build.VERSION.SDK_INT;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;
import me.aap.utils.text.TextUtils;
import me.aap.utils.ui.UiUtils;

/**
 * The Music tab's "more" menu: a frosted-glass card with big, easy-to-press tiles for the audio
 * effects and the sleep timer. The timer tile opens the timer page in the same card: presets, a
 * stepper for any other length, and the option to let the current song finish before stopping.
 * <p>
 * The card is blurred glass where the platform can blur what's behind a window (Android 12+ with
 * window blur enabled), and a nearly opaque panel otherwise. Built in code: it's one small,
 * self-contained popup that only the Music tab uses.
 */
public final class MusicMoreMenu {
	private static final int[] PRESETS = {10, 15, 30, 45, 60, 90};
	private static final int STEP = 5;
	private static final int MIN_MINUTES = 5;
	private static final int MAX_MINUTES = 12 * 60;
	// Remembered for the next time the menu opens, until the app is closed.
	private static int lastMinutes = 30;
	private static boolean lastFinishSong = true;

	private static MusicMoreMenu open;

	private final Context ctx;
	private final ViewGroup host;
	private final View insets;
	private final View anchor;
	private final MediaSessionCallback cb;
	private final Runnable onEffects;
	private final Runnable onFavorite;
	private final Runnable onPlaylist;
	private final java.util.function.BooleanSupplier isFavorite;
	private final boolean light;
	private final int primary;
	private final int secondary;
	private final int accent;
	private final int onAccent;
	private final int chipFill;
	private final int ripple;
	private final float density;
	private final Runnable tick = this::updateStatus;
	private final List<TextView> presetChips = new ArrayList<>();
	private FrameLayout overlay;
	private FrameLayout card;
	private LinearLayout root;
	private View mainPage;
	private View timerPage;
	private TextView timerTileSub;
	private TextView timerStatus;
	private TextView minutesLabel;
	private FrameLayout pages;
	private boolean dismissing;
	private boolean centered;
	// A short screen (Android Auto, a phone on its side): tighter pages so the timer fits without scrolling.
	private final boolean compact;
	private int usableTop;
	private int usableBottom;
	private ValueAnimator pageAnim;
	private int minutes = lastMinutes;
	private boolean finishSong = lastFinishSong;

	/** Opens on the timer page instead of the tiles. */
	private boolean startOnTimer;

	private MusicMoreMenu(Context ctx, ViewGroup host, View insets, View anchor,
											MediaSessionCallback cb, Runnable onEffects, Runnable onFavorite,
											java.util.function.BooleanSupplier isFavorite, Runnable onPlaylist) {
		this.ctx = ctx;
		this.onFavorite = onFavorite;
		this.isFavorite = isFavorite;
		this.onPlaylist = onPlaylist;
		this.host = host;
		this.insets = insets;
		this.anchor = anchor;
		this.cb = cb;
		this.onEffects = onEffects;
		this.light = MusicPlayerFragment.isLightTheme(ctx);
		this.primary = color(R.attr.musicTextPrimary);
		this.secondary = color(R.attr.musicTextSecondary);
		this.chipFill = color(R.attr.musicChipFill);
		this.ripple = color(R.attr.musicChipRipple);
		this.accent = EffectsUi.accent(ctx);
		this.onAccent = EffectsUi.onAccent(accent);
		this.density = ctx.getResources().getDisplayMetrics().density;
		int room = host.getHeight() - insets.getPaddingTop() - insets.getPaddingBottom();
		this.compact = (host.getWidth() > host.getHeight()) && (room < dp(400));
	}

	/**
	 * Shows the menu over {@code host} (the Music tab's root), sliding up from just above
	 * {@code anchor}. An overlay view rather than a dialog: Android Auto's window context doesn't
	 * allow adding dialog windows at all.
	 *
	 * @param insets    the view whose padding is the room the tool bar and nav bar take (the menu stays
	 *                  clear of it, and centres in what's left when it doesn't fit above the anchor)
	 * @param ctx       a context carrying the Music tab's palette (see {@code MusicPalette} in music.xml)
	 * @param onEffects what the Effects tile does
	 * @param onFavorite toggles what's playing in the favorites
	 * @param onPlaylist opens the playlist picker for what's playing
	 */
	public static void show(@NonNull Context ctx, @NonNull ViewGroup host, @NonNull View insets,
													@NonNull View anchor, @NonNull MediaSessionCallback cb,
													@NonNull Runnable onEffects, @NonNull Runnable onFavorite,
													@NonNull java.util.function.BooleanSupplier isFavorite,
													@NonNull Runnable onPlaylist) {
		dismissOpen();
		MusicMoreMenu m = new MusicMoreMenu(ctx, host, insets, anchor, cb, onEffects, onFavorite,
				isFavorite, onPlaylist);
		open = m;
		m.show();
	}

	/** Like {@link #show}, opening straight on the sleep timer page. */
	public static void showTimer(@NonNull Context ctx, @NonNull ViewGroup host, @NonNull View insets,
															 @NonNull View anchor, @NonNull MediaSessionCallback cb,
															 @NonNull Runnable onEffects, @NonNull Runnable onFavorite,
															 @NonNull java.util.function.BooleanSupplier isFavorite,
															 @NonNull Runnable onPlaylist) {
		dismissOpen();
		MusicMoreMenu m = new MusicMoreMenu(ctx, host, insets, anchor, cb, onEffects, onFavorite,
				isFavorite, onPlaylist);
		m.startOnTimer = true;
		open = m;
		m.show();
	}

	/** Closes the menu if it's open (for the back button); returns whether it was. */
	public static boolean dismissOpen() {
		MusicMoreMenu m = open;
		if ((m == null) || (m.overlay == null) || (m.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		m.dismiss();
		return true;
	}

	private void show() {
		overlay = new FrameLayout(ctx);
		overlay.setClickable(true);
		overlay.setFocusable(false);
		overlay.setElevation(dp(30));
		overlay.setOnClickListener(v -> dismiss());
		overlay.setBackgroundColor(0x33000000);
		overlay.setAlpha(0f);

		root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(16), dp(16), dp(16), dp(16));

		pages = new FrameLayout(ctx);
		mainPage = buildMainPage();
		timerPage = buildTimerPage();
		timerPage.setVisibility(View.GONE);
		if (startOnTimer) {
			mainPage.setVisibility(View.GONE);
			timerPage.setVisibility(View.VISIBLE);
		}
		pages.addView(mainPage, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		pages.addView(timerPage, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		root.addView(pages, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		// On a short screen (landscape) the card scrolls rather than running off it.
		ScrollView scroll = new ScrollView(ctx);
		scroll.setVerticalScrollBarEnabled(false);
		scroll.addView(root, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		card = new DraggableCard(ctx);
		card.setClickable(true);
		card.setAlpha(0f);
		card.setBackground(panel());
		card.setElevation(dp(12));
		card.addView(scroll, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		int w = Math.min(dp(compact ? 520 : 380), host.getWidth() - dp(32));
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, WRAP_CONTENT,
				Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		lp.bottomMargin = dp(12);
		overlay.addView(card, lp);
		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		updateStatus();
		// Once laid out: sit just above the chip that opened it, then slide up.
		card.post(this::placeAndEnter);
	}

	private void placeAndEnter() {
		if (overlay.getParent() == null) return;
		int hostW = host.getWidth();
		int hostH = host.getHeight();
		// Room for the taller of the two pages, so the card grows upwards into free space when the
		// timer page opens instead of running off the top.
		int contentW = card.getWidth() - dp(32);
		timerPage.measure(View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int ch = card.getHeight();
		int tallest = Math.max(ch, timerPage.getMeasuredHeight() + dp(32));
		int[] a = new int[2];
		int[] h = new int[2];
		anchor.getLocationInWindow(a);
		host.getLocationInWindow(h);
		int anchorTop = a[1] - h[1];

		// The room the tool bar and the nav bar leave.
		usableTop = insets.getPaddingTop();
		usableBottom = hostH - insets.getPaddingBottom();
		int usableH = usableBottom - usableTop - dp(16);
		FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
		lp.leftMargin = insets.getPaddingLeft();
		lp.rightMargin = insets.getPaddingRight();

		int margin;
		boolean wide = hostW > hostH;
		if (!wide && (tallest <= anchorTop - dp(10) - usableTop)) {
			// A phone held upright: sits right over the chip that opened it.
			centered = false;
			margin = hostH - anchorTop + dp(10);
		} else {
			// A tablet, Android Auto, any landscape screen: centred, scrolling if it's taller than the room.
			// Sized to the page showing (the timer page grows it later, see fitCard()), never stretched
			// to fill the room: a card taller than its content is empty at the bottom and no longer reads
			// as centred.
			centered = true;
			if (ch > usableH) lp.height = usableH;
			ch = Math.min(ch, usableH);
			margin = centeredMargin(ch);
		}
		lp.bottomMargin = margin;
		card.setLayoutParams(lp);

		card.setTranslationY(ch + margin);
		card.setAlpha(1f);
		card.animate().translationY(0f).setDuration(280).setInterpolator(new DecelerateInterpolator(1.6f))
				.start();
		overlay.animate().alpha(1f).setDuration(220).start();
	}

	/**
	 * The bottom margin that centres a card {@code cardH} tall in the room the tool bar and nav bar
	 * leave.
	 */
	private int centeredMargin(int cardH) {
		int pad = dp(8);
		int top = usableTop + (usableBottom - usableTop - cardH) / 2;
		top = Math.min(top, usableBottom - cardH - pad);
		top = Math.max(top, usableTop + pad);
		return Math.max(pad, host.getHeight() - top - cardH);
	}

	/**
	 * Keeps a centred card centred while the page inside it changes height: as tall as its content,
	 * or, when that doesn't fit the room, as tall as the room (it then scrolls).
	 */
	private void fitCard(int contentH) {
		int room = usableBottom - usableTop - dp(16);
		int cardH = dp(32) + contentH;
		FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) card.getLayoutParams();
		clp.height = (cardH > room) ? room : WRAP_CONTENT;
		clp.bottomMargin = centeredMargin(Math.min(cardH, room));
		card.setLayoutParams(clp);
	}

	/** Slides the card back down and away. */
	private void dismiss() {
		if (dismissing) return;
		dismissing = true;
		root.removeCallbacks(tick);
		overlay.animate().alpha(0f).setDuration(200).start();
		card.animate().translationY(card.getHeight() + dp(80)).setDuration(220)
				.setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(this::remove).start();
	}

	/** Closes at once, without the slide (leaving for another screen). */
	private void dismissNow() {
		dismissing = true;
		root.removeCallbacks(tick);
		overlay.animate().cancel();
		card.animate().cancel();
		remove();
	}

	private void remove() {
		if (pageAnim != null) pageAnim.cancel();
		host.removeView(overlay);
		if (open == this) open = null;
	}

	// ---------------------------------------------------------------------------------------------
	// The panel
	// ---------------------------------------------------------------------------------------------

	/** The card's fill: the palette's panel colour (the queue panel's), a clean, near-opaque surface. */
	private Drawable panel() {
		GradientDrawable d = new GradientDrawable();
		d.setColor(color(R.attr.musicPanelFill));
		d.setCornerRadius(dp(28));
		return d;
	}

	/** The card, which a downward drag pulls away (and, dragged far or flung, dismisses). */
	private final class DraggableCard extends FrameLayout {
		private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
		private float downY;
		private boolean dragging;
		private VelocityTracker velocity;

		DraggableCard(Context c) {
			super(c);
		}

		@Override
		public boolean onInterceptTouchEvent(MotionEvent e) {
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					downY = e.getRawY();
					dragging = false;
					break;
				case MotionEvent.ACTION_MOVE:
					if (!dragging && (e.getRawY() - downY > slop)) {
						dragging = true;
						begin();
						return true;
					}
					break;
				default:
					break;
			}
			return false;
		}

		private void begin() {
			velocity = VelocityTracker.obtain();
		}

		@Override
		public boolean onTouchEvent(MotionEvent e) {
			if (velocity == null) velocity = VelocityTracker.obtain();
			velocity.addMovement(e);

			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					downY = e.getRawY();
					return true;
				case MotionEvent.ACTION_MOVE:
					dragging = true;
					setTranslationY(Math.max(0, e.getRawY() - downY));
					return true;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					velocity.computeCurrentVelocity(1000);
					float vy = velocity.getYVelocity();
					velocity.recycle();
					velocity = null;
					if (dragging && ((getTranslationY() > getHeight() / 3f) || (vy > 1200))) {
						dismiss();
					} else {
						animate().translationY(0f).setDuration(180)
								.setInterpolator(new DecelerateInterpolator()).start();
					}
					dragging = false;
					return true;
				default:
					return true;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Main page: Effects and Timer tiles
	// ---------------------------------------------------------------------------------------------

	private View buildMainPage() {
		LinearLayout page = new LinearLayout(ctx);
		page.setOrientation(LinearLayout.VERTICAL);

		TextView title = text(ctx.getString(R.string.music_more), 18, primary, true);
		title.setPadding(dp(8), dp(2), dp(8), dp(compact ? 6 : 12));
		page.addView(title);

		LinearLayout tiles = new LinearLayout(ctx);
		tiles.setOrientation(LinearLayout.HORIZONTAL);
		page.addView(tiles, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		TextView[] sub = new TextView[1];
		View effects = tile(R.drawable.equalizer, ctx.getString(R.string.effects),
				ctx.getString(R.string.music_more_effects_hint), sub, () -> {
					dismissNow();
					onEffects.run();
				});
		View timer = tile(R.drawable.timer, ctx.getString(R.string.music_sleep_timer), "", sub,
				() -> showPage(true));
		timerTileSub = sub[0];

		tiles.addView(effects, tileParams());
		tiles.addView(timer, tileParams());

		// The second row: what's playing, into the favorites or a playlist.
		LinearLayout tiles2 = new LinearLayout(ctx);
		tiles2.setOrientation(LinearLayout.HORIZONTAL);
		LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		t2lp.topMargin = dp(8);
		page.addView(tiles2, t2lp);
		boolean fav = isFavorite.getAsBoolean();
		TextView[] sub2 = new TextView[1];
		View favorite = tile(fav ? R.drawable.favorite_filled : R.drawable.favorite,
				ctx.getString(fav ? R.string.favorites_remove : R.string.favorites_add),
				ctx.getString(R.string.music_more_favorite_hint), sub2, () -> {
					dismiss();
					onFavorite.run();
				});
		View playlist = tile(R.drawable.playlist_add, ctx.getString(R.string.playlist_add),
				ctx.getString(R.string.music_more_playlist_hint), sub2, () -> {
					dismissNow();
					onPlaylist.run();
				});
		tiles2.addView(favorite, tileParams());
		tiles2.addView(playlist, tileParams());
		return page;
	}

	private LinearLayout.LayoutParams tileParams() {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		lp.setMargins(dp(4), 0, dp(4), 0);
		return lp;
	}

	/** A big tile: an accent icon disc over a title and a one-line hint; {@code subOut[0]} gets the hint view. */
	private View tile(@DrawableRes int icon, String title, String hint, TextView[] subOut,
										Runnable onClick) {
		LinearLayout t = new LinearLayout(ctx);
		t.setOrientation(LinearLayout.VERTICAL);
		t.setGravity(Gravity.CENTER);
		t.setMinimumHeight(dp(compact ? 104 : 132));
		t.setPadding(dp(10), dp(compact ? 8 : 14), dp(10), dp(compact ? 8 : 14));
		t.setBackground(pressable(chipFill, 22));
		t.setClickable(true);
		t.setFocusable(true);
		t.setContentDescription(title);
		t.setOnClickListener(v -> onClick.run());

		FrameLayout disc = new FrameLayout(ctx);
		GradientDrawable d = new GradientDrawable();
		d.setShape(GradientDrawable.OVAL);
		d.setColor(accent);
		disc.setBackground(d);
		ImageView iv = new ImageView(ctx);
		iv.setImageResource(icon);
		iv.setImageTintList(ColorStateList.valueOf(onAccent));
		disc.addView(iv, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
		t.addView(disc, new LinearLayout.LayoutParams(dp(compact ? 44 : 52), dp(compact ? 44 : 52)));

		TextView name = text(title, 15, primary, true);
		name.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		nlp.topMargin = dp(10);
		t.addView(name, nlp);

		TextView sub = text(hint, 12, secondary, false);
		sub.setGravity(Gravity.CENTER);
		sub.setMaxLines(2);
		sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
		t.addView(sub, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		subOut[0] = sub;
		return t;
	}

	// ---------------------------------------------------------------------------------------------
	// Timer page
	// ---------------------------------------------------------------------------------------------

	private View buildTimerPage() {
		LinearLayout page = new LinearLayout(ctx);
		page.setOrientation(LinearLayout.VERTICAL);

		// Header: back, title, and the timer's state.
		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		ImageView back = new ImageView(ctx);
		back.setImageResource(me.aap.utils.R.drawable.back);
		back.setImageTintList(ColorStateList.valueOf(primary));
		back.setPadding(dp(8), dp(8), dp(8), dp(8));
		back.setBackground(pressable(Color.TRANSPARENT, 20));
		back.setFocusable(true);
		back.setClickable(true);
		back.setContentDescription(ctx.getString(R.string.music_more));
		back.setOnClickListener(v -> showPage(false));
		header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
		TextView title = text(ctx.getString(R.string.music_sleep_timer), 18, primary, true);
		title.setPadding(dp(8), 0, 0, 0);
		header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		timerStatus = text("", 14, accent, true);
		header.addView(timerStatus, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		page.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, dp(compact ? 40 : 44)));

		// Presets, three to a row (all six in one on a short, wide screen).
		int perRow = compact ? PRESETS.length : 3;
		int chipH = compact ? 38 : 44;
		presetChips.clear();
		for (int i = 0; i < PRESETS.length; i += perRow) {
			LinearLayout row = new LinearLayout(ctx);
			row.setOrientation(LinearLayout.HORIZONTAL);
			for (int j = i; (j < i + perRow) && (j < PRESETS.length); j++) {
				int m = PRESETS[j];
				TextView chip = pill(minutesText(m), false, () -> setMinutes(m));
				chip.setTag(m);
				presetChips.add(chip);
				LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(chipH), 1f);
				lp.setMargins(dp(4), dp(4), dp(4), dp(4));
				row.addView(chip, lp);
			}
			LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			if (i == 0) rlp.topMargin = dp(compact ? 4 : 8);
			page.addView(row, rlp);
		}

		// Any other length: - [ 30 min ] +
		LinearLayout stepper = new LinearLayout(ctx);
		stepper.setOrientation(LinearLayout.HORIZONTAL);
		stepper.setGravity(Gravity.CENTER_VERTICAL);
		TextView minus = pill("−", false, () -> setMinutes(snapDown(minutes)));
		minus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
		minus.setContentDescription("−" + STEP);
		minutesLabel = text("", 20, primary, true);
		minutesLabel.setGravity(Gravity.CENTER);
		TextView plus = pill("+", false, () -> setMinutes(snapUp(minutes)));
		plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
		plus.setContentDescription("+" + STEP);
		stepper.addView(minus, new LinearLayout.LayoutParams(dp(64), dp(compact ? 40 : 48)));
		stepper.addView(minutesLabel, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		stepper.addView(plus, new LinearLayout.LayoutParams(dp(64), dp(compact ? 40 : 48)));
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		slp.setMargins(dp(4), dp(compact ? 2 : 8), dp(4), dp(compact ? 0 : 4));
		page.addView(stepper, slp);

		// Let the current song finish.
		LinearLayout finish = new LinearLayout(ctx);
		finish.setOrientation(LinearLayout.HORIZONTAL);
		finish.setGravity(Gravity.CENTER_VERTICAL);
		finish.setPadding(dp(14), dp(compact ? 6 : 10), dp(10), dp(compact ? 6 : 10));
		finish.setBackground(pressable(chipFill, 18));
		finish.setClickable(true);
		finish.setFocusable(true);
		LinearLayout labels = new LinearLayout(ctx);
		labels.setOrientation(LinearLayout.VERTICAL);
		labels.addView(text(ctx.getString(R.string.music_timer_finish_song), 15, primary, true));
		labels.addView(text(ctx.getString(R.string.music_timer_finish_song_hint), 12, secondary, false));
		finish.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		SwitchCompat sw = new SwitchCompat(ctx);
		int[][] states = {{android.R.attr.state_checked}, {}};
		sw.setThumbTintList(new ColorStateList(states, new int[]{accent, secondary}));
		sw.setTrackTintList(new ColorStateList(states,
				new int[]{ColorUtils.setAlphaComponent(accent, 0x80), chipFill}));
		sw.setChecked(finishSong);
		sw.setClickable(false);
		sw.setFocusable(false);
		finish.setOnClickListener(v -> {
			finishSong = !finishSong;
			lastFinishSong = finishSong;
			sw.setChecked(finishSong);
		});
		finish.addView(sw, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		flp.setMargins(dp(4), dp(compact ? 4 : 8), dp(4), 0);
		page.addView(finish, flp);

		// Start / turn off.
		LinearLayout actions = new LinearLayout(ctx);
		actions.setOrientation(LinearLayout.HORIZONTAL);
		TextView off = pill(ctx.getString(R.string.music_timer_turn_off), false, () -> {
			cb.cancelPlaybackTimer();
			UiUtils.showToast(ctx, ctx.getString(R.string.music_timer_cancelled));
			dismiss();
		});
		off.setTag("off");
		TextView start = pill(ctx.getString(R.string.music_timer_start), true, this::startTimer);
		LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, dp(compact ? 42 : 48), 1f);
		olp.setMargins(dp(4), 0, dp(4), 0);
		LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(0, dp(compact ? 42 : 48), 1.5f);
		stlp.setMargins(dp(4), 0, dp(4), 0);
		actions.addView(off, olp);
		actions.addView(start, stlp);
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		alp.topMargin = dp(compact ? 8 : 14);
		page.addView(actions, alp);

		refreshMinutes();
		return page;
	}

	private void startTimer() {
		lastMinutes = minutes;
		lastFinishSong = finishSong;
		cb.setPlaybackTimer(minutes * 60, finishSong);
		UiUtils.showToast(ctx, ctx.getString(R.string.music_timer_set, minutesText(minutes)));
		dismiss();
	}

	private void setMinutes(int m) {
		minutes = Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, m));
		lastMinutes = minutes;
		refreshMinutes();
	}

	private static int snapDown(int m) {
		return (m % STEP == 0) ? m - STEP : m - (m % STEP);
	}

	private static int snapUp(int m) {
		return m + STEP - (m % STEP);
	}

	private void refreshMinutes() {
		minutesLabel.setText(minutesText(minutes));
		for (TextView chip : presetChips) {
			boolean sel = ((Integer) chip.getTag()) == minutes;
			chip.setBackground(pillBackground(sel));
			chip.setTextColor(sel ? onAccent : primary);
		}
	}

	private String minutesText(int m) {
		if (m < 60) return ctx.getString(R.string.music_timer_minutes, m);
		if (m % 60 == 0) return ctx.getString(R.string.music_timer_hours, m / 60);
		return ctx.getString(R.string.music_timer_hours_minutes, m / 60, m % 60);
	}

	/**
	 * Switches between the tiles and the timer page: the card's height glides to the new page's while
	 * the old page fades and drifts out and the new one fades and drifts in.
	 */
	private void showPage(boolean timer) {
		View out = timer ? mainPage : timerPage;
		View in = timer ? timerPage : mainPage;
		if (in.getVisibility() == View.VISIBLE) return;
		if (pageAnim != null) pageAnim.cancel();
		updateStatus();

		int fromH = pages.getHeight();
		in.measure(View.MeasureSpec.makeMeasureSpec(pages.getWidth(), View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int toH = in.getMeasuredHeight();
		float drift = dp(24) * (timer ? 1 : -1);
		ViewGroup.LayoutParams lp = pages.getLayoutParams();
		lp.height = fromH;
		pages.setLayoutParams(lp);
		in.setAlpha(0f);
		in.setTranslationX(drift);
		in.setVisibility(View.VISIBLE);

		pageAnim = ValueAnimator.ofFloat(0f, 1f);
		pageAnim.setDuration(240);
		pageAnim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
		pageAnim.addUpdateListener(a -> {
			float t = (float) a.getAnimatedValue();
			lp.height = Math.round(fromH + (toH - fromH) * t);
			pages.setLayoutParams(lp);
			if (centered) fitCard(lp.height);
			out.setAlpha(Math.max(0f, 1f - t / 0.4f));
			out.setTranslationX(-drift * t);
			in.setAlpha(Math.max(0f, (t - 0.25f) / 0.75f));
			in.setTranslationX(drift * (1f - t));
		});
		pageAnim.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				out.setVisibility(View.GONE);
				out.setAlpha(1f);
				out.setTranslationX(0f);
				in.setAlpha(1f);
				in.setTranslationX(0f);
				lp.height = WRAP_CONTENT;
				pages.setLayoutParams(lp);
				if (centered) fitCard(toH);
			}
		});
		pageAnim.start();
	}

	/** Refreshes what the timer tile / page say about the running timer, once a second while it counts down. */
	private void updateStatus() {
		root.removeCallbacks(tick);
		String s;
		boolean active = cb.hasPlaybackTimer();

		if (cb.isPlaybackTimerWaitingForTrackEnd()) {
			s = ctx.getString(R.string.music_timer_waiting);
		} else {
			int t = cb.getPlaybackTimer();
			if (t > 0) {
				StringBuilder sb = new StringBuilder(8);
				TextUtils.timeToString(sb, t);
				s = ctx.getString(R.string.music_timer_left, sb);
			} else {
				s = ctx.getString(R.string.music_timer_off);
				active = false;
			}
		}

		timerTileSub.setText(s);
		timerTileSub.setTextColor(active ? accent : secondary);
		timerStatus.setText(active ? s : "");
		View off = timerPage.findViewWithTag("off");
		if (off != null) off.setVisibility(active ? View.VISIBLE : View.GONE);
		if (active) root.postDelayed(tick, 1000);
	}

	// ---------------------------------------------------------------------------------------------
	// Little view helpers
	// ---------------------------------------------------------------------------------------------

	private TextView text(String s, float sp, int color, boolean bold) {
		TextView t = new TextView(ctx);
		t.setText(s);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private TextView pill(String label, boolean filled, Runnable onClick) {
		TextView t = text(label, 14, filled ? onAccent : primary, true);
		t.setGravity(Gravity.CENTER);
		t.setSingleLine(true);
		t.setBackground(pillBackground(filled));
		t.setClickable(true);
		t.setFocusable(true);
		t.setOnClickListener(v -> onClick.run());
		return t;
	}

	private Drawable pillBackground(boolean filled) {
		return pressable(filled ? accent : chipFill, 24);
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
