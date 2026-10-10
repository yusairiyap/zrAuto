## zrAuto
Your car deserves a better media player. zrAuto is a free, open source player built for Android Auto by [Yusairi Yap](https://github.com/yusairiyap): your music, your videos and YouTube, all in one place, and it keeps playing when the signal doesn't.

[Download the latest release](https://github.com/yusairiyap/zrAuto/releases/latest)

## ✨ Why you'll love it
* 🚗 **Car mode: drive it from your steering wheel**. Your wheel's Previous and Next buttons move a glowing outline around the screen, so you can browse, search and pick without touching it.
  * Previous / Next move from item to item; hold Next to open or play, hold Previous to go back (or, at the top, straight to what's playing).
  * Works in Favorites, Playlists, Folders and Downloads, YouTube search and Up next, the Music queue, Add to playlist, every menu, even Settings and the audio effects' sliders.
  * Lands you where you need to be: open Playlists and you're on the song that's playing; search and you're on the first result, no keyboard in the way.
  * YouTube is one hold away from fullscreen, and in fullscreen your buttons keep doing what you bound them to, with the controls popping up so you see each press land.
  * Only wakes up when Android Auto is connected; your phone stays as it is.
* 🎛️ **Key Simulator**: press any button on the wheel, a keyboard or a remote and watch it light up, see what it's bound to and change it on the spot. Mute, skip, step and voice buttons can be bound too.
* 📥 **Take YouTube with you, offline**. Dead zones, tunnels, roaming, a nearly empty data plan: none of it matters once your favourites are on your phone.
  * Download a single video, a whole playlist, your Favorites or a handful you picked, up to 1080p or audio only.
  * Downloads queue up, carry on in the background, pause and resume, and pick up where they stopped.
  * They sit right inside your lists with a little Downloaded badge, and a list mixing online and offline videos just flows from one to the next.
  * Signal gone mid-song? zrAuto switches to the downloaded copy at the very same second.
  * Watch or just listen, with your YouTube equalizer, bass boost and Live Hall, and a Downloads tab of their own.
* 🔔 **Messages you can actually see**: added to Favorites, a playlist or the queue, download finished: it all pops up on the car's screen, not just the phone.
* 🎵 **A real Music tab**: a full-screen, sound-only player with a blurred album-art backdrop, big thumb-friendly controls and a queue you can drag, swipe and shuffle. Your own songs and YouTube tracks play side by side.
* ▶️ **YouTube, made for the road**: it plays right inside the app with its own Favorites, Playlists and queue. Music mode drops the picture to the lowest quality, so you only pay data for what you hear, and switching between video and music never stops the song.
* 🔍 **Search without stopping the music**: a panel slides over whatever's playing, with YouTube results, your saved tracks and an "Up next" queue. Play next is one tap away.
* 📊 **Know where your data goes**: see exactly what YouTube videos, music mode, downloads and everything else cost you, and set a warning or a hard limit before your plan runs dry.
* 🟢 **Bring your Spotify playlists**: import one and zrAuto finds every song on YouTube for you.
* 🗂️ **Tidy lists in seconds**: Select mode moves, copies, reorders or deletes in bulk, while a normal long press just opens the menu, so nothing moves by accident.
* ⛽ **Fuel Log**: track refuels, distance and trips on a timeline, right from the car screen.
* 📶 **Straight with you about bad signal**: when streaming stalls, a clear message offers Try again, Play downloaded, or the music already on your phone.
* 🎨 **Easy on the eyes**: rounded cards, a floating pill nav bar, soft shadows, and smooth fades between songs and videos.
* 👆 **Easy to tap while driving**: a bigger toolbar, nav bar and controls, plus up to six floating buttons you set up your way.
* 🌙 **Night mode for video**: dim the picture with a colour of your choice, including a warm blue-light filter.
* 🕶️ **Private Mode**: a clean, incognito-style YouTube and Browser session with tracker and cookie blocking.
* 🎚️ **Better sound**: a mixer-style Equalizer, Bass Boost, Virtualizer and reverb, for YouTube too.
* 🩺 **A diagnostic log in your pocket**: read, copy and share what happened in the car, no laptop needed.
* 💚 **Yours to keep**: no donation nagging, and it updates itself.

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
