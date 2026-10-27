package me.aap.fermata.ui.home.widget;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.addon.music.MusicQueue;
import me.aap.fermata.media.engine.BufferingIndicator;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.TopToast;
import me.aap.fermata.ui.home.HomeArt;
import me.aap.fermata.ui.home.HomeBlur;
import me.aap.fermata.ui.home.HomeHost;
import me.aap.fermata.ui.home.HomeImages;
import me.aap.fermata.ui.home.HomeOption;
import me.aap.fermata.ui.home.HomeUi;
import me.aap.fermata.ui.home.HomeWidgetType;
import me.aap.fermata.ui.home.HomeWidgetView;

/**
 * The player: what plays, with its cover blended into the card (blurred behind everything, and
 * crisp on one side fading into it), play and pause, previous and next, the title and artist and,
 * as the card grows, a seek bar and a chip that goes to the video (or the Music tab). A strip shows
 * a thumbnail with the controls; the biggest sizes show the cover large. For a video it shows the
 * video's poster picture and a fullscreen chip; the picture itself plays in its own tab.
 */
final class MediaWidget extends HomeWidgetView {
	private static final int STRIP_SMALL = 0;
	private static final int STRIP_WIDE = 1;
	private static final int MEDIUM = 2;
	private static final int LARGE_WIDE = 3;
	private static final int LARGE_TALL = 4;
	private static final long TICK_MS = 500;
	/** Room beside the cover below which a wide card is laid out tight (see applyMode). */
	private static final int TIGHT_DP = 300;
	/** The name and artist lines of a wide card, at the top (20sp + 14sp). */
	private static final int TEXTS_DP = 46;
	/** The card is tight: the chip shows only its icon. */
	private boolean narrowChip;

	/** How often the player itself is asked where it is while playing. */
	private static final long ENGINE_POLL_MS = 3000;
	/** How long its answer is counted on from (a bit over two asks, so a late answer does not drop back to the session's). */
	private static final long ENGINE_TRUST_MS = 7000;

	private Fader wash;
	private Fader art;
	private Fader thumb;
	private ProgressBar artBusy;
	private View scrim;
	private TextView title;
	private TextView artist;
	private TextView badge;
	private ImageView prev;
	private ImageView next;
	private ImageView play;
	private ProgressBar playLoading;
	private FrameLayout playBox;
	private ImageView shuffle;
	private ImageView repeat;
	/** The size in dp of the shuffle and repeat buttons, wherever they sit. */
	private int extraDp = 42;
	private Seek seek;
	private TextView timeNow;
	private TextView timeAll;
	private LinearLayout seekBlock;
	private LinearLayout timesRow;
	private TextView chip;
	/** The hide / show covers button (CoverHide), beside the chip in the top right corner. */
	private ImageView eye;
	private LinearLayout topRight;
	private FrameLayout root;
	/** The card is laid out without its cover (covers hidden): the words and controls take its room. */
	private boolean layoutHidden;
	private LinearLayout controls;
	private LinearLayout texts;

