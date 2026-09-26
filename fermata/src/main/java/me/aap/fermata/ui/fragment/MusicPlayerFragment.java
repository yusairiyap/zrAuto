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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicAddon;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicQueue;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.InfoOverlayView;
import me.aap.fermata.ui.view.LoadingDimView;
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
	private LoadingDimView loading;
	private View playLoading;
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
	private View queueDismiss;
	private TextView queueCount;
	private TextView queueEmpty;
	private RecyclerView queueList;
	private QueueAdapter adapter;
	private ItemTouchHelper touchHelper;
	@Nullable
	private MusicQueue queue;
	@Nullable
	private InfoOverlayView infoOverlay;
	// The context this tab's views are inflated with: the app theme plus the tab's own palette, dark
	// or light to match it (see onCreateView()).
	private Context palette;
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
		Context ctx = inflater.getContext();
		palette = new ContextThemeWrapper(ctx,
				isLightTheme(ctx) ? R.style.MusicPalette_Light : R.style.MusicPalette_Dark);
		return inflater.cloneInContext(palette).inflate(R.layout.music_player_fragment, container, false);
	}

	/** Whether the app's current theme is a light one, going by its background's lightness. */
	private static boolean isLightTheme(Context ctx) {
		TypedValue tv = new TypedValue();
		if (!ctx.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)) return false;
		int color;
		if ((tv.type >= TypedValue.TYPE_FIRST_COLOR_INT) && (tv.type <= TypedValue.TYPE_LAST_COLOR_INT)) {
			color = tv.data;
		} else if (tv.resourceId != 0) {
			color = ContextCompat.getColor(ctx, tv.resourceId);
		} else {
			return false;
		}
		return ColorUtils.calculateLuminance(color) > 0.5;
	}

	/** A color of this tab's own palette (see MusicPalette in music.xml). */
	private int paletteColor(int attr) {
		TypedValue tv = new TypedValue();
		palette.getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
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
		playLoading = view.findViewById(R.id.music_play_loading);
		repeat = view.findViewById(R.id.music_repeat);
		videoButton = view.findViewById(R.id.music_video_button);
		queuePanel = view.findViewById(R.id.music_queue_panel);
		queueDismiss = view.findViewById(R.id.music_queue_dismiss);
		queueDismiss.setOnClickListener(v -> showQueue(false));
		queueCount = view.findViewById(R.id.music_queue_count);
		queueEmpty = view.findViewById(R.id.music_queue_empty);
		queueList = view.findViewById(R.id.music_queue_list);
		queue = MusicPlayer.getQueue(a);

		// Like every other tab: tool_bar/nav_bar are drawn over the fragment, so the content has to
		// reserve room for them itself (see MainActivityDelegate#insetScrollableContent).
		a.insetScrollableContent(content);
		content.addOnLayoutChangeListener(
				(v, l, t, r, b, ol, ot, or, ob) -> v.post(this::layoutQueuePanel));

		playPause.setOnClickListener(v -> onPlayPause());
		addPressEffect(playPause);
		enableQueueDrag(view.findViewById(R.id.music_queue_grip));
		enableQueueDrag(view.findViewById(R.id.music_queue_header));
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
	 * The same clock/battery/temperature/distance overlay as fullscreen video, with the same items
	 * and size (clock, battery and temperature when nothing is picked in its settings).
	 */
	private void addInfoOverlay(FrameLayout holder) {
		InfoOverlayView o = new InfoOverlayView(requireContext());
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				(isLandscape() ? Gravity.END : Gravity.CENTER_HORIZONTAL) | Gravity.TOP);
		holder.addView(o, lp);
		infoOverlay = o;
		applyInfoOverlayPrefs();
	}

	/**
	 * The Info Overlay settings, as for fullscreen video: shown unless its position is None or no
	 * item is on, with the same items, icons and size. Its position is this tab's own, though, and
	 * "only while the control panel shows" doesn't apply (this tab has no control panel).
	 */
	private void applyInfoOverlayPrefs() {
		InfoOverlayView o = infoOverlay;
		if (o == null) return;
		MainActivityPrefs p = getActivityDelegate().getPrefs();
		boolean clock = p.getInfoOverlayShowClockPref();
		boolean battery = p.getInfoOverlayShowBatteryPctPref();
		boolean temp = p.getInfoOverlayShowBatteryTempPref();
		boolean distance = p.getInfoOverlayShowDistancePref();
		boolean show = (p.getClockPosPref() != MainActivityPrefs.CLOCK_POS_NONE) &&
				(clock || battery || temp || distance);
		((View) o.getParent()).setVisibility(show ? View.VISIBLE : View.GONE);
		if (!show) return;
		o.setSize(p.getInfoOverlaySizePref());
		o.setItems(clock, p.getInfoOverlayShowClockIconPref(), battery,
				p.getInfoOverlayShowBatteryIconPref(), temp, p.getInfoOverlayShowTempIconPref(), distance,
				p.getInfoOverlayShowDistanceIconPref());
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
		if (a != null) suppressOverlays(a, true);
	}

	@Override
	public void switchingTo(@NonNull ActivityFragment to) {
		super.switchingTo(to);
		if (getContext() == null) return;
		suppressOverlays(getActivityDelegate(), false);
	}

	/**
	 * This tab has its own controls and loading indicator: no control panel, floating buttons or
	 * content loading indicator over it.
	 */
	private static void suppressOverlays(MainActivityDelegate a, boolean suppress) {
		a.setOverlaysSuppressed(suppress);
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
			// Also on every resume (e.g. Android Auto giving the screen back), not only when this tab
			// is switched to: something may have shown them meanwhile.
			suppressOverlays(getActivityDelegate(), true);
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
		suppressOverlays(a, on);

		if (on) {
			a.getMediaSessionCallback().addBroadcastListener(this);
			a.getMediaServiceBinder().addBroadcastListener(this);
			if (queue != null) queue.addListener(this);
			settings().addBroadcastListener(this);
			a.getPrefs().addBroadcastListener(this);
			// The Info Overlay settings may have changed while this tab was away.
			applyInfoOverlayPrefs();
		} else {
			a.getMediaSessionCallback().removeBroadcastListener(this);
			a.getMediaServiceBinder().removeBroadcastListener(this);
			if (queue != null) queue.removeListener(this);
			settings().removeBroadcastListener(this);
			a.getPrefs().removeBroadcastListener(this);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// State
	// ---------------------------------------------------------------------------------------------

	/** The item to show: the playing queue track, else what's playing, else where the queue left off. */
	@Nullable
	private PlayableItem getDisplayItem() {
		MusicTrackItem track = currentTrack();
		if (track != null) return track;
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
		// Also when the item is the same: music/video mode may have changed while this tab was away
		// (e.g. its Video button, then back here).
		updateVideoButton(shownItem);
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
		setTitle(i.getName());
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
			if ((t != null) && !t.isEmpty()) setTitle(t);
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
		if (isYoutube(i)) return "YouTube";
		// A library item's folder/playlist name; an internal placeholder parent (a player's own
		// root, e.g. YouTube's) has no name worth showing.
		BrowsableItem p = i.getParent();
		return p.isExternal() ? "" : p.getName();
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
		artBitmap = bm;

		if (bm == null) {
			// Through the palette: the placeholder is drawn in its colors.
			crossfade(art, ContextCompat.getDrawable(palette, R.drawable.music_art_placeholder));
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
		if (!Collections.disjoint(MainActivityPrefs.INFO_OVERLAY_PREFS, prefs)) {
			applyInfoOverlayPrefs();
			return;
		}
		if (prefs.contains(MusicAddon.BG_BLUR)) updateBackground(false);
		if (prefs.contains(MusicAddon.BG_ZOOM)) updateZoom();
	}

	private boolean isYoutube(PlayableItem i) {
		if (i instanceof MusicTrackItem t) return t.getVideoId() != null;
		// The YouTube player's own item for what it's playing (not played as music).
		MediaEngine eng = getActivityDelegate().getMediaSessionCallback().getEngine();
		return (eng != null) && (eng.getId() == MediaPrefs.MEDIA_ENG_YT) && (i == eng.getSource());
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

	/** The queue track playing now, if any -- see {@link MusicPlayer#getCurrentTrack}. */
	@Nullable
	private MusicTrackItem currentTrack() {
		return MusicPlayer.getCurrentTrack(getActivityDelegate().getMediaSessionCallback());
	}

	/** Whether what's playing is playing as music: a queue track, with YouTube in music mode. */
	private boolean playingAsMusic() {
		MusicTrackItem t = currentTrack();
		return (t != null) && ((t.getVideoId() == null) || MusicPlayer.isYoutubeAudioMode());
	}

	/** What Shuffle/Repeat apply to: the playing queue track, else whatever else is playing. */
	@Nullable
	private PlayableItem modeItem() {
		MusicTrackItem t = currentTrack();
		return (t != null) ? t : getActivityDelegate().getMediaSessionCallback().getCurrentItem();
	}

	/** The queue track shown while nothing plays (where the queue left off), if it has a video. */
	@Nullable
	private MusicTrackItem idleVideoTrack() {
		if (getActivityDelegate().getMediaSessionCallback().getCurrentItem() != null) return null;
		return ((shownItem instanceof MusicTrackItem t) && t.hasVideo()) ? t : null;
	}

	private void updateVideoButton(@Nullable PlayableItem i) {
		MusicTrackItem t = currentTrack();

		if ((playingAsMusic() && t.hasVideo()) || (idleVideoTrack() != null)) {
			videoButton.setText(R.string.video);
			videoButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.video, 0, 0, 0);
			videoButton.setVisibility(View.VISIBLE);
		} else if ((i != null) && isPlayingVideo()) {
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
		loading.setLoading(busy);
		setPlayLoading(busy);

		if (playing) {
			if (isResumed() && !isHidden()) startProgress();
		} else if (state != null) {
			if (!seeking && (st == PlaybackStateCompat.STATE_PAUSED)) {
				int sec = (int) (state.getPosition() / 1000);
				seek.setProgress(sec);
				setText(position, time(sec));
			}
		}
	}

	/** While loading, a spinner takes the place of the play/pause icon. */
	private void setPlayLoading(boolean busy) {
		if (busy == (playLoading.getVisibility() == View.VISIBLE)) return;
		playLoading.animate().cancel();

		if (busy) {
			playPause.setImageAlpha(0);
			playLoading.setAlpha(0f);
			playLoading.setVisibility(View.VISIBLE);
			playLoading.animate().alpha(1f).setDuration(150).start();
		} else {
			playLoading.setVisibility(View.GONE);
			playPause.setImageAlpha(255);
		}
	}

	/** A button that shrinks a little while held, and springs back when let go. */
	@SuppressLint("ClickableViewAccessibility")
	private static void addPressEffect(View button) {
		button.setOnTouchListener((v, e) -> {
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(120)
						.setInterpolator(new DecelerateInterpolator()).start();
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f)
						.setDuration(280).setInterpolator(new OvershootInterpolator(3f)).start();
			}
			return false; // Not consumed: the click still happens.
		});
	}

	private void updateModes() {
		BrowsableItemPrefs p = getModePrefs();
		PlayableItem i = modeItem();
		int accent = ContextCompat.getColor(requireContext(), R.color.music_accent);
		int dim = paletteColor(R.attr.musicIconSecondary);

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
		PlayableItem i = modeItem();
		if (i != null) return i.getParent().getPrefs();
		return (queue != null) ? queue.getPrefs() : null;
	}

	private void setDuration(long ms) {
		int sec = (int) (ms / 1000);
		if (seek.getMax() != sec) seek.setMax(sec);
		setText(duration, time(sec));
	}

	// Setting a TextView's text, even the same text, restarts the title's marquee (its own, or via
	// the relayout); these only touch it when the text actually changes.
	private void setTitle(CharSequence text) {
		setText(title, text);
	}

	private static void setText(TextView v, CharSequence text) {
		if (!android.text.TextUtils.equals(v.getText(), text)) v.setText(text);
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
				setText(position, time(sec));
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
		// Through the session when it's the session's own item, so its shuffle state follows;
		// a YouTube queue track isn't (the session's item is the YouTube player's own).
		PlayableItem i = modeItem();
		if ((i != null) && (i == cb.getCurrentItem())) {
			cb.onSetShuffleMode(enable ? SHUFFLE_MODE_ALL : SHUFFLE_MODE_NONE);
		} else {
			p.setShufflePref(enable);
		}
		updateModes();
	}

	/** Cycles off, repeat the whole queue/folder, repeat the current song. */
	private void onRepeat() {
		MediaSessionCallback cb = getActivityDelegate().getMediaSessionCallback();
		PlayableItem i = modeItem();
		BrowsableItemPrefs p = getModePrefs();
		if (p == null) return;
		boolean one = (i != null) && i.getId().equals(p.getRepeatItemPref());
		boolean all = p.getRepeatPref();
		int mode;

		if (one) mode = REPEAT_MODE_NONE;
		else if (all) mode = (i != null) ? REPEAT_MODE_ONE : REPEAT_MODE_NONE;
		else mode = REPEAT_MODE_ALL;

		if ((i != null) && (i == cb.getCurrentItem())) {
			cb.onSetRepeatMode(mode);
		} else {
			p.setRepeatItemPref(((i != null) && (mode == REPEAT_MODE_ONE)) ? i.getId() : null);
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
			// The YouTube player: its own in-page equalizer, since Android's effects can't reach a web
			// page's audio.
			DiagnosticLog.log("MUSIC", "effects: engine's own (in-page equalizer)", "engine=" + eng);
		} else {
			DiagnosticLog.log("MUSIC", "effects unavailable", "engine=" + eng, "item=" + src);
			UiUtils.showToast(requireContext(), (src == null) ? R.string.music_effects_play_first :
					R.string.music_effects_unavailable);
		}
	}

	private void onVideo() {
		MainActivityDelegate a = getActivityDelegate();
		MusicTrackItem idle = idleVideoTrack();
		if (idle != null) MusicPlayer.watch(a, idle);
		else if (playingAsMusic()) MusicPlayer.switchToVideo(a);
		else if (a.getMediaSessionCallback().getCurrentItem() != null) MusicPlayer.playCurrentAsMusic(a);
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
		queuePanel.animate().cancel();
		showQueueDismiss(show);

		if (show) {
			adapter.reload();
			layoutQueuePanel();
			queuePanel.setVisibility(View.VISIBLE);
			queuePanel.setAlpha(0f);
			queuePanel.setTranslationY(queuePanel.getHeight() / 3f);
			queuePanel.animate().alpha(1f).translationY(0f).setDuration(250)
					.setInterpolator(new DecelerateInterpolator()).start();
			scrollToCurrent();
		} else {
			queuePanel.animate().alpha(0f).translationY(queuePanel.getHeight() / 3f).setDuration(200)
					.setInterpolator(new DecelerateInterpolator())
					.withEndAction(() -> queuePanel.setVisibility(View.GONE)).start();
		}
	}

	/** The layer behind the open queue panel: dims the player a little, and a tap on it closes. */
	private void showQueueDismiss(boolean show) {
		View v = queueDismiss;
		if (v == null) return;
		v.animate().cancel();

		if (show) {
			v.setAlpha(0f);
			v.setVisibility(View.VISIBLE);
			v.animate().alpha(1f).setDuration(250).start();
		} else {
			v.animate().alpha(0f).setDuration(200).withEndAction(() -> v.setVisibility(View.GONE))
					.start();
		}
	}

	private boolean isLandscape() {
		return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
	}

	/** Portrait: a sheet over the bottom ~70%; landscape: a panel over the left side. */
	/**
	 * Dragging {@code handle} down moves the queue panel the way it closes: let go past a third of
	 * the way, or flung, and it closes; otherwise it springs back.
	 */
	@SuppressLint("ClickableViewAccessibility")
	private void enableQueueDrag(View handle) {
		ViewConfiguration vc = ViewConfiguration.get(requireContext());
		int slop = vc.getScaledTouchSlop();
		int fling = vc.getScaledMinimumFlingVelocity() * 4;

		handle.setOnTouchListener(new View.OnTouchListener() {
			private float start;
			private boolean dragging;
			@Nullable
			private VelocityTracker velocity;

			@Override
			public boolean onTouch(View v, MotionEvent e) {
				float p = e.getRawY();

				switch (e.getActionMasked()) {
					case MotionEvent.ACTION_DOWN -> {
						start = p;
						dragging = false;
						velocity = VelocityTracker.obtain();
						velocity.addMovement(e);
						queuePanel.animate().cancel();
						return true;
					}
					case MotionEvent.ACTION_MOVE -> {
						if (velocity != null) velocity.addMovement(e);
						// Only ever towards closing.
						float off = Math.max(0, p - start);
						if (!dragging && (off < slop)) return true;
						dragging = true;
						float size = queuePanel.getHeight();
						queuePanel.setTranslationY(off);
						if (size > 0) queuePanel.setAlpha(1f - 0.5f * off / size);
						return true;
					}
					case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						float speed = 0;
						if (velocity != null) {
							velocity.addMovement(e);
							velocity.computeCurrentVelocity(1000);
							speed = velocity.getYVelocity();
							velocity.recycle();
							velocity = null;
						}
						if (!dragging) return false;
						dragging = false;
						float size = queuePanel.getHeight();
						float off = queuePanel.getTranslationY();
						if ((off > size / 3f) || (speed > fling)) closeQueueFromDrag(size);
						else queuePanel.animate().translationY(0f).alpha(1f).setDuration(200)
								.setInterpolator(new DecelerateInterpolator()).start();
						return true;
					}
				}
				return false;
			}
		});
	}

	/** Finishes a drag-to-close: the panel carries on down out of the screen. */
	private void closeQueueFromDrag(float size) {
		showQueueDismiss(false);
		queuePanel.animate().translationY(size).alpha(0f).setDuration(180)
				.setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
					queuePanel.setVisibility(View.GONE);
					queuePanel.setTranslationY(0f);
					queuePanel.setAlpha(1f);
				}).start();
	}

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
		MusicTrackItem cur = currentTrack();
		if ((queue == null) || (cur == null)) return;
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
		PlayableItem cur = getDisplayItem();
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
		displayItem(getDisplayItem(), false);
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
			boolean current = t.equals(currentTrack());
			title.setText(t.getName());
			title.setTextColor(current ?
					ContextCompat.getColor(requireContext(), R.color.music_accent) :
					paletteColor(R.attr.musicTextPrimary));
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
