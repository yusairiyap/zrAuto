package me.aap.fermata.ui.view;

import static android.os.Build.VERSION.SDK_INT;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.transition.TransitionManager;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
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

	private final Context ctx;
	private final MediaSessionCallback cb;
	private final Runnable onEffects;
	private final Dialog dialog;
	private final boolean light;
	private final int primary;
	private final int secondary;
	private final int accent;
	private final int chipFill;
	private final int ripple;
	private final float density;
	private final Runnable tick = this::updateStatus;
	private final List<TextView> presetChips = new ArrayList<>();
	private LinearLayout root;
	private View mainPage;
	private View timerPage;
	private TextView timerTileSub;
	private TextView timerStatus;
	private TextView minutesLabel;
	private int minutes = lastMinutes;
	private boolean finishSong = lastFinishSong;

	private MusicMoreMenu(Context ctx, MediaSessionCallback cb, Runnable onEffects) {
		this.ctx = ctx;
		this.cb = cb;
		this.onEffects = onEffects;
		this.dialog = new Dialog(ctx, R.style.MusicMenuDialog);
		this.light = MusicPlayerFragment.isLightTheme(ctx);
		this.primary = color(R.attr.musicTextPrimary);
		this.secondary = color(R.attr.musicTextSecondary);
		this.chipFill = color(R.attr.musicChipFill);
		this.ripple = color(R.attr.musicChipRipple);
		this.accent = ContextCompat.getColor(ctx, R.color.music_accent);
		this.density = ctx.getResources().getDisplayMetrics().density;
	}

	/**
	 * Shows the menu.
	 *
	 * @param ctx       a context carrying the Music tab's palette (see {@code MusicPalette} in music.xml)
	 * @param onEffects what the Effects tile does
	 */
	public static void show(@NonNull Context ctx, @NonNull MediaSessionCallback cb,
													@NonNull Runnable onEffects) {
		new MusicMoreMenu(ctx, cb, onEffects).show();
	}

	private void show() {
		root = new LinearLayout(dialog.getContext());
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(16), dp(16), dp(16), dp(16));

		FrameLayout pages = new FrameLayout(ctx);
		mainPage = buildMainPage();
		timerPage = buildTimerPage();
		timerPage.setVisibility(View.GONE);
		pages.addView(mainPage, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		pages.addView(timerPage, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		root.addView(pages, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		dialog.setContentView(root);
		dialog.setCanceledOnTouchOutside(true);
		dialog.setOnDismissListener(d -> root.removeCallbacks(tick));
		configureWindow();
		dialog.show();

		root.setAlpha(0f);
		root.setTranslationY(dp(24));
		root.animate().alpha(1f).translationY(0f).setDuration(220)
				.setInterpolator(new DecelerateInterpolator()).start();
		updateStatus();
	}

	// ---------------------------------------------------------------------------------------------
	// Window: the frosted glass
	// ---------------------------------------------------------------------------------------------

	private void configureWindow() {
		Window w = dialog.getWindow();
		if (w == null) return;

		boolean blurred = false;
		if (SDK_INT >= 31) {
			WindowManager wm = ctx.getSystemService(WindowManager.class);
			blurred = (wm != null) && wm.isCrossWindowBlurEnabled();
		}

		int top;
		int bottom;
		if (blurred) {
			top = light ? 0xB8FFFFFF : 0x40FFFFFF;
			bottom = light ? 0x8CFFFFFF : 0x24FFFFFF;
		} else {
			top = light ? 0xF7FFFFFF : 0xF2262632;
			bottom = light ? 0xF7F1F2F6 : 0xF21A1A24;
		}

		GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
				new int[]{top, bottom});
		glass.setCornerRadius(dp(28));
		glass.setStroke(Math.max(1, dp(1)), light ? 0x33FFFFFF : 0x55FFFFFF);
		w.setBackgroundDrawable(glass);

		DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
		WindowManager.LayoutParams lp = w.getAttributes();
		lp.width = Math.min(dp(380), dm.widthPixels - dp(32));
		lp.height = WRAP_CONTENT;
		lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
		lp.y = dp(32);

		if (blurred && (SDK_INT >= 31)) {
			w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
			lp.setBlurBehindRadius(24);
			w.setBackgroundBlurRadius(110);
		}

		w.setAttributes(lp);
	}

	// ---------------------------------------------------------------------------------------------
	// Main page: Effects and Timer tiles
	// ---------------------------------------------------------------------------------------------

	private View buildMainPage() {
		LinearLayout page = new LinearLayout(ctx);
		page.setOrientation(LinearLayout.VERTICAL);

		TextView title = text(ctx.getString(R.string.music_more), 18, primary, true);
		title.setPadding(dp(8), dp(2), dp(8), dp(12));
		page.addView(title);

		LinearLayout tiles = new LinearLayout(ctx);
		tiles.setOrientation(LinearLayout.HORIZONTAL);
		page.addView(tiles, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		TextView[] sub = new TextView[1];
		View effects = tile(R.drawable.equalizer, ctx.getString(R.string.effects),
				ctx.getString(R.string.music_more_effects_hint), sub, () -> {
					dialog.dismiss();
					onEffects.run();
				});
		View timer = tile(R.drawable.timer, ctx.getString(R.string.music_sleep_timer), "", sub,
				() -> showPage(true));
		timerTileSub = sub[0];

		tiles.addView(effects, tileParams());
		tiles.addView(timer, tileParams());
		return page;
	}

	private LinearLayout.LayoutParams tileParams() {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(132), 1f);
		lp.setMargins(dp(4), 0, dp(4), 0);
		return lp;
	}

	/** A big tile: an accent icon disc over a title and a one-line hint; {@code subOut[0]} gets the hint view. */
	private View tile(@DrawableRes int icon, String title, String hint, TextView[] subOut,
										Runnable onClick) {
		LinearLayout t = new LinearLayout(ctx);
		t.setOrientation(LinearLayout.VERTICAL);
		t.setGravity(Gravity.CENTER);
		t.setPadding(dp(10), dp(12), dp(10), dp(12));
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
		iv.setImageTintList(ColorStateList.valueOf(0xFF000000));
		disc.addView(iv, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
		t.addView(disc, new LinearLayout.LayoutParams(dp(52), dp(52)));

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
		page.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, dp(44)));

		// Presets, three to a row.
		presetChips.clear();
		for (int i = 0; i < PRESETS.length; i += 3) {
			LinearLayout row = new LinearLayout(ctx);
			row.setOrientation(LinearLayout.HORIZONTAL);
			for (int j = i; (j < i + 3) && (j < PRESETS.length); j++) {
				int m = PRESETS[j];
				TextView chip = pill(minutesText(m), false, () -> setMinutes(m));
				chip.setTag(m);
				presetChips.add(chip);
				LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
				lp.setMargins(dp(4), dp(4), dp(4), dp(4));
				row.addView(chip, lp);
			}
			LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
			if (i == 0) rlp.topMargin = dp(8);
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
		stepper.addView(minus, new LinearLayout.LayoutParams(dp(64), dp(48)));
		stepper.addView(minutesLabel, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		stepper.addView(plus, new LinearLayout.LayoutParams(dp(64), dp(48)));
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		slp.setMargins(dp(4), dp(8), dp(4), dp(4));
		page.addView(stepper, slp);

		// Let the current song finish.
		LinearLayout finish = new LinearLayout(ctx);
		finish.setOrientation(LinearLayout.HORIZONTAL);
		finish.setGravity(Gravity.CENTER_VERTICAL);
		finish.setPadding(dp(14), dp(10), dp(10), dp(10));
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
		flp.setMargins(dp(4), dp(8), dp(4), 0);
		page.addView(finish, flp);

		// Start / turn off.
		LinearLayout actions = new LinearLayout(ctx);
		actions.setOrientation(LinearLayout.HORIZONTAL);
		TextView off = pill(ctx.getString(R.string.music_timer_turn_off), false, () -> {
			cb.cancelPlaybackTimer();
			UiUtils.showToast(ctx, ctx.getString(R.string.music_timer_cancelled));
			dialog.dismiss();
		});
		off.setTag("off");
		TextView start = pill(ctx.getString(R.string.music_timer_start), true, this::startTimer);
		LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, dp(48), 1f);
		olp.setMargins(dp(4), 0, dp(4), 0);
		LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(0, dp(48), 1.5f);
		stlp.setMargins(dp(4), 0, dp(4), 0);
		actions.addView(off, olp);
		actions.addView(start, stlp);
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		alp.topMargin = dp(14);
		page.addView(actions, alp);

		refreshMinutes();
		return page;
	}

	private void startTimer() {
		lastMinutes = minutes;
		lastFinishSong = finishSong;
		cb.setPlaybackTimer(minutes * 60, finishSong);
		UiUtils.showToast(ctx, ctx.getString(R.string.music_timer_set, minutesText(minutes)));
		dialog.dismiss();
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
			chip.setTextColor(sel ? 0xFF000000 : primary);
		}
	}

	private String minutesText(int m) {
		if (m < 60) return ctx.getString(R.string.music_timer_minutes, m);
		if (m % 60 == 0) return ctx.getString(R.string.music_timer_hours, m / 60);
		return ctx.getString(R.string.music_timer_hours_minutes, m / 60, m % 60);
	}

	private void showPage(boolean timer) {
		TransitionManager.beginDelayedTransition(root);
		mainPage.setVisibility(timer ? View.GONE : View.VISIBLE);
		timerPage.setVisibility(timer ? View.VISIBLE : View.GONE);
		updateStatus();
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
		TextView t = text(label, 14, filled ? 0xFF000000 : primary, true);
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
