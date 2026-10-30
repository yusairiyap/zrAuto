package me.aap.fermata.ui.view;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.home.HomeArt;
import me.aap.utils.app.App;

/**
 * "Follow the album art" (Settings > Interface, {@link MainActivityPrefs#ART_ACCENT}): the theme's blue
 * (its tinted bars, buttons, chips, seek bars and icons) takes the colour most of the playing cover is
 * in, gliding from one song's colour to the next's.
 * <p>
 * Android cannot change a theme attribute while a window is up, so the theme itself is left alone and the
 * places that draw a theme colour ask {@link #map} for the colour to use: it turns only a blue
 * (hue 185 to 270, some saturation) into the cover's hue, keeping the original's lightness and alpha, so a
 * black, white or grey theme stays as it is and a blue one follows the art. Views that cache a colour listen
 * with {@link #addListener} and draw again when it moves.
 */
public final class ArtAccent {
	private static final long GLIDE_MS = 900;
	private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private static final ValueAnimator.AnimatorUpdateListener STEP = an -> {
		float t = an.getAnimatedFraction();
		ArtAccent.hue = ArtAccent.lerpHue(ArtAccent.fromHue, ArtAccent.toHue, t);
		ArtAccent.sat = ArtAccent.fromSat + (ArtAccent.toSat - ArtAccent.fromSat) * t;
		fire();
	};
	/** The hue (0..360) and saturation (0..1) now shown; sat 0 = no colour found yet, so nothing is remapped. */
	private static volatile float hue;
	private static volatile float sat;
	private static float fromHue, toHue, fromSat, toSat;
	@Nullable
	private static ValueAnimator anim;
	@Nullable
	private static MainActivityDelegate owner;
	private static int generation;
	@Nullable
	private static PlayableItem shownItem;
	private static final FermataServiceUiBinder.Listener playing = new FermataServiceUiBinder.Listener() {
		@Override
		public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
			App.get().getHandler().post(() -> refresh());
		}
	};

	private ArtAccent() {
	}

	public static boolean isOn() {
		return MainActivityPrefs.get().getBooleanPref(MainActivityPrefs.ART_ACCENT);
	}

	/** Called once by the main activity; follows the playing song while the switch is on. */
	public static void init(MainActivityDelegate a) {
		if (owner == a) return;
		if (owner != null) owner.getMediaServiceBinder().removeBroadcastListener(playing);
		owner = a;
		a.getMediaServiceBinder().addBroadcastListener(playing);
		refresh();
	}

	/** The switch changed: the colour is looked for (on) or the theme's own colours come back (off). */
	public static void onSwitched() {
		if (!isOn()) {
			generation++;
			shownItem = null;
			glideTo(0f, 0f);
			return;
		}
		shownItem = null;
		refresh();
	}

	public static void addListener(Runnable r) {
		listeners.add(r);
	}

	public static void removeListener(Runnable r) {
		listeners.remove(r);
	}

	private static void fire() {
		for (Runnable r : listeners) r.run();
	}

	/** {@code color}, with a blue turned into the cover's colour while the switch is on. */
	public static int map(int color) {
		float s = sat;
		if (s <= 0.01f) return color;
		float[] hsl = new float[3];
		ColorUtils.colorToHSL(color, hsl);
		if ((hsl[0] < 185f) || (hsl[0] > 270f) || (hsl[1] < 0.12f)) return color;
		hsl[0] = hue;
		// Keeps how strong the original blue was against the cover's own strength, so a pale tint stays pale.
		hsl[1] = Math.min(1f, Math.max(0.25f, hsl[1] * (0.55f + 0.45f * s) + 0.1f * s));
		return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), Color.alpha(color));
	}

	private static final int[][] STATES = {{android.R.attr.state_pressed}, {android.R.attr.state_focused},
			{android.R.attr.state_selected}, {android.R.attr.state_activated}, {android.R.attr.state_checked},
			{-android.R.attr.state_enabled}, {}};

	/** {@code list} with each state's colour mapped (the same list when nothing is blue), for a view's tint or text. */
	@Nullable
	public static android.content.res.ColorStateList mapList(@Nullable android.content.res.ColorStateList list) {
		if ((list == null) || (sat <= 0.01f)) return list;
		int def = list.getDefaultColor();
		int[] cols = new int[STATES.length];
		boolean changed = false;
		for (int i = 0; i < cols.length; i++) {
			int c = (STATES[i].length == 0) ? def : list.getColorForState(STATES[i], def);
			cols[i] = map(c);
			changed |= (cols[i] != c);
		}
		return changed ? new android.content.res.ColorStateList(STATES, cols) : list;
	}

	/** Whether a colour from the cover is in use (so views that were recoloured must be looked at again). */
	public static boolean isActive() {
		return sat > 0.01f;
	}

	/** What a view had before it was recoloured, and what it was given, to tell its own later changes from ours. */
	private static final class Orig {
		android.content.res.ColorStateList orig;
		android.content.res.ColorStateList applied;
	}

	/**
	 * Recolours every text and tinted icon under {@code v} whose colour is a blue: the rest of the app's blue (list text,
	 * chips, icons, whatever the themes tint through XML) that has no code of its own to ask {@link #map}. Each view keeps
	 * its original colour in a tag, so the next song starts from the theme's and not from the last song's.
	 */
	public static void recolor(android.view.View v) {
		if (v instanceof android.widget.TextView t) {
			Orig o = (Orig) t.getTag(R.id.art_orig_text);
			android.content.res.ColorStateList cur = t.getTextColors();
			if (o == null) {
				o = new Orig();
				o.orig = cur;
				t.setTag(R.id.art_orig_text, o);
			} else if (cur != o.applied) {
				o.orig = cur;
			}
			android.content.res.ColorStateList m = mapList(o.orig);
			if (m == o.orig) {
				if ((o.applied != null) && (cur == o.applied)) t.setTextColor(o.orig);
				o.applied = null;
			} else {
				t.setTextColor(m);
				o.applied = t.getTextColors();
			}
		}
		if (v instanceof android.widget.ImageView i) {
			android.content.res.ColorStateList cur = androidx.core.widget.ImageViewCompat.getImageTintList(i);
			if (cur != null) {
				Orig o = (Orig) i.getTag(R.id.art_orig_tint);
				if (o == null) {
					o = new Orig();
					o.orig = cur;
					i.setTag(R.id.art_orig_tint, o);
				} else if (cur != o.applied) {
					o.orig = cur;
				}
				android.content.res.ColorStateList m = mapList(o.orig);
				if (m == o.orig) {
					if ((o.applied != null) && (cur == o.applied)) androidx.core.widget.ImageViewCompat.setImageTintList(i, o.orig);
					o.applied = null;
				} else {
					androidx.core.widget.ImageViewCompat.setImageTintList(i, m);
					o.applied = m;
				}
			}
		}
		if (v instanceof android.view.ViewGroup g) {
			for (int k = 0, n = g.getChildCount(); k < n; k++) recolor(g.getChildAt(k));
		}
	}

	private static void refresh() {
		MainActivityDelegate a = owner;
		if ((a == null) || !isOn()) return;
		PlayableItem pi = HomeArt.displayItem(a);
		if ((pi == null) || HomeArt.same(pi, shownItem)) return;
		shownItem = pi;
		int gen = ++generation;
		pi.getMediaData().main().onCompletion((md, err) -> {
			if (gen != generation) return;
			HomeArt.cover(a, pi, md, () -> gen == generation, bm -> {
				if ((gen != generation) || (bm == null)) return;
				App.get().getExecutor().submitTask(() -> dominant(bm)).main().onSuccess(c -> {
					if ((gen != generation) || (c == null)) return;
					float[] hsl = new float[3];
					ColorUtils.colorToHSL(c, hsl);
					glideTo(hsl[0], Math.max(0.35f, hsl[1]));
				});
			});
		});
	}

	private static void glideTo(float h, float s) {
		if (anim != null) anim.cancel();
		fromHue = hue;
		fromSat = sat;
		toHue = (s <= 0f) ? fromHue : h;
		toSat = s;
		// From nothing, the first colour appears at its own hue, not swept over from 0.
		if (fromSat <= 0.01f) fromHue = toHue;
		anim = ValueAnimator.ofFloat(0f, 1f);
		anim.setDuration(GLIDE_MS);
		anim.setInterpolator(new DecelerateInterpolator(1.4f));
		anim.addUpdateListener(STEP);
		anim.start();
	}

	private static float lerpHue(float a, float b, float t) {
		float d = ((b - a + 540f) % 360f) - 180f;
		return (a + d * t + 360f) % 360f;
	}

	/**
	 * The colour most of the picture is in: its pixels (a 32 pixel copy) fall into 18 hue bins weighted by how
	 * saturated and how mid-bright they are, and the heaviest bin's average is the answer. Null for a picture with no
	 * colour at all (greys), which leaves the theme's own colours.
	 */
	@Nullable
	private static Integer dominant(Bitmap src) {
		try {
			Bitmap b = src;
			if (b.getConfig() == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false);
			if (b == null) return null;
			Bitmap small = Bitmap.createScaledBitmap(b, 32, 32, true);
			int[] px = new int[32 * 32];
			small.getPixels(px, 0, 32, 0, 0, 32, 32);
			double[] weight = new double[18];
			double[] r = new double[18];
			double[] g = new double[18];
			double[] bl = new double[18];
			float[] hsv = new float[3];
			for (int p : px) {
				Color.colorToHSV(p, hsv);
				if ((hsv[1] < 0.18f) || (hsv[2] < 0.15f)) continue;
				double w = hsv[1] * (1.0 - Math.abs(hsv[2] - 0.6)) + 0.01;
				int bin = Math.min(17, (int) (hsv[0] / 20f));
				weight[bin] += w;
				r[bin] += Color.red(p) * w;
				g[bin] += Color.green(p) * w;
				bl[bin] += Color.blue(p) * w;
			}
			int best = -1;
			for (int i = 0; i < 18; i++) if ((best < 0) || (weight[i] > weight[best])) best = i;
			if ((best < 0) || (weight[best] < 0.5)) return null;
			return Color.rgb((int) (r[best] / weight[best]), (int) (g[best] / weight[best]),
					(int) (bl[best] / weight[best]));
		} catch (RuntimeException ex) {
			return null;
		}
	}
}
