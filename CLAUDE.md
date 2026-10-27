# CLAUDE.md

Guidance for Claude Code sessions (including cloud-hosted sessions at claude.ai) working in this repository.

## What this project is

zrAuto is an Android media player for Android Auto (and Android TV), forked from Andrey
Pavlenko's **Fermata Auto**. It's a Gradle multi-module Android project written mostly in
Java, with the app's core living in the `fermata` module and optional features split into
`modules/*`.

## Code and layout structure

```
zrAuto/
├── fermata/                  # Main app module
│   ├── build.gradle
│   ├── lib/auto/             # Vendored .aar/.jar deps (Android Auto, Xposed API)
│   └── src/
│       ├── main/java/me/aap/fermata/
│       │   ├── action/       # Key/input action handling (Action, Key, KeyEventHandler)
│       │   ├── addon/        # Pluggable add-ons (each modules/* addon hooks in here)
│       │   ├── media/        # Media library/session/metadata models
│       │   ├── provider/     # Content/media providers
│       │   ├── service/      # Background services (playback service, etc.)
│       │   ├── ui/
│       │   │   ├── activity/ # Activities
│       │   │   ├── fragment/ # Fragments (screens: browser, favorites, settings, ...)
│       │   │   └── view/     # Custom views/widgets
│       │   ├── util/         # Helpers
│       │   └── vfs/          # Virtual file system layer (local, network providers)
│       ├── main/res/         # Layouts, drawables, values (many locale value-* dirs)
│       ├── auto/             # Android Auto–specific source set (manifest, java, res, assets)
│       └── test/             # Unit tests
├── modules/                  # Optional feature add-ons, each its own Gradle module
│   ├── cast/                 # Chromecast support
│   ├── chat/                 # Chat/LLM-assistant UI (ChatListView, etc.)
│   ├── exoplayer/            # ExoPlayer playback engine
│   ├── felex/                # FelexAddon (flashcard/vocab-style feature)
│   ├── gdrive/                # Google Drive VFS provider (needs google-services.json)
│   ├── mlkit/                # ML Kit translation addon
│   ├── opusmt/                # OpusMT on-device translation
│   ├── poi/                  # POI/native-loader addon
│   ├── sftp/                 # SFTP VFS provider
│   ├── smb/                  # SMB/CIFS VFS provider
│   ├── tv/                   # Android TV specific UI (floating button mediator, etc.)
│   ├── vlc/                  # VLC playback engine
│   ├── web/                  # In-app browser addon (WebBrowserFragment)
│   └── whisper/              # Whisper speech-to-text (native/CMake build, slow in CI)
├── depends/utils/            # Git submodule: shared `me.aap` utils library (own Gradle module)
├── docker/                   # Dockerfile/entrypoint for containerized builds
├── res/                      # Root-level release resources (fermata.jks, build_jks.gradle)
├── build.sh                  # Local build script (produces APK/AAB into ./dist)
├── settings.gradle           # Wires up :fermata, :utils, and every modules/* as Gradle modules
├── gradle/libs.versions.toml # Version catalog (app version, AGP, NDK, AndroidX libs, etc.)
└── .github/workflows/        # build-apk.yml, build-abi.yml (reusable), release.yml
```

Conventions worth knowing:
- Package root for first-party code is `me.aap.fermata` (app) and `me.aap.utils` (submodule).
- Each `modules/*` add-on generally mirrors the app's package layout (`me.aap.fermata.addon.<name>`
  or `me.aap.fermata.<layer>.<name>`) and is registered into the addon system under
  `fermata/.../addon/`.
- `settings.gradle` auto-discovers every directory under `modules/` as a Gradle module, and
  conditionally excludes `:gdrive` when Google Services aren't configured (`NO_GS=true`).
- `depends/utils` is a regular, fully-tracked directory in this repo (not a real git submodule
  despite the folder name/historical upstream layout — no `.gitmodules`, normal `100644` file
  mode) — it's fine to edit files under it directly and commit them like any other source file.
- Locale/translation resources live under `fermata/src/main/res/values-<locale>/` — don't hand-edit
  translated strings unless specifically asked; app strings normally originate in `values/strings.xml`.
- XML comments (`<!-- ... -->`) in any Android resource file (layouts, drawables, etc.) can't contain
  `--` *anywhere in the body*, not just at the delimiters — Android's resource compiler (AAPT2, via a
  strict `javax.xml.stream` parser) rejects it with `The string "--" is not permitted within comments.`
  and fails the whole `mergeResources`/`parseLocalResources` task. This is easy to trip on because it's
  valid in Java/JS comments and this codebase's own Javadoc-style comments use `--` constantly as a
  dash separator (e.g. "not a real git submodule -- no `.gitmodules`") — that habit doesn't carry over
  into XML. Use a plain comma, em dash character (—), or semicolon instead when writing comments inside
  `.xml` files. This has bitten more than one Claude Code session already; if a build fails with that
  exact "not permitted within comments" error, check the newest/edited XML file's comments first.
- This project builds with non-transitive R classes (the AGP default here — `gradle.properties` doesn't
  override `android.nonTransitiveRClass`), so a Java file can only use an *unqualified* `R.attr.foo` /
  `R.drawable.foo` for resources this app itself declares. A theme color role (`colorPrimary`,
  `colorControlActivated`, `colorError`, `colorOnSecondary`, `colorSurface`, `colorOutline`, ...) is
  declared by some androidx dependency (`androidx.appcompat.R.attr.*` or
  `com.google.android.material.R.attr.*`), not this app, and which of those two artifacts declares any
  given one isn't guessable from the attr's *name* — `colorPrimary` is `androidx.appcompat.R.attr`,
  `colorOnSecondary`/`colorOutline`/`colorSurface` are `com.google.android.material.R.attr`, but
  `colorControlActivated`/`colorError` are `androidx.appcompat.R.attr` too even though they read like
  Material3 roles. Getting this wrong compiles fine in the IDE (which usually resolves R more leniently)
  but fails `compileXxxJavaWithJavac` in CI with `cannot find symbol ... location: class attr` — this
  can't be verified without a real build in this cloud session (see below), so when resolving a theme
  attr from Java (e.g. via `MaterialColors.getColor(view, attr)`), prefer letting **XML** resolve it
  instead (a drawable/style referencing `?attr/colorError` — AAPT links that against the full merged
  resource table regardless of R-class transitivity) over guessing the attr's declaring artifact in
  Java; if Java-side resolution is unavoidable, grep this codebase for an existing
  `androidx.appcompat.R.attr.*` / `com.google.android.material.R.attr.*` reference to the exact same
  attr name first rather than assuming which one it lives in.
- **Confirmations go in the top popup, not a toast.** A short "it's done" message (added to favorites,
  download started, quality set, sleep timer set) uses `ui/view/TopToast.show(icon, text)`, the card
  that drops in at the top of the app (it falls back to a plain toast only when no UI is up). Don't use
  `UiUtils.showToast` / `Toast.makeText` for these in new code; a toast looks out of place, and on the
  car screen it's easy to miss. Older code still has some toasts (about 40 calls), so convert them when
  you're already working in that spot.
- **Asking before something destructive** (reset, clear, delete): `UiUtils.showQuestion(ctx, title,
  message, icon).onSuccess(v -> ...)`, the app's own dialog view (works on the car screen, where
  Android Auto allows no real dialog windows).

## Adding a new nav-bar tab (addon)

The easiest way to add a whole new top-level tab (like Fuel Log) is to register it as a
`FermataFragmentAddon`, built directly into the `fermata` module (like `TranslateAddon`/
`SubGenAddon` in `fermata/.../addon/`) rather than as its own Gradle module under `modules/`,
unless it needs a heavy/optional dependency that would benefit from on-demand delivery.

1. Add an entry to the root `build.gradle`'s `addons = [...]` list: `name` (also the module-name
   key), `icon` (a drawable name under `fermata/src/main/res/drawable/`), `class` (fully-qualified
   addon class), optionally `order` (lower sorts earlier in the nav bar — the built-in `menu` item
   is always added last regardless, so any real order value already sorts above it) and
   `hasSettings: true` if you want it to appear.
2. Add `R.string.addon_name_<name>` to `fermata/src/main/res/values/strings.xml` and an
   `<item name="<name>_addon" type="id" />` to `fermata/src/main/res/values/ids.xml` — the class
   is instantiated by reflection off `BuildConfig.ADDONS` (generated from that `build.gradle` list),
   so it needs `@Keep` and a public no-arg constructor.
3. Implement `FermataFragmentAddon`: `getAddonId()` returns the id from step 2, `createFragment()`
   returns your fragment. `hasSettings: true` automatically gets it a Settings > Addons > `<name>` >
   Enable toggle (see `SettingsFragment.AddonPrefsBuilder`) with no extra code needed — override
   `stop()` only if the addon needs to release something (e.g. a location listener) when disabled.
4. Your fragment should extend `MainActivityFragment` (or `MediaLibFragment` if it's browsing the
   media library), override `getFragmentId()` (same id) and `getTitle()`.

**The one step that's bitten this exact scenario more than once (Audio Effects, then Fuel Log):**
this app deliberately draws `tool_bar`/`control_panel`/a bottom `nav_bar` as translucent overlays
*on top of* fragment content (so a list can scroll all the way underneath them edge-to-edge) —
there is no layout-level fix that makes a new fragment's content start "below" the toolbar, because
nothing is structurally wrong; every scrollable screen is expected to explicitly reserve the right
padding itself. If your fragment's root view is scrollable (a `RecyclerView` or `ScrollView`), call
`getActivityDelegate().insetScrollableContent(thatView)` (typically right after inflating/creating
it) — see `MainActivityDelegate.insetScrollableContent` and existing callers
(`MediaItemListView`, `AudioEffectsView`, `SettingsFragment`) for the exact pattern. Skipping this
renders the content flush under the toolbar title instead of below it — a purely visual bug that's
easy to mistake for a ConstraintLayout/measurement issue (it isn't) and easy to burn a whole session
chasing the wrong theory for, as has happened before. If you add a dialog via
`me.aap.utils.ui.UiUtils#queryPrefs` instead, it's already inset-safe (wired through
`ActivityDelegate.insetScrollableContent`, a no-op default in `depends/utils` that
`MainActivityDelegate` overrides) — no extra step needed there.

**Bars collapse on scroll** (Settings > Interface > "Hide bars while scrolling", `SCROLL_HIDE_BARS`,
and `SCROLL_HIDE_BARS_AA` for the car screen, both on by default; off leaves the bars and web page
layout exactly as before). `ScrollBarsController` (owned by `MainActivityDelegate`, fed by
`BodyLayout#dispatchTouchEvent`) hides the bars one step at a time as tab content scrolls down:
`control_panel` (tucked behind a bottom nav bar, clipped at its top edge), then a bottom `nav_bar`,
then `tool_bar`; scrolling up brings them back in reverse (all shown at the top), and the floating
buttons stay just above whatever still shows. Something else resetting a FAB's translation must
reset it to `scrollBars.getFabDy(fab)`, not 0 (see `resetFabPosition`). The bars fade as they slide
through their *transition* alpha (Android 10+), which nothing else animates on them; the top and
bottom edge fades (`BodyLayout#computeTopFade`, `FloatingBarsView`) read only the plain alpha, so they
stay on screen (narrowed to a strip) while the bars are scrolled away. List padding never follows
the bars (`computeContentInsets` uses their resting place, minus `getPanelDy`/`getNavDy`), like
Material's hide-on-scroll bars: changing it relays the list and shifts its rows under the finger
(tried per frame, at the end of each step, and once scrolling stopped; all felt bad). Instead,
reaching the end of a list brings the bars back over the room left for them.
When either edge fade goes off, the reason is written to the Diagnostic log ("top fade off: ..."). It only translates the bars, never resizes anything (the YouTube
WebView restarts its player on a resize): a web page is laid out as tall as its parent with a
negative bottom margin matching its top one (`insetWebViewTop`) and slides up into the tool bar's
room (`slideWebViews`). It works for any `RecyclerView`,
`ScrollView`, `ListView` or `WebView` under the finger with no per-tab code; a tab laid out around
the bars instead (Music, Car mode) opts out with `MainActivityFragment#collapsesBarsOnScroll`. Don't
animate the bars' translation elsewhere without calling `scrollBars.reset(false)` first, the
way `setBarsHidden`/`setVideoMode` do.

## All in One card and car mode on web pages

- **All in One** (`ui/view/AllInOneMenu`, settings page `ui/fragment/AllInOneFragment`, Settings >
  Interface > All in One, and Interface > Android Auto > All in One for the car screen's own set via
  the `*_AA` twins in `MainActivityPrefs.fabAA`): one card of tiles for any `Action`, opened by the
  `ALL_IN_ONE` action (a key binding or a floating button), hosted on `R.id.main_activity` so it shows
  over tabs and fullscreen video alike. Items are stored by `Action` *name* (`AIO_ITEMS`), not ordinal.
  The Music tab's More is the same class (`showMusic`) with its own four tiles unless `AIO_MUSIC_MORE`
  is on. Its timer page is the only sleep timer UI: the fullscreen control panel's Timer and its
  countdown pill open it too (`showTimer`); there's no Hours/Minutes overlay menu any more. The
  settings page is the card itself, edited in place: a grid of the card's own tiles
  (`AllInOneMenu.tileView`, so the two can't drift apart) on the card's fill (drawn by an item
  decoration), hold to drag, tap to replace, a ✕ badge to remove, and an "Add action" cell last.
  Its drag fixes (auto-scroll only in the outer half of the list's padding and only along the drag,
  `onMoved` correcting for the top padding) are what made dragging work at all; keep them. The
  card has three pages (tiles, sleep timer, Quality); the `QUALITY` action opens it on the Quality
  page (`showQuality`), whose squares come from `MediaEngine#getVideoQualityOptions` (only the
  YouTube engine implements it, via the player API's levels, falling back to the page's own menu). The
  Quality tile and a Quality floating button show the quality in use as their icon (`QualityIcon`,
  text such as "720p" or "Auto", refreshed from the player at most every 2 s), or the plain icon
  while it's unknown.
- **Car mode wrap-around**: `CarNav.wrapAround(root)` makes Next on a scope's last item go to its
  first; only the All in One card uses it.
- **Car mode on web pages** (`CarNav.findWebPage`, `action/WebNav`): on the YouTube or browser tab
  with no panel open and not fullscreen, previous/next move an outline over the page's links and
  buttons (a script in the page, run on every press; on YouTube the first press picks the first
  suggested video), long next clicks, long previous is Back. No double-press actions on purpose:
  drivers press repeatedly to move along. An open YouTube search / Up next panel is a normal CarNav scope
  and is found first, so the page logic never sees those keys.

## Reorderable settings pages, Alternative video picker, menu fade

- **Navigation tabs and Info overlay settings** are pages of draggable rows (`ui/fragment/ReorderListFragment`:
  handle, icon, name + state, switch, "more" menu; subclasses `NavTabsFragment`, `InfoOverlayFragment`), not
  PreferenceSet lists. The base class carries the All in One drag fixes (auto-scroll only along the drag and
  only in the outer half of the list padding; `onMoved` correcting for the top padding): keep them, dragging
  otherwise scrolls the page. The overlay's item order is `INFO_OVERLAY_ORDER` (keys in
  `InfoOverlayView.ITEM_KEYS`, applied by `InfoOverlayView#setOrder`).
- **Search alternative videos** is `ui/view/YoutubeAlternativesPicker`, a modal card like `PlaylistPicker`
  (the old fragment is gone). The current video is always row 0. From an item's menu a tap replaces, the play
  button previews; from the `YOUTUBE_ALTERNATIVES` action (FAB / All in One / key) a tap plays and the check
  button sets. Car mode never replaces (tap = play; long next taps, long previous goes back to now playing).
- **Floating button settings** are `FabSettingsFragment` (rows for FAB2 to FAB6; dragging swaps each slot's
  shown/action prefs; the pill, draggable and size options sit above, via `ReorderListFragment#addSwitchRow` /
  `addSliderRow`). Sliders there use the control panel's own `appSeekBarStyle` (`newSeekBar`); switches built in
  code use `SwitchStyle` (opaque pill + disc, accent when on, dimmed when off), XML ones the `switch_*_tint`
  colors and `AppTheme.MaterialSwitch`.
- **Alternative video, from an item's menu**: a preview (▶, or a tap where nothing can be replaced) morphs the
  card into one line of controls at the top (`morphTo`: previous / next / Use / Search more / close) and plays
  it; Search more morphs back. From the action, a tap plays and closes. Tapping the current video when it is
  downloaded plays it from YouTube (`YtOffline.forceStream`, cleared by a tap in Downloads).
- **Menus and dialogs fade out** because `OverlayMenuView#hide` keeps the content on screen until the fade
  ends (`fadingOut`); it used to remove the content first, which made every close look like a hard cut and
  killed the dialog closing animation in `DialogStyle`.
- **Quality icon for local files**: `QualityIcon#localLabel` (the download's chosen height, else the picture
  size via `labelForSize`) when the engine has no quality options.

## Backup and restore, scheduled backups

Settings > Backup and restore (above Other) holds the backup folder, Back up now, Restore from backup
(system file picker, so Google Drive works with no Drive API, OAuth or extra dependency) and the automatic
backup switch, interval, hour and count. A PreferenceSet row's Opts is built again on every bind (the
supplier runs each time), so a dynamic subtitle must be set inside the row's builder, never on a captured
Opts; `SettingsFragment#refreshBackupPage` just redraws the page. A missed alarm is caught up: the
planned time is kept in `AUTO_BACKUP_DUE` and `BackupManager.init` backs up at app start when it has
passed; everything is traced in the Diagnostic log under BACKUP. The zip has every shared-prefs file except
`image-cache` and `spotify_auth` (`BackupManager#includeFile`; no login tokens in a cloud zip). `backup/BackupManager` writes the zip and keeps ONE inexact alarm set for the chosen interval and hour,
fired into `backup/BackupReceiver` (which backs up, then sets the next alarm; a boot receiver re-sets it,
and `FermataApplication` does at start and on any change of the `AUTO_BACKUP*` prefs). The destination is a
folder picked with `ACTION_OPEN_DOCUMENT_TREE` (persistable permission), else the app's own
`getExternalFilesDir/backups`; `zrAuto_auto_*.zip` beyond the "keep" count are pruned. Google Drive's
provider doesn't offer a folder to pick, so Drive is manual only.

