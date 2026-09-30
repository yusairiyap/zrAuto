package me.aap.fermata.addon.web.yt;

import static me.aap.utils.async.Completed.completed;

import android.content.Context;

import androidx.annotation.IdRes;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.MediaLibAddon;
import me.aap.fermata.addon.VideoTitleCache;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.addon.music.MusicTrackItem;
import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.WebBrowserAddon;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.DefaultMediaLib;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * @author Andrey Pavlenko
 */
@Keep
@SuppressWarnings("unused")
public class YoutubeAddon extends WebBrowserAddon
		implements PreferenceStore.Listener, MediaLibAddon, VideoTitleCache {
	@NonNull
	private static final AddonInfo info = FermataAddon.findAddonInfo(YoutubeAddon.class.getName());
	public static final int YT_DARK_MODE_DISABLED = 0;
	public static final int YT_DARK_MODE_ENABLED = 1;
	public static final int YT_DARK_MODE_AUTO = 2;
	private static final Pref<IntSupplier> YT_DARK_MODE = Pref.i("YT_DARK_MODE", YT_DARK_MODE_AUTO);
	private static final Pref<BooleanSupplier> YT_DESKTOP_VERSION = Pref.b("YT_DESKTOP_VERSION", false);
	private static final Pref<Supplier<String[]>> YT_BOOKMARKS = Pref.sa("YT_BOOKMARKS");
	private static final Pref<Supplier<String>> VIDEO_SCALE = Pref.s("VIDEO_SCALE", VideoScale.CONTAIN::prefName);
	// Superseded by preferredQualityPref, only read as its default so an existing "highest" choice
	// carries over.
	private static final Pref<BooleanSupplier> YT_AUTO_HIGHEST_QUALITY =
			Pref.b("YT_AUTO_HIGHEST_QUALITY", false);
	/**
	 * The quality videos load in, an index into {@link #QUALITY_LEVELS}: 0 is YouTube's own
	 * automatic choice, 1 the highest available, the rest a fixed resolution (the closest available
	 * one at or below it). Music mode ignores it and always takes the lowest.
	 */
	private final Pref<IntSupplier> preferredQualityPref = Pref.i("YT_PREFERRED_QUALITY",
			() -> getPreferenceStore().getBooleanPref(YT_AUTO_HIGHEST_QUALITY) ? 1 : 0);
	/** The YouTube player API's quality level names, by {@link #preferredQualityPref} index. */
	private static final String[] QUALITY_LEVELS = {null, "highest", "hd2160", "hd1440", "hd1080",
			"hd720", "large", "medium", "small", "tiny"};
	private static final String[] QUALITY_LABELS = {null, null, "2160p", "1440p", "1080p", "720p",
			"480p", "360p", "240p", "144p"};
	private static final Pref<BooleanSupplier> YT_SKIP_ADD = Pref.b("YT_SKIP_ADD", true);
	/** How many of the videos on the user's YouTube feed float around on the suggestions tab. */
	static final Pref<IntSupplier> BUBBLES_COUNT = Pref.i("YT_BUBBLES_COUNT", 12);
	static final int BUBBLES_TAP_AUTO = 0;
	static final int BUBBLES_TAP_VIDEO = 1;
	static final int BUBBLES_TAP_MUSIC = 2;
	/** What tapping a bubble does: follows the current mode, or always video, or always music. */
	static final Pref<IntSupplier> BUBBLES_TAP = Pref.i("YT_BUBBLES_TAP", BUBBLES_TAP_AUTO);
	/** Titles over the bubbles; and thumbnails on them, or else one-line text cards. */
	static final Pref<BooleanSupplier> BUBBLES_TEXT = Pref.b("YT_BUBBLES_TEXT", true);
	static final Pref<BooleanSupplier> BUBBLES_THUMBS = Pref.b("YT_BUBBLES_THUMBS", true);
	/** How fast the bubbles drift, in percent of the normal speed. */
	static final Pref<IntSupplier> BUBBLES_SPEED = Pref.i("YT_BUBBLES_SPEED", 100);
	private static final Pref<Supplier<String[]>> YT_VIDEO_TITLES = Pref.sa("YT_VIDEO_TITLES");
	/** Flat {videoId, channel, album, durationMs} groups, see {@link #cacheVideoInfo}. */
	private static final Pref<Supplier<String[]>> YT_VIDEO_INFO = Pref.sa("YT_VIDEO_INFO");
	private static final int VIDEO_INFO_FIELDS = 4;
	/**
	 * What the player reported for recently played videos (channel, duration), so adding the one
	 * playing to Favorites/a Playlist can keep that too -- see {@link #recordAddedVideo}.
	 */
	private final Map<String, VideoInfo> liveInfo = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, VideoInfo> eldest) {
			return size() > 256;
		}
	};
	private static final Pref<BooleanSupplier> YT_EQ_ENABLED = Pref.b("YT_EQ_ENABLED", false);
	static final Pref<IntSupplier> YT_EQ_PRESET = Pref.i("YT_EQ_PRESET", 0);
	private static final Pref<Supplier<int[]>> YT_EQ_BANDS = Pref.ia("YT_EQ_BANDS", () -> null);
	private static final Pref<BooleanSupplier> YT_BASS_ENABLED = Pref.b("YT_BASS_ENABLED", false);
	private static final Pref<IntSupplier> YT_BASS_STRENGTH = Pref.i("YT_BASS_STRENGTH", 0);
	private static final Pref<BooleanSupplier> YT_VIRT_ENABLED = Pref.b("YT_VIRT_ENABLED", false);
	private static final Pref<IntSupplier> YT_VIRT_STRENGTH = Pref.i("YT_VIRT_STRENGTH", 0);
	private static final Pref<BooleanSupplier> YT_REVERB_ENABLED = Pref.b("YT_REVERB_ENABLED", false);
	private static final Pref<IntSupplier> YT_REVERB_STRENGTH = Pref.i("YT_REVERB_STRENGTH", 0);
	// Impulse response length for the Live Hall convolver, in ms. Directly drives its CPU cost, so
	// it's user-adjustable (see YoutubeEqualizerView) rather than fixed; 2500 matches the value this
	// was hardcoded to before it became adjustable, so existing users hear no change by default.
	private static final Pref<IntSupplier> YT_REVERB_DURATION = Pref.i("YT_REVERB_DURATION", 2500);
	// Which reverb engine the youtube_equalizer.js content script uses for Live Hall: 0 = smooth
	// (cheap algorithmic comb+allpass, the default), 1 = convolution (the original impulse-response
	// reverb, higher CPU cost but a different, more "random room" character). See
	// YoutubeEqualizerView's "Hall quality" row and YoutubeEqualizerScript's config JSON.
	static final Pref<IntSupplier> YT_REVERB_ENGINE = Pref.i("YT_REVERB_ENGINE", 0);
	// Whether the currently playing video should just loop itself on end -- a property of "whatever
	// video is playing right now", not of any playlist/favorites list, so unlike "repeat the whole
	// playlist" (which reads/writes getQueueItem()'s own parent prefs and needs a real queue item to
	// mean anything) this works the same with or without one.
	private static final Pref<BooleanSupplier> YT_REPEAT_ONE = Pref.b("YT_REPEAT_ONE", false);
	// The user's own "Up next" queue: video ids, in play order, queued from the search panel or by
	// long-pressing a video on the page. Takes priority over whatever would otherwise play next --
	// see YoutubeMediaEngine#queueAwareNextPlayable() -- without replacing the Favorites/Playlist
	// queue item, so once it runs dry the list carries on from where it was. Persisted so a queue
	// survives the app being killed in the background mid-drive.
	private static final Pref<Supplier<String[]>> YT_UP_NEXT = Pref.sa("YT_UP_NEXT");
	private final List<Runnable> upNextListeners = new CopyOnWriteArrayList<>();
	private boolean ignorePrefChange;
	private YoutubeRootItem root;
	// The library item (with its real Favorites/Playlist parent) that the currently loaded video
	// was selected from, if any -- set by YoutubeVideoItem#loadInFragment() and kept in sync by
	// YoutubeMediaEngine as playback moves to the next/previous video. Lets next/prev navigate the
	// actual playlist/favorites order (see YoutubeMediaEngine#queueAwareNextPlayable/PrevPlayable)
	// instead of YouTube's own page-internal next/prev, which has no notion of the app's playlists.
	// Null while the user is just browsing YouTube outside of any app playlist/favorites context.
	// Typed as the generic PlayableItem, not YoutubeVideoItem: a Favorites/Playlist entry is an
	// exported wrapper around one (see ExportedItem), not a YoutubeVideoItem itself, and it's that
	// wrapper -- not the underlying original -- whose getParent() is the real container.
	@Nullable
	private PlayableItem queueItem;
	// The video id the app most recently and explicitly decided should be playing next -- set here
	// (not on YoutubeMediaEngine, which doesn't exist yet the first time this matters) by
	// YoutubeVideoItem#loadInFragment() for the initial tap-to-play, and by YoutubeMediaEngine#
	// prepare() for every next/prev after that. YoutubeMediaEngine#playing() treats a page video id
	// that doesn't match this as an unrequested transition (YouTube's own autonav winning a race --
	// see YoutubeWebView's capture-phase interceptors) and corrects it; without a value here at all
	// (null), a mismatch is left alone as ordinary, non-app-driven page browsing. Consumed (cleared)
	// once playing() confirms a match, or after it gives up correcting toward it.
	@Nullable
	private String pendingVideoId;

	// Past searches, most recent first (see getSearchHistory()). Persisted for normal browsing only;
	// Private Mode keeps its own in memory, never written anywhere, and drops it when it ends.
	private static final Pref<Supplier<String[]>> YT_SEARCH_HISTORY = Pref.sa("YT_SEARCH_HISTORY");
	private static final int MAX_SEARCH_HISTORY = 12;
	private final List<String> privateSearchHistory = new ArrayList<>();
	// A field, not a bare method reference: EventBroadcaster only holds listeners weakly -- see
	// WebBrowserAddon#privateModeListener.
	private final PreferenceStore.Listener searchHistoryListener = this::onSearchHistoryPrefsChanged;

	public YoutubeAddon() {
		// Lets the Music tab play YouTube in this addon's own player -- see MusicHooks.
		MusicPlayer.setYoutubeHooks(new MusicHooks());
		MainActivityPrefs.get().addBroadcastListener(searchHistoryListener);
	}

	/**
	 * Recent searches, most recent first: none when turned off in Settings; while in Private Mode
	 * only that session's own, which are never saved.
	 */
	@NonNull
	List<String> getSearchHistory() {
		MainActivityPrefs mp = MainActivityPrefs.get();
		if (!mp.getBooleanPref(MainActivityPrefs.SEARCH_HISTORY_ENABLED)) return new ArrayList<>();
		if (mp.isPrivateModeEnabled()) return new ArrayList<>(privateSearchHistory);
		return new ArrayList<>(Arrays.asList(getPreferenceStore().getStringArrayPref(YT_SEARCH_HISTORY)));
	}

	void addSearchHistory(String query) {
		String q = query.trim();
		MainActivityPrefs mp = MainActivityPrefs.get();
		if (q.isEmpty() || !mp.getBooleanPref(MainActivityPrefs.SEARCH_HISTORY_ENABLED)) return;
		List<String> l = getSearchHistory();
		for (int i = l.size() - 1; i >= 0; i--) {
			if (l.get(i).equalsIgnoreCase(q)) l.remove(i);
		}
		l.add(0, q);
		while (l.size() > MAX_SEARCH_HISTORY) l.remove(l.size() - 1);
		setSearchHistory(l);
	}

	void removeSearchHistory(String query) {
		List<String> l = getSearchHistory();
		if (l.remove(query)) setSearchHistory(l);
	}

	private void setSearchHistory(List<String> l) {
		if (MainActivityPrefs.get().isPrivateModeEnabled()) {
			privateSearchHistory.clear();
			privateSearchHistory.addAll(l);
		} else {
			getPreferenceStore().applyStringArrayPref(YT_SEARCH_HISTORY, l.toArray(new String[0]));
		}
		for (Runnable r : upNextListeners) r.run();
	}

	/**
	 * "Clear browsing data" and turning the setting off wipe the saved history; entering or leaving
	 * Private Mode, or its "clear now", drops that session's in-memory one.
	 */
	private void onSearchHistoryPrefsChanged(PreferenceStore store, List<Pref<?>> changed) {
		MainActivityPrefs mp = MainActivityPrefs.get();
		boolean off = changed.contains(MainActivityPrefs.SEARCH_HISTORY_ENABLED) &&
				!mp.getBooleanPref(MainActivityPrefs.SEARCH_HISTORY_ENABLED);
		boolean changedAny = false;
		if (off || changed.contains(MainActivityPrefs.NORMAL_MODE_CLEAR_REQUEST)) {
			getPreferenceStore().removePref(YT_SEARCH_HISTORY);
			changedAny = true;
		}
		if (off || changed.contains(MainActivityPrefs.PRIVATE_MODE_ENABLED) ||
				changed.contains(MainActivityPrefs.PRIVATE_MODE_CLEAR_REQUEST)) {
			privateSearchHistory.clear();
			changedAny = true;
		}
		if (changedAny || changed.contains(MainActivityPrefs.SEARCH_HISTORY_ENABLED)) {
			for (Runnable r : upNextListeners) r.run();
		}
	}

	/**
	 * The Music tab plays its YouTube tracks in this addon's player, with the video held at its
	 * lowest quality (see {@link YoutubeMediaEngine#applyQuality()}), and the music queue as the
	 * player's queue -- so next/prev, the crossfade between songs and the switch to video are all
	 * exactly YouTube's own.
	 */
	private final class MusicHooks implements MusicPlayer.YoutubeHooks {
		@Override
		public boolean play(MainActivityDelegate a, MusicTrackItem t) {
			String videoId = t.getVideoId();
			if ((videoId == null) || !(a.getLib() instanceof DefaultMediaLib lib)) return false;
			YoutubeVideoItem video = new YoutubeVideoItem(videoId, getRootItem(lib));
			ActivityFragment f = a.getFragment(getFragmentId());

			// Never opened yet: its page is created (and laid out, YouTube won't play in a zero-size
			// window) without showing the tab.
			if (f == null) f = a.preloadFragment(getFragmentId());
			if (f == null) return false;

			video.loadInFragment(f, t);
			return true;
		}

		@Override
		public void showVideo(MainActivityDelegate a) {
			// The video keeps playing through the tab switch, so nothing else would take it
			// fullscreen (see YoutubeFragment#onPlayableChanged): done once the tab is showing.
			if (a.showFragment(getFragmentId()) instanceof YoutubeFragment f) {
				a.post(f::enterVideoFullScreen);
			}
		}

		@Override
		public boolean showEffects(MainActivityDelegate a) {
			if (!(a.getFragment(getFragmentId()) instanceof YoutubeFragment f)) return false;
			YoutubeWebView web = f.getWebView();
			if (web == null) return false;
			YoutubeEqualizerView.show(web);
			return true;
		}

		@Override
		public void setQueueItem(PlayableItem item) {
			YoutubeAddon.this.setQueueItem(item);
		}

		@Override
		public void applyQuality(@Nullable MediaEngine eng) {
			if (eng instanceof YoutubeMediaEngine yt) yt.applyQuality();
		}

		@Override
		public void openSearch(MainActivityDelegate a, boolean upNextOnly) {
			YoutubeFragment.openSearch(a.getContext(), upNextOnly);
		}

		@Override
		public int addToVideoQueue(MainActivityDelegate a, List<? extends PlayableItem> items) {
			if (!(a.getFragment(getFragmentId()) instanceof YoutubeFragment f)) return -1;
			YoutubeWebView web = f.getWebView();
			YoutubeMediaEngine eng = (web == null) ? null : web.getEngine();
			if ((eng == null) || !eng.isActive()) return -1;
			int n = 0;
			for (PlayableItem pi : items) {
				String id = YoutubeVideoItem.extractYoutubeVideoId(pi);
				if (id == null) continue;
				String name = pi.getName();
				if (!addUpNext(id, id.equals(name) ? null : name, false)) break;
				n++;
			}
			return n;
		}
	}

	@Nullable
	PlayableItem getQueueItem() {
		return queueItem;
	}

	void setQueueItem(@Nullable PlayableItem item) {
		if (queueItem == item) return;
		queueItem = item;
		// The Up next list previews the queue item's upcoming list entries -- see YoutubeSearchPanel.
		for (Runnable r : upNextListeners) r.run();
	}

	@Nullable
	String getPendingVideoId() {
		return pendingVideoId;
	}

	void setPendingVideoId(@Nullable String videoId) {
		pendingVideoId = videoId;
	}

	/** The Up next queue, in play order -- see {@link #YT_UP_NEXT}. */
	@NonNull
	List<String> getUpNext() {
		return new ArrayList<>(Arrays.asList(getPreferenceStore().getStringArrayPref(YT_UP_NEXT)));
	}

	boolean hasUpNext() {
		return getPreferenceStore().getStringArrayPref(YT_UP_NEXT).length != 0;
	}

	/** The video that plays next, without taking it off the queue -- see {@link #removeUpNext}. */
	@Nullable
	String peekUpNext() {
		String[] a = getPreferenceStore().getStringArrayPref(YT_UP_NEXT);
		return (a.length == 0) ? null : a[0];
	}

	/** See {@code MainActivityPrefs#UP_NEXT_MAX}. */
	int getUpNextMax() {
		return Math.max(1, Math.min(50, MainActivityPrefs.get().getIntPref(MainActivityPrefs.UP_NEXT_MAX)));
	}

	/**
	 * Queues {@code videoId}: at the front (play next) or at the end. A video already queued is
	 * moved rather than queued twice. False if the queue is full -- see {@link #getUpNextMax()}.
	 */
	boolean addUpNext(String videoId, @Nullable String title, boolean first) {
		if ((title != null) && !title.isEmpty()) cacheVideoTitle(videoId, title);
		List<String> l = getUpNext();
		if (!l.remove(videoId) && (l.size() >= getUpNextMax())) return false;
		if (first) l.add(0, videoId);
		else l.add(videoId);
		setUpNext(l);
		return true;
	}

	/** Removes the first occurrence of {@code videoId}; false if it wasn't queued. */
	boolean removeUpNext(String videoId) {
		List<String> l = getUpNext();
		if (!l.remove(videoId)) return false;
		setUpNext(l);
		return true;
	}

	void clearUpNext() {
		if (hasUpNext()) setUpNext(Collections.emptyList());
	}

	private void setUpNext(List<String> l) {
		getPreferenceStore().applyStringArrayPref(YT_UP_NEXT, l.toArray(new String[0]));
		for (Runnable r : upNextListeners) r.run();
	}

	void addUpNextListener(Runnable l) {
		upNextListeners.add(l);
	}

	void removeUpNextListener(Runnable l) {
		upNextListeners.remove(l);
	}

	boolean isRepeatOneEnabled() {
		return getPreferenceStore().getBooleanPref(YT_REPEAT_ONE);
	}

	void setRepeatOneEnabled(boolean enabled) {
		getPreferenceStore().applyBooleanPref(YT_REPEAT_ONE, enabled);
	}

	@IdRes
	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.youtube_fragment;
	}

	@NonNull
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new YoutubeFragment();
	}

	@Override
	public Pref<IntSupplier> getForceDarkPref() {
		return YT_DARK_MODE;
	}

	@Override
	public Pref<BooleanSupplier> getDesktopVersionPref() {
		return YT_DESKTOP_VERSION;
	}

	@Override
	public Pref<Supplier<String[]>> getBookmarksPref() {
		return YT_BOOKMARKS;
	}

	boolean skipAd() {
		return getPreferenceStore().getBooleanPref(YT_SKIP_ADD);
	}

	boolean skipAdChanged(List<Pref<?>> prefs) {
		return prefs.contains(YT_SKIP_ADD);
	}

	@NonNull
	String getVideoTitle(String videoId) {
		String[] p = getPreferenceStore().getStringArrayPref(YT_VIDEO_TITLES);
		for (int i = 0; i < p.length - 1; i += 2) {
			if (p[i].equals(videoId)) return p[i + 1];
		}
		return videoId;
	}

	/**
	 * The title to show for {@code videoId}: without its channel's name in front, when the channel
	 * is known (see MusicTrackItem#titleWithoutArtist()); the id itself if even the title isn't.
	 */
	@NonNull
	String getDisplayTitle(String videoId) {
		String title = getVideoTitle(videoId);
		return title.equals(videoId) ? title : titleWithoutChannel(videoId, title);
	}

	/** {@code title} of {@code videoId} without its channel's name in front, if the channel is known. */
	@Nullable
	String titleWithoutChannel(String videoId, @Nullable String title) {
		VideoInfo info = liveInfo.get(videoId);
		String channel = (info != null) ? info.artist : null;
		if (channel == null) {
			info = getVideoInfo(videoId);
			if (info != null) channel = info.artist;
		}
		return MusicTrackItem.titleWithoutArtist(title, channel);
	}

	void cacheVideoTitle(String videoId, String title) {
		cacheVideoTitles(Collections.singletonMap(videoId, title));
	}

	@Override
	public void cacheVideoTitles(Map<String, String> titles) {
		if (titles.isEmpty()) return;
		String[] p = getPreferenceStore().getStringArrayPref(YT_VIDEO_TITLES);
		Map<String, String> m = new LinkedHashMap<>(p.length / 2 + titles.size() + 1);
		for (int i = 0; i < p.length - 1; i += 2) m.put(p[i], p[i + 1]);
		m.putAll(titles);

		String[] a = new String[m.size() * 2];
		int i = 0;
		for (Map.Entry<String, String> e : m.entrySet()) {
			a[i++] = e.getKey();
			a[i++] = e.getValue();
		}
		getPreferenceStore().applyStringArrayPref(YT_VIDEO_TITLES, a);
	}

	/** What's stored about {@code videoId}, or null if nothing is. */
	@Nullable
	VideoInfo getVideoInfo(String videoId) {
		String[] p = getPreferenceStore().getStringArrayPref(YT_VIDEO_INFO);
		for (int i = 0; i <= p.length - VIDEO_INFO_FIELDS; i += VIDEO_INFO_FIELDS) {
			if (!p[i].equals(videoId)) continue;
			long dur;
			try {
				dur = Long.parseLong(p[i + 3]);
			} catch (NumberFormatException ex) {
				dur = -1;
			}
			return new VideoInfo(null, emptyToNull(p[i + 1]), emptyToNull(p[i + 2]), dur);
		}
		return null;
	}

	@Override
	public void cacheVideoInfo(Map<String, VideoInfo> info) {
		if (info.isEmpty()) return;
		Map<String, String> titles = new LinkedHashMap<>();
		String[] p = getPreferenceStore().getStringArrayPref(YT_VIDEO_INFO);
		Map<String, String[]> m = new LinkedHashMap<>(p.length / VIDEO_INFO_FIELDS + info.size() + 1);
		for (int i = 0; i <= p.length - VIDEO_INFO_FIELDS; i += VIDEO_INFO_FIELDS) {
			m.put(p[i], new String[]{p[i + 1], p[i + 2], p[i + 3]});
		}
		boolean changed = false;

		for (Map.Entry<String, VideoInfo> e : info.entrySet()) {
			VideoInfo vi = e.getValue();
			if ((vi.title != null) && !vi.title.isEmpty()) titles.put(e.getKey(), vi.title);
			String[] v = m.get(e.getKey());
			if (v == null) v = new String[]{"", "", "-1"};
			String[] nv = v.clone();
			if ((vi.artist != null) && !vi.artist.isEmpty()) nv[0] = vi.artist;
			if ((vi.album != null) && !vi.album.isEmpty()) nv[1] = vi.album;
			if (vi.durationMs > 0) nv[2] = String.valueOf(vi.durationMs);
			if (nv[0].isEmpty() && nv[1].isEmpty() && "-1".equals(nv[2])) continue;
			if (Arrays.equals(v, nv) && m.containsKey(e.getKey())) continue;
			m.put(e.getKey(), nv);
			changed = true;
		}

		if (!titles.isEmpty()) cacheVideoTitles(titles);
		if (!changed) return;
		String[] a = new String[m.size() * VIDEO_INFO_FIELDS];
		int i = 0;
		for (Map.Entry<String, String[]> e : m.entrySet()) {
			a[i++] = e.getKey();
			for (String f : e.getValue()) a[i++] = f;
		}
		getPreferenceStore().applyStringArrayPref(YT_VIDEO_INFO, a);
	}

	/** See {@link #liveInfo}; fed by YoutubeMediaEngine as a video plays. */
	void setLiveVideoInfo(String videoId, @Nullable String channel, long durationMs) {
		if ((videoId == null) || videoId.isEmpty()) return;
		VideoInfo old = liveInfo.get(videoId);
		if ((channel == null) && (old != null)) channel = old.artist;
		if ((durationMs <= 0) && (old != null)) durationMs = old.durationMs;
		liveInfo.put(videoId, new VideoInfo(null, channel, null, durationMs));
	}

	@Override
	public void recordAddedVideo(String videoId) {
		VideoInfo live = liveInfo.get(videoId);
		if (live != null) cacheVideoInfo(Collections.singletonMap(videoId, live));
	}

	@Nullable
	private static String emptyToNull(String s) {
		return ((s == null) || s.isEmpty()) ? null : s;
	}

	@Override
	public boolean isSupportedItem(Item i) {
		return (i instanceof YoutubeVideoItem);
	}

	@NonNull
	public YoutubeRootItem getRootItem(DefaultMediaLib lib) {
		if ((root == null) || (root.getLib() != lib)) root = new YoutubeRootItem(lib);
		return root;
	}

	@Nullable
	@Override
	public FutureSupplier<? extends Item> getItem(DefaultMediaLib lib, @Nullable String scheme,
																								 String id) {
		if (!"youtube".equals(scheme)) return null;
		String videoId = id.substring(id.indexOf(':') + 1);
		return completed(new YoutubeVideoItem(videoId, getRootItem(lib)));
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		super.contributeSettings(ctx, store, set, visibility);
		getPreferenceStore().addBroadcastListener(this);
		MainActivityPrefs.get().addBroadcastListener(this);
		FermataApplication.get().getPreferenceStore().addBroadcastListener(this);

		set.addListPref(o -> {
			String[] labels = QUALITY_LABELS.clone();
			labels[0] = ctx.getString(me.aap.fermata.R.string.auto);
			labels[1] = ctx.getString(R.string.video_quality_highest);
			o.store = getPreferenceStore();
			o.pref = preferredQualityPref;
			o.title = R.string.preferred_video_quality;
			o.subtitle = me.aap.fermata.R.string.string_format;
			o.formatSubtitle = true;
			o.stringValues = labels;
			o.visibility = visibility;
		});

		set.addBooleanPref(o -> {
			o.store = getPreferenceStore();
			o.pref = YT_SKIP_ADD;
			o.title = R.string.try_to_skip_ad;
			o.visibility = visibility;
		});

		YoutubeSponsorBlock.contributeSettings(getPreferenceStore(), set, visibility);
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<Pref<?>> prefs) {
		if (ignorePrefChange) return;
		ignorePrefChange = true;

		if (prefs.contains(getInfo().enabledPref)) {
			if (!store.getBooleanPref(getInfo().enabledPref)) {
				MainActivityPrefs ap = MainActivityPrefs.get();
				if (getInfo().className.equals(ap.getShowAddonOnStartPref()))
					ap.setShowAddonOnStartPref(null);
			}
		}

		ignorePrefChange = false;
	}

	@Override
	public void uninstall() {
		MusicPlayer.setYoutubeHooks(null);
		getPreferenceStore().removeBroadcastListener(this);
		MainActivityPrefs.get().removeBroadcastListener(this);
		FermataApplication.get().getPreferenceStore().removeBroadcastListener(this);
	}

	VideoScale getScale() {
		switch (getPreferenceStore().getStringPref(VIDEO_SCALE)) {
			case "fill":
				return VideoScale.FILL;
			case "contain":
				return VideoScale.CONTAIN;
			case "cover":
				return VideoScale.COVER;
			default:
				return VideoScale.NONE;
		}
	}

	void setScale(VideoScale scale) {
		getPreferenceStore().applyStringPref(VIDEO_SCALE, scale.prefName());
	}

	/**
	 * The quality level videos should load in outside music mode: "highest", a player API level name
	 * (e.g. "hd720"), or null to leave it to YouTube.
	 */
	@Nullable
	String preferredQuality() {
		int i = getPreferenceStore().getIntPref(preferredQualityPref);
		return ((i > 0) && (i < QUALITY_LEVELS.length)) ? QUALITY_LEVELS[i] : null;
	}

	boolean preferredQualityChanged(List<Pref<?>> prefs) {
		return prefs.contains(preferredQualityPref);
	}

	boolean eqEnabled() {
		return getPreferenceStore().getBooleanPref(YT_EQ_ENABLED);
	}

	void setEqEnabled(boolean enabled) {
		getPreferenceStore().applyBooleanPref(YT_EQ_ENABLED, enabled);
	}

	int eqPreset() {
		return getPreferenceStore().getIntPref(YT_EQ_PRESET);
	}

	void setEqPreset(int preset) {
		getPreferenceStore().applyIntPref(YT_EQ_PRESET, preset);
	}

	int[] eqBands() {
		int[] bands = getPreferenceStore().getIntArrayPref(YT_EQ_BANDS);
		return ((bands != null) && (bands.length == YoutubeEqualizerPresets.NUM_BANDS)) ? bands :
				new int[YoutubeEqualizerPresets.NUM_BANDS];
	}

	void setEqBands(int[] bands) {
		getPreferenceStore().applyIntArrayPref(YT_EQ_BANDS, bands);
	}

	boolean bassEnabled() {
		return getPreferenceStore().getBooleanPref(YT_BASS_ENABLED);
	}

	void setBassEnabled(boolean enabled) {
		getPreferenceStore().applyBooleanPref(YT_BASS_ENABLED, enabled);
	}

	int bassStrength() {
		return getPreferenceStore().getIntPref(YT_BASS_STRENGTH);
	}

	void setBassStrength(int strength) {
		getPreferenceStore().applyIntPref(YT_BASS_STRENGTH, strength);
	}

	boolean virtEnabled() {
		return getPreferenceStore().getBooleanPref(YT_VIRT_ENABLED);
	}

	void setVirtEnabled(boolean enabled) {
		getPreferenceStore().applyBooleanPref(YT_VIRT_ENABLED, enabled);
	}

	int virtStrength() {
		return getPreferenceStore().getIntPref(YT_VIRT_STRENGTH);
	}

	void setVirtStrength(int strength) {
		getPreferenceStore().applyIntPref(YT_VIRT_STRENGTH, strength);
	}

	boolean reverbEnabled() {
		return getPreferenceStore().getBooleanPref(YT_REVERB_ENABLED);
	}

	void setReverbEnabled(boolean enabled) {
		getPreferenceStore().applyBooleanPref(YT_REVERB_ENABLED, enabled);
	}

	int reverbStrength() {
		return getPreferenceStore().getIntPref(YT_REVERB_STRENGTH);
	}

	void setReverbStrength(int strength) {
		getPreferenceStore().applyIntPref(YT_REVERB_STRENGTH, strength);
	}

	int reverbDuration() {
		return getPreferenceStore().getIntPref(YT_REVERB_DURATION);
	}

	void setReverbDuration(int durationMs) {
		getPreferenceStore().applyIntPref(YT_REVERB_DURATION, durationMs);
	}

	/** 0 = smooth (algorithmic), 1 = convolution (impulse-response). */
	int reverbEngine() {
		return getPreferenceStore().getIntPref(YT_REVERB_ENGINE);
	}

	void setReverbEngine(int engine) {
		getPreferenceStore().applyIntPref(YT_REVERB_ENGINE, engine);
	}

	boolean eqPrefsChanged(List<Pref<?>> prefs) {
		return prefs.contains(YT_EQ_ENABLED) || prefs.contains(YT_EQ_PRESET) ||
				prefs.contains(YT_EQ_BANDS) || prefs.contains(YT_BASS_ENABLED) ||
				prefs.contains(YT_BASS_STRENGTH) || prefs.contains(YT_VIRT_ENABLED) ||
				prefs.contains(YT_VIRT_STRENGTH) || prefs.contains(YT_REVERB_ENABLED) ||
				prefs.contains(YT_REVERB_STRENGTH) || prefs.contains(YT_REVERB_DURATION) ||
				prefs.contains(YT_REVERB_ENGINE);
	}

	enum VideoScale {
		FILL, CONTAIN, COVER, NONE;

		String prefName() {
			return name().toLowerCase();
		}
	}
}
