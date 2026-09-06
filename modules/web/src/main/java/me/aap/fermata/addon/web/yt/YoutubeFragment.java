package me.aap.fermata.addon.web.yt;

import static me.aap.fermata.addon.web.FermataWebClient.isYoutubeUri;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedVoid;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.web.FermataChromeClient;
import me.aap.fermata.addon.web.FermataWebView;
import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.WebBrowserAddon;
import me.aap.fermata.addon.web.WebBrowserFragment;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.DefaultMediaLib;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.VideoView;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.Consumer;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.pref.SharedPreferenceStore;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
@Keep
@SuppressWarnings("unused")
public class YoutubeFragment extends WebBrowserFragment implements FermataServiceUiBinder.Listener {
	static final String DEFAULT_URL = "https://m.youtube.com";
	private static final Set<String> DEFAULT_URLS = new HashSet<>(Arrays.asList(DEFAULT_URL, DEFAULT_URL + '/'));
	private static final Pref<LongSupplier> RESUME_POS = Pref.l("YT_RESUME_POS", 0L);
	private static final String YT_VIDEO_VIEW_TAG = "yt_video_view_overlay";
	// Two JS samples this far apart tell a genuinely stuck video (readyState never reaches
	// HAVE_CURRENT_DATA, or currentTime never advances while claiming to play) apart from an
	// ordinary pause/resume blip -- see recoverFullscreenVideo()/isStuck() below.
	private static final long HEALTH_PROBE_DELAY_1 = 600L;
	private static final long HEALTH_PROBE_DELAY_2 = 900L;
	private static final String VIDEO_STATE_JS =
			"(function(){var v=document.querySelector('video');" +
					"return v?JSON.stringify({t:v.currentTime,p:v.paused,r:v.readyState,e:v.ended}):" +
					"'null';})()";
	private boolean playOnResume;
	private boolean recovering;

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.youtube_fragment;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.youtube, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
		YoutubeAddon addon = AddonManager.get().getAddon(YoutubeAddon.class);
		if (addon == null) return;

		String url;
		boolean pause;

		if (state != null) {
			url = state.getString("url", DEFAULT_URL);
			pause = state.getBoolean("pause", false);
		} else {
			url = DEFAULT_URL;
			pause = false;
		}

