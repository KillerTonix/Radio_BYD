# Radio_BYD automated test suite (test-writer, step 3 of 3)

Date: 2026-11-21 · Branch: `qa/claude-test-suite` (working tree only, nothing committed) · Device: `emulator-5554` (`BYD_DiLink_API29`, Android 10, 1920x1080 landscape, 160 dpi)

Radio_BYD is a native Kotlin / Jetpack Compose app (Media3 1.4.1 ExoPlayer in `RadioService`). The pipeline's default Playwright / `tests/` / `npx playwright test` setup does not apply. These are the native equivalents:

| Suite | Where | Runner | Network |
|---|---|---|---|
| JVM unit (Robolectric) | `app/src/test/java/dev/wheelradio/` | `./gradlew testCarDebugUnitTest` | none (missing `file:///` URIs, bundled assets) |
| Instrumented (emulator) | `app/src/androidTest/java/dev/wheelradio/` | `./gradlew connectedCarDebugAndroidTest` | loopback only: `MockWebServer` on `127.0.0.1` inside the test process |

**No test depends on a live Shoutcast/Icecast stream or on the Radio Browser API.**

---

## 1. How to run

```bash
export JAVA_HOME="$HOME/.jdks/jdk-17.0.21.1+2"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
cd Radio_BYD

./gradlew testCarDebugUnitTest            # JVM, ~20 s warm (first run downloads Robolectric's android-all jar)
./gradlew connectedCarDebugAndroidTest    # needs a running emulator/device, ~2.5 min
```

- Reports:
  - JVM: `app/build/reports/tests/testCarDebugUnitTest/index.html` (XML in `app/build/test-results/testCarDebugUnitTest/`)
  - Instrumented: `app/build/reports/androidTests/connected/debug/flavors/car/index.html` (XML in `app/build/outputs/androidTest-results/connected/debug/flavors/car/`)
- Run one class or method: `./gradlew connectedCarDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=dev.wheelradio.WheelKeyRouteTest#media_next_key_switches_exactly_one_station"`, or `./gradlew testCarDebugUnitTest --tests 'dev.wheelradio.StationSwitchingPlayerTest'`.
- `connectedCarDebugAndroidTest` installs the app and test APKs (AGP grants runtime permissions), **overwrites `radio.xml`** (queue / favorites / last_index / shuffle) and **uninstalls both APKs when it finishes**. Afterwards, reinstall with `./gradlew installCarDebug`.
- Gradle's plain console prints no test summary. The result is the `BUILD SUCCESSFUL/FAILED` line plus the report counts. With `| tail`, check the exit code (`set -o pipefail`).
- CI: `.github/workflows/build.yml` now runs `./gradlew testCarDebugUnitTest` after `assembleDebug` and uploads the HTML report (`if: always()`). Instrumented tests are **not** in CI.

### Build changes (test-only)
- `gradle/libs.versions.toml`: added `junit 4.13.2`, `robolectric 4.13`, `truth 1.4.4`, `androidx.test core 1.6.1 / ext-junit 1.2.1 / runner 1.6.2 / rules 1.6.1`, `uiautomator 2.3.0`, `okhttp mockwebserver 4.12.0`, `media3-test-utils` (= media3 1.4.1), `compose ui-test-junit4` (from the existing BOM 2024.09.03).
- `app/build.gradle.kts`:
  - `defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`
  - `testOptions { unitTests.isIncludeAndroidResources = true }`
  - `testImplementation(...)` / `androidTestImplementation(...)` only
  - No `debugImplementation`: `ui-test-manifest` was deliberately left out so the debug APK is unchanged.
- `app/src/test/resources/robolectric.properties`: `sdk=34`.
- Fixture: `app/src/androidTest/assets/qa_tone_440_90s.mp3` (90 s, 440 Hz, mono 22.05 kHz, 32 kbps, 360 KB, generated with `C:\FFMPEG\ffmpeg.exe -f lavfi -i sine=frequency=440:sample_rate=22050:duration=90 -ac 1 -b:a 32k`). It lives in the **test** APK only.
- **Production code, assets, station lists, URLs and MediaSession/key bindings: untouched.**

---

## 2. Final results

### JVM unit — `./gradlew cleanTestCarDebugUnitTest testCarDebugUnitTest`
Result line: **`BUILD SUCCESSFUL in 21s`** (run twice in a row, identical results).

