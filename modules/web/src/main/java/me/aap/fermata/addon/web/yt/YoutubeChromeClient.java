package me.aap.fermata.addon.web.yt;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.web.FermataChromeClient;
import me.aap.fermata.addon.web.FermataWebView;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.VideoView;

import static android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED;
import static android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

/**
 * @author Andrey Pavlenko
 */
public class YoutubeChromeClient extends FermataChromeClient {
	/**
	 * How long after the black transition cover comes down a deferred fullscreen exit waits before
	 * actually happening. The cover normally comes down the moment the new video reports itself
	 * playing, and the app's own re-entry into fullscreen follows a beat later (see {@code
	 * YoutubeFragment#onPlayableChanged}) -- without this grace the exit would land in that gap and
	 * produce exactly the flash of page layout the deferral exists to prevent.
	 */
	private static final long DEFERRED_EXIT_GRACE_MS = 1000L;
	/**
	 * Backstop: however the cover's own lifecycle goes, a deferred exit never waits longer than this
	 * before completing. Without it, a cover that is somehow never taken down (its host view detached
	 * mid-transition, say) would leave the app showing a blank fullscreen container with no way back.
	 */
	private static final long DEFERRED_EXIT_TIMEOUT_MS = 10000L;
	/** The custom view a deferred exit still owes a {@code removeCustomView()}, or null when no exit
	 * is deferred. See {@link #exitFullScreenUi}. */
	@Nullable
	private View deferredExitView;
	@Nullable
	private MainActivityDelegate deferredExitActivity;
	private final Runnable finishDeferredExitTask = this::finishDeferredExit;

	public YoutubeChromeClient(FermataWebView web, VideoView videoView) {
		super(web, videoView);
		videoView.setNativeFullscreen(new VideoView.NativeFullscreen() {
			@Override
			public boolean isNativeFullscreen() {
				return isFullScreen();
			}

			@Override
			public void setNativeFullscreen(boolean fullscreen) {
				if (fullscreen) enterFullScreen();
				else exitFullScreen();
			}
		});
	}

	@Override
	public VideoView getFullScreenView() {
		return (VideoView) super.getFullScreenView();
	}

	protected void addCustomView(View view) {
		VideoView vv = getFullScreenView();
		((ViewGroup) vv.getChildAt(0)).addView(view, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
	}

	protected void removeCustomView(View view) {
		VideoView vv = getFullScreenView();
		((ViewGroup) vv.getChildAt(0)).removeView(view);
	}

	protected void setFullScreen(MainActivityDelegate a, boolean fullScreen) {
		a.setVideoMode(fullScreen, getFullScreenView());
	}

	/**
	 * Leaving fullscreen while a deliberate video switch is covering the screen (see {@code
	 * YoutubeVideoView#showTransitionOverlay}) is not something the user should see happen: the page
	 * drops out of fullscreen on its own whenever the player is rebuilt -- which a real navigation to
	 * the next video does -- and the default exit would fade the watch page back in underneath the
	 * cover and then fade it straight back out again when the new video re-enters. That is the
	 * "brief page layout visible between videos" flash. So while the cover is up, don't perform the
	 * exit at all: hold the cover exactly where it is and hand it to whatever comes next.
	 * <p>
	 * Nothing about the fullscreen state itself is deferred -- {@link #isFullScreen()} goes false
	 * immediately, as it always did -- only the visual swap. {@link #isFullScreenExitDeferred()} is
	 * how the rest of the addon tells this in-between state apart from being plainly out of
	 * fullscreen.
	 */
	@Override
	protected void exitFullScreenUi(MainActivityDelegate a, View removed) {
		VideoView v = getFullScreenView();
		if ((v instanceof YoutubeVideoView yv) && yv.isTransitionCoverShowing()) {
			// A previous deferral being superseded still owes its own view a detach -- do it now,
			// under the cover, rather than leaving it stacked behind the next one.
			if (deferredExitView != null) removeCustomView(deferredExitView);
			deferredExitView = removed;
			deferredExitActivity = a;
			yv.setTransitionCoverHiddenListener(
					() -> v.postDelayed(finishDeferredExitTask, DEFERRED_EXIT_GRACE_MS));
			v.removeCallbacks(finishDeferredExitTask);
			v.postDelayed(finishDeferredExitTask, DEFERRED_EXIT_TIMEOUT_MS);
			return;
		}
		super.exitFullScreenUi(a, removed);
	}

	/**
	 * The other half of {@link #exitFullScreenUi}'s deferral: the new video is going fullscreen and
	 * the cover is still the thing on screen, so the container it is drawn on is already visible at
	 * full opacity. Re-running the normal crossfade from here would set the page visible again on its
	 * way out -- revealing, for a frame or two, exactly what the cover is there to hide -- so just
	 * swap the custom view underneath it instead.
	 */
	@Override
	protected void enterFullScreenUi(MainActivityDelegate a, View view) {
		View stale = deferredExitView;
		if (stale == null) {
			super.enterFullScreenUi(a, view);
			return;
		}
		clearDeferredExit();
		removeCustomView(stale);
		addCustomView(view);
		ViewGroup fs = getFullScreenView();
		fs.animate().cancel();
		fs.setAlpha(1f);
		fs.setVisibility(View.VISIBLE);
		FermataWebView web = getWebView();
		web.animate().cancel();
		web.setVisibility(View.GONE);
		// videoMode never went false (the exit's own setFullScreen() was deferred along with it), so
		// this is a no-op beyond re-claiming the active video view -- which is the point: no control
		// panel / nav bar flicker across the switch either.
		setFullScreen(a, true);
	}

	/** True between a fullscreen exit being deferred behind the transition cover and it either
	 * completing or being superseded by a re-entry. See {@link #exitFullScreenUi}. */
	public boolean isFullScreenExitDeferred() {
		return deferredExitView != null;
	}

	private void finishDeferredExit() {
		View removed = deferredExitView;
		MainActivityDelegate a = deferredExitActivity;
		clearDeferredExit();
		// Re-entry won the race after all (or something else already put us back in fullscreen) --
		// the exit this was holding is simply moot now.
		if ((removed == null) || (a == null) || isFullScreen()) return;
		super.exitFullScreenUi(a, removed);
	}

	private void clearDeferredExit() {
		deferredExitView = null;
		deferredExitActivity = null;
		VideoView v = getFullScreenView();
		v.removeCallbacks(finishDeferredExitTask);
		if (v instanceof YoutubeVideoView yv) yv.setTransitionCoverHiddenListener(null);
	}

	@Override
	public boolean canEnterFullScreen() {
		MainActivityDelegate a = MainActivityDelegate.get(getWebView().getContext());
		MediaSessionCallback cb = a.getMediaSessionCallback();
		if (!((cb.getEngine() instanceof YoutubeMediaEngine))) return false;
		// Playing as music (the Music tab): no fullscreen video.
		if (MusicPlayer.isYoutubeAudioMode()) return false;
		int st = cb.getPlaybackState().getState();
		return (st == STATE_PLAYING) || (st == STATE_PAUSED);
	}

	protected boolean onTouchEvent(View v, MotionEvent event) {
		return isFullScreen() && getFullScreenView().onTouchEvent(event);
	}
}
