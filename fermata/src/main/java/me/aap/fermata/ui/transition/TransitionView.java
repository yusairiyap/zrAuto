package me.aap.fermata.ui.transition;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.ui.home.HomeBlur;
import me.aap.fermata.ui.transition.TransitionConfig.Element;

/**
 * The transition screen itself: a background layer and one view per element, laid out from the
 * fractions in a {@link TransitionConfig}. The same class is the live overlay (shown when a song
 * starts) and the editable preview in the settings (with {@link #setEditor}: tap to pick an element,
 * drag it, drag its corner handle or pinch to size it).
 */
public final class TransitionView extends FrameLayout {
	/** What the elements show. */
	public static final class Data {
		@Nullable
		public String title;
		@Nullable
		public String channel;
		@Nullable
		public String playlist;
		@Nullable
		public Bitmap art;
		@Nullable
		public String next;
		/** "3 of 12". */
		@Nullable
		public String queue;
		@Nullable
		public String source;
		@Nullable
		public String quality;
	}

	public interface Editor {
		/** An element was picked by a touch (0 = none). */
		void onPicked(int elementId);

		/** An element was moved or sized; {@code done} once the finger lifts. */
		void onEdited(Element e, boolean done);

		/** A plain tap (no drag) on the element that was already picked. */
		default void onTapSelected(int elementId) {
		}
	}

	private static final float SNAP = 0.02f;
	private final float density;
	private TransitionConfig config;
	private final Data data = new Data();
	private final Bg bg;
	private final List<ElementView> views = new ArrayList<>();
	@Nullable
	private Editor editor;
	private int selectedId;
	@Nullable
	private Bitmap picture;
	/** What a live overlay shows when the element has no content: nothing; the preview shows samples. */
	private boolean sample;

	private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private boolean guideX;
	private boolean guideY;

	// Touch editing.
	private final ScaleGestureDetector pinch;
	private static final int M_NONE = 0, M_DRAG = 1, M_SIZE = 2, M_W = 3, M_H = 4;
	private int mode;
	private ElementView grabbed;
	private float downX, downY, startX, startY;
	private boolean pinching;
	private boolean wasSelected;
	private boolean moved;
	private final int slop;