| Class | Tests | Passed | Ignored | Failed |
|---|---|---|---|---|
| BydWheelKeyReceiverTest | 12 | 11 | 1 | 0 |
| CuratedStationsTest | 11 | 11 | 0 | 0 |
| RadioServiceRetryPolicyTest | 7 | 6 | 1 | 0 |
| StationAssetsTest | 4 | 4 | 0 | 0 |
| StationRepositoryTest | 7 | 7 | 0 | 0 |
| StationSwitchingPlayerTest | 17 | 17 | 0 | 0 |
| StationTest | 14 | 14 | 0 | 0 |
| **Total** | **72** | **70** | **2** | **0** |

### Instrumented — `./gradlew connectedCarDebugAndroidTest` (full run, no filter)
Result lines: **`Starting 46 tests on BYD_DiLink_API29(AVD) - 10`** / **`Finished 52 tests on BYD_DiLink_API29(AVD) - 10`** / **`BUILD SUCCESSFUL in 2m 26s`**
XML: `tests="46" failures="0" errors="0" skipped="6"`. AGP's "Finished 52" counts ignored tests twice; 46 is the real count.

| Class | Tests | Passed | Ignored | Failed |
|---|---|---|---|---|
| MainActivityUiTest | 12 | 12 | 0 | 0 |
| MiniPlayerAndShuffleTest | 8 | 7 | 1 | 0 |
| RadioServicePlaybackTest | 11 | 10 | 1 | 0 |
| WheelKeyRouteTest | 15 | 11 | 4 | 0 |
| **Total** | **46** | **40** | **6** | **0** |

---

## 3. What each class covers

### JVM (Robolectric, `@RunWith(AndroidJUnit4)`)
| Class | Covers |
|---|---|
| `BydWheelKeyReceiverTest` | A **real `byte[]` `list` extra**, the route `am broadcast` cannot exercise: `[4,1,0x03]` → NEXT exactly once, plus KeyLog line `[BYD] frame 04 01 03 code=0x03 -> NEXT`. Trailing bytes are ignored. Unmapped `0x7F` is logged as `unmapped` with no action. `0xFF` is masked to unsigned. Wrong group (5) or sub (2), short or empty frames, a missing extra, and `int[]`/`long[]`/`String`/`int` payloads → no action. The receiver does not debounce on its own. |
| `StationSwitchingPlayerTest` | Uses a `TestExoPlayerBuilder` player (never prepared, so no audio) and controls `SystemClock` with `ShadowSystemClock`. Covers: next/prev move exactly ±1 and wrap at both ends (also with `REPEAT_MODE_OFF` through the `floorMod` fallback); `*MediaItem` variants; debounce (399 ms ignored, 400 ms accepted, shared across directions and routes, 10 presses 110 ms apart → 3 switches, matching step 2); previous at 10 s position switches station and never restarts; skip sets `playWhenReady`; empty and single-item queues; follows a fixed `DefaultShuffleOrder` (3→1→4→0→2→3, and backwards); `perform(NEXT/PREVIOUS/PLAY_PAUSE)`, where play/pause toggles and never switches. |
| `CuratedStationsTest` | `merge`: curated first in order, then the directory in order; empty curated or empty directory. URL dedup across `;stream.mp3`, http/https, case, trailing `/` and query. Name dedup across `Radio`/`Радио`/`Ռադիո` prefixes, case, spaces and punctuation (Latin `Van` ≠ Armenian `Վան` is kept). Same host with a different path is kept. `forCountry`: `AM` → 46 stations from `stations_am.json` in file order, case-insensitive; `DE`/`RU`/`ZZ` → empty. |
| `StationTest` | JSON round trip (Armenian, Cyrillic, `;`/`?`/`&` in URLs); persisted key names; `listFromJson(null/""/blank/[])` → empty; optional fields default; a missing required field throws. `toMediaItem` fields (station null for local, null artist/artwork when blank). `toMediaItem`→`fromMediaItem` round trip. **URI survives the controller hop through `requestMetadata` when `localConfiguration` is stripped.** `localConfiguration` fallback; `local:` prefix → local; `Country.flag` for AM/ru/DE. |
| `StationRepositoryTest` | Fresh-install defaults. An empty or `[]` queue falls back to the 5 `stations.json` defaults in order. favorites, queue, last_index, shuffle and music_folder persist across instances. Clearing favorites writes `[]`. Reads the real `radio.xml` shape. |
| `StationAssetsTest` | Structure only, no network. Every `stations*.json` parses; ids are unique and non-blank; names are non-blank and trimmed; URLs are http(s) with a host and no whitespace; nothing is `local`; favicons are http(s). `stations.json` = 5 entries. `stations_am.json` = 46 entries, ids `am:00..am:45` in order, subtitle `Armenia`. Default and curated ids don't collide. |
| `RadioServiceRetryPolicyTest` | Creates the real `RadioService` through `Robolectric.buildService` with a queue of missing `file:///` URIs (fails fast with `ERROR_CODE_IO_FILE_NOT_FOUND`) on the fake clock. The session player is read through test-only reflection on the private `session` field. Covers: queue and last_index restored from prefs (out-of-range index clamped); REPEAT_ALL; `playWhenReady` true; the shuffle pref is ignored for radio; **4 errors (1 + 3 retries) with ≥ 12 s of back-off, then a skip to index 1 that is persisted**; a single-item queue stays in error at index 0; a BYD `byte[]` broadcast through the service's registered receiver → exactly +1. |

