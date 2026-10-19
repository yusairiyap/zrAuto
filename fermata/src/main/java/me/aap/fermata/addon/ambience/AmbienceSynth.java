package me.aap.fermata.addon.ambience;

/**
 * Makes the ambience sounds from noise and a few tones, one {@link Voice} per effect, so there is
 * nothing to download or bundle and nothing ever repeats. A voice fills a stereo block at the sample
 * rate of {@link #SR}; how loud it is overall is the engine's business (fades, intensity), the
 * voice only reads the intensity {@code lv} (0 to 1) to decide how dense or how strong it is.
 * <p>
 * Everything here runs on the audio thread: no allocation in {@code render}, no locks. The filters
 * are one-pole low-passes ({@code y += c * (x - y)}), and a band is the difference of two of them.
 * The noise levels in the comments are the rough standard deviation of what each stage gives, which
 * is what the gains were picked against; the engine soft-clips the sum, so a slip is not a crash.
 */
final class AmbienceSynth {
	static final int SR = 44100;
	private static final float TWO_PI = (float) (Math.PI * 2);

	private AmbienceSynth() {
	}

	/** Told when lightning strikes, from the audio thread. */
	interface FlashSink {
		void flash(float strength);
	}

	static Voice create(AmbienceEffect e, FlashSink sink) {
		return switch (e) {
			case RAIN -> new Rain();
			case THUNDER -> new Thunder(sink);
			case FIRE -> new Fire();
			case WIND -> new Wind();
			case OCEAN -> new Ocean();
			case STREAM -> new Stream();
			case DROPS -> new Drips();
			case BUBBLES -> new Bubbles();
			case CHIMES -> new Chimes();
			case CAFE -> new Cafe();
			case NIGHT -> new Night();
			case BIRDS -> new Birds();
		};
	}

	/** One-pole low-pass coefficient for a cut-off in Hz. */
	static float coef(float fc) {
		return (float) (1.0 - Math.exp(-TWO_PI * fc / SR));
	}

	abstract static class Voice {
		/** Writes {@code n} frames to {@code l} and {@code r} (replacing what was there). */
		/** {@code opt}: the sound's own character slider, 0 to 1, 0.5 being how it was designed. */
		abstract void render(float[] l, float[] r, int n, float lv, float opt);
	}

	/** xorshift32: white noise in -1..1 without the cost of java.util.Random. */
	static final class Rng {
		private int s;

		Rng(int seed) {
			s = (seed == 0) ? 0x2545F491 : seed;
		}

		float next() {
			s ^= s << 13;
			s ^= s >>> 17;
			s ^= s << 5;
			return s * (1f / 2147483648f);
		}

