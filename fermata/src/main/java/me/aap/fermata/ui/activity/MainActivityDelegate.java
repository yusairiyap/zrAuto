package me.aap.fermata.ui.activity;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS;
import static android.util.Base64.URL_SAFE;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static me.aap.fermata.BuildConfig.AUTO;
import static me.aap.fermata.action.KeyEventHandler.handleKeyEvent;
import static me.aap.fermata.ui.activity.MainActivityPrefs.BRIGHTNESS;
import static me.aap.fermata.ui.activity.MainActivityPrefs.CHANGE_BRIGHTNESS;
import static me.aap.fermata.ui.activity.MainActivityPrefs.CLOCK_POS;
import static me.aap.fermata.ui.activity.MainActivityPrefs.DIM_COLOR_CUSTOM_B;
import static me.aap.fermata.ui.activity.MainActivityPrefs.DIM_COLOR_CUSTOM_G;
import static me.aap.fermata.ui.activity.MainActivityPrefs.DIM_COLOR_CUSTOM_R;
import static me.aap.fermata.ui.activity.MainActivityPrefs.DIM_COLOR_PRESET;
import static me.aap.fermata.ui.activity.MainActivityPrefs.DIM_ENABLED;
import static me.aap.fermata.ui.activity.MainActivityPrefs.DIM_OPACITY;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB2_ACTION;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB2_ENABLED;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB3_ACTION;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB3_ENABLED;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB_DRAGGABLE;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB_SIZE;
import static me.aap.fermata.ui.activity.MainActivityPrefs.LOCALE;
import static me.aap.fermata.ui.activity.MainActivityPrefs.PRIVATE_MODE_ENABLED;
import static me.aap.fermata.ui.activity.MainActivityPrefs.VOICE_CONTROL_SUBST;
import static me.aap.fermata.ui.activity.MainActivityPrefs.VOICE_CONTROl_ENABLED;
import static me.aap.fermata.ui.activity.MainActivityPrefs.VOICE_CONTROl_FB;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.async.Completed.failed;
import static me.aap.utils.function.ResultConsumer.Cancel.isCancellation;
import static me.aap.utils.ui.UiUtils.ID_NULL;
import static me.aap.utils.ui.UiUtils.showAlert;
import static me.aap.utils.ui.UiUtils.toIntPx;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.Manifest;
import android.Manifest.permission;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.OperationCanceledException;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ContentLoadingProgressBar;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.textview.MaterialTextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.action.Key;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.FermataActivityAddon;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.FermataFragmentAddon;
import me.aap.fermata.addon.MediaLibAddon;
import me.aap.fermata.media.engine.MediaEngineManager;
import me.aap.fermata.media.lib.AtvInterface;
import me.aap.fermata.media.lib.DefaultMediaLib;
import me.aap.fermata.media.lib.ExportedItem;
import me.aap.fermata.media.lib.ExtRoot;
import me.aap.fermata.media.lib.IntentPlayable;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.lib.MediaLib.Playlist;
import me.aap.fermata.media.lib.SearchFolder;
import me.aap.fermata.media.pref.PlaybackControlPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.media.service.MediaSessionCallbackAssistant;
import me.aap.fermata.ui.fragment.AudioEffectsFragment;
import me.aap.fermata.ui.fragment.FavoritesFragment;
import me.aap.fermata.ui.fragment.FoldersFragment;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.fermata.ui.fragment.MediaLibFragment;
import me.aap.fermata.ui.fragment.NavBarMediator;
import me.aap.fermata.ui.fragment.PlaylistsFragment;
import me.aap.fermata.ui.fragment.SettingsFragment;
import me.aap.fermata.ui.fragment.SubtitlesFragment;
import me.aap.fermata.ui.view.BodyLayout;
import me.aap.fermata.ui.view.ControlPanelView;
import me.aap.fermata.ui.view.SecondaryFloatingButton;
import me.aap.fermata.ui.view.TertiaryFloatingButton;
import me.aap.fermata.ui.view.VideoView;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.concurrent.HandlerExecutor;
import me.aap.utils.event.ListenerLeakDetector;
import me.aap.utils.function.Cancellable;
import me.aap.utils.function.Function;
import me.aap.utils.function.IntObjectFunction;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.misc.MiscUtils;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.activity.AppActivity;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.view.DialogBuilder;
import me.aap.utils.ui.view.FloatingButton;
import me.aap.utils.ui.view.NavBarView;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
public class MainActivityDelegate extends ActivityDelegate
		implements MediaSessionCallbackAssistant, PreferenceStore.Listener {
	public static final String INTENT_ACTION_OPEN = "open";
	public static final String INTENT_ACTION_PLAY = "play";
	public static final String INTENT_ACTION_UPDATE = "update";
	public static final String INTENT_ACTION_FINISH = "finish";
	private static final String INTENT_SCHEME = "fermata";
	private final HandlerExecutor handler = new HandlerExecutor(App.get().getHandler().getLooper());
	private final NavBarMediator navBarMediator = new NavBarMediator();
	private final FermataServiceUiBinder mediaServiceBinder;
	private ToolBarView toolBar;
	private NavBarView navBar;
	private BodyLayout body;
	private ControlPanelView controlPanel;
	private FloatingButton floatingButton;
	private SecondaryFloatingButton floatingButton2;
	private TertiaryFloatingButton floatingButton3;
	private ContentLoadingProgressBar progressBar;
	private FutureSupplier<?> contentLoading;
	// Belt-and-suspenders re-sync for insetScrollableContent(): its own attach/layout listeners
	// cover the common case, but a tab restored by the fragment manager while switching
	// themes/nav-bar-position (both go through a full Activity.recreate()) can end up attached to
	// the window before tool_bar/control_panel finish their own post-recreate layout pass, or in an
	// ordering that has the sync listener miss the one layout change it needed -- leaving the
	// content's insets stuck at their initial (usually zero) value. A weak set so dropping a content
	// view (fragment/tab destroyed) doesn't pin it in memory; entries are added only while the view
	// is actually attached, so a stale/detached view here is harmless to visit.
	private final Set<ViewGroup> paddingInsetContent = Collections.newSetFromMap(new WeakHashMap<>());
	private final Set<View> topInsetContent = Collections.newSetFromMap(new WeakHashMap<>());
	private boolean barsHidden;
	private boolean videoMode;
	// Overrides the automatic bar-hiding that videoMode below otherwise forces in isFullScreen() --
	// set by Action.FULLSCREEN_TOGGLE for local (non-WebView) video, whose VideoView has no
	// NativeFullscreen handler to toggle instead. Reset on every videoMode transition so a manual
	// "show bars" choice doesn't leak into the next video played.
	private boolean videoBarsShown;
	private int brightness = 255;
	@Nullable
	private VideoView activeVideoView;
	private SpeechListener speechListener;
	private VoiceCommandHandler voiceCommandHandler;

	public MainActivityDelegate(AppActivity activity, FermataServiceUiBinder binder) {
		super(activity);
		mediaServiceBinder = binder;
	}

	@NonNull
	public static MainActivityDelegate get(Context ctx) {
		return (MainActivityDelegate) ActivityDelegate.get(ctx);
	}

	@NonNull
	@SuppressWarnings("unchecked")
	public static FutureSupplier<MainActivityDelegate> getActivityDelegate(Context ctx) {
		return (FutureSupplier<MainActivityDelegate>) ActivityDelegate.getActivityDelegate(ctx);
	}

	public static Context attachBaseContext(Context ctx) {
		MainActivityPrefs prefs = Prefs.instance;
		if (!prefs.hasPref(LOCALE)) return ctx;

		var cfg = ctx.getResources().getConfiguration();
		var loc = prefs.getLocalePref();
		cfg.setLocale(loc);
		Locale.setDefault(loc);
		return ctx.createConfigurationContext(cfg);
	}

	public static Uri toIntentUri(String action, String itemId) {
		String id = Base64.encodeToString(itemId.getBytes(US_ASCII), URL_SAFE);
		return new Uri.Builder().scheme(INTENT_SCHEME).authority(action).path(id).build();
	}

	@Nullable
	public static String intentUriToId(Uri u) {
		if ((u == null) || !INTENT_SCHEME.equals(u.getScheme())) return null;
		String id = u.getPath();
		return (id == null) ? null : new String(Base64.decode(id.substring(1), URL_SAFE), US_ASCII);
	}

	@Nullable
	public static String intentUriToAction(Uri u) {
		return (u != null) && INTENT_SCHEME.equals(u.getScheme()) ? u.getHost() : null;
	}

	@Override
	public void onActivityCreate(@Nullable Bundle state) {
		super.onActivityCreate(state);
		Intent intent = getIntent();
		if ((intent != null) && INTENT_ACTION_FINISH.equals(intent.getAction())) {
			finish();
			return;
		}

		getPrefs().addBroadcastListener(this);
		int navId;
		int fragmentId;

		if (state != null) {
			navId = state.getInt("navId", ID_NULL);
			fragmentId = state.getInt("fragmentId", ID_NULL);
		} else {
			navId = ID_NULL;
			fragmentId = ID_NULL;
		}

		AppActivity a = getAppActivity();
		FermataServiceUiBinder b = getMediaServiceBinder();
		Context ctx = a.getContext();
		b.getMediaSessionCallback().getSession().setSessionActivity(
				PendingIntent.getActivity(ctx, 0, new Intent(ctx, a.getClass()), FLAG_IMMUTABLE));
		b.getMediaSessionCallback().addAssistant(this, isCarActivityNotMirror() ? 0 : 1);
		if (b.getCurrentItem() == null) b.getMediaSessionCallback().onPrepare();
		init();

		for (FermataAddon addon : AddonManager.get().getAddons()) {
			if (addon instanceof FermataActivityAddon)
				((FermataActivityAddon) addon).onActivityCreate(this);
		}

		String[] perms = getRequiredPermissions();
		a.checkPermissions(perms).onCompletion((result, fail) -> {
			if (fail != null) {
				if (!isCarActivityNotMirror()) Log.e(fail);
			} else {
				Log.d("Requested permissions: ", Arrays.toString(perms),
						". Result: " + Arrays.toString(result));
			}

			if (fragmentId != ID_NULL) {
				setActiveNavItemId(navId);
				showFragment(fragmentId);
				return;
			}

			if ((intent != null) && !Intent.ACTION_MAIN.equals(intent.getAction())) {
				handleIntent(intent).onCompletion((r, err) -> {
					if (err != null) Log.e(err, "Failed to handle intent ", intent);
					if ((r == null) || !r) defaultIntent();
				});
			} else {
				defaultIntent();
			}
		});
	}

	@Override
	protected void onActivityNewIntent(Intent intent) {
		super.onActivityNewIntent(intent);
		handleIntent(intent);
	}

	private FutureSupplier<Boolean> handleIntent(Intent intent) {
		if (INTENT_ACTION_FINISH.equals(intent.getAction())) {
			finish();
			return completed(true);
		}

		for (FermataAddon a : AddonManager.get().getAddons()) {
			if (a.handleIntent(this, intent)) return completed(true);
		}

		Uri u = intent.getData();

		if (u != null) {
			if (INTENT_SCHEME.equals(u.getScheme())) {
				String action = u.getHost();
				if (action == null) return completed(false);
				String id = u.getPath();
				if (id == null) return completed(false);
				id = new String(Base64.decode(id.substring(1), URL_SAFE), US_ASCII);

				if (INTENT_ACTION_OPEN.equals(action)) {
					goToItem(id).map(MiscUtils::nonNull);
					return completed(true);
				} else if (INTENT_ACTION_PLAY.equals(action)) {
					goToItem(id).map(i -> {
						if (!(i instanceof PlayableItem)) return false;
						getMediaServiceBinder().playItem((PlayableItem) i);
						return true;
					});
					return completed(true);
				}
			} else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
				PlayableItem i = new IntentPlayable(this, u);
				getMediaServiceBinder().stop();
				post(() -> {
					if (!(getActiveFragment() instanceof MediaLibFragment))
						goToCurrent().onSuccess(v -> getMediaServiceBinder().playItem(i));
					else getMediaServiceBinder().playItem(i);
				});
			}
		}

		return completed(false);
	}

	private void defaultIntent() {
		if (getActiveFragment() != null) {
			checkUpdates();
			return;
		}

		String showAddon = getPrefs().getShowAddonOnStartPref();
		if (showAddon != null) {
			int fragId = NavBarMediator.nameToFragmentId(showAddon);

			if (fragId != 0) {
				showFragment(fragId);
				checkUpdates();
				return;
			}

			FermataAddon addon = AddonManager.get().getAddon(showAddon);

			if (addon instanceof FermataFragmentAddon) {
				showFragment(((FermataFragmentAddon) addon).getFragmentId());
				checkUpdates();
				return;
			}
		}

		FutureSupplier<Boolean> f = goToCurrent().onCompletion((ok, fail1) -> {
			if ((fail1 != null) && !isCancellation(fail1)) {
				Log.e(fail1, "Last played track not found");
			}
			if ((ok == null) || !ok) showFragment(R.id.folders_fragment);
			checkUpdates();
		});

		if (!f.isDone() || f.isFailed() || !Boolean.TRUE.equals(f.peek())) {
			showFragment(R.id.folders_fragment);
			setContentLoading(f);
		}
	}

	private void checkUpdates() {
		if (!AUTO) return;
		if (getAppActivity() instanceof MainActivity a) a.uninstallControl().thenRun(() -> {
			if (getPrefs().getCheckUpdatesPref()) a.checkUpdates();
		});
	}

	@Override
	protected void setUncaughtExceptionHandler() {
		if (!AUTO || getAppActivity().isCarActivity()) return;
		super.setUncaughtExceptionHandler();
	}

	@Override
	protected void onActivitySaveInstanceState(@NonNull Bundle outState) {
		super.onActivitySaveInstanceState(outState);
		if (isRecreating()) {
			outState.putInt("navId", getActiveNavItemId());
			outState.putInt("fragmentId", getActiveFragmentId());
		}
	}

	public void recreate() {
		if (AUTO && isCarActivityNotMirror()) showAlert(getContext(), R.string.please_restart_app);
		else getHandler().post(super::recreate);
	}

	@Override
	public void onActivityResume() {
		super.onActivityResume();
		refreshContentInsets();
		checkMirroringMode(true);
		for (FermataAddon addon : AddonManager.get().getAddons()) {
			if (addon instanceof FermataActivityAddon)
				((FermataActivityAddon) addon).onActivityResume(this);
		}
	}

	@Override
	public void onActivityPause() {
		super.onActivityPause();
		for (FermataAddon addon : AddonManager.get().getAddons()) {
			if (addon instanceof FermataActivityAddon)
				((FermataActivityAddon) addon).onActivityPause(this);
		}
	}

	// Widened to public (base ActivityDelegate declares these protected) and given trivial
	// public overrides purely so MainCarActivity -- in a different package, and not a subclass of
	// this class -- can forward onStart()/onStop() into the delegate the same way it already does
	// for onActivityResume()/onActivityDestroy(), restoring lifecycle parity with the phone path
	// (see ActivityBase, which forwards all of these but only works there because it's in the same
	// package as the base ActivityDelegate class).
	@Override
	public void onActivityStart() {
		super.onActivityStart();
	}

	@Override
	public void onActivityStop() {
		super.onActivityStop();
	}

	/**
	 * Fired when the Activity's window focus changes -- see
	 * {@link FermataActivityAddon#onActivityWindowFocusChanged}.
	 */
	public void onActivityWindowFocusChanged(boolean hasFocus) {
		for (FermataAddon addon : AddonManager.get().getAddons()) {
			if (addon instanceof FermataActivityAddon)
				((FermataActivityAddon) addon).onActivityWindowFocusChanged(this, hasFocus);
		}

		// Only ever fired with hasFocus=true (see MainCarActivity#onWindowFocusChanged) -- an
		// Android Auto display takeover (e.g. a car's camera overlay briefly taking the screen)
		// doesn't route through any playback-state change of its own, so the control panel can be
		// left showing whatever it was mid-interruption (most commonly hidden, if the interruption
		// coincided with a state transition through STOPPED/NONE) with nothing to naturally
		// re-sync it once focus returns. Re-syncing from the actual current state here is a no-op
		// if nothing was really wrong -- same "safe to fire on a spurious signal" spirit as
		// WebBrowserFragment#rebuildFullscreenVideoIfActive(), which handles the equivalent
		// recovery for YouTube's own fullscreen custom view.
		if (hasFocus) getMediaServiceBinder().resyncControlPanel();
	}

	@Override
	public void onActivityDestroy() {
		super.onActivityDestroy();
		handler.close();
		getMediaServiceBinder().getMediaSessionCallback().removeAssistant(this);
		getPrefs().removeBroadcastListener(this);
		if (speechListener != null) speechListener.destroy();

		for (FermataAddon addon : AddonManager.get().getAddons()) {
			if (addon instanceof FermataActivityAddon)
				((FermataActivityAddon) addon).onActivityDestroy(this);
		}

		if (me.aap.utils.BuildConfig.D) {
			boolean leaks = ListenerLeakDetector.hasLeaks((b, l) -> {
				if (l instanceof ExportedItem.ListenerWrapper)
					l = ((ExportedItem.ListenerWrapper) l).getListener();
				if (l instanceof Key.PrefsListener) return false;
				if (l instanceof FermataAddon) return false;
				if (l instanceof AtvInterface) return false;
				if ((l instanceof DefaultMediaLib) && (b instanceof DefaultMediaLib)) return false;
				if ((l instanceof MediaEngineManager) && (b instanceof DefaultMediaLib)) return false;
				return (!(l instanceof AddonManager)) ||
						(b != FermataApplication.get().getPreferenceStore());
			});
			if (leaks) Log.e(new IllegalStateException("Listener leaks detected!"));
		}
	}

	public void onActivityFinish() {
		super.onActivityFinish();
	}

	@NonNull
	@Override
	public ZrAutoActivity getAppActivity() {
		return (ZrAutoActivity) super.getAppActivity();
	}

	public boolean isCarActivity() {
		return AUTO && getAppActivity().isCarActivity() || FermataApplication.get().isMirroringMode();
	}

	public boolean isCarActivityNotMirror() {
		return isCarActivity();
	}

	@NonNull
	public MainActivityPrefs getPrefs() {
		return Prefs.instance;
	}

	@NonNull
	public PlaybackControlPrefs getPlaybackControlPrefs() {
		return getMediaServiceBinder().getMediaSessionCallback().getPlaybackControlPrefs();
	}

	public static void setTheme(Context ctx, boolean auto) {
		@StyleRes int theme = switch (Prefs.instance.getThemePref(auto)) {
			case MainActivityPrefs.THEME_LIGHT -> R.style.AppTheme_Light;
			case MainActivityPrefs.THEME_SYSTEM -> {
				if ((ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) ==
						Configuration.UI_MODE_NIGHT_YES)
					yield R.style.AppTheme_Dark;
				else yield R.style.AppTheme_Light;
			}
			case MainActivityPrefs.THEME_BLACK -> R.style.AppTheme_Black;
			case MainActivityPrefs.THEME_STAR_WARS -> R.style.AppTheme_BlackStarWars;
			case MainActivityPrefs.THEME_PURPLE -> R.style.AppTheme_Purple;
			case MainActivityPrefs.THEME_CLASSIC -> R.style.AppTheme_Classic;
			case MainActivityPrefs.THEME_DYNAMIC -> {
				if ((ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) ==
						Configuration.UI_MODE_NIGHT_YES)
					yield R.style.AppTheme_Dynamic;
				else yield R.style.AppTheme_DynamicLight;
			}
			default -> R.style.AppTheme_Dark;
		};
		ctx.setTheme(theme);
		if ((VERSION.SDK_INT >= VERSION_CODES.S) && (ctx instanceof Activity a)) {
			a.getSplashScreen().setSplashScreenTheme(theme);
		}
	}

	@Override
	public boolean interceptTouchEvent(MotionEvent e, Function<MotionEvent, Boolean> view) {
		if (AUTO && (e.getAction() == MotionEvent.ACTION_DOWN)) {
			ZrAutoActivity a = getAppActivity();

			if (a.isInputActive()) {
				a.stopInput();
				return true;
			}
		}

		return super.interceptTouchEvent(e, view);
	}

	@Override
	public boolean isFullScreen() {
		// While playing video, videoMode alone drives fullscreen (see videoBarsShown) rather than
		// OR-ing in the persisted pref -- otherwise Action.FULLSCREEN_TOGGLE's fallback toggle of that
		// pref would be a no-op for local video, since videoMode being true already forces this true
		// regardless of the pref's value.
		boolean fullscreen = videoMode ? !videoBarsShown : getPrefs().getFullscreenPref(this);
		if (!fullscreen) return false;

		if (isCarActivityNotMirror()) {
			FermataServiceUiBinder b = getMediaServiceBinder();
			return !b.getMediaSessionCallback().getPlaybackControlPrefs().getVideoAaShowStatusPref();
		} else {
			return true;
		}
	}

	/**
	 * Toggles the system status/navigation bars visible or hidden during active video playback,
	 * without leaving {@link BodyLayout.Mode#VIDEO}/{@link BodyLayout.Mode#BOTH} -- the local-video
	 * equivalent of a WebView-hosted player's {@link VideoView#toggleNativeFullscreen()}, used as
	 * {@link me.aap.fermata.action.Action#FULLSCREEN_TOGGLE}'s fallback when there's no such native
	 * handler to defer to.
	 */
	public void toggleVideoBars() {
		videoBarsShown = !videoBarsShown;
		setSystemUiVisibility();
	}

	public boolean isGridView() {
		ActivityFragment f = getActiveFragment();

		if ((f instanceof MediaLibFragment) && ((MediaLibFragment) f).isGridSupported()) {
			return getPrefs().getGridViewPref(this);
		} else {
			return false;
		}
	}

	@NonNull
	public FermataServiceUiBinder getMediaServiceBinder() {
		return mediaServiceBinder;
	}

	public MediaSessionCallback getMediaSessionCallback() {
		return getMediaServiceBinder().getMediaSessionCallback();
	}

	@NonNull
	public MediaLib getLib() {
		return getMediaServiceBinder().getLib();
	}

	@Nullable
	public PlayableItem getCurrentPlayable() {
		return getMediaServiceBinder().getCurrentItem();
	}

	public ToolBarView getToolBar() {
		return toolBar;
	}

	@Override
	public float getToolBarSize() {
		return getPrefs().getToolBarSizePref(this);
	}

	public NavBarView getNavBar() {
		return navBar;
	}

	@Override
	public float getNavBarSize() {
		return getPrefs().getNavBarSizePref(this);
	}

	public BodyLayout getBody() {
		return body;
	}

	public NavBarMediator getNavBarMediator() {
		return navBarMediator;
	}


	public ControlPanelView getControlPanel() {
		return controlPanel;
	}

	public FloatingButton getFloatingButton() {
		return floatingButton;
	}

	public SecondaryFloatingButton getFloatingButton2() {
		return floatingButton2;
	}

	@Nullable
	public TertiaryFloatingButton getFloatingButton3() {
		return floatingButton3;
	}

	@Nullable
	public VideoView getActiveVideoView() {
		return activeVideoView;
	}

	/**
	 * Leaves fullscreen video playback, whichever kind is currently active, so that a normal
	 * fragment can be shown afterwards.
	 * <p>
	 * A WebView-hosted player (YouTube) draws its fullscreen as its own overlay on the activity
	 * root and is not part of {@link BodyLayout}'s video mode at all, so collapsing the body alone
	 * would leave that overlay covering the whole screen -- ask the video view to leave its native
	 * fullscreen first, and only fall back to the body when there is no such fullscreen active.
	 */
	public void exitVideoMode() {
		VideoView v = activeVideoView;
		if ((v != null) && v.exitNativeFullscreen()) return;
		BodyLayout b = getBody();
		if ((b != null) && !b.isFrameMode()) b.setMode(BodyLayout.Mode.FRAME);
	}

	@Override
	public float getTextIconSize() {
		return getPrefs().getTextIconSizePref(this);
	}

	@Override
	public float getIconSize() {
		return getPrefs().getIconSizePref(this);
	}

	public boolean isBarsHidden() {
		return barsHidden;
	}

	public void setBarsHidden(boolean barsHidden) {
		App.get().getHandler().post(() -> {
			this.barsHidden = barsHidden;
			int visibility = barsHidden ? GONE : VISIBLE;
			ToolBarView tb = getToolBar();
			if (tb.getMediator() != ToolBarView.Mediator.Invisible.instance) tb.setVisibility(visibility);
			getNavBar().setVisibility(visibility);
			// tool_bar keeps its actual layout height above even when its mediator is Invisible (e.g.
			// while browsing a WebView, which draws its own navigation) -- its own visibility is
			// deliberately left untouched just above since toggling it wouldn't change anything
			// visible, but insetWebViewTop()'s margin is still sized off that height, so without this
			// a WebView never reclaims that reserved top space when the user hides the bars.
			refreshContentInsets();
		});
	}

	public void setVideoMode(boolean videoMode, @Nullable VideoView v) {
		if (videoMode == this.videoMode) {
			// The mode itself isn't changing, but getActiveVideoView() should still track whichever
			// view is actually live now, not whatever it was last set to -- e.g. YouTube re-entering
			// fullscreen after an Android Auto display takeover can call this with the same
			// videoMode value it already had (if this delegate's own tracking never flipped during
			// the interruption), and previously that meant this whole method was a no-op, leaving
			// getActiveVideoView() stale -- observed as Action.FULLSCREEN_TOGGLE either operating on
			// the wrong VideoView or silently falling through to the unrelated generic fullscreen-pref
			// toggle instead of YouTube's own WebView fullscreen.
			if ((v != null) && (v != activeVideoView)) {
				Log.d("setVideoMode(", videoMode, ", ", v, "): reclaiming activeVideoView (was ",
						activeVideoView, ")");
				activeVideoView = v;
				MainActivityPrefs p = getPrefs();
				v.setDimOverlay(videoMode && p.getBooleanPref(DIM_ENABLED), p.getIntPref(DIM_OPACITY),
						p.resolveDimColor());
			}
			return;
		}

		ControlPanelView cp = getControlPanel();
		videoBarsShown = false;

		if (videoMode) {
			this.videoMode = true;
			setSystemUiVisibility();
			keepScreenOn(true);
			cp.enableVideoMode();
		} else {
			this.videoMode = false;
			setSystemUiVisibility();
			keepScreenOn(false);
			if (cp != null) cp.disableVideoMode();
		}

		if (!checkMirroringMode(false)) {
			MainActivityPrefs p = getPrefs();

			if (p.getChangeBrightnessPref()) {
				if (videoMode) {
					brightness = getBrightness();
					setBrightness(p.getBrightnessPref());
				} else {
					setBrightness(brightness);
				}
			}
			if (p.getLandscapeVideoPref()) {
				if (videoMode) {
					getAppActivity().setRequestedOrientation(SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
				} else {
					getAppActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
				}
			}
		}

		if (v != null) {
			activeVideoView = v;
			MainActivityPrefs p = getPrefs();
			v.setDimOverlay(videoMode && p.getBooleanPref(DIM_ENABLED), p.getIntPref(DIM_OPACITY),
					p.resolveDimColor());
		}

		updateSecondaryFabVisibility();
		updateTertiaryFabVisibility();
		fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
	}

	/**
	 * Makes body_layout fill the whole screen, with tool_bar and control_panel floating over the
	 * top/bottom of it as translucent gradient scrims instead of squeezing it into the strip
	 * between them -- the same technique fullscreen video playback already used, now applied
	 * everywhere (video mode included) so every tab renders behind the bars, for a cleaner look
	 * with more of the screen visible, especially on Android Auto.
	 * <p>
	 * nav_bar is deliberately left completely alone, both its constraints and its appearance: it
	 * keeps its own fully opaque look and is declared after body_layout in every layout variant
	 * (bottom, left and right), so plain view-drawing order alone -- with no extra elevation
	 * needed -- already puts it on top of body_layout's now-larger bounds. body_layout extending
	 * geometrically behind it is invisible in practice since nav_bar is never translucent.
	 * <p>
	 * This deliberately does not go through {@code ConstraintSet}: cloning one captures every
	 * child's visibility, alpha, scale and translation as well, and applying it back stomps all of
	 * them -- it would re-hide the FAB and control panel (both are hidden the moment video mode
	 * starts, and the control panel is {@code gone} in the layout to begin with), reset a dragged
	 * FAB and undo the icon-size scaling. Touching only the anchors we actually change also means
	 * no dynamically added, id-less child can break the switch. Called once during setup, since the
	 * arrangement itself never changes afterward.
	 */
	private void enableBodyOverlayLayout() {
		View body = findViewById(R.id.body_layout);
		View tb = findViewById(R.id.tool_bar);
		View cp = findViewById(R.id.control_panel);
		if ((body == null) || (tb == null) || (cp == null)) return;
		if (!(body.getLayoutParams() instanceof ConstraintLayout.LayoutParams blp)
				|| !(tb.getLayoutParams() instanceof ConstraintLayout.LayoutParams tlp)
				|| !(cp.getLayoutParams() instanceof ConstraintLayout.LayoutParams clp)) return;

		blp.topToTop = PARENT_ID;
		blp.topToBottom = UNSET;
		blp.bottomToBottom = PARENT_ID;
		blp.bottomToTop = UNSET;

		tlp.bottomToTop = UNSET;
		tlp.bottomToBottom = UNSET;

		// control_panel's own bottom anchor (nav_bar or parent, depending on the layout variant) is
		// already correct as inflated -- only its top needs freeing so it floats off that single
		// anchor instead of also being pinned to body_layout's old (now much lower) bottom edge.
		clp.topToTop = UNSET;
		clp.topToBottom = UNSET;

		body.setLayoutParams(blp);
		tb.setLayoutParams(tlp);
		cp.setLayoutParams(clp);

		// In the bottom-nav layout, nav_bar's own topToBottom=control_panel constraint -- paired
		// with control_panel's bottomToTop=nav_bar above -- forms a genuine mutual reference now
		// that control_panel has no top constraint of its own to resolve independently: control_panel
		// needs nav_bar's position to place its bottom edge, and nav_bar needs control_panel's bottom
		// edge to place its own top, with neither resolvable first. ConstraintLayout does not resolve
		// this reliably (observed as nav_bar rendering right under tool_bar instead of at the screen
		// bottom). nav_bar's bottomToBottom=parent already fully determines its position on its own
		// (wrap_content height, single anchor -- exactly the pattern used above for tool_bar and
		// control_panel), so drop the redundant top constraint and let it resolve first,
		// independently; control_panel then resolves cleanly second, off nav_bar's now-correct edge.
		// The left/right layouts' nav_bar is unrelated to control_panel entirely (an independent
		// side column spanning top-to-bottom of its own accord) and is left untouched.
		if (getPrefs().getNavBarPosPref(this) == NavBarView.POSITION_BOTTOM) {
			View nb = findViewById(R.id.nav_bar);
			if ((nb != null) && (nb.getLayoutParams() instanceof ConstraintLayout.LayoutParams nlp)) {
				nlp.topToTop = UNSET;
				nlp.topToBottom = UNSET;
				nb.setLayoutParams(nlp);
			}
		}

		ToolBarView tbv = getToolBar();
		int c = MaterialColors.getColor(getContext(), androidx.appcompat.R.attr.colorPrimary,
				Color.BLACK);
		tbv.setBackground(ControlPanelView.buildScrimGradient(c, false));
	}

	/**
	 * Lets a tab's own scrollable content (a RecyclerView-based list or ScrollView -- a WebView
	 * doesn't reliably honor padding + clipToPadding for scroll-into-padding, so it uses
	 * {@link #insetWebViewTop} instead) keep scrolling all the way to its own first/last row
	 * underneath tool_bar/control_panel/nav_bar's translucent gradients, instead of either being
	 * cut off by them or permanently inset away from them -- gives {@code content} top/bottom
	 * padding sized to however much of tool_bar/control_panel/a bottom-positioned nav_bar actually
	 * overlaps {@code content}'s own on-screen bounds, with clipToPadding off, so rows already at
	 * rest show inset from the bars but can still scroll fully into view. Computed from actual
	 * screen position rather than assuming {@code content} always starts at the true top/bottom of
	 * the screen: BodyLayout.Mode.BOTH (a fragment shown alongside a still-playing video, e.g. Audio
	 * Effects) sits {@code content} below the video pane instead, where tool_bar may not reach it at
	 * all. Kept in sync with tool_bar/control_panel/nav_bar's actual size and position for as long as
	 * {@code content} stays attached to the window; each caller (e.g. MediaItemListView, the
	 * Settings list) is expected to call this once, typically from its own constructor.
	 */
	public void insetScrollableContent(ViewGroup content) {
		content.setClipToPadding(false);
		View.OnLayoutChangeListener sync = (v, left, top, right, bottom, oldLeft, oldTop, oldRight,
																				oldBottom) -> applyContentInsets(content);
		// Also self-triggered by content's own layout changes, not just tool_bar/control_panel's --
		// a fragment shown as its own root view (e.g. AudioEffectsView) can still be at its pre-
		// layout position/size (typically (0,0)) the moment it attaches to the window, before its
		// own first real layout pass places it where it'll actually sit; tool_bar/control_panel may
		// not move again afterward, so without this, the padding computed off that stale first
		// position/size never gets corrected once content settles into its real bounds.
		content.addOnLayoutChangeListener(sync);
		content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override
			public void onViewAttachedToWindow(@NonNull View v) {
				if (toolBar != null) toolBar.addOnLayoutChangeListener(sync);
				if (controlPanel != null) controlPanel.addOnLayoutChangeListener(sync);
				if (navBar != null) navBar.addOnLayoutChangeListener(sync);
				paddingInsetContent.add(content);
				applyContentInsets(content);
			}

			@Override
			public void onViewDetachedFromWindow(@NonNull View v) {
				if (toolBar != null) toolBar.removeOnLayoutChangeListener(sync);
				if (controlPanel != null) controlPanel.removeOnLayoutChangeListener(sync);
				if (navBar != null) navBar.removeOnLayoutChangeListener(sync);
				paddingInsetContent.remove(content);
			}
		});
		if (content.isAttachedToWindow()) {
			paddingInsetContent.add(content);
			applyContentInsets(content);
		}
	}

	private final int[] insetLoc1 = new int[2];
	private final int[] insetLoc2 = new int[2];

	private void applyContentInsets(ViewGroup content) {
		if ((toolBar == null) || (controlPanel == null) || !content.isAttachedToWindow()) return;

		content.getLocationOnScreen(insetLoc1);
		int contentTop = insetLoc1[1];
		int contentBottom = contentTop + content.getHeight();

		toolBar.getLocationOnScreen(insetLoc1);
		int top = Math.max(0, (insetLoc1[1] + toolBar.getHeight()) - contentTop);

		// Whichever bottom-anchored bar reaches furthest up the screen decides the inset -- usually
		// control_panel (nav_bar, when it's bottom-positioned, sits below it per the bottom-nav
		// layout's own constraints), but control_panel is routinely GONE while just browsing (nothing
		// playing), in which case nav_bar alone still needs clearing if it's the bottom-positioned one.
		int bottom = 0;
		if (controlPanel.getVisibility() == VISIBLE) {
			controlPanel.getLocationOnScreen(insetLoc2);
			bottom = Math.max(bottom, Math.max(0, contentBottom - insetLoc2[1]));
		}
		if ((navBar != null) && (navBar.getVisibility() == VISIBLE)
				&& (getPrefs().getNavBarPosPref(this) == NavBarView.POSITION_BOTTOM)) {
			navBar.getLocationOnScreen(insetLoc2);
			bottom = Math.max(bottom, Math.max(0, contentBottom - insetLoc2[1]));
		}

		if ((content.getPaddingTop() == top) && (content.getPaddingBottom() == bottom)) return;
		content.setPadding(content.getPaddingLeft(), top, content.getPaddingRight(), bottom);
	}

	/**
	 * A WebView's page content is composited internally by the browser engine rather than drawn as
	 * clippable child views, so it doesn't reliably scroll into padding the way a RecyclerView or
	 * ScrollView does -- observed as page content still rendering flush against/under tool_bar.
	 * Physically shrinking the WebView's own top bound with a margin instead is a hard guarantee
	 * regardless of how it renders internally. Deliberately top-only: the page is left full-bleed at
	 * the bottom, so control_panel is free to overlap the very end of the page the way it always
	 * has -- unlike the top, that's rarely actually scrolled to.
	 */
	public void insetWebViewTop(View content) {
		View.OnLayoutChangeListener sync = (v, left, top, right, bottom, oldLeft, oldTop, oldRight,
																				oldBottom) -> applyWebViewTopInset(content);
		content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override
			public void onViewAttachedToWindow(@NonNull View v) {
				if (toolBar != null) toolBar.addOnLayoutChangeListener(sync);
				topInsetContent.add(content);
				applyWebViewTopInset(content);
			}

			@Override
			public void onViewDetachedFromWindow(@NonNull View v) {
				if (toolBar != null) toolBar.removeOnLayoutChangeListener(sync);
				topInsetContent.remove(content);
			}
		});
		topInsetContent.add(content);
		// Applied right away, not only once attached -- a WebView's own attachment can lag behind a
		// synchronous loadUrl() call made right after construction, which would otherwise let that
		// first page load render unstyled/overlapping before the margin ever takes effect.
		applyWebViewTopInset(content);
	}

	private void applyWebViewTopInset(View content) {
		if (toolBar == null) return;
		if (!(content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp)) return;
		// tool_bar's own visibility/height doesn't actually change while its mediator is Invisible
		// (see setBarsHidden()) since a WebView draws its own navigation and toggling an invisible
		// bar's visibility wouldn't change anything -- but the user still expects "hide bars" to
		// reclaim that reserved space for the page, so treat it as zero-height ourselves here.
		int top = isBarsHidden() ? 0 : toolBar.getHeight();
		if (mlp.topMargin == top) return;
		mlp.topMargin = top;
		content.setLayoutParams(mlp);
	}

	/**
	 * Re-applies every currently-attached content view's insets against tool_bar/control_panel's
	 * present size and position. The attach/layout listeners set up in
	 * {@link #insetScrollableContent}/{@link #insetWebViewTop} already keep things in sync
	 * incrementally, but a tab restored by the fragment manager across a full {@link #recreate()}
	 * (theme or nav-bar-position change) can end up missing the one layout event it needed; called
	 * from a handful of extra points (a global layout pass, activity resume) as a cheap catch-all --
	 * {@link #applyContentInsets}/{@link #applyWebViewTopInset} already no-op when nothing changed.
	 */
	private void refreshContentInsets() {
		for (ViewGroup content : paddingInsetContent) applyContentInsets(content);
		for (View content : topInsetContent) applyWebViewTopInset(content);
	}

	private boolean checkMirroringMode(boolean clearFlags) {
		if (!AUTO) return false;
		var screenOnFlags =
				FLAG_KEEP_SCREEN_ON | FLAG_TURN_SCREEN_ON | FLAG_DISMISS_KEYGUARD | FLAG_SHOW_WHEN_LOCKED;
		var app = FermataApplication.get();
		if (!app.isMirroringMode()) {
			if (clearFlags) getWindow().clearFlags(screenOnFlags);
			return false;
		}
		setFullScreen(true);
		getWindow().addFlags(screenOnFlags);
		getAppActivity().setRequestedOrientation(
				app.isMirroringLandscape() ? SCREEN_ORIENTATION_SENSOR_LANDSCAPE :
						SCREEN_ORIENTATION_SENSOR_PORTRAIT);
		return true;
	}

	public void keepScreenOn(boolean on) {
		if (on) getWindow().addFlags(FLAG_KEEP_SCREEN_ON);
		else getWindow().clearFlags(FLAG_KEEP_SCREEN_ON);
	}

	public int getBrightness() {
		return Settings.System.getInt(getContext().getContentResolver(), SCREEN_BRIGHTNESS, 255);
	}

	public void setBrightness(int br) {
		try {
			Settings.System.putInt(getContext().getContentResolver(), SCREEN_BRIGHTNESS, br);
		} catch (SecurityException ex) {
			Log.e(ex, "Failed to change brightness");
		}
	}

	public boolean isVideoMode() {
		return videoMode;
	}

	public void setContentLoading(FutureSupplier<?> contentLoading) {
		if (this.contentLoading != null) {
			this.contentLoading.cancel();
			this.contentLoading = null;
		}

		progressBar.hide();
		if (contentLoading.isDone()) return;
		progressBar.show();

		var cl = this.contentLoading = contentLoading.main();
		cl.onCompletion((r, f) -> {
			if ((f != null) && !isCancellation(f)) Log.d(f);
			if (this.contentLoading == cl) {
				this.contentLoading = null;
				progressBar.hide();
			}
		});
	}

	public void backToNavFragment() {
		int id = getActiveNavItemId();
		showFragment((id == ID_NULL) ? R.id.folders_fragment : id);
	}

	@Override
	protected int getFrameContainerId() {
		return R.id.frame_layout;
	}

	@Nullable
	@Override
	public ActivityFragment showFragment(int id, Object input) {
		// A WebView-hosted player's (YouTube's) native fullscreen is drawn as its own overlay on
		// the activity root, entirely outside BodyLayout's video mode -- leave it first so the
		// fragment about to be shown isn't left hidden underneath it (matches what dim_settings'
		// own explicit exitVideoMode() call already does for that one menu item).
		VideoView v = getActiveVideoView();
		if (v != null) v.exitNativeFullscreen();
		BodyLayout b = getBody();
		if (b.isVideoMode()) b.setMode(BodyLayout.Mode.BOTH);
		ActivityFragment f = super.showFragment(id, input);
		updateSecondaryFabVisibility();
		updateTertiaryFabVisibility();
		return f;
	}

	protected ActivityFragment createFragment(int id) {
		if (id == R.id.folders_fragment) {
			return new FoldersFragment();
		} else if (id == R.id.favorites_fragment) {
			return new FavoritesFragment();
		} else if (id == R.id.playlists_fragment) {
			return new PlaylistsFragment();
		} else if (id == R.id.settings_fragment) {
			return new SettingsFragment();
		} else if (id == R.id.audio_effects_fragment) {
			return new AudioEffectsFragment();
		} else if (id == R.id.subtitles_fragment) {
			return new SubtitlesFragment();
		}
		ActivityFragment f = FermataApplication.get().getAddonManager().createFragment(id);
		return (f != null) ? f : super.createFragment(id);
	}

	@Nullable
	public MediaLibFragment getActiveMediaLibFragment() {
		ActivityFragment f = getActiveFragment();
		return (f instanceof MediaLibFragment) ? (MediaLibFragment) f : null;
	}

	@Nullable
	public MainActivityFragment getActiveMainActivityFragment() {
		ActivityFragment f = getActiveFragment();
		return (f instanceof MainActivityFragment) ? (MainActivityFragment) f : null;
	}

	@Nullable
	public MediaLibFragment getMediaLibFragment(int id) {
		for (Fragment f : getSupportFragmentManager().getFragments()) {
			if (!(f instanceof MediaLibFragment m)) continue;
			if (m.getFragmentId() == id) return m;
		}

		return null;
	}

	public boolean hasCurrent() {
		PlayableItem pi = getMediaServiceBinder().getCurrentItem();
		return (pi != null) || (getLib().getPrefs().getLastPlayedItemPref() != null);
	}

	public FutureSupplier<Boolean> goToCurrent() {
		PlayableItem pi = getMediaServiceBinder().getCurrentItem();
		return ((pi == null) || (pi.isExternal())) ?
				getLib().getLastPlayedItem().main().map(this::goToItem) : completed(goToItem(pi));
	}

	public FutureSupplier<Item> goToItem(String id) {
		return getLib().getItem(id).main(getHandler()).map(i -> goToItem(i) ? i : null);
	}

	public boolean goToItem(Item i) {
		if (i == null) return false;
		BrowsableItem root = i.getRoot();

		if (root instanceof MediaLib.Folders) {
			showFragment(R.id.folders_fragment);
		} else if (root instanceof MediaLib.Favorites) {
			showFragment(R.id.favorites_fragment);
		} else if (root instanceof MediaLib.Playlists) {
			showFragment(R.id.playlists_fragment);
		} else if (root instanceof ExtRoot) {
			if ("youtube".equals(root.getId())) {
				showFragment(R.id.youtube_fragment);
				return true;
			}
		} else {
			MediaLibAddon a = AddonManager.get().getMediaLibAddon(root);
			if (a != null) {
				showFragment(a.getFragmentId());
			} else {
				Log.d("Unsupported item: ", i);
				return false;
			}
		}

		FermataApplication.get().getHandler().post(() -> {
			ActivityFragment f = getActiveFragment();
			if (!(f instanceof MediaLibFragment)) return;
			if (i instanceof PlayableItem) ((MediaLibFragment) f).revealItem(i);
			else if (i instanceof BrowsableItem) ((MediaLibFragment) f).openItem((BrowsableItem) i);
		});

		return true;
	}

	@Override
	protected boolean exitOnBackPressed() {
		return !isCarActivityNotMirror();
	}

	@Override
	public OverlayMenu createMenu(View anchor) {
		return findViewById(R.id.context_menu);
	}

	public OverlayMenu getContextMenu() {
		return findViewById(R.id.context_menu);
	}

	public OverlayMenu getToolBarMenu() {
		return findViewById(R.id.tool_menu);
	}

	public void startVoiceAssistant() {
		ActivityFragment f = getActiveFragment();
		if (!(f instanceof MainActivityFragment) || !((MainActivityFragment) f).startVoiceAssistant())
			voiceSearch(getCurrentFocus());
	}

	private void voiceSearch(View focus) {
		startSpeechRecognizer().onSuccess(q -> {
			if (focus instanceof EditText) {
				((EditText) focus).setText(q.get(0));
				focus.requestFocus();
			} else if (getAppActivity().isInputActive()) {
				getAppActivity().setTextInput(q.get(0));
			} else {
				VoiceCommandHandler h = voiceCommandHandler;
				if (h == null) h = voiceCommandHandler = new VoiceCommandHandler(this);
				h.handle(q);
			}
		});
	}

	public FutureSupplier<List<String>> startSpeechRecognizer() {
		return startSpeechRecognizer(null, false);
	}

	public FutureSupplier<List<String>> startSpeechRecognizer(String locale, boolean textInput) {
		FutureSupplier<int[]> check =
				isCarActivityNotMirror() ? completed(new int[]{PERMISSION_GRANTED}) :
						getAppActivity().checkPermissions(Manifest.permission.RECORD_AUDIO);
		return check.then(r -> {
			if (r[0] == PERMISSION_GRANTED) return completedVoid();
			else return failed(new IllegalStateException("Audio recording permission is not granted"));
		}).onFailure(err -> {
			Log.e(err, "Failed to request RECORD_AUDIO permission");
			showAlert(getContext(), R.string.err_no_audio_record_perm);
		}).then(v -> {
			if (speechListener != null) speechListener.destroy();
			Promise<List<String>> p = new Promise<>();
			String lang = (locale == null) ? getPrefs().getVoiceControlLang(this) : locale;
			Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
			i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
			i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
			i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
			speechListener = new SpeechListener(p, textInput);
			speechListener.start(i);
			return p;
		});
	}

	@NonNull
	@Override
	public FutureSupplier<PlayableItem> getPrevPlayable(Item i) {
		MediaLibFragment f = getActiveMediaLibFragment();
		if (f == null) return MediaSessionCallbackAssistant.super.getPrevPlayable(i);
		BrowsableItem p = f.getAdapter().getParent();
		return (p instanceof SearchFolder) ? ((SearchFolder) p).getPrevPlayable(i) :
				MediaSessionCallbackAssistant.super.getPrevPlayable(i);
	}

	@NonNull
	@Override
	public FutureSupplier<PlayableItem> getNextPlayable(Item i) {
		MediaLibFragment f = getActiveMediaLibFragment();
		if (f == null) return MediaSessionCallbackAssistant.super.getNextPlayable(i);
		BrowsableItem p = f.getAdapter().getParent();
		return (p instanceof SearchFolder) ? ((SearchFolder) p).getNextPlayable(i) :
				MediaSessionCallbackAssistant.super.getNextPlayable(i);
	}

	@Override
	public EditText createEditText(Context ctx) {
		EditText t = getAppActivity().createEditText(ctx);
		if (isCarActivity() && getPrefs().getVoiceControlEnabledPref()) {
			t.setOnLongClickListener(v -> {
				startSpeechRecognizer().onSuccess(q -> t.setText(q.get(0)));
				return true;
			});
		}
		return t;
	}

	@Override
	public DialogBuilder createDialogBuilder(Context ctx) {
		return DialogBuilder.create(getContextMenu());
	}

	public void addPlaylistMenu(OverlayMenu.Builder builder,
															FutureSupplier<List<PlayableItem>> selection) {
		addPlaylistMenu(builder, () -> selection, () -> "");
	}

	public void addPlaylistMenu(OverlayMenu.Builder builder,
															Supplier<FutureSupplier<List<PlayableItem>>> selection,
															Supplier<? extends CharSequence> initName) {
		// Captured now, while builder still belongs to whichever OverlayMenu instance is actually
		// on screen for this particular caller -- context_menu for the per-item long-press menu,
		// control_menu for the control panel's own "..." button, tool_bar_menu for YouTube's
		// dedicated favorites/playlist toolbar buttons. createDialogBuilder() hardcodes
		// context_menu, so calling it here unconditionally would render the dialog into an
		// instance other than the one the tap actually came from for the latter two -- invisible,
		// since that instance isn't the one currently showing.
		OverlayMenu menu = builder.getMenu();
		builder.addItem(R.id.playlist_add, R.drawable.playlist_add, R.string.playlist_add)
				.setHandler(i -> {
					showPlaylistDialog(menu, selection, initName);
					return true;
				});
	}

	/**
	 * A single tap on "Add to playlist" now goes straight to a real dialog listing existing
	 * playlists (plus "Create new playlist") rather than drilling into another OverlayMenu page --
	 * one fewer menu-within-a-menu step for an action that's just a one-time choice.
	 */
	private void showPlaylistDialog(OverlayMenu menu,
																	 Supplier<FutureSupplier<List<PlayableItem>>> selection,
																	 Supplier<? extends CharSequence> initName) {
		getLib().getPlaylists().getUnsortedChildren().main().onSuccess(playlists -> {
			Context ctx = getContext();
			CharSequence[] items = new CharSequence[playlists.size() + 1];
			items[0] = ctx.getString(R.string.playlist_create);
			for (int i = 0; i < playlists.size(); i++) {
				items[i + 1] = ((Playlist) playlists.get(i)).getName();
			}

			DialogBuilder.create(menu).setTitle(R.drawable.playlist_add, R.string.playlist_add)
					.setSingleChoiceItems(items, -1, (d, which) -> {
						d.dismiss();
						if (which == 0) {
							createPlaylist(selection.get(), initName);
						} else {
							addToPlaylist(((Playlist) playlists.get(which - 1)).getName(), selection.get());
						}
					})
					.setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss())
					.show();
		});
	}

	private boolean createPlaylist(FutureSupplier<List<PlayableItem>> selection,
																 Supplier<? extends CharSequence> initName) {
		UiUtils.queryText(getContext(), R.string.playlist_name, R.drawable.playlist, initName.get())
				.onSuccess(name -> {
					discardSelection();
					if (name == null) return;

					getLib().getPlaylists().addItem(name)
							.onFailure(err -> showAlert(getContext(), err.getMessage())).then(
									pl -> selection.main().then(items -> pl.addItems(items)
											.onFailure(err -> showAlert(getContext(), err.getMessage())).thenRun(() -> {
												MediaLibFragment f = getMediaLibFragment(R.id.playlists_fragment);
												if (f != null) f.getAdapter().reload();
												UiUtils.showToast(getContext(), R.string.added_to_playlist, name);
											})));
				});
		return true;
	}

	private boolean addToPlaylist(String name, FutureSupplier<List<PlayableItem>> selection) {
		discardSelection();
		getLib().getPlaylists().getUnsortedChildren().main().onSuccess(playlists -> {
			for (Item i : getLib().getPlaylists().getUnsortedChildren().getOrThrow()) {
				Playlist pl = (Playlist) i;

				if (name.equals(pl.getName())) {
					selection.main().onSuccess(items -> {
						pl.addItems(items);
						MediaLibFragment f = getMediaLibFragment(R.id.playlists_fragment);
						if (f != null) f.getAdapter().reload();
						UiUtils.showToast(getContext(), R.string.added_to_playlist, name);
					});
					break;
				}
			}
		});
		return true;
	}

	public void removeFromPlaylist(Playlist pl, List<PlayableItem> selection) {
		discardSelection();
		pl.removeItems(selection).onFailure(err -> showAlert(getContext(), err.getMessage()))
				.thenRun(() -> {
					MediaLibFragment f = getMediaLibFragment(R.id.playlists_fragment);
					if (f != null) f.getAdapter().reload();
				});
	}

	private void discardSelection() {
		ActivityFragment f = getActiveFragment();
		if (f instanceof MainActivityFragment) ((MainActivityFragment) f).discardSelection();
	}

	@Override
	protected int getExitMsg() {
		return R.string.press_back_again;
	}

	private void init() {
		ZrAutoActivity a = getAppActivity();
		a.setContentView(getLayout());
		toolBar = a.findViewById(R.id.tool_bar);
		progressBar = a.findViewById(R.id.content_loading_progress);
		navBar = a.findViewById(R.id.nav_bar);
		body = a.findViewById(R.id.body_layout);
		controlPanel = a.findViewById(R.id.control_panel);
		floatingButton = a.findViewById(R.id.floating_button);
		floatingButton.setScale(getPrefs().getFabSizePref());
		floatingButton2 = a.findViewById(R.id.floating_button2);
		floatingButton2.setScale(getPrefs().getFabSizePref());
		floatingButton3 = a.findViewById(R.id.floating_button3);
		floatingButton3.setScale(getPrefs().getFabSizePref());
		updateFabDraggable();
		controlPanel.bind(getMediaServiceBinder());
		enableBodyOverlayLayout();
		// Catch-all re-sync -- see refreshContentInsets() -- for content whose own attach/layout
		// listeners missed the layout change they needed, most notably a tab restored by the
		// fragment manager across the recreate() that a theme or nav-bar-position change triggers.
		body.getViewTreeObserver().addOnGlobalLayoutListener(this::refreshContentInsets);

		if (VERSION.SDK_INT >= VERSION_CODES.VANILLA_ICE_CREAM && !a.isCarActivity()) {
			ViewCompat.setOnApplyWindowInsetsListener(toolBar, (v, insets) -> {
				var bars = insets.getInsets(
						WindowInsetsCompat.Type.systemBars()
				);
				a.findViewById(R.id.main_activity).setPadding(bars.left, bars.top, bars.right,
						bars.bottom);
				return WindowInsetsCompat.CONSUMED;
			});
		}
	}

	@LayoutRes
	private int getLayout() {
		MainActivityPrefs prefs = getPrefs();
		return switch (prefs.getNavBarPosPref(this)) {
			case NavBarView.POSITION_LEFT -> R.layout.main_activity_left;
			case NavBarView.POSITION_RIGHT -> R.layout.main_activity_right;
			default -> R.layout.main_activity;
		};
	}

	private static String[] getRequiredPermissions() {
		List<String> perms = new ArrayList<>();
		perms.add(permission.READ_EXTERNAL_STORAGE);
		if (VERSION.SDK_INT >= VERSION_CODES.P) {
			perms.add(permission.FOREGROUND_SERVICE);
		}
		if (VERSION.SDK_INT >= VERSION_CODES.Q) {
			perms.add(permission.ACCESS_MEDIA_LOCATION);
			perms.add(permission.USE_FULL_SCREEN_INTENT);
		}
		if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
			perms.add(permission.USE_FULL_SCREEN_INTENT);
			perms.add(permission.POST_NOTIFICATIONS);
		}
		if (VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
			perms.add(permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK);
		}
		return perms.toArray(new String[0]);
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		if (MainActivityPrefs.hasThemePref(this, prefs)) {
			recreate();
		} else if (MainActivityPrefs.hasNavBarPosPref(this, prefs)) {
			recreate();
		} else if (prefs.contains(FAB_SIZE)) {
			if (floatingButton != null) floatingButton.setScale(getPrefs().getFabSizePref());
			if (floatingButton2 != null) floatingButton2.setScale(getPrefs().getFabSizePref());
			if (floatingButton3 != null) floatingButton3.setScale(getPrefs().getFabSizePref());
		} else if (MainActivityPrefs.hasNavBarSizePref(this, prefs)) {
			if (navBar != null) navBar.setSize(getPrefs().getNavBarSizePref(this));
		} else if (MainActivityPrefs.hasToolBarSizePref(this, prefs)) {
			if (toolBar != null) toolBar.setSize(getPrefs().getToolBarSizePref(this));
		} else if (MainActivityPrefs.hasIconSizePref(this, prefs)) {
			if (navBar != null) navBar.setIconScale(getPrefs().getIconSizePref(this));
			if (toolBar != null) toolBar.setIconScale(getPrefs().getIconSizePref(this));
		} else if (MainActivityPrefs.hasFullscreenPref(this, prefs)) {
			setSystemUiVisibility();
		} else if (prefs.contains(CHANGE_BRIGHTNESS)) {
			if (getPrefs().getChangeBrightnessPref()) {
				if (!Settings.System.canWrite(getContext())) {
					Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
					i.setData(Uri.parse("package:" + getContext().getPackageName()));
					startActivity(i);
				}
			}
		} else if (prefs.contains(BRIGHTNESS)) {
			if (isVideoMode()) setBrightness(getPrefs().getBrightnessPref());
		} else if (prefs.contains(VOICE_CONTROl_ENABLED)) {
			if (!getPrefs().getVoiceControlEnabledPref()) {
				getPrefs().applyBooleanPref(VOICE_CONTROl_FB, false);
				return;
			}
			getAppActivity().checkPermissions(permission.RECORD_AUDIO).onCompletion((r, err) -> {
				if ((err == null) && (r[0] == PERMISSION_GRANTED)) return;
				if (err != null) Log.e(err, "Failed to request RECORD_AUDIO permission");
				showAlert(getContext(), R.string.err_no_audio_record_perm);
				getPrefs().applyBooleanPref(VOICE_CONTROl_FB, false);
			});
		} else if (prefs.contains(VOICE_CONTROL_SUBST)) {
			if (voiceCommandHandler != null) voiceCommandHandler.updateWordSubst();
		} else if (prefs.contains(LOCALE)) {
			recreate();
		} else if (prefs.contains(DIM_ENABLED) || prefs.contains(DIM_OPACITY)
				|| prefs.contains(DIM_COLOR_PRESET) || prefs.contains(DIM_COLOR_CUSTOM_R)
				|| prefs.contains(DIM_COLOR_CUSTOM_G) || prefs.contains(DIM_COLOR_CUSTOM_B)) {
			if (isVideoMode() && (activeVideoView != null)) {
				MainActivityPrefs p = getPrefs();
				activeVideoView.setDimOverlay(p.getBooleanPref(DIM_ENABLED), p.getIntPref(DIM_OPACITY),
						p.resolveDimColor());
			}
			// Keeps FAB2's icon (when its configured action is "dim toggle") in sync with changes
			// made from Settings or the video "more" menu, not just from FAB2 itself.
			if (prefs.contains(DIM_ENABLED) && (floatingButton2 != null)) {
				fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
			}
		} else if (prefs.contains(PRIVATE_MODE_ENABLED)) {
			// Keeps FAB2/FAB3's icon (when bound to "Private Mode toggle") and the browser/YouTube
			// toolbar's private-mode button in sync with changes made from Settings, the nav-bar menu,
			// or another FAB, not just whichever surface was actually tapped.
			fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
		} else if (prefs.contains(FAB2_ENABLED)) {
			updateSecondaryFabVisibility();
		} else if (prefs.contains(FAB2_ACTION)) {
			if (floatingButton2 != null) fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
		} else if (prefs.contains(FAB3_ENABLED)) {
			updateTertiaryFabVisibility();
		} else if (prefs.contains(FAB3_ACTION)) {
			if (floatingButton3 != null) fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
		} else if (prefs.contains(FAB_DRAGGABLE)) {
			updateFabDraggable();
		}
	}

	private void updateFabDraggable() {
		boolean draggable = getPrefs().getBooleanPref(FAB_DRAGGABLE);
		if (floatingButton != null) floatingButton.setDraggable(draggable);
		if (floatingButton2 != null) floatingButton2.setDraggable(draggable);
		if (floatingButton3 != null) floatingButton3.setDraggable(draggable);

		// Previously a dragged FAB only snapped back to its default layout position on the next app
		// restart (a fresh Activity/View never picked up the leftover drag translation to begin
		// with). Reset it live the moment dragging is turned off instead.
		if (!draggable) {
			resetFabPosition(floatingButton);
			resetFabPosition(floatingButton2);
			resetFabPosition(floatingButton3);
		}
	}

	private static void resetFabPosition(@Nullable FloatingButton fb) {
		if (fb == null) return;
		fb.animate().translationX(0f).translationY(0f).setDuration(200L).start();
	}

	private void updateSecondaryFabVisibility() {
		if (floatingButton2 == null) return;
		if (!getPrefs().getBooleanPref(FAB2_ENABLED)) {
			floatingButton2.setVisibility(GONE);
			return;
		}

		if (isVideoMode()) {
			// Mirror the primary FAB's actual current visibility rather than independently deriving
			// it from isVideoMode() -- ControlPanelView.enableVideoMode() may have just hidden both
			// FABs until the user taps the screen (getStartDelay() == 0), and recomputing visibility
			// here from scratch would immediately clobber that, causing a brief flash on entry.
			floatingButton2.setVisibility(floatingButton.getVisibility());
		} else {
			floatingButton2.setVisibility(isWebBrowserActive() ? VISIBLE : GONE);
		}
	}

	private void updateTertiaryFabVisibility() {
		if (floatingButton3 == null) return;
		if (!getPrefs().getBooleanPref(FAB3_ENABLED)) {
			floatingButton3.setVisibility(GONE);
			return;
		}

		if (isVideoMode()) {
			floatingButton3.setVisibility(floatingButton.getVisibility());
		} else {
			floatingButton3.setVisibility(isWebBrowserActive() ? VISIBLE : GONE);
		}
	}

	// The web/YouTube browser addon is a video-adjacent context (fullscreen/mute/dim/play-pause
	// all make sense there) even before/without the app's own isVideoMode() becoming true, e.g.
	// while just browsing YouTube, not yet playing a video.
	private boolean isWebBrowserActive() {
		ActivityFragment f = getActiveFragment();
		if (f == null) return false;
		int id = f.getFragmentId();
		return (id == R.id.youtube_fragment) || (id == R.id.web_browser_fragment);
	}

	@Override
	public boolean onKeyDown(int code, KeyEvent event, IntObjectFunction<KeyEvent, Boolean> next) {
		return handleKeyEvent(this, event, next);
	}

	@Override
	public boolean onKeyUp(int code, KeyEvent event, IntObjectFunction<KeyEvent, Boolean> next) {
		return handleKeyEvent(this, event, next);
	}

	@Override
	public boolean onKeyLongPress(int code, KeyEvent event,
																IntObjectFunction<KeyEvent, Boolean> next) {
		return handleKeyEvent(this, event, next);
	}

	public HandlerExecutor getHandler() {
		return handler;
	}

	public Cancellable post(Runnable task) {
		return getHandler().submit(task);
	}

	public Cancellable postDelayed(Runnable task, long delay) {
		return getHandler().schedule(task, delay);
	}

	public Cancellable interruptPlayback() {
		MediaSessionCallback cb = getMediaSessionCallback();
		if (!cb.isPlaying()) return Cancellable.CANCELED;
		PlaybackStateCompat playbackState = cb.getPlaybackState();
		cb.onPause();
		return () -> {
			PlaybackStateCompat state = cb.getPlaybackState();
			if ((state.getState() == PlaybackStateCompat.STATE_PAUSED) &&
					((state == playbackState) || (state.getPosition() != playbackState.getPosition()))) {
				cb.onPlay();
			}
			return true;
		};
	}

	@Override
	protected FutureSupplier<Void> sendCrashReport(Throwable err) {
		return completedVoid();
	}

	static final class Prefs implements MainActivityPrefs {
		static final Prefs instance = new Prefs();
		private final List<ListenerRef<PreferenceStore.Listener>> listeners = new LinkedList<>();
		private final SharedPreferences prefs = FermataApplication.get().getDefaultSharedPreferences();

		private Prefs() {
			App.get().getHandler().post(this::migratePrefs);
		}

		private void migratePrefs() {
			// Rename old prefs
			var oldTheme = Pref.i("THEME", THEME_DARK);
			var oldScale = Pref.f("MEDIA_ITEM_SCALE", 1f);
			var fbLongPress = Pref.i("FB_LONG_PRESS", 0);
			var fbLongPressAA = Pref.i("FB_LONG_PRESS_AA", 0);
			var showClock = Pref.b("SHOW_CLOCK", false);
			var voiceCtrlM = Pref.b("VOICE_CONTROl_M", false);
			var theme = getIntPref(oldTheme);
			var scale = getFloatPref(oldScale);

			if ((theme != THEME_DARK) || (scale != 1f)) {
				try (PreferenceStore.Edit e = editPreferenceStore()) {
					if (theme != THEME_DARK) {
						e.setIntPref(THEME_MAIN, theme);
						if (AUTO) e.setIntPref(THEME_AA, theme);
						e.removePref(oldTheme);
					}
					if (scale != 1f) {
						e.setFloatPref(TEXT_ICON_SIZE, scale);
						if (AUTO) e.setFloatPref(TEXT_ICON_SIZE_AA, scale);
						e.removePref(oldScale);
					}
				}
			}

			if ((getIntPref(fbLongPress) == 1) || (getIntPref(fbLongPressAA) == 1)) {
				try (PreferenceStore.Edit e = editPreferenceStore()) {
					e.setBooleanPref(VOICE_CONTROl_ENABLED, true);
					e.setBooleanPref(VOICE_CONTROl_FB, true);
				}
			}

			if (getBooleanPref(showClock)) {
				try (PreferenceStore.Edit e = editPreferenceStore()) {
					e.removePref(showClock);
					e.setIntPref(CLOCK_POS, CLOCK_POS_RIGHT);
				}
			}

			if (getBooleanPref(voiceCtrlM)) {
				removePref(voiceCtrlM);
				var kp = Key.getPrefs();
				var o = Action.ACTIVATE_VOICE_CTRL.ordinal();
				kp.applyIntPref(Key.M.getLongActionPref(), o);
				kp.applyIntPref(Key.MENU.getLongActionPref(), o);
			}
		}

		@NonNull
		@Override
		public SharedPreferences getSharedPreferences() {
			return prefs;
		}

		@Override
		public Collection<ListenerRef<Listener>> getBroadcastEventListeners() {
			return listeners;
		}
	}

	private final class SpeechListener implements RecognitionListener {
		private final Promise<List<String>> promise;
		private final boolean textInput;
		private final SpeechRecognizer recognizer;
		private final MaterialTextView text;
		private PlaybackStateCompat playbackState;

		private SpeechListener(Promise<List<String>> promise, boolean textInput) {
			this.promise = promise;
			this.textInput = textInput;
			recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
			recognizer.setRecognitionListener(this);
			text = new MaterialTextView(getContext());
		}

		void start(Intent i) {
			var cb = getMediaSessionCallback();
			if (cb.isPlaying()) {
				var eng = cb.getEngine();
				if ((eng != null) && eng.canPause()) {
					cb.onPause();
					playbackState = cb.getPlaybackState();
				} else {
					playbackState = null;
				}
			} else {
				playbackState = null;
			}
			recognizer.startListening(i);
		}

		void destroy() {
			MediaSessionCallback cb = getMediaSessionCallback();
			PlaybackStateCompat state = cb.getPlaybackState();
			if ((playbackState != null) && (state.getState() == PlaybackStateCompat.STATE_PAUSED)) {
				if ((state == playbackState) || (state.getPosition() != playbackState.getPosition())) {
					cb.onPlay();
				}
			}
			playbackState = null;
			recognizer.destroy();
			promise.cancel();
			if (speechListener == this) speechListener = null;
		}

		@Override
		public void onReadyForSpeech(Bundle params) {
			getContextMenu().show(b -> {
				Context ctx = getContext();
				DisplayMetrics dm = getResources().getDisplayMetrics();
				int size = Math.min(dm.heightPixels, dm.widthPixels) / 3;
				LinearLayoutCompat layout = new LinearLayoutCompat(ctx);
				layout.setOrientation(LinearLayoutCompat.VERTICAL);
				AppCompatImageView img = new AppCompatImageView(ctx);
				TypedArray ta = ctx.getTheme()
						.obtainStyledAttributes(new int[]{com.google.android.material.R.attr.colorOnSecondary});
				int imgColor = ta.getColor(0, 0);
				ta.recycle();
				img.setMinimumWidth(size);
				img.setMinimumHeight(size);
				img.setImageResource(R.drawable.record_voice);
				img.setImageTintList(ColorStateList.valueOf(imgColor));
				text.setMaxLines(5);
				text.setGravity(Gravity.CENTER);
				text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
				ta = ctx.getTheme().obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
				text.setTextColor(ColorStateList.valueOf(ta.getColor(0, 0)));
				ta.recycle();
				text.setLayoutParams(new LinearLayoutCompat.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
				layout.setLayoutParams(new ConstraintLayout.LayoutParams(size, WRAP_CONTENT));
				b.setView(layout);
				b.setCloseHandlerHandler(m -> destroy());
				layout.addView(img);
				layout.addView(text);

				if (textInput) {
					int kbSize = size / 5;
					int margin = toIntPx(getContext(), 1);
					LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(kbSize, kbSize);
					AppCompatImageView kb = new AppCompatImageView(ctx);
					lp.gravity = Gravity.CENTER;
					lp.setMargins(0, margin, 0, margin);
					kb.setLayoutParams(lp);
					kb.setImageResource(R.drawable.keyboard);
					kb.setImageTintList(ColorStateList.valueOf(imgColor));
					layout.setOnClickListener(v -> {
						promise.completeExceptionally(new OperationCanceledException());
						hideActiveMenu();
					});
					layout.addView(kb);
				}
			});
		}

		@Override
		public void onResults(Bundle b) {
			List<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
			if ((r != null) && !r.isEmpty()) text.setText(r.get(0));
			postDelayed(MainActivityDelegate.this::hideActiveMenu, 1000);
			promise.complete(r);
		}

		@Override
		public void onPartialResults(Bundle b) {
			List<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
			if ((r != null) && !r.isEmpty()) text.setText(r.get(0));
		}

		@Override
		public void onError(int error) {
			String msg = "Speech recognition failed with error code " + error;
			Log.e(msg);
			promise.completeExceptionally(new IOException(msg));
			hideActiveMenu();

			if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
				showAlert(getContext(), R.string.err_no_audio_record_perm);
			}
		}

		@Override
		public void onBeginningOfSpeech() {
		}

		@Override
		public void onRmsChanged(float rmsdB) {
		}

		@Override
		public void onBufferReceived(byte[] buffer) {
		}

		@Override
		public void onEndOfSpeech() {
		}

		@Override
		public void onEvent(int eventType, Bundle params) {
		}
	}
}
