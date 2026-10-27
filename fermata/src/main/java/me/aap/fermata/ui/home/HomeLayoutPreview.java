package me.aap.fermata.ui.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.SettingsStyle;
import me.aap.utils.app.App;
import me.aap.utils.pref.PreferenceStore;

/**
 * A small picture of a Home screen as the settings describe it, drawn live from them: a phone or the car's display with
 * the greeting, the info chips, the picture behind (dimmed, zoomed, blurred) and a sample set of cards packed the way
 * the arrangement does (scrolling down, sideways, or freeflow, which leaves a gap where a card was taken away), with as
 * many columns or rows and as much room between them as asked. The cards that run past the edge are cut off, as they would
 * scroll. Used big, on top of the Home settings, and small, one for each arrangement to pick from.
 * <p>
 * It moves: when a setting changes the cards glide from where they were to where they now go, and the picture that shows the
 * arrangement in use scrolls gently along the way its page would, to show what it does.
 */
public final class HomeLayoutPreview extends View implements PreferenceStore.Listener {
	public static final int AUTO = 0;
	public static final int DOWN = 1;
	public static final int SIDE = 2;
	public static final int FREE = 3;

	/** The sample cards: width x height in grid units; the first one is the "now playing" card. */
	private static final int[][] SAMPLE = {{4, 2}, {2, 2}, {2, 2}, {2, 1}, {2, 1}, {4, 1}, {2, 2}, {2, 2}, {4, 2}, {2, 1}, {2, 1}, {2, 2}};
	/** Freeflow leaves this card's place empty: cards left where they were dropped do not fill every cell. */
	private static final int[] FREE_HOLES = {3, 6};
	private static final int N = SAMPLE.length;
	private static final long MOVE_MS = 420;
	private static final float SCROLL_DP_PER_S = 22f;

	private final MainActivityPrefs prefs;
	private final boolean car;
	/** The arrangement this one always shows (a card to pick from), or -1 to follow the settings. */
	private final int fixedMode;
	private final SettingsStyle.Colors c;
	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF r = new RectF();
	private final Path clip = new Path();
	private final Interpolator ease = new DecelerateInterpolator(1.8f);

	private int mode;
	private int columns;
	private int gap;
	/** The widgets' size and the room outside them, as the settings have them (the same as the grid's). */
	private float scale = 1f;
	private float padding = 12f;
	private boolean greeting;
	private boolean overlay;
	private boolean editButton;
	private int dim;
	private int zoom;
	private int source;
	/** Whether the shown arrangement scrolls down (else sideways). */
	private boolean down = true;
	/** How far the content can scroll, in dp, along its way. */
	private float scrollRange;
	@Nullable
	private Bitmap picture;
	private int pictureVersion = -1;
	private int pictureBlur = -1;
	private int pictureSource = -1;
	private int loadGen;

	// Where each card is (x, y, w, h in dp, and whether it is drawn at all), where it was, and how far it has come.
	private float[][] target = new float[N][5];
	private float[][] origin = new float[N][5];
	private float[][] now = new float[N][5];
	private boolean haveLayout;
	private long moveStart;
	private boolean moving;
	private long frameClock;
	private float scrollPhase;
	private boolean running;

	public HomeLayoutPreview(@NonNull Context ctx, @NonNull MainActivityPrefs prefs, boolean car, int fixedMode) {
		super(ctx);
		this.prefs = prefs;
		this.car = car;
		this.fixedMode = fixedMode;
		c = new SettingsStyle.Colors(ctx);
		reread();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		prefs.addBroadcastListener(this);
		reread();
		startFrames();
	}

	@Override
	protected void onDetachedFromWindow() {
		prefs.removeBroadcastListener(this);
		loadGen++;
		running = false;
		super.onDetachedFromWindow();
	}

	@Override
	public void onVisibilityAggregated(boolean visible) {
		super.onVisibilityAggregated(visible);
		if (visible) startFrames();
		else running = false;
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> changed) {
		post(this::reread);
	}

