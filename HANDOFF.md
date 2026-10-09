# HANDOFF (one-time: delete this file with `git rm HANDOFF.md` once the pickup below is done)

Branch: `ccr-8b7dca94-medecy` (was `ccr-d32fd790-dpebv3`) (develop and push only here, no PR unless asked). Last CI-green commit
before this file: `5b479a0`; later commits on top are uncompiled until CI runs (check Build APK).
Follow `CLAUDE.md` (no local Gradle, CI is the compile loop, ask for adb/diagnostic logs when stuck).
The device is a Samsung SM-X736B (Android 16, One UI). The user tests on it and pastes the Diagnostic log.
**The user has no adb**: everything must come through the in-app Diagnostic log. For problem 3 it now also
logs activity resumed/paused/stopped (STATE), video surface created/destroyed (BODY) and ExoPlayer's own
non-user pauses / suppression (ENGINE `exo playWhenReady=... reason=`: 2 focus loss, 3 noisy, 4 remote).

## What this is
Offline YouTube downloads (Downloads tab, up to 1080p) that must play seamlessly mixed with live
YouTube (WebView) playback: fades, covers, Music tab, Favorites/playlists, notification/media card.
Local files play on ExoPlayer (fallback fresh Exo, then MediaPlayer); YouTube plays in a WebView engine.

