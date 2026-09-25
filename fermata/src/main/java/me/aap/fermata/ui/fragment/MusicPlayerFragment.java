package me.aap.fermata.ui.fragment;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE;
import static android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_ALL;
import static android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_NONE;
import static android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_ONE;
import static android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_ALL;
import static android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_NONE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicAddon;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicQueue;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.ControlPanelView;
import me.aap.fermata.ui.view.InfoOverlayView;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.text.TextUtils;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * The Music tab: a full-screen, audio-only player in the style of Spotify's / Android Auto's
 * now-playing screens -- the cover on a card over a blurred copy of itself, track info, seek bar,
 * shuffle / previous / play-pause / next / repeat (off, whole queue, current song), and quick
 * access to the queue, the audio effects and the video (for a YouTube or local video track).
 * <p>
 * Shows whatever is playing, music queue or not, so it doubles as a big now-playing screen; the
 * regular control panel is hidden while it's showing, since everything it offers is here too.
 * <p>
 * Kept deliberately cheap to run: nothing animates while idle, the progress only ticks while the
 * tab is actually visible and playing, and the background blur is computed once per cover from a
 * tiny downscaled copy, never per frame.
 */
public class MusicPlayerFragment extends MainActivityFragment implements
		MediaSessionCallback.Listener, FermataServiceUiBinder.Listener, MusicQueue.Listener,
		PreferenceStore.Listener {
	private static final long PROGRESS_INTERVAL = 500;
	private static final long RESTART_THRESHOLD = 3000;
	private static final long FADE_MS = 350;

	private final Runnable progressTask = this::updateProgress;
	private ViewGroup content;
	private ImageView bg;
	private ImageView art;
	private ProgressBar loading;
	private TextView title;
	private TextView artist;
	private TextView position;
	private TextView duration;
	private SeekBar seek;
	private ImageButton shuffle;
	private ImageButton playPause;
	private ImageButton repeat;
	private TextView videoButton;
	private View queuePanel;
	private TextView queueCount;
	private TextView queueEmpty;
	private RecyclerView queueList;
	private QueueAdapter adapter;
	private ItemTouchHelper touchHelper;
	@Nullable
	private InfoOverlayView infoOverlay;
	@Nullable
	private MusicQueue queue;
	@Nullable
	private PlayableItem shownItem;
	@Nullable
	private Object shownArt;
	// The cover currently shown, kept so the background can be re-blurred when the Background
	// blur setting changes, without reloading it.
	@Nullable
	private Bitmap artBitmap;
	private boolean listening;
	private boolean progressRunning;
	private boolean seeking;

	@Override
	public int getFragmentId() {
		return R.id.music_addon;
	}

	@NonNull
	@Override
	public CharSequence getTitle() {
		return getString(R.string.music_title);
	}

	@Override
	public boolean canScrollUp() {
		// Nothing here to pull-to-refresh, and it would fight the seek bar and the queue list.
		return true;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.music_player_fragment, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		MainActivityDelegate a = getActivityDelegate();
		content = view.findViewById(R.id.music_content);
		bg = view.findViewById(R.id.music_bg);
		art = view.findViewById(R.id.music_art);
		loading = view.findViewById(R.id.music_loading);
		title = view.findViewById(R.id.music_track_title);
		artist = view.findViewById(R.id.music_track_artist);
		position = view.findViewById(R.id.music_position);
		duration = view.findViewById(R.id.music_duration);
		seek = view.findViewById(R.id.music_seek);
		shuffle = view.findViewById(R.id.music_shuffle);
		playPause = view.findViewById(R.id.music_play_pause);
		repeat = view.findViewById(R.id.music_repeat);
		videoButton = view.findViewById(R.id.music_video_button);
		queuePanel = view.findViewById(R.id.music_queue_panel);
		queueCount = view.findViewById(R.id.music_queue_count);
		queueEmpty = view.findViewById(R.id.music_queue_empty);
		queueList = view.findViewById(R.id.music_queue_list);
		queue = MusicPlayer.getQueue(a);

		// Like every other tab: tool_bar/nav_bar are drawn over the fragment, so the content has to
		// reserve room for them itself (see MainActivityDelegate#insetScrollableContent).
		a.insetScrollableContent(content);
		content.addOnLayoutChangeListener(
				(v, l, t, r, b, ol, ot, or, ob) -> v.post(this::layoutQueuePanel));

		// The top/bottom spacing for the tool and nav bars only settles after the first layout pass
		// (see insetScrollableContent): fade the content in once it has, rather than showing it
		// jump from one size to the other.
		content.setAlpha(0f);
		content.getViewTreeObserver().addOnGlobalLayoutListener(
				new ViewTreeObserver.OnGlobalLayoutListener() {
					@Override
					public void onGlobalLayout() {
						content.getViewTreeObserver().removeOnGlobalLayoutListener(this);
						content.postDelayed(() -> content.animate().alpha(1f).setDuration(180).start(), 60);
					}
				});

		if (!isLandscape()) {
			// Keeps the bottom row of actions clear of the floating menu button in the corner.
			View controls = view.findViewById(R.id.music_controls);
			controls.setPaddingRelative(controls.getPaddingStart(), controls.getPaddingTop(),
					controls.getPaddingEnd(), UiUtils.toIntPx(requireContext(), 72));
		}

		playPause.setOnClickListener(v -> onPlayPause());
		view.findViewById(R.id.music_prev).setOnClickListener(v -> onPrev());
		view.findViewById(R.id.music_next).setOnClickListener(v -> onNext());
		shuffle.setOnClickListener(v -> onShuffle());
		repeat.setOnClickListener(v -> onRepeat());
		view.findViewById(R.id.music_queue_button).setOnClickListener(v -> toggleQueue());
		view.findViewById(R.id.music_effects_button).setOnClickListener(v -> onEffects());
		videoButton.setOnClickListener(v -> onVideo());
		view.findViewById(R.id.music_queue_close).setOnClickListener(v -> showQueue(false));
		view.findViewById(R.id.music_queue_clear).setOnClickListener(v -> {
			if (queue != null) queue.clear();
		});
		seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
				if (fromUser) position.setText(time(progress));
			}

			@Override
			public void onStartTrackingTouch(SeekBar sb) {
				seeking = true;
			}

			@Override
			public void onStopTrackingTouch(SeekBar sb) {
				seeking = false;
				MediaSessionCallback cb = getActivityDelegate().getMediaSessionCallback();
				if (cb.getCurrentItem() != null) cb.onSeekTo(sb.getProgress() * 1000L);
			}
		});

		adapter = new QueueAdapter();
		queueList.setLayoutManager(new LinearLayoutManager(requireContext()));
		queueList.setAdapter(adapter);
		touchHelper = new ItemTouchHelper(new QueueTouchCallback());
		touchHelper.attachToRecyclerView(queueList);

		addInfoOverlay(view.findViewById(R.id.music_info_holder));
		updateZoom();
	}

	/**
	 * The same clock/battery/temperature/distance overlay as fullscreen video (and the same
	 * choice of items), as a small pill in the top corner -- clock, battery and temperature when
	 * nothing is picked in its settings.
	 */
	private void addInfoOverlay(FrameLayout holder) {
		MainActivityPrefs p = getActivityDelegate().getPrefs();
		boolean clock = p.getInfoOverlayShowClockPref();
		boolean battery = p.getInfoOverlayShowBatteryPctPref();
		boolean temp = p.getInfoOverlayShowBatteryTempPref();
		boolean distance = p.getInfoOverlayShowDistancePref();
		if (!clock && !battery && !temp && !distance) clock = battery = temp = true;

		InfoOverlayView o = new InfoOverlayView(requireContext());
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				(isLandscape() ? Gravity.END : Gravity.CENTER_HORIZONTAL) | Gravity.TOP);
		holder.addView(o, lp);
		o.setSize(0.55f * Math.max(0.5f, p.getInfoOverlaySizePref()));
		o.setItems(clock, p.getInfoOverlayShowClockIconPref(), battery,
				p.getInfoOverlayShowBatteryIconPref(), temp, p.getInfoOverlayShowTempIconPref(), distance,
				p.getInfoOverlayShowDistanceIconPref());
		infoOverlay = o;
	}

	@Override
	public void onResume() {
		super.onResume();
		updateActive();
	}

	// Both run before the fragment transaction that shows/hides this tab commits: hiding the control
	// panel here (not only once this tab is already showing) means the tab is laid out once, at its
	// final size, instead of first with the panel and then again without it.
	@Override
	public void switchingFrom(@Nullable ActivityFragment from) {
		super.switchingFrom(from);
		MainActivityDelegate a = null;
		if (getContext() != null) a = getActivityDelegate();
		else if ((from instanceof MainActivityFragment m) && (m.getContext() != null))
			a = m.getActivityDelegate();
		ControlPanelView cp = (a != null) ? a.getControlPanel() : null;
		if (cp != null) cp.setSuppressed(true);
	}

	@Override
	public void switchingTo(@NonNull ActivityFragment to) {
		super.switchingTo(to);
		if (getContext() == null) return;
		ControlPanelView cp = getActivityDelegate().getControlPanel();
		if (cp != null) cp.setSuppressed(false);
	}

	@Override
	public void onPause() {
		super.onPause();
		stopProgress();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		updateActive();
	}

	@Override
	public void onDestroyView() {
		setListening(false);
		stopProgress();
		super.onDestroyView();
	}

	private void updateActive() {
		boolean visible = !isHidden() && (getView() != null);
		setListening(visible);
		if (visible) {
			refresh();
			// The background settings may have changed in Settings while this tab was hidden.
			updateBackground(false);
			updateZoom();
			if (isResumed()) startProgress();
		} else {
			stopProgress();
			showQueue(false);
		}
	}

	private void setListening(boolean on) {
		if (listening == on) return;
		listening = on;
		MainActivityDelegate a = getActivityDelegate();
		ControlPanelView cp = a.getControlPanel();
		if (cp != null) cp.setSuppressed(on);

		if (on) {
			a.getMediaSessionCallback().addBroadcastListener(this);
			a.getMediaServiceBinder().addBroadcastListener(this);
			if (queue != null) queue.addListener(this);
			settings().addBroadcastListener(this);
		} else {
			a.getMediaSessionCallback().removeBroadcastListener(this);
			a.getMediaServiceBinder().removeBroadcastListener(this);
			if (queue != null) queue.removeListener(this);
			settings().removeBroadcastListener(this);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// State
	// ---------------------------------------------------------------------------------------------

	/** The item to show: what's playing, else where the music queue left off. */
	@Nullable
	private PlayableItem getDisplayItem() {
		PlayableItem cur = getActivityDelegate().getMediaSessionCallback().getCurrentItem();
		if (cur != null) return cur;
		if (queue == null) return null;
		MusicTrackItem t = queue.getSavedCurrent();
		return (t != null) ? t : queue.getTrack(0);
	}

	private void refresh() {
		if (getView() == null) return;
		MediaSessionCallback cb = getActivityDelegate().getMediaSessionCallback();
		displayItem(getDisplayItem(), false);
		updatePlayState(cb.getPlaybackState());
		updateModes();
		adapter.reload();
	}

	private void displayItem(@Nullable PlayableItem i, boolean force) {
		if (!force && (i == shownItem) && (i != null)) return;
		shownItem = i;
		updateVideoButton(i);

		if (i == null) {
			title.setText(R.string.music_nothing_playing);
			artist.setText(queueIsEmpty() ? getString(R.string.music_queue_empty) : "");
			artist.setSingleLine(!queueIsEmpty());
			setArt(null, null);
			seek.setMax(0);
			seek.setProgress(0);
			position.setText(time(0));
			duration.setText(time(0));
			return;
		}

		artist.setSingleLine(true);
		title.setText(i.getName());
		title.setSelected(true); // Starts the marquee for long titles.
		artist.setText("");
		if (i instanceof MusicTrackItem t) {
			String a = t.getArtistName();
			if (a != null) artist.setText(a);
		}

		i.getMediaData().main().onSuccess(md -> {
			if (shownItem != i) return;
			String t = md.getString(METADATA_KEY_TITLE);
			if ((t == null) || t.isEmpty()) t = md.getString(METADATA_KEY_DISPLAY_TITLE);
			if ((t != null) && !t.isEmpty()) title.setText(t);
			artist.setText(artistOf(i, md));
			long dur = md.getLong(METADATA_KEY_DURATION);
			if (dur > 0) setDuration(dur);
			loadArt(i, md);
		});
	}

	private String artistOf(PlayableItem i, MediaMetadataCompat md) {
		String a = md.getString(METADATA_KEY_ARTIST);
		if ((a == null) || a.isEmpty()) a = md.getString(METADATA_KEY_ALBUM_ARTIST);
		if ((a == null) || a.isEmpty()) a = md.getString(METADATA_KEY_ALBUM);
		if ((a != null) && !a.isEmpty()) return a;
		if (i instanceof MusicTrackItem t) return (t.getVideoId() != null) ? "YouTube" : "";
		return i.getParent().getName();
	}

	private void loadArt(PlayableItem i, MediaMetadataCompat md) {
		String uri = (i instanceof MusicTrackItem t) ? t.getArtUri() : null;
		if (uri == null) {
			Bitmap bm = md.getBitmap(METADATA_KEY_ALBUM_ART);
			if (bm != null) {
				setArt(bm, bm);
				return;
			}
			uri = md.getString(METADATA_KEY_ALBUM_ART_URI);
			if (uri == null) uri = md.getString(METADATA_KEY_DISPLAY_ICON_URI);
		}

		if (uri == null) {
			i.getIconUri().main().onSuccess(u -> {
				if (shownItem != i) return;
				if (u == null) setArt(null, null);
				else loadArt(i, u.toString());
			});
		} else {
			loadArt(i, uri);
		}
	}

	private void loadArt(PlayableItem i, String uri) {
		if (uri.equals(shownArt)) return;
		MediaLib lib = getActivityDelegate().getLib();
		lib.getBitmap(uri).main().onCompletion((bm, err) -> {
			if (shownItem != i) return;
			setArt(isYoutube(i) ? cropLetterbox(bm) : bm, uri);
		});
	}

	private void setArt(@Nullable Bitmap bm, @Nullable Object key) {
		if ((key != null) && key.equals(shownArt)) return;
		shownArt = key;
		Context ctx = requireContext();

		artBitmap = bm;

		if (bm == null) {
			crossfade(art, ContextCompat.getDrawable(ctx, R.drawable.music_art_placeholder));
			crossfade(bg, null);
			return;
		}

		crossfade(art, new BitmapDrawable(getResources(), bm));
		updateBackground(true);
	}

	private static PreferenceStore settings() {
		return FermataApplication.get().getPreferenceStore();
	}

	/** Re-renders the background from the current cover with the Background blur setting. */
	private void updateBackground(boolean fade) {
		Bitmap bm = artBitmap;
		if ((bm == null) || (bg == null)) return;
		Bitmap blurred = blur(bm, settings().getIntPref(MusicAddon.BG_BLUR));
		Drawable d = (blurred != null) ? new BitmapDrawable(getResources(), blurred) : null;
		if (fade) crossfade(bg, d);
		else bg.setImageDrawable(d);
	}

	/** Background zoom: just a view scale around the centre, the image isn't re-rendered. */
	private void updateZoom() {
		if (bg == null) return;
		float z = Math.max(100, Math.min(300, settings().getIntPref(MusicAddon.BG_ZOOM))) / 100f;
		bg.setScaleX(z);
		bg.setScaleY(z);
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		if (getView() == null) return;
		if (prefs.contains(MusicAddon.BG_BLUR)) updateBackground(false);
		if (prefs.contains(MusicAddon.BG_ZOOM)) updateZoom();
	}

	private static boolean isYoutube(PlayableItem i) {
		return (i instanceof MusicTrackItem t) && (t.getVideoId() != null);
	}

	/**
	 * YouTube's fallback thumbnail (hqdefault.jpg, used when a video has no maxresdefault.jpg) is a
	 * 4:3 canvas with the 16:9 frame letterboxed inside it: cut the black bars off, so the cover
	 * card doesn't show them.
	 */
	@Nullable
	private static Bitmap cropLetterbox(@Nullable Bitmap bm) {
		if (bm == null) return null;
		int w = bm.getWidth();
		int h = bm.getHeight();
		if ((w <= 0) || (w * 3 != h * 4)) return bm;
		int ch = w * 9 / 16;
		try {
			return Bitmap.createBitmap(bm, 0, (h - ch) / 2, w, ch);
		} catch (Throwable ex) {
			return bm;
		}
	}

	private static void crossfade(ImageView v, @Nullable Drawable to) {
		Drawable from = v.getDrawable();
		if (from instanceof TransitionDrawable td) from = td.getDrawable(td.getNumberOfLayers() - 1);
		if (from == null) from = new android.graphics.drawable.ColorDrawable(0);
		if (to == null) to = new android.graphics.drawable.ColorDrawable(0);
		TransitionDrawable t = new TransitionDrawable(new Drawable[]{from, to});
		t.setCrossFadeEnabled(true);
		v.setImageDrawable(t);
		t.startTransition((int) FADE_MS);
	}

	/**
	 * A heavily blurred copy of the cover for the background: downscaled to a few dozen pixels
	 * first, so the blur itself costs next to nothing, then stretched back up by the GPU's own
	 * bilinear filtering -- which is exactly the soft, colour-wash look wanted here.
	 */
	@Nullable
	private static Bitmap blur(Bitmap src, int strength) {
		try {
			// 0 = the cover itself, unblurred.
			if (strength <= 0) return src;
			if (src.getConfig() == Bitmap.Config.HARDWARE) src = src.copy(Bitmap.Config.ARGB_8888, false);
			if ((src == null) || (src.getWidth() <= 0) || (src.getHeight() <= 0)) return null;
			float f = Math.min(100, strength) / 100f;
			// Stronger blur = a smaller working copy (256px wide at the lightest, 16px at the
			// heaviest) plus a wider box: both cheaper and softer as the setting goes up.
			int w = Math.max(8, Math.round(256f * (float) Math.pow(16f / 256f, f)));
			int h = Math.max(1, Math.round(w * (float) src.getHeight() / src.getWidth()));
			int r = 1 + Math.round(2 * f);
			Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
			int[] px = new int[w * h];
			small.getPixels(px, 0, w, 0, 0, w, h);
			int[] tmp = new int[px.length];
			for (int pass = 0; pass < 3; pass++) {
				boxBlur(px, tmp, w, h, r, true);
				boxBlur(tmp, px, w, h, r, false);
			}
			return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
		} catch (Throwable ex) {
			return null;
		}
	}

	private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
		int len = horizontal ? w : h;
		int lines = horizontal ? h : w;

		for (int line = 0; line < lines; line++) {
			for (int i = 0; i < len; i++) {
				int rs = 0, gs = 0, bs = 0, n = 0;
				for (int k = -r; k <= r; k++) {
					int j = Math.min(len - 1, Math.max(0, i + k));
					int c = horizontal ? in[line * w + j] : in[j * w + line];
					rs += (c >> 16) & 0xFF;
					gs += (c >> 8) & 0xFF;
					bs += c & 0xFF;
					n++;
				}
				int idx = horizontal ? (line * w + i) : (i * w + line);
				out[idx] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
			}
		}
	}

	private void updateVideoButton(@Nullable PlayableItem i) {
		if ((i instanceof MusicTrackItem t) && t.hasVideo()) {
			videoButton.setText(R.string.video);
			videoButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.video, 0, 0, 0);
			videoButton.setVisibility(View.VISIBLE);
		} else if ((i != null) && !(i instanceof MusicTrackItem) && isPlayingVideo()) {
			// A video is playing right now (e.g. in the split view): offer to drop the picture.
			videoButton.setText(R.string.play_as_music);
			videoButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.music, 0, 0, 0);
			videoButton.setVisibility(View.VISIBLE);
		} else {
			videoButton.setVisibility(View.GONE);
		}
	}

	private boolean isPlayingVideo() {
		MediaEngine eng = getActivityDelegate().getMediaSessionCallback().getEngine();
		PlayableItem src = (eng == null) ? null : eng.getSource();
		return (src != null) && src.isVideo();
	}

	private void updatePlayState(@Nullable PlaybackStateCompat state) {
		int st = (state == null) ? PlaybackStateCompat.STATE_NONE : state.getState();
		boolean playing = (st == PlaybackStateCompat.STATE_PLAYING);
		playPause.setImageResource(playing ? R.drawable.pause : R.drawable.play);
		boolean busy = (st == PlaybackStateCompat.STATE_CONNECTING) ||
				(st == PlaybackStateCompat.STATE_BUFFERING) ||
				(st == PlaybackStateCompat.STATE_SKIPPING_TO_NEXT) ||
				(st == PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS) ||
				(st == PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM);
		loading.setVisibility(busy ? View.VISIBLE : View.GONE);

		if (playing) {
			if (isResumed() && !isHidden()) startProgress();
		} else if (state != null) {
			if (!seeking && (st == PlaybackStateCompat.STATE_PAUSED)) {
				int sec = (int) (state.getPosition() / 1000);
				seek.setProgress(sec);
				position.setText(time(sec));
			}
		}
	}

	private void updateModes() {
		BrowsableItemPrefs p = getModePrefs();
		PlayableItem i = getActivityDelegate().getMediaSessionCallback().getCurrentItem();
		int accent = ContextCompat.getColor(requireContext(), R.color.music_accent);
		int dim = 0xB3FFFFFF;

		if (p == null) {
			shuffle.setImageTintList(ColorStateList.valueOf(dim));
			repeat.setImageResource(R.drawable.repeat);
			repeat.setImageTintList(ColorStateList.valueOf(dim));
			return;
		}

		boolean shuffleOn = p.getShufflePref();
		shuffle.setImageResource(shuffleOn ? R.drawable.shuffle_filled : R.drawable.shuffle);
		shuffle.setImageTintList(ColorStateList.valueOf(shuffleOn ? accent : dim));

		boolean one = (i != null) && i.getId().equals(p.getRepeatItemPref());
		boolean all = p.getRepeatPref();
		repeat.setImageResource(one ? R.drawable.repeat_one :
				(all ? R.drawable.repeat_filled : R.drawable.repeat));
		repeat.setImageTintList(ColorStateList.valueOf((one || all) ? accent : dim));
		repeat.setContentDescription(getString(one ? R.string.music_repeat_one :
				(all ? R.string.music_repeat_all : R.string.music_repeat_off)));
	}

	/** Shuffle/Repeat live on the playing item's container -- the music queue, or its folder. */
	@Nullable
	private BrowsableItemPrefs getModePrefs() {
		PlayableItem i = getActivityDelegate().getMediaSessionCallback().getCurrentItem();
		if (i != null) return i.getParent().getPrefs();
		return (queue != null) ? queue.getPrefs() : null;
	}

	private void setDuration(long ms) {
		int sec = (int) (ms / 1000);
		if (seek.getMax() != sec) seek.setMax(sec);
		duration.setText(time(sec));
	}

	private void startProgress() {
		if (progressRunning) return;
		progressRunning = true;
		updateProgress();
	}

	private void stopProgress() {
		progressRunning = false;
		if (seek != null) seek.removeCallbacks(progressTask);
	}

	private void updateProgress() {
		if (!progressRunning || (getView() == null)) return;
		MediaSessionCallback cb = getActivityDelegate().getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();

		if ((eng == null) || (eng.getSource() == null) || !cb.isPlaying()) {
			progressRunning = false;
			return;
		}

		FutureSupplier<Long> pos = eng.getPosition();
		eng.getDuration().and(pos).main().onSuccess(h -> {
			if (getView() == null) return;
			if (h.value1 > 0) setDuration(h.value1);
			if (!seeking) {
				int sec = (int) (h.value2 / 1000);
				seek.setProgress(sec);
				position.setText(time(sec));
			}
		});
		seek.postDelayed(progressTask, PROGRESS_INTERVAL);
	}

	private static String time(int seconds) {
		StringBuilder sb = new StringBuilder(8);
		TextUtils.timeToString(sb, Math.max(0, seconds));
		return sb.toString();
	}

	private boolean queueIsEmpty() {
		return (queue == null) || queue.isEmpty();
	}

	// ---------------------------------------------------------------------------------------------
	// Actions
	// ---------------------------------------------------------------------------------------------

	private void onPlayPause() {
		MainActivityDelegate a = getActivityDelegate();
		MediaSessionCallback cb = a.getMediaSessionCallback();

		if (cb.getCurrentItem() != null) {
			if (cb.isPlaying()) cb.onPause();
			else cb.onPlay();
			return;
		}

		if (queueIsEmpty()) {
			showQueue(true);
			return;
		}

		MusicTrackItem t = queue.getSavedCurrent();
		if (t != null) {
			MusicPlayer.playTrack(a, t, queue.getSavedPosition());
		} else {
			t = queue.getTrack(0);
			if (t != null) MusicPlayer.playTrack(a, t, 0);
		}
	}

	private void onPrev() {
		MediaSessionCallback cb = getActivityDelegate().getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		if ((eng == null) || (eng.getSource() == null)) return;
		// Like any music player: back to the start of the song first, unless it's only just begun.
		eng.getPosition().main().onSuccess(pos -> {
			if (pos > RESTART_THRESHOLD) cb.onSeekTo(0);
			else cb.onSkipToPrevious();
		});
	}

	private void onNext() {
		MediaSessionCallback cb = getActivityDelegate().getMediaSessionCallback();
		if (cb.getCurrentItem() != null) cb.onSkipToNext();
	}

	private void onShuffle() {
		MediaSessionCallback cb = getActivityDelegate().getMediaSessionCallback();
		BrowsableItemPrefs p = getModePrefs();
		if (p == null) return;
		boolean enable = !p.getShufflePref();
		if (cb.getCurrentItem() != null) cb.onSetShuffleMode(enable ? SHUFFLE_MODE_ALL : SHUFFLE_MODE_NONE);
		else p.setShufflePref(enable);
		updateModes();
	}

	/** Cycles off, repeat the whole queue/folder, repeat the current song. */
	private void onRepeat() {
		MediaSessionCallback cb = getActivityDelegate().getMediaSessionCallback();
		PlayableItem i = cb.getCurrentItem();
		BrowsableItemPrefs p = getModePrefs();
		if (p == null) return;
		boolean one = (i != null) && i.getId().equals(p.getRepeatItemPref());
		boolean all = p.getRepeatPref();
		int mode;

		if (one) mode = REPEAT_MODE_NONE;
		else if (all) mode = (i != null) ? REPEAT_MODE_ONE : REPEAT_MODE_NONE;
		else mode = REPEAT_MODE_ALL;

		if (i != null) {
			cb.onSetRepeatMode(mode);
		} else {
			p.setRepeatItemPref(null);
			p.setRepeatPref(mode == REPEAT_MODE_ALL);
		}

		updateModes();
		UiUtils.showToast(requireContext(), (mode == REPEAT_MODE_ONE) ? R.string.music_repeat_one :
				(mode == REPEAT_MODE_ALL) ? R.string.music_repeat_all : R.string.music_repeat_off);
	}

	private void onEffects() {
		MainActivityDelegate a = getActivityDelegate();
		MediaEngine eng = a.getMediaSessionCallback().getEngine();
		PlayableItem src = (eng == null) ? null : eng.getSource();

		// The effects screen works on the live engine's effects: with nothing playing (or an engine
		// without effects support) it would just close itself again straight away.
		if ((src != null) && eng.supportsAudioEffects()) {
			a.showFragment(R.id.audio_effects_fragment);
		} else if ((src != null) && eng.showOwnAudioEffects()) {
			// A web-hosted YouTube player (the YouTube tab's, or the hidden fallback): its own
			// in-page equalizer, since Android's effects can't reach a web page's audio.
			DiagnosticLog.log("MUSIC", "effects: engine's own (in-page equalizer)", "engine=" + eng);
		} else {
			DiagnosticLog.log("MUSIC", "effects unavailable", "engine=" + eng, "item=" + src);
			UiUtils.showToast(requireContext(), (src == null) ? R.string.music_effects_play_first :
					R.string.music_effects_unavailable);
		}
	}

	private void onVideo() {
		MainActivityDelegate a = getActivityDelegate();
		PlayableItem i = a.getMediaSessionCallback().getCurrentItem();
		if (i instanceof MusicTrackItem) MusicPlayer.switchToVideo(a);
		else if (i != null) MusicPlayer.playCurrentAsMusic(a);
	}

	// ---------------------------------------------------------------------------------------------
	// Queue panel
	// ---------------------------------------------------------------------------------------------

	private void toggleQueue() {
		showQueue(queuePanel.getVisibility() != View.VISIBLE);
	}

	private void showQueue(boolean show) {
		if (queuePanel == null) return;
		boolean visible = queuePanel.getVisibility() == View.VISIBLE;
		if (show == visible) return;
		boolean land = isLandscape();
		queuePanel.animate().cancel();

		if (show) {
			adapter.reload();
			layoutQueuePanel();
			queuePanel.setVisibility(View.VISIBLE);
			queuePanel.setAlpha(0f);
			if (land) queuePanel.setTranslationX(-queuePanel.getWidth() / 3f);
			else queuePanel.setTranslationY(queuePanel.getHeight() / 3f);
			queuePanel.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(250).start();
			scrollToCurrent();
		} else {
			queuePanel.animate().alpha(0f)
					.translationX(land ? -queuePanel.getWidth() / 3f : 0f)
					.translationY(land ? 0f : queuePanel.getHeight() / 3f)
					.setDuration(200).withEndAction(() -> queuePanel.setVisibility(View.GONE)).start();
		}
	}

	private boolean isLandscape() {
		return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
	}

	/** Portrait: a sheet over the bottom ~70%; landscape: a panel over the left side. */
	private void layoutQueuePanel() {
		if ((queuePanel == null) || (content == null)) return;
		View root = getView();
		if (root == null) return;
		FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) queuePanel.getLayoutParams();
		int top = content.getPaddingTop();
		int bottom = content.getPaddingBottom();
		int avail = root.getHeight() - top - bottom;
		int margin = UiUtils.toIntPx(requireContext(), 8);
		int w, h, gravity, mt, mb;

		if (isLandscape()) {
			w = Math.round(root.getWidth() * 0.55f);
			h = ViewGroup.LayoutParams.MATCH_PARENT;
			gravity = Gravity.START | Gravity.TOP;
			mt = top + margin;
			mb = bottom + margin;
		} else {
			w = ViewGroup.LayoutParams.MATCH_PARENT;
			h = Math.max(0, Math.round(avail * 0.72f));
			gravity = Gravity.BOTTOM;
			mt = 0;
			mb = bottom + margin;
		}

		if ((lp.width == w) && (lp.height == h) && (lp.gravity == gravity) && (lp.topMargin == mt) &&
				(lp.bottomMargin == mb)) {
			return;
		}

		lp.width = w;
		lp.height = h;
		lp.gravity = gravity;
		lp.topMargin = mt;
		lp.bottomMargin = mb;
		queuePanel.setLayoutParams(lp);
	}

	private void scrollToCurrent() {
		PlayableItem cur = getActivityDelegate().getMediaSessionCallback().getCurrentItem();
		if ((queue == null) || !(cur instanceof MusicTrackItem)) return;
		int idx = queue.indexOf(cur);
		if (idx >= 0) queueList.scrollToPosition(idx);
	}

	private void updateQueueHeader(int count) {
		queueCount.setText(getResources().getQuantityString(R.plurals.music_queue_size, count, count));
		queueEmpty.setVisibility((count == 0) ? View.VISIBLE : View.GONE);
	}

	// ---------------------------------------------------------------------------------------------
	// Listeners
	// ---------------------------------------------------------------------------------------------

	@Override
	public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
		if (getView() == null) return;
		PlayableItem cur = cb.getCurrentItem();
		if ((cur != null) && (cur != shownItem)) {
			displayItem(cur, false);
			adapter.notifyDataSetChanged();
		}
		updatePlayState(state);
		updateModes();
		updateVideoButton(shownItem);
	}

	@Override
	public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
		if (getView() == null) return;
		displayItem((newItem != null) ? newItem : getDisplayItem(), false);
		adapter.notifyDataSetChanged();
		updateModes();
	}

	@Override
	public void onPlaybackError(String message) {
		if ((getView() != null) && (message != null) && !message.isEmpty())
			UiUtils.showToast(requireContext(), message);
	}

	@Override
	public void onDurationChanged(PlayableItem i) {
		if ((getView() != null) && (i == shownItem)) i.getDuration().main().onSuccess(d -> {
			if ((d != null) && (d > 0)) setDuration(d);
		});
	}

	@Override
	public void onQueueChanged(MusicQueue q) {
		if (getView() == null) return;
		adapter.reload();
		if (getActivityDelegate().getMediaSessionCallback().getCurrentItem() == null)
			displayItem(getDisplayItem(), true);
	}

	@Override
	public void onTrackUpdated(MusicQueue q, MusicTrackItem track) {
		if (getView() == null) return;
		if (track == shownItem) displayItem(track, true);
		adapter.trackUpdated(track);
	}

	@Override
	public void onTrackFailed(MusicQueue q, MusicTrackItem track, Throwable err) {
		if (getView() == null) return;
		loading.setVisibility(View.GONE);
		UiUtils.showToast(requireContext(), getString(R.string.music_stream_failed, track.getName()));
	}

	// ---------------------------------------------------------------------------------------------
	// Queue list
	// ---------------------------------------------------------------------------------------------

	private final class QueueAdapter extends RecyclerView.Adapter<QueueHolder> {
		private final List<MusicTrackItem> tracks = new ArrayList<>();

		@SuppressLint("NotifyDataSetChanged")
		void reload() {
			tracks.clear();
			if (queue != null) tracks.addAll(queue.getTracks());
			notifyDataSetChanged();
			updateQueueHeader(tracks.size());
		}

		void trackUpdated(MusicTrackItem t) {
			int idx = tracks.indexOf(t);
			if (idx >= 0) notifyItemChanged(idx);
		}

		void moved(int from, int to) {
			tracks.add(to, tracks.remove(from));
			notifyItemMoved(from, to);
		}

		@NonNull
		@Override
		public QueueHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext())
					.inflate(R.layout.music_queue_item, parent, false);
			return new QueueHolder(v);
		}

		@Override
		public void onBindViewHolder(@NonNull QueueHolder h, int position) {
			h.bind(tracks.get(position));
		}

		@Override
		public int getItemCount() {
			return tracks.size();
		}
	}

	private final class QueueHolder extends RecyclerView.ViewHolder {
		final ImageView art;
		final ImageView playing;
		final TextView title;
		final TextView subtitle;
		@Nullable
		MusicTrackItem track;

		@SuppressLint("ClickableViewAccessibility")
		QueueHolder(View v) {
			super(v);
			art = v.findViewById(R.id.music_row_art);
			playing = v.findViewById(R.id.music_row_playing);
			title = v.findViewById(R.id.music_row_title);
			subtitle = v.findViewById(R.id.music_row_subtitle);
			v.setOnClickListener(x -> {
				MusicTrackItem t = track;
				if (t != null) MusicPlayer.playTrack(getActivityDelegate(), t, 0);
			});
			v.findViewById(R.id.music_row_drag).setOnTouchListener((x, e) -> {
				if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touchHelper.startDrag(this);
				return false;
			});
		}

		void bind(MusicTrackItem t) {
			track = t;
			boolean current = t.equals(getActivityDelegate().getMediaSessionCallback().getCurrentItem());
			title.setText(t.getName());
			title.setTextColor(current ?
					ContextCompat.getColor(requireContext(), R.color.music_accent) : 0xFFFFFFFF);
			String a = t.getArtistName();
			subtitle.setText((a != null) ? a : ((t.getVideoId() != null) ? "YouTube" : ""));
			playing.setVisibility(current ? View.VISIBLE : View.GONE);
			art.setImageResource(R.drawable.music_art_placeholder);

			MediaLib lib = getActivityDelegate().getLib();
			String uri = t.getArtUri();

			if (uri != null) {
				lib.getBitmap(uri, true, true).main().onSuccess(bm -> {
					if ((track == t) && (bm != null)) art.setImageBitmap(cropLetterbox(bm));
				});
			} else {
				t.getMediaData().main().onSuccess(md -> {
					if (track != t) return;
					Bitmap bm = md.getBitmap(METADATA_KEY_ALBUM_ART);
					if (bm != null) {
						art.setImageBitmap(bm);
						return;
					}
					String u = md.getString(METADATA_KEY_ALBUM_ART_URI);
					if (u == null) return;
					lib.getBitmap(u, true, true).main().onSuccess(b -> {
						if ((track == t) && (b != null)) art.setImageBitmap(b);
					});
					if (subtitle.getText().length() == 0) {
						String ar = md.getString(METADATA_KEY_ARTIST);
						if (ar != null) subtitle.setText(ar);
					}
				});
			}
		}
	}

	private final class QueueTouchCallback extends ItemTouchHelper.SimpleCallback {
		private int dragFrom = -1;
		private int dragTo = -1;

		QueueTouchCallback() {
			super(ItemTouchHelper.UP | ItemTouchHelper.DOWN,
					ItemTouchHelper.START | ItemTouchHelper.END);
		}

		@Override
		public boolean isLongPressDragEnabled() {
			// Dragging starts from the handle only -- a long press is too easy to trigger by accident
			// while scrolling in a moving car.
			return false;
		}

		@Override
		public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
													@NonNull RecyclerView.ViewHolder to) {
			int f = from.getAdapterPosition();
			int t = to.getAdapterPosition();
			if ((f == RecyclerView.NO_POSITION) || (t == RecyclerView.NO_POSITION)) return false;
			if (dragFrom == -1) dragFrom = f;
			dragTo = t;
			adapter.moved(f, t);
			return true;
		}

		@Override
		public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
			super.clearView(rv, h);
			int f = dragFrom;
			int t = dragTo;
			dragFrom = dragTo = -1;
			// Committed once the drag ends, not on every intermediate step.
			if ((f != -1) && (t != -1) && (f != t) && (queue != null)) {
				queue.removeListener(MusicPlayerFragment.this);
				queue.move(f, t);
				queue.addListener(MusicPlayerFragment.this);
			}
		}

		@Override
		public void onSwiped(@NonNull RecyclerView.ViewHolder h, int direction) {
			int pos = h.getAdapterPosition();
			if ((pos == RecyclerView.NO_POSITION) || (queue == null)) return;
			queue.remove(pos);
		}
	}
}
