package me.aap.fermata.ui.transition;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;

/**
 * Small pictures that show what an option does, for the tiles in the transition settings: the animations
 * actually play (looping, only while on screen), the backgrounds and fills are drawn as the screen would
 * draw them. Everything is drawn in code; nothing is allocated while drawing except the gradients.
 */
public final class TransitionDemos {
	private TransitionDemos() {
	}

	/** A looping demo of one animation: {@code entering} the card comes in, else it goes out. */
	public static View anim(Context c, int anim, boolean entering, int accent) {
		return new AnimDemo(c, anim, entering, accent);
	}

	/** A background mode drawn as a tiny screen (see {@link TransitionConfig#BG_DIM} and the others). */
	public static View background(Context c, int mode) {
		return new BgDemo(c, mode);
	}

	/** A fill style drawn as a tiny label over a dark scene. */
	public static View fill(Context c, int fill, int accent) {
		return new FillDemo(c, fill, accent);
	}

	/** "Aa" in a font of the set. */
	public static View font(Context c, int font, int color) {
		android.widget.TextView t = new android.widget.TextView(c);
		t.setText("Aa");
		t.setTextSize(24);
		t.setTextColor(color);
		t.setGravity(android.view.Gravity.CENTER);
		t.setTypeface(Typeface.create(TransitionConfig.FONT_FAMILIES[font], Typeface.NORMAL));
		return t;
	}

	/** Three lines aligned left, centre or right. */
	public static View align(Context c, int align, int color) {
		return new AlignDemo(c, align, color);
	}

	private static float dp(Context c, float v) {
		return v * c.getResources().getDisplayMetrics().density;
	}

	// ---- Animation ----

	private static final class AnimDemo extends View {
		private final int anim;
		private final boolean entering;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF r = new RectF();
		private final int accent;
		private ValueAnimator loop;
		private float t;

		AnimDemo(Context c, int anim, boolean entering, int accent) {
			super(c);
			this.anim = anim;
			this.entering = entering;
			this.accent = accent;
		}

		@Override
		protected void onAttachedToWindow() {
			super.onAttachedToWindow();
			start();
		}

		@Override
		protected void onDetachedFromWindow() {
			stop();
			super.onDetachedFromWindow();
		}

		@Override
		public void onVisibilityAggregated(boolean visible) {
			super.onVisibilityAggregated(visible);
			if (visible && isAttachedToWindow()) start();
			else stop();
		}

		private void start() {
			if (loop != null) return;
			loop = ValueAnimator.ofFloat(0f, 1f);
			loop.setDuration(2200);
			loop.setRepeatCount(ValueAnimator.INFINITE);
			loop.setInterpolator(new LinearInterpolator());
			loop.addUpdateListener(a -> {
				t = (float) a.getAnimatedValue();
				invalidate();
			});
			loop.start();
		}

		private void stop() {
			if (loop == null) return;
			loop.cancel();
			loop = null;
		}

