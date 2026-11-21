# Radio_BYD QA bugs

Screenshots and dumps referenced below are in
`QA scratch folder ui/ (local only, not committed)`.
Device: emulator-5554, API 29, 1920x1080 @160dpi (portrait = `user_rotation 1`).

## [UI] Search query is ignored while a country station list is open
- **Severity:** Medium
- **Steps:**
  1. By countries tab → tap the Armenia tile.
  2. Tap the search field and type `jazz`; wait 5 s.
- **Expected:** search results for "jazz", or the open country list filtered by "jazz".
- **Actual:** the Armenia list stays unchanged. `CountriesTab` checks `openCountry != null` before `q.length >= 2`, so the results never render. The network search still runs in the background. The results only appear after pressing Back.
- **Evidence:** `L30_search_while_country_open.png/.xml`; `Ui.kt` `CountriesTab` `when` order.

## [UI] Portrait: search country chips overflow and crush the last chip
- **Severity:** Medium
- **Steps:**
  1. Rotate to portrait (1080x1920).
  2. By countries → type `Islands` in search; hide the keyboard.
- **Expected:** chips wrap or scroll horizontally; the result list starts right below the chips.
- **Actual:** the chip `Row` is neither scrollable nor wrapping. The 6th chip is squeezed to `[1040,158][1080,639]` (40 px wide, 481 px tall, text unreadable and practically untappable). The 5th chip wraps to 2 lines. The station list is pushed down to y=657, leaving ~450 px of empty space. Landscape is fine.
- **Evidence:** `P05_search_islands.png/.xml` (portrait), `L49_search_islands.xml` (landscape OK); `Ui.kt` `SearchResults` `Row(...)`.

## [UI] Curated Armenian stations unreachable when the countries request fails
- **Severity:** Medium
- **Steps:**
  1. `adb shell svc wifi disable; adb shell svc data disable`
  2. `am force-stop dev.wheelradio; am start -n dev.wheelradio/.MainActivity`; wait ~25 s.
- **Expected:** the bundled Armenian stations (`assets/stations_am.json`, 46 stations) stay browsable. The code comment says "curated alone still works when the directory is down".
- **Actual:** the whole By countries tab is replaced by `Couldn't load countries. Check the internet connection.` + `Try again`. No tile is rendered, so `stationsFor("AM")`'s curated fallback can never be reached. In a car with patchy data, this blocks the owner's main use case until the network returns.
- **Evidence:** `L51_offline.png/.xml`; `AppModel.loadCountries` / `CountriesTab`.

## [UI] Country tile station count changes after opening the country
- **Severity:** Low
- **Steps:** By countries → note `Russia 2494 Stations`, `Germany 5620 Stations`, `Armenia 56 Stations` → open each country → go back.
- **Expected:** a consistent count.
- **Actual:** the tiles now read `292`, `289` and `51` Stations. The grid count is the directory total (+ curated, ignoring duplicates). The list is capped at `limit=300` and deduplicated, and `showCountry` overwrites the count. The counts reset again when the activity is recreated.
- **Evidence:** `L01_countries.xml` vs `L08_grid.xml`; `RadioBrowser.stationsByCountry` `limit=300`.

## [UI] Search IME action does nothing; the keyboard can only be dismissed with system Back
- **Severity:** Low
- **Steps:** tap search → type `jazz` → press Enter/Done on the IME.
- **Expected:** the keyboard hides (ImeAction.Search/Done).
- **Actual:** nothing happens; `mInputShown=true` stays. Clearing with × also keeps the IME open. In landscape the IME covers ~40 % of the screen.
- **Evidence:** `L26_search_jazz.png`; `TopBar` TextField has no `keyboardOptions`/`keyboardActions`.

## [UI] No UI to choose a music folder: USB folder fallback is unreachable
- **Severity:** Medium
- **Steps:** open Local & USB. Look for a "choose folder" control.
- **Expected:** a folder picker (`ActivityResultContracts.OpenDocumentTree`) so USB sticks that MediaStore does not index can be scanned. `LocalMusic.scanFolder` documents this as the fallback.
- **Actual:** only `Rescan device & USB` / `Shuffle all (n)` exist. `AppModel.setMusicFolder()` has no caller, so `music_folder` is never written and `scanFolder` never runs. Source-inferred: not testable on the emulator without a real non-indexed USB stick.
- **Evidence:** `grep setMusicFolder` → only its definition in `AppModel.kt`; `L42_local_after_rescan.png`.

