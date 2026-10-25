package me.aap.fermata.ui.view;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.WeakHashMap;

import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.pref.PreferenceStore;

/**
 * The UI style (Settings > Interface > UI style): the usual bars and cards, or glass. With glass, the
 * tool bar, the nav bar / control panel pill, the floating buttons' pill, menus, dialogs and Home's
 * Glass cards show what is behind them blurred (real time, Android 12+), under a tint of their own
 * colour, a little saturation, a faint grain and a light rim along the top edge.
 * <p>
 * How it is drawn: {@code BodyLayout} records the tab's content into a RenderNode (the same display
 * list the screen draws, so nothing is drawn twice on the UI thread), and each glass surface draws that
 * node through a {@link GlassLens}: its own small RenderNode with a blur RenderEffect, as big as the
 * surface (plus a margin), clipped to its shape, rendered at a fraction of the size (the quality
 * setting) and stretched back up. Below Android 12 there is no RenderEffect (RenderScript is deprecated
 * and slow), so the glass is the tint, grain and rim without the blur.
 * <p>
 * <b>A surface is only drawn again when something under it changed</b>: the surfaces are registered
 * here ({@link #register}), and BodyLayout calls {@link #invalidateSurfaces} when a view in the tab
 * asks to be drawn again ({@code onDescendantInvalidated}) and {@link #syncSurfaces} before a frame
 * to catch a surface that moved. Invalidating them before every frame instead kept the screen
 * redrawing (and blurring) 60 times a second while nothing moved.
 * <p>
 * Web pages (the YouTube and browser tabs) and fullscreen video are never blurred: a WebView draws
 * through a functor that must not be drawn twice in a frame (it flickers), and video is a SurfaceView
 * or the page's own fullscreen layer, which nothing in the app's drawing can read. There the glass is
 * the tint, grain and rim.
 * <p>
 * Style changes glide: {@link #look()} is the look on its way from the old values to the new ones for
 * {@link #ANIM_MS}, while every surface is drawn again each frame.
 */
public final class Glass {
	public static final int NORMAL = 0;
	public static final int FROSTED = 1;
	public static final int CLEAR = 2;
	public static final int ACRYLIC = 3;
	public static final int STYLE_COUNT = 4;
	/** Blur quality: rendered at full size, half, or a quarter (and when the power saver is on). */
	public static final int QUALITY_HIGH = 0;
	public static final int QUALITY_BALANCED = 1;
	public static final int QUALITY_SAVER = 2;
	static final long ANIM_MS = 320;
	/** The parts the rim light can be on or off for (Settings > UI style > Rim light), see {@link Look#rimFor}. */
	public static final int RIM_TOOLBAR = 1;
	public static final int RIM_NAV = 2;
	public static final int RIM_FAB = 4;
	public static final int RIM_MENUS = 8;
	public static final int RIM_HOME = 16;
	public static final int RIM_VIDEO = 32;
	public static final int RIM_TILES = 64;

	/** Each style's blur (dp), tint (%), saturation (%), grain and rim (1 = on). NORMAL has none. */
	private static final int[][] PRESETS = {
			null,
			{22, 55, 140, 1, 1},
			{10, 22, 125, 0, 1},
			{34, 74, 110, 1, 0},
	};

	/** What a glass surface is drawn with. */
	public static final class Look {
		public final int style;
		public final float blurDp;
		/** The tint, 0 to 100 (% opacity). */
		public final float tint;
		public final float saturation;
		/** How much grain and rim light, 0 to 1 (they fade in and out with a change). */
		public final float grain;
		public final float rim;
		public final boolean bars;
		public final boolean home;
		public final boolean menus;
		/** The size the blur is rendered at, 1 = full. */
		public final float scale;
		/** The parts with the rim light (RIM_*), and whether the settings cards are glass too. */
		final int rimParts;
		final boolean tiles;
		@Nullable
		private Look noRim;

		Look(int style, float blurDp, float tint, float saturation, float grain, float rim, boolean bars,
				 boolean home, boolean menus, float scale) {
			this(style, blurDp, tint, saturation, grain, rim, bars, home, menus, scale, ~0, false);
		}

		Look(int style, float blurDp, float tint, float saturation, float grain, float rim, boolean bars,
				 boolean home, boolean menus, float scale, int rimParts, boolean tiles) {
			this.rimParts = rimParts;
			this.tiles = tiles;
			this.style = style;
			this.blurDp = blurDp;
			this.tint = tint;
			this.saturation = saturation;
			this.grain = grain;
			this.rim = rim;
			this.bars = bars;
			this.home = home;
			this.menus = menus;
			this.scale = scale;
		}

		public boolean isGlass() {
			return style != NORMAL;
		}

		/** The tint's alpha, 0 to 255. */
		public int tintAlpha() {
			return Math.round(Math.max(0f, Math.min(100f, tint)) * 2.55f);
		}

		/** The same look with at least {@code t} of tint (text on it must stay readable). */
		public Look withMinTint(float t) {
			return (tint >= t) ? this :
					new Look(style, blurDp, t, saturation, grain, rim, bars, home, menus, scale, rimParts, tiles);
		}

