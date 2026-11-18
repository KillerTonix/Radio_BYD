# Wheel Radio

Internet radio for the BYD Yuan Plus / Atto 3 (DiLink, Android 10), controlled from the steering wheel.

| Wheel button | What happens |
|---|---|
| Next / Previous | Switch to the next / previous station (loops around) |
| Volume + / − | Changes the car's media volume (handled by the car; the app plays as normal media audio) |

## How wheel buttons reach the app

BYD units don't all route wheel keys the same way, so the app listens on three routes at once and de-duplicates presses (400 ms):

1. **MediaSession** (standard Android). `RadioService` is a Media3 `MediaSessionService`. If the car routes media keys to the active media session, this just works.
2. **Focused window.** When the app is on screen, BYD hands `KEYCODE_MEDIA_NEXT/PREVIOUS` to the foreground activity (`MainActivity.dispatchKeyEvent`).
3. **BYD broadcast.** `com.byd.multimediaservice` re-broadcasts wheel presses as `APP_REV_DATA` with a byte frame `[4, 1, code]` (`BydWheelKeyReceiver`). Only `0x03` = Next is documented so far; other codes get filled in after a test drive.

The **key log** (button on the main screen) shows every press from every route, so a single test in the car tells us which route works for each button.

## Build

No Android SDK is needed locally if you use GitHub: push the repo and the `Build APK` workflow produces `wheel-radio-debug-apk`.

Locally: open the folder in Android Studio, or run `./gradlew assembleDebug` with the Android SDK installed. The APK is in `app/build/outputs/apk/debug/`.

## Install on the car

Newer DiLink firmware blocks APK installs from USB by default. Owners enable ADB / an unlocked package installer first, e.g. per https://github.com/ahmada3mar/BYD (firmware after 2407). Then install from USB, or `adb install app-debug.apk` over wireless ADB.

## Stations

Edit `app/src/main/assets/stations.json`. Planned: in-app search through the free Radio Browser API.

## Code map

- `RadioService.kt` – ExoPlayer + MediaSession, looping station playlist, reconnect on network drops
- `StationSwitchingPlayer.kt` – makes next/previous always switch station, debounces duplicate presses
- `BydWheelKeyReceiver.kt` – BYD `APP_REV_DATA` decoder
- `MainActivity.kt` – landscape UI, foreground key handling, key log
- `KeyLog.kt` – diagnostics (also in logcat, tag `WheelRadio`)