	/** Takes the settings as they are now; the cards glide to where they now go. */
	public void reread() {
		mode = (fixedMode >= 0) ? fixedMode : prefs.getIntPref(car ? MainActivityPrefs.HOME_ARRANGE_AA : MainActivityPrefs.HOME_ARRANGE);
		columns = prefs.getIntPref(car ? MainActivityPrefs.HOME_COLUMNS_AA : MainActivityPrefs.HOME_COLUMNS);
		gap = Math.max(4, Math.min(20, prefs.getIntPref(car ? MainActivityPrefs.HOME_GAP_AA : MainActivityPrefs.HOME_GAP)));
		scale = prefs.getIntPref(car ? MainActivityPrefs.HOME_SCALE_AA : MainActivityPrefs.HOME_SCALE) / 100f;
		int pad = prefs.getIntPref(car ? MainActivityPrefs.HOME_PADDING_AA : MainActivityPrefs.HOME_PADDING);
		padding = (pad < 0) ? 12f : pad;
		greeting = prefs.getBooleanPref(car ? MainActivityPrefs.HOME_GREETING_AA : MainActivityPrefs.HOME_GREETING);
		overlay = prefs.getBooleanPref(car ? MainActivityPrefs.HOME_TOP_OVERLAY_AA : MainActivityPrefs.HOME_TOP_OVERLAY);
		editButton = prefs.getBooleanPref(car ? MainActivityPrefs.HOME_EDIT_BUTTON_AA : MainActivityPrefs.HOME_EDIT_BUTTON);
		dim = prefs.getIntPref(HomeBgPrefs.dim(car));
		zoom = Math.max(100, prefs.getIntPref(HomeBgPrefs.zoom(car)));
		source = prefs.getIntPref(HomeBgPrefs.source(car));
		loadPicture();
		computeLayout();
		invalidate();
		startFrames();
	}

