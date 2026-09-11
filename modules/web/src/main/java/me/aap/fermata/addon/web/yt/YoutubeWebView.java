package me.aap.fermata.addon.web.yt;

import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_AD_ENDED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_AD_SHOWING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_CONTENT_PLAYING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_ERR;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_EVENT;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_SKIP_PREV_NEXT;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_ENDED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_FOUND;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_PAUSED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_PLAYING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_QUALITIES;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;

import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.addon.web.FermataChromeClient;
import me.aap.fermata.addon.web.FermataJsInterface;
import me.aap.fermata.addon.web.FermataWebView;
import me.aap.fermata.addon.web.WebToolBarMediator;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.text.TextUtils;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
public class YoutubeWebView extends FermataWebView {
	private static final String CLEAR_HIGHEST_VIDEO_QUALITY_JS =
			"function clearFermataQ() {\n" +
					"  if (!window.__fermataQ) return;\n" +
					"  if (window.__fermataQ.timeout) clearTimeout(window.__fermataQ.timeout);\n" +
					"  if (window.__fermataQ.player && window.__fermataQ.handler) {\n" +
					"    try { window.__fermataQ.player.removeEventListener('onStateChange', window.__fermataQ.handler); } catch(e) {}\n" +
					"  }\n" +
					"  window.__fermataQ = null;\n" +
					"}\n";
	private YoutubeJsInterface js;

	public YoutubeWebView(Context context) {
		super(context);
	}

	public YoutubeWebView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public YoutubeWebView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	protected FermataJsInterface createJsInterface() {
		MainActivityDelegate a = MainActivityDelegate.get(getContext());
		return js = new YoutubeJsInterface(this, new YoutubeMediaEngine(this, a));
	}

	@Override
	public YoutubeAddon getAddon() {
		return (YoutubeAddon) super.getAddon();
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		super.onPreferenceChanged(store, prefs);

		if (getAddon().autoHighestQualityChanged(prefs)) {
			if (getAddon().autoHighestQuality()) setHighestVideoQuality();
			else clearHighestVideoQuality();
		}

		if (YoutubeSponsorBlock.isPreferenceChanged(prefs)) injectSponsorBlock();
		if (getAddon().eqPrefsChanged(prefs)) configureEqualizer();
		if (getAddon().skipAdChanged(prefs)) attachAdObserver();
	}

	@Override
	public void loadUrl(@NonNull String url) {
		Log.d("Loading URL: " + url);
		super.loadUrl(url);
	}

	@Override
	public void goBack() {
		MediaSessionCallback cb = MainActivityDelegate.get(getContext()).getMediaSessionCallback();
		if (cb.getEngine() instanceof YoutubeMediaEngine) cb.onStop();
		super.goBack();
	}

	@Override
	protected void pageLoaded(String uri) {
		attachListeners();
		injectSponsorBlock();
		injectEqualizer();
		hideAppPromoBanners();
		attachAdObserver();
		disableAutoplay();
		addFocusHighlight();
		currentCookieManager().flush();
		refreshAddressBarTitle();
	}