## [UI] Offline, the player walks through the whole queue and the last station is lost
- **Status: FIXED on branch `fix/playback-bugs`.** `RadioService.onPlayerError` now asks the new `NetworkMonitor` whether the default network has internet. If a network stream (`http`/`https`) fails while offline, it keeps the station, schedules no retries and does not skip; when connectivity returns it retries the same station with a fresh set of retries. With the network up, the old 3-retries-then-skip rule is unchanged, so dead stations are still skipped. Local/USB files never wait for the network.
  - Regression tests: `androidTest/RadioServicePlaybackTest.offline_outage_does_not_walk_through_the_queue` (no longer `@Ignore`d, passes), and the new hermetic JVM test `test/RadioServiceRetryPolicyTest.offline_stream_errors_keep_the_station_and_retry_when_the_network_returns`, which fails without the fix (`expected: 0 but was: 1`).
  - Manual check on the emulator: `SomaFM Groove Salad` playing, Wi-Fi + data off for 60 s (session goes to ERROR, the station is kept, key log `offline, keeping the station until the network returns`), network back on, and the same station is PLAYING again within 1 s (`network back, retrying the same station`).
  - Remaining gap: a network that keeps its INTERNET capability but carries no data (weak cellular signal) still looks "online", so the old retry/skip rule applies there. During the outage the UI shows no "waiting for network" state; that belongs to the separate "no error state" bug below.