## Spotify Liked Songs, duplicate popup, Share (Wi-Fi)

- **Liked Songs** import/sync only reads the latest N songs (`SpotifyPrefs.LIKES_LIMIT`, default 50, Settings >
  Import from Spotify). `Playlist.limit` overrides it per import (`LIMIT_DEFAULT` follows Settings, 0 = all); the
  picker/"Liked Songs" chip counts the account's likes first (`SpotifyApi.getLikedTotal`) and asks "Latest N or
  All?" when there are more. They land in the local playlist named `SpotifyApi.LIKED_PLAYLIST_NAME` ("Liked from
  Spotify"): created as the first playlist (the user may reorder), can't be renamed (`DefaultPlaylist#rename`, menu
  hides Rename), and a sync of a playlist with that name reads Liked Songs (`SpotifyPlaylistSync`). A sync that
  adds songs shows them in the progress card (`ModalProgressPopup#showResult(msg, ok, songs)`).
- The Spotify import header uses chips (`ChipFlowLayout#chip`, Music-tab palette, the accent-filled one is the main
  action); `bindChips` rebuilds the row only when it would look different, since the header is rebound on every
  matched track and a rebuild under a finger drops the tap.
- **Adding a duplicate to a playlist**: `MainActivityDelegate#addCheckingDuplicates` compares `getOrigId()`s and asks
  with `TopConfirm` (Yes adds everything via `Playlist#addItems(items, true)`, No adds only the new ones). Only the
  plain "Add to playlist" path asks; Spotify/Share imports still skip duplicates silently.
- **Share** (`share/` package, `ui/view/ShareSheet`): phones on the same Wi-Fi find each other with NSD
  (`ShareDiscovery`, `_zrauto-share._tcp`), announced while the app's activity exists and Settings > Share allows
  it. `ShareManager` serves/starts the TCP transfers (header JSON, receiver accepts via `TopConfirm`, raw bytes,
  final ack); `ShareService` is the dataSync foreground service with the progress notification and Cancel;
  `ShareImporter` stores what arrives: downloaded YouTube files via `YtDownloads#importFile`, other files in
  `<external files>/Shared/<name>` (added to Folders), and a playlist with the shared name (unless a folder was sent).
  "Share as link" hands the youtu.be links to Android's share sheet. The sockets must never be used on the main
  thread. None of this has been run on a device yet, check the Diagnostic log (SHARE) and Log output first.

- **Refresh thumbnail** (`ytdl/YtThumbnails`, one item, a selection, a whole list or a playlist's own menu): drops every
  cached thumbnail size and then looks up channel/duration (`YoutubeSearch`, by video id, then by title) for videos
  whose cached `VideoInfo` lacks them. `BitmapCache` only remembers a failed image for a minute (`markInvalid`): remembering
  it for good left half of an imported playlist without pictures after one burst of timeouts, and a failed
  maxresdefault now falls back to hqdefault.
- `SelectionPanel` lives in `R.id.main_activity` (below the 10dp menus), not the window's content frame, so the nav/FAB
  menus open in front of it. Share notifications are skipped while the app is in front (`ShareManager#isUiVisible`);
  an incoming transfer shows `ShareReceiveCard` (progress + Cancel) at the top.

## YouTube comments, dynamic floating buttons

- **YouTube comments** (`ui/view/YoutubeCommentsView`, data in `ytcomments/YtComments`, action `YOUTUBE_COMMENTS`, so All
  in One, a floating button or a key): a modal card like `PlaylistPicker`. `YtComments` talks to InnerTube (`next` for the
  comments section token, then continuation tokens for pages, sorting and replies; `comment/create_comment` and
  `create_comment_reply` to post). It finds what it needs by key anywhere in the response (`findString`/`findObject`),
  since the layout of those responses changes (the newer `commentViewModel` points by key into
  `frameworkUpdates.entityBatchUpdate.mutations`, the older `commentRenderer` holds it all). Writing needs the browser's
  cookies (`CookieManager`, so Private Mode and signed out read only) and YouTube only includes `createCommentParams` /
  `createReplyParams` when signed in: the box and Reply buttons show only when those are there, never in car mode. **None of
  this has run against live YouTube from the cloud session (no route to it); check the Diagnostic log / Log output first.**
- **Floating buttons are slots FAB2 to FAB10**; `FAB_COUNT` (and `FAB_COUNT_AA`) is how many are in use (default 5, the old
  five). FAB settings adds the next slot / removes one (later slots shift up), capped by how many fit the screen width at the
  chosen size. Slots 7 to 10 have their own view classes and mediators (`Septenary` to `Denary`, in the three `main_activity*`
  layouts); `MainActivityDelegate#getExtraFloatingButtons` lists all nine. Use `MainActivityPrefs.fabEnabled/fabAction(slot)`.
- **All in One card**: a drag down only closes it when the list was at its top when the touch began; scrolling the list
  back up to the top never closes it in the same gesture (`DraggableCard#scrolled`).
- **Refresh thumbnail** drops the item's cached *metadata* too (`PlayableItem#refreshMetadata`), not only its description;
  without that the artist/duration it found was cached but never shown. It falls back to YouTube's oEmbed
  (`YoutubeSearch.lookUpById`) for the channel when searching misses; adding a never-played video to Favorites looks the
  channel up in the background (`YoutubeAddon#recordAddedVideo`).

## Lyrics, Captions, comments beside the video

- **YouTube captions** (`ytcaptions/YtCaptions`): the InnerTube `player` endpoint, asked as several of YouTube's
  apps in turn (`CLIENTS`; the first that gives readable `json3` captions wins), then the track's `baseUrl` with
  `fmt=json3`. Manual captions in the preferred language, then English, then any; auto-generated ones only when
  `CAPTIONS_AUTO` allows. **None of it has run against live YouTube from the cloud session**; the Diagnostic log
  (CAPTIONS) says which app answered what. `CaptionsHub` is the one place that follows the playing video's id
  (`Action.getFavoritableItem` + `YtDownloads.videoIdOf`) and position (`MediaEngine#getPosition`, 4x/s); it only
  runs while something has `acquire`d it, and the views do that only while they are on screen
  (`onVisibilityAggregated`), so lyrics/captions off cost nothing. A new song's captions are fetched 1 s in, and the next
  song's 8 s into the current one (`prefetchNext`, Music queue only, from `MusicQueue#getPlayOrder`; never an item's `getNextPlayable`, which walks its folder/favorites and steps a shared shuffle), so lyrics show at once on Next.
- **Lyrics**: `ui/view/LyricsView` draws the lines in one pass (a fractional scroll position glides to the playing
  line; alpha by distance: the line before dim, gone after the third after). Over the Music tab's cover it sits in
  the art card under the loading dim (`MusicPlayerFragment#addLyricsOverlay`, shown by `LYRICS_ON`, the Lyrics chip
  = fifth optional chip of `updateEffectsChip`) and takes no touches: a tap on the cover opens `LyricsCard` (modal,
  in the look of the timer/playlist picker; tap a line to seek). `Action.LYRICS`: Music tab = show/hide over the
  cover, elsewhere = the card.
- **One action for lyrics and captions**: `Action.LYRICS` ("Lyrics / Captions") is the only one (`CAPTIONS` is gone);
  `LyricsCard#action` decides: fullscreen video = captions on/off, Music tab = lyrics over the cover on/off, anywhere
  else = the card. `ActionFabMediator.labelFor/iconFor` and the All in One tile follow `isVideoMode()`. A vertical drag
  on `LyricsView` (or on the Music tab's cover, forwarded by `enableArtSwipe` via `beginBrowse/browseBy/endBrowse`)
  scrubs; release seeks (`CaptionsHub#seekTo`, with a guard against the player still reporting the old position).
  `LyricsView` listens to the `LYRICS_*` prefs while on screen, so the cover follows Settings. Settings > Captions and
  > Lyrics are `TextStyleFragment` pages (live preview from the real views: `CaptionsOverlayView#preview`,
  `LyricsView#setPreview`). `Action.SETTINGS` is also an action; the FAB long-press menu ends with it as its own item.
- **Leaving fullscreen / PiP closes the docked comments at once** (`YoutubeCommentsView#onVideoLeft`, called from
  `setVideoMode(false)`; `onPictureInPicture` from `onPictureInPictureChanged`): no slide, a short fade with the video.
- **Captions over fullscreen video**: every `VideoView` carries a `CaptionsOverlayView` under its controls
  (`CAPTIONS_ON`, `Action.CAPTIONS`); the look is `CaptionStyle` (the `CAPTION_*` prefs, Settings > Captions). The
  YouTube page's own captions are left alone by `hideFullscreenControlsJs`, so with the page's CC on they double up.
- **Comments beside the video**: `YoutubeCommentsView` docks (`shouldDock`: fullscreen video, `COMMENTS_SPLIT`, car
  screen or a landscape tablet with min side >= 480dp) as a column on the right with no scrim; the video is shrunk
  with `VideoView#setSplitShare`, a *transform* (pivot top-left/centre, black backdrop view under it), never a
  resize, because resizing restarts the YouTube player. The strip on the column's left edge drags the width
  (`COMMENTS_SPLIT_PERCENT`). While docked, `watchDock` follows the video: the next video's comments load in the
  same column, leaving fullscreen closes it (two misses, a page can drop out of fullscreen for a moment).
  `CommentsSkeletonView` is the shimmering placeholder while a batch loads. The column's elevation (7dp) is below
  the control panel/FABs (10dp) on purpose.

## Lyrics page, Power usage and power saver, welcome wizard

- **Lyrics page** (Music tab): a tap on the lyrics over the cover (or the Lyrics chip held) opens `lyricsPage` in
  `MusicPlayerFragment`, an interactive `LyricsView` on a glass panel that fills `music_art_holder` (`setLyricsExpanded`).
  It is a view of the tab, not a card over the app: Back (`onBackPressed`), its close button, the chip turning lyrics off
  or a track without lyrics close it. While it is open the cover's own lyrics step aside (`updateLyrics` hides the
  overlay), so only one `LyricsView` follows the video. `LyricsCard` is now only for the other tabs. Never run on a device.
- **Power usage addon** (`addon/power/`, tab `ui/fragment/PowerUsageFragment`, built into `fermata` like Data usage):
  `PowerUsageTracker` reads `BATTERY_PROPERTY_CURRENT_NOW` (the *whole phone's* current; Android gives an app no per-app
  figure, so categories are "what the app was doing": video / music / idle, and Android Auto vs phone alone). Unit (µA or
  mA) and sign (charging positive or negative) are learned from the first readings, with Settings overrides
  (`UNITS`, `SIGN`); look for `POWER` lines in the Diagnostic log. It reads once a second only while a tab/overlay listens
  (`addListener`), else every 15-60 s and only while the app is open, playing or in a car; a handler message never wakes a
  sleeping phone. `PowerUsageStore` keeps µAh and ms per hour (2 months) and day (3 years). The Info Overlay item is the
  key `power` (`INFO_OVERLAY_SHOW_POWER`, with a car twin); the actions are `POWER_USAGE` and `POWER_SAVER` (end of the enum).
  `DataUsageChartView` takes a value formatter (`setValueFormatter`) so the Power tab reuses it for mAh.
- **Time left / to full** (`PowerUsageTracker#getEstimateMs`): from time-weighted averages, not the live reading: draining over
  ~10 min (`drawEma`), charging over ~1 min (`chargeEma`: a charger ramps up and tapers, a long average lagged far behind what
  the tablet was really doing); each starts afresh when the current's sign flips (|current| >= 30 mA, so noise near zero doesn't).
  The battery size is `POWER_CAPACITY` (Power settings > Power usage > Battery size, typed in; 0 = learned from
  `CHARGE_COUNTER`/level, which can be well off, e.g. 10061 for an 8400 mAh tablet); when set it drives both the estimate (by
  level) and the light/moderate/heavy load.
- The wizard's swipe fades the leaving page out within the first 60% of the drag and the arriving one in from 25%
  (`WelcomeWizard#morph`), so no ghost of the old page shows over the new one. Turning power saver on/off on the Power tab
  animates (`PowerUsageFragment#refreshSaver`: a delayed Fade+ChangeBounds transition on the fragment root, chips popping in).
- **Power saver** (`PowerSaver`): on by hand, below a battery level while unplugged, with Android Auto, or with Android's
  Battery Saver. It caps the phone window's brightness (and takes it to the minimum while a car is connected), holds
  60 Hz (`preferredRefreshRate`, which `MusicPlayerFragment#setLowRefreshRate` also respects) and pauses downloads it then
  resumes; turning it off while an automatic reason holds keeps it off until the reason passes (`suppressed`).
- **Welcome and setup wizard** (`ui/welcome/WelcomeWizard`, art in `WelcomeArtView`, drawn in code): an overlay on
  `R.id.main_activity` (elevation 24dp: above bars and menus, below the 26dp top cards so toasts show). Shown once on a
  fresh install (`firstInstallTime` ~ `lastUpdateTime`; an update only marks `WELCOME_DONE`), from
  `MainActivityDelegate#onActivityCreate`, and from Settings > Other. Phone screen only. Its key page uses the existing
  `KeyTester` hook; Back is routed through `WelcomeWizard.onBack()` in `MainActivityDelegate#onBackPressed`.
  Restore uses `SettingsFragment.importPrefs` (public static now) and marks the wizard done first, since a restored
  backup brings its own prefs back and the app restarts.

## Ambience addon (tab, sounds, Music tab weather)

- **Addon** `addon/ambience/AmbienceAddon` (built into `fermata`, order 9, `hasSettings`), tab `ui/fragment/AmbienceFragment`,
  all of it built in code in the Music palette (no XML layout). Actions `AMBIENCE` (open the tab) and `AMBIENCE_TOGGLE`
  (the last mix on/off, the rain when none; `AmbienceEngine#toggleAll`) are in the FAB list, the All in One card, key bindings
  and the FAB long-press Tools group; both toast `ambience_addon_off` when the addon is off.
- **Sounds are synthesized, not shipped** (`AmbienceSynth`: rain, thunder, fireplace, wind, ocean, stream, night crickets,
  forest birds, droplets; one `Voice` per `AmbienceEffect`), so there is nothing to download and a loop never repeats. `AmbienceEngine`
  mixes the switched-on voices on one thread into a streaming `AudioTrack` (44.1 kHz stereo, `USAGE_MEDIA`, **no audio
  focus**, so it simply mixes under local files, the Music tab and the YouTube page). The thread only lives while some
  fade is audible. Each effect's gain moves a block at a time towards 1 or 0 over `AMBIENCE_FADE_SEC` (smoothstep), the
  intensity (`AmbienceEffect#intensity`, 5 to 100) and master volume are smoothed too, and the mix is soft-clipped, so
  nothing starts or stops abruptly. Which effects are on is deliberately **not saved across app starts**; intensity, volume,
  length and the last mix are. The synth gains were tuned by rendering each voice offline (rms ~0.1 to 0.2 at full
  intensity, no NaNs); retune them the same way if a voice is changed.
- **Length** (`AMBIENCE_LENGTH_MIN`, 0 = no limit) is a timer from the moment the first effect goes on; at the end everything
  fades out. "Pause with playback" (`AMBIENCE_FOLLOW`, off by default) holds the ambience while the media session is
  paused/stopped (`setHeld`); switching a sound on clears the hold. Headphones unplugged (`ACTION_AUDIO_BECOMING_NOISY`)
  fades it out too (`AMBIENCE_NOISY`).
- **Weather** is `ui/view/AmbienceScene` (rain streaks, lightning with a bolt, clouds, embers and flames, gusts, waves,
  ripples, stars with fireflies and a moon, birds), driven by `AmbienceEngine#getVisual` so it fades with the sound; thunder
  calls `flash` on the scene when the strike happens (the rumble follows 0.1 to 2.4 s later, like real thunder). It is shown by
  `AmbienceBackdropView` (inserted under `music_content` by `MusicPlayerFragment#onViewCreated`), `AmbienceHeroView` and
  every `AmbienceCardView`. `AmbienceScene.Clock` runs it at 30 fps and stops by itself when nothing is audible; views also
  stop when not visible (`onVisibilityAggregated`), when the power saver is on, and per surface when "Animate the Music tab"
  (`AMBIENCE_ANIM_MUSIC`) / "Animate this tab" (`AMBIENCE_ANIM_TAB`) is off. Nothing is allocated while drawing, except the
  hero's sky gradient.
- **Foreground service** `AmbienceService` (media playback, notification with names and Stop all) is started by
  `AmbienceEngine#kick` and stopped when the audio thread ends, so the sound survives the screen off. Starting it from the
  background can be refused on Android 12+; that is only logged, the sound carries on while the process lives.
- **Held = silent and the timer waits too** (`pausedLeft`): with "Pause with playback" the timer is frozen while the playback
  is paused, not run out in silence. The switch is in the tab (under the length chips) and in the addon settings.
- **Frame loops must run while a sound is on, not only while it is audible**: the audio thread takes a moment to start, and
  a loop that saw nothing audible stopped itself and only woke when another sound changed (`isPlaying`/`isAnyPlaying`). The
  picture also leads the sound (`visualCurve`, full at half the fade). The settings chip is `ToolBarChip` like Data usage's.
- **Twelve sounds** (a full row of four on the tablet grid): rain, rainy window (`Window`: indoor hush and drops ringing the
  pane), thunder, fireplace, wind, ocean, stream, leaf droplets (`Drips`: drops from wet leaves, each landing on a leaf (soft pat, a mid band of
  noise + a short "tok") or in a puddle (a plip, a sine gliding up), mixed by the Landing slider, with showers when a gust shakes
  the canopy, a faint hush and a small `Reverb`, a Freeverb-style 4 combs + 2 allpasses), underwater (`Bubbles`: low gliding bubbles, rushes,
  a swirling hum, muffled, small room), night, birds, and cozy café (`Cafe`, **no voices on purpose**: synthesized murmur never
  sounded human; it is room hum, china clinks, a spoon, dishes, a cup put down, the espresso machine and the shop bell in a small
  reverb). A new sound needs an `AmbienceEffect` (with its option strings), a `Voice` in `AmbienceSynth#create`, a scene branch
  in `AmbienceScene` and strings/icon. Voices that are meant to sound like a real thing are the weak spot of synthesis (droplets,
  underwater, café): check pitch (spectral centroid) and tail length in the offline render, not just level.
