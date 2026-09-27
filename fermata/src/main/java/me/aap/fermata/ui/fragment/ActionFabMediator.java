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
			Action.OPEN_FAVORITES, Action.OPEN_PLAYLISTS);

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
		MainActivityDelegate.get(fb.getContext()).getMediaSessionCallback().addBroadcastListener(this);
	}

	@Override
	public void disable(FloatingButton fb) {
		FloatingButton.Mediator.super.disable(fb);
		MainActivityDelegate.get(fb.getContext()).getMediaSessionCallback()
				.removeBroadcastListener(this);
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
		if (fab != null) updateIcon(fab);
	}

	private void updateIcon(FloatingButton fb) {
		MainActivityDelegate a = MainActivityDelegate.get(fb.getContext());
		fb.setImageResource(iconFor(a, Action.get(a.getPrefs().getIntPref(actionPref))));
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
		if (action == Action.PLAY_AS_MUSIC) return R.drawable.music;
		if (action == Action.YOUTUBE_SEARCH) return R.drawable.search;
		if (action == Action.YOUTUBE_UP_NEXT) return R.drawable.up_next;
		if (action == Action.OPEN_FAVORITES) return R.drawable.favorite_filled;
		if (action == Action.OPEN_PLAYLISTS) return R.drawable.playlist;
		if (action == Action.FAVORITE_ADD) return Action.isCurrentFavorite(a) ?
				R.drawable.favorite_filled : R.drawable.favorite;
		if (action == Action.PLAY_PAUSE)
			return a.getMediaSessionCallback().isPlaying() ? R.drawable.pause : R.drawable.play;
		return R.drawable.play_pause;
	}

	/** "Add to favorites" reads "Remove from favorites" when it would remove (it toggles). */
	@StringRes
	private static int labelFor(MainActivityDelegate a, Action action) {
		if ((action == Action.FAVORITE_ADD) && Action.isCurrentFavorite(a))
			return R.string.favorites_remove;
		return action.getName();
	}

	@Override
	public void onClick(View v) {
		MainActivityDelegate a = MainActivityDelegate.get(v.getContext());
		Action action = Action.get(a.getPrefs().getIntPref(actionPref));
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
					});
			category(a, fb, b, R.id.fab_cat_screen, R.drawable.dim_screen, R.string.fab_cat_screen,
					sb -> {
						addAction(a, fb, sb, Action.DIM_TOGGLE, 0);
						addSettings(a, sb, R.id.dim_settings, R.drawable.settings, R.string.dim_settings,
								SettingsFragment.SHOW_DIM_SETTINGS);
						addAction(a, fb, sb, Action.PRIVATE_MODE_TOGGLE, 1);
						addSettings(a, sb, R.id.private_mode_settings, R.drawable.private_mode,
								R.string.private_mode_settings, SettingsFragment.SHOW_PRIVATE_MODE_SETTINGS);
					});
			category(a, fb, b, R.id.fab_cat_library, R.drawable.favorite, R.string.fab_cat_library,
					sb -> {
						addAction(a, fb, sb, Action.FAVORITE_ADD, 0);
						addAction(a, fb, sb, Action.PLAYLIST_ADD, 1);
						addAction(a, fb, sb, Action.OPEN_FAVORITES, 2);
						addAction(a, fb, sb, Action.OPEN_PLAYLISTS, 3);
					});
			if (MusicPlayer.hasYoutube()) {
				category(a, fb, b, R.id.fab_cat_youtube, R.drawable.search, R.string.fab_cat_youtube,
						sb -> {
							addAction(a, fb, sb, Action.YOUTUBE_SEARCH, 0);
							addAction(a, fb, sb, Action.YOUTUBE_UP_NEXT, 1);
						});
			}
			category(a, fb, b, R.id.fab_cat_tools, R.drawable.fuel, R.string.fab_cat_tools,
					sb -> addAction(a, fb, sb, Action.REFUEL, 0));
			// Last item in the menu.
			addSettings(a, b, R.id.fab_settings, R.drawable.fab, R.string.fab_settings,
					SettingsFragment.SHOW_FAB_SETTINGS);
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
			a.showFragment(R.id.settings_fragment, show);
			return true;
		});
	}
}
