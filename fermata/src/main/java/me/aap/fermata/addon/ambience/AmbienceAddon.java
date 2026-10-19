package me.aap.fermata.addon.ambience;

import android.content.Context;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.FermataActivityAddon;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.addon.FermataMediaServiceAddon;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.AmbienceFragment;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * The Ambience tab: rain, thunder, a fireplace, wind, waves, a stream, crickets and birds that play
 * softly behind whatever else plays, each with its own intensity, a shared volume and a length (see
 * {@link AmbienceEngine}), and the weather drawn over the Music tab while they do. Built into the
 * {@code fermata} module like the Power usage tab.
 * <p>
 * The addon itself only wires the engine to the rest of the app: with "Pause with playback" it
 * follows the media session's play state, and it re-draws the scenes when a setting changes.
 */
@Keep
public class AmbienceAddon implements FermataFragmentAddon, FermataActivityAddon,
		FermataMediaServiceAddon, MediaSessionCallback.Listener, PreferenceStore.Listener {
	private static final AddonInfo info = FermataAddon.findAddonInfo(AmbienceAddon.class.getName());
	private MediaSessionCallback callback;
	private boolean listening;

	@Override
	public int getAddonId() {
		return R.id.ambience_addon;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new AmbienceFragment();
	}

	/** Whether the addon is on: its actions only work then. */
	public static boolean isEnabled() {
		AddonManager m = AddonManager.get();
		return (m != null) && (m.getAddon(AmbienceAddon.class) != null);
	}

	private void attach(MediaSessionCallback cb) {
		if (!listening) {
			listening = true;
			AmbienceEngine.prefs().addBroadcastListener(this);
		}
		if (callback == cb) return;
		if (callback != null) callback.removeBroadcastListener(this);
		callback = cb;
		if (cb != null) cb.addBroadcastListener(this);
	}

	@Override
	public void onActivityCreate(MainActivityDelegate a) {
		attach(a.getMediaSessionCallback());
		// Which recordings can be downloaded (cached, and read again every few hours).
		AmbienceSamples.get().refresh();
	}

	@Override
	public void onServiceCreate(MediaSessionCallback cb) {
		attach(cb);
	}

	/** With "Pause with playback" the ambience waits while the playback does. */
	@Override
	public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
		AmbienceEngine e = AmbienceEngine.get();
		if (!AmbienceEngine.prefs().getBooleanPref(AmbienceEngine.FOLLOW)) {
			e.setHeld(false);
			return;
		}
		int s = state.getState();
		if (s == PlaybackStateCompat.STATE_PLAYING) e.setHeld(false);
		else if ((s == PlaybackStateCompat.STATE_PAUSED) || (s == PlaybackStateCompat.STATE_STOPPED)) {
			e.setHeld(true);
		}
	}

	/** An animation switch was flipped, or "Pause with playback" turned off: the views follow. */
	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		for (PreferenceStore.Pref<?> p : prefs) {
			String name = p.getName();
			if (name.equals(AmbienceEngine.ANIM_MUSIC.getName()) ||
					name.equals(AmbienceEngine.ANIM_TAB.getName())) {
				AmbienceEngine.get().refresh();
			} else if (name.equals(AmbienceEngine.FOLLOW.getName()) &&
					!store.getBooleanPref(AmbienceEngine.FOLLOW)) {
				AmbienceEngine.get().setHeld(false);
			}
		}
	}

	@Override
	public void stop() {
		if (callback != null) callback.removeBroadcastListener(this);
		callback = null;
		if (listening) {
			listening = false;
			AmbienceEngine.prefs().removeBroadcastListener(this);
		}
		AmbienceEngine.get().shutdown();
		AmbienceEngine.get().refresh();
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		PreferenceStore ps = AmbienceEngine.prefs();
		set.addIntPref(o -> {
			o.store = ps;
			o.pref = AmbienceEngine.FADE;
			o.title = R.string.ambience_set_fade;
			o.subtitle = R.string.ambience_set_fade_sub;
			o.seekMin = 1;
			o.seekMax = 10;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = ps;
			o.pref = AmbienceEngine.FOLLOW;
			o.title = R.string.ambience_set_follow;
			o.subtitle = R.string.ambience_set_follow_sub;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = ps;
			o.pref = AmbienceEngine.NOISY;
			o.title = R.string.ambience_set_noisy;
			o.subtitle = R.string.ambience_set_noisy_sub;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = ps;
			o.pref = AmbienceEngine.ANIM_MUSIC;
			o.title = R.string.ambience_set_anim_music;
			o.subtitle = R.string.ambience_set_anim_music_sub;
			o.visibility = visibility.copy();
		});
		set.addBooleanPref(o -> {
			o.store = ps;
			o.pref = AmbienceEngine.ANIM_TAB;
			o.title = R.string.ambience_set_anim_tab;
			o.subtitle = R.string.ambience_set_anim_tab_sub;
			o.visibility = visibility.copy();
		});
	}
}
