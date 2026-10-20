package me.aap.fermata.ui.home.widget;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;
import me.aap.fermata.ytcaptions.CaptionsHub;
import me.aap.fermata.ytcaptions.YtCaptions;

/**
 * The lyrics (the captions of a YouTube song) following along: the line singing now, big, with the
 * ones before and after fading away above and below it. A tap on a line seeks to it. How many lines
 * show follows the card's height.
 */
final class LyricsWidget extends HomeWidgetView implements CaptionsHub.Listener {
	private static final int LINES = 5;
	private static final int MID = 2;

	private LinearLayout column;
	private LinearLayout lines;
	private TextView message;
	private final TextView[] line = new TextView[LINES];
	private final int[] cueIndex = new int[LINES];
	private int shownIndex = Integer.MIN_VALUE;
	private CaptionsHub.State shownState;
	private String shownVideo;

	LyricsWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	@Override
	protected void onCreate(FrameLayout content) {
		column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		column.addView(header(() -> host.run(Action.LYRICS)),
				new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));

		lines = new LinearLayout(ctx);
		lines.setOrientation(LinearLayout.VERTICAL);
		lines.setGravity(Gravity.CENTER);
		for (int i = 0; i < LINES; i++) {
			final int slot = i;
			TextView t = text((i == MID) ? ROLE_PRIMARY : ROLE_SECONDARY, 14, i == MID);
			t.setGravity(Gravity.CENTER);
			t.setMaxLines(2);
			t.setEllipsize(android.text.TextUtils.TruncateAt.END);
			t.setBackground(HomeUi.pressable(ripple(), 0, px(12)));
			t.setPadding(px(6), px(3), px(6), px(3));
			t.setClickable(true);
			t.setFocusable(true);
			t.setOnClickListener(v -> {
				if (cueIndex[slot] >= 0) CaptionsHub.get().seekTo(cueIndex[slot]);
			});
			line[i] = t;
			lines.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
		}
		column.addView(lines, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		message = text(ROLE_SECONDARY, 13.5f, false);
		message.setGravity(Gravity.CENTER);
		message.setVisibility(GONE);
		column.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		content.addView(column, new FrameLayout.LayoutParams(-1, -1));
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.choice("size", ctx.getString(R.string.home_opt_text_size), 0,
				ctx.getString(R.string.home_size_auto), ctx.getString(R.string.home_size_small),
				ctx.getString(R.string.home_size_large)));
		return l;
	}

	@Override
	protected void onBind() {
		shownIndex = Integer.MIN_VALUE;
		update();
	}

	@Override
	protected void onResized(int w, int h) {
		if (column != null) {
			shownIndex = Integer.MIN_VALUE;
			update();
		}
	}

	@Override
	protected void onActive() {
		CaptionsHub.get().acquire(host.activity(), this);
		update();
	}

	@Override
	protected void onInactive() {
		CaptionsHub.get().release(this);
	}

	@Override
	public void onCaptionsState(CaptionsHub hub) {
		update();
	}

	@Override
	public void onCaptionsTick(CaptionsHub hub) {
		update();
	}

	/** How many lines fit: just the current one in a strip, three, or all five. */
	private int visibleLines() {
		if (getCellsH() == 1) return 1;
		return (getCellsH() >= 3 || (getCellsH() >= 2 && getCellsW() >= 4)) ? 5 : 3;
	}

	private void update() {
		if (column == null) return;
		CaptionsHub hub = CaptionsHub.get();
		CaptionsHub.State st = hub.getState();
		if (st != CaptionsHub.State.READY) {
			if (st == shownState && message.getVisibility() == VISIBLE) return;
			shownState = st;
			shownIndex = Integer.MIN_VALUE;
			lines.setVisibility(GONE);
			message.setVisibility(VISIBLE);
			message.setText(switch (st) {
				case LOADING -> R.string.home_lyrics_loading;
				case NONE -> R.string.home_lyrics_none;
				case FAILED -> R.string.home_lyrics_failed;
				default -> R.string.home_lyrics_idle;
			});
			return;
		}
		shownState = st;
		int idx = Math.max(hub.getIndex(), 0);
		String video = hub.getVideoId();
		if ((idx == shownIndex) && (video != null) && video.equals(shownVideo)
				&& lines.getVisibility() == VISIBLE) {
			return;
		}
		boolean changed = (shownIndex != Integer.MIN_VALUE) && (idx != shownIndex);
		shownIndex = idx;
		shownVideo = video;
		message.setVisibility(GONE);
		lines.setVisibility(VISIBLE);
		List<YtCaptions.Cue> cues = hub.getCues();
		int visible = visibleLines();
		int half = visible / 2;
		float scale = switch (optInt("size", 0)) {
			case 1 -> 0.85f;
			case 2 -> 1.25f;
			default -> (getCellsH() >= 3) ? 1.15f : 1f;
		};
		for (int i = 0; i < LINES; i++) {
			int off = i - MID;
			boolean show = Math.abs(off) <= half;
			TextView t = line[i];
			t.setVisibility(show ? VISIBLE : GONE);
			int ci = idx + off;
			boolean has = show && (ci >= 0) && (ci < cues.size());
			cueIndex[i] = has ? ci : -1;
			String txt = has ? cues.get(ci).text : "";
			t.setClickable(has);
			t.setAlpha((off == 0) ? 1f : Math.abs(off) == 1 ? 0.62f : 0.32f);
			t.setTextSize((off == 0) ? 18 * scale : 14 * scale);
			if (changed && (off == 0) && !txt.contentEquals(t.getText())) {
				t.setText(txt);
				t.setTranslationY(px(10));
				t.animate().translationY(0f).setDuration(200).start();
			} else {
				t.setText(txt);
			}
		}
	}
}
