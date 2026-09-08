package me.aap.fermata.addon.web.yt;

import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_AD_ENDED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_AD_SHOWING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_CONTENT_PLAYING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_ERR;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_EVENT;
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
				"  v.addEventListener('ended', function(e) {" + JS_EVENT + "(" + JS_VIDEO_ENDED +
				", null);});\n" +
				"}\n" +
				"function findVideo() {\n" +
				"  var video = document.querySelectorAll('video');" +
				"  video.forEach(attachVideoListeners);\n" +
				"   setTimeout(findVideo, 1000);\n" +
				"}\n" +
				"findVideo();");
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
				// nothing on a page version where that particular class no longer applies.
				"var AD_SELECTOR = '.ad-showing, .ad-interrupting, .ytp-ad-player-overlay';\n" +
				"function fermataAdCheck() {\n" +
				"  var showing = document.querySelector(AD_SELECTOR) != null;\n" +
				"  if (showing === window.__fermataAdShowing) return;\n" +
				"  window.__fermataAdShowing = showing;\n" + debugLog +
				"  if (!window.__fermataAdSkipEnabled) return;\n" +
				// querySelectorAll (not just the first video element) in case the ad and the real
				// content are ever two separate <video> elements rather than one reused element.
				"  var videos = document.querySelectorAll('video');\n" +
				"  if (showing) {\n" +
				"    videos.forEach(function(v) {\n" +
				"      v.__fermataAdMuted = !v.muted;\n" +
				"      v.muted = true;\n" +
				"      if (v.duration) v.currentTime = v.duration;\n" +
				"    });\n" +
				"    " + JS_EVENT + "(" + JS_AD_SHOWING + ", null);\n" +
				"  } else {\n" +
				"    videos.forEach(function(v) {\n" +
				"      if (v.__fermataAdMuted) v.muted = false;\n" +
				"      v.__fermataAdMuted = false;\n" +
				"    });\n" +
				"    " + JS_EVENT + "(" + JS_AD_ENDED + ", null);\n" +
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
		loadUrl("javascript:(function() {\n" +
				"  function tryFullscreen(attempt) {\n" +
				"    var v = document.querySelector('video');\n" +
				"    if (v != null) {\n" +
				"      if ('webkitRequestFullscreen' in v) v.webkitRequestFullscreen();\n" +
				"      else if ('requestFullscreen' in v) v.requestFullscreen();\n" +
				"      else " + JS_EVENT + "(" + JS_ERR + ", 'Method requestFullscreen not found in ' + v);\n" +
				"    } else if (attempt < 5) {\n" +
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
				  if (buttons) buttons[%d].click();
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
