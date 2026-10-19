package me.aap.fermata.ui.view;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

import me.aap.fermata.addon.ambience.AmbienceEffect;

/**
 * Draws the weather of the Ambience tab: rain streaks, lightning, embers, gusts, waves, ripples,
 * fireflies under stars and birds on the wing. One scene shows any mix of the effects, each as
 * strongly as its level (0 to 1, from {@code AmbienceEngine#getVisual}) says, so a sound fading in
 * or out fades in or out on screen too. The Music tab's backdrop, the tab's hero and every card
 * each have a scene of their own.
 * <p>
 * Positions are kept as fractions of the view, so a resize costs nothing, and nothing is allocated
 * while drawing (paints, paths and shaders are made once). {@link Clock} runs it at 30 frames a
 * second, only while some level is above zero.
 */
public final class AmbienceScene {
	private static final int DROPS = 120;
	private static final int EMBERS = 36;
	private static final int GUSTS = 12;
	private static final int SPARKS = 40;
	private static final int STARS = 50;
	private static final int FLIES = 10;
	private static final int BIRDS = 4;
	private static final int MOTES = 16;
	private static final int E_RAIN = AmbienceEffect.RAIN.ordinal();
	private static final int E_THUNDER = AmbienceEffect.THUNDER.ordinal();
	private static final int E_FIRE = AmbienceEffect.FIRE.ordinal();
	private static final int E_WIND = AmbienceEffect.WIND.ordinal();
	private static final int E_OCEAN = AmbienceEffect.OCEAN.ordinal();
	private static final int E_STREAM = AmbienceEffect.STREAM.ordinal();
	private static final int E_NIGHT = AmbienceEffect.NIGHT.ordinal();
	private static final int E_BIRDS = AmbienceEffect.BIRDS.ordinal();

	private final float dp;
	private final Random rnd;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private final Path path2 = new Path();
	private float t;

	// Rain
	private final float[] dropX = new float[DROPS], dropY = new float[DROPS], dropV = new float[DROPS],
			dropLen = new float[DROPS], dropA = new float[DROPS];
	// Lightning
	private float flashT = 99f, flashStrength;
	private final float[] boltX = new float[10], boltY = new float[10];
	private final float[] branchX = new float[5], branchY = new float[5];
	private final float[] cloudX = new float[4], cloudS = new float[4], cloudV = new float[4];
	private RadialGradient cloudShader, glowShader, mistShader;
	private LinearGradient skyShader;
	private int shaderW, shaderH;
	// Fire
	private final float[] emberX = new float[EMBERS], emberY = new float[EMBERS],
			emberV = new float[EMBERS], emberP = new float[EMBERS], emberS = new float[EMBERS];
	// Wind
	private final float[] gustP = new float[GUSTS], gustY = new float[GUSTS], gustLen = new float[GUSTS],
			gustV = new float[GUSTS], gustPh = new float[GUSTS];
	// Stream
	private final float[] sparkX = new float[SPARKS], sparkY = new float[SPARKS],
			sparkV = new float[SPARKS], sparkP = new float[SPARKS];
	// Night
	private final float[] starX = new float[STARS], starY = new float[STARS], starS = new float[STARS],
			starP = new float[STARS];
	private final float[] flyX = new float[FLIES], flyY = new float[FLIES], flyAng = new float[FLIES],
			flyP = new float[FLIES];
	// Birds
	private final float[] birdP = new float[BIRDS], birdY = new float[BIRDS], birdS = new float[BIRDS],
			birdV = new float[BIRDS], birdPh = new float[BIRDS];
	private final float[] moteX = new float[MOTES], moteY = new float[MOTES], moteV = new float[MOTES],
			moteP = new float[MOTES];

