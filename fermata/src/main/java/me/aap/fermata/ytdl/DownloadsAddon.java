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
	/** The tallest picture a video download takes; the pref is an index into {@link #QUALITIES}. */
	private static final int[] QUALITIES = {360, 480, 720, 1080};
	private static final Pref<IntSupplier> QUALITY = Pref.i("DOWNLOADS_QUALITY", 2);
	private static final Pref<BooleanSupplier> YT_TOOLBAR = Pref.b("DOWNLOADS_YT_TOOLBAR", true);
	private static final Pref<BooleanSupplier> GRID = Pref.b("DOWNLOADS_GRID", false);

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

	private static PreferenceStore store() {
		return FermataApplication.get().getPreferenceStore();
	}
}
