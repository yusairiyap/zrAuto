package me.aap.fermata.ui.view;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.UiUtils;

/**
 * Where the cards of the overlay pickers ({@link PlaylistPicker}, {@link DownloadPicker}) go:
 * horizontally in the very middle of the screen -- the same distance from both edges, however the
 * side navigation bar's pill sits -- and vertically in the room the tab has, between the bars.
 */
final class OverlayCard {
	private static final int MAX_WIDTH_DP = 420;

	private OverlayCard() {
	}

	/**
	 * @param host    the full-window view the overlay is added to
	 * @param rowsH   the height the card's rows need, in pixels; capped to most of the room it has
	 */
	static FrameLayout.LayoutParams params(MainActivityDelegate a, ViewGroup host, int rowsH) {
		int hostW = host.getWidth();
		int hostH = host.getHeight();
		int[] side = new int[2];
		int[] bl = new int[2];
		int[] hl = new int[2];
		int top = 0;
		int bottom = 0;
		View body = a.getBody();

		if ((body != null) && body.isAttachedToWindow()) {
			a.computeSideInsets(body, side);
			body.getLocationInWindow(bl);
			host.getLocationInWindow(hl);
			side[0] += Math.max(0, bl[0] - hl[0]);
			side[1] += Math.max(0, (hl[0] + hostW) - (bl[0] + body.getWidth()));
			top = Math.max(0, bl[1] - hl[1]);
			bottom = Math.max(0, (hl[1] + hostH) - (bl[1] + body.getHeight()));
		}

		// The same on both sides, so the card is centered on the screen and still clear of the pill.
		int m = Math.max(side[0], side[1]);
		int w = Math.min(UiUtils.toIntPx(host.getContext(), MAX_WIDTH_DP),
				hostW - UiUtils.toIntPx(host.getContext(), 32) - 2 * m);
		int h = Math.min(rowsH, Math.round((hostH - top - bottom) * 0.86f));
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
		lp.leftMargin = m;
		lp.rightMargin = m;
		lp.topMargin = top;
		lp.bottomMargin = bottom;
		return lp;
	}
}
