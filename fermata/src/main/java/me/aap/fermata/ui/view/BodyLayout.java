package me.aap.fermata.ui.view;

import static me.aap.fermata.R.id.subtitles_fragment;
import static me.aap.fermata.ui.activity.MainActivityPrefs.L_SPLIT_PERCENT;
import static me.aap.fermata.ui.activity.MainActivityPrefs.P_SPLIT_PERCENT;
import static me.aap.utils.async.Completed.completedVoid;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Guideline;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import me.aap.fermata.R;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.engine.SubtitleStreamInfo;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.fermata.ui.fragment.SubtitlesFragment;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * @author Andrey Pavlenko
 */
public class BodyLayout extends SplitLayout
		implements SwipeRefreshLayout.OnRefreshListener, SwipeRefreshLayout.OnChildScrollUpCallback,
		MainActivityListener, FermataServiceUiBinder.Listener, MediaSessionCallback.Listener {
	private Mode mode;
	private FutureSupplier<?> startingPlayback = completedVoid();

	public BodyLayout(@NonNull Context ctx, @Nullable AttributeSet attrs) {
		super(ctx, attrs);

		SwipeRefreshLayout srl = getSwipeRefresh();
		srl.setId(R.id.swiperefresh);
		srl.setOnRefreshListener(this);
		srl.setOnChildScrollUpCallback(this);
		setMode(Mode.FRAME);

		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(a -> {
			FermataServiceUiBinder b = a.getMediaServiceBinder();
			b.addBroadcastListener(this);
			a.addBroadcastListener(this, FRAGMENT_CHANGED | ACTIVITY_DESTROY);
			b.getMediaSessionCallback().addBroadcastListener(this);
			onPlayableChanged(null, b.getCurrentItem());
		});
	}

	@Override
	protected int getLayout(boolean portrait) {
		return portrait ? R.layout.body_layout : R.layout.body_layout_land;
	}

	@Override
	protected Pref<DoubleSupplier> getSplitPercentPref(boolean portrait) {
		return portrait ? P_SPLIT_PERCENT : L_SPLIT_PERCENT;
	}

	public Mode getMode() {
		return mode;
	}

	public boolean isFrameMode() {
		return getMode() == Mode.FRAME;
	}

	public boolean isVideoMode() {
		return getMode() == Mode.VIDEO;
	}

	public boolean isBothMode() {
		return getMode() == Mode.BOTH;
	}

	public void setMode(Mode mode) {
		Mode oldMode = this.mode;
		this.mode = mode;
		Guideline gl = getGuideline();
		ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) gl.getLayoutParams();
		MainActivityDelegate a = getActivity();
		VideoView vv = getVideoView();
		View sr = getSwipeRefresh();
		// Captured before the switch below touches visibility/bounds, so the animation after it can
		// FLIP from exactly what was on screen a moment ago. Left null (skipping the animation) for
		// a view that's about to newly appear or disappear -- UiUtils.flipAnimate no-ops on a 0-size
		// end, and there's nothing meaningful to animate from/to there anyway.
		boolean animate = isAttachedToWindow() && (getWidth() > 0);
		int[] vvBounds = animate ? UiUtils.captureBounds(vv) : null;
		int[] srBounds = animate ? UiUtils.captureBounds(sr) : null;

		switch (mode) {
			case FRAME -> {
				getSplitLine().setVisibility(GONE);
				getSplitHandle().setVisibility(GONE);
				lp.guidePercent = isPortrait() ? 0f : 1f;
				// Only push this to the delegate when BodyLayout's own video mode is actually
				// changing -- e.g. FRAGMENT_CHANGED re-enters this with FRAME on every tab switch
				// where the active fragment isn't a MediaLibFragment (including the YouTube tab
				// itself), and BodyLayout was typically already in FRAME mode the whole time since
				// a WebView-hosted player never uses this generic VideoView. Calling
				// setVideoMode(false, vv) unconditionally there would clobber
				// getActiveVideoView() back to this unused generic VideoView, stomping on
				// whichever real VideoView (e.g. YouTube's) was legitimately active.
				if (oldMode != Mode.FRAME) a.setVideoMode(false, vv);
			}
			case VIDEO -> {
				getSplitLine().setVisibility(GONE);
				getSplitHandle().setVisibility(GONE);
				lp.guidePercent = isPortrait() ? 1f : 0f;
				vv.showVideo();
				a.setVideoMode(true, vv);
				App.get().getHandler().post(vv::requestFocus);
			}
			case BOTH -> {
				vv.setVisibility(VISIBLE);
				getSplitLine().setVisibility(VISIBLE);
				getSplitHandle().setVisibility(VISIBLE);
				getSwipeRefresh().setVisibility(VISIBLE);
				lp.guidePercent = a.getPrefs().getFloatPref(getSplitPercentPref(isPortrait()));
				vv.showVideo();
				a.setVideoMode(true, vv);
				MediaItemListView.focusActive(getContext(), vv);
			}
		}

		gl.setLayoutParams(lp);

		// FRAME and VIDEO each hide one of vv/sr entirely while showing the other -- animated as a
		// crossfade rather than an instant visibility swap, e.g. entering/leaving fullscreen video
		// playback. Both ending up visible (BOTH) needs no such swap, so is left to the plain
		// setVisibility(VISIBLE) calls above.
		// Only when the mode is actually changing: setMode() is routinely re-entered with the *same*
		// mode it's already in (see the FRAGMENT_CHANGED comment above -- exitVideoMode() alone can
		// trigger this right on top of an already-running transition), and re-running the crossfade
		// on every one of those redundant calls would restart it mid-fade each time via
		// animate().cancel(), which can leave a view stuck at a partial alpha if that keeps
		// happening faster than 300ms apart -- observed as the screen going blank until something
		// else (e.g. pressing back) happens to reset it.
		if (oldMode != mode) {
			if (mode == Mode.FRAME) {
				if (animate) crossfade(vv, sr, 300L);
				else {
					vv.setVisibility(GONE);
					// A prior crossfade a caller interrupted (e.g. a second setMode() call arriving
					// before the 300ms fade finished) can leave sr's alpha short of 1 -- animate().cancel()
					// stops mid-fade without snapping the value to its target, so it's reset explicitly
					// here rather than relying on it already being 1.
					sr.animate().cancel();
					sr.setAlpha(1f);
					sr.setVisibility(VISIBLE);
				}
			} else if (mode == Mode.VIDEO) {
				if (animate) crossfade(sr, vv, 300L);
				else {
					sr.setVisibility(GONE);
					vv.animate().cancel();
					vv.setAlpha(1f);
					vv.setVisibility(VISIBLE);
				}
			}
		}

		// Animates the video pane/list growing or shrinking against the guideline's new split
		// instead of snapping there instantly -- a no-op (by design, see UiUtils.flipAnimate) for
		// the FRAME/VIDEO collapse-to/grow-from-zero above, which the crossfade already covers;
		// meaningful for BOTH's split-percent changes.
		if (animate) {
			UiUtils.flipAnimate(vv, vvBounds, 300L);
			UiUtils.flipAnimate(sr, srBounds, 300L);
		}
		a.fireBroadcastEvent(MODE_CHANGED);
	}

	/**
	 * Fades {@code incoming} in while fading {@code outgoing} out, only actually hiding
	 * {@code outgoing} once its fade completes -- the same idiom as
	 * {@code ActivityDelegate.crossfadeFragmentViews}, used here for vv/sr instead of fragments.
	 */
	private static void crossfade(@Nullable View outgoing, @Nullable View incoming, long duration) {
		if (incoming != null) {
			incoming.setVisibility(VISIBLE);
			incoming.animate().cancel();
			incoming.setAlpha(0f);
			incoming.animate().alpha(1f).setDuration(duration).start();
		}
		if ((outgoing != null) && (outgoing != incoming)) {
			outgoing.animate().cancel();
			outgoing.setAlpha(1f);
			outgoing.setVisibility(VISIBLE);
			outgoing.animate().alpha(0f).setDuration(duration)
					.withEndAction(() -> outgoing.setVisibility(GONE)).start();
		}
	}

	public VideoView getVideoView() {
		return findViewById(R.id.video_view);
	}

	private SwipeRefreshLayout getSwipeRefresh() {
		return findViewById(R.id.swiperefresh);
	}

	@Override
	protected void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		setMode(getMode());
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (handleActivityDestroyEvent(a, e)) {
			FermataServiceUiBinder b = a.getMediaServiceBinder();
			b.removeBroadcastListener(this);
			b.getMediaSessionCallback().removeBroadcastListener(this);
		} else if (e == FRAGMENT_CHANGED) {
			if (a.getActiveMediaLibFragment() == null) {
				setMode(Mode.FRAME);
			} else {
				MediaSessionCallback cb = a.getMediaSessionCallback();
				MediaEngine eng = cb.getEngine();

				if (eng == null) {
					setMode(Mode.FRAME);
					return;
				}

				MediaLib.PlayableItem i = eng.getSource();

				if ((i != null) && i.isVideo() && eng.isSplitModeSupported() &&
						(cb.getVideoView() == getVideoView())) {
					setMode(Mode.BOTH);
				} else {
					setMode(Mode.FRAME);
				}
			}
		}
	}

	public void playItem(MediaLib.PlayableItem i) {
		startingPlayback.cancel();
		MainActivityDelegate a = getActivity();

		if (i.isVideo() && !i.isExternal() && !getVideoView().isSurfaceCreated() &&
				!a.getMediaSessionCallback().hasCustomEngineProvider()) {
			setMode(BodyLayout.Mode.VIDEO);
			getVideoView().onSurfaceCreated(() -> playItem(i));
			return;
		}

		FermataServiceUiBinder b = a.getMediaServiceBinder();
		MediaLib.PlayableItem cur = b.getCurrentItem();
		startingPlayback = new Promise<Void>().thenRun(() -> startingPlayback = completedVoid());
		a.setContentLoading(startingPlayback);
		b.playItem(i);
		MediaEngine eng = b.getCurrentEngine();
		if (i.equals(cur) && (eng != null) && eng.isVideoModeRequired())
			setMode(BodyLayout.Mode.VIDEO);
	}

	@Override
	public void onPlayableChanged(MediaLib.PlayableItem oldItem, MediaLib.PlayableItem newItem) {
		startingPlayback.cancel();
		MainActivityDelegate a = getActivity();
		if (!(a.getActiveFragment() instanceof MainActivityFragment f)) return;
		if (f instanceof SubtitlesFragment) a.goToCurrent();
		else if (!f.isVideoModeSupported()) return;
		MediaEngine eng = a.getMediaServiceBinder().getCurrentEngine();

		if ((newItem == null) || !newItem.isVideo() || (eng == null) || !eng.isSplitModeSupported()) {
			setMode(Mode.FRAME);
		} else {
			if (!eng.isVideoModeRequired()) setMode(Mode.FRAME);
			else if (isFrameMode()) setMode(Mode.VIDEO);
			else getVideoView().showVideo();
		}

		if ((eng != null) && (newItem != null) && !newItem.isVideo() && (getMode() == Mode.FRAME)) {
			eng.selectSubtitleStream();
		}
	}

	@Override
	public void onSubtitleStreamChanged(MediaSessionCallback cb, @Nullable SubtitleStreamInfo info) {
		if (getMode() != Mode.FRAME) return;
		var i = cb.getCurrentItem();
		if ((i == null) || i.isVideo()) return;
		var a = getActivity();
		var f = a.getActiveFragment();
		if (info == null) {
			if (f instanceof SubtitlesFragment) a.goToCurrent();
		} else if (f instanceof SubtitlesFragment) {
			((SubtitlesFragment) f).restart();
		} else {
			a.showFragment(subtitles_fragment);
		}
	}

	@Override
	public void onPlaybackError(String message) {
		onPlaybackStopped();
		Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
	}

	@Override
	public void onPlaybackStopped() {
		startingPlayback.cancel();
		var a = getActivity();
		if (a.getActiveFragment() instanceof SubtitlesFragment) a.goToCurrent();
	}

	@Override
	public void onRefresh() {
		ActivityFragment f = getActivity().getActiveFragment();
		if (f != null) f.onRefresh(getSwipeRefresh()::setRefreshing);
	}

	@Override
	public boolean canChildScrollUp(@NonNull SwipeRefreshLayout parent, @Nullable View child) {
		MainActivityDelegate a = getActivity();
		if (a.isMenuActive()) return true;
		ActivityFragment f = a.getActiveFragment();
		return (f != null) && f.canScrollUp();
	}

	public enum Mode {
		FRAME, VIDEO, BOTH
	}
}
