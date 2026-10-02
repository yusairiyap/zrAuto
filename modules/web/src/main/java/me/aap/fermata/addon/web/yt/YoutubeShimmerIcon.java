package me.aap.fermata.addon.web.yt;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;

/**
 * A big icon with a band of light sweeping across it again and again, for "loading": the dim icon
 * is painted first, then the moving highlight over just the icon's own pixels.
 */
final class YoutubeShimmerIcon extends View {
	private final Drawable icon;
	private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final int base;
	private final int highlight;
	private float phase;
	private ValueAnimator anim;

	YoutubeShimmerIcon(Context ctx, int iconRes, int base, int highlight) {
		super(ctx);
		this.base = base;
		this.highlight = highlight;
		Drawable d = ContextCompat.getDrawable(ctx, iconRes);
		icon = (d != null) ? d.mutate() : null;
		shine.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
	}

	void start() {
		if (anim != null) return;
		anim = ValueAnimator.ofFloat(0f, 1f);
		anim.setDuration(1500);
		anim.setRepeatCount(ValueAnimator.INFINITE);
		anim.setInterpolator(new LinearInterpolator());
		anim.addUpdateListener(v -> {
			phase = (float) v.getAnimatedValue();
			invalidate();
		});
		anim.start();
	}

	void stop() {
		if (anim != null) anim.cancel();
		anim = null;
	}

	@Override
	protected void onDetachedFromWindow() {
		stop();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onDraw(Canvas canvas) {
		if (icon == null) return;
		int w = getWidth();
		int h = getHeight();
		int layer = canvas.saveLayer(0, 0, w, h, null);
		icon.setBounds(0, 0, w, h);
		icon.setTint(base);
		icon.draw(canvas);
		if (anim != null) {
			// The band starts left of the icon and ends right of it.
			float x = -w * 0.6f + phase * w * 2.2f;
			shine.setShader(new LinearGradient(x, 0, x + w * 0.6f, h * 0.35f,
					new int[]{0x00FFFFFF & highlight, highlight, 0x00FFFFFF & highlight}, null,
					Shader.TileMode.CLAMP));
			canvas.drawRect(0, 0, w, h, shine);
		}
		canvas.restoreToCount(layer);
	}
}
