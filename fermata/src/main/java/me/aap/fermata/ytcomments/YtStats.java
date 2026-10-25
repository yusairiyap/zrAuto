package me.aap.fermata.ytcomments;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.utils.app.App;

/**
 * The views and likes of a YouTube video, read once per video (one InnerTube "next" request, the
 * same one the comments load with) and kept for the session, so the fullscreen controls can show
 * them beside the channel name without asking again.
 */
public final class YtStats {
	private static final int MAX_CACHED = 64;
	private static final Map<String, long[]> stats = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
			return size() > MAX_CACHED;
		}
	};
	private static final Set<String> loading = new HashSet<>();

	private YtStats() {
	}

	/** {views, likes} of {@code videoId}, each -1 where YouTube doesn't say; null while it is not read yet. */
	@Nullable
	public static synchronized long[] cached(String videoId) {
		return stats.get(videoId);
	}

	/** Reads {@code videoId}'s views and likes in the background, then calls {@code onRead} on the UI thread. */
	public static synchronized void load(String videoId, Runnable onRead) {
		if (stats.containsKey(videoId) || !loading.add(videoId)) return;
		App.get().getExecutor().submitTask(() -> YtComments.stats(videoId)).main()
				.onCompletion((r, err) -> {
					synchronized (YtStats.class) {
						loading.remove(videoId);
						// A failed read is kept as unknown: it is not asked again every time the controls sync.
						stats.put(videoId, (err == null) && (r != null) ? r : new long[]{-1, -1});
					}
					onRead.run();
				});
	}

	/**
	 * Appends "👁 1.2M    👍 34K" in the comment box's style (its icons, spacing and the text's own
	 * size and colour), after whatever {@code sb} already holds. What YouTube doesn't say is left out.
	 */
	public static void appendStats(SpannableStringBuilder sb, Context ctx, float textPx, int color,
																 long views, long likes) {
		if (views >= 0) appendStat(sb, ctx, R.drawable.visibility, textPx, color, YtComments.shortCount(views));
		if (likes >= 0) appendStat(sb, ctx, R.drawable.thumb_up, textPx, color, YtComments.shortCount(likes));
	}

	private static void appendStat(SpannableStringBuilder sb, Context ctx, int icon, float textPx,
																 int color, String value) {
		Drawable d = ContextCompat.getDrawable(ctx, icon);
		if (sb.length() > 0) sb.append("    ");
		if (d != null) {
			d = d.mutate();
			int size = Math.round(textPx * 1.1f);
			d.setBounds(0, 0, size, size);
			d.setTint(color);
			int at = sb.length();
			sb.append(" ");
			sb.setSpan(new ImageSpan(d, DynamicDrawableSpan.ALIGN_BOTTOM), at, at + 1,
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			sb.append(' ');
		}
		sb.append(value);
	}
}
