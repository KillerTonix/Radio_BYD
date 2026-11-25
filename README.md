# Wheel Radio

Internet radio for the BYD Yuan Plus / Atto 3 (DiLink, Android 10), controlled from the steering wheel.

| Wheel button | What happens |
|---|---|
| Next / Previous | Switch to the next / previous station (loops around) |
| Volume + / − | Changes the car's media volume (handled by the car; the app plays as normal media audio) |

## Features

- **Radio:** stations by country: every country from the free [Radio Browser](https://www.radio-browser.info) directory, with your home country (Armenia by default) first, then Russia, Germany, USA, Georgia and France. Tap a tile for its stations, or its play button to start the most popular one.
- **Search:** station names worldwide, plus matching countries.
- **Favorites:** heart any station (or local track) from a list or the mini player. Radio stations and songs are kept in separate sections, so next / previous never jumps between them.
- **Shuffle (local / USB songs only):** toggle in the mini player while a song plays, "Shuffle all" on Music, or shuffle favorite songs. Radio always plays in list order. Wheel next/previous follow the shuffled order.
- **Music:** music the media scanner finds on the car and on USB sticks.
- **Bluetooth:** shows the song playing from your phone over the car's Bluetooth, with play/pause/next/previous. The car still plays the audio; the app is the remote. It tries Android's AVRCP controller service first, then other apps' media sessions (needs notification access once: `adb shell cmd notification allow_listener dev.wheelradio/dev.wheelradio.MediaListener`). After you press play there, the wheel buttons control Bluetooth until you play radio or a song in the app again.
- Next / previous (on screen or on the wheel) steps through whichever list you started playing from.
- **Settings** (gear, top right): language (English, Русский, Հայերեն), screen size (Small to Extra large), theme (dark, light, or auto by time of day and the car's night mode), auto-play on start, weak-coverage mode (40 s instead of 20 s before a silent station is retried or skipped), home country, and the wheel-button log.

## How wheel buttons reach the app

BYD units don't all route wheel keys the same way, so the app listens on three routes at once and de-duplicates presses (400 ms):

1. **MediaSession** (standard Android). `RadioService` is a Media3 `MediaSessionService`. If the car routes media keys to the active media session, this just works.
2. **Focused window.** When the app is on screen, BYD hands `KEYCODE_MEDIA_NEXT/PREVIOUS` to the foreground activity (`MainActivity.dispatchKeyEvent`).
3. **BYD broadcast.** `com.byd.multimediaservice` re-broadcasts wheel presses as `APP_REV_DATA` with a byte frame `[4, 1, code]` (`BydWheelKeyReceiver`). Only `0x03` = Next is documented so far; other codes get filled in after a test drive.

The **key log** (button on the main screen) shows every press from every route, so a single test in the car tells us which route works for each button.

## Build

No Android SDK is needed locally if you use GitHub: push the repo and the `Build APK` workflow produces `wheel-radio-debug-apk`.

Locally: open the folder in Android Studio, or run `./gradlew assembleDebug` with the Android SDK installed. The APKs are in `app/build/outputs/apk/<car|phone>/debug/`.

## Which APK

The build makes two APKs: **app-car-debug.apk** for the head unit (everything), and **app-phone-debug.apk** for testing on a phone. The phone one leaves out the notification listener used by the Bluetooth fallback, because Google Play Protect blocks sideloaded apps that have one.

## Install on the car

Newer DiLink firmware blocks APK installs from USB by default. Owners enable ADB / an unlocked package installer first, e.g. per https://github.com/ahmada3mar/BYD (firmware after 2407). Then install from USB, or `adb install app-debug.apk` over wireless ADB.

## Stations

`app/src/main/assets/stations.json` is only the starter list for the very first launch.

`assets/stations_<country code>.json` (e.g. `stations_am.json`, 44 Armenian stations) holds hand-picked stations that are shown first in that country and merged with Radio Browser, skipping duplicates.

## Code map

- `RadioService.kt` – ExoPlayer + MediaSession, looping station playlist, reconnect on network drops
- `StationSwitchingPlayer.kt` – makes next/previous always switch station, debounces duplicate presses
- `BydWheelKeyReceiver.kt` – BYD `APP_REV_DATA` decoder
- `MainActivity.kt` – media controller, foreground key handling
- `Ui.kt` / `AppModel.kt` – screens (tabs, country grid, lists, mini player, settings, key log) and their state
- `AppSettings.kt` – user settings; UI text is in `res/values{,-ru,-hy}/strings.xml`
- `StreamRecovery.kt` – retries, skips, no-audio watchdog, offline wait
- `RadioBrowser.kt` – station directory client
- `LocalMusic.kt` – device / USB / folder music scanning
- `KeyLog.kt` – diagnostics (also in logcat, tag `WheelRadio`)
