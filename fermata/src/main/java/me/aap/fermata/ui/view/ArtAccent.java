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
		ArtAccent.amount = ArtAccent.fromAmount + (ArtAccent.toAmount - ArtAccent.fromAmount) * t;
		ArtAccent.version++;
		fire();
	};
	/** The hue (0..360) and saturation (0..1) now shown; sat 0 = no colour found yet, so nothing is remapped. */
	private static volatile float hue;
	private static volatile float sat;
	private static float fromHue, toHue, fromSat, toSat, fromAmount, toAmount;
	/** How much of the way to the cover's colour the blues are (0 = the theme's own, 1 = fully the cover's): it glides, so on and off are smooth. */
	private static volatile float amount;
	/** Counts every step, so a recolour that ran mid-glide knows it has to run again. */
	private static volatile int version;
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
			glideTo(hue, sat, 0f);
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

	/** {@code color}, with a blue turned into the cover's colour (as far as {@link #amount} says) while the switch is on. */
	public static int map(int color) {
		float s = sat;
		float a = amount;
		if ((s <= 0.01f) || (a <= 0.01f)) return color;
		float[] hsl = new float[3];
		ColorUtils.colorToHSL(color, hsl);
		if ((hsl[0] < 185f) || (hsl[0] > 270f) || (hsl[1] < 0.12f)) return color;
		hsl[0] = hue;
		// Keeps how strong the original blue was against the cover's own strength, so a pale tint stays pale.
		hsl[1] = Math.min(1f, Math.max(0.25f, hsl[1] * (0.55f + 0.45f * s) + 0.1f * s));
		int mapped = ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), Color.alpha(color));
		return (a >= 0.99f) ? mapped : ColorUtils.blendARGB(color, mapped, a);
	}

	/** The cover's colour itself, for things that are filled with the accent (the playing row); {@code fallback} while off. */
	public static int accentOr(int fallback) {
		float s = sat;
		float a = amount;
		if ((s <= 0.01f) || (a <= 0.01f)) return fallback;
		int c = ColorUtils.HSLToColor(new float[]{hue, Math.max(0.45f, s), 0.58f});
		return ColorUtils.blendARGB(fallback, c, a);
	}

	/** Bumped on every step of a glide. */
	public static int getVersion() {
		return version;
	}

	/** The states a tint can depend on; a list is mapped for every combination of them, so a selector that names two at once is mapped too. */
	private static final int[] FLAGS = {android.R.attr.state_enabled, android.R.attr.state_checked,
			android.R.attr.state_pressed, android.R.attr.state_focused, android.R.attr.state_selected,
			android.R.attr.state_activated};
	private static final int[][] QUERIES = new int[1 << FLAGS.length][];
	private static final int[][] SPECS = new int[(1 << FLAGS.length) + 1][];

	static {
		for (int i = 0; i < QUERIES.length; i++) {
			int n = Integer.bitCount(i);
			int[] q = new int[n];
			int[] spec = new int[FLAGS.length];
			for (int b = 0, k = 0; b < FLAGS.length; b++) {
				boolean on = (i & (1 << b)) != 0;
				if (on) q[k++] = FLAGS[b];
				spec[b] = on ? FLAGS[b] : -FLAGS[b];
			}
			QUERIES[i] = q;
			SPECS[i] = spec;
		}
		SPECS[QUERIES.length] = new int[0];
	}

	/** Lists already mapped for the colour of version {@link #mappedVersion}: a list with no blue maps to NONE. */
	private static final java.util.WeakHashMap<android.content.res.ColorStateList, Object> mappedLists = new java.util.WeakHashMap<>();
	private static final Object NONE = new Object();
	private static int mappedVersion = -1;

	/** {@code list} with each state's colour mapped (the same list when nothing is blue), for a view's tint or text. */
	@Nullable
	public static android.content.res.ColorStateList mapList(@Nullable android.content.res.ColorStateList list) {
		if ((list == null) || !isActive()) return list;
		int v = version;
		if (v != mappedVersion) {
			mappedLists.clear();
			mappedVersion = v;
		}
		Object known = mappedLists.get(list);
		if (known != null) return (known == NONE) ? list : (android.content.res.ColorStateList) known;
		int def = list.getDefaultColor();
		int[] cols = new int[SPECS.length];
		boolean changed = false;
		for (int i = 0; i < QUERIES.length; i++) {
			int c = list.getColorForState(QUERIES[i], def);
			cols[i] = map(c);
			changed |= (cols[i] != c);
		}
		cols[QUERIES.length] = map(def);
		changed |= (cols[QUERIES.length] != def);
		android.content.res.ColorStateList m = changed ? new android.content.res.ColorStateList(SPECS, cols) : null;
		mappedLists.put(list, (m == null) ? NONE : m);
		return (m == null) ? list : m;
	}

	/** Whether a colour from the cover is in use (so views that were recoloured must be looked at again). */
	public static boolean isActive() {
		return (sat > 0.01f) && (amount > 0.01f);
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
		if (v instanceof android.widget.ProgressBar pb) {
			// Every slider has the same bead and the same colours, whoever made it.
			if (pb instanceof android.widget.SeekBar sb) Glass.knob(sb);
			android.content.res.ColorStateList cur = pb.getProgressTintList();
			if (cur != null) {
				Orig o = (Orig) pb.getTag(R.id.art_orig_progress);
				if (o == null) {
					o = new Orig();
					o.orig = cur;
					pb.setTag(R.id.art_orig_progress, o);
				} else if (cur != o.applied) {
					o.orig = cur;
				}
				android.content.res.ColorStateList m = mapList(o.orig);
				if (m == o.orig) {
					if ((o.applied != null) && (cur == o.applied)) pb.setProgressTintList(o.orig);
					o.applied = null;
				} else {
					pb.setProgressTintList(m);
					o.applied = m;
				}
			}
			recolorDrawable(pb.getProgressDrawable());
		}
		if (v instanceof android.widget.TextView t) {
			android.content.res.ColorStateList cur = t.getCompoundDrawableTintList();
			if (cur != null) {
				Orig o = (Orig) t.getTag(R.id.art_orig_compound);
				if (o == null) {
					o = new Orig();
					o.orig = cur;
					t.setTag(R.id.art_orig_compound, o);
				} else if (cur != o.applied) {
					o.orig = cur;
				}
				android.content.res.ColorStateList m = mapList(o.orig);
				if (m == o.orig) {
					if ((o.applied != null) && (cur == o.applied)) t.setCompoundDrawableTintList(o.orig);
					o.applied = null;
				} else {
					t.setCompoundDrawableTintList(m);
					o.applied = m;
				}
			}
		}
		if (v instanceof android.widget.CompoundButton cb) {
			tintOf(cb, R.id.art_orig_button, androidx.core.widget.CompoundButtonCompat.getButtonTintList(cb),
					l -> androidx.core.widget.CompoundButtonCompat.setButtonTintList(cb, l));
		}
		if (v instanceof androidx.appcompat.widget.SwitchCompat sw) {
			tintOf(sw, R.id.art_orig_thumb, sw.getThumbTintList(), sw::setThumbTintList);
			tintOf(sw, R.id.art_orig_track, sw.getTrackTintList(), sw::setTrackTintList);
		}
		if (v instanceof com.google.android.material.card.MaterialCardView card) {
			// The outline of a selectable card (the hall quality choices): blue in the theme, the cover's colour here.
			tintOf(card, R.id.art_orig_stroke, card.getStrokeColorStateList(), card::setStrokeColor);
		}
		if (v instanceof android.widget.ProgressBar pb2) {
			// The track behind the filled part (the dimmed blue of every slider) and the buffered part.
			tintOf(pb2, R.id.art_orig_pbg, pb2.getProgressBackgroundTintList(), pb2::setProgressBackgroundTintList);
			tintOf(pb2, R.id.art_orig_psec, pb2.getSecondaryProgressTintList(), pb2::setSecondaryProgressTintList);
		}
		// Chips, discs, tiles and the rest that are a blue shape: their fill takes the cover's colour too.
		if (!(v instanceof android.widget.ProgressBar)) recolorDrawable(v.getBackground());
		if (v instanceof android.view.ViewGroup g) {
			for (int k = 0, n = g.getChildCount(); k < n; k++) recolor(g.getChildAt(k));
		}
	}

	/** One tint of a view, mapped from what it was before it was ever recoloured; {@code key} is where that is kept. */
	private static void tintOf(android.view.View v, int key, @Nullable android.content.res.ColorStateList cur,
														 java.util.function.Consumer<android.content.res.ColorStateList> set) {
		if (cur == null) return;
		Orig o = (Orig) v.getTag(key);
		if (o == null) {
			o = new Orig();
			o.orig = cur;
			v.setTag(key, o);
		} else if (cur != o.applied) {
			o.orig = cur;
		}
		android.content.res.ColorStateList m = mapList(o.orig);
		if (m == o.orig) {
			if ((o.applied != null) && (cur == o.applied)) set.accept(o.orig);
			o.applied = null;
		} else {
			set.accept(m);
			o.applied = m;
		}
	}

	private static final java.util.WeakHashMap<android.graphics.drawable.Drawable, Boolean> owned = new java.util.WeakHashMap<>();

	private static final java.util.WeakHashMap<android.graphics.drawable.GradientDrawable, Orig> shapes =
			new java.util.WeakHashMap<>();

	/** A shape drawable's plain fill (also inside layers, clips and ripples), mapped; each drawable is made its own first so no shared one changes. */
	private static void recolorDrawable(@Nullable android.graphics.drawable.Drawable d) {
		if (d == null) return;
		if (d instanceof android.graphics.drawable.GradientDrawable g) {
			// Only the plain kind: the app's own subclasses (the settings tiles) look after their colours themselves.
			if (g.getClass() != android.graphics.drawable.GradientDrawable.class) return;
			Orig o = shapes.get(g);
			android.content.res.ColorStateList cur = g.getColor();
			if (cur == null) return;
			if (o == null) {
				// Only a drawable with a blue in it is taken over (and made its own); the others are left alone.
				if (!isActive() || (mapList(cur) == cur)) return;
				g.mutate();
				o = new Orig();
				o.orig = cur;
				shapes.put(g, o);
				cur = g.getColor();
			} else if ((cur != o.applied) && !cur.equals(o.applied)) {
				o.orig = cur;
			}
			android.content.res.ColorStateList m = mapList(o.orig);
			if (m == o.orig) {
				if ((o.applied != null) && cur.equals(o.applied)) g.setColor(o.orig);
				o.applied = null;
			} else {
				g.setColor(m);
				o.applied = g.getColor();
			}
			return;
		}
		if (d instanceof android.graphics.drawable.DrawableContainer dc) {
			// A state list (a selected tile's border, a pressed fill): made its own once, then its shapes like any other.
			if (owned.put(d, Boolean.TRUE) == null) d.mutate();
			if (dc.getConstantState() instanceof android.graphics.drawable.DrawableContainer.DrawableContainerState st) {
				android.graphics.drawable.Drawable[] ch = st.getChildren();
				for (int i = 0, n = st.getChildCount(); i < n; i++) recolorDrawable(ch[i]);
			}
			return;
		}
		if (d instanceof android.graphics.drawable.LayerDrawable l) {
			for (int i = 0, n = l.getNumberOfLayers(); i < n; i++) recolorDrawable(l.getDrawable(i));
		} else if (d instanceof android.graphics.drawable.ClipDrawable c) {
			recolorDrawable(c.getDrawable());
		} else if (d instanceof android.graphics.drawable.InsetDrawable c) {
			recolorDrawable(c.getDrawable());
		} else if (d instanceof android.graphics.drawable.ScaleDrawable c) {
			recolorDrawable(c.getDrawable());
		} else if (d instanceof android.graphics.drawable.RotateDrawable c) {
			recolorDrawable(c.getDrawable());
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
					glideTo(hsl[0], Math.max(0.35f, hsl[1]), 1f);
				});
			});
		});
	}

	private static void glideTo(float h, float s, float a) {
		if (anim != null) anim.cancel();
		fromHue = hue;
		fromSat = sat;
		fromAmount = amount;
		toHue = h;
		toSat = s;
		toAmount = a;
		// Coming in from the theme's own colours, the cover's colour is there from the start and only its strength glides.
		if (fromAmount <= 0.01f) {
			fromHue = toHue;
			fromSat = toSat;
		}
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
