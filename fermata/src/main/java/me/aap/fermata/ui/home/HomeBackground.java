package me.aap.fermata.ui.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.app.App;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;

/**
 * The picture behind the Home tab: picked from the phone with the system's picker, shrunk and kept
 * as {@code files/home_bg.jpg} (so it needs no permission and survives the original being
 * deleted), and loaded again, blurred as asked, whenever Home shows. Only a version number lives
 * in the preferences, to tell the tab it changed.
 */
final class HomeBackground {
	private static final int MAX_EDGE = 1600;
	private static final String FILE = "home_bg.jpg";
	private static final String FILE_AA = "home_bg_aa.jpg";

	/** A loaded picture and the colour of its top strip (for the status bar). */
	record Loaded(Bitmap bitmap, int topColor) {}

	private HomeBackground() {
	}

	static File file(Context ctx, boolean car) {
		return new File(ctx.getFilesDir(), car ? FILE_AA : FILE);
	}

	static boolean exists(Context ctx, boolean car) {
		return file(ctx, car).isFile();
	}

	/** Opens the system's picture picker; the chosen one becomes the background. */
	static void pick(MainActivityDelegate a, boolean car, @Nullable Runnable done) {
		a.startActivityForResult(() -> new Intent(Intent.ACTION_OPEN_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")).onCompletion((d, err) -> {
			Context ctx = a.getContext();
			if (err != null) {
				UiUtils.showAlert(ctx, ctx.getString(R.string.home_bg_failed, err));
				return;
			}
			if ((d == null) || (d.getData() == null)) return;
			Uri uri = d.getData();
			App.get().getExecutor().submitTask(() -> {
				save(ctx, uri, car);
				return null;
			}).main().onCompletion((r, e2) -> {
				if (e2 != null) {
					Log.e(e2, "Failed to use the Home background");
					UiUtils.showAlert(ctx, ctx.getString(R.string.home_bg_failed, e2));
					return;
				}
				MainActivityPrefs p = a.getPrefs();
				p.applyIntPref(HomeBgPrefs.version(car), p.getIntPref(HomeBgPrefs.version(car)) + 1);
				TopToast.show(R.drawable.home_image, R.string.home_bg_set);
				if (done != null) done.run();
			});
		});
	}

	static void clear(MainActivityDelegate a, boolean car) {
		File f = file(a.getContext(), car);
		if (f.isFile() && !f.delete()) Log.w("Could not delete the Home background");
		MainActivityPrefs p = a.getPrefs();
		p.applyIntPref(HomeBgPrefs.version(car), p.getIntPref(HomeBgPrefs.version(car)) + 1);
	}

	/** Decodes the picked picture shrunk to a sensible size (turned upright) and stores it as JPEG. */
	private static void save(Context ctx, Uri uri, boolean car) throws IOException {
		Bitmap bm;
		try {
			ImageDecoder.Source src = ImageDecoder.createSource(ctx.getContentResolver(), uri);
			bm = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
				int w = info.getSize().getWidth();
				int h = info.getSize().getHeight();
				float k = Math.min(1f, (float) MAX_EDGE / Math.max(w, h));
				decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
				decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
			});
		} catch (Throwable ex) {
			// The decoder can't read it: the older way, without turning it upright.
			try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
				if (is == null) throw new IOException("Can't open " + uri);
				bm = BitmapFactory.decodeStream(is);
			}
			if (bm == null) throw new IOException("Not a picture: " + uri);
		}
		File tmp = new File(ctx.getFilesDir(), (car ? FILE_AA : FILE) + ".tmp");
		try (FileOutputStream out = new FileOutputStream(tmp)) {
			bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
		}
		File f = file(ctx, car);
		if (f.exists() && !f.delete()) Log.w("Could not replace the Home background");
		if (!tmp.renameTo(f)) throw new IOException("Could not store the picture");
	}

	/**
	 * Reads the stored picture, blurred by {@code blur} (0 to 100), for a screen about
	 * {@code w x h}; null if there is none. Blocks: call it off the main thread.
	 */
	@Nullable
	static Loaded load(Context ctx, boolean car, int blur, int w, int h) {
		File f = file(ctx, car);
		if (!f.isFile()) return null;
		try {
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			BitmapFactory.decodeFile(f.getPath(), o);
			int target = Math.max(w, h);
			int sample = 1;
			while ((o.outWidth / (sample * 2) >= target) && (o.outHeight / (sample * 2) >= target)) sample *= 2;
			o = new BitmapFactory.Options();
			o.inSampleSize = sample;
			Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
			if (bm == null) return null;
			if (blur > 0) {
				Bitmap soft = HomeBlur.blur(bm, blur);
				if (soft != null) {
					if (soft != bm) bm.recycle();
					bm = soft;
				}
			}
			return new Loaded(bm, topColor(bm));
		} catch (Throwable ex) {
			Log.e(ex, "Failed to load the Home background");
			return null;
		}
	}

	/** The average colour of the top 8% of the picture. */
	static int topColor(Bitmap bm) {
		int w = bm.getWidth();
		int h = Math.max(1, bm.getHeight() / 12);
		long r = 0, g = 0, b = 0;
		int n = 0;
		int stepX = Math.max(1, w / 32);
		int stepY = Math.max(1, h / 6);
		for (int y = 0; y < h; y += stepY) {
			for (int x = 0; x < w; x += stepX) {
				int c = bm.getPixel(x, y);
				r += Color.red(c);
				g += Color.green(c);
				b += Color.blue(c);
				n++;
			}
		}
		if (n == 0) return Color.BLACK;
		return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
	}
}