### Instrumented (emulator)
| Class | Covers |
|---|---|
| `FakeStreamServer` (helper) | One `MockWebServer` on `127.0.0.1` for the whole run: `/ok/<n>.mp3` (200 `audio/mpeg`, the tone), `/dead/404`, `/dead/close` (`DISCONNECT_AT_START`), and an `outage` switch. It is never shut down, because `RadioService` outlives single tests and a server that disappeared would start the retry/skip cycle at random. |
| `TestSupport.kt` (helper) | `seedPrefs()` / `SeedPrefsRule` write `radio.xml` with `commit()` **before** anything binds `RadioService`. A freshly created service then resumes a fake stream instead of the live `stations.json` defaults. `ServiceController` = a `MediaController` to `RadioService` on the main looper (as `MainActivity` does). `playQueue()` mirrors `MainActivity.play()`. Also `pollUntil`, KeyLog marks, and `waitOutDebounce()` (500 ms). |
| `MainActivityUiTest` | `createAndroidComposeRule<MainActivity>`. Starts on BY COUNTRIES with 4 tabs, search placeholder, Key log, and mini-player Next/Previous/Favorite. Each tab tap moves `selected`. Favorites empty state. Seeded favorites render in insertion order. **Un-heart removes and persists across `scenario.recreate()`.** Removing the last one → empty state + `favorites=[]`. A one-letter query filters Favorites locally (no Radio Browser call), and Clear search restores. Key log dialog shows a KeyLog entry; it closes with `Close` and with system BACK while the app stays open. Local & USB with permission granted: `Rescan device & USB` plus content matching `LocalMusic.scanMediaStore` (`Shuffle all (n)` or the empty text). Bluetooth tab reaches a known state. **Portrait (1080x1920)**: tabs and the favorites un-heart flow work after `setOrientationPortrait()`, then rotation is restored. |
| `MiniPlayerAndShuffleTest` | Local songs are fixture copies in the app's cache dir with `local:file://…` ids, so no MediaStore content or permission is needed. Covers: the mini player shows the playing station; the mini-player heart adds and removes it; radio has no shuffle toggle; a local song shows `Shuffle off`, toggling it flips `shuffleModeEnabled` and `radio.xml shuffle`; shuffled next visits all 3 songs per cycle and wraps; **tapping a radio favorite turns shuffle off**; `Shuffle favorite songs (2)` plays only the 2 local favorites with shuffle on; the button disappears below 2 local songs. |
| `RadioServicePlaybackTest` | Real ExoPlayer against the local stream: READY + isPlaying, position advances, no error. Session metadata (title/station/artist) and the URI survive the controller hop. `seekToNext` → exactly +1 and `last_index` persisted. Previous wraps to last; next wraps to first. Double `seekToNext` within 400 ms → +1. Pause/play keeps the station. **HTTP 404 → ≥ 4 errors (`ERROR_CODE_IO_BAD_HTTP_STATUS`) → skip to the next station in about 24 s**, `last_index`=1. Connection closed → skip in about 12 s. A single dead station ends in IDLE + error without crashing or skipping, then recovers when a working station is picked. |
| `WheelKeyRouteTest` | The activity is in front and the service plays the fake queue. The test waits for `Pause` in the mini player so the activity's controller is connected. Covers: `KEYCODE_MEDIA_NEXT` via `sendKeyDownUpSync` → exactly +1 (`[Activity] KEYCODE_MEDIA_NEXT DOWN` + one `[Player] seekToNext`, mini player shows the new title); MEDIA_PREVIOUS wraps; CHANNEL_UP/DOWN in the foreground; mini-player Next/Previous; Play/Pause button; a single PLAY_PAUSE key pauses without switching; **BYD `APP_REV_DATA` `byte[]{4,1,3}` via in-process `sendBroadcast` → +1 on the emulator**; BYD frame + media key for the same press → +1 (debounce); unmapped or malformed BYD frames do nothing; a **real 2 s key hold** (injected with `UiAutomation`, auto-repeat every 50 ms) in the foreground → +1; MEDIA_NEXT with the app behind HOME → +1. |

