package me.aap.fermata.ui.activity;

import static me.aap.fermata.BuildConfig.AUTO;

import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.aap.fermata.action.Action;
import me.aap.utils.event.EventBroadcaster;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.SharedPreferenceStore;
import me.aap.utils.ui.view.NavBarView;

/**
 * @author Andrey Pavlenko
 */
public interface MainActivityPrefs
		extends SharedPreferenceStore, EventBroadcaster<PreferenceStore.Listener> {
	int THEME_DARK = 0;
	int THEME_LIGHT = 1;
	int THEME_SYSTEM = 2;
	int THEME_BLACK = 3;
	int THEME_STAR_WARS = 4;
	int THEME_PURPLE = 5;
	int THEME_CLASSIC = 6;
	int THEME_DYNAMIC = 7;
	int CLOCK_POS_NONE = 0;
	int CLOCK_POS_LEFT = 1;
	int CLOCK_POS_RIGHT = 2;
	int CLOCK_POS_CENTER = 3;

	// Curated dim-overlay tint presets; DIM_COLOR_CUSTOM_IDX (one past the end) selects the
	// user-defined RGB sliders instead of a preset.
	int[] DIM_COLOR_PRESETS = {
			Color.BLACK,
			Color.rgb(255, 147, 41), // Blue light filter (warm candle tone; cuts blue wavelengths)
			Color.rgb(255, 0, 0),   // Red
			Color.rgb(139, 0, 0),   // Deep Red
			Color.rgb(255, 191, 0), // Amber
			Color.rgb(255, 255, 0), // Yellow
	};
	int DIM_COLOR_CUSTOM_IDX = DIM_COLOR_PRESETS.length;

	// Hidden baseline multipliers applied on top of the user-facing size-slider preferences below,
	// so a slider showing "1.0" renders at the intended default size rather than the raw
	// 40dp/70dp view base. Moving a slider away from 1.0 scales relative to this baseline.
	float TOOL_BAR_SIZE_BASE_MOBILE = 1.3f;
	float CONTROL_PANEL_SIZE_BASE_MOBILE = 1.4f;
	float NAV_BAR_SIZE_BASE_AUTO = 1.05f;
	float TOOL_BAR_SIZE_BASE_AUTO = 1.5f;
	float CONTROL_PANEL_SIZE_BASE_AUTO = 1.3f;

	Pref<IntSupplier> THEME_MAIN = Pref.i("THEME_MAIN", THEME_CLASSIC);
	Pref<BooleanSupplier> HIDE_BARS = Pref.b("HIDE_BARS", false);
	Pref<BooleanSupplier> FULLSCREEN = Pref.b("FULLSCREEN", false);
	Pref<BooleanSupplier> SHOW_PG_UP_DOWN = Pref.b("SHOW_PG_UP_DOWN", true);
	Pref<BooleanSupplier> USE_DPAD_CURSOR = AUTO ? Pref.b("USE_DPAD_CURSOR", true) : null;
	Pref<IntSupplier> NAV_BAR_POS = Pref.i("NAV_BAR_POS", NavBarView.POSITION_BOTTOM);
	Pref<DoubleSupplier> NAV_BAR_SIZE = Pref.f("NAV_BAR_SIZE", 1f);
	Pref<DoubleSupplier> TOOL_BAR_SIZE = Pref.f("TOOL_BAR_SIZE", 1f);
	Pref<DoubleSupplier> CONTROL_PANEL_SIZE = Pref.f("CONTROL_PANEL_SIZE", 1f);
	Pref<DoubleSupplier> TEXT_ICON_SIZE = Pref.f("TEXT_ICON_SIZE", 1f);
	// Scales icon graphics specifically (toolbar buttons, nav bar icons), independent of the
	// bar-container size sliders above.
	Pref<DoubleSupplier> ICON_SIZE = Pref.f("ICON_SIZE", 1f);
	Pref<BooleanSupplier> GRID_VIEW = Pref.b("GRID_VIEW", false);
	Pref<DoubleSupplier> P_SPLIT_PERCENT = Pref.f("P_SPLIT_PERCENT", 0.6f);
	Pref<DoubleSupplier> L_SPLIT_PERCENT = Pref.f("L_SPLIT_PERCENT", 0.4f);
	Pref<DoubleSupplier> P_SPLIT_PERCENT_SUB = Pref.f("P_SPLIT_PERCENT_SUB", 0.5f);
	Pref<DoubleSupplier> L_SPLIT_PERCENT_SUB = Pref.f("L_SPLIT_PERCENT_SUB", 0.5f);
	Pref<Supplier<String>> SHOW_ADDON_ON_START = Pref.s("SHOW_ADDON_ON_START", (String) null);
	Pref<BooleanSupplier> CHECK_UPDATES = Pref.b("CHECK_UPDATES", true);
	Pref<LongSupplier> CHECK_UPDATES_STAMP = Pref.l("CHECK_UPDATES_STAMP", 0);
	Pref<BooleanSupplier> SYS_BARS_ON_VIDEO_TOUCH = Pref.b("SYS_BARS_ON_VIDEO_TOUCH", false);
	Pref<BooleanSupplier> LANDSCAPE_VIDEO = Pref.b("LANDSCAPE_VIDEO", false);
	Pref<BooleanSupplier> CHANGE_BRIGHTNESS = Pref.b("CHANGE_BRIGHTNESS", false);
	Pref<IntSupplier> BRIGHTNESS = Pref.i("BRIGHTNESS", 255);
	Pref<BooleanSupplier> FAB2_ENABLED = Pref.b("FAB2_ENABLED", true);
	Pref<IntSupplier> FAB2_ACTION = Pref.i("FAB2_ACTION", Action.PLAY_PAUSE.ordinal());
	Pref<BooleanSupplier> FAB3_ENABLED = Pref.b("FAB3_ENABLED", false);
	Pref<IntSupplier> FAB3_ACTION = Pref.i("FAB3_ACTION", Action.DIM_TOGGLE.ordinal());
	Pref<BooleanSupplier> FAB_DRAGGABLE = Pref.b("FAB_DRAGGABLE", true);
	Pref<DoubleSupplier> FAB_SIZE = Pref.f("FAB_SIZE", 1f);
	Pref<BooleanSupplier> DIM_ENABLED = Pref.b("DIM_ENABLED", false);
	Pref<IntSupplier> DIM_OPACITY = Pref.i("DIM_OPACITY", 50);
	Pref<IntSupplier> DIM_COLOR_PRESET = Pref.i("DIM_COLOR_PRESET", 0);
	Pref<IntSupplier> DIM_COLOR_CUSTOM_R = Pref.i("DIM_COLOR_CUSTOM_R", 0);
	Pref<IntSupplier> DIM_COLOR_CUSTOM_G = Pref.i("DIM_COLOR_CUSTOM_G", 0);
	Pref<IntSupplier> DIM_COLOR_CUSTOM_B = Pref.i("DIM_COLOR_CUSTOM_B", 0);
	// Whether the Browser/YouTube tabs are currently running in Private Mode. Persisted (rather
	// than session-only) so a session left active survives an app restart, matching
	// PRIVATE_MODE_ALWAYS's "stay private even after exiting the app" contract; WebBrowserAddon's
	// constructor and MainActivity.onResume() are what actually enforce "off unless Always is on".
	Pref<BooleanSupplier> PRIVATE_MODE_ENABLED = Pref.b("PRIVATE_MODE_ENABLED", false);
	Pref<BooleanSupplier> PRIVATE_MODE_ALWAYS = Pref.b("PRIVATE_MODE_ALWAYS", false);
	Pref<BooleanSupplier> PRIVATE_MODE_BLOCK_TRACKERS = Pref.b("PRIVATE_MODE_BLOCK_TRACKERS", true);
	Pref<BooleanSupplier> PRIVATE_MODE_BLOCK_3RD_PARTY_COOKIES =
			Pref.b("PRIVATE_MODE_BLOCK_3RD_PARTY_COOKIES", true);
	// Bumped to request an immediate data wipe regardless of whether PRIVATE_MODE_ENABLED actually
	// changes value (e.g. the Settings "clear now" button while already in Private Mode) -- a plain
	// boolean pref only broadcasts on a real value change, so a timestamp is used instead.
	Pref<LongSupplier> PRIVATE_MODE_CLEAR_REQUEST = Pref.l("PRIVATE_MODE_CLEAR_REQUEST", 0L);
	// Bumped by the web addon module once cookies/site data have actually finished clearing --
	// CookieManager's removal is asynchronous, so a WebView reload triggered directly off
	// PRIVATE_MODE_ENABLED can race the clear and still show the old (personalized) page. WebViews
	// wait for this signal instead of reacting to PRIVATE_MODE_ENABLED directly for their reload.
	Pref<LongSupplier> PRIVATE_MODE_DATA_CLEARED_STAMP = Pref.l("PRIVATE_MODE_DATA_CLEARED_STAMP", 0L);
	// Bumped by the Settings "Clear browsing data" action -- clears the regular (non-Private Mode)
	// profile's cookies/storage/form data, same mechanism as PRIVATE_MODE_CLEAR_REQUEST but for the
	// Default profile, so a user can reset their normal session without ever touching Private Mode.
	Pref<LongSupplier> NORMAL_MODE_CLEAR_REQUEST = Pref.l("NORMAL_MODE_CLEAR_REQUEST", 0L);
	Pref<BooleanSupplier> VOICE_CONTROl_ENABLED = Pref.b("VOICE_CONTROl_ENABLED", false);
	Pref<BooleanSupplier> VOICE_CONTROl_FB = Pref.b("VOICE_CONTROl_FB", false);
	Pref<Supplier<String>> VOICE_CONTROL_SUBST = Pref.s("VOICE_CONTROL_SUBST", "");
	Pref<Supplier<String>> VOICE_CONTROL_LANG =
			Pref.s("VOICE_CONTROL_LANG", () -> Locale.getDefault().toLanguageTag());
	Pref<IntSupplier> CLOCK_POS = Pref.i("CLOCK_POS", CLOCK_POS_NONE);
	// Which items the fullscreen video Info Overlay (positioned via CLOCK_POS) shows -- SHOW_CLOCK
	// defaults to true to preserve the pre-existing clock-only behavior for anyone who already had
	// CLOCK_POS set to something other than NONE.
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_CLOCK = Pref.b("INFO_OVERLAY_SHOW_CLOCK", true);
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_BATTERY_PCT = Pref.b("INFO_OVERLAY_SHOW_BATTERY_PCT", false);
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_BATTERY_TEMP =
			Pref.b("INFO_OVERLAY_SHOW_BATTERY_TEMP", false);
	Pref<DoubleSupplier> INFO_OVERLAY_SIZE = Pref.f("INFO_OVERLAY_SIZE", 1f);
	Pref<IntSupplier> LOCALE =
			Pref.i("LOCALE", () -> Lang.get(Locale.getDefault().getLanguage()).ordinal());

	Pref<IntSupplier> THEME_AA = Pref.i("THEME_AA", THEME_DARK);
	Pref<BooleanSupplier> HIDE_BARS_AA = AUTO ? Pref.b("HIDE_BARS_AA", false) : null;
	Pref<BooleanSupplier> FULLSCREEN_AA = AUTO ? Pref.b("FULLSCREEN_AA", false) : null;
	Pref<BooleanSupplier> SHOW_PG_UP_DOWN_AA = AUTO ? Pref.b("SHOW_PG_UP_DOWN_AA", true) : null;
	Pref<IntSupplier> NAV_BAR_POS_AA =
			AUTO ? Pref.i("NAV_BAR_POS_AA", NavBarView.POSITION_BOTTOM) : null;
	Pref<DoubleSupplier> NAV_BAR_SIZE_AA = AUTO ? Pref.f("NAV_BAR_SIZE_AA", 1f) : null;
	Pref<DoubleSupplier> TOOL_BAR_SIZE_AA = AUTO ? Pref.f("TOOL_BAR_SIZE_AA", 1f) : null;
	Pref<DoubleSupplier> CONTROL_PANEL_SIZE_AA = AUTO ? Pref.f("CONTROL_PANEL_SIZE_AA", 1f) : null;
	Pref<DoubleSupplier> TEXT_ICON_SIZE_AA = AUTO ? Pref.f("TEXT_ICON_SIZE_AA", 1f) : null;
	Pref<DoubleSupplier> ICON_SIZE_AA = AUTO ? Pref.f("ICON_SIZE_AA", 1f) : null;
	Pref<BooleanSupplier> GRID_VIEW_AA = AUTO ? Pref.b("GRID_VIEW_AA", false) : null;

	static MainActivityPrefs get() {
		return MainActivityDelegate.Prefs.instance;
	}

	static boolean hasThemePref(MainActivityDelegate a, List<Pref<?>> prefs) {
		if (AUTO && a.isCarActivity()) return prefs.contains(THEME_AA);
		return prefs.contains(THEME_MAIN);
	}

	default int getThemePref(boolean auto) {
		return (AUTO && auto) ? getIntPref(THEME_AA) : getIntPref(THEME_MAIN);
	}

	@Nullable
	default String getShowAddonOnStartPref() {
		return getStringPref(SHOW_ADDON_ON_START);
	}

	default void setShowAddonOnStartPref(@Nullable String className) {
		applyStringPref(SHOW_ADDON_ON_START, className);
	}

	default boolean getCheckUpdatesPref() {
		if (!getBooleanPref(CHECK_UPDATES)) return false;
		var now = System.currentTimeMillis();
		var stamp = getLongPref(CHECK_UPDATES_STAMP);
		if ((now - stamp) < (24L * 3600000L)) return false;
		applyLongPref(CHECK_UPDATES_STAMP, now);
		return true;
	}

	static boolean hasFullscreenPref(MainActivityDelegate a, List<Pref<?>> prefs) {
		if (AUTO && a.isCarActivity()) return prefs.contains(FULLSCREEN_AA);
		return prefs.contains(FULLSCREEN);
	}

	default boolean getFullscreenPref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity()) return getBooleanPref(FULLSCREEN_AA);
		return getBooleanPref(FULLSCREEN);
	}

	default void setFullscreenPref(MainActivityDelegate a, boolean v) {
		applyBooleanPref((AUTO && a.isCarActivity()) ? FULLSCREEN_AA : FULLSCREEN, v);
	}

	static boolean hasHideBarsPref(MainActivityDelegate a, List<Pref<?>> prefs) {
		if (AUTO && a.isCarActivity()) return prefs.contains(HIDE_BARS_AA);
		return prefs.contains(HIDE_BARS);
	}

	default boolean getHideBarsPref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity()) return getBooleanPref(HIDE_BARS_AA);
		return getBooleanPref(HIDE_BARS);
	}

	default boolean getShowPgUpDownPref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity()) return getBooleanPref(SHOW_PG_UP_DOWN_AA);
		return getBooleanPref(SHOW_PG_UP_DOWN);
	}

	default boolean useDpadCursor(MainActivityDelegate a) {
		return AUTO && a.isCarActivity() && getBooleanPref(USE_DPAD_CURSOR);
	}

	static boolean hasNavBarPosPref(MainActivityDelegate a, List<Pref<?>> prefs) {
		if (AUTO && a.isCarActivity()) return prefs.contains(NAV_BAR_POS_AA);
		return prefs.contains(NAV_BAR_POS);
	}

	default int getNavBarPosPref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity()) return getIntPref(NAV_BAR_POS_AA);
		return getIntPref(NAV_BAR_POS);
	}

	static boolean hasNavBarSizePref(MainActivityDelegate a, List<Pref<?>> prefs) {
		if (AUTO && a.isCarActivity()) return prefs.contains(NAV_BAR_SIZE_AA);
		return prefs.contains(NAV_BAR_SIZE);
	}

	default float getNavBarSizePref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity()) return getFloatPref(NAV_BAR_SIZE_AA) * NAV_BAR_SIZE_BASE_AUTO;
		return getFloatPref(NAV_BAR_SIZE);
	}

	static boolean hasToolBarSizePref(MainActivityDelegate a, List<Pref<?>> prefs) {
		if (AUTO && a.isCarActivity()) return prefs.contains(TOOL_BAR_SIZE_AA);
		return prefs.contains(TOOL_BAR_SIZE);
	}

	default float getToolBarSizePref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity()) return getFloatPref(TOOL_BAR_SIZE_AA) * TOOL_BAR_SIZE_BASE_AUTO;
		return getFloatPref(TOOL_BAR_SIZE) * TOOL_BAR_SIZE_BASE_MOBILE;
	}

	static boolean hasControlPanelSizePref(MainActivityDelegate a, List<Pref<?>> prefs) {
		if (AUTO && a.isCarActivity()) return prefs.contains(CONTROL_PANEL_SIZE_AA);
		return prefs.contains(CONTROL_PANEL_SIZE);
	}

	default float getControlPanelSizePref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity())
			return getFloatPref(CONTROL_PANEL_SIZE_AA) * CONTROL_PANEL_SIZE_BASE_AUTO;
		return getFloatPref(CONTROL_PANEL_SIZE) * CONTROL_PANEL_SIZE_BASE_MOBILE;
	}

	static boolean hasTextIconSizePref(MainActivityDelegate a, List<Pref<?>> prefs) {
		if (AUTO && a.isCarActivity()) return prefs.contains(TEXT_ICON_SIZE_AA);
		return prefs.contains(TEXT_ICON_SIZE);
	}

	default float getTextIconSizePref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity()) return getFloatPref(TEXT_ICON_SIZE_AA);
		return getFloatPref(TEXT_ICON_SIZE);
	}

	static boolean hasIconSizePref(MainActivityDelegate a, List<Pref<?>> prefs) {
		if (AUTO && a.isCarActivity()) return prefs.contains(ICON_SIZE_AA);
		return prefs.contains(ICON_SIZE);
	}

	default float getIconSizePref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity()) return getFloatPref(ICON_SIZE_AA);
		return getFloatPref(ICON_SIZE);
	}

	static boolean hasGridViewPref(MainActivityDelegate a, List<Pref<?>> prefs) {
		return prefs.contains(getGridViewPrefKey(a));
	}

	static Pref<BooleanSupplier> getGridViewPrefKey(MainActivityDelegate a) {
		return (AUTO && a.isCarActivity()) ? GRID_VIEW_AA : GRID_VIEW;
	}

	default boolean getGridViewPref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity()) return getBooleanPref(GRID_VIEW_AA);
		return getBooleanPref(GRID_VIEW);
	}

	default void setGridViewPref(MainActivityDelegate a, boolean value) {
		applyBooleanPref(getGridViewPrefKey(a), value);
	}

	default float getFabSizePref() {
		return getFloatPref(FAB_SIZE);
	}

	default boolean getSysBarsOnVideoTouchPref() {
		return getBooleanPref(SYS_BARS_ON_VIDEO_TOUCH);
	}

	default boolean getLandscapeVideoPref() {
		return getBooleanPref(LANDSCAPE_VIDEO);
	}

	default boolean getChangeBrightnessPref() {
		return getBooleanPref(CHANGE_BRIGHTNESS);
	}

	default int getBrightnessPref() {
		return getIntPref(BRIGHTNESS);
	}

	@ColorInt
	default int resolveDimColor() {
		int idx = getIntPref(DIM_COLOR_PRESET);
		if (idx == DIM_COLOR_CUSTOM_IDX) {
			return Color.rgb(getIntPref(DIM_COLOR_CUSTOM_R), getIntPref(DIM_COLOR_CUSTOM_G),
					getIntPref(DIM_COLOR_CUSTOM_B));
		}
		return ((idx >= 0) && (idx < DIM_COLOR_PRESETS.length)) ? DIM_COLOR_PRESETS[idx] : Color.BLACK;
	}

	default boolean isPrivateModeEnabled() {
		return getBooleanPref(PRIVATE_MODE_ENABLED);
	}

	/**
	 * Toggles Private Mode. Actually clearing cookies/site data on the transition is left to
	 * whoever owns the WebViews (the web addon module, which this interface -- part of core -- must
	 * not depend on); those modules react to the {@code PRIVATE_MODE_ENABLED} broadcast themselves.
	 */
	default void setPrivateModeEnabled(boolean enabled) {
		applyBooleanPref(PRIVATE_MODE_ENABLED, enabled);
	}

	default void requestPrivateDataClear() {
		applyLongPref(PRIVATE_MODE_CLEAR_REQUEST, System.currentTimeMillis());
	}

	default void notifyPrivateModeDataCleared() {
		applyLongPref(PRIVATE_MODE_DATA_CLEARED_STAMP, System.currentTimeMillis());
	}

	default void requestNormalDataClear() {
		applyLongPref(NORMAL_MODE_CLEAR_REQUEST, System.currentTimeMillis());
	}

	default boolean getVoiceControlEnabledPref() {
		return getBooleanPref(VOICE_CONTROl_ENABLED);
	}

	default boolean getVoiceControlFBPref() {
		return getBooleanPref(VOICE_CONTROl_FB);
	}

	default String getVoiceControlLang(MainActivityDelegate a) {
		return a.getPrefs().getStringPref(VOICE_CONTROL_LANG);
	}

	default int getClockPosPref() {
		return getIntPref(CLOCK_POS);
	}

	default boolean getInfoOverlayShowClockPref() {
		return getBooleanPref(INFO_OVERLAY_SHOW_CLOCK);
	}

	default boolean getInfoOverlayShowBatteryPctPref() {
		return getBooleanPref(INFO_OVERLAY_SHOW_BATTERY_PCT);
	}

	default boolean getInfoOverlayShowBatteryTempPref() {
		return getBooleanPref(INFO_OVERLAY_SHOW_BATTERY_TEMP);
	}

	default float getInfoOverlaySizePref() {
		return getFloatPref(INFO_OVERLAY_SIZE);
	}

	default Locale getLocalePref() {
		return Lang.get(getIntPref(LOCALE)).locale;
	}

	enum Lang {
		EN(Locale.ENGLISH),
		RU,
		IT(Locale.ITALIAN),
		TR,
		DE(Locale.GERMAN),
		PT,
		VI,
		PL,
		HR,
		JA,
		ZH_TW(Locale.TRADITIONAL_CHINESE),
		KO,
		FR(Locale.FRENCH),
    RO,
		AR,
		ES,
		KM,
    ;

		private static final List<Lang> values = List.of(values());
		private static final Map<String, Lang> nameToValue = new HashMap<>();
		public final Locale locale;

		static {
			for (var v : values) {
				nameToValue.put(v.locale.getLanguage(), v);
			}
		}

		Lang() {
			locale = new Locale(name().toLowerCase());
		}

		Lang(Locale locale) {
			this.locale = locale;
		}

		public static List<Lang> getValues() {
			return values;
		}

		public static Lang get(int pref) {
			return ((pref < 0) || (pref >= Lang.values.size())) ? Lang.EN : Lang.values.get(pref);
		}

		public static Lang get(String name) {
			var value = nameToValue.get(name);
			return (value == null) ? Lang.EN : value;
		}
	}
}
