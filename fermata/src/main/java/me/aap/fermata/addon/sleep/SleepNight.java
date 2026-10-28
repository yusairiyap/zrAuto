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

	/** A copy that can be changed while this one is still being recorded. */
	public SleepNight copy() {
		SleepNight n = new SleepNight(start);
		n.end = end;
		n.move = (move == null) ? null : Arrays.copyOf(move, move.length);
		n.noise = (noise == null) ? null : Arrays.copyOf(noise, noise.length);
		n.flags = (flags == null) ? null : Arrays.copyOf(flags, flags.length);
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
