# Handover: header and control panel beside the in-video panels

Status: built, never compiled or run (cloud session). `VideoView#setSplitShare` now also calls `VideoControlsOverlay#setSplit` and
`ControlPanelView#setDockShare`, so the three move in step with the picture, on open, close and the width drag. The overlay's title bar and middle buttons
live in a `dock` holder that cancels the video's shrink on itself (scale 1/(1-share), same pivot) and is only `parentW * (1-share)` wide; the control panel
gets an end margin of `share * parentW` while in its video look. The Info overlay does the same (`VideoView#refreshInfoSplit`: counter-scaled, shifted to the end of the free width; its glide to the right corner uses the free width too). Check on a device: title/buttons
sizes, the seek-streak touch mapping in `VideoControlsOverlay#buttonTouch`, and that the bottom panel's relayout per frame is smooth.
Originally taken out of the offline / effects / snap branch (PR #68).

## What was asked

While a panel is docked beside fullscreen video (comments, audio effects, captions, YouTube search / Up next), make the fullscreen
playback header (the one with the title and channel) and the control panel:

- keep their font and icon sizes (nothing shrinks),
- move smoothly, the way the Info overlay does, to sit just beside the docked panel instead of spreading across the whole screen width,
- animate in and out together with the panel opening and closing,
- leave the video itself exactly as it is: still the small split picture, never resized or interrupted.

## What exists now (read these first)

- `ui/view/SidePanel`: the docked column. It computes `dockW + dockEnd + dp(10)` as the share of the screen it takes and calls
  `VideoView#setSplitShare(share * progress)` while it opens and closes (lines ~326 and ~361). `SidePanel.toggle`, `shouldDock`, `isVideoUp` and
  `onVideoLeft` are the entry points. `YoutubeCommentsView` has its own copy of the same dock logic (`shouldDock`, `watchDock`).
- `ui/view/VideoView#setSplitShare`: a transform on the picture (pivot top-left / centre, black backdrop under it). **Never resize the video:**
  resizing restarts the YouTube player (see CLAUDE.md, "Comments beside the video").
- `ui/view/VideoControlsOverlay`: title and channel (`title`, `subtitle`), the prev / play / next buttons in the middle and the double-tap feedback. Its
  visibility is decided by `ControlPanelView#showVideoControls(vv, center, title)` and `hideVideoUi`.
- `ui/view/ControlPanelView`: the bottom panel. In fullscreen it shows the seek bar line only (plus a gap for the gesture area).
- The Info overlay is the reference for "moves smoothly to the free side": look at how `InfoOverlayView` / `HomeScreen#topInfo` fit themselves to the
  room that is left rather than to the screen.
- Panel widths: `MainActivityPrefs.SIDE_PANEL_EFFECTS_PERCENT`, `SIDE_PANEL_CAPTIONS_PERCENT`, `COMMENTS_SPLIT_PERCENT` (percent of the screen, dragged by the
  strip on the panel's edge, down to 8%).

## Suggested approach

1. One shared value: how much of the screen the docked panel takes now (0 to 1, animated). `SidePanel` and `YoutubeCommentsView` already compute it;
   publish it from one place (a small holder on `MainActivityDelegate`, or on `VideoView` next to `splitShare`) instead of each panel talking to the video only.
2. Give the control panel and the video controls overlay a "free width" taken from that value, and lay them out inside the left part only
   (right margin = the panel's share of the width). Do it with translation/clip or a margin that is set per animation frame from the shared value, not by
   changing the video's size.
3. The title and channel row and the control panel keep their text and icon sizes; if the free width is too small, drop the least important parts
   (as `PowerWidget#applyHeader` does for short cards) rather than shrinking fonts.
4. Run the move from the same animator as `setSplitShare` so the two stay in step, in both directions, and on a drag of the width strip.
5. Car screen: the same rule (the dock is always used there), and the steering-key scope (`CarNav.markModalScope`) must still reach the panel first.

## Watch out for

- Fullscreen video is drawn by a SurfaceView / the page's own top layer: only views over it move, never the picture.
- The control panel is hidden and shown by `ScrollBarsController`, `setOverlaysSuppressed` and the video UI timers: do not fight them, offset the panel
  from its own layout parameters instead of its translation (the bars use translation).
- Leaving fullscreen or PiP closes docked panels at once (`SidePanel.onVideoLeft`, `YoutubeCommentsView#onVideoLeft`): reset the offsets there too.
- Never run on a device from the cloud session. Say so in the PR.
