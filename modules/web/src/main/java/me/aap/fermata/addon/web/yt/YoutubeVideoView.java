package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.Nullable;

import me.aap.fermata.media.engine.BufferingIndicator;
import me.aap.fermata.ui.view.LoadingCircleView;
import me.aap.fermata.ui.view.VideoView;
import me.aap.utils.ui.UiUtils;

/**
 * @author Andrey Pavlenko
 */
public class YoutubeVideoView extends VideoView {
	// Matches the fade duration/idiom used elsewhere in the app for view crossfades (see
	// ControlPanelView's fadeIn/fadeOut, ActivityDelegate's crossfadeFragmentViews) -- a plain
	// ViewPropertyAnimator alpha tween, no external animation library.
	private static final long FADE_MS = 200L;
	// Revealing the next video after a switch cover -- a little slower than FADE_MS so the new
	// picture dissolves in alongside its audio fade-in (see youtube_fade.js) rather than popping.
	private static final long REVEAL_MS = 450L;
	// Safety net in case the page-side "hide" signal (ad-ended, or a video actually playing again
	// after a next/prev switch -- see YoutubeWebView's ad MutationObserver and 'playing' listener)
	// is ever missed -- e.g. the page navigates away mid-transition -- so the overlay can't get
	// stuck covering real content indefinitely.
	private static final long TRANSITION_OVERLAY_TIMEOUT_MS = 8000L;
	// How long a next/prev switch (no spinner up front, see showTransitionOverlay()) is given to
	// finish before the spinner appears after all -- long enough that a quick switch never shows
	// it at all (avoiding the "reads as buffering" look asked to be avoided there), short enough
	// that a genuinely slow one (YouTube resolving/buffering the next video, or the exit-fullscreen
	// fallback in YoutubeWebView#prevNextByClick()) still gets a visible "it's working" signal
	// instead of just sitting on a plain dark screen.
	private static final long SPINNER_REVEAL_DELAY_MS = 400L;
	private View transitionOverlay;
	private LoadingCircleView transitionSpinner;
	private final Runnable hideTransitionOverlayTask = this::hideTransitionOverlay;
	private final Runnable showSpinnerTask = this::revealSpinner;
	/** See {@link #setTransitionCoverHiddenListener(Runnable)}. */
	@Nullable
	private Runnable coverHiddenListener;
	/** Whether the cover currently up is covering a deliberate video switch (as opposed to an ad
	 * skip) -- see {@link #isTransitionCoverShowing()}. */
	private boolean coverIsVideoSwitch;

