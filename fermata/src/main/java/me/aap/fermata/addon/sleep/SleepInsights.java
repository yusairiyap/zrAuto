package me.aap.fermata.addon.sleep;

import android.content.Context;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import me.aap.fermata.R;
import me.aap.fermata.addon.ambience.AmbienceEffect;
import me.aap.fermata.addon.ambience.AmbienceEngine;

/**
 * What the recorded nights say, in words: the week against the goal, the sleep debt, how regular the bedtime is, and tips that
 * come from the patterns (a long time to fall asleep, many awakenings, a noisy room, a late bedtime). The Ambience sounds are
 * tied in two ways: a night with sounds is compared with one without (only when there are enough of both to mean something),
 * and a slow start to sleep points to the Ambience tab.
 */
public final class SleepInsights {
	/** Nights of each kind needed before sounds are compared. */
	private static final int MIN_PER_GROUP = 3;
	private static final int MIN_NIGHTS = 7;

	private SleepInsights() {
	}

	/** Every night analysed against the ones before it, oldest first. */
	@NonNull
	public static List<SleepAnalyzer.Result> analyzeAll(List<SleepNight> nights, SleepAnalyzer.Settings st) {
		List<SleepAnalyzer.Result> out = new ArrayList<>();
		for (int i = 0; i < nights.size(); i++) {
			out.add(SleepAnalyzer.analyze(nights.get(i), nights.subList(0, i), st));
		}
		return out;
	}

	public static String duration(long ms) {
		long m = Math.max(0, ms / 60_000L);
		return String.format(Locale.getDefault(), "%dh %02dm", m / 60, m % 60);
	}

	public static String clock(Context ctx, long time) {
		return DateFormat.getTimeFormat(ctx).format(new Date(time));
	}

	public static String minuteOfDay(Context ctx, int minute) {
		java.util.Calendar c = java.util.Calendar.getInstance();
		c.set(java.util.Calendar.HOUR_OF_DAY, minute / 60);
		c.set(java.util.Calendar.MINUTE, minute % 60);
		return DateFormat.getTimeFormat(ctx).format(c.getTime());
	}