	public AmbienceScene(float density, long seed) {
		dp = density;
		rnd = new Random(seed);
		for (int i = 0; i < DROPS; i++) {
			dropX[i] = rnd.nextFloat();
			dropY[i] = rnd.nextFloat();
			resetDrop(i, false);
		}
		for (int i = 0; i < cloudX.length; i++) {
			cloudX[i] = rnd.nextFloat();
			cloudS[i] = 0.7f + 0.8f * rnd.nextFloat();
			cloudV[i] = 0.004f + 0.01f * rnd.nextFloat();
		}
		for (int i = 0; i < EMBERS; i++) {
			emberX[i] = rnd.nextFloat();
			emberY[i] = rnd.nextFloat();
			resetEmber(i, false);
		}
		for (int i = 0; i < GUSTS; i++) {
			gustP[i] = rnd.nextFloat();
			resetGust(i, false);
		}
		for (int i = 0; i < SPARKS; i++) {
			sparkX[i] = rnd.nextFloat();
			sparkY[i] = 0.45f + 0.55f * rnd.nextFloat();
			sparkV[i] = 0.05f + 0.15f * rnd.nextFloat();
			sparkP[i] = rnd.nextFloat() * 6.28f;
		}
		for (int i = 0; i < STARS; i++) {
			starX[i] = rnd.nextFloat();
			starY[i] = 0.7f * rnd.nextFloat();
			starS[i] = 0.5f + rnd.nextFloat();
			starP[i] = rnd.nextFloat() * 6.28f;
		}
		for (int i = 0; i < FLIES; i++) {
			flyX[i] = rnd.nextFloat();
			flyY[i] = 0.4f + 0.6f * rnd.nextFloat();
			flyAng[i] = rnd.nextFloat() * 6.28f;
			flyP[i] = rnd.nextFloat() * 6.28f;
		}
		for (int i = 0; i < BIRDS; i++) {
			birdP[i] = rnd.nextFloat();
			resetBird(i);
		}
		for (int i = 0; i < MOTES; i++) {
			moteX[i] = rnd.nextFloat();
			moteY[i] = rnd.nextFloat();
			moteV[i] = 0.01f + 0.03f * rnd.nextFloat();
			moteP[i] = rnd.nextFloat() * 6.28f;
		}
		paint.setStrokeCap(Paint.Cap.ROUND);
		paint.setStrokeJoin(Paint.Join.ROUND);
	}

	private void resetDrop(int i, boolean top) {
		dropX[i] = rnd.nextFloat();
		if (top) dropY[i] = -0.1f * rnd.nextFloat();
		dropV[i] = 0.9f + 0.9f * rnd.nextFloat();
		dropLen[i] = 0.025f + 0.045f * rnd.nextFloat();
		dropA[i] = 0.25f + 0.55f * rnd.nextFloat();
	}

	private void resetEmber(int i, boolean bottom) {
		emberX[i] = 0.1f + 0.8f * rnd.nextFloat();
		if (bottom) emberY[i] = 1.02f;
		emberV[i] = 0.07f + 0.16f * rnd.nextFloat();
		emberP[i] = rnd.nextFloat() * 6.28f;
		emberS[i] = 0.6f + 1.4f * rnd.nextFloat();
	}

	private void resetGust(int i, boolean fresh) {
		if (fresh) gustP[i] = 0f;
		gustY[i] = 0.08f + 0.84f * rnd.nextFloat();
		gustLen[i] = 0.25f + 0.35f * rnd.nextFloat();
		gustV[i] = 0.12f + 0.22f * rnd.nextFloat();
		gustPh[i] = rnd.nextFloat() * 6.28f;
	}

	private void resetBird(int i) {
		birdP[i] = -0.05f;
		birdY[i] = 0.1f + 0.4f * rnd.nextFloat();
		birdS[i] = 7f + 7f * rnd.nextFloat();
		birdV[i] = 0.045f + 0.05f * rnd.nextFloat();
		birdPh[i] = rnd.nextFloat() * 6.28f;
	}

