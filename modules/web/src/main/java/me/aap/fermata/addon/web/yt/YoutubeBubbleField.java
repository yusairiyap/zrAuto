package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The YouTube suggestions tab's playground: a rounded thumbnail card per video, big title over the
 * picture, drifting slowly around the screen like bubbles -- bouncing softly off the edges and off
 * one another, bobbing a little, and standing still under a finger so one is easy to tap.
 * <p>
 * The cards are sized to fill the room the videos are given (bigger with fewer of them, and never
 * too small to tap, more so on the car's screen). Motion runs off the choreographer while
 * {@link #setRunning running}, at a speed the user sets.
 */
final class YoutubeBubbleField extends FrameLayout implements Choreographer.FrameCallback {
	interface Listener {
		void onBubbleClick(YoutubeFeed.Video video);

		void onBubbleLongClick(YoutubeFeed.Video video);
	}

	private final List<Bubble> bubbles = new ArrayList<>();
	private final Random rnd = new Random();
	private final boolean car;
	@Nullable
	private Listener listener;
	private float speed = 1f;
	/** Titles and channel over the cards; and the thumbnails, or else one-line text cards. */
	private boolean showText = true;
	private boolean showThumbs = true;
	private int insetTop;
	private int insetBottom;
	private boolean running;
	private boolean scatterPending;
	private long lastNanos;
	private float clock;

	YoutubeBubbleField(Context ctx, boolean car) {
		super(ctx);
		this.car = car;
		setClipChildren(false);
		setClipToPadding(false);
	}

	void setListener(@Nullable Listener l) {
		listener = l;
	}

	/** What the cards show; applies to the ones made after this, see setVideos(). */
	void setOptions(boolean text, boolean thumbs) {
		showThumbs = thumbs;
		// Without thumbnails the title is all there is.
		showText = text || !thumbs;
	}

	/** 1 is the normal drift; 0.5 half of that, 2 twice as fast. */
	void setSpeed(float s) {
		speed = Math.max(0.2f, Math.min(3f, s));
	}

	/** The room to keep clear of the floating bars, top and bottom. */
	void setInsets(int top, int bottom) {
		if ((top == insetTop) && (bottom == insetBottom)) return;
		insetTop = top;
		insetBottom = bottom;
		relayout(false);
	}

	private int dp(int v) {
		return toIntPx(getContext(), v);
	}

	// ---------------------------------------------------------------- content

	/** Replaces all the bubbles with these videos, popping in one after the other. */
	void setVideos(List<YoutubeFeed.Video> videos) {
		removeAllViews();
		bubbles.clear();
		for (YoutubeFeed.Video v : videos) {
			Bubble b = new Bubble(getContext(), v);
			bubbles.add(b);
			addView(b, new LayoutParams(dp(200), dp(112)));
		}
		scatterPending = true;
		relayout(true);
	}

	/** Throws the bubbles about again: every one to a new place, going a new way. */
	void shuffle() {
		scatterPending = true;
		relayout(true);
	}

	boolean isEmpty() {
		return bubbles.isEmpty();
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		relayout(scatterPending);
	}

	/**
	 * Sizes every bubble to the room there is and, for {@code scatter} (or a bubble not yet
	 * placed), puts them at random places going random ways; otherwise only keeps them inside.
	 */
	private void relayout(boolean scatter) {
		int w = getWidth();
		int h = getHeight();
		int n = bubbles.size();
		if ((w <= 0) || (h <= 0) || (n == 0)) return;

		float top = insetTop;
		float bottom = h - insetBottom;
		if (bottom - top < dp(160)) {
			top = 0;
			bottom = h;
		}
		float area = w * (bottom - top);
		float fill = car ? 0.52f : 0.44f;
		float base = (float) Math.sqrt(area * fill / (n * 0.5625f));
		float minW = dp(car ? 220 : 160);
		float maxW = Math.min(w * 0.72f, dp(car ? 420 : 320));
		base = Math.max(minW, Math.min(maxW, base));

		for (int i = 0; i < n; i++) {
			Bubble b = bubbles.get(i);
			int bw = Math.min(w, Math.round(base * b.sizeFactor));
			int bh = Math.round(bw * 9f / 16f);
			if (!showThumbs) {
				// One line of text in a wide pill with generous padding: the text as big as the room
				// allows, the pill as wide as the text.
				float ts = Math.max(dp(car ? 22 : 18), Math.min(dp(car ? 34 : 26), base * 0.13f));
				b.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, ts);
				float tw = b.title.getPaint().measureText(b.video.title);
				bh = Math.round(ts * 3.0f);
				bw = Math.min(Math.round(w * 0.92f), Math.round(tw + ts * 3.2f));
				bw = Math.max(bw, Math.round(ts * 6f));
			}
			if ((b.w != bw) || (b.h != bh)) {
				b.w = bw;
				b.h = bh;
				LayoutParams lp = (LayoutParams) b.getLayoutParams();
				lp.width = bw;
				lp.height = bh;
				b.setLayoutParams(lp);
				if (showThumbs) b.fitText();
				b.invalidateOutline();
			}
			if (scatter || !b.placed) place(b, i, top, bottom);
			else clamp(b, top, bottom);
		}
		scatterPending = false;
		apply();
	}

	private void place(Bubble b, int index, float top, float bottom) {
		int w = getWidth();
		float x = 0, y = 0;
		// A few tries at a spot clear of the ones already placed; the drift sorts out the rest.
		for (int attempt = 0; attempt < 40; attempt++) {
			x = rnd.nextFloat() * Math.max(1, w - b.w);
			y = top + rnd.nextFloat() * Math.max(1, bottom - top - b.h);
			boolean free = true;
			for (int j = 0; j < index; j++) {
				Bubble o = bubbles.get(j);
				if ((x < o.x + o.w) && (x + b.w > o.x) && (y < o.y + o.h) && (y + b.h > o.y)) {
					free = false;
					break;
				}
			}
			if (free) break;
		}
		b.x = x;
		b.y = y;
		double angle = rnd.nextDouble() * Math.PI * 2;
		float sp = dp(14) + rnd.nextFloat() * dp(20);
		b.vx = (float) Math.cos(angle) * sp;
		b.vy = (float) Math.sin(angle) * sp;
		b.placed = true;
	}

	private void clamp(Bubble b, float top, float bottom) {
		b.x = Math.max(0, Math.min(getWidth() - b.w, b.x));
		b.y = Math.max(top, Math.min(bottom - b.h, b.y));
	}

	// ---------------------------------------------------------------- motion

	void setRunning(boolean run) {
		if (running == run) return;
		running = run;
		if (run) {
			lastNanos = 0;
			Choreographer.getInstance().postFrameCallback(this);
		} else {
			Choreographer.getInstance().removeFrameCallback(this);
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		setRunning(false);
		super.onDetachedFromWindow();
	}

	@Override
	public void doFrame(long nanos) {
		if (!running) return;
		float dt = (lastNanos == 0) ? 0.016f : Math.min(0.05f, (nanos - lastNanos) / 1e9f);
		lastNanos = nanos;
		clock += dt;
		step(dt);
		apply();
		Choreographer.getInstance().postFrameCallback(this);
	}

	private void step(float dt) {
		int w = getWidth();
		int h = getHeight();
		if ((w <= 0) || (h <= 0)) return;
		float top = insetTop;
		float bottom = h - insetBottom;
		if (bottom - top < dp(160)) {
			top = 0;
			bottom = h;
		}
		float minSpeed = dp(11);
		int n = bubbles.size();

		for (int i = 0; i < n; i++) {
			Bubble b = bubbles.get(i);
			if (b.held) continue;
			b.x += b.vx * speed * dt;
			b.y += b.vy * speed * dt;
			if (b.x < 0) {
				b.x = 0;
				b.vx = Math.abs(b.vx);
			} else if (b.x + b.w > w) {
				b.x = w - b.w;
				b.vx = -Math.abs(b.vx);
			}
			if (b.y < top) {
				b.y = top;
				b.vy = Math.abs(b.vy);
			} else if (b.y + b.h > bottom) {
				b.y = bottom - b.h;
				b.vy = -Math.abs(b.vy);
			}
			// Never quite stopping: a collision can leave one nearly at rest.
			float sp = (float) Math.hypot(b.vx, b.vy);
			if (sp < minSpeed) {
				if (sp < 1e-3f) {
					b.vx = minSpeed;
				} else {
					b.vx *= minSpeed / sp;
					b.vy *= minSpeed / sp;
				}
			}
		}

		// Bubbles that meet push apart along whichever way they overlap least, and trade the speed
		// they had that way -- a soft bounce.
		for (int i = 0; i < n; i++) {
			Bubble a = bubbles.get(i);
			for (int j = i + 1; j < n; j++) {
				Bubble b = bubbles.get(j);
				float ox = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
				float oy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
				if ((ox <= 0) || (oy <= 0)) continue;
				boolean horizontal = ox < oy;
				float shift = (horizontal ? ox : oy) / 2f;
				boolean aFirst = horizontal ? (a.x + a.w / 2f < b.x + b.w / 2f)
						: (a.y + a.h / 2f < b.y + b.h / 2f);
				float sa = a.held ? 0 : (b.held ? shift * 2 : shift);
				float sb = b.held ? 0 : (a.held ? shift * 2 : shift);
				if (horizontal) {
					a.x += aFirst ? -sa : sa;
					b.x += aFirst ? sb : -sb;
					if (aFirst ? (a.vx > b.vx) : (a.vx < b.vx)) {
						float t = a.vx;
						if (!a.held) a.vx = b.vx;
						if (!b.held) b.vx = t;
					}
				} else {
					a.y += aFirst ? -sa : sa;
					b.y += aFirst ? sb : -sb;
					if (aFirst ? (a.vy > b.vy) : (a.vy < b.vy)) {
						float t = a.vy;
						if (!a.held) a.vy = b.vy;
						if (!b.held) b.vy = t;
					}
				}
			}
		}
	}

	private void apply() {
		float bob = dp(3);
		for (int i = 0; i < bubbles.size(); i++) {
			Bubble b = bubbles.get(i);
			b.setTranslationX(b.x);
			b.setTranslationY(b.y + (float) Math.sin(clock * 0.9f + b.phase) * bob);
		}
	}

	// ---------------------------------------------------------------- one bubble

	private final class Bubble extends FrameLayout {
		final YoutubeFeed.Video video;
		final float sizeFactor = 0.86f + rnd.nextFloat() * 0.28f;
		final float phase = rnd.nextFloat() * 6.28f;
		final ImageView image;
		final TextView title;
		final TextView channel;
		float x, y, vx, vy;
		int w, h;
		boolean placed;
		boolean held;
		boolean dragging;
		float downRawX, downRawY, lastRawX, lastRawY, startX, startY, flingX, flingY;
		long lastT;

		@SuppressLint("ClickableViewAccessibility")
		Bubble(Context ctx, YoutubeFeed.Video video) {
			super(ctx);
			this.video = video;
			final int radius = dp(32);
			setClipToOutline(true);
			setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View v, Outline o) {
					o.setRoundRect(0, 0, v.getWidth(), v.getHeight(),
							showThumbs ? radius : v.getHeight() / 2f);
				}
			});
			setElevation(dp(10));
			setFocusable(true);
			setClickable(true);
			setLongClickable(true);
			setContentDescription(video.title);

			image = new ImageView(ctx);
			image.setScaleType(ImageView.ScaleType.CENTER_CROP);
			int hue = Math.abs(video.id.hashCode()) % 360;
			image.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
					new int[]{Color.HSVToColor(new float[]{hue, 0.55f, 0.55f}),
							Color.HSVToColor(new float[]{(hue + 40) % 360, 0.7f, 0.25f})}));
			addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

			// Darkest at the bottom, under the title -- like a Favorites card's fade, but taller so a
			// big title reads over any thumbnail.
			View fade = new View(ctx);
			fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
					new int[]{0xF0000000, 0xB0000000, 0x00000000}));
			if (!showThumbs) fade.setVisibility(GONE);
			addView(fade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

			LinearLayout texts = new LinearLayout(ctx);
			texts.setOrientation(LinearLayout.VERTICAL);
			texts.setPadding(dp(14), dp(10), dp(14), dp(12));
			if (!showThumbs) {
				texts.setPadding(dp(24), 0, dp(24), 0);
				texts.setGravity(Gravity.CENTER_VERTICAL);
				addView(texts, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
			} else {
				addView(texts, new LayoutParams(LayoutParams.MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM));
			}
			if (!showText) texts.setVisibility(GONE);

			title = new TextView(ctx);
			title.setText(video.title);
			title.setTextColor(Color.WHITE);
			title.setTypeface(Typeface.DEFAULT_BOLD);
			title.setMaxLines(showThumbs ? 3 : 1);
			title.setGravity(showThumbs ? Gravity.START : Gravity.CENTER);
			title.setEllipsize(TextUtils.TruncateAt.END);
			title.setShadowLayer(4f, 0f, 1.5f, 0xC0000000);
			title.setLineSpacing(0f, 0.95f);
			texts.addView(title);

			channel = new TextView(ctx);
			channel.setText((video.channel == null) ? "" : video.channel);
			channel.setTextColor(0xCCFFFFFF);
			channel.setSingleLine(true);
			channel.setEllipsize(TextUtils.TruncateAt.END);
			channel.setShadowLayer(3f, 0f, 1f, 0xB0000000);
			channel.setVisibility(((video.channel == null) || !showThumbs) ? GONE : VISIBLE);
			texts.addView(channel);

			setOnClickListener(v -> {
				if (listener != null) listener.onBubbleClick(video);
			});
			setOnLongClickListener(v -> {
				if (listener == null) return false;
				listener.onBubbleLongClick(video);
				return true;
			});
			// Stands still and rises a little while touched (or focused, with a rotary controller).
			// A tap plays it; dragging moves it about, and letting go sends it off at the speed the
			// finger had.
			final float slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
			setOnTouchListener((v, e) -> {
				switch (e.getActionMasked()) {
					case MotionEvent.ACTION_DOWN -> {
						hold(true);
						downRawX = lastRawX = e.getRawX();
						downRawY = lastRawY = e.getRawY();
						startX = x;
						startY = y;
						lastT = e.getEventTime();
						flingX = flingY = 0;
						dragging = false;
						return false;
					}
					case MotionEvent.ACTION_MOVE -> {
						float dx = e.getRawX() - downRawX;
						float dy = e.getRawY() - downRawY;
						if (!dragging && (Math.hypot(dx, dy) > slop)) {
							dragging = true;
							cancelLongPress();
							setPressed(false);
							if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
						}
						if (!dragging) return false;
						long t = e.getEventTime();
						float dt = Math.max(1, t - lastT) / 1000f;
						flingX = 0.6f * flingX + 0.4f * ((e.getRawX() - lastRawX) / dt);
						flingY = 0.6f * flingY + 0.4f * ((e.getRawY() - lastRawY) / dt);
						lastRawX = e.getRawX();
						lastRawY = e.getRawY();
						lastT = t;
						x = Math.max(0, Math.min(YoutubeBubbleField.this.getWidth() - w, startX + dx));
						y = Math.max(0, Math.min(YoutubeBubbleField.this.getHeight() - h, startY + dy));
						return true;
					}
					case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						boolean was = dragging;
						dragging = false;
						hold(false);
						if (was) {
							// Thrown: the drift continues the way it was let go, never faster than a
							// brisk glide (the speed setting scales it on top of that).
							float cap = dp(car ? 260 : 220);
							float sp = (float) Math.hypot(flingX, flingY);
							float k = (sp > cap) ? cap / sp : 1f;
							vx = flingX * k / speed;
							vy = flingY * k / speed;
						}
						return was;
					}
					default -> {
					}
				}
				return false;
			});
			setOnFocusChangeListener((v, focus) -> hold(focus));

			Bitmap cached = showThumbs ? YoutubeFeed.cachedThumbnail(video) : null;
			if (!showThumbs) {
				// Just the colour: no thumbnail is loaded at all.
			} else if (cached != null) {
				image.setImageBitmap(cached);
			} else {
				image.setAlpha(1f);
				YoutubeFeed.loadThumbnail(video, bm -> {
					if ((bm == null) || !isAttachedToWindow()) return;
					image.setImageBitmap(bm);
					image.setAlpha(0f);
					image.animate().alpha(1f).setDuration(300).start();
				});
			}

			// Pops in.
			setAlpha(0f);
			setScaleX(0.5f);
			setScaleY(0.5f);
			animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
					.setStartDelay(Math.min(1200, bubbles.size() * 80L)).setInterpolator(
							new OvershootInterpolator(1.2f)).start();
		}

		private void hold(boolean on) {
			held = on;
			animate().cancel();
			animate().scaleX(on ? 1.07f : 1f).scaleY(on ? 1.07f : 1f).alpha(1f).setStartDelay(0)
					.setDuration(140).start();
			setElevation(dp(on ? 22 : 10));
		}

		/** Text sized to the card: big enough to read across a car's dashboard. */
		void fitText() {
			title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(car ? 20 : 16), w * 0.105f));
			channel.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(car ? 14 : 11), w * 0.058f));
		}

		@Override
		public boolean hasOverlappingRendering() {
			return false;
		}
	}
}
