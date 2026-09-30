package me.aap.fermata.addon.web;

import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import me.aap.fermata.ui.activity.MainActivityPrefs;

/**
 * Private Mode is backed by a genuinely separate WebView profile (isolated cookie jar, DOM
 * storage, etc. -- see {@link Profile}) rather than a shared jar that gets cleared and
 * (best-effort) restored. Android's public {@code CookieManager} can't read or write HttpOnly
 * cookies -- exactly the kind Google/YouTube use for actual sign-in -- so a snapshot/restore
 * approach can never bring a real signed-in session back. A dedicated profile sidesteps the
 * problem entirely: the default profile's cookies are simply never touched by Private Mode in the
 * first place, so there's nothing to restore when leaving it.
 * <p>
 * Only available where {@link WebViewFeature#MULTI_PROFILE} is supported (in practice, almost any
 * device from the last few years); {@link WebBrowserAddon} falls back to a simpler, honestly
 * non-restoring shared-jar clear where it isn't.
 */
public final class PrivateProfile {
	static final String NAME = "zrAutoPrivate";

	private PrivateProfile() {}

	public static boolean isSupported() {
		return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
	}

	@NonNull
	static String nameFor(boolean privateMode) {
		return privateMode ? NAME : Profile.DEFAULT_PROFILE_NAME;
	}

	@NonNull
	public static String currentName(MainActivityPrefs mp) {
		return nameFor(mp.isPrivateModeEnabled());
	}

	@NonNull
	static Profile get(boolean privateMode) {
		return ProfileStore.getInstance().getOrCreateProfile(nameFor(privateMode));
	}

	/**
	 * Whether {@code webView} is already bound to the profile matching the current Private Mode
	 * state (nothing to do), as opposed to needing to be recreated against a different one.
	 */
	static boolean matchesCurrentProfile(WebView webView, MainActivityPrefs mp) {
		Profile p = WebViewCompat.getProfile(webView);
		return (p != null) && p.getName().equals(currentName(mp));
	}
}