- **Severity:** High for a car (raised from Medium after step 2's confirmation)
- **Steps:**
  1. Play `SWR3` from the Germany list (queue index 1).
  2. Disable wifi and data. Force-stop and relaunch (or just wait). Wait ~60 s.
  3. Re-enable the network.
- **Expected:** the player keeps retrying the user's station (or pauses) and resumes it when connectivity returns.
- **Actual:** after 3 retries per station, `RadioService.onPlayerError` calls `seekToNextMediaItem()`. Offline, every station fails, so it cycles `SWR3 → 80s80s Radio → … → 1LIVE`, and `onMediaItemTransition` persists `last_index=6`. Back online, `1LIVE` plays instead of `SWR3`. The key log shows repeated `[Player] error: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`.
- **Evidence:** `L51_offline.xml` (mini player `80s80s Radio` / `Connecting…`), `L54_online_again.xml` (`1LIVE`), `L55_keylog_errors.xml`, radio.xml `last_index` 1 → 6.
- **[AUDIO] Confirmed by audio-session-tester (step 2).** Reproduced mid-playback without a force-stop.
  - **75 s outage** while `SWR3` (Germany queue idx 1) was playing: BUFFERING at +6 s; first `[Player] error: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` at +19 s; 4 errors per station; **SWR3 → `80s80s Radio` at +41 s → `Deutschlandfunk` at +66 s**. Back online at +75 s, DLF played instead of SWR3. `last_index` 1 → 3.
  - **15 s outage**: the same station recovered (+22 s) with no app-level error, so short drop-outs are fine.
  - **Threshold:** an outage of about 40 s loses the station. Each further ~24.5 s offline costs one more.
  - **Suggested severity:** Medium → High for a car (tunnels and underground parking easily exceed 40 s).
  - **Root cause:** `RadioService.onPlayerError` treats a network-wide outage (`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` / `_TIMEOUT` with no default network) the same as a dead station. Fix direction: check `ConnectivityManager` before skipping and wait for `onAvailable`; do not persist `last_index` for automatic skips.
  - **Evidence:** `test-results/audio-session-report.md` section 4.

## [UI] Mini-player favorite button contentDescription does not reflect state
- **Severity:** Low (accessibility / testability)
- **Steps:** play any station → tap the mini-player heart → inspect the node.
- **Expected:** `Add to favorites` / `Remove from favorites` (as in the list rows), or a stateDescription.
- **Actual:** always `Favorite`. Only the tint (white vs red) changes, so TalkBack users and UI tests cannot tell the state.
- **Evidence:** `L31_hearted.xml` (filled heart, desc `Favorite`); `Ui.kt` `MiniPlayer`.

## [UI] Curated Armenian stations are not found by search
- **Severity:** Low
- **Steps:** By countries → type `Arm` (or a curated name such as `Ավտոռադիո`).
- **Expected:** curated `am:*` stations appear in the results.
- **Actual:** only Radio Browser API results appear (`Radio Armisa`, `Armin van Buuren (Record)`, …). `AppModel.updateQuery` only calls `RadioBrowser.search`; curated stations are never searched. The `🇦🇲 Armenia` chip is the only way in.
- **Evidence:** `L28_search_arm.xml`.

## [AUDIO] A station whose host never answers stays "Connecting…" for about 9 minutes before the dead-station skip
- **Severity:** Medium
- **Steps:**
  1. By countries → Armenia list → play `Ռադիո Ջազ` (`am:16`, `http://am.radioaurora.am:8010/jz`). The same happens with `Ayp-FM` (`am:35`).
  2. Watch `adb shell dumpsys media_session` and `adb logcat -s WheelRadio`.
- **Expected:** a dead station is abandoned within about 20–30 s (as it is for 404 or refused connections), so the driver is not left in silence.
- **Actual:** the player stays in BUFFERING with `Connecting…` for **130 s** before the first `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT`. The 2nd error comes 133 s later. Each attempt is ExoPlayer’s 8 s connect timeout × several resolved addresses × ExoPlayer’s internal load retries. With the app’s 3 back-off retries, the skip comes at about +535 s (about 9 min). The next station in this list is also black-holed.
- **Evidence:** logcat `14:37:01.7 tap` → `14:39:12.014 [Player] error: ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` (`SocketTimeoutException: failed to connect to am.radioaurora.am/2606:4700:3035::ac43:acb2 (port 8010) … after 8000ms`) → `14:41:25.171` 2nd error. `audio-session-report.md` section 4. Fix direction: an overall "time to first audio" watchdog in `RadioService` (for example 15 s in BUFFERING → count as an error), and/or a custom `LoadErrorHandlingPolicy` with fewer retries for live streams.

## [AUDIO] Dead streams in the curated / bundled station lists
- **Severity:** Low (content). Each one costs the driver about 26 s of silence before the auto-skip, or about 9 min for timeouts (see above).
- **Steps:** play each station on the emulator (Armenia list rows, or a temporary favorites queue).
- **Expected:** curated stations play.
- **Actual (device, 2026-11-21):**
  - `am:01` Ավտոռադիո `https://de.auroramedia.am/autoradio.mp3` → **HTTP 404**
  - `am:07` Ռադիո Ազալիա `https://stream-153.zeno.fm/s865660v7hhvv` → **HTTP 401**
  - `am:16` Ռադիո Ջազ `http://am.radioaurora.am:8010/jz` → **connect timeout**
  - `am:35` Ayp-FM `https://stric6.streamakaci.com/radioayp.mp3` → **connect timeout**
  - `stations.json` `jazz24` `https://live.wostreaming.net/direct/ppm-jazz24aac-ibc1` → **DNS failure** (`CONNECTION_FAILED`)
  - The other 41 Armenian URLs answered `200/206 audio/*` from the host. `am:40` (`;stream.nsv`) and Radio Paradise also play on the device.
- **Evidence:** logcat `InvalidResponseCodeException: Response code: 404` / `401`; host probe `QA scratch folder audio/curl_results.txt`. The lists were not edited (guardrail). The owner needs to update the URLs.

## [AUDIO] Double press of Play/Pause skips to the next station
- **Severity:** Medium
- **Steps:**
  1. Play any station (foreground or background).
  2. `adb shell input keyevent 85 85` (two presses within about 300 ms). Also `79 79` (HEADSETHOOK).
- **Expected:** pause then resume, or pause only. Never a station change.
- **Actual:** the station changes (Δ index +1, `[Player] seekToNext`). Media3’s default media-button handling treats a PLAY_PAUSE / HEADSETHOOK double-tap as "next". On a steering wheel, a quick double press, a bouncing switch, or BYD delivering one press twice switches the station. Presses 420 ms apart work correctly (pause → play).
- **Evidence:** foreground `Sunshine Live - Techno` (idx 22) → `WDR 2 Rheinland` (idx 23), only `[Activity] KEYCODE_MEDIA_PLAY_PAUSE` DOWN/UP ×2 then `[Player] seekToNext`. Background: idx 20 → 21 for `85 85`, 21 → 22 for `79 79`. `audio-session-report.md` section 3. Fix direction: handle PLAY_PAUSE in the session callback / override media-button handling so a double-tap is not mapped to next.

## [AUDIO] CHANNEL_UP / CHANNEL_DOWN wheel keys do nothing unless the activity window has focus
- **Severity:** Medium (if the DiLink wheel sends CHANNEL keys; the app maps them explicitly, so the author expects it to)
- **Steps:**
  1. Play a station. Press HOME (or swipe the app from recents, or open the in-app `Key log` dialog).
  2. `adb shell input keyevent 166` and `167`.
- **Expected:** next / previous station, as when the activity is focused.
- **Actual:** nothing happens and nothing is logged. CHANNEL_UP/DOWN are handled only in `MainActivity.dispatchKeyEvent`. They are not media-session keys, so the system never routes them to the MediaSession. MEDIA_NEXT/PREVIOUS (87/88) do work in the background through the session.
- **Side effect:** while the `Key log` dialog is open, the dialog window takes the keys. The `[Activity]` route is never logged and 166 is ignored. The diagnostic screen therefore hides the route it is meant to reveal.
- **Evidence:** background `KEY 166 | before idx=20 | after idx=20`, no KeyLog line. Foreground: `[Activity] KEYCODE_CHANNEL_UP DOWN` → `[Player] seekToNext`. With the dialog open: `87` → only `[Player] seekToNext`; `166` → nothing. `QA scratch folder audio/s3_keylog_dialog_open.xml`.

## [AUDIO] Failed station shows no error state; single-station queue stays silent with a plain Play button
- **Severity:** Low (UX). The UI is not frozen.
- **Steps:**
  1. Favorites containing one dead URL (test data `http://127.0.0.1:9/dead.mp3`) → tap it.
  2. Wait about 30 s.
- **Expected:** a visible "Station unavailable / Retry" state (or similar).
- **Actual:**
  - During the whole retry cycle the mini player alternates between `Connecting…` and a blank subtitle. The button always reads `Play`, so it looks paused.
  - After the 4th error the session stays in `state=7 error=Source error` and the UI shows only the station name + `Play`.
  - Tapping Play (or wheel `85`) makes exactly one new attempt. The retry counter is not reset because `onIsPlayingChanged(true)` never fires.
  - Next / Previous (`87`, mini-player Next) log `seekToNext` but do nothing (single item, player IDLE).
- **Evidence:** `QA scratch folder audio/s5_single_dead_final.png/.xml`, `s5_dead_midretry.png/.xml`; logcat errors at 14:41:52 / 57 / 04 / 13, then a single retry at 14:42:46 after the Play tap.

## [AUDIO] Next/Previous during an error back-off waits for the stale retry timer
- **Severity:** Low
- **Steps:** queue with a dead first station → wait for the first error (state 7) → press `87` at once.
- **Expected:** the next station starts buffering immediately.
- **Actual:** `StationSwitchingPlayer.skip` only seeks and sets `playWhenReady`. It does not call `prepare()` while the player is IDLE after an error. The new station starts only when the earlier `handler.postDelayed({ exo.prepare() }, 2000L * retries)` fires: BUFFERING +1.8 s, PLAYING +2.7 s here; up to 6 s in the 3rd back-off window. In a single-item queue there is no pending timer, so it never starts (see above). The pending callbacks are also never cancelled on a station change.
- **Evidence:** `14:43:30.070 error` → `14:43:30.378 [Player] seekToNext` → `Հանրային ռադիո` state 6 at +1.8 s, state 3 at +2.7 s.

## [AUDIO] BYD APP_REV_DATA frames with an unexpected payload are dropped without any KeyLog entry
- **Severity:** Low (diagnostics)
- **Steps:** `adb shell am broadcast -a APP_REV_DATA --eia list 4,1,3` (also `--ela`, `--es`, `--ei`, a short `4,1`, wrong group `5,1,3`).
- **Expected:** per the class comment ("every frame is logged to KeyLog"), each received broadcast shows up in the Key log, so the real BYD format can be learned in the car.
- **Actual:**
  - Delivered (the receiver is registered and exported) but silently ignored: no KeyLog line, no action.
  - `onReceive` returns before logging if `list` is not a `ByteArray` or the frame is not group 4 / sub 1. If the head unit sends `int[]`, a different extra name, or another group, the in-car log shows nothing.
  - The receiver is exported with no sender permission, so any app can skip stations. This is probably needed for BYD, but worth noting.
  - The positive `byte[]` path cannot be delivered with `am` (it has no byte-array extra). It needs a unit or instrumented test.
- **Evidence:** `dumpsys activity broadcasts` (`Action: "APP_REV_DATA"` → dev.wheelradio, historical `BroadcastRecord{… APP_REV_DATA}`); before/after state unchanged; `logcat -s WheelRadio` empty.

## [AUDIO] Holding a wheel key in the background repeats the skip (session route ignores auto-repeat)
- **Severity:** Low (partly inferred)
- **Steps:** app in background → `adb shell input keyevent --longpress 87`.
- **Expected:** one station per press, as the Activity route does (`repeatCount == 0` check).
- **Actual:** the MediaSession route forwards the auto-repeat DOWN to `seekToNext`. Here: `[Player] seekToNext` + `[Player] seekToNext ignored (duplicate within 400ms)`, so only the debounce prevented a 2nd skip. A real hold (repeats every 50 ms after 500 ms) would skip about one station per 400 ms held [S]. A true hold cannot be simulated on this emulator (there is no key input device).
- **Evidence:** `audio-session-report.md` section 3, `--longpress 87` row.
- **[TEST] Verified by test-writer (step 3)** — now [V], no longer inferred. `WheelKeyRouteTest.holding_media_next_in_background_switches_one_station` injects a real 2 s hold through `UiAutomation.injectInputEvent` (DOWN, then auto-repeat DOWNs every 50 ms after 500 ms, then UP) with the app behind HOME. Result: **Δ index = 2** (expected 1). The same hold with the activity in front gives Δ = 1 (`holding_media_next_in_foreground_switches_one_station`, passes), because `dispatchKeyEvent` checks `repeatCount == 0`. A longer hold costs more stations.

## [TEST] Regression tests for the bugs above (step 3: test-writer)
- **Severity:** n/a (tracking note, not a new defect)
- Each confirmed bug that can be reproduced hermetically now has a test that asserts the **expected** behaviour. The test is marked `@Ignore("Known bug <ID> ...")` so the suite stays green while the bug is open. Before ignoring, each test was run once without `@Ignore` and **failed on the bug assertion** (2026-11-21, emulator-5554). Remove the `@Ignore` when the bug is fixed.

| Bug | Test | Observed failure when un-ignored |
|---|---|---|
| U7 offline queue walk (**fixed**, test now enabled) | `androidTest/RadioServicePlaybackTest.offline_outage_does_not_walk_through_the_queue` (Wi-Fi + data off via `svc`, local fake stream refusing connections, 60 s) | Failed with `expected: 0 but was: 1` before the fix; passes on `fix/playback-bugs`. |
| U8 mini-player heart description | `androidTest/MiniPlayerAndShuffleTest.mini_player_heart_exposes_its_state` | no node `Remove from favorites` after hearting; the description stays `Favorite` |
| [AUDIO] Double Play/Pause skips | `androidTest/WheelKeyRouteTest.double_play_pause_never_switches_station` | `expected: qa:ok:1 but was: qa:ok:2` |
| [AUDIO] CHANNEL_UP/DOWN need window focus | `WheelKeyRouteTest.channel_up_switches_station_with_the_app_in_background`, `...channel_up_switches_station_while_key_log_dialog_is_open` | no switch within 5 s in both cases |
| [AUDIO] Hold repeats skip in background | `WheelKeyRouteTest.holding_media_next_in_background_switches_one_station` | `expected: 1 but was: 2` |
| [AUDIO] Next during back-off waits for stale timer | `test/RadioServiceRetryPolicyTest.next_during_error_back_off_starts_the_new_station_immediately` (Robolectric, fake clock) | `playbackState` stays `STATE_IDLE` (1) after `seekToNext()` |
| [AUDIO] APP_REV_DATA frames dropped without KeyLog | `test/BydWheelKeyReceiverTest.every_app_rev_data_frame_is_logged` | 0 KeyLog lines for an `int[]` frame and a group-5 frame (expected 2) |

Not covered by an automated test:
- U1–U6 and U9 need the live Radio Browser directory. `RadioBrowser` is an `object` with hard-coded hosts, so it cannot be stubbed without a production seam.
- The ~9 min hang on a host that never answers (`SocketPolicy.NO_RESPONSE`) would take minutes per run.
- Dead curated URLs are content: they are checked by the network probe in step 2, never in tests.

Positive path verified for the first time: a real `byte[]` `APP_REV_DATA` frame `[4,1,0x03]` switches exactly one station, both in Robolectric (`RadioServiceRetryPolicyTest.byd_next_frame_switches_the_service_player_once`) and on the emulator through an in-process `sendBroadcast` (`WheelKeyRouteTest.byd_app_rev_data_next_frame_switches_one_station`).