---

## 4. Ignored tests ↔ bugs

Each test below asserts the **expected** behaviour. Before it was ignored, it was run once without `@Ignore` and failed on the bug assertion (details in `test-results/bugs.md`, section "[TEST] Regression tests…").

| Test | Bug | Failure when un-ignored |
|---|---|---|
| `RadioServicePlaybackTest.offline_outage_does_not_walk_through_the_queue` | **U7** (offline queue walk overwrites last_index) | `expected: 0 but was: 1`. Wi-Fi + data off via `svc`, fake stream refusing connections, 60 s. **First hermetic repro of U7.** The `finally` block re-enables the network. |
| `MiniPlayerAndShuffleTest.mini_player_heart_exposes_its_state` | **U8** (static `Favorite` desc) | no `Remove from favorites` node after hearting |
| `WheelKeyRouteTest.double_play_pause_never_switches_station` | [AUDIO] Double press of Play/Pause skips | `expected: qa:ok:1 but was: qa:ok:2` |
| `WheelKeyRouteTest.channel_up_switches_station_with_the_app_in_background` | [AUDIO] CHANNEL_UP/DOWN need window focus | no switch within 5 s |
| `WheelKeyRouteTest.channel_up_switches_station_while_key_log_dialog_is_open` | same (dialog-open variant) | no switch within 5 s |
| `WheelKeyRouteTest.holding_media_next_in_background_switches_one_station` | [AUDIO] Holding a key in background repeats the skip | `expected: 1 but was: 2`. **Now verified** (was "partly inferred"). |
| `RadioServiceRetryPolicyTest.next_during_error_back_off_starts_the_new_station_immediately` (JVM) | [AUDIO] Next during back-off waits for the stale timer | state stays `STATE_IDLE` after `seekToNext()` |
| `BydWheelKeyReceiverTest.every_app_rev_data_frame_is_logged` (JVM) | [AUDIO] APP_REV_DATA frames dropped without KeyLog | 0 log lines (expected 2) |

**New bugs found by this step:** none beyond those already filed. The suite turned two "inferred / not testable" items into verified ones:
- the background key-hold repeat (now measured: a 2 s hold skips 2 stations)
- the positive BYD `byte[]` path, which works

It also gives U7 a hermetic reproduction.

---

## 5. Design decisions

- **Radio Browser is not stubbed.** `RadioBrowser` is a Kotlin `object` with hard-coded `https://*.api.radio-browser.info` hosts. Stubbing it would need a production seam (an injectable base URL), which the guardrails forbid. So no assertion touches the country grid, country lists or search results. `MainActivity` still calls `loadCountries()` in the background during UI tests, but no test waits for it or reads its result, so the tests pass offline or with the API down. To cover U1–U6/U9 automatically, add an injectable base URL (or a `StationDirectory` interface) and serve fixtures from `MockWebServer`.
- **Network-tolerant `Assume` was not needed.** Every instrumented test is deterministic against loopback.
- **Same-process server.** Instrumentation runs inside the `dev.wheelradio` process, so ExoPlayer reaches `127.0.0.1:<port>` (cleartext is allowed by the manifest).
- **The pref seeding order matters.** `RadioService.onCreate` auto-plays `queue[last_index]` and falls back to the live `stations.json` when the queue is empty. Every class seeds a fake queue before the first bind, and tests set their own queue explicitly through the controller.
- **Debounce awareness.** `StationSwitchingPlayer` keeps `lastSkipAt` for the life of the service, so tests wait 500 ms before their first skip. Robolectric's clock starts near 0, so JVM tests advance it first; otherwise the first skip would look like a duplicate.

