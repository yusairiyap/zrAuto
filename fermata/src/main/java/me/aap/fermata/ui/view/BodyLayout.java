package me.aap.fermata.ui.view;

import static me.aap.fermata.R.id.subtitles_fragment;
import static me.aap.fermata.ui.activity.MainActivityPrefs.L_SPLIT_PERCENT;
import static me.aap.fermata.ui.activity.MainActivityPrefs.P_SPLIT_PERCENT;
import static me.aap.utils.async.Completed.completedVoid;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
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
import me.aap.fermata.media.pref.MediaPrefs;
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
	private static final long FADE_MS = 300L;
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

	private final Paint topFadePaint = new Paint();
	private final float topFadeLen = UiUtils.toPx(getContext(), 20);
	private int topFadeBg = Color.BLACK;
	private boolean topFadeBgResolved;
	// What the fade was last drawn for: the tool bar's bottom edge and how much of it is showing.
	private float topFadeEnd = -1f;
	private float topFadeAlpha;
	// computeTopFade()'s results.
	private float fadeEnd;
	private float fadeAlpha;

	/**
	 * Redraws the top fade whenever the tool bar moves or fades (its show/hide animation), since
	 * nothing else invalidates this layout then. Checked before every frame, cheaply.
	 */
	private final ViewTreeObserver.OnPreDrawListener topFadeSync = () -> {
		syncRefreshOffset();
		float end = -1f;
		float alpha = 0f;
		if (computeTopFade()) {
			end = fadeEnd;
			alpha = fadeAlpha;
		}
		if ((end != topFadeEnd) || (alpha != topFadeAlpha)) {
			topFadeEnd = end;
			topFadeAlpha = alpha;
			invalidate();
		}
		return true;
	};

	private final int[] refreshLoc1 = new int[2];
	private final int[] refreshLoc2 = new int[2];
	private int refreshOffsetFor = Integer.MIN_VALUE;

	/**
	 * The pull-to-refresh spinner comes down from under the floating tool bar pill, not from the
	 * top of the screen behind it. Only re-set once the tool bar is settled (not mid show/hide).
	 */
	private void syncRefreshOffset() {
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
		SwipeRefreshLayout srl = getSwipeRefresh();
		if ((a == null) || (srl == null) || srl.isRefreshing()) return;
		View tb = a.getToolBar();
		if ((tb == null) || (tb.getVisibility() != VISIBLE) || (tb.getHeight() == 0) ||
				(tb.getAlpha() < 1f) || (tb.getTranslationY() != 0f) || !srl.isAttachedToWindow()) {
			return;
		}
		srl.getLocationOnScreen(refreshLoc1);
		tb.getLocationOnScreen(refreshLoc2);
		int bottom = refreshLoc2[1] + tb.getHeight() - refreshLoc1[1];
		if (bottom == refreshOffsetFor) return;
		refreshOffsetFor = bottom;
		int circle = srl.getProgressCircleDiameter();
		srl.setProgressViewOffset(false, bottom - circle,
				bottom + Math.round(UiUtils.toPx(getContext(), 24)));
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		getViewTreeObserver().addOnPreDrawListener(topFadeSync);
	}

	@Override
	protected void onDetachedFromWindow() {
		getViewTreeObserver().removeOnPreDrawListener(topFadeSync);
		super.onDetachedFromWindow();
	}

	@Override
	protected void dispatchDraw(@NonNull Canvas canvas) {
		super.dispatchDraw(canvas);
		drawTopFade(canvas);
	}

	/**
	 * The One UI style top: content scrolling up under the floating tool bar pill fades into the
	 * background toward the top edge, down to a little past the pill, instead of showing at full
	 * strength in the gaps around it. Drawn over this layout's children but under the tool bar
	 * (which is above this layout), so the pill itself stays untouched. The mirror of the fade at
	 * the bottom, see FloatingBarsView#drawFade.
	 * <p>
	 * Never over video (fullscreen, or sharing the screen with a tab), a tab with its own
	 * background under the bars (see MainActivityFragment#drawsTopFade), or a web page: the
	 * YouTube tab and the browser start right below the pill (see
	 * MainActivityDelegate#insetWebViewTop), so a fade reaching past it would tint the page's own
	 * top bar. Follows the tool bar's own alpha, so it goes and comes back with the bars.
	 */
	private void drawTopFade(Canvas canvas) {
		if ((topFadeEnd <= 0f) || (topFadeAlpha <= 0f)) return;
		if (!topFadeBgResolved) {
			TypedArray ta = getContext().obtainStyledAttributes(
					new int[]{android.R.attr.colorBackground});
			topFadeBg = ta.getColor(0, Color.BLACK);
			ta.recycle();
			topFadeBgResolved = true;
		}
		int rgb = topFadeBg & 0x00FFFFFF;
		float a = topFadeAlpha;
		int[] colors = {rgb | (Math.round(0xEB * a) << 24), rgb | (Math.round(0xA6 * a) << 24),
				rgb};
		float end = topFadeEnd;
		float[] stops = {0f, Math.max(0f, Math.min(1f, (end - topFadeLen) / end)), 1f};
		topFadePaint.setShader(new LinearGradient(0, 0, 0, end, colors, stops,
				Shader.TileMode.CLAMP));
		canvas.drawRect(0, 0, getWidth(), end, topFadePaint);
	}

	/** Into fadeEnd/fadeAlpha: where the fade ends in this view, and how strong; false for none. */
	private boolean computeTopFade() {
		if (getMode() != Mode.FRAME) return false;
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
		if ((a == null) || a.isVideoMode() || a.isTopInsetWebViewShown()) return false;
		if (!(a.getActiveFragment() instanceof MainActivityFragment f) || !f.drawsTopFade()) {
			return false;
		}
		View tb = a.getToolBar();
		if ((tb == null) || (tb.getVisibility() != VISIBLE) || (tb.getHeight() == 0)) return false;
		float alpha = tb.getAlpha();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) alpha *= tb.getTransitionAlpha();
		if (alpha <= 0f) return false;
		// Both are children of the same parent (main_activity).
		float end = (tb.getY() + tb.getHeight()) - getTop() + topFadeLen;
		if (end <= 0f) return false;
		fadeEnd = end;
		fadeAlpha = Math.min(1f, alpha);
		return true;
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

	public void setMode(Mode mode) {
		Mode oldMode = this.mode;
		this.mode = mode;
		Guideline gl = getGuideline();
		ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) gl.getLayoutParams();
		MainActivityDelegate a = getActivity();
		VideoView vv = getVideoView();
		boolean animate = (oldMode != mode) && isAttachedToWindow() && (getWidth() > 0);

		getSplitLine().setVisibility(GONE);
		getSplitHandle().setVisibility(GONE);

		switch (mode) {
			case FRAME -> {
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
				lp.guidePercent = isPortrait() ? 1f : 0f;
				vv.showVideo();
				a.setVideoMode(true, vv);
				App.get().getHandler().post(vv::requestFocus);
			}
		}

		gl.setLayoutParams(lp);

		// Only one of the video pane and the list shows: the other fades out as it fades in. Re-entered
		// with the mode it is already in (a tab change, a rotation), the panes are only put right --
		// never while the fade is still on its way, which that would cut short.
		if (animate) {
			showPane(mode, true);
		} else if (SystemClock.uptimeMillis() >= paneFadeEnd) {
			showPane(mode, false);
		}
	}

	// Identifies the latest fade of the panes, so that what an earlier one still has to do is dropped.
	private int paneFadeGen;
	// When that fade is over.
	private long paneFadeEnd;

	/**
	 * Shows the pane of {@code m} (the video, or the list) and hides the other one, fading when
	 * {@code animate}. Whatever the panes were left in by an earlier, interrupted fade -- hidden,
	 * half transparent -- is dealt with first: cancelling a view animation runs its end action, which
	 * for the fade-out of the other pane means hiding it, so that has to happen before this shows
	 * anything (hiding a pane that is meant to be showing left the screen blank, or a transparent
	 * black video pane over the list).
	 */
	private void showPane(Mode m, boolean animate) {
		View in = (m == Mode.VIDEO) ? getVideoView() : getSwipeRefresh();
		View out = (m == Mode.VIDEO) ? getSwipeRefresh() : getVideoView();
		int gen = ++paneFadeGen;
		in.animate().cancel();
		out.animate().cancel();

		boolean inShown = (in.getVisibility() == VISIBLE) && (in.getAlpha() >= 1f);
		// A pane that is still on its way out comes back from where it is, not from nothing.
		if (in.getVisibility() != VISIBLE) in.setAlpha(0f);
		in.setVisibility(VISIBLE);
		paneFadeEnd = animate ? (SystemClock.uptimeMillis() + FADE_MS) : 0L;

		if (animate && !inShown) {
			in.animate().alpha(1f).setDuration(FADE_MS).start();
		} else {
			in.setAlpha(1f);
		}

		if (animate && (out.getVisibility() == VISIBLE)) {
			out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> hidePane(out, gen)).start();
			// Should the end action be lost to a cancelled animation, the pane is still taken away.
			out.postDelayed(() -> hidePane(out, gen), FADE_MS + 100);
		} else {
			hidePane(out, gen);
		}
	}

	private void hidePane(View pane, int gen) {
		if (gen != paneFadeGen) return;
		pane.setVisibility(GONE);
		pane.setAlpha(1f);
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
			// Fullscreen video carries on over a tab that plays video itself (the one it was started
			// from, Downloads); any other tab is in front of a video that plays on behind it.
			if (isVideoMode() && (a.getActiveFragment() instanceof MainActivityFragment f) &&
					f.isVideoModeSupported()) {
				return;
			}
			setMode(Mode.FRAME);
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

	/**
	 * Plays a downloaded YouTube video (an external item, whose own player is the YouTube tab's
	 * page) from its file, fullscreen, from wherever it was started -- the way a local video
	 * plays: the picture's surface first, then the engine. Leaving fullscreen returns to the
	 * screen it was started from.
	 */
	public void playLocalVideo(MediaLib.PlayableItem i) {
		startingPlayback.cancel();
		MainActivityDelegate a = getActivity();
		// Fullscreen first, and once: the engine is given the picture's surface only if it is there
		// when the engine is created, and changing the mode again and again while it is still
		// moving leaves the two panes half-way (the info overlay stretched, no picture).
		if (!isVideoMode()) setMode(Mode.VIDEO);
		Runnable play = () -> a.getMediaServiceBinder().playItem(i);
		if (!getVideoView().isSurfaceCreated() && !a.getMediaSessionCallback().hasCustomEngineProvider()) {
			getVideoView().onSurfaceCreated(play);
		} else {
			play.run();
		}
	}

	@Override
	public void onPlayableChanged(MediaLib.PlayableItem oldItem, MediaLib.PlayableItem newItem) {
		startingPlayback.cancel();
		MainActivityDelegate a = getActivity();
		if (!(a.getActiveFragment() instanceof MainActivityFragment f)) return;
		if (f instanceof SubtitlesFragment) a.goToCurrent();
		MediaEngine eng = a.getMediaServiceBinder().getCurrentEngine();

		if ((newItem == null) || !newItem.isVideo() || (eng == null) ||
				(eng.getId() == MediaPrefs.MEDIA_ENG_YT)) {
			// Whatever tab is showing: a track without a picture must never find the fullscreen video
			// pane still up (black, over the Music tab) from the video before it.
			setMode(Mode.FRAME);
		} else if (!f.isVideoModeSupported()) {
			// The video plays on behind a tab that does not show it.
			return;
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
		// The black that covered the way to a video that is not coming.
		getActivity().liftVideoSwitchFade();
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
		FRAME, VIDEO
	}
}
