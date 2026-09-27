## zrAuto
zrAuto is a free, open source media player built for Android Auto, made by [Yusairi Yap](https://github.com/yusairiyap). It's simple, easy to use, and gets you playing your music, videos, and playlists in the car without any fuss.

[Download the latest release](https://github.com/yusairiyap/zrAuto/releases/latest)

## Highlights
The things zrAuto does best, especially behind the wheel:

* **A real Music tab** — a full-screen, audio-only player with a blurred album-art background, its own editable play queue (drag to reorder, swipe to remove, Shuffle/Repeat), and big car-friendly controls. Mix local songs and YouTube tracks in the same queue. "Add to queue" and "Play next" drop tracks right after the one playing, and once you're in music mode, tapping anything in Favorites or a Playlist just keeps playing it as music
* **YouTube, made for the car** — YouTube plays inside the app with its own Favorites, Playlists and queue. Music mode holds the video at the lowest quality so you only pay (in data) for the sound, and switching between video and music never interrupts playback
* **Search without stopping the music** — a native search panel slides over whatever is playing: results from YouTube, matching tracks already in your Favorites and Playlists, and an Up next queue where anything can be set to play next with one tap
* **Spotify playlist import** — bring a Spotify playlist over and zrAuto finds each song on YouTube for you
* **Data usage tracker** — see exactly how much mobile data went to YouTube videos, music mode and everything else, by hour, day, week or month, next to how long each was actually played (and what that cost per hour). Set a warning level or a hard limit and zrAuto pauses streaming before you blow through your plan
* **Tells you when the network is the problem** — if streaming stalls on a weak or lost connection, a clear message pops up in the middle of the screen with a Try again button, and the option to carry on with music stored on your phone meanwhile
* **Easy list editing** — the toolbar's Select mode (in Favorites, playlists and the list of playlists alike) brings up a panel to move items to the top or end, add or move them to another playlist, or remove them, and lets you drag them into place, with selected cards outlined. Outside Select mode a long press always opens the item's menu, so nothing moves by accident. Prefer menus? Set taps to open the item's menu instead of playing (separately for the phone and Android Auto) and a long press drags
* **Fuel Log** — log refuels with the distance driven and where you filled up, and follow your trips on a timeline, right from the car screen
* **A fresher look** — card-style menus, a floating pill nav bar, borderless floating buttons with soft shadows, and smooth animations throughout
* **Bigger, easier-to-tap controls** — the toolbar, navigation bar, and control panel are sized with driving in mind, so you're not squinting or fumbling for buttons on the road
* **Customizable floating buttons** — up to six floating buttons, set up out of the box for fullscreen, screen dimming, favorites and search. Each is optional and configurable from Settings: pick its tap action (fullscreen, mute, play/pause, dimming, search, open Favorites or Playlists and more), long-press any of them for a tidy categorized menu of every action, and resize them all with a single slider
* **Night-friendly video dimming** — a translucent overlay over the video, with adjustable opacity and color (Black, a warm Blue light filter, Red, Deep red, Amber, Yellow, or your own custom color), to keep the screen easier on your eyes at night
* **Private Mode** — an incognito-style mode for the Browser and YouTube tabs: a clean slate with no personalized recommendations, optional tracker/ad and third-party cookie blocking, and your normal session restored automatically when you turn it off
* **Upgraded audio effects** — a spacious, mixer-style Equalizer, Bass Boost and Virtualizer with vertical sliders built for use while driving, plus a Live Hall reverb. Works for YouTube too, not just local media
* **Built-in diagnostic log** — an opt-in event log you can read, copy and share from inside the app, for tracking down car-only problems without a laptop
* **No donation nagging** — the app doesn't interrupt you asking for money
* **Its own update channel** — zrAuto checks for and installs its own updates, so you're always running the latest build

zrAuto is built on top of Andrey Pavlenko's original Fermata Auto.

## What zrAuto can do
* Play your media files, organized by folders — just like browsing files normally
* Remembers where you left off, for every folder
* Save your favorite tracks and folders, and build playlists
* Works with CUE and M3U playlists
* Bookmark spots in a track or video to jump back to later
* Built-in audio effects — Equalizer, Bass/Volume Boost, and Virtualizer — that you can tune per track or folder
* Adjust playback speed per track or folder
* Customize how titles and subtitles look
* IPTV support, with EPG and catch-up TV
* Works great on Android Auto and Android TV
* Show your favorites and playlists right on the Android TV home screen
* Choice of playback engines (MediaPlayer, ExoPlayer, VLC) depending on what works best for your files
* Video playback with subtitle and audio-track support (when using the VLC engine)

## Getting the app
The easiest way is to grab the latest APK from the [Releases page](https://github.com/yusairiyap/zrAuto/releases/latest) and install it on your phone. zrAuto will let you know in-app whenever a new version is available.

## Building it yourself
If you'd rather build zrAuto from source:

1. Install [Android Studio](https://developer.android.com/studio) (or just the Android SDK).
2. Point the `ANDROID_SDK_ROOT` environment variable at your SDK folder:
   ```bash
   export ANDROID_SDK_ROOT=<path to your Android SDK>
   ```
3. Clone the project:
   ```bash
   git clone --recurse-submodules https://github.com/yusairiyap/zrAuto.git
   cd zrAuto
   ```
4. Build it:
   ```bash
   ./gradlew bundleAutoRelease
   ```
   The finished app package will show up under the project's build output folders.

## Supporting the project
zrAuto is free and doesn't ask for donations, but it's built on the excellent work of Andrey Pavlenko's original Fermata Media Player. If you'd like to say thanks, you can support him directly:

[PayPal](https://www.paypal.com/donate/?hosted_button_id=NP5Q3YDSCJ98N)

[CloudTips](https://pay.cloudtips.ru/p/a03a73da)

[Yandex Money](https://money.yandex.ru/to/410014661137336)
