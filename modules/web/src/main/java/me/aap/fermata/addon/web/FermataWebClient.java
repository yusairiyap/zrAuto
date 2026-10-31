package me.aap.fermata.addon.web;

import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.util.Set;

import me.aap.fermata.addon.web.yt.YoutubeFragment;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.async.Completed;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.function.BooleanConsumer;
import me.aap.utils.log.Log;

/**
 * @author Andrey Pavlenko
 */
public class FermataWebClient extends WebViewClientCompat {
	// A small, well-known set of ad/analytics/tracking domains blocked while Private Mode's
	// "Block trackers & ads" setting is on. Not exhaustive -- it's meant to cut the most common
	// cross-site tracking, not to be a full ad blocker.
	private static final Set<String> TRACKER_HOSTS = Set.of(
			"doubleclick.net", "googlesyndication.com", "googleadservices.com",
			"google-analytics.com", "googletagmanager.com", "googletagservices.com",
			"adservice.google.com", "scorecardresearch.com", "facebook.net",
			"connect.facebook.net", "amazon-adsystem.com", "adnxs.com", "criteo.com",
			"outbrain.com", "taboola.com");
	BooleanConsumer loading;

	@Nullable
	@Override
	public WebResourceResponse shouldInterceptRequest(@NonNull WebView view,
																										 @NonNull WebResourceRequest request) {
		if (isTrackerBlockingEnabled() && isTrackerHost(request.getUrl().getHost())) {
			return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
		}
		return super.shouldInterceptRequest(view, request);
	}

	private static boolean isTrackerBlockingEnabled() {
		MainActivityPrefs p = MainActivityPrefs.get();
		return p.isPrivateModeEnabled() && p.getBooleanPref(MainActivityPrefs.PRIVATE_MODE_BLOCK_TRACKERS);
	}

	private static boolean isTrackerHost(@Nullable String host) {
		if (host == null) return false;
		for (String h : TRACKER_HOSTS) {
			if (host.equals(h) || host.endsWith("." + h)) return true;
		}
		return false;
	}

	@Override
	public void onPageStarted(WebView view, String url, Bitmap favicon) {
		if (loading != null) {
			loading.accept(true);
		} else {
			MainActivityDelegate.getActivityDelegate(view.getContext())
					.onSuccess(a -> a.setContentLoading(new Promise<>()));
		}
		super.onPageStarted(view, url, favicon);
	}

	@Override
	public void onPageFinished(WebView view, String url) {
		FermataWebView v = (FermataWebView) view;
		FutureSupplier<MainActivityDelegate> f =
				MainActivityDelegate.getActivityDelegate(v.getContext());
		f.onSuccess(a -> a.setContentLoading(Completed.completedVoid()));

		if (loading != null) {
			loading.accept(false);
			loading = null;
		}

		super.onPageFinished(view, url);
		((FermataWebView) view).hideKeyboard();
		v.pageLoaded(url);
		f.onSuccess(a -> a.fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED));
	}

	@Override
	public boolean shouldOverrideUrlLoading(@NonNull WebView view,
																					@NonNull WebResourceRequest request) {
		if (isYoutubeUri(request.getUrl())) {
			try {
				MainActivityDelegate a =
						MainActivityDelegate.getActivityDelegate(view.getContext()).peek();
				if (a == null) return false;
				if (!(a.showFragment(me.aap.fermata.R.id.youtube_fragment) instanceof YoutubeFragment f))
					return false;
				f.loadUrl(request.getUrl().toString());
				return true;
			} catch (IllegalArgumentException ex) {
				Log.d(ex);
			}
		}

		return false;
	}

	public static boolean isYoutubeUri(Uri uri) {
		String host = uri.getHost();
		return ((host != null) && ((host.endsWith("youtube.com") && !host.endsWith("tv.youtube.com")) ||
				host.equals("youtu.be")));
	}

	@Override
	public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request,
															@NonNull WebResourceErrorCompat error) {
		if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) {
			Log.e("Web error received: " + error.getDescription());
		} else {
			Log.e("Web error received");
		}

		// A page that cannot be reached at all (no host, no connection): check for real whether the internet is there.
		if (request.isForMainFrame()) me.aap.fermata.util.NetworkState.hintFailure();
		super.onReceivedError(view, request, error);
	}
}
