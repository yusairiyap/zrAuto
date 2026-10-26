package me.aap.fermata.ui.home;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI;

import android.graphics.Bitmap;
import android.support.v4.media.MediaMetadataCompat;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicQueue;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.engine.BitmapCache;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;
import me.aap.fermata.ytdl.YtDownloads;
import me.aap.utils.app.App;

/**
 * What "now playing" is to the Home tab, as the Music tab has it: the item playing, else the track
 * its queue stopped at (so a cover and a title show right after the app starts, not "nothing"),
 * and its cover. The media widget and the background that follows the cover share this.
 */
public final class HomeArt {
	private HomeArt() {
	}

	/** The item to show: what plays, else where the queue stopped, else what played when the app last ran. */
	@Nullable
	public static PlayableItem displayItem(MainActivityDelegate a) {
		PlayableItem pi = Action.getFavoritableItem(a);
		if (pi != null) return pi;
		MusicQueue q = MusicPlayer.getQueue(a);
		if (q != null) {
			MusicTrackItem t = q.getSavedCurrent();
			if (t == null) t = q.getTrack(0);
			if (t != null) return t;
		}
		return a.getMediaSessionCallback().getResumeItem();
	}

	/** Whether nothing is loaded in a player: what {@link #displayItem} gives is only where playback stopped. */
	public static boolean isIdle(MainActivityDelegate a) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		return (cb.getCurrentItem() == null) && (Action.getFavoritableItem(a) == null);
	}

	/**
	 * Finds the cover of {@code pi} (from its metadata, else its icon; a downloaded video's saved
	 * one without a connection) and hands it over on the main thread, null if it has none. Nothing
	 * is delivered once {@code wanted} says no.
	 */
	/**
	 * With High thumbnail quality a YouTube cover is delivered twice: its balanced picture first, then the frame of the video
	 * once that is taken (the card and background fade from one to the other). Covers in memory already are delivered once.
	 */
	public static void cover(MainActivityDelegate a, PlayableItem pi, MediaMetadataCompat md,
													 BooleanSupplier wanted, Consumer<Bitmap> deliver) {
		// Covers on a black canvas (a square cover in a 16:9 picture) lose the black, so they fill the card.
		Consumer<Bitmap> out = bm -> deliver.accept(trimBars(bm));
		String uri = (pi instanceof MusicTrackItem t) ? t.getArtUri() : null;
		if (uri == null) {
			Bitmap bm = (md == null) ? null : md.getBitmap(METADATA_KEY_ALBUM_ART);
			if (bm != null) {
				out.accept(bm);
				return;
			}
			if (md != null) {
				uri = md.getString(METADATA_KEY_ALBUM_ART_URI);
				if (uri == null) uri = md.getString(METADATA_KEY_DISPLAY_ICON_URI);
			}
		}
		if (uri != null) {
			fetch(a, BitmapCache.highQualityCovers() ? largest(uri) : uri, wanted, out);
			return;
		}
		pi.getIconUri().main().onSuccess(u -> {
			if (!wanted.getAsBoolean()) return;
			if (u == null) out.accept(null);
			else fetch(a, BitmapCache.highQualityCovers() ? largest(u.toString()) : u.toString(), wanted, out);
		});
	}

	/**
	 * Cuts off black bars along the edges (up to a third of each side): a square cover that sits in the
	 * middle of a wide picture, with black either side, is what music videos and many uploads have.
	 */
	@Nullable
	static Bitmap trimBars(@Nullable Bitmap bm) {
		if (bm == null) return null;
		// The same picture again gives the same result object, so a caller can tell nothing changed.
		if (bm == memoSrc) return memoOut;
		Bitmap out = trimBars0(bm);
		memoSrc = bm;
		memoOut = out;
		return out;
	}

	private static Bitmap memoSrc;
	private static Bitmap memoOut;

	/** Whether {@code a} and {@code b} are the same song or video (the player may hand out another object for it). */
	public static boolean same(@Nullable PlayableItem a, @Nullable PlayableItem b) {
		if (a == b) return true;
		if ((a == null) || (b == null)) return false;
		try {
			return java.util.Objects.equals(a.getId(), b.getId());
		} catch (RuntimeException ex) {
			return false;
		}
	}

	private static Bitmap trimBars0(Bitmap bm) {
		try {
			Bitmap src = (bm.getConfig() == Bitmap.Config.HARDWARE) ? bm.copy(Bitmap.Config.ARGB_8888, false) : bm;
			if (src == null) return bm;
			int w = src.getWidth();
			int h = src.getHeight();
			if ((w < 16) || (h < 16)) return bm;
			int left = 0;
			while ((left < w / 3) && isBar(src, left, true)) left++;
			int right = 0;
			while ((right < w / 3) && isBar(src, w - 1 - right, true)) right++;
			int top = 0;
			while ((top < h / 3) && isBar(src, top, false)) top++;
			int bottom = 0;
			while ((bottom < h / 3) && isBar(src, h - 1 - bottom, false)) bottom++;
			// Only real bars: a hair of black at an edge is just the picture.
			if (left < w / 40) left = 0;
			if (right < w / 40) right = 0;
			if (top < h / 40) top = 0;
			if (bottom < h / 40) bottom = 0;
			if ((left | right | top | bottom) == 0) return bm;
			int nw = w - left - right;
			int nh = h - top - bottom;
			if ((nw < w / 3) || (nh < h / 3)) return bm;
			return Bitmap.createBitmap(src, left, top, nw, nh);
		} catch (RuntimeException ex) {
			return bm;
		}
	}

	/** Whether the column (or row) {@code at} is black: sampled along its length. */
	private static boolean isBar(Bitmap bm, int at, boolean column) {
		int len = column ? bm.getHeight() : bm.getWidth();
		int step = Math.max(1, len / 32);
		for (int i = 0; i < len; i += step) {
			int c = column ? bm.getPixel(at, i) : bm.getPixel(i, at);
			int luma = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
			if (luma > 26) return false;
		}
		return true;
	}

	/** A YouTube thumbnail address with the largest picture of the video (1280x720 where YouTube has it). */
	private static String largest(String uri) {
		if (!uri.contains("ytimg.com/vi/") && !uri.contains("img.youtube.com/vi/")) return uri;
		return uri.replaceAll("/(default|0|1|2|3|hqdefault|mqdefault|sddefault)\\.jpg", "/maxresdefault.jpg");
	}

	private static void fetch(MainActivityDelegate a, String uri, BooleanSupplier wanted, Consumer<Bitmap> out) {
		BitmapCache cache = FermataApplication.get().getBitmapCache();
		boolean frames = BitmapCache.wantsVideoFrame(uri);
		if (frames) {
			// A frame taken earlier this session is shown straight away: no balanced picture to flash first.
			Bitmap taken = cache.getCachedVideoFrame(uri);
			if (taken != null) {
				out.accept(taken);
				return;
			}
		} else {
			Bitmap hit = HomeImages.cached(uri);
			if (hit != null) {
				out.accept(hit);
				return;
			}
		}
		AtomicBoolean framed = new AtomicBoolean();
		AtomicBoolean frameDone = new AtomicBoolean();
		AtomicBoolean noBalanced = new AtomicBoolean();
		if (frames) {
			cache.getVideoFrame(uri).main().onCompletion((f, err) -> {
				frameDone.set(true);
				if (!wanted.getAsBoolean()) return;
				if (f != null) {
					framed.set(true);
					out.accept(f);
				} else if (noBalanced.get()) {
					// Neither picture: nothing to show (the balanced one has already come back empty).
					out.accept(null);
				}
			});
		}
		(frames ? cache.getBalancedCover(a.getContext(), uri) : a.getLib().getBitmap(uri))
				.main().onCompletion((bm, err) -> {
			// The frame came first: the balanced picture is not shown over it.
			if (!wanted.getAsBoolean() || framed.get()) return;
			String vid = (bm == null) ? YtDownloads.thumbnailVideoId(uri) : null;
			if (vid != null) {
				// No connection: a downloaded video's saved cover, or a picture out of its file.
				App.get().getExecutor().submitTask(() -> YtDownloads.get().localArt(vid)).main()
						.onCompletion((fr, e2) -> {
							if (wanted.getAsBoolean()) out.accept(fr);
						});
				return;
			}
			if (bm == null) {
				// With a frame still being taken, the card keeps what it shows until the frame is in (or is not).
				if (frames) {
					noBalanced.set(true);
					if (frameDone.get() && wanted.getAsBoolean()) out.accept(null);
					return;
				}
				out.accept(null);
				return;
			}
			// A video's picture comes letterboxed from YouTube.
			Bitmap use = MusicPlayerFragment.cropLetterbox(bm);
			if (use != null) HomeImages.put(uri, use);
			out.accept(use);
		});
	}
}
