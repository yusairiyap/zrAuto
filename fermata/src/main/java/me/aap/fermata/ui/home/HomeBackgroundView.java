package me.aap.fermata.ui.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Paints the Home tab's background: the picked picture (centre-cropped, dimmed, with a soft shade
 * at the top and bottom where the status bar and the navigation bar sit), or, with none, a quiet
 * gradient in the theme's colours. A new picture fades over the old one. The dim and the shade go
 * towards black in a dark theme and towards white in a light one, like the Music tab's scrim does.
 */
final class HomeBackgroundView extends View {
	private final HomePalette palette;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
	private final Paint shade = new Paint();
	private final Matrix matrix = new Matrix();
	@Nullable
	private Bitmap image;
	@Nullable
	private Bitmap fading;
	private float fadeIn = 1f;
	private int dim = 35;
	private LinearGradient plain;
	private LinearGradient top;
	private LinearGradient bottom;
	@Nullable
	private ValueAnimator anim;

	HomeBackgroundView(Context ctx, HomePalette palette) {
		super(ctx);
		this.palette = palette;
	}

	boolean hasImage() {
		return image != null;
	}

	void setDim(int pct) {
		dim = Math.max(0, Math.min(90, pct));
		invalidate();
	}

	/** Shows {@code bm} (null for the plain gradient), fading from what was there. */
	void setImage(@Nullable Bitmap bm, boolean animate) {
		if (bm == image) return;
		if (anim != null) anim.cancel();
		fading = image;
		image = bm;
		if (!animate || (fading == null && bm == null)) {
			fading = null;
			fadeIn = 1f;
			invalidate();
			return;
		}
		ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
		a.setDuration(420);
		a.addUpdateListener(v -> {
			fadeIn = (float) v.getAnimatedValue();
			if (fadeIn >= 1f) fading = null;
			invalidate();
		});
		anim = a;
		a.start();
	}

	/** What the plain gradient is at its top: the theme's background with a little of the accent (barely, in a light theme). */
	static int plainTop(HomePalette p) {
		return HomeUi.mix(p.background | 0xFF000000, p.accent, p.light ? 0.06f : 0.2f);
	}

	/** The colour the dim and the shade are made of: black in a dark theme, white in a light one. */
	static int veil(HomePalette p) {
		return p.light ? 0xFFFFFFFF : 0xFF000000;
	}

	@Override
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		super.onSizeChanged(w, h, ow, oh);
		int bg = palette.background | 0xFF000000;
		int v = veil(palette) & 0x00FFFFFF;
		plain = new LinearGradient(0, 0, 0, h, new int[]{plainTop(palette), bg,
				HomeUi.mix(bg, palette.accent, palette.light ? 0.02f : 0.06f)},
				new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
		float edge = Math.min(h * 0.25f, HomeUi.dp(getContext(), 140));
		top = new LinearGradient(0, 0, 0, edge, 0x99000000 | v, v, Shader.TileMode.CLAMP);
		bottom = new LinearGradient(0, h - edge, 0, h, v, 0x99000000 | v, Shader.TileMode.CLAMP);
	}

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;
		paint.setShader(plain);
		paint.setAlpha(255);
		c.drawRect(0, 0, w, h, paint);
		paint.setShader(null);
		if (fading != null) draw(c, fading, w, h, 1f - fadeIn);
		if (image != null) draw(c, image, w, h, fadeIn);
		if (image != null || fading != null) {
			shade.setShader(null);
			shade.setColor(veil(palette));
			shade.setAlpha(Math.round(255 * dim / 100f));
			c.drawRect(0, 0, w, h, shade);
			shade.setAlpha(255);
			shade.setShader(top);
			c.drawRect(0, 0, w, h, shade);
			shade.setShader(bottom);
			c.drawRect(0, 0, w, h, shade);
		}
	}

	private void draw(Canvas c, Bitmap bm, int w, int h, float alpha) {
		if (alpha <= 0f) return;
		float s = Math.max((float) w / bm.getWidth(), (float) h / bm.getHeight());
		matrix.setScale(s, s);
		matrix.postTranslate((w - bm.getWidth() * s) / 2f, (h - bm.getHeight() * s) / 2f);
		paint.setAlpha(Math.round(255 * alpha));
		c.drawBitmap(bm, matrix, paint);
		paint.setAlpha(255);
	}
}
