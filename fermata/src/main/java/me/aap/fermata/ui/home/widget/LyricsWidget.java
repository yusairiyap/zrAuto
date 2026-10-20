package me.aap.fermata.ui.home.widget;

import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import me.aap.fermata.action.Action;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ui.view.LyricsView;
import me.aap.fermata.ytcaptions.CaptionsHub;

/**
 * The lyrics (the captions of a YouTube song) following along, drawn by the same view the Music tab
 * has: the line singing now, big, the ones around it fading away. A drag up or down scrubs through
 * them and lets go on a line to play it; a tap on a line seeks to it. The look (size, font,
 * alignment) is Settings > Lyrics. The page does not take the drag over while a finger is on it.
 */
final class LyricsWidget extends HomeWidgetView {
	private FrameLayout holder;
	private LyricsView lyrics;

	LyricsWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		LinearLayout column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		column.addView(header(() -> host.run(Action.LYRICS)),
				new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
		holder = new FrameLayout(ctx);
		holder.setClipChildren(true);
		column.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
		buildLyrics();
	}

	/** The view takes its colours when made, so a change of look makes it again. */
	private void buildLyrics() {
		if (lyrics != null) {
			lyrics.onVisibilityAggregated(false);
			holder.removeView(lyrics);
		}
		lyrics = new LyricsView(ctx, host.activity(), color(ROLE_SECONDARY), color(ROLE_PRIMARY), false);
		lyrics.setLineListener(i -> CaptionsHub.get().seekTo(i));
		holder.addView(lyrics, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected void onStyleChanged() {
		if (holder != null) buildLyrics();
	}

	@Override
	protected int gestures() {
		return GESTURE_V;
	}
}