		MainActivityDelegate.getActivityDelegate(view.getContext()).onSuccess(a -> {
			YoutubeWebView webView = a.findViewById(R.id.ytWebView);
			initWebView(webView, addon, view);
			registerListeners(a);
			webView.loadUrl(DEFAULT_URL);
			if (!DEFAULT_URL.equals(url)) a.post(() -> webView.loadUrl(url));
			a.postDelayed(() -> {
				PreferenceStore ps = addon.getPreferenceStore();
				long pos = ps.getLongPref(RESUME_POS);
				ps.removePref(RESUME_POS);
				MediaSessionCallback cb = a.getMediaSessionCallback();
				if (cb.getEngine() instanceof YoutubeMediaEngine) {
					if (pos > 0L) cb.onSeekTo(pos);
					if (pause) cb.onPause();
				}
			}, 3000L);
		});
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle state) {
		super.onSaveInstanceState(state);
		String url = getUrl();
		if (url != null) state.putString("url", url);
		WebBrowserAddon addon = getAddon();
		if (addon == null) return;
		MainActivityDelegate a = MainActivityDelegate.getActivityDelegate(getContext()).peek();
		if (a == null) return;

		SharedPreferenceStore ps = addon.getPreferenceStore();
		MediaSessionCallback cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();

		if (eng instanceof YoutubeMediaEngine) {
			state.putBoolean("pause", !cb.isPlaying());
			eng.getPosition().onSuccess(pos -> ps.applyLongPref(RESUME_POS, pos));
		} else {
			ps.removePref(RESUME_POS);
		}
	}

	/**
	 * Overrides {@code WebBrowserFragment}'s plain "just re-enter fullscreen" recovery with a
	 * two-stage one. Stage 1 is always the same safe, reversible thing the base implementation
	 * does -- re-enter fullscreen on the existing, already-loaded &lt;video&gt; element -- which
	 * covers the overwhelming majority of resumes (a routine app switch, a brief camera overlay):
	 * the video was never actually interrupted, so nothing else needs to happen. Only for an actual
	 * YouTube engine does stage 2 also run: a couple of JS health samples a moment apart, and only
	 * if those show the video is genuinely stuck (not just paused, and not just ended) does this
	 * fall through to a real page reload, matching what manually refreshing the page already does.
	 * <p>
	 * A previous version of this override reloaded unconditionally on every recovery, which meant
	 * ordinary backgrounding (switch to another Android Auto app and back) reloaded and force-paused
	 * the video every single time -- the reload is now reserved for the case it was actually meant
	 * to catch: a display takeover leaving the video frozen and unresponsive.
	 */
	@Override
	protected void recoverFullscreenVideo() {
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
			MediaSessionCallback cb = a.getMediaSessionCallback();
			MediaEngine eng = cb.getEngine();
			FermataWebView v = getWebView();
			if (v == null) return;

			a.post(() -> {
				FermataChromeClient chrome = v.getWebChromeClient();
				if (chrome != null) chrome.enterFullScreen();
			});

			if (!(eng instanceof YoutubeMediaEngine) || recovering) return;

			recovering = true;
			a.postDelayed(() -> sampleVideoState(v, s1 -> a.postDelayed(() ->
					sampleVideoState(v, s2 -> {
						recovering = false;
						if (isStuck(s1, s2)) reloadAndRecover(a, v, cb);
					}), HEALTH_PROBE_DELAY_2)), HEALTH_PROBE_DELAY_1);
		});
	}

	private void sampleVideoState(FermataWebView v, Consumer<VideoState> consumer) {
		v.evaluateJavascript(VIDEO_STATE_JS, raw -> consumer.accept(VideoState.parse(raw)));
	}

	/**
	 * A video is only ever treated as stuck -- worth the disruption of a reload -- when it claims
	 * to be playing but genuinely isn't moving, or its element has disappeared entirely. A user-
	 * paused video, a naturally-ended one, or a video this couldn't get a reading on (e.g. the
	 * WebView itself was torn down mid-probe) is always left alone: reloading those would be the
	 * exact unconditional-reload regression this replaces.
	 */
	private static boolean isStuck(@Nullable VideoState s1, @Nullable VideoState s2) {
		if (s2 == null) return false;
		if (!s2.present) return true;
		if (s2.paused || s2.ended) return false;
		if ((s1 == null) || !s1.present) return false;
		if ((s1.readyState < 2) && (s2.readyState < 2)) return true;
		return !s1.paused && !s1.ended && (Math.abs(s2.time - s1.time) < 0.05);
	}

	private void reloadAndRecover(MainActivityDelegate a, FermataWebView v, MediaSessionCallback cb) {
		MediaEngine eng = cb.getEngine();
		if (!(eng instanceof YoutubeMediaEngine)) return;
		boolean wasPlaying = wasPlayingOnPause;

		eng.getPosition().onSuccess(pos -> a.post(() -> {
			v.reload();
			a.postDelayed(() -> {
				MediaEngine e2 = cb.getEngine();
				if (!(e2 instanceof YoutubeMediaEngine)) return; // engine changed/torn down meanwhile
				if (pos > 0L) cb.onSeekTo(pos);
				// Restore whatever state playback was actually in before the takeover instead of
				// always forcing a pause -- a video that was mid-stall while playing should resume
				// playing once the reload gives it a fresh decoder pipeline, not sit there paused
				// waiting for the user to notice and tap play again.
				if (wasPlaying) cb.onPlay();
				else cb.onPause();
				FermataChromeClient chrome = v.getWebChromeClient();
				if (chrome != null) chrome.enterFullScreen();
			}, 3000L);
		}));
	}

	/** Minimal parse of {@link #VIDEO_STATE_JS}'s result -- {@code evaluateJavascript} hands back a
	 * JS value serialized as a quoted/escaped JSON string, so the outer layer of quoting has to be
	 * undone before the inner JSON (or the literal {@code null}) can be parsed. */
	private static final class VideoState {
		final boolean present;
		final double time;
		final boolean paused;
		final int readyState;
		final boolean ended;

		VideoState(boolean present, double time, boolean paused, int readyState, boolean ended) {
			this.present = present;
			this.time = time;
			this.paused = paused;
			this.readyState = readyState;
			this.ended = ended;
		}

		@Nullable
		static VideoState parse(@Nullable String raw) {
			if (raw == null) return null;
			String s = raw.trim();
			if (s.startsWith("\"") && s.endsWith("\"") && (s.length() >= 2)) {
				s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
			}
			if ("null".equals(s)) return new VideoState(false, 0, false, 0, false);

			try {
				JSONObject j = new JSONObject(s);
				return new VideoState(true, j.optDouble("t", 0), j.optBoolean("p", false),
						j.optInt("r", 0), j.optBoolean("e", false));
			} catch (JSONException ex) {
				return null;
			}
		}
	}

	@Override
	public void onDestroyView() {
		recovering = false;
		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		// The WebView (and the YoutubeMediaEngine built around it) is about to be torn down along
		// with this view -- same situation as applyPrivateModeProfile() swapping the WebView below,
		// and the same fix applies: stop first, or the media session keeps pointing at an engine
		// tied to a dead WebView (e.g. after a theme switch recreates the Activity) until a new
		// video happens to start playing. Without this, the control panel/video menu built from
		// that stale engine stays showing and its actions (e.g. "Audio effects") can end up running
		// against an already-destroyed Activity.
		MediaSessionCallback cb = a.getMediaSessionCallback();
		if (cb.getEngine() instanceof YoutubeMediaEngine) cb.onStop();
		unregisterListeners(a);
		removeVideoViewOverlay(a);
		super.onDestroyView();
	}

	@Override
	protected FermataWebView createWebView(Context ctx) {
		YoutubeWebView v = new YoutubeWebView(ctx);
		v.setId(R.id.ytWebView);
		return v;
	}

	@Override
	protected void initWebView(FermataWebView webView, WebBrowserAddon addon, View root) {
		applyProfile(webView);
		YoutubeWebView yt = (YoutubeWebView) webView;
		MainActivityDelegate a = MainActivityDelegate.get(root.getContext());
		VideoView videoView = getOrCreateVideoViewOverlay(a);
		YoutubeWebClient webClient = new YoutubeWebClient();
		YoutubeChromeClient chromeClient = new YoutubeChromeClient(yt, videoView);
		yt.init(addon, webClient, chromeClient);
	}

	@Override
	protected void applyPrivateModeProfile() {
		// Swapping the WebView drops whatever JS interface/media engine was tied to the old
		// instance -- stop playback first so the media session doesn't keep pointing at it.
		MediaSessionCallback cb = MainActivityDelegate.get(requireContext()).getMediaSessionCallback();
		if (cb.getEngine() instanceof YoutubeMediaEngine) cb.onStop();

		// Also drop out of fullscreen first -- the video-view overlay outlives the WebView swap
		// (it's reused via getOrCreateVideoViewOverlay()), so leaving it up would keep showing the
		// old instance's last frame over a WebView that no longer has any content backing it.
		FermataWebView v = getWebView();
		if (v != null) {
			FermataChromeClient chrome = v.getWebChromeClient();
			if ((chrome != null) && chrome.isFullScreen()) chrome.exitFullScreen();
		}

		super.applyPrivateModeProfile();
	}

	@Override
	protected String urlToLoadAfterProfileSwitch(FermataWebView old, WebBrowserAddon addon) {
		// A Private Mode switch should always land on a clean homepage, even mid-video (including
		// fullscreen playback) -- reloading the same video would undercut the fresh,
		// no-recommendations session Private Mode is supposed to start.
		return DEFAULT_URL;
	}

	// YouTube's fullscreen custom view used to live nested inside this fragment's own layout, so
	// showing the app's control panel (which shrinks the fragment's container to make room for it)
	// also shrank the video, letterboxing it. Host the video as a full-window overlay directly on
	// the activity's root instead, so it keeps its own size regardless of the control panel/bars.
	private VideoView getOrCreateVideoViewOverlay(MainActivityDelegate a) {
		ConstraintLayout root = a.findViewById(me.aap.fermata.R.id.main_activity);
		View existing = root.findViewWithTag(YT_VIDEO_VIEW_TAG);
		if (existing instanceof VideoView) return (VideoView) existing;

		YoutubeVideoView v = new YoutubeVideoView(root.getContext(), null);
		v.setTag(YT_VIDEO_VIEW_TAG);
		v.setVisibility(View.GONE);
		// Below control_panel/floating_button/menus (elevation 10dp) so they still show over the
		// video, but above the rest of the app chrome (toolbar/nav bar/body, elevation 0).
		v.setElevation(UiUtils.toPx(root.getContext(), 5));

		ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(0, 0);
		lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
		lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
		root.addView(v, lp);
		return v;
	}

	private void removeVideoViewOverlay(MainActivityDelegate a) {
		if (a == null) return;
		ConstraintLayout root = a.findViewById(me.aap.fermata.R.id.main_activity);
		if (root == null) return;
		View v = root.findViewWithTag(YT_VIDEO_VIEW_TAG);
		if (v == null) return;
		// Drop the chrome client hook with the view, or the fullscreen action would keep poking a
		// dead WebView instead of falling back to the app's own fullscreen once we're gone.
		if (v instanceof VideoView vv) vv.setNativeFullscreen(null);
		root.removeView(v);
	}

	@Override
	protected void registerListeners(MainActivityDelegate a) {
		super.registerListeners(a);
		a.getMediaServiceBinder().addBroadcastListener(this);
	}

	protected void unregisterListeners(MainActivityDelegate a) {
		super.unregisterListeners(a);
		a.getMediaServiceBinder().removeBroadcastListener(this);
	}

	@Override
	public void onPause() {
		if (!BuildConfig.AUTO) {
			MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
				FermataServiceUiBinder b = a.getMediaServiceBinder();
				if (YoutubeMediaEngine.isYoutubeItem(b.getCurrentItem()) && b.isPlaying()) {
					b.getMediaSessionCallback().onPause();
					playOnResume = true;
				} else {
					playOnResume = false;
				}
			});
		}
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();
		if (BuildConfig.AUTO || !playOnResume) return;
		playOnResume = false;
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
			FermataServiceUiBinder b = a.getMediaServiceBinder();
			if (YoutubeMediaEngine.isYoutubeItem(b.getCurrentItem())) {
				b.getMediaSessionCallback().onPlay();
			}
		});
	}

	public void loadUrl(String url) {
		FermataWebView v = getWebView();
		if (v != null) v.loadUrl(url);
	}

	@Override
	public void onPlayableChanged(MediaLib.PlayableItem oldItem, MediaLib.PlayableItem newItem) {
		if (isHidden()) return;

		if (YoutubeMediaEngine.isYoutubeItem(newItem)) {
			FermataWebView v = getWebView();
			MainActivityDelegate a = MainActivityDelegate.get(getContext());
			if (v == null) return;

			FermataChromeClient chrome = v.getWebChromeClient();
			if (chrome == null) return;

			if (!DEFAULT_URLS.contains(getUrl())) chrome.enterFullScreen();
		} else if (YoutubeMediaEngine.isYoutubeItem(oldItem)) {
			FermataWebView v = getWebView();
			if (v == null) return;
			FermataChromeClient chrome = v.getWebChromeClient();
			if (chrome != null) chrome.exitFullScreen();
		}
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return YoutubeToolBarMediator.getInstance();
	}

	@Override
	public boolean canScrollUp() {
		FermataWebView v = getWebView();
		if (v == null) return false;
		FermataChromeClient chrome = v.getWebChromeClient();
		return (chrome != null) && (chrome.isFullScreen() || (v.getScrollY() > 0));
	}

	@Nullable
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(YoutubeAddon.class);
	}

	@Nullable
	protected YoutubeWebView getWebView() {
		View v = getView();
		return (v != null) ? v.findViewById(R.id.ytWebView) : null;
	}

	protected boolean isDesktopVersionSupported() {
		return false;
	}

	@Nullable
	String getCurrentVideoId() {
		FermataWebView v = getWebView();
		if (v == null) return null;
		String url = v.getUrl();
		if (url == null) return null;

		Uri u = Uri.parse(url);
		if (!isYoutubeUri(u)) return null;

		String id = u.getQueryParameter("v");
		if ((id != null) && !id.isEmpty()) return id;

		String path = u.getPath();
		if (path != null && path.startsWith("/shorts/")) {
			String[] seg = path.split("/");
			if (seg.length >= 3 && !seg[2].isEmpty()) return seg[2];
		}

		return null;
	}

	@Override
	public void contributeToNavBarMenu(OverlayMenu.Builder b) {
		super.contributeToNavBarMenu(b);

		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		DefaultMediaLib lib = (DefaultMediaLib) a.getLib();
		MediaLib.Favorites favorites = lib.getFavorites();
		YoutubeVideoItem current = getCurrentVideoItem(lib);

		boolean isFav = (current != null) && current.isFavoriteItem();
		b.addItem(me.aap.fermata.R.id.favorites,
				isFav ? me.aap.fermata.R.drawable.favorite_filled : me.aap.fermata.R.drawable.favorite,
				me.aap.fermata.R.string.favorites)
				.setFutureSubmenu(sb -> favoritesMenu(sb, favorites, current));

		b.addItem(me.aap.fermata.R.id.playlists, me.aap.fermata.R.drawable.playlist,
				me.aap.fermata.R.string.playlists)
				.setFutureSubmenu(sb -> playlistsMenu(sb, lib, current));
	}

	void showFavoritesMenu() {
		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		DefaultMediaLib lib = (DefaultMediaLib) a.getLib();
		MediaLib.Favorites favorites = lib.getFavorites();
		YoutubeVideoItem current = getCurrentVideoItem(lib);
		a.getToolBarMenu().showFuture(sb -> favoritesMenu(sb, favorites, current));
	}

	void showPlaylistsMenu() {
		MainActivityDelegate a = MainActivityDelegate.get(requireContext());
		DefaultMediaLib lib = (DefaultMediaLib) a.getLib();
		YoutubeVideoItem current = getCurrentVideoItem(lib);
		a.getToolBarMenu().showFuture(sb -> playlistsMenu(sb, lib, current));
	}

	@Nullable
	private YoutubeVideoItem getCurrentVideoItem(DefaultMediaLib lib) {
		String videoId = getCurrentVideoId();
		if (videoId == null) return null;

		YoutubeAddon addon = (YoutubeAddon) getAddon();
		FermataWebView v = getWebView();
		if ((addon == null) || (v == null)) return null;

		String title = v.getTitle();
		addon.cacheVideoTitle(videoId, ((title == null) || title.isEmpty()) ? videoId : title);
		return new YoutubeVideoItem(videoId, addon.getRootItem(lib));
	}

	private FutureSupplier<Void> favoritesMenu(OverlayMenu.Builder b, MediaLib.Favorites favorites,
																							@Nullable YoutubeVideoItem current) {
		if (current != null) {
			if (current.isFavoriteItem()) {
				b.addItem(me.aap.fermata.R.id.favorites_remove, me.aap.fermata.R.drawable.favorite_filled,
						me.aap.fermata.R.string.favorites_remove).setHandler(i -> {
					favorites.removeItem(current);
					return true;
				});
			} else {
				b.addItem(me.aap.fermata.R.id.favorites_add, me.aap.fermata.R.drawable.favorite,
						me.aap.fermata.R.string.favorites_add).setHandler(i -> {
					favorites.addItem(current);
					return true;
				});
			}
		}

		return favorites.getUnsortedChildren().main().then(list -> {
			int i = 0;
			for (MediaLib.Item it : list) {
				if (it instanceof MediaLib.ExternallyPlayableItem) {
					b.addItem(UiUtils.getArrayItemId(i++), it.getName()).setData(it)
							.setHandler(item -> favoriteItemSelected(item, favorites));
				}
			}
			return completedVoid();
		});
	}

	private boolean favoriteItemSelected(OverlayMenuItem item, MediaLib.Favorites favorites) {
		MediaLib.Item it = item.getData();
		if (item.isLongClick()) {
			item.getMenu().show(sb -> sb.addItem(me.aap.fermata.R.id.favorites_remove,
					me.aap.fermata.R.string.favorites_remove).setHandler(i -> {
				favorites.removeItem((MediaLib.PlayableItem) it);
				return true;
			}));
		} else if (it instanceof MediaLib.ExternallyPlayableItem ext) {
			ext.loadInFragment(this);
		}
		return true;
	}

	private FutureSupplier<Void> playlistsMenu(OverlayMenu.Builder b, DefaultMediaLib lib,
																							@Nullable YoutubeVideoItem current) {
		if (current != null) {
			List<MediaLib.PlayableItem> selection = Collections.singletonList(current);
			MainActivityDelegate.get(requireContext()).addPlaylistMenu(b, completed(selection));
		}

		MediaLib.Playlists playlists = lib.getPlaylists();
		return playlists.getUnsortedChildren().main().then(list -> {
			int i = 0;
			for (MediaLib.Item it : list) {
				if (it instanceof MediaLib.Playlist pl) {
					b.addItem(UiUtils.getArrayItemId(i++), it.getName())
							.setFutureSubmenu(sb -> playlistItemsMenu(sb, pl));
				}
			}
			return completedVoid();
		});
	}

	private FutureSupplier<Void> playlistItemsMenu(OverlayMenu.Builder b, MediaLib.Playlist playlist) {
		return playlist.getUnsortedChildren().main().then(list -> {
			for (int i = 0; i < list.size(); i++) {
				MediaLib.Item it = list.get(i);
				if (it instanceof MediaLib.ExternallyPlayableItem) {
					int idx = i;
					b.addItem(UiUtils.getArrayItemId(i), it.getName()).setData(it)
							.setHandler(item -> playlistItemSelected(item, playlist, idx));
				}
			}
			return completedVoid();
		});
	}

	private boolean playlistItemSelected(OverlayMenuItem item, MediaLib.Playlist playlist, int idx) {
		MediaLib.Item it = item.getData();
		if (item.isLongClick()) {
			item.getMenu().show(sb -> sb.addItem(me.aap.fermata.R.id.playlist_remove_item,
					me.aap.fermata.R.string.playlist_remove_item).setHandler(i -> {
				playlist.removeItem(idx);
				return true;
			}));
		} else if (it instanceof MediaLib.ExternallyPlayableItem ext) {
			ext.loadInFragment(this);
		}
		return true;
	}

	@Override
	protected String getSearchUrl() {
		return "https://www.youtube.com/results?search_query=";
	}
}