		/**
		 * This look as drawn on {@code part} (RIM_*): its rim light only where Settings > UI style > Rim light has
		 * it, fading in and out when that changes.
		 */
		public Look rimFor(int part) {
			float r = rim(part);
			if (r == rim) return this;
			if (r <= 0f) {
				Look n = noRim;
				if (n == null) noRim = n = withRim(0f);
				return n;
			}
			return withRim(r);
		}

		private Look withRim(float r) {
			return new Look(style, blurDp, tint, saturation, grain, r, bars, home, menus, scale, rimParts, tiles);
		}

		/** The same look with another blur (the settings previews draw it scaled down). */
		public Look withBlur(float dp) {
			return new Look(style, dp, tint, saturation, grain, rim, bars, home, menus, scale, rimParts, tiles);
		}

		boolean sameAs(Look o) {
			return (style == o.style) && (blurDp == o.blurDp) && (tint == o.tint) && (saturation == o.saturation) &&
					(grain == o.grain) && (rim == o.rim) && (bars == o.bars) && (home == o.home) &&
					(menus == o.menus) && (scale == o.scale) && (rimParts == o.rimParts) && (tiles == o.tiles);
		}
	}

	@Nullable
	private static Look target;
	@Nullable
	private static Look from;
	private static long animStart = -1;
	private static boolean ticking;
	@Nullable
	private static PreferenceStore.Listener listener;
	@Nullable
	private static Bitmap grainTile;
	private static final WeakHashMap<View, long[]> surfaces = new WeakHashMap<>();
	private static final List<Runnable> animListeners = new ArrayList<>();
	private static final int[] loc = new int[2];

	private Glass() {
	}

