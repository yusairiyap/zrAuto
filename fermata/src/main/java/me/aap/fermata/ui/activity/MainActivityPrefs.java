package me.aap.fermata.ui.activity;

import static me.aap.fermata.BuildConfig.AUTO;

import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
	/** What tapping a track in a list does: {@link #TAP_PLAYS} or {@link #TAP_OPENS_MENU}. */
	Pref<IntSupplier> TAP_ACTION = Pref.i("TAP_ACTION", 0);
	int TAP_PLAYS = 0;
	int TAP_OPENS_MENU = 1;
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
	// Independent of TEXT_ICON_SIZE: scales only the grid card size (span count), adjustable live
	// via the toolbar's card-size slider.
	Pref<DoubleSupplier> GRID_ITEM_SIZE = Pref.f("GRID_ITEM_SIZE", 1f);
	/** See getListItemSizePref(). */
	Pref<DoubleSupplier> LIST_ITEM_SIZE = Pref.f("LIST_ITEM_SIZE", 1f);
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
	// All four FABs (primary + secondary + tertiary + fourth) are available out of the box: FAB2
	// defaults to the fullscreen toggle, FAB3 to the dim-screen toggle, FAB4 to add to favourites.
	Pref<BooleanSupplier> FAB2_ENABLED = Pref.b("FAB2_ENABLED", true);
	Pref<IntSupplier> FAB2_ACTION = Pref.i("FAB2_ACTION", Action.FULLSCREEN_TOGGLE.ordinal());
	Pref<BooleanSupplier> FAB3_ENABLED = Pref.b("FAB3_ENABLED", true);
	Pref<IntSupplier> FAB3_ACTION = Pref.i("FAB3_ACTION", Action.DIM_TOGGLE.ordinal());
	Pref<BooleanSupplier> FAB4_ENABLED = Pref.b("FAB4_ENABLED", true);
	Pref<IntSupplier> FAB4_ACTION = Pref.i("FAB4_ACTION", Action.FAVORITE_ADD.ordinal());
	// FAB5 (search) is on out of the box too; FAB6 is there to be turned on.
	Pref<BooleanSupplier> FAB5_ENABLED = Pref.b("FAB5_ENABLED", true);
	Pref<IntSupplier> FAB5_ACTION = Pref.i("FAB5_ACTION", Action.YOUTUBE_SEARCH.ordinal());
	Pref<BooleanSupplier> FAB6_ENABLED = Pref.b("FAB6_ENABLED", false);
	Pref<IntSupplier> FAB6_ACTION = Pref.i("FAB6_ACTION", Action.OPEN_PLAYLISTS.ordinal());
	// FAB7 to FAB10 are there to be added from Settings (how many extra buttons are in use is
	// FAB_COUNT); the rest of the slots stay unused until then.
	Pref<BooleanSupplier> FAB7_ENABLED = Pref.b("FAB7_ENABLED", true);
	Pref<IntSupplier> FAB7_ACTION = Pref.i("FAB7_ACTION", Action.PLAY_PAUSE.ordinal());
	Pref<BooleanSupplier> FAB8_ENABLED = Pref.b("FAB8_ENABLED", true);
	Pref<IntSupplier> FAB8_ACTION = Pref.i("FAB8_ACTION", Action.PLAY_PAUSE.ordinal());
	Pref<BooleanSupplier> FAB9_ENABLED = Pref.b("FAB9_ENABLED", true);
	Pref<IntSupplier> FAB9_ACTION = Pref.i("FAB9_ACTION", Action.PLAY_PAUSE.ordinal());
	Pref<BooleanSupplier> FAB10_ENABLED = Pref.b("FAB10_ENABLED", true);
	Pref<IntSupplier> FAB10_ACTION = Pref.i("FAB10_ACTION", Action.PLAY_PAUSE.ordinal());
	/** The extra buttons (FAB2, FAB3, ...) in use: the first this many slots; the rest are unused. */
	int FAB_COUNT_DEFAULT = 5;
	int FAB_COUNT_MAX = 9;
	Pref<IntSupplier> FAB_COUNT = Pref.i("FAB_COUNT", FAB_COUNT_DEFAULT);
	// Fixed in place by default: dragging is easy to trigger by accident while driving.
	Pref<BooleanSupplier> FAB_DRAGGABLE = Pref.b("FAB_DRAGGABLE", false);
	// The floating buttons shown together as one pill (see FabPillView) rather than as separate
	// round buttons; on by default.
	Pref<BooleanSupplier> FAB_PILL = Pref.b("FAB_PILL", true);
	// The bars slide away as tab content scrolls down (see ScrollBarsController); on by default.
	Pref<BooleanSupplier> SCROLL_HIDE_BARS = Pref.b("SCROLL_HIDE_BARS", true);
	// 5% smaller than the original 1.0 out of the box.
	Pref<DoubleSupplier> FAB_SIZE = Pref.f("FAB_SIZE", 0.95f);
	// The All in One card (see AllInOneMenu): its actions, by Action name in the order shown (the
	// Music tab's More out of the box), tiles per row, and whether the Music tab's More follows it.
	String AIO_DEFAULT_ITEMS = "AUDIO_EFFECTS,SLEEP_TIMER,FAVORITE_ADD,PLAYLIST_ADD";
	Pref<Supplier<String>> AIO_ITEMS = Pref.s("AIO_ITEMS", AIO_DEFAULT_ITEMS);
	Pref<IntSupplier> AIO_COLUMNS = Pref.i("AIO_COLUMNS", 2);
	Pref<BooleanSupplier> AIO_MUSIC_MORE = Pref.b("AIO_MUSIC_MORE", false);
	// YouTube's captions (see ytcaptions/CaptionsHub): shown as lyrics over the Music tab's cover
	// (the Lyrics chip), and as captions over fullscreen video (the Captions action). Off until asked.
	Pref<BooleanSupplier> LYRICS_ON = Pref.b("LYRICS_ON", false);
	Pref<BooleanSupplier> CAPTIONS_ON = Pref.b("CAPTIONS_ON", false);
	// Language code to prefer ("" = the phone's), and whether speech-recognition captions may be used
	// when a video has no written ones.
	Pref<Supplier<String>> CAPTIONS_LANG = Pref.s("CAPTIONS_LANG", "");
	Pref<BooleanSupplier> CAPTIONS_AUTO = Pref.b("CAPTIONS_AUTO", true);
	// How the lyrics look, on the Music tab's cover and in their card (see ui/view/LyricsView):
	// text size (percent), font (CaptionStyle.FONT_NAMES), bold, italic, alignment (0 centre, 1 left),
	// the playing line's colour (0 follows the theme, else CaptionStyle.COLORS index + 1) and how
	// dark the veil over the cover is (percent).
	Pref<IntSupplier> LYRICS_SIZE = Pref.i("LYRICS_SIZE", 100);
	Pref<IntSupplier> LYRICS_FONT = Pref.i("LYRICS_FONT", 0);
	Pref<BooleanSupplier> LYRICS_BOLD = Pref.b("LYRICS_BOLD", false);
	Pref<BooleanSupplier> LYRICS_ITALIC = Pref.b("LYRICS_ITALIC", false);
	Pref<IntSupplier> LYRICS_ALIGN = Pref.i("LYRICS_ALIGN", 0);
	Pref<IntSupplier> LYRICS_COLOR = Pref.i("LYRICS_COLOR", 0);
	Pref<IntSupplier> LYRICS_VEIL = Pref.i("LYRICS_VEIL", 65);
	// How the captions over fullscreen video look (see ui/view/CaptionStyle).
	Pref<IntSupplier> CAPTION_FONT = Pref.i("CAPTION_FONT", 0);
	Pref<IntSupplier> CAPTION_SIZE = Pref.i("CAPTION_SIZE", 100);
	Pref<BooleanSupplier> CAPTION_BOLD = Pref.b("CAPTION_BOLD", false);
	Pref<BooleanSupplier> CAPTION_ITALIC = Pref.b("CAPTION_ITALIC", false);
	Pref<IntSupplier> CAPTION_COLOR = Pref.i("CAPTION_COLOR", 0);
	Pref<IntSupplier> CAPTION_OPACITY = Pref.i("CAPTION_OPACITY", 100);
	Pref<IntSupplier> CAPTION_EDGE = Pref.i("CAPTION_EDGE", 1);
	Pref<IntSupplier> CAPTION_EDGE_COLOR = Pref.i("CAPTION_EDGE_COLOR", 7);
	Pref<IntSupplier> CAPTION_EDGE_STRENGTH = Pref.i("CAPTION_EDGE_STRENGTH", 60);
	Pref<IntSupplier> CAPTION_BG_COLOR = Pref.i("CAPTION_BG_COLOR", 7);
	Pref<IntSupplier> CAPTION_BG_OPACITY = Pref.i("CAPTION_BG_OPACITY", 50);
	// 0 bottom, 1 middle, 2 top; and how far from that edge, percent of the picture's height.
	Pref<IntSupplier> CAPTION_POSITION = Pref.i("CAPTION_POSITION", 0);
	Pref<IntSupplier> CAPTION_MARGIN = Pref.i("CAPTION_MARGIN", 10);
	// The comments dock beside fullscreen video on a big screen / the car screen (see
	// YoutubeCommentsView): whether to, and how much of the width they take (percent).
	Pref<BooleanSupplier> COMMENTS_SPLIT = Pref.b("COMMENTS_SPLIT", true);
	Pref<IntSupplier> COMMENTS_SPLIT_PERCENT = Pref.i("COMMENTS_SPLIT_PERCENT", 38);
	// How many upcoming Favorites/Playlist entries the YouTube tab's Up next list previews below the
	// user's own queue (1..10) -- enough to see what comes after, few enough to leave room for search
	// results.
	Pref<IntSupplier> UP_NEXT_LIST_PREVIEW = Pref.i("UP_NEXT_LIST_PREVIEW", 3);
	// The most videos the YouTube tab's Up next queue holds (1..50).
	Pref<IntSupplier> UP_NEXT_MAX = Pref.i("UP_NEXT_MAX", 20);
	// Whether the YouTube tab remembers past searches (shown as chips in its search panel).
	Pref<BooleanSupplier> SEARCH_HISTORY_ENABLED = Pref.b("YT_SEARCH_HISTORY_ENABLED", true);
	// Whether tapping a YouTube search result queues it (Play next) instead of playing it -- its
	// row button then plays it instead.
	Pref<BooleanSupplier> YT_SEARCH_TAP_QUEUES = Pref.b("YT_SEARCH_TAP_QUEUES", false);
	// Whether the YouTube tab's search and Up next open on their own (the search field/button shows
	// just the search, the Up next button just the queue) rather than together.
	Pref<BooleanSupplier> YT_SEPARATE_PANELS = Pref.b("YT_SEPARATE_PANELS", false);
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
	// Opt-in diagnostics -- see me.aap.fermata.util.DiagnosticLog. Off by default and deliberately
	// not persisted as "on" by anything but an explicit user toggle: it records an event trace only
	// while the user is actively investigating something they can only reproduce in the car.
	Pref<BooleanSupplier> DEBUG_LOG_ENABLED = Pref.b("DEBUG_LOG_ENABLED", false);
	Pref<BooleanSupplier> DEBUG_LOG_TOASTS = Pref.b("DEBUG_LOG_TOASTS", false);
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
	// Per-item icon toggles, shown alongside each label -- default true so the icons appear
	// out of the box wherever the label itself is already shown.
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_CLOCK_ICON = Pref.b("INFO_OVERLAY_SHOW_CLOCK_ICON", true);
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_BATTERY_ICON =
			Pref.b("INFO_OVERLAY_SHOW_BATTERY_ICON", true);
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_TEMP_ICON = Pref.b("INFO_OVERLAY_SHOW_TEMP_ICON", true);
	Pref<BooleanSupplier> INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE =
			Pref.b("INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE", false);
	// Current Fuel Log trip distance, offered as a 4th Info Overlay item -- see FuelLogStore.
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DISTANCE = Pref.b("INFO_OVERLAY_SHOW_DISTANCE", false);
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DISTANCE_ICON =
			Pref.b("INFO_OVERLAY_SHOW_DISTANCE_ICON", true);
	Pref<DoubleSupplier> INFO_OVERLAY_SIZE = Pref.f("INFO_OVERLAY_SIZE", 1f);
	// The Data Usage addon's items: data used this cycle, and what's left of the data limit (only
	// shown when a limit is set) -- see DataUsageTracker.
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DATA_USAGE = Pref.b("INFO_OVERLAY_SHOW_DATA_USAGE", false);
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DATA_REMAINING =
			Pref.b("INFO_OVERLAY_SHOW_DATA_REMAINING", false);
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DATA_ICON = Pref.b("INFO_OVERLAY_SHOW_DATA_ICON", true);
	// The Android Auto session's data (DataSessionLimit): shown during a session only, with a mini
	// progress bar unless turned off, as used or as what's left.
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DATA_SESSION = Pref.b("INFO_OVERLAY_SHOW_DATA_SESSION", false);
	Pref<BooleanSupplier> INFO_OVERLAY_DATA_SESSION_BAR = Pref.b("INFO_OVERLAY_DATA_SESSION_BAR", true);
	Pref<BooleanSupplier> INFO_OVERLAY_DATA_SESSION_LEFT = Pref.b("INFO_OVERLAY_DATA_SESSION_LEFT", false);
	// An icon (first, no text) while what's playing has a downloaded copy (see YtDownloads).
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DOWNLOADED = Pref.b("INFO_OVERLAY_SHOW_DOWNLOADED", false);
	// The Power Usage addon's item: the battery current in mA, live -- see PowerUsageTracker.
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_POWER = Pref.b("INFO_OVERLAY_SHOW_POWER", false);
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_POWER_ICON = Pref.b("INFO_OVERLAY_SHOW_POWER_ICON", true);
	// Every Info Overlay pref, for the views showing it (fullscreen video, the Music tab) to follow.
	// The overlay's items in the order they're laid out, as keys joined by commas (see
	// InfoOverlayView#ITEM_KEYS); empty: the default order. Items missing from it follow in default order.
	Pref<Supplier<String>> INFO_OVERLAY_ORDER = Pref.s("INFO_OVERLAY_ORDER", "");
	Set<Pref<?>> INFO_OVERLAY_PREFS = Set.of(CLOCK_POS, INFO_OVERLAY_ORDER, INFO_OVERLAY_SHOW_CLOCK,
			INFO_OVERLAY_SHOW_CLOCK_ICON, INFO_OVERLAY_SHOW_BATTERY_PCT, INFO_OVERLAY_SHOW_BATTERY_ICON,
			INFO_OVERLAY_SHOW_BATTERY_TEMP, INFO_OVERLAY_SHOW_TEMP_ICON, INFO_OVERLAY_SHOW_DISTANCE,
			INFO_OVERLAY_SHOW_DISTANCE_ICON, INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE,
			INFO_OVERLAY_SIZE, INFO_OVERLAY_SHOW_DATA_USAGE, INFO_OVERLAY_SHOW_DATA_REMAINING,
			INFO_OVERLAY_SHOW_DATA_ICON, INFO_OVERLAY_SHOW_DOWNLOADED, INFO_OVERLAY_SHOW_DATA_SESSION,
			INFO_OVERLAY_DATA_SESSION_BAR, INFO_OVERLAY_DATA_SESSION_LEFT, INFO_OVERLAY_SHOW_POWER,
			INFO_OVERLAY_SHOW_POWER_ICON);
	// The car screen's own Info Overlay settings (the phone's are the plain ones above), so the two
	// screens can show different items; copied from the phone's once, see INFO_OVERLAY_AA_MIGRATED.
	Pref<IntSupplier> CLOCK_POS_AA = AUTO ? Pref.i("CLOCK_POS_AA", CLOCK_POS_NONE) : null;
	Pref<Supplier<String>> INFO_OVERLAY_ORDER_AA = AUTO ? Pref.s("INFO_OVERLAY_ORDER_AA", "") : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_CLOCK_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_CLOCK_AA", true) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_CLOCK_ICON_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_CLOCK_ICON_AA", true) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_BATTERY_PCT_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_BATTERY_PCT_AA", false) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_BATTERY_ICON_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_BATTERY_ICON_AA", true) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_BATTERY_TEMP_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_BATTERY_TEMP_AA", false) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_TEMP_ICON_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_TEMP_ICON_AA", true) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DISTANCE_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_DISTANCE_AA", false) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DISTANCE_ICON_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_DISTANCE_ICON_AA", true) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE_AA = AUTO ? Pref.b("INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE_AA", false) : null;
	Pref<DoubleSupplier> INFO_OVERLAY_SIZE_AA = AUTO ? Pref.f("INFO_OVERLAY_SIZE_AA", 1f) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DATA_USAGE_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_DATA_USAGE_AA", false) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DATA_REMAINING_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_DATA_REMAINING_AA", false) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DATA_ICON_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_DATA_ICON_AA", true) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DOWNLOADED_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_DOWNLOADED_AA", false) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_DATA_SESSION_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_DATA_SESSION_AA", false) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_DATA_SESSION_BAR_AA = AUTO ? Pref.b("INFO_OVERLAY_DATA_SESSION_BAR_AA", true) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_DATA_SESSION_LEFT_AA = AUTO ? Pref.b("INFO_OVERLAY_DATA_SESSION_LEFT_AA", false) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_POWER_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_POWER_AA", false) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_SHOW_POWER_ICON_AA = AUTO ? Pref.b("INFO_OVERLAY_SHOW_POWER_ICON_AA", true) : null;
	Pref<BooleanSupplier> INFO_OVERLAY_AA_MIGRATED =
			AUTO ? Pref.b("INFO_OVERLAY_AA_MIGRATED", false) : null;

	/** The Android Auto twin of an Info Overlay pref, or null if it has none. */
	@Nullable
	static Pref<?> infoAA(Pref<?> p) {
		if (!AUTO) return null;
		if (p == CLOCK_POS) return CLOCK_POS_AA;
		if (p == INFO_OVERLAY_ORDER) return INFO_OVERLAY_ORDER_AA;
		if (p == INFO_OVERLAY_SHOW_CLOCK) return INFO_OVERLAY_SHOW_CLOCK_AA;
		if (p == INFO_OVERLAY_SHOW_CLOCK_ICON) return INFO_OVERLAY_SHOW_CLOCK_ICON_AA;
		if (p == INFO_OVERLAY_SHOW_BATTERY_PCT) return INFO_OVERLAY_SHOW_BATTERY_PCT_AA;
		if (p == INFO_OVERLAY_SHOW_BATTERY_ICON) return INFO_OVERLAY_SHOW_BATTERY_ICON_AA;
		if (p == INFO_OVERLAY_SHOW_BATTERY_TEMP) return INFO_OVERLAY_SHOW_BATTERY_TEMP_AA;
		if (p == INFO_OVERLAY_SHOW_TEMP_ICON) return INFO_OVERLAY_SHOW_TEMP_ICON_AA;
		if (p == INFO_OVERLAY_SHOW_DISTANCE) return INFO_OVERLAY_SHOW_DISTANCE_AA;
		if (p == INFO_OVERLAY_SHOW_DISTANCE_ICON) return INFO_OVERLAY_SHOW_DISTANCE_ICON_AA;
		if (p == INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE) return INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE_AA;
		if (p == INFO_OVERLAY_SIZE) return INFO_OVERLAY_SIZE_AA;
		if (p == INFO_OVERLAY_SHOW_DATA_USAGE) return INFO_OVERLAY_SHOW_DATA_USAGE_AA;
		if (p == INFO_OVERLAY_SHOW_DATA_REMAINING) return INFO_OVERLAY_SHOW_DATA_REMAINING_AA;
		if (p == INFO_OVERLAY_SHOW_DATA_ICON) return INFO_OVERLAY_SHOW_DATA_ICON_AA;
		if (p == INFO_OVERLAY_SHOW_DOWNLOADED) return INFO_OVERLAY_SHOW_DOWNLOADED_AA;
		if (p == INFO_OVERLAY_SHOW_DATA_SESSION) return INFO_OVERLAY_SHOW_DATA_SESSION_AA;
		if (p == INFO_OVERLAY_DATA_SESSION_BAR) return INFO_OVERLAY_DATA_SESSION_BAR_AA;
		if (p == INFO_OVERLAY_DATA_SESSION_LEFT) return INFO_OVERLAY_DATA_SESSION_LEFT_AA;
		if (p == INFO_OVERLAY_SHOW_POWER) return INFO_OVERLAY_SHOW_POWER_AA;
		if (p == INFO_OVERLAY_SHOW_POWER_ICON) return INFO_OVERLAY_SHOW_POWER_ICON_AA;
		return null;
	}

	/** An Info Overlay pref as it applies to {@code a}: its Android Auto twin on the car screen. */
	@SuppressWarnings("unchecked")
	static <S> Pref<S> info(@Nullable MainActivityDelegate a, Pref<S> p) {
		if (!AUTO || (a == null) || !a.isCarActivity()) return p;
		Pref<?> aa = infoAA(p);
		return (aa == null) ? p : (Pref<S>) aa;
	}

	/** Every Info Overlay pref, phone and car, for the views showing it to follow. */
	static Set<Pref<?>> infoOverlayPrefs() {
		java.util.HashSet<Pref<?>> all = new java.util.HashSet<>(INFO_OVERLAY_PREFS);
		if (AUTO) for (Pref<?> p : INFO_OVERLAY_PREFS) all.add(infoAA(p));
		return all;
	}
	Pref<IntSupplier> LOCALE =
			Pref.i("LOCALE", () -> Lang.get(Locale.getDefault().getLanguage()).ordinal());

	Pref<IntSupplier> THEME_AA = Pref.i("THEME_AA", THEME_DARK);
	Pref<BooleanSupplier> HIDE_BARS_AA = AUTO ? Pref.b("HIDE_BARS_AA", false) : null;
	Pref<BooleanSupplier> FULLSCREEN_AA = AUTO ? Pref.b("FULLSCREEN_AA", false) : null;
	// The car screen's own "Hide bars while scrolling", see SCROLL_HIDE_BARS.
	Pref<BooleanSupplier> SCROLL_HIDE_BARS_AA = AUTO ? Pref.b("SCROLL_HIDE_BARS_AA", true) : null;
	Pref<BooleanSupplier> SHOW_PG_UP_DOWN_AA = AUTO ? Pref.b("SHOW_PG_UP_DOWN_AA", true) : null;
	Pref<IntSupplier> TAP_ACTION_AA = AUTO ? Pref.i("TAP_ACTION_AA", 0) : null;
	Pref<IntSupplier> NAV_BAR_POS_AA =
			AUTO ? Pref.i("NAV_BAR_POS_AA", NavBarView.POSITION_BOTTOM) : null;
	Pref<DoubleSupplier> NAV_BAR_SIZE_AA = AUTO ? Pref.f("NAV_BAR_SIZE_AA", 1f) : null;
	Pref<DoubleSupplier> TOOL_BAR_SIZE_AA = AUTO ? Pref.f("TOOL_BAR_SIZE_AA", 1f) : null;
	Pref<DoubleSupplier> CONTROL_PANEL_SIZE_AA = AUTO ? Pref.f("CONTROL_PANEL_SIZE_AA", 1f) : null;
	Pref<DoubleSupplier> TEXT_ICON_SIZE_AA = AUTO ? Pref.f("TEXT_ICON_SIZE_AA", 1f) : null;
	Pref<DoubleSupplier> ICON_SIZE_AA = AUTO ? Pref.f("ICON_SIZE_AA", 1f) : null;
	Pref<BooleanSupplier> GRID_VIEW_AA = AUTO ? Pref.b("GRID_VIEW_AA", false) : null;
	// The floating buttons' own set for the car screen, independent of the phone's (see fab()).
	Pref<BooleanSupplier> FAB2_ENABLED_AA = AUTO ? Pref.b("FAB2_ENABLED_AA", true) : null;
	Pref<IntSupplier> FAB2_ACTION_AA =
			AUTO ? Pref.i("FAB2_ACTION_AA", Action.FULLSCREEN_TOGGLE.ordinal()) : null;
	Pref<BooleanSupplier> FAB3_ENABLED_AA = AUTO ? Pref.b("FAB3_ENABLED_AA", true) : null;
	Pref<IntSupplier> FAB3_ACTION_AA =
			AUTO ? Pref.i("FAB3_ACTION_AA", Action.DIM_TOGGLE.ordinal()) : null;
	Pref<BooleanSupplier> FAB4_ENABLED_AA = AUTO ? Pref.b("FAB4_ENABLED_AA", true) : null;
	Pref<IntSupplier> FAB4_ACTION_AA =
			AUTO ? Pref.i("FAB4_ACTION_AA", Action.FAVORITE_ADD.ordinal()) : null;
	Pref<BooleanSupplier> FAB5_ENABLED_AA = AUTO ? Pref.b("FAB5_ENABLED_AA", true) : null;
	Pref<IntSupplier> FAB5_ACTION_AA =
			AUTO ? Pref.i("FAB5_ACTION_AA", Action.YOUTUBE_SEARCH.ordinal()) : null;
	Pref<BooleanSupplier> FAB6_ENABLED_AA = AUTO ? Pref.b("FAB6_ENABLED_AA", false) : null;
	Pref<IntSupplier> FAB6_ACTION_AA =
			AUTO ? Pref.i("FAB6_ACTION_AA", Action.OPEN_PLAYLISTS.ordinal()) : null;
	Pref<BooleanSupplier> FAB7_ENABLED_AA = AUTO ? Pref.b("FAB7_ENABLED_AA", true) : null;
	Pref<IntSupplier> FAB7_ACTION_AA = AUTO ? Pref.i("FAB7_ACTION_AA", Action.PLAY_PAUSE.ordinal()) : null;
	Pref<BooleanSupplier> FAB8_ENABLED_AA = AUTO ? Pref.b("FAB8_ENABLED_AA", true) : null;
	Pref<IntSupplier> FAB8_ACTION_AA = AUTO ? Pref.i("FAB8_ACTION_AA", Action.PLAY_PAUSE.ordinal()) : null;
	Pref<BooleanSupplier> FAB9_ENABLED_AA = AUTO ? Pref.b("FAB9_ENABLED_AA", true) : null;
	Pref<IntSupplier> FAB9_ACTION_AA = AUTO ? Pref.i("FAB9_ACTION_AA", Action.PLAY_PAUSE.ordinal()) : null;
	Pref<BooleanSupplier> FAB10_ENABLED_AA = AUTO ? Pref.b("FAB10_ENABLED_AA", true) : null;
	Pref<IntSupplier> FAB10_ACTION_AA = AUTO ? Pref.i("FAB10_ACTION_AA", Action.PLAY_PAUSE.ordinal()) : null;
	Pref<IntSupplier> FAB_COUNT_AA = AUTO ? Pref.i("FAB_COUNT_AA", FAB_COUNT_DEFAULT) : null;
	Pref<BooleanSupplier> FAB_DRAGGABLE_AA = AUTO ? Pref.b("FAB_DRAGGABLE_AA", false) : null;
	Pref<DoubleSupplier> FAB_SIZE_AA = AUTO ? Pref.f("FAB_SIZE_AA", 0.95f) : null;
	// The car screen's own All in One card.
	Pref<Supplier<String>> AIO_ITEMS_AA = AUTO ? Pref.s("AIO_ITEMS_AA", AIO_DEFAULT_ITEMS) : null;
	Pref<IntSupplier> AIO_COLUMNS_AA = AUTO ? Pref.i("AIO_COLUMNS_AA", 2) : null;
	Pref<BooleanSupplier> AIO_MUSIC_MORE_AA = AUTO ? Pref.b("AIO_MUSIC_MORE_AA", false) : null;

	/**
	 * A floating button pref as it applies to {@code a}: on the car screen its Android Auto twin
	 * (FAB2_ENABLED -> FAB2_ENABLED_AA, ...), so the phone and the car each keep their own
	 * buttons, actions and size; the phone pref itself anywhere else.
	 */
	@SuppressWarnings("unchecked")
	static <S> Pref<S> fab(MainActivityDelegate a, Pref<S> p) {
		if (!AUTO || !a.isCarActivity()) return p;
		Pref<?> aa = fabAA(p);
		return (aa == null) ? p : (Pref<S>) aa;
	}

	/** The Android Auto twin of a phone floating button pref, or null if it has none. */
	@Nullable
	static Pref<?> fabAA(Pref<?> p) {
		if (!AUTO) return null;
		if (p == FAB2_ENABLED) return FAB2_ENABLED_AA;
		if (p == FAB2_ACTION) return FAB2_ACTION_AA;
		if (p == FAB3_ENABLED) return FAB3_ENABLED_AA;
		if (p == FAB3_ACTION) return FAB3_ACTION_AA;
		if (p == FAB4_ENABLED) return FAB4_ENABLED_AA;
		if (p == FAB4_ACTION) return FAB4_ACTION_AA;
		if (p == FAB5_ENABLED) return FAB5_ENABLED_AA;
		if (p == FAB5_ACTION) return FAB5_ACTION_AA;
		if (p == FAB6_ENABLED) return FAB6_ENABLED_AA;
		if (p == FAB6_ACTION) return FAB6_ACTION_AA;
		if (p == FAB7_ENABLED) return FAB7_ENABLED_AA;
		if (p == FAB7_ACTION) return FAB7_ACTION_AA;
		if (p == FAB8_ENABLED) return FAB8_ENABLED_AA;
		if (p == FAB8_ACTION) return FAB8_ACTION_AA;
		if (p == FAB9_ENABLED) return FAB9_ENABLED_AA;
		if (p == FAB9_ACTION) return FAB9_ACTION_AA;
		if (p == FAB10_ENABLED) return FAB10_ENABLED_AA;
		if (p == FAB10_ACTION) return FAB10_ACTION_AA;
		if (p == FAB_COUNT) return FAB_COUNT_AA;
		if (p == FAB_DRAGGABLE) return FAB_DRAGGABLE_AA;
		if (p == FAB_SIZE) return FAB_SIZE_AA;
		// Not a floating button's, but kept apart for the car screen the same way.
		if (p == AIO_ITEMS) return AIO_ITEMS_AA;
		if (p == AIO_COLUMNS) return AIO_COLUMNS_AA;
		if (p == AIO_MUSIC_MORE) return AIO_MUSIC_MORE_AA;
		return null;
	}

	/** The "shown" setting of floating button {@code slot} (2 to 10). */
	static Pref<BooleanSupplier> fabEnabled(int slot) {
		return switch (slot) {
			case 2 -> FAB2_ENABLED;
			case 3 -> FAB3_ENABLED;
			case 4 -> FAB4_ENABLED;
			case 5 -> FAB5_ENABLED;
			case 6 -> FAB6_ENABLED;
			case 7 -> FAB7_ENABLED;
			case 8 -> FAB8_ENABLED;
			case 9 -> FAB9_ENABLED;
			default -> FAB10_ENABLED;
		};
	}

	/** The "action" setting of floating button {@code slot} (2 to 10). */
	static Pref<IntSupplier> fabAction(int slot) {
		return switch (slot) {
			case 2 -> FAB2_ACTION;
			case 3 -> FAB3_ACTION;
			case 4 -> FAB4_ACTION;
			case 5 -> FAB5_ACTION;
			case 6 -> FAB6_ACTION;
			case 7 -> FAB7_ACTION;
			case 8 -> FAB8_ACTION;
			case 9 -> FAB9_ACTION;
			default -> FAB10_ACTION;
		};
	}

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

	/** "Hide bars while scrolling" for this screen: the car's own setting on the car's screen. */
	default Pref<BooleanSupplier> getScrollHideBarsPref(MainActivityDelegate a) {
		return (AUTO && a.isCarActivity()) ? SCROLL_HIDE_BARS_AA : SCROLL_HIDE_BARS;
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

	/**
	 * Whether tapping a track opens its menu rather than playing it (set separately for the car
	 * screen). Items can then also be dragged with a long press, outside selection mode.
	 */
	default boolean getTapOpensMenuPref(MainActivityDelegate a) {
		if (AUTO && a.isCarActivity()) return getIntPref(TAP_ACTION_AA) == TAP_OPENS_MENU;
		return getIntPref(TAP_ACTION) == TAP_OPENS_MENU;
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

	static boolean hasGridItemSizePref(List<Pref<?>> prefs) {
		return prefs.contains(GRID_ITEM_SIZE);
	}

	default float getGridItemSizePref() {
		return getFloatPref(GRID_ITEM_SIZE);
	}

	/** List view's row size (the thumbnail, and so the row's height) -- the text keeps its size. */
	default float getListItemSizePref() {
		return getFloatPref(LIST_ITEM_SIZE);
	}

	default void setGridItemSizePref(float value) {
		applyFloatPref(GRID_ITEM_SIZE, value);
	}

	/**
	 * The floating buttons' scale: the size setting (0.95 by default) on top of a base a little
	 * larger than Material's standard FAB, which read as small on a car screen.
	 */
	default float getFabSizePref(MainActivityDelegate a) {
		return getFloatPref(fab(a, FAB_SIZE)) * 1.1f;
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

	default int getClockPosPref(MainActivityDelegate a) {
		return getIntPref(info(a, CLOCK_POS));
	}

	default boolean getInfoOverlayShowClockPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_CLOCK));
	}

	default boolean getInfoOverlayShowBatteryPctPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_BATTERY_PCT));
	}

	default boolean getInfoOverlayShowBatteryTempPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_BATTERY_TEMP));
	}

	default boolean getInfoOverlayShowClockIconPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_CLOCK_ICON));
	}

	default boolean getInfoOverlayShowBatteryIconPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_BATTERY_ICON));
	}

	default boolean getInfoOverlayShowTempIconPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_TEMP_ICON));
	}

	default boolean getInfoOverlayOnlyWhenControlPanelVisiblePref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_ONLY_WHEN_CONTROL_PANEL_VISIBLE));
	}

	default boolean getInfoOverlayShowDistancePref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_DISTANCE));
	}

	default boolean getInfoOverlayShowDistanceIconPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_DISTANCE_ICON));
	}

	/** Whether the Data Usage addon is on: its overlay items only show while it is. */
	private static boolean dataUsageEnabled() {
		me.aap.fermata.addon.AddonManager m = me.aap.fermata.addon.AddonManager.get();
		return (m != null) && (m.getAddon(me.aap.fermata.addon.data.DataUsageAddon.class) != null);
	}

	default boolean getInfoOverlayShowDataUsagePref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_DATA_USAGE)) && dataUsageEnabled();
	}

	default boolean getInfoOverlayShowDataRemainingPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_DATA_REMAINING)) && dataUsageEnabled();
	}

	/** Whether the Power Usage addon is on: its overlay item only shows while it is. */
	private static boolean powerUsageEnabled() {
		return me.aap.fermata.addon.power.PowerUsageAddon.isEnabled();
	}

	default boolean getInfoOverlayShowPowerPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_POWER)) && powerUsageEnabled();
	}

	default boolean getInfoOverlayShowPowerIconPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_POWER_ICON));
	}

	default boolean getInfoOverlayShowDownloadedPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_DOWNLOADED));
	}

	default boolean getInfoOverlayShowDataSessionPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_DATA_SESSION)) && dataUsageEnabled();
	}

	default boolean getInfoOverlayDataSessionBarPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_DATA_SESSION_BAR));
	}

	default boolean getInfoOverlayDataSessionLeftPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_DATA_SESSION_LEFT));
	}

	default boolean getInfoOverlayShowDataIconPref(MainActivityDelegate a) {
		return getBooleanPref(info(a, INFO_OVERLAY_SHOW_DATA_ICON));
	}

	default String getInfoOverlayOrderPref(MainActivityDelegate a) {
		return getStringPref(info(a, INFO_OVERLAY_ORDER));
	}

	default float getInfoOverlaySizePref(MainActivityDelegate a) {
		return getFloatPref(info(a, INFO_OVERLAY_SIZE));
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