	/** Lightning struck: the sky flashes and a bolt is drawn for a moment. */
	public void flash(float strength) {
		flashT = 0f;
		flashStrength = Math.max(0.2f, Math.min(1f, strength));
		float x = 0.2f + 0.6f * rnd.nextFloat();
		for (int i = 0; i < boltX.length; i++) {
			float f = i / (float) (boltX.length - 1);
			boltX[i] = x + (rnd.nextFloat() - 0.5f) * 0.12f;
			boltY[i] = f * (0.5f + 0.25f * rnd.nextFloat());
			x = boltX[i];
		}
		// The first point is on the top edge, the last a fraction lower than the rest.
		boltY[0] = -0.02f;
		int from = 3 + rnd.nextInt(3);
		float bx = boltX[from];
		float by = boltY[from];
		float dir = rnd.nextBoolean() ? 1f : -1f;
		for (int i = 0; i < branchX.length; i++) {
			branchX[i] = bx;
			branchY[i] = by;
			bx += dir * (0.03f + 0.04f * rnd.nextFloat());
			by += 0.05f + 0.04f * rnd.nextFloat();
		}
	}

	/** Whether anything should be drawn or moved for these levels (or the flash is still fading). */
	public boolean isActive(float[] lv) {
		if (flashT < 1f) return true;
		for (float v : lv) if (v > 0.003f) return true;
		return false;
	}

	/** Moves everything on by {@code dt} seconds. */
	public void step(float dt, float[] lv) {
		t += dt;
		if (flashT < 1f) flashT += dt;
		float rain = lv[E_RAIN];
		if (rain > 0.003f) {
			for (int i = 0; i < DROPS; i++) {
				dropY[i] += dropV[i] * dt;
				if (dropY[i] > 1.05f) {
					resetDrop(i, true);
					dropY[i] = -0.05f;
				}
			}
		}
		if ((lv[E_THUNDER] > 0.003f) || (rain > 0.003f)) {
			for (int i = 0; i < cloudX.length; i++) {
				cloudX[i] += cloudV[i] * dt;
				if (cloudX[i] > 1.4f) cloudX[i] = -0.4f;
			}
		}
		if (lv[E_FIRE] > 0.003f) {
			for (int i = 0; i < EMBERS; i++) {
				emberY[i] -= emberV[i] * dt;
				if (emberY[i] < -0.05f) resetEmber(i, true);
			}
		}
		if (lv[E_WIND] > 0.003f) {
			for (int i = 0; i < GUSTS; i++) {
				gustP[i] += gustV[i] * dt * (0.5f + lv[E_WIND]);
				if (gustP[i] > 1f) resetGust(i, true);
			}
		}
		if (lv[E_STREAM] > 0.003f) {
			for (int i = 0; i < SPARKS; i++) {
				sparkX[i] += sparkV[i] * dt;
				if (sparkX[i] > 1.05f) sparkX[i] = -0.05f;
			}
		}
		if (lv[E_NIGHT] > 0.003f) {
			for (int i = 0; i < FLIES; i++) {
				flyAng[i] += (rnd.nextFloat() - 0.5f) * dt * 5f;
				flyX[i] += (float) Math.cos(flyAng[i]) * 0.04f * dt;
				flyY[i] += (float) Math.sin(flyAng[i]) * 0.04f * dt;
				if (flyX[i] < 0f) flyX[i] += 1f;
				if (flyX[i] > 1f) flyX[i] -= 1f;
				if (flyY[i] < 0.35f) flyAng[i] += 0.3f;
				if (flyY[i] > 1f) flyAng[i] -= 0.3f;
			}
		}
		if (lv[E_BIRDS] > 0.003f) {
			for (int i = 0; i < BIRDS; i++) {
				birdP[i] += birdV[i] * dt;
				birdPh[i] += dt * (7f + i);
				if (birdP[i] > 1.08f) resetBird(i);
			}
			for (int i = 0; i < MOTES; i++) {
				moteY[i] -= moteV[i] * dt * 0.5f;
				moteX[i] += (float) Math.sin(t * 0.5f + moteP[i]) * 0.01f * dt;
				if (moteY[i] < -0.02f) moteY[i] = 1.02f;
			}
		}
	}

	private void ensureShaders(int w, int h) {
		if ((w == shaderW) && (h == shaderH) && (cloudShader != null)) return;
		shaderW = w;
		shaderH = h;
		cloudShader = new RadialGradient(0, 0, 100, new int[]{0xCC3A3B5E, 0x003A3B5E}, null,
				Shader.TileMode.CLAMP);
		glowShader = new RadialGradient(0, 0, 100, new int[]{0xFFFFA24D, 0x00FF7A1A}, null,
				Shader.TileMode.CLAMP);
		mistShader = new RadialGradient(0, 0, 100, new int[]{0x66DDEEFF, 0x00DDEEFF}, null,
				Shader.TileMode.CLAMP);
		skyShader = new LinearGradient(0, 0, 0, Math.max(1, h * 0.6f), 0xCC12132B, 0x0012132B,
				Shader.TileMode.CLAMP);
	}

