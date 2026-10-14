package me.aap.fermata.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ytcaptions.CaptionsHub;
import me.aap.fermata.ytcaptions.YtCaptions.Cue;
import me.aap.utils.pref.PreferenceStore;

/**
 * The captions of the playing YouTube video as lyrics: the line being sung is big and bright, the
 * one before it dim and fading away upwards, the next ones dimmer the further they are, gone after
 * the third. When the line changes the lines glide up (the highlight moves with them) rather than
 * jump. Drawn in one pass instead of as list rows so that the fade, the glide and the highlight are
 * all the same smooth number ({@link #pos}) moving, with no row recycling in the way.
 * <p>
 * A vertical drag scrubs: the lines follow the finger (a time shows what is under the highlight), a
 * fling carries on and settles on a line, and the video plays from there. It feeds itself from
 * {@link CaptionsHub} while it is on screen, and says what's up instead of lines while they load,
 * when the video has none, or when nothing from YouTube plays. The look (font, size, alignment, the
 * highlight's colour) is Settings > Lyrics and changes under the eye. In preview mode it shows
 * given sample lines instead and follows nothing.
 */
public final class LyricsView extends View implements CaptionsHub.Listener, PreferenceStore.Listener {
	private static final float ACTIVE_ZOOM = 0.14f;
	private final MainActivityDelegate activity;
	private final float density;
	private final int textColor;
	private final int baseHighlight;
	private final boolean shadow;
	private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
	private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint pillText = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final int slop;
	private final List<Cue> cues = new ArrayList<>();
	private StaticLayout[] layouts = new StaticLayout[0];
	private float[] centers = new float[0];
	private float[] heights = new float[0];
	private int laidOutWidth = -1;
	private float sizeScale = 1f;
	private int align;
	private int highlightColor;
	/** The line at the highlight, as a fractional index while it glides (-1: before the first). */
	private float pos = -1f;
	private int index = -1;
	private ValueAnimator glide;
	private ValueAnimator settle;
	private ValueAnimator pulse;
	private float pulseAlpha = 1f;
	@Nullable
	private StaticLayout statusLayout;
	private String status = "";
	private boolean statusBusy;
	@Nullable
	private LineListener lineListener;
	private boolean interactive = true;
	private boolean previewMode;
	private boolean listening;
	// Scrubbing.
	private boolean browsing;
	private float browseStartCenter;
	private float downX;
	private float downY;
	private float dragBaseY;
	private boolean tracking;
	private boolean dragging;
	@Nullable
	private VelocityTracker velocity;

	public interface LineListener {
		void onLine(int index);
	}

	/**
	 * @param textColor      the lines other than the playing one
	 * @param highlightColor the playing line (unless Settings pick another colour)
	 * @param shadow         a soft shadow under the letters, for lyrics over a picture
	 */
	public LyricsView(@NonNull Context ctx, @NonNull MainActivityDelegate a, int textColor,
										int highlightColor, boolean shadow) {
		super(ctx);
		this.activity = a;
		this.density = ctx.getResources().getDisplayMetrics().density;
		this.textColor = textColor;
		this.baseHighlight = highlightColor;
		this.highlightColor = highlightColor;
		this.shadow = shadow;
		this.slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
		pill.setColor(0xCC000000);
		pillText.setColor(Color.WHITE);
		pillText.setTextAlign(Paint.Align.CENTER);
		pillText.setTypeface(Typeface.DEFAULT_BOLD);
		pillText.setTextSize(13 * density);
		applyStyle();
		setClickable(true);
	}

	/** Over the cover it takes no touches at all: the cover's swipe and tap see them instead. */
	public void setInteractive(boolean interactive) {
		this.interactive = interactive;
		setClickable(interactive);
	}

	/** Tapping a line (rather than the view) calls this with the line's index. */
	public void setLineListener(@Nullable LineListener l) {
		lineListener = l;
	}

	/** Sample lines instead of a video's, the {@code active} one highlighted: Settings' preview. */
	public void setPreview(List<String> lines, int active) {
		previewMode = true;
		cues.clear();
		long t = 0;
		for (String s : lines) {
			cues.add(new Cue(t, t + 3000, s));
			t += 3000;
		}
		laidOutWidth = -1;
		setStatus(null, false);
		index = active;
		pos = active;
		invalidate();
	}