---

## 6. Flakiness observed and handling

- Three full, unfiltered instrumented runs were all green with no flaky failures: the initial run (36 tests at that point), the final run, and a back-to-back repeat (`BUILD SUCCESSFUL in 2m 26s`, `tests="46" failures="0" skipped="6"`). The JVM suite was green on all three full runs. No test needed a retry or a relaxed assertion.
- One genuine **test** mistake was fixed during healing: in `RadioServiceRetryPolicyTest` the back-off test assumed the first ExoPlayer error arrives within 1 s of *fake* time. The loader runs on a real thread, so the wait is now condition-based (`runUntil { playerError != null }`), followed by a 500 ms fake-time step past the debounce. Two other test-side fixes:
  - The session player could not be read through `getSessions()`, which is empty until a controller connects. It is now read through reflection on the private field.
  - The U8 assertion now uses `assertExists()` so the failure message is clear.
- Timing margins: dead-station skips use 60–90 s timeouts (measured 12–27 s). "Exactly one switch" checks wait 1 s after the switch to catch a late double skip. Playing-state checks poll up to 15 s (measured < 2 s on loopback).
- The only test that changes device state outside the app (`offline_outage…`, ignored) restores Wi-Fi and data in `finally`. The portrait test restores rotation in `finally` (`setOrientationNatural()` + `unfreezeRotation()`).

---

## 7. Recommended testTags (for the app owner; not added, per guardrails)

Today the tests use text and contentDescription: uppercase tab labels, `Key log`, `Close`, `Next`/`Previous`/`Play`/`Pause`/`Favorite`, `Shuffle on/off`, `Add/Remove from favorites` scoped with `hasAnyAncestor(hasText(<station>))`, and the empty-state strings. These break on copy or locale changes. Suggested tags (full table in `exploration-report.md` §7):

`tab_countries`, `tab_favorites`, `tab_local`, `tab_bluetooth`, `search_field`, `search_clear`, `keylog_button`, `keylog_dialog`, `keylog_close`, `country_tile_<CODE>`, `country_play_<CODE>`, `country_back`, `station_list`, `station_row_<id>`, `station_fav_<id>`, `empty_state`, `favorites_shuffle`, `local_scan_button`, `local_shuffle_all`, `mini_player`, `mini_title`, `mini_subtitle`, `mini_prev`, `mini_play_pause`, `mini_next`, `mini_fav`, `mini_shuffle`.

Also recommended:
- `Modifier.semantics { testTagsAsResourceId = true }` on the root, for UiAutomator.
- `stateDescription = "Playing"` on the current station row.
- A state-aware description on the mini-player heart (U8).

---

## 8. Files

Added:
- `app/src/test/java/dev/wheelradio/{BydWheelKeyReceiverTest, StationSwitchingPlayerTest, CuratedStationsTest, StationTest, StationRepositoryTest, StationAssetsTest, RadioServiceRetryPolicyTest}.kt`
- `app/src/test/resources/robolectric.properties`
- `app/src/androidTest/java/dev/wheelradio/{FakeStreamServer, TestSupport, MainActivityUiTest, MiniPlayerAndShuffleTest, RadioServicePlaybackTest, WheelKeyRouteTest}.kt`
- `app/src/androidTest/assets/qa_tone_440_90s.mp3`
- `test-results/test-suite-report.md` (this file)

Changed:
- `gradle/libs.versions.toml`
- `app/build.gradle.kts` (runner, testOptions, test dependencies only)
- `.github/workflows/build.yml` (unit-test step + report upload)
- `test-results/bugs.md` (verification note on the key-hold bug, plus a "[TEST] Regression tests" tracking section)

Logs: `QA scratch folder tests/ (local only, not committed)` (`unit_final_*.log`, `at_final.log`, `at_final2.log`, `at_confirm.log`, `at_new.log`, `unit_confirm*.log`).
