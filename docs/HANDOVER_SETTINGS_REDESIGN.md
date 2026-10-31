# Handover: consolidate and redesign Settings

Status: plan written (`docs/SETTINGS_REDESIGN_PLAN.md`); only a first slice of rollout step 2 is built: `PreferenceSet#group` moves root entries into a
category (ids kept), and `SettingsFragment#groupRoot` makes **Playback** (settings, control, engine) and **Video and subtitles** (video, subtitles, captions, lyrics)
from the old root entries. The car screen now gets the same root as the phone (the reduced car-only Interface page is gone); settings that only do something on Android Auto
carry a CAR SCREEN ONLY tag after their title (`Opts#carOnly`, set by `PreferenceSet#markCarOnly` / `markLastCarOnly`, drawn in `SettingsStyle#apply`): the
Interface > Android Auto page, the car audio delay and the car video status row. Voice control, backup, the welcome wizard and Share app stay phone-only (they
need system pickers or dialogs). Everything else below is still to do. Taken out of PR #68 to be done on its own branch.

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
