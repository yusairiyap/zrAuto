package me.aap.fermata.ui.welcome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;

import me.aap.fermata.R;

/**
 * The pictures of the welcome and setup wizard: one small animated scene per page, drawn in code
 * (shapes and the app's own icons) so they follow the theme's colours, scale to whatever room the
 * page gives them (a phone's top, a tablet's left half) and cost no assets. A scene loops for as
 * long as it is on screen and stands still otherwise.
 */
final class WelcomeArtView extends View {
	static final int INTRO = 0;
	static final int DOWNLOAD = 1;
	static final int SPOTIFY = 2;
	static final int CAR = 3;
	static final int LYRICS = 4;
	static final int EFFECTS = 5;
	static final int ALL_IN_ONE = 6;
	static final int MORE = 7;
	static final int BACKUP = 8;
	static final int CAR_ASK = 9;
	static final int KEYS = 10;
	static final int TUNE = 11;
	static final int DONE = 12;
	static final int AMBIENCE = 13;

	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF r = new RectF();
	private final Path path = new Path();
	private final SparseArray<Drawable> icons = new SparseArray<>();
	private final float density;
	private int scene;
	private int accent = 0xFF1ED760;
	private int onAccent = 0xFF000000;
	private int card = 0xFF222222;
	private int text = 0xFFFFFFFF;
	private int textDim = 0x99FFFFFF;
	private int track = 0x33FFFFFF;
	private float t;
	private long start = SystemClock.uptimeMillis();
	private boolean running;
	private final Runnable frame = new Runnable() {
		@Override
		public void run() {
			if (!running) return;
			t = (SystemClock.uptimeMillis() - start) / 1000f;
			invalidate();
			postOnAnimation(this);
		}
	};

	WelcomeArtView(Context ctx) {
		super(ctx);
		density = ctx.getResources().getDisplayMetrics().density;
		stroke.setStyle(Paint.Style.STROKE);
		stroke.setStrokeCap(Paint.Cap.ROUND);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
	}

	void setScene(int scene) {
		this.scene = scene;
		start = SystemClock.uptimeMillis();
		invalidate();
	}

	void setColors(int accent, int onAccent, int card, int text, int textDim, int track) {
		this.accent = accent;
		this.onAccent = onAccent;
		this.card = card;
		this.text = text;
		this.textDim = textDim;
		this.track = track;
		invalidate();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		updateRunning();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		running = false;
	}

	@Override
	protected void onVisibilityChanged(View changed, int visibility) {
		super.onVisibilityChanged(changed, visibility);
		updateRunning();
	}

	@Override
	public void onWindowFocusChanged(boolean hasWindowFocus) {
		super.onWindowFocusChanged(hasWindowFocus);
		updateRunning();
	}

