package me.aap.fermata.ui.home;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI;

import android.graphics.Bitmap;
import android.support.v4.media.MediaMetadataCompat;

import androidx.annotation.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicQueue;
import me.aap.fermata.addon.music.MusicTrackItem;
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
	public static void cover(MainActivityDelegate a, PlayableItem pi, MediaMetadataCompat md,
													 BooleanSupplier wanted, Consumer<Bitmap> out) {
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
			fetch(a, uri, wanted, out);
			return;
		}
		pi.getIconUri().main().onSuccess(u -> {
			if (!wanted.getAsBoolean()) return;
			if (u == null) out.accept(null);
			else fetch(a, u.toString(), wanted, out);
		});
	}

	private static void fetch(MainActivityDelegate a, String uri, BooleanSupplier wanted, Consumer<Bitmap> out) {
		Bitmap hit = HomeImages.cached(uri);
		if (hit != null) {
			out.accept(hit);
			return;
		}
		a.getLib().getBitmap(uri).main().onCompletion((bm, err) -> {
			if (!wanted.getAsBoolean()) return;
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