	// ---------------------------------------------------------------------------------------------
	// Look

	/** Reads the look from Settings; the lines are laid out again at the next draw. */
	private void applyStyle() {
		MainActivityPrefs p = activity.getPrefs();
		sizeScale = Math.max(50, Math.min(250, p.getIntPref(MainActivityPrefs.LYRICS_SIZE))) / 100f;
		int font = p.getIntPref(MainActivityPrefs.LYRICS_FONT);
		boolean bold = p.getBooleanPref(MainActivityPrefs.LYRICS_BOLD);
		boolean italic = p.getBooleanPref(MainActivityPrefs.LYRICS_ITALIC);
		// The default font is the medium weight sans-serif; bold goes heavier.
		Typeface tf = ((font == 0) && !bold) ?
				Typeface.create("sans-serif-medium", italic ? Typeface.ITALIC : Typeface.NORMAL) :
				CaptionStyle.typeface(font, bold, italic);
		paint.setTypeface(tf);
		paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 19 * sizeScale,
				getResources().getDisplayMetrics()));
		align = p.getIntPref(MainActivityPrefs.LYRICS_ALIGN);
		int c = p.getIntPref(MainActivityPrefs.LYRICS_COLOR);
		highlightColor = ((c > 0) && (c <= CaptionStyle.COLORS.length)) ?
				(CaptionStyle.COLORS[c - 1] | 0xFF000000) : baseHighlight;
		laidOutWidth = -1;
		statusLayout = null;
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		for (PreferenceStore.Pref<?> p : prefs) {
			if (p.getName().startsWith("LYRICS_")) {
				applyStyle();
				invalidate();
				return;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Hub

	/**
	 * Following the hub (and the settings) only while the view is on screen: over the Music tab's
	 * cover it sits in the layout all the time, and must cost nothing while the lyrics are off.
	 */
	@Override
	public void onVisibilityAggregated(boolean visible) {
		super.onVisibilityAggregated(visible);
		if (visible && !listening) {
			listening = true;
			activity.getPrefs().addBroadcastListener(this);
			// What it missed while it was away.
			applyStyle();
			if (!previewMode) {
				CaptionsHub.get().acquire(activity, this);
				sync(false);
			}
		} else if (!visible && listening) {
			listening = false;
			activity.getPrefs().removeBroadcastListener(this);
			if (!previewMode) CaptionsHub.get().release(this);
			if (glide != null) glide.cancel();
			if (settle != null) settle.cancel();
			browsing = false;
			stopPulse();
		}
	}

	@Override
	public void onCaptionsState(CaptionsHub hub) {
		sync(true);
	}

	@Override
	public void onCaptionsTick(CaptionsHub hub) {
		if (browsing || (hub.getState() != CaptionsHub.State.READY)) return;
		moveTo(hub.getIndex(), true);
	}

	private void sync(boolean animate) {
		CaptionsHub hub = CaptionsHub.get();
		CaptionsHub.State s = hub.getState();
		if (s == CaptionsHub.State.READY) {
			List<Cue> list = hub.getCues();
			if (cues.size() != list.size() || (!cues.isEmpty() && cues.get(0) != list.get(0))) {
				cues.clear();
				cues.addAll(list);
				laidOutWidth = -1;
				if (glide != null) glide.cancel();
				pos = index = -1;
			}
			setStatus(null, false);
			if (!browsing) moveTo(hub.getIndex(), animate);
		} else {
			cues.clear();
			laidOutWidth = -1;
			layouts = new StaticLayout[0];
			centers = heights = new float[0];
			if (glide != null) glide.cancel();
			pos = index = -1;
			switch (s) {
				case LOADING -> setStatus(getContext().getString(R.string.lyrics_loading), true);
				case NONE -> setStatus(getContext().getString(R.string.lyrics_none), false);
				case FAILED -> setStatus(getContext().getString(R.string.lyrics_failed), false);
				default -> setStatus(getContext().getString(R.string.lyrics_not_available), false);
			}
		}
		invalidate();
	}

	// ---------------------------------------------------------------------------------------------
	// Glide

	private void moveTo(int i, boolean animate) {
		if (i == index) return;
		int from = index;
		index = i;
		if (glide != null) glide.cancel();
		if (!animate || (getWidth() == 0) || !isShown()) {
			pos = i;
			invalidate();
			return;
		}
		float start = pos;
		// A seek (far away) glides faster than the next line does.
		long ms = (Math.abs(i - from) > 3) ? 260 : 420;
		glide = ValueAnimator.ofFloat(start, i);
		glide.setDuration(ms);
		glide.setInterpolator(new DecelerateInterpolator(1.8f));
		glide.addUpdateListener(a -> {
			pos = (float) a.getAnimatedValue();
			invalidate();
		});
		glide.start();
	}

	private void setStatus(@Nullable String text, boolean busy) {
		if (text == null) {
			status = "";
			statusLayout = null;
			statusBusy = false;
			stopPulse();
			return;
		}
		if (!text.equals(status)) statusLayout = null;
		status = text;
		statusBusy = busy;
		if (busy) startPulse();
		else stopPulse();
	}

	private void startPulse() {
		if (pulse != null) return;
		pulse = ValueAnimator.ofFloat(0.35f, 1f);
		pulse.setDuration(900);
		pulse.setRepeatMode(ValueAnimator.REVERSE);
		pulse.setRepeatCount(ValueAnimator.INFINITE);
		pulse.setInterpolator(new LinearInterpolator());
		pulse.addUpdateListener(a -> {
			pulseAlpha = (float) a.getAnimatedValue();
			invalidate();
		});
		pulse.start();
	}

	private void stopPulse() {
		if (pulse != null) pulse.cancel();
		pulse = null;
		pulseAlpha = 1f;
	}

	// ---------------------------------------------------------------------------------------------
	// Scrubbing (also driven from outside, over the cover, by the cover's own touch handling)

	/** Whether there are lines to scrub through. */
	public boolean canBrowse() {
		return !cues.isEmpty();
	}

	public boolean isBrowsing() {
		return browsing;
	}

	/** A drag begins: the lines stop following the video and follow the finger. */
	public void beginBrowse() {
		if (cues.isEmpty() || (laidOutWidth < 0)) return;
		if (glide != null) glide.cancel();
		if (settle != null) settle.cancel();
		browsing = true;
		browseStartCenter = centerAt(pos);
		invalidate();
	}

	/** The finger is {@code dy} pixels (down positive) from where the drag began. */
	public void browseBy(float dy) {
		if (!browsing) return;
		pos = posForCenter(browseStartCenter - dy);
		invalidate();
	}

	/** The finger is up (moving at {@code vy} px/s): carries on a little, settles on a line, plays it. */
	public void endBrowse(float vy) {
		if (!browsing) return;
		float travel = centerAt(pos) - vy * 0.12f;
		int target = Math.round(posForCenter(travel));
		target = Math.max(0, Math.min(cues.size() - 1, target));
		int to = target;
		float dist = Math.abs(to - pos);
		if (settle != null) settle.cancel();
		settle = ValueAnimator.ofFloat(pos, to);
		settle.setDuration(Math.min(520, 200 + Math.round(dist * 40)));
		settle.setInterpolator(new DecelerateInterpolator(1.6f));
		settle.addUpdateListener(a -> {
			pos = (float) a.getAnimatedValue();
			invalidate();
		});
		settle.addListener(new android.animation.AnimatorListenerAdapter() {
			private boolean cancelled;

			@Override
			public void onAnimationCancel(android.animation.Animator animation) {
				cancelled = true;
			}

			@Override
			public void onAnimationEnd(android.animation.Animator animation) {
				if (cancelled) return;
				pos = to;
				index = to;
				browsing = false;
				if (!previewMode) CaptionsHub.get().seekTo(to);
				invalidate();
			}
		});
		settle.start();
	}

	/** The position at which the highlight sits on the point {@code c} of the lines' own axis. */
	private float posForCenter(float c) {
		int n = centers.length;
		if (n == 0) return 0;
		if (c <= centers[0]) return 0;
		if (c >= centers[n - 1]) return n - 1;
		int i = 0;
		while ((i < n - 2) && (centers[i + 1] <= c)) i++;
		float span = centers[i + 1] - centers[i];
		return (span <= 0) ? i : i + (c - centers[i]) / span;
	}

	private static String time(long ms) {
		long s = Math.max(0, ms / 1000);
		return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
	}

	// ---------------------------------------------------------------------------------------------
	// Layout

	private float pad() {
		return 20 * density;
	}

	private float gap() {
		return 16 * density * sizeScale;
	}

	private void layoutLines(int w) {
		int n = cues.size();
		layouts = new StaticLayout[n];
		centers = new float[n];
		heights = new float[n];
		// Room for the highlighted line growing.
		int lw = Math.max(1, Math.round((w - 2 * pad()) / (1f + ACTIVE_ZOOM)));
		Layout.Alignment al = (align == 1) ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_CENTER;
		float y = 0;
		for (int i = 0; i < n; i++) {
			String t = cues.get(i).text;
			StaticLayout l = StaticLayout.Builder.obtain(t, 0, t.length(), paint, lw)
					.setAlignment(al).setLineSpacing(0f, 1.05f).build();
			layouts[i] = l;
			heights[i] = l.getHeight();
			centers[i] = y + heights[i] / 2f;
			y += heights[i] + gap();
		}
		laidOutWidth = w;
	}

	@Override
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		super.onSizeChanged(w, h, ow, oh);
		laidOutWidth = -1;
		statusLayout = null;
	}

	/** The scroll position's centre, interpolated between the lines' centres. */
	private float centerAt(float p) {
		int n = centers.length;
		if (n == 0) return 0;
		if (p <= 0) return centers[0] + p * (heights[0] + gap());
		if (p >= n - 1) return centers[n - 1];
		int i = (int) Math.floor(p);
		float f = p - i;
		return centers[i] + (centers[i + 1] - centers[i]) * f;
	}

	/** How visible a line is {@code d} lines after the playing one (negative: before it). */
	private static float alphaFor(float d) {
		if (d <= -2f) return 0f;
		if (d < -1f) return 0.38f * (d + 2f);
		if (d < 0f) return 0.38f + 0.62f * (d + 1f);
		if (d < 1f) return 1f - 0.38f * d;
		if (d < 2f) return 0.62f - 0.17f * (d - 1f);
		if (d < 3f) return 0.45f - 0.17f * (d - 2f);
		if (d < 4f) return 0.28f * (4f - d);
		return 0f;
	}

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;
		if (cues.isEmpty()) {
			drawStatus(c, w, h);
			return;
		}
		if (laidOutWidth != w) layoutLines(w);
		float anchor = h * 0.42f;
		float scroll = centerAt(pos);
		int n = layouts.length;
		int from = Math.max(0, (int) Math.floor(pos) - 2);
		int to = Math.min(n - 1, (int) Math.ceil(pos) + 4);
		float fade = Math.min(h * 0.22f, 80 * density);
		for (int i = from; i <= to; i++) {
			float d = i - pos;
			float a = alphaFor(d);
			if (a <= 0.01f) continue;
			float act = Math.max(0f, 1f - Math.abs(d));
			float s = 1f + ACTIVE_ZOOM * act;
			float y = centers[i] - scroll + anchor;
			// Softly out at the view's own top and bottom edges rather than cut.
			float top = y - heights[i] * s / 2f;
			float bottom = y + heights[i] * s / 2f;
			if (top < 0f) a *= Math.max(0f, 1f + top / fade);
			if (bottom > h) a *= Math.max(0f, 1f - (bottom - h) / fade);
			if (a <= 0.01f) continue;
			paint.setColor(blend(textColor, highlightColor, act));
			paint.setAlpha(Math.round(255 * a));
			if (shadow) paint.setShadowLayer(5 * density, 0, 1.5f * density, Color.argb(Math.round(150 * a), 0, 0, 0));
			StaticLayout l = layouts[i];
			c.save();
			// Centred lines grow around their middle, left aligned ones around their left edge.
			if (align == 1) {
				c.translate(pad(), y);
				c.scale(s, s, 0f, 0f);
				c.translate(0f, -heights[i] / 2f);
			} else {
				c.translate(w / 2f, y);
				c.scale(s, s);
				c.translate(-l.getWidth() / 2f, -heights[i] / 2f);
			}
			l.draw(c);
			c.restore();
		}
		paint.clearShadowLayer();
		if (browsing) drawBrowseTime(c, w, h);
	}

	/** While scrubbing: the time of the line at the highlight, in a pill at the bottom. */
	private void drawBrowseTime(Canvas c, int w, int h) {
		int i = Math.max(0, Math.min(cues.size() - 1, Math.round(pos)));
		String t = time(cues.get(i).start);
		float tw = pillText.measureText(t) + 28 * density;
		float th = 30 * density;
		float cx = w / 2f;
		float cy = h - th / 2f - 10 * density;
		rect.set(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f);
		c.drawRoundRect(rect, th / 2f, th / 2f, pill);
		Paint.FontMetrics fm = pillText.getFontMetrics();
		c.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2f, pillText);
	}

	private void drawStatus(Canvas c, int w, int h) {
		if (status.isEmpty()) return;
		if (statusLayout == null) {
			int lw = Math.max(1, Math.round(w - 2 * pad()));
			statusLayout = StaticLayout.Builder.obtain(status, 0, status.length(), paint, lw)
					.setAlignment(Layout.Alignment.ALIGN_CENTER).build();
		}
		paint.setColor(textColor);
		paint.setAlpha(Math.round(255 * 0.8f * (statusBusy ? pulseAlpha : 1f)));
		if (shadow) paint.setShadowLayer(5 * density, 0, 1.5f * density, 0x96000000);
		c.save();
		c.translate(pad(), (h - statusLayout.getHeight()) / 2f);
		statusLayout.draw(c);
		c.restore();
		paint.clearShadowLayer();
	}

	private static int blend(int a, int b, float t) {
		if (t <= 0f) return a | 0xFF000000;
		if (t >= 1f) return b | 0xFF000000;
		int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
		int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
		int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
		return Color.rgb(r, g, bl);
	}

	// ---------------------------------------------------------------------------------------------
	// Touch

	/** The line under {@code y}, or -1. */
	private int lineAt(float y) {
		if (cues.isEmpty() || (laidOutWidth < 0)) return -1;
		float anchor = getHeight() * 0.42f;
		float scroll = centerAt(pos);
		int best = -1;
		float bestDist = Float.MAX_VALUE;
		for (int i = 0; i < centers.length; i++) {
			if (alphaFor(i - pos) <= 0.05f) continue;
			float cy = centers[i] - scroll + anchor;
			float dist = Math.abs(y - cy);
			if ((dist <= heights[i] / 2f + 8 * density) && (dist < bestDist)) {
				best = i;
				bestDist = dist;
			}
		}
		return best;
	}

	@Override
	public boolean onTouchEvent(MotionEvent e) {
		if (!interactive) return false;
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				downX = e.getX();
				downY = e.getY();
				tracking = true;
				dragging = false;
				if (velocity != null) velocity.recycle();
				velocity = VelocityTracker.obtain();
				velocity.addMovement(e);
				return true;
			}
			case MotionEvent.ACTION_MOVE -> {
				if (!tracking) return true;
				if (velocity != null) velocity.addMovement(e);
				if (!dragging) {
					float dx = e.getX() - downX;
					float dy = e.getY() - downY;
					// Only a mostly vertical drag scrubs; the card around may have its own gestures.
					if ((Math.abs(dy) > slop) && (Math.abs(dy) > Math.abs(dx)) && canBrowse()) {
						dragging = true;
						dragBaseY = e.getY();
						if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
						beginBrowse();
					} else if (Math.abs(dx) > slop) {
						tracking = false;
					}
				}
				if (dragging) browseBy(e.getY() - dragBaseY);
				return true;
			}
			case MotionEvent.ACTION_UP -> {
				if (dragging) {
					float vy = 0;
					if (velocity != null) {
						velocity.addMovement(e);
						velocity.computeCurrentVelocity(1000);
						vy = velocity.getYVelocity();
					}
					endBrowse(vy);
				} else if (tracking) {
					int i = (lineListener == null) ? -1 : lineAt(e.getY());
					if (i >= 0) lineListener.onLine(i);
					else performClick();
				}
				finishTouch();
				return true;
			}
			case MotionEvent.ACTION_CANCEL -> {
				if (dragging) endBrowse(0f);
				finishTouch();
				return true;
			}
			default -> {
				return super.onTouchEvent(e);
			}
		}
	}

	private void finishTouch() {
		tracking = false;
		dragging = false;
		if (velocity != null) velocity.recycle();
		velocity = null;
	}

	@Override
	public boolean performClick() {
		return super.performClick();
	}
}