	/** The insights and tips for the nights in {@code results} (oldest first), best first. */
	@NonNull
	public static List<String> build(Context ctx, List<SleepAnalyzer.Result> results, SleepAnalyzer.Settings st) {
		List<String> out = new ArrayList<>();
		int total = results.size();
		if (total == 0) return out;
		List<SleepAnalyzer.Result> week = results.subList(Math.max(0, total - 7), total);

		long asleep = 0;
		long debt = 0;
		int awakenings = 0;
		long latency = 0;
		int noise = 0;
		int noisy = 0;
		for (SleepAnalyzer.Result r : week) {
			asleep += r.asleepMs;
			debt += Math.max(0, st.goalMin * 60_000L - r.asleepMs);
			awakenings += r.awakenings;
			latency += r.latencyMs;
			if (r.hasNoise) {
				noise += r.noiseAvg;
				noisy++;
			}
		}
		int n = week.size();
		out.add(ctx.getString(R.string.sleep_insight_week, duration(asleep / n), n, duration(st.goalMin * 60_000L)));
		if (debt >= 60 * 60_000L) out.add(ctx.getString(R.string.sleep_insight_debt, duration(debt)));

		SleepAnalyzer.Result best = week.get(0);
		SleepAnalyzer.Result worst = week.get(0);
		for (SleepAnalyzer.Result r : week) {
			if (r.score > best.score) best = r;
			if (r.score < worst.score) worst = r;
		}
		if (n >= 3) {
			out.add(ctx.getString(R.string.sleep_insight_best, best.score,
					DateFormat.getMediumDateFormat(ctx).format(new Date(best.night.start)), worst.score));
		}

		float dev = 0;
		for (SleepAnalyzer.Result r : week) dev += SleepAnalyzer.consistencyDeviationMin(r, null, st);
		dev /= n;

		// Sounds against none, and the sound that goes with the best nights.
		if (total >= MIN_NIGHTS) {
			int withN = 0, withoutN = 0;
			float withSum = 0, withoutSum = 0;
			for (SleepAnalyzer.Result r : results) {
				if (r.night.usedAmbience()) {
					withN++;
					withSum += r.score;
				} else {
					withoutN++;
					withoutSum += r.score;
				}
			}
			if ((withN >= MIN_PER_GROUP) && (withoutN >= MIN_PER_GROUP)) {
				float diff = withSum / withN - withoutSum / withoutN;
				if (Math.abs(diff) >= 3f) {
					out.add(ctx.getString((diff > 0) ? R.string.sleep_insight_amb_better : R.string.sleep_insight_amb_worse,
							Math.round(Math.abs(diff)), withN));
				} else {
					out.add(ctx.getString(R.string.sleep_insight_amb_same, withN, withoutN));
				}
			}
			float overall = 0;
			for (SleepAnalyzer.Result r : results) overall += r.score;
			overall /= total;
			Map<String, float[]> per = new TreeMap<>();
			for (SleepAnalyzer.Result r : results) {
				if (!r.night.usedAmbience()) continue;
				for (String k : r.night.mix.split(",")) {
					float[] a = per.computeIfAbsent(k, x -> new float[2]);
					a[0]++;
					a[1] += r.score;
				}
			}
			String bestKey = null;
			float bestGain = 3f;
			for (Map.Entry<String, float[]> e : per.entrySet()) {
				if (e.getValue()[0] < MIN_PER_GROUP) continue;
				float gain = e.getValue()[1] / e.getValue()[0] - overall;
				if (gain >= bestGain) {
					bestGain = gain;
					bestKey = e.getKey();
				}
			}
			AmbienceEffect fx = (bestKey == null) ? null : AmbienceEffect.byKey(bestKey);
			if (fx != null) out.add(ctx.getString(R.string.sleep_insight_amb_best, ctx.getString(fx.name), Math.round(bestGain)));
		}

		// What recording costs the battery, from the nights it was measured on.
		float pphSum = 0;
		float maSum = 0;
		int pphN = 0;
		for (SleepAnalyzer.Result r : results) {
			float pph = r.night.percentPerHour();
			if (r.night.hasBattery() && (pph >= 0f)) {
				pphSum += pph;
				maSum += r.night.drawMa();
				pphN++;
			}
		}
		if (pphN >= 2) {
			out.add(ctx.getString(R.string.sleep_insight_battery, String.format(Locale.getDefault(), "%.1f", pphSum / pphN),
					Math.round(maSum / pphN), pphN));
		}

		// Tips from the pattern.
		int tips = 0;
		boolean usedAmbience = false;
		for (SleepAnalyzer.Result r : week) usedAmbience |= r.night.usedAmbience();
		long avgLatencyMin = latency / n / 60_000L;
		if ((avgLatencyMin >= 30) && (tips < 3)) {
			out.add(ctx.getString(usedAmbience ? R.string.sleep_tip_latency_amb : R.string.sleep_tip_latency_try_amb,
					avgLatencyMin));
			tips++;
		}
		if (((float) awakenings / n >= 3f) && (tips < 3)) {
			out.add(ctx.getString(R.string.sleep_tip_awakenings));
			tips++;
		}
		if ((noisy > 0) && (noise / noisy >= 110) && (tips < 3)) {
			out.add(ctx.getString(R.string.sleep_tip_noise));
			tips++;
		}
		if ((dev >= 45f) && (tips < 3)) {
			out.add(ctx.getString(R.string.sleep_tip_regular, Math.round(dev)));
			tips++;
		}
		if ((asleep / n < st.goalMin * 60_000L * 0.85) && (tips < 3)) {
			out.add(ctx.getString(R.string.sleep_tip_earlier, minuteOfDay(ctx, Math.floorMod(st.bedMin - 30, 1440))));
			tips++;
		}
		if ((pphN >= 2) && (pphSum / pphN >= 1.5f) && (tips < 3)) {
			out.add(ctx.getString(R.string.sleep_tip_battery));
			tips++;
		}
		if (tips == 0 && n >= 3) out.add(ctx.getString(R.string.sleep_tip_none));
		return out;
	}
}
