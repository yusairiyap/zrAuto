package me.aap.fermata.addon.web.yt;

import static me.aap.utils.async.Completed.completed;

import android.content.Context;

import androidx.annotation.IdRes;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.MediaLibAddon;
import me.aap.fermata.addon.web.R;
import me.aap.fermata.addon.web.WebBrowserAddon;
import me.aap.fermata.media.lib.DefaultMediaLib;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * @author Andrey Pavlenko
 */
@Keep
@SuppressWarnings("unused")
public class YoutubeAddon extends WebBrowserAddon
		implements PreferenceStore.Listener, MediaLibAddon {
	@NonNull
	private static final AddonInfo info = FermataAddon.findAddonInfo(YoutubeAddon.class.getName());
	public static final int YT_DARK_MODE_DISABLED = 0;
	public static final int YT_DARK_MODE_ENABLED = 1;
	public static final int YT_DARK_MODE_AUTO = 2;
	private static final Pref<IntSupplier> YT_DARK_MODE = Pref.i("YT_DARK_MODE", YT_DARK_MODE_AUTO);
	private static final Pref<BooleanSupplier> YT_DESKTOP_VERSION = Pref.b("YT_DESKTOP_VERSION", false);
	private static final Pref<Supplier<String[]>> YT_BOOKMARKS = Pref.sa("YT_BOOKMARKS");
	private static final Pref<Supplier<String>> VIDEO_SCALE = Pref.s("VIDEO_SCALE", VideoScale.CONTAIN::prefName);
	private static final Pref<BooleanSupplier> YT_OPEN_ON_START = Pref.b("YT_OPEN_ON_START", false);
	private static final Pref<BooleanSupplier> YT_AUTO_HIGHEST_QUALITY =
			Pref.b("YT_AUTO_HIGHEST_QUALITY", false);
	private static final Pref<BooleanSupplier> YT_SKIP_ADD = Pref.b("YT_SKIP_ADD", true);
	private static final Pref<Supplier<String[]>> YT_VIDEO_TITLES = Pref.sa("YT_VIDEO_TITLES");
	private static final Pref<BooleanSupplier> YT_EQ_ENABLED = Pref.b("YT_EQ_ENABLED", false);
	static final Pref<IntSupplier> YT_EQ_PRESET = Pref.i("YT_EQ_PRESET", 0);
	private static final Pref<Supplier<int[]>> YT_EQ_BANDS = Pref.ia("YT_EQ_BANDS", () -> null);
	private static final Pref<BooleanSupplier> YT_BASS_ENABLED = Pref.b("YT_BASS_ENABLED", false);
	private static final Pref<IntSupplier> YT_BASS_STRENGTH = Pref.i("YT_BASS_STRENGTH", 0);
	private static final Pref<BooleanSupplier> YT_VIRT_ENABLED = Pref.b("YT_VIRT_ENABLED", false);
	private static final Pref<IntSupplier> YT_VIRT_STRENGTH = Pref.i("YT_VIRT_STRENGTH", 0);
	private static final Pref<BooleanSupplier> YT_REVERB_ENABLED = Pref.b("YT_REVERB_ENABLED", false);
	private static final Pref<IntSupplier> YT_REVERB_STRENGTH = Pref.i("YT_REVERB_STRENGTH", 0);
	// Impulse response length for the Live Hall convolver, in ms. Directly drives its CPU cost, so
	// it's user-adjustable (see YoutubeEqualizerView) rather than fixed; 2500 matches the value this
	// was hardcoded to before it became adjustable, so existing users hear no change by default.
	private static final Pref<IntSupplier> YT_REVERB_DURATION = Pref.i("YT_REVERB_DURATION", 2500);
	// Which reverb engine the youtube_equalizer.js content script uses for Live Hall: 0 = smooth
	// (cheap algorithmic comb+allpass, the default), 1 = convolution (the original impulse-response
	// reverb, higher CPU cost but a different, more "random room" character). See
	// YoutubeEqualizerView's "Hall quality" row and YoutubeEqualizerScript's config JSON.
	static final Pref<IntSupplier> YT_REVERB_ENGINE = Pref.i("YT_REVERB_ENGINE", 0);
	private boolean ignorePrefChange;
	private YoutubeRootItem root;

	@IdRes
	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.youtube_fragment;
	}

	@NonNull
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new YoutubeFragment();
	}

	@Override
	public Pref<IntSupplier> getForceDarkPref() {
		return YT_DARK_MODE;
	}

	@Override
	public Pref<BooleanSupplier> getDesktopVersionPref() {
		return YT_DESKTOP_VERSION;
	}

	@Override
	public Pref<Supplier<String[]>> getBookmarksPref() {
		return YT_BOOKMARKS;
	}

	boolean skipAd() {
		return getPreferenceStore().getBooleanPref(YT_SKIP_ADD);
	}

	boolean skipAdChanged(List<Pref<?>> prefs) {
		return prefs.contains(YT_SKIP_ADD);
	}

	@NonNull
	String getVideoTitle(String videoId) {
		String[] p = getPreferenceStore().getStringArrayPref(YT_VIDEO_TITLES);
		for (int i = 0; i < p.length - 1; i += 2) {
			if (p[i].equals(videoId)) return p[i + 1];
		}
		return videoId;
	}

	void cacheVideoTitle(String videoId, String title) {
		String[] p = getPreferenceStore().getStringArrayPref(YT_VIDEO_TITLES);
		Map<String, String> m = new LinkedHashMap<>(p.length / 2 + 1);
		for (int i = 0; i < p.length - 1; i += 2) m.put(p[i], p[i + 1]);
		m.put(videoId, title);

		String[] a = new String[m.size() * 2];
		int i = 0;
		for (Map.Entry<String, String> e : m.entrySet()) {
			a[i++] = e.getKey();
			a[i++] = e.getValue();
		}
		getPreferenceStore().applyStringArrayPref(YT_VIDEO_TITLES, a);
	}

	@Override
	public boolean isSupportedItem(Item i) {
		return (i instanceof YoutubeVideoItem);
	}

	@NonNull
	public YoutubeRootItem getRootItem(DefaultMediaLib lib) {
		if ((root == null) || (root.getLib() != lib)) root = new YoutubeRootItem(lib);
		return root;
	}

	@Nullable
	@Override
	public FutureSupplier<? extends Item> getItem(DefaultMediaLib lib, @Nullable String scheme,
																								 String id) {
		if (!"youtube".equals(scheme)) return null;
		String videoId = id.substring(id.indexOf(':') + 1);
		return completed(new YoutubeVideoItem(videoId, getRootItem(lib)));
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		super.contributeSettings(ctx, store, set, visibility);
		getPreferenceStore().addBroadcastListener(this);
		MainActivityPrefs.get().addBroadcastListener(this);
		FermataApplication.get().getPreferenceStore().addBroadcastListener(this);

		set.addBooleanPref(o -> {
			o.store = getPreferenceStore();
			o.pref = YT_OPEN_ON_START;
			o.title = R.string.open_on_start;
			o.visibility = visibility;
		});
		set.addBooleanPref(o -> {
			o.store = getPreferenceStore();
			o.pref = YT_AUTO_HIGHEST_QUALITY;
			o.title = R.string.auto_highest_video_quality;
			o.visibility = visibility;
		});

		set.addBooleanPref(o -> {
			o.store = getPreferenceStore();
			o.pref = YT_SKIP_ADD;
			o.title = R.string.try_to_skip_ad;
			o.visibility = visibility;
		});

		YoutubeSponsorBlock.contributeSettings(getPreferenceStore(), set, visibility);
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<Pref<?>> prefs) {
		if (ignorePrefChange) return;
		ignorePrefChange = true;

		if (prefs.contains(getInfo().enabledPref)) {
			if (!store.getBooleanPref(getInfo().enabledPref)) {
				MainActivityPrefs ap = MainActivityPrefs.get();
				getPreferenceStore().applyBooleanPref(YT_OPEN_ON_START, false);
				if (getInfo().className.equals(ap.getShowAddonOnStartPref()))
					ap.setShowAddonOnStartPref(null);
			}
		} else if (prefs.contains(YT_OPEN_ON_START)) {
			MainActivityPrefs ap = MainActivityPrefs.get();
			if (store.getBooleanPref(YT_OPEN_ON_START)) {
				ap.setShowAddonOnStartPref(getInfo().className);
			} else if (getInfo().className.equals(ap.getShowAddonOnStartPref())) {
				ap.setShowAddonOnStartPref(null);
			}
		} else if (prefs.contains(MainActivityPrefs.SHOW_ADDON_ON_START)) {
			getPreferenceStore().applyBooleanPref(YT_OPEN_ON_START,
					getInfo().className.equals(MainActivityPrefs.get().getShowAddonOnStartPref()));
		}

		ignorePrefChange = false;
	}

	@Override
	public void uninstall() {
		getPreferenceStore().removeBroadcastListener(this);
		MainActivityPrefs.get().removeBroadcastListener(this);
		FermataApplication.get().getPreferenceStore().removeBroadcastListener(this);
	}

	VideoScale getScale() {
		switch (getPreferenceStore().getStringPref(VIDEO_SCALE)) {
			case "fill":
				return VideoScale.FILL;
			case "contain":
				return VideoScale.CONTAIN;
			case "cover":
				return VideoScale.COVER;
			default:
				return VideoScale.NONE;
		}
	}

	void setScale(VideoScale scale) {
		getPreferenceStore().applyStringPref(VIDEO_SCALE, scale.prefName());
	}

	boolean autoHighestQuality() {
		return getPreferenceStore().getBooleanPref(YT_AUTO_HIGHEST_QUALITY);
	}

	boolean autoHighestQualityChanged(List<Pref<?>> prefs) {
		return prefs.contains(YT_AUTO_HIGHEST_QUALITY);
	}

	boolean eqEnabled() {
		return getPreferenceStore().getBooleanPref(YT_EQ_ENABLED);
	}

	void setEqEnabled(boolean enabled) {
		getPreferenceStore().applyBooleanPref(YT_EQ_ENABLED, enabled);
	}

	int eqPreset() {
		return getPreferenceStore().getIntPref(YT_EQ_PRESET);
	}

	void setEqPreset(int preset) {
		getPreferenceStore().applyIntPref(YT_EQ_PRESET, preset);
	}

	int[] eqBands() {
		int[] bands = getPreferenceStore().getIntArrayPref(YT_EQ_BANDS);
		return ((bands != null) && (bands.length == YoutubeEqualizerPresets.NUM_BANDS)) ? bands :
				new int[YoutubeEqualizerPresets.NUM_BANDS];
	}

	void setEqBands(int[] bands) {
		getPreferenceStore().applyIntArrayPref(YT_EQ_BANDS, bands);
	}

	boolean bassEnabled() {
		return getPreferenceStore().getBooleanPref(YT_BASS_ENABLED);
	}

	void setBassEnabled(boolean enabled) {
		getPreferenceStore().applyBooleanPref(YT_BASS_ENABLED, enabled);
	}

	int bassStrength() {
		return getPreferenceStore().getIntPref(YT_BASS_STRENGTH);
	}

	void setBassStrength(int strength) {
		getPreferenceStore().applyIntPref(YT_BASS_STRENGTH, strength);
	}

	boolean virtEnabled() {
		return getPreferenceStore().getBooleanPref(YT_VIRT_ENABLED);
	}

	void setVirtEnabled(boolean enabled) {
		getPreferenceStore().applyBooleanPref(YT_VIRT_ENABLED, enabled);
	}

	int virtStrength() {
		return getPreferenceStore().getIntPref(YT_VIRT_STRENGTH);
	}

	void setVirtStrength(int strength) {
		getPreferenceStore().applyIntPref(YT_VIRT_STRENGTH, strength);
	}

	boolean reverbEnabled() {
		return getPreferenceStore().getBooleanPref(YT_REVERB_ENABLED);
	}

	void setReverbEnabled(boolean enabled) {
		getPreferenceStore().applyBooleanPref(YT_REVERB_ENABLED, enabled);
	}

	int reverbStrength() {
		return getPreferenceStore().getIntPref(YT_REVERB_STRENGTH);
	}

	void setReverbStrength(int strength) {
		getPreferenceStore().applyIntPref(YT_REVERB_STRENGTH, strength);
	}

	int reverbDuration() {
		return getPreferenceStore().getIntPref(YT_REVERB_DURATION);
	}

	void setReverbDuration(int durationMs) {
		getPreferenceStore().applyIntPref(YT_REVERB_DURATION, durationMs);
	}

	/** 0 = smooth (algorithmic), 1 = convolution (impulse-response). */
	int reverbEngine() {
		return getPreferenceStore().getIntPref(YT_REVERB_ENGINE);
	}

	void setReverbEngine(int engine) {
		getPreferenceStore().applyIntPref(YT_REVERB_ENGINE, engine);
	}

	boolean eqPrefsChanged(List<Pref<?>> prefs) {
		return prefs.contains(YT_EQ_ENABLED) || prefs.contains(YT_EQ_PRESET) ||
				prefs.contains(YT_EQ_BANDS) || prefs.contains(YT_BASS_ENABLED) ||
				prefs.contains(YT_BASS_STRENGTH) || prefs.contains(YT_VIRT_ENABLED) ||
				prefs.contains(YT_VIRT_STRENGTH) || prefs.contains(YT_REVERB_ENABLED) ||
				prefs.contains(YT_REVERB_STRENGTH) || prefs.contains(YT_REVERB_DURATION) ||
				prefs.contains(YT_REVERB_ENGINE);
	}

	enum VideoScale {
		FILL, CONTAIN, COVER, NONE;

		String prefName() {
			return name().toLowerCase();
		}
	}
}
