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
	}

	public interface Editor {
		/** An element was picked by a touch (0 = none). */
		void onPicked(int elementId);

		/** An element was moved or sized; {@code done} once the finger lifts. */
		void onEdited(Element e, boolean done);
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
	private static final int M_NONE = 0, M_DRAG = 1, M_SIZE = 2;
	private int mode;
	private ElementView grabbed;
	private float downX, downY, startX, startY;
	private boolean pinching;

	public TransitionView(@NonNull Context ctx, @NonNull TransitionConfig config) {
		super(ctx);
		density = ctx.getResources().getDisplayMetrics().density;
		this.config = config;
		setWillNotDraw(false);
		setClipChildren(false);
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

	/** Brings the background and the elements in, each with its own animation and delay. */
	public void playIn() {
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
	}

	/** Takes them out again and calls {@code done} when the last has left. */
	public void playOut(@Nullable Runnable done) {
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
		if (done != null) postDelayed(done, longest + 30);
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
		float slideX = isBg ? w : w * 0.12f;
		float slideY = isBg ? h : h * 0.10f;
		switch (anim) {
			case TransitionConfig.ANIM_UP -> dy = slideY;
			case TransitionConfig.ANIM_DOWN -> dy = -slideY;
			case TransitionConfig.ANIM_LEFT -> dx = slideX;
			case TransitionConfig.ANIM_RIGHT -> dx = -slideX;
			case TransitionConfig.ANIM_ZOOM_IN -> sc = 0.6f;
			case TransitionConfig.ANIM_ZOOM_OUT -> sc = 1.5f;
			default -> {
			}
		}
		// A background that slides carries its own opacity with it: fade only for the fade and zoom looks.
		boolean fades = (anim != TransitionConfig.ANIM_NONE) && (!isBg || (anim == TransitionConfig.ANIM_FADE) ||
				(anim == TransitionConfig.ANIM_ZOOM_IN) || (anim == TransitionConfig.ANIM_ZOOM_OUT));
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

	private boolean onHandle(ElementView v, float x, float y) {
		float r = density * 26;
		return (Math.abs(x - v.getRight()) < r) && (Math.abs(y - v.getBottom()) < r);
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
				ElementView sel = selectedView();
				if ((sel != null) && sel.shown() && onHandle(sel, x, y)) {
					grabbed = sel;
					mode = M_SIZE;
				} else {
					ElementView h = hit(x, y);
					grabbed = h;
					mode = (h == null) ? M_NONE : M_DRAG;
					int id = (h == null) ? 0 : h.el.id;
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
				Element e = grabbed.el;
				guideX = false;
				guideY = false;
				if (mode == M_DRAG) {
					e.x = Math.max(0f, Math.min(1f, snap(startX + (x - downX) / getWidth(), true)));
					e.y = Math.max(0f, Math.min(1f, snap(startY + (y - downY) / getHeight(), false)));
				} else if (mode == M_SIZE) {
					e.w = Math.max(0.06f, Math.min(1f, 2f * (x - e.x * getWidth()) / getWidth()));
				}
				requestLayout();
				editor.onEdited(e, false);
				invalidate();
				return true;
			}
			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				if ((grabbed != null) && !pinching) editor.onEdited(grabbed.el, true);
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
				float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
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
				default -> null;
			};
			if ((s == null) || s.isEmpty()) {
				if (!sample) return null;
				s = switch (el.type) {
					case TransitionConfig.T_TITLE -> getContext().getString(me.aap.fermata.R.string.tr_sample_title);
					case TransitionConfig.T_CHANNEL -> getContext().getString(me.aap.fermata.R.string.tr_sample_channel);
					case TransitionConfig.T_PLAYLIST -> getContext().getString(me.aap.fermata.R.string.tr_sample_playlist);
					default -> getContext().getString(me.aap.fermata.R.string.tr_sample_text);
				};
			}
			return s;
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
				boxW = Math.min(boxW, Math.round(H * 0.95f));
				boxH = boxW;
			} else {
				String s = content();
				if (s == null) {
					empty = true;
					boxH = 0;
				} else {
					int pad = Math.round(el.pad * density);
					tp.setTextSize(el.size * H);
					tp.setColor(el.color);
					int style = (el.bold ? Typeface.BOLD : 0) | (el.italic ? Typeface.ITALIC : 0);
					tp.setTypeface(Typeface.create(Typeface.create(TransitionConfig.FONT_FAMILIES[el.font], Typeface.NORMAL), style));
					int inner = Math.max(1, boxW - 2 * pad);
					Layout.Alignment al = (el.align == TransitionConfig.ALIGN_LEFT) ? Layout.Alignment.ALIGN_NORMAL :
							(el.align == TransitionConfig.ALIGN_RIGHT) ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_CENTER;
					layout = StaticLayout.Builder.obtain(s, 0, s.length(), tp, inner).setAlignment(al)
							.setEllipsize(TextUtils.TruncateAt.END).setMaxLines(el.type == TransitionConfig.T_TITLE ? 3 : 2)
							.setIncludePad(false).build();
					boxH = layout.getHeight() + 2 * pad;
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
			boolean art = el.type == TransitionConfig.T_ART;
			float r = art ? Math.min(w, h) * el.radius / 100f : el.radius * density;
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
				float inset = el.pad * density;
				RectF a = new RectF(inset, inset, w - inset, h - inset);
				float ar = Math.max(0f, r - inset);
				Bitmap b = data.art;
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
			} else if (layout != null) {
				c.save();
				float pad = el.pad * density;
				c.translate(pad, pad);
				layout.draw(c);
				c.restore();
			}

			if (el.edge > 0) {
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(el.edge * density);
				paint.setColor(el.edgeColor);
				RectF in = new RectF(rect);
				float hw = el.edge * density / 2f;
				in.inset(hw, hw);
				c.drawRoundRect(in, Math.max(0, r - hw), Math.max(0, r - hw), paint);
				paint.setStyle(Paint.Style.FILL);
			}
		}
	}
}