		@Override
		protected void onDraw(@NonNull Canvas cv) {
			float w = getWidth();
			float h = getHeight();
			if (w <= 0) return;
			// How far along the move is: in = comes in then stays, out = stays then leaves.
			float k;
			if (entering) k = (t < 0.4f) ? 1f - (1f - t / 0.4f) * (1f - t / 0.4f) : 1f;
			else k = (t < 0.45f) ? 1f : (t < 0.85f) ? 1f - (float) Math.pow((t - 0.45f) / 0.4f, 1.6) : 0f;
			if (t > 0.92f) k = entering ? 1f : 0f;

			float dx = 0, dy = 0, sc = 1;
			switch (anim) {
				case TransitionConfig.ANIM_UP -> dy = h * 0.55f;
				case TransitionConfig.ANIM_DOWN -> dy = -h * 0.55f;
				case TransitionConfig.ANIM_LEFT -> dx = w * 0.5f;
				case TransitionConfig.ANIM_RIGHT -> dx = -w * 0.5f;
				case TransitionConfig.ANIM_ZOOM_IN -> sc = 0.4f;
				case TransitionConfig.ANIM_ZOOM_OUT -> sc = 1.7f;
				default -> {
				}
			}
			float tx, ty, s, a;
			if (entering) {
				tx = dx * (1 - k);
				ty = dy * (1 - k);
				s = sc + (1 - sc) * k;
				a = (anim == TransitionConfig.ANIM_NONE) ? (t < 0.2f ? 0f : 1f) : k;
			} else {
				tx = -dx * (1 - k);
				ty = -dy * (1 - k);
				s = (2 - sc) + (1 - (2 - sc)) * k;
				a = (anim == TransitionConfig.ANIM_NONE) ? (t < 0.6f ? 1f : 0f) : k;
			}
			float cw = w * 0.62f;
			float ch = h * 0.5f;
			cv.save();
			cv.clipRect(0, 0, w, h);
			cv.translate(w / 2f + tx, h / 2f + ty);
			cv.scale(s, s);
			r.set(-cw / 2, -ch / 2, cw / 2, ch / 2);
			float rad = dp(getContext(), 6);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(accent);
			paint.setAlpha(Math.round(255 * Math.max(0, Math.min(1, a))));
			cv.drawRoundRect(r, rad, rad, paint);
			paint.setColor(0xFFFFFFFF);
			paint.setAlpha(Math.round(200 * Math.max(0, Math.min(1, a))));
			float m = dp(getContext(), 5);
			cv.drawRoundRect(r.left + m, r.top + m, r.right - m, r.top + m + dp(getContext(), 4), 2, 2, paint);
			cv.drawRoundRect(r.left + m, r.top + m + dp(getContext(), 9), r.right - m * 2.5f, r.top + m + dp(getContext(), 13), 2, 2, paint);
			cv.restore();
		}
	}

	// ---- Background ----

	private static final class BgDemo extends View {
		private final int mode;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path clip = new Path();

		BgDemo(Context c, int mode) {
			super(c);
			this.mode = mode;
		}

		@Override
		protected void onDraw(@NonNull Canvas cv) {
			float w = getWidth();
			float h = getHeight();
			if (w <= 0) return;
			float d = dp(getContext(), 1);
			clip.reset();
			clip.addRoundRect(new RectF(0, 0, w, h), 8 * d, 8 * d, Path.Direction.CW);
			cv.save();
			cv.clipPath(clip);
			// The app behind.
			paint.setShader(new LinearGradient(0, 0, w, h, 0xFF2B5876, 0xFF4E4376, Shader.TileMode.CLAMP));
			cv.drawRect(0, 0, w, h, paint);
			paint.setShader(null);
			paint.setColor(0x55FFFFFF);
			for (int i = 0; i < 3; i++) cv.drawRoundRect(w * 0.12f, h * (0.2f + i * 0.25f), w * (0.8f - i * 0.15f), h * (0.2f + i * 0.25f) + 4 * d, 2 * d, 2 * d, paint);
			switch (mode) {
				case TransitionConfig.BG_DIM -> cv.drawColor(0x99000000);
				case TransitionConfig.BG_ART, TransitionConfig.BG_GLASS -> {
					paint.setShader(new LinearGradient(0, 0, w, h, 0xFF3A1C71, 0xFFD76D77, Shader.TileMode.CLAMP));
					cv.drawRect(0, 0, w, h, paint);
					paint.setShader(new RadialGradient(w * 0.7f, h * 0.35f, w * 0.5f, 0xFFFFAF7B, 0x00FFAF7B, Shader.TileMode.CLAMP));
					cv.drawRect(0, 0, w, h, paint);
					paint.setShader(null);
					cv.drawColor(0x33000000);
					if (mode == TransitionConfig.BG_GLASS) {
						paint.setShader(new LinearGradient(0, 0, 0, h, 0x55FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
						cv.drawRect(0, 0, w, h, paint);
						paint.setShader(null);
					}
				}
				case TransitionConfig.BG_PICTURE -> {
					paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF56CCF2, 0xFF2F80ED, Shader.TileMode.CLAMP));
					cv.drawRect(0, 0, w, h, paint);
					paint.setShader(null);
					paint.setColor(0xFFFFE082);
					cv.drawCircle(w * 0.75f, h * 0.28f, h * 0.12f, paint);
					paint.setColor(0xFF1B5E20);
					Path hill = new Path();
					hill.moveTo(0, h);
					hill.lineTo(w * 0.3f, h * 0.45f);
					hill.lineTo(w * 0.6f, h);
					hill.close();
					cv.drawPath(hill, paint);
					paint.setColor(0xFF2E7D32);
					Path hill2 = new Path();
					hill2.moveTo(w * 0.35f, h);
					hill2.lineTo(w * 0.7f, h * 0.55f);
					hill2.lineTo(w, h);
					hill2.close();
					cv.drawPath(hill2, paint);
				}
				default -> {
					// Clear: nothing over the app; a dashed frame says "the screen is there, empty".
				}
			}
			cv.restore();
			if (mode == TransitionConfig.BG_TRANSPARENT) return;
			// The words that would sit over it.
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(0xEEFFFFFF);
			cv.drawRoundRect(w * 0.25f, h * 0.55f, w * 0.75f, h * 0.55f + 5 * d, 2.5f * d, 2.5f * d, paint);
			paint.setColor(0x99FFFFFF);
			cv.drawRoundRect(w * 0.33f, h * 0.55f + 10 * d, w * 0.67f, h * 0.55f + 14 * d, 2 * d, 2 * d, paint);
		}
	}