	private static int withAlpha(int color, float a) {
		int v = Math.round(255f * Math.max(0f, Math.min(1f, a)));
		return (color & 0x00FFFFFF) | (v << 24);
	}

	/** Draws the scene; call {@link #step} first. */
	public void draw(Canvas c, int w, int h, float[] lv) {
		if ((w <= 0) || (h <= 0)) return;
		ensureShaders(w, h);
		if (lv[E_NIGHT] > 0.003f) drawNight(c, w, h, lv[E_NIGHT]);
		if ((lv[E_THUNDER] > 0.003f) || (lv[E_RAIN] > 0.003f)) {
			drawClouds(c, w, h, Math.max(lv[E_THUNDER], lv[E_RAIN] * 0.7f), lv[E_THUNDER] > 0.003f);
		}
		if (lv[E_OCEAN] > 0.003f) drawOcean(c, w, h, lv[E_OCEAN]);
		if (lv[E_STREAM] > 0.003f) drawStream(c, w, h, lv[E_STREAM]);
		if (lv[E_WIND] > 0.003f) drawWind(c, w, h, lv[E_WIND]);
		if (lv[E_BIRDS] > 0.003f) drawBirds(c, w, h, lv[E_BIRDS]);
		if (lv[E_FIRE] > 0.003f) drawFire(c, w, h, lv[E_FIRE]);
		if (lv[E_RAIN] > 0.003f) drawRain(c, w, h, lv[E_RAIN], lv[E_WIND]);
		if (flashT < 1f) drawLightning(c, w, h, Math.max(lv[E_THUNDER], 0.35f));
	}

	// ---------------------------------------------------------------------------------------------

	private void drawRain(Canvas c, int w, int h, float a, float wind) {
		AmbienceEffect fx = AmbienceEffect.RAIN;
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(dp * 1.3f);
		int count = (int) Math.ceil(DROPS * (0.25f + 0.75f * a));
		float slant = 0.12f + 0.25f * wind;
		for (int i = 0; i < count; i++) {
			float len = dropLen[i] * h;
			float x = dropX[i] * w;
			float y = dropY[i] * h;
			paint.setColor(withAlpha(fx.tint, dropA[i] * Math.min(1f, a * 1.4f)));
			c.drawLine(x, y, x - len * slant, y - len, paint);
		}
		// Mist rising where the rain lands.
		shaderPaint.setShader(mistShader);
		shaderPaint.setAlpha(Math.round(110 * a));
		c.save();
		c.translate(w * 0.5f, h * 1.02f);
		c.scale(w * 0.8f / 100f, h * 0.25f / 100f);
		c.drawCircle(0, 0, 100, shaderPaint);
		c.restore();
		shaderPaint.setShader(null);
	}

	private void drawClouds(Canvas c, int w, int h, float a, boolean storm) {
		shaderPaint.setShader(cloudShader);
		shaderPaint.setAlpha(Math.round(255 * Math.min(1f, a * (storm ? 1.1f : 0.8f))));
		for (int i = 0; i < cloudX.length; i++) {
			c.save();
			c.translate(cloudX[i] * w, h * (0.05f + 0.05f * i));
			float s = cloudS[i];
			c.scale(w * 0.55f * s / 100f, h * 0.28f * s / 100f);
			c.drawCircle(0, 0, 100, shaderPaint);
			c.restore();
		}
		shaderPaint.setShader(null);
	}

	private static float flashCurve(float ft) {
		if (ft < 0.05f) return ft / 0.05f;
		if (ft < 0.12f) return 1f - (ft - 0.05f) / 0.07f * 0.65f;
		if (ft < 0.18f) return 0.35f + (ft - 0.12f) / 0.06f * 0.5f;
		return (float) (0.85 * Math.exp(-(ft - 0.18f) * 6f));
	}