	private void loadPicture() {
		int version = prefs.getIntPref(HomeBgPrefs.version(car));
		int blur = prefs.getIntPref(HomeBgPrefs.blur(car));
		if ((version == pictureVersion) && (blur == pictureBlur) && (source == pictureSource)) return;
		pictureVersion = version;
		pictureBlur = blur;
		pictureSource = source;
		int gen = ++loadGen;
		Context ctx = getContext();
		if (!HomeBackground.exists(ctx, car) || (source != 0)) {
			picture = null;
			return;
		}
		App.get().getExecutor().submitTask(() -> HomeBackground.load(ctx, car, blur, 360, 360)).main().onSuccess(l -> {
			if (gen != loadGen) return;
			picture = (l == null) ? null : l.bitmap();
			invalidate();
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Where the cards go

	/** The screen's shape in dp (what the packing is worked out for). */
	private float screenW() {
		return car ? 1100f : 392f;
	}

	private float screenH() {
		return car ? 540f : 850f;
	}

	/** Packs the sample cards the way the arrangement does and sets them to glide there from where they are. */
	private void computeLayout() {
		float w = screenW();
		float h = screenH();
		int shown = (mode == AUTO) ? (((w > h) && (h < 600f)) ? SIDE : DOWN) : mode;
		down = shown != SIDE;
		float side = padding;
		float top = down ? (16f + (greeting ? (car ? 54f : 62f) : 38f)) : 58f;
		int n;
		float cell;
		if (down) {
			n = (columns > 0) ? columns : Math.max(4, Math.min(8, (int) (w / 90f)));
			n = Math.max(2, Math.min(8, n));
			cell = (w - 2 * side - (n - 1) * gap) / n;
		} else {
			n = (columns > 0) ? columns : Math.max(2, Math.min(5, (int) (h / 105f)));
			n = Math.max(2, Math.min(5, n));
			cell = (h - top - 8f - (n - 1) * gap) / n;
		}
		// The same as the grid: the cards' size follows the scale and as many lanes fit at that size (Freeflow scales its
		// places instead); a block narrower than the page is centred.
		if (shown == FREE) {
			cell *= scale;
		} else {
			float avail = down ? (w - 2 * side) : (h - top - 8f);
			cell = Math.max(1f, cell * scale);
			n = Math.max(1, Math.min(12, (int) ((avail + gap) / (cell + gap))));
		}
		float lead = down ? Math.max(0f, (w - 2 * side - (n * cell + (n - 1) * gap)) / 2f) : 0f;

		float[][] next = new float[N][5];
		boolean[][] used = new boolean[down ? n : 80][down ? 80 : n];
		float far = 0f;
		for (int i = 0; i < N; i++) {
			int iw = down ? Math.min(SAMPLE[i][0], n) : SAMPLE[i][0];
			int ih = down ? SAMPLE[i][1] : Math.min(SAMPLE[i][1], n);
			boolean done = false;
			for (int a = 0; (a < 78) && !done; a++) {
				for (int b = 0; (b <= (down ? n - iw : n - ih)) && !done; b++) {
					int x = down ? b : a;
					int y = down ? a : b;
					if (!fits(used, x, y, iw, ih)) continue;
					for (int p = 0; p < iw; p++) for (int q = 0; q < ih; q++) used[x + p][y + q] = true;
					float px = side + (down ? lead : 0f) + x * (cell + gap);
					float py = top + y * (cell + gap);
					float cw = iw * cell + (iw - 1) * gap;
					float ch = ih * cell + (ih - 1) * gap;
					if (shown == FREE) {
						// Cards left where they were dropped, a little off the grid.
						px += ((i * 7) % 5 - 2) * 2.5f;
						py += ((i * 11) % 5 - 2) * 2.5f;
					}
					next[i][0] = px;
					next[i][1] = py;
					next[i][2] = cw;
					next[i][3] = ch;
					boolean hole = false;
					if (shown == FREE) for (int hi : FREE_HOLES) if (hi == i) hole = true;
					next[i][4] = hole ? 0f : 1f;
					far = Math.max(far, down ? (py + ch) : (px + cw));
					done = true;
				}
			}
		}
		scrollRange = Math.max(0f, far - (down ? h : w) + (down ? 0f : side) + 30f);

		if (!haveLayout) {
			copy(next, target);
			copy(next, now);
			haveLayout = true;
			return;
		}
		boolean same = true;
		for (int i = 0; (i < N) && same; i++) for (int k = 0; k < 5; k++) if (Math.abs(next[i][k] - target[i][k]) > 0.01f) same = false;
		if (same) return;
		copy(now, origin);
		copy(next, target);
		moveStart = SystemClock.uptimeMillis();
		moving = isAttachedToWindow();
		if (!moving) copy(target, now);
	}

	private static void copy(float[][] from, float[][] to) {
		for (int i = 0; i < from.length; i++) System.arraycopy(from[i], 0, to[i], 0, from[i].length);
	}

	private static boolean fits(boolean[][] used, int x, int y, int w, int h) {
		if ((x + w > used.length) || (y + h > used[0].length)) return false;
		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) if (used[x + i][y + j]) return false;
		}
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// Moving

	/** Whether this picture shows its page scrolling: the big one, and the one of the arrangement in use. */
	private boolean scrolls() {
		if (scrollRange <= 0f) return false;
		if (fixedMode < 0) return true;
		return fixedMode == prefs.getIntPref(car ? MainActivityPrefs.HOME_ARRANGE_AA : MainActivityPrefs.HOME_ARRANGE);
	}

	private void startFrames() {
		if (running || !isAttachedToWindow() || (getVisibility() != VISIBLE)) return;
		running = true;
		frameClock = SystemClock.uptimeMillis();
		postOnAnimation(frame);
	}

	private final Runnable frame = new Runnable() {
		@Override
		public void run() {
			if (!running) return;
			long t = SystemClock.uptimeMillis();
			float dt = Math.min(0.1f, (t - frameClock) / 1000f);
			frameClock = t;
			boolean again = false;
			if (moving) {
				float f = Math.min(1f, (t - moveStart) / (float) MOVE_MS);
				float e = ease.getInterpolation(f);
				for (int i = 0; i < N; i++) {
					for (int k = 0; k < 4; k++) now[i][k] = origin[i][k] + (target[i][k] - origin[i][k]) * e;
					// A card that appears or goes grows or shrinks away.
					now[i][4] = origin[i][4] + (target[i][4] - origin[i][4]) * e;
				}
				if (f >= 1f) {
					moving = false;
					copy(target, now);
				}
				again = true;
			}
			if (scrolls()) {
				scrollPhase += dt;
				again = true;
			}
			invalidate();
			if (again) postOnAnimation(this);
			else running = false;
		}
	};

	/** How far the page has scrolled now, in dp: down and back again, easing at both ends, resting for a moment first. */
	private float scrollOffset() {
		if (!scrolls()) return 0f;
		float period = Math.max(4f, scrollRange / SCROLL_DP_PER_S);
		float t = (scrollPhase % (period * 2f + 2f));
		float f;
		if (t < 1f) f = 0f;
		else if (t < 1f + period) f = (t - 1f) / period;
		else if (t < 1f + period + 1f) f = 1f;
		else f = 1f - (t - 2f - period) / period;
		f = Math.max(0f, Math.min(1f, f));
		f = f * f * (3f - 2f * f);
		return f * scrollRange;
	}

	// ---------------------------------------------------------------------------------------------
	// Drawing

	@Override
	protected void onMeasure(int wSpec, int hSpec) {
		// The device's shape: a phone is tall, the car's display wide; it takes the room it is given, never stretched.
		float aspect = screenW() / screenH();
		int w = MeasureSpec.getSize(wSpec);
		int h = MeasureSpec.getSize(hSpec);
		int wm = MeasureSpec.getMode(wSpec);
		int hm = MeasureSpec.getMode(hSpec);
		if ((hm == MeasureSpec.UNSPECIFIED) || (hm == MeasureSpec.AT_MOST && wm == MeasureSpec.EXACTLY)) {
			h = Math.round(w / aspect) + c.dp(8);
			if (hm == MeasureSpec.AT_MOST) h = Math.min(h, MeasureSpec.getSize(hSpec));
		}
		setMeasuredDimension(w, h);
	}

	@Override
	protected void onDraw(@NonNull Canvas cv) {
		float pad = c.dp(4);
		float aspect = screenW() / screenH();
		float availW = getWidth() - 2 * pad;
		float availH = getHeight() - 2 * pad;
		float dw = availW;
		float dh = dw / aspect;
		if (dh > availH) {
			dh = availH;
			dw = dh * aspect;
		}
		float dx = (getWidth() - dw) / 2f;
		float dy = (getHeight() - dh) / 2f;
		float k = dw / screenW();

		// The device.
		float body = car ? 12f * k : 34f * k;
		fill.setStyle(Paint.Style.FILL);
		fill.setShader(null);
		fill.setColor(0xFF0A0D12);
		r.set(dx, dy, dx + dw, dy + dh);
		cv.drawRoundRect(r, body, body, fill);
		float bezel = car ? 6f * k : 9f * k;
		RectF scr = new RectF(dx + bezel, dy + bezel, dx + dw - bezel, dy + dh - bezel);
		float sr = Math.max(2f, body - bezel);
		float sk = scr.width() / screenW();
		cv.save();
		clip.reset();
		clip.addRoundRect(scr, sr, sr, Path.Direction.CW);
		cv.clipPath(clip);
		drawBackground(cv, scr);
		drawContent(cv, scr, sk);
		cv.restore();
	}

	private void drawBackground(Canvas cv, RectF scr) {
		if (picture != null) {
			float bw = picture.getWidth();
			float bh = picture.getHeight();
			float sc = Math.max(scr.width() / bw, scr.height() / bh) * (zoom / 100f);
			float w = bw * sc;
			float h = bh * sc;
			r.set(scr.centerX() - w / 2f, scr.centerY() - h / 2f, scr.centerX() + w / 2f, scr.centerY() + h / 2f);
			cv.drawBitmap(picture, null, r, null);
		} else {
			int top = mix(0xFF000000, c.accent, (source == 1) ? 0.55f : 0.32f);
			fill.setShader(new LinearGradient(0, scr.top, 0, scr.bottom, top, 0xFF080A0E, Shader.TileMode.CLAMP));
			cv.drawRect(scr, fill);
			fill.setShader(null);
		}
		fill.setColor(((Math.round(Math.min(80, dim) / 100f * 255f * 0.9f)) << 24));
		cv.drawRect(scr, fill);
	}

	private void drawContent(Canvas cv, RectF scr, float k) {
		float w = screenW();
		float scroll = scrollOffset();

		// The cards, scrolled along the way the page scrolls.
		cv.save();
		cv.clipRect(scr);
		for (int i = 0; i < N; i++) {
			float[] p = now[i];
			if (p[4] <= 0.02f) continue;
			float x = p[0] - (down ? 0f : scroll);
			float y = p[1] - (down ? scroll : 0f);
			if (p[4] < 1f) {
				// Coming or going: grows or shrinks about its middle.
				float s = p[4];
				x += p[2] * (1f - s) / 2f;
				y += p[3] * (1f - s) / 2f;
				card(cv, scr, k, x, y, p[2] * s, p[3] * s, i == 0, s);
			} else {
				card(cv, scr, k, x, y, p[2], p[3], i == 0, 1f);
			}
		}
		cv.restore();

		// The header stays where it is while the cards pass under it: greeting words, info chips and the edit button.
		float headTop = 14f;
		if (greeting) {
			bar(cv, scr, k, 16f, headTop, car ? 120f : 130f, car ? 14f : 16f, 0x66FFFFFF, 4f);
			bar(cv, scr, k, 16f, headTop + (car ? 24f : 26f), car ? 80f : 90f, car ? 8f : 9f, 0x33FFFFFF, 3f);
		}
		if (overlay) {
			float cx = w - 16f - (editButton ? 44f : 0f);
			for (int i = 0; i < 3; i++) {
				float cw = car ? 46f : 40f;
				cx -= cw + 6f;
				bar(cv, scr, k, cx, headTop + 2f, cw, car ? 16f : 18f, 0x66FFFFFF, 8f);
			}
		}
		if (editButton) bar(cv, scr, k, w - 16f - 30f, headTop, 30f, 30f, 0x66FFFFFF, 15f);

		// Past the edge it scrolls on: a soft fade says so, and a small arrow shows the way.
		float fadeLen = car ? 70f : 90f;
		if (down) {
			fill.setShader(new LinearGradient(0, scr.bottom - fadeLen * k, 0, scr.bottom, 0x00080A0E, 0xCC080A0E, Shader.TileMode.CLAMP));
			r.set(scr.left, scr.bottom - fadeLen * k, scr.right, scr.bottom);
		} else {
			fill.setShader(new LinearGradient(scr.right - fadeLen * k, 0, scr.right, 0, 0x00080A0E, 0xCC080A0E, Shader.TileMode.CLAMP));
			r.set(scr.right - fadeLen * k, scr.top, scr.right, scr.bottom);
		}
		cv.drawRect(r, fill);
		fill.setShader(null);
		fill.setColor(0xCCFFFFFF);
		float ax = down ? scr.centerX() : scr.right - 12f * k;
		float ay = down ? scr.bottom - 12f * k : scr.centerY();
		float as = 6f * k;
		clip.reset();
		if (down) {
			clip.moveTo(ax - as, ay - as * 0.5f);
			clip.lineTo(ax + as, ay - as * 0.5f);
			clip.lineTo(ax, ay + as * 0.6f);
		} else {
			clip.moveTo(ax - as * 0.5f, ay - as);
			clip.lineTo(ax - as * 0.5f, ay + as);
			clip.lineTo(ax + as * 0.6f, ay);
		}
		clip.close();
		cv.drawPath(clip, fill);
	}

	/** One sample card at {@code x, y} (dp from the screen's corner), the now-playing one in the accent. */
	private void card(Canvas cv, RectF scr, float k, float x, float y, float w, float h, boolean media, float alpha) {
		fill.setStyle(Paint.Style.FILL);
		fill.setColor(media ? mix(0xFF101418, c.accent, 0.62f) : 0xFF1B2230);
		fill.setAlpha(Math.round((media ? 235 : 215) * alpha));
		r.set(scr.left + x * k, scr.top + y * k, scr.left + (x + w) * k, scr.top + (y + h) * k);
		float rad = Math.min(14f, Math.min(w, h) * 0.22f) * k;
		cv.drawRoundRect(r, rad, rad, fill);
		if (alpha < 0.6f) return;
		float lx = x + 10f;
		bar(cv, scr, k, lx, y + 10f, Math.max(8f, w * 0.45f), 8f, media ? 0xCCFFFFFF : 0x77FFFFFF, 4f);
		if (h > 70f) bar(cv, scr, k, lx, y + 24f, Math.max(8f, w * 0.3f), 6f, 0x33FFFFFF, 3f);
		if (media && (h > 90f)) {
			bar(cv, scr, k, lx, y + h - 22f, Math.max(8f, w - 20f), 5f, 0x55FFFFFF, 2.5f);
		}
	}

	private void bar(Canvas cv, RectF scr, float k, float x, float y, float w, float h, int color, float radius) {
		fill.setStyle(Paint.Style.FILL);
		fill.setColor(color);
		r.set(scr.left + x * k, scr.top + y * k, scr.left + (x + w) * k, scr.top + (y + h) * k);
		cv.drawRoundRect(r, radius * k, radius * k, fill);
	}

	private static int mix(int base, int over, float f) {
		int rr = Math.round(((base >> 16) & 0xFF) * (1f - f) + ((over >> 16) & 0xFF) * f);
		int g = Math.round(((base >> 8) & 0xFF) * (1f - f) + ((over >> 8) & 0xFF) * f);
		int b = Math.round((base & 0xFF) * (1f - f) + (over & 0xFF) * f);
		return 0xFF000000 | (rr << 16) | (g << 8) | b;
	}
}
