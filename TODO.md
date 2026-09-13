# TODO

## YouTube: true background autoplay/continuation (needs architecture redesign)

**Status as of 2026-09-14 (branch `claude/youtube-pause-ihu-camera-um859s`): partially mitigated, not solved.**

### What works today

While the app is backgrounded (screen off, or the user switched to another app):

- An already-playing video keeps its audio going fine — Chromium does **not** suspend
  a WebView tab's already-running media playback just because the Activity is hidden.
- The app's own decision logic for "what should play next" runs correctly even while
  hidden: `YoutubeMediaEngine.ended()` → `MediaSessionCallback.engineEnded()` →
  `YoutubeMediaEngine.queueAwareNextPlayable()` → `YoutubeMediaEngine.prepare()` all
  fire and resolve the correct next Favorites/Playlist item, confirmed via on-device
  log capture (see the git history on this branch for the full trace).
- Seeking (`MediaSessionCallback.onSeekTo()` → `YoutubeMediaEngine.setPosition()`) also
  decides correctly while hidden.

### What doesn't work, and why

`prepare()`'s actual page-mutating calls — `YoutubeWebView.next()`/`prev()`/
`loadVideo()` (video navigation) and `setPosition()` (seeking) — silently do **not**
take effect while the WebView is hidden. This was confirmed on-device: the Java-side
state moves on to the new video/position, but the page itself stays parked on the old
one, sometimes for 20+ seconds, until the app is foregrounded again. This is Chromium's
own background-tab throttling: it treats *starting a new video/seeking* differently
from *continuing an already-playing one* (the currently-audible tab gets an exemption
that a fresh player-state mutation doesn't).

**Current mitigation** (`YoutubeMediaEngine.runOrDeferPageAction()`/
`flushPendingPageAction()`, driven by `YoutubeAddon.isVisible()` /
`YoutubeFragment.onPause()`/`onResume()`): defers navigation/seek calls while hidden
and flushes them the instant the app is foregrounded again. This fixes the *wrong
symptoms* (getting stuck entirely, or skipping a video, or a seek landing unexpectedly
and pausing playback) — but it does **not** deliver what a user actually wants, which
is the video really auto-advancing to the next one while the screen is off, the way
audio-only playback in a normal media app would.

### Why this needs a bigger redesign, not another patch

The constraint is enforced by the WebView/Chromium engine itself, not by anything this
app's Java code controls. Making YouTube actually keep advancing through a queue while
backgrounded needs the app to either keep the WebView considered "foreground" by the
OS, or stop depending on the WebView's own throttled JS execution for playback state
transitions. Concretely, one of:

1. **Android Picture-in-Picture (PiP).** Keep a small floating video surface up when
   the user backgrounds the app, so Android (and therefore Chromium) still consider
   the tab visible/foreground. This is the standard way video apps get real
   background/multitasking playback, and it stays within the current WebView-based
   architecture. Real UI/lifecycle engineering effort (entering/exiting PiP, sizing,
   handling further backgrounding from within PiP, Android Auto interaction if
   relevant), but the most "native Android" answer.
2. **Hand off to a native player once a video starts.** Extract the actual playable
   stream for the current video and play it via ExoPlayer/VLC (both already integrated
   in this app and already capable of running headless via `FermataMediaService`,
   the same background Service other engines already use) instead of through the
   WebView, once given a video id. Sidesteps WebView throttling entirely. Bigger
   architectural lift than (1), and very likely raises YouTube Terms of Service
   concerns (stream extraction) that this project may not want to take on — evaluate
   that before investing engineering time here.
3. **Accept the current catch-up behavior as the practical ceiling** for a
   WebView-based implementation, and only invest further in shrinking the gap (e.g.
   surfacing to the user that a video finished and is waiting, rather than silently
   sitting there) rather than trying to eliminate it.

No option has been started. Whoever picks this up: talk to the user about which
direction they want (they were still deciding as of the date above) before writing
code — this is a real product/architecture decision, not just a bug fix.

### Relevant code for whoever picks this up

- `modules/web/src/main/java/me/aap/fermata/addon/web/yt/YoutubeMediaEngine.java` —
  `ended()`, `prepare()`, `runOrDeferPageAction()`/`flushPendingPageAction()`,
  `setPosition()`.
- `modules/web/src/main/java/me/aap/fermata/addon/web/yt/YoutubeAddon.java` —
  `isVisible()`/`setVisible()`.
- `modules/web/src/main/java/me/aap/fermata/addon/web/yt/YoutubeFragment.java` —
  `onPause()`/`onResume()` (visibility tracking + flush trigger).
- `fermata/src/main/java/me/aap/fermata/media/service/MediaSessionCallback.java` —
  `engineEnded()`, `onSeekTo()`.
- `fermata/src/main/java/me/aap/fermata/media/service/FermataMediaService.java` — the
  existing background Service other engines (VLC/ExoPlayer) already run headless in;
  relevant reference point for option 2 above.
