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
		abstract void render(float[] l, float[] r, int n, float lv);
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
		private static final float C_HI = coef(7000), C_LO = coef(700), C_ROAR = coef(250),
				C_TICK = coef(2500);
		private final Rng rng = new Rng(0x1234567);
		private float aL, bL, cL, tL, dropL, aR, bR, cR, tR, dropR;

		@Override
		void render(float[] l, float[] r, int n, float lv) {
			float hiss = 0.12f + 0.19f * lv;
			float rate = (40f + 900f * lv * lv) / SR;
			for (int i = 0; i < n; i++) {
				float nl = rng.next();
				float nr = rng.next();
				aL += C_HI * (nl - aL);
				bL += C_LO * (nl - bL);
				cL += C_ROAR * (nl - cL);
				tL += C_TICK * (nl - tL);
				aR += C_HI * (nr - aR);
				bR += C_LO * (nr - bR);
				cR += C_ROAR * (nr - cR);
				tR += C_TICK * (nr - tR);
				if (rng.unit() < rate) dropL = 0.4f + 0.6f * rng.unit();
				if (rng.unit() < rate) dropR = 0.4f + 0.6f * rng.unit();
				l[i] = (aL - bL) * hiss + cL * 1.0f + dropL * (nl - tL) * 0.35f;
				r[i] = (aR - bR) * hiss + cR * 1.0f + dropR * (nr - tR) * 0.35f;
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

		private void strike(float lv) {
			float dist = rng.unit();
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
		void render(float[] l, float[] r, int n, float lv) {
			if (wait < 0) wait = nextWait(lv, true);
			for (int i = 0; i < n; i++) {
				if (state == 0) {
					if (--wait <= 0) strike(lv);
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
		void render(float[] l, float[] r, int n, float lv) {
			float rate = (3f + 26f * lv) / SR;
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
		void render(float[] l, float[] r, int n, float lv) {
			float strength = 0.35f + 0.65f * lv;
			for (int i = 0; i < n; i++) {
				if (--gustCnt <= 0) {
					gustT = rng.unit();
					gustCnt = (int) (SR * (1.5f + 4f * rng.unit()));
				}
				gust += 0.00004f * (gustT - gust);
				if ((i & 63) == 0) {
					float fc = 220f + 650f * gust + 250f * lv;
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

		Ocean() {
			wl.ph = 0.3f;
			wr.ph = 0.7f;
		}

		private float envelope(Wave w, float lv) {
			w.ph += 1f / (w.per * SR);
			if (w.ph >= 1f) {
				w.ph = 0f;
				w.per = 6.5f + 5f * rng.unit() - 2f * lv;
				w.peak = 0.55f + 0.45f * rng.unit();
			}
			float x = w.ph;
			float s = (x < 0.4f) ? (x / 0.4f) : (1f - (x - 0.4f) / 0.6f);
			s = s * s * (3f - 2f * s);
			return 0.12f + s * w.peak;
		}

		@Override
		void render(float[] l, float[] r, int n, float lv) {
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
		void render(float[] l, float[] r, int n, float lv) {
			float gain = 1.0f * (0.4f + 0.6f * lv);
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
				if ((i & 63) == 0) cHi = coef(2200f + 1800f * ampL);
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
		void render(float[] l, float[] r, int n, float lv) {
			float rate = (0.25f + 1.6f * lv) / SR;
			float loud = 0.5f + 0.5f * lv;
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
				float yl = xl + lpL * 0.5f;
				bufL[pL] = yl;
				if (++pL >= bufL.length) pL = 0;
				float dr = bufR[pR];
				lpR += 0.3f * (dr - lpR);
				float yr = xr + lpR * 0.5f;
				bufR[pR] = yr;
				if (++pR >= bufR.length) pR = 0;
				l[i] = yl;
				r[i] = yr;
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
		void render(float[] l, float[] r, int n, float lv) {
			int count = 1 + (int) (lv * 3.99f);
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
					c.ph += TWO_PI * c.freq / SR;
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

		Birds() {
			for (int k = 0; k < BIRDS; k++) {
				birds[k] = new Bird();
				birds[k].count = (int) (SR * (1f + 3f * rng.unit() + k));
			}
		}

		private int nextWait(float lv) {
			return (int) (SR * (7f - 5.5f * lv) * (0.5f + rng.unit()));
		}

		private void startNote(Bird b) {
			b.noteLen = (int) (SR * (0.05f + 0.11f * rng.unit()));
			b.count = b.noteLen;
			b.f0 = 2400f + 2600f * rng.unit();
			b.f1 = b.f0 * (0.65f + 0.9f * rng.unit());
			b.state = 1;
		}

		@Override
		void render(float[] l, float[] r, int n, float lv) {
			float loud = 0.4f + 0.6f * lv;
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