## Why a fresh session is worth it
The remaining bugs are cross-cutting state problems (activity video mode, BodyLayout mode, YouTube's
own fullscreen, three different black covers, the session's engine/metadata), patched one at a time
for many rounds; several patches caused the next regression (e.g. my cover lift hid YouTube's cover,
a seek-restarted stall watchdog killed a slow seek). A stronger model with a clean context should
**design the fix as one owner of transitions instead of adding more patches** (see Plan). It still
cannot run the app: every fix needs the user's log, so keep rounds small and log-driven.

## Key files
- `fermata/.../media/service/MediaSessionCallback.java`: session brain. skipWithFade, skipTo,
  handOverYoutube, playPreparedItem, switchItem, onEngine* callbacks (now ignore engines that are not
  current), setPlayingState + `metaEpoch`, publishMetadata(m, item) (drops stale), fallback chain
  (`tryAnotherEngine`, `fallbackStage`, `MediaEngineManager.createAnotherEngine/freshExoTried`).
- `fermata/.../ui/view/BodyLayout.java` (FRAME/VIDEO), `ui/activity/MainActivityDelegate.java`
  (`videoMode`, `setVideoMode`, window covers: `fadeToBlack*`, `coverIntoVideo`, `coverUntilPlaying`,
  `liftLocalVideoCover`, `fadeInFromVideo`), `ui/view/VideoView.java`.
- YouTube: `modules/web/.../yt/YoutubeMediaEngine.java` (`yieldToLocal/leavePageForLocal`),
  `YoutubeChromeClient.java` (`leaveForLocal`, deferred fullscreen exit behind the transition cover),
  `YoutubeWebView.java` (`afterAudioFadeOut`, `cancelPendingSwitch`).
- Music tab: `addon/music/MusicPlayer.java` (static flags `youtubeAudioMode/watchRequested/watchingLocal`,
  `startingDownloadedTrack`), `MusicTrackItem.java`, `YoutubeStartEngine.java`, `ui/fragment/MusicPlayerFragment.java`.
- Local engine: `modules/exoplayer/.../ExoPlayerEngine.java` (stall watchdog, picture off/on,
  hidden dummy surface, `keepPicture`), `fermata/.../media/engine/MediaEngineBase.java` (fades, endWatch).
- Downloads: `ytdl/YtOffline.java`, `YtDownloads.java`, `ui/fragment/DownloadsFragment.java`.
- Diagnostics: `util/DiagnosticLog.java` (categories STATE TRANSPORT MUSIC BODY ENGINE META NOTIF YT YTDL
  INTERRUPT DIAG; header line has app version/device). Trim the noisy ones (NOTIF/META/BODY) when stable.

## Already fixed (do not redo; verify from logs only)
YouTube fullscreen to local no longer exits late; Downloads list no longer fights the tab crossfade
(never touch a fragment root view's alpha, the delegate animates it); span known before first layout;
stale async metadata dropped (epoch + item check); downloaded favourites carry artist/duration;
stale engine callbacks ignored; pending YouTube switch cancelled for a local takeover; fallback chain
bounded; stall watchdog not re-armed on seek; reused Exo no longer auto-plays; paused notification
below Android 13; diagnostic log trims in halves; YouTube cover not lifted early; loading circle
cleared when playing.

## Open problems (need device logs to confirm each)
1. **FIXED (pending confirmation)**: root cause was `MediaSessionCallback.metadata` only being set by the
   resume-restore path; the subtitle callback `accept()` re-published it (a restored item's) for any item
   with a subtitle stream. Now `metadata` is set with `metadataItem` in `publishMetadata(m, item)` and
   `accept()` ignores it unless it is the current item's. Old note: **Media card sometimes stale for local playback** (screenshot: card "Is There Really No Happiness"
   while Patient Lips plays; trace 22:11:50 `NOTIF built state=2 title=Is There Really...` with no
   preceding META line). Publisher still unknown; look for `META stale metadata dropped` lines now
   logged, that names the late publisher.
2. **Card art looks blocky for local items** (YouTube items look the same in some screenshots; art is
   1280x720 either way). Possibly One UI's own style; unverified. Idea: crop/scale a square notification
   large icon once per item.
3. **Root cause found (pending confirmation)**: SubGen (`AudioTranscriptProcessor`) only releases sound the
   transcriptor has read; when Whisper falls behind (background throttling) playback freezes with Exo READY,
   and the fullscreen picture is black because video follows the frozen audio clock. Now: Music tab tracks
   bypass SubGen (`PendingLoadAudioProcessor.setBypass`, applied at flush), and a stall with SubGen active
   bypasses it for the track + seeks in place (`ENGINE stall: SubGen holding the sound`). Earlier note: log 22:50:52-22:51:56 shows Perfect Pinterest silently froze ~30 s in (pos 31 s
   after 64 s of PLAYING, no pause, no Exo playWhenReady change). The Exo stall watchdog now runs for the
   whole track (`checkStall`: picture on, hidden surface, seek in place, then error) and logs
   `ENGINE no progress while playing ...`. Old note: **App backgrounded while a downloaded video plays as music: playback pauses sometimes; back in the
   app, fullscreen FAB shows a black screen.** No system pause appears in the logs seen; ask the user
   for the Diagnostic log around the Home press (TRANSPORT/AUDIOFOCUS/STATE/BODY/ENGINE lines). Suspects: video surface destroyed
   while the video track is enabled (`reattachVideoView`, `BodyLayout.setMode(VIDEO)` after 500 ms),
   `FermataMediaService` foreground/wake lock on PAUSED/BUFFERING, audio focus transient loss.
4. **File 4l23KmKhtCQ (Perfect Pinterest) stalls when started as music (picture off)**; plays as video.
   Recovery now switches the picture on + hidden surface in one 3 s step. Root cause unknown (very
   sparse key frames, MTK decoder `c2.mtk.avc.decoder` init failure on the fresh Exo seen once).
   Consider: never disable the video track for files whose video has few key frames, or re-encode on download.
5. Brief previous-cover flash fixed: during the engine hand-over STOPPED blip `getDisplayItem` keeps the shown
   queue track instead of the queue's saved one. Earlier: Music tab can show the previous track while YouTube plays the next one (trace 21:10:5x, cause not found:
   instrument `MusicPlayerFragment.displayItem`).
6. Silent hand-over: local item faded out + handed to YouTube; if the page never starts the local
   engine stays muted with no error and next-press re-hands the same item (audit finding).
7. `MusicPlayer.watchRequested/watchingLocal` can outlive a cancelled start (set before the async
   `playItem`); attach the flag to the track instead of static fields.
8. Speculative (audit): into-video cover not re-armed while an earlier cover is fading out;
   `liftLocalVideoCover` does not check which engine it is for; YouTube cover lifted 150 ms after entry;
   MediaPlayer false error after completion (`source` kept after `reset()`); 150 ms end-of-track poll
   and 1 s UI timer run in background; first `YtDownloads` load and file deletes on the main thread.

## Plan (suggested)
1. Run the app logs on the current branch first; confirm which of 1/3/5 still reproduce.
2. Introduce **one transition coordinator** (small class owned by `MainActivityDelegate`) with explicit
   states `IDLE | LOCAL_FULLSCREEN | YOUTUBE_FULLSCREEN | COVERED(reason)` and a single cover. All of
   `BodyLayout.setMode`, `setVideoMode`, `fadeToBlack*`, `leaveForLocal`, `playExternal` call it; remove
   the three independent covers and the deferred-exit special cases. Add a generation id per transition.
3. Make the session publish **item-scoped state**: metadata, playback state, notification are built from
   `(engine, item)` pairs and rejected if either is no longer current (already done for metadata).
4. Replace `MusicPlayer` static flags with per-track/per-play fields. **Partly done**: `startTrack` no
   longer sets `watchRequested/watchingLocal` up front; it leaves a `StartRequest(track, watch)` that is
   applied only when that same track's engine is chosen (`applyStartRequest`), else dropped (logged
   `MUSIC start request dropped`). Problem 5 is instrumented (`MUSIC display ...` lines).
5. Keep logs: after stable, cut NOTIF/META/BODY lines to state changes only.

## Pickup prompt for the user to paste into the new session
"Read HANDOFF.md on this branch, then work through the Plan. Verify CI is green first. When done, `git rm HANDOFF.md`."
