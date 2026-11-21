# Radio_BYD (Wheel Radio) — QA & Testing Instructions

Native **Android** app (Kotlin + Jetpack Compose, package `dev.wheelradio`) for the BYD DiLink head unit
(Yuan Plus / Atto 3, Android 10). Playback is Media3 ExoPlayer inside a `MediaSessionService`.
There is no web UI, no dev server, no `localStorage` and no `navigator.mediaSession`, so **Playwright does not apply**.
Drive the app on an emulator with adb (or the `android-emulator` MCP tools) and test it with Gradle.

## Where things live
| Concern | Location |
|---|---|
| Screens, tabs (`By countries`, `Favorites`, `Local & USB`, `Bluetooth`), mini player, key log | `Ui.kt`, `AppModel.kt` |
| Playback, retry/skip on errors, `last_index` persistence | `RadioService.kt` |
| Next/previous = station switch, 400 ms debounce across key routes | `StationSwitchingPlayer.kt` |
| Wheel key routes: focused window (`MainActivity.dispatchKeyEvent`), MediaSession media buttons, BYD broadcast `APP_REV_DATA` byte[] `[4,1,code]` | `MainActivity.kt`, `RadioService.kt`, `BydWheelKeyReceiver.kt` |
| Station directory (live Radio Browser API) | `RadioBrowser.kt` |
| Station lists: starter `stations.json`, curated `stations_am.json` (46 Armenian, merged first) | `app/src/main/assets/`, `CuratedStations.kt` |
| Persistence: SharedPreferences `radio` (`favorites`, `queue`, `last_index`, `shuffle`, `music_folder`) | `StationRepository.kt` |
| Local / USB music (MediaStore scan) | `LocalMusic.kt` |

All sources are in `app/src/main/java/dev/wheelradio/`. The UI has **no `testTag`s or resource ids**, so tests select by text or contentDescription.
`test-results/test-suite-report.md` §7 lists the testTags we recommend adding.

## Local toolchain (Windows)
- JDK 17 (Temurin) and the Android SDK (platform 34, build-tools 34, platform-tools, emulator, `system-images;android-29;google_apis;x86_64`).
  Neither is on PATH system-wide, so export both in every shell that runs Gradle:
  ```bash
  export JAVA_HOME="$HOME/.jdks/jdk-17.0.21.1+2"
  export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
  ADB="$ANDROID_HOME/platform-tools/adb"
  ```
- `local.properties` (git-ignored) must use **forward slashes**: `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`.
  Unescaped backslashes make the build fail with "The filename, directory name, or volume label syntax is incorrect".
- Gradle piped into `tail` reports `tail`'s exit code. Use `set -o pipefail` or check `$?` before claiming a build passed.

## BYD DiLink emulator
- AVD `BYD_DiLink_API29`: Android 10 / API 29, `hw.lcd.width=1920`, `hw.lcd.height=1080`, density 160, WHPX acceleration.
  Start it with `"$ANDROID_HOME/emulator/emulator" -avd BYD_DiLink_API29 -no-snapshot -no-boot-anim` (in the background), then wait for `getprop sys.boot_completed` = `1`.
- Portrait (rotating screen) test: `adb shell wm size 1080x1920`, or `settings put system accelerometer_rotation 0` + `settings put system user_rotation 1`.
  **Restore afterwards** with `wm size reset` and `user_rotation 0`. The activity handles config changes itself and is not recreated on rotation.
- `android-emulator` MCP server: `mcp-android-emulator` runs plain `adb` unless `ADB_PATH` is set. It is registered with
  `-e ADB_PATH=<sdk>\platform-tools\adb.exe`. Without that setting every tool fails with `spawn adb ENOENT`.

## Build, install, run
```bash
./gradlew assembleCarDebug        # app/build/outputs/apk/car/debug/app-car-debug.apk
./gradlew installCarDebug         # or: $ADB install -r -g <apk>
$ADB shell am start -W -n dev.wheelradio/.MainActivity
```
`car` = head-unit build (includes the Bluetooth notification listener). `phone` = same app without it.
On start the service resumes the last station and auto-plays.

## Tests
```bash
./gradlew testCarDebugUnitTest            # JVM + Robolectric, hermetic, ~20 s; also runs in CI
./gradlew connectedCarDebugAndroidTest    # instrumented, needs the emulator, ~2.5 min; local only
```
- Unit tests are in `app/src/test/`; instrumented tests (Compose UI, `MediaController`, wheel-key routes) are in `app/src/androidTest/`.
- **Hermetic only:** no test may depend on live Shoutcast/Icecast streams or the Radio Browser API.
  Instrumented playback uses `FakeStreamServer` (`MockWebServer` on 127.0.0.1 serving `androidTest/assets/qa_tone_440_90s.mp3`).
  Radio Browser can't be stubbed without a production seam, so the country grid, country lists and search are not covered yet.
- `connectedCarDebugAndroidTest` **overwrites `radio.xml` and uninstalls the app** when it finishes. Run `installCarDebug` again afterwards.
- Confirmed bugs are covered by tests that assert the *expected* behaviour and are marked `@Ignore("Known bug … – see test-results/bugs.md")`.
  When a bug is fixed, remove the `@Ignore` and confirm the test passes.
- `adb shell am broadcast` cannot send a `byte[]` extra, so the BYD `APP_REV_DATA` route can only be tested in code (`BydWheelKeyReceiverTest`, `WheelKeyRouteTest`).

## QA agent pipeline
1. Build, install and launch the car debug APK on the running `BYD_DiLink_API29` emulator, and confirm the app process and its media session exist (`dumpsys media_session`).
2. **ui-explorer**: map screens and selectors in landscape 1920x1080 and portrait 1080x1920. Cover tabs, the Armenia / Russia / Germany station lists, search, favorites persistence across force-stop and relaunch, the Local & USB flow with pushed test MP3s, and the key log. Writes `test-results/exploration-report.md`.
3. **audio-session-tester**: check `dumpsys media_session` / `dumpsys audio` states, rapid switching and the debounce, wheel keyevents (85/87/88/126/127/166/167) with the app in the foreground and the background, dead-stream and network-loss recovery, shuffle, audio focus, and lifecycle. Writes `test-results/audio-session-report.md`.
4. **test-writer-healer**: extend the JVM and instrumented suites from the reports above, run both, fix *test-side* failures, and re-run until green. Writes `test-results/test-suite-report.md`.

The agent definitions in `~/.claude/agents/` were written for a web app (HTMLMediaElement, Playwright, `localStorage`).
When you start them, give them the Android context from this file. `ui-explorer` only has Playwright, Read, Glob and Grep,
so it can neither drive the emulator nor write its report. Run its brief with a general-purpose agent, or add
`mcp__android-emulator__*`, `Bash` and `Write` to its tools. The emulator is shared, so run the agents one after another.

## Guardrails
- Never alter production code, stream URLs, station lists (`assets/*.json`), or the MediaSession / wheel-key bindings to make a test pass.
  Test changes may touch only test sources, test fixtures, test dependencies in `gradle/libs.versions.toml` / `app/build.gradle.kts`, and the CI test step.
- Temporary test data in the device's prefs is fine **only if it is restored** afterwards.
- Log real stream, cleartext, network and app failures in `test-results/bugs.md` (`## [UI|AUDIO|TEST] <title>` with Severity, Steps, Expected, Actual, Evidence).
  Don't weaken assertions to hide them.
- Keep screenshots, UI dumps and logs out of the repo, and keep machine-specific paths out of committed reports.