	private void drawLightning(Canvas c, int w, int h, float a) {
		float f = flashCurve(flashT) * flashStrength;
		if (f < 0.01f) return;
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(withAlpha(0xFFDCD6FF, f * 0.38f));
		c.drawRect(0, 0, w, h, paint);
		paint.setStyle(Paint.Style.STROKE);
		path.rewind();
		path.moveTo(boltX[0] * w, boltY[0] * h);
		for (int i = 1; i < boltX.length; i++) path.lineTo(boltX[i] * w, boltY[i] * h);
		path2.rewind();
		path2.moveTo(branchX[0] * w, branchY[0] * h);
		for (int i = 1; i < branchX.length; i++) path2.lineTo(branchX[i] * w, branchY[i] * h);
		paint.setStrokeWidth(dp * 7f);
		paint.setColor(withAlpha(0xFFB69CFF, f * 0.35f));
		c.drawPath(path, paint);
		c.drawPath(path2, paint);
		paint.setStrokeWidth(dp * 2.2f);
		paint.setColor(withAlpha(0xFFFFFFFF, Math.min(1f, f * 1.2f)));
		c.drawPath(path, paint);
		paint.setStrokeWidth(dp * 1.4f);
		c.drawPath(path2, paint);
	}

	private void drawFire(Canvas c, int w, int h, float a) {
		AmbienceEffect fx = AmbienceEffect.FIRE;
		float flicker = 0.5f + 0.25f * (float) Math.sin(t * 7.3f) + 0.25f * (float) Math.sin(t * 12.1f + 1f);
		// The glow on the floor.
		shaderPaint.setShader(glowShader);
		shaderPaint.setAlpha(Math.round(255 * a * (0.45f + 0.35f * flicker)));
		c.save();
		c.translate(w * 0.5f, h * 1.0f);
		c.scale(w * 0.75f / 100f, h * 0.62f / 100f);
		c.drawCircle(0, 0, 100, shaderPaint);
		c.restore();
		shaderPaint.setShader(null);
		// Tongues of flame along the bottom.
		paint.setStyle(Paint.Style.FILL);
		for (int k = 0; k < 5; k++) {
			float bx = w * (0.14f + 0.18f * k);
			float wd = w * 0.07f;
			float ht = h * (0.12f + 0.08f * a) * (0.7f + 0.3f * (float) Math.sin(t * (3f + k) + k * 1.7f));
			float sway = (float) Math.sin(t * (2f + 0.6f * k) + k) * wd * 0.5f;
			path.rewind();
			path.moveTo(bx - wd, h);
			path.quadTo(bx - wd * 0.2f, h - ht * 0.5f, bx + sway, h - ht);
			path.quadTo(bx + wd * 0.2f, h - ht * 0.5f, bx + wd, h);
			path.close();
			paint.setColor(withAlpha(fx.tint, 0.32f * a));
			c.drawPath(path, paint);
			c.save();
			c.scale(0.55f, 0.6f, bx, h);
			paint.setColor(withAlpha(0xFFFFD27A, 0.4f * a));
			c.drawPath(path, paint);
			c.restore();
		}
		// Embers rising.
		int count = (int) Math.ceil(EMBERS * (0.3f + 0.7f * a));
		for (int i = 0; i < count; i++) {
			float y = emberY[i];
			float life = Math.max(0f, Math.min(1f, y));
			float fadeIn = Math.min(1f, (1.05f - y) * 10f);
			float x = (emberX[i] + 0.025f * (float) Math.sin(t * 1.6f + emberP[i])) * w;
			paint.setColor(withAlpha((i & 1) == 0 ? 0xFFFFB347 : 0xFFFF6A2B, a * life * fadeIn));
			c.drawCircle(x, y * h, dp * emberS[i], paint);
		}
	}

