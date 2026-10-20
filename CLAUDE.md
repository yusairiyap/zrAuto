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
- **Chrome that hides**: the tool bar fades out (`HomeFragment.HomeToolBarMediator`), the control panel is *covered*
  (`ControlPanelView#setCovered`, the fade the YouTube search panel already uses, not suppressed, so the floating buttons stay),
  the background runs under the status bar through `BehindBarsLayers` (Android 15+) and the status bar takes the picture's top
  colour before that (`HomeScreen#systemBars`, restored with `MainActivityDelegate#matchStatusBarToBackground`).
- **Background**: `HomeBackground` copies the picked picture (system picker, no permission) to `files/home_bg.jpg`, shrunk to
  1600 px; `HOME_BG_VERSION` only says it changed. Blur is a small copy drawn large again (no RenderScript). Not in backups.
- **Car mode**: `CarNav#findScope` treats `HomeFragment` as a scope, which finds every visible view *with a click listener*.
  So: every control needs a click listener (the helpers add one), and **never put a click listener on a whole card or a row that
  contains other controls** (it would swallow them: split the row into separate clickable parts, as `LibraryWidget` does).

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
- Known open items: media card art can look blocky on One UI (1280x720 art); a YouTube hand-over
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
