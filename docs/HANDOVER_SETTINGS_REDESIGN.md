# Handover: consolidate and redesign Settings

Status: plan written (`docs/SETTINGS_REDESIGN_PLAN.md`); only a first slice of rollout step 2 is built: `PreferenceSet#group` moves root entries into a
category (ids kept), and `SettingsFragment#groupRoot` makes **Playback** (settings, control, engine) and **Video and subtitles** (video, subtitles, captions, lyrics)
from the old root entries. The car screen now gets the same root as the phone (the reduced car-only Interface page is gone); settings that only do something on Android Auto
carry a CAR SCREEN ONLY tag after their title (`Opts#carOnly`, set by `PreferenceSet#markCarOnly` / `markLastCarOnly`, drawn in `SettingsStyle#apply`): the
Interface > Android Auto page, the car audio delay and the car video status row. Voice control, backup, the welcome wizard and Share app stay phone-only (they
need system pickers or dialogs). Also built (never compiled): `NetworkSettingsFragment` (Playback > Network and offline: offline mode, play downloaded on stall, stall wait, shortcut to
Data usage; replaces the two loose rows), `ThemeSettingsFragment` + `ThemePreviewView` (Interface > Theme: gallery of phones drawn from each theme's colours,
Phone / Android Auto pill, colour-from-album-art; replaces the theme list row), and a **Search settings** button at the root (`SettingsFragment#searchSettings`,
walks the PreferenceSet tree for title/subtitle matches and opens the page; no separate registry was needed). Then the regroup was finished: **Layout and navigation** is a native page (`LayoutSettingsFragment`, Phone / Android Auto pill, rows for bars, sizes,
nav bar, tabs, floating buttons, All in One, Info overlay, lists and Up next/search, with a car-only section); **Appearance** (theme, UI style, album-art colour,
language) is a small page; the root is now: Search, Home, Appearance, Layout and navigation, Playback, Video settings, Subtitles/captions/lyrics, Controls,
Library and downloads, Privacy, Add-ons, Backup and about. `PreferenceSet#group` takes entries from anywhere below the set, `SettingsFragment#groupRoot` finds them
by title and `orderRoot` sorts the root. The old `addInterface`/`addAAInterface`/`addFabSettings`/`addAllInOne`/`addNavTabsPrefs`/`addInfoOverlayPrefs`/`addUpNextPrefs`
builders and the Interface/Android Auto lists are gone (`SHOW_INTERFACE` / `SHOW_AA_INTERFACE` now open the layout page on the right side). The `SettingsPage`
extraction was not done: `ReorderListFragment` already is that base, a rename would be churn. The remaining `PreferenceSet` pages (Playback, Video, Subtitles, Key
bindings, Add-ons, Other) keep the tile look through `SettingsStyle` and the CAR SCREEN ONLY tag for their few car twins. Taken out of PR #68 to be done on its own branch.

## What was asked

Consolidate and review every settings item so it is easier to reach and looks modern like the Home settings page:

- the current categories feel scattered across sections; give a plan for the new layout,
- prefer nested categories for an easier breakdown (for example Interface: the theme gets its own page with card previews, and more),
- every settings page must be consistent and match in look.

## What is already decided (see the plan file for the detail)

- One page framework for everything: extract a `SettingsPage` base from `ReorderListFragment`'s row helpers (`addSwitchRow`, `addChoiceRow`,
  `addValueSliderRow`, `addActionRow`, `section`, sticky preview). Keep `PreferenceSet` only as a data source for exotic rows (key bindings).
- Three levels at most: category tile on the root, a page, an optional detail page (Theme, Floating buttons ...).
- A Phone / Android Auto pill on every page that has car twins, as Home settings has.
- Eleven categories: Home, Appearance, Layout and navigation, Playback (with Network and offline), Video and sound, Subtitles / captions / lyrics, Controls,
  Library and downloads, Privacy, Add-ons, Backup and about.
- Theme page: a gallery of phone-preview cards drawn in code (like `GlassStylePreview` / `HomeLayoutPreview`), live sticky preview on top.
- Search across settings via a registry in `SettingsNav`.
- Preference keys do not change, so backups and restores keep working.

## Settings added in PR #68 that must find a home in the new tree

- Playback > "Wait for a stalled connection" (`OfflineFallback.STALL_SECONDS`) and "Play downloaded when the connection stalls" (`OfflineFallback.ON_STALL`)
  go under Playback > Network and offline, with a shortcut to the Offline mode action (`Action.OFFLINE_TOGGLE`, `NetworkState`).
- Home > "Switch to music when on Home" (`HOME_AUTO_MUSIC`).
- Video panels: only the width sliders remain (`SIDE_PANEL_EFFECTS_PERCENT`, `SIDE_PANEL_CAPTIONS_PERCENT`, `COMMENTS_SPLIT_PERCENT`); the old "effects as a
  side panel" switch is gone.

## Where the code is

