package me.aap.fermata.share;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Favorites;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.vfs.VirtualResource;

import static me.aap.utils.async.Completed.completed;

/**
 * Works out what sharing an item, a playlist, the favorites or a folder would send: a
 * {@link ShareOffer} with an entry per playable item. YouTube videos go as their id (and, when
 * downloaded, optionally the file); local files go as their bytes. Nothing is read here beyond
 * file sizes.
 */
public final class ShareSource {
	/** The most links written into a "share as link" text. */
	private static final int MAX_LINKS = 100;

	private ShareSource() {
	}

	/** @return the offer for sharing {@code item} (one item, or everything in a container) */
	@NonNull
	public static FutureSupplier<ShareOffer> create(Context ctx, Item item) {
		if (item instanceof PlayableItem pi) {
			return completed(build(ctx, ShareOffer.KIND_ITEM, pi.getName(), List.of(pi)));
		}
		if (!(item instanceof BrowsableItem bi)) {
			return completed(build(ctx, ShareOffer.KIND_ITEM, "", List.of()));
		}

		String kind = (bi instanceof Playlist) ? ShareOffer.KIND_PLAYLIST :
				(bi instanceof Favorites) ? ShareOffer.KIND_FAVORITES : ShareOffer.KIND_FOLDER;
		String title = (bi instanceof Playlist pl) ? pl.getName() :
				(bi instanceof Favorites) ? ctx.getString(R.string.favorites) : bi.getName();
		// Playlists and favorites are flat; a folder takes everything below it.
		boolean recursive = ShareOffer.KIND_FOLDER.equals(kind);
		return bi.getPlayableChildren(recursive, false).main()
				.map(list -> build(ctx, kind, title, list));
	}

	/**
	 * The app itself: its installed APK, sent as a file the other phone offers to install. Only the
	 * base APK is sent, so an install split into several APKs (from an app bundle) can't be shared;
	 * the releases of this app are single APKs.
	 */
	@NonNull
	public static ShareOffer createApp(Context ctx) {
		List<ShareEntry> entries = new ArrayList<>(1);
		String version = "";
		try {
			android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
			String[] splits = ai.splitSourceDirs;
			File f = new File(ai.sourceDir);
			version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
			if (((splits == null) || (splits.length == 0)) && f.isFile()) {
				String name = ctx.getString(R.string.app_name).replaceAll("\\s+", "") + "-" + version + ".apk";
				ShareEntry e = new ShareEntry(ShareEntry.FILE, null, name, null, -1, false, 0, f.length());
				e.file = f;
				entries.add(e);
			}
		} catch (Exception ex) {
			me.aap.utils.log.Log.e(ex, "Cannot share the app");
		}
		String title = ctx.getString(R.string.app_name) + ((version == null) || version.isEmpty() ?
				"" : " " + version);
		return new ShareOffer(SharePrefs.getDeviceName(), title, ShareOffer.KIND_APP, entries);
	}

	/** Several items picked from a list (a playlist or the favorites), named {@code title}. */
	@NonNull
	public static ShareOffer create(Context ctx, String kind, String title, List<PlayableItem> items) {
		return build(ctx, kind, title, items);
	}

	private static ShareOffer build(Context ctx, String kind, String title, List<PlayableItem> items) {
		List<ShareEntry> entries = new ArrayList<>(items.size());
		int skipped = 0;
		YtDownloads dl = YtDownloads.get();

		for (PlayableItem pi : items) {
			String vid = YtDownloads.videoIdOf(pi);

			if (vid != null) {
				YtDownloads.Entry de = dl.getEntry(vid);
				File f = dl.getFile(vid);
				String name = pi.getName();
				if ((de != null) && (de.title != null) && !de.title.isEmpty()) name = de.title;
				ShareEntry e = new ShareEntry(ShareEntry.YOUTUBE, vid, name,
						(de != null) ? de.artist : null, (de != null) ? de.durationMs : -1,
						(de != null) && de.video, (de != null) ? de.height : 0, 0);
				e.downloadFile = f;
				entries.add(e);
				continue;
			}

			ShareEntry e = fileEntry(ctx, pi);
			if (e == null) skipped++;
			else entries.add(e);
		}

		ShareOffer o = new ShareOffer(SharePrefs.getDeviceName(), title.isEmpty() ?
				ctx.getString(R.string.share_default_title) : title, kind, entries);
		o.skipped = skipped;
		o.applyDownloads(true);
		return o;
	}

	@Nullable
	private static ShareEntry fileEntry(Context ctx, PlayableItem pi) {
		try {
			VirtualResource r = pi.getResource();
			File f = r.getLocalFile();

			if ((f != null) && f.isFile()) {
				ShareEntry e = new ShareEntry(ShareEntry.FILE, null, f.getName(), null, -1,
						pi.isVideo(), 0, f.length());
				e.file = f;
				return e;
			}

			Uri u = r.getRid().toAndroidUri();
			if ((u != null) && "content".equals(u.getScheme())) {
				long len = -1;
				try (android.content.res.AssetFileDescriptor fd =
										 ctx.getContentResolver().openAssetFileDescriptor(u, "r")) {
					if (fd != null) len = fd.getLength();
				}
				if (len > 0) {
					ShareEntry e = new ShareEntry(ShareEntry.FILE, null, r.getName(), null, -1,
							pi.isVideo(), 0, len);
					e.uri = u;
					return e;
				}
			}
		} catch (Exception ex) {
			me.aap.utils.log.Log.e(ex, "Cannot share ", pi.getId());
		}

		return null;
	}

	/**
	 * The text to send as links: one line per YouTube video, "title" then its address, or null when
	 * there is no video in the offer.
	 */
	@Nullable
	public static String linkText(ShareOffer o) {
		List<ShareEntry> videos = new ArrayList<>();
		for (ShareEntry e : o.entries) {
			if ((e.type == ShareEntry.YOUTUBE) && (e.videoId != null)) videos.add(e);
		}
		if (videos.isEmpty()) return null;

		StringBuilder sb = new StringBuilder();
		if (videos.size() > 1) sb.append(o.title).append("\n\n");

		for (int i = 0; i < videos.size(); i++) {
			if (i == MAX_LINKS) {
				sb.append('…');
				break;
			}
			ShareEntry e = videos.get(i);
			if (i > 0) sb.append("\n\n");
			sb.append(e.name).append('\n').append("https://youtu.be/").append(e.videoId);
		}

		return sb.toString();
	}
}