	public TransitionView(@NonNull Context ctx, @NonNull TransitionConfig config) {
		super(ctx);
		density = ctx.getResources().getDisplayMetrics().density;
		slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
		this.config = config;
		setWillNotDraw(false);
		bg = new Bg(ctx);
		addView(bg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
		guidePaint.setColor(0xCC4FC3F7);
		guidePaint.setStrokeWidth(density * 1.2f);
		handlePaint.setStyle(Paint.Style.FILL);
		pinch = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
			@Override
			public boolean onScale(@NonNull ScaleGestureDetector d) {
				Element e = selected();
				if (e == null) return false;
				float f = d.getScaleFactor();
				if (e.isText()) e.size *= f;
				else e.w *= f;
				e.clamp();
				requestLayout();
				if (editor != null) editor.onEdited(e, false);
				return true;
			}
		});
		rebuild();
	}

	// ---- Content ----

	public TransitionConfig getConfig() {
		return config;
	}

	/** Uses a different set (or the same one, edited): the views are made again. */
	public void setConfig(@NonNull TransitionConfig c) {
		config = c;
		rebuild();
	}

	/** After the settings of the set or of an element changed: looks again at everything. */
	public void refresh() {
		boolean same = views.size() == config.elements.size();
		if (same) {
			for (int i = 0; i < views.size(); i++) {
				if (views.get(i).el != config.elements.get(i)) {
					same = false;
					break;
				}
			}
		}
		if (!same) rebuild();
		bg.update();
		for (ElementView v : views) v.update();
		requestLayout();
		invalidate();
	}

	private void rebuild() {
		for (ElementView v : views) removeView(v);
		views.clear();
		for (Element e : config.elements) {
			ElementView v = new ElementView(getContext(), e);
			views.add(v);
			addView(v);
		}
		bg.update();
	}

	public void setSample(boolean sample) {
		this.sample = sample;
		refresh();
	}

	public void setData(@Nullable String title, @Nullable String channel, @Nullable String playlist) {
		data.title = title;
		data.channel = channel;
		data.playlist = playlist;
		refresh();
	}

	/** The song's place in the queue, what follows it, where it plays from and its quality. */
	public void setExtra(@Nullable String next, @Nullable String queue, @Nullable String source, @Nullable String quality) {
		data.next = next;
		data.queue = queue;
		data.source = source;
		data.quality = quality;
		refresh();
	}

	public void setArt(@Nullable Bitmap art) {
		data.art = art;
		bg.update();
		for (ElementView v : views) v.update();
		invalidate();
	}

	/** The picture for the Picture background (null = none). */
	public void setPicture(@Nullable Bitmap b) {
		picture = b;
		bg.update();
	}

	public void setEditor(@Nullable Editor e) {
		editor = e;
		invalidate();
	}

	public void setSelected(int id) {
		selectedId = id;
		invalidate();
	}

	@Nullable
	private Element selected() {
		return (selectedId == 0) ? null : config.find(selectedId);
	}

	// ---- Layout ----

	@Override
	protected void onMeasure(int wSpec, int hSpec) {
		int w = MeasureSpec.getSize(wSpec);
		int h = MeasureSpec.getSize(hSpec);
		setMeasuredDimension(w, h);
		bg.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
		for (ElementView v : views) v.measureIn(w, h);
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		int w = r - l;
		int h = b - t;
		bg.layout(0, 0, w, h);
		for (ElementView v : views) {
			int cx = Math.round(v.el.x * w);
			int cy = Math.round(v.el.y * h);
			int vw = v.getMeasuredWidth();
			int vh = v.getMeasuredHeight();
			v.layout(cx - vw / 2, cy - vh / 2, cx - vw / 2 + vw, cy - vh / 2 + vh);
		}
	}

	// ---- Showing and hiding ----

	private static TimeInterpolator in() {
		return new DecelerateInterpolator(1.6f);
	}

	private static TimeInterpolator out() {
		return new AccelerateInterpolator(1.3f);
	}

	@Nullable
	private Runnable pendingDone;
	private final Runnable clearLayers = () -> setLayers(false);

	private void setLayers(boolean on) {
		int t = on ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
		bg.setLayerType(t, null);
		for (ElementView v : views) v.setLayerType(t, null);
	}

	/** Stops whatever is moving and shows everything at rest (the preview's stop button). */
	public void stop() {
		if (pendingDone != null) {
			removeCallbacks(pendingDone);
			pendingDone = null;
		}
		removeCallbacks(clearLayers);
		showNow();
		setLayers(false);
		setAlpha(1f);
	}

	/** Brings the background and the elements in, each with its own animation and delay. */
	public void playIn() {
		long longest = config.inMs;
		for (ElementView v : views) longest = Math.max(longest, v.el.inMs + v.el.delay);
		setLayers(true);
		removeCallbacks(clearLayers);
		postDelayed(clearLayers, longest + 80);
		bg.animate().cancel();
		bg.setAlpha(config.inAnim == TransitionConfig.ANIM_NONE ? 1f : 0f);
		bg.setTranslationX(0f);
		bg.setTranslationY(0f);
		bg.setScaleX(1f);
		bg.setScaleY(1f);
		start(bg, config.inAnim, config.inMs, 0, true, in(), getWidth(), getHeight(), null);
		for (ElementView v : views) {
			Element e = v.el;
			v.animate().cancel();
			v.setAlpha(e.inAnim == TransitionConfig.ANIM_NONE ? 1f : 0f);
			start(v, e.inAnim, e.inMs, e.delay, true, in(), getWidth(), getHeight(), null);
		}
		// Hidden (alpha 0) until every start pose is set, so the finished picture never shows for a frame first.
		setAlpha(1f);
	}

	/** Takes them out again and calls {@code done} when the last has left. */
	public void playOut(@Nullable Runnable done) {
		setLayers(true);
		long longest = 0;
		for (ElementView v : views) {
			Element e = v.el;
			if (e.outAnim == TransitionConfig.ANIM_NONE) v.setAlpha(0f);
			else start(v, e.outAnim, e.outMs, 0, false, out(), getWidth(), getHeight(), null);
			longest = Math.max(longest, e.outMs);
		}
		if (config.outAnim == TransitionConfig.ANIM_NONE) bg.setAlpha(0f);
		else start(bg, config.outAnim, config.outMs, 0, false, out(), getWidth(), getHeight(), null);
		longest = Math.max(longest, config.outMs);
		if (done != null) {
			pendingDone = done;
			postDelayed(done, longest + 30);
		}
	}

	/** Shows everything at rest at once (the preview). */
	public void showNow() {
		bg.animate().cancel();
		bg.setAlpha(1f);
		bg.setTranslationX(0f);
		bg.setTranslationY(0f);
		bg.setScaleX(1f);
		bg.setScaleY(1f);
		for (ElementView v : views) {
			v.animate().cancel();
			v.setAlpha(1f);
			v.setTranslationX(0f);
			v.setTranslationY(0f);
			v.setScaleX(1f);
			v.setScaleY(1f);
		}
	}

	/**
	 * Animates {@code v} from its rest state to {@code anim}'s offstage state or back: {@code entering} true = from offstage
	 * to rest. The offstage pose is a slide of a tenth of the screen (a whole screen for the background's slide), a zoom, or
	 * only a fade.
	 */
	private void start(View v, int anim, int ms, int delay, boolean entering, TimeInterpolator ip, int w, int h,
										 @Nullable Runnable end) {
		boolean isBg = v == bg;
		float dx = 0f, dy = 0f, sc = 1f;
		// The background only drifts a little and always fades: moved or scaled by a lot its hard edges would show.
		float slideX = isBg ? w * 0.06f : w * 0.12f;
		float slideY = isBg ? h * 0.05f : h * 0.10f;
		switch (anim) {
			case TransitionConfig.ANIM_UP -> dy = slideY;
			case TransitionConfig.ANIM_DOWN -> dy = -slideY;
			case TransitionConfig.ANIM_LEFT -> dx = slideX;
			case TransitionConfig.ANIM_RIGHT -> dx = -slideX;
			case TransitionConfig.ANIM_ZOOM_IN -> sc = isBg ? 0.94f : 0.6f;
			case TransitionConfig.ANIM_ZOOM_OUT -> sc = isBg ? 1.06f : 1.5f;
			default -> {
			}
		}
		// A background that slides carries its own opacity with it: fade only for the fade and zoom looks.
		boolean fades = anim != TransitionConfig.ANIM_NONE;
		if (entering) {
			v.setTranslationX(dx);
			v.setTranslationY(dy);
			v.setScaleX(sc);
			v.setScaleY(sc);
			v.setAlpha((fades || anim == TransitionConfig.ANIM_NONE) ? (anim == TransitionConfig.ANIM_NONE ? 1f : 0f) : 1f);
			if (anim == TransitionConfig.ANIM_NONE) return;
			v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(delay)
					.setDuration(ms).setInterpolator(ip).start();
		} else {
			// Leaving: toward the offstage pose (a slide leaves the way it came, so up in, down out is the other look).
			v.animate().translationX(-dx).translationY(-dy).scaleX(sc == 1f ? 1f : 2f - sc).scaleY(sc == 1f ? 1f : 2f - sc)
					.alpha(fades ? 0f : 1f).setStartDelay(0).setDuration(ms).setInterpolator(ip).start();
		}
	}

	// ---- Editing ----

	/** One "unit" of padding / corner / edge in pixels: a 400th of the screen's height, so the preview and the real screen match. */
	private float unitPx(int heightPx) {
		float h = (heightPx > 0) ? heightPx : ((getHeight() > 0) ? getHeight() : 400 * density);
		return Math.max(0.4f, h / 400f);
	}

	private float snap(float v, boolean horizontal) {
		if (Math.abs(v - 0.5f) < SNAP) {
			if (horizontal) guideX = true;
			else guideY = true;
			return 0.5f;
		}
		return v;
	}

	@Nullable
	private ElementView hit(float x, float y) {
		for (int i = views.size() - 1; i >= 0; i--) {
			ElementView v = views.get(i);
			if (v.shown() && (x >= v.getLeft()) && (x <= v.getRight()) && (y >= v.getTop()) && (y <= v.getBottom())) {
				return v;
			}
		}
		return null;
	}

	@Nullable
	private ElementView selectedView() {
		for (ElementView v : views) if (v.el.id == selectedId) return v;
		return null;
	}

	/** Which handle of the picked element is at the point: the corner (both sizes), the right edge (width), the bottom (height), or 0. */
	private int handleAt(ElementView v, float x, float y) {
		float r = density * 26;
		if ((Math.abs(x - v.getRight()) < r) && (Math.abs(y - v.getBottom()) < r)) return M_SIZE;
		if (!v.el.isBox()) return 0;
		float cx = (v.getLeft() + v.getRight()) / 2f;
		float cy = (v.getTop() + v.getBottom()) / 2f;
		if ((Math.abs(x - v.getRight()) < r) && (Math.abs(y - cy) < r)) return M_W;
		if ((Math.abs(x - cx) < r) && (Math.abs(y - v.getBottom()) < r)) return M_H;
		return 0;
	}

	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		if (editor == null) return super.onTouchEvent(ev);
		pinch.onTouchEvent(ev);
		if (ev.getPointerCount() > 1 || pinch.isInProgress()) {
			pinching = true;
			return true;
		}
		float x = ev.getX();
		float y = ev.getY();
		switch (ev.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				getParent().requestDisallowInterceptTouchEvent(true);
				pinching = false;
				moved = false;
				wasSelected = false;
				ElementView sel = selectedView();
				int handle = ((sel != null) && sel.shown()) ? handleAt(sel, x, y) : 0;
				if (handle != 0) {
					grabbed = sel;
					mode = handle;
				} else {
					ElementView h = hit(x, y);
					grabbed = h;
					mode = (h == null) ? M_NONE : M_DRAG;
					int id = (h == null) ? 0 : h.el.id;
					wasSelected = (id != 0) && (id == selectedId);
					if (id != selectedId) {
						selectedId = id;
						editor.onPicked(id);
						invalidate();
					}
				}
				downX = x;
				downY = y;
				if (grabbed != null) {
					startX = grabbed.el.x;
					startY = grabbed.el.y;
				}
				return true;
			}
			case MotionEvent.ACTION_MOVE -> {
				if (pinching || (grabbed == null) || (getWidth() == 0)) return true;
				if (!moved && (Math.abs(x - downX) < slop) && (Math.abs(y - downY) < slop)) return true;
				moved = true;
				Element e = grabbed.el;
				guideX = false;
				guideY = false;
				if (mode == M_DRAG) {
					e.x = Math.max(-0.5f, Math.min(1.5f, snap(startX + (x - downX) / getWidth(), true)));
					e.y = Math.max(-0.5f, Math.min(1.5f, snap(startY + (y - downY) / getHeight(), false)));
				} else {
					if ((mode == M_SIZE) || (mode == M_W)) {
						e.w = Math.max(0.06f, Math.min(e.isText() ? 1f : 3f, 2f * (x - e.x * getWidth()) / getWidth()));
					}
					if (e.isBox() && ((mode == M_SIZE) || (mode == M_H))) {
						e.h = Math.max((e.type == TransitionConfig.T_SHAPE) ? 0.001f : 0.03f, Math.min(1f, 2f * (y - e.y * getHeight()) / getHeight()));
					}
				}
				requestLayout();
				editor.onEdited(e, false);
				invalidate();
				return true;
			}
			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				if ((grabbed != null) && !pinching && !moved && wasSelected && (mode == M_DRAG) &&
						(ev.getActionMasked() == MotionEvent.ACTION_UP)) {
					editor.onTapSelected(grabbed.el.id);
				} else if ((grabbed != null) && !pinching && moved) editor.onEdited(grabbed.el, true);
				else if (pinching && (selected() != null)) editor.onEdited(selected(), true);
				grabbed = null;
				mode = M_NONE;
				pinching = false;
				guideX = false;
				guideY = false;
				invalidate();
				return true;
			}
			default -> {
				return true;
			}
		}
	}

	@Override
	protected void dispatchDraw(Canvas c) {
		super.dispatchDraw(c);
		if (editor == null) return;
		if (guideX) c.drawLine(getWidth() / 2f, 0, getWidth() / 2f, getHeight(), guidePaint);
		if (guideY) c.drawLine(0, getHeight() / 2f, getWidth(), getHeight() / 2f, guidePaint);
		ElementView v = selectedView();
		if ((v == null) || !v.shown()) return;
		guidePaint.setStyle(Paint.Style.STROKE);
		c.drawRoundRect(v.getLeft(), v.getTop(), v.getRight(), v.getBottom(), density * 4, density * 4, guidePaint);
		handlePaint.setColor(0xFF4FC3F7);
		c.drawCircle(v.getRight(), v.getBottom(), density * 9, handlePaint);
		handlePaint.setColor(0xFFFFFFFF);
		c.drawCircle(v.getRight(), v.getBottom(), density * 4, handlePaint);
		if (v.el.isBox()) {
			// The edges: width on the right, height at the bottom.
			float cx = (v.getLeft() + v.getRight()) / 2f;
			float cy = (v.getTop() + v.getBottom()) / 2f;
			handlePaint.setColor(0xFF4FC3F7);
			c.drawCircle(v.getRight(), cy, density * 6, handlePaint);
			c.drawCircle(cx, v.getBottom(), density * 6, handlePaint);
			handlePaint.setColor(0xFFFFFFFF);
			c.drawCircle(v.getRight(), cy, density * 2.5f, handlePaint);
			c.drawCircle(cx, v.getBottom(), density * 2.5f, handlePaint);
		}
	}

	// ---- Background layer ----

	private final class Bg extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		private final Matrix m = new Matrix();
		@Nullable
		private Bitmap src;
		@Nullable
		private Bitmap blurred;
		private int blurredFor = -1;

		Bg(Context ctx) {
			super(ctx);
		}

		void update() {
			Bitmap want = null;
			if (config.bg == TransitionConfig.BG_PICTURE) want = picture;
			else if ((config.bg == TransitionConfig.BG_ART) || (config.bg == TransitionConfig.BG_GLASS)) want = data.art;
			int strength = (config.bg == TransitionConfig.BG_GLASS) ? Math.max(60, config.blur) : config.blur;
			if ((want != src) || (strength != blurredFor)) {
				src = want;
				blurredFor = strength;
				blurred = (want == null) ? null : HomeBlur.blur(want, strength);
			}
			invalidate();
		}

		@Override
		protected void onDraw(Canvas c) {
			int w = getWidth();
			int h = getHeight();
			if (config.bg == TransitionConfig.BG_TRANSPARENT) return;
			Bitmap b = blurred;
			if (config.bg == TransitionConfig.BG_DIM) {
				c.drawColor(shade(config.dim));
				return;
			}
			if (b == null) {
				// No picture yet (or none): a deep gradient, so the words are still readable.
				paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF23262E, 0xFF0B0C10, Shader.TileMode.CLAMP));
				c.drawRect(0, 0, w, h, paint);
				paint.setShader(null);
			} else {
				float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight()) * (config.zoom / 100f);
				m.setScale(s, s);
				m.postTranslate((w - b.getWidth() * s) / 2f, (h - b.getHeight() * s) / 2f);
				c.drawBitmap(b, m, paint);
			}
			c.drawColor(shade(config.dim));
			if (config.bg == TransitionConfig.BG_GLASS) {
				// The frosted look: a pale veil, brighter at the top, and a fine rim at the edge.
				paint.setShader(new LinearGradient(0, 0, 0, h, 0x33FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP));
				c.drawRect(0, 0, w, h, paint);
				paint.setShader(null);
			}
		}
	}

	private static int shade(int percent) {
		return (Math.round(Math.max(0, Math.min(100, percent)) * 2.55f) << 24);
	}

	// ---- One element ----

	private final class ElementView extends View {
		final Element el;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final Path clip = new Path();
		private final Matrix m = new Matrix();
		@Nullable
		private StaticLayout layout;
		private boolean empty;

		ElementView(Context ctx, Element e) {
			super(ctx);
			el = e;
		}

		boolean shown() {
			return el.visible && !empty;
		}

		@Nullable
		private Bitmap pic;
		@Nullable
		private String picPath;

		/** The picked picture's file (stored shrunk when it was picked), read again only when the path changes. */
		private void loadPicture() {
			String path = el.text;
			if (java.util.Objects.equals(path, picPath)) return;
			picPath = path;
			pic = null;
			if ((path == null) || path.isEmpty()) return;
			try {
				pic = android.graphics.BitmapFactory.decodeFile(path);
			} catch (Throwable ex) {
				pic = null;
			}
		}

		void update() {
			invalidate();
		}

		@Nullable
		private String content() {
			String s = switch (el.type) {
				case TransitionConfig.T_TITLE -> data.title;
				case TransitionConfig.T_CHANNEL -> data.channel;
				case TransitionConfig.T_PLAYLIST -> data.playlist;
				case TransitionConfig.T_TEXT -> el.text;
				case TransitionConfig.T_NEXT -> (data.next == null) ? null :
						getContext().getString(me.aap.fermata.R.string.tr_next_fmt, data.next);
				case TransitionConfig.T_QUEUE -> data.queue;
				case TransitionConfig.T_SOURCE -> data.source;
				case TransitionConfig.T_QUALITY -> data.quality;
				case TransitionConfig.T_CLOCK -> TransitionFacts.clock(getContext(), el.opt);
				case TransitionConfig.T_INFO -> TransitionFacts.info(getContext(), el.text);
				default -> null;
			};
			if ((s == null) || s.isEmpty()) {
				if (!sample) return null;
				s = switch (el.type) {
					case TransitionConfig.T_NEXT -> getContext().getString(me.aap.fermata.R.string.tr_next_fmt,
							getContext().getString(me.aap.fermata.R.string.tr_sample_title));
					case TransitionConfig.T_QUEUE -> getContext().getString(me.aap.fermata.R.string.tr_queue_fmt, 3, 12);
					case TransitionConfig.T_SOURCE -> getContext().getString(me.aap.fermata.R.string.tr_source_youtube);
					case TransitionConfig.T_QUALITY -> "720p";
					case TransitionConfig.T_TITLE -> getContext().getString(me.aap.fermata.R.string.tr_sample_title);
					case TransitionConfig.T_CHANNEL -> getContext().getString(me.aap.fermata.R.string.tr_sample_channel);
					case TransitionConfig.T_PLAYLIST -> getContext().getString(me.aap.fermata.R.string.tr_sample_playlist);
					default -> getContext().getString(me.aap.fermata.R.string.tr_sample_text);
				};
			}
			return s;
		}

		private StaticLayout build(String s, float size, int width, int maxLines) {
			tp.setTextSize(size);
			Layout.Alignment al = (el.align == TransitionConfig.ALIGN_LEFT) ? Layout.Alignment.ALIGN_NORMAL :
					(el.align == TransitionConfig.ALIGN_RIGHT) ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_CENTER;
			StaticLayout.Builder b = StaticLayout.Builder.obtain(s, 0, s.length(), tp, width).setAlignment(al)
					.setMaxLines(maxLines).setIncludePad(false);
			if (el.ellipsis) b.setEllipsize(TextUtils.TruncateAt.END);
			return b.build();
		}

		/** Whether all of {@code s} is shown and the lines are no taller than {@code innerH}. */
		private boolean fits(StaticLayout lay, String s, int innerH) {
			int n = lay.getLineCount();
			boolean cut = (n > 0) && ((lay.getEllipsisCount(n - 1) > 0) || (lay.getLineEnd(n - 1) < s.length()));
			return !cut && (lay.getHeight() <= innerH);
		}

		void measureIn(int W, int H) {
			empty = false;
			if (!el.visible) {
				empty = true;
				setVisibility(View.GONE);
				measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY));
				return;
			}
			int boxW = Math.max(1, Math.round(el.w * W));
			int boxH;
			if (el.type == TransitionConfig.T_ART) {
				if ((data.art == null) && !sample) empty = true;
				boxH = boxW;
			} else if (el.type == TransitionConfig.T_PICTURE) {
				loadPicture();
				if ((pic == null) && !sample) empty = true;
				boxH = (pic == null) ? boxW : Math.max(1, Math.round(boxW * pic.getHeight() / (float) pic.getWidth()));
			} else if (el.type == TransitionConfig.T_SHAPE) {
				boxH = Math.max(1, Math.round(el.h * H));
			} else {
				String s = content();
				if (s == null) {
					empty = true;
					boxH = 0;
				} else {
					int pad = Math.round(el.pad * unitPx(H));
					int innerW = Math.max(1, boxW - 2 * pad);
					boolean fixedH = el.h > 0f;
					int fixedBox = Math.max(1, Math.round(el.h * H));
					int innerH = fixedH ? Math.max(1, fixedBox - 2 * pad) : Integer.MAX_VALUE;
					int style = (el.bold ? Typeface.BOLD : 0) | (el.italic ? Typeface.ITALIC : 0);
					tp.setColor(el.color);
					tp.setTypeface(TransitionFonts.get(getContext(), el.font, el.bold, el.italic));
					tp.setLetterSpacing(el.spacing / 100f);
					if (el.caps) s = s.toUpperCase();
					int maxLines = el.wrap ? Math.max(1, el.lines) : 1;
					float base = el.size * H;
					float size = base;
					StaticLayout lay = build(s, size, innerW, maxLines);
					if (el.autoFit) {
						// Shrinks until the words fit the box (their width, and the height when it is set).
						float min = Math.max(5f * density, base * 0.25f);
						for (int i = 0; (i < 16) && !fits(lay, s, innerH) && (size > min); i++) {
							size = Math.max(min, size * 0.9f);
							lay = build(s, size, innerW, maxLines);
						}
					} else if (fixedH) {
						// Only the lines that fit the height.
						int n = Math.max(1, lay.getLineCount());
						int lh = Math.max(1, lay.getHeight() / n);
						int allowed = Math.max(1, innerH / lh);
						if (allowed < maxLines) lay = build(s, size, innerW, allowed);
					}
					layout = lay;
					boxH = fixedH ? fixedBox : lay.getHeight() + 2 * pad;
				}
			}
			setVisibility(empty ? View.GONE : View.VISIBLE);
			measure(MeasureSpec.makeMeasureSpec(empty ? 0 : boxW, MeasureSpec.EXACTLY),
					MeasureSpec.makeMeasureSpec(empty ? 0 : Math.max(1, boxH), MeasureSpec.EXACTLY));
		}

		@Override
		protected void onDraw(Canvas c) {
			if (empty) return;
			int w = getWidth();
			int h = getHeight();
			boolean art = (el.type == TransitionConfig.T_ART) || (el.type == TransitionConfig.T_PICTURE);
			boolean shape = el.type == TransitionConfig.T_SHAPE;
			float u = unitPx(0);
			float r = art ? Math.min(w, h) * el.radius / 100f : el.radius * u;
			r = Math.min(r, Math.min(w, h) / 2f);
			rect.set(0, 0, w, h);

			// The fill behind: a colour, or the frosted look (a pale veil with a sheen and a rim).
			if (el.fill == TransitionConfig.FILL_SOLID) {
				paint.setStyle(Paint.Style.FILL);
				paint.setColor((el.fillColor & 0x00FFFFFF) | (Math.round(el.fillAlpha * 2.55f) << 24));
				c.drawRoundRect(rect, r, r, paint);
			} else if (el.fill == TransitionConfig.FILL_GLASS) {
				int tint = (el.fillColor & 0x00FFFFFF) | (Math.round(el.fillAlpha * 2.55f * 0.6f) << 24);
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(tint);
				c.drawRoundRect(rect, r, r, paint);
				paint.setShader(new LinearGradient(0, 0, 0, h, 0x40FFFFFF, 0x08FFFFFF, Shader.TileMode.CLAMP));
				c.drawRoundRect(rect, r, r, paint);
				paint.setShader(null);
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(density);
				paint.setColor(0x55FFFFFF);
				RectF in = new RectF(rect);
				in.inset(density / 2f, density / 2f);
				c.drawRoundRect(in, r, r, paint);
				paint.setStyle(Paint.Style.FILL);
			}

			if (art) {
				float inset = el.pad * u;
				RectF a = new RectF(inset, inset, w - inset, h - inset);
				float ar = Math.max(0f, r - inset);
				Bitmap b = (el.type == TransitionConfig.T_PICTURE) ? pic : data.art;
				if (b != null) {
					clip.reset();
					clip.addRoundRect(a, ar, ar, Path.Direction.CW);
					c.save();
					c.clipPath(clip);
					float s = Math.max(a.width() / b.getWidth(), a.height() / b.getHeight());
					m.setScale(s, s);
					m.postTranslate(a.left + (a.width() - b.getWidth() * s) / 2f, a.top + (a.height() - b.getHeight() * s) / 2f);
					c.drawBitmap(b, m, paint);
					c.restore();
				} else {
					paint.setShader(new LinearGradient(a.left, a.top, a.right, a.bottom, 0xFF6C63FF, 0xFFFF6584, Shader.TileMode.CLAMP));
					c.drawRoundRect(a, ar, ar, paint);
					paint.setShader(null);
				}
			} else if (shape) {
				// Nothing but its fill and edge.
			} else if (layout != null) {
				c.save();
				float pad = el.pad * u;
				float room = h - 2 * pad;
				c.clipRect(pad, pad, w - pad, h - pad);
				// A box taller than its text: the text sits in the middle of it.
				c.translate(pad, pad + Math.max(0f, (room - layout.getHeight()) / 2f));
				layout.draw(c);
				c.restore();
			}

			if (el.edge > 0) {
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(Math.max(1f, el.edge * u));
				paint.setColor(el.edgeColor);
				RectF in = new RectF(rect);
				float hw = Math.max(1f, el.edge * u) / 2f;
				in.inset(hw, hw);
				c.drawRoundRect(in, Math.max(0, r - hw), Math.max(0, r - hw), paint);
				paint.setStyle(Paint.Style.FILL);
			}
		}
	}
}