	// ---- Fill ----

	private static final class FillDemo extends View {
		private final int fill;
		private final int accent;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

		FillDemo(Context c, int fill, int accent) {
			super(c);
			this.fill = fill;
			this.accent = accent;
		}

		@Override
		protected void onDraw(@NonNull Canvas cv) {
			float w = getWidth();
			float h = getHeight();
			if (w <= 0) return;
			float d = dp(getContext(), 1);
			RectF clipR = new RectF(0, 0, w, h);
			Path clip = new Path();
			clip.addRoundRect(clipR, 8 * d, 8 * d, Path.Direction.CW);
			cv.save();
			cv.clipPath(clip);
			paint.setShader(new LinearGradient(0, 0, w, h, 0xFF3A1C71, 0xFFD76D77, Shader.TileMode.CLAMP));
			cv.drawRect(0, 0, w, h, paint);
			paint.setShader(null);
			cv.restore();
			RectF p = new RectF(w * 0.12f, h * 0.3f, w * 0.88f, h * 0.7f);
			if (fill == TransitionConfig.FILL_SOLID) {
				paint.setColor(0x99000000);
				cv.drawRoundRect(p, 8 * d, 8 * d, paint);
			} else if (fill == TransitionConfig.FILL_GLASS) {
				paint.setColor(0x33FFFFFF);
				cv.drawRoundRect(p, 8 * d, 8 * d, paint);
				paint.setShader(new LinearGradient(0, p.top, 0, p.bottom, 0x55FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
				cv.drawRoundRect(p, 8 * d, 8 * d, paint);
				paint.setShader(null);
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(d);
				paint.setColor(0x88FFFFFF);
				cv.drawRoundRect(p, 8 * d, 8 * d, paint);
				paint.setStyle(Paint.Style.FILL);
			}
			paint.setColor(0xFFFFFFFF);
			cv.drawRoundRect(w * 0.24f, h * 0.43f, w * 0.76f, h * 0.43f + 4 * d, 2 * d, 2 * d, paint);
			cv.drawRoundRect(w * 0.32f, h * 0.43f + 8 * d, w * 0.68f, h * 0.43f + 11 * d, 1.5f * d, 1.5f * d, paint);
		}
	}

	// ---- Alignment ----

	private static final class AlignDemo extends View {
		private final int align;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

		AlignDemo(Context c, int align, int color) {
			super(c);
			this.align = align;
			paint.setColor(color);
		}

		@Override
		protected void onDraw(@NonNull Canvas cv) {
			float w = getWidth();
			float h = getHeight();
			float d = dp(getContext(), 1);
			float[] widths = {0.62f, 0.42f, 0.52f};
			float lh = 4 * d;
			float gap = 6 * d;
			float total = 3 * lh + 2 * gap;
			float y = (h - total) / 2f;
			float left = w * 0.18f;
			float area = w * 0.64f;
			for (float fw : widths) {
				float lw = area * fw / 0.62f;
				float x = (align == TransitionConfig.ALIGN_LEFT) ? left : (align == TransitionConfig.ALIGN_RIGHT) ?
						left + area - lw : left + (area - lw) / 2f;
				cv.drawRoundRect(x, y, x + lw, y + lh, 2 * d, 2 * d, paint);
				y += lh + gap;
			}
		}
	}
}
