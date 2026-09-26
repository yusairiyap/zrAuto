package me.aap.fermata.addon.web.yt;

import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_AD_ENDED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_AD_SHOWING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_CONTENT_PLAYING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_ERR;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_EVENT;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_SKIP_PREV_NEXT;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_USER_PICKED_VIDEO;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_ENDED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_ENDING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_FOUND;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_PAUSED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_PLAYING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_QUALITIES;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.addon.music.MusicPlayer;
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
	// Setting the player's quality through its API can also be stored by YouTube as the viewer's own
	// preferred quality (localStorage 'yt-player-quality'), which would then stick after music mode
	// (lowest quality) ends. So the viewer's value is saved (under our own key, which survives page
	// loads and app restarts) before going lowest, and put back when leaving music mode;
	// fermataRestoreUserQ() returns the quality level to go back to, or null if nothing was saved.
	private static final String USER_QUALITY_JS =
			"function fermataSaveUserQ() {\n" +
					"  try {\n" +
					"    if (localStorage.getItem('fermataUserQ') === null)\n" +
					"      localStorage.setItem('fermataUserQ', JSON.stringify({v: localStorage.getItem('yt-player-quality')}));\n" +
					"  } catch (e) {}\n" +
					"}\n" +
					"function fermataRestoreUserQ() {\n" +
					"  var level = 'auto';\n" +
					"  try {\n" +
					"    var s = localStorage.getItem('fermataUserQ');\n" +
					"    if (s === null) return null;\n" +
					"    localStorage.removeItem('fermataUserQ');\n" +
					"    var raw = JSON.parse(s).v;\n" +
					"    if (raw === null) localStorage.removeItem('yt-player-quality');\n" +
					"    else localStorage.setItem('yt-player-quality', raw);\n" +
					"    var q = JSON.parse(JSON.parse(raw).data).quality;\n" +
					"    level = {144: 'tiny', 240: 'small', 360: 'medium', 480: 'large', 720: 'hd720',\n" +
					"      1080: 'hd1080', 1440: 'hd1440', 2160: 'hd2160', 4320: 'highres'}[q] || 'auto';\n" +
					"  } catch (e) {}\n" +
					"  return level;\n" +
					"}\n" +
					"function fermataSetQ(level) {\n" +
					"  var p = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');\n" +
					"  try { if (p && p.setPlaybackQualityRange) p.setPlaybackQualityRange(level, level); } catch (e) {}\n" +
					"}\n";

	private static final String CLEAR_HIGHEST_VIDEO_QUALITY_JS =
			"function clearFermataQ() {\n" +
					"  if (!window.__fermataQ) return;\n" +
					"  if (window.__fermataQ.timeout) clearTimeout(window.__fermataQ.timeout);\n" +
					"  if (window.__fermataQ.player && window.__fermataQ.handler) {\n" +
					"    try { window.__fermataQ.player.removeEventListener('onStateChange', window.__fermataQ.handler); } catch(e) {}\n" +
					"  }\n" +
					"  window.__fermataQ = null;\n" +
					"}\n";
	/**
	 * How long {@link #navigateToVideoJs} waits for YouTube's own router (or the browser) to act on
	 * the injected link click before falling back to swapping the video inside the existing player.
	 * Long enough that a router that merely takes a moment isn't raced into a double navigation,
	 * short enough to stay under the transition cover that is up over all of this anyway (see
	 * {@code YoutubeVideoView#showTransitionOverlay}).
	 */
	private static final int NAVIGATION_FALLBACK_MS = 1000;
	/**
	 * How long an explicit next/prev/queue switch lets the current video's audio fade out (see
	 * {@code youtube_fade.js}) before actually navigating -- see {@link #afterAudioFadeOut}.
	 */
	static final int SWITCH_FADE_OUT_MS = 300;
	/** Shared lookup of the element every playback helper below acts on. */
	private static final String JS_FIND_VIDEO = "var v = document.querySelector('video');\n";
	private YoutubeJsInterface js;
	/** See {@link #afterAudioFadeOut}. */
	@Nullable
	private Runnable pendingSwitch;
	private int switchGeneration;

	public YoutubeWebView(Context context) {
		super(context);
		paintUnrenderedAreaBlack();
	}

	public YoutubeWebView(Context context, AttributeSet attrs) {
		super(context, attrs);
		paintUnrenderedAreaBlack();
	}

	public YoutubeWebView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		paintUnrenderedAreaBlack();
	}

	/**
	 * A WebView paints white wherever the page hasn't rendered yet, and that white is what shows
	 * through the fullscreen enter/exit crossfade (see {@code FermataChromeClient#crossfade}) and
	 * every moment a new watch page is still loading -- the "white flash" between videos. YouTube's
	 * own page is dark here anyway (see the Dark mode preference, default auto), so there is nothing
	 * to lose by making the gap dissolve through black instead.
	 */
	private void paintUnrenderedAreaBlack() {
		setBackgroundColor(Color.BLACK);
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

		// While playing as music (the Music tab) the quality stays at its lowest regardless.
		if (getAddon().autoHighestQualityChanged(prefs) && !MusicPlayer.isYoutubeAudioMode()) {
			if (getAddon().autoHighestQuality()) applyQualityPolicy(false);
			else clearQualityPolicy();
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
		injectFade();
		attachListeners();
		injectSponsorBlock();
		injectEqualizer();
		hideAppPromoBanners();
		attachAdObserver();
		disableAutoplay();
		disableVideoPreviews();
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
		getVideoTitle().onSuccess(this::showTitleInAddressBar);
	}

	/**
	 * Same as {@link #refreshAddressBarTitle()} but for a title the caller already has, rather than
	 * one read back out of the page. Called from {@link YoutubeMediaEngine#playing} with the title
	 * the player itself reported: YouTube's single-page-app navigation between videos (the app's own
	 * queue-driven next/prev, Repeat One, and YouTube's own autonav alike) never triggers a page
	 * load, so {@link #pageLoaded(String)} -- the only other thing that refreshes this -- simply
	 * never fires for those, leaving the toolbar showing whichever video's title happened to be up
	 * when the page was last actually loaded.
	 */
	void showTitleInAddressBar(@Nullable String title) {
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> {
			ActivityFragment f = a.getActiveFragment();
			if (f == null) return;
			if (!(f.getToolBarMediator() instanceof WebToolBarMediator wm)) return;
			String display = TextUtils.isNullOrBlank(title)
					? getContext().getString(me.aap.fermata.R.string.youtube) : title;
			wm.setAddress(a.getToolBar(), display);
		});
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
				// The WebView's own reported document URL (what YoutubeMediaEngine#playing() used to
				// derive the current video id from) lags behind player.loadVideoById()'s SPA-internal video
				// swap -- YouTube updates the address bar via the History API only once it's fetched the
				// new video's metadata, well after the <video> element itself has already switched sources
				// and fired 'playing'. Reading the id straight from the player object instead (what it's
				// actually playing, right now) is instantaneous, so the id is prefixed onto every
				// JS_VIDEO_PLAYING payload here instead. See YoutubeMediaEngine#playing()'s parsing of it.
				"function fermataCurrentVideoId() {\n" +
				"  try {\n" +
				"    var p = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');\n" +
				"    var d = p && p.getVideoData ? p.getVideoData() : null;\n" +
				"    return (d && d.video_id) ? d.video_id : '';\n" +
				"  } catch (e) { return ''; }\n" +
				"}\n" +
				// See interceptLinkClicksJs() below for what sets __fermataLastLinkClickTime. A video
				// change that follows a real link tap within this window is the user browsing to a
				// different video on purpose -- as opposed to YouTube's own autonav, which never involves a
				// click at all -- see YoutubeMediaEngine#playing()'s use of this flag.
				// The video id a watch/shorts URL points to, or '' -- see fermataUserPicked() below.
				"function fermataVideoIdFromUrl(u) {\n" +
				"  try {\n" +
				"    var url = new URL(u, location.href);\n" +
				"    if (url.pathname === '/watch') return url.searchParams.get('v') || '';\n" +
				"    var m = url.pathname.match(/^\\/(shorts|live)\\/([A-Za-z0-9_-]+)/);\n" +
				"    return m ? m[2] : '';\n" +
				"  } catch (e) { return ''; }\n" +
				"}\n" +
				// Tells the app, right away, which video the user just tapped. The 4s in-page window of
				// fermataRecentLinkClick() is too short for a tap that is followed by an ad or a slow
				// load, and it doesn't survive a full document load at all -- in both cases the new
				// video used to be mistaken for YouTube's own autonav and replaced with the next
				// Favorites/Playlist item. See YoutubeMediaEngine#userPickedVideo().
				"function fermataUserPicked(u) {\n" +
				"  var id = fermataVideoIdFromUrl(u);\n" +
				"  if (id) " + JS_EVENT + "(" + JS_USER_PICKED_VIDEO + ", id);\n" +
				"}\n" +
				"function fermataRecentLinkClick() {\n" +
				"  return (Date.now() - (window.__fermataLastLinkClickTime || 0)) < 4000;\n" +
				"}\n" +
				// Same reasoning as fermataCurrentVideoId() above, for the title: document.title (what
				// refreshAddressBarTitle()/YoutubeMediaEngine's metadata used to be built from) only
				// catches up with an SPA-internal video swap once YouTube has fetched the new video's
				// metadata and updated the document -- long after 'playing' fires -- so reading it at
				// that moment yields the PREVIOUS video's title, which is then never corrected (nothing
				// re-reads it afterwards). The player object knows the real title immediately.
				// URI-encoded so a title containing '|' can't corrupt the payload's field separators --
				// encodeURIComponent escapes it as %7C. See YoutubeMediaEngine#playing()'s parsing.
				"function fermataCurrentVideoTitle() {\n" +
				"  try {\n" +
				"    var p = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');\n" +
				"    var d = p && p.getVideoData ? p.getVideoData() : null;\n" +
				"    return (d && d.title) ? encodeURIComponent(d.title) : '';\n" +
				"  } catch (e) { return ''; }\n" +
				"}\n" +
				// The channel name, the same way -- the Music tab shows it as the artist.
				"function fermataCurrentVideoAuthor() {\n" +
				"  try {\n" +
				"    var p = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');\n" +
				"    var d = p && p.getVideoData ? p.getVideoData() : null;\n" +
				"    return (d && d.author) ? encodeURIComponent(d.author) : '';\n" +
				"  } catch (e) { return ''; }\n" +
				"}\n" +
				"function attachVideoListeners(v) {\n" +
				"  if (!(window.__fermataAdShowing && window.__fermataAdSkipEnabled)) v.muted = false;\n" +
				"  if (v.getAttribute('FermataAttached') === 'true') return;\n" +
				"  v.setAttribute('FermataAttached', 'true');\n" +
				"  v.style.objectFit = '" + scale + "';\n" + debug +
				"  if ((v.currentTime > 0) && !v.paused && !v.ended) {\n" +
				"    if (typeof fermataAdCheck === 'function') fermataAdCheck();\n" +
				"    if (!window.__fermataAdShowing) " + JS_EVENT + "(" + JS_CONTENT_PLAYING + ", null);\n" +
				"    " + JS_EVENT + "(" + JS_VIDEO_PLAYING + ", fermataCurrentVideoId() + '|' + " +
				"(fermataRecentLinkClick() ? '1' : '0') + '|' + fermataCurrentVideoTitle() + '|' + " +
				"fermataCurrentVideoAuthor() + '|' + v.currentSrc);\n" +
				"  }\n" +
				"  v.addEventListener('playing', function(e) {\n" +
				"    if (typeof fermataAdCheck === 'function') fermataAdCheck();\n" +
				"    if (!window.__fermataAdShowing) " + JS_EVENT + "(" + JS_CONTENT_PLAYING + ", null);\n" +
				"    " + JS_EVENT + "(" + JS_VIDEO_PLAYING + ", fermataCurrentVideoId() + '|' + " +
				"(fermataRecentLinkClick() ? '1' : '0') + '|' + fermataCurrentVideoTitle() + '|' + " +
				"fermataCurrentVideoAuthor() + '|' + v.currentSrc);\n" +
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
				interceptNativeSkipButtonsJs() +
				interceptLinkClicksJs() +
				interceptUserNavigationJs());
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

	/**
	 * Records the time of the last click on a link ({@code <a>}, or something inside one -- every
	 * "tap another video" gesture on a YouTube page, thumbnail/title/related-video-card alike, is a
	 * link to another watch page; player controls like play/pause, seek and volume are not) so
	 * {@code attachVideoListeners()}'s {@code JS_VIDEO_PLAYING} payload can tell
	 * {@link me.aap.fermata.addon.web.yt.YoutubeMediaEngine#playing} whether a video change was a
	 * real user tap rather than YouTube's own autonav. Passive -- doesn't touch propagation/default
	 * at all -- so it can't interfere with {@link #interceptNativeSkipButtonsJs()} or anything else
	 * that also listens for the same click.
	 */
	private String interceptLinkClicksJs() {
		return "if (!window.__fermataClickTracker) {\n" +
				"  window.__fermataClickTracker = true;\n" +
				"  document.addEventListener('click', function(e) {\n" +
				// The app's own queue-driven navigation clicks a link it injected itself (see
				// navigateToVideoJs()) -- recording that as a user tap would make
				// YoutubeMediaEngine#playing() mistake the app's own queue move for the user
				// deliberately picking a different video and drop the queue on the spot.
				"    if (window.__fermataSuppressLinkClick) return;\n" +
				"    var a = (e.target && e.target.closest) ? e.target.closest('a') : null;\n" +
				"    if (a) {\n" +
				"      window.__fermataLastLinkClickTime = Date.now();\n" +
				"      if (a.href) fermataUserPicked(a.href);\n" +
				"    }\n" +
				"  }, true);\n" +
				"}\n";
	}

	/**
	 * Belt-and-braces companion to {@link #interceptLinkClicksJs()}: that listener only recognizes
	 * a tap on a real {@code <a>} element (or something inside one), which is how the watch page's
	 * classic related-video sidebar list navigates -- but not how YouTube's newer non-anchor video
	 * tiles do (e.g. the "lockup" grid renderers used on the home feed and search-results pages,
	 * reached via {@link #loadUrl}-driven navigation from this app's own "home"/search toolbar
	 * buttons rather than a tap within the page): those drive navigation entirely through their own
	 * click handler and the History API, with no {@code <a>} anywhere in the DOM, so a tap there
	 * never sets {@code __fermataLastLinkClickTime} and {@code YoutubeMediaEngine#playing()}'s
	 * "unexpected transition" handling ends up mistaking the tapped video for YouTube's own autonav,
	 * pulling a still-active Favorites/Playlist queue's own next item back in instead of what was
	 * actually tapped.
	 * <p>
	 * The standard Navigation API's {@code userInitiated} flag sidesteps guessing at YouTube's
	 * ever-changing tile markup entirely: it's true for any navigation the browser itself attributes
	 * to a real user gesture (a click anywhere, a form submit, back/forward), false for anything
	 * programmatic such as YouTube's own autonav -- exactly the distinction {@code
	 * fermataRecentLinkClick()} needs, from the browser instead of inferred from a selector.
	 * Feature-detected and purely additive: on a WebView build without the Navigation API this is a
	 * no-op and the click listener above is all there is.
	 */
	private String interceptUserNavigationJs() {
		return "if (!window.__fermataNavInterceptor && window.navigation && " +
				"typeof navigation.addEventListener === 'function') {\n" +
				"  window.__fermataNavInterceptor = true;\n" +
				"  navigation.addEventListener('navigate', function(e) {\n" +
				// Same exclusion as interceptLinkClicksJs() above -- see there.
				"    if (window.__fermataSuppressLinkClick) return;\n" +
				"    if (e.userInitiated) {\n" +
				"      window.__fermataLastLinkClickTime = Date.now();\n" +
				"      if (e.destination && e.destination.url) fermataUserPicked(e.destination.url);\n" +
				"    }\n" +
				"  });\n" +
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

	private void injectFade() {
		String script = YoutubeFadeScript.getScript(getContext());
		if (script.isEmpty()) return;
		evaluateJavascript(script, result -> evaluateJavascript(
				"if (window.FermataFade) window.FermataFade.configure({endingEvent: " + JS_VIDEO_ENDING +
						"});", null));
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
	 * #applyQualityPolicy(boolean)} already target elsewhere in this class, used across both the mobile
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

	/**
	 * Independent of (and more reliable than) the "Video previews" toggle in YouTube's own General
	 * settings (Home/Search feed rows silently autoplaying a muted preview clip as you scroll):
	 * that account-level preference doesn't survive being turned off (it isn't tied to this device
	 * or session, and toggling it back off after it re-enables itself is a known YouTube-side
	 * annoyance, not something this app's settings can reach). A feed preview video is also what was
	 * behind unwanted jumps straight into fullscreen playback -- {@link #requestFullScreen()} and
	 * the fullscreen-entry FAB both locate the "current" video with a bare
	 * {@code document.querySelector('video')}, which happily returns a feed preview's element if one
	 * happens to be playing, so killing every preview before that lookup ever runs removes the
	 * ambiguity rather than trying to special-case it there.
	 * <p>
	 * Real playback only ever happens on the watch/shorts pages, so anything under {@code <video>}
	 * found anywhere else is necessarily a feed preview: paused, muted and hidden immediately rather
	 * than matched against a preview-specific selector, since YouTube's own preview container
	 * class/element names are unstable across app versions (same reasoning as {@link
	 * #disableAutoplay()}'s comment on {@code .ytp-autonav-toggle-button}). Re-applied via
	 * MutationObserver (idempotent guard, same pattern as {@link #disableAutoplay()}) since feed
	 * rows mount/unmount their preview elements continuously while scrolling, not just once per page
	 * load.
	 */
	private void disableVideoPreviews() {
		loadUrl("javascript:\n" +
				"function fermataKillPreviewVideo(v) {\n" +
				"  var p = location.pathname;\n" +
				"  if (p.indexOf('/watch') === 0 || p.indexOf('/shorts') === 0) return;\n" +
				"  try {\n" +
				"    v.pause();\n" +
				"    v.muted = true;\n" +
				"    v.removeAttribute('autoplay');\n" +
				"    v.removeAttribute('src');\n" +
				"    v.style.display = 'none';\n" +
				"  } catch (e) {}\n" +
				"}\n" +
				"function fermataScanForPreviewVideos() {\n" +
				"  document.querySelectorAll('video').forEach(fermataKillPreviewVideo);\n" +
				"}\n" +
				"fermataScanForPreviewVideos();\n" +
				"if (!window.__fermataPreviewObserver) {\n" +
				"  window.__fermataPreviewObserver = new MutationObserver(fermataScanForPreviewVideos);\n" +
				"  window.__fermataPreviewObserver.observe(document.body, " +
				"{childList: true, subtree: true});\n" +
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
				"  " + JS_FIND_VIDEO +
				"  if (v == null) { console.error('Fermata play(): no video element found'); return; }\n" +
				"  if (window.FermataFade) window.FermataFade.prepareToPlay(v);\n" +
				"  var p = v.play();\n" +
				"  if (p && p.catch) p.catch(function(e) { console.error('Fermata play() rejected: ' + e); });\n" +
				"})();");
	}

	/** Fades the audio out first (see {@code youtube_fade.js}), then pauses. */
	void pause() {
		loadUrl("javascript:(function() {\n" +
				"  " + JS_FIND_VIDEO +
				"  if (v == null) return;\n" +
				"  if (window.FermataFade) window.FermataFade.pause(v, false); else v.pause();\n" +
				"})();");
	}

	/** Same as {@link #pause()}, rewinding to the start once the fade-out finishes. */
	void stop() {
		loadUrl("javascript:(function() {\n" +
				"  " + JS_FIND_VIDEO +
				"  if (v == null) return;\n" +
				"  if (window.FermataFade) window.FermataFade.pause(v, true);\n" +
				"  else { v.currentTime = 0; v.pause(); }\n" +
				"})();");
	}

	/**
	 * Seeks to 0 and resumes playback in one JS call, for Repeat One (see {@code
	 * YoutubeMediaEngine#ended()}) -- a separate {@link #setPosition}/{@link #play()} pair would
	 * still work, but round-trips through two separate {@code loadUrl()} evaluations with no
	 * ordering guarantee between them, where this is a single atomic script.
	 */
	void replay() {
		loadUrl("javascript:(function() {\n" +
				"  " + JS_FIND_VIDEO +
				"  if (v == null) { console.error('Fermata replay(): no video element found'); return; }\n" +
				"  v.currentTime = 0;\n" +
				"  if (window.FermataFade) window.FermataFade.prepareToPlay(v);\n" +
				"  var p = v.play();\n" +
				"  if (p && p.catch) p.catch(function(e) { console.error('Fermata replay() rejected: ' + e); });\n" +
				"})();");
	}

	/**
	 * Runs {@code switchVideo} once the current video's audio has faded out, so an explicit
	 * next/prev/queue switch doesn't cut the sound off mid-word. There is only one player, so the
	 * closest thing to a crossfade is fade-out, switch, then the new video fading itself in (see
	 * {@code youtube_fade.js}'s 'playing' handling). Runs it straight away when there is nothing
	 * audible to fade -- already paused/ended (a natural end-of-video advance, whose last seconds
	 * already faded out on their own), hidden, or the fade script isn't on this page.
	 */
	void afterAudioFadeOut(Runnable switchVideo) {
		// A newer switch (rapid repeated skips) supersedes one still waiting on its fade -- only the
		// last requested video should actually be navigated to.
		int gen = ++switchGeneration;
		if (pendingSwitch != null) removeCallbacks(pendingSwitch);
		pendingSwitch = null;
		evaluateJavascript("(function() {\n" +
				"  " + JS_FIND_VIDEO +
				"  return !!(window.FermataFade && v && window.FermataFade.fadeOut(v, " +
				SWITCH_FADE_OUT_MS + "));\n" +
				"})();", result -> {
			if (gen != switchGeneration) return;
			if ("true".equals(result)) {
				pendingSwitch = () -> {
					pendingSwitch = null;
					switchVideo.run();
				};
				postDelayed(pendingSwitch, SWITCH_FADE_OUT_MS);
			} else {
				switchVideo.run();
			}
		});
	}

	void prev() {
		prevNext(false);
	}

	void next() {
		prevNext(true);
	}

	/**
	 * Switches to a specific video by id -- used for queue-driven (Favorites/Playlist) next/prev,
	 * where (unlike {@link #next()}/{@link #prev()}) the app already knows exactly which video comes
	 * next and just needs the page to show it.
	 * <p>
	 * This used to go straight to the player's own {@code loadVideoById()}, which swaps the media
	 * inside the existing player and nothing else: the document URL, the page title, the
	 * recommendations list -- the whole visible page -- stayed on whichever video was last reached by
	 * a real navigation. That left the user looking at one video's page while a different one played,
	 * and it broke several things that quite reasonably read the page's URL as "what is playing":
	 * {@code YoutubeFragment#getCurrentVideoId()} (so the toolbar's favorites/playlist buttons, and
	 * "add to playlist", all acted on the stale video), and a manual page refresh, which reloaded the
	 * stale video and so looked to {@code YoutubeMediaEngine#playing()} exactly like YouTube's own
	 * autonav jumping somewhere unrequested -- whereupon it "corrected" it by advancing the queue,
	 * which is the reported "refresh just plays the next playlist item instead of what I picked".
	 * <p>
	 * So navigate the page for real instead, through YouTube's own single-page router (a click on an
	 * injected anchor, the same thing tapping a video tile does), which updates the whole page
	 * without a document reload and keeps fullscreen. {@link #navigateToVideoJs} falls back on its
	 * own to the old in-player swap, and then to a full document load, so the video always ends up
	 * playing even where the router doesn't take the click.
	 */
	void loadVideo(String videoId) {
		evaluateJavascript(navigateToVideoJs(videoId), result -> {
			// Only reached if the script itself couldn't run at all (no document body yet, an
			// exception) -- a plain page load is the last resort either way.
			if (!"true".equals(result)) loadUrl(YoutubeVideoItem.watchUrl(videoId));
		});
	}

	/**
	 * The cheap half of {@link #loadVideo}: swap the media inside the existing player and rewrite the
	 * document URL to match, with no page navigation at all. Used where the page is already on the
	 * right watch page and only the player has drifted -- {@code YoutubeMediaEngine#playing()}'s
	 * correction of a video id that isn't the one the app asked for, and Repeat One's re-arm -- both
	 * of which can fire repeatedly in a short burst and must stay cheap; routing those through a real
	 * navigation instead would turn a race the app is already losing into a series of page loads.
	 */
	void switchVideoInPlayer(String videoId) {
		evaluateJavascript("""
				(function() {
				  var p = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
				  var fn = p ? p.loadVideoById : null;
				  if (typeof fn !== 'function') return false;
				  fn.call(p, '%1$s');
				  try { history.replaceState(history.state, '', '/watch?v=%1$s'); } catch (e) {}
				  return true;
				})();
				""".formatted(videoId), result -> {
			if (!"true".equals(result)) loadUrl(YoutubeVideoItem.watchUrl(videoId));
		});
	}

	/**
	 * Drives YouTube's own single-page router to a watch page, exactly the way tapping one of its
	 * video tiles does -- by clicking a link. An injected {@code <a href="/watch?v=...">} is used
	 * rather than poking at YouTube's internal navigation objects (whose names change release to
	 * release): a same-origin anchor click is the one thing its router has always handled, and if a
	 * given page build doesn't handle it, the browser just performs the navigation itself, which is
	 * the correct destination anyway -- only heavier.
	 * <p>
	 * {@code __fermataSuppressLinkClick} keeps the app's own click out of its own user-tap detectors
	 * (see {@link #interceptLinkClicksJs()}/{@link #interceptUserNavigationJs()}), and
	 * {@code __fermataSyntheticClick} does the same for the prev/next button interceptor -- without
	 * them this navigation would read back as "the user deliberately picked another video" and
	 * {@code YoutubeMediaEngine#playing()} would drop the very queue that asked for it.
	 * <p>
	 * The deferred check is the safety net: if the URL hasn't become this video's within
	 * {@code NAVIGATION_FALLBACK_MS} the click was swallowed (neither router nor browser acted on
	 * it), so fall back to the in-player swap -- the pre-existing behaviour -- and finally to a
	 * plain document load. A full browser navigation replaces this document outright, taking the
	 * pending timer with it, so it can never double-navigate on the path that did work.
	 */
	private static String navigateToVideoJs(String videoId) {
		return """
				(function() {
				  var id = '%1$s';
				  var url = '/watch?v=' + id;
				  function onTarget() {
				    try { return new URLSearchParams(location.search).get('v') === id; }
				    catch (e) { return location.search.indexOf('v=' + id) >= 0; }
				  }
				  if (onTarget()) return true;
				  try {
				    var a = document.createElement('a');
				    a.href = url;
				    a.style.display = 'none';
				    document.body.appendChild(a);
				    window.__fermataSuppressLinkClick = true;
				    window.__fermataSyntheticClick = true;
				    a.click();
				    setTimeout(function() {
				      try { a.remove(); } catch (e) {}
				      window.__fermataSuppressLinkClick = false;
				      window.__fermataSyntheticClick = false;
				      window.__fermataLastLinkClickTime = 0;
				    }, 0);
				  } catch (e) { return false; }
				  setTimeout(function() {
				    if (onTarget()) return;
				    var p = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
				    if (p && (typeof p.loadVideoById === 'function')) {
				      p.loadVideoById(id);
				      try { history.replaceState(history.state, '', url); } catch (e) {}
				    } else {
				      location.assign(url);
				    }
				  }, %2$d);
				  return true;
				})();
				""".formatted(videoId, NAVIGATION_FALLBACK_MS);
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

	/**
	 * Keeps the player at its highest quality level, or its lowest ({@code lowest}: playing as music,
	 * where only the sound matters), re-applied on every player state change until cleared.
	 */
	void applyQualityPolicy(boolean lowest) {
		loadUrl("javascript:\n" +
				"(function() {\n" +
				CLEAR_HIGHEST_VIDEO_QUALITY_JS + USER_QUALITY_JS +
				"  clearFermataQ();\n" +
				(lowest ? "  fermataSaveUserQ();\n" : "  fermataRestoreUserQ();\n") +
				"  var state = window.__fermataQ = { player: null, handler: null, timeout: null, attempts: 0 };\n" +
				"  function getPlayer() {\n" +
				"    return document.querySelector('#movie_player') || document.querySelector('.html5-video-player');\n" +
				"  }\n" +
				"  var lowest = " + lowest + ";\n" +
				"  function applyHighest(p) {\n" +
				"    if (!p || typeof p.getAvailableQualityLevels !== 'function') return false;\n" +
				"    var levels = p.getAvailableQualityLevels();\n" +
				"    if (!levels || levels.length === 0) return false;\n" +
				"    var best = null;\n" +
				// Levels come highest first, 'auto' last.
				"    for (var i = 0; i < levels.length; i++) {\n" +
				"      if (levels[i] === 'auto') continue;\n" +
				"      best = levels[i];\n" +
				"      if (!lowest) break;\n" +
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

	/**
	 * Stops {@link #applyQualityPolicy} and hands the quality choice back to the viewer: their own
	 * saved preference if music mode had replaced it, else YouTube's automatic choice.
	 */
	void clearQualityPolicy() {
		loadUrl("javascript:\n" +
				"(function() {\n" +
				CLEAR_HIGHEST_VIDEO_QUALITY_JS + USER_QUALITY_JS +
				"  clearFermataQ();\n" +
				"  fermataSetQ(fermataRestoreUserQ() || 'auto');\n" +
				"})();");
	}

	/**
	 * Puts the viewer's own quality back if music mode left it replaced -- e.g. the app was closed
	 * while playing as music -- and does nothing otherwise.
	 */
	void restoreUserQuality() {
		loadUrl("javascript:\n" +
				"(function() {\n" +
				USER_QUALITY_JS +
				"  var level = fermataRestoreUserQ();\n" +
				"  if (level) fermataSetQ(level);\n" +
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

	/**
	 * The page document's own title, already unquoted (see {@link #unquoteJsResult(String)}).
	 * Only a fallback these days -- it lags YouTube's single-page-app navigation between videos and
	 * carries YouTube's own " - YouTube" suffix; the player's own title, reported alongside every
	 * "playing" event, is what actually names the current video (see {@code
	 * YoutubeWebView#attachListeners()}'s {@code fermataCurrentVideoTitle()} and {@code
	 * YoutubeMediaEngine#playing}).
	 */
	FutureSupplier<String> getVideoTitle() {
		Promise<String> p = new Promise<>();
		evaluateJavascript("document.title", r -> p.complete(unquoteJsResult(r)));
		return p;
	}

	/**
	 * What the page's {@code <video>} element is actually doing right now -- the only trustworthy
	 * answer to "is this thing playing", as opposed to what the media session last published.
	 * See {@link #getPageState()}.
	 */
	static final class PageState {
		/** False when the page has no {@code <video>} element at all right now (mid-navigation, or
		 * a page shape that simply doesn't have one) -- every other field is meaningless then. */
		final boolean hasVideo;
		final boolean paused;
		final boolean ended;
		final long positionMs;
		/** The id the player reports for whatever it currently holds, or {@code null}. */
		@Nullable
		final String videoId;

		private PageState(boolean hasVideo, boolean paused, boolean ended, long positionMs,
											@Nullable String videoId) {
			this.hasVideo = hasVideo;
			this.paused = paused;
			this.ended = ended;
			this.positionMs = positionMs;
			this.videoId = videoId;
		}

		@NonNull
		@Override
		public String toString() {
			return "PageState[hasVideo=" + hasVideo + ", paused=" + paused + ", ended=" + ended +
					", positionMs=" + positionMs + ", videoId=" + videoId + ']';
		}

		private static final PageState NONE = new PageState(false, true, false, 0, null);

		/** Parses the {@code hasVideo|paused|ended|positionMs|videoId} payload built below. */
		static PageState parse(@Nullable String s) {
			if (s == null) return NONE;
			String[] p = s.split("\\|", 5);
			if ((p.length < 5) || !"1".equals(p[0])) return NONE;
			long pos;
			try {
				pos = Long.parseLong(p[3]);
			} catch (NumberFormatException ex) {
				pos = 0;
			}
			return new PageState(true, "1".equals(p[1]), "1".equals(p[2]), pos,
					p[4].isEmpty() ? null : p[4]);
		}
	}

	/**
	 * Reads the page's real playback state. Deliberately self-contained (it re-derives the player id
	 * inline instead of calling the {@code fermataCurrentVideoId()} helper {@code attachListeners()}
	 * installs) so it still answers on a page where that injection hasn't run yet -- which, after a
	 * reload or an Android Auto display takeover, is exactly when it is most needed.
	 */
	FutureSupplier<PageState> getPageState() {
		Promise<PageState> p = new Promise<>();
		evaluateJavascript("""
				(function() {
				  var v = document.querySelector('video');
				  if (v == null) return '0|1|0|0|';
				  var id = '';
				  try {
				    var pl = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
				    var d = (pl && pl.getVideoData) ? pl.getVideoData() : null;
				    if (d && d.video_id) id = d.video_id;
				  } catch (e) {}
				  return '1|' + (v.paused ? '1' : '0') + '|' + (v.ended ? '1' : '0') + '|' +
				      Math.round((v.currentTime || 0) * 1000) + '|' + id;
				})();
				""", r -> p.complete(PageState.parse(unquoteJsResult(r))));
		return p;
	}

	/**
	 * Resumes the page's video, optionally seeking first -- one atomic script rather than a
	 * {@link #setPosition}/{@link #play()} pair, which round-trips through two independent
	 * evaluations with no ordering guarantee between them. {@code positionMs < 0} means "play from
	 * wherever it is".
	 * <p>
	 * Unlike {@link #play()}, this drives YouTube's own player object first and only then falls back
	 * to the raw {@code <video>} element. That matters specifically here: this is the recovery path
	 * after a display takeover, where the element {@code querySelector('video')} finds may be one the
	 * player has already abandoned and rebuilt around -- calling {@code play()} on it does nothing at
	 * all, which is exactly what a captured trace shows (an engine start() followed by half a minute
	 * of silence). {@code playVideo()} is what YouTube's own play button calls, and it acts on
	 * whichever element the player currently considers live. Both are issued: {@code play()} on an
	 * element the player already started is a harmless no-op.
	 */
	void resumeAt(long positionMs) {
		String seek = (positionMs < 0) ? "" :
				"  try {\n" +
						"    if (p && (typeof p.seekTo === 'function')) p.seekTo(" + (positionMs / 1000d) +
						", true);\n" +
						"    else if (v != null) v.currentTime = " + (positionMs / 1000d) + ";\n" +
						"  } catch (e) {}\n";
		loadUrl("javascript:(function() {\n" +
				"  var p = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');\n" +
				"  var v = document.querySelector('video');\n" +
				seek +
				"  if (window.FermataFade && (v != null)) window.FermataFade.prepareToPlay(v);\n" +
				"  try { if (p && (typeof p.playVideo === 'function')) p.playVideo(); } catch (e) {}\n" +
				"  if (v == null) { console.error('Fermata resumeAt(): no video element found'); return; }\n" +
				"  var r = v.play();\n" +
				"  if (r && r.catch) r.catch(function(e) { console.error('Fermata resumeAt() rejected: ' + e); });\n" +
				"})();");
	}

	void setScale(YoutubeAddon.VideoScale scale) {
		getAddon().setScale(scale);
		String p = scale.prefName();
		loadUrl("javascript:" +
				"document.querySelectorAll('video')" +
				".forEach(v=> v.style.objectFit = '" + p + "');");
	}
}
