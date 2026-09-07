package me.aap.fermata.addon.web;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebViewDatabase;

import androidx.annotation.IdRes;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.webkit.Profile;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataActivityAddon;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.app.App;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.SharedPreferenceStore;
import me.aap.utils.text.TextUtils;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * @author Andrey Pavlenko
 */
@Keep
@SuppressWarnings("unused")
public class WebBrowserAddon implements FermataFragmentAddon, FermataActivityAddon,
		SharedPreferenceStore {
	@NonNull
	private static final AddonInfo info = FermataAddon.findAddonInfo(WebBrowserAddon.class.getName());
	private static final Pref<Supplier<String>> LAST_URL = Pref.s("LAST_URL", "http://google.com");
	public static final int DARK_MODE_DISABLED = 0;
	public static final int DARK_MODE_ENABLED = 1;
	public static final int DARK_MODE_AUTO = 2;
	private static final Pref<IntSupplier> DARK_MODE = Pref.i("DARK_MODE", DARK_MODE_AUTO);
	private static final Pref<Supplier<String>> USER_AGENT = Pref.s("USER_AGENT",
			"Mozilla/5.0 (Linux; Android {ANDROID_VERSION}) " +
					"AppleWebKit/{WEBKIT_VERSION} (KHTML, like Gecko) " +
					"Chrome/{CHROME_VERSION} Mobile Safari/{WEBKIT_VERSION}");
	private static final Pref<Supplier<String>> USER_AGENT_DESKTOP = Pref.s("USER_AGENT_DESKTOP",
			"Mozilla/5.0 (X11; Linux x86_64) " +
					"AppleWebKit/{WEBKIT_VERSION} (KHTML, like Gecko) " +
					"Chrome/{CHROME_VERSION} Safari/{WEBKIT_VERSION}");
	private static final Pref<BooleanSupplier> DESKTOP_VERSION = Pref.b("DESKTOP_VERSION", false);
	private static final Pref<BooleanSupplier> WEB_OPEN_ON_START = Pref.b("WEB_OPEN_ON_START", false);
	private static final Pref<Supplier<String[]>> BOOKMARKS = Pref.sa("BOOKMARKS");
	private final SharedPreferences prefs;
	private boolean ignorePrefChange;
	// EventBroadcaster keeps listeners via WeakReference (ListenerRef extends WeakReference<L>), so
	// a bare `this::onPrivateModePrefsChanged` passed straight to addBroadcastListener() has nothing
	// else holding it alive and gets garbage-collected shortly after this constructor returns --
	// silently dropping the listener with no error. Keeping a strong reference in this field is what
	// keeps it registered for the addon's whole lifetime.
	private final PreferenceStore.Listener privateModeListener = this::onPrivateModePrefsChanged;

	public WebBrowserAddon() {
		prefs = App.get().getSharedPreferences("web", Context.MODE_PRIVATE);

		// YoutubeAddon extends WebBrowserAddon, so this constructor runs once per addon subclass
		// AddonManager instantiates -- and since "Web Browser" and "YouTube" are independently
		// enabled (each has its own AddonInfo/enabledPref), a user can have YouTube on with the
		// Browser tab off, so this can NOT be skipped for subclasses the way the WebBrowserAddon-only
		// prefs in contributeSettings() are: it's the only registration a YouTube-only user gets.
		// Running it twice (once per addon instance) is a bit redundant but harmless/idempotent.

		// Registered here rather than in contributeSettings() (only wired up once the user actually
		// opens Settings) so a Private Mode toggle flipped from the toolbar or the nav-bar menu -
		// without ever visiting Settings - still clears/restores browsing data.
		MainActivityPrefs.get().addBroadcastListener(privateModeListener);
		Log.i("WebBrowserAddon: Private Mode listener registered");

		// PRIVATE_MODE_ENABLED is a persisted pref (so a session left active while "Always" is on
		// survives a restart), but Private Mode itself is meant to be a per-session thing when
		// "Always" is off: closing the app while still private shouldn't leave the next launch
		// stuck private with no normal session to go back to short of a manual toggle. This runs on
		// every cold start, before any WebView exists, so the resulting restore (via the same path a
		// manual toggle-off uses) finishes before anything actually loads a page.
		MainActivityPrefs mp = MainActivityPrefs.get();
		if (mp.isPrivateModeEnabled()) {
			if (mp.getBooleanPref(MainActivityPrefs.PRIVATE_MODE_ALWAYS)) {
				// "Always" is meant to survive a restart, but each *session* still shouldn't carry over
				// the last one's browsing -- real incognito starts every fresh window clean. Since
				// PRIVATE_MODE_ENABLED is already true here, a plain toggle wouldn't broadcast a change
				// (no value flip), so force a clear the same way the Settings "Clear now" button does,
				// before any WebView exists to load a page with the stale data.
				Log.i("Private Mode: still enabled via Always on cold start, clearing the previous " +
						"session's data");
				mp.requestPrivateDataClear();
			} else {
				Log.i("Private Mode: left enabled from a previous session but Always is off, disabling");
				mp.setPrivateModeEnabled(false);
			}
		}
	}

	private void onPrivateModePrefsChanged(PreferenceStore store, List<Pref<?>> changed) {
		if (changed.contains(MainActivityPrefs.NORMAL_MODE_CLEAR_REQUEST)) {
			// Unlike the private profile, the Default profile is exactly what CookieManager.getInstance()
			// / WebStorage.getInstance() already target -- clearSharedBrowsingData() is the right method
			// here regardless of whether multi-profile is supported.
			Log.i("Clearing normal (non-Private Mode) browsing data");
			clearSharedBrowsingData(() -> {});
		}

		if (!changed.contains(MainActivityPrefs.PRIVATE_MODE_ENABLED) &&
				!changed.contains(MainActivityPrefs.PRIVATE_MODE_CLEAR_REQUEST)) {
			return;
		}

		MainActivityPrefs mp = MainActivityPrefs.get();

		// Keep "Always use Private Mode" honest: it's meant to reflect an active choice, not linger
		// on once Private Mode has actually been turned off -- whether from the toolbar, a FAB, the
		// nav-bar menu, or the Settings toggle itself (which writes PRIVATE_MODE_ENABLED directly,
		// bypassing MainActivityPrefs.setPrivateModeEnabled(), so this broadcast reaction is the one
		// place all of those paths funnel through).
		if (changed.contains(MainActivityPrefs.PRIVATE_MODE_ENABLED) && !mp.isPrivateModeEnabled()) {
			mp.applyBooleanPref(MainActivityPrefs.PRIVATE_MODE_ALWAYS, false);
		}

		if (PrivateProfile.isSupported()) {
			// The private profile is a separate, isolated cookie jar/storage that the default
			// profile's WebViews never touch -- see PrivateProfile -- so there's nothing to restore
			// on the way out, only the private profile's own leftovers to wipe on the way in (or on a
			// manual "clear now"). WebBrowserFragment/YoutubeFragment are what actually rebind their
			// WebView to the right profile, reacting to the PRIVATE_MODE_DATA_CLEARED_STAMP this
			// bumps once that wipe has actually finished.
			Log.i("Private Mode: clearing the private profile's cookies/storage");
			clearPrivateProfileData(mp::notifyPrivateModeDataCleared);
		} else {
			// No isolated profile on this WebView version: fall back to clearing the single jar every
			// WebView in the app shares. There's deliberately no attempt to restore it afterwards --
			// Android's CookieManager can't read or write HttpOnly cookies, exactly the kind
			// Google/YouTube use for actual sign-in, so a snapshot/restore here could never bring a
			// real session back; better to be upfront that Private Mode signs you out until you sign
			// in again manually, on these older WebView installs.
			Log.i("Private Mode: multi-profile unsupported, clearing the shared cookie jar");
			clearSharedBrowsingData(mp::notifyPrivateModeDataCleared);
		}
	}

	/**
	 * Wipes cookies and site storage for the isolated Private Mode profile only, then runs
	 * {@code onDone}. {@link CookieManager#removeAllCookies} is asynchronous despite returning
	 * immediately, so anything that must only happen once cookies are actually gone (e.g. a WebView
	 * reloading a page that must come back with a clean slate) needs to wait for {@code onDone}
	 * rather than running right after calling this method.
	 */
	private void clearPrivateProfileData(Runnable onDone) {
		Profile p = PrivateProfile.get(true);
		CookieManager cm = p.getCookieManager();
		cm.removeAllCookies(cleared -> {
			cm.flush();
			p.getWebStorage().deleteAllData();
			onDone.run();
		});
	}

	/** Clears the Default profile's cookie jar/storage/form data -- i.e. the regular, non-Private
	 * Mode browsing session. Used both for the Settings "Clear browsing data" action, and as
	 * Private Mode's own fallback on WebView releases without {@link PrivateProfile#isSupported()},
	 * where the Default profile is the only one there is. */
	private void clearSharedBrowsingData(Runnable onDone) {
		CookieManager cm = CookieManager.getInstance();
		cm.removeAllCookies(cleared -> {
			cm.flush();
			WebStorage.getInstance().deleteAllData();
			try {
				WebViewDatabase.getInstance(FermataApplication.get()).clearFormData();
			} catch (Exception ex) {
				Log.e(ex, "Failed to clear WebView form data");
			}
			onDone.run();
		});
	}

	@IdRes
	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.web_browser_fragment;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new WebBrowserFragment();
	}

	/**
	 * Covers both plain web browsing and YouTube fullscreen (YoutubeFragment extends
	 * WebBrowserFragment, YoutubeAddon extends WebBrowserAddon) -- see
	 * {@link WebBrowserFragment#rebuildFullscreenVideoIfActive()}.
	 */
	@Override
	public void onActivityWindowFocusChanged(MainActivityDelegate a, boolean hasFocus) {
		if (!hasFocus) return;
		ActivityFragment f = a.getActiveFragment();
		if (f instanceof WebBrowserFragment wf) wf.rebuildFullscreenVideoIfActive();
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		getPreferenceStore().addBroadcastListener(this::onPreferenceChanged);
		FermataApplication.get().getPreferenceStore().addBroadcastListener(this::onPreferenceChanged);
		MainActivityPrefs.get().addBroadcastListener(this::onPreferenceChanged);
		set.addListPref(o -> {
			o.store = getPreferenceStore();
			o.pref = getForceDarkPref();
			o.title = R.string.force_dark;
			o.subtitle = R.string.force_dark_sub;
			o.visibility = visibility;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.force_dark_disabled, R.string.force_dark_enabled, R.string.force_dark_auto};
		});

		if (getClass() == WebBrowserAddon.class) {
			set.addBooleanPref(o -> {
				o.store = getPreferenceStore();
				o.pref = WEB_OPEN_ON_START;
				o.title = R.string.open_on_start;
				o.visibility = visibility;
			});
			set.addStringPref(o -> {
				o.store = getPreferenceStore();
				o.pref = getUserAgentPref();
				o.title = R.string.user_agent;
				o.stringHint = o.pref.getDefaultValue().get();
				o.visibility = visibility;
				o.maxLines = 3;
			});
			set.addStringPref(o -> {
				o.store = getPreferenceStore();
				o.pref = getUserAgentDesktopPref();
				o.title = R.string.user_agent_desktop;
				o.stringHint = o.pref.getDefaultValue().get();
				o.visibility = visibility;
				o.maxLines = 3;
			});
		}
	}

	public void onPreferenceChanged(PreferenceStore store, List<Pref<?>> prefs) {
		if (ignorePrefChange) return;
		ignorePrefChange = true;

		if (prefs.contains(getInfo().enabledPref)) {
			if (!store.getBooleanPref(getInfo().enabledPref)) {
				MainActivityPrefs ap = MainActivityPrefs.get();
				getPreferenceStore().applyBooleanPref(WEB_OPEN_ON_START, false);
				if (getInfo().className.equals(ap.getShowAddonOnStartPref()))
					ap.setShowAddonOnStartPref(null);
			}
		} else if (prefs.contains(WEB_OPEN_ON_START)) {
			MainActivityPrefs ap = MainActivityPrefs.get();
			if (store.getBooleanPref(WEB_OPEN_ON_START)) {
				ap.setShowAddonOnStartPref(getInfo().className);
			} else if (getInfo().className.equals(ap.getShowAddonOnStartPref())) {
				ap.setShowAddonOnStartPref(null);
			}
		} else if (prefs.contains(MainActivityPrefs.SHOW_ADDON_ON_START)) {
			getPreferenceStore().applyBooleanPref(WEB_OPEN_ON_START,
					getInfo().className.equals(MainActivityPrefs.get().getShowAddonOnStartPref()));
		}
		ignorePrefChange = false;
	}
	public SharedPreferenceStore getPreferenceStore() {
		return this;
	}

	private Collection<ListenerRef<Listener>> listeners;

	@NonNull
	@Override
	public SharedPreferences getSharedPreferences() {
		return prefs;
	}

	@Override
	public Collection<ListenerRef<Listener>> getBroadcastEventListeners() {
		return (listeners != null) ? listeners : (listeners = new LinkedList<>());
	}

	public Pref<IntSupplier> getForceDarkPref() {
		return DARK_MODE;
	}

	public Pref<Supplier<String>> getUserAgentPref() {
		return USER_AGENT;
	}

	public Pref<Supplier<String>> getUserAgentDesktopPref() {
		return USER_AGENT_DESKTOP;
	}

	public String getUserAgentDesktop() {
		Pref<Supplier<String>> p = getUserAgentDesktopPref();
		String ua = getPreferenceStore().getStringPref(p);
		return TextUtils.isNullOrBlank(ua) ? p.getDefaultValue().get() : ua;
	}

	public String getUserAgent() {
		Pref<Supplier<String>> p = getUserAgentPref();
		String ua = getPreferenceStore().getStringPref(p);
		return TextUtils.isNullOrBlank(ua) ? p.getDefaultValue().get() : ua;
	}

	public boolean isDisableDark() {
		return getPreferenceStore().getIntPref(getForceDarkPref()) == 0;
	}

	public boolean isForceDark() {
		return getPreferenceStore().getIntPref(getForceDarkPref()) == 1;
	}

	public boolean isAutoDark() {
		return getPreferenceStore().getIntPref(getForceDarkPref()) == 2;
	}

	public Pref<BooleanSupplier> getDesktopVersionPref() {
		return DESKTOP_VERSION;
	}

	public Pref<Supplier<String[]>> getBookmarksPref() {
		return BOOKMARKS;
	}

	public boolean isDesktopVersion() {
		return getPreferenceStore().getBooleanPref(getDesktopVersionPref());
	}

	public void setDesktopVersion(boolean v) {
		getPreferenceStore().applyBooleanPref(DESKTOP_VERSION, v);
	}

	Map<String, String> getBookmarks() {
		String[] p = getPreferenceStore().getStringArrayPref(getBookmarksPref());
		if (p.length == 0) return Collections.emptyMap();

		Map<String, String> m = new LinkedHashMap<>(p.length);
		for (int i = 0; i < p.length; i++) {
			m.put(p[i], p[++i]);
		}
		return m;
	}

	void addBookmark(String name, String url) {
		Map<String, String> m = getBookmarks();

		if (m.isEmpty()) {
			setBookmarks(Collections.singletonMap(url, name));
		} else {
			m.put(url, name);
			setBookmarks(m);
		}
	}

	void removeBookmark(String url) {
		Map<String, String> m = getBookmarks();

		if (!m.isEmpty()) {
			m.remove(url);
			setBookmarks(m);
		}
	}

	void setBookmarks(Map<String, String> m) {
		String[] p = new String[m.size() * 2];
		int i = 0;

		for (Map.Entry<String, String> e : m.entrySet()) {
			p[i++] = e.getKey();
			p[i++] = e.getValue();
		}

		getPreferenceStore().applyStringArrayPref(getBookmarksPref(), p);
	}

	String getLastUrl() {
		return getPreferenceStore().getStringPref(LAST_URL);
	}

	void setLastUrl(String url) {
		getPreferenceStore().applyStringPref(LAST_URL, url);
	}
}
