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
	// Safety net in case the page-side ad-ended signal (see YoutubeWebView's ad MutationObserver) is
	// ever missed -- e.g. the page navigates away while an ad is showing -- so the overlay can't get
	// stuck covering real content indefinitely.
	private static final long AD_OVERLAY_TIMEOUT_MS = 8000L;
	private View adOverlay;
	private final Runnable hideAdOverlayTask = this::hideAdOverlay;

	public YoutubeVideoView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected void init(Context context) {
		addView(new FrameLayout(context), new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addDimOverlay(context);
		addAdOverlay(context);
	}

	private void addAdOverlay(Context context) {
		FrameLayout scrim = new FrameLayout(context);
		scrim.setBackgroundColor(Color.argb(204, 0, 0, 0));
		ProgressBar spinner = new ProgressBar(context);
		FrameLayout.LayoutParams spp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		spp.gravity = Gravity.CENTER;
		scrim.addView(spinner, spp);
		scrim.setVisibility(GONE);
		scrim.setClickable(false);
		scrim.setFocusable(false);
		addView(scrim, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		adOverlay = scrim;
	}

	/** Shown while an ad is being detected/muted/skipped -- see {@code YoutubeMediaEngine#adShowing()}. */
	void showAdOverlay() {
		if (adOverlay == null) return;
		adOverlay.setVisibility(VISIBLE);
		adOverlay.removeCallbacks(hideAdOverlayTask);
		adOverlay.postDelayed(hideAdOverlayTask, AD_OVERLAY_TIMEOUT_MS);
	}

	/** See {@code YoutubeMediaEngine#adEnded()}. */
	void hideAdOverlay() {
		if (adOverlay == null) return;
		adOverlay.removeCallbacks(hideAdOverlayTask);
		adOverlay.setVisibility(GONE);
	}

	@Nullable
	@Override
	public SurfaceView getSubtitleSurface() {
		return null;
	}
}
