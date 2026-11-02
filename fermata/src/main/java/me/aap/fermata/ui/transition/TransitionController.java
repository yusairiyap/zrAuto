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
import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
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

	/** A song started playing (any thread). */
	public static void onTrackStarted(@Nullable PlayableItem pi) {
		if (pi == null) return;
		main.post(() -> start(pi));
	}

	private static void start(PlayableItem pi) {
		String id = pi.getId();
		boolean first = lastId == null;
		boolean same = id.equals(lastId);
		lastId = id;
		if (same) return;

		for (MainActivityDelegate a : delegates) {
			boolean car = a.getAppActivity().isCarActivity();
			TransitionConfig c = TransitionConfig.load(car);
			if (!c.on || (first && !c.firstSong)) continue;
			boolean video = a.isVideoMode();
			if ((c.where == TransitionConfig.WHERE_FULLSCREEN && !video) ||
					(c.where == TransitionConfig.WHERE_OUTSIDE && video)) {
				continue;
			}
			show(a, pi, c, car);
		}
	}

	private static void show(MainActivityDelegate a, PlayableItem pi, TransitionConfig c, boolean car) {
		ViewGroup host = a.findViewById(R.id.main_activity);
		if (host == null) return;
		Shown old = shownBy.get(a);
		if (old != null) old.discard();

		pi.getMediaData().main().onCompletion((md, err) -> {
			String title = pi.getName();
			if (md != null) {
				String t = md.getString(METADATA_KEY_TITLE);
				if ((t == null) || t.isEmpty()) t = md.getString(METADATA_KEY_DISPLAY_TITLE);
				if ((t != null) && !t.isEmpty()) title = t;
			}
			String channel = channelOf(pi, md);
			String playlist = playlistOf(pi);
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
		Shown s = new Shown(a, host, v);
		if (c.tapToDismiss) v.setOnClickListener(x -> s.hide());
		shownBy.put(a, s);
		host.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		v.post(v::playIn);
		main.postDelayed(s.hideTask, c.holdTenths * 100L + c.inMs);
		DiagnosticLog.log(LogCat.MUSIC, "transition screen shown", "car=" + car, "title=" + title);
		return s;
	}

	@Nullable
	private static String channelOf(PlayableItem pi, @Nullable MediaMetadataCompat md) {
		String ar = (md == null) ? null : md.getString(METADATA_KEY_ARTIST);
		if (md != null) {
			if ((ar == null) || ar.isEmpty()) ar = md.getString(METADATA_KEY_ALBUM_ARTIST);
			if ((ar == null) || ar.isEmpty()) ar = md.getString(METADATA_KEY_ALBUM);
		}
		if ((ar == null) || ar.isEmpty()) {
			if (pi instanceof MusicTrackItem t) ar = t.getArtistName();
		}
		return ((ar == null) || ar.isEmpty()) ? null : MusicTrackItem.cleanArtist(ar);
	}

	@Nullable
	private static String playlistOf(PlayableItem pi) {
		MediaLib.BrowsableItem p = pi.getParent();
		if (p instanceof MediaLib.Playlist) return p.getName();
		if (p instanceof MediaLib.Favorites) return p.getName();
		return null;
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
