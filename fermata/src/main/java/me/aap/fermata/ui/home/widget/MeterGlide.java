package me.aap.fermata.ui.home.widget;

/**
 * The value a meter shows, easing from where it was to a new fraction over about a second. A countdown
 * updates once a second, so the meter glides across the whole second in between and never sits still and then jumps.
 */
final class MeterGlide {
	/** Long enough to cover the one-second update of a countdown. */
	static final long MS = 1100;
	private static final long NONE = Long.MIN_VALUE;
	private float from;
	private float to;
	private long startedAt = NONE;
	private boolean set;

	/** Whether a value was ever set (until {@link #reset()}). */
	boolean isSet() {
		return set;
	}

	/** The fraction the meter is heading to. */
	float target() {
		return to;
	}

	/** Shows {@code f} at once, with no glide. */
	void jump(float f) {
		from = to = f;
		startedAt = NONE;
		set = true;
	}

	/** Glides from the value shown at {@code now} to {@code f}. */
	void glideTo(float f, long now) {
		if (!set) {
			jump(f);
			return;
		}
		from = value(now);
		to = f;
		startedAt = now;
	}

	/** The value shown at {@code now}. */
	float value(long now) {
		if (startedAt == NONE) return to;
		float k = Math.min(1f, (now - startedAt) / (float) MS);
		k = k * k * (3f - 2f * k);
		return from + (to - from) * k;
	}

	/** Whether the value is still on its way at {@code now}. */
	boolean moving(long now) {
		return (startedAt != NONE) && (now - startedAt < MS);
	}

	/** Forgets the value: the next one is shown at once. */
	void reset() {
		set = false;
		startedAt = NONE;
	}
}
