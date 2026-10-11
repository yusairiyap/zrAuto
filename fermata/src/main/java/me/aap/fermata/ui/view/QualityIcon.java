package me.aap.fermata.ui.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.engine.VideoQualityOption;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.async.FutureSupplier;

/**
 * The Quality action's icon: the quality in use as text ("720p", "Auto"), the same on the All in
 * One tile and a floating button; {@link #getLabel()} is null while it isn't known (nothing from
 * YouTube playing), and the plain quality icon shows instead.
 * <p>
 * The label is the player's own answer (see {@link MediaEngine#getVideoQualityOptions()}), asked
 * at most every couple of seconds by whatever shows the icon, and set at once when the Quality
 * card picks one.
 */
public final class QualityIcon extends Drawable {
	private static final long ASK_INTERVAL = 2000;
	@Nullable
	private static String label;
	private static boolean asking;
	// Waiting for the answer to an ask already on its way.
	private static final List<Runnable> waiting = new java.util.ArrayList<>();
	private static long lastAsk;

	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final String text;
	private final int size;
	@Nullable
	private ColorStateList tint;

	public QualityIcon(@NonNull Context ctx, @NonNull String text) {
		this.text = text;
		size = Math.round(24 * ctx.getResources().getDisplayMetrics().density);
		paint.setTypeface(Typeface.DEFAULT_BOLD);
		paint.setTextAlign(Paint.Align.CENTER);
		paint.setColor(0xFF000000);
	}

	/** The quality in use, as the player last said; null if unknown. */
	@Nullable
	public static String getLabel() {
		return label;
	}

	/** The Quality card picked {@code l}: shown at once, before the player is asked again. */
	public static void setLabel(@Nullable String l) {
		label = l;
		lastAsk = SystemClock.uptimeMillis();
	}

	/**
	 * Asks the player which quality it plays (no more often than every couple of seconds);
	 * {@code onChange} runs if the answer differs from the label shown.
	 */
	public static void refresh(@NonNull MainActivityDelegate a, @Nullable Runnable onChange) {
		refresh(a, onChange, false);
	}

	/**
	 * @param now ask right away, however recently it was asked: something the user is looking at
	 *            just came up (the All in One card with a Quality tile)
	 */
	public static void refresh(@NonNull MainActivityDelegate a, @Nullable Runnable onChange,
														 boolean now) {
		long t = SystemClock.uptimeMillis();
		if (asking) {
			// The answer is on its way: told too when it comes, if it changes the label.
			if (onChange != null) waiting.add(onChange);
			return;
		}
		if (!now && (t - lastAsk < ASK_INTERVAL)) return;
		refreshNow(a, onChange, t);
	}

	private static void refreshNow(MainActivityDelegate a, @Nullable Runnable onChange, long now) {
		lastAsk = now;
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		FutureSupplier<List<VideoQualityOption>> f = (eng == null) ? null : eng.getVideoQualityOptions();
		if (f == null) {
			update(null, onChange);
			return;
		}
		asking = true;
		f.main().onCompletion((list, err) -> {
			asking = false;
			List<Runnable> also = new java.util.ArrayList<>(waiting);
			waiting.clear();
			String l = null;
			if ((err == null) && (list != null)) {
				for (VideoQualityOption q : list) {
					if (q.selected) {
						l = q.label;
						break;
					}
				}
			}
			boolean changed = update(l, onChange);
			if (changed) for (Runnable r : also) r.run();
		});
	}

	/** @return whether the label changed */
	private static boolean update(@Nullable String l, @Nullable Runnable onChange) {
		if ((l == null) ? (label == null) : l.equals(label)) return false;
		label = l;
		if (onChange != null) onChange.run();
		return true;
	}

	@Override
	public void draw(@NonNull Canvas c) {
		Rect b = getBounds();
		if (b.isEmpty()) return;
		// As big as fits: "Auto" and "720p" fill the icon's width, "1080p" a little smaller.
		float maxW = b.width() * 0.96f;
		float ts = b.height() * 0.46f;
		paint.setTextSize(ts);
		float w = paint.measureText(text);
		if (w > maxW) paint.setTextSize(ts * maxW / w);
		Paint.FontMetrics fm = paint.getFontMetrics();
		float y = b.exactCenterY() - (fm.ascent + fm.descent) / 2f;
		c.drawText(text, b.exactCenterX(), y, paint);
	}

	@Override
	public int getIntrinsicWidth() {
		return size;
	}

	@Override
	public int getIntrinsicHeight() {
		return size;
	}

	// The image tint of the ImageView or button it's in (white on the accent disc, the FAB's own
	// colour) is the text's colour.

	@Override
	public void setTintList(@Nullable ColorStateList tint) {
		this.tint = tint;
		applyTint();
	}

	@Override
	public boolean isStateful() {
		return (tint != null) && tint.isStateful();
	}

	@Override
	protected boolean onStateChange(@NonNull int[] state) {
		return applyTint();
	}

	private boolean applyTint() {
		int color = (tint == null) ? 0xFF000000 : tint.getColorForState(getState(),
				tint.getDefaultColor());
		if (paint.getColor() == color) return false;
		paint.setColor(color);
		invalidateSelf();
		return true;
	}

	@Override
	public void setAlpha(int alpha) {
		paint.setAlpha(alpha);
		invalidateSelf();
	}

	@Override
	public void setColorFilter(@Nullable ColorFilter cf) {
		paint.setColorFilter(cf);
		invalidateSelf();
	}

	@Override
	public int getOpacity() {
		return PixelFormat.TRANSLUCENT;
	}
}
