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
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Guideline;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.engine.SubtitleStreamInfo;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import android.support.v4.media.session.PlaybackStateCompat;

import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.util.DiagnosticLog;
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
		// The Music tab has no video mode: the fullscreen pane over it is a blank page with the video's
		// control panel on it. Whatever asks for it, the tab stays as it is.
		if ((mode == Mode.VIDEO) && isMusicTabActive()) {
			DiagnosticLog.log("BODY", "video mode refused over the Music tab");
			mode = Mode.FRAME;
		}
		Mode oldMode = this.mode;
		if (oldMode != mode) {
			MainActivityDelegate act = MainActivityDelegate.getActivityDelegate(getContext()).peek();
			ActivityFragment shown = (act == null) ? null : act.getActiveFragment();
			DiagnosticLog.log("BODY", "mode " + oldMode + " -> " + mode,
					"fragment=" + ((shown == null) ? null : shown.getClass().getSimpleName()));
		}
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
				// Whatever the surface's own events did or did not do, the picture is handed to the
				// player once the screen has settled.
				postDelayed(() -> {
					if (isVideoMode()) a.getMediaSessionCallback().reattachVideoView();
				}, 500);
				// Open problem: fullscreen sometimes black after a downloaded video played as music.
				// Says whether the pane itself is up and has a screen, and what the player has.
				postDelayed(() -> {
					if (!isVideoMode()) return;
					MediaEngine eng = a.getMediaSessionCallback().getEngine();
					android.view.SurfaceView sv = vv.getVideoSurface();
					DiagnosticLog.log("BODY", "video pane 3 s in", "visible=" + (vv.getVisibility() == VISIBLE),
							"alpha=" + vv.getAlpha(), "size=" + vv.getWidth() + "x" + vv.getHeight(),
							"surface=" + sv.getWidth() + "x" + sv.getHeight() + "/" +
									(sv.getVisibility() == VISIBLE) + "/" + sv.getHolder().getSurface().isValid(),
							"registered=" + (a.getMediaSessionCallback().getVideoView() == vv),
							"engine=" + ((eng == null) ? null : eng.getClass().getSimpleName()),
							"item=" + ((eng == null) ? null : eng.getSource()),
							"isVideo=" + ((eng != null) && (eng.getSource() != null) && eng.getSource().isVideo()));
					// What is on top of the middle of the picture: the video itself, or something over it.
					int[] at = new int[2];
					vv.getLocationOnScreen(at);
					View root = getRootView();
					DiagnosticLog.log("BODY", "over the video", "cover=" + a.describeWindowCover(),
							"top=" + topViewAt(root, at[0] + vv.getWidth() / 2, at[1] + vv.getHeight() / 2));
				}, 3000);
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

	private boolean isMusicTabActive() {
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
		ActivityFragment f = (a == null) ? null : a.getActiveFragment();
		return (f != null) && (f.getFragmentId() == R.id.music_addon);
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
		// The video pane is not faded with its own alpha: its picture is a SurfaceView, whose surface
		// does not reliably follow its parent's alpha. Faded in from 0, an already existing surface
		// could keep the alpha of the fade's start: the pane at 1, the picture invisible, only the
		// pane's black background showing (sound playing, controls fine). It is up at once and its
		// picture fades in from black instead (VideoView#fadeInFromBlack), the list fading out.
		boolean video = (m == Mode.VIDEO);
		// A pane that is still on its way out comes back from where it is, not from nothing.
		if ((in.getVisibility() != VISIBLE) && !video) in.setAlpha(0f);
		if (in instanceof VideoView v) v.setSurfacesShown(true);
		in.setVisibility(VISIBLE);
		paneFadeEnd = animate ? (SystemClock.uptimeMillis() + FADE_MS) : 0L;

		if (video) {
			in.setAlpha(1f);
			if (animate && !inShown && (in instanceof VideoView vv)) vv.fadeInFromBlack(FADE_MS);
		} else if (animate && !inShown) {
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
		// See VideoView#setSurfacesShown: the picture's surfaces go with the pane.
		if (pane instanceof VideoView v) v.setSurfacesShown(false);
		pane.setVisibility(GONE);
		pane.setAlpha(1f);
	}

	public VideoView getVideoView() {
		return findViewById(R.id.video_view);
	}

	/**
	 * For the diagnostic log: the chain of views drawn last (topmost) at screen point x,y, with any
	 * that paint a background of their own marked, so whatever hides the picture can be named.
	 */
	private static String topViewAt(View v, int x, int y) {
		StringBuilder sb = new StringBuilder();
		android.graphics.Rect r = new android.graphics.Rect();
		for (int depth = 0; (v != null) && (depth < 40); depth++) {
			if (sb.length() != 0) sb.append(" > ");
			sb.append(v.getClass().getSimpleName());
			if (v.getId() != NO_ID) {
				try {
					sb.append('#').append(v.getResources().getResourceEntryName(v.getId()));
				} catch (Exception ignore) {
				}
			}
			if (v.getBackground() != null) sb.append("[bg]");
			if (v.getAlpha() < 1f) sb.append("[a=").append(v.getAlpha()).append(']');
			if (!(v instanceof ViewGroup g)) break;
			View next = null;
			// The child drawn last that is showing at the point (elevation aside).
			for (int i = g.getChildCount() - 1; i >= 0; i--) {
				View c = g.getChildAt(i);
				if ((c.getVisibility() != VISIBLE) || (c.getAlpha() == 0f)) continue;
				if (c.getGlobalVisibleRect(r) && r.contains(x, y)) {
					next = c;
					break;
				}
			}
			v = next;
		}
		return sb.toString();
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
		if (!isVideoMode()) {
			// Through black into the picture, as YouTube's video comes.
			a.fadeToBlackForLocalVideo();
			setMode(Mode.VIDEO);
		}
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

		// A black that covered the switch from one file to the next (never YouTube's: its own video
		// lifts that).
		if ((eng != null) && (newItem != null) && (eng.getId() != MediaPrefs.MEDIA_ENG_YT)) {
			a.liftLocalVideoCover(false);
		}

		if ((eng != null) && (newItem != null) && !newItem.isVideo() && (getMode() == Mode.FRAME)) {
			eng.selectSubtitleStream();
		}
	}

	@Override
	public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
		MainActivityDelegate a = getActivity();
		// The black over the start of a downloaded video goes once it plays.
		if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
			a.liftLocalVideoCover(true);
			// Playing: the loading circle of the start (shown over whatever tab is up) has done its job.
			startingPlayback.cancel();
		}
		// Over a list the fullscreen button comes and goes with whether a video plays.
		if (!isVideoMode()) a.updateExtraFabsVisibility();
	}

	@Override
	public void onSubtitleStreamChanged(MediaSessionCallback cb, @Nullable SubtitleStreamInfo info) {
		if (getMode() != Mode.FRAME) return;
		var i = cb.getCurrentItem();
		if ((i == null) || i.isVideo()) return;
		var a = getActivity();
		var f = a.getActiveFragment();
		// A Music tab track (a downloaded video played as music, say) that has a subtitle stream
		// is still shown by the Music tab: its own screen, never the Subtitles tab, which with
		// nothing to show is the blank page with the control panel in front.
		if ((info != null) && ((i instanceof MusicTrackItem) || isMusicTabActive())) {
			DiagnosticLog.log("BODY", "subtitle stream on a Music tab track: Subtitles tab not shown");
			return;
		}
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
		// Nothing plays any more (an error, the end of the list, a stop): the fullscreen video would
		// only be a black screen with the control panel on it.
		a.liftLocalVideoCover(true);
		if (isVideoMode()) setMode(Mode.FRAME);
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
