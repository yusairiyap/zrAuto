package me.aap.fermata.addon.web;

import static android.content.res.Configuration.UI_MODE_NIGHT_MASK;
import static android.content.res.Configuration.UI_MODE_NIGHT_YES;
import static android.os.Build.VERSION;
import static android.os.Build.VERSION_CODES;
import static android.view.MotionEvent.ACTION_UP;
import static androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING;
import static androidx.webkit.WebViewFeature.FORCE_DARK;
import static androidx.webkit.WebViewFeature.FORCE_DARK_STRATEGY;
import static java.util.Objects.requireNonNull;
import static me.aap.fermata.addon.web.FermataJsInterface.JS_EDIT;
import static me.aap.fermata.addon.web.FermataJsInterface.JS_EVENT;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.ui.activity.ZrAutoActivity;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.TextChangedListener;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
public class FermataWebView extends WebView
		implements TextChangedListener, TextView.OnEditorActionListener, PreferenceStore.Listener,
		MainActivityListener {
	private final boolean isCar;
	private WebBrowserAddon addon;
	private FermataWebClient webClient;
	private FermataChromeClient chrome;

	public FermataWebView(Context context) {
		this(context, null);
	}

	public FermataWebView(Context context, AttributeSet attrs) {
		super(context, attrs);
		isCar = BuildConfig.AUTO && MainActivityDelegate.get(context).isCarActivityNotMirror();
	}

	public FermataWebView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		isCar = BuildConfig.AUTO && MainActivityDelegate.get(context).isCarActivityNotMirror();
	}

	@SuppressLint("SetJavaScriptEnabled")
	public void init(WebBrowserAddon addon, FermataWebClient webClient,
									 FermataChromeClient chromeClient) {
		this.addon = addon;
		this.webClient = webClient;
		setWebViewClient(webClient);
		setWebChromeClient(chromeClient);
		WebSettings s = getSettings();
		s.setSupportZoom(true);
		s.setBuiltInZoomControls(true);
		s.setDisplayZoomControls(false);
		s.setDatabaseEnabled(true);
		s.setDomStorageEnabled(true);
		s.setAllowFileAccess(true);
		s.setLoadWithOverviewMode(true);
		s.setJavaScriptEnabled(true);
		s.setMediaPlaybackRequiresUserGesture(false);
		s.setJavaScriptCanOpenWindowsAutomatically(true);

		addJavascriptInterface(createJsInterface(), FermataJsInterface.NAME);

		addon.getPreferenceStore().addBroadcastListener(this);
		MainActivityPrefs.get().addBroadcastListener(this);
		getActivity().onSuccess(a -> a.addBroadcastListener(this));

		// "Always use Private Mode" is enforced by WebBrowserAddon's constructor and
		// MainActivity.onResume() -- both settle PRIVATE_MODE_ENABLED before any fragment/WebView
		// exists, so by the time this runs, WebViewCompat.setProfile() (already called on this
		// instance by WebBrowserFragment/YoutubeFragment before init()) already used the right value.
		applyPrivateModeCookiePolicy();

		setDesktopMode(addon, false);
		setForceDark(addon, false);
	}

	@Override
	protected void onWindowVisibilityChanged(int visibility) {
		Log.i("FermataWebView.onWindowVisibilityChanged(", visibility, ")");
		if (!BuildConfig.AUTO) super.onWindowVisibilityChanged(visibility);
		else if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		Log.i("FermataWebView.onSizeChanged(): ", oldw, "x", oldh, " -> ", w, "x", h);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		Log.i("FermataWebView.onAttachedToWindow()");
	}

	@Override
	protected void onDetachedFromWindow() {
		Log.i("FermataWebView.onDetachedFromWindow()");
		super.onDetachedFromWindow();
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		if (prefs.contains(MainActivityPrefs.PRIVATE_MODE_ENABLED) ||
				prefs.contains(MainActivityPrefs.PRIVATE_MODE_BLOCK_3RD_PARTY_COOKIES)) {
			applyPrivateModeCookiePolicy();
			return;
		}

		WebBrowserAddon a = getAddon();
		if (a == null) return;

		if (prefs.contains(a.getDesktopVersionPref())) {
			setDesktopMode(a, true);
		} else if (prefs.contains(a.getUserAgentPref())) {
			UserAgent.ua = null;
			setDesktopMode(a, true);
		} else if (prefs.contains(a.getUserAgentDesktopPref())) {
			UserAgent.uaDesktop = null;
			setDesktopMode(a, true);
		} else if (prefs.contains(a.getForceDarkPref())) {
			setForceDark(addon, true);
		}
	}

	private void applyPrivateModeCookiePolicy() {
		MainActivityPrefs mp = MainActivityPrefs.get();
		boolean blockThirdParty = mp.isPrivateModeEnabled() &&
				mp.getBooleanPref(MainActivityPrefs.PRIVATE_MODE_BLOCK_3RD_PARTY_COOKIES);
		currentCookieManager().setAcceptThirdPartyCookies(this, !blockThirdParty);
	}

	/**
	 * The {@code CookieManager} for whichever profile this WebView is actually bound to. Plain
	 * {@code CookieManager.getInstance()} always targets the default profile's cookie jar --
	 * calling it directly here would silently misconfigure a WebView bound to the Private Mode
	 * profile (see {@link PrivateProfile}) instead of leaving its own jar alone.
	 */
	protected CookieManager currentCookieManager() {
		if (PrivateProfile.isSupported()) {
			androidx.webkit.Profile p = androidx.webkit.WebViewCompat.getProfile(this);
			if (p != null) return p.getCookieManager();
		}
		return CookieManager.getInstance();
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (handleActivityDestroyEvent(a, e)) {
			getAddon().getPreferenceStore().removeBroadcastListener(this);
			MainActivityPrefs.get().removeBroadcastListener(this);
		}
	}

	@Override
	protected void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		FermataChromeClient c = getWebChromeClient();
		if ((c != null) && c.isFullScreen()) getActivity().onSuccess(a -> c.setFullScreen(a, true));
	}

	private void setDesktopMode(WebBrowserAddon a, boolean reload) {
		if (getClass() != FermataWebView.class) return;

		WebSettings s = getSettings();
		boolean v = a.getPreferenceStore().getBooleanPref(a.getDesktopVersionPref());
		String ua = v ? UserAgent.getUaDesktop(s, a) : UserAgent.getUa(s, a);
		s.setUseWideViewPort(v);

		try {
			Log.d("Setting User-Agent to " + ua);
			s.setUserAgentString(ua);
		} catch (Exception ex) {
			Log.e(ex, "Invalid User-Agent: ", ua);
			String msg = ex.getLocalizedMessage();
			if (msg == null) msg = "Invalid User-Agent: " + ua;
			UiUtils.showAlert(getContext(), msg);
		}

		if (reload) reload();
	}

	@SuppressWarnings("deprecation")
	private void setForceDark(WebBrowserAddon a, boolean reload) {
		if ((VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) &&
				(WebViewFeature.isFeatureSupported(ALGORITHMIC_DARKENING))) {
			boolean dark = a.isForceDark() || (isDarkPhoneTheme() && a.isAutoDark());
			WebSettingsCompat.setAlgorithmicDarkeningAllowed(getSettings(), dark);
			if (reload) reload();
		} else if (WebViewFeature.isFeatureSupported(FORCE_DARK)) {
			int force;
			int strategy;
			if (a.isForceDark() || (isDarkPhoneTheme() && a.isAutoDark())) {
				force = WebSettingsCompat.FORCE_DARK_ON;
				strategy = WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING;
			} else {
				force = WebSettingsCompat.FORCE_DARK_OFF;
				strategy = WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY;
			}
			WebSettingsCompat.setForceDark(getSettings(), force);
			if (WebViewFeature.isFeatureSupported(FORCE_DARK_STRATEGY))
				WebSettingsCompat.setForceDarkStrategy(getSettings(), strategy);
			if (reload) reload();
		}
	}

	private boolean isDarkPhoneTheme() {
		int mode = getResources().getConfiguration().uiMode;
		return (mode & UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES;
	}

	protected FermataJsInterface createJsInterface() {
		return new FermataJsInterface(this);
	}

	protected boolean isCar() {
		return BuildConfig.AUTO && isCar;
	}

	public WebBrowserAddon getAddon() {
		return addon;
	}

	@NonNull
	@Override
	public FermataWebClient getWebViewClient() {
		return webClient;
	}

	public void setWebChromeClient(FermataChromeClient chrome) {
		this.chrome = chrome;
		super.setWebChromeClient(chrome);
	}

	@Nullable
	@Override
	public FermataChromeClient getWebChromeClient() {
		return chrome;
	}

	protected void pageLoaded(String uri) {
		addFocusHighlight();
		getAddon().setLastUrl(uri);
		getActivity().onSuccess(a -> {
			ActivityFragment f = a.getActiveFragment();
			if (f == null) return;

			ToolBarView.Mediator m = f.getToolBarMediator();

			if (m instanceof WebToolBarMediator wm) {
				ToolBarView tb = a.getToolBar();
				wm.setAddress(tb, uri);
				wm.setButtonsVisibility(tb, canGoBack(), canGoForward());
			}

			// Safe to always flush now, even in Private Mode: this targets whichever profile's cookie
			// jar this WebView is actually bound to, and the private profile's jar is wiped on its own
			// on the next entry (or on a manual "clear now") regardless of what's flushed to it here.
			currentCookieManager().flush();
		});
	}

	protected void addFocusHighlight() {
		evaluateJavascript("""
				(function() {
				  var style = document.createElement('style');
				  style.innerHTML = ':focus {outline: 2px solid blue !important; border-radius: 5px;}';
				  document.head.appendChild(style);
				})()""", null);
	}

	protected boolean requestFullScreen() {
		return false;
	}

	/**
	 * Tells the PAGE itself (not just the native custom view) to leave fullscreen, then runs
	 * {@code onDone} -- called whenever the app force-exits fullscreen from the Java side (e.g. an
	 * Android Auto display takeover backgrounding the app while a video is fullscreen), and always
	 * right before any subsequent re-entry, not just at the original exit point: a backgrounded
	 * WebView can suspend or queue injected JavaScript, so there is no guarantee an exit fired at
	 * pause-time has actually completed by the time resume-time tries to re-enter -- calling this
	 * again immediately beforehand and waiting for {@code onDone} closes that race by construction.
	 * No-op by default (runs {@code onDone} immediately); overridden where fullscreen is actually
	 * driven by a real DOM {@code requestFullscreen()} call (see {@code YoutubeWebView}), since the
	 * Android {@code WebChromeClient} contract for {@code onHideCustomView()}/
	 * {@code CustomViewCallback} does not reliably clear the page's own
	 * {@code document.fullscreenElement} when the app -- rather than the page's own JS -- initiates
	 * the exit. Left stuck, that silently blocks every later {@code requestFullscreen()} call on
	 * that element (automatic recovery and a manual re-tap alike) until the page is reloaded.
	 */
	protected void exitPageFullScreen(Runnable onDone) {
		onDone.run();
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (isCar()) {
			if (event.getAction() == ACTION_UP) checkTextInput();
		}
		return super.onTouchEvent(event);
	}

	private void checkTextInput() {
		if (!BuildConfig.AUTO || isKeyboardActive()) return;

		Log.d("checkTextInput");
		loadUrl("javascript:\n" + "function checkInput() {\n" + "  var e =  document.activeElement;" +
				"\n" + "  if (e == null) return;\n" + "  if (e instanceof HTMLInputElement) {\n" + "    " +
				JS_EVENT + '(' + JS_EDIT + ", e.value);\n" +
				"  } else if(e.getAttribute('contenteditable') == 'true') {\n" + "    " + JS_EVENT + '(' +
				JS_EDIT + ", e.innerText);\n" + "  }\n" + "}\n" + "setTimeout(checkInput, 500);");
	}

	private void setTextInput(CharSequence text) {
		if (!BuildConfig.AUTO) return;

		Log.d(text);
		loadUrl(
				"javascript:\n" + "var e =  document.activeElement;\n" + "var text = '" + text + "';\n" +
						"if (e.isContentEditable) e.innerText = text;\n" + "else e.value = text;\n" +
						"e.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true }));\n" +
						"e.dispatchEvent(new KeyboardEvent('keypress', { bubbles: true }));\n" +
						"e.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: " +
						"'insertText' }));\n" +
						"e.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));\n" +
						"e.dispatchEvent(new Event('change', { bubbles: true }));");
	}


	protected void submitForm() {
		if (!BuildConfig.AUTO) return;
		loadUrl("""
				javascript:
				var ae = document.activeElement;
				if (ae.form != null) {
				  ae.form.submit();
				} else {
				  var e = new KeyboardEvent('keydown',
				  { code: 'Enter', key: 'Enter', keyCode: 13, view: window, bubbles: true });
				  ae.dispatchEvent(e);
				  e = new KeyboardEvent('keyup',
				  { code: 'Enter', key: 'Enter', keyCode: 13, view: window, bubbles: true });
				  ae.dispatchEvent(e);
				}""");
	}

	public void showKeyboard(String text) {
		if (!BuildConfig.AUTO) return;

		getActivity().onSuccess(a -> {
			EditText et = a.getAppActivity().startInput(this);
			if (et == null) return;

			if (text != null) {
				et.setText(text);
				et.setSelection(et.getText().length());
			}

			et.setOnEditorActionListener(this);
		});
	}

	public void hideKeyboard() {
		if (!BuildConfig.AUTO) return;
		getActivity().onSuccess(a -> a.getAppActivity().stopInput());
	}

	private boolean isKeyboardActive() {
		if (!BuildConfig.AUTO) return false;

		ZrAutoActivity a = getActivity().map(MainActivityDelegate::getAppActivity).peek();
		return (a != null) && a.isInputActive();
	}

	@Override
	public void afterTextChanged(Editable s) {
		if (BuildConfig.AUTO) setTextInput(s);
	}

	@Override
	public boolean onEditorAction(TextView v, int actionId, @Nullable KeyEvent event) {
		if (!BuildConfig.AUTO) return false;

		switch (actionId) {
			case EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
					EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE -> {
				submitForm();
				hideKeyboard();
			}
		}

		return false;
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		FermataChromeClient chrome = getWebChromeClient();

		if ((chrome != null) && chrome.isFullScreen()) {
			chrome.onTouchEvent(this, ev);
		} else if (BuildConfig.AUTO) {
			ZrAutoActivity a = getActivity().map(MainActivityDelegate::getAppActivity).peek();

			if ((a != null) && a.isInputActive()) {
				a.stopInput();
				return true;
			}
		}

		return super.onInterceptTouchEvent(ev);
	}

	private FutureSupplier<MainActivityDelegate> getActivity() {
		return MainActivityDelegate.getActivityDelegate(getContext());
	}

	static final class UserAgent {
		private static final Pattern pattern =
				Pattern.compile(".+ AppleWebKit/(\\S+) .+ Chrome/(\\S+) .+");
		static String ua;
		static String uaDesktop;

		static String getUa(WebSettings s, WebBrowserAddon a) {
			if (ua != null) return ua;

			String ua = s.getUserAgentString();
			Matcher m = pattern.matcher(ua);

			if (m.matches()) {
				String av;
				if (VERSION.SDK_INT >= VERSION_CODES.R) av = VERSION.RELEASE_OR_CODENAME;
				else av = VERSION.RELEASE;
				String wv = m.group(1);
				String cv = m.group(2);
				UserAgent.ua = a.getUserAgent().replace("{ANDROID_VERSION}", av)
						.replace("{WEBKIT_VERSION}", requireNonNull(wv))
						.replace("{CHROME_VERSION}", requireNonNull(cv));
				UserAgent.ua = normalize(UserAgent.ua);
				if (UserAgent.ua.isEmpty()) UserAgent.ua = ua;
			} else {
				Log.w("User-Agent does not match the pattern ", pattern, ": " + ua);
				UserAgent.ua = ua;
			}

			return UserAgent.ua;
		}

		static String getUaDesktop(WebSettings s, WebBrowserAddon a) {
			if (uaDesktop != null) return uaDesktop;

			String ua = s.getUserAgentString();
			Matcher m = pattern.matcher(ua);

			if (m.matches()) {
				String wv = m.group(1);
				String cv = m.group(2);
				uaDesktop = a.getUserAgentDesktop().replace("{WEBKIT_VERSION}", requireNonNull(wv))
						.replace("{CHROME_VERSION}", requireNonNull(cv));
			} else {
				Log.w("User-Agent does not match the pattern ", pattern, ": " + ua);
				int i1 = ua.indexOf('(') + 1;
				int i2 = ua.indexOf(')', i1);
				uaDesktop = ua.substring(0, i1) + "X11; Linux x86_64" +
						ua.substring(i2).replace(" Mobile ", " ").replaceFirst(" Version/\\d+\\.\\d+ ", " ");
			}

			return uaDesktop = normalize(uaDesktop);
		}

		private static String normalize(String ua) {
			try (SharedTextBuilder b = SharedTextBuilder.get()) {
				int cut = 0;
				boolean changed = false;

				for (int i = 0, n = ua.length(); i < n; i++) {
					char c = ua.charAt(i);

					if (c <= ' ') {
						if ((b.length() == 0) || (ua.charAt(i - 1) == ' ')) {
							changed = true;
							continue;
						} else if (c != ' ') {
							b.append(' ');
							changed = true;
							continue;
						}
					}

					b.append(c);
				}

				for (int i = b.length() - 1; i >= 0; i--) {
					if (b.charAt(i) == ' ') cut++;
					else break;
				}

				if (cut != 0) {
					changed = true;
					b.setLength(b.length() - cut);
				}

				return changed ? b.toString() : ua;
			}
		}
	}
}
