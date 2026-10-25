package me.aap.fermata.ui.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.SettingsStyle;
import me.aap.utils.app.App;
import me.aap.utils.pref.PreferenceStore;

/**
 * A small picture of a Home screen as the settings describe it, drawn live from them: a phone or the car's display with
 * the greeting, the info chips, the picture behind (dimmed, zoomed, blurred) and a sample set of cards packed the way
 * the arrangement does (scrolling down, sideways, or freeflow), with as many columns or rows and as much room between them
 * as asked. The cards that run past the edge are cut off, as they would scroll. Used big, on top of the Home settings, and small,
 * one for each arrangement to pick from.
 */
public final class HomeLayoutPreview extends View implements PreferenceStore.Listener {
	public static final int AUTO = 0;
	public static final int DOWN = 1;
	public static final int SIDE = 2;
	public static final int FREE = 3;

	/** The sample cards: width x height in grid units; the first one is the "now playing" card. */
	private static final int[][] SAMPLE = {{4, 2}, {2, 2}, {2, 2}, {2, 1}, {2, 1}, {4, 1}, {2, 2}, {2, 2}, {4, 2}, {2, 1}, {2, 1}};

	private final MainActivityPrefs prefs;
	private final boolean car;
	/** The arrangement this one always shows (a card to pick from), or -1 to follow the settings. */
	private final int fixedMode;
	private final SettingsStyle.Colors c;
	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF r = new RectF();
	private final Path clip = new Path();

	private int mode;
	private int columns;
	private int gap;
	private boolean greeting;
	private boolean overlay;
	private boolean editButton;
	private int dim;
	private int zoom;
	private int source;
	@Nullable
	private Bitmap picture;
	private int pictureVersion = -1;
	private int pictureBlur = -1;
	private int pictureSource = -1;
	private int loadGen;

	public HomeLayoutPreview(@NonNull Context ctx, @NonNull MainActivityPrefs prefs, boolean car, int fixedMode) {
		super(ctx);
		this.prefs = prefs;
		this.car = car;
		this.fixedMode = fixedMode;
		c = new SettingsStyle.Colors(ctx);
		stroke.setStyle(Paint.Style.STROKE);
		reread();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		prefs.addBroadcastListener(this);
		reread();
	}

	@Override
	protected void onDetachedFromWindow() {
		prefs.removeBroadcastListener(this);
		loadGen++;
		super.onDetachedFromWindow();
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> changed) {
		post(this::reread);
	}

	/** Takes the settings as they are now and draws again. */
	public void reread() {
		mode = (fixedMode >= 0) ? fixedMode : prefs.getIntPref(car ? MainActivityPrefs.HOME_ARRANGE_AA : MainActivityPrefs.HOME_ARRANGE);
		columns = prefs.getIntPref(car ? MainActivityPrefs.HOME_COLUMNS_AA : MainActivityPrefs.HOME_COLUMNS);
		gap = Math.max(4, Math.min(20, prefs.getIntPref(car ? MainActivityPrefs.HOME_GAP_AA : MainActivityPrefs.HOME_GAP)));
		greeting = prefs.getBooleanPref(car ? MainActivityPrefs.HOME_GREETING_AA : MainActivityPrefs.HOME_GREETING);
		overlay = prefs.getBooleanPref(car ? MainActivityPrefs.HOME_TOP_OVERLAY_AA : MainActivityPrefs.HOME_TOP_OVERLAY);
		editButton = prefs.getBooleanPref(car ? MainActivityPrefs.HOME_EDIT_BUTTON_AA : MainActivityPrefs.HOME_EDIT_BUTTON);
		dim = prefs.getIntPref(HomeBgPrefs.dim(car));
		zoom = Math.max(100, prefs.getIntPref(HomeBgPrefs.zoom(car)));
		source = prefs.getIntPref(HomeBgPrefs.source(car));
		loadPicture();
		invalidate();
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

	/** The screen's shape in dp (what the packing is worked out for), and the room its layout keeps. */
	private float screenW() {
		return car ? 1100f : 392f;
	}

	private float screenH() {
		return car ? 540f : 850f;
	}

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
		float h = screenH();
		int shown = (mode == AUTO) ? (((w > h) && (h < 600f)) ? SIDE : DOWN) : mode;
		boolean down = shown != SIDE;
		float side = 12f;
		float top;
		// The header: greeting words, info chips and the edit button.
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
				bar(cv, scr, k, cx, headTop + 2f, cw, car ? 16f : 18f, 0x44FFFFFF, 8f);
			}
		}
		if (editButton) bar(cv, scr, k, w - 16f - 30f, headTop, 30f, 30f, 0x55FFFFFF, 15f);

		if (down) top = 16f + (greeting ? (car ? 54f : 62f) : 38f);
		else top = 58f;

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

		// First fit along the way it scrolls.
		List<int[]> placed = new ArrayList<>();
		boolean[][] used = new boolean[down ? n : 80][down ? 80 : n];
		for (int[] s : SAMPLE) {
			int iw = down ? Math.min(s[0], n) : s[0];
			int ih = down ? s[1] : Math.min(s[1], n);
			boolean done = false;
			for (int a = 0; (a < 78) && !done; a++) {
				for (int b = 0; (b <= (down ? n - iw : n - ih)) && !done; b++) {
					int x = down ? b : a;
					int y = down ? a : b;
					if (fits(used, x, y, iw, ih)) {
						for (int i = 0; i < iw; i++) for (int j = 0; j < ih; j++) used[x + i][y + j] = true;
						placed.add(new int[]{x, y, iw, ih, placed.size()});
						done = true;
					}
				}
			}
		}
		for (int[] p : placed) {
			float x = side + p[0] * (cell + gap);
			float y = top + p[1] * (cell + gap);
			float cw = p[2] * cell + (p[2] - 1) * gap;
			float ch = p[3] * cell + (p[3] - 1) * gap;
			if (shown == FREE) {
				// Freeflow: cards left where they were dropped, a little off the grid.
				x += ((p[4] * 7) % 5 - 2) * 2.5f;
				y += ((p[4] * 11) % 5 - 2) * 2.5f;
			}
			card(cv, scr, k, x, y, cw, ch, p[4] == 0);
		}
		// Past the edge it scrolls on: a soft fade says so.
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
		// And the way: a small arrow.
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

	private static boolean fits(boolean[][] used, int x, int y, int w, int h) {
		if ((x + w > used.length) || (y + h > used[0].length)) return false;
		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) if (used[x + i][y + j]) return false;
		}
		return true;
	}

	/** One sample card at {@code x, y} (dp from the screen's corner), the now-playing one in the accent. */
	private void card(Canvas cv, RectF scr, float k, float x, float y, float w, float h, boolean media) {
		fill.setStyle(Paint.Style.FILL);
		fill.setColor(media ? mix(0xFF101418, c.accent, 0.62f) : 0xFF1B2230);
		fill.setAlpha(media ? 235 : 215);
		r.set(scr.left + x * k, scr.top + y * k, scr.left + (x + w) * k, scr.top + (y + h) * k);
		float rad = Math.min(14f, Math.min(w, h) * 0.22f) * k;
		cv.drawRoundRect(r, rad, rad, fill);
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
