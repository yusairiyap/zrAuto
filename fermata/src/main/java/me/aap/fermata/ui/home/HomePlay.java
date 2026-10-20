package me.aap.fermata.ui.home;

import java.util.Objects;

import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * Plays what a Home card shows the way tapping it in its own tab does: as music (the list becomes the
 * Music queue, from that song) when the Music tab is what plays or the setting asks for it, else in
 * the player it belongs to. The screen stays on Home.
 */
public final class HomePlay {
	private HomePlay() {
	}

	/** A song or video tapped in a list of {@code Favorites}, a playlist or the like. */
	public static void tapped(MainActivityDelegate a, PlayableItem pi) {
		boolean asMusic = !(pi instanceof MediaLib.StreamItem) && MusicPlayer.tapPlaysAsMusic(a);
		// From now on the mode in use carries on; the default only decides the first start.
		MusicPlayer.markStarted();
		if (asMusic) {
			MusicPlayer.play(a, pi, false);
			return;
		}
		if (pi instanceof MediaLib.ExternallyPlayableItem ext) {
			a.playExternally(ext, pi);
			return;
		}
		if (Objects.equals(a.getCurrentPlayable(), pi)) return;
		a.getMediaServiceBinder().playItem(pi);
	}

	/** A whole playlist or folder: as music all its tracks become the queue, else its first item plays. */
	public static void list(MainActivityDelegate a, MediaLib.BrowsableItem list) {
		if (MusicPlayer.tapPlaysAsMusic(a)) {
			MusicPlayer.markStarted();
			MusicPlayer.play(a, list, false);
			return;
		}
		list.getFirstPlayable().main().onSuccess(first -> {
			if (first != null) tapped(a, first);
		});
	}
}
