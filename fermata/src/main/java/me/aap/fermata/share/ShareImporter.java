package me.aap.fermata.share;

import static me.aap.utils.async.Completed.completed;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.VideoTitleCache;
import me.aap.fermata.addon.VideoTitleCache.VideoInfo;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.media.service.FermataMediaServiceConnection;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MediaLibFragment;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.utils.async.Async;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;

/**
 * Puts what another phone sent into the library. Downloaded YouTube files become downloads;
 * other files are kept in a folder of their own under the app's files ("Shared"), which is added
 * to the Folders tab; and unless a whole folder was sent, a playlist with the shared name collects
 * the videos and files (adding to it when it already exists).
 */
final class ShareImporter {
	private static final Handler main = new Handler(Looper.getMainLooper());

	interface Done {
		/** On the main thread. */
		void done(boolean ok, String message);
	}

	private ShareImporter() {
	}

	/** Where files of a share called {@code title} go. */
	static File folderFor(Context ctx, String title) {
		File base = ctx.getExternalFilesDir("Shared");
		if (base == null) base = new File(ctx.getFilesDir(), "Shared");
		File dir = new File(base, safeName(title));
		if (!dir.isDirectory() && !dir.mkdirs()) Log.w("Failed to create ", dir);
		return dir;
	}

	static void clearParts(File dir) {
		File[] files = dir.listFiles((d, n) -> n.startsWith(".part"));
		if (files == null) return;
		for (File f : files) {
			//noinspection ResultOfMethodCallIgnored
			f.delete();
		}
	}

	/** A name usable as a file or folder name (and as a playlist name). */
	static String safeName(String s) {
		String n = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
		while (n.startsWith(".")) n = n.substring(1);
		if (n.length() > 80) n = n.substring(0, 80).trim();
		return n.isEmpty() ? "Shared" : n;
	}

	/**
	 * Called with the received parts in {@code dir} (entry index to temporary file) once all
	 * bytes are in; moves them into place and adds everything to the library.
	 */
	static void save(ShareManager.Transfer t, ShareOffer offer, File dir, Map<Integer, File> parts,
									 Done done) {
		YtDownloads dl = YtDownloads.get();
		// Files that go to the shared folder, by entry.
		Map<ShareEntry, File> files = new LinkedHashMap<>();

		try {
			for (Map.Entry<Integer, File> p : parts.entrySet()) {
				ShareEntry e = offer.entries.get(p.getKey());
				File part = p.getValue();

				if ((e.type == ShareEntry.YOUTUBE) && (e.videoId != null)) {
					dl.importFile(e.videoId, e.name, e.artist, e.durationMs, e.video ? Math.max(e.height, 1) : 0,
							part);
				} else {
					File dest = unique(dir, safeName(e.name));
					Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
					files.put(e, dest);
				}
			}
		} catch (IOException ex) {
			Log.e(ex, "Share: failed to store the files");
			clearParts(dir);
			main.post(() -> done.done(false, String.valueOf(ex.getMessage())));
			return;
		}

		if (ShareOffer.KIND_APP.equals(offer.kind)) {
			// Not media: kept in its folder and offered for install, nothing goes to the library.
			File apk = files.isEmpty() ? null : files.values().iterator().next();
			main.post(() -> {
				if (apk == null) {
					done.done(false, me.aap.fermata.FermataApplication.get().getString(R.string.share_saved_nothing));
					return;
				}
				done.done(true, me.aap.fermata.FermataApplication.get().getString(R.string.share_app_received));
				offerInstall(apk);
			});
			return;
		}

		main.post(() -> FermataMediaServiceConnection.connect(null).main().onCompletion((con, cerr) -> {
			if ((cerr != null) || (con == null) || (con.getMediaSessionCallback() == null)) {
				if (cerr != null) Log.e(cerr, "Share: no media service");
				done.done(false, (cerr != null) ? String.valueOf(cerr.getMessage()) : "Media service unavailable");
				return;
			}

			addToLibrary(con.getMediaSessionCallback().getMediaLib(), offer, dir, files, t)
					.main().onCompletion((summary, err) -> {
						con.disconnect();

						if (err != null) {
							Log.e(err, "Share: failed to add to the library");
							done.done(false, (err.getMessage() != null) ? err.getMessage() : err.toString());
							return;
						}

						MainActivityDelegate a = MainActivityDelegate.getUiDelegate();
						if (a != null) {
							for (int id : new int[]{R.id.playlists_fragment, R.id.folders_fragment}) {
								MediaLibFragment f = a.getMediaLibFragment(id);
								if (f != null) f.reload();
							}
						}
						done.done(true, summary);
					});
		}));
	}

	private static FutureSupplier<String> addToLibrary(MediaLib lib, ShareOffer offer, File dir,
																										 Map<ShareEntry, File> files,
																										 ShareManager.Transfer t) {
		Context ctx = lib.getContext();
		List<ShareEntry> videos = new ArrayList<>();
		for (ShareEntry e : offer.entries) {
			if ((e.type == ShareEntry.YOUTUBE) && (e.videoId != null)) videos.add(e);
		}
		boolean folderShare = ShareOffer.KIND_FOLDER.equals(offer.kind);
		String name = safeName(offer.title);

		// 1. The folder with the files, so they show up in the Folders tab.
		FutureSupplier<List<PlayableItem>> fileItems = loadFileItems(lib, dir, files);

		if (folderShare) {
			return fileItems.map(list -> ctx.getString(R.string.share_saved_folder, files.size(), name));
		}

		// 2. The YouTube videos, as the items "Add to playlist" makes.
		return fileItems.main().then(local -> {
			if (videos.isEmpty()) return addToPlaylist(lib, name, local, t);
			return AddonManager.get().getOrInstallAddon(VideoTitleCache.YOUTUBE_ADDON_CLASS).main()
					.then(addon -> {
						if (addon instanceof VideoTitleCache c) {
							Map<String, String> titles = new LinkedHashMap<>();
							Map<String, VideoInfo> info = new LinkedHashMap<>();
							for (ShareEntry e : videos) {
								titles.put(e.videoId, e.name);
								info.put(e.videoId, new VideoInfo(e.name, e.artist, null, e.durationMs));
							}
							c.cacheVideoTitles(titles);
							c.cacheVideoInfo(info);
						}

						List<PlayableItem> all = new ArrayList<>(local);
						return Async.forEach(e -> lib.getItem("youtube:" + e.videoId).main().onSuccess(i -> {
							if (i instanceof PlayableItem pi) all.add(pi);
						}), videos).then(v -> addToPlaylist(lib, name, all, t));
					});
		});
	}

