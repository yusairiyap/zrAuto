package me.aap.fermata.action;

import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.ADJUST_MUTE;
import static android.media.AudioManager.ADJUST_RAISE;
import static android.media.AudioManager.ADJUST_TOGGLE_MUTE;
import static android.media.AudioManager.ADJUST_UNMUTE;
import static android.media.AudioManager.FLAG_SHOW_UI;
import static android.media.AudioManager.STREAM_MUSIC;
import static android.os.SystemClock.uptimeMillis;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.content.Context;
import android.media.AudioManager;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.Collections;
import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.activity.ActivityDelegate;

/**
 * @author Andrey Pavlenko
 */
public enum Action {
	STOP(R.string.action_stop, m(MediaSessionCallback::onStop)),
	PLAY(R.string.action_play, m(MediaSessionCallback::onPlay)),
	PAUSE(R.string.action_pause, m(MediaSessionCallback::onPause)),
	PLAY_PAUSE(R.string.action_play_pause, m(cb -> {
		if (cb.isPlaying()) cb.onPause();
		else cb.onPlay();
	})),
	PREV(R.string.action_prev, m(MediaSessionCallback::onSkipToPrevious)),
	NEXT(R.string.action_next, m(MediaSessionCallback::onSkipToNext)),
	PREV_FOLDER(R.string.action_prev_folder, m(MediaSessionCallback::onSkipToPreviousFolder)),
	NEXT_FOLDER(R.string.action_next_folder, m(MediaSessionCallback::onSkipToNextFolder)),
	RW(R.string.action_rw, new RwFfHandler(false)),
	FF(R.string.action_ff, new RwFfHandler(true)),
	VOLUME_UP(R.string.action_vol_up, new VolumeHandler(ADJUST_RAISE)),
	VOLUME_DOWN(R.string.action_vol_down, new VolumeHandler(ADJUST_LOWER)),
	VOLUME_MUTE_UNMUTE(R.string.action_vol_mute_unmute, new VolumeHandler(ADJUST_TOGGLE_MUTE)),
	ACTIVATE_VOICE_CTRL(R.string.action_activate_voice_ctrl,
			m(cb -> cb.getAssistant().startVoiceAssistant())),
	MENU(R.string.action_menu, a(a -> a.getNavBarMediator().showMenu(a))),
	CP_MENU(R.string.action_cp_menu, a(a -> {
		var cp = a.getControlPanel();
		if (cp.isActive()) cp.showMenu();
	})),
	BACK_OR_EXIT(R.string.action_back_or_exit, a(ActivityDelegate::onBackPressed)),
	// On the phone as the menus' Exit: what plays stops too (a bare finish() left it playing,
	// notification and all). The car's screen keeps closing only itself, as it always has.
	EXIT(R.string.action_exit, a(a -> {
		if (a.isCarActivityNotMirror()) a.finish();
		else a.exitApp();
	})),
	NONE(R.string.action_none, m(cb -> {})),
	// Key bindings and the secondary FAB's action are persisted as ordinals, so new entries go at
	// the end: inserting one mid-enum silently remaps every existing user's saved bindings.
	FULLSCREEN_TOGGLE(R.string.action_fullscreen_toggle, a(a -> {
		// From another tab (Favorites, Playlists) while a video plays: back to that video,
		// fullscreen.
		var f = a.getActiveFragment();
		if (!a.isVideoMode() && a.isVideoPlaying() && (f != null) &&
				(f.getFragmentId() != R.id.youtube_fragment)) {
			var eng = a.getMediaSessionCallback().getEngine();
			if ((eng != null) && (eng.getId() == me.aap.fermata.media.pref.MediaPrefs.MEDIA_ENG_YT)) {
				me.aap.fermata.addon.music.MusicPlayer.showYoutubeVideo(a);
				return;
			}
			var b = a.getBody();
			if (b != null) {
				b.setMode(me.aap.fermata.ui.view.BodyLayout.Mode.VIDEO);
				return;
			}
		}
		var vv = a.getActiveVideoView();
		boolean handled = (vv != null) && vv.toggleNativeFullscreen();
		Log.d("FULLSCREEN_TOGGLE: activeVideoView=", vv, ", handled=", handled);
		if (handled) return;
		// A native handler above covers WebView-hosted video (YouTube); local video's VideoView has
		// none, so while it's playing this toggles the system bars directly instead -- flipping the
		// persisted fullscreenPref here would be a no-op, since MainActivityDelegate.isFullScreen()
		// has videoMode itself already forcing fullscreen regardless of that pref's value.
		// Same as Back: out of the fullscreen video, to the tab it plays from (the bars shown over a
		// video left a half-laid-out screen).
		var eng = a.getMediaSessionCallback().getEngine();
		boolean youtube = (eng != null) && (eng.getId() == me.aap.fermata.media.pref.MediaPrefs.MEDIA_ENG_YT);
		// Only local playback: YouTube keeps its own behaviour.
		if (a.isVideoMode() && !youtube) a.exitVideoMode();
		else if (a.isVideoMode()) a.toggleVideoBars();
		else a.getPrefs().setFullscreenPref(a, !a.getPrefs().getFullscreenPref(a));
	})),
	DIM_TOGGLE(R.string.action_dim_toggle, a(a ->
			a.getPrefs().applyBooleanPref(MainActivityPrefs.DIM_ENABLED,
					!a.getPrefs().getBooleanPref(MainActivityPrefs.DIM_ENABLED)))),
	PRIVATE_MODE_TOGGLE(R.string.action_private_mode_toggle, a(a ->
			a.getPrefs().setPrivateModeEnabled(!a.getPrefs().isPrivateModeEnabled()))),
	REFUEL(R.string.action_refuel, a(me.aap.fermata.addon.fuel.FuelRefuelDialog::show)),
	FAVORITE_ADD(R.string.favorites_add, a(Action::toggleCurrentFavorite)),
	PLAYLIST_ADD(R.string.playlist_add, a(Action::addCurrentToPlaylist)),
	// Play as music, or back to the video when it already plays as music.
	PLAY_AS_MUSIC(R.string.action_music_video,
			a(me.aap.fermata.addon.music.MusicPlayer::toggleMusicVideo)),
	// The YouTube tab's search / Up next panel -- see MusicPlayer.YoutubeHooks#openSearch().
	YOUTUBE_SEARCH(R.string.action_youtube_search,
			a(a -> me.aap.fermata.addon.music.MusicPlayer.openYoutubeSearch(a, false))),
	YOUTUBE_UP_NEXT(R.string.action_youtube_up_next,
			a(a -> me.aap.fermata.addon.music.MusicPlayer.openYoutubeSearch(a, true))),
	OPEN_FAVORITES(R.string.action_open_favorites, a(a -> {
		a.exitVideoMode();
		a.showFragment(R.id.favorites_fragment);
	})),
	OPEN_PLAYLISTS(R.string.action_open_playlists, a(a -> {
		a.exitVideoMode();
		a.showFragment(R.id.playlists_fragment);
	})),
	// Persisted by ordinal: new actions only ever go at the end.
	OPEN_DOWNLOADS(R.string.action_open_downloads, a(a -> {
		a.exitVideoMode();
		a.showFragment(R.id.downloads_addon);
	})),
	DOWNLOAD_CURRENT(R.string.action_download_current,
			a(me.aap.fermata.ytdl.YtDownloadMenu::downloadCurrent)),
	DOWNLOADS_PAUSE_RESUME(R.string.action_downloads_pause_resume,
			a(a -> me.aap.fermata.ytdl.YtDownloadMenu.togglePause())),
	// Persisted by ordinal: new actions only ever go at the end.
	AUDIO_EFFECTS(R.string.action_audio_effects,
			a(me.aap.fermata.ui.view.SidePanels::effects)),
	SLEEP_TIMER(R.string.action_sleep_timer, a(me.aap.fermata.ui.view.AllInOneMenu::showTimer)),
	ALL_IN_ONE(R.string.action_all_in_one, a(me.aap.fermata.ui.view.AllInOneMenu::toggle)),
	// YouTube's video quality, picked on the All in One card's Quality page.
	QUALITY(R.string.quality, a(me.aap.fermata.ui.view.AllInOneMenu::showQuality)),
	// Other YouTube videos for what plays, searched by its title.
	YOUTUBE_ALTERNATIVES(R.string.action_youtube_alternatives,
			a(me.aap.fermata.ui.view.YoutubeAlternativesPicker::showForCurrent)),
	// The comments of the YouTube video that plays, in a card of their own. Kept last: actions are
	// saved by their position.
	YOUTUBE_COMMENTS(R.string.action_youtube_comments,
			a(me.aap.fermata.ui.view.YoutubeCommentsView::showForCurrent)),
	// The playing video's YouTube captions: over fullscreen video they show or hide as captions, on
	// the Music tab as lyrics over the cover, anywhere else in a lyrics card (see LyricsCard#action).
	LYRICS(R.string.action_lyrics, a(me.aap.fermata.ui.view.LyricsCard::action)),
	// The app's settings (out of fullscreen video first: the page sits behind it).
	SETTINGS(R.string.action_settings, a(a -> {
		a.exitVideoMode();
		a.showFragment(R.id.settings_fragment);
	})),
	// The Power Usage tab, and the power saver mode on or off (both need the Power usage addon).
	// Persisted by ordinal: new actions only ever go at the end.
	POWER_USAGE(R.string.action_power_usage, a(a -> {
		if (!me.aap.fermata.addon.power.PowerUsageAddon.isEnabled()) {
			me.aap.fermata.ui.view.TopToast.show(R.drawable.power_usage, R.string.power_addon_off);
			return;
		}
		a.exitVideoMode();
		a.showFragment(R.id.power_usage_addon);
	})),
	POWER_SAVER(R.string.action_power_saver, a(a -> {
		if (!me.aap.fermata.addon.power.PowerUsageAddon.isEnabled()) {
			me.aap.fermata.ui.view.TopToast.show(R.drawable.power_saver, R.string.power_addon_off);
			return;
		}
		me.aap.fermata.addon.power.PowerSaver.get().toggle();
	})),
	// The Ambience tab, and the ambience sounds on or off (the last mix, or the rain); both need
	// the Ambience addon. Persisted by ordinal: new actions only ever go at the end.
	AMBIENCE(R.string.action_ambience, a(a -> {
		if (!me.aap.fermata.addon.ambience.AmbienceAddon.isEnabled()) {
			me.aap.fermata.ui.view.TopToast.show(R.drawable.ambience, R.string.ambience_addon_off);
			return;
		}
		a.exitVideoMode();
		a.showFragment(R.id.ambience_addon);
	})),
	AMBIENCE_TOGGLE(R.string.action_ambience_toggle, a(a -> {
		if (!me.aap.fermata.addon.ambience.AmbienceAddon.isEnabled()) {
			me.aap.fermata.ui.view.TopToast.show(R.drawable.ambience, R.string.ambience_addon_off);
			return;
		}
		var eng = me.aap.fermata.addon.ambience.AmbienceEngine.get();
		eng.toggleAll();
		if (eng.anyOn()) {
			me.aap.fermata.ui.view.TopToast.show(R.drawable.ambience, R.string.ambience_started,
					eng.describe(a.getContext()));
		} else {
			me.aap.fermata.ui.view.TopToast.show(R.drawable.ambience, R.string.ambience_stopped);
		}
	})),
	// The Home tab with its widgets. Persisted by ordinal: new actions only ever go at the end.
	OPEN_HOME(R.string.action_open_home, a(a -> {
		a.exitVideoMode();
		a.showFragment(R.id.home_fragment);
	})),
	// The YouTube video that plays, shared as its youtu.be link (see YtShare).
	SHARE_VIDEO(R.string.action_share_video, a(me.aap.fermata.ytdl.YtShare::shareCurrent)),
	// Covers blurred to their colours only, or shown again (see CoverHide). Persisted by ordinal: new
	// actions only ever go at the end.
	COVER_HIDE(R.string.action_cover_hide, a(me.aap.fermata.ui.view.CoverHide::toggle)),
	// The Sleep tab, and the sleep session started or stopped; both need the Sleep addon. Persisted by ordinal: new
	// actions only ever go at the end.
	SLEEP(R.string.action_sleep, a(a -> {
		if (!me.aap.fermata.addon.sleep.SleepAddon.isEnabled()) {
			me.aap.fermata.ui.view.TopToast.show(R.drawable.sleep, R.string.sleep_addon_off);
			return;
		}
		a.exitVideoMode();
		a.showFragment(R.id.sleep_addon);
	})),
	SLEEP_TOGGLE(R.string.action_sleep_toggle, a(a -> {
		if (!me.aap.fermata.addon.sleep.SleepAddon.isEnabled()) {
			me.aap.fermata.ui.view.TopToast.show(R.drawable.sleep, R.string.sleep_addon_off);
			return;
		}
		var t = me.aap.fermata.addon.sleep.SleepTracker.get();
		if (t.isActive()) {
			var n = t.stop();
			me.aap.fermata.ui.view.TopToast.show(R.drawable.sleep, (n == null) ? R.string.sleep_too_short : R.string.sleep_saved);
		} else {
			t.start();
			me.aap.fermata.ui.view.TopToast.show(R.drawable.sleep, R.string.sleep_started);
		}
	})),
	// The captions settings as a panel beside fullscreen video, else the settings page. Persisted by ordinal: new
	// actions only ever go at the end.
	CAPTIONS_SETTINGS(R.string.action_captions_settings, a(me.aap.fermata.ui.view.SidePanels::captions)),
	// The picture on screen of the playing video, saved to Pictures/zrAuto at the video's own resolution.
	SNAPSHOT(R.string.action_snapshot, a(me.aap.fermata.ui.view.VideoSnapshot::take)),
	// Offline mode by hand (a flight): only downloaded items play and nothing waits for the network.
	OFFLINE_TOGGLE(R.string.action_offline_toggle, a(a -> me.aap.fermata.util.NetworkState.toggleForcedOffline())),
	;

