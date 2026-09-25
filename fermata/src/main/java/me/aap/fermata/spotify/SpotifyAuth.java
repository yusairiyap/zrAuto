package me.aap.fermata.spotify;

import static java.nio.charset.StandardCharsets.US_ASCII;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import me.aap.fermata.FermataApplication;

/**
 * Spotify login using the Authorization Code flow with PKCE -- the flow Spotify recommends for
 * apps that can't keep a client secret. The user registers their own (free) app on the Spotify
 * developer dashboard and enters its Client ID in Settings; no secret is ever stored.
 * <p>
 * The browser redirects back to {@link #REDIRECT_URI}, which {@code MainActivity} receives (see
 * the manifest) and hands to {@link #completeLogin}. Tokens live in their own private
 * SharedPreferences file rather than the app's preference store, so they never end up in an
 * exported preferences file.
 */
public final class SpotifyAuth {
	public static final String REDIRECT_URI = "zrauto://spotify-callback";
	private static final String CALLBACK_HOST = "spotify-callback";
	private static final String SCOPES =
			"playlist-read-private playlist-read-collaborative user-library-read";
	private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
	private static final String KEY_ACCESS = "access_token";
	private static final String KEY_REFRESH = "refresh_token";
	private static final String KEY_EXPIRES = "expires_at";
	private static final String KEY_VERIFIER = "pkce_verifier";
	private static final String KEY_STATE = "pkce_state";
	private static final String KEY_CLIENT = "client_id";

	private SpotifyAuth() {
	}

	private static SharedPreferences prefs() {
		return FermataApplication.get().getSharedPreferences("spotify_auth", Context.MODE_PRIVATE);
	}

	public static boolean isLoggedIn() {
		return prefs().getString(KEY_REFRESH, null) != null;
	}

	public static boolean isCallback(@Nullable Uri u) {
		return (u != null) && "zrauto".equals(u.getScheme()) && CALLBACK_HOST.equals(u.getHost());
	}

	/** Builds the authorize URL to open in the browser, remembering the PKCE verifier. */
	public static Uri buildAuthorizeUri(String clientId) {
		String verifier = randomString(64);
		String state = randomString(16);
		prefs().edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state)
				.putString(KEY_CLIENT, clientId).apply();

		return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
				.appendQueryParameter("client_id", clientId)
				.appendQueryParameter("response_type", "code")
				.appendQueryParameter("redirect_uri", REDIRECT_URI)
				.appendQueryParameter("code_challenge_method", "S256")
				.appendQueryParameter("code_challenge", challenge(verifier))
				.appendQueryParameter("state", state)
				.appendQueryParameter("scope", SCOPES)
				.build();
	}

	/** Exchanges the code from the redirect for tokens. Blocking. */
	public static void completeLogin(Uri callback) throws IOException {
		String error = callback.getQueryParameter("error");
		if (error != null) throw new IOException("Spotify login failed: " + error);

		SharedPreferences p = prefs();
		String code = callback.getQueryParameter("code");
		String state = callback.getQueryParameter("state");
		String verifier = p.getString(KEY_VERIFIER, null);
		String clientId = p.getString(KEY_CLIENT, null);

		if ((code == null) || (verifier == null) || (clientId == null) ||
				!String.valueOf(state).equals(p.getString(KEY_STATE, null))) {
			throw new IOException("Unexpected Spotify login response. Please try again.");
		}

		requestToken("grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" +
				enc(REDIRECT_URI) + "&client_id=" + enc(clientId) + "&code_verifier=" + enc(verifier));
		p.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply();
	}

	public static void logout() {
		prefs().edit().clear().apply();
		setLoggedInPref(false);
	}

	/** A valid access token, refreshing it if it has (nearly) expired. Blocking. */
	static synchronized String getAccessToken(boolean forceRefresh) throws IOException {
		SharedPreferences p = prefs();
		String access = p.getString(KEY_ACCESS, null);
		String refresh = p.getString(KEY_REFRESH, null);
		if (refresh == null) throw new AuthException();

		if (!forceRefresh && (access != null) &&
				(System.currentTimeMillis() < p.getLong(KEY_EXPIRES, 0) - 60000)) {
			return access;
		}

		String clientId = p.getString(KEY_CLIENT, null);
		if (clientId == null) throw new AuthException();
		requestToken("grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" +
				enc(clientId));
		access = prefs().getString(KEY_ACCESS, null);
		if (access == null) throw new AuthException();
		return access;
	}

	private static void requestToken(String form) throws IOException {
		Http.Response r = Http.postForm(TOKEN_URL, form, null);

		try {
			JSONObject json = new JSONObject(r.body);

			if (!r.isOk()) {
				String err = json.optString("error");
				if ("invalid_grant".equals(err) || "invalid_client".equals(err)) {
					logout();
					throw new AuthException();
				}
				throw new IOException("Spotify login failed: " +
						json.optString("error_description", err));
			}

			SharedPreferences.Editor e = prefs().edit();
			e.putString(KEY_ACCESS, json.getString("access_token"));
			e.putLong(KEY_EXPIRES, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000);
			// A refresh response may or may not rotate the refresh token.
			String refresh = json.optString("refresh_token");
			if (!refresh.isEmpty()) e.putString(KEY_REFRESH, refresh);
			e.apply();
			setLoggedInPref(true);
		} catch (JSONException ex) {
			throw new IOException("Unexpected Spotify response (HTTP " + r.code + ")", ex);
		}
	}

	private static void setLoggedInPref(boolean loggedIn) {
		FermataApplication.get().getHandler().post(() ->
				SpotifyPrefs.store().applyBooleanPref(SpotifyPrefs.LOGGED_IN, loggedIn));
	}

	private static String randomString(int len) {
		String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
		SecureRandom rnd = new SecureRandom();
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
		return sb.toString();
	}

	private static String challenge(String verifier) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(US_ASCII));
			return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String enc(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Not logged in, or the login was revoked/expired for good -- the user must log in again. */
	public static final class AuthException extends IOException {
		AuthException() {
			super("Not logged in to Spotify");
		}
	}
}
