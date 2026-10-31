# Settings redesign plan

Status: plan only, nothing here is built yet. Written from a read of `SettingsFragment` (about 2000 lines, one flat `PreferenceSet`
tree built in `onCreateView`), the Home / Power / Sleep / UI style / Captions / FAB / Nav tabs / Info overlay pages, and `SettingsNav`.

## What is wrong today

1. **Two systems side by side.** The root is the old `PreferenceSet` list (plain rows, nested sub-lists). Home, Power, Sleep, UI style,
   Captions, Lyrics, FAB, Nav tabs, Info overlay are modern code-built pages (`ReorderListFragment`, tiles, previews, sliders). The
   "one look" work (`SettingsStyle`) skins the old rows but they still read as a different app.
2. **Scattered categories.** The same subject lives in several places:
   - *Network and offline*: "Play downloaded when the connection stalls" and the new stall wait are under Playback; the Android Auto data
     session limit is on the Data usage tab; storage and cache is under Other.
   - *Looks*: theme, art accent, text/icon size, bar sizes and the bars' hiding are loose rows in Interface, UI style (glass) is a sub-page,
     Home has its own look, Captions/Lyrics have their own style pages.
   - *Sound and picture*: audio delay/language are inside Video, subtitles are a root category, subtitle generation is nested in it,
     "In video panel" (comments, effects, captions, search) is in Video, audio effects have no settings entry at all.
   - *Controls*: key bindings and the key tester are one category, voice control is inside Playback control, tap action is in Playback and
     again in Interface, steering-wheel/Car mode is in Key bindings.
   - *Other* holds everything unrelated: welcome wizard, updates, Spotify import, Share, Storage, Diagnostics, Backup.
3. **Interface is a 25+ row page.** Phone and Android Auto copies are the same rows twice, in a nested "Android Auto" sub-list.
4. **No preview for visual settings** except UI style and Home. Theme is a plain list of names.
5. **Hard to find a setting**: no search, no breadcrumb; the wide-window category list (`SettingsSplit`) only lists the root.

## Principles

- One framework for every page: a `SettingsPage` base (extracted from `ReorderListFragment`'s row helpers: `addSwitchRow`, `addChoiceRow`,
  `addValueSliderRow`, `addActionRow`, `section`, sticky preview). The old `PreferenceSet` is kept only as the data source for the
  few exotic rows (key-binding action pickers), wrapped in the same tile look.
- **Nested, three levels at most**: Category (tile on the root) -> Page (this is where most settings are) -> optional detail page
  (a preview-led editor like Theme or Floating buttons). Every page has: header with icon + one-line summary, an optional live preview
  card, sections with titles, and "Reset this page" last.
- **Phone / Android Auto pill** at the top of every page that has car twins (as Home does), instead of a separate nested list and copies.
- Preference keys do not change, so no migration and backups keep working. Strings move, not change (translations stay valid).
- Wide windows keep the category list on the left (`SettingsSplit`); it lists the new root and highlights the page's category.

## Proposed tree

| # | Category | Pages (detail pages in *italics*) | Taken from |
|---|----------|-----------------------------------|------------|
| 1 | **Home** | Layout, Header, Widgets, Background, Greeting, *Switch to music on Home* | already built (`HomeSettingsFragment`) |
| 2 | **Appearance** | *Theme* (card gallery, below), UI style (glass), Colour from album art, Text and icon size, Language | Interface rows, `GlassSettingsFragment` |
| 3 | **Layout and navigation** | Navigation bar (tabs, position, size), Tool bar and control panel (sizes, hide bars, hide while scrolling), *Floating buttons*, *All in One*, Info overlay, Lists (grid/list, track icons, Up next) | Interface, Fab/AllInOne/NavTabs/InfoOverlay pages |
| 4 | **Playback** | General (play next, default mode, resume), **Network and offline** (stall wait, play downloaded on stall, data session limit shortcut, Wi-Fi only downloads), Sleep timer and fades, Skip times (rw/ff), Engines | Playback, Playback control, Engine, Data usage |
| 5 | **Video and sound** | Video (quality, scaling, hardware decoding, dim screen), In video panels, Audio (delay, language, effects panel), Screen snap (folder, quality) | Video, Dim screen, panels |
| 6 | **Subtitles, captions and lyrics** | Subtitles, Auto subtitles (SubGen), Captions style, Lyrics style, Translation | Subtitles, Captions/Lyrics pages |
| 7 | **Controls** | Key bindings, Steering wheel / Car mode, Voice control, Tap and gestures, Key tester | Key bindings, Playback control, tap action |
| 8 | **Library and downloads** | Downloads (YouTube), Storage and cache, Spotify import, Share (Wi-Fi), Folders | Other, Storage |
| 9 | **Privacy** | Private mode, Diagnostics recording | Private mode, Diagnostics |
| 10 | **Add-ons** | one tile per add-on (Power, Sleep, Ambience, Data usage, Fuel, Translate ...) opening its own page in the same look | Add-ons, Power/Sleep pages |
| 11 | **Backup and about** | Backup and restore, Scheduled backups, Updates, Welcome guide, Share zrAuto, Diagnostic log, About | Other, Backup |

Search: a field at the top of the root (and `SettingsSplit`) that filters rows by title/subtitle across all pages and jumps to the page with the
row highlighted. Needs a registry: each page registers its rows (key, title, subtitle, page id) in `SettingsNav`.

## Theme page (card previews)

- A horizontal gallery of **theme cards**, each a small phone drawn in code from the theme's own colours (the way `GlassStylePreview` and
  `HomeLayoutPreview` draw): status bar, tool bar pill, a list row, a cover card, the nav pill and one accent button. Cards for: Classic, Dark,
  Light, Black, Purple, Star Wars black, Dynamic (Material You, only on Android 12+), plus "Follow the system" and "By time of day".
- Picking a card previews it live in the big sticky preview on top (cross-fade), a tick confirms and recreates the activity once.
- Below: accent follows album art (switch + strength), a separate pick for the car screen (pill), and contrast / text size sliders with the
  preview reacting. Each card is a tile in the settings palette so it matches Home settings.
- Theme resources already exist (`values/theme_*.xml`); the preview only reads their attributes, so a new theme appears automatically.

## Rollout (each step is shippable and CI-checkable)

1. Extract `SettingsPage` + shared row helpers; port `HomeSettingsFragment`, `PowerSettingsFragment`, `SleepSettingsFragment` onto it (no visible change).
2. New root with the 11 categories and the Phone/Android Auto pill; each category page initially hosts the existing `PreferenceSet` sub-trees
   re-parented (moves only, same prefs) in the tile look.
3. Convert the busiest pages to native pages: Playback (Network and offline first), Appearance (Theme gallery), Layout and navigation.
4. Search registry and deep links (also used by "Open settings" buttons in popups, e.g. the network placeholder).
5. Remove the old root builder once every row is on a page.

## Risks and open questions

- Cannot be compiled or run in the cloud session; the work is large and visual, so it should be done page by page with CI and device checks.
- Key-binding rows (one list per action) stay on `PreferenceSet`; they need a wrapper, not a rewrite.
- Android Auto: settings must stay usable with steering keys (focus rings, `CarNav` scopes); every new row type has to be focusable.
- Decision for you: should the car screen show only a reduced root (Home, Layout, Playback, Controls) as it does now?
