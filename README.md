## zrAuto
zrAuto is a free, open source media player built for Android Auto, made by [Yusairi Yap](https://github.com/yusairiyap). It's simple, easy to use, and gets you playing your music, videos, and playlists in the car without any fuss.

[Download the latest release](https://github.com/yusairiyap/zrAuto/releases/latest)

## Highlights
What makes zrAuto worth having in the car, biggest first:

* **A proper Music tab** — a full-screen, audio-only player that taps into a huge range of music from all over the world, without wrecking your data plan. Only the sound streams, so it's easy on data, with a blurred album-art backdrop, big controls and its own queue you can drag, swipe and shuffle. Local songs and YouTube tracks play side by side
* **YouTube, built for the road** — it plays right inside the app, with its own Favorites, Playlists and queue. Music mode drops the video to the lowest quality so you only pay data for the sound, and flipping between video and music never stops the song
* **Search that never stops the music** — a panel slides over whatever's playing, with YouTube results, your saved tracks and an "Up next" queue. Play next is one tap away
* **Data usage tracker** — see what YouTube, music mode and everything else cost you in mobile data, and set a warning or a hard limit before your plan runs dry
* **Bring your Spotify playlists** — import one and zrAuto finds every song on YouTube for you
* **Edit lists in seconds** — Select mode lets you move, copy, reorder or delete items in bulk. Otherwise a long press just opens the menu, so nothing moves by accident
* **Fuel Log** — track refuels, distance and trips on a timeline, right from the car screen
* **Honest about bad signal** — when streaming stalls, a clear message offers Try again, or switches to the music on your phone
* **Made to look good** — rounded cards, a floating pill nav bar, soft-shadow buttons and smooth animations everywhere
* **Easy to tap while driving** — a bigger toolbar, nav bar and controls, plus up to six floating buttons you set up your way
* **Night mode for video** — dim the picture with a colour of your choice, including a warm blue-light filter
* **Private Mode** — a clean, incognito-style YouTube and Browser session with tracker and cookie blocking
* **Better sound** — a mixer-style Equalizer, Bass Boost, Virtualizer and reverb, for YouTube too
* **Built-in diagnostic log** — read, copy and share what happened in the car, no laptop needed
* **Yours to keep** — no donation nagging, and it updates itself

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
