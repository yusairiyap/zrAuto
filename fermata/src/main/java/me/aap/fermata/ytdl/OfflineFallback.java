package me.aap.fermata.ytdl;

import androidx.annotation.Nullable;

import java.util.List;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Favorites;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.TopToast;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Switches playback to something stored on the phone, to save data or when the connection gives
 * out: the next downloaded song of the playlist or Favorites playing now (so the list carries on
 * from there), else the Music queue's next offline track, else everything downloaded.
 */
public final class OfflineFallback {
	/** Settings > Playback: on a stalled connection, go on with what's downloaded by itself. */
	public static final Pref<BooleanSupplier> ON_STALL = Pref.b("OFFLINE_ON_STALL", false);

	private OfflineFallback() {
	}

	public static PreferenceStore prefs() {
		return FermataApplication.get().getPreferenceStore();
	}

	/**
	 * Streaming stalled: with {@link #ON_STALL} on and something stored to play, plays that (the
	 * next downloaded song of the playlist / Favorites, else the queue's or all downloads) and says
	 * the connection is unstable, instead of the popup asking. False when it didn't.
	 */
	public static boolean onStall(MainActivityDelegate a) {
		if (!prefs().getBooleanPref(ON_STALL) || !hasOffline(a)) return false;
		DiagnosticLog.log("NETWORK", "stalled: playing downloaded by itself");
		TopToast.show(R.drawable.network_weak, R.string.offline_on_stall_toast);
		playDownloaded(a, null);
		return true;
	}

	/** Whether {@code i} plays without the internet: a downloaded YouTube video or a local file. */
	public static boolean isOffline(@Nullable PlayableItem i) {
		if (i == null) return false;
		if (YtDownloads.videoIdOf(i) != null) return YtOffline.isDownloadedYoutube(i);
		if (i.isStream()) return false;
		try {
			String scheme = i.getResource().getRid().getScheme();
			return !"http".equals(scheme) && !"https".equals(scheme);
		} catch (Throwable ex) {
			return false;
		}
	}

	/** Whether there is anything at all to fall back to. */
	public static boolean hasOffline(MainActivityDelegate a) {
		return MusicPlayer.hasOfflineTrack(a);
	}

	/**
	 * Plays something stored on the phone instead of what streams now (see the class comment).
	 *
	 * @param none run on the main thread when there's nothing stored to play
	 */
	public static void playDownloaded(MainActivityDelegate a, @Nullable Runnable none) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		// A Music tab track: its queue is the list.
		if (MusicPlayer.getCurrentTrack(cb) == null) {
			MediaEngine eng = cb.getEngine();
			PlayableItem cur = (eng != null) ? eng.getFavoritableItem() : cb.getCurrentItem();
			if (cur instanceof MusicTrackItem t) cur = t.getFavoritableItem();
			BrowsableItem p = (cur == null) ? null : cur.getParent();
			if ((p instanceof Playlist) || (p instanceof Favorites)) {
				PlayableItem from = cur;
				p.getPlayableChildren(false).main().onCompletion((list, err) -> {
					PlayableItem next = (list == null) ? null : nextOffline(list, from);
					if ((next != null) && MusicPlayer.isEnabled()) {
						// Only the list's offline songs, from that one on, become the Music queue: Next
						// never goes back to streaming.
						List<PlayableItem> offline = offlineFrom(list, next);
						DiagnosticLog.log("YTDL", "offline fallback: the list's downloaded songs", "first=" + next,
								"count=" + offline.size());
						MusicPlayer.play(a, offline, 0);
					} else if (next != null) {
						DiagnosticLog.log("YTDL", "offline fallback: next downloaded in the list", "item=" + next);
						if (!YtOffline.tryPlayLocal(a, next, 0)) a.getMediaServiceBinder().playItem(next);
					} else {
						playQueueOrAll(a, none);
					}
				});
				return;
			}
		}
		playQueueOrAll(a, none);
	}

	private static void playQueueOrAll(MainActivityDelegate a, @Nullable Runnable none) {
		if (!MusicPlayer.playOfflineTrack(a) && (none != null)) none.run();
	}

	/** The offline items of {@code list}, starting with {@code first} and wrapping around. */
	private static List<PlayableItem> offlineFrom(List<PlayableItem> list, PlayableItem first) {
		int n = list.size();
		int start = Math.max(0, list.indexOf(first));
		List<PlayableItem> out = new java.util.ArrayList<>();
		for (int i = 0; i < n; i++) {
			PlayableItem c = list.get((start + i) % n);
			if (isOffline(c)) out.add(c);
		}
		return out;
	}

	/** The first item after {@code from} in {@code list} (wrapping around) that plays offline. */
	@Nullable
	private static PlayableItem nextOffline(List<PlayableItem> list, PlayableItem from) {
		int n = list.size();
		if (n == 0) return null;
		int start = -1;
		String id = from.getOrigId();
		for (int i = 0; i < n; i++) {
			if (list.get(i).getOrigId().equals(id)) {
				start = i;
				break;
			}
		}
		for (int i = 1; i <= n; i++) {
			PlayableItem c = list.get(Math.floorMod(start + i, n));
			if ((c != from) && !c.getOrigId().equals(id) && isOffline(c)) return c;
		}
		return null;
	}
}
