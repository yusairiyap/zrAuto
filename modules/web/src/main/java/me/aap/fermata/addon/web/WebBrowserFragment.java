package me.aap.fermata.addon.web;

import static android.os.Build.*;
import static me.aap.fermata.addon.web.FermataWebClient.isYoutubeUri;
import static me.aap.fermata.util.Utils.dynCtx;

import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.webkit.WebViewCompat;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.web.yt.YoutubeFragment;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.activity.VoiceCommand;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.utils.function.BooleanConsumer;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.pref.BasicPreferenceStore;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
@Keep
@SuppressWarnings("unused")
public class WebBrowserFragment extends MainActivityFragment
		implements OverlayMenu.SelectionHandler, MainActivityListener {
	private boolean fullScreenOnResume;
	@Nullable
	private PreferenceStore.Listener privateModeListener;
	// applyPrivateModeProfile() destroys and recreates the WebView synchronously on the main thread
	// (see its own doc comment) -- fine when the user is actually looking at this fragment and
	// expects a brief overlay, but this fragment (and YoutubeFragment, which shares this class)
	// stays resident and keeps its listener registered even while hidden behind another fragment
	// (e.g. Settings), so without this guard, tapping "Clear browsing data now" there would run
	// that same expensive WebView churn invisibly in the background -- and if more than one
	// WebBrowserFragment/YoutubeFragment instance is resident at once, more than one at the same
	// time -- with no user-facing overlay to explain the resulting jank/ANR risk. Deferring it to
	// onHiddenChanged(false) means it only ever runs while this fragment is the one actually shown.
	private boolean profileSwitchPending;

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.web_browser_fragment;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		dynCtx(requireContext());
		return inflater.inflate(R.layout.browser, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		WebBrowserAddon addon = getAddon();
		if (addon == null) return;

		Context ctx = view.getContext();
		FermataWebView webView = view.findViewById(R.id.browserWebView);
		initWebView(webView, addon, view);
		webView.loadUrl(addon.getLastUrl());
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(this::registerListeners);
	}

	@Override
	public void onDestroyView() {
		MainActivityDelegate.getActivityDelegate(requireContext()).onSuccess(this::unregisterListeners);
		super.onDestroyView();
	}

	/** Creates a fresh WebView instance carrying the same id as the one declared in this
	 * fragment's layout, so it's found transparently by every existing {@code findViewById}
	 * lookup once swapped in by {@link #applyPrivateModeProfile()}. */
	protected FermataWebView createWebView(Context ctx) {
		FermataWebView v = new FermataWebView(ctx);
		v.setId(R.id.browserWebView);
		return v;
	}

	/** Binds {@code webView} to the profile matching the current Private Mode state (a no-op on
	 * WebView releases without multi-profile support) and wires up its client/chrome-client --
	 * shared between the initial creation in {@link #onViewCreated} and a later profile switch. */
	protected void initWebView(FermataWebView webView, WebBrowserAddon addon, View root) {
		applyProfile(webView);
		ViewGroup fullScreenView = root.findViewById(R.id.browserFullScreenView);
		FermataWebClient webClient = new FermataWebClient();
		FermataChromeClient chromeClient = new FermataChromeClient(webView, fullScreenView);
		webView.init(addon, webClient, chromeClient);
	}

	protected static void applyProfile(FermataWebView webView) {
		if (PrivateProfile.isSupported()) {
			WebViewCompat.setProfile(webView, PrivateProfile.currentName(MainActivityPrefs.get()));
		}
	}

	/**
	 * Recreates the WebView bound to whichever profile (Default or Private) matches the current
	 * Private Mode state, if it isn't already -- {@code WebViewCompat.setProfile()} only takes
	 * effect before a WebView is first used, so switching means swapping in a fresh instance
	 * rather than reconfiguring the live one. A brief overlay covers the swap so the reload that
	 * follows isn't a jarring blank flash. A no-op on WebView releases without multi-profile
	 * support (see {@link PrivateProfile}) -- those fall back to {@link WebBrowserAddon}'s simpler
	 * clear-only behavior on the single shared profile, which never needs a WebView swap.
	 */
	protected void applyPrivateModeProfile() {
		if (!PrivateProfile.isSupported()) return;

		View root = getView();
		FermataWebView old = getWebView();
		WebBrowserAddon addon = getAddon();
		if ((root == null) || (old == null) || (addon == null)) return;
		if (!(old.getParent() instanceof ViewGroup parent)) return;

		MainActivityPrefs mp = MainActivityPrefs.get();
		if (PrivateProfile.matchesCurrentProfile(old, mp)) return;

		String url = urlToLoadAfterProfileSwitch(old, addon);
		int index = parent.indexOfChild(old);
		ViewGroup.LayoutParams lp = old.getLayoutParams();
		Context ctx = root.getContext();

		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(a -> {
			ProfileSwitchOverlay overlay = ProfileSwitchOverlay.show(parent);
			old.stopLoading();
			parent.removeView(old);
			old.destroy();

			FermataWebView fresh = createWebView(ctx);
			initWebView(fresh, addon, root);
			parent.addView(fresh, index, lp);
			fresh.loadUrl(url);
			overlay.watch(a);
		});
	}

	/** The URL the freshly profile-switched WebView should load -- by default, whatever page was
	 * open before the switch (falling back to the last-known URL), so a plain Browser-tab toggle
	 * just continues where you were, now on the new profile. */
	protected String urlToLoadAfterProfileSwitch(FermataWebView old, WebBrowserAddon addon) {
		String url = old.getUrl();
		return (url != null) ? url : addon.getLastUrl();
	}

	@Override
	public void onRefresh(BooleanConsumer refreshing) {
		FermataWebView v = getWebView();
		if (v != null) {
			FermataWebClient c = v.getWebViewClient();
			if (c != null) {
				c.loading = refreshing;
				v.reload();
			}
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		if (!BuildConfig.AUTO) return;
		FermataWebView v = getWebView();
		if (v == null) return;
		FermataChromeClient chrome = v.getWebChromeClient();
		if (chrome != null) {
			if (chrome.isFullScreen()) {
				chrome.exitFullScreen();
				v.exitPageFullScreen(() -> {});
				fullScreenOnResume = true;
			} else {
				fullScreenOnResume = false;
			}
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		if (!BuildConfig.AUTO || !fullScreenOnResume) return;
		FermataWebView v = getWebView();
		if (v == null) return;
		// Calling here onResume makes the video to not get freezed
		// when you switch to another app and go back to Fermata
		v.onResume();
		recoverFullscreenVideo();
	}

	/**
	 * Best-effort recovery for an Android Auto display takeover (e.g. a car's camera overlay
	 * briefly taking the screen) that doesn't route through normal Activity/Fragment
	 * onPause()/onResume() -- see {@code MainCarActivity.onWindowFocusChanged}. Unlike
	 * onPause()/onResume() above, this fires only on the "we're back" signal and does the full
	 * exit+enter fullscreen rebuild in one shot, so it's a no-op if that signal never fires -- no
	 * risk of leaving the user stuck out of fullscreen from a routine, harmless focus blip.
	 * <p>
	 * Deliberately calls {@code enterFullScreen()} directly here rather than going through the
	 * overridable {@link #recoverFullscreenVideo()} hook: {@code onWindowFocusChanged}'s exact
	 * semantics under this car SDK aren't documented, this guard has no debounce, and it stays
	 * armed for as long as a video sits paused -- confirmed on-device that routing it into
	 * {@code YoutubeFragment}'s reload-based recovery broke ordinary pause/resume under Android
	 * Auto (a stray focus event during a pause could silently reload the page). Keep this trigger
	 * doing only the safe, reversible thing it was originally built for.
	 */
	public void rebuildFullscreenVideoIfActive() {
		if (!BuildConfig.AUTO) return;
		FermataWebView v = getWebView();
		if (v == null) return;
		FermataChromeClient chrome = v.getWebChromeClient();
		if ((chrome == null) || !chrome.isFullScreen()) return;
		v.onResume();
		chrome.exitFullScreen();
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> a.post(() ->
				// Re-clear the page's own fullscreen state immediately before re-entering (not just
				// once, back at the exit above) and wait for confirmation -- see
				// FermataWebView#exitPageFullScreen() for why relying on the earlier exit alone isn't
				// enough to avoid a race.
				v.exitPageFullScreen(chrome::enterFullScreen)));
	}

	/**
	 * Re-enters fullscreen after one of the two recovery paths above tore it down -- just
	 * re-entering fullscreen on the existing page/video element, not currently overridden by any
	 * subclass (a YouTube-specific reload+reseek+resume recovery was tried here previously; it was
	 * dropped after being found, on-device, to leave the WebView's fullscreen tracking stuck -- see
	 * {@code FermataWebView#exitPageFullScreen()}).
	 */
	protected void recoverFullscreenVideo() {
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> a.post(() -> {
			FermataWebView v = getWebView();
			if (v == null) return;
			FermataChromeClient chrome = v.getWebChromeClient();
			if (chrome == null) return;
			// See FermataWebView#exitPageFullScreen(): re-clear the page's own fullscreen state right
			// before asking it to re-enter, rather than relying on the earlier onPause()-time exit
			// having already taken effect -- a backgrounded WebView can suspend/queue injected JS, so
			// that exit and this enter could otherwise race in either order.
			v.exitPageFullScreen(chrome::enterFullScreen);
		}));
	}

	protected void registerListeners(MainActivityDelegate a) {
		a.addBroadcastListener(this, MainActivityListener.ACTIVITY_DESTROY);
		MainActivityPrefs.get().addBroadcastListener(privateModeListener = (store, prefs) -> {
			// WebBrowserAddon (a separate listener on the same prefs) only bumps this once the
			// relevant profile's cookies/site data have actually finished clearing -- switching the
			// WebView's profile any earlier would race that and could still load with stale data.
			if (!prefs.contains(MainActivityPrefs.PRIVATE_MODE_DATA_CLEARED_STAMP)) return;
			if (isHidden()) {
				// See profileSwitchPending's doc comment -- defer the actual (expensive) swap until
				// this fragment is shown again instead of running it now, in the background, behind
				// whatever fragment (e.g. Settings) the user is actually looking at.
				profileSwitchPending = true;
			} else {
				applyPrivateModeProfile();
			}
		});
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (!hidden && profileSwitchPending) {
			profileSwitchPending = false;
			applyPrivateModeProfile();
		}
	}

	protected void unregisterListeners(MainActivityDelegate a) {
		FermataWebView v = getWebView();
		WebBrowserAddon addon = getAddon();
		a.removeBroadcastListener(this);
		if (privateModeListener != null) {
			MainActivityPrefs.get().removeBroadcastListener(privateModeListener);
			privateModeListener = null;
		}
		if ((addon != null) && (v != null)) addon.getPreferenceStore().removeBroadcastListener(v);
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (e == ACTIVITY_DESTROY) unregisterListeners(a);
	}

	@Override
	public void setInput(Object input) {
		loadUrl(input.toString());
	}

	public void loadUrl(String url) {
		if (Uri.parse(url).getScheme() == null) {
			url = getSearchUrl() + url;
		}

		FermataWebView v = getWebView();

		if (v != null) {
			if (!(this instanceof YoutubeFragment) && isYoutubeUri(Uri.parse(url)) &&
					AddonManager.get().hasAddon(me.aap.fermata.R.id.youtube_fragment)) {
				String u = url;
				MainActivityDelegate.getActivityDelegate(requireContext()).onSuccess(a -> {
					if (a.showFragment(me.aap.fermata.R.id.youtube_fragment) instanceof YoutubeFragment f)
						f.loadUrl(u);
				});
			} else {
				v.loadUrl(url);
			}
		} else {
			WebBrowserAddon addon = AddonManager.get().getAddon(WebBrowserAddon.class);
			if (addon != null) addon.setLastUrl(url);
		}
	}

	@Nullable
	public String getUrl() {
		WebView v = getWebView();
		return (v == null) ? null : v.getUrl();
	}

	@Override
	public boolean isRootPage() {
		FermataWebView v = getWebView();
		if ((v == null) || (v.getWebChromeClient() == null)) return true;
		return !v.getWebChromeClient().isFullScreen() && !v.canGoBack();
	}

	@Override
	public boolean onBackPressed() {
		FermataWebView v = getWebView();
		if (v == null) return false;
		FermataChromeClient chrome = v.getWebChromeClient();

		if ((chrome != null) && chrome.isFullScreen()) {
			chrome.exitFullScreen();
			return true;
		}

		if (v.canGoBack()) {
			v.goBack();
			return true;
		}

		return false;
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return WebToolBarMediator.getInstance();
	}

	@Nullable
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(WebBrowserAddon.class);
	}

	@Nullable
	protected FermataWebView getWebView() {
		View v = getView();
		return (v != null) ? v.findViewById(R.id.browserWebView) : null;
	}

	@Override
	public void contributeToNavBarMenu(OverlayMenu.Builder b) {
		WebBrowserAddon a = getAddon();
		FermataWebView v = getWebView();
		if ((a == null) || (v == null)) return;

		Context ctx = dynCtx(requireContext());
		Resources res = ctx.getResources();
		Resources.Theme theme = ctx.getTheme();
		b.addItem(me.aap.fermata.R.id.refresh,
				ResourcesCompat.getDrawable(res, me.aap.fermata.R.drawable.refresh, theme),
				res.getString(me.aap.fermata.R.string.refresh)).setHandler(this);

		me.aap.fermata.ui.activity.MainActivityPrefs mp =
				me.aap.fermata.ui.activity.MainActivityPrefs.get();
		b.addItem(me.aap.fermata.R.id.private_mode,
						ResourcesCompat.getDrawable(res, me.aap.fermata.R.drawable.private_mode, theme),
						res.getString(me.aap.fermata.R.string.private_mode))
				.setChecked(mp.isPrivateModeEnabled()).setHandler(this);

		if (isDesktopVersionSupported()) {
			b.addItem(R.id.desktop_version,
							ResourcesCompat.getDrawable(res, R.drawable.desktop, theme),
							res.getString(R.string.desktop_version)).setChecked(a.isDesktopVersion())
					.setHandler(this);
		}

		FermataChromeClient chrome = v.getWebChromeClient();
		if (chrome == null) return;

		if (!chrome.isFullScreen()) {
			if (chrome.canEnterFullScreen()) {
				b.addItem(R.id.fullscreen,
						ResourcesCompat.getDrawable(res, R.drawable.fullscreen, theme),
						res.getString(R.string.full_screen)).setHandler(this);
			}
		} else {
			b.addItem(R.id.fullscreen_exit,
					ResourcesCompat.getDrawable(res, R.drawable.fullscreen_exit, theme),
					res.getString(R.string.full_screen_exit)).setHandler(this);
		}

		b.addItem(me.aap.fermata.R.id.bookmarks,
				ResourcesCompat.getDrawable(res, me.aap.fermata.R.drawable.bookmark_filled, theme),
				res.getText(me.aap.fermata.R.string.bookmarks)).setSubmenu(this::bookmarksMenu);
	}

	protected boolean isDesktopVersionSupported() {
		return true;
	}

	@Override
	public boolean menuItemSelected(OverlayMenuItem item) {
		FermataWebView v = getWebView();
		if (v == null) return false;

		int id = item.getItemId();

		if (id == me.aap.fermata.R.id.refresh) {
			v.reload();
			return true;
		} else if (id == me.aap.fermata.R.id.private_mode) {
			me.aap.fermata.ui.activity.MainActivityPrefs mp =
					me.aap.fermata.ui.activity.MainActivityPrefs.get();
			mp.setPrivateModeEnabled(!mp.isPrivateModeEnabled());
			return true;
		} else if (id == R.id.desktop_version) {
			WebBrowserAddon addon = getAddon();
			if (addon != null) addon.setDesktopVersion(!addon.isDesktopVersion());
			return true;
		} else if (id == R.id.fullscreen || id == R.id.fullscreen_exit) {
			FermataChromeClient chrome = v.getWebChromeClient();
			if (chrome == null) return false;
			if (id == R.id.fullscreen) chrome.enterFullScreen();
			else chrome.exitFullScreen();
			return true;
		}

		return false;
	}

	public void bookmarksMenu(OverlayMenu.Builder b) {
		WebBrowserAddon a = getAddon();
		if (a == null) return;

		b.addItem(me.aap.fermata.R.id.bookmark_create, me.aap.fermata.R.string.create_bookmark)
				.setSubmenu(this::createBookmark);
		int i = 0;

		for (Map.Entry<String, String> e : a.getBookmarks().entrySet()) {
			b.addItem(UiUtils.getArrayItemId(i++), e.getValue()).setData(e.getKey())
					.setHandler(this::bookmarkSelected);
		}
	}

	private void createBookmark(OverlayMenu.Builder b) {
		FermataWebView v = getWebView();
		if (v == null) return;
		PreferenceStore store = new BasicPreferenceStore();
		PreferenceStore.Pref<Supplier<String>> name = PreferenceStore.Pref.s("name", v.getTitle());
		PreferenceStore.Pref<Supplier<String>> url = PreferenceStore.Pref.s("url", v.getUrl());

		PreferenceSet set = new PreferenceSet();
		set.addStringPref(o -> {
			o.store = store;
			o.pref = name;
			o.title = me.aap.fermata.R.string.bookmark_name;
		});
		set.addStringPref(o -> {
			o.store = store;
			o.pref = url;
			o.title = R.string.url;
		});

		set.addToMenu(b, true);
		b.setCloseHandlerHandler(m -> {
			WebBrowserAddon a = getAddon();
			if (a != null) a.addBookmark(store.getStringPref(name), store.getStringPref(url));
		});
	}

	private boolean bookmarkSelected(OverlayMenuItem item) {
		if (item.isLongClick()) {
			String url = item.getData();
			item.getMenu().show(b ->
					b.addItem(me.aap.fermata.R.id.bookmark_remove, me.aap.fermata.R.string.remove_bookmark)
							.setHandler(i -> {
								WebBrowserAddon a = getAddon();
								if (a != null) a.removeBookmark(url);
								return true;
							})
			);
		} else {
			loadUrl(item.getData());
		}

		return true;
	}

	@Override
	public boolean isVoiceCommandsSupported() {
		return true;
	}

	@Override
	public void voiceCommand(VoiceCommand cmd) {
		String q = cmd.getQuery();

		if (cmd.isOpen()) {
			WebBrowserAddon a = getAddon();
			if (a != null) {
				for (Map.Entry<String, String> e : a.getBookmarks().entrySet()) {
					if (q.equalsIgnoreCase(e.getValue())) {
						loadUrl(e.getKey());
						return;
					}
				}
			}
		}

		try {
			var encoded =
					(VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) ? URLEncoder.encode(q,
							StandardCharsets.UTF_8) :
							URLEncoder.encode(q, "UTF-8");
			var u = getSearchUrl() + encoded;
			loadUrl(u);
		} catch (UnsupportedEncodingException ex) {
			Log.e(ex, "Failed to encode query ", q);
		}
	}

	protected String getSearchUrl() {
		return "https://www.google.com/search?q=";
	}
}