	private static final List<Action> all = unmodifiableList(asList(values()));

	@StringRes
	private final int name;
	private final Action.Handler handler;

	Action(int name, Action.Handler handler) {
		this.name = name;
		this.handler = handler;
	}

	@Nullable
	public static Action get(int ordinal) {
		return (ordinal >= 0) && (ordinal < all.size()) ? all.get(ordinal) : null;
	}

	/** Shared by anything that needs to show the current mute state (FAB2/FAB3's icon, menu items). */
	public static boolean isMuted(Context ctx) {
		var amgr = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
		return (amgr != null) && amgr.isStreamMute(STREAM_MUSIC);
	}

	/**
	 * The item "Add to favorites" (FAB action) acts on: whatever is playing now -- asking the engine
	 * first, since some engines' session item is only a placeholder (see
	 * {@link MediaEngine#getFavoritableItem()}) -- or the last session item when nothing is playing.
	 */
	@Nullable
	public static PlayableItem getFavoritableItem(MainActivityDelegate a) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		PlayableItem pi = (eng != null) ? eng.getFavoritableItem() : cb.getCurrentItem();
		// A Music-tab track is only a queue entry: favourite the song it plays instead.
		if (pi instanceof MusicTrackItem t) pi = t.getFavoritableItem();
		return pi;
	}

	/** Shared by the FAB icons: whether {@link #getFavoritableItem} is already a favorite. */
	public static boolean isCurrentFavorite(MainActivityDelegate a) {
		PlayableItem pi = getFavoritableItem(a);
		return (pi != null) && pi.isFavoriteItem();
	}

	public static void toggleCurrentFavorite(MainActivityDelegate a) {
		Context ctx = a.getContext();
		PlayableItem pi = getFavoritableItem(a);
		if (pi == null) {
			me.aap.fermata.ui.view.TopToast.show(R.drawable.favorite, R.string.favorites_nothing_playing);
			return;
		}
		MediaSessionCallback cb = a.getMediaSessionCallback();

		// Toggles: already a favourite means this removes it (the FAB icon and menu label say so).
		if (pi.isFavoriteItem()) {
			FutureSupplier<Void> removed;
			if (pi == cb.getCurrentItem()) {
				cb.favoriteAddRemove(false);
				removed = completedVoid();
			} else {
				removed = pi.getLib().getFavorites().removeItem(pi);
			}
			// The card saying so comes from the favorites themselves, see FavoritesNotice.
			removed.main().onSuccess(v -> {
				a.fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
			});
			return;
		}

		// The session's own current item goes through the callback so its Android Auto/notification
		// "favorite" custom action flips to match; anything else (YouTube's real video item behind its
		// placeholder) is added straight to the library.
		FutureSupplier<Void> added;
		if (pi == cb.getCurrentItem()) {
			cb.favoriteAddRemove(true);
			added = completedVoid();
		} else {
			added = pi.getLib().getFavorites().addItem(pi);
		}
		added.main().onSuccess(v -> {
			// Lets anything showing favorite state (YouTube's toolbar button, the FAB icon) refresh.
			a.fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
		});
	}

	/**
	 * Shows a pick list of playlists (plus "Create playlist") for whatever is playing -- the same
	 * item "Add to favorites" acts on, see {@link #getFavoritableItem}. Posted rather than shown
	 * directly: this also runs from the FAB's own long-press menu, which is still finishing its
	 * item selection (and hiding itself) at this point, on the same overlay.
	 */
	public static void addCurrentToPlaylist(MainActivityDelegate a) {
		PlayableItem pi = getFavoritableItem(a);
		if (pi == null) {
			me.aap.fermata.ui.view.TopToast.show(R.drawable.playlist_add, R.string.playlist_nothing_playing);
			return;
		}
		App.get().getHandler().post(() -> {
			OverlayMenu menu = a.findViewById(R.id.control_menu);
			if (menu != null) a.showAddToPlaylistMenu(menu, Collections.singletonList(pi));
		});
	}

	public static List<Action> getAll() {
		return all;
	}

	@StringRes
	public int getName() {
		return name;
	}

	public Handler getHandler() {
		return handler;
	}

	private static Handler m(MediaHandler h) {
		return h;
	}

	private static Handler a(ActivityHandler h) {
		return h;
	}

	public interface Handler {
		void handle(MediaSessionCallback cb, @Nullable MainActivityDelegate a, long timestamp);
	}

	private interface MediaHandler extends Handler {
		void handle(MediaSessionCallback cb);

		@Override
		default void handle(MediaSessionCallback cb, @Nullable MainActivityDelegate a,
												long timestamp) {
			handle(cb);
		}
	}

	private interface ActivityHandler extends Handler {
		void handle(MainActivityDelegate a);

		@Override
		default void handle(MediaSessionCallback cb, @Nullable MainActivityDelegate a,
												long timestamp) {
			// From a media button (a car's steering wheel): no UI came with it, act on the one there is.
			if (a == null) a = MainActivityDelegate.getUiDelegate();
			if (a != null) handle(a);
			else Log.i("No UI for the action ", this);
		}
	}

	private static final class VolumeHandler implements Handler {
		private final int direction;

		VolumeHandler(int direction) {this.direction = direction;}

		@Override
		public void handle(MediaSessionCallback cb, @Nullable MainActivityDelegate a, long timestamp) {
			var eng = cb.getEngine();
			if ((eng != null) && eng.adjustVolume(direction)) return;
			var ctx = (a == null) ? App.get() : a.getContext();
			var amgr = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
			if (amgr == null) return;

			if (direction == ADJUST_TOGGLE_MUTE) {
				// Resolve against the stream's actual current mute state rather than relying on the
				// OS's own toggle bookkeeping -- content played through a WebView (e.g. YouTube) never
				// reports a MediaEngine here, so this is the only mute path for it, and it can be
				// invoked from more than one UI entry point (FAB tap, its long-press quick menu) in
				// quick succession; a blind ADJUST_TOGGLE_MUTE is one dispatch away from silently
				// cancelling itself out, while explicitly setting the opposite of the current state
				// is idempotent no matter how many times or where it's triggered from.
				int explicit = amgr.isStreamMute(STREAM_MUSIC) ? ADJUST_UNMUTE : ADJUST_MUTE;
				amgr.adjustStreamVolume(STREAM_MUSIC, explicit, FLAG_SHOW_UI);
			} else {
				amgr.adjustStreamVolume(STREAM_MUSIC, direction, FLAG_SHOW_UI);
			}
		}
	}

	private static final class RwFfHandler implements Handler {
		private final boolean ff;

		private RwFfHandler(boolean ff) {this.ff = ff;}

		@Override
		public void handle(MediaSessionCallback cb, @Nullable MainActivityDelegate a, long timestamp) {
			cb.rewindFastForward(ff, (int) ((uptimeMillis() - timestamp) / 1000));
		}
	}
}
