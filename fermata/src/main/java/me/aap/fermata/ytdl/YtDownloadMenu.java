package me.aap.fermata.ytdl;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;

/**
 * The download entries of the item menus: Download (audio or video) for YouTube videos that aren't
 * on the phone yet, Remove download for those that are, and Pause/Resume/Cancel while there is a
 * queue. One place, so a single video, a selection and a whole playlist or Favorites all offer the
 * same things.
 */
public final class YtDownloadMenu {

	private YtDownloadMenu() {
	}

	/** Adds the entries that apply to {@code items}; nothing if none of them is a YouTube video. */
	public static void addTo(OverlayMenu.Builder b, MainActivityDelegate a,
													 List<? extends PlayableItem> items) {
		Map<String, String> videos = new LinkedHashMap<>();
		for (PlayableItem pi : items) {
			String id = YtDownloads.videoIdOf(pi);
			if (id != null) videos.putIfAbsent(id, pi.getName());
		}
		if (videos.isEmpty()) return;

		YtDownloads d = YtDownloads.get();
		List<YtDownloads.Request> audio = new ArrayList<>();
		List<YtDownloads.Request> video = new ArrayList<>();
		List<String> downloaded = new ArrayList<>();

		for (Map.Entry<String, String> e : videos.entrySet()) {
			if (d.isDownloaded(e.getKey())) {
				downloaded.add(e.getKey());
			} else {
				audio.add(new YtDownloads.Request(e.getKey(), e.getValue(), false));
				video.add(new YtDownloads.Request(e.getKey(), e.getValue(), true));
			}
		}

		boolean many = videos.size() > 1;
		Context ctx = a.getContext();

		if (!audio.isEmpty()) {
			b.addItem(R.id.ytdl_download_audio, R.drawable.download,
					many ? R.string.ytdl_download_all_audio : R.string.ytdl_download_audio).setHandler(i -> {
				queued(ctx, d.enqueue(audio));
				return true;
			});
			b.addItem(R.id.ytdl_download_video, R.drawable.download,
					many ? R.string.ytdl_download_all_video : R.string.ytdl_download_video).setHandler(i -> {
				queued(ctx, d.enqueue(video));
				return true;
			});
		}

		if (!downloaded.isEmpty()) {
			b.addItem(R.id.ytdl_remove, R.drawable.download_remove, R.string.ytdl_remove)
					.setHandler(i -> {
						for (String id : downloaded) d.remove(id);
						UiUtils.showToast(ctx, R.string.ytdl_removed);
						return true;
					});
		}

		addControls(b, d);
	}

	/** Pause/Resume and Cancel, while there is something queued, paused or failed. */
	public static void addControls(OverlayMenu.Builder b, YtDownloads d) {
		if (d.isBusy()) {
			b.addItem(R.id.ytdl_pause_all, R.drawable.pause, R.string.ytdl_pause_all).setHandler(i -> {
				d.pauseAll();
				return true;
			});
		} else if (d.hasResumable()) {
			b.addItem(R.id.ytdl_resume_all, R.drawable.download, R.string.ytdl_resume_all)
					.setHandler(i -> {
						d.resumeAll();
						return true;
					});
		} else {
			return;
		}

		b.addItem(R.id.ytdl_cancel_all, me.aap.utils.R.drawable.close, R.string.ytdl_cancel_all)
				.setHandler(i -> {
					d.cancelAll();
					return true;
				});
	}

	/**
	 * The FAB action: downloads what's playing -- as audio in the Music tab, as video otherwise.
	 * Nothing happens (but a message) unless it's a YouTube video.
	 */
	public static void downloadCurrent(MainActivityDelegate a) {
		PlayableItem pi = Action.getFavoritableItem(a);
		String id = YtDownloads.videoIdOf(pi);
		Context ctx = a.getContext();
		if (id == null) {
			UiUtils.showToast(ctx, R.string.ytdl_nothing_playing);
			return;
		}
		boolean video = !MusicPlayer.isMusicModeActive(a);
		queued(ctx, YtDownloads.get().enqueue(
				Collections.singletonList(new YtDownloads.Request(id, pi.getName(), video))));
	}

	/** The FAB action: pauses what's downloading, or carries on with what was paused. */
	public static void togglePause() {
		YtDownloads d = YtDownloads.get();
		if (d.isBusy()) d.pauseAll();
		else d.resumeAll();
	}

	private static void queued(Context ctx, int n) {
		if (n > 0) UiUtils.showToast(ctx, R.string.ytdl_queued, n);
		else UiUtils.showToast(ctx, R.string.ytdl_nothing_queued);
	}
}