	/**
	 * Asks whether to install the app file just received, and hands it to the system installer.
	 * Android itself checks that an update is signed like the installed app.
	 */
	private static void offerInstall(File apk) {
		MainActivityDelegate a = MainActivityDelegate.getUiDelegate();
		Context c = me.aap.fermata.FermataApplication.get();
		me.aap.fermata.util.DiagnosticLog.log("SHARE", "app received:", apk, apk.length());
		if ((a == null) || a.isCarActivityNotMirror()) return;
		me.aap.fermata.ui.view.TopConfirm.show(a, R.drawable.share, c.getString(R.string.share_app_install_title),
				c.getString(R.string.share_app_install_text, apk.getName()),
				c.getString(R.string.share_app_install), c.getString(R.string.share_app_later),
				() -> install(a, apk), null);
	}

	private static void install(MainActivityDelegate a, File apk) {
		Context c = a.getContext();
		try {
			Uri u = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".FileProvider",
					apk);
			android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
			i.setDataAndType(u, "application/vnd.android.package-archive");
			i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
					android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
			a.startActivity(i);
		} catch (Exception ex) {
			// The phone-only build has no file provider: the file stays where it was saved.
			Log.e(ex, "Share: cannot open the installer");
			me.aap.fermata.ui.view.TopToast.show(R.drawable.share,
					c.getString(R.string.share_app_saved_at, apk.getAbsolutePath()));
		}
	}

	/** The library items for the files just saved into {@code dir}. */
	private static FutureSupplier<List<PlayableItem>> loadFileItems(MediaLib lib, File dir,
																																 Map<ShareEntry, File> files) {
		if (files.isEmpty()) {
			List<PlayableItem> none = new ArrayList<>();
			return completed(none);
		}

		return lib.getFolders().addItem(Uri.fromFile(dir)).main().then(added -> findFolder(lib, dir))
				.main().then(folder -> {
					if (folder == null) {
						List<PlayableItem> none = new ArrayList<>();
						return completed(none);
					}

					return folder.refresh().main().then(v -> folder.getPlayableChildren(false, false))
							.map(all -> {
								List<PlayableItem> mine = new ArrayList<>();
								for (PlayableItem pi : all) {
									String n = pi.getResource().getName();
									for (File f : files.values()) {
										if (f.getName().equals(n)) {
											mine.add(pi);
											break;
										}
									}
								}
								return mine;
							});
				});
	}

	private static FutureSupplier<String> addToPlaylist(MediaLib lib, String name,
																											List<PlayableItem> items,
																											ShareManager.Transfer t) {
		Context ctx = lib.getContext();
		if (items.isEmpty()) return completed(ctx.getString(R.string.share_saved_nothing));

		return lib.getPlaylists().getUnsortedChildren().main().then(list -> {
			for (Item i : list) {
				if ((i instanceof Playlist pl) && name.equals(pl.getName())) return completed(pl);
			}
			return lib.getPlaylists().addItem(name);
		}).main().then(pl -> pl.getUnsortedChildren().main().then(children -> {
			// What is in the playlist already is not added again (see Playlist#addItems): counted, so
			// the sender can be told.
			Set<String> have = new HashSet<>();
			for (Item c : children) {
				if (c instanceof PlayableItem p) have.add(p.getOrigId());
			}
			int n = 0;
			for (PlayableItem i : items) {
				if (have.contains(i.getOrigId())) n++;
			}
			int dups = n;
			int fresh = items.size() - dups;
			t.duplicates = dups;
			return pl.addItems(items).map(v -> (dups == 0) ?
					ctx.getString(R.string.share_saved_playlist, items.size(), name) :
					ctx.getString(R.string.share_saved_playlist_dups, fresh, name, dups));
		}));
	}

	private static FutureSupplier<BrowsableItem> findFolder(MediaLib lib, File dir) {
		String path = canonical(dir);
		return lib.getFolders().getUnsortedChildren().map(children -> {
			for (Item c : children) {
				File f = c.getResource().getLocalFile();
				if ((f != null) && canonical(f).equals(path) && (c instanceof BrowsableItem b)) return b;
			}
			return null;
		});
	}

	private static String canonical(File f) {
		try {
			return f.getCanonicalPath();
		} catch (IOException ex) {
			return f.getAbsolutePath();
		}
	}

	private static File unique(File dir, String name) {
		File f = new File(dir, name);
		if (!f.exists()) return f;
		int dot = name.lastIndexOf('.');
		String base = (dot > 0) ? name.substring(0, dot) : name;
		String ext = (dot > 0) ? name.substring(dot) : "";
		for (int i = 2; ; i++) {
			f = new File(dir, base + " (" + i + ")" + ext);
			if (!f.exists()) return f;
		}
	}
}
