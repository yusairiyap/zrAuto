package me.aap.fermata.spotify;

import me.aap.fermata.FermataApplication;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Settings of the Spotify import, kept in the app-wide preference store (Settings > Other >
 * Import from Spotify). The login tokens themselves are not here -- see {@link SpotifyAuth}.
 */
public final class SpotifyPrefs {
	/** Read playlists through the official Web API, signed in with the user's own account. */
	public static final int SOURCE_ACCOUNT = 0;
	/** Read public playlists from Spotify's embed page, no login. */
	public static final int SOURCE_PUBLIC = 1;

	public static final Pref<IntSupplier> SOURCE = Pref.i("SPOTIFY_IMPORT_SOURCE", SOURCE_ACCOUNT);
	public static final Pref<Supplier<String>> CLIENT_ID = Pref.s("SPOTIFY_CLIENT_ID", "");
	/**
	 * Mirrors whether {@link SpotifyAuth} holds a token, so Settings can show Log in or Log out.
	 */
	public static final Pref<BooleanSupplier> LOGGED_IN = Pref.b("SPOTIFY_LOGGED_IN", false);

	private SpotifyPrefs() {
	}

	public static PreferenceStore store() {
		return FermataApplication.get().getPreferenceStore();
	}

	public static boolean isAccountSource() {
		return store().getIntPref(SOURCE) == SOURCE_ACCOUNT;
	}

	public static String getClientId() {
		String id = store().getStringPref(CLIENT_ID);
		return (id == null) ? "" : id.trim();
	}
}
