package me.aap.fermata.ui.view;

import static android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY;
import static androidx.core.text.HtmlCompat.fromHtml;
import static me.aap.fermata.media.lib.MediaLib.PlayableItem;
import static me.aap.fermata.media.pref.MediaPrefs.SCALE_16_9;
import static me.aap.fermata.media.pref.MediaPrefs.SCALE_4_3;
import static me.aap.fermata.media.pref.MediaPrefs.SCALE_BEST;
import static me.aap.fermata.media.pref.MediaPrefs.SCALE_FILL;
import static me.aap.fermata.media.pref.MediaPrefs.SCALE_ORIGINAL;
import static me.aap.fermata.media.pref.MediaPrefs.SUB_SIZE;
import static me.aap.utils.async.Completed.completedNull;
import static me.aap.utils.ui.UiUtils.isVisible;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.circularreveal.CircularRevealFrameLayout;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.media.sub.SubGrid;
import me.aap.fermata.media.sub.Subtitles;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.function.BiConsumer;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.view.NavBarView;

/**
 * @author Andrey Pavlenko
 */
public class VideoView extends FrameLayout
		implements SurfaceHolder.Callback, View.OnLayoutChangeListener, PreferenceStore.Listener,
		MainActivityListener, BiConsumer<SubGrid.Position, Subtitles.Text> {
	private final Set<PreferenceStore.Pref<?>> prefChange = new HashSet<>(
			Arrays.asList(MediaPrefs.VIDEO_SCALE, MediaPrefs.AUDIO_DELAY, MediaPrefs.AUDIO_DELAY_AA,
					MediaPrefs.SUB_DELAY));
	private static final Set<PreferenceStore.Pref<?>> infoOverlayPrefChange = new HashSet<>(
			Arrays.asList(MainActivityPrefs.CLOCK_POS, MainActivityPrefs.INFO_OVERLAY_SHOW_CLOCK,
					MainActivityPrefs.INFO_OVERLAY_SHOW_CLOCK_ICON,
					MainActivityPrefs.INFO_OVERLAY_SHOW_BATTERY_PCT,
					MainActivityPrefs.INFO_OVERLAY_SHOW_BATTERY_ICON,
					MainActivityPrefs.INFO_OVERLAY_SHOW_BATTERY_TEMP,
					MainActivityPrefs.INFO_OVERLAY_SHOW_TEMP_ICON,
					MainActivityPrefs.INFO_OVERLAY_SHOW_DISTANCE,
					MainActivityPrefs.INFO_OVERLAY_SHOW_DISTANCE_ICON,
					MainActivityPrefs.INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE,
					MainActivityPrefs.INFO_OVERLAY_SIZE));
	private SubDrawer subDrawer;
	private FutureSupplier<?> createSurface = new Promise<>();
	private View dimOverlay;
	@Nullable
	private InfoOverlayView infoOverlay;
	@Nullable
	private NativeFullscreen nativeFullscreen;
	/** Latest known on-screen visibility of the fullscreen video control panel, pushed by
	 * {@code ControlPanelView} -- see {@link #setControlPanelVisible}. Assumed visible until told
	 * otherwise so the overlay isn't wrongly hidden before the first real update arrives. */
	private boolean controlPanelVisible = true;

	public VideoView(Context context) {
		this(context, null);
	}

	public VideoView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context);
		getActivity().onSuccess(a -> {
			a.addBroadcastListener(this);
			a.getLib().getPrefs().addBroadcastListener(this);
			// Each VideoView instance (including a YoutubeVideoView, which YoutubeFragment creates as
			// a separate instance from BodyLayout's own fixed one) manages its own Info Overlay and
			// reacts to preference changes directly, rather than relying on a single centralized
			// update -- otherwise a VideoView instance that isn't "the" one a centralized handler knows
			// about never picks up a change until it's recreated (e.g. on app restart).
			a.getPrefs().addBroadcastListener(this);
			refreshInfoOverlay();
		});
	}

	protected void init(Context context) {
		setBackgroundColor(Color.BLACK);
		addView(new SurfaceView(getContext()) {
			{
				FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
				lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
				setLayoutParams(lp);
				getHolder().addCallback(VideoView.this);
			}
		});
		addView(new SurfaceView(getContext()) {
			{
				FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
				lp.gravity = Gravity.FILL;
				setLayoutParams(lp);
				setZOrderMediaOverlay(true);
				setZOrderOnTop(true);
				getHolder().setFormat(PixelFormat.TRANSLUCENT);
				getHolder().addCallback(VideoView.this);
			}
		});

		addDimOverlay(context);

		addOnLayoutChangeListener(this);
		setLayoutParams(new CircularRevealFrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		setFocusable(true);
	}

	/**
	 * Adds the night-driving dim overlay as the last child and returns it. Subclasses that
	 * override {@link #init(Context)} without calling {@code super.init(context)} (e.g.
	 * {@code YoutubeVideoView}, which builds its own child structure) must call this themselves to
	 * support {@link #setDimOverlay}.
	 */
	protected View addDimOverlay(Context context) {
		dimOverlay = new View(context);
		dimOverlay.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		dimOverlay.setVisibility(GONE);
		dimOverlay.setClickable(false);
		dimOverlay.setFocusable(false);
		addView(dimOverlay);
		return dimOverlay;
	}

	/**
	 * Shows/hides the night-driving dim overlay. {@code opacityPercent} is 0-100,
	 * {@code rgbColor} is an opaque RGB color whose alpha channel is ignored.
	 * <p>
	 * {@code dimOverlay} is {@code null} for subclasses (e.g. {@code YoutubeVideoView}) that
	 * override {@link #init(Context)} without calling {@code super.init(context)} to build their
	 * own child structure -- those act as plain fullscreen containers, not real playback surfaces,
	 * so this is a no-op for them rather than a crash.
	 */
	public void setDimOverlay(boolean show, int opacityPercent, int rgbColor) {
		if (dimOverlay == null) return;
		if (show) {
			int alpha = Math.round(opacityPercent * 255 / 100f);
			dimOverlay.setBackgroundColor((alpha << 24) | (rgbColor & 0x00FFFFFF));
		}
		dimOverlay.setVisibility(show ? VISIBLE : GONE);
	}

	/**
	 * A video source that has its own native fullscreen playback, separate from the app's own
	 * video mode -- e.g. a WebView-hosted YouTube player, whose fullscreen is the browser's custom
	 * view rather than anything this module controls.
	 */
	public interface NativeFullscreen {
		boolean isNativeFullscreen();

		void setNativeFullscreen(boolean fullscreen);
	}

	/**
	 * Lets such a source (wired up by {@code modules/web}) register itself here, so that both
	 * {@link me.aap.fermata.action.Action#FULLSCREEN_TOGGLE} and
	 * {@link me.aap.fermata.ui.activity.MainActivityDelegate#exitVideoMode()} can defer to it
	 * instead of driving the app's own video mode. This base module can't reference the feature
	 * module directly, so the feature module reaches in and sets this -- same inversion as
	 * {@link #addDimOverlay}.
	 */
	public void setNativeFullscreen(@Nullable NativeFullscreen fs) {
		nativeFullscreen = fs;
	}

	/**
	 * {@code true} only for a web-embedded video source (YouTube) that manages its own fullscreen
	 * chrome via {@link #setNativeFullscreen}; {@code false} for local playback (ExoPlayer/VLC/
	 * MediaPlayer), which has no such handler registered.
	 */
	public boolean hasNativeFullscreen() {
		return nativeFullscreen != null;
	}

	/** Returns {@code true} if a registered native fullscreen handled the toggle. */
	public boolean toggleNativeFullscreen() {
		NativeFullscreen fs = nativeFullscreen;
		if (fs == null) return false;
		fs.setNativeFullscreen(!fs.isNativeFullscreen());
		return true;
	}

	/** Returns {@code true} only if a native fullscreen really was active and has now been left. */
	public boolean exitNativeFullscreen() {
		NativeFullscreen fs = nativeFullscreen;
		if ((fs == null) || !fs.isNativeFullscreen()) return false;
		fs.setNativeFullscreen(false);
		return true;
	}

	@Override
	protected void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (subDrawer == null) return;
		var a = getActivity().peek();
		if (a != null) a.post(this::drawSubtitles);
	}

	public SurfaceView getVideoSurface() {
		return (SurfaceView) getChildAt(0);
	}

	@Nullable
	public SurfaceView getSubtitleSurface() {
		return (SurfaceView) getChildAt(1);
	}


	/** Re-reads the Info Overlay prefs and applies them to this view's overlay panel. */
	public void refreshInfoOverlay() {
		getActivity().onSuccess(a -> {
			MainActivityPrefs p = a.getPrefs();
			setInfoOverlay(p.getClockPosPref(), p.getInfoOverlayShowClockPref(),
					p.getInfoOverlayShowClockIconPref(), p.getInfoOverlayShowBatteryPctPref(),
					p.getInfoOverlayShowBatteryIconPref(), p.getInfoOverlayShowBatteryTempPref(),
					p.getInfoOverlayShowTempIconPref(), p.getInfoOverlayShowDistancePref(),
					p.getInfoOverlayShowDistanceIconPref(),
					p.getInfoOverlayOnlyWhenControlPanelVisiblePref(), p.getInfoOverlaySizePref());
		});
	}

	public void setInfoOverlay(int pos, boolean showClock, boolean showClockIcon,
														 boolean showBatteryPct, boolean showBatteryIcon, boolean showBatteryTemp,
														 boolean showTempIcon, boolean showDistance, boolean showDistanceIcon,
														 boolean onlyWhenControlPanelVisible, float size) {
		boolean show = (pos != MainActivityPrefs.CLOCK_POS_NONE) &&
				(showClock || showBatteryPct || showBatteryTemp || showDistance);

		if (!show) {
			if (infoOverlay != null) {
				infoOverlay.setItems(false, false, false, false, false, false, false, false);
			}
			return;
		}

		if (infoOverlay == null) {
			infoOverlay = new InfoOverlayView(getContext());
			infoOverlay.setControlPanelVisible(controlPanelVisible);
			Context ctx = getContext();
			int m = toIntPx(ctx, 10);
			FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
			lp.setMargins(m, m, m, m);
			addView(infoOverlay, lp);
		}

		int gravity = Gravity.TOP;
		switch (pos) {
			case MainActivityPrefs.CLOCK_POS_LEFT -> gravity |= Gravity.START;
			case MainActivityPrefs.CLOCK_POS_RIGHT -> gravity |= Gravity.END;
			case MainActivityPrefs.CLOCK_POS_CENTER -> gravity |= Gravity.CENTER;
		}

		FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) infoOverlay.getLayoutParams();
		lp.gravity = gravity;
		infoOverlay.setLayoutParams(lp);
		infoOverlay.setSize(size);
		infoOverlay.setOnlyWhenControlPanelVisible(onlyWhenControlPanelVisible);
		infoOverlay.setItems(showClock, showClockIcon, showBatteryPct, showBatteryIcon, showBatteryTemp,
				showTempIcon, showDistance, showDistanceIcon);
	}

	/**
	 * Called by {@code ControlPanelView} whenever its own on-screen visibility flips, so the Info
	 * Overlay's "only show while control panel is visible" option can react immediately rather than
	 * waiting for the next unrelated overlay refresh.
	 */
	public void setControlPanelVisible(boolean controlPanelVisible) {
		if (this.controlPanelVisible == controlPanelVisible) return;
		this.controlPanelVisible = controlPanelVisible;
		if (infoOverlay != null) infoOverlay.setControlPanelVisible(controlPanelVisible);
	}

	public void showVideo() {
		createSurface.onSuccess(v -> {
			MainActivityDelegate a = getActivity().peek();
			if (a == null) return;
			MediaSessionCallback cb = a.getMediaSessionCallback();
			MediaEngine eng = cb.getEngine();
			if (eng != null) setSurfaceSize(eng);
		});
	}

	public void prepareSubDrawer(boolean dbl) {
		MainActivityDelegate a = getActivity().peek();
		if (a == null) return;
		var src = a.getMediaSessionCallback().getCurrentItem();
		var ps = (src != null) ? src.getPrefs() : a.getLib().getPrefs();
		var scale = ps.getFloatPref(SUB_SIZE);
		if (dbl) {
			if (subDrawer instanceof DoubleSubDrawer && subDrawer.textScale == scale) return;
			subDrawer = new DoubleSubDrawer(scale);
		} else {
			if (subDrawer instanceof GridDrawer && subDrawer.textScale == scale) return;
			subDrawer = new GridDrawer(scale);
		}
	}

	public void releaseSubDrawer() {
		subDrawer = null;
	}

	@Override
	public void accept(SubGrid.Position position, @Nullable Subtitles.Text text) {
		if (subDrawer == null) return;
		if (!subDrawer.setText(position, text)) return;
		drawSubtitles();
	}

	public void clearVideoSurface() {
		createSurface.onSuccess(v -> {
			SurfaceView sv = getVideoSurface();
			if (sv == null) return;
			var h = sv.getHolder();
			var c = h.lockCanvas();
			try {
				c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
			} catch (Exception err) {
				Log.e(err);
			} finally {
				h.unlockCanvasAndPost(c);
			}

			h.removeCallback(this);
			h.setFormat(PixelFormat.TRANSPARENT);
			h.setFormat(PixelFormat.OPAQUE);
			h.addCallback(this);
		});
	}

	public void clearSubtitleSurface() {
		createSurface.onSuccess(v -> {
			SurfaceView sv = getSubtitleSurface();
			if (sv == null) return;
			var h = sv.getHolder();
			var c = h.lockCanvas();
			try {
				c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
			} catch (Exception err) {
				Log.e(err);
			} finally {
				h.unlockCanvasAndPost(c);
			}
		});
	}

	private void drawSubtitles() {
		createSurface.onSuccess(v -> {
			SurfaceView sv = getSubtitleSurface();
			if (sv == null) return;
			var h = sv.getHolder();
			var c = h.lockCanvas();
			var cs = c.save();
			try {
				subDrawer.clr(c);
				subDrawer.draw(c);
			} catch (Exception err) {
				Log.e(err);
			} finally {
				c.restoreToCount(cs);
				h.unlockCanvasAndPost(c);
			}
		});
	}

	public void setSurfaceSize(MediaEngine eng) {
		if (eng.setSurfaceSize(this)) return;

		PlayableItem item = eng.getSource();
		if (item == null) return;

		SurfaceView surface = getVideoSurface();
		ViewGroup.LayoutParams lp = surface.getLayoutParams();

		float videoWidth = eng.getVideoWidth();
		float videoHeight = eng.getVideoHeight();
		float videoRatio = videoWidth / videoHeight;

		float screenWidth = getWidth();
		float screenHeight = getHeight();

		int width;
		int height;
		int scale = item.getPrefs().getVideoScalePref();

		switch (scale) {
			case SCALE_4_3:
			case SCALE_16_9:
				videoRatio = (scale == SCALE_16_9) ? 16f / 9f : 4f / 3f;
			default:
			case SCALE_BEST:
				float screenRatio = screenWidth / screenHeight;

				if (videoRatio > screenRatio) {
					width = (int) screenWidth;
					height = (int) (screenWidth / videoRatio);
				} else {
					width = (int) (screenHeight * videoRatio);
					height = (int) screenHeight;
				}

				break;
			case SCALE_FILL:
				if (videoWidth > videoHeight) {
					width = (int) screenWidth;
					height = (int) (screenWidth / videoRatio);
				} else {
					width = (int) (screenHeight * videoRatio);
					height = (int) screenHeight;
				}

				break;
			case SCALE_ORIGINAL:
				width = (int) videoWidth;
				height = (int) videoHeight;
				break;
		}

		if ((lp.width != width) || (lp.height != height)) {
			lp.width = width;
			lp.height = height;
			surface.setLayoutParams(lp);
		}
	}

	@Override
	public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
														 int oldTop, int oldRight, int oldBottom) {
		FermataApplication.get().getHandler().post(() -> createSurface.onSuccess(s -> {
			MainActivityDelegate a = getActivity().peek();
			if (a == null) return;
			MediaEngine eng = a.getMediaServiceBinder().getCurrentEngine();
			if (eng == null) return;

			PlayableItem i = eng.getSource();
			if ((i != null) && i.isVideo()) setSurfaceSize(eng);
		}));
	}

	@Override
	public void surfaceCreated(@NonNull SurfaceHolder holder) {
		if (!getVideoSurface().getHolder().getSurface().isValid()) return;
		SurfaceView s = getSubtitleSurface();
		if ((s != null) && !s.getHolder().getSurface().isValid()) return;
		getActivity().onSuccess(
				a -> a.getMediaSessionCallback().addVideoView(this, a.isCarActivityNotMirror() ? 0 : 1));
		if (createSurface instanceof Promise<?> p) {
			createSurface = completedNull();
			p.complete(null);
		}
	}

	@Override
	public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
		createSurface = new Promise<>();
		getActivity().onSuccess(a -> a.getMediaSessionCallback().removeVideoView(this));
	}

	public boolean isSurfaceCreated() {
		return createSurface.isDone();
	}

	public void onSurfaceCreated(Runnable run) {
		createSurface.onSuccess(v -> run.run());
	}

	@Override
	public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(@NonNull MotionEvent e) {
		MainActivityDelegate a = getActivity().peek();
		return (a != null) && a.interceptTouchEvent(e, this::onTouch);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		MainActivityDelegate a;
		FermataServiceUiBinder b;
		ControlPanelView p;

		switch (keyCode) {
			case KEYCODE_ENTER, KEYCODE_DPAD_CENTER -> {
				if ((a = getActivity().peek()) == null) break;
				return a.getControlPanel().onTouch(this);
			}
			case KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT -> {
				if ((a = getActivity().peek()) == null) break;
				p = a.getControlPanel();
				if (!p.isVideoSeekMode() && !a.getBody().isVideoMode()) {
					View v = focusSearch(this, (keyCode == KEYCODE_DPAD_LEFT) ? FOCUS_LEFT : FOCUS_RIGHT);
					if (v != null) {
						v.requestFocus();
						return true;
					} else {
						break;
					}
				}
				b = a.getMediaServiceBinder();
				b.onRwFfButtonClick(keyCode == KEYCODE_DPAD_RIGHT);
				a.getControlPanel().onVideoSeek();
				return true;
			}
			case KEYCODE_DPAD_UP -> {
				if ((a = getActivity().peek()) == null) break;
				b = a.getMediaServiceBinder();
				b.onRwFfButtonLongClick(true);
				a.getControlPanel().onVideoSeek();
				return true;
			}
			case KEYCODE_DPAD_DOWN -> {
				if ((a = getActivity().peek()) == null) break;
				p = a.getControlPanel();
				if (!p.isVideoSeekMode() && isVisible(p)) {
					View v = p.focusSearch();
					if (v != null) {
						v.requestFocus();
						return true;
					} else {
						break;
					}
				}
				b = a.getMediaServiceBinder();
				b.onRwFfButtonLongClick(false);
				a.getControlPanel().onVideoSeek();
				return true;
			}
		}

		return super.onKeyUp(keyCode, event);
	}

	private boolean onTouch(@NonNull MotionEvent e) {
		MainActivityDelegate a = getActivity().peek();
		if (a == null) return false;
		a.getControlPanel().onVideoViewTouch(this, e);
		return true;
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		if (!Collections.disjoint(infoOverlayPrefChange, prefs)) refreshInfoOverlay();

		if (createSurface.isDone() && !Collections.disjoint(prefChange, prefs)) {
			MainActivityDelegate a = getActivity().peek();
			if (a == null) return;
			MediaEngine eng = a.getMediaSessionCallback().getEngine();
			if (eng == null) return;
			PlayableItem i = eng.getSource();
			if ((i == null) || !i.isVideo()) return;

			if (prefs.contains(MediaPrefs.VIDEO_SCALE)) {
				setSurfaceSize(eng);
			} else if (prefs.contains(MediaPrefs.AUDIO_DELAY) ||
					prefs.contains(MediaPrefs.AUDIO_DELAY_AA)) {
				eng.setAudioDelay(
						i.getPrefs().getAudioDelayPref(prefs.contains(MediaPrefs.AUDIO_DELAY_AA)));
			} else if (prefs.contains(MediaPrefs.SUB_DELAY)) {
				eng.setSubtitleDelay(i.getPrefs().getSubDelayPref());
			}
		}
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (handleActivityDestroyEvent(a, e)) {
			a.getMediaSessionCallback().removeVideoView(this);
			a.getLib().getPrefs().removeBroadcastListener(this);
			a.getPrefs().removeBroadcastListener(this);
		}
	}

	@Override
	public View focusSearch(View focused, int direction) {
		MainActivityDelegate a = getActivity().peek();
		if ((a == null) || !a.getBody().isBothMode()) return focused;

		if (direction == FOCUS_LEFT) {
			return MediaItemListView.focusSearchActive(getContext(), focused);
		} else if (direction == FOCUS_RIGHT) {
			NavBarView n = a.getNavBar();
			if (n.isRight()) return n.focusSearch();
		}

		return focused;
	}

	private FutureSupplier<MainActivityDelegate> getActivity() {
		return MainActivityDelegate.getActivityDelegate(getContext());
	}

	private static abstract class SubDrawer {
		final float textScale;
		final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

		SubDrawer(float textScale) {
			this.textScale = textScale;
			bgPaint.setColor(Color.BLACK);
			bgPaint.setAlpha(180);
		}

		abstract boolean setText(SubGrid.Position position, @Nullable Subtitles.Text text);

		abstract void draw(Canvas canvas);

		void clr(Canvas canvas) {
			canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
		}

		float textSize(int canvasHeight, int canvasWidth) {
			var s = textScale * canvasWidth / 25f;
			return canvasHeight > canvasWidth ? s * canvasHeight / canvasWidth : s;
		}

		static TextPaint paint(Paint.Align align) {
			TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
			paint.setColor(Color.WHITE);
			paint.setTypeface(Typeface.DEFAULT);
			paint.setElegantTextHeight(true);
			paint.setTextAlign(align);
			return paint;
		}

		static CharSequence text(String text) {
			var idx = text.indexOf('<');
			if ((idx == -1) || (text.indexOf('>', idx) == -1)) return text;
			return fromHtml(text, FROM_HTML_MODE_LEGACY);
		}

		static StaticLayout layout(CharSequence text, TextPaint paint, int width) {
			return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width).setMaxLines(10)
					.setEllipsize(TextUtils.TruncateAt.END).setIncludePad(true).build();
		}

		void draw(Canvas canvas, StaticLayout sl) {
			float pad = 20f;
			float w = 0f;
			for (int i = 0, n = sl.getLineCount(); i < n; i++) {
				w = Math.max(w, sl.getLineWidth(i));
			}
			w = w / 2f + pad;
			canvas.translate(0, -pad);
			canvas.drawRoundRect(-w, -pad, w, sl.getHeight() + pad, pad, pad, bgPaint);
			sl.draw(canvas);
		}
	}

	private static final class GridDrawer extends SubDrawer {
		private final String[] grid = new String[9];
		private final TextPaint[] paint = new TextPaint[3];
		private final float[] yoff = new float[]{1f, 0.5f, 0.f};


		private GridDrawer(float textScale) {
			super(textScale);
			for (int i = 0; i < 3; i++) {
				paint[i] =
						paint(i == 0 ? Paint.Align.LEFT : i == 1 ? Paint.Align.CENTER : Paint.Align.RIGHT);
			}
		}

		@Override
		public boolean setText(SubGrid.Position position, @Nullable Subtitles.Text text) {
			var t = (text == null) ? null : text.getText();
			int idx = position.ordinal();
			if (Objects.equals(grid[idx], t)) return false;
			grid[idx] = t;
			return true;
		}

		@Override
		public void draw(Canvas canvas) {
			var ch = canvas.getHeight();
			var cw = canvas.getWidth();
			var ts = textSize(ch, cw);
			var x = new int[]{0, cw / 2, cw};
			var y = new int[]{ch, ch / 2, 0};

			for (int i = 0, g = 0; i < 3; i++, g += 3) {
				var l = grid[g] != null;
				var c = grid[g + 1] != null;
				var r = grid[g + 2] != null;
				int[] w = new int[3];

				if (c) {
					if (l) {
						if (r) {
							w[0] = w[1] = w[2] = cw / 3;
						} else {
							w[0] = w[1] = cw / 3;
						}
					} else if (r) {
						w[1] = w[2] = cw / 3;
					} else {
						w[1] = cw;
					}
				} else if (l) {
					if (r) w[0] = w[2] = cw / 2;
					else w[0] = cw;
				} else if (r) {
					w[2] = cw;
				} else {
					continue;
				}

				for (int j = 0; j < 3; j++) {
					if (w[j] == 0) continue;
					var t = text(grid[g + j]);
					paint[j].setTextSize(ts);
					var sl = layout(t, paint[j], w[j]);
					canvas.save();
					canvas.translate(x[j], y[i] - sl.getHeight() * yoff[i]);
					draw(canvas, sl);
					canvas.restore();
				}
			}
		}
	}

	private static final class DoubleSubDrawer extends SubDrawer {
		private final TextPaint paint;
		private boolean center;
		private String text;
		private String translation;

		DoubleSubDrawer(float textScale) {
			super(textScale);
			paint = paint(Paint.Align.CENTER);
		}

		@Override
		boolean setText(SubGrid.Position position, @Nullable Subtitles.Text text) {
			if (position == SubGrid.Position.BOTTOM_LEFT) {
				var t = (text == null) ? null : text.getText();
				if (!center && Objects.equals(this.text, t)) return false;
				center = false;
				this.text = t;
			} else if (position == SubGrid.Position.BOTTOM_RIGHT) {
				var t = (text == null) ? null : text.getText();
				if (!center && Objects.equals(translation, t)) return false;
				center = false;
				translation = t;
			} else {
				center = true;
				var t = (text == null) ? null : text.getText();
				var trans = (text == null) ? null : text.getTranslation();
				var c = position != SubGrid.Position.BOTTOM_CENTER;
				if (center == c && Objects.equals(this.text, t) && Objects.equals(translation, trans))
					return false;
				center = c;
				this.text = t;
				translation = trans;
			}
			return true;
		}

		@Override
		void draw(Canvas canvas) {
			CharSequence sub;
			int start;
			int end;

			if (text != null) {
				if (translation != null) {
					var t = text(text).toString();
					sub = t + '\n' + text(translation);
					start = t.length() + 1;
					end = sub.length();
				} else {
					sub = text(text).toString();
					start = end = 0;
				}
			} else if (translation != null) {
				sub = text(translation).toString();
				start = 0;
				end = sub.length();
			} else {
				return;
			}

			if (start != end) {
				var st = new SpannableString(sub);
				st.setSpan(new ForegroundColorSpan(Color.RED), start, end, SPAN_EXCLUSIVE_EXCLUSIVE);
				sub = st;
			}

			var ch = canvas.getHeight();
			var cw = canvas.getWidth();
			var x = cw / 2f;

			if (center) {
				float size = (ch > cw) ? cw / 10f : ch / 5f;
				for (; ; ) {
					paint.setTextSize(size);
					var sl = layout(sub, paint, cw);
					var sh = sl.getHeight();
					if (sh < ch) {
						canvas.translate(x, (ch - sl.getHeight()) / 2f);
						draw(canvas, sl);
						break;
					} else {
						size *= 0.9f;
					}
				}
			} else {
				paint.setTextSize(textSize(ch, cw));
				var sl = layout(sub, paint, cw);
				canvas.translate(x, ch - sl.getHeight());
				draw(canvas, sl);
			}
		}
	}
}
