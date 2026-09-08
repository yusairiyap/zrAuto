package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.Nullable;

import me.aap.fermata.ui.view.VideoView;

/**
 * @author Andrey Pavlenko
 */
public class YoutubeVideoView extends VideoView {
	// Matches the fade duration/idiom used elsewhere in the app for view crossfades (see
	// ControlPanelView's fadeIn/fadeOut, ActivityDelegate's crossfadeFragmentViews) -- a plain
	// ViewPropertyAnimator alpha tween, no external animation library.
	private static final long FADE_MS = 200L;
	// Safety net in case the page-side "hide" signal (ad-ended, or a video actually playing again
	// after a next/prev switch -- see YoutubeWebView's ad MutationObserver and 'playing' listener)
	// is ever missed -- e.g. the page navigates away mid-transition -- so the overlay can't get
	// stuck covering real content indefinitely.
	private static final long TRANSITION_OVERLAY_TIMEOUT_MS = 8000L;
	private View transitionOverlay;
	private View transitionSpinner;
	private final Runnable hideTransitionOverlayTask = this::hideTransitionOverlay;

	public YoutubeVideoView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected void init(Context context) {
		addView(new FrameLayout(context), new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addDimOverlay(context);
		addTransitionOverlay(context);
	}

	private void addTransitionOverlay(Context context) {
		FrameLayout scrim = new FrameLayout(context);
		// Fully opaque -- a translucent scrim still let an ad show through, faintly, underneath it.
		scrim.setBackgroundColor(Color.BLACK);
		ProgressBar spinner = new ProgressBar(context);
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
	 * {@code YoutubeMediaEngine#adShowing()}, {@code withSpinner} true) and while a next/prev
	 * switch is in flight (see {@code YoutubeMediaEngine#prepare()}, {@code withSpinner} false --
	 * a spinner there would read as "buffering" rather than a deliberate transition).
	 */
	void showTransitionOverlay(boolean withSpinner) {
		if (transitionOverlay == null) return;
		transitionSpinner.setVisibility(withSpinner ? VISIBLE : GONE);
		transitionOverlay.removeCallbacks(hideTransitionOverlayTask);
		transitionOverlay.animate().cancel();
		transitionOverlay.setAlpha(0f);
		transitionOverlay.setVisibility(VISIBLE);
		transitionOverlay.animate().alpha(1f).setDuration(FADE_MS).start();
		transitionOverlay.postDelayed(hideTransitionOverlayTask, TRANSITION_OVERLAY_TIMEOUT_MS);
	}

	/** See {@code YoutubeMediaEngine#adEnded()}/{@code #contentPlaying()}. */
	void hideTransitionOverlay() {
		if (transitionOverlay == null) return;
		transitionOverlay.removeCallbacks(hideTransitionOverlayTask);
		transitionOverlay.animate().cancel();
		transitionOverlay.animate().alpha(0f).setDuration(FADE_MS)
				.withEndAction(() -> transitionOverlay.setVisibility(GONE)).start();
	}

	@Nullable
	@Override
	public SurfaceView getSubtitleSurface() {
		return null;
	}
}