	private void updateRunning() {
		boolean want = isAttachedToWindow() && (getVisibility() == VISIBLE);
		if (want == running) return;
		running = want;
		if (want) postOnAnimation(frame);
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers

	private float dp(float v) {
		return v * density;
	}

	private static float clamp(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	private static float smooth(float x) {
		x = clamp(x);
		return x * x * (3f - 2f * x);
	}

	/** 0 to 1 and back, {@code period} seconds a round. */
	private float wave(float period, float phase) {
		return 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * (t / period + phase));
	}

	private static float overshoot(float x) {
		x = clamp(x);
		float s = 1.70158f;
		x -= 1f;
		return x * x * ((s + 1f) * x + s) + 1f;
	}

	private int alpha(int color, float a) {
		return ColorUtils.setAlphaComponent(color, Math.round(255 * clamp(a)));
	}

	private void roundRect(Canvas c, float l, float tp, float rt, float b, float rad, int color) {
		fill.setShader(null);
		fill.setColor(color);
		r.set(l, tp, rt, b);
		c.drawRoundRect(r, rad, rad, fill);
	}

	private void ring(Canvas c, float cx, float cy, float rad, float width, int color) {
		stroke.setShader(null);
		stroke.setStrokeWidth(width);
		stroke.setColor(color);
		c.drawCircle(cx, cy, rad, stroke);
	}

	private void disc(Canvas c, float cx, float cy, float rad, int color) {
		fill.setShader(null);
		fill.setColor(color);
		c.drawCircle(cx, cy, rad, fill);
	}

	private void glow(Canvas c, float cx, float cy, float rad, int color, float a) {
		fill.setShader(new RadialGradient(cx, cy, rad, alpha(color, a), alpha(color, 0f),
				Shader.TileMode.CLAMP));
		c.drawCircle(cx, cy, rad, fill);
		fill.setShader(null);
	}

	private void icon(Canvas c, @DrawableRes int id, float cx, float cy, float size, int color, float a) {
		Drawable d = icons.get(id);
		if (d == null) {
			Drawable src = ContextCompat.getDrawable(getContext(), id);
			if (src == null) return;
			d = DrawableCompat.wrap(src.mutate());
			icons.put(id, d);
		}
		DrawableCompat.setTint(d, color);
		d.setAlpha(Math.round(255 * clamp(a)));
		int h = Math.round(size / 2f);
		d.setBounds(Math.round(cx) - h, Math.round(cy) - h, Math.round(cx) + h, Math.round(cy) + h);
		d.draw(c);
	}

	/** A rounded tile with an icon, popped in by {@code scale}. */
	private void tile(Canvas c, float cx, float cy, float size, float scale, @DrawableRes int icon,
										int fillColor, int iconColor, float a) {
		if (scale <= 0.01f) return;
		float h = size * scale / 2f;
		roundRect(c, cx - h, cy - h, cx + h, cy + h, size * 0.26f * scale, alpha(fillColor, a));
		icon(c, icon, cx, cy, size * 0.5f * scale, iconColor, a);
	}

	// ---------------------------------------------------------------------------------------------

	@Override
	protected void onDraw(Canvas c) {
		int w = getWidth();
		int h = getHeight();
		if ((w == 0) || (h == 0)) return;
		float s = Math.min(w, h);
		float cx = w / 2f;
		float cy = h / 2f;
		glow(c, cx, cy, s * 0.62f, accent, 0.22f + 0.06f * wave(4f, 0f));
		switch (scene) {
			case INTRO -> intro(c, cx, cy, s);
			case DOWNLOAD -> download(c, cx, cy, s);
			case SPOTIFY -> spotify(c, cx, cy, s);
			case CAR -> car(c, cx, cy, s);
			case LYRICS -> lyrics(c, cx, cy, s);
			case EFFECTS -> effects(c, cx, cy, s);
			case ALL_IN_ONE -> allInOne(c, cx, cy, s);
			case AMBIENCE -> ambience(c, cx, cy, s);
			case MORE -> more(c, cx, cy, s);
			case BACKUP -> backup(c, cx, cy, s);
			case CAR_ASK -> carAsk(c, cx, cy, s);
			case KEYS -> keys(c, cx, cy, s);
			case TUNE -> tune(c, cx, cy, s);
			default -> done(c, cx, cy, s);
		}
	}

	private void intro(Canvas c, float cx, float cy, float s) {
		for (int i = 0; i < 3; i++) {
			float p = (t / 3.2f + i / 3f) % 1f;
			ring(c, cx, cy, s * (0.2f + 0.32f * p), dp(2), alpha(accent, 0.5f * (1f - p)));
		}
		float breathe = 1f + 0.04f * (float) Math.sin(t * 2.2f);
		float tile = s * 0.3f * breathe;
		roundRect(c, cx - tile / 2f, cy - tile / 2f, cx + tile / 2f, cy + tile / 2f, tile * 0.28f, accent);
		icon(c, R.drawable.music, cx, cy, tile * 0.56f, onAccent, 1f);
		int[] orbit = {R.drawable.download, R.drawable.car, R.drawable.lyrics, R.drawable.equalizer,
				R.drawable.playlist_import};
		for (int i = 0; i < orbit.length; i++) {
			double a = t * 0.55 + i * 2 * Math.PI / orbit.length;
			float ox = cx + (float) Math.cos(a) * s * 0.4f;
			float oy = cy + (float) Math.sin(a) * s * 0.3f;
			float depth = 0.8f + 0.2f * (float) Math.sin(a);
			tile(c, ox, oy, s * 0.15f * depth, 1f, orbit[i], card, accent, 0.6f + 0.4f * depth);
		}
		// Equalizer bars under the tile.
		float bw = s * 0.022f;
		for (int i = 0; i < 9; i++) {
			float bh = s * (0.03f + 0.07f * wave(0.9f + i * 0.07f, i * 0.13f));
			float x = cx + (i - 4) * bw * 1.9f;
			roundRect(c, x - bw / 2f, cy + s * 0.27f - bh, x + bw / 2f, cy + s * 0.27f, bw / 2f,
					alpha(accent, 0.85f));
		}
	}

	private void download(Canvas c, float cx, float cy, float s) {
		float loop = (t % 4.5f) / 4.5f;
		float prog = smooth(loop / 0.7f);
		boolean done = loop > 0.7f;
		float pop = overshoot((loop - 0.7f) / 0.12f);
		// The video card, with its play button and two lines.
		float cw = s * 0.62f;
		float ch = s * 0.3f;
		float cardX = cx - s * 0.1f;
		roundRect(c, cardX - cw / 2f, cy - ch / 2f, cardX + cw / 2f, cy + ch / 2f, dp(16), card);
		roundRect(c, cardX - cw / 2f + dp(12), cy - ch / 2f + dp(12), cardX - cw / 2f + ch * 0.9f,
				cy + ch / 2f - dp(12), dp(10), alpha(accent, 0.25f));
		icon(c, R.drawable.play, cardX - cw / 2f + dp(12) + (ch * 0.9f - dp(12)) / 2f, cy, ch * 0.34f, accent, 1f);
		float tx = cardX - cw / 2f + ch * 0.9f + dp(12);
		roundRect(c, tx, cy - dp(14), tx + cw * 0.32f, cy - dp(6), dp(4), alpha(text, 0.85f));
		roundRect(c, tx, cy + dp(4), tx + cw * 0.22f, cy + dp(10), dp(3), alpha(textDim, 0.7f));
		// The ring with its arrow, then a tick.
		float rx = cx + s * 0.28f;
		float ry = cy;
		float rr = s * 0.13f;
		disc(c, rx, ry, rr + dp(6), card);
		ring(c, rx, ry, rr, dp(5), track);
		stroke.setShader(null);
		stroke.setColor(accent);
		stroke.setStrokeWidth(dp(5));
		r.set(rx - rr, ry - rr, rx + rr, ry + rr);
		c.drawArc(r, -90, 360 * prog, false, stroke);
		if (done) icon(c, R.drawable.done, rx, ry, rr * 1.1f * pop, accent, 1f);
		else icon(c, R.drawable.download, rx, ry + dp(2) * (float) Math.sin(t * 6), rr * 0.9f, accent, 1f);
		// No signal, and it plays anyway.
		float off = smooth((loop - 0.78f) / 0.08f) * (1f - smooth((loop - 0.97f) / 0.03f));
		icon(c, R.drawable.network_off, cardX, cy + ch / 2f + s * 0.12f, s * 0.1f, textDim, 0.4f + 0.6f * off);
		roundRect(c, cardX - s * 0.2f, cy + ch / 2f + s * 0.16f, cardX + s * 0.2f, cy + ch / 2f + s * 0.18f,
				s * 0.01f, alpha(accent, 0.15f + 0.6f * off));
	}

	private void spotify(Canvas c, float cx, float cy, float s) {
		float loop = (t % 5f) / 5f;
		// A green source disc on the left, the app tile on the right, notes flowing between.
		float lx = cx - s * 0.36f;
		float rx = cx + s * 0.36f;
		disc(c, lx, cy - s * 0.18f, s * 0.1f, 0xFF1ED760);
		for (int i = 0; i < 3; i++) {
			float bw = s * (0.09f - i * 0.018f);
			stroke.setColor(0xFF000000);
			stroke.setStrokeWidth(dp(3));
			r.set(lx - bw, cy - s * 0.18f - bw * 0.55f + i * dp(7), lx + bw, cy - s * 0.18f + bw * 0.55f + i * dp(7));
			c.drawArc(r, 200, 140, false, stroke);
		}
		float tile = s * 0.2f;
		roundRect(c, rx - tile / 2f, cy - s * 0.18f - tile / 2f, rx + tile / 2f, cy - s * 0.18f + tile / 2f,
				tile * 0.28f, accent);
		icon(c, R.drawable.music, rx, cy - s * 0.18f, tile * 0.55f, onAccent, 1f);
		for (int i = 0; i < 4; i++) {
			float p = (t / 1.6f + i / 4f) % 1f;
			float x = lx + (rx - lx) * p;
			float y = cy - s * 0.18f - (float) Math.sin(p * Math.PI) * s * 0.07f;
			icon(c, R.drawable.audiotrack, x, y, s * 0.06f, accent, (float) Math.sin(p * Math.PI));
		}
		// The playlist: rows appear one after the other and are ticked off.
		for (int i = 0; i < 3; i++) {
			float start = 0.05f + i * 0.2f;
			float in = smooth((loop - start) / 0.12f);
			float tick = overshoot((loop - start - 0.12f) / 0.1f);
			float y = cy + s * 0.08f + i * s * 0.15f;
			float rw = s * 0.72f;
			float off = (1f - in) * -s * 0.3f;
			roundRect(c, cx - rw / 2f + off, y - s * 0.055f, cx + rw / 2f + off, y + s * 0.055f, dp(12),
					alpha(card, in));
			roundRect(c, cx - rw / 2f + off + dp(8), y - s * 0.038f, cx - rw / 2f + off + dp(8) + s * 0.076f,
					y + s * 0.038f, dp(7), alpha(accent, 0.3f * in));
			roundRect(c, cx - rw / 2f + off + s * 0.13f, y - dp(7), cx - rw / 2f + off + s * (0.3f + 0.06f * i),
					y - dp(1), dp(3), alpha(text, 0.8f * in));
			roundRect(c, cx - rw / 2f + off + s * 0.13f, y + dp(5), cx - rw / 2f + off + s * (0.22f + 0.03f * i),
					y + dp(10), dp(3), alpha(textDim, 0.6f * in));
			icon(c, R.drawable.done, cx + rw / 2f - dp(22) + off, y, dp(22) * tick, accent, tick);
		}
	}

	private void car(Canvas c, float cx, float cy, float s) {
		// The car's screen: four tiles, with an outline stepping along them.
		float sw = s * 0.7f;
		float sh = s * 0.4f;
		float top = cy - s * 0.32f;
		roundRect(c, cx - sw / 2f, top, cx + sw / 2f, top + sh, dp(18), card);
		stroke.setShader(null);
		stroke.setColor(alpha(textDim, 0.4f));
		stroke.setStrokeWidth(dp(2));
		r.set(cx - sw / 2f, top, cx + sw / 2f, top + sh);
		c.drawRoundRect(r, dp(18), dp(18), stroke);
		int step = (int) (t / 0.9f) % 4;
		float within = (t / 0.9f) % 1f;
		float tw = sw * 0.38f;
		float th = sh * 0.34f;
		int[] ic = {R.drawable.music, R.drawable.video, R.drawable.download, R.drawable.lyrics};
		for (int i = 0; i < 4; i++) {
			float x = cx + ((i % 2) - 0.5f) * (tw + dp(10));
			float y = top + sh * 0.3f + (i / 2) * (th + dp(8));
			roundRect(c, x - tw / 2f, y - th / 2f, x + tw / 2f, y + th / 2f, dp(10), alpha(track, 0.9f));
			icon(c, ic[i], x, y, th * 0.55f, text, 0.9f);
			if (i == step) {
				float k = 0.6f + 0.4f * smooth(within / 0.35f);
				stroke.setColor(accent);
				stroke.setStrokeWidth(dp(3));
				r.set(x - tw / 2f - dp(3), y - th / 2f - dp(3), x + tw / 2f + dp(3), y + th / 2f + dp(3));
				stroke.setAlpha(Math.round(255 * k));
				c.drawRoundRect(r, dp(12), dp(12), stroke);
			}
		}
		// The steering wheel, its two buttons lighting as the outline moves.
		float wy = cy + s * 0.27f;
		float wr = s * 0.16f;
		ring(c, cx, wy, wr, dp(7), alpha(textDim, 0.55f));
		ring(c, cx, wy, wr * 0.35f, dp(4), alpha(textDim, 0.4f));
		float press = 1f - smooth(within / 0.3f);
		icon(c, R.drawable.prev, cx - wr * 0.72f, wy, dp(26), (step == 0) ? accent : text, 0.6f);
		icon(c, R.drawable.next, cx + wr * 0.72f, wy, dp(26), accent, 0.5f + 0.5f * press);
		glow(c, cx + wr * 0.72f, wy, dp(34), accent, 0.5f * press);
		icon(c, R.drawable.car, cx + sw / 2f - dp(10), top - dp(14), dp(24), accent, 0.9f);
	}

	private void lyrics(Canvas c, float cx, float cy, float s) {
		float period = 1.5f;
		float pos = t / period;
		int cur = (int) Math.floor(pos);
		float frac = smooth((pos - cur) / 0.35f);
		float[] widths = {0.52f, 0.66f, 0.46f, 0.6f, 0.4f, 0.64f, 0.5f, 0.58f};
		float lineH = s * 0.15f;
		for (int k = -3; k <= 4; k++) {
			int idx = cur + k;
			float d = k - frac; // 0 = the playing line
			float a = (d < -1.6f) ? 0f : (d < 0) ? 0.45f + 0.55f * (1f + d / 1.6f) * 0.7f : (d < 3.2f) ? 1f - d * 0.27f : 0f;
			a = clamp(a);
			if (a <= 0.02f) continue;
			float act = Math.max(0f, 1f - Math.abs(d));
			float y = cy + d * lineH - s * 0.04f;
			float lw = s * widths[((idx % 8) + 8) % 8] * (1f + 0.12f * act);
			float lh = s * (0.038f + 0.022f * act);
			roundRect(c, cx - lw / 2f, y - lh / 2f, cx + lw / 2f, y + lh / 2f, lh / 2f,
					alpha(act > 0.5f ? accent : text, a * (0.55f + 0.45f * act)));
		}
		// The time of the line under the highlight.
		float px = cx + s * 0.4f;
		icon(c, R.drawable.lyrics, px, cy - s * 0.38f, dp(24), accent, 0.9f);
		roundRect(c, cx - s * 0.36f, cy + s * 0.34f, cx + s * 0.36f, cy + s * 0.355f, dp(2), track);
		roundRect(c, cx - s * 0.36f, cy + s * 0.34f, cx - s * 0.36f + s * 0.72f * ((t % 6f) / 6f),
				cy + s * 0.355f, dp(2), accent);
	}

	private void effects(Canvas c, float cx, float cy, float s) {
		int n = 8;
		float gw = s * 0.82f;
		float top = cy - s * 0.3f;
		float bottom = cy + s * 0.3f;
		path.reset();
		float[] kx = new float[n];
		float[] ky = new float[n];
		for (int i = 0; i < n; i++) {
			float x = cx - gw / 2f + gw * i / (n - 1);
			float v = 0.5f + 0.38f * (float) Math.sin(t * 1.3f + i * 0.9f) * (float) Math.cos(t * 0.5f + i * 0.4f);
			float y = bottom - (bottom - top) * v;
			kx[i] = x;
			ky[i] = y;
			roundRect(c, x - dp(2), top, x + dp(2), bottom, dp(2), track);
			roundRect(c, x - dp(2), y, x + dp(2), bottom, dp(2), alpha(accent, 0.55f));
		}
		path.moveTo(kx[0], ky[0]);
		for (int i = 1; i < n; i++) {
			float mx = (kx[i - 1] + kx[i]) / 2f;
			path.cubicTo(mx, ky[i - 1], mx, ky[i], kx[i], ky[i]);
		}
		stroke.setShader(null);
		stroke.setColor(alpha(accent, 0.35f));
		stroke.setStrokeWidth(dp(9));
		c.drawPath(path, stroke);
		stroke.setColor(accent);
		stroke.setStrokeWidth(dp(3));
		c.drawPath(path, stroke);
		for (int i = 0; i < n; i++) {
			disc(c, kx[i], ky[i], dp(9), 0xFFFFFFFF);
			disc(c, kx[i], ky[i], dp(5.5f), accent);
		}
		icon(c, R.drawable.equalizer, cx, cy + s * 0.4f, dp(26), textDim, 0.8f);
	}

	private void allInOne(Canvas c, float cx, float cy, float s) {
		float loop = (t % 5f) / 5f;
		int[] ic = {R.drawable.timer, R.drawable.quality, R.drawable.equalizer, R.drawable.favorite,
				R.drawable.download, R.drawable.lyrics};
		float cw = s * 0.8f;
		float ch = s * 0.66f;
		float pop = overshoot(loop / 0.1f);
		roundRect(c, cx - cw / 2f * pop, cy - ch / 2f * pop, cx + cw / 2f * pop, cy + ch / 2f * pop, dp(22),
				alpha(card, clamp(pop)));
		float tw = cw / 3.3f;
		for (int i = 0; i < 6; i++) {
			float start = 0.08f + i * 0.06f;
			float sc = overshoot((loop - start) / 0.1f);
			float x = cx + ((i % 3) - 1) * (tw * 1.12f);
			float y = cy + ((i / 3) - 0.5f) * (tw * 1.12f);
			boolean tapped = (i == 4) && (loop > 0.62f) && (loop < 0.78f);
			float pr = tapped ? 0.92f : 1f;
			tile(c, x, y, tw * 0.86f, sc * pr, ic[i], tapped ? accent : track, tapped ? onAccent : accent, 1f);
			if (tapped) {
				float k = (loop - 0.62f) / 0.16f;
				ring(c, x, y, tw * (0.4f + 0.5f * k), dp(2), alpha(accent, 1f - k));
			}
		}
		// The button that opens it.
		float fx = cx + cw / 2f - dp(6);
		float fy = cy + ch / 2f + dp(18);
		disc(c, fx, fy, dp(20), accent);
		icon(c, R.drawable.view_grid, fx, fy, dp(22), onAccent, 1f);
	}

	private void ambience(Canvas c, float cx, float cy, float s) {
		float loop = (t % 6f) / 6f;
		float cw = s * 0.8f;
		float ch = s * 0.68f;
		float pop = overshoot(loop / 0.1f);
		float l = cx - cw / 2f * pop, rt = cx + cw / 2f * pop;
		float tp = cy - ch / 2f * pop, bt = cy + ch / 2f * pop;
		roundRect(c, l, tp, rt, bt, dp(22), alpha(card, clamp(pop)));
		// Rain falling inside the card, with a cloud over it.
		c.save();
		c.clipRect(l, tp, rt, cy + ch * 0.1f);
		stroke.setShader(null);
		stroke.setStrokeWidth(dp(1.6f));
		for (int i = 0; i < 14; i++) {
			float ph = (t * (0.7f + 0.08f * (i % 5)) + i * 0.37f) % 1f;
			float x = l + cw * (0.08f + 0.84f * ((i * 0.618f) % 1f));
			float y = tp + ch * (0.1f + 0.6f * ph);
			stroke.setColor(alpha(accent, 0.55f * (1f - ph) * clamp(pop)));
			c.drawLine(x, y, x - dp(2), y + dp(9), stroke);
		}
		c.restore();
		icon(c, R.drawable.ambience, cx, tp + ch * 0.25f, s * 0.2f, accent, clamp(pop));
		// The sounds, switched on one after the other.
		int[] ic = {R.drawable.ambience_rain, R.drawable.ambience_fire, R.drawable.ambience_ocean,
				R.drawable.ambience_night};
		float tw = cw / 5.2f;
		for (int i = 0; i < 4; i++) {
			float start = 0.1f + i * 0.05f;
			float sc = overshoot((loop - start) / 0.1f);
			float x = cx + (i - 1.5f) * (tw * 1.2f);
			float y = cy + ch * 0.12f;
			boolean on = loop > 0.35f + i * 0.12f;
			tile(c, x, y, tw, sc, ic[i], on ? accent : track, on ? onAccent : accent, 1f);
		}
		// A fading volume line under the tiles.
		float k = clamp((loop - 0.35f) / 0.5f);
		float ly = bt - dp(14);
		stroke.setColor(track);
		stroke.setStrokeWidth(dp(4));
		c.drawLine(l + dp(20), ly, rt - dp(20), ly, stroke);
		stroke.setColor(accent);
		c.drawLine(l + dp(20), ly, l + dp(20) + (cw - dp(40)) * (0.3f + 0.4f * k), ly, stroke);
	}

	private void more(Canvas c, float cx, float cy, float s) {
		int[] ic = {R.drawable.data_usage, R.drawable.power_usage, R.drawable.share, R.drawable.fuel,
				R.drawable.private_mode, R.drawable.timer, R.drawable.speed};
		float tile = s * 0.2f;
		roundRect(c, cx - tile / 2f, cy - tile / 2f, cx + tile / 2f, cy + tile / 2f, tile * 0.28f, accent);
		icon(c, R.drawable.music, cx, cy, tile * 0.55f, onAccent, 1f);
		for (int i = 0; i < ic.length; i++) {
			double a = -Math.PI / 2 + i * 2 * Math.PI / ic.length + 0.15 * Math.sin(t * 0.7 + i);
			float rad = s * (0.36f + 0.02f * (float) Math.sin(t * 1.1 + i * 1.7));
			float x = cx + (float) Math.cos(a) * rad;
			float y = cy + (float) Math.sin(a) * rad * 0.9f;
			float on = wave(3f, i / (float) ic.length);
			stroke.setShader(null);
			stroke.setColor(alpha(accent, 0.12f + 0.25f * on));
			stroke.setStrokeWidth(dp(1.5f));
			c.drawLine(cx, cy, x, y, stroke);
			tile(c, x, y, s * 0.15f, 1f, ic[i], card, accent, 0.65f + 0.35f * on);
		}
	}

	private void backup(Canvas c, float cx, float cy, float s) {
		float loop = (t % 4f) / 4f;
		float dx = s * 0.26f;
		// The phone's settings, a zip going up, a folder or cloud on the other side.
		roundRect(c, cx - dx - s * 0.12f, cy - s * 0.2f, cx - dx + s * 0.12f, cy + s * 0.2f, dp(14), card);
		icon(c, R.drawable.settings, cx - dx, cy, s * 0.14f, accent, 1f);
		roundRect(c, cx + dx - s * 0.14f, cy - s * 0.14f, cx + dx + s * 0.14f, cy + s * 0.14f, dp(18), card);
		icon(c, R.drawable.save, cx + dx, cy, s * 0.14f, accent, 1f);
		float p = smooth(loop / 0.6f);
		float fx = cx - dx + (2 * dx) * p;
		if (loop < 0.6f) {
			roundRect(c, fx - dp(14), cy - dp(10) - (float) Math.sin(p * Math.PI) * s * 0.1f, fx + dp(14),
					cy + dp(10) - (float) Math.sin(p * Math.PI) * s * 0.1f, dp(6), accent);
			icon(c, R.drawable.archive_label, fx, cy - (float) Math.sin(p * Math.PI) * s * 0.1f, dp(16), onAccent, 1f);
		} else {
			float pop = overshoot((loop - 0.6f) / 0.12f);
			disc(c, cx + dx + s * 0.1f, cy - s * 0.12f, dp(14) * pop, accent);
			icon(c, R.drawable.done, cx + dx + s * 0.1f, cy - s * 0.12f, dp(16) * pop, onAccent, 1f);
		}
		// And the way back.
		stroke.setShader(null);
		stroke.setColor(alpha(textDim, 0.6f));
		stroke.setStrokeWidth(dp(2));
		path.reset();
		path.moveTo(cx + dx, cy + s * 0.2f);
		path.quadTo(cx, cy + s * 0.42f, cx - dx, cy + s * 0.2f);
		c.drawPath(path, stroke);
		float q = (t / 2.4f) % 1f;
		float qx = (1 - q) * (1 - q) * (cx + dx) + 2 * (1 - q) * q * cx + q * q * (cx - dx);
		float qy = (1 - q) * (1 - q) * (cy + s * 0.2f) + 2 * (1 - q) * q * (cy + s * 0.42f) + q * q * (cy + s * 0.2f);
		icon(c, R.drawable.refresh, qx, qy, dp(20), accent, 1f);
	}

	private void carAsk(Canvas c, float cx, float cy, float s) {
		float px = cx - s * 0.3f;
		float kx = cx + s * 0.26f;
		roundRect(c, px - s * 0.08f, cy - s * 0.15f, px + s * 0.08f, cy + s * 0.15f, dp(12), card);
		icon(c, R.drawable.music, px, cy, s * 0.09f, accent, 1f);
		roundRect(c, kx - s * 0.17f, cy - s * 0.12f, kx + s * 0.17f, cy + s * 0.12f, dp(14), card);
		icon(c, R.drawable.car, kx, cy, s * 0.17f, accent, 1f);
		// The signal between them.
		for (int i = 0; i < 6; i++) {
			float p = ((t / 1.8f) + i / 6f) % 1f;
			float x = px + s * 0.1f + (kx - s * 0.2f - px - s * 0.1f) * p;
			disc(c, x, cy, dp(3.5f) * (0.5f + (float) Math.sin(p * Math.PI)), alpha(accent, (float) Math.sin(p * Math.PI)));
		}
		for (int i = 0; i < 3; i++) {
			float p = (t / 2.4f + i / 3f) % 1f;
			stroke.setShader(null);
			stroke.setColor(alpha(accent, 0.5f * (1f - p)));
			stroke.setStrokeWidth(dp(2));
			r.set(kx - s * (0.2f + 0.2f * p), cy - s * (0.2f + 0.2f * p), kx + s * (0.2f + 0.2f * p), cy + s * (0.2f + 0.2f * p));
			c.drawArc(r, -50, 100, false, stroke);
		}
	}

	private void keys(Canvas c, float cx, float cy, float s) {
		int[] ic = {R.drawable.prev, R.drawable.play_pause, R.drawable.next};
		int step = (int) (t / 1.1f) % 3;
		float within = (t / 1.1f) % 1f;
		float ks = s * 0.22f;
		for (int i = 0; i < 3; i++) {
			float x = cx + (i - 1) * ks * 1.35f;
			boolean on = i == step;
			float press = on ? (1f - smooth(within / 0.4f)) : 0f;
			float dy = press * dp(5);
			roundRect(c, x - ks / 2f, cy - ks / 2f + dp(6), x + ks / 2f, cy + ks / 2f + dp(6), dp(16),
					alpha(textDim, 0.25f));
			roundRect(c, x - ks / 2f, cy - ks / 2f + dy, x + ks / 2f, cy + ks / 2f + dy, dp(16),
					on && (within < 0.6f) ? accent : card);
			icon(c, ic[i], x, cy + dy, ks * 0.5f, on && (within < 0.6f) ? onAccent : text, 1f);
			if (on) glow(c, x, cy, ks, accent, 0.6f * press);
		}
		// A long press: the ring fills.
		float hold = wave(3f, 0f);
		ring(c, cx, cy + s * 0.3f, dp(18), dp(4), track);
		stroke.setColor(accent);
		stroke.setStrokeWidth(dp(4));
		r.set(cx - dp(18), cy + s * 0.3f - dp(18), cx + dp(18), cy + s * 0.3f + dp(18));
		c.drawArc(r, -90, 360 * hold, false, stroke);
		icon(c, R.drawable.keyboard, cx, cy - s * 0.3f, dp(26), textDim, 0.8f);
	}

	private void tune(Canvas c, float cx, float cy, float s) {
		float rowH = s * 0.18f;
		int[] ic = {R.drawable.save, R.drawable.lyrics, R.drawable.power_saver};
		for (int i = 0; i < 3; i++) {
			float y = cy + (i - 1) * rowH * 1.25f;
			float rw = s * 0.74f;
			roundRect(c, cx - rw / 2f, y - rowH / 2f, cx + rw / 2f, y + rowH / 2f, dp(16), card);
			icon(c, ic[i], cx - rw / 2f + dp(26), y, dp(24), accent, 1f);
			roundRect(c, cx - rw / 2f + dp(52), y - dp(6), cx - rw / 2f + dp(52) + s * (0.22f + 0.05f * i),
					y, dp(3), alpha(text, 0.85f));
			roundRect(c, cx - rw / 2f + dp(52), y + dp(6), cx - rw / 2f + dp(52) + s * 0.14f, y + dp(11),
					dp(3), alpha(textDim, 0.6f));
			float on = smooth(Math.abs(((t / 3f + i * 0.2f) % 1f) * 2f - 1f) * 1.6f - 0.3f);
			float sx = cx + rw / 2f - dp(40);
			roundRect(c, sx, y - dp(11), sx + dp(36), y + dp(11), dp(11),
					blend(track, accent, on));
			disc(c, sx + dp(11) + dp(14) * on, y, dp(8), 0xFFFFFFFF);
		}
	}

	private void done(Canvas c, float cx, float cy, float s) {
		float pop = overshoot(t / 0.5f);
		for (int i = 0; i < 3; i++) {
			float p = (t / 2.6f + i / 3f) % 1f;
			ring(c, cx, cy, s * (0.16f + 0.3f * p), dp(2), alpha(accent, 0.45f * (1f - p)));
		}
		disc(c, cx, cy, s * 0.17f * pop, accent);
		icon(c, R.drawable.done, cx, cy, s * 0.2f * pop, onAccent, 1f);
		// Confetti, each piece on its own steady track.
		int[] colors = {accent, 0xFFFFB020, 0xFF5B8DEF, 0xFFFF5A4E, 0xFFB388FF};
		for (int i = 0; i < 34; i++) {
			float seed = (i * 0.6180339f) % 1f;
			float seed2 = (i * 0.7548776f) % 1f;
			float life = 2.6f + 1.6f * seed2;
			float p = ((t - 0.2f * seed) / life + seed) % 1f;
			if (t < 0.25f) continue;
			float x = cx + (seed - 0.5f) * s * 1.05f + (float) Math.sin(t * 2 + i) * dp(10);
			float y = cy - s * 0.46f + p * s * 0.95f;
			float rot = t * (2f + seed2 * 3f) + i;
			c.save();
			c.translate(x, y);
			c.rotate((float) Math.toDegrees(rot));
			fill.setShader(null);
			fill.setColor(alpha(colors[i % colors.length], 1f - smooth((p - 0.8f) / 0.2f)));
			r.set(-dp(4), -dp(2), dp(4), dp(2));
			c.drawRoundRect(r, dp(1.5f), dp(1.5f), fill);
			c.restore();
		}
	}

	private static int blend(int a, int b, float t) {
		t = clamp(t);
		return Color.argb(255,
				Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
				Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
				Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
	}
}