	private void drawWind(Canvas c, int w, int h, float a) {
		AmbienceEffect fx = AmbienceEffect.WIND;
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(dp * 1.3f);
		for (int i = 0; i < GUSTS; i++) {
			float p = gustP[i];
			float len = gustLen[i] * w;
			float x = p * (w + len) - len;
			float y = gustY[i] * h;
			float amp = dp * (5f + 5f * a);
			float ph = gustPh[i] + t * 1.5f;
			path.rewind();
			path.moveTo(x, y);
			path.cubicTo(x + len * 0.3f, y + amp * (float) Math.sin(ph), x + len * 0.6f,
					y - amp * (float) Math.sin(ph + 1f), x + len, y + amp * 0.3f * (float) Math.sin(ph + 2f));
			float edge = (float) Math.sin(Math.PI * p);
			paint.setColor(withAlpha(fx.tint, 0.4f * a * edge));
			c.drawPath(path, paint);
		}
	}

	private void drawOcean(Canvas c, int w, int h, float a) {
		AmbienceEffect fx = AmbienceEffect.OCEAN;
		int steps = Math.max(8, Math.round(w / (10f * dp)));
		float swell = (float) Math.sin(t * 0.6f) * h * 0.03f * a;
		for (int layer = 0; layer < 3; layer++) {
			float base = h * (0.84f - 0.06f * layer) + swell * (layer + 1) * 0.5f;
			float amp = h * (0.012f + 0.03f * a) * (1f - 0.15f * layer);
			float waves = 1.2f + 0.7f * layer;
			float speed = (0.8f + 0.5f * layer) * (layer % 2 == 0 ? 1f : -1f);
			path.rewind();
			path2.rewind();
			for (int s = 0; s <= steps; s++) {
				float x = w * s / (float) steps;
				float y = base + amp * (float) Math.sin(6.2832f * waves * s / steps + t * speed + layer * 2f)
						+ amp * 0.4f * (float) Math.sin(6.2832f * waves * 2.3f * s / steps - t * speed * 1.3f);
				if (s == 0) {
					path.moveTo(x, y);
					path2.moveTo(x, y);
				} else {
					path.lineTo(x, y);
					path2.lineTo(x, y);
				}
			}
			path.lineTo(w, h);
			path.lineTo(0, h);
			path.close();
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(withAlpha(fx.tint, (0.14f + 0.08f * layer) * a));
			c.drawPath(path, paint);
			if (layer == 0) {
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(dp * 1.6f);
				paint.setColor(withAlpha(0xFFFFFFFF, 0.3f * a));
				c.drawPath(path2, paint);
			}
		}
	}

	private void drawStream(Canvas c, int w, int h, float a) {
		AmbienceEffect fx = AmbienceEffect.STREAM;
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(dp * 1.2f);
		for (int k = 0; k < 3; k++) {
			float y = h * (0.62f + 0.12f * k);
			path.rewind();
			int steps = Math.max(8, Math.round(w / (14f * dp)));
			for (int s = 0; s <= steps; s++) {
				float x = w * s / (float) steps;
				float yy = y + dp * (4f + 2f * k) * (float) Math.sin(s * 0.7f - t * (1.6f + 0.5f * k) + k);
				if (s == 0) path.moveTo(x, yy);
				else path.lineTo(x, yy);
			}
			paint.setColor(withAlpha(fx.tint, 0.22f * a));
			c.drawPath(path, paint);
		}
		paint.setStyle(Paint.Style.FILL);
		int count = (int) Math.ceil(SPARKS * (0.3f + 0.7f * a));
		for (int i = 0; i < count; i++) {
			float tw = 0.5f + 0.5f * (float) Math.sin(t * 3f + sparkP[i]);
			paint.setColor(withAlpha(0xFFFFFFFF, 0.8f * a * tw));
			c.drawCircle(sparkX[i] * w, sparkY[i] * h, dp * (0.8f + 1.2f * tw), paint);
		}
	}

