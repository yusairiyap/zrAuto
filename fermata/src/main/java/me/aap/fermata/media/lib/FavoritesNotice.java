package me.aap.fermata.media.lib;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.view.TopToast;

/**
 * Says so whenever something is added to or removed from Favorites, however that happened (a key
 * binding, a menu, Android Auto's own heart button): one card at the top of the screen, see
 * {@link TopToast}. A batch (a multi-selection) gives one card for all of it, not one per item.
 */
final class FavoritesNotice {
	private static final long BATCH_MS = 150;
	private static final Handler handler = new Handler(Looper.getMainLooper());
	// One instance: a method reference made twice isn't the same Runnable to removeCallbacks().
	private static final Runnable FLUSH = FavoritesNotice::flush;
	private static boolean pendingAdded;
	private static int pendingCount;
	@Nullable
	private static CharSequence pendingName;

	private FavoritesNotice() {
	}

	static void changed(PlayableItem i, boolean added) {
		CharSequence name = i.getName();
		handler.post(() -> queue(name, added));
	}

	private static void queue(CharSequence name, boolean added) {
		if ((pendingCount > 0) && (pendingAdded != added)) flush();
		pendingAdded = added;
		pendingCount++;
		pendingName = name;
		handler.removeCallbacks(FLUSH);
		handler.postDelayed(FLUSH, BATCH_MS);
	}

	private static void flush() {
		handler.removeCallbacks(FLUSH);
		int n = pendingCount;
		if (n == 0) return;
		pendingCount = 0;
		boolean added = pendingAdded;
		int icon = added ? R.drawable.favorite_filled : R.drawable.favorite;
		if (n == 1) {
			TopToast.show(icon, added ? R.string.favorites_added : R.string.favorites_removed,
					pendingName);
		} else {
			TopToast.show(icon, added ? R.string.favorites_added_n : R.string.favorites_removed_n, n);
		}
		pendingName = null;
	}
}
