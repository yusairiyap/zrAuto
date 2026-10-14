package me.aap.fermata.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ytcaptions.CaptionsHub;
import me.aap.fermata.ytcaptions.YtCaptions.Cue;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.utils.pref.PreferenceStore;

/**
 * YouTube's captions drawn over the picture of a fullscreen video, in the look picked in Settings >
 * Captions (see {@link CaptionStyle}); every {@link VideoView} carries one, under its controls. It
 * takes no touches. While the Captions switch is on and the video is on screen it follows the
 * playing video through {@link CaptionsHub} (a new video, the next one in a playlist, gets its own
 * captions without anyone asking), and fades each line in and out. It is sized from the picture,
 * so the text scales with the video whether it fills the screen or sits beside the comments.
 */
public final class CaptionsOverlayView extends View
		implements CaptionsHub.Listener, PreferenceStore.Listener {
	private static final float BASE_TEXT = 0.045f;
	private final TextPaint fill = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
	private final TextPaint stroke = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
	private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final float density;
	@Nullable
	private MainActivityDelegate activity;
	private CaptionStyle style;
	private boolean acquired;
	@Nullable
	private String text;
	@Nullable
	private StaticLayout layout;
	@Nullable
	private StaticLayout strokeLayout;
	private int laidOutFor = -1;
	private float alphaNow;
	private ValueAnimator fade;

	public CaptionsOverlayView(@NonNull Context ctx) {
		super(ctx);
		density = ctx.getResources().getDisplayMetrics().density;
		setClickable(false);
		setFocusable(false);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		stroke.setStyle(Paint.Style.STROKE);
		stroke.setStrokeJoin(Paint.Join.ROUND);
	}

	// ---------------------------------------------------------------------------------------------
	// Switching on and off

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
			if (!isAttachedToWindow()) return;
			activity = a;
			a.getPrefs().addBroadcastListener(this);
			readStyle();
			update();
		});
	}

	@Override
	protected void onDetachedFromWindow() {
		MainActivityDelegate a = activity;
		if (a != null) a.getPrefs().removeBroadcastListener(this);
		activity = null;
		release();
		super.onDetachedFromWindow();
	}

	@Override
	public void onVisibilityAggregated(boolean visible) {
		super.onVisibilityAggregated(visible);
		update();
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		boolean styleChanged = false;
		boolean onOff = prefs.contains(MainActivityPrefs.CAPTIONS_ON);
		for (PreferenceStore.Pref<?> p : prefs) {
			if (p.getName().startsWith("CAPTION_")) {
				styleChanged = true;
				break;
			}
		}
		if (prefs.contains(MainActivityPrefs.CAPTIONS_LANG) || prefs.contains(MainActivityPrefs.CAPTIONS_AUTO)) {
			CaptionsHub.get().invalidate();
		}
		if (styleChanged) {
			readStyle();
			laidOutFor = -1;
			invalidate();
		}
		if (onOff) update();
	}

	private void readStyle() {
		MainActivityDelegate a = activity;
		if (a != null) style = CaptionStyle.read(a.getPrefs());
	}

	/** Following the hub only while the switch is on and the picture is on screen. */
	private void update() {
		MainActivityDelegate a = activity;
		boolean want = (a != null) && isShown() &&
				a.getPrefs().getBooleanPref(MainActivityPrefs.CAPTIONS_ON);
		if (want && !acquired) {
			acquired = true;
			CaptionsHub.get().acquire(a, this);
			show(CaptionsHub.get().getCaption());
		} else if (!want && acquired) {
			release();
			show(null);
		}
	}

	private void release() {
		if (!acquired) return;
		acquired = false;
		CaptionsHub.get().release(this);
	}

	@Override
	public void onCaptionsState(CaptionsHub hub) {
		show(hub.getCaption());
	}

	@Override
	public void onCaptionsTick(CaptionsHub hub) {
		show(hub.getCaption());
	}

	// ---------------------------------------------------------------------------------------------
	// Showing a line

	private void show(@Nullable Cue c) {
		String t = (c == null) ? null : c.text;
		if ((t == null) ? (text == null) : t.equals(text)) return;
		if (t != null) {
			// A new line replaces the one showing: no flash of nothing between them.
			text = t;
			layout = strokeLayout = null;
			laidOutFor = -1;
			fadeTo(1f, alphaNow > 0.05f ? 90 : 160);
		} else {
			text = null;
			fadeTo(0f, 140);
		}
		invalidate();
	}

	private void fadeTo(float to, long ms) {
		if (fade != null) fade.cancel();
		fade = ValueAnimator.ofFloat(alphaNow, to);
		fade.setDuration(ms);
		fade.addUpdateListener(a -> {
			alphaNow = (float) a.getAnimatedValue();
			invalidate();
		});
		fade.start();
	}

	// ---------------------------------------------------------------------------------------------
	// Drawing

	private void buildLayout(int w, int h) {
		CaptionStyle s = style;
		String t = text;
		if ((s == null) || (t == null)) return;
		float px = Math.max(12 * density, h * BASE_TEXT * s.size);
		int tw = Math.max(1, Math.round(w * 0.84f));
		fill.setTypeface(s.typeface);
		fill.setTextSize(px);
		fill.setColor(s.textColor);
		stroke.setTypeface(s.typeface);
		stroke.setTextSize(px);
		stroke.setColor(s.edgeColor);
		stroke.setStrokeWidth(px * 0.16f * Math.max(0.15f, s.edgeStrength));
		fill.clearShadowLayer();
		switch (s.edge) {
			case CaptionStyle.EDGE_SHADOW -> {
				float r = px * 0.10f * Math.max(0.2f, s.edgeStrength * 1.6f);
				fill.setShadowLayer(r, r * 0.6f, r * 0.6f, s.edgeColor);
			}
			case CaptionStyle.EDGE_GLOW ->
					fill.setShadowLayer(px * 0.45f * Math.max(0.2f, s.edgeStrength), 0f, 0f, s.edgeColor);
			default -> {
			}
		}
		layout = StaticLayout.Builder.obtain(t, 0, t.length(), fill, tw)
				.setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).build();
		strokeLayout = (s.edge == CaptionStyle.EDGE_OUTLINE) ?
				StaticLayout.Builder.obtain(t, 0, t.length(), stroke, tw)
						.setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).build() : null;
		laidOutFor = w * 31 + h;
	}

	@Override
	protected void onDraw(Canvas c) {
		if ((text == null) && (alphaNow <= 0.01f)) return;
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0) || (style == null)) return;
		if ((layout == null) || (laidOutFor != w * 31 + h)) buildLayout(w, h);
		StaticLayout l = layout;
		if (l == null) return;
		CaptionStyle s = style;

		float padX = fill.getTextSize() * 0.45f;
		float padY = fill.getTextSize() * 0.22f;
		float boxW = l.getWidth() + 2 * padX;
		float boxH = l.getHeight() + 2 * padY;
		float left = (w - boxW) / 2f;
		float top = switch (s.position) {
			case CaptionStyle.POS_TOP -> h * s.margin;
			case CaptionStyle.POS_MIDDLE -> (h - boxH) / 2f;
			default -> h - h * s.margin - boxH;
		};
		top = Math.max(0f, Math.min(top, h - boxH));

		int a255 = Math.round(255 * Math.max(0f, Math.min(1f, alphaNow)));
		if (Color.alpha(s.boxColor) > 0) {
			box.setColor(s.boxColor);
			box.setAlpha(Math.round(Color.alpha(s.boxColor) * (a255 / 255f)));
			rect.set(left, top, left + boxW, top + boxH);
			float r = fill.getTextSize() * 0.3f;
			c.drawRoundRect(rect, r, r, box);
		}
		c.save();
		c.translate(left + padX, top + padY);
		// Room around the text for a shadow or glow to reach into.
		float room = fill.getTextSize();
		int save = c.saveLayerAlpha(-room, -room, l.getWidth() + room, l.getHeight() + room, a255);
		if (strokeLayout != null) strokeLayout.draw(c);
		l.draw(c);
		if (s.edge == CaptionStyle.EDGE_GLOW) l.draw(c);
		c.restoreToCount(save);
		c.restore();
	}

	/** The Captions action (All in One, a floating button, a key): switches the captions on or off. */
	public static void toggleAction(@NonNull MainActivityDelegate a) {
		MainActivityPrefs p = a.getPrefs();
		boolean on = !p.getBooleanPref(MainActivityPrefs.CAPTIONS_ON);
		p.applyBooleanPref(MainActivityPrefs.CAPTIONS_ON, on);
		if (on && (YtDownloads.videoIdOf(Action.getFavoritableItem(a)) == null)) {
			TopToast.show(R.drawable.captions, R.string.captions_no_video);
			return;
		}
		TopToast.show(R.drawable.captions, on ? R.string.captions_on : R.string.captions_off);
	}
}
