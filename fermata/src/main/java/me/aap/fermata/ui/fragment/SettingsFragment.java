package me.aap.fermata.ui.fragment;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static java.nio.charset.StandardCharsets.UTF_8;
import static me.aap.fermata.media.pref.MediaPrefs.MEDIA_ENG_EXO;
import static me.aap.fermata.media.pref.MediaPrefs.MEDIA_ENG_MP;
import static me.aap.fermata.media.pref.MediaPrefs.MEDIA_ENG_VLC;
import static me.aap.fermata.media.pref.MediaPrefs.MEDIA_SCANNER_DEFAULT;
import static me.aap.fermata.media.pref.MediaPrefs.MEDIA_SCANNER_SYSTEM;
import static me.aap.fermata.media.pref.MediaPrefs.MEDIA_SCANNER_VLC;
import static me.aap.fermata.media.pref.MediaPrefs.SUB_LANG;
import static me.aap.fermata.media.pref.MediaPrefs.SUB_SIZE;
import static me.aap.fermata.ui.activity.MainActivityPrefs.VOICE_CONTROL_LANG;
import static me.aap.fermata.ui.activity.MainActivityPrefs.VOICE_CONTROL_SUBST;
import static me.aap.fermata.ui.activity.MainActivityPrefs.VOICE_CONTROl_ENABLED;
import static me.aap.fermata.ui.activity.MainActivityPrefs.VOICE_CONTROl_FB;
import static me.aap.utils.ui.UiUtils.ID_NULL;
import static me.aap.utils.ui.UiUtils.showAlert;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.action.Action;
import me.aap.fermata.action.Key;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.SubGenAddon;
import me.aap.fermata.media.engine.BitmapCache;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.addon.music.MusicPlayer;
import me.aap.fermata.media.pref.MediaLibPrefs;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.pref.PlaybackControlPrefs;
import me.aap.fermata.share.ShareManager;
import me.aap.fermata.share.SharePrefs;
import me.aap.fermata.spotify.SpotifyAuth;
import me.aap.fermata.spotify.SpotifyPrefs;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.Utils;
import me.aap.utils.app.App;
import me.aap.utils.collection.CollectionUtils;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.Consumer;
import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.fermata.backup.BackupManager;
import me.aap.fermata.ui.view.TopToast;
import me.aap.utils.pref.PrefCondition;
import me.aap.utils.pref.PrefUtils;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.BasicPreferenceStore;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.pref.PreferenceView;
import me.aap.utils.pref.PreferenceViewAdapter;
import me.aap.fermata.ui.home.HomeModel;
import me.aap.fermata.ui.view.SettingsNav;
import me.aap.fermata.ui.view.SettingsSplit;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.FilePickerFragment;
import me.aap.utils.vfs.local.LocalFileSystem;

/**
 * @author Andrey Pavlenko
 */
