package me.aap.fermata.ui.transition;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.action.Action;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.home.HomeArt;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE;

/**
 * Shows the transition screen when a new song starts (Settings &gt; Playback &gt; Transition screen).
 * Each screen that is up (the phone's, the car's) has its own set of looks and its own switch; it is
 * a view over {@code R.id.main_activity}, so it shows over tabs and fullscreen video alike. It waits
 * for the cover (a second at most) so the picture is there when it comes in. Never run on a device.
 */
public final class TransitionController {
	private static final long COVER_WAIT_MS = 1000;
	private static final List<MainActivityDelegate> delegates = new CopyOnWriteArrayList<>();
	private static final Handler main = new Handler(Looper.getMainLooper());
	@Nullable
	private static String lastId;
	/** What each screen (the phone's, the car's) is showing now. */
	private static final java.util.Map<MainActivityDelegate, Shown> shownBy = new java.util.HashMap<>();

	private TransitionController() {
	}

	public static void register(MainActivityDelegate a) {
		if (!delegates.contains(a)) delegates.add(a);
	}

	public static void unregister(MainActivityDelegate a) {
		delegates.remove(a);
		Shown s = shownBy.get(a);
		if (s != null) s.discard();
	}

	private static final class Shown {
		final MainActivityDelegate a;
		final TransitionView view;
		final ViewGroup host;
		final Runnable hideTask = this::hide;
		boolean leaving;

		Shown(MainActivityDelegate a, ViewGroup host, TransitionView view) {
			this.a = a;
			this.host = host;
			this.view = view;
		}

		void hide() {
			if (leaving) return;
			leaving = true;
			main.removeCallbacks(hideTask);
			view.playOut(this::discard);
		}

		void discard() {
			main.removeCallbacks(hideTask);
			leaving = true;
			view.animate().cancel();
			host.removeView(view);
			if (shownBy.get(a) == this) shownBy.remove(a);
		}
	}

	/** Closes what is showing (for the Back button); returns whether something was. */
	public static boolean dismissOpen(MainActivityDelegate a) {
		Shown s = shownBy.get(a);
		if ((s == null) || s.leaving) return false;
		s.hide();
		return true;
	}

	/**
	 * An engine started a song (any thread). The engine's source can still be empty at that moment (the YouTube page
	 * sets it a little later), so it is asked again a few times, then the item the UI says is playing is taken.
	 */
	public static void onTrackStarted(@Nullable MediaEngine engine) {
		main.post(() -> resolve(engine, 0));
	}

	private static void resolve(@Nullable MediaEngine engine, int tries) {
		// The favoritable item, not getSource(): the YouTube engine's source is one placeholder ("current") whose id is the
		// same for every video, so every song after the first looked like the one already shown.
		PlayableItem pi = (engine == null) ? null : engine.getFavoritableItem();
		if (pi instanceof MusicTrackItem t) {
			PlayableItem f = t.getFavoritableItem();
			if (f != null) pi = f;
		}
		if (pi == null) {
			MainActivityDelegate ui = MainActivityDelegate.getUiDelegate();
			if (ui != null) pi = Action.getFavoritableItem(ui);
		}
		if (pi != null) {
			start(pi);
			return;
		}
		if (tries < 4) {
			main.postDelayed(() -> resolve(engine, tries + 1), 400);
		} else {
			DiagnosticLog.w(LogCat.MUSIC, "transition screen: no song to show", "engine=" + engine);
		}
	}

	/** The same song is one key whatever object the player hands out for it: its video id, else its id. */
	private static String keyOf(PlayableItem pi) {
		String v = YtDownloads.videoIdOf(pi);
		return ((v != null) && !v.isEmpty()) ? "yt:" + v : pi.getId();
	}

	private static void start(PlayableItem pi) {
		String key = keyOf(pi);
		boolean first = lastId == null;
		boolean same = key.equals(lastId);
		lastId = key;
		DiagnosticLog.d(LogCat.MUSIC, "transition screen: song started", "key=" + key, "same=" + same, "first=" + first);
		if (same) return;

		if (delegates.isEmpty()) {
			DiagnosticLog.w(LogCat.MUSIC, "transition screen: no screen registered", "key=" + key);
			return;
		}
		for (MainActivityDelegate a : delegates) {
			boolean car = a.getAppActivity().isCarActivity();
			TransitionConfig c = TransitionConfig.load(car);
			if (!c.on || (first && !c.firstSong)) {
				DiagnosticLog.d(LogCat.MUSIC, "transition screen skipped", "car=" + car, "on=" + c.on, "first=" + first);
				continue;
			}
			boolean video = a.isVideoMode();
			if ((c.where == TransitionConfig.WHERE_FULLSCREEN && !video) ||
					(c.where == TransitionConfig.WHERE_OUTSIDE && video)) {
				DiagnosticLog.d(LogCat.MUSIC, "transition screen skipped", "car=" + car, "where=" + c.where, "video=" + video);
				continue;
			}
			show(a, pi, c, car);
		}
	}

