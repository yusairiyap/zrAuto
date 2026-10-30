package me.aap.fermata.ui.fragment;

import static android.os.SystemClock.uptimeMillis;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CHANGED;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.support.v4.media.session.PlaybackStateCompat;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.ui.view.FloatingButton;

/**
 * An extra, user-configurable floating button (FAB2 to FAB6): a tap runs the action set for it in
 * Settings; a long press offers every action, grouped by what they're about, plus the related
 * settings pages. The buttons differ only in which preference holds their action.
 */
public abstract class ActionFabMediator implements FloatingButton.Mediator,
		View.OnClickListener, View.OnLongClickListener, MediaSessionCallback.Listener {
	/** Every action a floating button can be set to, in the order Settings lists them. */
	public static final List<Action> OFFERED_ACTIONS = List.of(
			Action.FULLSCREEN_TOGGLE, Action.VOLUME_MUTE_UNMUTE, Action.PLAY_PAUSE, Action.DIM_TOGGLE,
			Action.PRIVATE_MODE_TOGGLE, Action.REFUEL, Action.FAVORITE_ADD,
			Action.PLAYLIST_ADD, Action.PLAY_AS_MUSIC, Action.YOUTUBE_SEARCH, Action.YOUTUBE_UP_NEXT,
			Action.OPEN_HOME, Action.OPEN_FAVORITES, Action.OPEN_PLAYLISTS, Action.OPEN_DOWNLOADS,
			Action.DOWNLOAD_CURRENT,
			Action.DOWNLOADS_PAUSE_RESUME, Action.ALL_IN_ONE, Action.AUDIO_EFFECTS, Action.SLEEP_TIMER,
			Action.QUALITY, Action.YOUTUBE_ALTERNATIVES, Action.YOUTUBE_COMMENTS, Action.LYRICS,
			Action.POWER_USAGE, Action.POWER_SAVER, Action.AMBIENCE, Action.AMBIENCE_TOGGLE,
			Action.SETTINGS, Action.SHARE_VIDEO, Action.COVER_HIDE, Action.SLEEP, Action.SLEEP_TOGGLE,
			Action.CAPTIONS_SETTINGS);

	private final Pref<IntSupplier> actionPref;
	@Nullable
	private FloatingButton fab;

	protected ActionFabMediator(Pref<IntSupplier> actionPref) {
		this.actionPref = actionPref;
	}

	@Override
	public void enable(FloatingButton fb, ActivityFragment f) {
		fab = fb;
		updateIcon(fb);
		fb.setOnClickListener(this);
		fb.setOnLongClickListener(this);
		MainActivityDelegate a = MainActivityDelegate.get(fb.getContext());
		a.getMediaSessionCallback().addBroadcastListener(this);
		// A new track: the lyrics / captions icon follows at once (see updateIcon).
		a.getMediaServiceBinder().addBroadcastListener(trackListener);
		a.getPrefs().addBroadcastListener(switchListener);
		if (me.aap.fermata.addon.ambience.AmbienceAddon.isEnabled()) {
			me.aap.fermata.addon.ambience.AmbienceEngine.get().addListener(ambienceListener);
		}
	}

	@Override
	public void disable(FloatingButton fb) {
		FloatingButton.Mediator.super.disable(fb);
		MainActivityDelegate a = MainActivityDelegate.get(fb.getContext());
		a.getMediaSessionCallback().removeBroadcastListener(this);
		a.getMediaServiceBinder().removeBroadcastListener(trackListener);
		a.getPrefs().removeBroadcastListener(switchListener);
		if (me.aap.fermata.addon.ambience.AmbienceAddon.isEnabled()) {
			me.aap.fermata.addon.ambience.AmbienceEngine.get().removeListener(ambienceListener);
		}
		me.aap.fermata.ytcaptions.CaptionsHub.get().release(captionsListener);
		if (fab == fb) fab = null;
	}

	@Override
	public void onActivityEvent(FloatingButton fb, ActivityDelegate a, long e) {
		if ((e & (FRAGMENT_CHANGED | FRAGMENT_CONTENT_CHANGED)) != 0) updateIcon(fb);
	}

	// Play/pause, favourite state etc. change from outside the button too (the control panel,
	// media keys, the notification): its icon follows.
	@Override
	public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
		FloatingButton fb = fab;
		if (fb == null) return;
		updateIcon(fb);
		// Whether a video is playing decides whether a fullscreen button shows over the lists.
		MainActivityDelegate.getActivityDelegate(fb.getContext())
				.onSuccess(a -> {
					// Over video the buttons follow the control panel's own show/hide instead.
					if (!a.isVideoMode()) a.updateExtraFabsVisibility();
				});
	}

	/** Another track: asks the captions where they stand right now, then the icon is redrawn. */
	private final me.aap.fermata.media.service.FermataServiceUiBinder.Listener trackListener =
			new me.aap.fermata.media.service.FermataServiceUiBinder.Listener() {
				@Override
				public void onPlayableChanged(me.aap.fermata.media.lib.MediaLib.PlayableItem oldItem,
																			me.aap.fermata.media.lib.MediaLib.PlayableItem newItem) {
					me.aap.fermata.ytcaptions.CaptionsHub.get().poke();
					FloatingButton fb = fab;
					if (fb != null) updateIcon(fb);
				}
			};
	/** Ambience sounds were switched on or off from anywhere: the on/off button's icon follows. */
	private final Runnable ambienceListener = () -> {
		FloatingButton fb = fab;
		if (fb != null) updateIcon(fb);
	};
	/** The lyrics / captions switch was flipped from anywhere (a key, the tile, the chip): the icon follows. */
	private final me.aap.utils.pref.PreferenceStore.Listener switchListener = (store, prefs) -> {
		FloatingButton fb = fab;
		if ((fb != null) && (prefs.contains(MainActivityPrefs.LYRICS_ON) ||
				prefs.contains(MainActivityPrefs.CAPTIONS_ON) || prefs.contains(MainActivityPrefs.COVER_HIDDEN))) {
			updateIcon(fb);
		}
	};
	/** Whether the captions of what plays are there (or not) changed: the icon's dimming follows. */
	private final me.aap.fermata.ytcaptions.CaptionsHub.Listener captionsListener =
			new me.aap.fermata.ytcaptions.CaptionsHub.Listener() {
				@Override
				public void onCaptionsState(me.aap.fermata.ytcaptions.CaptionsHub hub) {
					FloatingButton fb = fab;
					if (fb != null) updateIcon(fb);
				}
			};

	/** The switch the lyrics / captions action flips: captions over fullscreen video, else the lyrics. */
	public static boolean lyricsSwitchOn(MainActivityDelegate a) {
		return a.getPrefs().getBooleanPref(a.isVideoMode() ? MainActivityPrefs.CAPTIONS_ON :
				MainActivityPrefs.LYRICS_ON);
	}

	private void updateIcon(FloatingButton fb) {
		MainActivityDelegate a = MainActivityDelegate.get(fb.getContext());
		Action action = Action.get(a.getPrefs().getIntPref(MainActivityPrefs.fab(a, actionPref)));
		// Only a lyrics / captions button keeps the captions hub running.
		if (action == Action.LYRICS) me.aap.fermata.ytcaptions.CaptionsHub.get().acquire(a, captionsListener);
		else me.aap.fermata.ytcaptions.CaptionsHub.get().release(captionsListener);
		if (action == Action.QUALITY) {
			// The quality in use as its icon ("720p", "Auto"), like the All in One tile's; asked
			// afresh now and then, the icon redrawn when it changes.
			String l = me.aap.fermata.ui.view.QualityIcon.getLabel();
			if (l != null) fb.setImageDrawable(new me.aap.fermata.ui.view.QualityIcon(fb.getContext(), l));
			else fb.setImageResource(R.drawable.quality);
			me.aap.fermata.ui.view.QualityIcon.refresh(a, () -> {
				if (fab == fb) updateIcon(fb);
			});
			return;
		}
		fb.setImageResource(iconFor(a, action));
		// Lyrics / captions: dimmed while they are off, and while what plays has none (or not known yet).
		boolean dimmed = (action == Action.LYRICS) && (!lyricsSwitchOn(a) ||
				me.aap.fermata.ytcaptions.CaptionsHub.get().isDimmed(a));
		// The captions settings button is dimmed while captions are off, like the captions button.
		if (action == Action.CAPTIONS_SETTINGS) dimmed = !a.getPrefs().getBooleanPref(MainActivityPrefs.CAPTIONS_ON);
		// The ambience on/off button is dimmed while nothing is playing.
		if (action == Action.AMBIENCE_TOGGLE) {
			dimmed = !me.aap.fermata.addon.ambience.AmbienceAddon.isEnabled() ||
					!me.aap.fermata.addon.ambience.AmbienceEngine.get().anyOn();
		}
		// The sleep start / stop button is dimmed while no session runs.
		if (action == Action.SLEEP_TOGGLE) {
			dimmed = !me.aap.fermata.addon.sleep.SleepAddon.isEnabled() ||
					!me.aap.fermata.addon.sleep.SleepTracker.get().isActive();
		}
		fb.setImageAlpha(dimmed ? 100 : 255);
	}

	@DrawableRes
	public static int iconFor(MainActivityDelegate a, @Nullable Action action) {
		if (action == Action.FULLSCREEN_TOGGLE) return R.drawable.video_fullscreen;
		if (action == Action.VOLUME_MUTE_UNMUTE) return Action.isMuted(a.getContext()) ?
				R.drawable.volume_mute : R.drawable.volume_up;
		if (action == Action.DIM_TOGGLE)
			return a.getPrefs().getBooleanPref(MainActivityPrefs.DIM_ENABLED) ?
					R.drawable.dim_screen : R.drawable.dim_screen_off;
		if (action == Action.PRIVATE_MODE_TOGGLE) return R.drawable.private_mode;
		if (action == Action.REFUEL) return R.drawable.fuel;
		if (action == Action.PLAYLIST_ADD) return R.drawable.playlist_add;
		if (action == Action.PLAY_AS_MUSIC) return MusicPlayer.canSwitchToVideo(
				a.getMediaSessionCallback()) ? R.drawable.video : R.drawable.music;
		if ((action == Action.YOUTUBE_SEARCH) || (action == Action.YOUTUBE_ALTERNATIVES)) {
			return R.drawable.search;
		}
		if (action == Action.YOUTUBE_UP_NEXT) return R.drawable.up_next;
		if (action == Action.YOUTUBE_COMMENTS) return R.drawable.comments;
		// Captions over fullscreen video, lyrics anywhere else.
		// Captions over fullscreen video, lyrics elsewhere.
		if (action == Action.LYRICS) return a.isVideoMode() ? R.drawable.captions : R.drawable.lyrics;
		if (action == Action.SETTINGS) return R.drawable.settings;
		if (action == Action.CAPTIONS_SETTINGS) return R.drawable.captions;
		if (action == Action.POWER_USAGE) return R.drawable.power_usage;
		if (action == Action.POWER_SAVER) return R.drawable.power_saver;
		if (action == Action.SHARE_VIDEO) return R.drawable.share;
		if (action == Action.COVER_HIDE) return me.aap.fermata.ui.view.CoverHide.isOn() ?
				R.drawable.cover_hidden : R.drawable.visibility;
		if ((action == Action.AMBIENCE) || (action == Action.AMBIENCE_TOGGLE)) return R.drawable.ambience;
		if ((action == Action.SLEEP) || (action == Action.SLEEP_TOGGLE)) return R.drawable.sleep;
		if (action == Action.OPEN_HOME) return R.drawable.home_page;
		if (action == Action.OPEN_FAVORITES) return R.drawable.favorite_filled;
		if (action == Action.OPEN_PLAYLISTS) return R.drawable.playlist;
		if ((action == Action.OPEN_DOWNLOADS) || (action == Action.DOWNLOAD_CURRENT)) {
			return R.drawable.download;
		}
		if (action == Action.DOWNLOADS_PAUSE_RESUME) {
			return me.aap.fermata.ytdl.YtDownloads.get().isBusy() ? R.drawable.pause : R.drawable.download;
		}
		if (action == Action.FAVORITE_ADD) return Action.isCurrentFavorite(a) ?
				R.drawable.favorite_filled : R.drawable.favorite;
		if (action == Action.PLAY_PAUSE)
			return a.getMediaSessionCallback().isPlaying() ? R.drawable.pause : R.drawable.play;
		if (action == Action.ALL_IN_ONE) return R.drawable.view_grid;
		if (action == Action.AUDIO_EFFECTS) return R.drawable.equalizer;
		if (action == Action.SLEEP_TIMER) return R.drawable.timer;
		if (action == Action.QUALITY) return R.drawable.quality;
		// The rest are only ever key bindings, but the All in One card can show any action.
		if (action == Action.STOP) return R.drawable.stop;
		if (action == Action.PLAY) return R.drawable.play;
		if (action == Action.PAUSE) return R.drawable.pause;
		if ((action == Action.PREV) || (action == Action.PREV_FOLDER)) return R.drawable.prev;
		if ((action == Action.NEXT) || (action == Action.NEXT_FOLDER)) return R.drawable.next;
		if (action == Action.RW) return R.drawable.rw;
		if (action == Action.FF) return R.drawable.ff;
		if ((action == Action.VOLUME_UP) || (action == Action.VOLUME_DOWN)) return R.drawable.volume_up;
		if (action == Action.ACTIVATE_VOICE_CTRL) return R.drawable.record_voice;
		if (action == Action.MENU) return me.aap.utils.R.drawable.menu;
		if (action == Action.CP_MENU) return R.drawable.more;
		if (action == Action.BACK_OR_EXIT) return me.aap.utils.R.drawable.back;
		if (action == Action.EXIT) return R.drawable.exit;
		return R.drawable.play_pause;
	}

	/** "Add to favorites" reads "Remove from favorites" when it would remove (it toggles). */
	@StringRes
	public static int labelFor(MainActivityDelegate a, Action action) {
		if ((action == Action.FAVORITE_ADD) && Action.isCurrentFavorite(a))
			return R.string.favorites_remove;
		// Captions over fullscreen video, lyrics anywhere else.
		if (action == Action.LYRICS) {
			return a.isVideoMode() ? R.string.action_captions : R.string.action_lyrics_label;
		}
		// Says which way it goes now.
		if (action == Action.PLAY_AS_MUSIC) return MusicPlayer.canSwitchToVideo(
				a.getMediaSessionCallback()) ? R.string.play_as_video : R.string.play_as_music;
		return action.getName();
	}

	@Override
	public void onClick(View v) {
		MainActivityDelegate a = MainActivityDelegate.get(v.getContext());
		Action action = Action.get(a.getPrefs().getIntPref(MainActivityPrefs.fab(a, actionPref)));
		if (action != null) action.getHandler().handle(a.getMediaSessionCallback(), a, uptimeMillis());
		updateIcon((FloatingButton) v);
	}

	/**
	 * Every action, grouped: Playback, Screen, Library, YouTube and Tools -- each with the settings
	 * page that belongs with it -- and the floating buttons' own settings last.
	 */
	@Override
	public boolean onLongClick(View v) {
		MainActivityDelegate a = MainActivityDelegate.get(v.getContext());
		FloatingButton fb = (FloatingButton) v;
		OverlayMenu menu = a.findViewById(R.id.control_menu);
		menu.show(b -> {
			category(a, fb, b, R.id.fab_cat_playback, R.drawable.play_pause, R.string.fab_cat_playback,
					sb -> {
						addAction(a, fb, sb, Action.PLAY_PAUSE, 0);
						if (MusicPlayer.isEnabled()) addAction(a, fb, sb, Action.PLAY_AS_MUSIC, 1);
						addAction(a, fb, sb, Action.VOLUME_MUTE_UNMUTE, 2);
						addAction(a, fb, sb, Action.FULLSCREEN_TOGGLE, 3);
						addAction(a, fb, sb, Action.AUDIO_EFFECTS, 4);
						addAction(a, fb, sb, Action.SLEEP_TIMER, 5);
					});
			category(a, fb, b, R.id.fab_cat_screen, R.drawable.dim_screen, R.string.fab_cat_screen,
					sb -> {
						addAction(a, fb, sb, Action.DIM_TOGGLE, 0);
						addSettings(a, sb, R.id.dim_settings, R.drawable.settings, R.string.dim_settings,
								SettingsFragment.SHOW_DIM_SETTINGS);
						addAction(a, fb, sb, Action.PRIVATE_MODE_TOGGLE, 1);
						addSettings(a, sb, R.id.private_mode_settings, R.drawable.private_mode,
								R.string.private_mode_settings, SettingsFragment.SHOW_PRIVATE_MODE_SETTINGS);
						addAction(a, fb, sb, Action.COVER_HIDE, 2);
					});
			category(a, fb, b, R.id.fab_cat_library, R.drawable.favorite, R.string.fab_cat_library,
					sb -> {
						addAction(a, fb, sb, Action.FAVORITE_ADD, 0);
						addAction(a, fb, sb, Action.PLAYLIST_ADD, 1);
						addAction(a, fb, sb, Action.OPEN_FAVORITES, 2);
						addAction(a, fb, sb, Action.OPEN_PLAYLISTS, 3);
						addAction(a, fb, sb, Action.DOWNLOAD_CURRENT, 4);
						addAction(a, fb, sb, Action.YOUTUBE_ALTERNATIVES, 5);
						addAction(a, fb, sb, Action.OPEN_HOME, 6);
					});
			if (MusicPlayer.hasYoutube()) {
				category(a, fb, b, R.id.fab_cat_youtube, R.drawable.search, R.string.fab_cat_youtube,
						sb -> {
							addAction(a, fb, sb, Action.YOUTUBE_SEARCH, 0);
							addAction(a, fb, sb, Action.YOUTUBE_UP_NEXT, 1);
							addAction(a, fb, sb, Action.QUALITY, 2);
							addAction(a, fb, sb, Action.YOUTUBE_COMMENTS, 3);
							addAction(a, fb, sb, Action.LYRICS, 4);
						});
			}
			category(a, fb, b, R.id.fab_cat_tools, R.drawable.fuel, R.string.fab_cat_tools,
					sb -> {
						addAction(a, fb, sb, Action.ALL_IN_ONE, 0);
						addAction(a, fb, sb, Action.REFUEL, 1);
						if (me.aap.fermata.addon.power.PowerUsageAddon.isEnabled()) {
							addAction(a, fb, sb, Action.POWER_USAGE, 2);
							addAction(a, fb, sb, Action.POWER_SAVER, 3);
						}
						if (me.aap.fermata.addon.ambience.AmbienceAddon.isEnabled()) {
							addAction(a, fb, sb, Action.AMBIENCE, 4);
							addAction(a, fb, sb, Action.AMBIENCE_TOGGLE, 5);
						}
						if (me.aap.fermata.addon.sleep.SleepAddon.isEnabled()) {
							addAction(a, fb, sb, Action.SLEEP, 6);
							addAction(a, fb, sb, Action.SLEEP_TOGGLE, 7);
						}
					});
			addSettings(a, b, R.id.fab_settings, R.drawable.fab, R.string.fab_settings,
					SettingsFragment.SHOW_FAB_SETTINGS);
			// Last item in the menu: the app's settings, as an item of its own.
			b.addItem(R.id.fab_app_settings, R.drawable.settings, R.string.settings).setHandler(item -> {
				Action.SETTINGS.getHandler().handle(a.getMediaSessionCallback(), a, uptimeMillis());
				return true;
			});
		});
		return true;
	}

	private void category(MainActivityDelegate a, FloatingButton fb, OverlayMenu.Builder b,
												@IdRes int id, @DrawableRes int icon, @StringRes int title,
												java.util.function.Consumer<OverlayMenu.Builder> items) {
		b.addItem(id, icon, title).setSubmenu(sb -> {
			sb.setSelectionHandler(item -> runAction(a, fb, item));
			items.accept(sb);
		});
	}

	private boolean runAction(MainActivityDelegate a, FloatingButton fb, OverlayMenuItem item) {
		Action action = item.getData();
		if (action != null) action.getHandler().handle(a.getMediaSessionCallback(), a, uptimeMillis());
		updateIcon(fb);
		return true;
	}

	private static void addAction(MainActivityDelegate a, FloatingButton fb, OverlayMenu.Builder b,
																Action action, int idx) {
		b.addItem(UiUtils.getArrayItemId(idx), iconFor(a, action), labelFor(a, action))
				.setData(action);
	}

	// Settings is a normal fragment hosted in frame_layout, which sits behind whatever is drawing
	// fullscreen video -- leave fullscreen first, or the page navigates but stays hidden under it.
	private static void addSettings(MainActivityDelegate a, OverlayMenu.Builder b, @IdRes int id,
																	@DrawableRes int icon, @StringRes int title, Object show) {
		b.addItem(id, icon, title).setHandler(item -> {
			a.exitVideoMode();
			// The floating buttons have their own page (the car's own set on the car screen).
			if (show == SettingsFragment.SHOW_FAB_SETTINGS) {
				a.showFragment(R.id.fab_settings_fragment, a.isCarActivity());
				return true;
			}
			a.showFragment(R.id.settings_fragment, show);
			return true;
		});
	}
}