public class SettingsFragment extends MainActivityFragment
		implements MainActivityListener, PreferenceStore.Listener {
	/** Pass to {@code showFragment(R.id.settings_fragment, SHOW_DIM_SETTINGS)} to jump straight to
	 * the dim-screen settings subsection instead of landing on the settings root page. */
	public static final Object SHOW_DIM_SETTINGS = "dim_settings";
	/** Same as {@link #SHOW_DIM_SETTINGS}, but for the Private Mode settings subsection. */
	public static final Object SHOW_PRIVATE_MODE_SETTINGS = "private_mode_settings";
	/** Same as {@link #SHOW_DIM_SETTINGS}, but for the Secondary/Tertiary FAB settings subsection. */
	public static final Object SHOW_FAB_SETTINGS = "fab_settings";
	/** Same as {@link #SHOW_DIM_SETTINGS}, but for the Home page. */
	/** Same as {@link #SHOW_DIM_SETTINGS}, but for the Key bindings page. */
	public static final Object SHOW_KEY_BINDINGS = "key_bindings";
	/** Same as {@link #SHOW_DIM_SETTINGS}, but for the Interface page. */
	public static final Object SHOW_INTERFACE = "interface";
	/** Same as {@link #SHOW_DIM_SETTINGS}, but for Interface &gt; Android Auto. */
	public static final Object SHOW_AA_INTERFACE = "aa_interface";
	private static final String ADDON_SETTINGS = "addon_settings:";
	private static final String KEY_SETTINGS = "key_settings:";

	private static final String CATEGORY = "category:";

	/** The page showing. */
	private PreferenceViewAdapter adapter;
	@Nullable
	private PreferenceSet rootSet;
	/**
	 * Wide window (a tablet): the categories are the list beside the page ({@code SettingsSplit}), so the page never shows the
	 * root itself, only a category and what is under it.
	 */
	private boolean twoPane;
	private PreferenceSet dimSettingsSet;
	// Each addon's own settings page, by module name, see addonSettings().
	private final java.util.Map<String, PreferenceSet> addonSettingsSets = new java.util.HashMap<>();
	@Nullable
	private PreferenceSet keyBindingsSet;
	@Nullable
	private PreferenceSet appearanceSet;
	@Nullable
	// Each key's own bindings page, by Key name, see keySettings().
	private final java.util.Map<String, PreferenceSet> keySettingsSets = new java.util.HashMap<>();
	// The key page opened from the key simulator: Back from it goes back there.
	@Nullable
	private PreferenceSet returnToKeyTesterFrom;
	@Nullable
	private Object pendingInput;

	/** Same as {@link #SHOW_DIM_SETTINGS}, but for the category with this preference set id (the list beside the page). */
	public static Object category(int setId) {
		return CATEGORY + setId;
	}

	/** Shows a category of the root (a tap in the list beside the page). */
	public void showCategory(int setId) {
		PreferenceSet root = rootSet;
		PreferenceViewAdapter ad = adapter;
		if ((root == null) || (ad == null)) {
			pendingInput = CATEGORY + setId;
			return;
		}
		PreferenceSet p = root.find(setId);
		if (p != null) ad.setPreferenceSet(p);
	}

	@Override
	public boolean isSettingsPage() {
		return true;
	}

	/** The title bar carries a Search chip on every settings page. */
	@Override
	public me.aap.utils.ui.view.ToolBarView.Mediator getToolBarMediator() {
		return SettingsSearchChip.MEDIATOR;
	}

	/**
	 * Same as {@link #SHOW_DIM_SETTINGS}, but for an addon's own settings page (Settings > Addons >
	 * the addon), by the addon's module name.
	 */
	public static Object addonSettings(String moduleName) {
		// An addon's class name works too (several addons can share one module).
		return ADDON_SETTINGS + moduleName;
	}

	/**
	 * Same as {@link #SHOW_DIM_SETTINGS}, but for one key's bindings page (Settings > Key bindings),
	 * opened from the key simulator: Back from that page returns to the simulator.
	 */
	public static Object keySettings(Key k) {
		return KEY_SETTINGS + k.name();
	}

	@Override
	public int getFragmentId() {
		return R.id.settings_fragment;
	}

	@Override
	public void setInput(Object input) {
		pendingInput = input;
		if (adapter != null) applyPendingInput();
	}

	private void applyPendingInput() {
		if ((pendingInput == SHOW_DIM_SETTINGS) && (dimSettingsSet != null)) {
			adapter.setPreferenceSet(dimSettingsSet);
		} else if (pendingInput == SHOW_PRIVATE_MODE_SETTINGS) {
			MainActivityDelegate d = MainActivityDelegate.get(requireContext());
			d.getBody().post(() -> d.showFragment(R.id.privacy_settings_fragment));
		} else if ((pendingInput instanceof String s) && s.startsWith(ADDON_SETTINGS)) {
			PreferenceSet p = addonSettingsSets.get(s.substring(ADDON_SETTINGS.length()));
			if (p != null) adapter.setPreferenceSet(p);
		} else if ((pendingInput == SHOW_KEY_BINDINGS) && (keyBindingsSet != null)) {
			adapter.setPreferenceSet(keyBindingsSet);
		} else if ((pendingInput == SHOW_INTERFACE) || (pendingInput == SHOW_AA_INTERFACE)) {
			// The old Interface lists became the Layout and navigation page.
			boolean aa = pendingInput == SHOW_AA_INTERFACE;
			MainActivityDelegate d = MainActivityDelegate.get(requireContext());
			// Not inside this fragment's own creation: a second switch started from here leaves the bars half set up.
			d.getBody().post(() -> d.showFragment(R.id.layout_settings_fragment, aa));
		} else if ((pendingInput instanceof String cs) && cs.startsWith(CATEGORY)) {
			try {
				showCategory(Integer.parseInt(cs.substring(CATEGORY.length())));
			} catch (NumberFormatException ignore) {
				// Not one of ours.
			}
		} else if ((pendingInput instanceof String ks) && ks.startsWith(KEY_SETTINGS)) {
			PreferenceSet p = keySettingsSets.get(ks.substring(KEY_SETTINGS.length()));
			if (p != null) {
				adapter.setPreferenceSet(p);
				returnToKeyTesterFrom = p;
			}
		}
		pendingInput = null;
	}

	@Override
	public CharSequence getTitle() {
		if (adapter != null) {
			var set = adapter.getPreferenceSet();
			if (set.getParent() != null) {
				var o = set.get();
				return (o.ctitle != null) ? o.ctitle : getResources().getString(o.title);
			}
		}
		return getResources().getString(R.string.settings);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		// The window's width, so split screen follows.
		twoPane = SettingsSplit.isWide(MainActivityDelegate.get(requireContext()));
		return inflater.inflate(me.aap.utils.R.layout.pref_list_view, container, false);
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		if (adapter != null) outState.putInt("id", adapter.getPreferenceSet().getId());
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
		super.onViewCreated(view, state);
		MainActivityDelegate.getActivityDelegate(requireContext()).onSuccess(a -> {
			adapter = createAdapter(a);
			a.addBroadcastListener(this);
			a.getPrefs().addBroadcastListener(this);

			RecyclerView listView = view.findViewById(me.aap.utils.R.id.prefs_list_view);
			listView.setHasFixedSize(true);
			listView.setLayoutManager(new LinearLayoutManager(getContext()));
			listView.setAdapter(adapter);
			a.insetScrollableContent(listView);

			if (twoPane && (rootSet != null)) {
				// The categories are the list beside the page; the page starts on the one shown last, else the first.
				PreferenceSet start = null;
				String sel = SettingsNav.selected();
				if ((sel != null) && sel.startsWith("set:")) {
					try {
						start = rootSet.find(Integer.parseInt(sel.substring(4)));
					} catch (NumberFormatException ignore) {
						// Falls back to the first.
					}
				}
				if (start == null) start = firstCategory(rootSet);
				if (start != null) adapter.setPreferenceSet(start);
			}

			if (state != null) {
				PreferenceSet p = (rootSet != null ? rootSet : adapter.getPreferenceSet()).find(state.getInt("id", ID_NULL));
				if (p != null) adapter.setPreferenceSet(p);
			}

			applyPendingInput();
		});
	}

	@Override
	public void onDestroyView() {
		lastRoot = null;
		MainActivityDelegate.getActivityDelegate(requireContext()).onSuccess(this::cleanUp);
		super.onDestroyView();
	}

	@Nullable
	private static PreferenceSet firstCategory(PreferenceSet root) {
		for (Object o : root.getPreferences()) {
			if (o instanceof PreferenceSet s) return s;
		}
		return null;
	}

	/** The direct child of the root that {@code set} is, or sits under. */
	@Nullable
	private static PreferenceSet categoryOf(PreferenceSet set) {
		PreferenceSet p = set;
		while ((p != null) && (p.getParent() != null) && (p.getParent().getParent() != null)) p = p.getParent();
		return ((p != null) && (p.getParent() != null)) ? p : null;
	}

	/** The page now showing: the list beside it (a wide window) marks the category it is in. */
	private void pageShown(PreferenceSet set) {
		if (!twoPane) return;
		PreferenceSet cat = categoryOf(set);
		if (cat != null) SettingsNav.select(SettingsNav.categoryKey(cat.getId()));
	}

	/**
	 * What the list beside the pages shows: the root's categories, and the buttons at the root that open a page of their own
	 * (Home, Captions, Lyrics, ...).
	 */
	private static void publishNav(PreferenceSet root, Context ctx) {
		List<SettingsNav.Entry> out = new java.util.ArrayList<>();
		for (var o : root.getPreferences()) {
			try {
				PreferenceView.Opts opts = o.get();
				if ((opts.visibility != null) && !opts.visibility.get()) continue;
				CharSequence title = (opts.ctitle != null) ? opts.ctitle : ((opts.title != ID_NULL) ? ctx.getString(opts.title) : null);
				if (title == null) continue;
				CharSequence sub = (opts.csubtitle != null) ? opts.csubtitle : ((opts.subtitle != ID_NULL) ? ctx.getString(opts.subtitle) : null);
				if (o instanceof PreferenceSet s) {
					out.add(new SettingsNav.Entry(SettingsNav.categoryKey(s.getId()), title, sub, opts.icon, s.getId(), null));
				} else if ((opts instanceof PreferenceView.ButtonOpts b) && (b.onClick != null)) {
					out.add(new SettingsNav.Entry("btn:" + title, title, sub, opts.icon, 0, b.onClick));
				}
			} catch (RuntimeException ex) {
				Log.e(ex, "A settings entry could not be listed");
			}
		}
		SettingsNav.publish(out);
	}

	private void cleanUp(MainActivityDelegate a) {
		a.removeBroadcastListener(this);
		a.getPrefs().removeBroadcastListener(this);
		FermataApplication.get().getAddonManager()
				.removeBroadcastListeners(l -> l instanceof AddonPrefsBuilder);
		if (adapter != null) adapter.onDestroy();
		adapter = null;
		rootSet = null;
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (e == ACTIVITY_DESTROY) {
			cleanUp(a);
			// Its buttons belong to this activity.
			SettingsNav.clear();
		}
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<Pref<?>> prefs) {
		MainActivityDelegate.getActivityDelegate(requireContext()).onSuccess(a -> {
			if (adapter == null) return;
			if (MainActivityPrefs.hasTextIconSizePref(a, prefs)) adapter.setSize(a.getTextIconSize());
		});
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		// Left for another screen: Back from that key page goes up as usual next time.
		if (hidden) returnToKeyTesterFrom = null;
	}

	@Override
	public boolean isRootPage() {
		if (adapter == null) return true;
		PreferenceSet cur = adapter.getPreferenceSet();
		// On a tablet a category is the top of the right pane.
		return (cur.getParent() == null) || (twoPane && (cur.getParent() == rootSet));
	}

	@Override
	public boolean onBackPressed() {
		if (adapter == null) return false;
		if ((returnToKeyTesterFrom != null) && (adapter.getPreferenceSet() == returnToKeyTesterFrom)) {
			returnToKeyTesterFrom = null;
			getActivityDelegate().showFragment(R.id.key_tester_fragment);
			return true;
		}
		returnToKeyTesterFrom = null;
		PreferenceSet p = adapter.getPreferenceSet().getParent();
		if ((p == null) || (twoPane && (p == rootSet))) return false;
		adapter.setPreferenceSet(p);
		return true;
	}

	public static void addDelayPrefs(PreferenceSet set, PreferenceStore store,
																	 Pref<IntSupplier> pref,
																	 @StringRes int title, ChangeableCondition visibility) {
		set.addIntPref(o -> {
			o.store = store;
			o.pref = pref;
			o.title = title;
			o.seekMin = -5000;
			o.seekMax = 5000;
			o.seekScale = 50;
			o.ems = 3;
			o.visibility = visibility;
		});
	}

	public static void addSubSizePrefs(PreferenceSet set, PreferenceStore store,
																		 ChangeableCondition visibility) {
		if (store instanceof MediaLib.PlayableItem p && !p.isVideo()) return;
		set.addFloatPref(o -> {
			o.store = store;
			o.pref = SUB_SIZE;
			o.title = R.string.subtitles_size;
			o.scale = 0.05f;
			o.seekMin = 10;
			o.seekMax = 40;
			o.visibility = visibility;
		});
	}

	public static void addAudioPrefs(PreferenceSet set, PreferenceStore store, boolean isCar) {
		addDelayPrefs(set, store, MediaLibPrefs.AUDIO_DELAY, R.string.audio_delay, null);

		if (BuildConfig.AUTO) {
			addDelayPrefs(set, store, MediaLibPrefs.AUDIO_DELAY_AA, R.string.audio_delay_aa, null);
			set.markLastCarOnly();
		}

		if (!isCar) {
			set.addStringPref(o -> {
				Locale locale = Locale.getDefault();
				o.store = store;
				o.pref = MediaLibPrefs.AUDIO_LANG;
				o.title = R.string.preferred_audio_lang;
				o.stringHint = locale.getLanguage() + ' ' + locale.getISO3Language();
			});
			set.addStringPref(o -> {
				o.store = store;
				o.pref = MediaLibPrefs.AUDIO_KEY;
				o.title = R.string.preferred_audio_key;
				o.stringHint = "studio1 studio2 default";
			});
		}
	}

	public static void addSubtitlePrefs(Context ctx, PreferenceSet set, PreferenceStore store,
																			boolean isCar) {
		set.addBooleanPref(o -> {
			o.store = store;
			o.pref = MediaLibPrefs.SUB_ENABLED;
			o.title = R.string.display_subtitles;
		});

		var enabled = PrefCondition.create(store, MediaLibPrefs.SUB_ENABLED);
		addAutoSubPrefs(ctx, set, store, enabled.copy(), true);
		addDelayPrefs(set, store, MediaLibPrefs.SUB_DELAY, R.string.subtitle_delay, enabled.copy());
		addSubSizePrefs(set, store, enabled.copy());

		if (!isCar) {
			set.addStringPref(o -> {
				o.store = store;
				o.pref = MediaLibPrefs.SUB_LANG;
				o.title = R.string.preferred_sub_lang;
				o.stringHint = SUB_LANG.getDefaultValue().get();
				o.visibility = enabled.copy();
			});
			set.addStringPref(o -> {
				o.store = store;
				o.pref = MediaLibPrefs.SUB_KEY;
				o.title = R.string.preferred_sub_key;
				o.stringHint = "full, forced";
				o.visibility = enabled.copy();
			});
		}
	}

	public static void addAutoSubPrefs(Context ctx, PreferenceSet set, PreferenceStore ps,
																		 ChangeableCondition cond, boolean isGlobalSettings) {
		var subGen = FermataApplication.get().getAddonManager().getAddon(SubGenAddon.class);
		if (subGen == null) return;

		var subGenSet = set.subSet(o -> {
			o.title = R.string.subtitles_auto;
			o.subtitle = R.string.prefs_sub_subgen;
			o.visibility = cond;
			o.icon = R.drawable.subgen;
		});
		subGenSet.addBooleanPref(o -> {
			o.store = ps;
			o.title = R.string.enable;
			o.pref = SubGenAddon.ENABLED;
		});
		subGen.contributeSettings(ctx, ps, subGenSet, PrefCondition.create(ps, SubGenAddon.ENABLED),
				false);
	}

	/** The context the pages are built with: the activity's, so a page can be built with no screen (see {@link #prepareNav}). */
	private Context buildContext;

	/**
	 * Builds the settings once without showing them, so the list of categories beside the settings pages (a wide window) is
	 * there for a page opened before Settings ever was (Home settings from Home's chips).
	 */
	public static void prepareNav(MainActivityDelegate a) {
		if (!SettingsNav.entries().isEmpty()) return;
		try {
			SettingsFragment f = new SettingsFragment();
			f.createAdapter(a);
			f.cleanUp(a);
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to prepare the settings list");
		}
	}

	private PreferenceViewAdapter createAdapter(MainActivityDelegate a) {
		buildContext = a.getContext();
		MediaLibPrefs mediaPrefs = a.getMediaServiceBinder().getLib().getPrefs();
		int[] timeUnits =
				new int[]{R.string.time_unit_second, R.string.time_unit_minute,
						R.string.time_unit_percent};
		boolean isCar = a.isCarActivityNotMirror();
		PreferenceSet set = new PreferenceSet();
		PreferenceSet sub1;
		PreferenceSet sub2;

		addHomePrefs(a, set);

		// Appearance: how the app looks. Layout and navigation is a page of its own (LayoutSettingsFragment).
		sub1 = set.subSet(o -> {
			o.title = R.string.appearance_prefs;
			o.subtitle = R.string.appearance_prefs_sub;
			o.icon = R.drawable.view_grid;
		});
		appearanceSet = sub1;
		sub1.addButton(o -> {
			o.title = R.string.theme;
			o.subtitle = R.string.theme_settings_sub;
			o.icon = R.drawable.view_grid;
			o.onClick = () -> a.showFragment(R.id.theme_settings_fragment);
		});
		addGlassPrefs(a, sub1);
		sub1.addListPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.LOCALE;
			o.title = R.string.lang;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.removeDefault = false;
			o.cmp = String::compareTo;
			o.stringValues = CollectionUtils.mapToArray(MainActivityPrefs.Lang.getValues(),
					v -> v.locale.getDisplayName(), String[]::new);
		});

		set.addButton(o -> {
			o.title = R.string.network_settings;
			o.subtitle = R.string.network_settings_sub;
			o.icon = R.drawable.wifi;
			o.onClick = () -> a.showFragment(R.id.network_settings_fragment);
		});
		set.addButton(o -> {
			o.title = R.string.layout_settings;
			o.subtitle = R.string.layout_settings_sub;
			o.icon = R.drawable.fab;
			o.onClick = () -> a.showFragment(R.id.layout_settings_fragment, a.isCarActivity());
		});

		keyBindingsSet = sub1 = set.subSet(o -> {
			o.title = R.string.key_bindings;
			o.subtitle = R.string.prefs_sub_keys;
			o.icon = R.drawable.keyboard;
		});
		sub1.addBooleanPref(o -> {
			o.store = Key.getPrefs();
			o.pref = Key.BIND_TRANSPORT;
			o.title = R.string.key_bind_transport;
			o.subtitle = R.string.key_bind_transport_sub;
		});
		// Its own page: the switch, and what the keys do with it on.
		sub1.addButton(o -> {
			o.title = R.string.key_car_mode;
			o.icon = R.drawable.car;
			o.subtitle = R.string.key_car_mode_sub;
			o.onClick = () -> a.showFragment(R.id.car_mode_fragment);
		});
		// Right above the keys themselves.
		sub1.addButton(o -> {
			o.title = R.string.key_tester;
			o.icon = R.drawable.key_simulator;
			o.subtitle = R.string.key_tester_sub;
			o.onClick = () -> a.showFragment(R.id.key_tester_fragment);
		});
		keySettingsSets.clear();
		var actions = Action.getAll();
		var actionNames = new int[actions.size()];
		var actionOrdinals = new int[actions.size()];
		for (var action : actions) {
			actionNames[action.ordinal()] = action.getName();
			actionOrdinals[action.ordinal()] = action.ordinal();
		}

		for (var k : Key.getAll()) {
			sub2 = sub1.subSet(o -> {
				o.ctitle = k.name();
				o.icon = R.drawable.keyboard;
			});
			keySettingsSets.put(k.name(), sub2);
			sub2.addListPref(o -> {
				o.store = Key.getPrefs();
				o.pref = k.getActionPref();
				o.title = R.string.key_on_click;
				o.subtitle = R.string.string_format;
				o.values = actionNames;
				o.valuesMap = actionOrdinals;
				o.formatSubtitle = true;
			});
			sub2.addListPref(o -> {
				o.store = Key.getPrefs();
				o.pref = k.getLongActionPref();
				o.title = R.string.key_on_long_click;
				o.subtitle = R.string.string_format;
				o.values = actionNames;
				o.valuesMap = actionOrdinals;
				o.formatSubtitle = true;
			});
			sub2.addListPref(o -> {
				o.store = Key.getPrefs();
				o.pref = k.getDblActionPref();
				o.title = R.string.key_on_dbl_click;
				o.subtitle = R.string.string_format;
				o.values = actionNames;
				o.valuesMap = actionOrdinals;
				o.formatSubtitle = true;
			});
		}


		sub1 = set.subSet(o -> {
			o.title = R.string.playback_settings;
			o.subtitle = R.string.prefs_sub_playback;
			o.icon = R.drawable.playback_settings;
		});
		sub1.addBooleanPref(o -> {
			o.store = mediaPrefs;
			o.pref = BrowsableItemPrefs.PLAY_NEXT;
			o.title = R.string.play_next_on_completion;
		});
		addTapAction(a, sub1, MainActivityPrefs.TAP_ACTION);
		if (MusicPlayer.isEnabled()) {
			sub1.addListPref(o -> {
				o.store = mediaPrefs;
				o.removeDefault = false;
				o.pref = MediaLibPrefs.DEFAULT_PLAY_MODE;
				o.title = R.string.default_play_mode;
				o.subtitle = R.string.string_format;
				o.formatSubtitle = true;
				o.values = new int[]{R.string.play_mode_video, R.string.play_mode_music};
			});
		}

		sub1 = set.subSet(o -> {
			o.title = R.string.playback_control;
			o.subtitle = R.string.prefs_sub_playback_control;
			o.icon = R.drawable.speed;
		});

		sub2 = sub1.subSet(o -> {
			o.title = R.string.rw_ff_click;
			o.subtitle = R.string.prefs_sub_rw_ff_click;
			o.icon = R.drawable.ff;
		});
		sub2.addIntPref(o -> {
			o.store = a.getPlaybackControlPrefs();
			o.pref = PlaybackControlPrefs.RW_FF_TIME;
			o.title = R.string.time;
			o.seekMax = 60;
		});
		sub2.addListPref(o -> {
			o.store = a.getPlaybackControlPrefs();
			o.pref = PlaybackControlPrefs.RW_FF_TIME_UNIT;
			o.title = R.string.time_unit;
			o.subtitle = R.string.time_unit_sub;
			o.formatSubtitle = true;
			o.values = timeUnits;
		});

		sub2 = sub1.subSet(o -> {
			o.title = R.string.rw_ff_long_click;
			o.subtitle = R.string.prefs_sub_rw_ff_long_click;
			o.icon = R.drawable.ff;
		});
		sub2.addIntPref(o -> {
			o.store = a.getPlaybackControlPrefs();
			o.pref = PlaybackControlPrefs.RW_FF_LONG_TIME;
			o.title = R.string.time;
			o.seekMax = 60;
		});
		sub2.addListPref(o -> {
			o.store = a.getPlaybackControlPrefs();
			o.pref = PlaybackControlPrefs.RW_FF_LONG_TIME_UNIT;
			o.title = R.string.time_unit;
			o.subtitle = R.string.time_unit_sub;
			o.formatSubtitle = true;
			o.values = timeUnits;
		});

		sub2 = sub1.subSet(o -> {
			o.title = R.string.prev_next_long_click;
			o.subtitle = R.string.prefs_sub_prev_next_long;
			o.icon = R.drawable.next;
		});
		sub2.addIntPref(o -> {
			o.store = a.getPlaybackControlPrefs();
			o.pref = PlaybackControlPrefs.PREV_NEXT_LONG_TIME;
			o.title = R.string.time;
			o.seekMax = 60;
		});
		sub2.addListPref(o -> {
			o.store = a.getPlaybackControlPrefs();
			o.pref = PlaybackControlPrefs.PREV_NEXT_LONG_TIME_UNIT;
			o.title = R.string.time_unit;
			o.subtitle = R.string.time_unit_sub;
			o.formatSubtitle = true;
			o.values = timeUnits;
		});

		sub1.addBooleanPref(o -> {
			o.store = a.getPlaybackControlPrefs();
			o.pref = PlaybackControlPrefs.PLAY_PAUSE_STOP;
			o.title = R.string.play_pause_stop;
		});

		sub2 = sub1.subSet(o -> {
			o.title = R.string.video_control;
			o.subtitle = R.string.prefs_sub_video_control;
			o.icon = R.drawable.video;
		});
		sub2.addIntPref(o -> {
			o.store = a.getPlaybackControlPrefs();
			o.pref = PlaybackControlPrefs.VIDEO_CONTROL_START_DELAY;
			o.title = R.string.video_control_start_delay;
			o.seekMax = 60;
		});
		sub2.addIntPref(o -> {
			o.store = a.getPlaybackControlPrefs();
			o.pref = PlaybackControlPrefs.VIDEO_CONTROL_TOUCH_DELAY;
			o.title = R.string.video_control_touch_delay;
			o.seekMax = 60;
		});
		sub2.addIntPref(o -> {
			o.store = a.getPlaybackControlPrefs();
			o.pref = PlaybackControlPrefs.VIDEO_CONTROL_SEEK_DELAY;
			o.title = R.string.video_control_seek_delay;
			o.seekMax = 60;
		});

		if (BuildConfig.AUTO) {
			sub2.addBooleanPref(o -> {
				o.store = a.getPlaybackControlPrefs();
				o.pref = PlaybackControlPrefs.VIDEO_AA_SHOW_STATUS;
				o.title = R.string.video_aa_show_status;
			});
			sub2.markLastCarOnly();
		}

		if (!a.isCarActivityNotMirror()) {
			sub1 = set.subSet(o -> {
				o.title = R.string.voice_control;
				o.subtitle = R.string.prefs_sub_voice;
				o.icon = R.drawable.record_voice;
			});
			sub1.addBooleanPref(o -> {
				o.title = R.string.enable;
				o.pref = VOICE_CONTROl_ENABLED;
				o.store = a.getPrefs();
			});
			sub1.addBooleanPref(o -> {
				o.title = R.string.voice_control_fb;
				o.subtitle = R.string.voice_control_sub_long;
				o.pref = VOICE_CONTROl_FB;
				o.store = a.getPrefs();
				o.visibility = PrefCondition.create(a.getPrefs(), VOICE_CONTROl_ENABLED);
			});
			sub1.addStringPref(o -> {
				o.title = R.string.voice_control_subst;
				o.subtitle = R.string.voice_control_subst_sub;
				o.hint = R.string.voice_control_subst_hint;
				o.pref = VOICE_CONTROL_SUBST;
				o.store = a.getPrefs();
				o.maxLines = 10;
				o.visibility = PrefCondition.create(a.getPrefs(), VOICE_CONTROl_ENABLED);
			});
			sub1.addTtsLocalePref(o -> {
				o.title = R.string.lang;
				o.subtitle = me.aap.fermata.R.string.string_format;
				o.pref = VOICE_CONTROL_LANG;
				o.store = a.getPrefs();
				o.formatSubtitle = true;
				o.visibility = PrefCondition.create(a.getPrefs(), VOICE_CONTROl_ENABLED);
			});
		}

		PrefCondition<BooleanSupplier> exoCond =
				PrefCondition.create(mediaPrefs, MediaLibPrefs.EXO_ENABLED);
		PrefCondition<BooleanSupplier> vlcCond =
				PrefCondition.create(mediaPrefs, MediaLibPrefs.VLC_ENABLED);
		Consumer<PreferenceView.ListOpts> initList = o -> {
			if (o.visibility == null) o.visibility = exoCond.or(vlcCond);

			o.values =
					new int[]{R.string.engine_mp_name, R.string.engine_exo_name, R.string.engine_vlc_name};
			o.valuesMap = new int[]{MEDIA_ENG_MP, MEDIA_ENG_EXO, MEDIA_ENG_VLC};
			o.valuesFilter = i -> {
				if (i == 1) return exoCond.get();
				if (i == 2) return vlcCond.get();
				return true;
			};
		};
		sub1 = set.subSet(o -> {
			o.title = R.string.engine_prefs;
			o.subtitle = R.string.prefs_sub_engine;
			o.icon = R.drawable.media_engine;
		});
		sub1.addBooleanPref(o -> {
			o.store = mediaPrefs;
			o.removeDefault = false;
			o.pref = MediaLibPrefs.EXO_ENABLED;
			o.title = R.string.enable_exoplayer;
		});
		sub1.addBooleanPref(o -> {
			o.store = mediaPrefs;
			o.removeDefault = false;
			o.pref = MediaLibPrefs.VLC_ENABLED;
			o.title = R.string.enable_vlcplayer;
		});
		sub1.addListPref(o -> {
			o.store = mediaPrefs;
			o.removeDefault = false;
			o.pref = MediaLibPrefs.AUDIO_ENGINE;
			o.title = R.string.preferred_audio_engine;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.initList = initList;
		});
		sub1.addListPref(o -> {
			o.store = mediaPrefs;
			o.removeDefault = false;
			o.pref = MediaLibPrefs.VIDEO_ENGINE;
			o.title = R.string.preferred_video_engine;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.initList = initList;
		});
		sub1.addListPref(o -> {
			o.store = mediaPrefs;
			o.removeDefault = false;
			o.pref = MediaLibPrefs.MEDIA_SCANNER;
			o.title = R.string.preferred_media_scanner;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.visibility = vlcCond;
			o.values = new int[]{R.string.preferred_media_scanner_default,
					R.string.preferred_media_scanner_system, R.string.engine_vlc_name};
			o.valuesMap = new int[]{MEDIA_SCANNER_DEFAULT, MEDIA_SCANNER_SYSTEM, MEDIA_SCANNER_VLC};
		});

		sub1 = set.subSet(o -> {
			o.title = R.string.video_settings;
			o.subtitle = R.string.prefs_sub_video;
			o.icon = R.drawable.video;
		});
		sub1.addListPref(o -> {
			o.store = mediaPrefs;
			o.pref = MediaLibPrefs.VIDEO_SCALE;
			o.title = R.string.video_scaling;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.video_scaling_best, R.string.video_scaling_fill,
					R.string.video_scaling_orig, R.string.video_scaling_4, R.string.video_scaling_16};
		});
		sub1.addListPref(o -> {
			o.store = mediaPrefs;
			o.pref = MediaLibPrefs.HW_ACCEL;
			o.title = R.string.hw_accel;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.values =
					new int[]{R.string.hw_accel_auto, R.string.hw_accel_full, R.string.hw_accel_decoding,
							R.string.hw_accel_disabled};
			o.visibility = vlcCond;
		});
		sub1.addBooleanPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.SYS_BARS_ON_VIDEO_TOUCH;
			o.title = R.string.sys_bars_on_video_touch;
		});
		sub1.addBooleanPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.LANDSCAPE_VIDEO;
			o.title = R.string.play_video_landscape;
		});
		sub1.addBooleanPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.CHANGE_BRIGHTNESS;
			o.title = R.string.change_brightness;
		});
		sub1.addIntPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.BRIGHTNESS;
			o.title = R.string.video_brightness;
			o.subtitle = R.string.change_brightness_sub;
			o.seekMin = 0;
			o.seekMax = 255;
			o.visibility = PrefCondition.create(a.getPrefs(), MainActivityPrefs.CHANGE_BRIGHTNESS);
		});

		sub1.addIntPref(o -> {
			o.store = mediaPrefs;
			o.pref = MediaPrefs.WATCHED_THRESHOLD;
			o.title = R.string.watched_threshold;
			o.subtitle = R.string.watched_threshold_sub;
			o.seekMin = 0;
			o.seekMax = 100;
			o.seekScale = 5;
		});

		// The panels that open beside fullscreen video: each can be switched off (the full page opens then) and has its own width.
		PreferenceSet panels = sub1.subSet(o -> {
			o.title = R.string.in_video_panel;
			o.subtitle = R.string.in_video_panel_sub;
			o.icon = R.drawable.comments;
		});
		panels.addBooleanPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.COMMENTS_SPLIT;
			o.title = R.string.comments_split;
			o.subtitle = R.string.comments_split_sub;
		});
		panels.addIntPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.COMMENTS_SPLIT_PERCENT;
			o.title = R.string.comments_split_width;
			o.seekMin = 20;
			o.seekMax = 60;
			o.seekScale = 2;
			o.visibility = PrefCondition.create(a.getPrefs(), MainActivityPrefs.COMMENTS_SPLIT);
		});
		panels.addIntPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.SIDE_PANEL_EFFECTS_PERCENT;
			o.title = R.string.side_panel_effects_width;
			o.seekMin = 8;
			o.seekMax = 60;
			o.seekScale = 2;
		});
		panels.addBooleanPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.SIDE_PANEL_YOUTUBE;
			o.title = R.string.side_panel_youtube;
			o.subtitle = R.string.side_panel_sub;
		});
		panels.addIntPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.SIDE_PANEL_YOUTUBE_PERCENT;
			o.title = R.string.side_panel_youtube_width;
			o.seekMin = 8;
			o.seekMax = 60;
			o.seekScale = 2;
			o.visibility = PrefCondition.create(a.getPrefs(), MainActivityPrefs.SIDE_PANEL_YOUTUBE);
		});
		panels.addBooleanPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.SIDE_PANEL_CAPTIONS;
			o.title = R.string.side_panel_captions;
			o.subtitle = R.string.side_panel_sub;
		});
		panels.addIntPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.SIDE_PANEL_CAPTIONS_PERCENT;
			o.title = R.string.side_panel_captions_width;
			o.seekMin = 8;
			o.seekMax = 60;
			o.seekScale = 2;
			o.visibility = PrefCondition.create(a.getPrefs(), MainActivityPrefs.SIDE_PANEL_CAPTIONS);
		});


		dimSettingsSet = sub2 = sub1.subSet(o -> {
			o.title = R.string.dim_prefs;
			o.subtitle = R.string.prefs_sub_dim;
			o.icon = R.drawable.dim_screen;
		});
		sub2.addBooleanPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.DIM_ENABLED;
			o.title = R.string.dim_enable;
		});
		var dimEnabledCond = PrefCondition.create(a.getPrefs(), MainActivityPrefs.DIM_ENABLED);
		sub2.addIntPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.DIM_OPACITY;
			o.title = R.string.dim_opacity;
			o.seekMin = 0;
			o.seekMax = 100;
			o.visibility = dimEnabledCond.copy();
		});
		sub2.addListPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.DIM_COLOR_PRESET;
			o.title = R.string.dim_color;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.color_black, R.string.color_blue_light, R.string.color_red,
					R.string.color_deep_red, R.string.color_amber, R.string.color_yellow,
					R.string.color_custom};
			o.visibility = dimEnabledCond.copy();
		});
		sub2.addIntPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.DIM_COLOR_CUSTOM_R;
			o.title = R.string.dim_color_r;
			o.seekMin = 0;
			o.seekMax = 255;
			o.visibility = dimCustomColorCond(a);
		});
		sub2.addIntPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.DIM_COLOR_CUSTOM_G;
			o.title = R.string.dim_color_g;
			o.seekMin = 0;
			o.seekMax = 255;
			o.visibility = dimCustomColorCond(a);
		});
		sub2.addIntPref(o -> {
			o.store = a.getPrefs();
			o.pref = MainActivityPrefs.DIM_COLOR_CUSTOM_B;
			o.title = R.string.dim_color_b;
			o.seekMin = 0;
			o.seekMax = 255;
			o.visibility = dimCustomColorCond(a);
		});

		sub2 = sub1.subSet(o -> {
			o.title = R.string.audio;
			o.subtitle = R.string.prefs_sub_audio;
			o.visibility = vlcCond;
			o.icon = R.drawable.audiotrack;
		});
		addAudioPrefs(sub2, mediaPrefs, isCar);

		sub1 = set.subSet(o -> {
			o.title = R.string.subtitles;
			o.subtitle = R.string.prefs_sub_subtitles;
			o.icon = R.drawable.subtitles;
		});
		addSubtitlePrefs(a.getContext(), sub1, mediaPrefs, isCar);

		// Their own pages, with a live preview each (see TextStyleFragment).
		set.addButton(o -> {
			o.title = R.string.captions;
			o.subtitle = R.string.prefs_sub_captions;
			o.icon = R.drawable.captions;
			o.onClick = () -> a.showFragment(R.id.captions_fragment);
		});
		set.addButton(o -> {
			o.title = R.string.lyrics;
			o.subtitle = R.string.prefs_sub_lyrics;
			o.icon = R.drawable.lyrics;
			o.onClick = () -> a.showFragment(R.id.lyrics_fragment);
		});

		set.addButton(o -> {
			o.title = R.string.private_mode_prefs;
			o.subtitle = R.string.prefs_sub_private;
			o.icon = R.drawable.private_mode;
			o.onClick = () -> a.showFragment(R.id.privacy_settings_fragment);
		});

		addAddons(set);

		if (!a.isCarActivityNotMirror()) addBackupRestore(a, set);

		sub1 = set.subSet(o -> {
			o.title = R.string.other;
			o.subtitle = R.string.prefs_sub_other;
			o.icon = R.drawable.settings;
		});
		// The welcome and setup wizard again, from the start (the phone's screen: it sets the phone up).
		if (!a.isCarActivityNotMirror()) {
			sub1.addButton(o -> {
				o.title = R.string.welcome_settings_title;
				o.subtitle = R.string.welcome_settings_sub;
				o.icon = R.drawable.music;
				o.onClick = () -> me.aap.fermata.ui.welcome.WelcomeWizard.show(a);
			});
		}
		addSpotifyImport(a, sub1);
		addShareSettings(sub1);
		addShareAppButton(a, sub1);
		addStoragePrefs(a, sub1);
		if (BuildConfig.AUTO) {
			sub1.addBooleanPref(o -> {
				o.store = a.getPrefs();
				o.pref = MainActivityPrefs.CHECK_UPDATES;
				o.title = R.string.check_updates;
			});
			if (!a.isCarActivityNotMirror()) {
				sub1.addButton(o -> {
					o.title = R.string.open_log;
					o.onClick = () -> openLog(a);
				});
			}
		}

		addDiagnostics(a, set);

		// The eleven categories (moves only, same prefs): entries are found by title wherever they were built.
		groupRoot(set, R.string.settings_group_playback, R.string.settings_group_playback_sub,
				R.string.playback_settings, R.string.playback_control, R.string.engine_prefs);
		groupRoot(set, R.string.settings_group_subtitles, R.string.settings_group_subtitles_sub,
				R.string.subtitles, R.string.captions, R.string.lyrics);
		// The car has no voice control, so its Controls subtitle leaves it out.
		groupRoot(set, 1, R.string.settings_group_controls,
				isCar ? R.string.settings_group_controls_car_sub : R.string.settings_group_controls_sub,
				R.string.key_bindings, R.string.voice_control);
		// Spotify import and Share are features like the add-ons; storage goes with backup.
		PreferenceSet addonsSet = (PreferenceSet) findByTitle(set, R.string.addons);
		if (addonsSet != null) {
			List<me.aap.utils.function.Supplier<? extends PreferenceView.Opts>> feats = new java.util.ArrayList<>();
			for (int t : new int[]{R.string.spotify_import, R.string.share}) {
				var o = findByTitle(set, t);
				if (o != null) feats.add(o);
			}
			addonsSet.adopt(feats);
		}
		groupRoot(set, R.string.settings_group_backup, R.string.settings_group_backup_sub,
				R.string.backup_restore, R.string.storage_prefs, R.string.other, R.string.diagnostics);
		orderRoot(set, R.string.home, R.string.appearance_prefs, R.string.layout_settings,
				R.string.settings_group_playback, R.string.video_settings, R.string.settings_group_subtitles,
				R.string.settings_group_controls, R.string.network_settings, R.string.private_mode_prefs,
				R.string.addons, R.string.settings_group_backup);

		rootSet = set;
		lastRoot = set;
		publishNav(set, a.getContext());
		return new PreferenceViewAdapter(set) {
			@Override
			public void setPreferenceSet(PreferenceSet set) {
				super.setPreferenceSet(set);
				pageShown(set);
				a.fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
			}
		};
	}

	/** One match of a search: where it is and what to do when it is picked. */
	public record Hit(CharSequence path, CharSequence title, @Nullable CharSequence sub, int icon, Runnable open) {
	}

	/** The last tree built (Settings itself or a throwaway one), what the search page looks through. */
	@Nullable
	private static PreferenceSet lastRoot;

	/** The tree kept for the search is dropped (its entries hold the activity). */
	public static void clearSearchRoot() {
		lastRoot = null;
	}

	private static PreferenceSet searchRoot(MainActivityDelegate a) {
		PreferenceSet r = lastRoot;
		if (r == null) {
			try {
				SettingsFragment f = new SettingsFragment();
				f.createAdapter(a);
				f.cleanUp(a);
			} catch (RuntimeException ex) {
				Log.e(ex, "Failed to build the settings for the search");
			}
			r = lastRoot;
		}
		return r;
	}

	/** One line of the search: what is shown and the lower-case text it is looked for in. */
	public record Indexed(Hit hit, String hay) {
	}

	/**
	 * Every setting of every page (the old list pages through their tree, the code-built ones from a table), read once with its
	 * text made lower case: a search is then only a look through this list, whatever is typed.
	 */
	public static List<Indexed> buildSearchIndex(MainActivityDelegate a) {
		List<Hit> all = new java.util.ArrayList<>();
		PreferenceSet root = searchRoot(a);
		if (root != null) collectHits(a, root, "", all, 0);
		SettingsNative.addAll(a, all);
		List<Indexed> out = new java.util.ArrayList<>(all.size());
		for (Hit h : all) {
			String hay = h.title().toString().toLowerCase();
			if (h.sub() != null) hay = hay + '\n' + h.sub().toString().toLowerCase();
			out.add(new Indexed(h, hay));
		}
		return out;
	}

	/** The first {@code max} lines of the index that mention {@code needle} (lower case). */
	public static List<Hit> search(List<Indexed> index, String needle, int max) {
		List<Hit> out = new java.util.ArrayList<>();
		if (needle.isEmpty()) return out;
		for (Indexed i : index) {
			if (i.hay().contains(needle)) {
				out.add(i.hit());
				if (out.size() >= max) break;
			}
		}
		return out;
	}

	private static void collectHits(MainActivityDelegate a, PreferenceSet set, String path, List<Hit> out, int parentIcon) {
		Context ctx = a.getContext();
		for (var o : set.getPreferences()) {
			try {
				PreferenceView.Opts opts = o.get();
				CharSequence title = (opts.ctitle != null) ? opts.ctitle : ((opts.title != ID_NULL) ? ctx.getString(opts.title) : null);
				if (title == null) continue;
				CharSequence sub = (opts.csubtitle != null) ? opts.csubtitle : ((opts.subtitle != ID_NULL) ? ctx.getString(opts.subtitle) : null);
				// A line that is a pattern for the value picked (%1$s ...) says nothing here: left out.
				if ((sub != null) && (sub.toString().indexOf('%') >= 0)) sub = null;
				// A row with no picture of its own takes its page's.
				int icon = ((opts.icon == ID_NULL) || (opts.icon == 0)) ? parentIcon : opts.icon;
				String where = path.isEmpty() ? ctx.getString(R.string.settings) : path;
				if (o instanceof PreferenceSet child) {
					out.add(new Hit(where, title, sub, icon, () -> a.showFragment(R.id.settings_fragment, category(child.getId()))));
					collectHits(a, child, path.isEmpty() ? title.toString() : (path + " > " + title), out, icon);
				} else {
					Runnable open = (opts instanceof PreferenceView.ButtonOpts bo && bo.onClick != null) ?
							() -> bo.onClick.run() : () -> a.showFragment(R.id.settings_fragment, category(set.getId()));
					out.add(new Hit(where, title, sub, icon, open));
				}
			} catch (RuntimeException ex) {
				Log.e(ex, "A settings entry could not be searched");
			}
		}
	}

	/** The entry titled {@code title} below {@code root}, the shallowest first. */
	@Nullable
	private static me.aap.utils.function.Supplier<? extends PreferenceView.Opts> findByTitle(PreferenceSet root, int title) {
		java.util.ArrayDeque<PreferenceSet> queue = new java.util.ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty()) {
			PreferenceSet cur = queue.poll();
			for (var o : cur.getPreferences()) {
				try {
					if (o.get().title == title) return o;
				} catch (RuntimeException ex) {
					Log.e(ex, "A settings entry could not be read");
				}
				if (o instanceof PreferenceSet ps) queue.add(ps);
			}
		}
		return null;
	}

	/** Puts the root's entries in the order of {@code titles}; any other goes last, in its place. */
	private static void orderRoot(PreferenceSet root, int... titles) {
		var list = root.getPreferences();
		java.util.Map<Object, Integer> rank = new java.util.IdentityHashMap<>();
		for (var o : list) {
			int r = titles.length;
			try {
				int t = o.get().title;
				for (int i = 0; i < titles.length; i++) {
					if (titles[i] == t) {
						r = i;
						break;
					}
				}
			} catch (RuntimeException ex) {
				Log.e(ex, "A settings entry could not be ordered");
			}
			rank.put(o, r);
		}
		list.sort((x, y) -> Integer.compare(rank.get(x), rank.get(y)));
		root.touch();
	}

	private static void groupRoot(PreferenceSet root, int title, int subtitle, int... titles) {
		groupRoot(root, 2, title, subtitle, titles);
	}

	/**
	 * Groups the entries found by title. {@code minimum} is how many must be there: Controls on the car screen has only Key
	 * bindings (Voice control is phone only), and it is still one "Controls" entry in the same place as on the phone.
	 */
	private static void groupRoot(PreferenceSet root, int minimum, int title, int subtitle, int... titles) {
		List<me.aap.utils.function.Supplier<? extends PreferenceView.Opts>> found = new java.util.ArrayList<>();
		int icon = ID_NULL;
		for (int t : titles) {
			var o = findByTitle(root, t);
			if (o == null) continue;
			found.add(o);
			if (icon == ID_NULL) {
				try {
					icon = o.get().icon;
				} catch (RuntimeException ex) {
					Log.e(ex, "A settings entry could not be grouped");
				}
			}
		}
		if (found.size() < minimum) return;
		final int ic = icon;
		root.group(o -> {
			o.title = title;
			o.subtitle = subtitle;
			o.icon = ic;
		}, found);
	}

	/**
	 * Storage and cache: how thumbnails are downloaded and stored, how big their cache may grow,
	 * and clearing it. Web browsing data has its own Clear button, next to the Private Mode settings.
	 */
	private static void addStoragePrefs(MainActivityDelegate a, PreferenceSet parent) {
		PreferenceStore store = FermataApplication.get().getPreferenceStore();
		PreferenceSet ps = parent.subSet(o -> {
			o.title = R.string.storage_prefs;
			o.subtitle = R.string.prefs_sub_storage;
			o.icon = me.aap.utils.R.drawable.folder;
		});
		ps.addListPref(o -> {
			o.store = store;
			o.pref = BitmapCache.THUMB_QUALITY;
			o.title = R.string.thumb_quality;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.thumb_quality_high, R.string.thumb_quality_balanced,
					R.string.thumb_quality_saver, R.string.thumb_quality_highest};
		});
		ps.addListPref(o -> {
			o.store = store;
			o.pref = BitmapCache.IMAGE_CACHE_LIMIT;
			o.title = R.string.image_cache_limit;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.image_cache_limit_100, R.string.image_cache_limit_250,
					R.string.image_cache_limit_500, R.string.image_cache_limit_1000,
					R.string.image_cache_limit_none};
		});
		ps.addButton(o -> {
			o.title = R.string.clear_image_cache;
			o.subtitle = R.string.clear_image_cache_sub;
			o.onClick = () -> {
				Context ctx = a.getContext();
				BitmapCache bc = FermataApplication.get().getBitmapCache();
				App.get().getExecutor().submitTask(bc::clearCache).main().onSuccess(freed ->
						UiUtils.showInfo(ctx, ctx.getString(R.string.image_cache_cleared,
								android.text.format.Formatter.formatShortFileSize(ctx, freed))));
			};
		});
	}

	/**
	 * Import from Spotify: the import itself, plus where playlists come from -- the signed-in
	 * official API (default) or public links only, see {@link SpotifyPrefs}.
	 */
	private void addSpotifyImport(MainActivityDelegate a, PreferenceSet set) {
		PreferenceStore store = SpotifyPrefs.store();
		PreferenceSet sub = set.subSet(o -> {
			o.title = R.string.spotify_import;
			o.subtitle = R.string.prefs_sub_spotify;
			o.icon = R.drawable.playlist_import;
		});
		sub.addButton(o -> {
			o.title = R.string.spotify_import;
			o.subtitle = R.string.spotify_import_sub;
			o.onClick = () -> SpotifyImportFragment.open(a);
		});
		sub.addListPref(o -> {
			o.store = store;
			o.pref = SpotifyPrefs.SOURCE;
			o.title = R.string.spotify_import_source;
			o.subtitle = R.string.spotify_import_source_sub;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.spotify_import_source_account,
					R.string.spotify_import_source_public};
		});
		sub.addButton(o -> {
			o.title = R.string.spotify_setup_title;
			o.subtitle = R.string.spotify_setup_sub;
			o.visibility = spotifyAccountCond(store);
			o.onClick = () -> SpotifyImportFragment.showSetupHelp(a);
		});
		sub.addIntPref(o -> {
			o.store = store;
			o.pref = SpotifyPrefs.LIKES_LIMIT;
			o.title = R.string.spotify_likes_limit;
			o.subtitle = R.string.spotify_likes_limit_sub;
			o.seekMin = 10;
			o.seekMax = 500;
			o.seekScale = 10;
			o.ems = 3;
			o.visibility = spotifyAccountCond(store);
		});
		sub.addStringPref(o -> {
			o.store = store;
			o.pref = SpotifyPrefs.CLIENT_ID;
			o.title = R.string.spotify_client_id;
			o.hint = R.string.spotify_client_id_hint;
			o.trim = true;
			o.visibility = spotifyAccountCond(store);
		});
		sub.addButton(o -> {
			o.title = R.string.spotify_login;
			o.subtitle = R.string.spotify_login_sub;
			o.visibility = spotifyAccountCond(store).and(new PrefCondition<>(store,
					SpotifyPrefs.LOGGED_IN, p -> !store.getBooleanPref(p)));
			o.onClick = () -> SpotifyImportFragment.startLogin(a);
		});
		sub.addButton(o -> {
			o.title = R.string.spotify_logout;
			o.visibility = spotifyAccountCond(store).and(PrefCondition.create(store,
					SpotifyPrefs.LOGGED_IN));
			o.onClick = () -> {
				SpotifyAuth.logout();
				UiUtils.showToast(a.getContext(), R.string.spotify_logged_out);
			};
		});
	}

	/** Sharing with phones on the same Wi-Fi, see {@link ShareManager}. */
	private void addShareSettings(PreferenceSet set) {
		PreferenceStore store = SharePrefs.store();
		PreferenceSet sub = set.subSet(o -> {
			o.title = R.string.share;
			o.subtitle = R.string.prefs_sub_share;
			o.icon = R.drawable.share;
		});
		sub.addBooleanPref(o -> {
			o.store = store;
			o.pref = SharePrefs.RECEIVE;
			o.title = R.string.share_receive;
			o.subtitle = R.string.share_receive_sub;
			o.asSwitch = true;
		});
		sub.addStringPref(o -> {
			o.store = store;
			o.pref = SharePrefs.DEVICE_NAME;
			o.title = R.string.share_device_name;
			o.stringHint = SharePrefs.getDeviceName();
			o.trim = true;
		});
	}

	/** Sends zrAuto itself to a phone nearby, which then offers to install or update it. */
	private void addShareAppButton(MainActivityDelegate a, PreferenceSet set) {
		if (a.isCarActivityNotMirror()) return;
		set.addButton(o -> {
			o.title = R.string.share_app;
			o.subtitle = R.string.share_app_sub;
			o.icon = R.drawable.share;
			o.onClick = () -> {
				me.aap.fermata.share.ShareOffer offer =
						me.aap.fermata.share.ShareSource.createApp(a.getContext());
				if (offer.entries.isEmpty()) TopToast.show(R.drawable.share, R.string.share_app_split);
				else me.aap.fermata.ui.view.ShareSheet.show(a, offer);
			};
		});
	}

	/** A fresh instance per row, for the same reason as {@link #dimCustomColorCond}. */
	private static ChangeableCondition spotifyAccountCond(PreferenceStore store) {
		return new PrefCondition<>(store, SpotifyPrefs.SOURCE,
				p -> store.getIntPref(p) == SpotifyPrefs.SOURCE_ACCOUNT);
	}

	/**
	 * Opt-in event tracing for problems that can only be reproduced in the car -- see
	 * {@link DiagnosticLog}. Available on every build and on the car screen itself: turning it on
	 * (and reading it back afterwards) has to be possible from wherever the user actually is when
	 * the problem happens, which by definition isn't next to a laptop with adb.
	 */
	private void addDiagnostics(MainActivityDelegate a, PreferenceSet set) {
		// The recording, event toasts and main menu switches live on the log's own screen now, with the log itself.
		set.addButton(o -> {
			o.title = R.string.diagnostics;
			o.subtitle = R.string.prefs_sub_diagnostics;
			o.icon = R.drawable.diagnostic_log;
			o.onClick = () -> a.showFragment(R.id.diagnostic_log_fragment);
		});
	}

	/**
	 * Visible only while dimming is on <em>and</em> the custom colour is selected. Built fresh per
	 * row rather than copied: each condition registers itself as the listener of the prefs it
	 * watches, so sharing one instance across rows would leave only the last row reacting.
	 */
	private static ChangeableCondition dimCustomColorCond(MainActivityDelegate a) {
		var prefs = a.getPrefs();
		return PrefCondition.create(prefs, MainActivityPrefs.DIM_ENABLED)
				.and(new PrefCondition<>(prefs, MainActivityPrefs.DIM_COLOR_PRESET,
						p -> prefs.getIntPref(p) == MainActivityPrefs.DIM_COLOR_CUSTOM_IDX));
	}

	/** Interface &gt; Navigation tabs: its own page, rows to drag into order (see {@link NavTabsFragment}). */
	private void addHomePrefs(MainActivityDelegate a, PreferenceSet parent) {
		// Home has a settings page of its own (HomeSettingsFragment), in the look of the Power settings.
		parent.addButton(o -> {
			o.title = R.string.home;
			o.subtitle = R.string.home_pref_sub;
			o.icon = R.drawable.home_page;
			o.onClick = () -> a.showFragment(R.id.home_settings_fragment, a.isCarActivity() ?
					HomeSettingsFragment.AA : HomeSettingsFragment.PHONE);
		});
	}

	/** Interface &gt; UI style: the glass look of the bars and Home's cards, a page of its own (GlassSettingsFragment). */
	private static void addGlassPrefs(MainActivityDelegate a, PreferenceSet parent) {
		parent.addButton(o -> {
			o.title = R.string.glass_settings;
			o.subtitle = R.string.glass_settings_sub;
			o.icon = R.drawable.glass_style;
			o.onClick = () -> a.showFragment(R.id.glass_settings_fragment);
		});
	}

	/** What tapping a track does: plays it, or opens its menu. */
	private static void addTapAction(MainActivityDelegate a, PreferenceSet ps,
																	 me.aap.utils.pref.PreferenceStore.Pref<me.aap.utils.function.IntSupplier> tapAction) {
		ps.addListPref(o -> {
			o.store = a.getPrefs();
			o.pref = tapAction;
			o.title = R.string.tap_action;
			o.subtitle = R.string.tap_action_sub;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.tap_action_play, R.string.tap_action_menu};
		});
	}

	/** The Android Auto twin of a phone floating button pref if {@code aa}, else the pref itself. */
	@SuppressWarnings("unchecked")
	private void addAddons(PreferenceSet set) {
		AddonManager amgr = FermataApplication.get().getAddonManager();
		PreferenceSet sub = set.subSet(o -> {
			o.title = R.string.addons;
			o.subtitle = R.string.prefs_sub_addons;
			o.icon = R.drawable.more;
		});
		PreferenceStore store = FermataApplication.get().getPreferenceStore();
		addonSettingsSets.clear();

		for (AddonInfo addon : BuildConfig.ADDONS) {
			if (!addon.hasSettings) continue;
			AddonPrefsBuilder b = new AddonPrefsBuilder(amgr, addon, store);
			PreferenceSet sub1 = sub.subSet(b);
			sub1.configure(b::configure);
			addonSettingsSets.put(addon.moduleName, sub1);
			// By class too: the YouTube, bubbles and browser addons all share the "web" module.
			addonSettingsSets.put(addon.className, sub1);
		}
	}

	/** Backup folder, back up now, restore, and the automatic backup. */
	private void addBackupRestore(MainActivityDelegate a, PreferenceSet parent) {
		PreferenceStore store = FermataApplication.get().getPreferenceStore();
		Context ctx = a.getContext();
		PreferenceSet ps = parent.subSet(o -> {
			o.title = R.string.backup_restore;
			o.subtitle = R.string.prefs_sub_backup;
			o.icon = R.drawable.save;
		});
		// Rows are built again on every draw of the page, so the subtitles are worked out here,
		// each time, not once when the settings were set up.
		ps.addButton(o -> {
			o.title = R.string.auto_backup_folder;
			o.csubtitle = ctx.getString(R.string.auto_backup_folder_sub,
					BackupManager.getDestinationName(ctx));
			o.onClick = () -> chooseBackupFolder(a, store);
		});
		ps.addButton(o -> {
			long last = store.getLongPref(BackupManager.AUTO_BACKUP_LAST);
			o.title = R.string.auto_backup_now;
			o.csubtitle = ctx.getString(R.string.auto_backup_now_sub, (last == 0) ?
					ctx.getString(R.string.auto_backup_never) :
					java.text.DateFormat.getDateTimeInstance().format(new Date(last)));
			o.onClick = () -> backupNow(a);
		});
		ps.addButton(o -> {
			o.title = R.string.import_prefs;
			o.subtitle = R.string.import_prefs_sub;
			o.onClick = () -> importPrefs(a);
		});

		ps.addBooleanPref(o -> {
			o.store = store;
			o.pref = BackupManager.AUTO_BACKUP;
			o.title = R.string.auto_backup;
			o.subtitle = R.string.auto_backup_sub;
			o.asSwitch = true;
		});
		var autoOn = PrefCondition.create(store, BackupManager.AUTO_BACKUP);
		ps.addListPref(o -> {
			o.store = store;
			o.pref = BackupManager.AUTO_BACKUP_INTERVAL;
			o.title = R.string.auto_backup_interval;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.auto_backup_interval_1, R.string.auto_backup_interval_3,
					R.string.auto_backup_interval_7, R.string.auto_backup_interval_30};
			o.visibility = autoOn.copy();
		});
		ps.addListPref(o -> {
			o.store = store;
			o.pref = BackupManager.AUTO_BACKUP_HOUR;
			o.title = R.string.auto_backup_time;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			String[] hours = new String[24];
			for (int i = 0; i < hours.length; i++) hours[i] = String.format(Locale.getDefault(), "%02d:00", i);
			o.stringValues = hours;
			o.visibility = autoOn.copy();
		});
		ps.addListPref(o -> {
			o.store = store;
			o.pref = BackupManager.AUTO_BACKUP_KEEP;
			o.title = R.string.auto_backup_keep;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			String[] counts = new String[BackupManager.KEEP_COUNT.length];
			for (int i = 0; i < counts.length; i++) counts[i] = String.valueOf(BackupManager.KEEP_COUNT[i]);
			o.stringValues = counts;
			o.visibility = autoOn.copy();
		});
		backupSet = ps;
	}

	private PreferenceSet backupSet;

	/** Draws the backup page again, if it's the one showing: its last backup and folder changed. */
	private void refreshBackupPage() {
		PreferenceViewAdapter ad = adapter;
		PreferenceSet set = backupSet;
		if ((ad != null) && (set != null) && (ad.getPreferenceSet() == set)) ad.setPreferenceSet(set);
	}

	private void backupNow(MainActivityDelegate a) {
		Context ctx = a.getContext();
		// Off the main thread: the folder may be a cloud provider's.
		new Thread(() -> {
			String name = BackupManager.runBackup(ctx);
			App.get().getHandler().post(() -> {
				if (name != null) TopToast.show(R.drawable.save, R.string.auto_backup_done, name);
				else UiUtils.showAlert(ctx, R.string.auto_backup_failed);
				refreshBackupPage();
			});
			BackupManager.schedule(ctx);
		}, "backup-now").start();
	}

	private void chooseBackupFolder(MainActivityDelegate a, PreferenceStore store) {
		pickBackupFolder(a, this::refreshBackupPage);
	}

	/**
	 * Lets the user pick the folder automatic backups go to (the system's folder picker, so Google
	 * Drive works too), and remembers it; {@code done} runs once it is set. Also used by the
	 * welcome and setup wizard.
	 */
	public static void pickBackupFolder(MainActivityDelegate a, @Nullable Runnable done) {
		int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
		a.startActivityForResult(() -> new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
				.addFlags(rw | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)).onCompletion((d, err) -> {
			Context ctx = a.getContext();
			if (err != null) {
				UiUtils.showAlert(ctx, ctx.getString(R.string.export_prefs_failed, err));
				return;
			}
			if (d == null) return;
			Uri uri = d.getData();
			if (uri == null) return;
			try {
				ctx.getContentResolver().takePersistableUriPermission(uri, d.getFlags() & rw);
				BackupManager.setDestination(ctx, uri);
				if (done != null) done.run();
				TopToast.show(R.drawable.save, R.string.auto_backup_folder_set);
			} catch (Exception ex) {
				Log.e(ex, "Failed to use the backup folder");
				UiUtils.showAlert(ctx, ctx.getString(R.string.export_prefs_failed, ex));
			}
		});
	}

	/**
	 * Restores a backup: the zip is picked with the system's file picker (Google Drive works), its
	 * preferences replace the app's, and the app restarts. Also used by the welcome and setup wizard.
	 */
	public static void importPrefs(MainActivityDelegate a) {
		importPrefs(a, null);
	}

	/**
	 * As {@link #importPrefs(MainActivityDelegate)}, but when the restore went through {@code onRestored} runs instead
	 * of the plain "restored, the app closes" message (the welcome wizard shows its own page).
	 */
	public static void importPrefs(MainActivityDelegate a, @Nullable Runnable onRestored) {
		if (Utils.isSafSupported(a)) {
			a.startActivityForResult(
							() -> new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip"))
					.onCompletion((d, err) -> {
						Context ctx = a.getContext();

						if (err != null) {
							UiUtils.showAlert(ctx, ctx.getString(R.string.import_prefs_failed, err));
							return;
						}

						if (d == null) return;
						Uri uri = d.getData();
						if (uri == null) return;

						try {
							DocumentFile f = DocumentFile.fromSingleUri(ctx, uri);
							if (f == null) return;

							try (InputStream is = ctx.getContentResolver().openInputStream(f.getUri())) {
								importStream(a, is, onRestored);
							}
						} catch (Exception ex) {
							Log.e(ex, "Failed to import preferences");
							UiUtils.showAlert(ctx, ctx.getString(R.string.import_prefs_failed, ex));
						}
					});
		} else {
			if (!(a.showFragment(me.aap.utils.R.id.file_picker) instanceof FilePickerFragment pick))
				return;
			pick.setMode(FilePickerFragment.FILE);
			pick.setPattern(Pattern.compile(".+\\.zip"));
			pick.setFileSystem(LocalFileSystem.getInstance());
			pick.setFileConsumer(res -> {
				a.showFragment(R.id.settings_fragment);
				if (res == null) return;
				File f = res.getLocalFile();
				if (f == null) return;
				try (InputStream is = new FileInputStream(f)) {
					importStream(a, is, onRestored);
				} catch (Exception ex) {
					Context ctx = a.getContext();
					Log.e(ex, "Failed to import preferences");
					UiUtils.showAlert(ctx, ctx.getString(R.string.import_prefs_failed, ex));
				}
			});
		}
	}

	private static void importStream(MainActivityDelegate a, InputStream is, @Nullable Runnable onRestored)
			throws IOException {
		Context ctx = a.getContext();

		// Home keeps its own copy of the layout and writes it when the screen goes away, which would put the old
		// layout back over the restored one while the app closes.
		HomeModel.freeze(true);
		try (ZipInputStream zis = (SDK_INT >= N) ? new ZipInputStream(is, UTF_8) :
				new ZipInputStream(is)) {
			PrefUtils.importSharedPrefs(ctx, zis);
		} catch (IOException | RuntimeException ex) {
			HomeModel.freeze(false);
			throw ex;
		}

		if (onRestored != null) {
			onRestored.run();
			return;
		}

		UiUtils.showInfo(ctx, ctx.getString(R.string.import_prefs_ok)).thenRun(() -> {
			App.get().getHandler().postDelayed(() -> System.exit(0), 1000);
			if (ctx instanceof Activity) ((Activity) ctx).finishAffinity();
			else System.exit(0);
		});
	}

	private void openLog(MainActivityDelegate a) {
		try {
			var i = new Intent(Intent.ACTION_SEND);
			var app = FermataApplication.get();
			var f = app.getLogFile();
			Uri u = (SDK_INT >= Build.VERSION_CODES.N) ?
					FileProvider.getUriForFile(app, app.getPackageName() + ".FileProvider", f) :
					Uri.fromFile(f);
			i.setType("text/plain");
			i.putExtra(Intent.EXTRA_STREAM, u);
			a.startActivity(Intent.createChooser(i, app.getString(R.string.open_log)));
		} catch (Exception err) {
			Log.e(err);
			showAlert(a.getContext(), err.getLocalizedMessage());
		}
	}

	private final class AddonPrefsBuilder
			implements Consumer<PreferenceView.Opts>, AddonManager.Listener {
		private final AddonManager amgr;
		private final AddonInfo info;
		private final PreferenceStore store;
		private PreferenceSet set;

		public AddonPrefsBuilder(AddonManager amgr, AddonInfo info, PreferenceStore store) {
			this.amgr = amgr;
			this.info = info;
			this.store = store;
			amgr.addBroadcastListener(this);
		}

		void configure(PreferenceSet set) {
			this.set = set;

			set.addBooleanPref(o -> {
				o.title = R.string.enable;
				o.subtitle = R.string.enable_addon_sub;
				o.pref = info.enabledPref;
				o.store = store;
			});
			// Always the first row of the page, whatever else is added.
			set.markLastPinned();

			FermataAddon a = amgr.getAddon(info.className);

			if (a != null) {
				ChangeableCondition cond = PrefCondition.create(store, info.enabledPref);
				a.contributeSettings(buildContext, store, set, cond);
			}
		}

		@Override
		public void accept(PreferenceView.Opts o) {
			o.title = info.addonName;
			o.icon = info.icon;
			// "addon_name_tv" has "addon_sub_tv" beside it: one line on what the add-on is for.
			try {
				String n = buildContext.getResources().getResourceEntryName(info.addonName);
				int sub = buildContext.getResources().getIdentifier(n.replace("addon_name_", "addon_sub_"), "string",
						buildContext.getPackageName());
				if (sub != 0) o.subtitle = sub;
			} catch (RuntimeException ex) {
				// No line for this one.
			}
		}

		@Override
		public void onAddonChanged(AddonManager mgr, AddonInfo info, boolean installed) {
			if (set != null) set.configure(this::configure);
		}
	}
}