	/**
	 * Where the screen is drawn: the window's own top view, so it covers the status bar and the navigation bar as the Home and Music
	 * tabs' backgrounds do ({@code main_activity} stops below the system bars). Falls back to {@code main_activity}.
	 */
	@Nullable
	public static ViewGroup overlayHost(MainActivityDelegate a) {
		try {
			View d = a.getAppActivity().getWindow().getDecorView();
			if (d instanceof ViewGroup g) return g;
		} catch (RuntimeException ignore) {
			// Falls back below.
		}
		return a.findViewById(R.id.main_activity);
	}

	/** Fills {@code v} with what {@code pi} is: its title, channel, playlist and cover (the preview in the settings). */
	public static void loadInto(MainActivityDelegate a, PlayableItem pi, TransitionView v) {
		// A later call (the next song) makes this one's answers stale.
		Object token = new Object();
		v.setTag(R.id.transition_settings_fragment, token);
		v.setArt(null);
		pi.getMediaData().main().onCompletion((md, err) -> {
			if (v.getTag(R.id.transition_settings_fragment) != token) return;
			MusicTrackItem tr = trackOf(a);
			v.setData(titleOf(pi, md), channelOf(pi, md, tr), playlistOf(a, pi, tr));
			HomeArt.cover(a, pi, md, () -> v.getTag(R.id.transition_settings_fragment) == token, bm -> {
				if ((bm != null) && (v.getTag(R.id.transition_settings_fragment) == token)) v.setArt(bm);
			});
		});
	}

	private static String titleOf(PlayableItem pi, @Nullable MediaMetadataCompat md) {
		String title = pi.getName();
		if (md != null) {
			String t = md.getString(METADATA_KEY_TITLE);
			if ((t == null) || t.isEmpty()) t = md.getString(METADATA_KEY_DISPLAY_TITLE);
			if ((t != null) && !t.isEmpty()) title = t;
		}
		return title;
	}

	private static void show(MainActivityDelegate a, PlayableItem pi, TransitionConfig c, boolean car) {
		ViewGroup host = overlayHost(a);
		if (host == null) return;
		Shown old = shownBy.get(a);
		if (old != null) old.discard();

		pi.getMediaData().main().onCompletion((md, err) -> {
			String title = titleOf(pi, md);
			MusicTrackItem tr = trackOf(a);
			String channel = channelOf(pi, md, tr);
			String playlist = playlistOf(a, pi, tr);
			String shownTitle = title;
			Shown[] up = {null};
			boolean[] launched = {false};
			Runnable go = () -> {
				if (launched[0]) return;
				launched[0] = true;
				up[0] = display(a, host, c, car, shownTitle, channel, playlist, null);
			};
			// The cover first, if it comes in time; if it is late it is put in when it arrives.
			boolean needsArt = c.elements.stream().anyMatch(e -> e.visible && e.type == TransitionConfig.T_ART) ||
					(c.bg == TransitionConfig.BG_ART) || (c.bg == TransitionConfig.BG_GLASS);
			if (!needsArt) {
				go.run();
				return;
			}
			main.postDelayed(go, COVER_WAIT_MS);
			HomeArt.cover(a, pi, md, () -> true, bm -> {
				if (!launched[0]) {
					launched[0] = true;
					main.removeCallbacks(go);
					up[0] = display(a, host, c, car, shownTitle, channel, playlist, bm);
				} else if ((up[0] != null) && (shownBy.get(a) == up[0]) && (bm != null)) {
					up[0].view.setArt(bm);
				}
			});
		});
	}

	@Nullable
	private static Shown display(MainActivityDelegate a, ViewGroup host, TransitionConfig c, boolean car, String title,
															 @Nullable String channel, @Nullable String playlist, @Nullable Bitmap art) {
		// A newer song may have taken the screen while the cover was coming.
		Shown old = shownBy.get(a);
		if (old != null) old.discard();

		TransitionView v = new TransitionView(a.getContext(), c);
		v.setData(title, channel, playlist);
		v.setArt(art);
		if (c.bg == TransitionConfig.BG_PICTURE) v.setPicture(loadPicture(car));
		v.setElevation(host.getResources().getDisplayMetrics().density * 18);
		v.setClickable(c.tapToDismiss);
		// Invisible until playIn has put every part in its start pose (else the finished screen shows for a frame, then restarts).
		v.setAlpha(0f);
		Shown s = new Shown(a, host, v);
		if (c.tapToDismiss) v.setOnClickListener(x -> s.hide());
		shownBy.put(a, s);
		host.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		v.post(() -> {
			if (car) TransitionConfig.rememberCarAspect(host.getWidth(), host.getHeight());
			v.playIn();
		});
		main.postDelayed(s.hideTask, c.holdTenths * 100L + c.inMs);
		DiagnosticLog.log(LogCat.MUSIC, "transition screen shown", "car=" + car, "title=" + title);
		return s;
	}

