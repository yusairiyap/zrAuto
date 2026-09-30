package me.aap.fermata.addon.web.yt;

import androidx.annotation.IdRes;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
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

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new YoutubeBubblesFragment();
	}
}
