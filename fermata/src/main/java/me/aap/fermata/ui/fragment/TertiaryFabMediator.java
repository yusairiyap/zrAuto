package me.aap.fermata.ui.fragment;

import static android.os.SystemClock.uptimeMillis;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CHANGED;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.support.v4.media.session.PlaybackStateCompat;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.view.FloatingButton;

/**
 * Third, optional, user-configurable FAB. Mirrors {@link SecondaryFabMediator} but reads its own
 * enable/action prefs so it can be turned on independently and bound to a different action.
 */
public final class TertiaryFabMediator implements FloatingButton.Mediator,
		View.OnClickListener, View.OnLongClickListener, MediaSessionCallback.Listener {
	public static final TertiaryFabMediator instance = new TertiaryFabMediator();

	private static final List<Action> OFFERED_ACTIONS = List.of(
			Action.FULLSCREEN_TOGGLE, Action.VOLUME_MUTE_UNMUTE, Action.PLAY_PAUSE, Action.DIM_TOGGLE,
			Action.PRIVATE_MODE_TOGGLE);

	@Nullable
	private FloatingButton fab;

	private TertiaryFabMediator() {}

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

	@Override
	public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
		if (fab != null) updateIcon(fab);
	}

	private void updateIcon(FloatingButton fb) {
		MainActivityDelegate a = MainActivityDelegate.get(fb.getContext());
		Action action = Action.get(a.getPrefs().getIntPref(MainActivityPrefs.FAB3_ACTION));
		fb.setImageResource(iconFor(a, action));
	}

	@DrawableRes
	private int iconFor(MainActivityDelegate a, @Nullable Action action) {
		if (action == Action.FULLSCREEN_TOGGLE) return R.drawable.video_fullscreen;
		if (action == Action.VOLUME_MUTE_UNMUTE) return Action.isMuted(a.getContext()) ?
				R.drawable.volume_mute : R.drawable.volume_up;
		if (action == Action.DIM_TOGGLE) return a.getPrefs().getBooleanPref(MainActivityPrefs.DIM_ENABLED)
				? R.drawable.dim_screen : R.drawable.dim_screen_off;
		if (action == Action.PRIVATE_MODE_TOGGLE) return R.drawable.private_mode;
		if (action == Action.PLAY_PAUSE)
			return a.getMediaSessionCallback().isPlaying() ? R.drawable.pause : R.drawable.play;
		return R.drawable.play_pause;
	}

	@Override
	public void onClick(View v) {
		MainActivityDelegate a = MainActivityDelegate.get(v.getContext());
		Action action = Action.get(a.getPrefs().getIntPref(MainActivityPrefs.FAB3_ACTION));
		if (action != null) action.getHandler().handle(a.getMediaSessionCallback(), a, uptimeMillis());
		updateIcon((FloatingButton) v);
	}

	@Override
	public boolean onLongClick(View v) {
		MainActivityDelegate a = MainActivityDelegate.get(v.getContext());
		OverlayMenu menu = a.findViewById(R.id.control_menu);
		menu.show(b -> {
			b.setSelectionHandler(item -> {
				Action action = item.getData();
				if (action != null) {
					action.getHandler().handle(a.getMediaSessionCallback(), a, uptimeMillis());
				}
				updateIcon((FloatingButton) v);
				return true;
			});
			for (int i = 0; i < OFFERED_ACTIONS.size(); i++) {
				Action action = OFFERED_ACTIONS.get(i);
				b.addItem(UiUtils.getArrayItemId(i), iconFor(a, action), action.getName()).setData(action);
			}
			b.addItem(R.id.dim_settings, R.drawable.settings, R.string.dim_settings).setHandler(item -> {
				a.exitVideoMode();
				a.showFragment(R.id.settings_fragment, SettingsFragment.SHOW_DIM_SETTINGS);
				return true;
			});
			b.addItem(R.id.private_mode_settings, R.drawable.private_mode, R.string.private_mode_settings)
					.setHandler(item -> {
						a.exitVideoMode();
						a.showFragment(R.id.settings_fragment, SettingsFragment.SHOW_PRIVATE_MODE_SETTINGS);
						return true;
					});
			// Last item in the menu.
			b.addItem(R.id.fab_settings, R.drawable.fab, R.string.fab_settings).setHandler(item -> {
				a.exitVideoMode();
				a.showFragment(R.id.settings_fragment, SettingsFragment.SHOW_FAB_SETTINGS);
				return true;
			});
		});
		return true;
	}
}
