package me.aap.fermata.ytdl;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.DownloadPicker;
import me.aap.fermata.ui.view.TopPopup;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;

/**
 * The download entries of the item menus: one Download that asks, in a card like Add to playlist,
 * what to download it as (the audio, or a picture quality), Remove download for videos that are
 * on the phone, and Pause/Resume/Cancel for the videos of the menu that are being downloaded --
 * only those, never somebody else's. One place, so a single video, a selection and a whole
 * playlist or Favorites all offer the same things.
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
		Map<String, String> todo = new LinkedHashMap<>();
		List<String> downloaded = new ArrayList<>();
		List<String> running = new ArrayList<>();
		List<String> stopped = new ArrayList<>();

		for (Map.Entry<String, String> e : videos.entrySet()) {
			String id = e.getKey();
			if (d.isDownloaded(id)) downloaded.add(id);
			else if (d.isRunning(id)) running.add(id);
			else if (d.isActive(id)) stopped.add(id);
			else todo.put(id, e.getValue());
		}

		Context ctx = a.getContext();

		if (!todo.isEmpty()) {
			b.addItem(R.id.ytdl_download, R.drawable.download, R.string.ytdl_download)
					.setHandler(i -> {
						pickAndDownload(a, todo);
						return true;
					});
		}

		if (!downloaded.isEmpty()) {
			b.addItem(R.id.ytdl_remove, R.drawable.download_remove, R.string.ytdl_remove)
					.setHandler(i -> {
						for (String id : downloaded) d.remove(id);
						TopToast.show(R.drawable.download_remove, R.string.ytdl_removed);
						return true;
					});
		}

		// Only for what is really being downloaded.
		if (!running.isEmpty()) {
			b.addItem(R.id.ytdl_pause_all, R.drawable.pause, R.string.ytdl_pause_one).setHandler(i -> {
				for (String id : running) d.pause(id);
				return true;
			});
		}
		if (!stopped.isEmpty()) {
			b.addItem(R.id.ytdl_resume_all, R.drawable.download, R.string.ytdl_resume_one)
					.setHandler(i -> {
						for (String id : stopped) d.resume(id);
						return true;
					});
		}
		if (!running.isEmpty() || !stopped.isEmpty()) {
			b.addItem(R.id.ytdl_cancel_all, me.aap.utils.R.drawable.close, R.string.ytdl_cancel_one)
					.setHandler(i -> {
						for (String id : running) d.remove(id);
						for (String id : stopped) d.remove(id);
						return true;
					});
		}
	}

	/**
	 * Asks what to download {@code videos} (id to title) as, then queues them. The one entry point
	 * of every Download in the app.
	 */
	public static void pickAndDownload(MainActivityDelegate a, Map<String, String> videos) {
		if (videos.isEmpty()) return;
		Context ctx = a.getContext();
		String name = (videos.size() == 1) ? videos.values().iterator().next() :
				ctx.getResources().getQuantityString(R.plurals.ytdl_videos, videos.size(), videos.size());
		DownloadPicker.show(a, name, height -> {
			List<YtDownloads.Request> list = new ArrayList<>(videos.size());
			for (Map.Entry<String, String> e : videos.entrySet()) {
				list.add(new YtDownloads.Request(e.getKey(), e.getValue(), height));
			}
			queued(a, YtDownloads.get().enqueue(list));
		});
	}

	/** Same for one video. */
	public static void pickAndDownload(MainActivityDelegate a, String videoId, @Nullable String title) {
		pickAndDownload(a, Collections.singletonMap(videoId,
				((title == null) || title.isEmpty()) ? videoId : title));
	}

	/**
	 * Pause/Resume and Cancel for the whole queue, while there is something queued, paused or
	 * failed -- the Downloads tab's menu.
	 */
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
	 * The FAB action: downloads what's playing -- asks what to download it as. Nothing happens (but
	 * a message) unless it's a YouTube video.
	 */
	public static void downloadCurrent(MainActivityDelegate a) {
		PlayableItem pi = Action.getFavoritableItem(a);
		String id = YtDownloads.videoIdOf(pi);
		if (id == null) {
			TopToast.show(R.drawable.download, R.string.ytdl_nothing_playing);
			return;
		}
		if (YtDownloads.get().isDownloaded(id) || YtDownloads.get().isActive(id)) {
			TopToast.show(R.drawable.download, R.string.ytdl_nothing_queued);
			return;
		}
		// Posted: this also runs from the FAB's own menu, which is still closing at this point.
		me.aap.utils.app.App.get().getHandler().post(() -> pickAndDownload(a, id, pi.getName()));
	}

	/** The FAB action: pauses what's downloading, or carries on with what was paused. */
	public static void togglePause() {
		YtDownloads d = YtDownloads.get();
		if (d.isBusy()) d.pauseAll();
		else d.resumeAll();
	}

	/**
	 * Says what was queued: a card at the top of the screen (like the other popups) that goes to the
	 * Downloads tab, rather than a toast that is gone before it can be acted on.
	 */
	private static void queued(MainActivityDelegate a, int n) {
		Context ctx = a.getContext();
		if (n <= 0) {
			UiUtils.showToast(ctx, R.string.ytdl_nothing_queued);
			return;
		}

		int bg = 0xFF263238;
		int fg = 0xFFFFFFFF;
		View b = LayoutInflater.from(ctx).inflate(R.layout.data_usage_banner, null, false);
		b.setElevation(UiUtils.toIntPx(ctx, 8));
		b.setBackgroundTintList(ColorStateList.valueOf(bg));
		ImageView icon = b.findViewById(R.id.data_usage_banner_icon);
		icon.setImageResource(R.drawable.download);
		icon.setImageTintList(ColorStateList.valueOf(bg));
		icon.setBackgroundTintList(ColorStateList.valueOf(fg));
		TextView title = b.findViewById(R.id.data_usage_banner_title);
		title.setTextColor(fg);
		title.setText(ctx.getString(R.string.ytdl_queued, n));
		TextView text = b.findViewById(R.id.data_usage_banner_text);
		text.setTextColor(fg);
		text.setAlpha(0.85f);
		text.setText(R.string.ytdl_queued_hint);
		TextView action = b.findViewById(R.id.data_usage_banner_action);
		action.setBackgroundTintList(ColorStateList.valueOf(fg));
		action.setTextColor(bg);
		action.setText(R.string.ytdl_title);
		action.setOnClickListener(v -> {
			TopPopup.dismiss(b);
			a.showFragment(R.id.downloads_addon);
		});
		ImageButton close = b.findViewById(R.id.data_usage_banner_close);
		close.setImageResource(me.aap.utils.R.drawable.close);
		close.setImageTintList(ColorStateList.valueOf(fg));
		close.setOnClickListener(v -> TopPopup.dismiss(b));

		if (TopPopup.show(a, b, null)) {
			b.postDelayed(() -> TopPopup.dismiss(b), 6000);
		} else {
			UiUtils.showToast(ctx, R.string.ytdl_queued, n);
		}
	}
}