	private int mode = -1;
	private int appliedW;
	private int appliedH;
	private int generation;
	@Nullable
	private PlayableItem shown;
	private long duration;
	private boolean playing;
	private boolean videoNow;
	private boolean hasArt;
	/** Nothing is loaded in a player: what shows is where the queue stopped, and play starts it as music. */
	private boolean idle;
	private boolean playLoadingShown;
	@Nullable
	private ValueAnimator playIconAnim;
	/** Until when the play button shows a spinner after a skip, whatever the session has said by then. */
	private long skipUntil;
	private boolean startedIdle;
	@Nullable
	private Bitmap shownArt;
	/** Where the shuffle and repeat buttons are: 0 not shown, 1 around the play button, 2 under the seek bar. */
	private int extrasAt;
	/** Where the user last seeked to, until the session reports it (the YouTube player is slow to). */
	private long enginePos = -1;
	private long enginePosAt;
	private long seekPos;
	private long seekAt;
	private int durationAsks;
	private Runnable chipAction = () -> {};
	private final Runnable ticker = this::tick;
	private final Runnable bufferingListener = () -> post(this::syncState);
	private final MediaSessionCallback.Listener stateListener = new MediaSessionCallback.Listener() {
		@Override
		public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
			post(MediaWidget.this::syncState);
		}
	};
	private final FermataServiceUiBinder.Listener itemListener = new FermataServiceUiBinder.Listener() {
		@Override
		public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
			post(MediaWidget.this::syncItem);
		}

		@Override
		public void onDurationChanged(PlayableItem i) {
			post(MediaWidget.this::refreshDuration);
		}
	};

	MediaWidget(HomeHost host, HomeWidgetType type) {
		super(host, type);
	}

	/**
	 * The card's first look, "Cover" (what it always was): the blurred cover is the card. Solid, Glass and Colour put the cover
	 * on the card of that look instead, dissolving into its fill (or, in Glass, into Home's blurred background).
	 */
	private static final int STYLE_COVER = 4;

	@Override
	protected int autoStyle() {
		return STYLE_COVER;
	}

	@Override
	protected int autoStyleLabel() {
		return R.string.home_style_cover;
	}

	@Override
	protected boolean hasLookOption() {
		return true;
	}

	@Override
	protected boolean ownBackground() {
		return style() == STYLE_COVER;
	}

	/** Over a cover the card is dark; in a light theme it is the Music tab's whitish wash instead. */
	@Override
	protected boolean ownBackgroundDark() {
		return !host.palette().light;
	}

	private MainActivityDelegate act() {
		return host.activity();
	}

	@Override
	protected void onCreate(FrameLayout content) {
		root = content;
		content.setPadding(0, 0, 0, 0);
		wash = new Fader(ctx, false);
		content.addView(wash, new FrameLayout.LayoutParams(-1, -1));
		art = new Fader(ctx, true);
		content.addView(art);
		thumb = new Fader(ctx, false);
		HomeImages.round(thumb, px(12));
		thumb.setBackground(HomeUi.round(0x33FFFFFF, px(12)));
		content.addView(thumb);
		scrim = new View(ctx);
		content.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

		texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		title = text(ROLE_PRIMARY, 17, true);
		title.setSingleLine(true);
		applyTitleScroll();
		artist = text(ROLE_SECONDARY, 13.5f, false);
		artist.setSingleLine(true);
		artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
		texts.addView(title);
		texts.addView(artist);
		content.addView(texts);

		badge = text(ROLE_PRIMARY, 11, true);
		badge.setText(R.string.home_media_video);
		badge.setPadding(px(9), px(4), px(9), px(4));
		badge.setVisibility(GONE);
		content.addView(badge);

		seekBlock = new LinearLayout(ctx);
		seekBlock.setOrientation(LinearLayout.VERTICAL);
		seek = new Seek();
		noGrab(seek);
		seekBlock.addView(seek, new LinearLayout.LayoutParams(-1, px(34)));
		timesRow = new LinearLayout(ctx);
		timesRow.setGravity(Gravity.CENTER_VERTICAL);
		timeNow = text(ROLE_SECONDARY, 11.5f, false);
		timeAll = text(ROLE_SECONDARY, 11.5f, false);
		timeAll.setGravity(Gravity.END);
		timesRow.addView(timeNow, new LinearLayout.LayoutParams(0, -2, 1f));
		timesRow.addView(timeAll, new LinearLayout.LayoutParams(0, -2, 1f));
		seekBlock.addView(timesRow, new LinearLayout.LayoutParams(-1, -2));
		content.addView(seekBlock);

		controls = new LinearLayout(ctx);
		controls.setOrientation(LinearLayout.HORIZONTAL);
		controls.setGravity(Gravity.CENTER_VERTICAL);
		prev = iconButton(R.drawable.prev, 44, ROLE_ICON, R.string.action_prev, this::onPrev);
		next = iconButton(R.drawable.next, 44, ROLE_ICON, R.string.action_next, this::onNext);
		playBox = new FrameLayout(ctx);
		playBox.setBackground(HomeUi.pressable(0x33000000, host.palette().playFill | 0xFF000000, px(28)));
		play = new ImageView(ctx);
		play.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		int pp = px(13);
		play.setPadding(pp, pp, pp, pp);
		play.setImageTintList(android.content.res.ColorStateList.valueOf(host.palette().playIcon));
		play.setContentDescription(ctx.getString(R.string.action_play_pause));
		play.setClickable(true);
		play.setFocusable(true);
		play.setOnClickListener(v -> {
			bounce(playBox);
			onPlayPause();
		});
		playBox.addView(play, new FrameLayout.LayoutParams(-1, -1));
		// Takes the icon's place while a song loads, like the Music tab's play button.
		playLoading = new ProgressBar(ctx);
		playLoading.setIndeterminate(true);
		playLoading.setIndeterminateTintList(ColorStateList.valueOf(host.palette().playIcon));
		playLoading.setVisibility(GONE);
		playBox.addView(playLoading, new FrameLayout.LayoutParams(px(30), px(30), Gravity.CENTER));
		shuffle = iconButton(R.drawable.shuffle, 42, ROLE_ICON, R.string.shuffle, this::onShuffle);
		repeat = iconButton(R.drawable.repeat, 42, ROLE_ICON, R.string.repeat, this::onRepeat);
		controls.addView(prev);
		controls.addView(playBox, new LinearLayout.LayoutParams(px(52), px(52)));
		controls.addView(next);
		content.addView(controls);

		chip = chip(R.drawable.video_fullscreen, "", () -> chipAction.run());
		// The same colour as the chip beside it (its text and icon), not the card's dimmer icon role.
		eye = iconButton(R.drawable.visibility, 36, ROLE_PRIMARY, R.string.action_cover_hide,
				() -> me.aap.fermata.ui.view.CoverHide.toggle(act(), false));
		// The chip's own soft fill, kept in step with it when the look changes.
		trackSoft(eye, 18);
		topRight = new LinearLayout(ctx);
		topRight.setOrientation(LinearLayout.HORIZONTAL);
		topRight.setGravity(Gravity.CENTER_VERTICAL);
		topRight.addView(eye, new LinearLayout.LayoutParams(px(36), px(36)));
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, px(36));
		clp.setMarginStart(px(8));
		topRight.addView(chip, clp);
		content.addView(topRight);

		artBusy = spinner(20);
		artBusy.setVisibility(GONE);
		content.addView(artBusy, lp(px(20), px(20), Gravity.TOP | Gravity.END, 0, px(12), px(12), 0));
		applyLook();
	}

	@Override
	protected void onStyleChanged() {
		applyLook();
	}

	/** What follows the look: the wash is the card only in Cover; the badge, chip and scrim follow the card's darkness. */
	private void applyLook() {
		if (wash == null) return;
		wash.setVisibility(ownBackground() ? VISIBLE : GONE);
		boolean dark = lightText();
		badge.setBackground(HomeUi.round(dark ? 0x88000000 : 0x26000000, px(10)));
		// The eye beside it wears the very same fill, so the two read as a pair.
		chip.setBackground(HomeUi.pressable(dark ? 0x33FFFFFF : host.palette().chipRipple,
				dark ? 0x44000000 : host.palette().chipFill, px(17)));
		eye.setBackground(HomeUi.pressable(dark ? 0x33FFFFFF : host.palette().chipRipple,
				dark ? 0x44000000 : host.palette().chipFill, px(18)));
		if (mode >= 0) scrim.setBackground(scrimFor(mode));
		seek.invalidate();
		invalidate();
	}


	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.sw("seek", ctx.getString(R.string.home_opt_seek), true));
		l.add(HomeOption.sw("chip", ctx.getString(R.string.home_opt_video_chip), true));
		l.add(HomeOption.sw("eye", ctx.getString(R.string.home_opt_cover_chip), true));
		l.add(HomeOption.sw("marquee", ctx.getString(R.string.home_opt_title_scroll), false));
		return l;
	}

	/**
	 * A long title ends in an ellipsis unless "Scroll a long title" is on: a scrolling title redraws the screen every
	 * frame while it moves, which at a high refresh rate is a real cost on Home (the Music tab holds 60 Hz for that).
	 */
	private void applyTitleScroll() {
		boolean scroll = optBool("marquee", false);
		if (title.getEllipsize() != null && scroll == (title.getEllipsize() == android.text.TextUtils.TruncateAt.MARQUEE)) return;
		if (scroll) {
			title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
			title.setMarqueeRepeatLimit(2);
			title.setHorizontallyScrolling(true);
			title.setSelected(true);
		} else {
			title.setSelected(false);
			title.setHorizontallyScrolling(false);
			title.setEllipsize(android.text.TextUtils.TruncateAt.END);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Size

	@Override
	protected void onResized(int w, int h) {
		if (thumb == null) return;
		int m;
		if ((w * h) <= 2) m = STRIP_SMALL;
		else if (h == 1) m = STRIP_WIDE;
		else if ((w * h) >= 6 || ((w >= 3) && (h >= 3))) m = (w >= 3) ? LARGE_WIDE : LARGE_TALL;
		else m = MEDIUM;
		mode = m;
		applyMode();
	}

	@Override
	protected void onSized(int w, int h) {
		if ((thumb != null) && ((w != appliedW) || (h != appliedH))) applyMode();
	}

	private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
		FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
		p.setMargins(l, t, r, b);
		return p;
	}

	private void applyMode() {
		if (mode < 0) return;
		int w = Math.max(widthPx(), px(160));
		int h = Math.max(heightPx(), px(120));
		appliedW = widthPx();
		appliedH = heightPx();
		boolean strip = (mode == STRIP_SMALL) || (mode == STRIP_WIDE);
		boolean large = (mode == LARGE_WIDE) || (mode == LARGE_TALL);
		boolean showSeek = large && optBool("seek", true);
		boolean showChip = large && optBool("chip", true);
		// The hide / show covers button, and the card without its cover while covers are hidden.
		boolean showEye = !strip && optBool("eye", true);
		boolean hid = layoutHidden;
		applyTitleScroll();
		int pad = px(14);

		// The cover is a square that keeps its shape at any size, dissolving into the blurred wash
		// beside or below it (a stretched or cropped cover looked wrong at some sizes).
		int artSide = (mode == LARGE_WIDE) ? Math.min(h, (int) (w * 0.58f)) : Math.min(w, (int) (h * 0.6f));
		int left = (mode == LARGE_WIDE) ? (hid ? pad : Math.max((int) (artSide * 0.86f), (int) (w * 0.38f))) : 0;
		// A wide card that is short on room beside the cover (4x2 on a phone, ~380 x 185dp): the words and controls start
		// further left over the cover's fading side, and the chip is only its icon, or the name had ~30dp left.
		boolean tight = (mode == LARGE_WIDE) && !hid && ((w - left) < px(TIGHT_DP));
		if (tight) left = Math.min(left, (int) (w * 0.30f));
		narrowChip = tight;

		// Shuffle and repeat: around the play button when the width allows, else under the seek bar. A wide card
		// has the controls in the middle of the right half, as large as that half allows.
		int zone = ((mode == LARGE_WIDE) ? (w - left) : w) - 2 * pad;
		int ex = 42;
		int sk = 44;
		int pl = 52;
		if (mode == LARGE_WIDE) {
			if (zone >= px(250)) {
				ex = 46;
				sk = 50;
				pl = 62;
			} else if (zone >= px(212)) {
				ex = 40;
				sk = 44;
				pl = 56;
			}
		}
		// A narrow wide card (a phone at a larger display size: ~208dp beside the cover) shrinks the round buttons a
		// little so shuffle and repeat still fit beside play, rather than going under the seek bar, which made that
		// block taller and put it under the play button.
		if ((mode == LARGE_WIDE) && (px(ex * 2 + sk * 2 + pl) > zone)) {
			float f = Math.max(0.78f, zone / (float) px(ex * 2 + sk * 2 + pl));
			ex = Math.round(ex * f);
			sk = Math.round(sk * f);
			pl = Math.round(pl * f);
		}
		int controlsNeed = px(ex) * 2 + px(sk) * 2 + px(pl);
		if (!large) extrasAt = 0;
		else if (zone >= controlsNeed) extrasAt = 1;
		else extrasAt = showSeek ? 2 : 0;
		// Wide: the controls sit between the name (top) and the seek block (bottom), never over either; on a short card
		// they shrink to the room left between them.
		int wideTop = pad + px(4) + px(TEXTS_DP);
		// (The seek row is 34dp with the bar in its middle: the controls may come into its empty top part.)
		int wideBottom = px(12) + (showSeek ? px(26) + ((extrasAt == 2) ? px(ex) : px(18)) : 0);
		if (mode == LARGE_WIDE) {
			int room = h - wideTop - wideBottom;
			if ((room > 0) && (px(pl) > room)) {
				float f = Math.max(0.7f, room / (float) px(pl));
				ex = Math.round(ex * f);
				sk = Math.round(sk * f);
				pl = Math.round(pl * f);
				controlsNeed = px(ex) * 2 + px(sk) * 2 + px(pl);
			}
		}
		extraDp = ex;
		sizeControls(ex, sk, pl);
		placeExtras();

		thumb.setVisibility(strip && !hid ? VISIBLE : GONE);
		art.setVisibility(strip || hid ? GONE : VISIBLE);
		art.setFade(mode == LARGE_WIDE ? FadeImageView.RIGHT : FadeImageView.BOTTOM);
		artBusy.setVisibility(strip ? GONE : artBusy.getVisibility());
		prev.setVisibility((mode == STRIP_SMALL) ? GONE : VISIBLE);
		next.setVisibility((mode == STRIP_SMALL) ? GONE : VISIBLE);
		seekBlock.setVisibility(showSeek ? VISIBLE : GONE);
		chip.setVisibility(showChip ? VISIBLE : GONE);
		eye.setVisibility(showEye ? VISIBLE : GONE);
		topRight.setVisibility(showChip || showEye ? VISIBLE : GONE);
		syncEye();
		// The chip's start margin only when the eye is beside it.
		if (chip.getLayoutParams() instanceof LinearLayout.LayoutParams clp) {
			int ms = showEye ? px(8) : 0;
			if (clp.getMarginStart() != ms) {
				clp.setMarginStart(ms);
				chip.setLayoutParams(clp);
			}
		}
		int thumbRoom = hid ? 0 : px(44) + px(10);
		badge.setVisibility(large && videoNow ? VISIBLE : GONE);
		title.setTextSize(strip ? 15 : large ? 20 : 16);
		artist.setTextSize(strip ? 12.5f : 14);
		title.setSingleLine(true);
		texts.setGravity(strip ? Gravity.CENTER_VERTICAL : Gravity.START);
		int seekRoom = (extrasAt == 2) ? 150 : 134;

		switch (mode) {
			case STRIP_SMALL -> {
				thumb.setLayoutParams(lp(px(44), px(44), Gravity.START | Gravity.CENTER_VERTICAL, pad, 0, 0, 0));
				texts.setLayoutParams(lp(-1, -2, Gravity.CENTER_VERTICAL, pad + thumbRoom, 0, px(60), 0));
				controls.setLayoutParams(lp(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, px(8), 0));
			}
			case STRIP_WIDE -> {
				thumb.setLayoutParams(lp(px(44), px(44), Gravity.START | Gravity.CENTER_VERTICAL, pad, 0, 0, 0));
				texts.setLayoutParams(lp(-1, -2, Gravity.CENTER_VERTICAL, pad + thumbRoom, 0, px(160), 0));
				controls.setLayoutParams(lp(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, px(8), 0));
			}
			case MEDIUM -> {
				art.setLayoutParams(lp(-1, -1, Gravity.TOP, 0, 0, 0, 0));
				texts.setLayoutParams(lp(-1, -2, Gravity.BOTTOM, pad, 0, pad, px(66)));
				controls.setLayoutParams(lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, px(8)));
			}
			case LARGE_WIDE -> {
				art.setLayoutParams(lp(artSide, -1, Gravity.START, 0, 0, 0, 0));
				// The name on top (clear of the chip), the controls in the middle of the right half, the
				// seek bar under them.
				// The words' chip needs about 132dp beside the name, the icon-only one (car screen, low density) about 48dp.
				int chipRoom = (showChip ? px(chipIconOnly() ? 48 : 132) : 0) + (showEye ? px(44) : 0);
				// Without the cover the VIDEO badge sits over the name's place: the name goes under it.
				int badgeRoom = (hid && large && videoNow) ? px(26) : 0;
				texts.setLayoutParams(lp(-1, -2, Gravity.TOP, left, pad + px(4) + badgeRoom, pad + chipRoom, 0));
				seekBlock.setLayoutParams(lp(-1, -2, Gravity.BOTTOM, left, 0, pad, px(12)));
				int cw = (extrasAt == 1) ? controlsNeed : (px(sk) * 2 + px(pl));
				int cx = left + Math.max(0, (zone - cw) / 2);
				// Centred in the room between the name and the seek block (the whole card's middle overlapped the
				// seek bar on a phone).
				int top = wideTop + badgeRoom;
				int bottom = Math.min(wideBottom, Math.max(0, h - top - px(pl)));
				controls.setLayoutParams(lp(-2, -2, Gravity.CENTER_VERTICAL | Gravity.START, cx, top, 0, bottom));
				topRight.setLayoutParams(lp(-2, px(36), Gravity.TOP | Gravity.END, 0, pad, pad, 0));
				badge.setLayoutParams(lp(-2, -2, Gravity.TOP | Gravity.START, pad, pad, 0, 0));
			}
			default -> {
				art.setLayoutParams(lp(-1, artSide, Gravity.TOP, 0, 0, 0, 0));
				texts.setLayoutParams(lp(-1, -2, Gravity.BOTTOM, pad, 0, pad, px(showSeek ? seekRoom : 82)));
				seekBlock.setLayoutParams(lp(-1, -2, Gravity.BOTTOM, pad, 0, pad, px(72)));
				controls.setLayoutParams(lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, px(10)));
				topRight.setLayoutParams(lp(-2, px(36), Gravity.TOP | Gravity.END, 0, pad, pad, 0));
				badge.setLayoutParams(lp(-2, -2, Gravity.TOP | Gravity.START, pad, pad, 0, 0));
			}
		}
		scrim.setBackground(scrimFor(mode));
		wash.setAlpha(strip ? 0.92f : 1f);
		syncState();
	}

	/** The control buttons' sizes in dp: the round ones around the play button and the play button itself. */
	private void sizeControls(int ex, int sk, int pl) {
		for (ImageView b : new ImageView[]{shuffle, repeat}) sizeButton(b, ex);
		for (ImageView b : new ImageView[]{prev, next}) sizeButton(b, sk);
		LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) playBox.getLayoutParams();
		if (lp.width != px(pl)) {
			lp.width = px(pl);
			lp.height = px(pl);
			playBox.setLayoutParams(lp);
			playBox.setBackground(HomeUi.pressable(0x33000000, host.palette().playFill | 0xFF000000, px(pl) / 2f));
		}
	}

	private void sizeButton(ImageView b, int dp) {
		LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
		if (lp.width == px(dp)) return;
		lp.width = px(dp);
		lp.height = px(dp);
		b.setLayoutParams(lp);
		int pd = px(dp * 0.26f);
		b.setPadding(pd, pd, pd, pd);
		b.setBackground(HomeUi.pressable(ripple(), 0, px(dp) / 2f));
	}

	/** Puts the shuffle and repeat buttons where {@link #extrasAt} says (or takes them out). */
	private void placeExtras() {
		boolean row = extrasAt == 2;
		for (ImageView b : new ImageView[]{shuffle, repeat}) {
			if (b.getParent() instanceof ViewGroup p) p.removeView(b);
		}
		if (extrasAt == 1) {
			controls.addView(shuffle, 0);
			controls.addView(repeat);
		} else if (row) {
			// Under the seek bar, between the two times.
			timesRow.addView(shuffle, 1, new LinearLayout.LayoutParams(px(extraDp), px(extraDp)));
			timesRow.addView(repeat, 2, new LinearLayout.LayoutParams(px(extraDp), px(extraDp)));
		}
		updateModes();
	}

	@Nullable
	private GradientDrawable scrimFor(int m) {
		GradientDrawable g = new GradientDrawable();
		// Black over a cover in a dark theme; white (the Music tab's whitish wash) in a light one.
		int c = lightText() ? 0x000000 : 0xFFFFFF;
		if (!ownBackground()) {
			// On a card of its own look the words sit on the card's fill, beside or under the cover; only where they
			// lie over the cover (the medium card) does it darken (or lighten) under them.
			if (m != MEDIUM) return null;
			g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
			g.setColors(new int[]{c, 0x11000000 | c, 0xAA000000 | c});
			return g;
		}
		if (m == LARGE_WIDE) {
			g.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
			g.setColors(new int[]{c, 0x33000000 | c, 0x88000000 | c});
		} else if ((m == STRIP_SMALL) || (m == STRIP_WIDE)) {
			g.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
			g.setColors(new int[]{0x55000000 | c, 0x77000000 | c});
		} else {
			g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
			g.setColors(new int[]{c, 0x22000000 | c, 0xCC000000 | c});
		}
		return g;
	}

	// ---------------------------------------------------------------------------------------------
	// Listening

	@Override
	protected void onActive() {
		MainActivityDelegate a = act();
		a.getMediaSessionCallback().addBroadcastListener(stateListener);
		a.getMediaServiceBinder().addBroadcastListener(itemListener);
		BufferingIndicator.addListener(bufferingListener);
		a.getPrefs().addBroadcastListener(coverHideListener);
		syncItem();
		// Hide cover switched while Home was away (on the Music tab): the cover follows at once, not
		// only with the next song.
		if ((shownArt != null) && (artHidden != me.aap.fermata.ui.view.CoverHide.isOn())) setArt(shownArt);
		syncState();
	}

	@Override
	protected void onInactive() {
		MainActivityDelegate a = act();
		a.getMediaSessionCallback().removeBroadcastListener(stateListener);
		a.getMediaServiceBinder().removeBroadcastListener(itemListener);
		BufferingIndicator.removeListener(bufferingListener);
		a.getPrefs().removeBroadcastListener(coverHideListener);
		removeCallbacks(ticker);
	}

	@Override
	protected void onBind() {
		if (title != null) applyTitleScroll();
		if (mode >= 0) applyMode();
	}

	// ---------------------------------------------------------------------------------------------
	// What plays

	/** The item shown changed: title, artist, cover. */
	private void syncItem() {
		if (title == null) return;
		MainActivityDelegate a = act();
		PlayableItem pi = HomeArt.displayItem(a);
		boolean idleNow = (pi != null) && HomeArt.isIdle(a);
		if (pi == null) {
			shown = null;
			idle = false;
			generation++;
			swap(title, ctx.getString(R.string.home_media_idle));
			swap(artist, ctx.getString(R.string.home_media_idle_sub));
			setBusy(false);
			setArt(null);
			duration = 0;
			syncState();
			return;
		}
		idle = idleNow;
		if (HomeArt.same(pi, shown)) {
			// The player may hand out another object for the same song (a seek does): nothing to redraw.
			shown = pi;
			syncState();
			return;
		}
		me.aap.fermata.util.DiagnosticLog.log("MUSIC", "home media: new item", "from=" + shown, "to=" + pi);
		shown = pi;
		// The new song is up: the spinner after a skip is the session's to show from here.
		if (cbState() == PlaybackStateCompat.STATE_PLAYING) skipUntil = 0;
		int gen = ++generation;
		duration = 0;
		enginePos = -1;
		swap(title, pi.getName());
		swap(artist, pi instanceof MusicTrackItem t && t.getArtistName() != null ? MusicTrackItem.cleanArtist(t.getArtistName()) : "");
		if (title.getEllipsize() == android.text.TextUtils.TruncateAt.MARQUEE) title.setSelected(true);
		setBusy(true);
		pi.getMediaData().main().onCompletion((md, err) -> {
			if (gen != generation) return;
			if (md == null) {
				setBusy(false);
				return;
			}
			String t = md.getString(METADATA_KEY_TITLE);
			if ((t == null) || t.isEmpty()) t = md.getString(METADATA_KEY_DISPLAY_TITLE);
			if ((t != null) && !t.isEmpty()) swap(title, t);
			swap(artist, artistOf(pi, md));
			duration = md.getLong(METADATA_KEY_DURATION);
			HomeArt.cover(act(), pi, md, () -> gen == generation, bm -> {
				if (gen == generation) setArt(bm);
			});
			syncState();
		});
		syncState();
	}

	/** The line under the title, as the Music tab has it: the artist, else the album, else where it is from. */
	private String artistOf(PlayableItem pi, MediaMetadataCompat md) {
		String ar = md.getString(METADATA_KEY_ARTIST);
		if ((ar == null) || ar.isEmpty()) ar = md.getString(METADATA_KEY_ALBUM_ARTIST);
		if ((ar == null) || ar.isEmpty()) ar = md.getString(METADATA_KEY_ALBUM);
		// "Artist - Topic" shows as "Artist", as on the Music tab and in the Home lists.
		if ((ar != null) && !ar.isEmpty()) return MusicTrackItem.cleanArtist(ar);
		if (pi instanceof MusicTrackItem t) {
			String name = t.getArtistName();
			if ((name != null) && !name.isEmpty()) return MusicTrackItem.cleanArtist(name);
			if (t.getVideoId() != null) return "YouTube";
		}
		MediaEngine eng = act().getMediaSessionCallback().getEngine();
		if ((eng != null) && (eng.getId() == me.aap.fermata.media.pref.MediaPrefs.MEDIA_ENG_YT) &&
				(pi == eng.getSource())) {
			return "YouTube";
		}
		BrowsableItem parent = pi.getParent();
		return ((parent == null) || parent.isExternal()) ? "" : parent.getName();
	}

	private int cbState() {
		return act().getMediaSessionCallback().getPlaybackState().getState();
	}

	/** The duration may only become known after the item (the YouTube player says it late). */
	private void refreshDuration() {
		PlayableItem pi = shown;
		if (pi == null) return;
		pi.getMediaData().main().onSuccess(md -> {
			if ((md != null) && HomeArt.same(pi, shown) && (md.getLong(METADATA_KEY_DURATION) > 0)) {
				duration = md.getLong(METADATA_KEY_DURATION);
				tick();
			}
		});
	}

	/**
	 * Changes a text, the new one fading and settling in. The text is set at once: it can't be lost
	 * to an animation that was cut short.
	 */
	private void swap(TextView t, CharSequence s) {
		if (s.toString().contentEquals(t.getText())) return;
		t.animate().cancel();
		t.setText(s);
		if (!t.isAttachedToWindow() || !isShown() || !host.animate()) {
			t.setAlpha(1f);
			t.setTranslationY(0f);
			return;
		}
		t.setAlpha(0f);
		t.setTranslationY(px(5));
		t.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(new DecelerateInterpolator()).start();
	}

	/** The little spinner in the card's corner is gone for good: the cover simply arrives. */
	private void setBusy(boolean on) {
		artBusy.setVisibility(GONE);
	}

	/** The eye button: struck out while covers are hidden. */
	private void syncEye() {
		if (eye == null) return;
		boolean on = me.aap.fermata.ui.view.CoverHide.isOn();
		int icon = on ? R.drawable.cover_hidden : R.drawable.visibility;
		if (Integer.valueOf(icon).equals(eye.getTag())) return;
		eye.setTag(icon);
		eye.setImageResource(icon);
		eye.setContentDescription(ctx.getString(on ? R.string.cover_hidden_chip : R.string.cover_shown_chip));
	}

	/** Lays the card out again for the cover shown or not, the words and controls gliding to their places. */
	private void relayout() {
		if ((root == null) || (mode < 0)) return;
		if (root.isLaidOut() && isShown() && host.animate()) {
			android.transition.AutoTransition t = new android.transition.AutoTransition();
			t.setDuration(320);
			t.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
			android.transition.TransitionManager.beginDelayedTransition(root, t);
		}
		applyMode();
	}

	/** Hide cover (CoverHide) turned on or off while the card is up: the cover shown again, as it now should be. */
	private final me.aap.utils.pref.PreferenceStore.Listener coverHideListener = (store, prefs) -> {
		if (!prefs.contains(me.aap.fermata.ui.activity.MainActivityPrefs.COVER_HIDDEN)) return;
		if (shownArt != null) setArt(shownArt);
		else syncEye();
	};
	/** Whether the cover on screen is the hidden one (colours only). */
	private boolean artHidden;

	/** Shows the cover, or the card's own colours when there is none; the old one fades into it. */
	private void setArt(@Nullable Bitmap bm) {
		hasArt = bm != null;
		setBusy(false);
		boolean hide = me.aap.fermata.ui.view.CoverHide.isOn();
		// The same cover again (a seek asks for it again): nothing to fade.
		if ((bm != null) && (bm == shownArt) && (hide == artHidden)) return;
		shownArt = bm;
		artHidden = hide;
		boolean lh = hide && (bm != null);
		if (lh != layoutHidden) {
			layoutHidden = lh;
			relayout();
		} else {
			syncEye();
		}
		if (bm == null) {
			GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
					ownBackgroundDark() ?
							new int[]{HomeUi.shade(type.accent, 0.7f), HomeUi.shade(type.accent, 0.3f)} :
							new int[]{HomeUi.mix(type.accent, 0xFFFFFFFF, 0.7f), HomeUi.mix(type.accent, 0xFFFFFFFF, 0.5f)});
			wash.set(g);
			art.set(null);
			thumb.setIcon(true);
			thumb.set(ContextCompat.getDrawable(ctx, R.drawable.music_note_large));
			thumb.tint(0x99FFFFFF);
			return;
		}
		// The blurred wash: a small copy, really blurred (a plain tiny copy shows as blocks).
		int sw = 96;
		int sh = Math.max(8, Math.round(sw * (float) bm.getHeight() / bm.getWidth()));
		Bitmap small = Bitmap.createScaledBitmap(bm, sw, sh, true);
		Bitmap soft = HomeBlur.blur(small, 70);
		Drawable d = new BitmapDrawable(getResources(), hide ? me.aap.fermata.ui.view.CoverHide.veil(bm) :
				(soft != null) ? soft : small);
		wash.set(d);
		// Covers hidden: the cover's colours only, on the card and in the thumbnail.
		Bitmap shownBm = hide ? me.aap.fermata.ui.view.CoverHide.veil(bm) : bm;
		art.set(new BitmapDrawable(getResources(), shownBm), HomeArt.isFrame(shownBm) ? HomeArt.FRAME_FADE_MS : 520);
		thumb.tint(0);
		thumb.setIcon(false);
		thumb.set(new BitmapDrawable(getResources(), shownBm));
	}

	private static boolean isBusy(int st) {
		return (st == PlaybackStateCompat.STATE_CONNECTING) || (st == PlaybackStateCompat.STATE_BUFFERING) ||
				(st == PlaybackStateCompat.STATE_SKIPPING_TO_NEXT) ||
				(st == PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS) ||
				(st == PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM);
	}

	/** Play state, the video chip and the seek bar follow the session. */
	private void syncState() {
		if (play == null) return;
		MainActivityDelegate a = act();
		MediaSessionCallback cb = a.getMediaSessionCallback();
		PlaybackStateCompat st = cb.getPlaybackState();
		int s = st.getState();
		boolean nowPlaying = (s == PlaybackStateCompat.STATE_PLAYING) || (s == PlaybackStateCompat.STATE_BUFFERING);
		if (nowPlaying != playing) {
			// Counted on from the player's last answer only while it plays on: asked again after a pause or a start.
			enginePos = -1;
			enginePosAt = 0;
		}
		playing = nowPlaying;
		play.setImageResource((s == PlaybackStateCompat.STATE_PLAYING) ? R.drawable.pause : R.drawable.play);
		// Also while playing but stalled for data, and right after a skip until the next song has started.
		// A start from nothing is over once the session plays.
		if (startedIdle && (s == PlaybackStateCompat.STATE_PLAYING)) {
			startedIdle = false;
			skipUntil = 0;
		}
		boolean skipping = SystemClock.elapsedRealtime() < skipUntil;
		// Starting from nothing shows no spinner at all: the button turns to pause when the song is up.
		setPlayLoading(isBusy(s) || ((s == PlaybackStateCompat.STATE_PLAYING) && BufferingIndicator.isBuffering())
				|| skipping);
		if (skipping) {
			removeCallbacks(skipEnd);
			postDelayed(skipEnd, Math.max(50, skipUntil - SystemClock.elapsedRealtime() + 50));
		}
		boolean none = shown == null;
		prev.setAlpha(none ? 0.4f : 1f);
		next.setAlpha(none ? 0.4f : 1f);
		// Dimmed and dead on the first / last song of the queue (a list) with Repeat off, as on the Music tab.
		boolean playingNow = !none && (cb.getCurrentItem() != null);
		FermataServiceUiBinder.setSkipAvailable(prev, !(playingNow && cb.isAtListStart()));
		FermataServiceUiBinder.setSkipAvailable(next, !(playingNow && cb.isAtListEnd()));
		play.setAlpha(1f);

		videoNow = a.isVideoPlaying();
		boolean canWatch = MusicPlayer.canSwitchToVideo(cb);
		if (idle || none) {
			if (MusicPlayer.isEnabled()) {
				setChip(R.drawable.music, R.string.home_media_open_music, () -> host.open(R.id.music_addon));
			} else {
				setChip(me.aap.utils.R.drawable.folder, R.string.home_media_open_library,
						() -> host.open(R.id.folders_fragment));
			}
		} else if (videoNow) {
			setChip(R.drawable.video_fullscreen, R.string.home_media_fullscreen,
					() -> host.run(Action.FULLSCREEN_TOGGLE));
		} else if (canWatch) {
			setChip(R.drawable.video, R.string.home_media_watch, () -> host.run(Action.PLAY_AS_MUSIC));
		} else if (MusicPlayer.isEnabled()) {
			setChip(R.drawable.music, R.string.home_media_open_music, () -> host.open(R.id.music_addon));
		} else {
			setChip(R.drawable.go_to_current, R.string.got_to_current, a::goToCurrent);
		}
		boolean large = (mode == LARGE_WIDE) || (mode == LARGE_TALL);
		badge.setVisibility(large && videoNow ? VISIBLE : GONE);
		seek.setAlpha(idle ? 0.55f : 1f);
		updateModes();

		removeCallbacks(ticker);
		tick();
	}

	private final Runnable skipEnd = this::syncState;

	/**
	 * While loading or buffering, a spinner takes the place of the play icon: the two cross-fade, the
	 * spinner growing in a little, as on the Music tab's play button.
	 */
	private void setPlayLoading(boolean busy) {
		if (busy == playLoadingShown) return;
		playLoadingShown = busy;
		playLoading.animate().cancel();
		if (playIconAnim != null) playIconAnim.cancel();
		if (!host.animate() || !isShown()) {
			play.setImageAlpha(busy ? 0 : 255);
			playLoading.setAlpha(1f);
			playLoading.setScaleX(1f);
			playLoading.setScaleY(1f);
			playLoading.setVisibility(busy ? VISIBLE : GONE);
			return;
		}
		ValueAnimator ia = ValueAnimator.ofInt(play.getImageAlpha(), busy ? 0 : 255);
		ia.setDuration(220);
		ia.setInterpolator(new DecelerateInterpolator());
		ia.addUpdateListener(v -> play.setImageAlpha((int) v.getAnimatedValue()));
		ia.start();
		playIconAnim = ia;
		if (busy) {
			if (playLoading.getVisibility() != VISIBLE) {
				playLoading.setAlpha(0f);
				playLoading.setScaleX(0.6f);
				playLoading.setScaleY(0.6f);
				playLoading.setVisibility(VISIBLE);
			}
			playLoading.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
					.setInterpolator(new DecelerateInterpolator()).start();
		} else {
			playLoading.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(220)
					.setInterpolator(new DecelerateInterpolator())
					.withEndAction(() -> playLoading.setVisibility(GONE)).start();
		}
	}

	/** Low density (the car screen's) makes the chip's words as big as the card's title: only its icon shows there. */
	private boolean chipIconOnly() {
		return narrowChip || host.isCar() || (ctx.getResources().getDisplayMetrics().density < 1.5f);
	}

	private void setChip(int icon, int label, Runnable action) {
		// Low density (the car screen's) makes the words as big as the card's own title: only the icon there,
		// the name stays as its description.
		boolean iconOnly = chipIconOnly();
		chip.setContentDescription(ctx.getString(label));
		if (iconOnly) {
			chip.setText("");
			chip.setCompoundDrawablePadding(0);
			chip.setPadding(px(11), 0, px(11), 0);
			chip.setMinWidth(px(36));
			chip.setGravity(Gravity.CENTER);
		} else {
			chip.setText(label);
			chip.setCompoundDrawablePadding(px(6));
			chip.setPadding(px(12), 0, px(14), 0);
			chip.setMinWidth(0);
			chip.setGravity(Gravity.CENTER_VERTICAL);
		}
		chip.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
		chipAction = action;
	}

	// ---------------------------------------------------------------------------------------------
	// Shuffle and repeat live on the playing item's container (the music queue, or its folder), as
	// on the Music tab.

	@Nullable
	private PlayableItem modeItem() {
		MediaSessionCallback cb = act().getMediaSessionCallback();
		MusicTrackItem t = MusicPlayer.getCurrentTrack(cb);
		return (t != null) ? t : cb.getCurrentItem();
	}

	@Nullable
	private BrowsableItemPrefs modePrefs() {
		PlayableItem i = modeItem();
		if (i != null) return i.getParent().getPrefs();
		MusicQueue q = MusicPlayer.getQueue(act());
		return (q != null) ? q.getPrefs() : null;
	}

	private void updateModes() {
		if ((shuffle == null) || (extrasAt == 0)) return;
		BrowsableItemPrefs p = modePrefs();
		boolean shuffleOn = (p != null) && p.getShufflePref();
		PlayableItem i = modeItem();
		boolean one = (p != null) && (i != null) && i.getId().equals(p.getRepeatItemPref());
		boolean all = (p != null) && p.getRepeatPref();
		shuffle.setImageResource(shuffleOn ? R.drawable.shuffle_filled : R.drawable.shuffle);
		shuffle.setAlpha(shuffleOn ? 1f : 0.55f);
		repeat.setImageResource(one ? R.drawable.repeat_one : (all ? R.drawable.repeat_filled : R.drawable.repeat));
		repeat.setAlpha((one || all) ? 1f : 0.55f);
	}

	private void onShuffle() {
		MediaSessionCallback cb = act().getMediaSessionCallback();
		BrowsableItemPrefs p = modePrefs();
		if (p == null) return;
		boolean enable = !p.getShufflePref();
		PlayableItem i = modeItem();
		// Through the session when it is the session's own item, so its shuffle state follows.
		if ((i != null) && (i == cb.getCurrentItem())) {
			cb.onSetShuffleMode(enable ? PlaybackStateCompat.SHUFFLE_MODE_ALL : PlaybackStateCompat.SHUFFLE_MODE_NONE);
		} else {
			p.setShufflePref(enable);
		}
		updateModes();
	}

	/** Cycles off, repeat the whole queue/folder, repeat the current song. */
	private void onRepeat() {
		MediaSessionCallback cb = act().getMediaSessionCallback();
		PlayableItem i = modeItem();
		BrowsableItemPrefs p = modePrefs();
		if (p == null) return;
		boolean one = (i != null) && i.getId().equals(p.getRepeatItemPref());
		boolean all = p.getRepeatPref();
		int m;
		if (one) m = PlaybackStateCompat.REPEAT_MODE_NONE;
		else if (all) m = (i != null) ? PlaybackStateCompat.REPEAT_MODE_ONE : PlaybackStateCompat.REPEAT_MODE_NONE;
		else m = PlaybackStateCompat.REPEAT_MODE_ALL;
		if ((i != null) && (i == cb.getCurrentItem())) {
			cb.onSetRepeatMode(m);
		} else {
			p.setRepeatItemPref(((i != null) && (m == PlaybackStateCompat.REPEAT_MODE_ONE)) ? i.getId() : null);
			p.setRepeatPref(m == PlaybackStateCompat.REPEAT_MODE_ALL);
		}
		updateModes();
		TopToast.show(R.drawable.repeat, (m == PlaybackStateCompat.REPEAT_MODE_ONE) ? R.string.music_repeat_one :
				(m == PlaybackStateCompat.REPEAT_MODE_ALL) ? R.string.music_repeat_all : R.string.music_repeat_off);
	}

	// ---------------------------------------------------------------------------------------------
	// Position

	/** The position now, extrapolated from the session's last report; where playback stopped when idle. */
	private long position() {
		MainActivityDelegate a = act();
		if (idle) {
			if (shown instanceof MusicTrackItem) {
				MusicQueue q = MusicPlayer.getQueue(a);
				return (q != null) ? q.getSavedPosition() : 0;
			}
			return a.getMediaSessionCallback().getResumePosition();
		}
		PlaybackStateCompat st = a.getMediaSessionCallback().getPlaybackState();
		// Just seeked: the session may not have heard of it yet, so go on from where the user put it.
		long since = SystemClock.elapsedRealtime() - seekAt;
		if ((seekAt > 0) && (since < 3000)) {
			long reported = st.getPosition();
			if (Math.abs(reported - seekPos) > 2500) return Math.max(0, seekPos + (playing ? since : 0));
		}
		long now = SystemClock.elapsedRealtime();
		if ((enginePos >= 0) && ((now - enginePosAt) < ENGINE_TRUST_MS)) {
			return enginePos + (playing ? (now - enginePosAt) : 0);
		}
		long pos = st.getPosition();
		if (st.getState() == PlaybackStateCompat.STATE_PLAYING) {
			pos += (long) ((now - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
		}
		return Math.max(0, pos);
	}

	/**
	 * The player's own word on where it is and how long the item is: the session's state and the item's
	 * metadata lag or lack both for YouTube (its player says them late), which left the bar stuck at 0
	 * and not answering a touch (it needs a length to turn a position into a time).
	 */
	private void pollEngine() {
		MediaEngine eng = act().getMediaSessionCallback().getEngine();
		PlayableItem pi = shown;
		if ((eng == null) || (pi == null)) return;
		// For YouTube each ask is a script run in the page while it plays: asked every few seconds, not every tick (the
		// position in between is counted on from the last answer).
		if (playing && ((SystemClock.elapsedRealtime() - enginePosAt) >= ENGINE_POLL_MS)) {
			eng.getPosition().main().onSuccess(p -> {
				if ((p != null) && (p >= 0) && HomeArt.same(pi, shown)) {
					enginePos = p;
					enginePosAt = SystemClock.elapsedRealtime();
				}
			});
		}
		if (duration <= 0) {
			eng.getDuration().main().onSuccess(d -> {
				if ((d != null) && (d > 0) && (duration <= 0) && HomeArt.same(pi, shown)) {
					duration = d;
					tick();
				}
			});
		}
	}

	private void tick() {
		if (seek == null) return;
		removeCallbacks(ticker);
		boolean large = (mode == LARGE_WIDE) || (mode == LARGE_TALL);
		if (large && (seekBlock.getVisibility() == VISIBLE)) {
			// A video whose length isn't known yet: asked again now and then.
			if ((duration <= 0) && (shown != null) && ((durationAsks++ % 4) == 0)) refreshDuration();
			pollEngine();
			long pos = position();
			if (duration > 0) pos = Math.min(pos, duration);
			if (!seek.isTouching()) seek.setFraction((duration > 0) ? (float) pos / duration : 0f);
			timeNow.setText(DateUtils.formatElapsedTime(pos / 1000));
			timeAll.setText((duration > 0) ? DateUtils.formatElapsedTime(duration / 1000) : "");
		}
		if (isActive() && playing && large) postDelayed(ticker, TICK_MS);
	}

	// ---------------------------------------------------------------------------------------------
	// Controls

	private void onPlayPause() {
		MainActivityDelegate a = act();
		MediaSessionCallback cb = a.getMediaSessionCallback();
		if (cb.getCurrentItem() != null) {
			if (cb.isPlaying()) cb.onPause();
			else cb.onPlay();
			return;
		}
		// Nothing loaded: where the last session stopped, played as music right here (the screen stays).
		startIdle();
	}

	private void startIdle() {
		MainActivityDelegate a = act();
		MediaSessionCallback cb = a.getMediaSessionCallback();
		PlayableItem last = cb.getResumeItem();
		// What played when the app last ran, if it was not a queue track (a local video, say).
		if ((last != null) && !(last instanceof MusicTrackItem) && !last.isExternal()) {
			skipUntil = SystemClock.elapsedRealtime() + 8000;
			startedIdle = true;
			MusicPlayer.playAsMusic(a, last, cb.getResumePosition());
			syncState();
			return;
		}
		MusicQueue q = MusicPlayer.getQueue(a);
		if ((q != null) && !q.isEmpty()) {
			MusicTrackItem t = q.getSavedCurrent();
			skipUntil = SystemClock.elapsedRealtime() + 8000;
			startedIdle = true;
			if (t != null) MusicPlayer.playTrack(a, t, q.getSavedPosition());
			else MusicPlayer.playTrack(a, q.getTrack(0), 0);
			syncState();
			return;
		}
		if (MusicPlayer.isEnabled()) host.open(R.id.music_addon);
		else host.open(R.id.folders_fragment);
	}

	private void onPrev() {
		MediaSessionCallback cb = act().getMediaSessionCallback();
		if (cb.getCurrentItem() == null) {
			skipFromSavedQueue(false);
			return;
		}
		if (cb.isAtListStart()) return;
		// Like any player: back to the start first, unless it has only just begun.
		if (position() > 3000) {
			cb.onSeekTo(0);
			return;
		}
		skipUntil = SystemClock.elapsedRealtime() + 2500;
		cb.onSkipToPrevious();
		syncState();
	}

	private void onNext() {
		MediaSessionCallback cb = act().getMediaSessionCallback();
		if (cb.getCurrentItem() == null) {
			skipFromSavedQueue(true);
			return;
		}
		if (cb.isAtListEnd()) return;
		skipUntil = SystemClock.elapsedRealtime() + 2500;
		cb.onSkipToNext();
		syncState();
	}

	/** Next or previous right after the app started: the queue's neighbour of where it stopped, wrapping around. */
	private void skipFromSavedQueue(boolean forward) {
		MainActivityDelegate a = act();
		MusicQueue q = MusicPlayer.getQueue(a);
		if ((q == null) || q.isEmpty()) return;
		List<MusicTrackItem> order = q.getPlayOrder();
		int n = order.size();
		if (n == 0) return;
		MusicTrackItem cur = q.getSavedCurrent();
		int idx = (cur == null) ? -1 : order.indexOf(cur);
		int target = (idx < 0) ? (forward ? 0 : n - 1) : Math.floorMod(idx + (forward ? 1 : -1), n);
		skipUntil = SystemClock.elapsedRealtime() + 8000;
		startedIdle = true;
		MusicPlayer.playTrack(a, order.get(target), 0);
		syncState();
	}

	private void bounce(View v) {
		v.animate().cancel();
		v.setScaleX(0.88f);
		v.setScaleY(0.88f);
		v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(2.4f)).start();
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Two stacked pictures; a new one fades in over the old one, so a song change doesn't cut. With
	 * {@code fade} the pictures dissolve at one edge ({@link #setFade}).
	 */
	private static final class Fader extends FrameLayout {
		private final ImageView[] v = new ImageView[2];
		private int front;
		private boolean icon;
		private int tint;

		Fader(android.content.Context ctx, boolean fade) {
			super(ctx);
			for (int i = 0; i < 2; i++) {
				v[i] = fade ? new FadeImageView(ctx) : new ImageView(ctx);
				v[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
				addView(v[i], new FrameLayout.LayoutParams(-1, -1));
			}
			v[1].setAlpha(0f);
		}

		void setFade(int side) {
			for (ImageView i : v) if (i instanceof FadeImageView f) f.setFade(side);
		}

		void setIcon(boolean icon) {
			if (this.icon == icon) return;
			this.icon = icon;
			int pad = icon ? HomeUi.dp(getContext(), 10) : 0;
			for (ImageView i : v) {
				i.setScaleType(icon ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.CENTER_CROP);
				i.setPadding(pad, pad, pad, pad);
			}
		}

		void tint(int color) {
			tint = color;
			for (ImageView i : v) i.setImageTintList((color == 0) ? null : ColorStateList.valueOf(color));
		}

		void set(@Nullable Drawable d) {
			set(d, 520);
		}

		/** Shows {@code d}, fading over {@code ms}. */
		void set(@Nullable Drawable d, int ms) {
			ImageView in = v[1 - front];
			ImageView out = v[front];
			in.animate().cancel();
			out.animate().cancel();
			in.setImageDrawable(d);
			boolean animate = isAttachedToWindow() && isShown() && (out.getDrawable() != null);
			front = 1 - front;
			if (!animate) {
				in.setAlpha(1f);
				out.setAlpha(0f);
				out.setImageDrawable(null);
				return;
			}
			in.setAlpha(0f);
			in.animate().alpha(1f).setDuration(ms).start();
			out.animate().alpha(0f).setDuration(ms).withEndAction(() -> {
				if (out != v[front]) out.setImageDrawable(null);
			}).start();
		}
	}

	/** The cover, drawn fading to nothing at one edge so it blends into the blurred wash behind. */
	private static final class FadeImageView extends ImageView {
		static final int RIGHT = 0;
		static final int BOTTOM = 1;
		private final Paint mask = new Paint();
		private final Paint shade = new Paint();
		private int side = RIGHT;
		private boolean bottomScrim;

		FadeImageView(android.content.Context ctx) {
			super(ctx);
			mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
		}

		void setFade(int side) {
			if (this.side == side) return;
			this.side = side;
			invalidate();
		}

		void setBottomScrim(boolean on) {
			bottomScrim = on;
		}

		@Override
		protected void onDraw(Canvas c) {
			int w = getWidth();
			int h = getHeight();
			if ((w == 0) || (h == 0) || (getDrawable() == null)) return;
			int layer = c.saveLayer(0, 0, w, h, null);
			super.onDraw(c);
			Shader sh = (side == RIGHT) ?
					new LinearGradient(0, 0, w, 0, new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
							new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP) :
					new LinearGradient(0, 0, 0, h, new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
							new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
			mask.setShader(sh);
			c.drawRect(0, 0, w, h, mask);
			c.restoreToCount(layer);
		}
	}

	/** A thin seek line: tap or drag to move; the thumb shows while touched. */
	private final class Seek extends View {
		private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
		private float fraction;
		private boolean touching;

		Seek() {
			super(MediaWidget.this.ctx);
		}

		boolean isTouching() {
			return touching;
		}

		void setFraction(float f) {
			fraction = Math.max(0f, Math.min(1f, f));
			invalidate();
		}

		/** The room at each end, so the thumb is whole at 0 and at the end. */
		private float inset() {
			return px(9);
		}

		@Override
		protected void onDraw(Canvas c) {
			float h = getHeight();
			float in = inset();
			float w = getWidth() - 2 * in;
			float cy = h / 2f;
			float t = px(4);
			int fg = color(ROLE_PRIMARY);
			p.setColor(HomeUi.alpha(fg, 0.27f));
			c.drawRoundRect(in, cy - t / 2, in + w, cy + t / 2, t / 2, t / 2, p);
			p.setColor(fg);
			c.drawRoundRect(in, cy - t / 2, in + w * fraction, cy + t / 2, t / 2, t / 2, p);
			c.drawCircle(in + w * fraction, cy, touching ? px(8) : px(5.5f), p);
		}

		@Override
		public boolean onTouchEvent(MotionEvent e) {
			if ((duration <= 0) || idle) return false;
			float in = inset();
			float f = Math.max(0f, Math.min(1f, (e.getX() - in) / Math.max(1, getWidth() - 2 * in)));
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN -> {
					touching = true;
					if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
					setFraction(f);
				}
				case MotionEvent.ACTION_MOVE -> {
					setFraction(f);
					timeNow.setText(DateUtils.formatElapsedTime((long) (f * duration) / 1000));
				}
				case MotionEvent.ACTION_UP -> {
					touching = false;
					setFraction(f);
					seekPos = (long) (f * duration);
					seekAt = SystemClock.elapsedRealtime();
					// The player's last answer is from before the seek: asked again on the next tick.
					enginePos = -1;
					enginePosAt = 0;
					act().getMediaSessionCallback().onSeekTo(seekPos);
					timeNow.setText(DateUtils.formatElapsedTime(seekPos / 1000));
					post(ticker);
				}
				case MotionEvent.ACTION_CANCEL -> {
					touching = false;
					invalidate();
				}
				default -> {
				}
			}
			return true;
		}
	}
}
