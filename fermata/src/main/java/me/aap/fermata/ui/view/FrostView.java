package me.aap.fermata.ui.view;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * The blurred backdrop of a frosted-glass panel: a snapshot of what's behind the panel, blurred,
 * drawn behind the panel's own translucent tint. Blurs only what the panel covers, unlike blurring
 * the whole screen. Android 12+ only (it uses a {@link RenderEffect}); elsewhere {@link #capture}
 * returns false and the panel should fall back to an opaque fill.
 * <p>
 * The snapshot is recorded as a display list rather than a bitmap, so hardware-only bitmaps (album
 * art, thumbnails) are drawn as they are. Take it while the panel itself isn't showing.
 */
public class FrostView extends View {
	private RenderNode node;
	private int originX;
	private int originY;

	public FrostView(Context context) {
		this(context, null);
	}

	public FrostView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public static boolean isSupported() {
		return SDK_INT >= 31;
	}

	/** Whether there's a snapshot to show. */
	public boolean isReady() {
		return node != null;
	}

	/**
	 * Snapshots {@code host} and shows the part of it that lies under this view, blurred.
	 *
	 * @param left where this view's left edge is in {@code host}
	 * @param top  where this view's top edge is in {@code host}
	 * @return whether it worked
	 */
	public boolean capture(ViewGroup host, int left, int top, float blurPx) {
		clear();
		if (SDK_INT < 31) return false;

		try {
			int w = host.getWidth();
			int h = host.getHeight();
			if ((w <= 0) || (h <= 0)) return false;
			RenderNode n = new RenderNode("frost");
			n.setPosition(0, 0, w, h);
			Canvas c = n.beginRecording(w, h);
			try {
				host.draw(c);
			} finally {
				n.endRecording();
			}
			n.setRenderEffect(RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
			node = n;
			originX = left;
			originY = top;
			invalidate();
			return true;
		} catch (Throwable ex) {
			node = null;
			return false;
		}
	}

	public void clear() {
		node = null;
		invalidate();
	}

	@Override
	protected void onDraw(Canvas canvas) {
		RenderNode n = node;
		if ((n == null) || !canvas.isHardwareAccelerated()) return;
		canvas.translate(-originX, -originY);
		canvas.drawRenderNode(n);
	}
}
