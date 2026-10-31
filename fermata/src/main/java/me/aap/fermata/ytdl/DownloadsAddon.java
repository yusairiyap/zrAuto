package me.aap.fermata.ytdl;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.ui.fragment.DownloadsFragment;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * The Downloads tab and the settings of downloading YouTube videos for offline use (see
 * {@link YtDownloads}). Built into the {@code fermata} module, like the Fuel Log.
 */
@Keep
public class DownloadsAddon implements FermataFragmentAddon {
	private static final AddonInfo info = FermataAddon.findAddonInfo(DownloadsAddon.class.getName());
	/** The quality the Download picker offers first; the pref is an index into {@link #QUALITIES}. */
	public static final int[] QUALITIES = {360, 480, 720, 1080};
	private static final Pref<IntSupplier> QUALITY = Pref.i("DOWNLOADS_QUALITY", 2);
	private static final Pref<BooleanSupplier> YT_TOOLBAR = Pref.b("DOWNLOADS_YT_TOOLBAR", true);
	private static final Pref<BooleanSupplier> GRID = Pref.b("DOWNLOADS_GRID", true);
	public static final int SORT_DATE = 0;
	public static final int SORT_NAME = 1;
	public static final int SORT_SIZE = 2;
	private static final Pref<IntSupplier> SORT = Pref.i("DOWNLOADS_SORT", SORT_DATE);

	/** The most videos whose sizes the Download picker adds up before showing it. */
	private static final int MAX_ESTIMATE = 10;

	/**
	 * What downloading {@code videoIds} would take, as the Download picker shows it: the sound alone,
	 * then each of {@link #QUALITIES}; null (to {@code done}, on the main thread) when it can't be told
	 * or there are too many videos to ask about. Asks YouTube once per video, no media is fetched.
	 */
	public static void estimateSizes(java.util.List<String> videoIds,
																	 java.util.function.Consumer<long[]> done) {
		if (videoIds.isEmpty() || (videoIds.size() > MAX_ESTIMATE)) {
			done.accept(null);
			return;
		}
		java.util.List<String> ids = new java.util.ArrayList<>(videoIds);
		me.aap.utils.app.App.get().getExecutor().submitTask(() -> {
			long[] total = new long[QUALITIES.length + 1];
			for (String id : ids) {
				long[] s;
				try {
					s = YtStreamResolver.estimate(id, QUALITIES);
				} catch (Exception ex) {
					me.aap.fermata.util.DiagnosticLog.w(me.aap.fermata.util.LogCat.YTDL, "size estimate failed", "id=" + id, ex);
					return null;
				}
				for (int i = 0; i < total.length; i++) {
					if ((s[i] <= 0) || (total[i] < 0)) total[i] = -1;
					else total[i] += s[i];
				}
			}
			return total;
		}).main().onCompletion((r, err) -> done.accept((err == null) ? r : null));
	}

	@Override
	public int getAddonId() {
		return R.id.downloads_addon;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new DownloadsFragment();
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		set.addListPref(o -> {
			String[] labels = new String[QUALITIES.length];
			for (int i = 0; i < labels.length; i++) labels[i] = QUALITIES[i] + "p";
			o.store = store;
			o.pref = QUALITY;
			o.title = R.string.ytdl_pref_quality;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.stringValues = labels;
			o.visibility = visibility;
		});
		set.addBooleanPref(o -> {
			o.store = store;
			o.pref = YT_TOOLBAR;
			o.title = R.string.ytdl_pref_toolbar;
			o.subtitle = R.string.ytdl_pref_toolbar_sub;
			o.visibility = visibility;
		});
	}

	/** The tallest picture a video download takes, in lines. */
	public static int getMaxVideoHeight() {
		int i = store().getIntPref(QUALITY);
		return QUALITIES[Math.max(0, Math.min(i, QUALITIES.length - 1))];
	}

	/** Whether the YouTube tab's toolbar has a Downloads button: the addon is on and it's allowed. */
	public static boolean isYoutubeToolbarButtonShown() {
		return isEnabled() && store().getBooleanPref(YT_TOOLBAR);
	}

	public static boolean isEnabled() {
		return AddonManager.get().getAddon(DownloadsAddon.class) != null;
	}

	public static boolean isGrid() {
		return store().getBooleanPref(GRID);
	}

	public static void setGrid(boolean grid) {
		store().applyBooleanPref(GRID, grid);
	}

	/** One of {@link #SORT_DATE} (newest first), {@link #SORT_NAME} or {@link #SORT_SIZE} (largest first). */
	public static int getSort() {
		return store().getIntPref(SORT);
	}

	public static void setSort(int sort) {
		store().applyIntPref(SORT, sort);
	}

	private static PreferenceStore store() {
		return FermataApplication.get().getPreferenceStore();
	}
}
