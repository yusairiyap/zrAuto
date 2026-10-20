package me.aap.fermata.ui.home.widget;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE;

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
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;
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
	private Seek seek;
	private TextView timeNow;
	private TextView timeAll;
	private LinearLayout seekBlock;
	private TextView chip;
	private LinearLayout controls;
	private LinearLayout texts;

	private int mode = -1;
	private int appliedW;
	private int generation;
	@Nullable
	private PlayableItem shown;
	private long duration;
	private boolean playing;
	private boolean videoNow;
	private boolean hasArt;
	/** Where the user last seeked to, until the session reports it (the YouTube player is slow to). */
	private long seekPos;
	private long seekAt;
	private int durationAsks;
	private Runnable chipAction = () -> {};
	private final Runnable ticker = this::tick;
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

	@Override
	protected boolean ownBackground() {
		return true;
	}

	private MainActivityDelegate act() {
		return host.activity();
	}

	@Override
	protected void onCreate(FrameLayout content) {
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
		title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
		title.setMarqueeRepeatLimit(2);
		title.setHorizontallyScrolling(true);
		artist = text(ROLE_SECONDARY, 13.5f, false);
		artist.setSingleLine(true);
		artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
		texts.addView(title);
		texts.addView(artist);
		content.addView(texts);

		badge = text(ROLE_PRIMARY, 11, true);
		badge.setText(R.string.home_media_video);
		badge.setBackground(HomeUi.round(0x88000000, px(10)));
		badge.setPadding(px(9), px(4), px(9), px(4));
		badge.setVisibility(GONE);
		content.addView(badge);

		seekBlock = new LinearLayout(ctx);
		seekBlock.setOrientation(LinearLayout.VERTICAL);
		seek = new Seek();
		noGrab(seek);
		seekBlock.addView(seek, new LinearLayout.LayoutParams(-1, px(34)));
		LinearLayout times = new LinearLayout(ctx);
		timeNow = text(ROLE_SECONDARY, 11.5f, false);
		timeAll = text(ROLE_SECONDARY, 11.5f, false);
		times.addView(timeNow, new LinearLayout.LayoutParams(0, -2, 1f));
		times.addView(timeAll);
		seekBlock.addView(times, new LinearLayout.LayoutParams(-1, -2));
		content.addView(seekBlock);

		controls = new LinearLayout(ctx);
		controls.setOrientation(LinearLayout.HORIZONTAL);
		controls.setGravity(Gravity.CENTER_VERTICAL);
		prev = iconButton(R.drawable.prev, 44, ROLE_ICON, R.string.action_prev, this::onPrev);
		next = iconButton(R.drawable.next, 44, ROLE_ICON, R.string.action_next, this::onNext);
		play = new ImageView(ctx);
		play.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		int pp = px(13);
		play.setPadding(pp, pp, pp, pp);
		play.setBackground(HomeUi.pressable(0x33000000, 0xFFFFFFFF, px(28)));
		play.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF111111));
		play.setContentDescription(ctx.getString(R.string.action_play_pause));
		play.setClickable(true);
		play.setFocusable(true);
		play.setOnClickListener(v -> {
			bounce(v);
			onPlayPause();
		});
		controls.addView(prev);
		controls.addView(play, new LinearLayout.LayoutParams(px(52), px(52)));
		controls.addView(next);
		content.addView(controls);

		chip = chip(R.drawable.video_fullscreen, "", () -> chipAction.run());
		chip.setBackground(HomeUi.pressable(0x33FFFFFF, 0x44000000, px(17)));
		content.addView(chip);

		artBusy = spinner(20);
		artBusy.setVisibility(GONE);
		content.addView(artBusy, lp(px(20), px(20), Gravity.TOP | Gravity.END, 0, px(12), px(12), 0));
	}

	@Override
	protected void onStyleChanged() {
		// Always white on its own picture; nothing to follow.
	}

	@Override
	protected List<HomeOption> getOptions() {
		List<HomeOption> l = new ArrayList<>();
		l.add(HomeOption.sw("seek", ctx.getString(R.string.home_opt_seek), true));
		l.add(HomeOption.sw("chip", ctx.getString(R.string.home_opt_video_chip), true));
		return l;
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
		if ((thumb != null) && (w != appliedW)) applyMode();
	}

	private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
		FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
		p.setMargins(l, t, r, b);
		return p;
	}

	private void applyMode() {
		if (mode < 0) return;
		int w = Math.max(widthPx(), px(160));
		appliedW = widthPx();
		boolean strip = (mode == STRIP_SMALL) || (mode == STRIP_WIDE);
		boolean large = (mode == LARGE_WIDE) || (mode == LARGE_TALL);
		boolean showSeek = large && optBool("seek", true);
		boolean showChip = large && optBool("chip", true);
		int pad = px(14);

		thumb.setVisibility(strip ? VISIBLE : GONE);
		art.setVisibility(strip ? GONE : VISIBLE);
		art.setFade(mode == LARGE_WIDE ? FadeImageView.RIGHT : FadeImageView.BOTTOM);
		artBusy.setVisibility(strip ? GONE : artBusy.getVisibility());
		prev.setVisibility((mode == STRIP_SMALL) ? GONE : VISIBLE);
		next.setVisibility((mode == STRIP_SMALL) ? GONE : VISIBLE);
		seekBlock.setVisibility(showSeek ? VISIBLE : GONE);
		chip.setVisibility(showChip ? VISIBLE : GONE);
		badge.setVisibility(large && videoNow ? VISIBLE : GONE);
		title.setTextSize(strip ? 15 : large ? 20 : 16);
		artist.setTextSize(strip ? 12.5f : 14);
		title.setSingleLine(true);
		texts.setGravity(strip ? Gravity.CENTER_VERTICAL : Gravity.START);

		switch (mode) {
			case STRIP_SMALL -> {
				thumb.setLayoutParams(lp(px(44), px(44), Gravity.START | Gravity.CENTER_VERTICAL, pad, 0, 0, 0));
				texts.setLayoutParams(lp(-1, -2, Gravity.CENTER_VERTICAL, pad + px(44) + px(10), 0, px(60), 0));
				controls.setLayoutParams(lp(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, px(8), 0));
			}
			case STRIP_WIDE -> {
				thumb.setLayoutParams(lp(px(44), px(44), Gravity.START | Gravity.CENTER_VERTICAL, pad, 0, 0, 0));
				texts.setLayoutParams(lp(-1, -2, Gravity.CENTER_VERTICAL, pad + px(44) + px(10), 0, px(160), 0));
				controls.setLayoutParams(lp(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, px(8), 0));
			}
			case MEDIUM -> {
				art.setLayoutParams(lp(-1, -1, Gravity.TOP, 0, 0, 0, 0));
				texts.setLayoutParams(lp(-1, -2, Gravity.BOTTOM, pad, 0, pad, px(66)));
				controls.setLayoutParams(lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, px(8)));
			}
			case LARGE_WIDE -> {
				art.setLayoutParams(lp((int) (w * 0.56f), -1, Gravity.START, 0, 0, 0, 0));
				int left = (int) (w * 0.44f);
				texts.setLayoutParams(lp(-1, -2, Gravity.TOP, left, pad + px(4), pad, 0));
				seekBlock.setLayoutParams(lp(-1, -2, Gravity.BOTTOM, left, 0, pad, px(72)));
				controls.setLayoutParams(lp(-2, -2, Gravity.BOTTOM | Gravity.START, left - px(6), 0, 0, px(10)));
				chip.setLayoutParams(lp(-2, px(36), Gravity.BOTTOM | Gravity.END, 0, 0, pad, px(18)));
				badge.setLayoutParams(lp(-2, -2, Gravity.TOP | Gravity.START, pad, pad, 0, 0));
			}
			default -> {
				art.setLayoutParams(lp(-1, -1, Gravity.TOP, 0, 0, 0, 0));
				texts.setLayoutParams(lp(-1, -2, Gravity.BOTTOM, pad, 0, pad, px(showSeek ? 134 : 82)));
				seekBlock.setLayoutParams(lp(-1, -2, Gravity.BOTTOM, pad, 0, pad, px(72)));
				controls.setLayoutParams(lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, px(10)));
				chip.setLayoutParams(lp(-2, px(36), Gravity.TOP | Gravity.END, 0, pad, pad, 0));
				badge.setLayoutParams(lp(-2, -2, Gravity.TOP | Gravity.START, pad, pad, 0, 0));
			}
		}
		scrim.setBackground(scrimFor(mode));
		wash.setAlpha(strip ? 0.92f : 1f);
		syncState();
	}

	private GradientDrawable scrimFor(int m) {
		GradientDrawable g = new GradientDrawable();
		if (m == LARGE_WIDE) {
			g.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
			g.setColors(new int[]{0x00000000, 0x33000000, 0x88000000});
		} else if ((m == STRIP_SMALL) || (m == STRIP_WIDE)) {
			g.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
			g.setColors(new int[]{0x55000000, 0x77000000});
		} else {
			g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
			g.setColors(new int[]{0x00000000, 0x22000000, 0xCC000000});
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
		syncItem();
		syncState();
	}

	@Override
	protected void onInactive() {
		MainActivityDelegate a = act();
		a.getMediaSessionCallback().removeBroadcastListener(stateListener);
		a.getMediaServiceBinder().removeBroadcastListener(itemListener);
		removeCallbacks(ticker);
	}

	@Override
	protected void onBind() {
		if (mode >= 0) applyMode();
	}

	// ---------------------------------------------------------------------------------------------
	// What plays

	/** The item shown changed: title, artist, cover. */
	private void syncItem() {
		if (title == null) return;
		MainActivityDelegate a = act();
		PlayableItem pi = Action.getFavoritableItem(a);
		if (pi == null) {
			PlayableItem last = a.getMediaSessionCallback().getResumeItem();
			shown = null;
			generation++;
			swap(title, ctx.getString(R.string.home_media_idle));
			swap(artist, (last != null) ? last.getName() : ctx.getString(R.string.home_media_idle_sub));
			setBusy(false);
			setArt(null);
			duration = 0;
			syncState();
			return;
		}
		if (pi == shown) {
			syncState();
			return;
		}
		shown = pi;
		int gen = ++generation;
		duration = 0;
		swap(title, pi.getName());
		swap(artist, pi instanceof MusicTrackItem t && t.getArtistName() != null ? t.getArtistName() : "");
		title.setSelected(true);
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
			String ar = md.getString(METADATA_KEY_ARTIST);
			if ((ar == null) || ar.isEmpty()) ar = md.getString(METADATA_KEY_ALBUM_ARTIST);
			if ((ar == null) || ar.isEmpty()) ar = md.getString(METADATA_KEY_ALBUM);
			if ((ar != null) && !ar.isEmpty()) swap(artist, ar);
			duration = md.getLong(METADATA_KEY_DURATION);
			loadArt(pi, md, gen);
			syncState();
		});
		syncState();
	}

	/** The duration may only become known after the item (the YouTube player says it late). */
	private void refreshDuration() {
		PlayableItem pi = shown;
		if (pi == null) return;
		pi.getMediaData().main().onSuccess(md -> {
			if ((md != null) && (pi == shown) && (md.getLong(METADATA_KEY_DURATION) > 0)) {
				duration = md.getLong(METADATA_KEY_DURATION);
				tick();
			}
		});
	}

	/** Changes a text with a short cross-fade, so a new song's name doesn't pop in. */
	private void swap(TextView t, CharSequence s) {
		if (s.toString().contentEquals(t.getText())) {
			t.animate().cancel();
			t.setAlpha(1f);
			return;
		}
		if (!t.isAttachedToWindow() || !isShown() || !host.animate()) {
			t.setText(s);
			return;
		}
		t.animate().cancel();
		t.animate().alpha(0f).setDuration(130).withEndAction(() -> {
			t.setText(s);
			t.animate().alpha(1f).setDuration(260).start();
		}).start();
	}

	private void setBusy(boolean on) {
		boolean strip = (mode == STRIP_SMALL) || (mode == STRIP_WIDE);
		artBusy.setVisibility(on && !strip ? VISIBLE : GONE);
	}

	private void loadArt(PlayableItem pi, MediaMetadataCompat md, int gen) {
		String uri = (pi instanceof MusicTrackItem t) ? t.getArtUri() : null;
		if (uri == null) {
			Bitmap bm = md.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
			if (bm != null) {
				setArt(bm);
				return;
			}
			uri = md.getString(METADATA_KEY_ALBUM_ART_URI);
			if (uri == null) uri = md.getString(METADATA_KEY_DISPLAY_ICON_URI);
		}
		if (uri == null) {
			pi.getIconUri().main().onSuccess(u -> {
				if (gen != generation) return;
				if (u == null) setArt(null);
				else fetchArt(pi, u.toString(), gen);
			});
		} else {
			fetchArt(pi, uri, gen);
		}
	}

	private void fetchArt(PlayableItem pi, String uri, int gen) {
		Bitmap hit = HomeImages.cached(uri);
		if (hit != null) {
			setArt(hit);
			return;
		}
		act().getLib().getBitmap(uri).main().onCompletion((bm, err) -> {
			if (gen != generation) return;
			if (bm == null) {
				setArt(null);
				return;
			}
			// A video's picture comes letterboxed from YouTube.
			Bitmap use = MusicPlayerFragment.cropLetterbox(bm);
			HomeImages.put(uri, use);
			setArt(use);
		});
	}

	/** Shows the cover, or the card's own colours when there is none; the old one fades into it. */
	private void setArt(@Nullable Bitmap bm) {
		hasArt = bm != null;
		setBusy(false);
		if (bm == null) {
			GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
					new int[]{HomeUi.shade(type.accent, 0.7f), HomeUi.shade(type.accent, 0.3f)});
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
		Drawable d = new BitmapDrawable(getResources(), (soft != null) ? soft : small);
		wash.set(d);
		art.set(new BitmapDrawable(getResources(), bm));
		thumb.tint(0);
		thumb.setIcon(false);
		thumb.set(new BitmapDrawable(getResources(), bm));
	}

	/** Play state, the video chip and the seek bar follow the session. */
	private void syncState() {
		if (play == null) return;
		MainActivityDelegate a = act();
		MediaSessionCallback cb = a.getMediaSessionCallback();
		PlaybackStateCompat st = cb.getPlaybackState();
		int s = st.getState();
		playing = (s == PlaybackStateCompat.STATE_PLAYING) || (s == PlaybackStateCompat.STATE_BUFFERING);
		play.setImageResource(playing ? R.drawable.pause : R.drawable.play);
		play.setAlpha((s == PlaybackStateCompat.STATE_BUFFERING) ? 0.7f : 1f);
		boolean idle = shown == null;
		prev.setAlpha(idle ? 0.4f : 1f);
		next.setAlpha(idle ? 0.4f : 1f);

		videoNow = a.isVideoPlaying();
		boolean canWatch = MusicPlayer.canSwitchToVideo(cb);
		if (idle) {
			PlayableItem last = cb.getResumeItem();
			if (last != null) {
				setChip(R.drawable.play, R.string.home_media_continue, a::continueLastPlayed);
			} else if (MusicPlayer.isEnabled()) {
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

		removeCallbacks(ticker);
		tick();
	}

	private void setChip(int icon, int label, Runnable action) {
		chip.setText(label);
		chip.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
		chipAction = action;
	}

	/** The position now, extrapolated from the session's last report. */
	private long position() {
		PlaybackStateCompat st = act().getMediaSessionCallback().getPlaybackState();
		// Just seeked: the session may not have heard of it yet, so go on from where the user put it.
		long since = SystemClock.elapsedRealtime() - seekAt;
		if ((seekAt > 0) && (since < 3000)) {
			long reported = st.getPosition();
			if (Math.abs(reported - seekPos) > 2500) return Math.max(0, seekPos + (playing ? since : 0));
		}
		long pos = st.getPosition();
		if (st.getState() == PlaybackStateCompat.STATE_PLAYING) {
			pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) *
					st.getPlaybackSpeed());
		}
		return Math.max(0, pos);
	}

	private void tick() {
		if (seek == null) return;
		boolean large = (mode == LARGE_WIDE) || (mode == LARGE_TALL);
		if (large && (seekBlock.getVisibility() == VISIBLE)) {
			// A video whose length isn't known yet: asked again now and then.
			if ((duration <= 0) && (shown != null) && ((durationAsks++ % 4) == 0)) refreshDuration();
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
		// Nothing loaded: where the last session stopped, else the Music tab.
		if (cb.getResumeItem() != null) a.continueLastPlayed();
		else if (MusicPlayer.isEnabled()) host.open(R.id.music_addon);
		else host.open(R.id.folders_fragment);
	}

	private void onPrev() {
		MediaSessionCallback cb = act().getMediaSessionCallback();
		if (cb.getCurrentItem() == null) return;
		// Like any player: back to the start first, unless it has only just begun.
		if (position() > 3000) cb.onSeekTo(0);
		else cb.onSkipToPrevious();
	}

	private void onNext() {
		MediaSessionCallback cb = act().getMediaSessionCallback();
		if (cb.getCurrentItem() != null) cb.onSkipToNext();
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
			in.animate().alpha(1f).setDuration(520).start();
			out.animate().alpha(0f).setDuration(520).withEndAction(() -> {
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

		@Override
		protected void onDraw(Canvas c) {
			float h = getHeight();
			float w = getWidth();
			float cy = h / 2f;
			float t = px(4);
			p.setColor(0x44FFFFFF);
			c.drawRoundRect(0, cy - t / 2, w, cy + t / 2, t / 2, t / 2, p);
			p.setColor(0xFFFFFFFF);
			c.drawRoundRect(0, cy - t / 2, w * fraction, cy + t / 2, t / 2, t / 2, p);
			c.drawCircle(w * fraction, cy, touching ? px(8) : px(5.5f), p);
		}

		@Override
		public boolean onTouchEvent(MotionEvent e) {
			if (duration <= 0) return false;
			float f = Math.max(0f, Math.min(1f, e.getX() / Math.max(1, getWidth())));
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
