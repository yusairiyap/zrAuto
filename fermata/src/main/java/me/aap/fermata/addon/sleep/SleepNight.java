package me.aap.fermata.addon.sleep;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * One recorded night: when the session started and ended, and (when the phone's sensors were used) one value per
 * {@link #EPOCH_SEC}-second epoch for how much the phone moved and how loud the room was. What was asleep, awake or
 * in which stage is not stored; {@link SleepAnalyzer} works it out from these, so a better analysis later applies to old
 * nights too.
 */
public final class SleepNight {
	/** Seconds per epoch. */
	public static final int EPOCH_SEC = 30;
	public static final long EPOCH_MS = EPOCH_SEC * 1000L;
	/** {@link #flags} bit: the user tapped "I'm awake" during this epoch. */
	public static final byte FLAG_AWAKE = 1;
	/** {@link #flags} bit: an ambience sound was playing during this epoch. */
	public static final byte FLAG_AMBIENCE = 2;
	/** {@link #flags} bit: the screen was on during this epoch. */
	public static final byte FLAG_SCREEN = 4;
	/** {@link #power} value of an epoch spent charging; 0 is not measured, 1..250 the current drawn in 10 mA steps. */
	public static final int POWER_CHARGING = 255;

	/** Start of the session (it is also the night's id). */
	public final long start;
	public long end;
	/** Movement per epoch, 0 (still) to 255 (a lot); null when the night has no sensor data. */
	@Nullable
	public byte[] move;
	/** Loudness per epoch, 0 (silent) to 255 (loud); null without the microphone. */
	@Nullable
	public byte[] noise;
	/** Per epoch flags ({@code FLAG_*}); null when none was set. */
	@Nullable
	public byte[] flags;
	/** The battery per epoch: 0 not measured, 1..250 the current drawn in 10 mA steps, {@link #POWER_CHARGING}; null when none. */
	@Nullable
	public byte[] power;
	/** The ambience sounds that were on at some time during the night, as their keys, comma separated. */
	@NonNull
	public String mix = "";
	/** The battery level (percent) when the session began and when it ended, -1 when not known. */
	public int levelStart = -1;
	public int levelEnd = -1;
	/** The average current drawn from the battery while it was not charging, in tenths of a mA (0 = none measured). */
	public int drawMa10;
	/** How many readings (one per epoch) were taken while drawing from the battery and while charging. */
	public int drawSamples;
	public int chargeSamples;
	/** Whether the room's sound stood in for movement (a tablet on a stand). */
	public boolean stand;
	/** Whether the session was cut short by the app being killed (the end is the last time it saved). */
	public boolean recovered;

	public SleepNight(long start) {
		this.start = start;
		this.end = start;
	}

	public long durationMs() {
		return Math.max(0, end - start);
	}

	public int epochs() {
		if (move != null) return move.length;
		return (int) Math.max(1, durationMs() / EPOCH_MS);
	}

	public boolean hasMovement() {
		return (move != null) && (move.length > 0);
	}

	public boolean hasNoise() {
		return (noise != null) && (noise.length > 0);
	}

	/** Whether the battery was measured while it was being drawn from. */
	public boolean hasBattery() {
		return (drawSamples >= 4) && (drawMa10 > 0);
	}

	/** The average current drawn, in mA (0 when it was not measured). */
	public float drawMa() {
		return drawMa10 / 10f;
	}

	/** Percent of the battery used per hour of the night, or -1 when the level was not known or the phone was mostly charging. */
	public float percentPerHour() {
		if ((levelStart < 0) || (levelEnd < 0) || (durationMs() < 30 * 60_000L)) return -1f;
		if (chargeSamples * 2 > drawSamples + chargeSamples) return -1f;
		return Math.max(0, levelStart - levelEnd) / (durationMs() / 3_600_000f);
	}

	public boolean usedAmbience() {
		return !mix.isEmpty();
	}

	public boolean hasSound(String key) {
		for (String k : mix.split(",")) if (k.equals(key)) return true;
		return false;
	}

	/** Sessions that start less than this after the previous one ended are one night (a stop and start in the middle of it). */
	public static final long MERGE_GAP_MS = 2 * 3600_000L;

	/** Whether {@code next} began soon enough after {@code prev} ended to be the same night. */
	public static boolean sameNight(SleepNight prev, SleepNight next) {
		long gap = next.start - prev.end;
		return (gap < MERGE_GAP_MS) && (next.start > prev.start);
	}

	/** One night made of two sessions; the time between them counts as awake. */
	public static SleepNight merge(SleepNight a, SleepNight b) {
		SleepNight n = new SleepNight(a.start);
		n.end = Math.max(a.end, b.end);
		int na = a.epochs();
		int nb = b.epochs();
		int gap = (int) Math.max(0, (b.start - a.end) / EPOCH_MS);
		if (a.hasMovement() || b.hasMovement()) n.move = join(a.move, na, b.move, nb, gap, (byte) 0);
		if (a.hasNoise() || b.hasNoise()) n.noise = join(a.noise, na, b.noise, nb, gap, (byte) 0);
		if ((a.flags != null) || (b.flags != null) || (gap > 0)) n.flags = join(a.flags, na, b.flags, nb, gap, FLAG_AWAKE);
		if ((a.power != null) || (b.power != null)) n.power = join(a.power, na, b.power, nb, gap, (byte) 0);
		StringBuilder mx = new StringBuilder(a.mix);
		for (String k : b.mix.split(",")) {
			if (!k.isEmpty() && !a.hasSound(k)) mx.append((mx.length() == 0) ? "" : ",").append(k);
		}
		n.mix = mx.toString();
		n.levelStart = (a.levelStart >= 0) ? a.levelStart : b.levelStart;
		n.levelEnd = (b.levelEnd >= 0) ? b.levelEnd : a.levelEnd;
		int samples = a.drawSamples + b.drawSamples;
		if (samples > 0) {
			n.drawMa10 = (int) (((long) a.drawMa10 * a.drawSamples + (long) b.drawMa10 * b.drawSamples) / samples);
		}
		n.drawSamples = samples;
		n.chargeSamples = a.chargeSamples + b.chargeSamples;
		n.stand = a.stand || b.stand;
		n.recovered = b.recovered;
		return n;
	}

	private static byte[] join(@Nullable byte[] x, int nx, @Nullable byte[] y, int ny, int gap, byte gapValue) {
		byte[] r = new byte[nx + gap + ny];
		if (x != null) System.arraycopy(x, 0, r, 0, Math.min(nx, x.length));
		if (gapValue != 0) Arrays.fill(r, nx, nx + gap, gapValue);
		if (y != null) System.arraycopy(y, 0, r, nx + gap, Math.min(ny, y.length));
		return r;
	}

	/**
	 * Cuts off a long stretch of "awake" at the very end (a session joined to a misclick later in the day, or a screen left on): what
	 * is kept is the last epoch that was not marked awake plus {@code graceEpochs}. Returns whether anything was cut.
	 */
	public boolean trimTrailingAwake(int graceEpochs) {
		if ((flags == null) || (flags.length == 0)) return false;
		int n = (move != null) ? Math.min(move.length, flags.length) : flags.length;
		int last = -1;
		for (int i = n - 1; i >= 0; i--) {
			if ((flags[i] & FLAG_AWAKE) == 0) {
				last = i;
				break;
			}
		}
		if (last < 0) return false;
		int keep = Math.min(n, last + 1 + graceEpochs);
		if (n - keep <= graceEpochs) return false;
		if (move != null) move = Arrays.copyOf(move, keep);
		if (noise != null) noise = Arrays.copyOf(noise, Math.min(keep, noise.length));
		flags = Arrays.copyOf(flags, keep);
		if (power != null) power = Arrays.copyOf(power, Math.min(keep, power.length));
		end = Math.min(end, start + keep * EPOCH_MS);
		return true;
	}

	/** Whether the night has the battery per epoch for a breakdown. */
	public boolean hasPowerSeries() {
		return (power != null) && (power.length > 0) && hasBattery();
	}

	/** A copy that can be changed while this one is still being recorded. */
	public SleepNight copy() {
		SleepNight n = new SleepNight(start);
		n.end = end;
		n.move = (move == null) ? null : Arrays.copyOf(move, move.length);
		n.noise = (noise == null) ? null : Arrays.copyOf(noise, noise.length);
		n.flags = (flags == null) ? null : Arrays.copyOf(flags, flags.length);
		n.power = (power == null) ? null : Arrays.copyOf(power, power.length);
		n.mix = mix;
		n.recovered = recovered;
		n.stand = stand;
		n.levelStart = levelStart;
		n.levelEnd = levelEnd;
		n.drawMa10 = drawMa10;
		n.drawSamples = drawSamples;
		n.chargeSamples = chargeSamples;
		return n;
	}

	JSONObject toJson() throws JSONException {
		JSONObject o = new JSONObject();
		o.put("s", start);
		o.put("e", end);
		if (hasMovement()) o.put("m", Base64.encodeToString(move, Base64.NO_WRAP));
		if (hasNoise()) o.put("n", Base64.encodeToString(noise, Base64.NO_WRAP));
		if (flags != null) o.put("f", Base64.encodeToString(flags, Base64.NO_WRAP));
		if (power != null) o.put("p", Base64.encodeToString(power, Base64.NO_WRAP));
		if (!mix.isEmpty()) o.put("x", mix);
		if (recovered) o.put("r", true);
		if (stand) o.put("sd", true);
		if (levelStart >= 0) o.put("l0", levelStart);
		if (levelEnd >= 0) o.put("l1", levelEnd);
		if (drawSamples > 0) {
			o.put("bd", drawMa10);
			o.put("bn", drawSamples);
		}
		if (chargeSamples > 0) o.put("bc", chargeSamples);
		return o;
	}

	static SleepNight fromJson(JSONObject o) throws JSONException {
		SleepNight n = new SleepNight(o.getLong("s"));
		n.end = o.optLong("e", n.start);
		if (o.has("m")) n.move = Base64.decode(o.getString("m"), Base64.NO_WRAP);
		if (o.has("n")) n.noise = Base64.decode(o.getString("n"), Base64.NO_WRAP);
		if (o.has("f")) n.flags = Base64.decode(o.getString("f"), Base64.NO_WRAP);
		if (o.has("p")) n.power = Base64.decode(o.getString("p"), Base64.NO_WRAP);
		n.mix = o.optString("x", "");
		n.recovered = o.optBoolean("r", false);
		n.stand = o.optBoolean("sd", false);
		n.levelStart = o.optInt("l0", -1);
		n.levelEnd = o.optInt("l1", -1);
		n.drawMa10 = o.optInt("bd", 0);
		n.drawSamples = o.optInt("bn", 0);
		n.chargeSamples = o.optInt("bc", 0);
		return n;
	}
}
