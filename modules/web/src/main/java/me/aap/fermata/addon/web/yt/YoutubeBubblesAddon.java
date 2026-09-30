package me.aap.fermata.addon.web.yt;

import android.content.Context;

import androidx.annotation.IdRes;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.addon.web.R;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * The YouTube suggestions tab: the videos on the user's YouTube feed, floating around the screen
 * as big thumbnail bubbles to tap. How many there are, how fast they drift and what tapping one
 * does are set in the YouTube addon's settings, see {@link YoutubeAddon}.
 */
@Keep
public class YoutubeBubblesAddon implements FermataFragmentAddon {
	@NonNull
	private static final AddonInfo info = FermataAddon.findAddonInfo(YoutubeBubblesAddon.class.getName());

	@IdRes
	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.youtube_bubbles_fragment;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	/** Their own screen: the YouTube addon's store holds the values, see {@link YoutubeAddon}. */
	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		YoutubeAddon yt = AddonManager.get().getAddon(YoutubeAddon.class);
		if (yt == null) return;
		PreferenceStore ys = yt.getPreferenceStore();
		set.addIntPref(o -> {
			o.store = ys;
			o.pref = YoutubeAddon.BUBBLES_COUNT;
			o.title = R.string.yt_bubbles_count;
			o.subtitle = R.string.yt_bubbles_count_sub;
			o.seekMin = 3;
			o.seekMax = 40;
			o.ems = 3;
			o.visibility = visibility.copy();
		});
		set.addListPref(o -> {
			o.store = ys;
			o.pref = YoutubeAddon.BUBBLES_TAP;
			o.title = R.string.yt_bubbles_tap;
			o.subtitle = me.aap.fermata.R.string.string_format;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.yt_bubbles_tap_auto, R.string.yt_bubbles_tap_video,
					R.string.yt_bubbles_tap_music};
			o.visibility = visibility.copy();
		});
		set.addIntPref(o -> {
			o.store = ys;
			o.pref = YoutubeAddon.BUBBLES_SPEED;
			o.title = R.string.yt_bubbles_speed;
			o.seekMin = 25;
			o.seekMax = 300;
			o.seekScale = 5;
			o.ems = 3;
			o.visibility = visibility.copy();
		});
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new YoutubeBubblesFragment();
	}
}
