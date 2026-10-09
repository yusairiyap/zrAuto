package me.aap.fermata.engine.exoplayer;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedNull;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.misc.Assert.assertMainThread;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.audiofx.PresetReverb;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.datasource.cronet.CronetUtil;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;

import org.chromium.net.CronetEngine;

import java.lang.reflect.Field;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Executors;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.SubGenAddon;
import me.aap.fermata.addon.TranslateAddon;
import me.aap.fermata.addon.TranslateAddon.Translator;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.media.engine.AudioEffects;
import me.aap.fermata.media.engine.AudioStreamInfo;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.engine.MediaEngineBase;
import me.aap.fermata.media.engine.SubtitleStreamInfo;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.media.sub.SubGrid;
import me.aap.fermata.media.sub.Subtitles;
import me.aap.fermata.ui.view.VideoView;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.text.SharedTextBuilder;

/**
 * @author Andrey Pavlenko
 */
@UnstableApi
public class ExoPlayerEngine extends MediaEngineBase implements Player.Listener {
	private static final DataSource.Factory httpDsFactory;

	static {
		CronetEngine cre;
		try {
			cre = CronetUtil.buildCronetEngine(FermataApplication.get(),
					"zrAuto/" + BuildConfig.VERSION_NAME, true);
		} catch (Throwable ex) {
			Log.e(ex, "Failed to build Cronet engine, falling back to DefaultHttpDataSource");
			cre = null;
		}
		if (cre != null) {
			httpDsFactory = new CronetDataSource.Factory(cre, Executors.newSingleThreadExecutor());
		} else {
			CookieManager cookieManager = new CookieManager();
			cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
			CookieHandler.setDefault(cookieManager);
			httpDsFactory = new DefaultHttpDataSource.Factory();
		}
	}

	private final Accessor accessor = new Accessor(this);
	private final Timeline.Period period = new Timeline.Period();
	private final PendingLoadAudioProcessor audioProc = new PendingLoadAudioProcessor(accessor);
	private final StageAudioProcessor stageProc = new StageAudioProcessor();
	private final ExoPlayer player;
	@Nullable
	private AudioEffects audioEffects;
	private volatile PlayableItem source;
	private boolean preparing;
	private boolean buffering;
	private boolean isHls;
	private Runnable drainBuffer;

	public ExoPlayerEngine(Context ctx, Listener listener) {
		super(listener);
		DefaultDataSource.Factory dsFactory = new DefaultDataSource.Factory(ctx, httpDsFactory);
		MediaSource.Factory msFactory =
				new DefaultMediaSourceFactory(ctx).setDataSourceFactory(dsFactory);
		player = new ExoPlayer.Builder(ctx, new DefaultRenderersFactory(ctx) {
			{
				setEnableDecoderFallback(true);
				setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON);
			}

			@Override
			protected AudioSink buildAudioSink(@NonNull Context context,
																				 boolean enableFloatOutput,
																				 boolean enableAudioTrackPlaybackParams) {
				return new DefaultAudioSink.Builder(ctx)
						.setAudioTrackBufferSizeProvider(new DefaultAudioTrackBufferSizeProvider.Builder()
								.setMaxPcmBufferDurationUs(5000_000)
								.setPcmBufferMultiplicationFactor(16)
								.setOffloadBufferDurationUs(120_000_000).build())
						.setAudioProcessorChain(
								new DefaultAudioSink.DefaultAudioProcessorChain(audioProc, stageProc)).build();
			}
		}).setMediaSourceFactory(msFactory).setWakeMode(C.WAKE_MODE_LOCAL).build();
		player.addListener(this);

