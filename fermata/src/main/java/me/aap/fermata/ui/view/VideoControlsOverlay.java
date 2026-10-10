package me.aap.fermata.ui.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import me.aap.fermata.R;
import me.aap.fermata.media.engine.BufferingIndicator;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * The YouTube-like controls over fullscreen video, local and YouTube alike: a dim over the picture,
 * the title (and channel/artist) at the top, and previous / play-pause / next in the middle; plus
 * the double tap seek feedback ({@link DoubleTapSeekView}). The control panel at the bottom still
 * has the seek bar and menu; {@link ControlPanelView} decides when this shows (see
 * {@code ControlPanelView#showVideoControls}): the buttons with the panel, the title only when a
 * single tap on the video brought them.
 * <p>
 * Lies over the whole video, so every touch that misses its buttons lands here and is handed to
 * the video view's own gesture handling. For YouTube that also keeps taps away from the page's own
 * player controls (hidden in fullscreen, see YoutubeWebView), which would otherwise show under these.
 */
public class VideoControlsOverlay extends FrameLayout {
	private static final long FADE_MS = 200L;
	private static final int SCRIM_COLOR = 0x66000000;
	/** The round buttons' see-through dark fill and press ripple; the floating buttons take the same
	 * over fullscreen video (see ControlPanelView#applyFabVideoLook). */
	static final int BUTTON_BG = 0x59000000;
	static final int BUTTON_RIPPLE = 0x40FFFFFF;
	static final int BUTTON_ICON = 0xFFFFFFFF;
	private final View scrim;
	private final LinearLayout titleBar;
	private final TextView title;
	private final TextView subtitle;
	private final LinearLayout center;
	private final ImageView prev;
	private final ImageView playPause;
	private final ImageView next;
	private final DoubleTapSeekView seekView;
	private final int titlePadH;
	private boolean centerShown;
	private boolean titleShown;
	/** Set while a touch on a middle button goes to the video instead (see {@link #buttonTouch}). */
	private boolean forwardingTouch;

	/**
	 * A loading circle of its own in the middle, for a video view without one (local video; YouTube's
	 * has its own under this view), see {@link #updateBuffering}.
	 */
	private final LoadingCircleView ownSpinner;
	/**
	 * Whether the video view has no loading circle of its own (local video): this one then shows for
	 * any stall while playing. Otherwise (YouTube's) only while the middle buttons are up, in front
	 * of their dim, the view's own one standing down meanwhile (see {@link #setCenterListener}).
	 */
	private final boolean spinnerAlways;
	@Nullable
	private Runnable centerListener;
	private final Runnable bufferingListener = this::updateBuffering;
	private boolean buffering;

	public VideoControlsOverlay(Context ctx) {
		this(ctx, true);
	}

	public VideoControlsOverlay(Context ctx, boolean withSpinner) {
		super(ctx);
		setClickable(false);
		setFocusable(false);

		seekView = new DoubleTapSeekView(ctx);
		addView(seekView, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

		scrim = new View(ctx);
		scrim.setBackgroundColor(SCRIM_COLOR);
		scrim.setVisibility(GONE);
		addView(scrim, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

		titlePadH = toIntPx(ctx, 20);
		titleBar = new LinearLayout(ctx);
		titleBar.setOrientation(LinearLayout.VERTICAL);
		titleBar.setPadding(titlePadH, toIntPx(ctx, 14), titlePadH, toIntPx(ctx, 14));
		titleBar.setVisibility(GONE);
		title = newText(ctx, 17, 0xFFFFFFFF, true);
		subtitle = newText(ctx, 13, 0xCCFFFFFF, false);
		titleBar.addView(title, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		titleBar.addView(subtitle, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
		addView(titleBar, new LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP | Gravity.START));

		center = new LinearLayout(ctx);
		center.setOrientation(LinearLayout.HORIZONTAL);
		center.setGravity(Gravity.CENTER_VERTICAL);
		center.setVisibility(GONE);
		int small = toIntPx(ctx, 52);
		int big = toIntPx(ctx, 68);
		int gap = toIntPx(ctx, 44);
		prev = newButton(ctx, R.drawable.prev, small, toIntPx(ctx, 14));
		playPause = newButton(ctx, R.drawable.play_pause, big, toIntPx(ctx, 16));
		next = newButton(ctx, R.drawable.next, small, toIntPx(ctx, 14));
		prev.setId(R.id.video_controls_prev);
		playPause.setId(R.id.video_controls_play_pause);
		next.setId(R.id.video_controls_next);
		prev.setContentDescription(ctx.getString(R.string.action_prev));
		playPause.setContentDescription(ctx.getString(R.string.action_play_pause));
		next.setContentDescription(ctx.getString(R.string.action_next));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(small, small);
		lp.setMarginEnd(gap);
		center.addView(prev, lp);
		center.addView(playPause, new LinearLayout.LayoutParams(big, big));
		lp = new LinearLayout.LayoutParams(small, small);
		lp.setMarginStart(gap);
		center.addView(next, lp);
		addView(center, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER));
		// In front of everything here, the dim and the buttons included: not darkened by them.
		spinnerAlways = withSpinner;
		ownSpinner = new LoadingCircleView(ctx);
		addView(ownSpinner, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER));

		for (View btn : new View[]{prev, playPause, next}) btn.setOnTouchListener(this::buttonTouch);
		prev.setOnClickListener(v -> onButton(b -> b.onPrevNextButtonClick(false)));
		next.setOnClickListener(v -> onButton(b -> b.onPrevNextButtonClick(true)));
		// Not the panel's play/pause handler: that one stops playback on a second tap within 300 ms,
		// too easy to hit here, where a double tap anywhere else seeks.
		playPause.setOnClickListener(v -> onButton(b -> {
			if (b.isPlaying()) b.getMediaSessionCallback().onPause();
			else b.getMediaSessionCallback().onPlay();
		}));
	}

	/**
	 * The middle buttons stay up with the panel during a double tap seek streak, so a quick tap of
	 * the streak can land on one: then it's one more seek, handed to the video view like a tap that
	 * missed them, not a press of the button.
	 */
	@SuppressLint("ClickableViewAccessibility")
	private boolean buttonTouch(View v, MotionEvent e) {
		int act = e.getActionMasked();
		if (act == MotionEvent.ACTION_DOWN) {
			forwardingTouch = MainActivityDelegate.get(getContext()).getControlPanel().isSeekStreakActive();
		}
		if (!forwardingTouch) return false;
		if (getParent() instanceof VideoView vv) {
			// From the button's own coordinates to this view's (the video view's).
			MotionEvent c = MotionEvent.obtain(e);
			c.offsetLocation(v.getLeft() + center.getLeft(), v.getTop() + center.getTop());
			vv.onTouchEvent(c);
			c.recycle();
		}
		if ((act == MotionEvent.ACTION_UP) || (act == MotionEvent.ACTION_CANCEL)) forwardingTouch = false;
		return true;
	}

	private static TextView newText(Context ctx, int sp, int color, boolean bold) {
		TextView t = new TextView(ctx);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		t.setMaxLines(1);
		t.setSingleLine(true);
		t.setEllipsize(TextUtils.TruncateAt.END);
		if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setShadowLayer(toIntPx(ctx, 3), 0, 0, 0x99000000);
		return t;
	}

	private static ImageView newButton(Context ctx, int icon, int size, int pad) {
		AppCompatImageView b = new AppCompatImageView(ctx);
		b.setImageResource(icon);
		b.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
		b.setScaleType(ImageView.ScaleType.FIT_CENTER);
		b.setPadding(pad, pad, pad, pad);
		b.setClickable(true);
		b.setFocusable(true);

		GradientDrawable normal = new GradientDrawable();
		normal.setShape(GradientDrawable.OVAL);
		normal.setColor(BUTTON_BG);
		GradientDrawable focused = new GradientDrawable();
		focused.setShape(GradientDrawable.OVAL);
		focused.setColor(BUTTON_BG);
		focused.setStroke(toIntPx(ctx, 2), 0xFFFFFFFF);
		StateListDrawable bg = new StateListDrawable();
		bg.addState(new int[]{android.R.attr.state_focused}, focused);
		bg.addState(new int[]{}, normal);
		GradientDrawable mask = new GradientDrawable();
		mask.setShape(GradientDrawable.OVAL);
		mask.setColor(0xFFFFFFFF);
		b.setBackground(new RippleDrawable(ColorStateList.valueOf(BUTTON_RIPPLE), bg, mask));
		b.setMinimumWidth(size);
		b.setMinimumHeight(size);
		return b;
	}

	private void onButton(me.aap.utils.function.Consumer<FermataServiceUiBinder> action) {
		MainActivityDelegate a = MainActivityDelegate.get(getContext());
		action.accept(a.getMediaServiceBinder());
		// Using the controls keeps them up.
		a.getControlPanel().restartVideoHideTimer();
	}

	/**
	 * Every touch that misses the buttons goes to the video view's gesture handling (single tap
	 * shows/hides the controls, double tap seeks, ...), as if it had landed on the video itself.
	 */
	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent e) {
		if (getParent() instanceof VideoView vv) vv.onTouchEvent(e);
		return true;
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		BufferingIndicator.addListener(bufferingListener);
		updateBuffering();
	}

	@Override
	protected void onDetachedFromWindow() {
		BufferingIndicator.removeListener(bufferingListener);
		super.onDetachedFromWindow();
	}

	/**
	 * While the video waits for data, the loading circle takes the play/pause button's place in the
	 * middle: the button (and its round backing) shrinks and fades away over it, and comes back the
	 * same way, rather than both being drawn on top of each other.
	 */
	private void updateSpinner() {
		boolean b = BufferingIndicator.isBuffering();
		if (spinnerAlways) {
			// A stall while playing only: before that (the start), the activity's own loading circle is
			// already up in the middle, and two would sit on each other.
			MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
			FermataServiceUiBinder fb = (a == null) ? null : a.getMediaServiceBinder();
			ownSpinner.setLoading(b && (fb != null) && fb.isPlaying());
		} else {
			ownSpinner.setLoading(b && centerShown);
		}
	}

	/** Called whenever the middle buttons come or go (the video view's own loading circle follows). */
	public void setCenterListener(@Nullable Runnable l) {
		centerListener = l;
	}

	private void updateBuffering() {
		boolean b = BufferingIndicator.isBuffering();
		updateSpinner();
		if (b == buffering) return;
		buffering = b;
		playPause.setEnabled(centerShown && !b);
		playPause.animate().cancel();
		playPause.animate().alpha(b ? 0f : 1f).scaleX(b ? 0.6f : 1f).scaleY(b ? 0.6f : 1f)
				.setDuration(FADE_MS).setInterpolator(new DecelerateInterpolator()).start();
	}

	public boolean isCenterShown() {
		return centerShown;
	}

	public boolean isTitleShown() {
		return titleShown;
	}

	/**
	 * Shows or hides the middle buttons and the title, fading them (and the dim behind them) unless
	 * {@code animate} is false.
	 */
	public void setShown(boolean showCenter, boolean showTitle, boolean animate) {
		boolean showScrim = showCenter || showTitle;
		boolean scrimWas = centerShown || titleShown;
		if (showCenter != centerShown) fade(center, showCenter, animate, 0.9f, 0f);
		if (showTitle != titleShown) fade(titleBar, showTitle, animate, 1f, -toIntPx(getContext(), 8));
		if (showScrim != scrimWas) fade(scrim, showScrim, animate, 1f, 0f);
		boolean centerChanged = centerShown != showCenter;
		centerShown = showCenter;
		if (centerChanged) {
			updateSpinner();
			if (centerListener != null) centerListener.run();
		}
		titleShown = showTitle;
		// Fading out, they no longer take taps: a quick tap meant for the video (a double tap seek
		// right after them) must not land on one.
		prev.setEnabled(showCenter);
		playPause.setEnabled(showCenter && !buffering);
		next.setEnabled(showCenter);
		if (!showCenter && center.hasFocus()) center.clearFocus();
	}

	private static void fade(View v, boolean show, boolean animate, float scaleFrom, float yFrom) {
		v.animate().cancel();
		if (!animate) {
			v.setAlpha(1f);
			v.setScaleX(1f);
			v.setScaleY(1f);
			v.setTranslationY(0f);
			v.setVisibility(show ? VISIBLE : GONE);
			return;
		}
		DecelerateInterpolator in = new DecelerateInterpolator();
		if (show) {
			if (v.getVisibility() != VISIBLE) {
				v.setAlpha(0f);
				v.setScaleX(scaleFrom);
				v.setScaleY(scaleFrom);
				v.setTranslationY(yFrom);
				v.setVisibility(VISIBLE);
			}
			v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(FADE_MS)
					.setInterpolator(in).start();
		} else {
			if (v.getVisibility() != VISIBLE) return;
			v.animate().alpha(0f).scaleX(scaleFrom).scaleY(scaleFrom).translationY(yFrom)
					.setDuration(FADE_MS).setInterpolator(in).withEndAction(() -> {
						v.setVisibility(GONE);
						v.setScaleX(1f);
						v.setScaleY(1f);
						v.setTranslationY(0f);
						v.setAlpha(1f);
					}).start();
		}
	}

	public void setTitle(@Nullable CharSequence t, @Nullable CharSequence sub) {
		title.setText(t);
		subtitle.setText(sub);
		subtitle.setVisibility(TextUtils.isEmpty(sub) ? GONE : VISIBLE);
	}

	/**
	 * Where the title's text ends, from this view's left edge, at its full length (not cut short):
	 * what the Info Overlay has to stay clear of.
	 */
	public int getTitleTextEnd() {
		float w = textWidth(title);
		if (subtitle.getVisibility() == VISIBLE) w = Math.max(w, textWidth(subtitle));
		return Math.round(titleBar.getPaddingStart() + w);
	}

	private static float textWidth(TextView t) {
		CharSequence s = t.getText();
		return ((s == null) || (s.length() == 0)) ? 0f : t.getPaint().measureText(s, 0, s.length());
	}

	/** Keeps the title clear of something on the right of the top edge (the Info Overlay). */
	public void setTitleEndInset(int px) {
		int end = Math.max(titlePadH, px);
		if (titleBar.getPaddingEnd() == end) return;
		titleBar.setPaddingRelative(titleBar.getPaddingStart(), titleBar.getPaddingTop(), end,
				titleBar.getPaddingBottom());
	}

	/** Mirrors the panel's play/pause button: activated while playing, selected while paused. */
	public void setPlayPauseState(boolean selected, boolean activated) {
		playPause.setSelected(selected);
		playPause.setActivated(activated);
	}

	public void showSeek(boolean forward, int seconds, float x, float y) {
		seekView.show(forward, seconds, x, y);
	}

	public void hideSeek() {
		seekView.hide();
	}

	/** Whether one of the middle buttons has the focus (D-pad). */
	public boolean hasButtonFocus() {
		return center.hasFocus();
	}

	public View getPlayPauseButton() {
		return playPause;
	}

	/**
	 * D-pad moves between the middle buttons: left/right among them, {@code null} (stay) at the
	 * ends or for anything that isn't one of them.
	 */
	@Nullable
	public View focusSearchButtons(@NonNull View focused, int direction) {
		if (focused == playPause) {
			if (direction == FOCUS_LEFT) return prev;
			if (direction == FOCUS_RIGHT) return next;
		} else if (focused == prev) {
			if (direction == FOCUS_RIGHT) return playPause;
		} else if (focused == next) {
			if (direction == FOCUS_LEFT) return playPause;
		}
		return null;
	}

	public boolean isButton(View v) {
		return (v == prev) || (v == playPause) || (v == next);
	}
}
