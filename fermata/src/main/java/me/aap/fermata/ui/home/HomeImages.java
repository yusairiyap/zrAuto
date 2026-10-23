package me.aap.fermata.ui.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.util.LruCache;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.async.FutureSupplier;

/** Loads cover art and thumbnails into the widgets' image views, with a small cache of its own. */
public final class HomeImages {
	private static final LruCache<String, Bitmap> cache = new LruCache<>(64);

	private HomeImages() {
	}

	/** A rounded image view of {@code wDp x hDp} (dp), cropping its picture to fill. */
	public static ImageView thumb(Context ctx, int wDp, int hDp, int radiusDp, int fill) {
		ImageView v = new ImageView(ctx);
		v.setScaleType(ImageView.ScaleType.CENTER_CROP);
		v.setBackground(HomeUi.round(fill, HomeUi.dp(ctx, radiusDp)));
		round(v, HomeUi.dp(ctx, radiusDp));
		v.setLayoutParams(new LinearLayout.LayoutParams(HomeUi.dp(ctx, wDp), HomeUi.dp(ctx, hDp)));
		return v;
	}

	/** Clips {@code v} to a rounded rectangle. */
	public static void round(View v, int radiusPx) {
		v.setOutlineProvider(new ViewOutlineProvider() {
			@Override
			public void getOutline(View view, Outline o) {
				o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
			}
		});
		v.setClipToOutline(true);
	}

	@Nullable
	public static Bitmap cached(String uri) {
		return cache.get(uri);
	}

	public static void put(String uri, Bitmap bm) {
		cache.put(uri, bm);
	}

	/**
	 * Shows the picture at {@code uri} in {@code v} when it is loaded (and clears the old one now). It
	 * is the small copy the library's lists use (decoded once, saved on disk, shared with the tabs), not
	 * the full picture; one that was not at hand comes in with a short fade instead of popping in.
	 */
	public static void loadUri(MainActivityDelegate a, ImageView v, @Nullable String uri) {
		loadUri(a, v, uri, false);
	}

	private static void loadUri(MainActivityDelegate a, ImageView v, @Nullable String uri, boolean fade) {
		v.setTag(R.id.home_image_key, uri);
		v.animate().cancel();
		v.setAlpha(1f);
		if (uri == null) {
			v.setImageDrawable(null);
			return;
		}
		v.setImageDrawable(null);
		boolean[] late = {fade};
		FutureSupplier<Bitmap> f = a.getLib().getBitmap(uri, true, true).main().onCompletion((bm, err) -> {
			if ((bm == null) || !uri.equals(v.getTag(R.id.home_image_key))) return;
			v.setImageBitmap(bm);
			if (late[0]) {
				v.setAlpha(0f);
				v.animate().alpha(1f).setDuration(180).start();
			}
		});
		if (!f.isDone()) late[0] = true;
	}

	/** Shows the item's own picture (its icon uri) in {@code v}. */
	public static void loadItem(MainActivityDelegate a, ImageView v, MediaLib.Item item) {
		v.setTag(R.id.home_image_item, item);
		v.setTag(R.id.home_image_key, null);
		v.setImageDrawable(null);
		boolean[] late = {false};
		FutureSupplier<android.net.Uri> f = item.getIconUri().main().onCompletion((u, err) -> {
			if ((u == null) || (v.getTag(R.id.home_image_item) != item)) return;
			loadUri(a, v, u.toString(), late[0]);
		});
		if (!f.isDone()) late[0] = true;
	}
}