- `ui/fragment/SettingsFragment` (about 2000 lines; the root `PreferenceSet` is built in `onCreateView`).
- Modern pages already in the target look: `HomeSettingsFragment`, `PowerSettingsFragment`, `SleepSettingsFragment`, `GlassSettingsFragment`, `FabSettingsFragment`,
  `NavTabsFragment`, `InfoOverlayFragment`, `TextStyleFragment`, `AllInOneFragment`; base class `ReorderListFragment`.
- Look: `ui/view/SettingsStyle` (`PreferenceView#setRowStyler`), `SettingsStyle.Colors`.
- Wide-window category list: `ui/view/SettingsSplit`, `SettingsNav` (fed by `SettingsFragment#publishNav`). A new page only needs `isSettingsPage()`.

## Open question for the owner

Should the car screen keep a reduced settings root (Home, Layout, Playback, Controls) as it has now?

## Rollout (each step shippable)

1. Extract `SettingsPage` and port Home / Power / Sleep onto it (no visible change).
2. New root with the categories and the Phone / Android Auto pill; each category first hosts the old `PreferenceSet` sub-trees, moved not changed.
3. Native pages for the busiest areas: Playback (Network and offline), Appearance (Theme gallery), Layout and navigation.
4. Search registry and deep links (the network placeholder's "Network settings" and similar buttons can use them).
5. Remove the old root builder once every row is on a page.

Never built or run from the cloud session: leave CI as the first compile and ask for device checks page by page.


## Round after the first device test

- Docked video panel: the title/Info overlay were clipped (`clipChildren` on `VideoView` / `VideoControlsOverlay`; the screen's top is outside the scaled view's bounds), the control
  panel inset now applies whatever its look, and `ui/view/FabDock` moves the floating buttons to the video's width and puts what does not fit behind a "..." button (menu).
- Settings: Appearance no longer repeats the album-art switch; Network and offline is a root category; Search is a page (`SearchSettingsFragment`) with a live text box and
  highlighted matches, indexing the old tree plus `SettingsNative` (a hand list of the code-built pages' rows: add a line there when a row is added); Back from Nav tabs / FAB /
  All in One / Info overlay goes straight to the Layout page (the detour through Settings' pending input left the bars half set up); "Show track icons" reads "Show video thumbnail";
  every list page shows its sub-pages and buttons first (`PreferenceSet#display`) and its on/off settings as switches (`BooleanOpts.asSwitch` by default).
- Not rebuilt as code-built pages: Playback, Video, Subtitles, Key bindings, Add-ons, Other, Backup, Library, Privacy still render through `PreferenceView` (restyled by
  `SettingsStyle`): add-ons supply their own `PreferenceSet`s and key bindings are one list per action, which a page per screen cannot hold.

## Third round (device test)

- Search is back as a page (`SearchSettingsFragment`, live text box, highlighted matches) opened from a **Search chip in the title bar of every settings page** (`SettingsSearchChip`, the mediator of `ReorderListFragment` and `SettingsFragment`); the redundant "Search settings" entry in the root list stays gone. `PreferenceSet#display` still sorts pages (sub-pages and buttons first) and now also honours
  `markLastPinned` (an add-on's Enable switch is always first). Add-on rows have subtitles (`*_sub` strings next to their titles).
- Privacy is a code-built page (`PrivacySettingsFragment`): the two clear actions come first and ask before clearing (they cannot be undone); the Private Mode options stay in
  view, dimmed, while it is off. `SHOW_PRIVATE_MODE_SETTINGS` opens it.
- "Library and downloads" is gone: Spotify import and Share moved under Add-ons (`PreferenceSet#adopt`), Storage under Backup and about; Network and offline sits after Controls.
- Docked video: the video view's parent no longer clips children while split (a view's drawing is clipped to its bounds by its parent: the title and the Info overlay lie outside
  the shrunk view's bounds); the control panel's inset also sets `goneEndMargin` (with the nav bar on a side, the nav bar is gone in fullscreen and that margin is the one used);
  `FabDock` puts itself right again on every layout while docked and names the buttons in its "..." menu by their action.
- `ArtAccent` recolours a MaterialButton's background tint (the dialogs' OK button).

## Performance notes (what was made cheap on purpose)

- `FabDock` reads the buttons and their widths once when the dock opens or the enabled set changes (`refresh`); each animation frame only compares the room with that cache
  (`relayout`), and a button's visibility change is answered through `FloatingButton#setVisibilityListener` (set only while docked), not a layout listener.
- The settings search builds its index once per page visit (`SettingsFragment#buildSearchIndex`, lower-cased text) and keystrokes search that list after a 90 ms pause.
- `QualityIcon#changedSoon` is one chain of at most four asks (a newer chain or the first different answer ends it).
- Selected chips and cards go through `ReorderListFragment#fillColor`, which has the album art colouring walk the page once per change (coalesced) instead of pre-mapping colours, so they glide with the cover colour like every other element.