	/**
	 * Shows the current video's title in the address bar instead of the raw URL (falls back to
	 * "YouTube" while no title is available yet, e.g. right after navigating home). Uses
	 * {@code document.title} rather than {@link #getTitle()}, which can lag behind on YouTube's
	 * single-page-app navigation between videos.
	 */
	void refreshAddressBarTitle() {
		getVideoTitle().onSuccess(title ->
				MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
					ActivityFragment f = a.getActiveFragment();
					if (f == null) return;
					if (!(f.getToolBarMediator() instanceof WebToolBarMediator wm)) return;
					String t = unquoteJsResult(title);
					String display = TextUtils.isNullOrBlank(t)
							? getContext().getString(me.aap.fermata.R.string.youtube) : t;
					wm.setAddress(a.getToolBar(), display);
				}));
	}

	/** {@link #evaluateJavascript} returns string results JSON-encoded (quoted, with escapes). */
	private static String unquoteJsResult(String s) {
		if ((s == null) || (s.length() < 2) || !s.startsWith("\"") || !s.endsWith("\"")) return s;
		return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
	}

	protected void submitForm() {
		if (!me.aap.fermata.BuildConfig.AUTO) return;
		loadUrl("javascript:\n" +
				"var e = new KeyboardEvent('keydown',\n" +
				"{ code: 'Enter', key: 'Enter', keyCode: 13, view: window, bubbles: true });\n" +
				"document.activeElement.dispatchEvent(e);\n" +
				"e = new KeyboardEvent('keyup',\n" +
				"{ code: 'Enter', key: 'Enter', keyCode: 13, view: window, bubbles: true });\n" +
				"document.activeElement.dispatchEvent(e);");
	}

	private void attachListeners() {
		String debug = BuildConfig.D ? JS_EVENT + "(" + JS_VIDEO_FOUND + ", null);\n" : "";
		String scale = getAddon().getScale().prefName();
		loadUrl("javascript:\n" +
				"function attachVideoListeners(v) {\n" +
				"  if (!(window.__fermataAdShowing && window.__fermataAdSkipEnabled)) v.muted = false;\n" +
				"  if (v.getAttribute('FermataAttached') === 'true') return;\n" +
				"  v.setAttribute('FermataAttached', 'true');\n" +
				"  v.style.objectFit = '" + scale + "';\n" + debug +
				"  if ((v.currentTime > 0) && !v.paused && !v.ended) {\n" +
				"    if (typeof fermataAdCheck === 'function') fermataAdCheck();\n" +
				"    if (!window.__fermataAdShowing) " + JS_EVENT + "(" + JS_CONTENT_PLAYING + ", null);\n" +
				"    " + JS_EVENT + "(" + JS_VIDEO_PLAYING + ", v.currentSrc);\n" +
				"  }\n" +
				"  v.addEventListener('playing', function(e) {\n" +
				"    if (typeof fermataAdCheck === 'function') fermataAdCheck();\n" +
				"    if (!window.__fermataAdShowing) " + JS_EVENT + "(" + JS_CONTENT_PLAYING + ", null);\n" +
				"    " + JS_EVENT + "(" + JS_VIDEO_PLAYING + ", v.currentSrc);\n" +
				"  });\n" +
				"  v.addEventListener('pause', function(e) {" + JS_EVENT + "(" + JS_VIDEO_PAUSED +
				", v.currentSrc);});\n" +
				// Deliberately NOT a plain v.addEventListener('ended', ...) here -- see the
				// document-level capture-phase listener below, which replaces it.
				"}\n" +
				"function findVideo() {\n" +
				"  var video = document.querySelectorAll('video');" +
				"  video.forEach(attachVideoListeners);\n" +
				"   setTimeout(findVideo, 1000);\n" +
				"}\n" +
				"findVideo();\n" +
				interceptEndedJs() +
				interceptNativeSkipButtonsJs());
	}

	/**
	 * YouTube's own "ended" handling (whatever picks and loads its own "up next" video once
	 * playback finishes) is wired to the SAME native {@code <video>} "ended" event the app listens
	 * for (see {@code attachVideoListeners()} above) -- and since that native handling is attached
	 * directly on the element (well before the app's own JS gets a chance to run), a plain
	 * {@code v.addEventListener('ended', ...)} here would always lose that race: YouTube's own
	 * transition is already underway by the time the app's JS-to-Java bridge round-trip gets a
	 * chance to act on Repeat One/queue-driven next (see {@code YoutubeMediaEngine#ended()}), so
	 * whatever plays next ends up being whatever YouTube's own pick was, not what the app decided.
	 * <p>
	 * The capture phase runs before the target/bubble phase regardless of listener registration
	 * order -- {@code document}-level, capture=true always sees the event first -- so intercepting
	 * it here and calling {@code stopImmediatePropagation()} prevents YouTube's own handling (and
	 * the plain listener above) from ever running at all, leaving the app's own {@code JS_VIDEO_ENDED}
	 * bridge call as the only thing that reacts to a video actually ending. Added once per page
	 * (idempotent guard) rather than per-video, since {@code document} itself doesn't get recreated
	 * between videos the way the player/video element does.
	 */
	private String interceptEndedJs() {
		return "if (!window.__fermataEndedInterceptor) {\n" +
				"  window.__fermataEndedInterceptor = true;\n" +
				"  document.addEventListener('ended', function(e) {\n" +
				"    if (e.target && (e.target.tagName === 'VIDEO')) {\n" +
				"      e.stopImmediatePropagation();\n" +
				"      " + JS_EVENT + "(" + JS_VIDEO_ENDED + ", null);\n" +
				"    }\n" +
				"  }, true);\n" +
				"}\n";
	}

	/**
	 * Same capture-phase-wins-the-race trick as {@link #interceptEndedJs()}, for a different
	 * problem: a direct tap on YouTube's own on-screen prev/next button (the desktop-style HTML5
	 * player's {@code .ytp-next-button}/{@code .ytp-prev-button}, and the mobile overlay's {@code
	 * button.player-middle-controls-prev-next-button} pair that {@link #prevNextByClick} also
	 * targets) never reaches the app's Java layer at all -- it's YouTube's own click handler, on
	 * YouTube's own button, driving YouTube's own page-internal navigation, with no app involvement
	 * to fix on the Java side no matter what {@code YoutubeMediaEngine} does. Intercepting the click
	 * in the capture phase and routing it to {@code JS_SKIP_PREV_NEXT} instead (see {@code
	 * YoutubeMediaEngine#skipRequested}) makes a tap on YouTube's own buttons behave exactly like a
	 * tap on the app's own control panel next/prev buttons -- both end up going through the app's
	 * queue-aware Favorites/Playlist navigation instead of YouTube's own pick.
	 */
	private String interceptNativeSkipButtonsJs() {
		return "if (!window.__fermataSkipInterceptor) {\n" +
				"  window.__fermataSkipInterceptor = true;\n" +
				"  document.addEventListener('click', function(e) {\n" +
				"    if (window.__fermataSyntheticClick) {\n" +
				"      window.__fermataSyntheticClick = false;\n" +
				"      return;\n" +
				"    }\n" +
				"    var t = (e.target && e.target.closest) ? e.target.closest(" +
				"'.ytp-next-button, .ytp-prev-button, " +
				"button.player-middle-controls-prev-next-button') : null;\n" +
				"    if (!t) return;\n" +
				"    var next;\n" +
				"    if (t.classList.contains('ytp-next-button')) next = true;\n" +
				"    else if (t.classList.contains('ytp-prev-button')) next = false;\n" +
				"    else {\n" +
				"      var buttons = document.querySelectorAll(" +
				"'button.player-middle-controls-prev-next-button');\n" +
				"      next = Array.prototype.indexOf.call(buttons, t) === 1;\n" +
				"    }\n" +
				"    e.stopImmediatePropagation();\n" +
				"    e.preventDefault();\n" +
				"    " + JS_EVENT + "(" + JS_SKIP_PREV_NEXT + ", next ? '1' : '0');\n" +
				"  }, true);\n" +
				"}\n";
	}

	private void injectSponsorBlock() {
		String script = YoutubeSponsorBlock.getScript(getContext(), getAddon().getPreferenceStore());
		if (!script.isEmpty()) evaluateJavascript(script, result -> configureSponsorBlock());
		else configureSponsorBlock();
	}

	private void configureSponsorBlock() {
		evaluateJavascript("if (window.FermataSponsorBlock) window.FermataSponsorBlock.configure(" +
				YoutubeSponsorBlock.getConfigJson(getAddon().getPreferenceStore()) + ");", null);
	}

	private void injectEqualizer() {
		String script = YoutubeEqualizerScript.getScript(getContext());
		if (!script.isEmpty()) evaluateJavascript(script, result -> configureEqualizer());
	}

	void configureEqualizer() {
		evaluateJavascript("if (window.FermataEqualizer) window.FermataEqualizer.configure(" +
				YoutubeEqualizerScript.getConfigJson(getAddon()) + ");", null);
	}

	private void hideAppPromoBanners() {
		loadUrl("javascript:\n" +
				"function fermataHideBanners() {\n" +
				"  ['ytm-mealbar-promo-renderer', 'ytm-app-promo-renderer', 'ytm-you-there-renderer']" +
				".forEach(function(t) {\n" +
				"    document.querySelectorAll(t).forEach(function(el) { el.style.display = 'none'; });\n" +
				"  });\n" +
				"}\n" +
				"fermataHideBanners();\n" +
				"if (!window.__fermataBannerObserver) {\n" +
				"  window.__fermataBannerObserver = new MutationObserver(fermataHideBanners);\n" +
				"  window.__fermataBannerObserver.observe(document.body, {childList: true, subtree: true});\n" +
				"}");
	}

	/**
	 * Watches for YouTube's own {@code .ad-showing} marker class and reacts to it entirely
	 * client-side (mute + seek-to-end, in the same synchronous MutationObserver callback), instead
	 * of the previous approach of reacting to it inside {@code YoutubeMediaEngine#playing()} --
	 * that only ran once a native "playing" DOM event had already round-tripped through the JS
	 * bridge to Java and back as a new {@code loadUrl()} call, which was long enough for a beat of
	 * ad audio to be audible before the skip took effect. Fires {@link
	 * me.aap.fermata.addon.web.yt.YoutubeJsInterface#JS_AD_SHOWING}/{@code JS_AD_ENDED} purely so
	 * the app can show/hide a loading overlay over the skip; the mute/seek itself never waits on Java.
	 * <p>
	 * Always (re)attaches -- the observer itself is idempotent ({@code window.__fermataAdObserver}
	 * guard) and the actual mute/skip action is separately gated by {@code
	 * window.__fermataAdSkipEnabled}, refreshed on every call -- so this doubles as the live handler
	 * for the "Try to skip advertising" preference changing while already on the page (see {@link
	 * #onPreferenceChanged}), matching {@link #injectSponsorBlock()}'s reconfigure-in-place pattern.
	 */
	private void attachAdObserver() {
		// Logged only in debug builds -- see BuildConfig.D use elsewhere in this file -- since
		// FermataChromeClient#onConsoleMessage() forwards every page console.log to logcat
		// unconditionally, and this fires on every ad boundary. Diagnostic aid for confirming
		// whether AD_SELECTOR below is still matching what the current YouTube page actually uses,
		// without needing a full rebuild to add print statements.
		String debugLog = BuildConfig.D ?
				"  console.log('Fermata ad state changed: showing=' + showing + ', bodyClass=' + " +
						"document.body.className);\n" : "";
		loadUrl("javascript:\n" +
				// Not just '.ad-showing': YouTube's ad markup/class names have shifted before and
				// aren't a documented API, so checking a few known variants (rather than just the one
				// this was originally written against) hedges a little against silently detecting
				// nothing on a page version where that particular class no longer applies. The
				// wildcard attribute selector catches any element under YouTube's own "ytp-ad-*"
				// naming convention for ad-related player UI (skip button, ad text/countdown, etc.),
				// which is more likely to survive markup changes than any single exact class name.
				"var AD_SELECTOR = '.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, " +
				"[class*=\"ytp-ad-\"]';\n" +
				// The wildcard above is broad enough that a false match (some ad-related node the
				// page keeps in the DOM, just hidden, even outside an actual ad) would be far worse
				// than a false miss -- it would mute every video permanently instead of just failing
				// to skip one ad -- so require an actual match to be genuinely rendered, not merely
				// present in the DOM.
				"function fermataIsVisible(el) {\n" +
				"  return !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);\n" +
				"}\n" +
				"function fermataAdCheck() {\n" +
				"  var showing = Array.prototype.some.call(" +
				"document.querySelectorAll(AD_SELECTOR), fermataIsVisible);\n" +
				"  var changed = showing !== window.__fermataAdShowing;\n" +
				"  window.__fermataAdShowing = showing;\n" +
				"  if (changed) {\n" + debugLog +
				"  }\n" +
				"  if (!window.__fermataAdSkipEnabled) return;\n" +
				// querySelectorAll (not just the first video element) in case the ad and the real
				// content are ever two separate <video> elements rather than one reused element.
				"  var videos = document.querySelectorAll('video');\n" +
				"  if (showing) {\n" +
				// Re-applied on every check while still showing, not just on the false->true
				// transition -- a multi-ad pod (2-3 ads back to back) can keep this marker present
				// continuously across all of them, so only reacting to the transition would mute/skip
				// the first ad and then silently let the rest of the pod play through untouched. The
				// per-video __fermataAdActive flag makes sure the original (pre-ad) muted state is
				// captured once per pod, not overwritten by our own mute on every repeat check.
				"    videos.forEach(function(v) {\n" +
				"      if (!v.__fermataAdActive) {\n" +
				"        v.__fermataAdMuted = !v.muted;\n" +
				"        v.__fermataAdActive = true;\n" +
				"      }\n" +
				"      v.muted = true;\n" +
				"      if (v.duration) v.currentTime = v.duration;\n" +
				"    });\n" +
				"    if (changed) " + JS_EVENT + "(" + JS_AD_SHOWING + ", null);\n" +
				"  } else {\n" +
				"    videos.forEach(function(v) {\n" +
				"      if (v.__fermataAdActive) {\n" +
				"        if (v.__fermataAdMuted) v.muted = false;\n" +
				"        v.__fermataAdMuted = false;\n" +
				"        v.__fermataAdActive = false;\n" +
				"      }\n" +
				"    });\n" +
				"    if (changed) " + JS_EVENT + "(" + JS_AD_ENDED + ", null);\n" +
				"  }\n" +
				"}\n" +
				"window.__fermataAdSkipEnabled = " + getAddon().skipAd() + ";\n" +
				"if (!window.__fermataAdObserver) {\n" +
				"  window.__fermataAdShowing = false;\n" +
				"  window.__fermataAdObserver = new MutationObserver(fermataAdCheck);\n" +
				"  window.__fermataAdObserver.observe(document.body, " +
				"{childList: true, subtree: true, attributes: true, attributeFilter: ['class']});\n" +
				"  fermataAdCheck();\n" +
				"}");
	}

	/**
	 * YouTube's own "Autoplay" toggle (the countdown-to-next-video overlay near the end of playback)
	 * races the app's own {@code <video>} "ended" listener (see {@code attachListeners()}, whose
	 * JS_VIDEO_ENDED ultimately drives {@code YoutubeMediaEngine#ended()} -- Repeat One and
	 * queue-driven next/prev both act there) -- and reliably wins it: YouTube's transition to
	 * whatever video it auto-picked is already underway (its own listener on the same element,
	 * attached long before ours) by the time our JS-to-Java bridge round-trip gets a chance to
	 * react, so the app's own decision arrives too late to matter and the video that actually plays
	 * next is whatever YouTube's autoplay chose, not what the app asked for. Turning Autoplay off
	 * removes YouTube's side of that race entirely, leaving the app's own end-of-video handling as
	 * the only thing that acts once a video actually ends.
	 * <p>
	 * Re-applied on every DOM mutation (MutationObserver, idempotent guard, same pattern as {@link
	 * #hideAppPromoBanners()}/{@link #attachAdObserver()}) rather than once per page load -- the
	 * player (and this toggle) is rebuilt on every video transition, not just full navigations, so a
	 * one-shot check right after the page loads could miss a toggle that resets itself to "on" once
	 * the player for the *next* video spins up.
	 * <p>
	 * {@code .ytp-autonav-toggle-button} is YouTube's own HTML5 player control (the same
	 * {@code #movie_player}/{@code .html5-video-player} embed {@link #next()}/{@link
	 * #setHighestVideoQuality()} already target elsewhere in this class, used across both the mobile
	 * and desktop-style watch pages) -- if a future YouTube markup change moves or renames it, this
	 * becomes a silent no-op rather than a crash, same as the ad-selector fallback in {@link
	 * #attachAdObserver()}; the debug log below is there to confirm whether it's still matching.
	 */
	private void disableAutoplay() {
		String debugLog = BuildConfig.D ?
				"  else if (btn) console.log('Fermata: Autoplay toggle found, checked=' + " +
						"btn.getAttribute('aria-checked'));\n" +
						"  else console.log('Fermata: Autoplay toggle not found');\n" : "";
		loadUrl("javascript:\n" +
				"function fermataDisableAutoplay() {\n" +
				"  var btn = document.querySelector('.ytp-autonav-toggle-button');\n" +
				"  if (btn && btn.getAttribute('aria-checked') === 'true') btn.click();\n" + debugLog +
				"}\n" +
				"fermataDisableAutoplay();\n" +
				"if (!window.__fermataAutoplayObserver) {\n" +
				"  window.__fermataAutoplayObserver = new MutationObserver(fermataDisableAutoplay);\n" +
				"  window.__fermataAutoplayObserver.observe(document.body, " +
				"{childList: true, subtree: true, attributes: true, attributeFilter: ['aria-checked']});\n" +
				"}");
	}

	protected boolean requestFullScreen() {
		// document.querySelector('video') can come back null for a beat right after a refocus (the
		// same player DOM churn confirmed during the window-resize investigation -- YouTube can tear
		// down and recreate the <video> element on its own). A null v here used to throw
		// (`'webkitRequestFullscreen' in v` on null) and silently abort, with no fallback: this
		// method always returns true, so FermataChromeClient#enterFullScreen() never falls back to
		// its bare-FrameLayout custom view either. The result: onShowCustomView() never fires,
		// isFullScreen() stays false, and the fullscreen FAB/toggle looks like it does nothing, with
		// nothing to retry until the page is reloaded. Poll briefly for the element instead of
		// giving up on the first miss.
		//
		// 25 attempts * 200ms = up to 5s, not the original 5 * 200ms = 1s: a plain refocus blip is
		// quick, but returning to this tab after a while on a completely different one (e.g. the
		// app's own Playlists tab) can leave the page doing considerably more catching up -- WebView
		// rendering isn't paused while merely hidden (no explicit onPause()/pauseTimers() call), but
		// Chromium can still defer/throttle a hidden page's own work, so whatever churn the original
		// 1s budget was sized for a brief blip of can plausibly take noticeably longer here.
		loadUrl("javascript:(function() {\n" +
				"  function tryFullscreen(attempt) {\n" +
				"    var v = document.querySelector('video');\n" +
				"    if (v != null) {\n" +
				"      if ('webkitRequestFullscreen' in v) v.webkitRequestFullscreen();\n" +
				"      else if ('requestFullscreen' in v) v.requestFullscreen();\n" +
				"      else " + JS_EVENT + "(" + JS_ERR + ", 'Method requestFullscreen not found in ' + v);\n" +
				"    } else if (attempt < 25) {\n" +
				"      setTimeout(function() { tryFullscreen(attempt + 1); }, 200);\n" +
				"    } else {\n" +
				"      " + JS_EVENT + "(" + JS_ERR + ", 'No video element found for requestFullscreen');\n" +
				"    }\n" +
				"  }\n" +
				"  tryFullscreen(0);\n" +
				"})();");
		return true;
	}

	@Override
	protected void exitPageFullScreen(Runnable onDone) {
		// See FermataWebView#exitPageFullScreen(): make sure document.fullscreenElement actually
		// clears when the app force-exits fullscreen, or a later requestFullscreen() call above --
		// automatic or a manual re-tap -- silently no-ops since the page still thinks it's fullscreen.
		// evaluateJavascript() (not loadUrl("javascript:...")) so onDone only runs once this has
		// actually executed, not just been queued -- the whole point of taking a callback here.
		evaluateJavascript("(function() {\n" +
				"  var fs = document.fullscreenElement || document.webkitFullscreenElement;\n" +
				"  if (!fs) return;\n" +
				"  if (document.exitFullscreen) document.exitFullscreen().catch(function() {});\n" +
				"  else if (document.webkitExitFullscreen) document.webkitExitFullscreen();\n" +
				"})();", result -> onDone.run());
	}

	void play() {
		loadUrl("javascript:(function() {\n" +
				"  var v = document.querySelector('video');\n" +
				"  if (v == null) { console.error('Fermata play(): no video element found'); return; }\n" +
				"  var p = v.play();\n" +
				"  if (p && p.catch) p.catch(function(e) { console.error('Fermata play() rejected: ' + e); });\n" +
				"})();");
	}

	void pause() {
		loadUrl("javascript:var v = document.querySelector('video'); if (v != null) v.pause();");
	}

	void stop() {
		loadUrl("javascript:var v = document.querySelector('video');\n" +
				"if (v != null) { v.currentTime = 0; v.pause(); }");
	}

	void prev() {
		prevNext(false);
	}

	void next() {
		prevNext(true);
	}

	private void prevNext(boolean next) {
		FermataChromeClient chrome = getWebChromeClient();
		if (chrome == null) return;

		// Try the page's own player API first -- it drives the SPA video swap without touching the
		// DOM controls overlay at all, so fullscreen (the app's custom view, entirely separate from
		// this WebView's own visibility) never has to be exited and re-entered around it. Read back
		// whether the call actually happened (method present and callable) via evaluateJavascript's
		// result callback, falling back to the older click-based approach -- which does need to exit
		// fullscreen first, see prevNextByClick() -- if the API isn't available on this page build.
		evaluateJavascript("""
				(function() {
				  var p = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
				  var fn = p ? p.%s : null;
				  if (typeof fn !== 'function') return false;
				  fn.call(p);
				  return true;
				})();
				""".formatted(next ? "nextVideo" : "previousVideo"),
				result -> {
					if (!"true".equals(result)) prevNextByClick(chrome, next);
				});
	}

	private void prevNextByClick(FermataChromeClient chrome, boolean next) {
		chrome.exitFullScreen().thenRun(() -> evaluateJavascript("""
				function prevNextVideo() {
				  const buttons = document.querySelectorAll('button.player-middle-controls-prev-next-button');
				  console.log('Prev/Next buttons:', buttons);
				  // Marks this as the app's own click, not a user tap -- see interceptNativeSkipButtonsJs(),
				  // which would otherwise catch this synthetic click too and redirect it right back into
				  // this same next()/prev() call, looping forever.
				  if (buttons) { window.__fermataSyntheticClick = true; buttons[%d].click(); }
				}
				setTimeout(prevNextVideo, 600);
				""".formatted(next ? 1 : 0), null));
	}

	FutureSupplier<Long> getDuration() {
		return getMilliseconds("duration");
	}

	FutureSupplier<Long> getPosition() {
		return getMilliseconds("currentTime");
	}

	FutureSupplier<String> getVideoQualities() {
		Promise<String> p = js.getResultPromise();
		loadUrl("javascript:\n" +
				"function retryGetVideoQualities(attempt, openMenu) {\n" +
				"  if (attempt < 10) setTimeout(getVideoQualities, 100, attempt + 1, openMenu);\n" +
				"  else " + JS_EVENT + '(' + JS_VIDEO_QUALITIES + ", null);\n" +
				"  return null;\n" +
				"}\n" +
				"function getVideoQualities(attempt, openMenu) {\n" +
				"  if (openMenu) {\n" +
				"    var b = document.querySelector('.player-settings-icon');\n" +
				"    if (b == null) return retryGetVideoQualities(attempt, true);\n" +
				"    b.click();\n" +
				"  }\n" +
				"  var settings = document.querySelector('.player-quality-settings');\n" +
				"  if (settings == null) return retryGetVideoQualities(attempt, false);\n" +
				"  var select = settings.querySelector('.select');\n" +
				"  if (select == null) return retryGetVideoQualities(attempt, false);\n" +
				"  var options = select.querySelectorAll('.option');\n" +
				"  var result = '';\n" +
				"  for (let i = 0; i < options.length; i++) {\n" +
				"    if (i != 0) result += ';';\n" +
				"    if (i == select.selectedIndex) result += '*';\n" +
				"    result += options[i].innerText;\n" +
				"  }\n" +
				"  " + JS_EVENT + '(' + JS_VIDEO_QUALITIES + ", result);\n" +
				"  setTimeout(()=> {settings.parentNode.parentNode.querySelector('" +
				".c3-material-button-button').click();}, 100);\n" +
				"  return result;\n" +
				"}\n" +
				"getVideoQualities(0, true);");
		return p;
	}

	void setVideoQuality(int idx) {
		loadUrl("javascript:\n" +
				"function retrySetVideoQuality(idx, attempt, openMenu) {\n" +
				"  if (attempt < 10) setTimeout(setVideoQuality, 100, idx, attempt + 1, openMenu);\n" +
				"  return false;\n" +
				"}\n" +
				"function setVideoQuality(idx, attempt, openMenu) {\n" +
				"  if (openMenu) {\n" +
				"    var b = document.querySelector('.player-settings-icon');\n" +
				"    if (b == null) return retrySetVideoQuality(idx, attempt, true);\n" +
				"    b.click();\n" +
				"  }\n" +
				"  var settings = document.querySelector('.player-quality-settings');\n" +
				"  if (settings == null) return retrySetVideoQuality(idx, attempt, false);\n" +
				"  var select = settings.querySelector('.select');\n" +
				"  if (select == null) return retrySetVideoQuality(idx, attempt, false);\n" +
				"  var options = select.querySelectorAll('.option');\n" +
				"  var evt = document.createEvent(\"HTMLEvents\");\n" +
				"  evt.initEvent(\"change\", true, true);\n" +
				"  select.selectedIndex = idx;\n" +
				"  options[idx].selected = true;\n" +
				"  select.dispatchEvent(evt);\n" +
				"  setTimeout(()=> {settings.parentNode.parentNode.querySelector('" +
				".c3-material-button-button').click();}, 100);\n" +
				"  return true;\n" +
				"}\n" +
				"setVideoQuality(" + idx + ", 0, true);");
	}

	void setHighestVideoQuality() {
		loadUrl("javascript:\n" +
				"(function() {\n" +
				CLEAR_HIGHEST_VIDEO_QUALITY_JS +
				"  clearFermataQ();\n" +
				"  var state = window.__fermataQ = { player: null, handler: null, timeout: null, attempts: 0 };\n" +
				"  function getPlayer() {\n" +
				"    return document.querySelector('#movie_player') || document.querySelector('.html5-video-player');\n" +
				"  }\n" +
				"  function applyHighest(p) {\n" +
				"    if (!p || typeof p.getAvailableQualityLevels !== 'function') return false;\n" +
				"    var levels = p.getAvailableQualityLevels();\n" +
				"    if (!levels || levels.length === 0) return false;\n" +
				"    var best = null;\n" +
				"    for (var i = 0; i < levels.length; i++) {\n" +
				"      if (levels[i] !== 'auto') { best = levels[i]; break; }\n" +
				"    }\n" +
				"    if (!best) return false;\n" +
				"    if (p.getPlaybackQuality && p.getPlaybackQuality() === best) return true;\n" +
				"    if (typeof p.setPlaybackQualityRange === 'function') p.setPlaybackQualityRange(best, best);\n" +
				"    else if (typeof p.setPlaybackQuality === 'function') p.setPlaybackQuality(best);\n" +
				"    else return false;\n" +
				"    return true;\n" +
				"  }\n" +
				"  function install() {\n" +
				"    var p = getPlayer();\n" +
				"    if (!p || typeof p.addEventListener !== 'function') {\n" +
				"      if (++state.attempts < 50) state.timeout = setTimeout(install, 200);\n" +
				"      return;\n" +
				"    }\n" +
				"    state.player = p;\n" +
				"    state.handler = function(s) {\n" +
				"      if ((s === 1) || (s === 3)) applyHighest(getPlayer() || p);\n" +
				"    };\n" +
				"    p.addEventListener('onStateChange', state.handler);\n" +
				"    applyHighest(p);\n" +
				"  }\n" +
				"  install();\n" +
				"})();");
	}

	void clearHighestVideoQuality() {
		loadUrl("javascript:\n" +
				"(function() {\n" +
				CLEAR_HIGHEST_VIDEO_QUALITY_JS +
				"  clearFermataQ();\n" +
				"})();");
	}

	private FutureSupplier<Long> getMilliseconds(String value) {
		Promise<Long> p = new Promise<>();
		evaluateJavascript(
				"(function(){var v = document.querySelector('video'); return (v != null) ? v." + value +
						" : 0})();",
				v -> {
					try {
						p.complete((long) (Double.parseDouble(v) * 1000));
					} catch (NumberFormatException ex) {
						Log.d(ex);
						p.complete(0L);
					}
				});
		return p;
	}

	void setPosition(long position) {
		double pos = position / 1000f;
		loadUrl("javascript:var v = document.querySelector('video'); if (v != null) v.currentTime = " +
				pos + ";");
	}

	FutureSupplier<Float> getSpeed() {
		Promise<Float> p = new Promise<>();
		evaluateJavascript(
				"(function(){var v = document.querySelector('video'); return (v != null) ? v" +
						".playbackRate" +
						" " +
						": 0})();",
				v -> {
					try {
						p.complete(Float.parseFloat(v));
					} catch (NumberFormatException ex) {
						Log.d(ex);
						p.complete(1f);
					}
				});
		return p;
	}

	void setSpeed(float speed) {
		loadUrl("javascript:var v = document.querySelector('video'); if (v != null) v.playbackRate =" +
				" " +
				speed + ";");
	}

	FutureSupplier<String> getVideoTitle() {
		Promise<String> p = new Promise<>();
		evaluateJavascript("document.title", p::complete);
		return p;
	}

	void setScale(YoutubeAddon.VideoScale scale) {
		getAddon().setScale(scale);
		String p = scale.prefName();
		loadUrl("javascript:" +
				"document.querySelectorAll('video')" +
				".forEach(v=> v.style.objectFit = '" + p + "');");
	}
}
