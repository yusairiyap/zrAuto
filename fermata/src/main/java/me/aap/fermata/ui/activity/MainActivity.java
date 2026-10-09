package me.aap.fermata.ui.activity;

import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.ADJUST_RAISE;
import static android.media.AudioManager.FLAG_SHOW_UI;
import static android.media.AudioManager.STREAM_MUSIC;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.InputDevice.SOURCE_CLASS_POINTER;
import static android.view.MotionEvent.ACTION_SCROLL;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;
import static me.aap.fermata.util.Utils.createDownloader;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedNull;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.async.Completed.failed;
import static me.aap.utils.misc.MiscUtils.isPackageInstalled;
import static me.aap.utils.pref.PreferenceStore.Pref.sa;
import static me.aap.utils.ui.UiUtils.showAlert;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.app.PictureInPictureParams;
import android.util.Rational;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.FileProvider;

import com.google.android.play.core.splitcompat.SplitCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.service.FermataMediaServiceConnection;
import me.aap.fermata.ui.view.VideoView;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.collection.NaturalOrderComparator;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.net.http.HttpConnection;
import me.aap.utils.net.http.HttpFileDownloader;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.text.TextUtils;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.activity.AppActivity;
import me.aap.utils.ui.activity.SplitCompatActivityBase;

public class MainActivity extends SplitCompatActivityBase
		implements ZrAutoActivity, AddonManager.Listener {
	private static FermataMediaServiceConnection service;
	// Where androidx keeps the FragmentManager's saved state in an activity's saved instance state.
	private static final String SAVED_STATE_REGISTRY_KEY =
			"androidx.lifecycle.BundlableSavedStateRegistry.key";
	private static final String FRAGMENTS_STATE_KEY = "android:support:fragments";
	private static MainActivity activeInstance;
	private int nightMode;

	@Nullable
	public static MainActivity getActiveInstance() {
		return activeInstance;
	}

	@Override
	protected FutureSupplier<MainActivityDelegate> createDelegate(AppActivity a) {
		FermataMediaServiceConnection s = service;

		if ((s != null) && s.isConnected()) {
			return completed(new MainActivityDelegate(a, service.createBinder()));
		}

		return FermataMediaServiceConnection.connect(a).map(c -> {
			assert service == null;
			service = c;
			return new MainActivityDelegate(a, service.createBinder());
		}).onFailure(err -> showAlert(getContext(), String.valueOf(err)));
	}

	@Override
	protected void attachBaseContext(Context base) {
		super.attachBaseContext(MainActivityDelegate.attachBaseContext(base));
	}

	@Override
	public void finish() {
		FermataMediaServiceConnection s = service;
		service = null;
		if (s != null) s.disconnect();
		super.finish();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		boolean auto = isCarActivity() || FermataApplication.get().isMirroringMode();
		MainActivityDelegate.setTheme(this, auto);
		if ((SDK_INT >= Build.VERSION_CODES.S)
				&& (MainActivityDelegate.Prefs.instance.getThemePref(auto)
						== MainActivityPrefs.THEME_DYNAMIC)) {
			com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
		}
		nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
		AddonManager.get().addBroadcastListener(this);
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				getActivityDelegate().onSuccess(MainActivityDelegate::onBackPressed);
			}
		});
		// Every screen's fragment needs the activity delegate, which is only ready once the media
		// service is connected. After the process was killed in the background (it happens on a
		// long drive with Android Auto), that connection is asynchronous, yet the FragmentManager
		// would still restore the old fragments and create their views in onStart(), before the
		// delegate exists: the YouTube tab then crashed building its web view ("FutureSupplier is
		// not done"). The delegate reopens the saved tab itself (the navId/fragmentId it saves, see
		// MainActivityDelegate#onActivityCreate) and creates its fragment anew when missing, so the
		// FragmentManager's own copy is simply dropped in that case.
		FermataMediaServiceConnection s = service;
		if ((savedInstanceState != null) && ((s == null) || !s.isConnected())) {
			dropFragmentState(savedInstanceState);
		}
		super.onCreate(savedInstanceState);
	}

	private static void dropFragmentState(Bundle state) {
		state.remove(FRAGMENTS_STATE_KEY);
		Bundle registry = state.getBundle(SAVED_STATE_REGISTRY_KEY);
		if (registry != null) registry.remove(FRAGMENTS_STATE_KEY);
	}

	@Override
	protected void onDestroy() {
		AddonManager.get().removeBroadcastListener(this);
		super.onDestroy();
	}

	/**
	 * android:configChanges in the manifest lists "uiMode" so the system never recreates this
	 * Activity on its own when the user flips system dark/light mode -- without this override the
	 * THEME_SYSTEM/THEME_DYNAMIC background picked in {@link MainActivityDelegate#setTheme} would
	 * stay stuck on whatever it was at launch until the app is killed and restarted. Recreate
	 * ourselves only when the night-mode bit actually flipped and the active theme actually follows
	 * it, so THEME_LIGHT/THEME_DARK/etc. (which don't depend on system dark mode) aren't disturbed
	 * by unrelated config changes (keyboard, locale, ...) that this same configChanges entry covers.
	 */
	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		int mode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
		if (mode == nightMode) return;
		nightMode = mode;
		boolean auto = isCarActivity() || FermataApplication.get().isMirroringMode();
		int theme = MainActivityDelegate.Prefs.instance.getThemePref(auto);
		if ((theme == MainActivityPrefs.THEME_SYSTEM) || (theme == MainActivityPrefs.THEME_DYNAMIC)) {
			recreate();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		activeInstance = this;
		DiagnosticLog.log("STATE", "activity resumed");

		// Fragment switches within the app never pause/resume the Activity -- only actually leaving
		// it (home button, task switcher, another app taking focus) does -- so this is the signal for
		// "the user came back to the app" that Private Mode without "Always" needs: it's meant to be
		// gone the moment they've stepped away, not just when the process happens to get killed (a
		// background app is normally left running, so WebBrowserAddon's own constructor-time check
		// only catches an actual cold start / force-stop, not this far more common case).
		MainActivityPrefs mp = MainActivityPrefs.get();
		if (mp.isPrivateModeEnabled() && !mp.getBooleanPref(MainActivityPrefs.PRIVATE_MODE_ALWAYS)) {
			mp.setPrivateModeEnabled(false);
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		activeInstance = null;
		DiagnosticLog.log("STATE", "activity paused (left the app or covered)");
	}

	@Override
	protected void onStop() {
		super.onStop();
		DiagnosticLog.log("STATE", "activity stopped (in the background)");
	}

	/**
	 * A tap anywhere outside the text field being typed into -- a floating button, the toolbar, the
	 * nav bar, a search result -- closes the soft keyboard, as the field it belonged to is no longer
	 * where the user is. The tap itself still does whatever it does.
	 * <p>
	 * Only once the tap is over, though: closing the keyboard re-lays out the window (the floating
	 * buttons drop back down from above it, a panned window pans back), and doing that as the finger
	 * went down moved the very button being tapped out from under it, cancelling the tap -- it took a
	 * second one to actually do anything. The click itself is posted by the view on ACTION_UP, so the
	 * keyboard is closed in a post after that.
	 */
	@Override
	public boolean dispatchTouchEvent(MotionEvent ev) {
		int action = ev.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			View focus = getCurrentFocus();
			outsideTap = null;
			if (focus instanceof EditText) {
				Rect r = new Rect();
				if (!focus.getGlobalVisibleRect(r) || !r.contains((int) ev.getRawX(), (int) ev.getRawY())) {
					outsideTap = focus;
					MainActivityDelegate a = getActivityDelegate().peek();
					if ((a != null) && (a.getActiveFragment() instanceof MainActivityFragment f)) {
						f.onTouchDownOutsideTextField(ev.getRawX(), ev.getRawY());
					}
				}
			}
		}

		boolean handled = super.dispatchTouchEvent(ev);

		if ((action == MotionEvent.ACTION_UP) || (action == MotionEvent.ACTION_CANCEL)) {
			View focus = outsideTap;
			outsideTap = null;
			if (focus != null) {
				focus.post(() -> {
					InputMethodManager imm = getSystemService(InputMethodManager.class);
					if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
					MainActivityDelegate a = getActivityDelegate().peek();
					if ((a != null) && (a.getActiveFragment() instanceof MainActivityFragment f)) {
						f.onTapOutsideTextField();
					}
				});
			}
		}

		return handled;
	}

	/** The focused text field a touch went down outside of -- see {@link #dispatchTouchEvent}. */
	@Nullable
	private View outsideTap;

	// Lets the active fragment keep a playing video on screen in picture-in-picture as the user
	// leaves the app -- see MainActivityFragment#onUserLeaveHint() (e.g. the YouTube tab's).
	@Override
	protected void onUserLeaveHint() {
		super.onUserLeaveHint();
		MainActivityDelegate a = getActivityDelegate().peek();
		if (a == null) return;
		if (a.getActiveFragment() instanceof MainActivityFragment f) f.onUserLeaveHint();
		if (!isInPictureInPictureMode()) enterLocalVideoPip(a);
	}

	/**
	 * A local (or downloaded) video playing fullscreen goes on in a small window as the user leaves
	 * the app, as YouTube's does (see above).
	 */
	private void enterLocalVideoPip(MainActivityDelegate a) {
		if ((SDK_INT < Build.VERSION_CODES.O) || !a.isVideoMode()) return;
		var cb = a.getMediaSessionCallback();
		MediaEngine eng = cb.getEngine();
		if ((eng == null) || (eng.getId() == MediaPrefs.MEDIA_ENG_YT) || !cb.isPlaying()) return;
		PlayableItem src = eng.getSource();
		if ((src == null) || !src.isVideo()) return;

		try {
			float w = eng.getVideoWidth();
			float h = eng.getVideoHeight();
			// The window's ratio must stay within what Android accepts.
			float r = ((w > 0) && (h > 0)) ? Math.max(0.42f, Math.min(2.39f, w / h)) : (16f / 9f);
			PictureInPictureParams.Builder pb = new PictureInPictureParams.Builder()
					.setAspectRatio(new Rational(Math.round(r * 1000), 1000));
			VideoView vv = a.getActiveVideoView();
			Rect rect = new Rect();
			if ((vv != null) && vv.getGlobalVisibleRect(rect)) pb.setSourceRectHint(rect);
			enterPictureInPictureMode(pb.build());
		} catch (Throwable ex) {
			Log.w(ex, "Failed to enter picture-in-picture");
		}
	}

	@Override
	public void onPictureInPictureModeChanged(boolean pip, @NonNull Configuration cfg) {
		super.onPictureInPictureModeChanged(pip, cfg);
		MainActivityDelegate a = getActivityDelegate().peek();
		if (a != null) a.onPictureInPictureChanged(pip);
	}

	@Override
	public boolean isCarActivity() {
		return false;
	}

	@SuppressWarnings("unchecked")
	@NonNull
	@Override
	public FutureSupplier<MainActivityDelegate> getActivityDelegate() {
		return (FutureSupplier<MainActivityDelegate>) super.getActivityDelegate();
	}

	@Override
	public void onAddonChanged(AddonManager mgr, AddonInfo info, boolean installed) {
		SplitCompat.installActivity(this);
	}

	@Override
	public boolean onGenericMotionEvent(MotionEvent event) {
		if (((event.getSource() & SOURCE_CLASS_POINTER) != 0) && (event.getAction() == ACTION_SCROLL)) {
			AudioManager amgr = (AudioManager) getContext().getSystemService(AUDIO_SERVICE);
			if (amgr == null) return false;
			float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
			amgr.adjustStreamVolume(STREAM_MUSIC, (v > 0) ? ADJUST_RAISE : ADJUST_LOWER, FLAG_SHOW_UI);
			return true;
		}

		return super.onGenericMotionEvent(event);
	}

	public FutureSupplier<?> uninstallControl() {
		if (!BuildConfig.AUTO) return completedVoid();

		var pkgName = "me.aap.fermata.auto.control.dear.google.why";
		if (!isPackageInstalled(this, pkgName)) return completedVoid();

		return startActivityForResult(() -> {
			var i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + pkgName));
			i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
			return i;
		});
	}

	public void checkUpdates() {
		if (!BuildConfig.AUTO) return;

		PreferenceStore ps = FermataApplication.get().getPreferenceStore();
		Pref<Supplier<String[]>> deletePref = sa("DELETE_ON_STARTUP", new String[0]);
		String[] delete = ps.getStringArrayPref(deletePref);

		if (delete.length != 0) {
			App.get().getScheduler().schedule(() -> {
				for (String f : delete) {
					//noinspection ResultOfMethodCallIgnored
					new File(f).delete();
				}

				synchronized (this) {
					List<String> l = new ArrayList<>(Arrays.asList(ps.getStringArrayPref(deletePref)));
					l.removeAll(Arrays.asList(delete));
					if (l.isEmpty()) ps.removePref(deletePref);
					else ps.applyStringArrayPref(deletePref, l.toArray(new String[0]));
				}
			}, 1, MINUTES);
		}

		String reqUrl = "https://api.github.com/repos/yusairiyap/zrAuto/releases/latest";
		HttpConnection.connect(o -> o.url(reqUrl), (resp, err) -> {
			if (err != null) {
				Log.e(err, "Failed to check updates");
				return failed(err);
			}

			resp.getPayload((p, perr) -> {
				if (perr != null) {
					Log.e(perr, "Failed to read response");
					return completedNull();
				}

				try {
					JSONObject json = new JSONObject(TextUtils.toString(p, UTF_8));
					String tag = json.getString("tag_name");
					String[] res = new String[2];
					res[0] = tag;
					int idx = tag.indexOf('(');
					if (idx != -1) tag = tag.substring(0, idx);

					if (NaturalOrderComparator.compareNatural(BuildConfig.VERSION_NAME, tag.trim()) < 0) {
						Log.i("New version is available: ", res[0]);
						JSONArray assets = json.getJSONArray("assets");
						String ext = "armeabi".equals(Build.SUPPORTED_ABIS[0]) ? "-arm.apk" : "-arm64.apk";

						for (int i = 0, n = assets.length(); i < n; i++) {
							JSONObject asset = assets.getJSONObject(i);
							String name = asset.getString("name");
							if (name.endsWith(ext)) res[1] = asset.getString("browser_download_url");
						}

						return (res[1] != null) ? completed(res) : completedNull();
					} else {
						Log.i("The latest release version - ", res[0], ". Application is up to date");
						return completedNull();
					}
				} catch (Exception ex) {
					Log.e(ex, "Failed to parse response");
					return failed(ex);
				}
			}).main().onSuccess(res -> {
				if (res == null) return;
				UiUtils.showQuestion(getContext(), getString(R.string.update),
								getString(R.string.update_question, res[0]),
								AppCompatResources.getDrawable(getContext(), R.drawable.notification))
						.onSuccess(r -> update(res[1], ps, deletePref));
			});

			return completedVoid();
		});
	}

	private FutureSupplier<Void> update(String uri, PreferenceStore ps,
																			Pref<Supplier<String[]>> deletePref) {
		if (!BuildConfig.AUTO) return completedVoid();
		try {
			File tmp;
			File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

			if ((tmp = createTempFile(dir)) == null) {
				App app = App.get();
				File cache = app.getExternalCacheDir();
				if (cache == null) cache = app.getCacheDir();
				dir = new File(cache, "updates");
				//noinspection ResultOfMethodCallIgnored
				dir.mkdirs();
				if ((tmp = createTempFile(dir)) == null) {
					App.get()
							.run(() -> showAlert(this, "Update failed - unable to create a temporary " + "file"
							));
					return completedVoid();
				}
			}

			File f = tmp;

			synchronized (this) {
				List<String> l = new ArrayList<>(Arrays.asList(ps.getStringArrayPref(deletePref)));
				l.add(f.getAbsolutePath());
				ps.applyStringArrayPref(deletePref, l.toArray(new String[0]));
			}

			HttpFileDownloader d = createDownloader(getContext(), uri);
			return d.download(uri, f).then(s -> {
				Uri u = (SDK_INT >= Build.VERSION_CODES.N) ?
						FileProvider.getUriForFile(getApplicationContext(), getPackageName() + ".FileProvider",
								f) : Uri.fromFile(f);

				try {
					installApk(u, true);
				} catch (Exception ex) {
					Log.e(ex, "Update failed");
					App.get().run(() -> showAlert(this, "Update failed: " + ex.getLocalizedMessage()));
					return failed(ex);
				}

				return completedVoid();
			}).onFailure(err -> {
				Log.e(err, "Failed to download apk: ", uri);
				App.get().run(() -> showAlert(this, "Failed to download apk: " + uri));
			});
		} catch (Exception ex) {
			Log.e(ex, "Update failed");
			App.get().run(() -> showAlert(this, "Update failed: " + ex.getLocalizedMessage()));
			return failed(ex);
		}
	}

	private static File createTempFile(File dir) {
		try {
			if (dir == null) return null;
			return File.createTempFile("zrAuto-", ".apk", dir);
		} catch (Exception ex) {
			Log.e(ex, "Failed to create a temporary file in the directory ", dir);
			return null;
		}
	}
}