	private void drawNight(Canvas c, int w, int h, float a) {
		paint.setStyle(Paint.Style.FILL);
		// A deep sky first, so the stars have something to stand on.
		shaderPaint.setShader(skyShader);
		shaderPaint.setAlpha(Math.round(200 * a));
		c.drawRect(0, 0, w, h * 0.6f, shaderPaint);
		shaderPaint.setShader(null);
		for (int i = 0; i < STARS; i++) {
			float tw = 0.5f + 0.5f * (float) Math.sin(t * (1f + starS[i]) + starP[i]);
			paint.setColor(withAlpha(0xFFFFFFFF, a * (0.25f + 0.65f * tw)));
			c.drawCircle(starX[i] * w, starY[i] * h, dp * 0.9f * starS[i], paint);
		}
		// The moon: a disc with a bite taken out of it.
		float r = dp * 16f;
		float mx = w - dp * 40f;
		float my = dp * 44f;
		path.rewind();
		path.addCircle(mx, my, r, Path.Direction.CW);
		path2.rewind();
		path2.addCircle(mx + r * 0.5f, my - r * 0.2f, r * 0.85f, Path.Direction.CW);
		path.op(path2, Path.Op.DIFFERENCE);
		paint.setColor(withAlpha(AmbienceEffect.NIGHT.tint, 0.9f * a));
		c.drawPath(path, paint);
		// Fireflies.
		for (int i = 0; i < FLIES; i++) {
			float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2f + flyP[i]);
			float x = flyX[i] * w;
			float y = flyY[i] * h;
			paint.setColor(withAlpha(0xFFD8F26A, 0.14f * a * pulse));
			c.drawCircle(x, y, dp * 7f, paint);
			paint.setColor(withAlpha(0xFFEFFF9A, 0.9f * a * pulse));
			c.drawCircle(x, y, dp * 1.8f, paint);
		}
	}

	private void drawBirds(Canvas c, int w, int h, float a) {
		AmbienceEffect fx = AmbienceEffect.BIRDS;
		paint.setStyle(Paint.Style.FILL);
		for (int i = 0; i < MOTES; i++) {
			float tw = 0.5f + 0.5f * (float) Math.sin(t * 1.5f + moteP[i]);
			paint.setColor(withAlpha(fx.tint, 0.35f * a * tw));
			c.drawCircle(moteX[i] * w, moteY[i] * h, dp * 1.6f, paint);
		}
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(dp * 1.7f);
		paint.setColor(withAlpha(0xFFE9F6D0, 0.75f * a));
		int count = 1 + (int) Math.ceil((BIRDS - 1) * a);
		for (int i = 0; i < count; i++) {
			float x = birdP[i] * w;
			float y = birdY[i] * h + (float) Math.sin(t * 0.8f + birdPh[i]) * dp * 6f;
			float s = dp * birdS[i];
			float flap = (float) Math.sin(birdPh[i]);
			path.rewind();
			path.moveTo(x - s, y - s * 0.45f * flap);
			path.quadTo(x - s * 0.5f, y - s * (0.3f + 0.3f * flap), x, y);
			path.quadTo(x + s * 0.5f, y - s * (0.3f + 0.3f * flap), x + s, y - s * 0.45f * flap);
			c.drawPath(path, paint);
		}
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Drives a scene at 30 frames a second while it has something to show: {@link #start()} when
	 * the view is attached and visible (and again whenever a level may have risen), and the loop
	 * ends by itself when {@link Frame#onFrame} says there is nothing left to move.
	 */
	public static final class Clock {
		/** The cap on the frame rate: the weather needs no more, the battery would feel it. */
		private static final long FRAME_MS = 33;

		public interface Frame {
			/** @return whether to keep running */
			boolean onFrame(float dt);
		}

		private final View view;
		private final Frame frame;
		private boolean running;
		private long last;
		private final Runnable tick = this::tick;

		public Clock(View view, Frame frame) {
			this.view = view;
			this.frame = frame;
		}

		public void start() {
			if (running) return;
			running = true;
			last = SystemClock.uptimeMillis();
			view.postOnAnimation(tick);
		}

		public void stop() {
			running = false;
			view.removeCallbacks(tick);
		}

		private void tick() {
			if (!running) return;
			long now = SystemClock.uptimeMillis();
			long el = now - last;
			if (el < FRAME_MS) {
				view.postOnAnimation(tick);
				return;
			}
			last = now;
			if (frame.onFrame(Math.min(0.1f, el / 1000f))) view.postOnAnimation(tick);
			else running = false;
		}
	}
}
