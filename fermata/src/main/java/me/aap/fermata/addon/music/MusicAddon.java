package me.aap.fermata.addon.music;

import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedNull;

import android.content.Context;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.FermataActivityAddon;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.MediaLibAddon;
import me.aap.fermata.media.lib.DefaultMediaLib;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MusicPlayerFragment;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * Registers the Music tab -- a full-screen, audio-only music player for local files and YouTube,
 * with its own persistent queue (see {@link MusicQueue}). Built directly into the {@code fermata}
 * module, like the Fuel Log, since it needs no optional dependency. Also a {@link MediaLibAddon},
 * so queue tracks resolve by id (Android Auto's browser, the media session's queue) like any other
 * library item.
 */
@Keep
public class MusicAddon implements MediaLibAddon, FermataActivityAddon,
		MediaSessionCallback.Listener {
	private static final AddonInfo info = FermataAddon.findAddonInfo(MusicAddon.class.getName());
	/** How blurred the Music tab's background copy of the cover is: 0 (sharp) to 100. */
	public static final Pref<IntSupplier> BG_BLUR = Pref.i("MUSIC_BG_BLUR", 60);
	/** How far the background is zoomed in, in percent: 100 (fits the screen) to 300. */
	public static final Pref<IntSupplier> BG_ZOOM = Pref.i("MUSIC_BG_ZOOM", 120);
	@Nullable
	private MusicQueue queue;
	@Nullable
	private MediaSessionCallback callback;

	@Nullable
	public static MusicAddon get() {
		return AddonManager.get().getAddon(MusicAddon.class);
	}

	@Override
	public int getAddonId() {
		return R.id.music_addon;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new MusicPlayerFragment();
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		set.addIntPref(o -> {
			o.store = store;
			o.pref = BG_BLUR;
			o.title = R.string.music_bg_blur;
			o.seekMin = 0;
			o.seekMax = 100;
			o.seekScale = 5;
			o.ems = 3;
			o.visibility = visibility.copy();
		});
		set.addIntPref(o -> {
			o.store = store;
			o.pref = BG_ZOOM;
			o.title = R.string.music_bg_zoom;
			o.seekMin = 100;
			o.seekMax = 300;
			o.seekScale = 5;
			o.ems = 3;
			o.visibility = visibility.copy();
		});
	}

	@NonNull
	public synchronized MusicQueue getQueue(MediaLib lib) {
		if ((queue == null) || (queue.getLib() != lib)) queue = new MusicQueue(lib);
		return queue;
	}

	@Override
	public boolean isSupportedItem(Item i) {
		return (i instanceof MusicQueue) || (i instanceof MusicTrackItem);
	}

	@Override
	public Item getRootItem(DefaultMediaLib lib) {
		return getQueue(lib);
	}

	@Nullable
	@Override
	public FutureSupplier<? extends Item> getItem(DefaultMediaLib lib, @Nullable String scheme,
																								String id) {
		if (scheme == null) {
			if (!MusicQueue.ID.equals(id)) return null;
			MusicQueue q = getQueue(lib);
			return completed(q);
		}
		if (!MusicQueue.SCHEME.equals(scheme)) return null;
		MusicTrackItem t = getQueue(lib).findTrack(id);
		if (t == null) return completedNull();
		return completed(t);
	}

	@Override
	public void onActivityCreate(MainActivityDelegate a) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		if (callback != null) callback.removeBroadcastListener(this);
		callback = cb;
		cb.addBroadcastListener(this);
	}

	@Override
	public void onActivityDestroy(MainActivityDelegate a) {
		if (callback == a.getMediaSessionCallback()) {
			callback.removeBroadcastListener(this);
			callback = null;
		}
	}

	@Override
	public void stop() {
		if (callback != null) {
			callback.removeBroadcastListener(this);
			callback = null;
		}
	}

	@Override
	public void onPlaybackStateChanged(MediaSessionCallback cb, PlaybackStateCompat state) {
		// Remembers where the queue was, so opening the tab (or pressing play in it) after a restart
		// picks up the same track at the same spot. Only on settled states, not every transition.
		int st = state.getState();
		if ((st != PlaybackStateCompat.STATE_PLAYING) && (st != PlaybackStateCompat.STATE_PAUSED))
			return;
		PlayableItem i = cb.getCurrentItem();
		if (i instanceof MusicTrackItem t) t.getParent().setCurrent(t, state.getPosition());
	}
}
