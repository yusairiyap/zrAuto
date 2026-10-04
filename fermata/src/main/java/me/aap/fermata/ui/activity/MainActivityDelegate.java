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
import static android.view.View.INVISIBLE;
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
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB4_ACTION;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB4_ENABLED;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB5_ACTION;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB5_ENABLED;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB6_ACTION;
import static me.aap.fermata.ui.activity.MainActivityPrefs.FAB6_ENABLED;
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.drawable.ColorDrawable;
import android.animation.ValueAnimator;
import android.Manifest;
import android.Manifest.permission;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
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
import android.view.animation.PathInterpolator;
import android.view.animation.Interpolator;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageView;

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
import androidx.fragment.app.FragmentManager;

import com.google.android.material.textview.MaterialTextView;

import java.io.IOException;
import java.lang.ref.WeakReference;
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
import me.aap.fermata.addon.music.MusicAddon;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.addon.music.MusicQueue;
import me.aap.fermata.addon.music.MusicTrackItem;
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
import me.aap.fermata.media.pref.MediaLibPrefs;
import me.aap.fermata.media.pref.PlaybackControlPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.media.service.MediaSessionCallbackAssistant;
import me.aap.fermata.media.service.PlaybackResume;
import me.aap.fermata.ytdl.YtOffline;
import me.aap.fermata.spotify.SpotifyAuth;
import me.aap.fermata.ui.fragment.AudioEffectsFragment;
import me.aap.fermata.ui.fragment.DiagnosticLogFragment;
import me.aap.fermata.ui.fragment.FavoritesFragment;
import me.aap.fermata.ui.fragment.FoldersFragment;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.fermata.ui.fragment.MediaLibFragment;
import me.aap.fermata.ui.fragment.NavBarMediator;
import me.aap.fermata.ui.fragment.PlaylistsFragment;
import me.aap.fermata.ui.fragment.SettingsFragment;
import me.aap.fermata.ui.fragment.SpotifyImportFragment;
import me.aap.fermata.ui.fragment.SubtitlesFragment;
import me.aap.fermata.ui.fragment.YoutubeAlternativesFragment;
import me.aap.fermata.ui.view.BodyLayout;
import me.aap.fermata.ui.view.ControlPanelView;
import me.aap.fermata.ui.view.DownloadPicker;
import me.aap.fermata.ui.view.FermataNavBarView;
import me.aap.fermata.ui.view.PlaylistPicker;
import me.aap.fermata.ui.view.ToolBarPill;
import me.aap.fermata.ui.view.QuaternaryFloatingButton;
import me.aap.fermata.ui.view.QuinaryFloatingButton;
import me.aap.fermata.ui.view.SenaryFloatingButton;
import me.aap.fermata.ui.view.SecondaryFloatingButton;
import me.aap.fermata.ui.view.TertiaryFloatingButton;
import me.aap.fermata.ui.view.VideoView;
import me.aap.fermata.util.DiagnosticLog;
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
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.activity.AppActivity;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.view.DialogBuilder;
import me.aap.utils.ui.view.FloatingButton;
import me.aap.utils.ui.view.NavBarView;
import me.aap.utils.ui.view.NavButtonView;
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
	/** How far the floating nav bar/control panel pill sits off the screen edges, in dp. */
	private static final int FLOATING_BAR_MARGIN = 12;
	/** tool_bar's padding inside its pill's rounded ends, in dp -- see ToolBarPill. */
	private static final int TOOL_BAR_INNER_PAD = 6;
	/** How far a web page (YouTube, the browser) reaches up under the tool bar pill, in dp. */
	private static final int WEB_UNDER_TOOL_BAR = 8;
	private static final long BARS_ANIM_MS = 260;
	// Hiding matches the control panel's own fade (ControlPanelView.FADE_DURATION) and starts
	// dimming from its very first frame: an ease-in curve here left the bar looking untouched for
	// its first ~100ms, so it read as fading late behind the control panel (most visibly when a
	// video goes fullscreen, which hides both at once).
	private static final long BARS_HIDE_MS = 200;
	private final NavBarMediator navBarMediator = new NavBarMediator();
	private final FermataServiceUiBinder mediaServiceBinder;
	private ToolBarView toolBar;
	private NavBarView navBar;
	private BodyLayout body;
	private ControlPanelView controlPanel;
	private FloatingButton floatingButton;
	private SecondaryFloatingButton floatingButton2;
	private TertiaryFloatingButton floatingButton3;
	private QuaternaryFloatingButton floatingButton4;
	private QuinaryFloatingButton floatingButton5;
	private SenaryFloatingButton floatingButton6;
	private ContentLoadingProgressBar progressBar;
	// See setOverlaysSuppressed().
	private boolean loadingSuppressed;
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

	// Whether the native Android Auto car Activity (MainCarActivity, auto flavor only -- not a
	// subclass of MainActivity, or of Activity at all) is currently alive. Static because the only
	// thing that needs it, FuelTracker, is a process-wide singleton that outlives any one Activity
	// and lives in the main source set, where MainCarActivity isn't even visible. Set/cleared from
	// this delegate's own create/destroy, which MainCarActivity forwards into just like the phone
	// Activity does. MainActivity#isCarActivity() is hardcoded false, so without this there is no
	// way at all outside the auto source set to tell "projected onto the car's screen" apart from
	// "just open on the phone".
	private static volatile boolean carActivityActive;

	public MainActivityDelegate(AppActivity activity, FermataServiceUiBinder binder) {
		super(activity);
		mediaServiceBinder = binder;
	}

	/** The native Android Auto UI while it's running, see {@link #getPlaybackDelegate()}. */
	private static WeakReference<MainActivityDelegate> carDelegate = new WeakReference<>(null);

	/** The native Android Auto UI, if it's running. */
	@Nullable
	public static MainActivityDelegate getCarDelegate() {
		MainActivityDelegate d = carDelegate.get();
		return ((d != null) && carActivityActive) ? d : null;
	}

	/**
	 * Where playback started from this UI should happen: the car's screen while Android Auto is
	 * connected (so a tap on the phone plays exactly as if tapped in the car -- one session, one
	 * player, one queue), else this UI itself. Local audio already goes through the shared media
	 * service either way; this matters for what plays inside a UI of its own, like the YouTube tab.
	 */
	public MainActivityDelegate getPlaybackDelegate() {
		if (getAppActivity().isCarActivity()) return this;
		MainActivityDelegate car = getCarDelegate();
		return (car != null) ? car : this;
	}

	/** Whether playback started from this UI goes to the car's screen, see above. */
	public boolean isPlaybackOnCar() {
		return getPlaybackDelegate() != this;
	}

	/**
	 * Plays an externally played item (a YouTube video, a Favorites/Playlist entry of one) in its
	 * player's tab -- on the car's screen while Android Auto is connected, see
	 * {@link #getPlaybackDelegate()}; then the phone just says so.
	 */
	public boolean playExternally(MediaLib.ExternallyPlayableItem ext, PlayableItem self) {
		MainActivityDelegate p = getPlaybackDelegate();
		// Downloaded, and no (usable) connection: plays from the file instead of the page.
		if (YtOffline.tryPlayLocal(p, self, 0)) return true;
		ActivityFragment f = p.showFragment(ext.getPlayerFragmentId());
		if (f == null) return false;
		ext.loadInFragment(f, self);
		if (p != this) UiUtils.showToast(getContext(), R.string.playing_on_car, self.getName());
		return true;
	}

	/** See {@link #carActivityActive} -- true while the app is running as the native Android Auto
	 * car Activity (mirroring mode is a separate check, see {@code FermataApplication#isMirroringMode}). */
	public static boolean isCarActivityActive() {
		return carActivityActive;
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
		if (getAppActivity().isCarActivity()) {
			carActivityActive = true;
			carDelegate = new WeakReference<>(this);
		}
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

		if (SpotifyAuth.isCallback(u)) {
			SpotifyImportFragment.handleAuthCallback(this, u);
			return completed(true);
		}

		if ((u != null) && "zrauto".equals(u.getScheme()) &&
				"spotify-import".equals(u.getHost())) {
			// From the Spotify import's progress notification.
			SpotifyImportFragment.open(this);
			return completed(true);
		}

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

		FutureSupplier<Boolean> f = resumeLastPlayed().then(resumed ->
				Boolean.TRUE.equals(resumed) ? completed(true) : goToCurrent()).onCompletion((ok, fail1) -> {
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

	/**
	 * On a fresh start, when the last thing played was a YouTube video or a Music tab track: brings
	 * it back where it was left off, loaded but not playing -- the video in the YouTube tab (see
	 * {@link PlaybackResume}), the track in the Music tab (which shows where its queue left off).
	 * Completes with false if there's nothing like that to resume, for the usual last library item.
	 */
	private FutureSupplier<Boolean> resumeLastPlayed() {
		if (getMediaServiceBinder().getCurrentItem() != null) return completed(false);
		MediaLibPrefs prefs = getLib().getPrefs();
		String id = prefs.getResumeExtItemPref();
		if (id == null) return completed(false);
		long pos = prefs.getResumeExtPosPref();

		return getLib().getItem(id).main().map(i -> {
			if (i instanceof MusicTrackItem) {
				if (MusicAddon.get() == null) return false;
				showFragment(R.id.music_addon);
				return true;
			}
			if (i instanceof MediaLib.ExternallyPlayableItem ext) {
				String origId = ext.getOrigId();
				if (origId != null) PlaybackResume.set(origId, pos, true);
				ActivityFragment f = showFragment(ext.getPlayerFragmentId());
				if (f == null) return false;
				ext.loadInFragment(f, ext);
				return true;
			}
			return false;
		}).ifFail(err -> {
			Log.e(err, "Failed to resume ", id);
			return false;
		});
	}

	/** See {@link MediaSessionCallbackAssistant#playExternal}. */
	@Override
	public boolean playExternal(PlayableItem i, long pos) {
		if (i instanceof MusicTrackItem t) {
			MusicPlayer.playTrack(this, t, pos);
			return true;
		}
		if (i instanceof MediaLib.ExternallyPlayableItem ext) {
			if (YtOffline.tryPlayLocal(this, ext, pos)) return true;
			String origId = ext.getOrigId();
			if (origId != null) PlaybackResume.set(origId, pos, false);
			ActivityFragment f = showFragment(ext.getPlayerFragmentId());
			if (f == null) return false;
			ext.loadInFragment(f, ext);
			return true;
		}
		return false;
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
	public void uncaughtException(@NonNull Thread t, @NonNull Throwable err) {
		// super.setUncaughtExceptionHandler() (above) installs this instance itself as the process's
		// default handler on every Activity create -- replacing whatever was set before, including
		// DiagnosticLog.installCrashHandler()'s own hook from Application.onCreate(). Without this,
		// a crash on any real (non-CarActivity) screen -- which is exactly where Settings/Diagnostics
		// live -- would never make it into the diagnostic log at all.
		DiagnosticLog.logCrash(t, err);
		super.uncaughtException(t, err);
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

		// An Android Auto display takeover (e.g. a car's camera overlay briefly taking the screen)
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
		if (getAppActivity().isCarActivity()) {
			carActivityActive = false;
			if (carDelegate.get() == this) carDelegate = new WeakReference<>(null);
		}
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

	@Override
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
	 * Toggles the system status/navigation bars <em>and</em> the app's own tool/nav bar visible or
	 * hidden during active video playback, without leaving {@link BodyLayout.Mode#VIDEO}/
	 * {@link BodyLayout.Mode#BOTH} -- the local-video equivalent of a WebView-hosted player's
	 * {@link VideoView#toggleNativeFullscreen()}, used as
	 * {@link me.aap.fermata.action.Action#FULLSCREEN_TOGGLE}'s fallback when there's no such native
	 * handler to defer to (so this never runs for YouTube, whose own fullscreen chrome/behavior is
	 * untouched).
	 */
	public void toggleVideoBars() {
		videoBarsShown = !videoBarsShown;
		setSystemUiVisibility();
		setBarsHidden(!videoBarsShown);
		ControlPanelView cp = getControlPanel();
		if (cp != null) cp.refreshShowHideBarsIcon();
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
	public QuaternaryFloatingButton getFloatingButton4() {
		return floatingButton4;
	}

	@Nullable
	public QuinaryFloatingButton getFloatingButton5() {
		return floatingButton5;
	}

	@Nullable
	public SenaryFloatingButton getFloatingButton6() {
		return floatingButton6;
	}

	/** FAB2 to FAB6, in order -- any may be null (not in this layout). */
	public FloatingButton[] getExtraFloatingButtons() {
		return new FloatingButton[]{floatingButton2, floatingButton3, floatingButton4, floatingButton5,
				floatingButton6};
	}

	/**
	 * The extra floating buttons the user has turned on (shown and hidden along with the primary
	 * one over video).
	 */
	public List<View> getEnabledExtraFabs() {
		FloatingButton[] fabs = getExtraFloatingButtons();
		Pref<BooleanSupplier>[] on = extraFabEnabledPrefs();
		List<View> l = new ArrayList<>(fabs.length);
		for (int i = 0; i < fabs.length; i++) {
			if ((fabs[i] != null) && getPrefs().getBooleanPref(MainActivityPrefs.fab(this, on[i]))) {
				l.add(fabs[i]);
			}
		}
		return l;
	}

	@SuppressWarnings("unchecked")
	private static Pref<BooleanSupplier>[] extraFabEnabledPrefs() {
		return new Pref[]{FAB2_ENABLED, FAB3_ENABLED, FAB4_ENABLED, FAB5_ENABLED, FAB6_ENABLED};
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
			ToolBarView tb = getToolBar();
			if (tb.getMediator() != ToolBarView.Mediator.Invisible.instance) {
				animateBar(tb, !barsHidden, 0, -tb.getBottom());
			}
			animateNavBar(!barsHidden);
			syncSideNavInset();
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

		// Set before cp.enableVideoMode() runs, not after -- that method reads getActiveVideoView()
		// (to tell local playback from a web-embedded source like YouTube), and it would otherwise
		// still see whatever was active *before* this call, e.g. a still-stale YoutubeVideoView from
		// the previous video, right when a local file is what's actually starting now.
		if (v != null) {
			activeVideoView = v;
			MainActivityPrefs dimPrefs = getPrefs();
			v.setDimOverlay(videoMode && dimPrefs.getBooleanPref(DIM_ENABLED), dimPrefs.getIntPref(DIM_OPACITY),
					dimPrefs.resolveDimColor());
		}

		if (videoMode) {
			this.videoMode = true;
			cancelVideoExitFade();
			// Came from the Music tab's Video: lift the black now that the video is taking over.
			ColorDrawable sf = videoSwitchFade;
			if (sf != null) getHandler().postDelayed(() -> releaseVideoSwitchFade(sf), 150);
			setSystemUiVisibility();
			keepScreenOn(true);
			cp.enableVideoMode();
		} else {
			this.videoMode = false;
			fadeInFromVideo();
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

		updateExtraFabsVisibility();
		fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
	}

	/**
	 * Makes body_layout fill the whole screen, with tool_bar and control_panel floating over the
	 * top/bottom of it as translucent gradient scrims instead of squeezing it into the strip
	 * between them -- the same technique fullscreen video playback already used, now applied
	 * everywhere (video mode included) so every tab renders behind the bars, for a cleaner look
	 * with more of the screen visible, especially on Android Auto.
	 * <p>
	 * nav_bar and control_panel float over it as a pill -- see {@link #enableFloatingBars}. Both,
	 * and the floating_bars view that paints the pill, are declared after body_layout in every
	 * layout variant (bottom, left and right), so plain view-drawing order alone -- with no extra
	 * elevation needed -- puts them on top of body_layout's now-larger bounds.
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

		// tool_bar floats as a pill too, in the nav bar pill's colour (see FloatingBarsView), instead
		// of a full-width scrim across the top.
		ToolBarView tbv = getToolBar();
		int[] nbc = NavBarView.resolveStyleColors(getContext());
		ToolBarPill.apply(tbv, (nbc[1] & 0x00FFFFFF) | 0xF2000000,
				toIntPx(getContext(), TOOL_BAR_INNER_PAD));
		// Its buttons in the nav bar's icon colour, so the two bars' icons match.
		if ((nbc[0] >>> 24) != 0) tbv.setIconTint(ColorStateList.valueOf(nbc[0]));
		enableFloatingBars();
	}

	/**
	 * Detaches nav_bar and control_panel from the screen edges so they float as a pill (painted
	 * behind them by {@link me.aap.fermata.ui.view.FloatingBarsView}), and lets the tab content
	 * run behind a side nav bar too, not only a bottom one.
	 * <p>
	 * Bottom nav bar: both bars share the same side margins, and control_panel sits directly on
	 * top of nav_bar, so together they read as one two-row pill. When nav_bar is hidden (bars
	 * hidden, e.g. over a video), control_panel's gone-margin keeps it floating off the bottom edge.
	 * <p>
	 * Left/right nav bar: nav_bar is a vertical pill along its side and control_panel a separate
	 * pill along the bottom of the remaining width -- both still follow the one nav-bar position
	 * setting. body_layout now spans the full width behind the side pill; its own horizontal
	 * padding ({@link #syncSideNavInset}) keeps the tab content itself clear of the pill.
	 */
	private void enableFloatingBars() {
		View body = findViewById(R.id.body_layout);
		View cp = findViewById(R.id.control_panel);
		View tb = findViewById(R.id.tool_bar);
		View bars = findViewById(R.id.floating_bars);
		if (!(navBar instanceof FermataNavBarView nb) || (body == null) || (cp == null)
				|| (tb == null) || (bars == null)) return;
		if (!(body.getLayoutParams() instanceof ConstraintLayout.LayoutParams blp)
				|| !(nb.getLayoutParams() instanceof ConstraintLayout.LayoutParams nlp)
				|| !(cp.getLayoutParams() instanceof ConstraintLayout.LayoutParams clp)
				|| !(tb.getLayoutParams() instanceof ConstraintLayout.LayoutParams tlp)) return;

		// The pill (and the nav bar on it) are raised to tool_bar's own elevation -- tied, so the
		// layout's declaration order decides, and both come after tool_bar -- so a side pill draws
		// over tool_bar's scrim where they meet (tool_bar spans the full width now, see below)
		// instead of tool_bar's scrim ending in a hard notch next to the pill. No outline shadows:
		// the pill paints its own.
		float z = tb.getElevation();
		bars.setOutlineProvider(null);
		bars.setElevation(z);
		nb.setOutlineProvider(null);
		nb.setElevation(z);

		int m = toIntPx(getContext(), FLOATING_BAR_MARGIN);
		int pos = getPrefs().getNavBarPosPref(this);

		// tool_bar floats off the top and the sides like the other bars (with a side nav bar, its
		// padding keeps its pill clear of that one, see syncToolBarInset()).
		setHorizontalMargins(tlp, m, m);
		tlp.topMargin = m;
		tb.setLayoutParams(tlp);

		if (pos == NavBarView.POSITION_BOTTOM) {
			setHorizontalMargins(nlp, m, m);
			nlp.bottomMargin = m;
			setHorizontalMargins(clp, m, m);
			clp.width = 0;
			clp.goneBottomMargin = m;
		} else {
			nlp.topMargin = m;
			nlp.bottomMargin = m;
			// body_layout and tool_bar both span the full width, behind the pill; each is padded
			// clear of it (see syncSideNavInset()).
			if (pos == NavBarView.POSITION_LEFT) {
				setHorizontalMargins(nlp, m, 0);
				blp.startToEnd = UNSET;
				blp.startToStart = PARENT_ID;
				tlp.startToEnd = UNSET;
				tlp.startToStart = PARENT_ID;
				clp.goneStartMargin = m;
			} else {
				setHorizontalMargins(nlp, 0, m);
				blp.endToStart = UNSET;
				blp.endToEnd = PARENT_ID;
				tlp.endToStart = UNSET;
				tlp.endToEnd = PARENT_ID;
				clp.goneEndMargin = m;
			}
			setHorizontalMargins(clp, m, m);
			clp.bottomMargin = m;
			body.setLayoutParams(blp);
			tb.setLayoutParams(tlp);
			// Every tab is padded clear of the side pill, but a tab may still extend its own
			// background out under it (see MainActivityFragment#drawsBehindSideNavBar): don't clip
			// that at body_layout's padding, nor at the containers in between.
			if (body instanceof ViewGroup bg) {
				// Both: clipChildren would still clip each child to its own (padded) bounds.
				bg.setClipChildren(false);
				bg.setClipToPadding(false);
				for (int id : new int[]{R.id.swiperefresh, R.id.frame_layout}) {
					View c = bg.findViewById(id);
					if (c instanceof ViewGroup g) {
						g.setClipChildren(false);
						g.setClipToPadding(false);
					}
				}
			}
		}

		nb.setLayoutParams(nlp);
		cp.setLayoutParams(clp);
		nb.matchConstraints();

		// tool_bar's own padding follows the side pill's place as the bars are laid out.
		View.OnLayoutChangeListener sync = (v, l, t, r, b, ol, ot, or, ob) -> syncToolBarInset();
		nb.addOnLayoutChangeListener(sync);
		tb.addOnLayoutChangeListener(sync);
		syncToolBarInset();
	}

	/**
	 * The status bar in the app's own background colour instead of the theme's darker
	 * colorPrimaryDark, so it blends seamlessly into the screen below it -- the content fades into
	 * that same colour at the top (see BodyLayout#drawTopFade). Its icons stay light or dark as the
	 * theme's windowLightStatusBar already sets them. Not on the car screen, whose status bar
	 * belongs to the car.
	 */
	private void matchStatusBarToBackground() {
		if (isCarActivity()) return;
		TypedArray ta = getContext().obtainStyledAttributes(
				new int[]{android.R.attr.colorBackground});
		int bg = ta.getColor(0, 0);
		ta.recycle();
		if ((bg >>> 24) == 0) return;
		getWindow().setStatusBarColor(bg | 0xFF000000);
		// From Android 15 the window is drawn edge to edge: the status bar shows through to
		// main_activity (padded clear of it, see init()), whose own background -- colorPrimary, the
		// nav bar's colour -- was what showed there instead of the colour set just above.
		View root = findViewById(R.id.main_activity);
		if (root != null) root.setBackgroundColor(bg | 0xFF000000);
	}

	private static void setHorizontalMargins(ConstraintLayout.LayoutParams lp, int start, int end) {
		// Every main_activity layout is forced LTR, so start/end and left/right are the same thing.
		lp.setMarginStart(start);
		lp.setMarginEnd(end);
		lp.leftMargin = start;
		lp.rightMargin = end;
	}

	/**
	 * With a side nav bar, body_layout spans the full width behind the floating pill (so the
	 * background and the fade continue under it), but the tab content itself is kept clear of it
	 * via body_layout's horizontal padding, sized to how far the pill actually reaches into
	 * body_layout -- or none at all while the bars are hidden (e.g. fullscreen video). Nothing in
	 * a tab scrolls horizontally, so unlike a bottom bar there is nothing to scroll underneath it.
	 * <p>
	 * Deliberately never animated, and switched the moment the bars are hidden/shown rather than
	 * once the nav bar's fade has finished: each padding change resizes the whole tab, and
	 * YouTube's player restarts (and may pause, or miss going fullscreen on the next video) on
	 * every resize of its WebView -- one resize, at the same moment the old side-by-side layout
	 * resized it, is the only thing it copes with well.
	 */
	private void syncSideNavInset() {
		BodyLayout b = body;
		NavBarView nb = navBar;
		if ((b == null) || (nb == null)) return;
		int left = 0;
		int right = 0;

		if (isSideNavShown(nb)) {
			int gap = toIntPx(getContext(), FLOATING_BAR_MARGIN);
			if (nb.isLeft()) left = Math.max(0, nb.getRight() - b.getLeft() + gap);
			else right = Math.max(0, b.getRight() - nb.getLeft() + gap);
		}

		syncToolBarInset();
		if ((b.getPaddingLeft() == left) && (b.getPaddingRight() == right)) return;
		b.setPadding(left, b.getPaddingTop(), right, b.getPaddingBottom());
	}

	/**
	 * tool_bar's horizontal padding: a little room inside its pill's rounded ends, plus, with a
	 * side nav bar showing, whatever keeps its pill (and so its buttons and title) clear of the nav
	 * bar's own -- the pill is drawn inside that extra padding, see ToolBarPill.
	 */
	private void syncToolBarInset() {
		ToolBarView tb = toolBar;
		NavBarView nb = navBar;
		if ((tb == null) || (nb == null)) return;
		int inner = toIntPx(getContext(), TOOL_BAR_INNER_PAD);
		int left = inner;
		int right = inner;

		if (isSideNavShown(nb)) {
			int gap = toIntPx(getContext(), FLOATING_BAR_MARGIN);
			if (nb.isLeft()) left += Math.max(0, nb.getRight() + gap - tb.getLeft());
			else right += Math.max(0, tb.getRight() - (nb.getLeft() - gap));
		}

		syncToolBarHeight(tb, nb);
		if ((tb.getPaddingLeft() == left) && (tb.getPaddingRight() == right)) return;
		tb.setPadding(left, tb.getPaddingTop(), right, tb.getPaddingBottom());
		tb.invalidateOutline();
		tb.invalidate();
	}

	/**
	 * Next to a side nav bar (tablet, landscape, the car): the tool bar pill as thick as the nav
	 * bar's, unless its own size setting makes it thicker still, with its buttons padded down to
	 * draw their icons the same size as the nav bar's. With a bottom nav bar, a compact pill.
	 */
	private void syncToolBarHeight(ToolBarView tb, NavBarView nb) {
		// The tool bar buttons' own padding, see ToolBarView.Mediator#setButtonPadding.
		int btnPad = toIntPx(getContext(), Math.round(10 * getToolBarSize()));
		if (nb.isBottom()) {
			// A phone in portrait: the two bars don't sit side by side, and a tool bar as tall as the
			// bottom nav bar (with its labels) looks oversized. A compact pill with standard 24dp icons.
			int scale = Math.round(getToolBarSize() * 100);
			int h = toIntPx(getContext(), 56 * scale / 100);
			int icon = toIntPx(getContext(), 24 * scale / 100);
			tb.setMinBarHeight(h, Math.max(0, (h - (icon + 2 * btnPad)) / 2));
			return;
		}
		if ((nb.getVisibility() != VISIBLE) || !nb.isLaidOut()) return;
		int thick = nb.getWidth();
		if (thick <= 0) return;
		int icon = navIconSize(nb);
		int vPad = (icon > 0) ? Math.max(0, (thick - (icon + 2 * btnPad)) / 2) : 0;
		tb.setMinBarHeight(thick, vPad);
	}

	/** How big the nav bar draws its icons (fit into each button, less its padding), 0 if unknown. */
	private static int navIconSize(NavBarView nb) {
		int max = 0;
		for (int i = 0, n = nb.getChildCount(); i < n; i++) {
			if (!(nb.getChildAt(i) instanceof NavButtonView b)) continue;
			ImageView img = b.getIcon();
			int w = img.getWidth() - img.getPaddingLeft() - img.getPaddingRight();
			int h = img.getHeight() - img.getPaddingTop() - img.getPaddingBottom();
			max = Math.max(max, Math.min(w, h));
		}
		return max;
	}

	/**
	 * Whether a side nav bar counts as taking up room: going by the bars' hidden state rather than
	 * the nav bar's own visibility, which lags behind it by its fade (see animateNavBar()).
	 */
	private boolean isSideNavShown(NavBarView nb) {
		return !nb.isBottom() && !barsHidden && (nb.getVisibility() == VISIBLE) && (nb.getWidth() > 0);
	}

	/**
	 * The left and right padding {@code content} needs, right now, to clear a side nav bar's
	 * floating pill, into {@code out[0]} and {@code out[1]} -- for a tab that
	 * {@link MainActivityFragment#drawsBehindSideNavBar() draws behind it}. Both zero with a bottom
	 * or hidden nav bar. False if it can't tell yet (not attached).
	 */
	public boolean computeSideInsets(View content, int[] out) {
		out[0] = out[1] = 0;
		if (!content.isAttachedToWindow()) return false;
		NavBarView nb = navBar;
		if ((nb == null) || !isSideNavShown(nb)) return true;

		int gap = toIntPx(getContext(), FLOATING_BAR_MARGIN);
		content.getLocationOnScreen(insetLoc1);
		int contentLeft = insetLoc1[0];
		int contentRight = contentLeft + content.getWidth();
		nb.getLocationOnScreen(insetLoc2);
		if (nb.isLeft()) out[0] = Math.max(0, insetLoc2[0] + nb.getWidth() + gap - contentLeft);
		else out[1] = Math.max(0, contentRight - insetLoc2[0] + gap);
		return true;
	}

	@Nullable
	private ColorDrawable videoExitFade;

	/**
	 * Leaving fullscreen video relayouts the whole screen at once (bars back, the video pane
	 * shrinking or going, tab content resizing, system bars returning). Covers the whole window
	 * with black -- the colour fullscreen video sits on -- the instant that starts, and fades it
	 * out once things have had a moment to settle, so the switch reads as one smooth fade rather
	 * than a series of jumps.
	 */
	private void fadeInFromVideo() {
		View decor = getWindow().getDecorView();
		if (!decor.isLaidOut() || (decor.getWidth() == 0)) return;
		ColorDrawable prev = videoExitFade;
		if (prev != null) decor.getOverlay().remove(prev);

		ColorDrawable d = new ColorDrawable(Color.BLACK);
		d.setBounds(0, 0, decor.getWidth(), decor.getHeight());
		decor.getOverlay().add(d);
		fadeOutOverlay(decor, d, 255, 120);
	}

	@Nullable
	private ColorDrawable videoSwitchFade;

	/**
	 * The other way round from {@link #fadeInFromVideo()}: switching from the Music tab to its
	 * video (the tab change, the video going fullscreen, bars and system bars going away) fades the
	 * whole window to black first, and fades back in once the video has taken over (see
	 * {@link #setVideoMode}) -- the same smooth fade as leaving fullscreen, instead of a series of
	 * jumps. Lifts by itself after a moment should the video not show up.
	 */
	public void fadeToBlackForVideo() {
		View decor = getWindow().getDecorView();
		if (!decor.isLaidOut() || (decor.getWidth() == 0)) return;
		cancelVideoExitFade();
		ColorDrawable prev = videoSwitchFade;
		if (prev != null) decor.getOverlay().remove(prev);

		ColorDrawable d = new ColorDrawable(Color.BLACK);
		d.setBounds(0, 0, decor.getWidth(), decor.getHeight());
		d.setAlpha(0);
		videoSwitchFade = d;
		decor.getOverlay().add(d);

		ValueAnimator anim = ValueAnimator.ofInt(0, 255);
		anim.setDuration(200);
		anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
		anim.addUpdateListener(v -> {
			if (videoSwitchFade != d) {
				v.cancel();
				return;
			}
			d.setAlpha((int) v.getAnimatedValue());
			decor.invalidate();
		});
		anim.start();
		getHandler().postDelayed(() -> releaseVideoSwitchFade(d), 2000);
	}

	private void releaseVideoSwitchFade(ColorDrawable d) {
		if (videoSwitchFade != d) return;
		videoSwitchFade = null;
		fadeOutOverlay(getWindow().getDecorView(), d, d.getAlpha(), 0);
	}

	/** Fades {@code d}, already on the window's overlay, out from {@code from} and removes it. */
	private void fadeOutOverlay(View decor, ColorDrawable d, int from, long delay) {
		videoExitFade = d;
		ValueAnimator anim = ValueAnimator.ofInt(from, 0);
		anim.setStartDelay(delay);
		anim.setDuration(320);
		anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
		anim.addUpdateListener(v -> {
			if (videoExitFade != d) {
				v.cancel();
				return;
			}
			d.setAlpha((int) v.getAnimatedValue());
			decor.invalidate();
		});
		anim.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				decor.getOverlay().remove(d);
				if (videoExitFade == d) videoExitFade = null;
			}
		});
		anim.start();
	}

	/** Back into video before the exit fade finished: drop it, it'd only dim the new video. */
	private void cancelVideoExitFade() {
		ColorDrawable d = videoExitFade;
		if (d == null) return;
		videoExitFade = null;
		getWindow().getDecorView().getOverlay().remove(d);
	}

	/**
	 * Shows or hides the nav bar with a fade instead of a snap. The bar stays genuinely VISIBLE
	 * (and laid out) until its fade-out ends, so nothing laid out around it -- the content's
	 * insets -- sees it go and come back mid-way. (body_layout's side padding goes by the bars'
	 * hidden state instead, and switches at once, see syncSideNavInset().)
	 * <p>
	 * A bottom nav bar sharing its pill with the control panel: the control panel (and the floating
	 * buttons sitting on it) move down into the nav bar's place as it fades, so the pill shrinks
	 * smoothly from the top, and move up out of its way when it comes back.
	 */
	private void animateNavBar(boolean show) {
		NavBarView nb = navBar;
		if (nb == null) return;
		// Just a fade, wherever the bar is: at the bottom it fades out of its pill row; on a side
		// the tab content takes its room at once (see syncSideNavInset()), and the pill fading over
		// it is smoother than one sliding across it.
		float outX = 0f;

		if (show) {
			// Also brings back anything a hide cut short had already moved down part of the way.
			glideAfterLayout(controlPanel, floatingButton, floatingButton2, floatingButton3,
					floatingButton4, floatingButton5, floatingButton6);
			animateBar(nb, true, outX, 0);
			return;
		}

		if (nb.getVisibility() != VISIBLE) return;
		ControlPanelView cp = controlPanel;
		boolean moveDown = nb.isBottom() && (cp != null) && (cp.getVisibility() == VISIBLE)
				&& nb.isLaidOut();
		View[] followers = {cp, floatingButton, floatingButton2, floatingButton3, floatingButton4,
				floatingButton5, floatingButton6};

		if (moveDown && (cp.getParent() instanceof View parent)
				&& (cp.getLayoutParams() instanceof ConstraintLayout.LayoutParams clp)) {
			// Once the nav bar is GONE, the control panel's bottom anchor becomes the bottom edge
			// (less its gone-margin); the floating buttons sit on top of the control panel.
			float dy = (parent.getHeight() - parent.getPaddingBottom() - clp.goneBottomMargin)
					- cp.getBottom();
			if (dy > 0f) {
				for (View v : followers) {
					if ((v != null) && (v.getVisibility() == VISIBLE)) slideBy(v, 0, dy);
				}
			}
		}

		animateBar(nb, false, outX, 0, () -> {
			if (moveDown) {
				// Laid out in its new place from this same frame on: drop the offset.
				for (View v : followers) {
					if (v != null) settleSlide(v);
				}
			}
			refreshContentInsets();
		});
	}

	private void animateBar(View v, boolean show, float outX, float outY) {
		animateBar(v, show, outX, outY, null);
	}

	/**
	 * Fades/slides {@code v} in (from {@code outX}/{@code outY}) or out (to them), and only makes it
	 * GONE at the end of the hide -- unless the bars were shown again meanwhile.
	 */
	private void animateBar(View v, boolean show, float outX, float outY,
													@Nullable Runnable onHidden) {
		ViewPropertyAnimator anim = v.animate();
		anim.cancel();

		if (show) {
			if (v.getVisibility() != VISIBLE) {
				v.setAlpha(0f);
				v.setTranslationX(outX);
				v.setTranslationY(outY);
				v.setVisibility(VISIBLE);
			}
			anim.alpha(1f).translationX(0f).translationY(0f).setDuration(BARS_ANIM_MS)
					.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
			return;
		}

		if (v.getVisibility() != VISIBLE) return;
		if (!v.isLaidOut() || !v.isAttachedToWindow()) {
			v.setVisibility(GONE);
			if (onHidden != null) onHidden.run();
			return;
		}

		anim.alpha(0f).translationX(outX).translationY(outY).setDuration(BARS_HIDE_MS)
				.setInterpolator(new PathInterpolator(0f, 0f, 0.2f, 1f)).withEndAction(() -> {
					if (barsHidden) v.setVisibility(GONE);
					v.setAlpha(1f);
					v.setTranslationX(0f);
					v.setTranslationY(0f);
					if (onHidden != null) onHidden.run();
				}).start();
	}

	/**
	 * Lets each of {@code views} glide from where it is now to wherever the next layout pass puts
	 * it, rather than jumping there. Runs its own translation animator per view (see
	 * {@link #slideBy}), so it never touches a view's {@code animate()} -- which the floating
	 * buttons use for their own press/release scaling, and cancelling that would leave them stuck
	 * enlarged -- and never suppresses the parent's layout the way ChangeBounds would.
	 */
	public void glideAfterLayout(View... views) {
		View rootView = findViewById(R.id.main_activity);
		if ((rootView == null) || !rootView.isLaidOut()) return;
		int n = views.length;
		float[] oldX = new float[n];
		float[] oldY = new float[n];
		boolean[] wasShown = new boolean[n];

		for (int i = 0; i < n; i++) {
			View v = views[i];
			if (v == null) continue;
			wasShown[i] = (v.getVisibility() == VISIBLE) && (v.getWidth() > 0);
			oldX[i] = v.getLeft() + slideBase(v, true) + slideOffset(v, true);
			oldY[i] = v.getTop() + slideBase(v, false) + slideOffset(v, false);
		}

		ViewTreeObserver vto = rootView.getViewTreeObserver();
		vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
			@Override
			public boolean onPreDraw() {
				ViewTreeObserver o = rootView.getViewTreeObserver();
				if (o.isAlive()) o.removeOnPreDrawListener(this);

				for (int i = 0; i < n; i++) {
					View v = views[i];
					if ((v == null) || !wasShown[i] || (v.getVisibility() != VISIBLE)) continue;
					float dx = oldX[i] - (v.getLeft() + slideBase(v, true));
					float dy = oldY[i] - (v.getTop() + slideBase(v, false));
					if ((Math.abs(dx) < 1f) && (Math.abs(dy) < 1f)) continue;
					startSlide(v, dx, dy, 0f, 0f, BARS_ANIM_MS,
							new PathInterpolator(0.2f, 0f, 0f, 1f));
				}

				return true;
			}
		});
	}

	/** {@link #glideAfterLayout} for the floating buttons, e.g. around the control panel. */
	public void glideFabsAfterLayout() {
		glideAfterLayout(floatingButton, floatingButton2, floatingButton3, floatingButton4,
				floatingButton5, floatingButton6);
	}

	/** Slides {@code v} by (dx, dy) away from its resting translation, animated. */
	private static void slideBy(View v, float dx, float dy) {
		// Only while the nav bar fades out: in step with it, see BARS_HIDE_MS.
		startSlide(v, slideOffset(v, true), slideOffset(v, false), dx, dy, BARS_HIDE_MS,
				new PathInterpolator(0f, 0f, 0.2f, 1f));
	}

	/** Ends any slide on {@code v}, putting it straight back at its resting translation. */
	private static void settleSlide(View v) {
		if (!(v.getTag(R.id.floating_bars) instanceof Slide s)) return;
		v.setTag(R.id.floating_bars, null);
		s.anim.cancel();
		v.setTranslationX(s.baseX);
		v.setTranslationY(s.baseY);
	}

	/**
	 * Animates {@code v}'s offset from its resting translation (whatever it had before any slide of
	 * ours began, e.g. where a floating button was dragged to) from one value to another. Keeps
	 * the resting translation and the running animator in the view's tag.
	 */
	private static void startSlide(View v, float fromDx, float fromDy, float toDx, float toDy,
																 long duration, Interpolator interpolator) {
		Slide s;
		if (v.getTag(R.id.floating_bars) instanceof Slide prev) {
			prev.anim.cancel();
			s = new Slide(prev.baseX, prev.baseY);
		} else {
			s = new Slide(v.getTranslationX(), v.getTranslationY());
		}

		ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
		a.setDuration(duration);
		a.setInterpolator(interpolator);
		a.addUpdateListener(va -> {
			float f = (float) va.getAnimatedValue();
			s.dx = fromDx + (toDx - fromDx) * f;
			s.dy = fromDy + (toDy - fromDy) * f;
			v.setTranslationX(s.baseX + s.dx);
			v.setTranslationY(s.baseY + s.dy);
			if ((f >= 1f) && (s.dx == 0f) && (s.dy == 0f) && (v.getTag(R.id.floating_bars) == s)) {
				v.setTag(R.id.floating_bars, null);
			}
		});
		s.anim = a;
		v.setTag(R.id.floating_bars, s);
		s.dx = fromDx;
		s.dy = fromDy;
		v.setTranslationX(s.baseX + fromDx);
		v.setTranslationY(s.baseY + fromDy);
		a.start();
	}

	private static float slideBase(View v, boolean x) {
		if (v.getTag(R.id.floating_bars) instanceof Slide s) return x ? s.baseX : s.baseY;
		return x ? v.getTranslationX() : v.getTranslationY();
	}

	private static float slideOffset(View v, boolean x) {
		if (v.getTag(R.id.floating_bars) instanceof Slide s) return x ? s.dx : s.dy;
		return 0f;
	}

	/** A running slide of a view: its resting translation, current offset, and animator. */
	private static final class Slide {
		final float baseX;
		final float baseY;
		float dx;
		float dy;
		ValueAnimator anim;

		Slide(float baseX, float baseY) {
			this.baseX = baseX;
			this.baseY = baseY;
		}
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
	@Override
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
	private final int[] insetOut = new int[2];

	private void applyContentInsets(ViewGroup content) {
		if (!computeContentInsets(content, insetOut)) return;
		int top = insetOut[0];
		int bottom = insetOut[1];
		if ((content.getPaddingTop() == top) && (content.getPaddingBottom() == bottom)) return;
		content.setPadding(content.getPaddingLeft(), top, content.getPaddingRight(), bottom);
	}

	/**
	 * The top and bottom padding {@code content} needs, right now, to clear tool_bar and the bottom
	 * bars drawn over it, into {@code out[0]} and {@code out[1]} -- see
	 * {@link #insetScrollableContent}. False if it can't tell yet (not attached).
	 */
	public boolean computeContentInsets(View content, int[] out) {
		if ((toolBar == null) || (controlPanel == null) || !content.isAttachedToWindow()) return false;

		content.getLocationOnScreen(insetLoc1);
		int contentTop = insetLoc1[1];
		int contentBottom = contentTop + content.getHeight();

		int top;
		if ((toolBar.getVisibility() == GONE) && !isBarsHidden()) {
			// A tab without a tool bar at all (the Music tab on a phone): nothing to keep clear of.
			// (Hidden with the bars, it keeps its room, so the content doesn't jump each time.)
			top = 0;
		} else {
			toolBar.getLocationOnScreen(insetLoc1);
			// A little room below the floating tool bar pill, so the first item doesn't sit against it.
			top = Math.max(0, (insetLoc1[1] + toolBar.getHeight() + toIntPx(getContext(), 6)) -
					contentTop);
		}

		// Whichever bottom-anchored bar reaches furthest up the screen decides the inset -- usually
		// control_panel (nav_bar, when it's bottom-positioned, sits below it per the bottom-nav
		// layout's own constraints), but control_panel is routinely GONE while just browsing (nothing
		// playing), in which case nav_bar alone still needs clearing if it's the bottom-positioned one.
		int bottom = 0;
		// A suppressed panel (the Music tab) doesn't count even while it's still showing, e.g. until
		// the video mode it was left in ends: it's about to go, and the content is laid out without it.
		if ((controlPanel.getVisibility() == VISIBLE) && !controlPanel.isSuppressed()) {
			controlPanel.getLocationOnScreen(insetLoc2);
			bottom = Math.max(bottom, Math.max(0, contentBottom - insetLoc2[1]));
		}
		if ((navBar != null) && (navBar.getVisibility() == VISIBLE)
				&& (getPrefs().getNavBarPosPref(this) == NavBarView.POSITION_BOTTOM)) {
			navBar.getLocationOnScreen(insetLoc2);
			bottom = Math.max(bottom, Math.max(0, contentBottom - insetLoc2[1]));
		}

		out[0] = top;
		out[1] = bottom;
		return true;
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

	/**
	 * Whether a web page (the YouTube tab, the browser) is showing below the tool bar: it starts
	 * right under the tool bar's pill (see insetWebViewTop), so nothing may fade over it there.
	 */
	public boolean isTopInsetWebViewShown() {
		for (View v : topInsetContent) {
			if (v.isShown()) return true;
		}
		return false;
	}

	private void applyWebViewTopInset(View content) {
		if (toolBar == null) return;
		if (!(content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp)) return;
		// tool_bar's own visibility/height doesn't actually change while its mediator is Invisible
		// (see setBarsHidden()) since a WebView draws its own navigation and toggling an invisible
		// bar's visibility wouldn't change anything -- but the user still expects "hide bars" to
		// reclaim that reserved space for the page, so treat it as zero-height ourselves here.
		// Its bottom edge, not its height: the pill floats off the top of the screen. A little less,
		// tucking the page's own top spacing (YouTube's, a site's header padding) under the pill's
		// bottom edge, so more of the page shows.
		int top = isBarsHidden() ? 0 :
				Math.max(0, toolBar.getBottom() - toIntPx(getContext(), WEB_UNDER_TOOL_BAR));
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
	 * Also called by {@code ControlPanelView} when the Music tab hides it, so the content's padding
	 * follows in the same frame instead of one layout pass later.
	 */
	public void refreshContentInsets() {
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

	/**
	 * The spinner in the middle of the page, after a short beat so a quick load doesn't flash it
	 * (ContentLoadingProgressBar's own show() waits half a second, long enough to read as a freeze).
	 */
	private void showContentLoading() {
		progressBar.removeCallbacks(showLoadingBar);
		progressBar.postDelayed(showLoadingBar, 120);
	}

	private void hideContentLoading() {
		progressBar.removeCallbacks(showLoadingBar);
		progressBar.setVisibility(GONE);
	}

	private final Runnable showLoadingBar = () -> {
		if (progressBar != null) progressBar.setVisibility(VISIBLE);
	};

	public void setContentLoading(FutureSupplier<?> contentLoading) {
		if (this.contentLoading != null) {
			this.contentLoading.cancel();
			this.contentLoading = null;
		}

		hideContentLoading();
		if (contentLoading.isDone()) return;
		if (!loadingSuppressed) showContentLoading();

		var cl = this.contentLoading = contentLoading.main();
		cl.onCompletion((r, f) -> {
			if ((f != null) && !isCancellation(f)) Log.d(f);
			if (this.contentLoading == cl) {
				this.contentLoading = null;
				hideContentLoading();
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
		// A downloaded YouTube video's effects are the YouTube equalizer's, on their own screen.
		if ((id == R.id.audio_effects_fragment) && MusicPlayer.showDownloadedEffects(this)) {
			return getActiveFragment();
		}
		// A WebView-hosted player's (YouTube's) native fullscreen is drawn as its own overlay on
		// the activity root, entirely outside BodyLayout's video mode -- leave it first so the
		// fragment about to be shown isn't left hidden underneath it (matches what dim_settings'
		// own explicit exitVideoMode() call already does for that one menu item).
		VideoView v = getActiveVideoView();
		if (v != null) v.exitNativeFullscreen();
		BodyLayout b = getBody();
		// Leaving fullscreen video for another screen: the split of video and list -- except for a
		// downloaded YouTube video, which is only ever watched fullscreen. Going through the split
		// first and out of it again a moment later (the fragment change does that) left the two
		// panes half-way.
		if (b.isVideoMode()) {
			b.setMode(YtOffline.isDownloadedYoutube(getMediaServiceBinder().getCurrentItem()) ?
					BodyLayout.Mode.FRAME : BodyLayout.Mode.BOTH);
		}
		ActivityFragment f = super.showFragment(id, input);
		updateExtraFabsVisibility();
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
		} else if (id == R.id.spotify_import_fragment) {
			return new SpotifyImportFragment();
		} else if (id == R.id.youtube_alternatives_fragment) {
			return new YoutubeAlternativesFragment();
		} else if (id == R.id.diagnostic_log_fragment) {
			return new DiagnosticLogFragment();
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

	/**
	 * Creates the fragment with this id, if it doesn't exist yet, without showing it: it's laid out
	 * once, invisibly, at the full size of the frame, then hidden like any other tab that isn't on
	 * screen. For a tab whose content has to exist before the user ever opens it, e.g. the YouTube
	 * tab's page, which the Music tab plays through.
	 */
	@Nullable
	public ActivityFragment preloadFragment(int id) {
		ActivityFragment f = getFragment(id);
		if (f != null) return f;
		ActivityFragment created = createFragment(id);
		FragmentManager fm = getSupportFragmentManager();
		fm.beginTransaction().add(getFrameContainerId(), created).commitNow();
		View v = created.getView();

		if (v == null) {
			fm.beginTransaction().hide(created).commitNowAllowingStateLoss();
			return created;
		}

		v.setVisibility(INVISIBLE);
		v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
			@Override
			public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or,
																 int ob) {
				if ((r == l) || (b == t)) return;
				view.removeOnLayoutChangeListener(this);
				view.post(() -> {
					if (!created.isAdded() || created.isHidden()) return;
					if (getActiveFragment() == created) view.setVisibility(VISIBLE);
					else fm.beginTransaction().hide(created).commitNowAllowingStateLoss();
				});
			}
		});
		return created;
	}

	/** The fragment with this id if it's been created (shown at least once), else null. */
	@Nullable
	public ActivityFragment getFragment(int id) {
		for (Fragment f : getSupportFragmentManager().getFragments()) {
			if ((f instanceof ActivityFragment af) && (af.getFragmentId() == id)) return af;
		}

		return null;
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
		// Listening as music (a local track or YouTube in music mode): the Music tab.
		if ((pi != null) && (MusicAddon.get() != null) && MusicPlayer.isMusicModeActive(this)) {
			showFragment(R.id.music_addon);
			return completed(true);
		}
		// Watching YouTube: its own tab, where the video is.
		MediaEngine eng = getMediaSessionCallback().getEngine();
		if ((pi != null) && (eng != null) && (eng.getId() == MediaPrefs.MEDIA_ENG_YT)) {
			return completed(showFragment(R.id.youtube_fragment) != null);
		}
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
		} else if (root instanceof MusicQueue) {
			showFragment(R.id.music_addon);
			return true;
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

	@Override
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

	/** The "Add to playlist" dialog for {@code items}, straight away (no menu item first). */
	public void showAddToPlaylistDialog(List<PlayableItem> items) {
		if (items.isEmpty()) return;
		showPlaylistDialog(getContextMenu(), () -> completed(items), () -> "");
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
			try {
				List<Playlist> pls = new ArrayList<>(playlists.size());
				for (Item it : playlists) pls.add((Playlist) it);
				PlaylistPicker.show(this, R.string.playlist_add, R.drawable.playlist_add, pls, true,
						new PlaylistPicker.Callback() {
							@Override
							public void onCreate() {
								createPlaylist(selection.get(), initName);
							}

							@Override
							public void onPick(Playlist pl) {
								addToPlaylist(pl.getName(), selection.get());
							}
						});
			} catch (Exception err) {
				// Seen crashing specifically on the CarActivity surface: an InflateException/
				// UnsupportedOperationException resolving a TextAppearance attribute while inflating
				// this dialog's title -- and this app's own showAlert()/showInfo() (see UiUtils) go
				// through this exact same DialogBuilder.create(OverlayMenu)/DialogView machinery too
				// (via MainActivityDelegate#createDialogBuilder), just against a different menu, so if
				// whatever's actually broken here is the host Context's theme rather than anything
				// specific to this one dialog, calling showAlert() from this catch block would repeat
				// the identical failure as its own error path. UiUtils.showToast() is a plain
				// android.widget.Toast, entirely outside that machinery, so it's used here instead.
				DiagnosticLog.log("DIALOG", "failed to show playlist dialog:", err);
				Log.e(err, "Failed to show the playlist dialog");
				UiUtils.showToast(ctx, R.string.playlist_add_failed);
			}
		});
	}

	/**
	 * "Add to playlist" as an overlay menu rather than {@link #showPlaylistDialog}'s dialog: "Create
	 * playlist" first, then every existing playlist -- one tap adds {@code items} to it. Used by the
	 * FAB action (see {@code Action.PLAYLIST_ADD}), where a quick pick list suits a single tap.
	 */
	public void showAddToPlaylistMenu(OverlayMenu menu, List<PlayableItem> items) {
		if (items.isEmpty()) return;
		CharSequence initName = items.get(0).getName();
		showPlaylistDialog(menu, () -> completed(items), () -> initName);
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

	/**
	 * "Move to playlist": like "Add to playlist" (a new playlist or any other existing one), then
	 * removes {@code items} from {@code from}. Only removed once they've been added.
	 */
	public void showMoveToPlaylistDialog(OverlayMenu menu, Playlist from, List<PlayableItem> items) {
		if (items.isEmpty()) return;
		getLib().getPlaylists().getUnsortedChildren().main().onSuccess(children -> {
			Context ctx = getContext();
			List<Playlist> targets = new ArrayList<>(children.size());
			for (Item i : children) {
				if ((i instanceof Playlist pl) && !pl.equals(from)) targets.add(pl);
			}
			try {
				PlaylistPicker.show(this, R.string.playlist_move, R.drawable.playlist_move, targets, true,
						new PlaylistPicker.Callback() {
							@Override
							public void onCreate() {
								UiUtils.queryText(ctx, R.string.playlist_name, R.drawable.playlist, from.getName())
										.onSuccess(name -> {
											if (name == null) return;
											getLib().getPlaylists().addItem(name)
													.onFailure(err -> showAlert(ctx, err.getMessage()))
													.onSuccess(pl -> moveToPlaylist(from, pl, items));
										});
							}

							@Override
							public void onPick(Playlist pl) {
								moveToPlaylist(from, pl, items);
							}
						});
			} catch (Exception err) {
				Log.e(err, "Failed to show the move-to-playlist dialog");
				UiUtils.showToast(ctx, R.string.playlist_add_failed);
			}
		});
	}

	private void moveToPlaylist(Playlist from, Playlist to, List<PlayableItem> items) {
		discardSelection();
		Context ctx = getContext();
		to.addItems(items).then(v -> from.removeItems(items)).main()
				.onFailure(err -> showAlert(ctx, err.getMessage()))
				.onSuccess(v -> {
					MediaLibFragment f = getMediaLibFragment(R.id.playlists_fragment);
					if (f != null) f.getAdapter().reload();
					UiUtils.showToast(ctx, R.string.playlist_moved, items.size(), to.getName());
				});
	}

	public void removeFromPlaylist(Playlist pl, List<PlayableItem> selection) {
		discardSelection();
		pl.removeItems(selection).onFailure(err -> showAlert(getContext(), err.getMessage()))
				.thenRun(() -> {
					MediaLibFragment f = getMediaLibFragment(R.id.playlists_fragment);
					if (f != null) f.getAdapter().reload();
				});
	}

	@Override
	public void onBackPressed() {
		if (PlaylistPicker.dismissOpen()) return;
		if (DownloadPicker.dismissOpen()) return;
		super.onBackPressed();
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
		matchStatusBarToBackground();
		me.aap.utils.ui.view.DialogView.setStyler(me.aap.fermata.ui.view.DialogStyle::apply);
		toolBar = a.findViewById(R.id.tool_bar);
		progressBar = a.findViewById(R.id.content_loading_progress);
		navBar = a.findViewById(R.id.nav_bar);
		body = a.findViewById(R.id.body_layout);
		controlPanel = a.findViewById(R.id.control_panel);
		floatingButton = a.findViewById(R.id.floating_button);
		floatingButton.setScale(getPrefs().getFabSizePref(this));
		floatingButton2 = a.findViewById(R.id.floating_button2);
		floatingButton2.setScale(getPrefs().getFabSizePref(this));
		floatingButton3 = a.findViewById(R.id.floating_button3);
		floatingButton3.setScale(getPrefs().getFabSizePref(this));
		floatingButton4 = a.findViewById(R.id.floating_button4);
		floatingButton4.setScale(getPrefs().getFabSizePref(this));
		floatingButton5 = a.findViewById(R.id.floating_button5);
		if (floatingButton5 != null) floatingButton5.setScale(getPrefs().getFabSizePref(this));
		floatingButton6 = a.findViewById(R.id.floating_button6);
		if (floatingButton6 != null) floatingButton6.setScale(getPrefs().getFabSizePref(this));
		updateFabDraggable();
		controlPanel.bind(getMediaServiceBinder());
		enableBodyOverlayLayout();
		// Catch-all re-sync -- see refreshContentInsets() -- for content whose own attach/layout
		// listeners missed the layout change they needed, most notably a tab restored by the
		// fragment manager across the recreate() that a theme or nav-bar-position change triggers.
		body.getViewTreeObserver().addOnGlobalLayoutListener(this::refreshContentInsets);
		body.getViewTreeObserver().addOnGlobalLayoutListener(this::syncSideNavInset);
		// The soft keyboard shows over the bottom of the window without resizing it, hiding the
		// floating buttons (e.g. while typing a YouTube search) -- keep them above it instead.
		body.getViewTreeObserver().addOnGlobalLayoutListener(this::liftFabsAboveKeyboard);

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
		} else if (prefs.contains(MainActivityPrefs.fab(this, FAB_SIZE))) {
			if (floatingButton != null) floatingButton.setScale(getPrefs().getFabSizePref(this));
			if (floatingButton2 != null) floatingButton2.setScale(getPrefs().getFabSizePref(this));
			if (floatingButton3 != null) floatingButton3.setScale(getPrefs().getFabSizePref(this));
			if (floatingButton4 != null) floatingButton4.setScale(getPrefs().getFabSizePref(this));
			if (floatingButton5 != null) floatingButton5.setScale(getPrefs().getFabSizePref(this));
			if (floatingButton6 != null) floatingButton6.setScale(getPrefs().getFabSizePref(this));
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
		} else if (containsFabPref(prefs, extraFabEnabledPrefs())) {
			updateExtraFabsVisibility();
		} else if (containsFabPref(prefs, extraFabActionPrefs())) {
			fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
		} else if (prefs.contains(MainActivityPrefs.fab(this, FAB_DRAGGABLE))) {
			updateFabDraggable();
		}
	}

	/** Whether one of these floating button prefs, as it applies to this screen, changed. */
	private boolean containsFabPref(List<PreferenceStore.Pref<?>> changed, Pref<?>[] fabPrefs) {
		for (Pref<?> p : fabPrefs) {
			if (changed.contains(MainActivityPrefs.fab(this, p))) return true;
		}
		return false;
	}

	/** How far the floating buttons are currently lifted above their place -- see below. */
	private int fabKeyboardLift;
	private final Rect keyboardFrame = new Rect();
	private final int[] fabParentLoc = new int[2];

	/**
	 * Moves the floating buttons up out from under the soft keyboard while it's showing, and back
	 * when it goes. Done with their bottom margins, not a translation: dragging a button (see
	 * FAB_DRAGGABLE) already owns its translation.
	 */
	private void liftFabsAboveKeyboard() {
		FloatingButton f = floatingButton;
		if ((f == null) || !f.isAttachedToWindow() || !(f.getParent() instanceof View parent)) return;
		View root = f.getRootView();
		root.getWindowVisibleDisplayFrame(keyboardFrame);
		int screenBottom = root.getHeight();
		// Anything smaller is just the system navigation bar, not a keyboard.
		boolean keyboard = (screenBottom - keyboardFrame.bottom) > (screenBottom * 0.15f);
		int keyboardTop = keyboardFrame.bottom;
		// Android Auto's keyboard belongs to the car host and may not show up in the window's
		// visible frame at all: while its text input is active, keep the buttons in the upper half.
		if (!keyboard && isCarActivity() && getAppActivity().isInputActive()) {
			keyboard = true;
			root.getLocationOnScreen(fabParentLoc);
			keyboardTop = fabParentLoc[1] + screenBottom / 2;
		}

		int lift = 0;
		if (keyboard) {
			parent.getLocationOnScreen(fabParentLoc);
			// Where the buttons' bottom edge sits without any lift.
			int bottom = fabParentLoc[1] + f.getBottom() + fabKeyboardLift;
			int gap = UiUtils.toIntPx(getContext(), 8);
			lift = Math.max(0, bottom + gap - keyboardTop);
		}
		if (lift == fabKeyboardLift) return;

		int delta = lift - fabKeyboardLift;
		fabKeyboardLift = lift;
		for (View b : new View[]{floatingButton, floatingButton2, floatingButton3, floatingButton4,
				floatingButton5, floatingButton6}) {
			if ((b == null) || !(b.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp)) {
				continue;
			}
			lp.bottomMargin = Math.max(0, lp.bottomMargin + delta);
			b.setLayoutParams(lp);
		}
	}

	/** Re-checks {@link #liftFabsAboveKeyboard()} -- for keyboards that don't relayout the window. */
	public void refreshFabKeyboardLift() {
		if (floatingButton != null) floatingButton.post(this::liftFabsAboveKeyboard);
	}

	private void updateFabDraggable() {
		boolean draggable = getPrefs().getBooleanPref(MainActivityPrefs.fab(this, FAB_DRAGGABLE));
		if (floatingButton != null) floatingButton.setDraggable(draggable);
		if (floatingButton2 != null) floatingButton2.setDraggable(draggable);
		if (floatingButton3 != null) floatingButton3.setDraggable(draggable);
		if (floatingButton4 != null) floatingButton4.setDraggable(draggable);
		if (floatingButton5 != null) floatingButton5.setDraggable(draggable);
		if (floatingButton6 != null) floatingButton6.setDraggable(draggable);

		// Previously a dragged FAB only snapped back to its default layout position on the next app
		// restart (a fresh Activity/View never picked up the leftover drag translation to begin
		// with). Reset it live the moment dragging is turned off instead.
		if (!draggable) {
			resetFabPosition(floatingButton);
			resetFabPosition(floatingButton2);
			resetFabPosition(floatingButton3);
			resetFabPosition(floatingButton4);
			resetFabPosition(floatingButton5);
			resetFabPosition(floatingButton6);
		}
	}

	private static void resetFabPosition(@Nullable FloatingButton fb) {
		if (fb == null) return;
		fb.animate().translationX(0f).translationY(0f).setDuration(200L).start();
	}

	/**
	 * Hides what's normally drawn over the content (the control panel, every floating button and
	 * the content loading indicator) while a screen with its own controls and loading indicator
	 * (the Music tab) shows.
	 */
	public void setOverlaysSuppressed(boolean suppressed) {
		ControlPanelView cp = getControlPanel();
		if (cp != null) cp.setSuppressed(suppressed);
		loadingSuppressed = suppressed;
		if (progressBar != null) {
			if (suppressed) hideContentLoading();
			else if (contentLoading != null) showContentLoading();
		}
		// The primary one first: the others may mirror its visibility.
		if (floatingButton != null) floatingButton.setSuppressed(suppressed);
		if (floatingButton2 != null) floatingButton2.setSuppressed(suppressed);
		if (floatingButton3 != null) floatingButton3.setSuppressed(suppressed);
		if (floatingButton4 != null) floatingButton4.setSuppressed(suppressed);
		if (floatingButton5 != null) floatingButton5.setSuppressed(suppressed);
		if (floatingButton6 != null) floatingButton6.setSuppressed(suppressed);
		if (suppressed) return;
		// Whatever the others mirrored while suppressed is stale.
		updateExtraFabsVisibility();
	}

	/**
	 * FAB2..FAB6: hidden unless turned on; over video they mirror the primary FAB's actual current
	 * visibility rather than independently deriving it from isVideoMode() --
	 * ControlPanelView.enableVideoMode() may have just hidden all FABs until the user taps the
	 * screen (getStartDelay() == 0), and recomputing visibility here from scratch would immediately
	 * clobber that, causing a brief flash on entry.
	 */
	public void updateExtraFabsVisibility() {
		FloatingButton[] fabs = getExtraFloatingButtons();
		Pref<BooleanSupplier>[] on = extraFabEnabledPrefs();
		Pref<IntSupplier>[] actions = extraFabActionPrefs();
		boolean listWithVideo = isFavoritesOrPlaylistsActive() && isVideoPlaying();
		for (int i = 0; i < fabs.length; i++) {
			FloatingButton fb = fabs[i];
			if (fb == null) continue;
			if (!getPrefs().getBooleanPref(MainActivityPrefs.fab(this, on[i]))) fb.setVisibility(GONE);
			else if (isVideoMode()) fb.setVisibility(floatingButton.getVisibility());
			else if (isWebBrowserActive()) fb.setVisibility(VISIBLE);
			// A video playing while browsing Favorites/Playlists: its fullscreen button is one tap
			// back to it.
			else if (listWithVideo &&
					(getPrefs().getIntPref(MainActivityPrefs.fab(this, actions[i])) ==
							Action.FULLSCREEN_TOGGLE.ordinal())) {
				fb.setVisibility(VISIBLE);
			} else {
				fb.setVisibility(GONE);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Pref<IntSupplier>[] extraFabActionPrefs() {
		return new Pref[]{FAB2_ACTION, FAB3_ACTION, FAB4_ACTION, FAB5_ACTION, FAB6_ACTION};
	}

	private boolean isFavoritesOrPlaylistsActive() {
		ActivityFragment f = getActiveFragment();
		if (f == null) return false;
		int id = f.getFragmentId();
		return (id == R.id.favorites_fragment) || (id == R.id.playlists_fragment);
	}

	/** Whether a video (not music) is playing: YouTube out of music mode, or a local video. */
	public boolean isVideoPlaying() {
		MediaSessionCallback cb = getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		if ((eng == null) || !cb.isPlaying()) return false;
		if (eng.getId() == MediaPrefs.MEDIA_ENG_YT) return !MusicPlayer.isYoutubeAudioMode();
		PlayableItem src = eng.getSource();
		return (src != null) && src.isVideo();
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