	public YoutubeVideoView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected void init(Context context) {
		addView(new FrameLayout(context), new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addDimOverlay(context);
		addBufferingSpinner(context);
		addTransitionOverlay(context);
	}

	@Nullable
	private LoadingCircleView bufferingSpinner;
	private final Runnable bufferingListener = this::updateBuffering;

	/**
	 * The app's loading circle over the middle of the picture while the video waits for data (see
	 * {@link BufferingIndicator}) -- rather than a frozen frame that leaves it unclear whether
	 * anything is still happening. Under the transition cover, which has its own.
	 */
	private void addBufferingSpinner(Context context) {
		LoadingCircleView circle = new LoadingCircleView(context);
		addView(circle, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER));
		bufferingSpinner = circle;
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

	private void updateBuffering() {
		LoadingCircleView v = bufferingSpinner;
		if (v != null) v.setLoading(BufferingIndicator.isBuffering());
	}

	/**
	 * Whether the black cover is up right now for a deliberate video switch. {@code
	 * YoutubeChromeClient} uses this to decide whether leaving fullscreen is something the user would
	 * actually see: while such a cover is up the exit is deliberately not animated at all, so a
	 * switch that tears the page out of fullscreen doesn't flash the watch page's layout on screen in
	 * the middle of it.
	 * <p>
	 * Deliberately false for the ad-skip cover, which is the same overlay but can be up for however
	 * long an ad pod lasts -- holding a fullscreen exit open for that long would mean a tab switch
	 * mid-ad left this black overlay sitting over whatever tab the user went to.
	 */
	boolean isTransitionCoverShowing() {
		return coverIsVideoSwitch && (transitionOverlay != null) &&
				(transitionOverlay.getVisibility() == VISIBLE);
	}

	/**
	 * Called once whenever the cover actually comes down -- because the new video started playing
	 * ({@code YoutubeMediaEngine#contentPlaying()}), or because {@link #TRANSITION_OVERLAY_TIMEOUT_MS}
	 * gave up waiting. {@code YoutubeChromeClient} uses it to finish a fullscreen exit it deferred
	 * behind the cover, so a switch that never makes it back into fullscreen still ends up showing
	 * the page rather than sitting on black.
	 */
	void setTransitionCoverHiddenListener(@Nullable Runnable r) {
		coverHiddenListener = r;
	}

	private void addTransitionOverlay(Context context) {
		FrameLayout scrim = new FrameLayout(context);
		// Fully opaque -- a translucent scrim still let an ad show through, faintly, underneath it.
		scrim.setBackgroundColor(Color.BLACK);
		LoadingCircleView spinner = new LoadingCircleView(context);
		FrameLayout.LayoutParams spp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		spp.gravity = Gravity.CENTER;
		scrim.addView(spinner, spp);
		scrim.setVisibility(GONE);
		scrim.setClickable(false);
		scrim.setFocusable(false);
		addView(scrim, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		transitionOverlay = scrim;
		transitionSpinner = spinner;
	}

	/**
	 * Fades the transition overlay in. Used both while an ad is being detected/muted/skipped (see
	 * {@code YoutubeMediaEngine#adShowing()}, {@code withSpinner} true -- shown immediately, since
	 * that wait is already known to be real) and while a next/prev switch is in flight (see
	 * {@code YoutubeMediaEngine#prepare()}, {@code withSpinner} false -- the spinner instead only
	 * appears after {@link #SPINNER_REVEAL_DELAY_MS}, if the switch is still going by then).
	 */
	void showTransitionOverlay(boolean withSpinner) {
		showTransitionOverlay(withSpinner, FADE_MS);
	}

	/**
	 * Same as {@link #showTransitionOverlay(boolean)}, fading in over {@code fadeMs} -- used with the
	 * video's own remaining time when its last moments start fading out (see {@code
	 * YoutubeMediaEngine#videoEnding}), so the picture goes to black together with the sound. If the
	 * cover is already partly or fully up, it continues from where it is rather than flashing back
	 * to transparent -- {@code YoutubeMediaEngine#ended()} re-shows it right after that ending fade.
	 */
	void showTransitionOverlay(boolean withSpinner, long fadeMs) {
		if (transitionOverlay == null) return;
		coverIsVideoSwitch = !withSpinner;
		if (!withSpinner) {
			// This view itself (the transition overlay's own parent) is what YoutubeChromeClient's
			// addCustomView()/removeCustomView() toggle VISIBLE/GONE as native HTML5 fullscreen is
			// entered/exited -- and the fallback next/prev path (YoutubeWebView#prevNextByClick(),
			// used when the page's own player API isn't available) explicitly exits fullscreen before
			// clicking the in-page button, which sets this view GONE partway through the switch. A
			// GONE ancestor hides every descendant regardless of the descendant's own visibility, so
			// without this, the overlay/spinner set VISIBLE below would still not actually render for
			// exactly the fallback path's duration -- the one case where a visible "it's working" cue
			// matters most. Only done for the no-spinner (next/prev) case, which is only ever
			// triggered while a video is already actively playing and expected to already be in
			// fullscreen -- unlike the ad-skip case below, which can fire from plain DOM mutations
			// with no such guarantee, where forcing this view visible could pop a full-screen black
			// cover over the app outside of fullscreen playback entirely.
			setVisibility(VISIBLE);
		}
		transitionSpinner.removeCallbacks(showSpinnerTask);
		if (withSpinner) {
			transitionSpinner.setLoading(true);
		} else {
			transitionSpinner.setLoading(false);
			transitionSpinner.postDelayed(showSpinnerTask, fadeMs + SPINNER_REVEAL_DELAY_MS);
		}

		transitionOverlay.removeCallbacks(hideTransitionOverlayTask);
		transitionOverlay.animate().cancel();
		boolean alreadyUp = transitionOverlay.getVisibility() == VISIBLE;
		float from = alreadyUp ? transitionOverlay.getAlpha() : 0f;
		transitionOverlay.setAlpha(from);
		transitionOverlay.setVisibility(VISIBLE);
		// Scaled to the distance still left to cover, so re-showing an almost-opaque cover doesn't
		// take a full fade to do nothing visible.
		long duration = Math.max(0L, Math.round(fadeMs * (1f - from)));
		transitionOverlay.animate().alpha(1f).setDuration(duration).start();
		transitionOverlay.postDelayed(hideTransitionOverlayTask, TRANSITION_OVERLAY_TIMEOUT_MS);
	}

	private void revealSpinner() {
		transitionSpinner.setLoading(true);
	}

	/** See {@code YoutubeMediaEngine#adEnded()}/{@code #contentPlaying()}. */
	void hideTransitionOverlay() {
		if (transitionOverlay == null) return;
		// Reached unconditionally from contentPlaying()/adEnded() whether or not anything is actually
		// covered, so the listener below must only fire for a cover that was really up -- otherwise
		// every ordinary "playing" event would look like a finished transition.
		boolean wasShowing = isTransitionCoverShowing();
		coverIsVideoSwitch = false;
		transitionOverlay.removeCallbacks(hideTransitionOverlayTask);
		transitionSpinner.removeCallbacks(showSpinnerTask);
		transitionSpinner.setLoading(false);
		transitionOverlay.animate().cancel();
		transitionOverlay.animate().alpha(0f).setDuration(wasShowing ? REVEAL_MS : FADE_MS)
				.withEndAction(() -> transitionOverlay.setVisibility(GONE)).start();
		if (!wasShowing) return;
		Runnable l = coverHiddenListener;
		if (l != null) l.run();
	}

	@Nullable
	@Override
	public SurfaceView getSubtitleSurface() {
		return null;
	}
}
