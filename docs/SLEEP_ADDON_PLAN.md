# Sleep addon: plan for a future session (ON HOLD)

Status: **not started.** The user wants this kept for later. Read `CLAUDE.md` first (adding a nav-bar tab, `insetScrollableContent`,
`TopToast`, the Diagnostic log line format with `LogCat`, and the fact that this cloud session cannot build or run the app).

## Decisions already made with the user
- **Sensor mode is required in the first version** (accelerometer, and microphone as an option). Health Connect / watch import is a
  *future* step; design the data layer so imported nights can be added later, but build none of it now.
- Free to run: everything local, no cloud, no account.
- UI follows the Data usage and Ambience tabs (animated, relevant graphs); settings follow the Home settings page (`ReorderListFragment`
  based, with live animated previews where a preview helps).
- It is an addon with a nav tab, an All in One tile, key bindings and floating button actions (new `Action` values go at the END of the enum).

## Research summary (why the design is what it is)
- No open-source Android app was found with a documented accelerometer sleep-staging algorithm. Gadgetbridge only shows what the watch
  sends (some bands give no light/deep split). A phone cannot honestly measure REM or deep sleep.
- Commercial scores are mostly undisclosed. Apple's is published: duration 50, bedtime consistency 30, interruptions 20, no stages.
  Oura lists total sleep, efficiency, restfulness, REM, deep, latency, timing. Fitbit's breakdown sources conflict.
- Devices measure total sleep time and bedtime consistency well and detect wake poorly. So the score must lean on those.
- Sources: https://sleepspace.com/sleep-score/ , https://ouraring.com/blog/sleep-score/ , https://support.google.com/fitbit/answer/14236513 ,
  https://gadgetbridge.org/basics/features/activities/ , https://www.kygo.app/post/every-sleep-metric-your-wearable-tracks-oura-apple-watch-fitbit-garmin

## Build as
A `FermataFragmentAddon` built into the `fermata` module (like `AmbienceAddon`): entry in the root `build.gradle` `addons` list, `addon_name_sleep`
string, `sleep_addon` id, `hasSettings: true`. Tab fragment extends `MainActivityFragment`, calls `insetScrollableContent` on its scrollable
root. Package `me.aap.fermata.addon.sleep`. Log under a new `LogCat.SLEEP` constant (add it and its table line).

## Capture (sensor mode)
- A "Start sleep" / "Stop sleep" session. A foreground service while it runs (like `AmbienceService`), a partial wake lock, low sampling.
- Accelerometer: movement counts per 30 s epoch. Optional microphone: only loudness per epoch (snoring/noise level), **never store audio**.
- Also use phone events: screen on/off, charging, and an optional manual "I'm awake" tap.
- Per night store: bed time, wake time, sleep onset (first still stretch), epochs of movement, awake episodes, noise level, ambience mix used,
  sleep timer used. Keep nights for 3 years in a small store like `PowerUsageStore` (day granularity plus the night's epochs).
- Sleep/wake per epoch with a standard actigraphy rule (Cole-Kripke style weighted movement window). Say in the UI that it is an estimate.
- Stages (light / deep / REM): estimated from movement and time into the night, **always labelled "estimated"**. Leave the model replaceable
  so imported data can override it later.

## Score (0-100, shown with its breakdown, weights adjustable in settings)
- Duration against the user's goal: 40. Bedtime consistency over the last 7 nights: 25. Time to fall asleep: 15. Interruptions: 20.
- Never show a number with more confidence than the data has: with no sensor data (manual times only) mark the score "from times only".

## Insights and suggestions
- Averages, best/worst night, sleep debt against the goal, consistency of bedtime and wake time.
- **Ambience link**: a "Start sleep" button that starts the last ambience mix and the sleep timer together and ends the session when the timer ends;
  a correlation insight ("nights with rain averaged +8 points") shown only after 7 or more nights of data; tips from patterns (late bedtime,
  long latency, noisy nights, screen use before bed).

## Graphs (Data usage / Ambience look, animated)
- Hypnogram of the night (tap/drag to read a moment), weekly duration bars with a goal line, bedtime scatter (consistency), a score ring that
  fills in, a movement/noise strip. Draw in code like `DataUsageChartView` (reuse it where it fits). Respect the power saver (no animation) and
  stop frame loops when not visible.

## Settings (Home-settings style, with live previews)
Sleep goal, target bedtime and wake time, score weights (previewed live on a sample night), capture mode (accelerometer / microphone / times only),
sensitivity, auto-stop, smart reminders, the ambience link, graph animation, units. Add a settings page `SleepSettingsFragment`
(`isSettingsPage()` true; see `HomeSettingsFragment`, `PowerSettingsFragment`).

## Wiring checklist
`Action.SLEEP` (open tab) and `Action.SLEEP_TOGGLE` (start/stop) at the END of the enum; FAB list; All in One tile; key bindings; FAB long-press
Tools group; Home widget (optional, `HomeWidgets`). Toast `sleep_addon_off` when the addon is disabled, as Ambience does.

## Cannot be verified here
No device or emulator: sensor capture, the foreground service, battery use overnight and the graphs are untested. Add `LogCat.SLEEP` lines for
sessions, epochs summary and score inputs, and ask the user for the Diagnostic log after the first night.