- **Per-sound character slider** (`AmbienceEffect#option`, pref `AMBIENCE_OPT_<KEY>`, 0 to 100, 50 = as designed, passed to
  `Voice#render` as `opt` and smoothed like the intensity): rain surface, thunder distance, fire crackle, wind tone, wave pace,
  stream flow, echo, depth, cricket pitch, bird chorus, café activity, window drizzle/downpour. It lives on the card's second page
  (`AmbienceCardView`: the tune button flips front/tune with a scale+fade, so the grid never reflows; Reset =
  `AmbienceEngine#resetEffect`). Keep every voice at its old sound when `opt` = 0.5, and re-run the offline render
  (rms, peaks, NaNs at opt 0, 0.5 and 1) after touching one.
- **Recorded loops (bundled)**: some sounds (so far leaf droplets = `drops`, and underwater = `bubbles`) can be played from real
  CC0 recordings instead of the synthesized voice. They ship in `fermata/src/main/assets/ambience/` (`<key>.ogg` = Opus in Ogg,
  ~340 KB for a 60 s stereo loop, plus `manifest.json` with the credits), so there is no download, hosting or network; the
  `.ogg` name keeps them stored uncompressed. The files are made by hand: download the CC0 sounds listed in
  `tools/ambience/sounds.json` from Freesound (the download needs a login; check CC0 on each page), put them in a folder and run
  `python3 tools/ambience/build_sounds.py DOWNLOAD_DIR fermata/src/main/assets/ambience` (needs ffmpeg with libopus, numpy): it
  finds each by its Freesound id, bakes a seamless loop (equal-power crossfade of the tail into the head; a short source loops on
  itself first), matches the loudness to the synth voices, layers several sources and writes the manifest. Sources that are not
  downloaded yet are skipped. In the app `AmbienceSamples` reads the manifest, and decodes a recording with MediaCodec into PCM in
  memory (~11 MB a minute of stereo) when it is first wanted, handing it to `AmbienceSample` (a `Voice`: looped playback with
  interpolation, random start, a warm/bright tilt as its slider). The card's tune page shows Synth / Recorded for sounds the
  manifest lists (`AMBIENCE_SRC_<KEY>`, default Synth); the engine plays the synth until the recording is loaded and then
  crossfades (`Layer#mix`); the decoded PCM is released again 5 s + the fade time after the sound is switched off
  (`scheduleSampleRelease`), so only sounds that are on hold memory. A loop is as long as its longest source allows (up to
  60 s: a 10 s brook is a 10 s loop), loudness is matched with a soft limiter (`tanh`) that squashes only the peaks. The recording has its own slider pref (`AMBIENCE_TONE_<KEY>`), so Echo/Landing/Depth never change
  meaning. (An earlier design downloaded them from a release, built by a workflow with a Freesound API key; dropped as too
  much machinery for ~1 MB.)
- **Real, not Recorded** (UI word). Settings: the tab's controls card has a Synth / Real row for every sound at once
  (`AmbienceEngine#setAllRecorded`, mixed shows as "Mixed"); each card's tune page can still differ. The synth <-> real
  crossfade is slow (`SOURCE_FADE_SEC` 3 s, equal-power). Loops are encoded as the loop repeated plus 0.25 s each side
  and the manifest holds `frames`/`margin`; `AmbienceSamples#loopWindow` plays only that middle window, so Opus
  pre-skip/padding (which Android does not trim) can no longer make a tick at the loop point. A source marked
  `"seamless": true` in `sounds.json` (already a loop, e.g. the stream, waterfall) is not crossfaded again.
- **Real-only sounds** (`AmbienceEffect#realOnly`: umbrella rain, rainy street with passing cars, waterfall, train ride)
  have no synth voice (`AmbienceSynth.Silent`) and are hidden (`AmbienceEngine#isAvailable`) until their `.ogg` is in
  assets. `sounds.json` lists `alternatives` per sound; `build_sounds.py` falls back to the first one downloaded.
  Timer lengths now start 5, 10, 15 min.
- **None of this has run on a device yet** (no emulator in the cloud session). If sound is silent, choppy or the weather
  looks wrong, look for `AMBIENCE` lines in the Diagnostic log first.

## Home tab and its widgets

`ui/home/` + `ui/fragment/HomeFragment`. Home is **built into the nav bar** (name `"home"` in `NavBarMediator`, like
folders/favorites/playlists; not an addon) and is the start page: `MainActivityDelegate#defaultIntent` opens it when no
"open on start" tab is set and `HOME_OPEN_ON_START` is on (Settings > Home). The old "resume what played last" start is now
the media widget's **Continue** chip (`MainActivityDelegate#continueLastPlayed`). Action `OPEN_HOME` (end of the enum) is in
the FAB list, All in One and key bindings. Settings > Home (`SettingsFragment#addHomePrefs`) holds the page-wide settings;
what each widget shows is set on the widget (edit mode > its gear).

- **Pieces.** `HomeFragment` only passes its lifecycle to `HomeScreen`, which builds everything in code in the Music palette
  (`HomePalette`): `HomeBackgroundView` (picture or gradient), a `ScrollView` or `HorizontalScrollView` holding the
  `HomeGridLayout`, the greeting, the pinned edit chip bar (top right) and the status bar handling. `HomeScreen` is the
  `HomeHost` widgets talk to. No XML layouts on purpose.
- **Model** (`HomeModel`, JSON in `MainActivityPrefs.HOME_LAYOUT`, null = the default set): an *ordered list of cells*, each a
  stack of widgets of one size (w x h grid units). Only the order is stored; `HomeGridLayout` packs the cells first-fit along
  the scrolling direction, so one layout fits phone, tablet and car screen. Scrolling down = fixed columns, sideways = fixed
  rows (`HOME_ARRANGE`; by default sideways only in landscape under 600dp high). Widget type keys are saved: never rename one.
- **Edit mode** (chip top right, or a long press on any widget, or Settings > Home > Edit widgets): a hold of ~170 ms picks a
  widget up (a swipe still scrolls); the others glide aside (position FLIP animation in `onLayout`, sizes get a bounce);
  dropping on the middle of a cell of the *same size* stacks them (`canStack`); handles: remove (top left, with Undo), gear
  (top right) and resize (bottom right). All of it is in `HomeGridLayout`/`HomeCellView`; touches are caught in
  `HomeCellView#dispatchTouchEvent` *before* the children (so a button under the finger doesn't matter).
- **Stacks** (`HomeStackView`): swipe sideways to switch; dots show on swipe and fade after 1.8 s (always in edit/car mode);
  car mode gets prev/next buttons. Hidden stack members are `INVISIBLE`, which is also what deactivates them.
- **Options**: `HomeWidgetView#getOptions` (switch / choice / slider / button, `HomeOption`) after the common Look and Title.
  The panel is shown *inside the card* when it is 3x2 or bigger, else in a `HomeModal` card over the page (same rows,
  `HomeOptionsPanel`). Values live in the widget's JSON (`optInt/optBool/optString`, written with `setOpt`).
- **Look**: `STYLE_SOLID` (theme card), `STYLE_GLASS` (dark translucent), `STYLE_COLOUR` (the widget type's accent); "Same as
  Home" follows `HOME_CARD_STYLE`. Text/icon colours come from `color(ROLE_*)`; views made with `text()/icon()/iconButton()/
  chip()/header()` re-tint themselves when the look changes. Widgets that paint their whole card (media, ambience) override
  `ownBackground()` and stay white.
- **Chrome that hides**: exactly as the Music tab does it (see the fifteenth round): the tool bar goes GONE and the control panel and
  floating buttons are *suppressed* (`MainActivityDelegate#setOverlaysSuppressed`) at once, from `HomeFragment#switchingFrom`, before the switch
  commits, never faded. The background runs under the status bar through `BehindBarsLayers` (Android 15+) and the status bar takes the picture's top
  colour before that (`HomeScreen#systemBars`, restored with `MainActivityDelegate#matchStatusBarToBackground`).
- **Background**: `HomeBackground` copies the picked picture (system picker, no permission) to `files/home_bg.jpg`, shrunk to
  1600 px; `HOME_BG_VERSION` only says it changed. Blur is a small copy drawn large again (no RenderScript). Not in backups.
- **Car mode**: not on Home. `CarNav` leaves Next/Previous to the user's own key bindings while the Home tab is in front (`CarNav#isHome`,
  like fullscreen video: only an open modal, menu or panel over it is walked through, e.g. `HomeModal`, which is a modal scope). Home's cards are
  touched. Keep that in mind for new Home modals: mark them with `CarNav.markModalScope`. The stack's prev/next buttons stay on the car screen (touch).

### Smoothness rules (learned from the first tablet test)

- **Size before the first frame.** A widget that lays itself out by pixels overrides `onSized(wPx, hPx)` and reads
  `widthPx()/heightPx()`: it runs from the measure pass (EXACTLY specs only), so the first frame is already right. Never size
  from `onSizeChanged`/`getHeight()` in `onBind`; the Power gauge once drew small and then jumped (fixed that way).
- **Styles animate.** `HomeWidgetView#applyStyle(true)` cross-fades fill and text/icon tints (280 ms). Text/icon/progress views made with
  the base helpers re-tint themselves; a custom view recolours itself in `onStyleChanged`. Loading shows `spinner(dp)`.
- **Real blur** is `HomeBlur.blur(bitmap, strength)` (box blur on a small copy); a tiny copy stretched up shows as blocks. The
  media widget's wash and the background both use it. Pictures change through `MediaWidget.Fader` (two stacked images,
  cross-fade), texts through `swap`.
- **Own insets.** `HomeScreen` does not use `insetScrollableContent`: the title bar and the control panel are gone here, so
  there is nothing to follow. Top room is 0, bottom room is only a bottom nav pill (animated).
- **Entrance**: `HomeGridLayout#prepareEntrance` hides the widgets in `onShown` itself; `playEntrance` (one post later) brings them in.
  Doing both in the post showed the widgets for a frame first.
- **Edge fades** (`HomeFadeLayout`, wraps the scroller): content melts at the edges it can still scroll to (top/bottom, or left/right
  in sideways mode), clearing the part under a bottom nav pill, because Home opts out of the bars' own edge fade.
- **FABs are hidden** on Home (suppressed with the control panel, see above); the edit chip bar has a Settings chip
  that opens Settings > Home (`SettingsFragment.SHOW_HOME_SETTINGS`).
- `MeterBar` is a thin animated bar for widgets (Power breakdown, Data session limit).

### Second round (gestures, placeholders, now playing)

