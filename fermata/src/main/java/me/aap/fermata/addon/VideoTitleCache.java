package me.aap.fermata.addon;

import java.util.Map;

/**
 * Implemented by the YouTube addon (which lives in the on-demand {@code web} module, so the
 * {@code fermata} module can't reference it directly): lets the Spotify import pre-seed the
 * titles its {@code youtube:<videoId>} playlist entries display, without first playing each video.
 */
public interface VideoTitleCache {
	String YOUTUBE_ADDON_CLASS = "me.aap.fermata.addon.web.yt.YoutubeAddon";

	/** videoId to title; written in one go. */
	void cacheVideoTitles(Map<String, String> titles);
}
