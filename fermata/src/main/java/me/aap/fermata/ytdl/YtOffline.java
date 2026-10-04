package me.aap.fermata.ytdl;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.vfs.VirtualResource;
import me.aap.utils.vfs.local.LocalFileSystem;

/**
 * Plays YouTube videos from their {@link YtDownloads downloaded} copy instead of streaming them.
 * <p>
 * The video items themselves ({@code YoutubeVideoItem}, and the Music tab's queue tracks) ask
 * {@link #useLocal} / {@link #getResource} whether to point at the file, so every way of reaching
 * them -- a tap, next/previous, the car, resuming -- ends up playing the file without each having
 * to know about downloads.
 */
public final class YtOffline {
	private YtOffline() {
	}

	/**
	 * Whether {@code videoId} plays from its downloaded copy: always when there is one, so a video
	 * that was downloaded never uses the internet again -- the point of downloading it.
	 */
	public static boolean useLocal(@Nullable String videoId) {
		return YtDownloads.get().isDownloaded(videoId);
	}

	/** The downloaded file of {@code videoId}, or null. */
	@Nullable
	public static VirtualResource getResource(@Nullable String videoId) {
		File f = YtDownloads.get().getFile(videoId);
		return (f == null) ? null : LocalFileSystem.getInstance().getFile(f);
	}

	/**
	 * Plays {@code item} from its downloaded copy if {@link #useLocal} says so. False if it isn't
	 * a downloaded video or should be streamed, so the caller carries on as usual.
	 */
	public static boolean tryPlayLocal(MainActivityDelegate a, PlayableItem item, long pos) {
		String id = YtDownloads.videoIdOf(item);
		if (!useLocal(id)) return false;
		YtDownloads.Entry e = YtDownloads.get().getEntry(id);
		DiagnosticLog.log("YTDL", "playing the downloaded copy", "id=" + id, "pos=" + pos);
		silenceYoutubePage(a);
		if (pos > 0) item.getPrefs().setPositionPref(pos);

		if ((e != null) && e.video) {
			// The picture: fullscreen, like a local video. The item points at the file by now, see
			// the class comment.
			a.getBody().playLocalVideo(item);
		} else if (MusicPlayer.isEnabled()) {
			// Just the sound: the Music tab, like a track.
			MusicPlayer.play(a, item, true);
		} else {
			a.getMediaServiceBinder().playItem(item);
		}
		return true;
	}

	/** Whether {@code item} is a YouTube video that plays from its downloaded copy. */
	public static boolean isDownloadedYoutube(@Nullable PlayableItem item) {
		return useLocal(YtDownloads.videoIdOf(item));
	}

	/** Same, from where the page's player was when the connection gave out. */
	public static void switchToDownloaded(MainActivityDelegate a, PlayableItem item, long pos) {
		if (!tryPlayLocal(a, item, pos)) {
			UiUtils.showToast(a.getContext(), R.string.ytdl_not_downloaded);
			return;
		}
		UiUtils.showToast(a.getContext(), R.string.ytdl_playing_offline_copy);
	}

	/** Whether there's any downloaded video to play. */
	public static boolean hasDownloaded() {
		return !YtDownloads.get().getDownloaded().isEmpty();
	}

	/**
	 * Plays everything that's been downloaded, in the order it was downloaded, as a queue: the
	 * "Play downloaded" button of the network warning. False if there's nothing.
	 */
	public static boolean playAllDownloaded(MainActivityDelegate a) {
		List<YtDownloads.Entry> list = YtDownloads.get().getDownloaded();
		if (list.isEmpty()) return false;
		DiagnosticLog.log("YTDL", "playing all downloads", "count=" + list.size());

		List<PlayableItem> items = new ArrayList<>(list.size());
		resolve(a, list, 0, items);
		return true;
	}

	private static void resolve(MainActivityDelegate a, List<YtDownloads.Entry> list, int i,
															List<PlayableItem> out) {
		if (i == list.size()) {
			if (out.isEmpty()) return;
			silenceYoutubePage(a);
			MusicPlayer.play(a, out, 0);
			return;
		}

		a.getLib().getItem(YtDownloads.ID_PREFIX + list.get(i).videoId).main().onCompletion((it, err) -> {
			if ((err == null) && (it instanceof PlayableItem pi)) out.add(pi);
			resolve(a, list, i + 1, out);
		});
	}

	/** The YouTube page's player must be told to stop: its engine's close() leaves it playing. */
	private static void silenceYoutubePage(MainActivityDelegate a) {
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		if ((eng != null) && (eng.getId() == MediaPrefs.MEDIA_ENG_YT)) eng.pause();
	}
}
