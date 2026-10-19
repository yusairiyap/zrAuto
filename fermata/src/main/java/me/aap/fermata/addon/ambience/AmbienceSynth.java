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
			case WINDOW -> new Window();
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
	// A small Freeverb-style reverb: four damped feedback combs in parallel and two allpasses in
	// series for each ear. {@code scale} stretches the delay lengths (a bigger room), {@link
	// #setDecay} sets how long the tail lasts. Mono in, {@link #outL}/{@link #outR} out (wet only).
	// ---------------------------------------------------------------------------------------------

	static final class Reverb {
		private static final int[] COMB = {1116, 1188, 1277, 1356};
		private static final int[] ALL = {556, 441};
		private final float[][] cbL = new float[4][], cbR = new float[4][];
		private final float[][] abL = new float[2][], abR = new float[2][];
		private final int[] ciL = new int[4], ciR = new int[4], aiL = new int[2], aiR = new int[2];
		private final float[] fsL = new float[4], fsR = new float[4], fbL = new float[4], fbR = new float[4];
		private float damp;
		float outL, outR;

		Reverb(float scale) {
			for (int c = 0; c < 4; c++) {
				cbL[c] = new float[Math.round(COMB[c] * scale)];
				cbR[c] = new float[Math.round((COMB[c] + 23) * scale)];
			}
			for (int a = 0; a < 2; a++) {
				abL[a] = new float[ALL[a]];
				abR[a] = new float[ALL[a] + 23];
			}
			setDecay(1f, 0.4f);
		}

		/** @param rt60 seconds for the tail to fall 60 dB; @param damp 0 to 1, how much faster the highs die */
		void setDecay(float rt60, float damp) {
			this.damp = damp;
			for (int c = 0; c < 4; c++) {
				fbL[c] = (float) Math.pow(10.0, -3.0 * cbL[c].length / (rt60 * SR));
				fbR[c] = (float) Math.pow(10.0, -3.0 * cbR[c].length / (rt60 * SR));
			}
		}

		void process(float in) {
			float sl = 0f, sr = 0f;
			for (int c = 0; c < 4; c++) {
				float[] b = cbL[c];
				int i = ciL[c];
				float o = b[i];
				fsL[c] = o * (1f - damp) + fsL[c] * damp;
				b[i] = in + fsL[c] * fbL[c];
				ciL[c] = (i + 1 >= b.length) ? 0 : i + 1;
				sl += o;
				b = cbR[c];
				i = ciR[c];
				o = b[i];
				fsR[c] = o * (1f - damp) + fsR[c] * damp;
				b[i] = in + fsR[c] * fbR[c];
				ciR[c] = (i + 1 >= b.length) ? 0 : i + 1;
				sr += o;
			}
			for (int a = 0; a < 2; a++) {
				float[] b = abL[a];
				int i = aiL[a];
				float o = b[i];
				float v = sl;
				b[i] = v + o * 0.5f;
				sl = o - v;
				aiL[a] = (i + 1 >= b.length) ? 0 : i + 1;
				b = abR[a];
				i = aiR[a];
				o = b[i];
				v = sr;
				b[i] = v + o * 0.5f;
				sr = o - v;
				aiR[a] = (i + 1 >= b.length) ? 0 : i + 1;
			}
			outL = sl * 0.25f;
			outR = sr * 0.25f;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Droplets: single drops into still water in a cave. A drop is a short "plip": a sine that
	// glides up in pitch as it dies away (the bubble the drop makes), a faint octave over it and a
	// tick of noise at the start; the tail is the cave, a real reverb that grows with the Echo slider.
	// ---------------------------------------------------------------------------------------------

	static final class Drips extends Voice {
		private static final int SLOTS = 4;
		private final Rng rng = new Rng(0xD21B5);
		private final float[] ph = new float[SLOTS], f0 = new float[SLOTS], ce = new float[SLOTS],
				env = new float[SLOTS], amp = new float[SLOTS], att = new float[SLOTS],
				clk = new float[SLOTS], panL = new float[SLOTS], panR = new float[SLOTS];
		private final Reverb verb = new Reverb(2.6f);
		private float lastRt = -1f;
		private float hpL;

		private void spawn(float loud) {
			for (int k = 0; k < SLOTS; k++) {
				if (env[k] > 0.0008f) continue;
				float u = rng.unit();
				ph[k] = 0f;
				// 280 to 660 Hz (it glides up from there), and now and then a bigger, lower plop.
				f0[k] = (280f + 380f * u) * ((rng.unit() < 0.15f) ? 0.65f : 1f);
				ce[k] = 1f;
				amp[k] = (0.28f + 0.3f * rng.unit()) * loud;
				env[k] = amp[k];
				att[k] = 0f;
				clk[k] = 1f;
				float pan = rng.unit();
				panL[k] = 1f - 0.6f * pan;
				panR[k] = 0.4f + 0.6f * pan;
				return;
			}
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			float rate = (0.25f + 0.9f * lv) / SR;
			float loud = 0.55f + 0.45f * lv;
			// Echo: a dry drip over a few small walls, to a cavern with a long tail.
			float wet = 0.35f + 2.2f * opt;
			float dry = 0.85f - 0.35f * opt;
			float rt = 0.5f + 3.5f * opt;
			if (rt != lastRt) {
				verb.setDecay(rt, 0.55f - 0.3f * opt);
				lastRt = rt;
			}
			for (int i = 0; i < n; i++) {
				if (rng.unit() < rate) spawn(loud);
				float dl = 0f, dr = 0f, mono = 0f;
				for (int k = 0; k < SLOTS; k++) {
					if (env[k] <= 0.0008f) continue;
					float f = f0[k] * (1f + 0.7f * (1f - ce[k]));
					ph[k] += TWO_PI * f / SR;
					if (ph[k] > TWO_PI) ph[k] -= TWO_PI;
					ce[k] *= 0.9988f;
					float a = Math.min(1f, att[k]);
					att[k] += 1f / 60f;
					float nz = rng.next();
					hpL += 0.3f * (nz - hpL);
					float tick = (nz - hpL) * amp[k] * clk[k] * 0.15f;
					clk[k] *= 0.985f;
					float s = ((float) Math.sin(ph[k]) + 0.25f * (float) Math.sin(2f * ph[k])) * env[k] * a + tick;
					env[k] *= 0.9993f;
					dl += s * panL[k];
					dr += s * panR[k];
					mono += s;
				}
				verb.process(mono);
				l[i] = dl * dry + verb.outL * wet;
				r[i] = dr * dry + verb.outR * wet;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Underwater: a deep, slowly swirling hum with bubbles in it. A bubble is a low sine that
	// glides up (a big one is lower and slower) with a soft attack; now and then a rush of them
	// rises in pitch ("glug glug"). It all goes through a muffling low-pass and a small diffuse room.
	// ---------------------------------------------------------------------------------------------

	static final class Bubbles extends Voice {
		private static final int SLOTS = 8;
		private final Rng rng = new Rng(0xB0BB1E);
		private final float[] ph = new float[SLOTS], f0 = new float[SLOTS], ce = new float[SLOTS],
				env = new float[SLOTS], att = new float[SLOTS], panL = new float[SLOTS],
				panR = new float[SLOTS];
		private final Reverb verb = new Reverb(1.3f);
		private int burst, burstWait;
		private float burstPitch = 1f, burstAmp = 1f;
		private float bl, br, bedL, bedR, lpL, lpR, swirlPh;

		Bubbles() {
			verb.setDecay(1.4f, 0.5f);
		}

		private void spawn(float size, float lv, float pitchMul, float ampMul) {
			for (int k = 0; k < SLOTS; k++) {
				if (env[k] > 0.001f) continue;
				float u = rng.unit();
				f0[k] = Math.max(90f, (120f + 380f * u * u + 150f * u) * size * pitchMul);
				ph[k] = 0f;
				ce[k] = 1f;
				att[k] = 0f;
				env[k] = (0.14f + 0.22f * rng.unit()) * (0.5f + 0.5f * lv) * ampMul;
				float pan = rng.unit();
				panL[k] = 1f - 0.7f * pan;
				panR[k] = 0.3f + 0.7f * pan;
				return;
			}
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			// Depth: shallow water is lighter and bubblier, deep water low and muffled.
			float size = 1.5f - 1.0f * opt;
			float cut = coef(3200f - 1800f * opt);
			float bed = 0.4f + 0.9f * opt;
			swirlPh += TWO_PI * 0.07f * n / SR;
			if (swirlPh > TWO_PI) swirlPh -= TWO_PI;
			float bedCut = coef(150f + 350f * (0.5f + 0.5f * (float) Math.sin(swirlPh)));
			float rate = (0.4f + 2.4f * lv) / SR;
			float clusterRate = (0.08f + 0.55f * lv) / SR;
			for (int i = 0; i < n; i++) {
				if (rng.unit() < rate) spawn(size, lv, 1f, 1f);
				if (burst > 0) {
					if (--burstWait <= 0) {
						spawn(size, lv, burstPitch, burstAmp);
						burstPitch *= 1.07f + 0.05f * rng.unit();
						burstAmp *= 0.9f;
						burst--;
						burstWait = (int) (SR * (0.04f + 0.07f * rng.unit()));
					}
				} else if (rng.unit() < clusterRate) {
					burst = 3 + (int) (rng.unit() * 4f);
					burstWait = 0;
					burstPitch = 0.8f;
					burstAmp = 1f;
				}
				float dl = 0f, dr = 0f, mono = 0f;
				for (int k = 0; k < SLOTS; k++) {
					if (env[k] <= 0.001f) continue;
					float f = f0[k] * (1f + 1.1f * (1f - ce[k]));
					ph[k] += TWO_PI * f / SR;
					if (ph[k] > TWO_PI) ph[k] -= TWO_PI;
					ce[k] *= 0.999f;
					float a = Math.min(1f, att[k]);
					att[k] += 1f / 130f;
					float s = (float) Math.sin(ph[k]) * env[k] * a;
					env[k] *= 0.9997f;
					dl += s * panL[k];
					dr += s * panR[k];
					mono += s;
				}
				bl = bl * 0.998f + rng.next() * 0.02f;
				br = br * 0.998f + rng.next() * 0.02f;
				bedL += bedCut * (bl - bedL);
				bedR += bedCut * (br - bedR);
				verb.process(mono * 0.6f);
				lpL += cut * (dl + bedL * bed * 0.7f + verb.outL * 0.6f - lpL);
				lpR += cut * (dr + bedR * bed * 0.7f + verb.outR * 0.6f - lpR);
				l[i] = lpL;
				r[i] = lpR;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Rainy window: the hush of rain heard from indoors (a soft band of noise swelling slowly) and
	// single drops tapping the glass, each a short damped ring of the pane at its own pitch.
	// ---------------------------------------------------------------------------------------------

	static final class Window extends Voice {
		private static final int SLOTS = 10;
		private static final float C_HI = coef(2400), C_LO = coef(250);
		private final Rng rng = new Rng(0x3A1D0);
		private final float[] ph = new float[SLOTS], freq = new float[SLOTS], env = new float[SLOTS],
				dec = new float[SLOTS], panL = new float[SLOTS], panR = new float[SLOTS];
		private float aL, bL, aR, bR, brownL, brownR, swellPh;

		private void tap(float lv) {
			for (int k = 0; k < SLOTS; k++) {
				if (env[k] > 0.0008f) continue;
				float u = rng.unit();
				boolean big = rng.unit() < 0.05f;
				freq[k] = big ? 250f + 150f * u : 1100f + 2300f * u;
				float tau = big ? 0.025f : 0.006f + 0.01f * rng.unit();
				dec[k] = (float) Math.exp(-1.0 / (tau * SR));
				env[k] = (big ? 0.22f : 0.04f + 0.16f * u * u) * (0.5f + 0.5f * lv);
				ph[k] = 0f;
				float pan = rng.unit();
				panL[k] = 1f - 0.8f * pan;
				panR[k] = 0.2f + 0.8f * pan;
				return;
			}
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			// Rain: a light drizzle to a downpour: how many drops hit and how loud the hush is.
			float rate = (2f + 40f * lv * lv) * (0.3f + 1.4f * opt) / SR;
			float hush = (0.25f + 0.4f * lv) * (0.7f + 0.6f * opt);
			swellPh += TWO_PI * 0.12f * n / SR;
			if (swellPh > TWO_PI) swellPh -= TWO_PI;
			float swell = 0.85f + 0.15f * (float) Math.sin(swellPh);
			for (int i = 0; i < n; i++) {
				if (rng.unit() < rate) tap(lv);
				float nl = rng.next();
				float nr = rng.next();
				aL += C_HI * (nl - aL);
				bL += C_LO * (nl - bL);
				aR += C_HI * (nr - aR);
				bR += C_LO * (nr - bR);
				brownL = brownL * 0.998f + nl * 0.02f;
				brownR = brownR * 0.998f + nr * 0.02f;
				float sl = (aL - bL) * hush * swell + brownL * 0.25f;
				float sr = (aR - bR) * hush * swell + brownR * 0.25f;
				for (int k = 0; k < SLOTS; k++) {
					if (env[k] <= 0.0008f) continue;
					ph[k] += TWO_PI * freq[k] / SR;
					if (ph[k] > TWO_PI) ph[k] -= TWO_PI;
					float s = (float) Math.sin(ph[k]) * env[k];
					env[k] *= dec[k];
					sl += s * panL[k];
					sr += s * panR[k];
				}
				l[i] = sl;
				r[i] = sr;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Cozy café, without voices: the room's low hum, cups and spoons clinking on china (inharmonic
	// ringing partials), a spoon stirred, a stack of dishes set down, a cup put on its saucer, and
	// every so often the espresso machine (grinder, then steam) and the shop bell over the door; all
	// of it in a small room's reverb.
	// ---------------------------------------------------------------------------------------------

	static final class Cafe extends Voice {
		private static final int SLOTS = 8, PARTS = 3;
		private static final float[] CHINA = {1f, 2.76f, 5.40f};
		private static final float[] CHINA_TAU = {0.07f, 0.035f, 0.018f};
		private static final float[] BELL = {1f, 2.4f, 4.1f};
		private static final float[] BELL_TAU = {0.9f, 0.5f, 0.25f};
		private static final float[] WEIGHT = {1f, 0.55f, 0.3f};
		private static final float C_ROOM = coef(250), C_AIR = coef(1200), C_STEAM = coef(3000),
				C_BAND_HI = coef(2500), C_BAND_LO = coef(500), C_GRIND = coef(900), C_WARM = coef(7000);
		private final Rng rng = new Rng(0xCAFE);
		private final Reverb verb = new Reverb(1.0f);
		private final float[] ph = new float[SLOTS * PARTS], fr = new float[SLOTS * PARTS],
				am = new float[SLOTS * PARTS], dc = new float[SLOTS * PARTS],
				panL = new float[SLOTS], panR = new float[SLOTS];
		private int seqLeft, seqWait, seqKind;
		private float seqBase, seqAmp;
		private float thunkEnv, thunkPh, thunkF;
		private int machPhase, machLeft;
		private float grindEnv, steamEnv, grindPh, grindAm, grindLp, bandHi, bandLo, steamLp, turb, turbT;
		private int turbCnt;
		private float roomL, roomR, airL, airR, warmL, warmR;

		Cafe() {
			verb.setDecay(0.7f, 0.5f);
		}

		/** Starts a ringing object: three partials at the given ratios, each dying at its own pace. */
		private void ring(float base, float amp, float[] ratio, float[] tau) {
			int slot = 0;
			float lowest = Float.MAX_VALUE;
			for (int k = 0; k < SLOTS; k++) {
				float a = am[k * PARTS];
				if (a < lowest) {
					lowest = a;
					slot = k;
				}
			}
			for (int p = 0; p < PARTS; p++) {
				int idx = slot * PARTS + p;
				fr[idx] = base * ratio[p];
				ph[idx] = 0f;
				am[idx] = (fr[idx] < 12000f) ? amp * WEIGHT[p] : 0f;
				dc[idx] = (float) Math.exp(-1.0 / (tau[p] * SR));
			}
			float pan = 0.25f + 0.5f * rng.unit();
			panL[slot] = 1f - pan;
			panR[slot] = pan;
		}

		@Override
		void render(float[] l, float[] r, int n, float lv, float opt) {
			// Activity: a quiet corner (rare clinks, the machine now and then) to a busy counter.
			float busy = 0.3f + 1.4f * opt;
			float clinkRate = 0.35f * busy * (0.5f + 0.5f * lv) / SR;
			float seqRate = 0.04f * busy / SR;
			float thunkRate = 0.1f * busy / SR;
			float machRate = (1f / 45f) * busy / SR;
			float bellRate = (1f / 80f) * busy / SR;
			float room = 0.06f * (0.7f + 0.6f * opt);
			for (int i = 0; i < n; i++) {
				if (rng.unit() < clinkRate) ring(1900f + 1400f * rng.unit(), 0.05f + 0.07f * rng.unit(), CHINA, CHINA_TAU);
				if ((seqLeft == 0) && (rng.unit() < seqRate)) {
					seqKind = (rng.unit() < 0.7f) ? 0 : 1;
					seqLeft = (seqKind == 0) ? 8 + (int) (rng.unit() * 6f) : 4 + (int) (rng.unit() * 4f);
					seqBase = 3000f + 1600f * rng.unit();
					seqAmp = 0.05f;
					seqWait = 0;
				}
				if ((seqLeft > 0) && (--seqWait <= 0)) {
					if (seqKind == 0) {
						// A spoon going round a glass: small, quick clinks of nearly the same pitch.
						ring(seqBase * (0.96f + 0.08f * rng.unit()), seqAmp * (0.4f + 0.6f * rng.unit()), CHINA, CHINA_TAU);
						seqWait = (int) (SR * (0.09f + 0.07f * rng.unit()));
					} else {
						// Dishes set down on each other: wider pitches, closer together, louder first.
						ring(1500f + 3000f * rng.unit(), seqAmp * 1.6f * (0.5f + 0.5f * rng.unit()), CHINA, CHINA_TAU);
						seqAmp *= 0.8f;
						seqWait = (int) (SR * (0.03f + 0.06f * rng.unit()));
					}
					seqLeft--;
				}
				if (rng.unit() < thunkRate) {
					thunkF = 130f + 70f * rng.unit();
					thunkEnv = 0.18f + 0.1f * rng.unit();
					thunkPh = 0f;
				}
				if (rng.unit() < bellRate) ring(2300f, 0.09f, BELL, BELL_TAU);
				if ((machPhase == 0) && (rng.unit() < machRate)) {
					machPhase = 1;
					machLeft = (int) (SR * (1.5f + 0.8f * rng.unit()));
				}
				float machine = 0f;
				if (machPhase != 0) {
					if (--machLeft <= 0) {
						if (machPhase == 1) {
							machPhase = 2;
							machLeft = (int) (SR * (2.5f + 2f * rng.unit()));
						} else {
							machPhase = 3;
						}
					}
					float grindT = (machPhase == 1) ? 1f : 0f;
					float steamT = (machPhase == 2) ? 1f : 0f;
					grindEnv += (grindT > grindEnv ? 0.0006f : 0.0012f) * (grindT - grindEnv);
					steamEnv += (steamT > steamEnv ? 0.0008f : 0.0012f) * (steamT - steamEnv);
					if ((machPhase == 3) && (grindEnv < 0.005f) && (steamEnv < 0.005f)) machPhase = 0;
					float nz = rng.next();
					// The grinder: a rough 105 Hz motor tone and a band of noise, shuddering at 38 Hz.
					grindPh += 105f / SR;
					if (grindPh > 1f) grindPh -= 1f;
					grindAm += TWO_PI * 38f / SR;
					if (grindAm > TWO_PI) grindAm -= TWO_PI;
					grindLp += C_GRIND * ((2f * grindPh - 1f) - grindLp);
					bandHi += C_BAND_HI * (nz - bandHi);
					bandLo += C_BAND_LO * (nz - bandLo);
					float grind = (grindLp * 0.6f + (bandHi - bandLo) * 0.5f) * (0.75f + 0.25f * (float) Math.sin(grindAm)) * grindEnv * 0.14f;
					// The steam wand: a hiss of high noise, gusting.
					if (--turbCnt <= 0) {
						turbT = 0.5f + 0.5f * rng.unit();
						turbCnt = SR / 10;
					}
					turb += 0.002f * (turbT - turb);
					steamLp += C_STEAM * (nz - steamLp);
					float steam = (nz - steamLp) * steamEnv * (0.6f + 0.4f * turb) * 0.1f;
					machine = grind + steam;
				}
				float thunk = 0f;
				if (thunkEnv > 0.001f) {
					thunkPh += TWO_PI * thunkF / SR;
					if (thunkPh > TWO_PI) thunkPh -= TWO_PI;
					thunk = (float) Math.sin(thunkPh) * thunkEnv;
					thunkEnv *= 0.9994f;
				}
				float sl = machine + thunk, sr = sl;
				for (int k = 0; k < SLOTS; k++) {
					float t = 0f;
					for (int p = 0; p < PARTS; p++) {
						int idx = k * PARTS + p;
						float a = am[idx];
						if (a <= 0.00005f) continue;
						ph[idx] += TWO_PI * fr[idx] / SR;
						if (ph[idx] > TWO_PI) ph[idx] -= TWO_PI;
						t += (float) Math.sin(ph[idx]) * a;
						am[idx] = a * dc[idx];
					}
					sl += t * panL[k];
					sr += t * panR[k];
				}
				verb.process((sl + sr) * 0.5f);
				roomL = roomL * 0.998f + rng.next() * 0.02f;
				roomR = roomR * 0.998f + rng.next() * 0.02f;
				airL += C_AIR * (rng.next() - airL);
				airR += C_AIR * (rng.next() - airR);
				float outL = sl * 0.8f + verb.outL * 1.6f + roomL * room * 4f + airL * 0.03f;
				float outR = sr * 0.8f + verb.outR * 1.6f + roomR * room * 4f + airR * 0.03f;
				warmL += C_WARM * (outL - warmL);
				warmR += C_WARM * (outR - warmR);
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