	/** Whether the blur itself can be drawn (RenderEffect, Android 12+); below, glass is the tint only. */
	@androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
	public static boolean realBlur() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
	}

	/** The look to draw now: the one set, or on its way to it after a change. */
	public static Look look() {
		Look t = target();
		if (animStart < 0) return t;
		float f = (SystemClock.uptimeMillis() - animStart) / (float) ANIM_MS;
		if ((f >= 1f) || (from == null)) {
			animStart = -1;
			return t;
		}
		// Ease out: most of the change at once, settling softly.
		f = 1f - (1f - f) * (1f - f);
		return mix(from, t, f);
	}

	/**
	 * How much glass there is now, 0 (Normal) to 1, gliding between the two after a change of style: what
	 * the settings tiles and the seek bar knob blend by.
	 */
	public static float amount() {
		Look t = target();
		return glide((from != null) && from.isGlass() ? 1f : 0f, t.isGlass() ? 1f : 0f);
	}

	/** As {@link #amount}, for the settings cards: 0 while they keep the Normal look (GLASS_TILES off). */
	public static float tileAmount() {
		Look t = target();
		return glide((from != null) && from.isGlass() && from.tiles ? 1f : 0f, t.isGlass() && t.tiles ? 1f : 0f);
	}

	/** How much rim light {@code part} (RIM_*) has now, 0 to 1, gliding after a change. */
	public static float rim(int part) {
		return glide((from != null) ? rimOf(from, part) : 0f, rimOf(target(), part));
	}

	private static float rimOf(Look l, int part) {
		return (l.isGlass() && ((l.rimParts & part) != 0)) ? l.rim : 0f;
	}

	/** From {@code fr} (the look before a change) to {@code to}, with the glide's curve. */
	private static float glide(float fr, float to) {
		if ((animStart < 0) || (from == null)) return to;
		float f = (SystemClock.uptimeMillis() - animStart) / (float) ANIM_MS;
		if (f >= 1f) return to;
		f = 1f - (1f - f) * (1f - f);
		return fr + (to - fr) * f;
	}

	/** The look set in the prefs, without the glide. */
	private static Look target() {
		Look l = target;
		if (l != null) return l;
		MainActivityPrefs p = MainActivityPrefs.get();
		if (listener == null) {
			// Held here: the store keeps its listeners weakly.
			listener = (store, prefs) -> {
				if (touches(prefs)) reset();
			};
			p.addBroadcastListener(listener);
		}
		int style = Math.max(0, Math.min(STYLE_COUNT - 1, p.getIntPref(MainActivityPrefs.GLASS_STYLE)));
		int q = p.getIntPref(MainActivityPrefs.GLASS_QUALITY);
		float scale = (q == QUALITY_HIGH) ? 1f : (q == QUALITY_SAVER) ? 0.25f : 0.5f;
		l = new Look(style, Math.max(0, p.getIntPref(MainActivityPrefs.GLASS_BLUR)),
				p.getIntPref(MainActivityPrefs.GLASS_TINT), p.getIntPref(MainActivityPrefs.GLASS_SATURATION) / 100f,
				p.getBooleanPref(MainActivityPrefs.GLASS_GRAIN) ? 1f : 0f,
				p.getBooleanPref(MainActivityPrefs.GLASS_RIM) ? 1f : 0f,
				p.getBooleanPref(MainActivityPrefs.GLASS_BARS), p.getBooleanPref(MainActivityPrefs.GLASS_HOME),
				p.getBooleanPref(MainActivityPrefs.GLASS_MENUS), scale, rimParts(p),
				p.getBooleanPref(MainActivityPrefs.GLASS_TILES));
		target = l;
		return l;
	}

	private static int rimParts(MainActivityPrefs p) {
		int m = 0;
		if (p.getBooleanPref(MainActivityPrefs.GLASS_RIM_TOOLBAR)) m |= RIM_TOOLBAR;
		if (p.getBooleanPref(MainActivityPrefs.GLASS_RIM_NAV)) m |= RIM_NAV;
		if (p.getBooleanPref(MainActivityPrefs.GLASS_RIM_FAB)) m |= RIM_FAB;
		if (p.getBooleanPref(MainActivityPrefs.GLASS_RIM_MENUS)) m |= RIM_MENUS;
		if (p.getBooleanPref(MainActivityPrefs.GLASS_RIM_HOME)) m |= RIM_HOME;
		if (p.getBooleanPref(MainActivityPrefs.GLASS_RIM_VIDEO)) m |= RIM_VIDEO;
		if (p.getBooleanPref(MainActivityPrefs.GLASS_RIM_TILES)) m |= RIM_TILES;
		return m;
	}

	/** NORMAL as a glass look: the usual mostly opaque surface, so a change to or from it glides too. */
	private static Look asGlass(Look n) {
		return new Look(n.style, 0f, 95f, 1f, 0f, 0f, n.bars, n.home, n.menus, n.scale, n.rimParts, n.tiles);
	}

	private static Look mix(Look a, Look b, float f) {
		if (!a.isGlass() && !b.isGlass()) return b;
		Look x = a.isGlass() ? a : asGlass(a);
		Look y = b.isGlass() ? b : asGlass(b);
		// Glass until the glide is over; the switches follow the look that has glass.
		Look flags = b.isGlass() ? b : a;
		return new Look(flags.style, lerp(x.blurDp, y.blurDp, f), lerp(x.tint, y.tint, f),
				lerp(x.saturation, y.saturation, f), lerp(x.grain, y.grain, f), lerp(x.rim, y.rim, f),
				flags.bars, flags.home, flags.menus, b.scale, flags.rimParts, flags.tiles);
	}

	private static float lerp(float a, float b, float f) {
		return a + (b - a) * f;
	}

	/**
	 * The prefs changed: read again, and glide from what is on screen to the new look, every surface
	 * drawn again each frame of it.
	 */
	public static void reset() {
		web = null;
		Look before = (target != null) ? look() : null;
		target = null;
		Look after = target();
		if ((before == null) || before.sameAs(after)) return;
		from = before;
		animStart = SystemClock.uptimeMillis();
		if (!ticking) {
			ticking = true;
			Choreographer.getInstance().postFrameCallback(tick);
		}
	}

	private static final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
		@Override
		public void doFrame(long frameTimeNanos) {
			look();
			invalidateSurfaces();
			for (Runnable r : new ArrayList<>(animListeners)) r.run();
			if (animStart >= 0) {
				Choreographer.getInstance().postFrameCallback(this);
			} else {
				ticking = false;
			}
		}
	};

	/** Called each frame while the look glides (Home's cards, the previews), and once at its end. */
	public static void addAnimListener(Runnable r) {
		if (!animListeners.contains(r)) animListeners.add(r);
	}

	public static void removeAnimListener(Runnable r) {
		animListeners.remove(r);
	}

	/** Whether a change of {@code prefs} changes the look. */
	public static boolean touches(List<PreferenceStore.Pref<?>> prefs) {
		return prefs.contains(MainActivityPrefs.GLASS_STYLE) || prefs.contains(MainActivityPrefs.GLASS_BLUR) ||
				prefs.contains(MainActivityPrefs.GLASS_TINT) || prefs.contains(MainActivityPrefs.GLASS_SATURATION) ||
				prefs.contains(MainActivityPrefs.GLASS_GRAIN) || prefs.contains(MainActivityPrefs.GLASS_RIM) ||
				prefs.contains(MainActivityPrefs.GLASS_BARS) || prefs.contains(MainActivityPrefs.GLASS_HOME) ||
				prefs.contains(MainActivityPrefs.GLASS_MENUS) || prefs.contains(MainActivityPrefs.GLASS_QUALITY) ||
				prefs.contains(MainActivityPrefs.GLASS_WEB) || prefs.contains(MainActivityPrefs.GLASS_TILES) ||
				prefs.contains(MainActivityPrefs.GLASS_RIM_TOOLBAR) || prefs.contains(MainActivityPrefs.GLASS_RIM_NAV) ||
				prefs.contains(MainActivityPrefs.GLASS_RIM_FAB) || prefs.contains(MainActivityPrefs.GLASS_RIM_MENUS) ||
				prefs.contains(MainActivityPrefs.GLASS_RIM_HOME) || prefs.contains(MainActivityPrefs.GLASS_RIM_VIDEO) ||
				prefs.contains(MainActivityPrefs.GLASS_RIM_TILES);
	}

	/** Glass on the tool bar, nav bar, control panel and floating buttons. */
	public static boolean onBars() {
		Look l = look();
		return l.isGlass() && l.bars;
	}

	/** Glass under Home's cards that have the Glass look. */
	public static boolean onHome() {
		Look l = look();
		return l.isGlass() && l.home;
	}

	/** Glass under menus and dialogs. */
	public static boolean onMenus() {
		Look l = look();
		return l.isGlass() && l.menus;
	}

	@Nullable
	private static Boolean web;

	/**
	 * Experimental (Settings > UI style): blur over web pages and fullscreen YouTube video too. A WebView draws
	 * through a functor that is then drawn a second time in the frame (in the lens), which Chromium does not
	 * promise to handle: it can flicker or show stale frames, depending on the device. Off by default.
	 * Local video (a SurfaceView) can't be read at all, so its glass blurs black even with this on.
	 */
	public static boolean webBlur() {
		Boolean w = web;
		if (w == null) web = w = realBlur() && MainActivityPrefs.get().getBooleanPref(MainActivityPrefs.GLASS_WEB);
		return w;
	}

	/**
	 * A fullscreen video button's glass: the picture behind it blurred when {@link #webBlur} is on and the
	 * picture can be read (YouTube's page, recorded by {@link GlassFrame}), else tint, grain and rim. Off glass
	 * it is the plain {@code color} disc.
	 */
	public static android.graphics.drawable.Drawable videoButton(View self, int color) {
		return new VideoButton(self, color);
	}

	private static final class VideoButton extends android.graphics.drawable.Drawable {
		private final View self;
		private final int color;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final GlassLens[] lens = new GlassLens[1];
		private int alpha = 255;

		VideoButton(View self, int color) {
			this.self = self;
			this.color = color;
		}

		@Override
		public void draw(Canvas c) {
			rect.set(getBounds());
			if (rect.isEmpty()) return;
			float r = Math.min(rect.width(), rect.height()) / 2f;
			float a = alpha / 255f;
			if (!onBars()) {
				paint.setShader(null);
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(color);
				paint.setAlpha(Math.round((color >>> 24) * a));
				c.drawRoundRect(rect, r, r, paint);
				return;
			}
			Look l = look();
			boolean blurred = webBlur() && (GlassFrame.drawBehind(self, c, rect, r, a, l, lens) ||
					VideoBackdrop.drawBehindButton(self, c, rect, r, a, l, lens));
			paintOver(c, rect, r, color, a, l.rimFor(RIM_VIDEO), false, self.getResources().getDisplayMetrics().density,
					paint, blurred);
		}

		@Override
		public void setAlpha(int alpha) {
			this.alpha = alpha;
			invalidateSelf();
		}

		@Override
		public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {
			paint.setColorFilter(cf);
		}

		@Override
		public int getOpacity() {
			return android.graphics.PixelFormat.TRANSLUCENT;
		}
	}

	/** Some glass surface wants the tab content recorded (see BodyLayout). */
	public static boolean wantsSource() {
		Look l = look();
		return l.isGlass() && (l.bars || l.menus);
	}

	/** The size the blur is rendered at now: the quality setting, a quarter while the power saver is on. */
	static float renderScale(Look l) {
		try {
			if (me.aap.fermata.addon.power.PowerSaver.get().isActive()) return Math.min(l.scale, 0.25f);
		} catch (Throwable ignore) {
		}
		return l.scale;
	}

	// ---------------------------------------------------------------------------------------------
	// Surfaces

	/** {@code v} draws the tab content through a lens: drawn again when the content changes. Weakly held. */
	public static void register(View v) {
		if (!surfaces.containsKey(v)) surfaces.put(v, new long[]{Long.MIN_VALUE});
	}

	public static void unregister(View v) {
		surfaces.remove(v);
	}

	/** The content under the surfaces changed: they are drawn again. */
	public static void invalidateSurfaces() {
		if (surfaces.isEmpty()) return;
		for (View v : new ArrayList<>(surfaces.keySet())) {
			if (v.isAttachedToWindow() && (v.getVisibility() == View.VISIBLE)) v.invalidate();
		}
	}

	/** Before a frame: a surface that moved (a bar sliding, a menu rising) is drawn again at its new place. */
	public static void syncSurfaces() {
		if (surfaces.isEmpty()) return;
		for (var e : new ArrayList<>(surfaces.entrySet())) {
			View v = e.getKey();
			if ((v == null) || !v.isAttachedToWindow() || (v.getVisibility() != View.VISIBLE)) continue;
			v.getLocationInWindow(loc);
			long key = (((long) loc[0]) << 32) ^ (loc[1] & 0xFFFFFFFFL) ^ (((long) v.getWidth()) << 48) ^
					(((long) v.getHeight()) << 16);
			long[] last = e.getValue();
			if (last[0] != key) {
				last[0] = key;
				v.invalidate();
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Picking a style

	/** Picks a style: its values are written into the prefs (which can then be tuned one by one). */
	public static void applyStyle(MainActivityPrefs p, int style) {
		try (var e = p.editPreferenceStore()) {
			e.setIntPref(MainActivityPrefs.GLASS_STYLE, style);
			int[] v = (style > 0) && (style < PRESETS.length) ? PRESETS[style] : null;
			if (v != null) {
				e.setIntPref(MainActivityPrefs.GLASS_BLUR, v[0]);
				e.setIntPref(MainActivityPrefs.GLASS_TINT, v[1]);
				e.setIntPref(MainActivityPrefs.GLASS_SATURATION, v[2]);
				e.setBooleanPref(MainActivityPrefs.GLASS_GRAIN, v[3] != 0);
				e.setBooleanPref(MainActivityPrefs.GLASS_RIM, v[4] != 0);
			}
		}
	}

	/** Whether the values now set are the style's own (else Settings says "Tuned"). */
	public static boolean isPresetValues(MainActivityPrefs p) {
		int style = p.getIntPref(MainActivityPrefs.GLASS_STYLE);
		int[] v = (style > 0) && (style < PRESETS.length) ? PRESETS[style] : null;
		if (v == null) return true;
		return (p.getIntPref(MainActivityPrefs.GLASS_BLUR) == v[0]) && (p.getIntPref(MainActivityPrefs.GLASS_TINT) == v[1]) &&
				(p.getIntPref(MainActivityPrefs.GLASS_SATURATION) == v[2]) &&
				(p.getBooleanPref(MainActivityPrefs.GLASS_GRAIN) == (v[3] != 0)) &&
				(p.getBooleanPref(MainActivityPrefs.GLASS_RIM) == (v[4] != 0));
	}

	/** A preset's look, for the style previews (every surface on, full quality). */
	public static Look preset(int style) {
		int[] v = (style > 0) && (style < PRESETS.length) ? PRESETS[style] : null;
		if (v == null) return new Look(NORMAL, 0, 0, 1f, 0f, 0f, true, true, true, 1f);
		return new Look(style, v[0], v[1], v[2] / 100f, v[3], v[4], true, true, true, 1f);
	}

	// ---------------------------------------------------------------------------------------------
	// Painting

	/**
	 * A glass surface's tint, grain and rim over whatever was drawn under {@code r} (the blur through a
	 * {@link GlassLens}, or nothing below Android 12 / over a web page or video). {@code rgb} is the
	 * surface's own colour (its alpha is ignored), {@code alpha} how much of the surface shows (a fading
	 * bar), {@code light} a light theme (the rim and grain are then darker and fainter).
	 */
	public static void paintOver(Canvas c, RectF r, float radius, int rgb, float alpha, Look l, boolean light,
															 float density, Paint paint) {
		paintOver(c, r, radius, rgb, alpha, l, light, density, paint, realBlur());
	}

	/** As above; {@code blurred} says whether a blur is under it (else the tint never goes thinner than 45%). */
	public static void paintOver(Canvas c, RectF r, float radius, int rgb, float alpha, Look l, boolean light,
															 float density, Paint paint, boolean blurred) {
		if (r.isEmpty() || (alpha <= 0f)) return;
		paint.setShader(null);
		paint.setStyle(Paint.Style.FILL);
		paint.clearShadowLayer();
		int ta = blurred ? l.tintAlpha() : Math.max(115, l.tintAlpha());
		paint.setColor((rgb & 0x00FFFFFF) | (Math.round(ta * alpha) << 24));
		c.drawRoundRect(r, radius, radius, paint);
		if (l.grain > 0f) {
			paint.setColor(0xFFFFFFFF);
			paint.setShader(grainShader());
			paint.setAlpha(Math.round((light ? 22 : 30) * alpha * l.grain));
			c.drawRoundRect(r, radius, radius, paint);
			paint.setShader(null);
		}
		if (l.rim > 0f) {
			float sw = Math.max(1f, density);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(sw);
			int top = light ? 0x99FFFFFF : 0x59FFFFFF;
			int bottom = light ? 0x14000000 : 0x0DFFFFFF;
			paint.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, bottom, Shader.TileMode.CLAMP));
			paint.setAlpha(Math.round(255 * alpha * l.rim));
			float h = sw / 2f;
			c.drawRoundRect(r.left + h, r.top + h, r.right - h, r.bottom - h, Math.max(0f, radius - h),
					Math.max(0f, radius - h), paint);
			paint.setShader(null);
			paint.setStyle(Paint.Style.FILL);
		}
	}

	/**
	 * A tile in the glass look for list rows and option tiles (settings rows, chips): a faint vertical
	 * sheen with a hairline rim, no blur (there are many of them, all inside the tab). Null while the UI
	 * style is Normal, so the caller keeps its own fill.
	 */
	@Nullable
	public static GradientDrawable tile(float radiusPx, boolean light) {
		if (!look().isGlass()) return null;
		GradientDrawable d = new GradientDrawable();
		d.setColor(tileFill(light));
		d.setCornerRadius(radiusPx);
		d.setStroke(1, tileRim(light));
		return d;
	}

	/** A glass tile's fill: flat, a see-through white (no gradient: it read as dated). */
	public static int tileFill(boolean light) {
		return light ? 0xB8FFFFFF : 0x1AFFFFFF;
	}

	/** A glass tile's hairline rim. */
	public static int tileRim(boolean light) {
		return light ? 0x17000000 : 0x26FFFFFF;
	}

	/**
	 * A row's tile that follows the UI style as it glides (see {@link #amount}): {@code normalFill} in Normal,
	 * the flat glass fill with a hairline rim in glass, blended on the way. {@code picked} adds the accent
	 * outline (and a tint of it in glass). A colour set on it from outside (a picked option) wins until
	 * {@code normalFill} is set again.
	 */
	public static GradientDrawable tile(int normalFill, float radiusPx, boolean light, boolean picked, int accent,
																			float density) {
		return new Tile(normalFill, radiusPx, light, picked, accent, density);
	}

	/** A tile made by {@link #tile(int, float, boolean, boolean, int, float)}: picked or not, gliding when asked. */
	public static void setPicked(android.graphics.drawable.Drawable d, boolean picked, boolean animate) {
		if (d instanceof Tile t) t.setPicked(picked, animate);
	}

	public static boolean isTile(@Nullable android.graphics.drawable.Drawable d) {
		return d instanceof Tile;
	}

	private static final class Tile extends GradientDrawable {
		private final int normalFill;
		private final boolean light;
		private final int accent;
		private final float density;
		private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rimRect = new RectF();
		/** 0 not picked, 1 picked: glides between the two (see {@link #setPicked}). */
		private float pick;
		@Nullable
		private android.animation.ValueAnimator pickAnim;
		private float shownAmount = -1f;
		private float shownPick = -1f;
		private boolean internal;
		/** A colour set from outside (other than the normal fill): kept, see {@link #tile}. */
		private int manual;
		private boolean hasManual;

		Tile(int normalFill, float radius, boolean light, boolean picked, int accent, float density) {
			// (shownAmount etc. are set by apply below.)
			this.normalFill = normalFill;
			this.light = light;
			this.accent = accent;
			this.density = density;
			pick = picked ? 1f : 0f;
			setCornerRadius(radius);
			rimPaint.setStyle(Paint.Style.STROKE);
			// (Whatever the superclass set up is not a colour from outside.)
			hasManual = false;
			apply(tileAmount(), pick);
		}

		void setPicked(boolean picked, boolean animate) {
			float to = picked ? 1f : 0f;
			if (pickAnim != null) pickAnim.cancel();
			pickAnim = null;
			if (!animate) {
				pick = to;
				invalidateSelf();
				return;
			}
			if (pick == to) return;
			android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(pick, to);
			va.setDuration(220);
			va.setInterpolator(new android.view.animation.DecelerateInterpolator());
			va.addUpdateListener(v -> {
				pick = (float) v.getAnimatedValue();
				invalidateSelf();
			});
			pickAnim = va;
			va.start();
		}

		@Override
		public void setColor(int argb) {
			if (!internal) {
				hasManual = argb != normalFill;
				manual = argb;
				shownAmount = -1f;
			}
			super.setColor(argb);
		}

		@Override
		public void draw(Canvas c) {
			float a = tileAmount();
			if ((a != shownAmount) || (pick != shownPick)) apply(a, pick);
			super.draw(c);
			// The rim light where Settings > UI style > Rim light has it on the settings cards (off by default).
			float rim = rim(RIM_TILES) * (1f - pick);
			if (rim <= 0f) return;
			float sw = Math.max(1f, density);
			android.graphics.Rect b = getBounds();
			float h = sw / 2f;
			rimRect.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
			rimPaint.setStrokeWidth(sw);
			rimPaint.setShader(new LinearGradient(0, b.top, 0, b.bottom, light ? 0xB3FFFFFF : 0x66FFFFFF,
					light ? 0x0F000000 : 0x08FFFFFF, Shader.TileMode.CLAMP));
			rimPaint.setAlpha(Math.round(255 * rim));
			float r = Math.max(0f, getCornerRadius() - h);
			c.drawRoundRect(rimRect, r, r, rimPaint);
		}

		private void apply(float a, float p) {
			shownAmount = a;
			shownPick = p;
			internal = true;
			try {
				int nf = androidx.core.graphics.ColorUtils.blendARGB(normalFill,
						androidx.core.graphics.ColorUtils.blendARGB(normalFill, accent, 0.14f), p);
				int gf = androidx.core.graphics.ColorUtils.blendARGB(tileFill(light), (accent & 0x00FFFFFF) | 0x33000000, p);
				super.setColor(hasManual ? manual : androidx.core.graphics.ColorUtils.blendARGB(nf, gf, a));
				// The picked outline fades in and out with the pick; always the same width, so nothing moves.
				int outline = (accent & 0x00FFFFFF) | (Math.round(255 * p) << 24);
				setStroke(Math.max(1, Math.round(2f * density)), outline);
			} finally {
				internal = false;
			}
		}
	}

	/**
	 * A seek bar's knob that follows the UI style: the plain filled disc in Normal; in glass a see-through
	 * bead with a bright rim and the accent in its middle. Replaces the thumb and its tint.
	 */
	public static void knob(android.widget.SeekBar sb) {
		if (sb.getThumb() instanceof Knob) return;
		android.content.res.ColorStateList tl = sb.getThumbTintList();
		int accent = (tl != null) ? tl.getDefaultColor() : 0xFF7FA6FF;
		float d = sb.getResources().getDisplayMetrics().density;
		sb.setThumbTintList(null);
		Knob k = new Knob(accent, d);
		sb.setThumb(k);
		// The filled part of the track ends round under the bead in glass (the bead is see-through, and the
		// track's square cut showed through it).
		if ((sb.getProgressDrawable() instanceof android.graphics.drawable.LayerDrawable pd) &&
				(pd.mutate() instanceof android.graphics.drawable.LayerDrawable ld) &&
				(ld.findDrawableByLayerId(android.R.id.progress) instanceof android.graphics.drawable.ClipDrawable cd)) {
			RoundFill f = new RoundFill(cd, k);
			f.setBounds(cd.getBounds());
			f.setLevel(cd.getLevel());
			f.setState(cd.getState());
			ld.setDrawableByLayerId(android.R.id.progress, f);
			ld.invalidateSelf();
		}
	}

	/**
	 * A seek bar's filled track: the usual clipped bar in Normal; in glass a pill from the start to the level,
	 * with a round end that sits under the knob. Blends with the style's glide.
	 */
	private static final class RoundFill extends android.graphics.drawable.Drawable
			implements android.graphics.drawable.Drawable.Callback {
		private final android.graphics.drawable.Drawable inner;
		private final Knob knob;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		@Nullable
		private android.content.res.ColorStateList tint;
		private int alpha = 255;

		RoundFill(android.graphics.drawable.Drawable inner, Knob knob) {
			this.inner = inner;
			this.knob = knob;
			inner.setCallback(this);
		}

		@Override
		public void draw(Canvas c) {
			float g = amount();
			if (g < 1f) {
				inner.setAlpha(Math.round(alpha * (1f - g)));
				inner.draw(c);
			}
			if (g <= 0f) return;
			android.graphics.Rect b = getBounds();
			float h = b.height();
			if ((h <= 0) || (b.width() <= 0)) return;
			float x = b.left + b.width() * (getLevel() / 10000f);
			if (x <= b.left) return;
			// At least a circle: a round start that grows into the pill.
			rect.set(b.left, b.top, Math.max(x, b.left + h), b.bottom);
			int color = (tint != null) ? tint.getColorForState(getState(), tint.getDefaultColor()) : knob.accent;
			paint.setColor(color);
			paint.setAlpha(Math.round((color >>> 24) * (alpha / 255f) * g));
			c.drawRoundRect(rect, h / 2f, h / 2f, paint);
		}

		@Override
		protected boolean onLevelChange(int level) {
			inner.setLevel(level);
			invalidateSelf();
			return true;
		}

		@Override
		protected void onBoundsChange(android.graphics.Rect bounds) {
			inner.setBounds(bounds);
		}

		@Override
		public boolean isStateful() {
			return true;
		}

		@Override
		protected boolean onStateChange(int[] state) {
			inner.setState(state);
			invalidateSelf();
			return true;
		}

		@Override
		public void setTintList(@Nullable android.content.res.ColorStateList t) {
			tint = t;
			inner.setTintList(t);
			invalidateSelf();
		}

		@Override
		public void setTintMode(@Nullable android.graphics.PorterDuff.Mode mode) {
			inner.setTintMode(mode);
		}

		@Override
		public int getIntrinsicHeight() {
			return inner.getIntrinsicHeight();
		}

		@Override
		public int getIntrinsicWidth() {
			return inner.getIntrinsicWidth();
		}

		@Override
		public void setAlpha(int alpha) {
			this.alpha = alpha;
			invalidateSelf();
		}

		@Override
		public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {
			paint.setColorFilter(cf);
			inner.setColorFilter(cf);
		}

		@Override
		public int getOpacity() {
			return android.graphics.PixelFormat.TRANSLUCENT;
		}

		@Override
		public void invalidateDrawable(@androidx.annotation.NonNull android.graphics.drawable.Drawable who) {
			invalidateSelf();
		}

		@Override
		public void scheduleDrawable(@androidx.annotation.NonNull android.graphics.drawable.Drawable who,
																 @androidx.annotation.NonNull Runnable what, long when) {
			scheduleSelf(what, when);
		}

		@Override
		public void unscheduleDrawable(@androidx.annotation.NonNull android.graphics.drawable.Drawable who,
																	 @androidx.annotation.NonNull Runnable what) {
			unscheduleSelf(what);
		}
	}

	private static final class Knob extends android.graphics.drawable.Drawable {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private int accent;
		private final float density;
		private final int size;
		private int alpha = 255;

		Knob(int accent, float density) {
			this.accent = accent;
			this.density = density;
			size = Math.round(20 * density);
		}

		/** A thumb tint given to the seek bar later (a slider in another colour) is the disc's colour. */
		@Override
		public void setTintList(@Nullable android.content.res.ColorStateList tint) {
			if (tint != null) {
				accent = tint.getDefaultColor();
				invalidateSelf();
			}
		}

		@Override
		public int getIntrinsicWidth() {
			return size;
		}

		@Override
		public int getIntrinsicHeight() {
			return size;
		}

		@Override
		public void draw(Canvas c) {
			android.graphics.Rect b = getBounds();
			float cx = b.exactCenterX();
			float cy = b.exactCenterY();
			float r = Math.min(b.width(), b.height()) / 2f;
			float g = amount();
			float a = alpha / 255f;
			paint.setStyle(Paint.Style.FILL);
			paint.setShader(null);
			if (g < 1f) {
				paint.setColor(accent);
				paint.setAlpha(Math.round(255 * a * (1f - g)));
				c.drawCircle(cx, cy, r, paint);
			}
			if (g <= 0f) return;
			// The bead: a see-through white body, lighter at the top, and a light rim brightest at the top (no
			// coloured core: the track's filled part says where it is).
			paint.setShader(new LinearGradient(0, cy - r, 0, cy + r, 0x8CFFFFFF, 0x40FFFFFF, Shader.TileMode.CLAMP));
			paint.setAlpha(Math.round(255 * a * g));
			c.drawCircle(cx, cy, r, paint);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(1.5f * density);
			paint.setShader(new LinearGradient(0, cy - r, 0, cy + r, 0xFFFFFFFF, 0x66FFFFFF, Shader.TileMode.CLAMP));
			paint.setAlpha(Math.round(255 * a * g));
			c.drawCircle(cx, cy, r - 0.75f * density, paint);
			paint.setShader(null);
			paint.setStyle(Paint.Style.FILL);
		}

		@Override
		public void setAlpha(int alpha) {
			this.alpha = alpha;
			invalidateSelf();
		}

		@Override
		public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {
			paint.setColorFilter(cf);
		}

		@Override
		public int getOpacity() {
			return android.graphics.PixelFormat.TRANSLUCENT;
		}
	}

	/** Every view under {@code v} drawn again (the glide reaching tiles and knobs that draw by {@link #amount}). */
	public static void invalidateTree(View v) {
		v.invalidate();
		if (v instanceof android.view.ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) invalidateTree(g.getChildAt(i));
		}
	}

	/**
	 * A surface with no blur behind it (over fullscreen video, which nothing can read): glass tint, grain
	 * and rim while the bars are glass, else the plain {@code color}. {@code radius} below 0 = a pill /
	 * disc (half the short side).
	 */
	public static android.graphics.drawable.Drawable surface(int color, float radius, float density) {
		return new Surface(color, radius, density);
	}

	private static final class Surface extends android.graphics.drawable.Drawable {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final int color;
		private final float radius;
		private final float density;
		private int alpha = 255;

		Surface(int color, float radius, float density) {
			this.color = color;
			this.radius = radius;
			this.density = density;
		}

		@Override
		public void draw(Canvas c) {
			rect.set(getBounds());
			if (rect.isEmpty()) return;
			float r = (radius < 0) ? Math.min(rect.width(), rect.height()) / 2f : radius;
			float a = alpha / 255f;
			if (onBars()) {
				paintOver(c, rect, r, color, a, look().rimFor(RIM_VIDEO), false, density, paint, false);
			} else {
				paint.setShader(null);
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(color);
				paint.setAlpha(Math.round((color >>> 24) * a));
				c.drawRoundRect(rect, r, r, paint);
			}
		}

		@Override
		public void setAlpha(int alpha) {
			this.alpha = alpha;
			invalidateSelf();
		}

		@Override
		public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {
			paint.setColorFilter(cf);
		}

		@Override
		public int getOpacity() {
			return android.graphics.PixelFormat.TRANSLUCENT;
		}
	}

	/** A 96 px tile of soft noise, repeated: the frosted look's grain. Made once. */
	private static Shader grainShader() {
		Bitmap t = grainTile;
		if (t == null) {
			int n = 96;
			int[] px = new int[n * n];
			Random rnd = new Random(7);
			for (int i = 0; i < px.length; i++) {
				int v = rnd.nextInt(256);
				// Light and dark specks around grey, most of them faint.
				int a = (int) (Math.abs(rnd.nextGaussian()) * 60);
				px[i] = (Math.min(255, a) << 24) | (v << 16) | (v << 8) | v;
			}
			t = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
			grainTile = t;
		}
		return new BitmapShader(t, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
	}

	/**
	 * Whether {@code v} holds a WebView that is on screen: such content is never drawn through a lens
	 * (see the class comment).
	 */
	public static boolean hasWebView(@Nullable View v) {
		if ((v == null) || (v.getVisibility() != View.VISIBLE)) return false;
		if (v instanceof android.webkit.WebView) return v.getWidth() > 0;
		if (v instanceof android.view.ViewGroup g) {
			for (int i = 0, n = g.getChildCount(); i < n; i++) {
				if (hasWebView(g.getChildAt(i))) return true;
			}
		}
		return false;
	}
}