		/** 0 to 1. */
		float unit() {
			return (next() + 1f) * 0.5f;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Rain: a band of hiss, a low roar, and drops ticking on top.
	// ---------------------------------------------------------------------------------------------

	static final class Rain extends Voice {
		private static final float C_LO = coef(700), C_ROAR = coef(250);
		private final Rng rng = new Rng(0x1234567);
		private float aL, bL, cL, tL, dropL, aR, bR, cR, tR, dropR;

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			float hiss = 0.12f + 0.19f * lv;
			// Surface: soft leaves (dull hiss, faint ticks) to a hard roof (bright hiss, sharp ticks).
			float cHi = coef(3500f + 7000f * opt), cTick = coef(1000f + 3000f * opt);
			float tick = 0.1f + 0.5f * opt;
			float rate = (40f + 900f * lv * lv) / SR;
			for (int i = 0; i < n; i++) {
				float nl = rng.next();
				float nr = rng.next();
				aL += cHi * (nl - aL);
				bL += C_LO * (nl - bL);
				cL += C_ROAR * (nl - cL);
				tL += cTick * (nl - tL);
				aR += cHi * (nr - aR);
				bR += C_LO * (nr - bR);
				cR += C_ROAR * (nr - cR);
				tR += cTick * (nr - tR);
				if (rng.unit() < rate) dropL = 0.4f + 0.6f * rng.unit();
				if (rng.unit() < rate) dropR = 0.4f + 0.6f * rng.unit();
				l[i] = (aL - bL) * hiss + cL * 1.0f + dropL * (nl - tL) * tick;
				r[i] = (aR - bR) * hiss + cR * 1.0f + dropR * (nr - tR) * tick;
				dropL *= 0.996f;
				dropR *= 0.996f;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Thunder: a flash first, then (the further away, the later) a rumble that swells and dies.
	// ---------------------------------------------------------------------------------------------

	static final class Thunder extends Voice {
		private static final float C_RUMBLE = coef(170), C_CRACK = coef(1800);
		private final Rng rng = new Rng(0x7A11E5);
		private final FlashSink sink;
		private int state;        // 0 waiting for a strike, 1 flash seen waiting for the sound, 2 rumbling
		private int wait = -1;
		private int delay;
		private boolean rising;
		private float env, peak, attack, decay, crack, crackNext;
		private float bl, br, fl, fr, hl, hr;

		Thunder(FlashSink sink) {
			this.sink = sink;
		}

		private int nextWait(float lv, boolean first) {
			if (first) return (int) (SR * (2f + 4f * rng.unit()));
			float sec = (36f - 28f * lv) * (0.6f + 0.8f * rng.unit());
			return (int) (SR * sec);
		}

		private void strike(float lv, float opt) {
			// Distance: the slider sets where the storm is, each strike varies around it.
			float dist = Math.max(0f, Math.min(1f, opt + (rng.unit() - 0.5f) * 0.6f));
			delay = (int) (SR * (0.12f + 2.3f * dist));
			peak = (0.4f + 0.6f * rng.unit()) * (1f - 0.5f * dist) * (0.55f + 0.45f * lv);
			attack = peak / (SR * (0.25f + 0.5f * rng.unit()));
			float seconds = 3.5f + 3.5f * rng.unit();
			decay = (float) Math.pow(0.002 / Math.max(0.01f, peak), 1.0 / (seconds * SR));
			crackNext = (dist < 0.35f) ? (1f - dist) : 0f;
			state = 1;
			if (sink != null) sink.flash(1f - 0.6f * dist);
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			if (wait < 0) wait = nextWait(lv, true);
			for (int i = 0; i < n; i++) {
				if (state == 0) {
					if (--wait <= 0) strike(lv, opt);
				} else if (state == 1) {
					if (--delay <= 0) {
						state = 2;
						env = 0f;
						rising = true;
						crack = crackNext;
					}
				}
				float e = 0f;
				if (state == 2) {
					if (rising) {
						env += attack;
						if (env >= peak) {
							env = peak;
							rising = false;
						}
					} else {
						env *= decay;
						if (env < 0.002f) {
							state = 0;
							env = 0f;
							wait = nextWait(lv, false);
						}
					}
					e = env;
				}
				float nl = rng.next();
				float nr = rng.next();
				// Brown noise (an integrated, leaky white one), low-passed: the rumble.
				bl = bl * 0.997f + nl * 0.025f;
				br = br * 0.997f + nr * 0.025f;
				fl += C_RUMBLE * (bl - fl);
				fr += C_RUMBLE * (br - fr);
				hl += C_CRACK * (nl - hl);
				hr += C_CRACK * (nr - hr);
				crack *= 0.9993f;
				l[i] = e * fl * 6f + crack * (nl - hl) * 0.3f;
				r[i] = e * fr * 6f + crack * (nr - hr) * 0.3f;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Fire: a low breathing bed, a hiss of flame, and crackles and pops at random.
	// ---------------------------------------------------------------------------------------------

	static final class Fire extends Voice {
		private static final float C_BED = coef(450), C_B1 = coef(3000), C_B2 = coef(900),
				C_CRK = coef(1200), C_POP = coef(700);
		private final Rng rng = new Rng(0xF1AE);
		private float bl, br, fl, fr, b1l, b1r, b2l, b2r, ckl, ckr, pl, pr;
		private float cl, cr, dl = 0.99f, dr = 0.99f, popL, popR;
		private float slow = 1f, slowT = 1f;
		private int slowCnt;

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			float rate = (3f + 26f * lv) * (0.3f + 1.4f * opt) / SR;
			float bedGain = 0.35f + 0.65f * lv;
			for (int i = 0; i < n; i++) {
				if (--slowCnt <= 0) {
					slowT = 0.7f + 0.6f * rng.unit();
					slowCnt = SR / 4 + (int) (rng.unit() * SR / 4);
				}
				slow += 0.0004f * (slowT - slow);
				float nl = rng.next();
				float nr = rng.next();
				bl = bl * 0.9985f + nl * 0.03f;
				br = br * 0.9985f + nr * 0.03f;
				fl += C_BED * (bl - fl);
				fr += C_BED * (br - fr);
				b1l += C_B1 * (nl - b1l);
				b2l += C_B2 * (nl - b2l);
				b1r += C_B1 * (nr - b1r);
				b2r += C_B2 * (nr - b2r);
				ckl += C_CRK * (nl - ckl);
				ckr += C_CRK * (nr - ckr);
				pl += C_POP * (nl - pl);
				pr += C_POP * (nr - pr);
				if (rng.unit() < rate) {
					float a = rng.unit();
					a = 0.12f + 0.88f * a * a * a;
					float side = rng.unit();
					float decay = 1f - (0.004f + 0.02f * rng.unit());
					if (side < 0.7f) {
						cl = Math.max(cl, a);
						dl = decay;
					}
					if (side > 0.3f) {
						cr = Math.max(cr, a);
						dr = decay;
					}
					// Now and then a deeper pop with it.
					if (rng.unit() < 0.06f) {
						if (side < 0.5f) popL = a;
						else popR = a;
					}
				}
				float breath = 0.12f * (0.5f + 0.5f * slow) * bedGain;
				float bed = fl * 0.5f * slow * bedGain;
				l[i] = bed + (b1l - b2l) * breath + cl * (nl - ckl) * 1.0f + popL * pl * 3f;
				r[i] = bed * 0.95f + (b1r - b2r) * breath + cr * (nr - ckr) * 1.0f + popR * pr * 3f;
				cl *= dl;
				cr *= dr;
				popL *= 0.9993f;
				popR *= 0.9993f;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Wind: noise between two moving cut-offs, swelling and fading in gusts.
	// ---------------------------------------------------------------------------------------------

	static final class Wind extends Voice {
		private final Rng rng = new Rng(0x31D0);
		private float gust = 0.3f, gustT = 0.3f;
		private int gustCnt;
		private float hiL, loL, hiR, loR;
		private float cHi = coef(800), cLo = coef(250);

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			float strength = 0.35f + 0.65f * lv;
			for (int i = 0; i < n; i++) {
				if (--gustCnt <= 0) {
					gustT = rng.unit();
					gustCnt = (int) (SR * (1.5f + 4f * rng.unit()));
				}
				gust += 0.00004f * (gustT - gust);
				if ((i & 63) == 0) {
					float fc = (220f + 650f * gust + 250f * lv) * (0.6f + 0.8f * opt);
					cHi = coef(fc * 1.7f);
					cLo = coef(fc * 0.55f);
				}
				float g = (0.1f + 0.9f * gust) * strength * 3f;
				float nl = rng.next();
				float nr = rng.next();
				hiL += cHi * (nl - hiL);
				loL += cLo * (nl - loL);
				hiR += cHi * (nr - hiR);
				loR += cLo * (nr - loR);
				l[i] = (hiL - loL) * g;
				r[i] = (hiR - loR) * g;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Ocean: surf that swells and brightens as each wave comes in, one wave train per ear.
	// ---------------------------------------------------------------------------------------------

	static final class Ocean extends Voice {
		private static final class Wave {
			float ph, per = 8f, peak = 0.8f;
		}

		private final Rng rng = new Rng(0x0CEA2);
		private final Wave wl = new Wave(), wr = new Wave();
		private float sl, sr, bl, br;
		private float cl = coef(900), cr = coef(900);
		private float el, er;
		private float pace = 1f;

		Ocean() {
			wl.ph = 0.3f;
			wr.ph = 0.7f;
		}

		private float envelope(Wave w, float lv) {
			w.ph += 1f / (w.per * SR);
			if (w.ph >= 1f) {
				w.ph = 0f;
				w.per = (6.5f + 5f * rng.unit() - 2f * lv) * pace;
				w.peak = 0.55f + 0.45f * rng.unit();
			}
			float x = w.ph;
			float s = (x < 0.4f) ? (x / 0.4f) : (1f - (x - 0.4f) / 0.6f);
			s = s * s * (3f - 2f * s);
			return 0.12f + s * w.peak;
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			pace = 1.6f - 1.2f * opt;
			float strength = (0.4f + 0.6f * lv) * 0.75f;
			for (int i = 0; i < n; i++) {
				el = envelope(wl, lv);
				er = envelope(wr, lv);
				if ((i & 63) == 0) {
					cl = coef(500f + 3500f * el);
					cr = coef(500f + 3500f * er);
				}
				float nl = rng.next();
				float nr = rng.next();
				sl += cl * (nl - sl);
				sr += cr * (nr - sr);
				bl = bl * 0.998f + nl * 0.02f;
				br = br * 0.998f + nr * 0.02f;
				l[i] = (sl * (0.25f + 0.9f * el) + bl * 0.5f * el) * strength;
				r[i] = (sr * (0.25f + 0.9f * er) + br * 0.5f * er) * strength;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Stream: babbling, bright water with a droplet plinking now and then.
	// ---------------------------------------------------------------------------------------------

	static final class Stream extends Voice {
		private static final float C_LOW = coef(380);
		private final Rng rng = new Rng(0x57AE);
		private float hiL, hiR, loL, loR;
		private float cHi = coef(3000);
		private float p1, p2, p3;
		private float plinkEnv, plinkPhase, plinkFreq, plinkL, plinkR;

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			float gain = (0.4f + 0.6f * lv) * (0.7f + 0.6f * opt);
			float flow = 0.6f + 0.8f * opt;
			float plinkRate = (0.4f + 2.5f * lv * lv) / SR;
			for (int i = 0; i < n; i++) {
				p1 += TWO_PI * 3.1f / SR;
				p2 += TWO_PI * 6.7f / SR;
				p3 += TWO_PI * 11.3f / SR;
				if (p1 > TWO_PI) p1 -= TWO_PI;
				if (p2 > TWO_PI) p2 -= TWO_PI;
				if (p3 > TWO_PI) p3 -= TWO_PI;
				float ampL = 0.65f + 0.2f * (float) Math.sin(p1) + 0.15f * (float) Math.sin(p2) +
						0.12f * (float) Math.sin(p3);
				float ampR = 0.65f + 0.2f * (float) Math.sin(p1 + 2.1f) +
						0.15f * (float) Math.sin(p2 + 4.2f) + 0.12f * (float) Math.sin(p3 + 1.3f);
				if ((i & 63) == 0) cHi = coef((2200f + 1800f * ampL) * flow);
				float nl = rng.next();
				float nr = rng.next();
				hiL += cHi * (nl - hiL);
				hiR += cHi * (nr - hiR);
				loL += C_LOW * (nl - loL);
				loR += C_LOW * (nr - loR);
				if (rng.unit() < plinkRate) {
					plinkEnv = 0.4f + 0.6f * rng.unit();
					plinkFreq = 500f + 1500f * rng.unit();
					plinkPhase = 0f;
					float pan = rng.unit();
					plinkL = 1f - pan * 0.8f;
					plinkR = 0.2f + pan * 0.8f;
				}
				float plink = 0f;
				if (plinkEnv > 0.001f) {
					plinkPhase += TWO_PI * plinkFreq / SR;
					if (plinkPhase > TWO_PI) plinkPhase -= TWO_PI;
					plinkFreq *= 1.0003f;
					plink = (float) Math.sin(plinkPhase) * plinkEnv * 0.16f;
					plinkEnv *= 0.999f;
				}
				l[i] = (hiL - loL) * ampL * gain + plink * plinkL;
				r[i] = (hiR - loR) * ampR * gain + plink * plinkR;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Droplets: single drops plinking into water, each a short sine that rises in pitch as it
	// dies away, then bounced around a cave by two feedback delays (a cheap reverb).
	// ---------------------------------------------------------------------------------------------

	static final class Drips extends Voice {
		private static final int SLOTS = 4;
		private final Rng rng = new Rng(0xD21B5);
		private final float[] ph = new float[SLOTS], freq = new float[SLOTS], env = new float[SLOTS],
				panL = new float[SLOTS], panR = new float[SLOTS];
		private final float[] bufL = new float[(int) (SR * 0.29f)], bufR = new float[(int) (SR * 0.37f)];
		private int pL, pR;
		private float lpL, lpR;

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			float rate = (0.25f + 1.6f * lv) / SR;
			float loud = 0.5f + 0.5f * lv;
			// Echo: from a dry drip to a cavern (the feedback of the two delays).
			float fb = 0.1f + 0.78f * opt;
			for (int i = 0; i < n; i++) {
				if (rng.unit() < rate) {
					for (int k = 0; k < SLOTS; k++) {
						if (env[k] > 0.001f) continue;
						ph[k] = 0f;
						freq[k] = 600f + 1400f * rng.unit();
						env[k] = (0.25f + 0.3f * rng.unit()) * loud;
						float pan = rng.unit();
						panL[k] = 1f - 0.7f * pan;
						panR[k] = 0.3f + 0.7f * pan;
						break;
					}
				}
				float xl = 0f, xr = 0f;
				for (int k = 0; k < SLOTS; k++) {
					if (env[k] <= 0.001f) continue;
					ph[k] += TWO_PI * freq[k] / SR;
					if (ph[k] > TWO_PI) ph[k] -= TWO_PI;
					freq[k] *= 1.0002f;
					float s = (float) Math.sin(ph[k]) * env[k];
					env[k] *= 0.9992f;
					xl += s * panL[k];
					xr += s * panR[k];
				}
				// Each side's echo feeds itself through a low-pass, so the repeats soften as they go.
				float dl = bufL[pL];
				lpL += 0.3f * (dl - lpL);
				float yl = xl + lpL * fb;
				bufL[pL] = yl;
				if (++pL >= bufL.length) pL = 0;
				float dr = bufR[pR];
				lpR += 0.3f * (dr - lpR);
				float yr = xr + lpR * fb;
				bufR[pR] = yr;
				if (++pR >= bufR.length) pR = 0;
				l[i] = yl;
				r[i] = yr;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Underwater: a deep muffled hum, with single bubbles and now and then a rush of rising ones.
	// ---------------------------------------------------------------------------------------------

	static final class Bubbles extends Voice {
		private static final int SLOTS = 8;
		private final Rng rng = new Rng(0xB0BB1E);
		private final float[] ph = new float[SLOTS], freq = new float[SLOTS], env = new float[SLOTS],
				rise = new float[SLOTS], panL = new float[SLOTS], panR = new float[SLOTS];
		private int burst, burstWait;
		private float bedL, bedR, lpL, lpR, swellPh;

		private void spawn(float size, float lv) {
			for (int k = 0; k < SLOTS; k++) {
				if (env[k] > 0.001f) continue;
				ph[k] = 0f;
				freq[k] = (250f + 900f * rng.unit()) * size;
				rise[k] = 1.00012f + 0.00015f * rng.unit();
				env[k] = (0.12f + 0.2f * rng.unit()) * (0.5f + 0.5f * lv);
				float pan = rng.unit();
				panL[k] = 1f - 0.7f * pan;
				panR[k] = 0.3f + 0.7f * pan;
				return;
			}
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			// Depth: shallow water is bright and bubbly, deep water low and muffled.
			float size = 1.6f - 1.2f * opt;
			float cut = coef(3800f - 2600f * opt);
			float bed = 0.5f + 1.0f * opt;
			float rate = (0.5f + 3.0f * lv) / SR;
			float clusterRate = (0.1f + 0.8f * lv) / SR;
			swellPh += TWO_PI * 0.09f * n / SR;
			if (swellPh > TWO_PI) swellPh -= TWO_PI;
			float swell = 0.7f + 0.3f * (float) Math.sin(swellPh);
			for (int i = 0; i < n; i++) {
				if (rng.unit() < rate) spawn(size, lv);
				if (burst > 0) {
					if (--burstWait <= 0) {
						spawn(size, lv);
						burst--;
						burstWait = (int) (SR * (0.025f + 0.07f * rng.unit()));
					}
				} else if (rng.unit() < clusterRate) {
					burst = 3 + (int) (rng.unit() * 5f);
					burstWait = 0;
				}
				float xl = 0f, xr = 0f;
				for (int k = 0; k < SLOTS; k++) {
					if (env[k] <= 0.001f) continue;
					ph[k] += TWO_PI * freq[k] / SR;
					if (ph[k] > TWO_PI) ph[k] -= TWO_PI;
					freq[k] *= rise[k];
					float s = (float) Math.sin(ph[k]) * env[k];
					env[k] *= 0.9993f;
					xl += s * panL[k];
					xr += s * panR[k];
				}
				bedL = bedL * 0.998f + rng.next() * 0.02f;
				bedR = bedR * 0.998f + rng.next() * 0.02f;
				lpL += cut * (xl + bedL * 0.5f * bed * swell - lpL);
				lpR += cut * (xr + bedR * 0.5f * bed * swell - lpR);
				l[i] = lpL;
				r[i] = lpR;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Wind chimes: strikes in small runs on a pentatonic set of tubes. Each tube rings with three
	// bar-chime partials (1, 2.76 and 5.4 times the pitch) that die away at their own pace, made by
	// rotating a point round a circle (no sine per sample).
	// ---------------------------------------------------------------------------------------------

	static final class Chimes extends Voice {
		private static final int SLOTS = 6, PARTS = 3;
		private static final float[] NOTES = {587f, 659f, 784f, 880f, 988f, 1175f, 1319f};
		private static final float[] RATIO = {1f, 2.76f, 5.40f};
		private static final float[] WEIGHT = {1f, 0.45f, 0.2f};
		private static final float[] TAU = {3.2f, 1.5f, 0.6f};
		private final Rng rng = new Rng(0xC41E5);
		private final float[] px = new float[SLOTS * PARTS], py = new float[SLOTS * PARTS],
				rc = new float[SLOTS * PARTS], rs = new float[SLOTS * PARTS],
				amp = new float[SLOTS * PARTS], dec = new float[SLOTS * PARTS];
		private final float[] panL = new float[SLOTS], panR = new float[SLOTS];
		private int burst, burstWait;

		private void strike(float pitch, float lv) {
			int slot = 0;
			float lowest = Float.MAX_VALUE;
			for (int k = 0; k < SLOTS; k++) {
				float a = amp[k * PARTS];
				if (a < lowest) {
					lowest = a;
					slot = k;
				}
			}
			float note = NOTES[(int) (rng.unit() * (NOTES.length - 0.01f))] * pitch;
			float vel = (0.5f + 0.5f * rng.unit()) * 0.11f * (0.5f + 0.5f * lv);
			for (int p = 0; p < PARTS; p++) {
				int idx = slot * PARTS + p;
				float f = note * RATIO[p];
				if (f > 9000f) {
					amp[idx] = 0f;
					continue;
				}
				float w = TWO_PI * f / SR;
				rc[idx] = (float) Math.cos(w);
				rs[idx] = (float) Math.sin(w);
				px[idx] = 1f;
				py[idx] = 0f;
				amp[idx] = vel * WEIGHT[p];
				dec[idx] = (float) Math.exp(-1.0 / (TAU[p] * SR));
			}
			float pan = rng.unit();
			panL[slot] = 1f - 0.7f * pan;
			panR[slot] = 0.3f + 0.7f * pan;
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			// Bells: deep and slow to bright and glassy, by shifting every tube's pitch.
			float pitch = 0.5f + 1.0f * opt;
			float rate = (0.12f + 0.9f * lv) / SR;
			for (int i = 0; i < n; i++) {
				if ((burst == 0) && (rng.unit() < rate)) {
					burst = 1 + (int) (rng.unit() * 3.99f);
					burstWait = 0;
				}
				if ((burst > 0) && (--burstWait <= 0)) {
					strike(pitch, lv);
					burst--;
					burstWait = (int) (SR * (0.07f + 0.22f * rng.unit()));
				}
				float sl = 0f, sr = 0f;
				for (int k = 0; k < SLOTS; k++) {
					float t = 0f;
					for (int p = 0; p < PARTS; p++) {
						int idx = k * PARTS + p;
						float a = amp[idx];
						if (a <= 0.00005f) continue;
						float nx = px[idx] * rc[idx] - py[idx] * rs[idx];
						float ny = px[idx] * rs[idx] + py[idx] * rc[idx];
						px[idx] = nx;
						py[idx] = ny;
						t += ny * a;
						amp[idx] = a * dec[idx];
					}
					sl += t * panL[k];
					sr += t * panR[k];
				}
				l[i] = sl;
				r[i] = sr;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Cozy café: a crowd of talkers (noise through two moving formant bands, switched on and off in
	// syllables with pauses between), a low room hum, and cups clinking now and then; all of it
	// softened by a low-pass so it stays in the background.
	// ---------------------------------------------------------------------------------------------

	static final class Cafe extends Voice {
		private static final int TALKERS = 7, CLINKS = 3;
		private static final float C_WARM = coef(4500);

		private static final class Talker {
			float a1, b1, a2, b2, c1h, c1l, c2h, c2l, env, envT, g, panL, panR;
			int count;
		}

		private final Rng rng = new Rng(0xCAFE);
		private final Talker[] talkers = new Talker[TALKERS];
		private final float[] cph = new float[CLINKS * 2], cfr = new float[CLINKS * 2],
				cenv = new float[CLINKS], cpl = new float[CLINKS], cpr = new float[CLINKS];
		private float roomL, roomR, warmL, warmR;

		Cafe() {
			for (int k = 0; k < TALKERS; k++) {
				Talker t = new Talker();
				t.g = 0.7f + 0.6f * rng.unit();
				float pan = (k + 0.5f) / TALKERS;
				t.panL = 1f - 0.8f * pan;
				t.panR = 0.2f + 0.8f * pan;
				t.count = (int) (rng.unit() * SR * 0.4f);
				talkers[k] = t;
			}
		}

		private void nextSyllable(Talker t) {
			if (rng.unit() < 0.22f) {
				t.envT = 0f;
				t.count = (int) (SR * (0.25f + 0.9f * rng.unit()));
				return;
			}
			t.envT = 0.35f + 0.65f * rng.unit();
			t.count = (int) (SR * (0.10f + 0.20f * rng.unit()));
			float f1 = 350f + 450f * rng.unit();
			float f2 = 1000f + 1400f * rng.unit();
			t.c1h = coef(f1 * 1.4f);
			t.c1l = coef(f1 * 0.7f);
			t.c2h = coef(f2 * 1.4f);
			t.c2l = coef(f2 * 0.7f);
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			// Crowd: a quiet corner (two talkers, rare clinks) to a busy room (all seven).
			int active = 2 + Math.round(opt * 5f);
			float clinkRate = (0.15f + 1.2f * opt) * (0.5f + 0.5f * lv) / SR;
			float chatter = 0.45f + 0.55f * lv;
			for (int i = 0; i < n; i++) {
				float sl = 0f, sr = 0f;
				for (int k = 0; k < TALKERS; k++) {
					Talker t = talkers[k];
					if (--t.count <= 0) nextSyllable(t);
					float target = (k < active) ? t.envT : 0f;
					t.env += 0.0009f * (target - t.env);
					if ((t.env < 0.002f) && (target == 0f)) continue;
					float nz = rng.next();
					t.a1 += t.c1h * (nz - t.a1);
					t.b1 += t.c1l * (nz - t.b1);
					t.a2 += t.c2h * (nz - t.a2);
					t.b2 += t.c2l * (nz - t.b2);
					float s = ((t.a1 - t.b1) + 0.6f * (t.a2 - t.b2)) * t.env * t.g * 2.2f * chatter;
					sl += s * t.panL;
					sr += s * t.panR;
				}
				roomL = roomL * 0.998f + rng.next() * 0.02f;
				roomR = roomR * 0.998f + rng.next() * 0.02f;
				sl += roomL * 0.15f;
				sr += roomR * 0.15f;
				if (rng.unit() < clinkRate) {
					for (int c = 0; c < CLINKS; c++) {
						if (cenv[c] > 0.001f) continue;
						float base = 2400f + 3200f * rng.unit();
						cfr[c * 2] = base;
						cfr[c * 2 + 1] = base * (2.3f + 0.6f * rng.unit());
						cph[c * 2] = 0f;
						cph[c * 2 + 1] = 0f;
						cenv[c] = 0.05f + 0.07f * rng.unit();
						float pan = rng.unit();
						cpl[c] = 1f - 0.8f * pan;
						cpr[c] = 0.2f + 0.8f * pan;
						break;
					}
				}
				for (int c = 0; c < CLINKS; c++) {
					if (cenv[c] <= 0.001f) continue;
					float s = 0f;
					for (int p = 0; p < 2; p++) {
						int idx = c * 2 + p;
						cph[idx] += TWO_PI * cfr[idx] / SR;
						if (cph[idx] > TWO_PI) cph[idx] -= TWO_PI;
						s += (float) Math.sin(cph[idx]) * (p == 0 ? 1f : 0.6f);
					}
					s *= cenv[c];
					cenv[c] *= 0.9993f;
					sl += s * cpl[c];
					sr += s * cpr[c];
				}
				warmL += C_WARM * (sl - warmL);
				warmR += C_WARM * (sr - warmR);
				l[i] = warmL;
				r[i] = warmR;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Night: a few crickets, each a high tone chopped into quick pulses that repeat in bursts.
	// ---------------------------------------------------------------------------------------------

	static final class Night extends Voice {
		private static final int CRICKETS = 4;
		private static final float C_AIR = coef(900);

		private static final class Cricket {
			float freq, ph, t, cycle = 0.4f, burst = 0.12f, pulseRate, amp, panL, panR;
			boolean on;
		}

		private final Rng rng = new Rng(0xC1C4);
		private final Cricket[] crickets = new Cricket[CRICKETS];
		private float airL, airR;

		Night() {
			for (int k = 0; k < CRICKETS; k++) {
				Cricket c = new Cricket();
				c.freq = 4200f + 220f * k + 80f * rng.unit();
				c.pulseRate = 48f + 6f * k;
				c.t = rng.unit() * 0.4f;
				c.amp = 0.15f + 0.04f * rng.unit();
				float pan = (k + 0.5f) / CRICKETS;
				c.panL = 1f - 0.7f * pan;
				c.panR = 0.3f + 0.7f * pan;
				crickets[k] = c;
			}
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			int count = 1 + (int) (lv * 3.99f);
			float pm = 0.85f + 0.3f * opt;
			float dt = 1f / SR;
			for (int i = 0; i < n; i++) {
				float nl = rng.next();
				float nr = rng.next();
				airL += C_AIR * (nl - airL);
				airR += C_AIR * (nr - airR);
				float sl = airL * 0.05f;
				float sr = airR * 0.05f;
				for (int k = 0; k < CRICKETS; k++) {
					Cricket c = crickets[k];
					c.t += dt;
					if (c.t >= c.cycle) {
						c.t -= c.cycle;
						c.cycle = 0.32f + 0.25f * rng.unit();
						c.burst = 0.10f + 0.07f * rng.unit();
						c.on = k < count;
					}
					if (!c.on || (c.t >= c.burst)) continue;
					float p = (float) Math.sin(TWO_PI * c.pulseRate * c.t);
					if (p <= 0f) continue;
					c.ph += TWO_PI * c.freq * pm / SR;
					if (c.ph > TWO_PI) c.ph -= TWO_PI;
					float env = (float) Math.sin(Math.PI * c.t / c.burst) * p;
					float s = (float) Math.sin(c.ph) * env * c.amp;
					sl += s * c.panL;
					sr += s * c.panR;
				}
				l[i] = sl;
				r[i] = sr;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Birds: short phrases of swept whistles, a few birds calling at their own pace.
	// ---------------------------------------------------------------------------------------------

	static final class Birds extends Voice {
		private static final int BIRDS = 3;
		private static final float C_AIR = coef(1200);

		private static final class Bird {
			int state;            // 0 waiting, 1 singing a note, 2 pause between notes
			int count;            // wait, note or pause length left in samples
			int noteLen, notesLeft;
			float f0, f1, ph, amp, panL = 0.7f, panR = 0.7f;
		}

		private final Rng rng = new Rng(0xB1D5);
		private final Bird[] birds = new Bird[BIRDS];
		private float airL, airR;
		private float busy = 1f;

		Birds() {
			for (int k = 0; k < BIRDS; k++) {
				birds[k] = new Bird();
				birds[k].count = (int) (SR * (1f + 3f * rng.unit() + k));
			}
		}

		private int nextWait(float lv) {
			return (int) (SR * (7f - 5.5f * lv) * (0.5f + rng.unit()) * busy);
		}

		private void startNote(Bird b) {
			b.noteLen = (int) (SR * (0.05f + 0.11f * rng.unit()));
			b.count = b.noteLen;
			b.f0 = 2400f + 2600f * rng.unit();
			b.f1 = b.f0 * (0.65f + 0.9f * rng.unit());
			b.state = 1;
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			float loud = 0.4f + 0.6f * lv;
			// Chorus: a few lone calls to a busy dawn.
			busy = 1.5f - 1.0f * opt;
			for (int i = 0; i < n; i++) {
				float nl = rng.next();
				float nr = rng.next();
				airL += C_AIR * (nl - airL);
				airR += C_AIR * (nr - airR);
				float sl = airL * 0.04f;
				float sr = airR * 0.04f;
				for (int k = 0; k < BIRDS; k++) {
					Bird b = birds[k];
					if (b.state == 0) {
						if (--b.count <= 0) {
							b.notesLeft = 2 + (int) (rng.unit() * 4f);
							b.amp = (0.12f + 0.14f * rng.unit()) * loud;
							float pan = rng.unit();
							b.panL = 1f - 0.8f * pan;
							b.panR = 0.2f + 0.8f * pan;
							startNote(b);
						}
					} else if (b.state == 2) {
						if (--b.count <= 0) startNote(b);
					} else {
						float u = 1f - b.count / (float) b.noteLen;
						float f = b.f0 + (b.f1 - b.f0) * u * u;
						b.ph += TWO_PI * f / SR;
						if (b.ph > TWO_PI) b.ph -= TWO_PI;
						float env = (float) Math.sin(Math.PI * u);
						float s = ((float) Math.sin(b.ph) + 0.25f * (float) Math.sin(2f * b.ph)) * env * b.amp;
						sl += s * b.panL;
						sr += s * b.panR;
						if (--b.count <= 0) {
							if (--b.notesLeft > 0) {
								b.state = 2;
								b.count = (int) (SR * (0.03f + 0.09f * rng.unit()));
							} else {
								b.state = 0;
								b.count = nextWait(lv);
							}
						}
					}
				}
				l[i] = sl;
				r[i] = sr;
			}
		}
	}
}
