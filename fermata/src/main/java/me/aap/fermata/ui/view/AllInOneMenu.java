package me.aap.fermata.ui.view;

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
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.action.CarNav;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.engine.VideoQualityOption;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.fragment.ActionFabMediator;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.log.Log;
import me.aap.utils.text.TextUtils;

/**
 * The All in One card: big, easy-to-press tiles for the actions picked in Settings &gt; Interface
 * &gt; All in One (any action a key or a floating button can run), opened by a key binding or a
 * floating button set to "All in One", anywhere: over a tab or over fullscreen video. The Music
 * tab's "more" button opens the same card, with its own four tiles (Effects, Sleep timer,
 * Favorites, Playlist) unless it's set to follow the All in One settings.
 * <p>
 * The Sleep timer tile opens the timer page in the same card: presets, a stepper for any other
 * length, and the option to let the current song finish before stopping. It is also what the
 * Sleep timer action and the fullscreen video menu's Timer open.
 * <p>
 * Built in code, as an overlay view rather than a dialog: Android Auto's window context doesn't
 * allow adding dialog windows at all. In car mode the steering wheel moves through the tiles (Next
 * on the last one goes back to the first), hold Next runs one, hold Previous goes back.
 */
public final class AllInOneMenu {
	private static final int[] PRESETS = {10, 15, 30, 45, 60, 90};
	private static final int STEP = 5;
	private static final int MIN_MINUTES = 5;
	private static final int MAX_MINUTES = 12 * 60;
	/** The tiles per row Settings offers. */
	public static final int[] COLUMNS = {2, 3, 4};
	/** The Music tab's More out of the box (and the All in One card's, until changed). */
	public static final List<Action> DEFAULT_ITEMS =
			parse(MainActivityPrefs.AIO_DEFAULT_ITEMS);
	// Remembered for the next time the timer opens, until the app is closed.
	private static int lastMinutes = 30;
	private static boolean lastFinishSong = true;

	@Nullable
	private static AllInOneMenu open;

	/** What the Music tab does itself for some tiles (it says why over its cover, not in a toast). */
	public interface Hooks {
		/** @return true if it took care of {@code action} */
		boolean onAction(Action action);
	}

	private final MainActivityDelegate activity;
	private final Context ctx;
	private final ViewGroup host;
	@Nullable
	private final View insets;
	@Nullable
	private final View anchor;
	private final MediaSessionCallback cb;
	private final List<Action> items;
	private final int columns;
	@StringRes
	private final int titleRes;
	@Nullable
	private final Hooks hooks;
	/** A still picture of the card for the settings page: nothing in it reacts to a tap. */
	private final boolean preview;
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
	private View qualityPage;
	/** The Quality page's squares (filled once the player says which qualities it has). */
	private LinearLayout qualityGrid;
	private TextView qualityStatus;
	// What the player offered when last asked (see fetchQualities()); null: none to pick.
	@Nullable
	private List<VideoQualityOption> qualities;
	@Nullable
	private MediaEngine qualityEngine;
	@Nullable
	private TextView timerTileSub;
	/** The Sleep timer tile's title: on the smallest tiles it carries the timer's state too. */
	@Nullable
	private TextView timerTileName;
	private TextView timerStatus;
	private TextView minutesLabel;
	private FrameLayout pages;
	private boolean dismissing;
	private boolean centered;
	// A short screen (Android Auto, a phone on its side): tighter pages so the timer fits without scrolling.
	private final boolean compact;
	private int usableTop;
	private int usableBottom;
	private int sideLeft;
	private int sideRight;
	private ValueAnimator pageAnim;
	private int minutes = lastMinutes;
	private boolean finishSong = lastFinishSong;

	private static final int PAGE_MAIN = 0;
	private static final int PAGE_TIMER = 1;
	private static final int PAGE_QUALITY = 2;
	/** The page it opened on: the tiles, or straight on the timer or the quality page. */
	private int startPage = PAGE_MAIN;

