package me.aap.fermata.ytdl;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.ShareSheet;
import me.aap.fermata.ui.view.TopToast;

/** The SHARE_VIDEO action: what plays now, shared through the same Share window as an item's menu. */
public final class YtShare {
	private YtShare() {
	}

	public static void shareCurrent(MainActivityDelegate a) {
		PlayableItem pi = Action.getFavoritableItem(a);
		if (pi == null) {
			TopToast.show(R.drawable.share, R.string.share_video_none);
			return;
		}
		ShareSheet.share(a, pi);
	}
}
