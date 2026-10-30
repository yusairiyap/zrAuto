package me.aap.fermata.ui.home.widget;

import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.ui.home.HomeOption;
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
	private android.widget.TextView langChip;

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
		lyrics.setInteractive(scrubbing());
		holder.addView(lyrics, new FrameLayout.LayoutParams(-1, -1));
		// The language, the Latin letters and the translation, switched here on the spot.
		// A pill like the cover's hide button: the CC icon at a fixed size and the language beside it.
		LinearLayout pill = new LinearLayout(ctx);
		pill.setOrientation(LinearLayout.HORIZONTAL);
		pill.setGravity(android.view.Gravity.CENTER);
		pill.setPadding(px(10), 0, px(12), 0);
		android.widget.ImageView cc = icon(R.drawable.captions, 18, ROLE_PRIMARY);
		pill.addView(cc);
		langChip = text(ROLE_PRIMARY, 12, true);
		langChip.setSingleLine(true);
		langChip.setText(me.aap.fermata.ui.view.CaptionLang.shortLabel());
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		tlp.setMarginStart(px(6));
		pill.addView(langChip, tlp);
		trackSoft(pill, 17);
		pill.setClickable(true);
		pill.setFocusable(true);
		pill.setContentDescription(ctx.getString(R.string.captions_language_button));
		pill.setOnClickListener(v -> me.aap.fermata.ui.view.CaptionLang.showMenu(host.activity(), () ->
				postDelayed(() -> langChip.setText(me.aap.fermata.ui.view.CaptionLang.shortLabel()), 1500)));
		FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34),
				android.view.Gravity.END | android.view.Gravity.TOP);
		clp.topMargin = px(2);
		holder.addView(pill, clp);
	}

	/** Whether a drag scrubs through the lines and a tap on one seeks to it (else the page scrolls over the card). */
	private boolean scrubbing() {
		return optBool("scrub", true);
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.sw("scrub", ctx.getString(R.string.home_opt_lyrics_scrub), true));
		return l;
	}

	@Override
	protected void onBind() {
		if (lyrics != null) lyrics.setInteractive(scrubbing());
	}

	@Override
	protected void onStyleChanged() {
		if (holder != null) buildLyrics();
	}

	@Override
	protected int gestures() {
		// With scrubbing off the card claims nothing: a drag on it scrolls the page like on any other card.
		return scrubbing() ? GESTURE_V : 0;
	}
}