	private AllInOneMenu(MainActivityDelegate activity, Context ctx, ViewGroup host,
											 @Nullable View insets, @Nullable View anchor, List<Action> items,
											 int columns, @StringRes int titleRes, @Nullable Hooks hooks,
											 boolean preview) {
		this.activity = activity;
		this.ctx = ctx;
		this.host = host;
		this.insets = insets;
		this.anchor = anchor;
		this.cb = activity.getMediaSessionCallback();
		this.items = items;
		this.columns = Math.max(2, Math.min(4, columns));
		this.titleRes = titleRes;
		this.hooks = hooks;
		this.preview = preview;
		this.primary = color(R.attr.musicTextPrimary);
		this.secondary = color(R.attr.musicTextSecondary);
		this.chipFill = color(R.attr.musicChipFill);
		this.ripple = color(R.attr.musicChipRipple);
		this.accent = EffectsUi.accent(ctx);
		this.onAccent = EffectsUi.onAccent(accent);
		this.density = ctx.getResources().getDisplayMetrics().density;
		computeRoom();
		this.compact = (host.getWidth() > host.getHeight()) && (usableBottom - usableTop < dp(400));
	}

	// ---------------------------------------------------------------------------------------------
	// Opening
	// ---------------------------------------------------------------------------------------------

	/** The All in One action: opens the card, or closes it if it's open. */
	public static void toggle(@NonNull MainActivityDelegate a) {
		if (dismissOpen()) return;
		show(a, PAGE_MAIN);
	}

	/** The Sleep timer action (and the fullscreen video menu's Timer): the card on its timer page. */
	public static void showTimer(@NonNull MainActivityDelegate a) {
		show(a, PAGE_TIMER);
	}

	/** The Quality action: the card on its quality page (YouTube's video qualities). */
	public static void showQuality(@NonNull MainActivityDelegate a) {
		show(a, PAGE_QUALITY);
	}

	private static void show(MainActivityDelegate a, int page) {
		ViewGroup host = a.findViewById(R.id.main_activity);
		if ((host == null) || (host.getWidth() == 0)) {
			Log.e("No view to show the All in One card over");
			return;
		}
		dismissOpen();
		AllInOneMenu m = new AllInOneMenu(a, EffectsUi.palette(a.getContext()), host, null, null,
				getItems(a), getColumns(a), R.string.all_in_one, null, false);
		m.startPage = page;
		open = m;
		// The Quality page comes up with its qualities in place, so it's sized and centred like the
		// timer page rather than growing under the user's eyes.
		if (page == PAGE_QUALITY) m.fetchQualities(() -> {
			if (open == m) m.show();
		});
		else m.show();
	}

	/**
	 * The Music tab's "more" menu: slides up from just above {@code anchor}, with the Music tab's
	 * own tiles, or the All in One ones when it's set to follow them.
	 *
	 * @param ctx    a context carrying the Music tab's palette (see {@code MusicPalette} in music.xml)
	 * @param host   the Music tab's root
	 * @param insets the view whose padding is the room the tool bar and nav bar take (the menu stays
	 *               clear of it, and centres in what's left when it doesn't fit above the anchor)
	 * @param timer  opens straight on the sleep timer page
	 */
	public static void showMusic(@NonNull MainActivityDelegate a, @NonNull Context ctx,
															 @NonNull ViewGroup host, @NonNull View insets, @NonNull View anchor,
															 boolean timer, @NonNull Hooks hooks) {
		dismissOpen();
		boolean own = a.getPrefs().getBooleanPref(MainActivityPrefs.fab(a,
				MainActivityPrefs.AIO_MUSIC_MORE));
		AllInOneMenu m = new AllInOneMenu(a, ctx, host, insets, anchor,
				own ? getItems(a) : DEFAULT_ITEMS, own ? getColumns(a) : 2, R.string.music_more, hooks,
				false);
		m.startPage = timer ? PAGE_TIMER : PAGE_MAIN;
		open = m;
		m.show();
	}

	/**
	 * The card as it would show on this screen, without the overlay around it, for the settings
	 * page: the same tiles, sizes and fill, nothing in it reacting to a tap.
	 */
	@NonNull
	public static View preview(@NonNull MainActivityDelegate a, @NonNull Context ctx,
														 @NonNull List<Action> items, int columns) {
		ViewGroup host = a.findViewById(R.id.main_activity);
		AllInOneMenu m = new AllInOneMenu(a, ctx, host, null, null, items, columns,
				R.string.all_in_one, null, true);
		return m.buildPreview();
	}

