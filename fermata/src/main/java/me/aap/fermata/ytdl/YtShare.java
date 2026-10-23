package me.aap.fermata.ytdl;

import android.content.Intent;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.log.Log;

/** Shares the YouTube video that is playing as its short link, through the share sheet. */
public final class YtShare {
	private static final String SHORT_LINK = "https://youtu.be/";

	private YtShare() {
	}

	/** The SHARE_VIDEO action: what plays now (see {@link Action#getFavoritableItem}). */
	public static void shareCurrent(MainActivityDelegate a) {
		PlayableItem pi = Action.getFavoritableItem(a);
		String id = YtDownloads.videoIdOf(pi);
		if (id == null) {
			TopToast.show(R.drawable.share, R.string.share_video_none);
			return;
		}
		if (a.isCarActivity()) {
			TopToast.show(R.drawable.share, R.string.share_link_on_phone);
			return;
		}

		try {
			Intent send = new Intent(Intent.ACTION_SEND);
			send.setType("text/plain");
			send.putExtra(Intent.EXTRA_SUBJECT, pi.getName());
			send.putExtra(Intent.EXTRA_TEXT, SHORT_LINK + id);
			Intent chooser = Intent.createChooser(send, a.getContext().getString(R.string.share_video));
			chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			a.getContext().startActivity(chooser);
		} catch (Exception ex) {
			Log.e(ex, "Failed to open the share menu for a video");
		}
	}
}
