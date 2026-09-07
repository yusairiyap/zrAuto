## zrAuto
zrAuto is a free, open source media player built for Android Auto, made by [Yusairi Yap](https://github.com/yusairiyap). It's simple, easy to use, and gets you playing your music, videos, and playlists in the car without any fuss.

[Download the latest release](https://github.com/yusairiyap/zrAuto/releases/latest)

## What makes zrAuto different
zrAuto is built on top of the original Fermata Auto, with a few changes to make it nicer to use:

* **A fresher look** — redesigned menus with a cleaner, card-style layout, plus smoother animations when switching tabs, entering or leaving fullscreen video, and navigating Settings
* **Bigger, easier-to-tap controls** — the toolbar, navigation bar, and control panel are sized with driving in mind, so you're not squinting or fumbling for buttons on the road
* **Customizable floating buttons** — add up to two extra floating buttons alongside the main one, each fully optional and independently configurable from Settings: pick its tap action (fullscreen, mute, play/pause, or screen dimming), long-press any of them for a quick menu of all four, resize them all with a single slider, and drag them anywhere on screen
* **Night-friendly video dimming** — a translucent overlay over the video, with adjustable opacity and color (Black, a warm Blue light filter, Red, Deep red, Amber, Yellow, or your own custom color), to keep the screen easier on your eyes when watching or driving at night. Turn it on and off from Settings, the secondary floating button, or the video screen's menu
* **Private Mode** — an incognito-style mode for the Browser and YouTube tabs: turning it on clears cookies and site data for a clean slate, so YouTube stops showing personalized recommendations, and turning it back off signs you back into your normal session automatically (with an option to skip that and discard it too, for a fully clean break). Extra privacy options (in the spirit of Brave's) let you block trackers/ads and third-party cookies, and "Always use Private Mode" keeps it on by default, even across app restarts. Toggle it from the toolbar, the secondary/tertiary floating buttons, or the Browser/YouTube menu
* **YouTube in your Favorites and Playlists** — save YouTube videos alongside your other media, not just browse them live
* **Upgraded audio effects** — the Equalizer, Bass Boost, and Virtualizer got a redesigned, spacious mixer-style interface with vertical sliders built for easy use while driving, plus a new Live Hall reverb effect that simulates live-concert-hall acoustics. Now available for YouTube video playback too, not just local media
* **No donation nagging** — the app doesn't interrupt you asking for money
* **Its own update channel** — zrAuto checks for and installs its own updates, so you're always running the latest zrAuto build

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