	/** Closes the card if it's open (for the back button); returns whether it was. */
	public static boolean dismissOpen() {
		AllInOneMenu m = open;
		if ((m == null) || (m.overlay == null) || (m.overlay.getParent() == null)) {
			open = null;
			return false;
		}
		m.dismiss();
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// Settings
	// ---------------------------------------------------------------------------------------------

	/** The card's actions as set for {@code a}'s screen (the car screen has its own). */
	public static List<Action> getItems(MainActivityDelegate a) {
		return parse(a.getPrefs().getStringPref(MainActivityPrefs.fab(a, MainActivityPrefs.AIO_ITEMS)));
	}

	/** Tiles per row as set for {@code a}'s screen. */
	public static int getColumns(MainActivityDelegate a) {
		return a.getPrefs().getIntPref(MainActivityPrefs.fab(a, MainActivityPrefs.AIO_COLUMNS));
	}

	/** Every action the card can hold, in the order Settings offers them. */
	public static List<Action> offeredActions() {
		List<Action> l = new ArrayList<>();
		for (Action act : Action.getAll()) {
			if ((act != Action.NONE) && (act != Action.ALL_IN_ONE)) l.add(act);
		}
		return l;
	}

	/** Action names, comma separated, skipping any unknown (an action from a newer version). */
	public static List<Action> parse(@Nullable String s) {
		if ((s == null) || s.isEmpty()) return Collections.emptyList();
		List<Action> l = new ArrayList<>();
		for (String n : s.split(",")) {
			try {
				Action act = Action.valueOf(n.trim());
				if ((act != Action.NONE) && (act != Action.ALL_IN_ONE) && !l.contains(act)) l.add(act);
			} catch (IllegalArgumentException ignore) {
			}
		}
		return l;
	}

	public static String format(List<Action> items) {
		StringBuilder sb = new StringBuilder();
		for (Action act : items) {
			if (sb.length() > 0) sb.append(',');
			sb.append(act.name());
		}
		return sb.toString();
	}

	/**
	 * The Audio effects action: the effects screen for what's playing, the YouTube player's own
	 * equalizer, or a note saying why there's none.
	 */
	public static void openEffects(@NonNull MainActivityDelegate a) {
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		PlayableItem src = (eng == null) ? null : eng.getSource();

		if ((src != null) && (eng.supportsAudioEffects() || eng.supportsSoundStage())) {
			a.showFragment(R.id.audio_effects_fragment);
		} else if ((src != null) && eng.showOwnAudioEffects()) {
			DiagnosticLog.log("AIO", "effects: engine's own (in-page equalizer)", "engine=" + eng);
		} else {
			TopToast.show(R.drawable.equalizer, (src == null) ? R.string.music_effects_play_first :
					R.string.music_effects_unavailable);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// The overlay
	// ---------------------------------------------------------------------------------------------

	/** The room the bars leave: the insets view's padding, else around the tab's body. */
	private void computeRoom() {
		int hostH = host.getHeight();
		if (insets != null) {
			usableTop = insets.getPaddingTop();
			usableBottom = hostH - insets.getPaddingBottom();
			sideLeft = insets.getPaddingLeft();
			sideRight = insets.getPaddingRight();
			return;
		}
		usableTop = 0;
		usableBottom = hostH;
		View body = activity.getBody();
		if ((body == null) || !body.isAttachedToWindow() || activity.isVideoMode()) return;
		int[] side = new int[2];
		int[] bl = new int[2];
		int[] hl = new int[2];
		activity.computeSideInsets(body, side);
		body.getLocationInWindow(bl);
		host.getLocationInWindow(hl);
		usableTop = Math.max(0, bl[1] - hl[1]);
		usableBottom = Math.min(hostH, bl[1] - hl[1] + body.getHeight());
		// The same on both sides, so the card is centred on the screen and clear of a side nav bar.
		int m = Math.max(side[0] + Math.max(0, bl[0] - hl[0]),
				side[1] + Math.max(0, (hl[0] + host.getWidth()) - (bl[0] + body.getWidth())));
		sideLeft = sideRight = m;
	}

	private void show() {
		overlay = new FrameLayout(ctx);
		overlay.setClickable(true);
		overlay.setFocusable(false);
		overlay.setElevation(dp(30));
		overlay.setOnClickListener(v -> dismiss());
		// Car mode: the steering wheel moves through the tiles, round from the last to the first, and
		// long previous goes back (a page, or closes).
		CarNav.markModalScope(overlay, this::back);
		CarNav.wrapAround(overlay);
		overlay.setBackgroundColor(0x33000000);
		overlay.setAlpha(0f);
		// Its own id: the activity's root is a ConstraintLayout, and anything that walks it with a
		// ConstraintSet requires every direct child to have one.
		overlay.setId(View.generateViewId());

		buildContent();

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

		int w = Math.min(dp(cardWidthDp()), host.getWidth() - dp(32) - sideLeft - sideRight);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, WRAP_CONTENT,
				Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		lp.bottomMargin = dp(12);
		overlay.addView(card, lp);
		host.addView(overlay, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		updateStatus();
		// Once laid out: sit just above the chip that opened it (or centred), then slide up.
		card.post(this::placeAndEnter);
	}

	/** Both pages, in {@link #root}. */
	private void buildContent() {
		root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(16), dp(16), dp(16), dp(16));

		pages = new FrameLayout(ctx);
		mainPage = buildMainPage();
		timerPage = buildTimerPage();
		qualityPage = buildQualityPage();
		mainPage.setVisibility((startPage == PAGE_MAIN) ? View.VISIBLE : View.GONE);
		timerPage.setVisibility((startPage == PAGE_TIMER) ? View.VISIBLE : View.GONE);
		qualityPage.setVisibility((startPage == PAGE_QUALITY) ? View.VISIBLE : View.GONE);
		pages.addView(mainPage, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		pages.addView(timerPage, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		pages.addView(qualityPage, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		if (startPage == PAGE_QUALITY) fillQualityPage();
		root.addView(pages, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
	}

	private View buildPreview() {
		buildContent();
		updateStatus();
		FrameLayout c = new FrameLayout(ctx);
		c.setBackground(panel());
		c.addView(root, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		// As wide as the real card, at most: the tiles keep their proportions.
		MaxWidthFrame frame = new MaxWidthFrame(ctx, dp(cardWidthDp()));
		frame.addView(c, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT,
				Gravity.CENTER_HORIZONTAL));
		return frame;
	}

	private int cardWidthDp() {
		// Opened on its Quality page: sized for its three tiles to a row, whatever the grid's size.
		if (startPage == PAGE_QUALITY) return compact ? 480 : 400;
		return switch (columns) {
			case 3 -> compact ? 560 : 440;
			case 4 -> compact ? 600 : 480;
			default -> compact ? 520 : 380;
		};
	}

	private void placeAndEnter() {
		if (overlay.getParent() == null) return;
		int hostW = host.getWidth();
		int hostH = host.getHeight();
		// Room for the taller of the two pages, so the card grows upwards into free space when the
		// timer page opens instead of running off the top.
		int contentW = card.getWidth() - dp(32);
		int ch = card.getHeight();
		int tallest = ch;
		for (View other : new View[]{mainPage, timerPage}) {
			if (other.getVisibility() == View.VISIBLE) continue;
			other.measure(View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
			tallest = Math.max(tallest, other.getMeasuredHeight() + dp(32));
		}

		FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
		lp.leftMargin = sideLeft;
		lp.rightMargin = sideRight;
		int usableH = usableBottom - usableTop - dp(16);

		int margin;
		boolean wide = hostW > hostH;
		int anchorTop = -1;
		if (anchor != null) {
			int[] a = new int[2];
			int[] h = new int[2];
			anchor.getLocationInWindow(a);
			host.getLocationInWindow(h);
			anchorTop = a[1] - h[1];
		}
		if (!wide && (anchorTop >= 0) && (tallest <= anchorTop - dp(10) - usableTop)) {
			// A phone held upright: sits right over the chip that opened it.
			centered = false;
			margin = hostH - anchorTop + dp(10);
		} else {
			// A tablet, Android Auto, any landscape screen, a key or a floating button: centred,
			// scrolling if it's taller than the room. Sized to the page showing (the timer page grows
			// it later, see fitCard()), never stretched to fill the room: a card taller than its
			// content is empty at the bottom and no longer reads as centred.
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

	/** Long previous in car mode (and a page's back arrow): to the tiles, else closes. */
	private void back() {
		if ((startPage == PAGE_MAIN) && (mainPage.getVisibility() != View.VISIBLE)) showPage(mainPage);
		else dismiss();
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

	/**
	 * A row of tiles all as tall as its tallest: measured as they are, then each again at that
	 * height (the tiles centre their content in it).
	 */
	private static final class UniformRow extends LinearLayout {
		UniformRow(Context c) {
			super(c);
			setOrientation(HORIZONTAL);
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			super.onMeasure(widthMeasureSpec,
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
			int max = 0;
			for (int i = 0, n = getChildCount(); i < n; i++) {
				max = Math.max(max, getChildAt(i).getMeasuredHeight());
			}
			for (int i = 0, n = getChildCount(); i < n; i++) {
				View c = getChildAt(i);
				c.measure(View.MeasureSpec.makeMeasureSpec(c.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.EXACTLY));
			}
			setMeasuredDimension(getMeasuredWidth(), max + getPaddingTop() + getPaddingBottom());
		}
	}

	/** As wide as it may be, never wider than {@code max}: the preview keeps the real card's width. */
	private static final class MaxWidthFrame extends FrameLayout {
		private final int max;

		MaxWidthFrame(Context c, int max) {
			super(c);
			this.max = max;
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			// All the room there is, up to the card's width: the tiles share it out by weight.
			int w = (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) ? max :
					Math.min(max, View.MeasureSpec.getSize(widthMeasureSpec));
			super.onMeasure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
					heightMeasureSpec);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Main page: the tiles
	// ---------------------------------------------------------------------------------------------

	private View buildMainPage() {
		LinearLayout page = new LinearLayout(ctx);
		page.setOrientation(LinearLayout.VERTICAL);

		// Title, and a way to the settings that pick the tiles.
		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		TextView title = text(ctx.getString(titleRes), 18, primary, true);
		title.setPadding(dp(8), dp(2), dp(8), 0);
		header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		ImageView settings = new ImageView(ctx);
		settings.setImageResource(R.drawable.settings);
		settings.setImageTintList(ColorStateList.valueOf(secondary));
		settings.setPadding(dp(8), dp(8), dp(8), dp(8));
		settings.setContentDescription(ctx.getString(R.string.all_in_one));
		if (!preview) {
			settings.setBackground(pressable(Color.TRANSPARENT, 20));
			settings.setClickable(true);
			settings.setFocusable(true);
			settings.setOnClickListener(v -> {
				dismissNow();
				activity.exitVideoMode();
				activity.showFragment(R.id.all_in_one_fragment, activity.isCarActivity());
			});
		}
		// Not for the steering wheel: the first press lands on the first tile.
		CarNav.skip(settings);
		header.addView(settings, new LinearLayout.LayoutParams(dp(36), dp(36)));
		LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		hlp.bottomMargin = dp(compact ? 4 : 8);
		page.addView(header, hlp);

		if (items.isEmpty()) {
			TextView empty = text(ctx.getString(R.string.all_in_one_empty), 14, secondary, false);
			empty.setGravity(Gravity.CENTER);
			empty.setPadding(dp(12), dp(16), dp(12), dp(16));
			page.addView(empty, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			return page;
		}

		LinearLayout row = null;
		int gap = dp((columns == 2) ? 8 : 6);
		for (int i = 0; i < items.size(); i++) {
			if (i % columns == 0) {
				row = new UniformRow(ctx);
				LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
				if (i > 0) rlp.topMargin = gap;
				page.addView(row, rlp);
			}
			row.addView(tile(items.get(i)), tileParams());
		}
		// The last row's tiles keep the others' width.
		int rest = (columns - items.size() % columns) % columns;
		for (int i = 0; i < rest; i++) row.addView(new View(ctx), tileParams());
		return page;
	}

	private LinearLayout.LayoutParams tileParams() {
		// Made as tall as the row's tallest tile by UniformRow.
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
		int m = dp((columns == 2) ? 4 : 3);
		lp.setMargins(m, 0, m, 0);
		return lp;
	}

	/** A big tile: an accent icon disc over a title and a one-line hint. */
	private View tile(Action action) {
		boolean fav = (action == Action.FAVORITE_ADD) && Action.isCurrentFavorite(activity);
		String title;
		String hint;
		if (action == Action.AUDIO_EFFECTS) {
			title = ctx.getString(R.string.effects);
			hint = ctx.getString(R.string.music_more_effects_hint);
		} else if (action == Action.SLEEP_TIMER) {
			title = ctx.getString(R.string.music_sleep_timer);
			hint = "";
		} else if (action == Action.FAVORITE_ADD) {
			// Short enough for one line, like "Add to favorites": a two-line title made this tile
			// taller than the others in the grid.
			title = ctx.getString(fav ? R.string.music_more_favorite_remove : R.string.favorites_add);
			hint = ctx.getString(R.string.music_more_favorite_hint);
		} else if (action == Action.PLAYLIST_ADD) {
			title = ctx.getString(R.string.playlist_add);
			hint = ctx.getString(R.string.music_more_playlist_hint);
		} else if (action == Action.QUALITY) {
			title = ctx.getString(R.string.quality);
			hint = ctx.getString(R.string.quality_tile_hint);
		} else {
			title = ctx.getString(ActionFabMediator.labelFor(activity, action));
			hint = "";
		}

		boolean big = columns == 2;
		LinearLayout t = new LinearLayout(ctx);
		t.setOrientation(LinearLayout.VERTICAL);
		t.setGravity(Gravity.CENTER);
		t.setMinimumHeight(dp(switch (columns) {
			case 3 -> compact ? 84 : 104;
			case 4 -> compact ? 72 : 88;
			default -> compact ? 104 : 132;
		}));
		int padV = big ? (compact ? 8 : 14) : (compact ? 6 : 10);
		t.setPadding(dp(big ? 10 : 6), dp(padV), dp(big ? 10 : 6), dp(padV));
		t.setBackground(pressable(chipFill, big ? 22 : 18));
		t.setContentDescription(title);
		if (!preview) {
			t.setClickable(true);
			t.setFocusable(true);
			t.setOnClickListener(v -> run(action));
		}

		int discDp = switch (columns) {
			case 3 -> compact ? 38 : 44;
			case 4 -> compact ? 32 : 38;
			default -> compact ? 44 : 52;
		};
		FrameLayout disc = new FrameLayout(ctx);
		GradientDrawable d = new GradientDrawable();
		d.setShape(GradientDrawable.OVAL);
		d.setColor(accent);
		disc.setBackground(d);
		ImageView iv = new ImageView(ctx);
		iv.setImageResource(ActionFabMediator.iconFor(activity, action));
		iv.setImageTintList(ColorStateList.valueOf(onAccent));
		int iconDp = (columns == 2) ? 28 : (columns == 3) ? 24 : 20;
		disc.addView(iv, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
		t.addView(disc, new LinearLayout.LayoutParams(dp(discDp), dp(discDp)));

		boolean timerLine = (columns == 4) && (action == Action.SLEEP_TIMER);
		float nameSp = (columns == 2) ? 15 : (columns == 3) ? 13.5f : 12.5f;
		TextView name = new androidx.appcompat.widget.AppCompatTextView(ctx);
		name.setText(title);
		name.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp);
		name.setTextColor(primary);
		name.setTypeface(Typeface.DEFAULT_BOLD);
		name.setGravity(Gravity.CENTER);
		name.setEllipsize(android.text.TextUtils.TruncateAt.END);
		if (timerLine) {
			// The smallest tiles: the timer's state on the title's line ("Sleep timer · 12:34"),
			// shrunk to fit rather than wrapped.
			name.setMaxLines(1);
			androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(name, 8,
					Math.round(nameSp), 1, TypedValue.COMPLEX_UNIT_SP);
			timerTileName = name;
		} else {
			name.setMaxLines(2);
		}
		LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		nlp.topMargin = dp(big ? 10 : 6);
		t.addView(name, nlp);

		TextView sub = text(hint, big ? 12 : 11, secondary, false);
		sub.setGravity(Gravity.CENTER);
		sub.setMaxLines(big ? 2 : 1);
		sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
		// The smallest tiles have no hints (the timer's state is on its title line).
		if (columns == 4) sub.setVisibility(View.GONE);
		t.addView(sub, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		if ((action == Action.SLEEP_TIMER) && !timerLine) timerTileSub = sub;
		return t;
	}

	/** A tile's tap. */
	private void run(Action action) {
		if (action == Action.SLEEP_TIMER) {
			showPage(timerPage);
			return;
		}
		if (action == Action.QUALITY) {
			fetchQualities(() -> {
				if (dismissing) return;
				fillQualityPage();
				showPage(qualityPage);
			});
			return;
		}
		DiagnosticLog.log("AIO", "run", action);
		// What opens another screen or a picker leaves at once (the picker comes up on its own); the
		// rest slide away.
		if (opensSomething(action)) dismissNow();
		else dismiss();
		if ((hooks != null) && hooks.onAction(action)) return;
		action.getHandler().handle(cb, activity, SystemClock.uptimeMillis());
	}

	private static boolean opensSomething(Action action) {
		return switch (action) {
			case AUDIO_EFFECTS, PLAYLIST_ADD, OPEN_FAVORITES, OPEN_PLAYLISTS, OPEN_DOWNLOADS, MENU,
					CP_MENU, YOUTUBE_SEARCH, YOUTUBE_UP_NEXT, REFUEL, DOWNLOAD_CURRENT, PLAY_AS_MUSIC,
					BACK_OR_EXIT, EXIT -> true;
			default -> false;
		};
	}

	// ---------------------------------------------------------------------------------------------
	// Quality page
	// ---------------------------------------------------------------------------------------------

	private View buildQualityPage() {
		LinearLayout page = new LinearLayout(ctx);
		page.setOrientation(LinearLayout.VERTICAL);

		// Header: back, title, and the quality in use.
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
		back.setContentDescription(ctx.getString(titleRes));
		back.setOnClickListener(v -> showPage(mainPage));
		header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
		TextView title = text(ctx.getString(R.string.quality), 18, primary, true);
		title.setPadding(dp(8), 0, 0, 0);
		header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		qualityStatus = text("", 14, accent, true);
		header.addView(qualityStatus, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		page.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, dp(compact ? 40 : 44)));

		TextView hint = text(ctx.getString(R.string.quality_hint), 13, secondary, false);
		hint.setPadding(dp(8), dp(2), dp(8), dp(compact ? 4 : 8));
		page.addView(hint);

		qualityGrid = new LinearLayout(ctx);
		qualityGrid.setOrientation(LinearLayout.VERTICAL);
		page.addView(qualityGrid, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return page;
	}

	/** Asks the player which qualities it has (Auto among them), then runs {@code then}. */
	private void fetchQualities(Runnable then) {
		MediaEngine eng = cb.getEngine();
		me.aap.utils.async.FutureSupplier<List<VideoQualityOption>> f =
				(eng == null) ? null : eng.getVideoQualityOptions();
		qualityEngine = eng;
		if (f == null) {
			qualities = null;
			then.run();
			return;
		}
		f.main().onCompletion((list, err) -> {
			if (err != null) Log.e(err, "No video qualities");
			qualities = ((err == null) && (list != null) && !list.isEmpty()) ? list : null;
			then.run();
		});
	}

	/** A tile per quality, or why there's none. */
	private void fillQualityPage() {
		qualityGrid.removeAllViews();
		qualityStatus.setText("");
		List<VideoQualityOption> list = qualities;
		MediaEngine eng = qualityEngine;
		if ((list == null) || (eng == null)) {
			TextView t = text(ctx.getString(R.string.quality_unavailable), 14, secondary, false);
			t.setGravity(Gravity.CENTER);
			t.setPadding(dp(12), dp(20), dp(12), dp(20));
			qualityGrid.addView(t, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
			return;
		}
		// Three to a row, the size of the card's Medium tiles.
		int perRow = 3;
		LinearLayout row = null;
		for (int i = 0; i < list.size(); i++) {
			VideoQualityOption q = list.get(i);
			if (q.selected) qualityStatus.setText(q.label);
			if (i % perRow == 0) {
				row = new UniformRow(ctx);
				LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
				if (i > 0) rlp.topMargin = dp(6);
				qualityGrid.addView(row, rlp);
			}
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins(dp(3), 0, dp(3), 0);
			row.addView(qualityTile(eng, q), lp);
		}
		int rest = (perRow - list.size() % perRow) % perRow;
		for (int i = 0; i < rest; i++) {
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
			lp.setMargins(dp(3), 0, dp(3), 0);
			row.addView(new View(ctx), lp);
		}
	}

	/** A quality: its name, and "Now" on the one in use (filled with the accent). */
	private View qualityTile(MediaEngine eng, VideoQualityOption q) {
		LinearLayout t = new LinearLayout(ctx);
		t.setOrientation(LinearLayout.VERTICAL);
		t.setGravity(Gravity.CENTER);
		t.setMinimumHeight(dp(compact ? 56 : 72));
		t.setPadding(dp(6), dp(6), dp(6), dp(6));
		t.setBackground(pressable(q.selected ? accent : chipFill, 18));
		t.setClickable(true);
		t.setFocusable(true);
		t.setContentDescription(q.label);
		t.setOnClickListener(v -> {
			DiagnosticLog.log("AIO", "quality", q.label);
			eng.setVideoQuality(q);
			TopToast.show(R.drawable.quality, ctx.getString(R.string.quality_set, q.label));
			dismiss();
		});

		TextView label = text(q.label, compact ? 15 : 17, q.selected ? onAccent : primary, true);
		label.setGravity(Gravity.CENTER);
		label.setSingleLine(true);
		label.setEllipsize(android.text.TextUtils.TruncateAt.END);
		t.addView(label, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		if (q.selected) {
			TextView now = text(ctx.getString(R.string.quality_current), 10, onAccent, true);
			now.setGravity(Gravity.CENTER);
			now.setAllCaps(true);
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			clp.topMargin = dp(2);
			t.addView(now, clp);
		}
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
		back.setContentDescription(ctx.getString(titleRes));
		back.setOnClickListener(v -> showPage(mainPage));
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
			TopToast.show(R.drawable.timer, ctx.getString(R.string.music_timer_cancelled));
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
		TopToast.show(R.drawable.timer, ctx.getString(R.string.music_timer_set, minutesText(minutes)));
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
	 * Switches between the tiles and the timer or quality page: the card's height glides to the new page's while
	 * the old page fades and drifts out and the new one fades and drifts in.
	 */
	private void showPage(View in) {
		if (in.getVisibility() == View.VISIBLE) return;
		View out = (mainPage.getVisibility() == View.VISIBLE) ? mainPage :
				(timerPage.getVisibility() == View.VISIBLE) ? timerPage : qualityPage;
		boolean forward = in != mainPage;
		if (pageAnim != null) pageAnim.cancel();
		updateStatus();

		int fromH = pages.getHeight();
		in.measure(View.MeasureSpec.makeMeasureSpec(pages.getWidth(), View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int toH = in.getMeasuredHeight();
		float drift = dp(24) * (forward ? 1 : -1);
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

		if (timerTileSub != null) {
			timerTileSub.setText(s);
			timerTileSub.setTextColor(active ? accent : secondary);
		}
		if (timerTileName != null) {
			String state;
			if (cb.isPlaybackTimerWaitingForTrackEnd()) {
				state = ctx.getString(R.string.music_timer_waiting_short);
			} else if (active) {
				StringBuilder sb = new StringBuilder(8);
				TextUtils.timeToString(sb, cb.getPlaybackTimer());
				state = sb.toString();
			} else {
				state = ctx.getString(R.string.music_timer_off);
			}
			timerTileName.setText(ctx.getString(R.string.all_in_one_timer_line,
					ctx.getString(R.string.music_sleep_timer), state));
		}
		timerStatus.setText(active ? s : "");
		View off = timerPage.findViewWithTag("off");
		if (off != null) off.setVisibility(active ? View.VISIBLE : View.GONE);
		if (active && !preview) root.postDelayed(tick, 1000);
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