	@Nullable
	private static String channelOf(PlayableItem pi, @Nullable MediaMetadataCompat md, @Nullable MusicTrackItem track) {
		String ar = (md == null) ? null : md.getString(METADATA_KEY_ARTIST);
		if (md != null) {
			if ((ar == null) || ar.isEmpty()) ar = md.getString(METADATA_KEY_ALBUM_ARTIST);
			if ((ar == null) || ar.isEmpty()) ar = md.getString(METADATA_KEY_ALBUM);
		}
		if ((ar == null) || ar.isEmpty()) {
			if (pi instanceof MusicTrackItem t) ar = t.getArtistName();
			if (((ar == null) || ar.isEmpty()) && (track != null)) ar = track.getArtistName();
		}
		// A downloaded video played from its file carries no tags: the download remembers its channel.
		if ((ar == null) || ar.isEmpty()) {
			String vid = YtDownloads.videoIdOf(pi);
			if ((vid == null) && (track != null)) vid = track.getVideoId();
			if (vid != null) {
				YtDownloads.Entry e = YtDownloads.get().getEntry(vid);
				if ((e != null) && (e.artist != null) && !e.artist.isEmpty()) ar = e.artist;
			}
		}
		return ((ar == null) || ar.isEmpty()) ? null : MusicTrackItem.cleanArtist(ar);
	}

	/**
	 * The playlist (or Favorites) the song plays from: the one the Music queue was filled from, else the one of the playing queue item
	 * (a video started from a playlist), else of the queue entry's source or of the item itself.
	 */
	@Nullable
	private static String playlistOf(MainActivityDelegate a, PlayableItem pi, @Nullable MusicTrackItem track) {
		if (track != null) {
			me.aap.fermata.addon.music.MusicQueue q = MusicPlayer.getQueue(a);
			String o = (q == null) ? null : q.getOrigin();
			if (o != null) return o;
		}
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		String n = (eng == null) ? null : listName(eng.getQueueItem());
		if (n != null) return n;
		n = listName((track == null) ? null : track.getSource());
		return (n != null) ? n : listName(pi);
	}

	@Nullable
	private static String listName(@Nullable PlayableItem i) {
		if (i == null) return null;
		MediaLib.BrowsableItem p = i.getParent();
		if ((p instanceof MediaLib.Playlist) || (p instanceof MediaLib.Favorites)) {
			String n = p.getName();
			return ((n == null) || n.isEmpty()) ? null : n;
		}
		return null;
	}

	@Nullable
	private static MusicTrackItem trackOf(@Nullable MainActivityDelegate a) {
		return (a == null) ? null : MusicPlayer.getCurrentTrack(a.getMediaSessionCallback());
	}

	// ---- The picked picture ----

	public static File pictureFile(boolean car) {
		return new File(FermataApplication.get().getFilesDir(), car ? "transition_bg_aa.jpg" : "transition_bg.jpg");
	}

	/** Decodes a picked picture shrunk to 1600 px (turned upright) and stores it as the set's picture. Blocks. */
	public static void savePicture(android.content.Context ctx, android.net.Uri uri, boolean car) throws java.io.IOException {
		Bitmap bm;
		try {
			android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(ctx.getContentResolver(), uri);
			bm = android.graphics.ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
				int w = info.getSize().getWidth();
				int h = info.getSize().getHeight();
				float k = Math.min(1f, 1600f / Math.max(w, h));
				decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
				decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
			});
		} catch (Throwable ex) {
			try (java.io.InputStream is = ctx.getContentResolver().openInputStream(uri)) {
				if (is == null) throw new java.io.IOException("Can't open " + uri);
				bm = BitmapFactory.decodeStream(is);
			}
			if (bm == null) throw new java.io.IOException("Not a picture: " + uri);
		}
		File f = pictureFile(car);
		File tmp = new File(f.getPath() + ".tmp");
		try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
			bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
		}
		if (f.exists() && !f.delete()) throw new java.io.IOException("Could not replace the picture");
		if (!tmp.renameTo(f)) throw new java.io.IOException("Could not store the picture");
	}

	@Nullable
	public static Bitmap loadPicture(boolean car) {
		File f = pictureFile(car);
		if (!f.isFile()) return null;
		try {
			return BitmapFactory.decodeFile(f.getAbsolutePath());
		} catch (Throwable ex) {
			return null;
		}
	}
}