- **A widget that reads drags itself** (lyrics scrubbing, a stack's swipe) must keep the page from taking them: override
  `HomeWidgetView#gestures()` (`GESTURE_H` / `GESTURE_V`). `HomeCellView#claimGestures` holds the scroller off from the touch down
  when the page scrolls that way, and lets go once the finger moves the other way (a stack with nowhere to go lets go too).
  Waiting for the widget's own intercept is too late: the scroller intercepts first. A seek bar only needs
  `requestDisallowInterceptTouchEvent` on its own DOWN. Lyrics is the Music tab's `LyricsView` (scrub, tap a line to seek).
- **Resizing** shows a dashed outline of the size the handle would give (`HomeGridLayout` draws it over the cells) and applies it on
  release; the handle itself follows the finger. The inline options panel hides the three handles while it is open.
- **Look in Settings** shows Custom when some widget has its own look (`HomeScreen.styleShown`); picking one sets every widget
  (`HomeScreen.setAllStyles`), through the live model when Home is up, else the stored layout.
- **Placeholders**: `HomeSkeleton` (grey rows with a sweeping light) stands in for lists while they load (Library widgets, YouTube
  suggestions, and the suggestions again whenever the playing song changes: `stale`). A spinner is only for strips.
- **Opening something inside a card**: `LibraryWidget#drill(title, source)` swaps the rows for another source under a back chip
  (the playlists widget opens a playlist's songs; a tap plays one). Not saved.
- **Now playing** (`ui/home/HomeArt`): the item shown is what plays, else where the music queue stopped (cover and title right after
  start, like the Music tab), else the last session item; Play then starts it as music without leaving Home
  (`MusicPlayer.playTrack/playAsMusic`). The play button shows a spinner while the session is busy or a skip was just asked for.
  The cover is a square (never cropped) that dissolves into the blurred wash on its open side. Shuffle and repeat sit around the
  play button when the width allows, else under the seek bar.
- **Background from the cover** (`HomeNowPlayingBg`, Settings > Home > Background): the blurred cover fades from song to song.
  The background itself fades in after the bars extension has been applied (`HomeScreen#fadeBackgroundIn`), not stretching.
- **YouTube suggestions** play through `MusicPlayer#playYoutubeQuietly`: swapped into the page that plays, or the page is loaded
  without showing its tab; the screen stays on Home.
- **Greeting** sits pinned top left when the page scrolls sideways.
- New widgets: Sleep timer (`TimerWidget`), Audio effects (`EffectsWidget`, the equalizer's bands as bars), Info overlay
  (`InfoOverlayWidget`, a switch per overlay item, the car's twin prefs on the car screen).

### Third round (effects, queue, glow, status bar)

- **The `home` drawable is shadowed**: `fermata/src/auto/res/drawable/home.xml` (the Android Auto home button, a small glyph in a 960
  viewport) overrides any `drawable/home.xml` in `main` in the Auto flavor, which is why the nav icon stayed small. The tab's icon is
  `drawable/home_page.xml`; never add a drawable with a name the `auto` source set already has.
- **Edit mode and resizing draw a glow** (`HomeUi.glow`: a few strokes, wide and faint to thin and bright), not a dashed line. It draws
  outside the cell, which works because the grid, the scroller and its parents do not clip children.
- **Lists inside a card scroll** (`LibraryWidget#listScroll`, a ScrollView with fading edges); such a widget returns `GESTURE_V` from
  `gestures()` only while its list is taller than the card. `focusRow` scrolls a row into view (the queue's playing song).
- **Effects** (`EffectsWidget`, `media/engine/EffectsControls`): switches for the equalizer, bass, virtualizer and a fourth (YouTube's
  reverb, the platform's loudness), the equalizer's bands as bars and a preset stepper. Source: the playing engine's platform effects
  (`Platform`), or YouTube's (`MusicPlayer.getYoutubeEffects()`, whose settings are prefs the page follows, so they work with nothing
  playing). A new effect source implements `EffectsControls`.
- **Info overlay widget** embeds the real `InfoOverlayView` and applies the prefs as the Music tab does (`applyInfoOverlayPrefs`);
  it scales down to fit the card. **Queue widget** is a `LibraryWidget` over `MusicQueue#getPlayOrder`.
- **Ambience look**: its own `autoStyle()` is Colour (the sky); Solid and Glass make it a plain card (`ownBackground()` follows the
  style). `hasLookOption()`/`autoStyleLabel()` are the hooks for a widget that has a default look of its own.
- **Background**: the Background card has Picture / Cover of what plays. Leaving fades the bars' extension with the tab
  (`fadeBackgroundOut`), the status bar colour glides (`glideStatusBar`), and the cover background is not remade when it is the same song.
- **Covers lose black bars** (`HomeArt.trimBars`) so a square cover inside a 16:9 picture fills the card.
- A stack (or any widget) holding the page off needs `claimGestures` to be *called*: it was written but not wired for a round.

### Fourth round (play from widgets, swipe zones, chips, sliders)

- **Tapping a song or playlist in a widget plays it as the tab does** (`ui/home/HomePlay`: `tapped` / `list`): music-tab
  playback (`MusicPlayer.play`, so the queue is rebuilt) when `tapPlaysAsMusic`, else the session's `playItem`. Favorites,
  Playlists and their drill-in use it; never call `playItem` straight from a widget.
- **The whole card swipes a stack** (`HomeStackView#onTouchEvent`): content that consumes nothing never made the stack a
  touch target (only the title did), so the stack takes DOWN itself and starts the drag on the first horizontal move; with no
  neighbour to go to it hands the touch back (`requestDisallowInterceptTouchEvent(false)`). Neighbours move 55% of the width and
  cross-dissolve (smoothstep), so no cut-off ghost of the next card shows.
- **Queue widget** listens to `MusicQueue.Listener` while active, so it follows the queue as it changes.
- **Info overlay widget** is the real `InfoOverlayView` in chip mode (`setChipStyle(fg, fill)`: no shaded bar, no dividers, every
  item its own rounded chip in a `ChipFlowLayout`, same items/order/icons as the settings); it scales down only when the wrapped
  chips are taller than the card.
- **Effects widget**: a row per effect (switch + strength slider; the equalizer's row has the preset chip), the bars below when
  all rows fit and 90dp is left; bars can be dragged to set a band. What is shown is decided from the card's pixel height
  (`arrange`, from `onSized`), never from cell counts. `EffectsControls` gained `hasLevel/level/setLevel/setBand`
  (platform: BassBoost/Virtualizer 0..1000, loudness 0..`LOUDNESS_MAX_MB`; YouTube: the strength prefs).
- **Ambience** follows Home's look like any widget (no own default); it only paints its sky when the look is Colour.
- **Home's root is opaque** (`palette.background`) from the first frame; the picture/gradient fades in over it. A transparent
  root let the window's dark colour show through the tab crossfade as a veil.

### Fifth round (lists, entrance, art on seek)

- **`LibraryWidget` is a `RecyclerView`** (GridLayoutManager: 1 or 2 columns), not a column of views: rows are bound for what is on screen
  only, so a queue of hundreds costs a few rows, and coming back to Home rebinds nothing. A refresh is a `DiffUtil` against the old rows
  (`Row#key()` = its item, else its title). `Row` has a `current` flag: the playing queue row is filled with the accent instead of
  carrying a marker. `subFill` now gets a *setter* (`Consumer<Consumer<CharSequence>>`) that ignores a row that was recycled meanwhile.
  The playing row is scrolled into view once per song (`lastFocusKey`), never while the finger is on the list.
- **Thumbnails** (`HomeImages#loadUri/loadItem`) use the library's resized icon copies (`getBitmap(uri, true, true)`: decoded once,
  saved on disk, shared with the tabs' `recent` LRU), not the full picture, and fade in when they arrive late. The full-size path is
  only for the now-playing cover (`HomeArt`).
- **`HomePlay` always plays as music** (queue replaced, the screen stays on Home) when the Music addon is on; only streams and the addon
  being off go to the player they belong to.
- **`chip()` had never set its label** (every chip made through `HomeWidgetView#chip` was blank: timer presets, Settings chip); fixed there.
- **Entrance**: Home has two layers under its content that reach under the bars, `base` (the plain gradient, never faded) and `bg`
  (the picture). Like the Music tab's layers, both are at full alpha from the first frame (holding `bg` back and fading it in after the
  tab showed was what made a bare gradient appear first and the picture dissolve in 0.5 s later); `bg` is only faded when leaving
  (`bgShown`) and when a *new* picture arrives (`HomeBackgroundView#setImage`). Any index passed to `root.addView(.., index)` counts both.
- **Now playing**: no corner spinner any more. Items are compared by
  id (`HomeArt#same`), the trimmed cover is memoised, and the card/background skip a cover they already show, so a seek or lyric
  scrub that makes the player hand out another object for the song does not fade the art out and in. If it still does, the
  Diagnostic log has `home media: new item` lines (MUSIC).

### Sixth round (Android Auto layout, open issue)

- **The car screen has its own widgets**: `MainActivityPrefs.HOME_LAYOUT_AA`, the same JSON as `HOME_LAYOUT`; `HomeModel(prefs, car)` picks
  the pref (and its default set, `DEFAULT_TYPES_AA`) from `a.isCarActivity()`, and nothing is shared between the two. It is edited on the car
  screen (edit mode works there too); Settings > Home (its Android Auto pill) has "Copy these widgets to Android Auto" and "Reset the Android Auto widgets".
- **Now playing**: the play button's spinner (busy, buffering, just skipped) is wanted and stays; the spinners that were *not* wanted
  were the card's corner one (gone) and the page-centre one (below). The seek bar also asks the *engine* for position and duration (`MediaWidget#pollEngine`): the session's state and the item's metadata
  lag or lack both for YouTube, which left the bar frozen and not answering touches (a touch needs a length). `tick()` removes its own
  callback first, so a duration arriving late doesn't double the polling chain.
- **No page-centre spinner on Home**: `MainActivityDelegate#setContentLoading` (used by `BodyLayout` while a started playback comes up, among others)
  shows the big spinner in the middle of the page; `onHome()` keeps it off there, both when it is asked for and when its delayed
  show fires (a song picked in the Queue card used to bring it up over the dashboard).
- **Edit chips on a phone**: Background and Settings keep only their icons under 520dp, or the done tick went off the screen's edge.
- The bare bars on entering Home that this round left open are solved, see the fifteenth round.

### Seventh round (stacks go round, greeting, top info overlay)

- **Stacks loop** (`HomeStackView`): `pos` is unbounded while dragging or animating and `wrap`/`mod` bring it back (after each settle too);
  a swipe or the car's prev/next past the last widget goes to the first (and back). The dots measure circular distance.
  They show on touch and fade after 1.8 s; only edit mode pins them (`syncControls` also re-arms the fade when edit mode ends, which used
  to leave them up for good, and car mode no longer pins them: its prev/next buttons stay).
- **Greeting by the hour** (`HomeScreen#greetingText`): morning from 5, afternoon 12, evening 17, night 21 (10:47 pm is night). Each of the four
  can be replaced in Settings > Home (`HOME_GREET_*`, empty = the built-in words).
- **Info overlay on the greeting's row** (`HOME_TOP_OVERLAY`, `HOME_TOP_OVERLAY_AA` for the car screen): `HomeScreen#topInfo`, the same
  `InfoOverlayView` in chip mode (`HomeInfo#apply` fills it from Settings > Info overlay, the widget uses it too), one row before the edit
  button, shrunk to the room the greeting leaves and hidden while editing. The Info overlay *widget* drops its title row below 130dp of
  height (the chips get the whole card and a tap on them opens the settings) and fits its chips to the card minus its padding.
- **Ambience alone**: with "Pause with playback" the ambience is held only when music that *was playing* pauses or stops
  (`AmbienceAddon#wasPlaying`); the session repeats "paused"/"stopped" while nothing plays, and each repeat used to hold an ambience that had
  just been started on its own into silence. Held/released is in the Diagnostic log (AMBIENCE).

### Eighth round (Home settings page, info overlay fit, lyrics scrub option)

- **Settings > Home is a page of its own** (`ui/fragment/HomeSettingsFragment`, id `home_settings_fragment`, a `ReorderListFragment` with no rows, like
  `PowerSettingsFragment`): what holds on every screen (open on start, animation, widget look, background, the greeting's words) is
  listed first with no group around it; under two pills (Phone / Android Auto) are each screen's own settings, with their `*_AA` twins:
  layout (`HOME_ARRANGE`, `HOME_COLUMNS`, `HOME_GAP`), header (`HOME_GREETING`, `HOME_TOP_OVERLAY`) and widgets (edit, copy, reset). `HomeScreen`
  picks the twin from `a.isCarActivity()`. The row helpers (`addValueSliderRow`, `addChoiceRow`, `addActionRow`) now live in
  `ReorderListFragment` for both pages. Settings has only a button for it; its Back goes to Home when it was opened from Home's chips.
- **Info overlay widget**: chips a little smaller than the Music tab's (`HomeInfo.apply(.., factor)`), smaller still under 130dp of card, and placed
  by hand (top-left pivot, pushed down to the middle) when scaled, so a two-line set of chips never overflows the card.
- **Lyrics widget** has a "Scrub and tap lines to seek" switch (`scrub`); off, the view is not interactive and the card claims no
  gestures, so the page scrolls over it.

### Ninth round (top overlay row, stack buttons, ambience in the car, cold start)

- **The top info overlay is one row that scrolls** (`HomeScreen#topScroll` around `topInfo`; `InfoOverlayView#setOneRow` / `ChipFlowLayout#setOneRow`):
  it used to be given a fixed width from one measurement, and a reading that grew a digit (-2509 mA) wrapped the last chip onto a second
  line. Now the scroller is as wide as the room the greeting leaves (or as the chips if narrower), re-fitted whenever the chips change
  size, and the end fades out when it scrolls.
- **The car's stack buttons fade with the dots** (`HomeStackView#fadeArrows`); a tap on a hidden one only brings the controls back.
- **Ambience asks for audio focus** (`AmbienceEngine#requestFocus`, transient "may duck", changes ignored) when it starts while the app's own
  player is not playing; with Android Auto connected, a stream from an app holding no focus was not played through the car (the card and
  notification said playing, nothing was heard; first thing after an app start). Not confirmed on a car: the Diagnostic log has
  `AMBIENCE audio focus result=` and `track playing state= device=` lines to read if it still stays silent.
- (The window-wide start-up veil that was here is gone, replaced by the Music tab's own quiet start; see the fifteenth round.)

### Tenth round (playing rows, item pickers, car header)

- **Favorites and the songs of an opened playlist mark the playing song** like the Queue: `LibraryWidget#watchesPlaying()` listens for
  `onPlayableChanged`, `playingIds(a)` is the session item, the queue entry's source and the favoritable item, and a row is current when
  its `getOrigId()` is in it; `focusRow` brings it into view once per song. `marksPlayingOnTop()` is for lists whose top-level rows are songs.
- **All in One and Pages pick their items** (`TileWidget#pickOption`, "Pick the items"): a card of chips like the ambience sounds picker, a
  tap shows or hides that tile (`opt "items"`; empty = the card's own default set, so a page added later still shows, "-" = none).
  `candidates()` lists what can be picked, `defaultPicked(key)` what shows before anything was picked (Pages: the bar's pages, and the old
  "settings"/"hidden" switches still count).
- **Tile cards size from their pixel height** (`TileWidget#applySize`, from `onSized`): the icon shrinks to fit and the names only show
  when they fit under it (Labels "Automatic"); a card two cells high on a wide car screen is short in pixels, which is what clipped the names.
- **Car screen, scrolling down**: the edit button and the info chips sit on the middle line of the greeting's two lines
  (`HomeScreen#alignHeader`, a translation, since the pinned bar and the greeting have different heights); both stay pinned when the page
  scrolls (only the greeting scrolls away).

### Eleventh round (title rows that give way, data strip, chip sizes)

- **Power and Data drop their title row (icon, name, arrow) when the card is too short in pixels**, like Fuel does in a strip: `PowerWidget#applyHeader`
  (a strip always; the meter under 174dp, the graph/breakdown under 160dp) and `DataUsageWidget#layoutFor` (a strip always; a ring card under ~152dp,
  the breakdown list under ~166dp). The figures then open the tab on a tap (`body.setOnClickListener`), and the ring/meter size and the session-line
  room are worked out with or without the 34dp row (`headerOn`). Decide such things from the measured height, never from the cell count.
- **Data usage strip (4x1)** shows the total and its "of 18 GB · 72%" on the left and, beside it, the Android Auto session (`home_data_car_session`:
  used of the limit, what is left, with the bar; "Starts when Android Auto connects" while no session runs) when the session limit is on and the
  option "session" is on; otherwise the four kinds as a small 2x2 grid (dot + name + figure).
- **Info overlay chips are all one height** (`InfoOverlayView#layoutChips`): the icon-only Downloaded chip was only as tall as its icon, shorter than
  the chips with text; every chip wrapper now has the height of one text line plus its padding and the icon-only one is as wide as it is tall.
  The cut chip at the row's right end in the earlier build was the row running under the edit button; the row is now fitted to the room the
  greeting and the button leave (see the ninth round).

### Twelfth round (chips that turn opaque, start-up wait, edge fades)

- **The top info chips** (`HomeScreen#updateTopInfoScroll`, fed by the page's scroll through `HomeGridLayout#setScroller(s, onScrolled)`: a scroller has ONE scroll listener, and the grid's own `setScroller` was silently replacing the one `HomeFadeLayout#follow` had set, so the chips never reacted until edit mode was toggled (which re-applies them)): staying
  pinned, their fill blends from the translucent chip colour to an opaque panel colour over the first 56dp of scrolling
  (`InfoOverlayView#setChipFill`, which only recolours the chips' drawables), so widgets passing under them don't show through. Settings > Home >
  Header has "Hide the info chips while scrolling down" (`HOME_TOP_OVERLAY_HIDE`, `_AA` twin): they then leave with the greeting, fading.
- **No `setHorizontalFadingEdgeEnabled` on the chips' scroller**: on the car screen the built-in fading edge drew hard dark boxes at the row's
  ends (the "weird cut"). The row sits in a `HomeFadeLayout` (a layer cleared with gradients, as the page's own edge fade, which draws right there).
- **An opened playlist's songs have no play button** (a tap on a row plays it); the playlists themselves keep theirs, back at the top they come back
  with the list's fade.

### Thirteenth round (icon-only media chip)

- **The media widget's chip (Watch / Fullscreen / Open Music) is icon only on the car screen and at low density** (`MediaWidget#setChip`: `host.isCar()` or
  density under 1.5, i.e. under 240 dpi): the words were as big as the card's title there. The name stays as the chip's content description. The wide card then leaves only ~48dp (not 132dp) beside the title for it (`chipIconOnly()` in `MediaWidget`).

### Fourteenth round (back to Home, chip fixes, open issue)

- **Car mode, long Previous at the top of a tab goes to Home** when Home is in the bar (`CarNav#back`, `homeIsThere`: in `NavBarMediator.getTabs` and not
  hidden), else to what's playing as before. Inside a folder or playlist it still goes up one level first.
- **One scroll listener** (`HomeGridLayout#setScroller(s, onScrolled)`): the page edge fade redraw and the chips' opaque/hide step now run from the same listener
  as the grid's on-screen check. Anything else that needs the page's scroll goes there too.
- **Rapid edit-mode toggling crashed on Android Auto** (cause not seen; no log yet). Found and fixed on the way: `InfoOverlayView#setChipStyle` threw away the chip
  wrappers when the fill differed (which the chips' scroll fill makes routine), leaving item views attached to the old wrappers, so the next `addView` threw
  "already has a parent"; it recolours them instead. The chip bar's delayed transition now ends a running one first, and the toggle is logged
  (`HOME edit mode on/off`). If it still crashes, the crash is in the Diagnostic log (`DiagnosticLog#installCrashHandler` writes it): read the stack there.
- **`ChipFlowLayout` makes every chip of a line as tall as the tallest** (the icon-only Downloaded chip stayed shorter than the others even with a minimum height).

### Fifteenth round (the late fill-in, found; light theme; centring; last played; edit button; downloaded badge)

- **The cause of the late fill-in under the status bar and navigation bar, and of the start-up flash (open for five rounds): Home's root was a
  plain `FrameLayout`.** The tab-switch crossfade (`ActivityDelegate#crossfadeFragmentViews`) and the start-up fade put the tab's root at alpha
  below 1; a view that reports overlapping rendering (every ViewGroup by default) is then drawn through an offscreen layer *the size of its own
  bounds*, which cut off the background layers (`base`, `bg`) wherever they reach past the root under the bars, for the whole fade, and popped
  them in at the end. The Music tab never had it because its root is `OverflowFrameLayout` (`hasOverlappingRendering()` false, the alpha is
  applied to each child). `HomeScreen`'s root is that class now. **Any new tab whose background reaches under the bars needs it too.**
- **Home hides its bars the way the Music tab does** (the other half of "the Music tab has no glitch"): in `HomeFragment#switchingFrom`, before the
  transaction commits, the tool bar goes GONE and `setOverlaysSuppressed(true)` takes the control panel and the floating buttons away, all at once.
  Fading them (what Home did: the tool bar 160 ms, the control panel with `setCovered`) resized the tab in the middle of its fade in: on the car
  screen the sideways grid has a row count from its *height*, so the whole grid re-packed when the tool bar finished going. `switchingTo` gives
  them back (not `leave()`: after the next tab, the Music tab, suppressed them there, giving them back again would undo it).
  `MainActivityDelegate#keepsOverlaysAway` (Music or Home) is what the picture-in-picture exit and `resyncOverlaysAfterSwitch` check.
- **Quiet start, as the Music tab's** (`HomeScreen#onViewCreated(quiet)`, `quiet` = restored, or the first fragment shown since the app started): the root
  stays at alpha 0 for `QUIET_MS` (500 ms), then fades in over 350 ms with the widgets' entrance. The window-wide veil of the earlier rounds
  (`veilStartup`, `liftStartupVeil`, `isStartupVeil`, and the tool bar mediator's check of it) is deleted: it faded the whole window, bars included,
  and never ran on the car screen at all.
- **The bars are veiled until the first tab is up** (`MainActivityDelegate#veilBars`, end of `init`): tool bar and nav bar at alpha 0 and the
  overlays suppressed, so no empty pills show while the permissions are checked; the first `showFragment` fades the two bars in
  (`unveilBars`, 500 ms later on Home/Music to match their quiet start, 200 ms on other tabs, 4 s fallback).
- **Light theme.** `HomeBackgroundView` dims and shades towards white (`veil`) and its plain gradient is barely tinted (`plainTop`); the
  greeting and the top chips use the theme's own text colours on a picture too, and the chips' opaque fill (a theme panel colour) now has
  matching text (white text on the light panel was the invisible "weird" chips). Cards: `HomeWidgetView#lightText/color/applyStyle` treat a light
  theme as light cards in every look (Glass = white glass with a hairline, Colour = a pale tint of the accent, theme text on both); a card that
  paints itself says `ownBackgroundDark()` (the Ambience sky stays dark; the media card is the Music tab's whitish wash in a light theme, with the
  theme's play button colours, seek bar and chip). Dark-theme looks are unchanged on purpose.
- **Centred between the screen's edge and the side pill** (`HomeScreen#measureSideExtras`, re-run by `onSidePreDraw` whenever the root or the pill moves; the
  first version centred on the whole window and left the cards cramped against the pill): body_layout pads every tab clear of a side navigation pill on that
  side only, and by more than the pill's visible width, which left a thin margin on one side and a wide strip on the other. Scrolling down, the side with the
  smaller margin gets the difference as extra grid/greeting/header padding (`extraLeft/extraRight`), so the margin from the screen's edge to the first card equals
  the gap from the last card to the pill (no side pill: centred in the window). The `HOME side room` lines in the Diagnostic log say what it worked out. Scrolling
  sideways keeps its padding, and `HomeGridLayout#setViewportWidth` centres a row that is shorter than the page (no scrolling); a longer one scrolls as before.
  Not verified on the car screen: if its left edge is still clipped there, look at what the window's real left edge is (the car may be letterboxing the display).
- **Edit button off** (`HOME_EDIT_BUTTON`, `_AA` twin, Settings > Home > Header): the chip bar is GONE while not editing; the greeting and the info chips
  take the room back (`applyHeaderRoom`). Edit mode stays a long press, Settings > Home > Edit widgets, and **Home's long-press menu in the navigation
  bar** ("Edit home screen layout", `NavBarMediator#contributeItemMenu`, `HomeScreen.startEditing`).
- **Last played song in the widgets right after the app starts**: `LibraryWidget#playingIds` also takes `HomeArt.displayItem` while nothing is loaded
  (the queue's saved track, or the resume item), so Favorites, an opened playlist and the Queue mark it and scroll to it. The Playlists widget opens the
  playlist that holds that song by itself, once per widget (`openHolding`; "Open the last played song's playlist" switch, backing out sticks).
- **Downloaded badge** at the end of a favorite / playlist / queue row whose song is on the phone (`LibraryWidget.RowHolder#updateBadge`): just the download icon
  inside the row's own `main` layout (so the playing row's fill runs under it), not a chip of its own; `YtDownloads.Listener` refreshes the rows on screen when a
  download finishes or goes.

### Sixteenth round (smooth bars, shared clipping, auto-rotate, grow, tile colours)

- **The tool bar is still seen fading when Home comes up** (`HomeFragment#fadeGhost`): it goes out of the layout at once (Home is laid out once, see the fifteenth round), but a
  picture of it is drawn in `main_activity`'s overlay and faded over 180 ms. Anything that must leave the layout at once and still look as if it faded can do the same.
- **Unclipping the containers for the status bar is shared** (`ui/view/BarsClipping`, used by `BehindBarsLayers` and the Music tab): both tabs unclip the same containers and
  overlap while one fades out and the other in; each used to keep its own copy of "how they were", so the second one to unclip saved the first one's unclipped state
  as the original and restored THAT, leaving the containers unclipped for good: every other tab's list then scrolled out behind the status bar with no top fade over
  it (the see-through status bar that came and went). One original per container, a count of users, restored when the last lets go (`BODY containers clipped again`
  in the Diagnostic log). **Never save and restore `clipChildren` / `clipToPadding` of the shared containers yourself.**
- **Stacks can turn by themselves** (`HOME_STACK_ROTATE`, seconds, 0 = off, Settings > Home; `HomeStackView#scheduleRotate`): after the interval a stack on screen goes to
  its next widget unless edit mode, a finger or a swipe is on it; a touch starts the wait over. At least 3 s (`HomeScreen#stackRotateSeconds`).
- **A resized card grows into its new size** (`HomeCellView#growOnNextSize`, started from `onSizeChanged`): it is already laid out at the new size and is drawn scaled
  down to the old one, settling over 320 ms with the pivot travelling from the top left to the centre; the others glide to their places as before. (An animated real size
  would relayout the widgets every frame.)
- **Tile colours in a light theme** (`TileWidget#hue`): deeper and darker (saturation 0.78, value 0.62) than the pastel used on dark cards; All in One and Pages use it.

- **A song's natural end that did not go on** (reported as "sometimes", more in a light theme; the light theme itself cannot reach playback, but a
  system theme that follows the clock recreates the activity at dusk and dawn, and `YoutubeFragment#onDestroyView` stops playback then by design): the move to
  the next track in `MediaSessionCallback#engineEnded` was a chain of futures with no failure handling, so a next track that could not be resolved or prepared
  (a connection that gave out in the car) vanished without a trace. Now it is logged (`TRANSPORT engine ended: ...`), tried again twice 2.5 s apart, then stopped
  cleanly. Not confirmed as the cause: next time, ask for the Diagnostic log's TRANSPORT and YT lines around the end of the song.

### Seventeenth round (toolbar room, row menus, stack swipe in edit mode, sideways centring)

- **Leaving Home keeps the tool bar's room** (`HomeFragment#switchingTo`): when the tab coming up has a tool bar, the bar goes INVISIBLE (not GONE) with alpha 0 before the
  switch commits, so that tab is laid out with the bar's room from its first frame; it used to appear a frame later and push the list down (the list "suddenly scrolled a
  bit" on coming back to Playlists/Favorites). `HomeToolBarMediator#disable` then fades it in under the next tab's own mediator.
- **A shorter crossfade into Home** (`ActivityFragment#getFadeInMillis`, 200 ms by default, 110 ms for Home; the outgoing tab fades in at most the same time, and the
  tool bar's picture too): Home is mostly background, so the old tab (and a toolbar picture) showed through it for the whole fade.
- **Sideways rows are centred between the screen's edge and the side pill too** (`HomeScreen#sidewaysCentre` -> `HomeGridLayout#setViewportWidth(w, centreX)`): a row shorter
  than the page sits so that the margin before the first card equals the gap after the last; a row that needs the room scrolls as before (lead 0).
- **Library rows can open the tab's long-press menu** (`LibraryWidget#supportsMenu`, Favorites and Playlists; option "Menu on long press", on by default): a long press on a row
  opens `MediaItemMenuHandler` for its item, and the row is marked `home_no_grab` so the card does not take that press for picking itself up and starting edit mode. In edit mode
  the cell takes every touch as before, so a hold still moves the card. Turn the option off to get the old long press (pick up, edit mode) back on those rows.
- **A stacked card swipes in edit mode** (`HomeCellView#onTouchEvent` feeds `HomeStackView#onTouchEvent`): the cell intercepts everything while editing, so the stack never saw a
  touch; a sideways swipe now turns the stack, and holding for `PICKUP_MS` still picks the card up (the stack gets CANCEL then).

### Eighteenth round (same menu on rows, fade through, smoother auto turn)

- **The row menu is the tab card's menu**: `MediaItemMenuHandler(menu, item, listItem=true)` (the Home widgets' third argument). The short menu a bare item gets (no view) lacked
  Play, Play as music/video, Add into queue, Search alternative videos, Refresh thumbnail and Preferred media engine, all of which were gated on `view != null`; they now
  check `listItem`. Play from Home goes through `HomePlay.tapped` (what a tap on that row does), not the session directly. Select only exists in the tabs (it needs their list).
- **Home fades in only after the old tab is gone** (`ActivityFragment#getFadeInDelayMillis`: 70 ms for Home, then 140 ms in): a plain crossfade left the old tab's grid and its tool bar
  showing through Home for a few frames (seen frame by frame in a screen recording). The old tab fades out in the delay, the tool bar's picture in the same 70 ms.
  `ViewPropertyAnimator` keeps its start delay between calls: the crossfade always sets it (0 for the tab fading out).
- **A stack turning by itself** (`HomeStackView#animateTo(index, auto=true)`): 620 ms with a fast-out-slow-in curve (it starts from rest, unlike a swipe settling), and the widgets are
  drawn from hardware layers while it moves (`layers`), so sliding and dissolving two heavy cards is a transform per frame, not a redraw.

### Nineteenth round (picture of the old tab, panel on Music to Home)

- **The "shadow" entering Home was two things, seen frame by frame in a screen recording**: the old tab's list jumped up the moment the tool bar left
  the layout (its content insets follow the bar), and the 70 ms fade-through of the previous round then dipped through the bare background before Home
  came in (the dip made the jump easier to see). Now `HomeFragment#ghostOf` (from `switchingFrom`, before `putBarsAway`) draws the tab being left into a
  bitmap (filled with the theme's `colorBackground` first, so it is opaque) in its parent's overlay, and fades that out over `GHOST_MS` (180 ms, the tool
  bar's picture too) while Home is simply there at alpha 1 (`getFadeInMillis()` 0, so `ActivityDelegate#crossfadeFragmentViews` skips the fade; the tab
  left is GONE at once). A picture is taken only when the tab is laid out and fully opaque, and never of the Music tab (a WebView/SurfaceView does not draw
  into a bitmap; Music to Home keeps the short 140 ms crossfade). Do not go back to fading the old tab's real view: it relayouts under the fade.
- **The tool bar's own picture is faster than the tab's** (`BAR_GHOST_MS` 90 ms, decelerating, against `GHOST_MS` 180 ms): the bar is a dark pill, and
  at the tab's pace it stayed over the bright Home for a moment as a black bar at the top left. The strip of the tab's own picture that the bar (and the
  status bar) sat over is bare dark background too, so `GhostDrawable` fades that top band on its own, faster alpha curve than the rest of the picture
  (what was still seen as a black bar from Favorites to Home after the bar's picture alone was sped up).
- **Music to Home showed the control panel**: Music's `onHiddenChanged` -> `setListening(false)` ran during the commit, after Home had suppressed the
  overlays, and gave them back. `MusicPlayerFragment#suppressOverlays` now keeps them suppressed while `MainActivityDelegate#activeKeepsOverlaysAway()`
  (Music or Home is the tab being shown; the active id is already the new one during a switch). Any tab that suppresses the overlays must go through the
  same check when it hides.

- **Play as video from a Home row's menu** (`HomePlay#asVideo`): the menu's Play as video used to fall through to the session's `playItem`, which
  started the item without bringing the video up. It now does what the tab's `playTapped(pi, false)` does (`getBody().playItem`, which enters fullscreen
  video; externally played items go through `playExternally`), even while music mode is on.

### Twentieth round (rows refresh after their menu, queue reordering)

- **A Home row's menu changes show in the lists at once** (`MediaItemMenuHandler#onClosed`, set by `LibraryWidget#showMenu`): the library sends no notice when a favorite is
  removed or a song is added to a playlist (the tab only reloaded its own fragment), so the cards stayed as they were. When the menu closes the widget fires
  `FRAGMENT_CONTENT_CHANGED`, which every `LibraryWidget` already answers with a forced refresh (an unchanged list does not blink, see the signature), and once more
  450 ms later for changes that finish in the background.
- **Rows move with a drag handle only** (`LibraryWidget#supportsReorder`/`reorderOn`/`onReordered`, option "Drag handle"; an `ItemTouchHelper` started from the handle, since a long
  press never drags any more). The Queue card (on by default), Favorites and an opened playlist's songs have it; rows move in the adapter on the way and the move is committed once on
  drop (`onReordered`, positions as fetched). Queue: `MusicQueue#move(from, to)` in play-order positions. Favorites: `Favorites#moveItem`, mapped back to library positions when
  "Latest first" reverses the list (`FavoritesWidget#libraryIndex`). An opened playlist: `Playlist#moveItem` through `PlaylistsWidget#songIndex`. `rowsEditable()` is false for a
  top-level list of playlists, so those have no handle or remove button. A refresh that arrives while a row is held is held back and run after the drop (`dragging`, `refreshAfterDrag`).
- **Remove button** (option "Remove button", left of the row; `LibraryWidget#supportsRemove`/`removeByDefault`/`onRemoved(position, row)`): the row leaves the card at once.
  Queue on by default (`MusicQueue#remove(position)`); Favorites and an opened playlist off by default (`removeItem`/`removeItems` on the library, the row passed in).
- **The Queue card's long press is its menu** (option "Menu on long press", on by default, `QueueWidget#showMenu`): Play next (`MusicPlayer#playNext`), Play as video
  (`HomePlay#asVideo`), Download (`YtDownloadMenu#addTo`), Share (`ShareSheet`), Remove from queue (`indexInPlayOrder`). The old long-press reorder is gone ("Reorder on long press"
  removed). Never run on a device.
- **Thumbnail quality** (Settings > Storage and cache, `BitmapCache.THUMB_QUALITY`): High = the largest YouTube picture (as before), Highest (experimental, `THUMB_QUALITY_HIGHEST`) = video frames. Home's card and background, the Music tab cover
  and notifications all follow it. With Highest, a YouTube album art is a picture of the video at its middle (`ytdl/YtFrames`, `YtStreamResolver#frames`): the tallest stream
  up to 2160 lines in VP9, AV1 or H.264, falling back to lower ones after up to three tries. It is kept as `ytframe://<id>/2160.jpg` in the image cache (the file keeps its
  size; memory gets a copy capped at 2160 on the long side, `capFrame`). Callers show the balanced maxresdefault.jpg first (`BitmapCache#getBalancedCover`) and swap in the frame
  when it arrives (`HomeArt#fetch`, `MusicPlayerFragment#loadArt`, `FRAME_KEY`); a frame already in memory is shown straight away. "Refresh thumbnail" drops the frame too.
  Only those callers ask for frames (`getVideoFrame`); lists, icons and the lock screen get the balanced picture from `getBitmap`. Captures run one at a time on their
  own thread (`frameQueue`/`frameExecutor`), never on the app pool, which has 5 threads (`FermataApplication#getMaxNumberOfThreads`): a slow capture on the pool starved
  every list and the Home blur. Only MP4 streams are read (AV1, H.264), since a 4K WebM stream failed in `setDataSource` on the device.
  The still is the most contrasty of three stills at 45/50/55% of the length (`YtFrames#score`, dark ones penalised). The swap from the balanced cover fades over
  `HomeArt.FRAME_FADE_MS` (1400 ms): frames are marked with `HomeArt#markFrame` and the card, background and Music tab look that up with `isFrame`.
  Logged under ART. Not run against live YouTube from the cloud session.
- **Channel names**: a playlist or Favorites row's artist line (the tab's subtitle in `PlayableItemBase`, and the widget's rows in `LibraryWidget#artistOf`) goes through
  `MusicTrackItem#cleanArtist`, so "Artist - Topic" shows as "Artist" as on the Music tab.

- **Rows picked up or removed must move in the adapter's own list** (`RowAdapter#adopt`): a refresh whose rows look the same as the ones shown (same signature) used to
  replace `rows` without handing the adapter the new list, so a drag or a remove moved rows in a list the adapter was not reading, and the rows were rebound from one
  that had not moved (a song seen twice, the other one gone from view). `render()` adopts the list on that path, and a drag start / a remove adopts it again.
- **Removed rows leave with an animation**: the list has a `DefaultItemAnimator` (add 200, remove 220, move 240 ms, no change animation: refreshes in place must not blink;
  none when Home's animation is off), so a removed queue song fades out and the rows under it slide up.
- **Every list answers every change at once** (`ItemContainer#addContentListener`, called from `setNewChildren`, i.e. any add or remove in Favorites, a playlist or the list of
  playlists, from a menu, a tab or the Music tab): the Favorites, Playlists and their opened lists re-read at once, not when the 15 s staleness ran out or the menu closed; and a list
  coming back on screen re-reads at once (`onActive` forces it). The Queue card listens to the queue instead (`QueueWidget#watch`, re-resolved on every read: the queue is made the
  first time something is queued, which a card already up never saw before) and to the playing song.

- **The now-playing card's Previous / Next are dimmed and dead on the queue's first / last song** (Repeat off), the Music tab's rule: `MediaWidget#syncState` calls
  `FermataServiceUiBinder.setSkipAvailable` from `MediaSessionCallback#isAtListStart/End`, and `onPrev` / `onNext` return early there. Nothing loaded (right after app start) keeps them working
  (they start the saved queue's neighbour).
- **A queued YouTube song's channel is looked up** (`MusicQueue#lookUpMissingChannels`, from `changed()` and `getPlayOrder()`, at most every 1.5 s and once per video per 5 minutes): a song queued
  from a list or a search was never played, so only `setInfo` while playing gave it a channel, and its row (the Music tab's queue and the Queue card) showed none until it played. The
  background thread asks the YouTube addon's cache first, then YouTube's oEmbed (`YoutubeSearch.lookUpById`) and calls `MusicTrackItem#setInfo`, which saves and tells the queue's listeners, so
  both lists fill in. Until then the Queue card shows "YouTube" like the Music tab.
  A track made from a Favorites / playlist entry takes what that entry already knows when it is queued (`MusicQueue#newTrack`: the item's loaded metadata, else the YouTube addon's stored
  `VideoInfo`, channel and length), and for a YouTube entry whose metadata is not loaded yet it reads it from the item and fills the channel in (`setInfo`), so the oEmbed look-up is only
  the last resort for songs that came from neither.

- **The Playlists card fills the playlists that hold the playing song** (`PlaylistsWidget#fetch`/`holders`: each playlist's children are read in turn, the cached ones at once, and every playlist
  containing the song (`playingIds`, also the last played song right after app start) is a `current` row, the accent fill the playing song's row has; the first one is scrolled into view, and the
  one-time "open the last played song's playlist" now uses it too). `marksPlayingOnTop()` is on, so a song change re-reads the list.
  **The search for the holders does not hold the card back** (`PlaylistsWidget#scanHolders`): the rows are drawn at once from the last known `holding` set, and the scan (one playlist after the
  other, hundreds of reads on a big library) runs behind, redraws only when its answer differs, and is not repeated for the same playing song and the same library change count. The first
  version waited for the scan before showing any row, which made the card slow to appear with 200+ playlists.
- **Long lists appear fast** (`LibraryWidget`): `ItemContainer#changeCount` counts every add/remove of Favorites and playlists; a card coming back on screen re-reads only when the count moved
  (`seenChanges`) or it has no rows, instead of every time. A card sitting in a stack (not active) reads its list 700 ms after it was bound (`prefetch`), so rows and pictures are there when it is
  swiped to; the skeleton placeholder still shows if the read is not done by then.

### Twenty-first round (car keys on widgets, pins, per-screen background, tablet settings)

- **Car keys on a Library card** (`LibraryWidget#takeCarKeys`, option "Control with car keys", on by default): on the car screen a touch on Favorites / Playlists / Queue
  gives the card the Next/Previous keys: its `listView` is marked as a *modal* `CarNav` scope (so Home's "keys stay the user's own" rule yields to it), the first row is outlined
  (`CarNav.selectFirst`), short keys walk the rows, long Next = `performClick` on the row (opens a playlist at the top level, plays inside it / in Queue / Favorites),
  long Previous = `carBack` (up out of an opened list, else `releaseCarKeys` -> `CarNav.release`, the keys return to the user's bindings). Only the row's main view is a stop
  (the play, remove and drag buttons are tagged `CarNav.skip`). After an open/back the outline goes to the first row of the new list (`carReselect`). Released in `onInactive`. Never run on a car.
- **Pinned playlists** (`HomePins`, pref `PINNED_PLAYLISTS`, the row menu's "Pin to top" / "Unpin" for a playlist opened from a Home card): `PlaylistsWidget#pinnedFirst` puts them first,
  newest pin on top; the list refresh (DiffUtil) moves the rows.
- **Hide downloaded icon** option on every Library card (`hideDownloaded`, bit 16 of `rowMode`).
- **The Home background is per screen**: `HomeBgPrefs` picks the phone's or the car screen's prefs (`HOME_BG_*` / `HOME_BG_*_AA`) and `HomeBackground` its picture file (`home_bg.jpg` /
  `home_bg_aa.jpg`). Each screen's arrangements remember their own dim/blur/zoom/quality/source (`HomeModel#rememberBackground`). The settings sit under each screen's Layout in Settings > Home.
- **Settings on a tablet** (window >= 720dp wide, not the car screen): `SettingsFragment` shows two panes. The left `navAdapter` stays on the root set (a tap on a category calls the right
  adapter), the right `adapter` holds the category and its pages; `pageShown` keeps the left highlight (`settings_nav_selected`) in step, Back never goes above a category (`twoPane`).
- **A fresh start is judged by the app's data**: `FermataApplication#attachBaseContext` writes `WELCOME_FRESH` when the preferences are empty (a fresh install, or the data cleared on an older
  install, which `firstInstallTime ~ lastUpdateTime` missed), and the start-up permission dialogs (one reads as a location permission) wait for the wizard (`WelcomeWizard#afterWizard`).
- **Back with the YouTube comments open** is guarded by an overlay-priority `OnBackInvokedCallback` (`YoutubeCommentsView#armBackGuard`): the WebView registers its own fullscreen-exit callback, newer than the
  activity's, so Back used to leave fullscreen (pausing the video) before the comments saw it.

### Twenty-second round (one settings look, the list beside every settings page, live previews, crash on first run)

- **One look for every settings row**: `PreferenceView#setRowStyler` (set to `ui/view/SettingsStyle#apply` in `MainActivityDelegate#init`) is called after each PreferenceSet row is bound
  and gives it the Home settings page's look (rounded tile, bold title, accent icon and tick). The option pages built in code (`ReorderListFragment`) draw the same tiles with the same colours
  (`SettingsStyle.Colors` is the palette they share). A new settings control should use those, not its own colours.
- **The list of categories beside every settings page on a wide window** (>= 720dp, not the car screen): `ui/view/SettingsSplit`, a view added to `BodyLayout` (below the tool bar, over the page
  container, which gets a left margin as wide as it) and shown while the fragment in front says `MainActivityFragment#isSettingsPage()` (Settings, `ReorderListFragment` pages, Captions/Lyrics, All in One,
  the key simulator, the diagnostic log). It stays while the pages change, slides in and out, and a divider on its right edge drags its width (`SETTINGS_NAV_DP`). What it lists is `SettingsNav`: `SettingsFragment`
  publishes the root's categories and the buttons at its root (`publishNav`); a page reached from a button keeps that button selected, a page inside a category its category. Settings itself shows only the
  chosen category on a wide window (`twoPane`: its root is never the page). Nothing is listed until Settings has been built once, so on a fresh start the list is missing from pages opened before it.
  A new settings page only needs `isSettingsPage()`; it must not add a left pane of its own.
- **Home settings previews** (`ui/home/HomeLayoutPreview`): a phone or the car's display drawn live from the prefs (arrangement, columns or rows, gap, greeting, info chips, edit button, the picture with dim, zoom
  and blur, and sample cards packed first-fit the way `HomeGridLayout` does, cut off at the scrolling edge). One big one tops each screen's part; the arrangement picker is four small ones
  (`HomeSettingsFragment#addArrangeCards`). It listens to the prefs itself while attached.
- **First-run crash**: the permission dialogs must come before anything that lists the library; deferring them until the wizard closed (the twenty-first round) left the app starting without storage access and it
  crashed on the first thing that left the wizard, and again at every start. They are asked first again, as ever. `DiagnosticLog#logCrash` also writes `Android/data/<app>/files/last_crash.txt`, which a file manager can reach
  when the app cannot start.
- **The wizard swipes over its picture too**: a touch nothing under it takes makes `PageHost` itself the target, and then the moves never go through `onInterceptTouchEvent`; `PageHost#onTouchEvent` starts the drag.

- **`SettingsSplit` outlives its activity in a static list** (`live`): when the theme changes the activity is recreated and the old body layout is still listed, and asking the destroyed activity's delegate
  (`MainActivityDelegate.get`) throws `ActivityDestroyedException`: the crash on a theme change and on opening Settings that followed. `entriesChanged` skips bodies that are not attached and catches the rest.
  A selected category keeps the tile's own transparency (`ColorUtils.blendARGB`, never `mix()` of two opaque colours: the tile fill is a see-through white or black and went solid) and always has a stroke of the same
  width, so it is not a different size. The list fades in and out and the pages' margin is set at once: animating the margin relaid out a whole page every frame.
- **Between Home and a settings page a plain crossfade** (`HomeFragment#switchingFrom`: no picture of the old page, no delayed fade).
- **`HomeLayoutPreview` moves**: the cards glide to their new places when a setting changes (`computeLayout` sets origin/target, `frame` interpolates), the picture of the arrangement in use scrolls gently along its way
  (`scrolls()`), and Freeflow leaves holes (`FREE_HOLES`) so the page shows empty room around the cards. It only runs frames while visible.

### Phone sizes (a cell is ~90dp there: 2x2 is ~185dp, 4x1 is 380x90dp)

- Size what is *inside* a card from its pixels, not from cell counts or fixed dp: the Data ring's words (`used`/`caption`) and the Power
  gauge's number follow the ring's diameter (a 19sp "12 GB" does not fit a 70dp ring), and what does not fit is left out (Power's
  one-line stats drop temperature, then time left; Data's caption becomes "of 18 GB" under ~105dp; its session line needs a card
  at least 220dp tall; Fuel's refuel chip and date need room, and a one-cell-high Fuel drops its title row, its figures opening the tab).
- A card 2 cells wide gets smaller pictures and play buttons in `LibraryWidget` rows (`widthPx() < 230dp`).
- **Data usage has a Show option** like Power: Automatic (ring, and the legend beside it when wide), Pie chart (ring only) or Breakdown
  (a line per kind with its share as a bar and the total on top; the bars go when the card is under 150dp of room).

### Adding a widget (the easy path)

1. Write `ui/home/widget/FooWidget extends HomeWidgetView` (constructor `(HomeHost, HomeWidgetType)`). Build views in
   `onCreate(FrameLayout content)`, fill them in `onBind()`, listen to data only between `onActive()` and `onInactive()`,
   adapt to the size in `onResized`/`isCompact()`/`isLarge()`/`getCellsW()`/`getCellsH()` (and the height in
   `onSizeChanged` for "how many rows fit"), list settings in `getOptions()`, run app actions with `host.run(Action)`,
   open tabs with `open(id)`. For a grid of round icons extend `TileWidget` (implement `tabId()` and `fill()`); for a list of
   library rows extend `LibraryWidget` (implement `fetch()`).
2. Register it in `widget/HomeWidgets` (key, name, description, icon, accent, min/default/max size, optional `available`).
3. Add `home_w_<key>` and `home_w_<key>_d` (and any texts) to `values/home.xml`. Nothing else: the add list, options panel,
   look, stacking, resizing, saving and car mode come with the base class. If it needs an addon, show `note(...)` when off.

Never run on a device from the cloud session. The **live video** is *not* drawn inside the media widget: the YouTube WebView
lives in its own tab and moving or resizing it restarts the player, so the large media widget shows the video's poster
picture, a VIDEO badge and a fullscreen chip (or a Watch chip in music mode) that go to the real player.

## Hide cover, UI style (glass)

- **Hide cover** (`ui/view/CoverHide`, action `COVER_HIDE` at the end of the enum: FAB list, the FAB long-press Screen group,
  All in One, key bindings; pref `COVER_HIDDEN`): covers are shown as their colours only, a 6 px copy box blurred and stretched
  (`CoverHide.veil`, the last one cached; `applyToThumb` for list rows, uncached). The Music tab's cover, its background, the
  swipe peek card and the queue rows follow (`MusicPlayerFragment#applyCoverHide`), with a small eye chip in the cover's top
  right corner (a tap shows the covers again). Home's media card (`MediaWidget#setArt`) and the "cover of what plays"
  background (`HomeNowPlayingBg`, which then ignores the blur setting) follow too.
- **UI style** (`ui/view/Glass`, page `ui/fragment/GlassSettingsFragment`, Settings > Interface > UI style; prefs `GLASS_*`):
  Normal (unchanged), Frosted, Clear, Acrylic; picking one writes its preset into blur / tint / saturation / grain / rim,
  which can then be tuned; "Bars" and "Home cards" say where it goes. Previews are `GlassStylePreview` (a phone drawn in
  code, like `HomeLayoutPreview`; the big one scrolls rows under the bars and uses the real blur).
- **How the real-time blur works (Android 12+ only, no library)**: `BodyLayout#draw` records itself into a RenderNode
  (`GlassLens.recordAndDraw`, the BlurView 3 `BlurTarget` idea: the screen draws that node, so nothing is drawn twice) while
  the bars are glass; each glass surface (`ToolBarPill`'s drawable, `FloatingBarsView#drawPill`) draws it through a
  `GlassLens`: a RenderNode the size of the surface plus a margin, holding the content node moved into place, with a blur
  (+ saturation) `RenderEffect`, clipped to the pill. Only the surface's area is blurred, on the GPU. `BodyLayout#syncGlass`
  (its pre-draw) invalidates the bars each frame that happens anyway, so they follow scrolling without asking for frames.
  Home's Glass cards blur Home's own background layers instead (`HomeBackgroundView#draw` records each, `HomeScreen
  #drawGlassBehind31`; a card can never blur the tab, which contains itself: a RenderNode cycle), redrawn from their own
  pre-draw (`HomeWidgetView#glassSync`). A WebView must not be drawn twice in a frame, so on the YouTube / browser tabs the
  bars get the tint only (`Glass.hasWebView`, checked twice a second). Below Android 12 glass is the tint, grain and rim
  without blur (RenderScript is deprecated and slow). With glass on the bars the bottom fade is off and the top fade only
  covers the status bar. **None of this has run on a device yet**: `BODY glass source on/off` is in the Diagnostic log.

### Second round (redraw only on change, menus, quality, glide)

- **Never invalidate a glass surface before every frame.** An `invalidate()` from a pre-draw listener schedules the
  next frame whenever the traversal didn't relayout, so the first version redrew (and blurred) 60 times a second on an
  idle screen (~400 mA). Surfaces are registered (`Glass.register`, done by `GlassLens.behindBars`) and drawn again only
  from `BodyLayout#onDescendantInvalidated` (something in the tab changed), `BodyLayout#draw`, and `Glass.syncSurfaces`
  (the surface moved). Home's glass cards: `HomeWidgetView#glassSync` only when the card moved,
  `HomeBackgroundView#setOnRedraw` when the background changed.
- **Quality** (`GLASS_QUALITY`): the lens renders at 1, 1/2 or 1/4 size (`Glass.renderScale`, 1/4 with the power saver)
  as its own compositing layer and is stretched back up.
- **Glide**: `Glass.look()` interpolates from the old look to the new for `ANIM_MS` after any `GLASS_*` change (NORMAL counts
  as tint 95%, no blur), driving `invalidateSurfaces` and `Glass.addAnimListener` listeners each frame.
- **Web tabs**: `BodyLayout#syncGlass` treats the YouTube / browser fragment ids as web at once (and for 600 ms after
  leaving), not after the throttled WebView search: drawing a WebView in a lens even for a few frames flickered.
  Blur over a page or fullscreen video is not possible (functor / SurfaceView / the page's fullscreen top layer is the
  `<video>` element itself, so not even CSS `backdrop-filter` in the page could sit over it); those get `Glass.surface`
  (tint, grain, rim).
- **Menus** (`ui/view/MenuStyle`, `OverlayMenuView.setStyler` hook in `depends/utils`): every menu's card (`MenuStyle.panel`,
  glass with `GLASS_MENUS`), a left-aligned header, roomy rows with a rounded focus/selected outline for car keys. The context
  menu opens at the finger (`recordTouch` from `BodyLayout#dispatchTouchEvent`, within 1.5 s; a submenu keeps the place),
  centred on the car screen and for dialogs. Dialogs (`DialogStyle`) and the cards (playlist picker, All in One, lyrics,
  download, share, alternatives, comments) use `MenuStyle.panel` too.
- Settings rows take `SettingsStyle.Colors#tile` (`Glass.tile`: sheen + hairline rim) while the style is glass; bound rows
  only change when the page is built again.
- **Hide cover button**: `MusicAddon.COVER_CHIP` (Music addon settings) and Home's player card option `eye`; the card lays out
  without its cover while hidden (`MediaWidget#layoutHidden`, `relayout` with an AutoTransition) and re-checks in `onActive`.

### Third round (the native crash, tiles and knobs that glide, Playlists card)

- **A lens must never blur the content that holds it**: the Music tab's More card and the All in One settings preview are
  inside `body_layout`, and drawing the body's RenderNode inside them is a RenderNode cycle that kills the render thread
  natively (no Java exception, nothing in the Diagnostic log: "it crashed, nothing logged"). `GlassLens#inside` refuses that;
  such a surface gets the tint only.
- Settings tiles (`Glass.tile(normalFill, ...)`, a GradientDrawable that re-colours itself by `Glass.amount()`: flat glass fill +
  hairline, no gradient) and seek bar knobs (`Glass.knob(SeekBar)`: disc in Normal, a bead with a rim and the accent core in
  glass) blend while the style glides; `BodyLayout#glide` redraws the whole window each frame of the glide.
- Home cards restyle when the glide ends (`HomeWidgetView#glassAnim`, and a check in `draw`): restyling mid-glide left them
  clear (transparent) after going back to Normal. The top info chips take the glass cards' tint and a rim (`setChipStroke`).
- Menu rows get the focus outline only on the car screen; the eye buttons toggle without a top popup (`CoverHide.toggle(a, false)`).
- **Playlists card**: which playlist is open, and whether the last played song's one was opened, are kept per widget id for the
  life of the app (`PlaylistsWidget#OPEN`, `OPENED_LAST`): a card made again used to show every playlist with no back chip.

### Fourth round onwards (More card, chips, tiles, rim light, seek bars, menus, web pages)

- **Music tab's More card** opens on `R.id.main_activity` (not the tab's root) while glass is on menus (`AllInOneMenu#showMusic`),
  so it can blur the tab; the anchor still places it over the chip; `MusicPlayerFragment#onHiddenChanged` closes it with the tab.
- **Home's info chips** blur the background like the glass cards: `InfoOverlayView#setChipBackdrop` (each chip is a `ChipWrap`
  drawing the backdrop under its fill), redrawn on background change, the row's scroll and the hide-while-scrolling slide.
- **Tiles** (`Glass.tile`): the picked state glides (`Glass.setPicked`, reused per row in `SettingsSplit`). Settings cards keep the
  Normal look unless `GLASS_TILES` (Where > Settings cards) is on (`Tile` blends by `tileAmount()`).
- **Rim light per part**: `GLASS_RIM` is the style's rim, `GLASS_RIM_TOOLBAR/NAV/FAB/MENUS/HOME/VIDEO/TILES` say where it shows
  (Settings > UI style > "Where the rim light shows", an expandable group; settings cards off by default). Draw a surface with
  `look.rimFor(Glass.RIM_*)`; `Glass.rim(part)` glides when a part is switched.
- **Seek bars in glass**: `Glass.knob` gives a see-through bead (no coloured core; later thumb tints are taken, every `StepSeekBar`
  uses it) and wraps the track's progress layer in `RoundFill` (a pill ending round under the bead; the clipped bar in Normal).
- **Context menu rows on the phone** are the settings rows' size (15sp, 22dp icons, `MenuStyle#styleItem`), not scaled by the
  interface's text/icon size; the car screen keeps its own sizing.
- **Web pages (experimental `GLASS_WEB`, "Blur over web pages")**: our surfaces cannot read a WebView. Drawing the tab with a WebView
  in it a second time flickered without end; recording a frame around the page, or around fullscreen YouTube's picture, crashed the
  app natively (nothing logged) or showed nothing; PixelCopy of the window holds the glass itself, and of fullscreen YouTube found no
  video surface (Chrome draws it in its own view). **Do not try those again.** What works: the page blurs itself (`ui/view/PageBlur`):
  a surface reports its window rect, radius, alpha, blur and saturation (`PageBlur.cover`, keyed by its lens slot, from
  `GlassLens#behindBars` when the tab is not recorded), and a script keeps matching boxes in the page (`#__zrauto_glass`, position fixed,
  no touches) with `backdrop-filter: blur() saturate()`. Boxes fade in and out (kept by id, CSS opacity transition) and take the view
  alpha of their surface (sampled every 100 ms while up); sent at most once a frame when changed and every 2 s anyway (page reloads).
  A pill that stops drawing takes its box with it (`Glass.notDrawn(slot)`, where `FloatingBarsView#drawPill`, `FabPillView#onDraw` and
  `ToolBarPill` return early). `cover` returns whether the page supports backdrop-filter.
- **Top popups and Home's own surfaces**: `TopPopup#styleCard` gives the top toast, `TopConfirm` and `ShareReceiveCard` the menu card
  (`MenuStyle.panel`, glass with `GLASS_MENUS`, no shadow then); `HomeModal` (widget settings, Background) uses it too, it is on
  `main_activity`. What is drawn inside Home (the edit button and chip bar, a widget's inline settings panel) cannot blur the tab that holds it,
  so `ui/home/HomeGlassFill` blurs Home's background layers like the glass cards (with `GLASS_HOME`), redrawn when it moves.
- **Now playing card has a Look**: its first choice is "Cover" (`MediaWidget.STYLE_COVER`, returned by `autoStyle()`, so "Same as Home" and
  `setAllStyles` keep the blurred cover as the card, as before); Solid, Glass and Colour make it an ordinary card of that look with the wash
  hidden and the cover dissolving into the fill or the glass (`applyLook`, `scrimFor` only darkens under words that lie over the cover).
- **`ModalProgressPopup`** (the Spotify sync / import progress) is a modal card like the playlist picker: `MenuStyle.panel`, Music palette
  colours, an accent disc with the icon, a close button (cancels while running), pills in the settings tile colours, rising into place.
- **Menus open at the finger anywhere**: `MenuStyle.recordTouch` is fed from `MainActivity#dispatchTouchEvent` (not only the tab), and a menu
  tagged `R.id.menu_at_finger` (the nav bar's, `NavBarMediator#createOverlayMenu`) is placed like the context menu.
- **Fullscreen video is never blurred** (YouTube or local): `BodyLayout` does not record in FRAME mode and `behindBars` returns false in
  video mode, so every surface there (control panel, floating buttons, menus, dialogs, the video buttons via `Glass.surface`) draws the
  **frosted look**: `Glass#paintOver(.., blurred=false)` = at least 45% tint plus grain, so the video reads as behind frosted glass. The
  same look covers web pages without the experimental switch, and everything below Android 12.

## This app cannot be built in this (cloud) session

Claude Code sessions hosted at claude.ai run in an ephemeral container **without the Android SDK,
NDK, or emulator**, and generally without network access to Google's Maven/SDK manager. This means:

- Do **not** attempt `./gradlew`, `./build.sh`, or any Android build/test task here — it will fail
  or hang on missing `ANDROID_SDK_ROOT`, missing NDK, or network-restricted dependency downloads.
- Do **not** try to install the Android SDK or download it in this container to "make it work" —
  it's not a supported path for this environment.
- Verify changes by **reading the code carefully** (types, imports, resource references, XML/Java
  syntax) instead of compiling. Cross-check callers/usages with Grep before assuming a change is safe.
- The **real build/verification happens in GitHub Actions** after you push (see below) — that's the
  first point a change actually gets compiled, so treat CI as your test loop for anything you can't
  verify by inspection alone.
- If a task genuinely requires a local build to verify (e.g. suspected native/CMake issue), say so
  explicitly rather than claiming the build passed.

## After committing: check GitHub Actions, and fix failures

This repo builds on every push via `.github/workflows/build-apk.yml` (which fans out to
`build-abi.yml` for arm64, and armv7 only on `master`). After you push a commit or branch:

1. Look up the workflow run for your branch/commit (`mcp__github__actions_list` with
   `method: list_workflow_runs`, filtered by branch, or `mcp__github__list_commits` to confirm the
   pushed SHA) — owner/repo is `yusairiyap/zrauto` (this session's scope).
2. Builds take several minutes (whisper's native CMake step alone is ~5-7 min per the comment in
   `build-abi.yml`), so it won't be done immediately. Use `ScheduleWakeup` to check back periodically
   (a few minutes apart) instead of polling in a tight loop or sleeping inline.
3. If the run **fails**: pull the failing job's logs with `mcp__github__get_job_logs`
   (`failed_only: true` for the run, or a specific `job_id`), diagnose the root cause from the log
   output, fix it in code, commit, push, and re-check the new run. Don't guess blindly — read the
   actual error (compile error, resource conflict, lint/test failure, etc.).
4. If the run **succeeds**, you're done — report that CI is green.
5. Never bypass a failure by disabling the check, skipping a module, or force-pushing over history to
   "reset" CI — fix the underlying cause.

## Local video / playback pitfalls (learned the hard way)

- **SurfaceView, not a normal view.** Local video (ExoPlayer/MediaPlayer/VLC) draws into the two
  `SurfaceView`s of `VideoView` (children 0 and 1). A SurfaceView reacts only to its *own* visibility
  and never follows a parent's alpha: fading/hiding the video pane does not reliably reach the
  surface, and a surface kept alive in a hidden pane could come back black (frames drawn, pane up,
  sound playing). So: fade the picture with the black `fadeOverlay` view on top
  (`VideoView.fadeInFromBlack/fadeToBlack/liftBlack`), never with the pane's alpha; and anything that
  brings local video back should get fresh surfaces (`VideoView.recreateSurfaces()` then
  `onSurfaceCreated`, as `MusicPlayer.switchToVideo` does). YouTube plays in a WebView and fades fine.
- **Don't switch ExoPlayer's video track back on mid-play** to bring a picture back (music -> video):
  it once raced through the rest of the file with a black picture. Restart the item at its position
  with the picture on instead (`MusicPlayer.startTrack(.., true)`).
- **SubGen gates the sound.** With on-device subtitle generation on, `AudioTranscriptProcessor` only
  releases audio Whisper has read, so a slow/throttled Whisper freezes playback (READY, position
  stuck). `PendingLoadAudioProcessor.setBypass` takes it out (applied at the next flush): Music tab
  tracks always bypass it, and the Exo stall watchdog (`checkStall`) bypasses it on a stall.
- **Item-scoped state.** Session metadata is published per item (`publishMetadata(m, item)` drops
  stale ones; `metadata`/`metadataItem` is what the subtitle callback re-publishes). Music tab "watch"
  requests are tied to their track (`MusicPlayer.StartRequest`).
- **Fullscreen controls overlay.** Every `VideoView` (YouTube's too) carries a `VideoControlsOverlay`
  on top: title, prev/play-pause/next in the middle and the double tap seek feedback. `ControlPanelView`
  decides when it shows (`showVideoControls`): the buttons always together with the panel, the title
  only on a single tap; a double tap seek hides them all (`hideVideoUi`) and shows only its feedback.
  While buffering, the play button shrinks away for the loading circle (YouTube's own, or the overlay's).
  The floating buttons' look is all in `FabPillView`: by default (`FAB_PILL`) one pill painted behind
  whichever buttons show (buttons flat, side by side, not draggable), else separate round buttons; over
  fullscreen video the dark see-through look with white icons. No shadows on see-through buttons: the
  theme's black outline shadow showed through, so `noShadow` drops the outline itself.
  The overlay takes every touch that misses its buttons and hands it to `VideoView.onTouchEvent`, which
  also keeps taps off YouTube's page (its own player controls are hidden in fullscreen by CSS, see
  `YoutubeWebView#hideFullscreenControlsJs`). In fullscreen the panel hides its whole transport row
  (seek bar line only, plus a bottom gap for the gesture area). A double tap streak makes one seek when
  the taps stop (`applyPendingSeek`): a seek per tap paused local video.
- **Don't change visibility from a layout callback.** A view shown/hidden inside its parent's
  `onLayoutChange` asks for a layout the parent never gets (it still counts as mid-layout), leaving stale
  positions on screen (the Music tab chips row had a hole and a clipped more button for that). Post the
  change and `requestLayout()` the parent (see `MusicPlayerFragment#updateEffectsChip`).
  The reverse trap: an `onLayoutChange` listener fires on *every* layout of its view, not only when
  its bounds change, so anything it (or a `post` from it) does that calls `requestLayout()` again,
  even setting the same compound drawable or text, loops a layout every frame. The Music tab once
  drew ~240 mA more than other tabs (paused too), very likely because the chips row's listener reset the Favorite chip's
  icon each time; only touch views there when the value actually changes.
  Same family: anything animating nonstop (an endless marquee) redraws the whole screen every frame,
  so the Music tab title scrolls only `marqueeRepeatLimit` times (tap to scroll again), and a text
  that changes every second (the timer countdown) gets a fixed width so it doesn't relayout the block,
  which also restarted that marquee each second (`fixCountdownWidth`).
- **Grid vs list** for media lists comes from the grid preference, never from `isGridView()` (which
  asks the active tab and is wrong while tabs switch).
- Known open items: a YouTube hand-over
  whose page never starts leaves the local engine muted with no error.
  **Open: an occasional micro stutter at the start of a YouTube song on the Music tab** (play, tiny
  pause, play; never seen in fullscreen video, seeking is fine). Already ruled out or fixed: router
  navigation (Music swaps in the player), quality switched while the song starts (now set before the
  swap; `YT media quality` lines log any switch), the fade holding the start silent, Web Audio when no
  effect is on. Still possible: YouTube's own player or the WebView's audio start (the log once showed
  the playback position jumping ~50-80 ms ahead in the first 1.5 s on the plain path), and the effects
  path (Web Audio) on Bluetooth (`out=` ~400 ms). Untried ideas: compare against m.youtube.com in
  Chrome, and skipping with the screen off (to rule the Music tab's own UI work in or out).
- **Background polls.** The end-of-track fade watcher (`MediaEngineBase#endWatch`) sleeps until the
  last seconds (at most 1 s between looks, 150 ms near the end); the control panel's and the Music
  tab's progress polls skip their work while their window isn't visible (for YouTube each tick is a
  script run in the page). Keep new periodic work off when nothing is on screen.
- **A local engine replacing YouTube** must silence the page (`MediaSessionCallback#playPreparedItem`
  calls `yieldToLocal()`; the page's `close()` is inert on purpose). After that the page may not take
  the session back on its own (`YoutubeMediaEngine#yielded`, checked in `playing()`): only a video the
  app asked for (pending id), a tap on the page, or play pressed with the YouTube tab in front.
- **YouTube queue skips in music mode, or with the page hidden** swap the video inside the player
  (`loadVideoById`), not through YouTube's router: a hidden page's router navigation is partly
  deferred until shown, which restarted songs on return to the app and left Android Auto's Music tab
  stuck on the loading circle. `YoutubeMediaEngine#watchSwitch` retries a switch that never plays.
- **YouTube quality is never switched on a video that is playing** (`applyQualityPolicy(target, now)`):
  by itself (a new song in music mode): the player reloads it for that (heard as the song starting
  twice). The lowest applies as each video is set up or buffers (and YouTube's stored
  `yt-player-quality` is set to 144p); the user switching into music or back to video switches the
  playing song at once, as they expect. In music mode the swap sets the lowest range *before* `loadVideoById`, and a switch is never made while a song is starting (`kept ... at the start` in the YT media log): one then made the player load the song again, heard as play, pause, play, only on the Music tab. A page pause during a switch is ignored
  (`YoutubeMediaEngine#isSwitchPause`): rapid skips used to pause the session. `youtube_fade.js` takes a volume YouTube sets while a fade
  holds the level down (it does per video, on load) as the target and puts the held level back. A new video fades
  in from `loadstart` (`startFade`: from 25% over 0.5 s of its playback time, even in dB (`rise`); the switch's fade out stays 300 ms, 150 felt abrupt, never from silence): holding it at zero until
  `playing` cut off each song's first moment.
- **YouTube Effects route the page's sound through Web Audio** (`createMediaElementSource`, irreversible per
  element), which can make a new video's first second choppy on a phone. `youtube_equalizer.js` only takes an
  element over when an effect really changes the sound (`anyEffectEnabled` checks values, not just switches), and
  with effects turned off afterwards the next queue switch (not in fullscreen) loads a page instead (`routedButIdle`, the
  'reload' result of `navigateToVideoJs`) to get the plain path back. `fade=gain` vs `element` in the YT media log.
- **A YouTube video can stop at its very end with no `ended`** (the last piece of the stream never arrives, seen
  with the screen off; YouTube's player then pauses the element itself, or it just waits until the stall check plays
  a download): `endCheck` in `YoutubeWebView#attachListeners` (on `pause` and `waiting` in the last 5 s) reports it
  as `JS_VIDEO_ENDED` with `'stuck'` when no real `ended` or progress follows within 2 s
  (`YoutubeMediaEngine#stuckAtEnd`, skipped for a pause the app asked for), well before the 10 s stall, and `play()`
  rewinds a video stopped within 1.5 s of its end. A third form: YouTube's player ends it itself, back to 0:00 under its
  replay button (player state 0) with no `ended`; `endCheck` reports that state at any position (`stuck:state`), and
  `play()` restarts it through the player API (`seekTo(0)` + `playVideo()`), since the element's `play()` just waits there. Before, Repeat One stopped there and play only buffered until the stall fallback.
- **A page restored after the app was killed** (the fragment's saved `url`, with YouTube not playing then) is kept
  quiet until asked (`YoutubeMediaEngine#quietRestoredPage`): its autoplay used to start the old song on app open.
- **Previous / Next go nowhere past the ends of a list**: on the first / last song of the Music queue (its
  play order), a playlist or Favorites with Repeat off (`MediaSessionCallback#isAtListEdge`; for YouTube, the
  engine's queue item; a playlist/Favorites with Shuffle on never counts) they are dimmed and take no taps in the
  control panel (`FermataServiceUiBinder#updateSkipButtons`), the fullscreen overlay
  (`VideoControlsOverlay#setSkipAvailable`, via `ControlPanelView#syncVideoControls`) and the Music tab
  (`updateSkipButtons`, from `updateModes`); the cover swipe there only gives a little and springs back (`canSwipeTrack(next)`); the session leaves them out of its actions (`withSkipActions`,
  so Android Auto and the system media controls follow) and `onSkipToNext/Previous` ignore them from media
  buttons, instead of fading the song out and back in. The queue does not go on with YouTube's suggestions.
  The "end" placeholder between videos shows the video just over (`Placeholder`), never its made-up address.

## Long/exhausting bug investigations: ask for adb logs

**Note: the user currently has no adb access.** Their only window into a running app is the in-app
**Diagnostic log** (`fermata/.../util/DiagnosticLog.java`, shared from the app). So when you need
runtime evidence, add `DiagnosticLog.log(...)` lines for exactly what you need (under an existing
category such as STATE/TRANSPORT/ENGINE/BODY/MUSIC), push, and ask the user to reproduce and paste the
Diagnostic log; don't hand them adb commands. The adb examples below only apply if that changes.

This session cannot run the app, attach a debugger, or capture logcat — there's no device/emulator
and no adb here. If an investigation is dragging on (a bug can't be root-caused from reading code
alone, a crash report is missing detail, or you're going back and forth on hypotheses without a way
to confirm one), **stop guessing and give the user a concrete `adb` command to run on their own
machine/device**, then continue once they paste back the output. Examples:

- Full logcat while reproducing, filtered to this app's tag/package:
  ```sh
  adb logcat -c && adb logcat | grep -i fermata
  ```
  (or `adb logcat --pid=$(adb shell pidof -s me.aap.fermata)` once the app is running, adjusting the
  package id if a build-flavor/suffix like `APP_ID_SFX` is in play)
- Crash-only output:
  ```sh
  adb logcat *:E AndroidRuntime:E
  ```
- Save a full log to a file for pasting/attaching:
  ```sh
  adb logcat -d > fermata_log.txt
  ```

Tailor the filter/tag to what's actually being investigated (e.g. a specific addon's log tag, or
`*:F` for fatal-only) rather than always using the generic examples above. Ask for the log **before**
proposing further speculative fixes once a couple of code-reading passes haven't settled the cause.

## Pull request descriptions

The PR template is `.github/pull_request_template.md` (sections: Description, Type of change, Changes made,
How to test, Checklist, Related issues, Screenshots; modelled on the gitmore.io PR template guide). The
**Description** is broken down by feature or area with a short heading each, as in the Ambience PR.

- Describe the **branch as a whole, in its end state**: what the reviewer gets compared with `master`.
  Do **not** list every bug fix made along the way, especially fixes to something this same branch
  introduced (a compile error in new code, an overflow in a new function, a tweak after the user's feedback
  on an earlier iteration). To a reviewer those never existed; they only see the finished feature. Mention a
  fix only when it changes something that already exists on `master`.
- Keep it current: **whenever you push to a branch that has a PR, rewrite the PR description to match the
  branch now** (`mcp__github__update_pull_request`), so it is right before anyone asks for a review. When the
  user has no PR yet, keep the finished description ready (in your reply, or as soon as the PR exists) so it
  is never written after the fact. Never create a PR unprompted, but once one exists, keep it updated without
  being asked.
- Follow the template's headings; delete a section only if it truly does not apply. Say plainly what was not
  run (this cloud session cannot run the app) rather than ticking "verified on a device".

## When in doubt, ask

This is someone's personal Android project (not a work codebase) with real users on the release
channel. If a task is ambiguous, touches release signing/keystore config (`res/fermata.jks`,
`res/build_jks.gradle`, CI secrets), changes what ships in `master`/tagged releases, or could affect
translated strings across many locales, **ask the user before proceeding** rather than guessing.

## App sharing, session data limit, offline fallback, seek preview

- **Share zrAuto app** (Settings > Other): `ShareSource#createApp` offers the installed base APK as a
  `ShareOffer.KIND_APP`; the receiver keeps it in `Shared/<title>` and `ShareImporter#offerInstall` asks via
  `TopConfirm`, then opens the installer through the Auto flavor's FileProvider (the mobile flavor has none, it
  just says where the file is). Split installs (app bundles) can't be shared. Once installed, the received file is
  deleted at the next app start (`share/ReceivedAppCleanup`, from `FermataApplication#onCreate`): an `.apk` under
  `Shared/*/` that is byte for byte the installed `sourceDir` APK; any other `.apk` is left alone.
- **Android Auto session limit** (`addon/data/DataSessionLimit`): a session runs from Android Auto connecting
  to disconnecting (car activity, mirroring, or the phone's car UI mode), polled with each
  `DataUsageTracker` reading, counting what it books (mobile only when the tracker counts mobile only). Set on
  the Data usage tab's card; past the limit a `TopConfirm` offers `OfflineFallback#playDownloaded`. Info
  overlay item `data_session` (mini bar and used/left in its row menu, `INFO_OVERLAY_DATA_SESSION_*`).
- **OfflineFallback** (`ytdl/`): next downloaded item of the playing playlist/Favorites, else the Music
  queue's offline track, else all downloads. `ON_STALL` (Settings > Playback) makes YouTube stalls use it
  with a toast instead of `NetworkIssuePopup`.
- **Seek preview** (`ui/view/SeekPreview`, hooked into `FermataServiceUiBinder`'s seek bar): YouTube via
  storyboard sheets (`ytcaptions/YtStoryboard`, spec from the player request, 80 px pictures on metered
  networks), local/downloaded video via `MediaMetadataRetriever`. Never run against live YouTube from the
  cloud session; check the Diagnostic log (SEEK).
- **Comments panel** shows views/likes from the `next` response (`YtComments#views/likes`, best effort).
- **Download picker** shows sizes per quality (`DownloadsAddon#estimateSizes`, one player request per video,
  up to 10 videos).

## Blank screen on a clean install (solved)

Symptom (phone, app data cleared or a fresh install): bars but no content, no welcome wizard. A temporary start log written to
Downloads showed the first-tab callback throwing a `NullPointerException` inside `HomeScreen.<init>`: `NavBarMediator#getLayout`
called `Arrays.asList(null)`, because `NAV_BAR_*` string-array prefs (`Pref.sa(..., null)`) are null until saved, and the Pages
widget (`PagesWidget#fill` -> `NavBarMediator.getTabs`) is in Home's default set. The exception escaped into a future's completion
handler, so nothing was shown and nothing crashed visibly. Fixed by treating a null saved layout as empty; confirmed on the device,
and the start log is gone. The lesson: a string-array pref read can be null; never pass it to `Arrays.asList`. And a failure inside a
future's completion handler is silent: if a start path shows nothing, look for an exception swallowed there first (the temporary
fix for that was a log written to a file a file manager can reach, e.g. via MediaStore Downloads).

## Back closes the YouTube comments, but the video pauses (tested OK on device)

Status: the page-side consume (the first hide returning early, and its time guard) was reverted, so Back with
the comments open once again leaves fullscreen (and the column closes with it); the comment view's own Back
handling (`YoutubeCommentsView.dismissOpen`, search first) is kept. The notes below describe the reverted
attempt, and the pause question still stands for the plain fullscreen exit.

Symptom (reported on the device, several times): with the comments open beside fullscreen video, the Back
gesture closes the comment column as intended, but the video playback pauses (the frame stops, the audio
may go on). Fullscreen is also left, which is what Back must not do.

What the logs show (`VIDEO` lines, added for this):
- The first hide of the custom view (`FermataChromeClient#onHideCustomView`, comments open) is consumed:
  `YoutubeCommentsView.dismissOpen()` closes the column (search line first), and the method returns without
  calling the custom-view callback, so the page stays fullscreen.
- About 2.4 s later a second `onHideCustomView` arrives with the comments already closed
  (`VIDEO hide custom view comments open=false ms since a back closed them=2370`). That one runs the real
  exit (`exitFullScreenUi` -> `setFullScreen(false)` -> `VIDEO left fullscreen`), so the page leaves
  fullscreen, which is the playback-pause the user sees.
- Nothing in our code requests the second call, so it is most likely WebView/Chromium redelivering the back.
  An 800 ms guard missed it; 3000 ms was added in `FermataChromeClient` (`BACK_REPEAT_MS`). That covers the
  observed gap but also means a Back pressed within 3 s of closing the comments does not leave fullscreen.

Not yet checked:
- Whether the pause comes from the page's own exit (YouTube pauses when leaving its fullscreen element) or
  from something of ours (`YoutubeFragment`/`MusicPlayer` reacting to the fullscreen change, the dock's
  `watchDock`, the video's `setSplitShare` transform). Look at the `VIDEO` and `YT` lines around the pause.
- Whether the redelivered back can be ignored more precisely: for example, by remembering the custom view
  that was kept, and treating a hide for that same view as the echo of the consumed back only while the
  page is still playing, instead of a time window.
- Whether a consumed back should also re-run the page's own fullscreen request (the
  `YoutubeFragment#enterVideoFullScreen` path), so the page stays fullscreen with its video playing.

For the next session: ask for the Diagnostic log from one Back press with the comments open (the `BACK`,
`VIDEO` and `YT` lines around it). Do not treat a longer time window as the fix; find the echo or the
pause source.

## Captions box, preview position, data banner colour

- **Captions background fits the text** (`CAPTION_BOX_FIT`, default on, Settings > Captions > Background): the box is as wide as
  the longest line (`CaptionsOverlayView#textW`) instead of the whole text area (84% of the picture); the text itself stays centred
  where it was (`textLeft`), only the box changes.
- **The preview on Captions / Lyrics settings rises with the tool bar** (`TextStyleFragment#previewSync`): it sits under the tool bar
  while it shows and slides up to just under the status bar by `ScrollBarsController#getToolBarHidden()`; at once when the tool bar is
  gone. The list's own padding still never follows the bars.
- **Data warning / limit banner colour**: `DataUsageAlerts` built the banner inside a `TopPopup.holder` (a swipe layout around the card)
  and tinted the holder, which has no background, so the card stayed white under white text. The tint goes on the holder's child.

## Wizard first, restore keeps Home, restore page in the wizard

- **The wizard comes up at once on a fresh start** (`MainActivityDelegate#onActivityCreate` calls `WelcomeWizard.maybeShow` in the
  permission callback, no 1.2 s wait; `show(a, instant=true)` puts it at full alpha from its first frame; retries are 150 ms apart). Home is
  still built behind it, so nothing about the first tab changes, it just is never seen first.
- **A restore must not be overwritten by Home**: `HomeModel` keeps its own copy of the layout JSON (widgets, sizes, per-widget options, each
  arrangement's background) and `HomeScreen#onDestroyView`/`onHidden` save it, which wrote the old layout back over the restored one while the
  app closed. `SettingsFragment#importStream` calls `HomeModel.freeze(true)` before it writes the backup's prefs (and `false` if that fails), and
  `HomeModel#save` does nothing while frozen. Any other model that caches a pref's value and writes it back on stop needs the same treatment.
- **Restore from the wizard** (`WelcomeWizard#restoreBackup` -> `SettingsFragment.importPrefs(a, onRestored)`): instead of the plain
  "restored, the app closes" message the wizard goes to a page of its own (`restoredPage`, shown alone: `restored` makes `active()` return only
  it) saying the app has to restart; its button calls `Utils.restartApp` (starts the launch intent with NEW_TASK|CLEAR_TASK, then ends the
  process 300 ms later). Not run on a device. Back does nothing there. The file-picker route (no system picker) keeps the old message and exit.

## Home: comments, search, stack manager, timer picks, widget scale

- **YouTube comments widget** (`ui/home/widget/CommentsWidget`): the top (or Newest) comments of the playing video, from `YtComments`.
  `YtComments.firstPage(videoId, newest)` is the one first-page path: the comments card's Newest is the same request (load, then the
  `sortNew` token). The header or a row opens `YoutubeCommentsView.showForCurrent`. Options: "Which comments", "Profile pictures".
- **YouTube search widget** (`ui/home/widget/SearchWidget`): typed text gets `YoutubeSearch.suggest` chips, an empty field gets the past
  searches (`MusicPlayer.youtubeSearchHistory`, which follows "Remember searches"; a long press removes one). Enter, the button or a chip
  calls `MusicPlayer.searchYoutube` -> `YoutubeFragment.searchFor`: the tab fades in, `startSearch(false)` opens the panel the way the search
  button does, and the query runs `YoutubeSearchPanel.SLIDE_MS` later. The layout follows the card's width in dp (`ICON_BELOW_DP`,
  `CHIPS_IN_STRIP_DP`) and height (`chipRoom`): one unit high = input plus one row of chips; two rows fit = wrapped, else one scrolling row.
  On the car screen the field is not focusable; a tap opens the panel. **Keyboard**: the page glides up with it (`HomeHost#followKeyboard`, see
  "Start-up quiet, playback calm, search from Home" and item 3 of the list below).
- **Widget size and space** (per screen, Settings > Home): `HOME_SCALE` (40-150%) and `HOME_PADDING` (-1 = automatic, else dp, both sides, centred).
  `HomeGridLayout#setScale` and `onMeasure`: the card size is `base * scale` and the lanes (columns, or rows sideways) are as many as fit at
  that size (`lanes`, capped by `MAX_LANES`); `colLead` centres the leftover when scrolling down. Freeflow scales its places
  (`placeFree`/`dropFree` store positions at scale 1). `HomeLayoutPreview` mirrors the same rule. `arrange()` clamps spans to `lanes`.
- **Sleep timer picks** (`TimerWidget`): option "Pick the items" (a chip card): the lengths shown, or "Recommended" (`RECOMMENDED`),
  which ranks lengths by how often each was started (`uses`, counted per widget). `minutesToShow` thins the pool to the slots that fit.
- **Stack manager** (`ui/home/HomeStackManager`): card menu > "Manage stack" (below "Take out of the stack"). A `RecyclerView` in a
  `HomeModal`: `ItemTouchHelper` drag by the handle (lifted, rows move; `HomeModel#moveInStack` keeps the top item on top), the minus removes
  one with Undo (`HomeModel#remove`/`restore`), and "Add a widget to this stack" opens `HomePickers.showAdd` (the Home add picker), then the
  window reopens. Each change calls `HomeGridLayout#stackEdited` (sync, changed, save).
- **Library back navigation** (`LibraryWidget#backOut`): the rows and the back chip change only while the list is hidden; the chip collapses
  without an animation (`showBack(on, false)`), so the list does not slide as it fades back in.
- **Diagnostics**: the comments loader no longer logs (the old COMMENTS/YTCOMMENTS lines were removed). The keyboard lift logs `HOME keyboard lift`.
- **Device status**: tested on the tablet and working: the comments widget, the search widget (except the keyboard, open below), the timer chips,
  widget size and space around widgets. Not run on a device yet: the stack manager and the add picker, the UI style on the car screen.

## Start-up quiet, playback calm, search from Home

- **Home's widgets read the library only after the start** (`HomeHost#isSettled` / `whenSettled`, `HomeScreen#settleAfter`): the
  start counts as over when Home has faded in, 400 ms later and once the main thread is idle (`STATE home settled`); then the waiting
  widgets read one at a time, 70 ms apart. Until then `LibraryWidget#refresh` only shows its skeleton (`awaitingStart`), and the
  Comments and YouTube suggestions widgets hold their network reads. The rows replace the skeleton with a short fade. Process-wide:
  once settled, Home made again later reads at once. A new widget that reads lists or the network at bind should do the same.
- **`util/PlaybackCalm`** (fed by `MediaSessionCallback#setPlaybackState`): steady = the song has played 3 s without a buffering
  or skipping state. Background work that only makes things nicer waits for it on its own thread (`awaitSteady`, never on the main
  thread): the video-frame capture (`BitmapCache#readOrCaptureFrame`, its thread at background priority), captions (the next song's
  fully, the playing song's at most 2.5 s; `CaptionsHub`'s thread at background priority) and the queue's channel look-ups.
- **Home waits 1.5 s into a song before reacting to it** (`PlaybackCalm#afterStart`, `START_QUIET_MS`; 0 takes it out): the media card's
  cover, the "cover of what plays" background and the lists marking the playing song (Library widgets, Queue). The title and artist change
  at once. That decode/blur/cross-fade burst landed on the song's first moment, the only place the stutter is heard and only on screens that
  react to a new song (never fullscreen video). The current song's captions wait until it is steady too (3.5 s at most). Every song start
  writes `STATE start load: main late max=..ms over50=.. gc=+.. blocking=+.. (..ms)`: how late a 100 ms main-thread tick came at worst
  and the garbage collections in its first 3 s. A stutter with big numbers there is the app's own work; with small ones, the page.
- **A cover missing on Home's card while the Music tab had it** (videos with no maxresdefault.jpg, the 480x270 ones): the session, Home and
  the Music tab downloaded the same picture into the same file at once, and the one that found YouTube's grey placeholder deleted the file
  while another still read it; that one failed with no fallback. `BitmapCache#loadHttpBitmap` now shares a download under way
  (`httpLoads`), and a maxresdefault.jpg that does not decode falls back to hqdefault.jpg. Home logs `ART no cover for Home` if it still gets none.
- **Seeks while a song starts are traced**: the page's `YT media seeking t=` (where it went) and `YT seek while starting` with the two
  callers when the app asks for it; a start that took long is traced for 5 s after its `playing`.
- **Home's media card asks the player for its position every 3 s**, not every 500 ms tick (for YouTube each ask is a script in the
  playing page); in between it counts on from the last answer, and asks again after a seek or a play/pause change.
- **Its title ends in an ellipsis** unless the card's "Scroll a long title" option (`marquee`) is on: a scrolling title redraws every
  frame at Home's high refresh rate. The eye (hide cover) button takes the same fill as the Watch / Fullscreen chip in `applyLook`.
- **A search from Home never lands in fullscreen** (`YoutubeFragment#searchPendingUntil`, `holdsFullScreen`): a freshly made YouTube tab
  restores the last video, and its playable change put the page in fullscreen over the panel. While the panel is open, or for up to 4 s
  until it opens, `onPlayableChanged` and `enterVideoFullScreen` do not go fullscreen (`YT fullscreen held`); a freshly made tab also
  waits 350 ms more before the panel slides down, so the WebView's set-up and the slide don't stutter together.

### Second round (after the first device test)

- **The keyboard lift works every time**: a field that kept the focus after the keyboard was closed with Back gets no focus change on
  the next tap, so `SearchWidget`'s click calls `followKeyboard` too.
- **Only the first rows fade in over the placeholders** (`LibraryWidget#render`, `fromSkeleton`; never while drilled in or backing out),
  so opening and going back keep their own fades. `util/TouchState` (fed by `MainActivity#dispatchTouchEvent`) says a finger is down (or
  lifted under 350 ms ago): the start's queued reads wait (`HomeScreen#runNextSettled`) and `HomeSkeleton` stops redrawing its shimmer.
- **The media card**: the artist line goes through `MusicTrackItem#cleanArtist` (no " - Topic"); a wide card with under 300dp beside the
  cover (`TIGHT_DP`, the phone's 4x2) starts its words and controls at 30% of the width over the cover's fading side and shows the chip
  as its icon only (`narrowChip`), so the title, the controls (shuffle and repeat beside them) and the seek bar fit.
- **Share zrAuto** is also in the main menu, above About (`NavBarMediator`, `R.id.nav_share_app`; not on the car screen).
- **A song downloaded as sound only can still be watched** (`MusicTrackItem#isAudioOnlyDownload`): the Music tab's Video button, the
  Watch chip and Play as video stream it from YouTube at the usual quality from where it is (`MusicPlayer#switchToVideo` ->
  `YtOffline.forceStream` + `startTrack(.., watch)`); Play as music goes back to the file from the video's position
  (`playCurrentAsMusic`, `YtOffline#isForcedStream`, `clearForcedStream`).

### YouTube start gate (against the stutter at a song's start; easy to take out)

`modules/web/src/main/res/raw/youtube_startgate.js`, injected first by `YoutubeWebView#injectStartGate`, YouTube setting "Wait for a song to
buffer before it starts" (`YT_START_GATE`, on by default). It wraps `HTMLMediaElement.prototype.play`: the first `play()` of a video after
its `loadstart` is held (the promise stays pending; nothing is paused, no event is made up) until 2 s are buffered ahead of the playback
point, or 1 s with `readyState` 4, or 3 s have passed. A resume, a seek or a later play is never held. `youtube_fade.js` leaves a held
element alone (`__fermataGate`: not "loaded but left paused"). The `YT media` trace has `start gate: holding / open after N ms` lines
for the first 8 s after a switch. **To turn it off**: the setting. **To take it out**: delete the script, `YoutubeStartGateScript`, the
`injectStartGate` call and the one `__fermataGate` check in `youtube_fade.js` (all in one commit). If a start ever hangs, look there first.

What the first device log showed: YouTube's player does not start a queued video through `play()` on the element (`loadVideoById`
starts it inside), so the held `play()` never engaged on a skip. The likely stutter is another thing: **YouTube's player pauses the
element a moment after a new video starts**, and the app heard of it over the bridge and played again (`YoutubeMediaEngine#paused`'s
retry, now logged as `YT page paused right after play: play again`; when the retry did not stick it showed "Window too small to play
video"). The same script now undoes such a pause in the page itself, in the same task: within 1.5 s of a video's first `playing`, a pause
that is not the app's (`FermataFade.pause` marks `__fermataAppPauseAt`) and not a tap (`navigator.userActivation`) is followed by `play()`
at once, and that `pause` and the `play` after it are stopped at the window (capture) so neither the app nor the fade nor YouTube's own
listeners see them (at most 2 per video). Trace line: `start gate: the page paused the start, played on at once`. The `YT media` trace
now also writes `pause` / `play` events and progress gaps over 400 ms.
- **Forced streams are respected by the YouTube engine**: `YoutubeMediaEngine#playing` sends a downloaded video to its file only when
  `YtOffline.useLocal` says so (a sound-only download watched streamed changed quality and was sent back to the file, then refused:
  "Not downloaded, and there is no connection"); a stall of a forced stream clears it and plays the file. Watching a sound-only download
  turns music mode off before the page sets the video up, so it gets the usual quality, not 144p.
- **A start that says `playing` but does not move** (a device log: a song held at 0:00 for 5 s after `playing`, then `waiting`
  and a second start): `youtube_startgate.js#watchStart` watches the clock (not `timeupdate`, which stops while stuck) for 8 s
  after a video's first `playing`; stuck 700 ms with data buffered, it seeks to where it is (at most twice, setting on), else
  only traces `start gate: no progress at t=`. The `YT media` trace now also writes `loadstart` and `emptied` (a reload).
- **Music mode reaches the page while a file plays** (`YoutubeHooks#applyQuality(a, eng)`): with a downloaded file as the player,
  the page's engine still gets the new quality policy, so a sound-only download watched streamed gets the preferred quality, not
  music mode's 144p. Back to music from it: the file starts at the video's position first, music mode follows 1 s later
  (switched while the page still played, its quality reload handed the song to the file from 0:00).
- **Captions are not asked for over and over** (`CaptionsHub#failedAt`): a video whose captions could not be had is not asked
  again for 5 minutes (the buttons' `isDimmed` asked on every redraw and a failure was never kept, so the same four requests went
  out every ~1.5 s); an app YouTube asks to sign in is left out for 30 minutes (`YtCaptions#signInUntil`). **The YouTube page is
  asked first** (`YtCaptions#fromPage` -> `MusicPlayer.youtubePageCaptions` -> `YoutubeHooks#pageCaptions`): when the page has that
  video, its player response's caption tracks are read and the json3 fetched inside the page with its cookies
  (`YoutubeWebView#pageCaptionsStartJs` / `pageCaptionsResultJs`, polled 4x/s for up to 6 s). `CAPTIONS PAGE` lines say what it got;
  if they say `empty track`, YouTube wants its proof-of-origin token on the request too (not done).
- **A stale "the app paused it"** (`YoutubeMediaEngine#appRequestedPause`) is cleared when the page pauses while another engine owns
  the session (the pause that silenced it) and when a switch starts: left set, a pause the page made during a song switch after
  coming back to the app counted as the app's, `isSwitchPause` let it through and the session paused (silence until play was pressed).
- **The keyboard lift ends where the animation left the keyboard** (`ImeFollower#onEnd`, `lastIme`), not on the window insets, which can
  still report the keyboard right at the end of a Back-swipe hide (the flicker).

## Open issues (handover list)

These are the open items to take up next, in a new session or on a new branch. Each is a prompt: the first line says what is wrong.
Items marked resolved were tested OK on the device; the numbers are kept so references stay stable.

1. **Next plays the downloaded copy and the queue collapses to one track** (rare now). Likely cause fixed: `MusicPlayer#indexOfSame`
   matched only by id, and a Favorites / playlist entry playing from its downloaded copy points at the file by then, so it was not
   found in its own list and the queue was made of that one song. It now also matches by `getOrigId()` and the YouTube video id, and
   logs `MUSIC item not found in its list: a queue of one` when it still falls back. If it happens again, read that line and the
   TRANSPORT / YTDL lines around the Next press.
2. **The YouTube page reports `ended` about 10 s into a song, right after the app paused it.** The log shows `YT paused (app asked)` and then
   `YT video ended` with no `stuck` tag, so it is the page's own `ended` (`YoutubeWebView#interceptEndedJs`), not the end check. A guard
   that ignored it was tried and reverted; find what fires the event instead.
3. **The search widget and the keyboard**: reworked, not yet confirmed on a device. The window is not resized for the keyboard, and
   scrolling the page could not lift a card at the very bottom. Now `SearchWidget` calls `HomeHost#followKeyboard` on focus and
   `HomeScreen` translates the page layer (`fade`) up with the keyboard, frame by frame from a `WindowInsetsAnimation.Callback`
   (Android 11+), with settle checks for when no animation is reported; `HOME keyboard lift` lines say what it computed.
4. ~~**Media card art looks blocky on One UI**~~ **Resolved: not seen any more on the device.**
5. **A YouTube hand-over whose page never starts leaves the local engine muted with no error.**
6. **A micro-stutter at the start of a YouTube song on the Music tab** (play, tiny pause, play). See the "Open" note in the playback section.
   Being tried: the YouTube start gate (see "YouTube start gate" above). If the stutter is gone, mark this resolved; if starts hang or
   the stutter stays, the `start gate:` trace lines say how long each start was held and how much was buffered.
7. ~~**Back with YouTube comments open pauses the video**~~ **Resolved: tested OK on device.**
8. ~~**Rapid edit-mode toggling crashed on Android Auto**~~ **Resolved: tested OK on device.**
9. **A song sometimes does not continue to the next one** (fourteenth round). Needs TRANSPORT and YT lines around the end of the song.
10. ~~**Ambience audio focus on the car screen is unconfirmed**~~ **Resolved: tested OK on device.**
11. ~~**Never run on a device**~~ **Resolved: the items listed as never run on a device (Ambience, the glass blur and UI style, the power
    tracker, Share, YouTube comments and captions, the lyrics page, the welcome wizard's restore, the Home layout on the car screen, and the
    stack manager, timer picks and widget size on Android Auto) were tested OK by the user.**