		try {
			Field f = player.getClass().getDeclaredField("internalPlayer");
			f.setAccessible(true);
			Object internal = requireNonNull(f.get(player));
			f = internal.getClass().getDeclaredField("handler");
			f.setAccessible(true);
			var handler = (HandlerWrapper) requireNonNull(f.get(internal));
			drainBuffer = () -> {
				try {
					handler.sendEmptyMessage(2 /*MSG_DO_SOME_WORK*/);
				} catch (Exception err) {
					Log.w(err);
				}
			};
		} catch (Exception err) {
			Log.w(err);
		}
	}

	@Override
	public int getId() {
		return MediaPrefs.MEDIA_ENG_EXO;
	}

	@SuppressLint("SwitchIntDef")
	@Override
	public void prepare(PlayableItem source) {
		stallGen++;
		stallRetried = false;
		stallNudged = false;
		subGenWaits = 0;
		firstFrame = false;
		// A reused player keeps playWhenReady through stop() and the end of a track: the next item would
		// start playing at full volume before start() fades it in.
		player.setPlayWhenReady(false);
		if (this.source == null) {
			resetFade();
			stopped(false);
		} else {
			stop();
		}
		this.source = source;
		// A Music tab track never shows subtitles (its tab does not, and its picture is off): generating
		// them would only make its sound wait on the transcriptor.
		boolean musicTrack = source instanceof me.aap.fermata.addon.music.MusicTrackItem;
		audioProc.setBypass(musicTrack);
		if (musicTrack && source.getPrefs().getBooleanPref(me.aap.fermata.addon.SubGenAddon.ENABLED)) {
			DiagnosticLog.log("ENGINE", "SubGen off for a Music tab track", "item=" + source);
		}
		// A downloaded YouTube video sounds as it does on YouTube: the page's equalizer applies.
		stageProc.setFx(me.aap.fermata.ytdl.YtOffline.isDownloadedYoutube(source));
		accessor.sourceChanged(source);
		// Keeps the CPU (and for a stream the network) awake while playing with the screen off.
		player.setWakeMode(source.isNetResource() ? C.WAKE_MODE_NETWORK : C.WAKE_MODE_LOCAL);
		preparing = true;
		buffering = false;

		Uri uri = source.getLocation();
		MediaItem m = MediaItem.fromUri(uri);
		isHls = Util.inferContentType(uri) == C.CONTENT_TYPE_HLS;
		setVideoTrackDisabled(videoOff(source, shown));
		String oid = source.getOrigId();
		// The hidden surface a stalled file needed is not for the files after it.
		if ((dummySurface != null) && ((oid == null) || !keepPicture.contains(oid))) {
			player.clearVideoSurface(dummySurface);
			releaseDummySurface();
		}
		if ((shown == null) && (dummySurface == null) && (oid != null) && keepPicture.contains(oid)) {
			useDummySurface();
		}
		player.setMediaItem(m);
		player.prepare();
	}

	/**
	 * Audio-only playback (the Music tab) of a file that also has video: disabling the video track
	 * type stops ExoPlayer from decoding it at all, rather than just having no surface to draw to.
	 */
	private void setVideoTrackDisabled(boolean disabled) {
		var params = player.getTrackSelectionParameters();
		if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == disabled) return;
		player.setTrackSelectionParameters(
				params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled).build());
		// The picture switched on while the file plays on: its samples were not kept while it was off,
		// so it would stay black until the next key frame (a still-image video has very few of them).
		// Seeking to where it is starts the picture from the key frame before it.
		if (!disabled && !preparing && (player.getPlaybackState() == Player.STATE_READY)) {
			player.seekTo(player.getCurrentPosition());
		}
	}

	@Override
	public boolean adoptSource(PlayableItem src) {
		PlayableItem cur = source;
		if ((cur == null) || !cur.getLocation().equals(src.getLocation())) return false;
		source = src;
		accessor.sourceChanged(src);
		setVideoTrackDisabled(videoOff(src, shown));
		return true;
	}

	@Override
	public void start() {
		// The sound comes in rather than starting at full volume, as YouTube's does.
		fadeIn();
		player.setPlayWhenReady(true);
		listener.onEngineStarted(this);
		started();
		watchForStall();
		if (shown != null) watchBlackPicture();
	}

	// Identifies the latest start, so that what an earlier one still has to check is dropped.
	private int stallGen;

	/**
	 * A file on the phone that is ready and told to play, yet does not move at all for a few seconds,
	 * is stuck (some files stall this player from their first second): reported as an error at once,
	 * so the platform player takes over, instead of after the 10 seconds of silence the player itself
	 * waits before saying so.
	 * <p>
	 * Kept up for as long as the track plays, not only after its start: a file can also freeze half
	 * way through (seen in the background, picture off, with no pause and no error: the position just
	 * stopped). Then it is nudged first (picture on, a surface, a seek to where it is) before the error.
	 */
	private void watchForStall() {
		int gen = ++stallGen;
		PlayableItem src = source;
		if ((src == null) || src.isNetResource()) return;
		checkStall(gen, src, player.getCurrentPosition(), false, 3000);
	}

	private void checkStall(int gen, PlayableItem src, long at, boolean midPlay, long delay) {
		FermataApplication.get().getHandler().postDelayed(() -> {
			if ((gen != stallGen) || (source != src) || (accessor.player == null)) return;
			// Paused: start() watches again.
			if (!player.getPlayWhenReady()) return;
			long now = player.getCurrentPosition();
			if ((player.getPlaybackState() != Player.STATE_READY) || (now > at + 300)) {
				// Moving (or legitimately not: buffering, ended): watched on.
				checkStall(gen, src, now, true, 4000);
				return;
			}
			boolean off = player.getTrackSelectionParameters().disabledTrackTypes
					.contains(C.TRACK_TYPE_VIDEO);
			Format a = player.getAudioFormat();
			Format v = player.getVideoFormat();
			DiagnosticLog.log("ENGINE", midPlay ? "no progress while playing" : "no progress after start",
					"item=" + src, "pos=" + now, "videoOff=" + off, "screen=" + (shown != null),
					"hiddenSurface=" + (dummySurface != null), "retried=" + stallRetried,
					"nudged=" + stallNudged, "subGen=" + (!audioProc.isBypassed() && audioProc.isTranscribing()),
					"audio=" + ((a == null) ? null : a.sampleMimeType + "/" + a.sampleRate + "Hz/" + a.channelCount + "ch"),
					"video=" + ((v == null) ? null : v.sampleMimeType + "/" + v.width + "x" + v.height));
			if (!audioProc.isBypassed() && audioProc.isTranscribing() && !midPlay && (shown != null) &&
					(subGenWaits++ < 3)) {
				// A video watched with generated subtitles waits for the first of them at its start, by
				// design: a few more seconds before it counts as stuck.
				checkStall(gen, src, now, false, 3000);
				return;
			}
			if (!audioProc.isBypassed() && audioProc.isTranscribing()) {
				// Subtitle generation is what holds the sound (and with it the picture, which follows
				// the sound's clock): off for this track, the seek's flush takes it out of the pipeline.
				audioProc.setBypass(true);
				DiagnosticLog.log("ENGINE", "stall: SubGen holding the sound, off for this track",
						"item=" + src, "pos=" + now);
				player.seekTo(now);
				checkStall(gen, src, now, midPlay, 4000);
				return;
			}
			if (off && !stallRetried) {
				// Stuck with the picture switched off: on again (and for good, for this file).
				stallRetried = true;
				String id = src.getOrigId();
				if (id != null) keepPicture.add(id);
				DiagnosticLog.log("ENGINE", "stall: picture switched on", "item=" + src);
				setVideoTrackDisabled(false);
				// With nowhere to show it too: what is stuck with the picture off is also stuck without a screen.
				if ((shown == null) && (dummySurface == null)) useDummySurface();
				checkStall(gen, src, now, midPlay, 3000);
				return;
			}
			if (!off && (shown == null) && (dummySurface == null) && useDummySurface()) {
				// Stuck with the picture on but nowhere to show it: given a surface of its own, which
				// is what a file that plays on screen but not off it needs.
				DiagnosticLog.log("ENGINE", "stall: given a hidden surface", "item=" + src);
				checkStall(gen, src, now, midPlay, 3000);
				return;
			}
			if (midPlay && !stallNudged) {
				// Frozen part way through: a seek to where it is restarts the decoders from a key frame.
				stallNudged = true;
				DiagnosticLog.log("ENGINE", "stall: seek in place", "item=" + src, "pos=" + now);
				player.seekTo(now);
				checkStall(gen, src, now, true, 4000);
				return;
			}
			DiagnosticLog.log("ENGINE", "stall: given up, reported as an error", "item=" + src);
			listener.onEngineError(this, new java.io.IOException("Playback stalled"));
		}, delay);
	}

	@Override
	public void stop() {
		stallGen++;
		resetFade();
		stopped(false);
		player.stop();
		source = null;
		accessor.sourceChanged(null);
	}

	@Override
	public void pause() {
		stallGen++;
		pauseWithFade(() -> player.setPlayWhenReady(false));
	}

	@Override
	protected boolean supportsFade() {
		return true;
	}

	@Override
	protected void setFadeVolume(float volume) {
		player.setVolume(volume);
	}

	@Override
	public PlayableItem getSource() {
		return source;
	}

	@Override
	public FutureSupplier<Long> getDuration() {
		return completed(!isHls && (source != null) ? player.getDuration() : 0);
	}

	@Override
	public FutureSupplier<Long> getPosition() {
		syncSub(false);
		return completed(pos());
	}

	@Override
	protected FutureSupplier<Long> getSubtitlePosition() {
		return completed(pos());
	}

	private long pos() {
		if (source == null) return 0L;
		var pos = player.getCurrentPosition();
		if (isHls) {
			var tl = player.getCurrentTimeline();
			if (!tl.isEmpty()) {
				pos -= tl.getPeriod(player.getCurrentPeriodIndex(), period).getPositionInWindowMs();
			}
		}
		return pos - source.getOffset();
	}

	protected long subSchedulerClock() {
		return pos();
	}

	void syncSub(boolean restart) {
		syncSub(subSchedulerClock(), speed(), restart);
	}

	@Override
	public void setPosition(long position) {
		if (source == null) return;
		var pos = source.getOffset() + position;
		player.seekTo(pos);
		// A seek is not a stall (a file with few key frames takes seconds to show the new position):
		// what the watchdog was waiting on is dropped, and it watches on only well after the seek.
		int gen = ++stallGen;
		PlayableItem src = source;
		if (!src.isNetResource()) checkStall(gen, src, pos, true, 8000);
		accessor.setSubGenTimeOffset(this);
		syncSub(true);
	}

	@Override
	public FutureSupplier<Float> getSpeed() {
		return completed(speed());
	}

	private float speed() {
		return player.getPlaybackParameters().speed;
	}

	@Override
	public void setSpeed(float speed) {
		player.setPlaybackParameters(new PlaybackParameters(speed));
		syncSub(true);
	}

	@Override
	public void setVideoView(VideoView view) {
		super.setVideoView(view);
		player.setVideoSurfaceHolder((view == null) ? null : view.getVideoSurface().getHolder());
		// With nowhere to show it (the app in the background) the picture is not decoded at all: the
		// sound plays on without a video decoder that can stall or be taken away.
		PlayableItem s = source;
		shown = view;
		if ((view == null) && (dummySurface != null)) player.setVideoSurface(dummySurface);
		if (s != null) setVideoTrackDisabled(videoOff(s, view));
		firstFrame = false;
		if (view != null) watchBlackPicture();
	}

	// Files whose picture has to stay switched on even for the sound alone: some stall from their
	// first second without it (and the platform player fails on them too).
	private static final java.util.Set<String> keepPicture =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private boolean stallRetried;
	// The frozen track was already seeked in place once (see checkStall()).
	private boolean stallNudged;
	// Checks a watched video's start has waited for its generated subtitles (see checkStall()).
	private int subGenWaits;
	// A surface nothing is shown on, for the file that stalls without one (see watchForStall()).
	private android.graphics.SurfaceTexture dummyTexture;
	private android.view.Surface dummySurface;

	private boolean useDummySurface() {
		try {
			dummyTexture = new android.graphics.SurfaceTexture(false);
			dummySurface = new android.view.Surface(dummyTexture);
			player.setVideoSurface(dummySurface);
			return true;
		} catch (Throwable ex) {
			releaseDummySurface();
			return false;
		}
	}

	private void releaseDummySurface() {
		if (dummySurface != null) dummySurface.release();
		if (dummyTexture != null) dummyTexture.release();
		dummySurface = null;
		dummyTexture = null;
	}

	/** Whether the picture's track is to be off for {@code s}: no screen for it, or sound only. */
	private static boolean videoOff(PlayableItem s, @Nullable VideoView view) {
		String id = s.getOrigId();
		if ((id != null) && keepPicture.contains(id)) return false;
		return (view == null) || s.isAudioOnlyPlayback();
	}

	// The view the picture is given to, whether the first frame of it has been drawn, and the
	// latest check of that (see watchBlackPicture()).
	private VideoView shown;
	private boolean firstFrame;
	private int blackGen;

	@Override
	public void onRenderedFirstFrame() {
		firstFrame = true;
		// The picture is here: the black its predecessor's end faded to (VideoView#fadeToBlack) lifts.
		VideoView v = shown;
		if (v != null) v.liftBlack(450);
	}

	/**
	 * A video that plays (sound) with its screen up but whose picture does not come -- the decoder
	 * lost its surface, or the picture's track was left switched off -- is put right: track on, surface
	 * given again. Logged, so what it was shows in the diagnostic log.
	 */
	private void watchBlackPicture() {
		int gen = ++blackGen;
		FermataApplication.get().getHandler().postDelayed(() -> {
			VideoView v = shown;
			PlayableItem src = source;
			if ((gen != blackGen) || firstFrame || (v == null) || (src == null) ||
					(accessor.player == null) || !src.isVideo()) return;
			if (!player.getPlayWhenReady()) return;
			if (player.getPlaybackState() != Player.STATE_READY) {
				// Still seeking to the key frame (or buffering): looked at again, not given up on.
				if (gen == blackGen) watchBlackPicture();
				return;
			}
			Format f = player.getVideoFormat();
			boolean valid = v.getVideoSurface().getHolder().getSurface().isValid();
			DiagnosticLog.log("ENGINE", "no picture 3 s after the screen was given", "item=" + src,
					"videoOff=" + player.getTrackSelectionParameters().disabledTrackTypes
							.contains(C.TRACK_TYPE_VIDEO),
					"format=" + ((f == null) ? null : f.sampleMimeType + "/" + f.width + "x" + f.height),
					"surfaceValid=" + valid);
			setVideoTrackDisabled(false);
			if (valid) {
				player.clearVideoSurface();
				player.setVideoSurfaceHolder(v.getVideoSurface().getHolder());
			}
		}, 3000);
	}

	@Override
	public float getVideoWidth() {
		Format f = player.getVideoFormat();
		return (f == null) ? 0 : f.width;
	}

	@Override
	public float getVideoHeight() {
		Format f = player.getVideoFormat();
		return (f == null) ? 0 : f.height;
	}

	@Nullable
	@Override
	public AudioEffects getAudioEffects() {
		return audioEffects;
	}

	@Override
	public boolean supportsAudioEffects() {
		return AudioEffects.isSupported();
	}

	@Override
	public boolean supportsSoundStage() {
		return true;
	}

	@Nullable
	@Override
	public AudioEffects ensureAudioEffects() {
		if (audioEffects == null) {
			audioEffects = AudioEffects.create(0, player.getAudioSessionId());
			attachAuxEffect();
		}
		return audioEffects;
	}

	@Override
	public void disposeAudioEffectsIfIdle() {
		if (audioEffects == null) return;
		player.setAuxEffectInfo(new AuxEffectInfo(0, 0f));
		audioEffects.release();
		audioEffects = null;
	}

	/**
	 * Unlike {@code MediaPlayer}, ExoPlayer's audio session (and thus an attached aux effect)
	 * persists across {@code setMediaItem}/{@code prepare()} calls, so this only needs to run once,
	 * right after the effect is created, rather than being re-attached per item.
	 */
	private void attachAuxEffect() {
		if (audioEffects == null) return;
		PresetReverb reverb = audioEffects.getPresetReverb();
		if (reverb != null) player.setAuxEffectInfo(new AuxEffectInfo(reverb.getId(), 0f));
	}

	@Override
	public void setAuxEffectSendLevel(float level) {
		if (audioEffects == null) return;
		PresetReverb reverb = audioEffects.getPresetReverb();
		if (reverb != null) player.setAuxEffectInfo(new AuxEffectInfo(reverb.getId(), level));
	}

	@Override
	public FutureSupplier<Void> selectSubtitleStream() {
		var src = getSource();
		if (src == null) return completedVoid();
		var ps = src.getPrefs();
		if (!ps.getBooleanPref(SubGenAddon.ENABLED)) return super.selectSubtitleStream();
		setCurrentSubtitleStream(new SubtitleStreamInfo.Generated(ps.getStringPref(SubGenAddon.LANG)));
		if (BuildConfig.AUTO && !src.isVideo() && (listener instanceof MediaSessionCallback cb)) {
			addSubtitleConsumer(cb);
		}
		return completedVoid();
	}

	@Override
	public FutureSupplier<SubGrid> getCurrentSubtitles() {
		var cur = super.getCurrentSubtitles();
		if (cur != NO_SUBTITLES) return cur;
		var src = getSource();
		if (src == null) return cur;
		var ps = src.getPrefs();
		if (!ps.getBooleanPref(SubGenAddon.ENABLED)) return cur;
		setCurrentSubtitleStream(new SubtitleStreamInfo.Generated(ps.getStringPref(SubGenAddon.LANG)));
		return super.getCurrentSubtitles();
	}

	@Override
	public FutureSupplier<List<SubtitleStreamInfo>> getSubtitleStreamInfo() {
		return super.getSubtitleStreamInfo().main().map(subFiles -> {
			var src = getSource();
			if (src == null) return emptyList();
			var ps = src.getPrefs();
			if (ps.getBooleanPref(SubGenAddon.ENABLED)) {
				var streams = new ArrayList<SubtitleStreamInfo>(subFiles.size() + 1);
				streams.add(new SubtitleStreamInfo.Generated(ps.getStringPref(SubGenAddon.LANG)));
				streams.addAll(subFiles);
				return streams;
			}
			return subFiles;
		});
	}

	@Override
	public List<AudioStreamInfo> getAudioStreamInfo() {
		var groups = player.getCurrentTracks().getGroups();
		var streams = new ArrayList<AudioStreamInfo>();
		for (int i = 0, n = groups.size(); i < n; i++) {
			var group = groups.get(i);
			if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
			for (int j = 0; j < group.length; j++) {
				var fmt = group.getTrackFormat(j);
				streams.add(new AudioStreamInfo(i * 1000L + j, fmt.language, fmt.label));
			}
		}
		return streams;
	}

	@Nullable
	@Override
	public AudioStreamInfo getCurrentAudioStreamInfo() {
		var groups = player.getCurrentTracks().getGroups();
		for (int i = 0, n = groups.size(); i < n; i++) {
			var group = groups.get(i);
			if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
			for (int j = 0; j < group.length; j++) {
				if (group.isTrackSelected(j)) {
					var fmt = group.getTrackFormat(j);
					return new AudioStreamInfo(i * 1000L + j, fmt.language, fmt.label);
				}
			}
		}
		return null;
	}

	@Override
	public void setCurrentAudioStream(@Nullable AudioStreamInfo info) {
		if (info == null) return;

		var groups = player.getCurrentTracks().getGroups();
		for (int i = 0, n = groups.size(); i < n; i++) {
			var group = groups.get(i);
			if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
			for (int j = 0; j < group.length; j++) {
				if (info.getId() != (i * 1000L + j)) continue;
				player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
						.setOverrideForType(new androidx.media3.common.TrackSelectionOverride(
								group.getMediaTrackGroup(), j)).build());
				return;
			}
		}
	}

	@Override
	public void close() {
		stop();
		super.close();
		drainBuffer = null;
		accessor.player = null;
		player.removeListener(this);
		player.release();
		releaseDummySurface();
		source = null;
		if (audioEffects != null) audioEffects.release();
	}

	@Override
	public void mute(Context ctx) {
		setMuteLevel(true);
	}

	@Override
	public void unmute(Context ctx) {
		setMuteLevel(false);
	}

	@Override
	public void onPlaybackStateChanged(int playbackState) {
		if (playbackState == Player.STATE_BUFFERING) {
			buffering = true;
			listener.onEngineBuffering(this, player.getBufferedPercentage());
		} else if (playbackState == Player.STATE_READY) {
			if (buffering) {
				buffering = false;
				listener.onEngineBufferingCompleted(this);
			}
			if (preparing) {
				preparing = false;
				long off = source.getOffset();
				if (off > 0) player.seekTo(off);
				accessor.setSubGenTimeOffset(this);
				listener.onEnginePrepared(this);
				var prefs = source.getPrefs();
				MediaEngine.selectMediaStream(prefs::getAudioIdPref, prefs::getAudioLangPref,
						prefs::getAudioKeyPref, () -> completed(getAudioStreamInfo()),
						this::setCurrentAudioStream);
			}
		} else if (playbackState == Player.STATE_ENDED) {
			stopped(false);
			listener.onEngineEnded(this);
		}
	}

	// Open problem: playback pausing in the background with no session pause logged. A pause the
	// player decides on itself (audio becoming noisy, focus, suppression) never goes through the
	// session's onPause, so it is only visible here.
	@Override
	public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
		if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return;
		DiagnosticLog.log("ENGINE", "exo playWhenReady=" + playWhenReady, "reason=" + reason,
				"item=" + source);
	}

	@Override
	public void onPlaybackSuppressionReasonChanged(int reason) {
		DiagnosticLog.log("ENGINE", "exo playback suppression", "reason=" + reason, "item=" + source);
	}

	@Override
	public void onVideoSizeChanged(VideoSize videoSize) {
		listener.onVideoSizeChanged(this, videoSize.width, videoSize.height);
	}

	@Override
	public void onPlayerError(@NonNull PlaybackException error) {
		listener.onEngineError(this, error);
	}

	@Override
	protected SubGrid createSubStreamGrid() {
		return accessor.createSubStreamGrid();
	}

	static class Accessor {
		private volatile ExoPlayerEngine player;
		private volatile long subGenTimeOffset;
		private Subtitles.Stream subStream;
		private Subtitles.Stream subTransStream;
		private String transLang;
		private FutureSupplier<Translator> translator = completedNull();
		private boolean useBatchTranslate = true;

		private Accessor(ExoPlayerEngine player) {
			this.player = player;
		}

		void drainBuffer() {
			assertMainThread();
			if (player == null) return;
			if (player.drainBuffer != null) player.drainBuffer.run();
			player.syncSub(false);
		}

		@Nullable
		public PlayableItem getSource() {
			var p = player;
			return p == null ? null : p.source;
		}

		public long getSubGenTimeOffset() {
			return subGenTimeOffset;
		}

		private void sourceChanged(PlayableItem src) {
			if (subStream != null) subStream.clear();
			if (subTransStream != null) subTransStream.clear();

			if (src == null) {
				transLang = null;
				translator = completedNull();
				return;
			}

			var ps = src.getPrefs();
			var lang = ps.getBooleanPref(SubGenAddon.TRANSLATE) ?
					ps.getStringPref(SubGenAddon.TRANSLATE_LANG) : null;
			if (lang == null || !lang.equals(transLang)) {
				transLang = lang;
				translator = completedNull();
			}
		}

		void addSubtitles(String lang, List<Subtitles.Text> subs) {
			if (subs.isEmpty()) return;
			App.get().run(() -> {
				if (subStream == null) subStream = new Subtitles.Stream();
				var added = subStream.add(subs);
				if (transLang == null) return;
				var src = getSource();
				if (src == null) return;

				var targetLang = transLang;
				if (translator.isDone() && translator.peek() == null) {
					translator = TranslateAddon.get().then(a -> {
						if (a == null || !targetLang.equals(transLang)) return completedNull();
						return a.getTranslator(src.getPrefs(), lang, transLang);
					});
				}
				translator.main().onSuccess(tr -> {
					if (tr == null || !targetLang.equals(transLang)) return;
					if (useBatchTranslate && tr.supportsBatch()) batchTranslate(tr, targetLang, added);
					else perItemTranslate(tr, targetLang, added);
				});
			});
		}

		private void batchTranslate(Translator tr, String targetLang, List<Subtitles.Text> subs) {
			assertMainThread();
			boolean prependPrev = false;
			if (!subStream.isEmpty()) {
				var last = subStream.get(subStream.size() - 1).getText().trim();
				var lastChar = last.isEmpty() ? '\0' : last.charAt(last.length() - 1);
				prependPrev = lastChar != '.' && lastChar != ',' && lastChar != '!' && lastChar != '?';
			}

			String concat;
			try (var tb = SharedTextBuilder.get()) {
				if (prependPrev) tb.append(subStream.get(subStream.size() - 1).getText()).append("|");
				for (var t : subs) tb.append(t.getText()).append("|");
				tb.setLength(tb.length() - 1);
				concat = tb.toString();
			}

			boolean skipFirst = prependPrev;
			Log.d("Translating: ", concat);
			tr.translate(concat).onCompletion((r, err) -> {
				if (err != null) {
					Log.e(err);
					return;
				}

				Log.d("Translation: ", r);
				String[] parts = r.split("\\|", -1);
				int off = skipFirst ? 1 : 0;

				if (subs.size() != parts.length - off) {
					Log.d("Fall back to per item translation");
					useBatchTranslate = false;
					perItemTranslate(tr, targetLang, subs);
					return;
				}

				var translated = new ArrayList<Subtitles.Text>(subs.size());
				for (int i = 0, n = subs.size(); i < n; i++) {
					var t = subs.get(i);
					t.setTranslation(parts[off + i].trim());
					translated.add(new Subtitles.Text(t.getTranslation(), t.getTime(), t.getDuration()));
				}
				App.get().run(() -> {
					if (!targetLang.equals(transLang)) return;
					if (subTransStream == null) subTransStream = new Subtitles.Stream();
					subTransStream.add(translated);
				});
			});
		}

		private void perItemTranslate(Translator tr, String targetLang, List<Subtitles.Text> subs) {
			for (var t : subs) {
				tr.translate(t.getText()).onCompletion((r, err) -> {
					if (err != null) {
						Log.e(err);
						return;
					}
					t.setTranslation(r.trim());
					var translated = new Subtitles.Text(t.getTranslation(), t.getTime(), t.getDuration());
					App.get().run(() -> {
						if (!targetLang.equals(transLang)) return;
						if (subTransStream == null) subTransStream = new Subtitles.Stream();
						subTransStream.add(translated);
					});
				});
			}
		}

		private void setSubGenTimeOffset(ExoPlayerEngine eng) {
			this.subGenTimeOffset = eng.subSchedulerClock();
		}

		private SubGrid createSubStreamGrid() {
			assertMainThread();
			if (subStream == null) subStream = new Subtitles.Stream();
			if (transLang == null) return new SubGrid(subStream);
			if (subTransStream == null) subTransStream = new Subtitles.Stream();
			var m = new EnumMap<SubGrid.Position, Subtitles>(SubGrid.Position.class);
			m.put(SubGrid.Position.BOTTOM_LEFT, subStream);
			m.put(SubGrid.Position.BOTTOM_RIGHT, subTransStream);
			return new SubGrid(m);
		}
	}
}
