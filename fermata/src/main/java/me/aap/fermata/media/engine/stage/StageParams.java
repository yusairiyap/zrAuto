package me.aap.fermata.media.engine.stage;

/**
 * An immutable snapshot of the "sound stage" settings: stereo width, differential surround and the
 * 3D speaker position. Plain Java, so the DSP ({@link StageDsp}) and the YouTube script config can
 * read it without touching preferences.
 * <p>
 * The position is the top-down spot the sound comes from, as the 3D pad shows it: {@code x} to the
 * right, {@code y} to the front, both -100..100 with the listener at (0, 0).
 */
public final class StageParams {
	public static final int WIDTH_MIN = 0;
	public static final int WIDTH_MAX = 200;
	public static final int WIDTH_DEFAULT = 100;
	public static final int DIFF_DELAY_MIN = 1;
	public static final int DIFF_DELAY_MAX = 20;
	public static final int DIFF_DELAY_DEFAULT = 8;
	public static final int DIFF_STRENGTH_DEFAULT = 50;
	public static final int SPREAD_MAX = 60;
	public static final int SPREAD_DEFAULT = 30;
	/** Tenths of a revolution per minute. */
	public static final int ORBIT_MAX = 150;
	public static final int ORBIT_DEFAULT = 40;

	public static final StageParams OFF = new StageParams(false, WIDTH_DEFAULT, false,
			DIFF_STRENGTH_DEFAULT, DIFF_DELAY_DEFAULT, false, 0, 100, SPREAD_DEFAULT, 0);

	/** Stereo width on (0 = mono, 100 = as recorded, 200 = twice as wide). */
	public final boolean widthOn;
	public final int width;
	/** Differential surround: the L-R difference, delayed and mixed back in, like ViPER's. */
	public final boolean diffOn;
	public final int diffStrength;
	public final int diffDelayMs;
	/** 3D speaker position. */
	public final boolean posOn;
	public final int posX;
	public final int posY;
	/** Half the angle between the two virtual speakers, in degrees. */
	public final int spread;
	/** Automatic circling around the listener (8D), tenths of a revolution per minute; 0 = off. */
	public final int orbit;

	public StageParams(boolean widthOn, int width, boolean diffOn, int diffStrength, int diffDelayMs,
										 boolean posOn, int posX, int posY, int spread, int orbit) {
		this.widthOn = widthOn;
		this.width = clamp(width, WIDTH_MIN, WIDTH_MAX);
		this.diffOn = diffOn;
		this.diffStrength = clamp(diffStrength, 0, 100);
		this.diffDelayMs = clamp(diffDelayMs, DIFF_DELAY_MIN, DIFF_DELAY_MAX);
		this.posOn = posOn;
		this.posX = clamp(posX, -100, 100);
		this.posY = clamp(posY, -100, 100);
		this.spread = clamp(spread, 0, SPREAD_MAX);
		this.orbit = clamp(orbit, 0, ORBIT_MAX);
	}

	/** Whether anything here changes the sound. */
	public boolean isActive() {
		return (widthOn && (width != WIDTH_DEFAULT)) || (diffOn && (diffStrength > 0)) || posOn;
	}

	public StageParams withWidth(boolean on, int width) {
		return new StageParams(on, width, diffOn, diffStrength, diffDelayMs, posOn, posX, posY, spread,
				orbit);
	}

	public StageParams withDiff(boolean on, int strength, int delayMs) {
		return new StageParams(widthOn, width, on, strength, delayMs, posOn, posX, posY, spread, orbit);
	}

	public StageParams withPosition(boolean on, int x, int y, int spread, int orbit) {
		return new StageParams(widthOn, width, diffOn, diffStrength, diffDelayMs, on, x, y, spread,
				orbit);
	}

	/** Direction of the position, in degrees: 0 = straight ahead, 90 = right, 180 = behind. */
	public double azimuthDegrees() {
		return Math.toDegrees(Math.atan2(posX, posY));
	}

	/** 0 at the listener, 1 at the edge of the pad. */
	public double distance() {
		return Math.min(1d, Math.hypot(posX, posY) / 100d);
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof StageParams p)) return false;
		return (widthOn == p.widthOn) && (width == p.width) && (diffOn == p.diffOn)
				&& (diffStrength == p.diffStrength) && (diffDelayMs == p.diffDelayMs)
				&& (posOn == p.posOn) && (posX == p.posX) && (posY == p.posY) && (spread == p.spread)
				&& (orbit == p.orbit);
	}

	@Override
	public int hashCode() {
		int h = widthOn ? 1 : 0;
		h = h * 31 + width;
		h = h * 31 + (diffOn ? 1 : 0);
		h = h * 31 + diffStrength;
		h = h * 31 + diffDelayMs;
		h = h * 31 + (posOn ? 1 : 0);
		h = h * 31 + posX;
		h = h * 31 + posY;
		h = h * 31 + spread;
		return h * 31 + orbit;
	}
}
