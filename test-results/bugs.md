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
- **Status: FIXED on branch `fix/failure-handling`.** The mini player's heart now reads `Add to favorites` / `Remove from favorites`, the same as the list hearts. Test: `androidTest/MiniPlayerAndShuffleTest.mini_player_heart_exposes_its_state` (no longer `@Ignore`d, passes; toggles both ways). Tests that selected the old `Favorite` label were updated.
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
- **Status: FIXED on branch `fix/failure-handling`.** The new `StreamRecovery` (used by `RadioService`) runs a "first audio" watchdog: if an attempt is still BUFFERING 20 s after it started, and has not played yet, it counts as a failure. A stalled station gets one retry (each attempt already cost 20 s), then the usual skip. The watchdog only covers the start of an attempt; a stream that stalls mid-play is still left to ExoPlayer's own reconnect.
  - Real network, `am:16` `Ռադիո Ջազ` (`http://am.radioaurora.am:8010/jz`) seeded as the queue: `no audio after 20 s` twice, then `station unavailable, skipping to the next one` at **+44 s** (was about +535 s), then `Ռադիո Մարիամ` PLAYING.
  - Tests: `test/RadioServiceRetryPolicyTest.station_that_never_answers_is_skipped_after_the_no_audio_watchdog` (local socket that never replies, fake clock) and `androidTest/RadioServicePlaybackTest.station_that_never_answers_is_skipped_within_a_minute` (`MockWebServer` `NO_RESPONSE`, skipped after 42 s).
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
- **Status: FIXED on branch `fix/playback-bugs`.** `RadioService` now overrides `MediaSession.Callback.onMediaButtonEvent`. Media3 calls it before its own double-tap logic, and only for ACTION_DOWN. PLAY_PAUSE and HEADSETHOOK are handled there with `Util.handlePlayPauseButtonAction` (play / pause, re-preparing an errored or ended player as Media3 did) and consumed, so a double press can no longer become "next". Each press is logged as `[Session] <key>`. Tests: `WheelKeyRouteTest.double_play_pause_never_switches_station` (enabled, passes) and the new `double_headsethook_in_background_never_switches_station`. Manual: background `input keyevent 85 85` and `79 79` stay on `SomaFM Groove Salad`.
- **Severity:** Medium
- **Steps:**
  1. Play any station (foreground or background).
  2. `adb shell input keyevent 85 85` (two presses within about 300 ms). Also `79 79` (HEADSETHOOK).
- **Expected:** pause then resume, or pause only. Never a station change.
- **Actual:** the station changes (Δ index +1, `[Player] seekToNext`). Media3’s default media-button handling treats a PLAY_PAUSE / HEADSETHOOK double-tap as "next". On a steering wheel, a quick double press, a bouncing switch, or BYD delivering one press twice switches the station. Presses 420 ms apart work correctly (pause → play).
- **Evidence:** foreground `Sunshine Live - Techno` (idx 22) → `WDR 2 Rheinland` (idx 23), only `[Activity] KEYCODE_MEDIA_PLAY_PAUSE` DOWN/UP ×2 then `[Player] seekToNext`. Background: idx 20 → 21 for `85 85`, 21 → 22 for `79 79`. `audio-session-report.md` section 3. Fix direction: handle PLAY_PAUSE in the session callback / override media-button handling so a double-tap is not mapped to next.

## [AUDIO] CHANNEL_UP / CHANNEL_DOWN wheel keys do nothing unless the activity window has focus
- **Status: PARTLY FIXED on branch `fix/playback-bugs`.**
  - **Key log open: fixed.** The key log is now an overlay in the activity's own window instead of a `Dialog` window, so keys keep reaching `MainActivity.dispatchKeyEvent`. CHANNEL_UP/DOWN work with it open, and the log shows the real `[Activity]` route. It still closes on Close, Back or a tap outside. Tests: `WheelKeyRouteTest.channel_up_switches_station_while_key_log_dialog_is_open` (enabled, passes) and the new `MainActivityUiTest.key_log_closes_with_a_tap_outside_but_not_on_the_panel`.
  - **App in the background: not fixable inside the app.** Android routes only media keys (`KeyEvent.isMediaSessionKey`: play, pause, next, previous, …) to a background MediaSession. CHANNEL_UP/DOWN go to whichever window has focus, so a background app never receives them. The only way to catch them would be an AccessibilityService with key filtering, which the driver has to enable by hand in Settings; that is a product decision. `channel_up_switches_station_with_the_app_in_background` stays `@Ignore`d as a platform limit. Whether this matters depends on what the BYD wheel actually sends: if it sends MEDIA_NEXT/PREVIOUS (or the `APP_REV_DATA` broadcast), the background route already works. The in-car key log will tell.
- **Severity:** Medium (if the DiLink wheel sends CHANNEL keys; the app maps them explicitly, so the author expects it to)
- **Steps:**
  1. Play a station. Press HOME (or swipe the app from recents, or open the in-app `Key log` dialog).
  2. `adb shell input keyevent 166` and `167`.
- **Expected:** next / previous station, as when the activity is focused.
- **Actual:** nothing happens and nothing is logged. CHANNEL_UP/DOWN are handled only in `MainActivity.dispatchKeyEvent`. They are not media-session keys, so the system never routes them to the MediaSession. MEDIA_NEXT/PREVIOUS (87/88) do work in the background through the session.
- **Side effect:** while the `Key log` dialog is open, the dialog window takes the keys. The `[Activity]` route is never logged and 166 is ignored. The diagnostic screen therefore hides the route it is meant to reveal.
- **Evidence:** background `KEY 166 | before idx=20 | after idx=20`, no KeyLog line. Foreground: `[Activity] KEYCODE_CHANNEL_UP DOWN` → `[Player] seekToNext`. With the dialog open: `87` → only `[Player] seekToNext`; `166` → nothing. `QA scratch folder audio/s3_keylog_dialog_open.xml`.

## [AUDIO] Failed station shows no error state; single-station queue stays silent with a plain Play button
- **Status: FIXED on branch `fix/failure-handling`.**
  - **Status line:** `StreamRecovery` publishes a status line through the MediaSession's session extras, and the mini player shows it in amber in place of "Connecting…" or the song title:
    - `Can't reach the station – retrying (n/3)…` (`n/1` for a station that never answers)
    - `No internet – the station resumes when it's back`
    - `Station unavailable – press Play to try again`
    - It clears as soon as something plays or the station changes.
  - **Play on a failed station:** it now gets a fresh set of retries. `StationSwitchingPlayer.prepare()` reports a user retry, which resets the count and cancels pending timers, and the mini player prepares an idle player before `play()`.
  - **Next / Previous in a one-station queue:** they now start a new attempt (see the next bug).
  - **Tests:**
    - `androidTest/PlaybackStatusUiTest`: retrying → unavailable → Play retries; the status clears when a working station plays.
    - `test/RadioServiceRetryPolicyTest.status_line_reports_retries_then_unavailable_for_a_single_dead_station`
    - `play_on_a_failed_station_gets_a_fresh_set_of_retries` (4 → 8 errors)
    - `offline_status_line_says_waiting_for_internet`
    - Status assertions added to `RadioServicePlaybackTest`.
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
- **Status: FIXED on branch `fix/failure-handling`.** `StationSwitchingPlayer.skip()` now prepares an idle player right after seeking, and every station change or user retry cancels the pending back-off timer (`StreamRecovery.reset`). Test: `test/RadioServiceRetryPolicyTest.next_during_error_back_off_starts_the_new_station_immediately` (no longer `@Ignore`d, passes).
- **Severity:** Low
- **Steps:** queue with a dead first station → wait for the first error (state 7) → press `87` at once.
- **Expected:** the next station starts buffering immediately.
- **Actual:** `StationSwitchingPlayer.skip` only seeks and sets `playWhenReady`. It does not call `prepare()` while the player is IDLE after an error. The new station starts only when the earlier `handler.postDelayed({ exo.prepare() }, 2000L * retries)` fires: BUFFERING +1.8 s, PLAYING +2.7 s here; up to 6 s in the 3rd back-off window. In a single-item queue there is no pending timer, so it never starts (see above). The pending callbacks are also never cancelled on a station change.
- **Evidence:** `14:43:30.070 error` → `14:43:30.378 [Player] seekToNext` → `Հանրային ռադիո` state 6 at +1.8 s, state 3 at +2.7 s.

## [AUDIO] BYD APP_REV_DATA frames with an unexpected payload are dropped without any KeyLog entry
- **Status: FIXED on branch `fix/failure-handling`.** `BydWheelKeyReceiver` now logs every payload that is not a `byte[] [4, 1, code]` wheel-key frame as `[BYD] unrecognised: …`, with its type and contents (`byte[]` hex, `int[]`, `long[]`, `String`, other, or the extra names when there is no `list` extra). It still never acts on them.
  - **Flood protection:** the head unit may broadcast other data constantly, so each distinct payload is logged once, up to 50, then a single "not logging any further ones" line. Wheel-key frames are always logged, so they can't be pushed out of the 100-line key log.
  - **Manual check:** step 2's `am broadcast -a APP_REV_DATA` commands (`--eia`, `--ela`, `--es`, `--ei`, no `list`) now each show one line in the key log; a repeated `int[]` frame is not logged twice; no station change.
  - **Tests:** `test/BydWheelKeyReceiverTest.every_app_rev_data_frame_is_logged` (no longer `@Ignore`d, passes), plus new tests for de-duplication and the 50-payload cap with a wheel key after it. The three tests that asserted "ignored silently" now expect a single log line and no action.
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
- **Status: FIXED on branch `fix/playback-bugs`.** `RadioService.onMediaButtonEvent` consumes every auto-repeat DOWN (`repeatCount > 0`), the same rule `MainActivity.dispatchKeyEvent` applies, and logs `[Session] <key> held, repeats ignored` once per hold. Test: `WheelKeyRouteTest.holding_media_next_in_background_switches_one_station` (enabled, passes; a 2 s hold now moves exactly one station). Manual: background `input keyevent --longpress 87` moved exactly one station.
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
| U8 mini-player heart description (**fixed**, test now enabled) | `androidTest/MiniPlayerAndShuffleTest.mini_player_heart_exposes_its_state` | Failed with no node `Remove from favorites` (label stayed `Favorite`) before the fix; passes on `fix/failure-handling`. |
| [AUDIO] Double Play/Pause skips (**fixed**, test now enabled) | `androidTest/WheelKeyRouteTest.double_play_pause_never_switches_station` | Failed with `expected: qa:ok:1 but was: qa:ok:2` before the fix; passes on `fix/playback-bugs`. |
| [AUDIO] CHANNEL_UP/DOWN with key log open (**fixed**, test now enabled) | `WheelKeyRouteTest.channel_up_switches_station_while_key_log_dialog_is_open` | Failed with no switch within 5 s before the fix; passes on `fix/playback-bugs`. |
| [AUDIO] CHANNEL_UP/DOWN in background (**platform limit**, stays ignored) | `WheelKeyRouteTest.channel_up_switches_station_with_the_app_in_background` | No switch within 5 s; Android never routes these keys to a background app. |
| [AUDIO] Hold repeats skip in background (**fixed**, test now enabled) | `WheelKeyRouteTest.holding_media_next_in_background_switches_one_station` | Failed with `expected: 1 but was: 2` before the fix; passes on `fix/playback-bugs`. |
| [AUDIO] Next during back-off waits for stale timer (**fixed**, test now enabled) | `test/RadioServiceRetryPolicyTest.next_during_error_back_off_starts_the_new_station_immediately` (Robolectric, fake clock) | Failed with `playbackState` staying `STATE_IDLE` (1) after `seekToNext()` before the fix; passes on `fix/failure-handling`. |
| [AUDIO] APP_REV_DATA frames dropped without KeyLog (**fixed**, test now enabled) | `test/BydWheelKeyReceiverTest.every_app_rev_data_frame_is_logged` | Failed with 0 KeyLog lines for an `int[]` and a group-5 frame (expected 2) before the fix; passes on `fix/failure-handling`. |

Not covered by an automated test:
- U1–U6 and U9 need the live Radio Browser directory. `RadioBrowser` is an `object` with hard-coded hosts, so it cannot be stubbed without a production seam.
- The ~9 min hang on a host that never answers (`SocketPolicy.NO_RESPONSE`) would take minutes per run.
- Dead curated URLs are content: they are checked by the network probe in step 2, never in tests.

Positive path verified for the first time: a real `byte[]` `APP_REV_DATA` frame `[4,1,0x03]` switches exactly one station, both in Robolectric (`RadioServiceRetryPolicyTest.byd_next_frame_switches_the_service_player_once`) and on the emulator through an in-process `sendBroadcast` (`WheelKeyRouteTest.byd_app_rev_data_next_frame_switches_one_station`).

## [AUDIO] "System UI keeps stopping" while a station with a 320 px wide logo plays — FIXED
- **Severity:** High (on a 1920x1080 Android 10 head unit the system UI crash-loops for as long as the station is the active media session).
- **Steps:** on `BYD_DiLink_API29` (1920x1080), play Radio Marshall (Armenia, logo `radiomarshall.am/images/logo.png`, 320x87).
- **Expected:** the station plays; the system UI is unaffected.
- **Actual:** "System UI keeps stopping" about every 3 s. `logcat -b crash`: `IllegalArgumentException: cannot use a recycled source in createBitmap` at `com.android.systemui.statusbar.MediaArtworkProcessor.processArtwork(MediaArtworkProcessor.kt:54)`.
- **Cause:** an Android 10 SystemUI bug. It scales session artwork so the longer side is `max(screenW, screenH) / 6` (320 px here). When the artwork already has that size, `createScaledBitmap` returns the artwork itself, SystemUI recycles it, and the next pass crashes. Any station logo with a longer side of exactly 320 px triggers it.
- **Fix:** `SystemUiSafeBitmapLoader` (session bitmap loader) scales such artwork up by 1 px, so SystemUI always works on a copy. Verified on the emulator: 0 SystemUI crashes in 45 s with Radio Marshall playing (about 15 before). Unit tests: `SystemUiSafeBitmapLoaderTest`.

## [AUDIO] Bluetooth tab shows "Nothing playing" while the car's Bluetooth Music plays the phone (BYD Yuan Plus)
- **Severity:** High (the Bluetooth tab and wheel-to-Bluetooth routing don't work on the real car).
- **Steps:** on a BYD Yuan Plus (DiLink, Android 10), play music from a phone through the car's own Bluetooth Music app, then open Wheel Radio → Bluetooth.
- **Expected:** the phone's song (title, artist, art) with working play / pause / next / previous.
- **Actual:** "From your phone · Bluetooth (AVRCP)", "Nothing playing". "Open the car's Bluetooth music" offered `BT Phone · com.byd.bluetoothcall`.
- **Cause:** the AOSP AVRCP controller service (`com.android.bluetooth`) accepts the connection on DiLink but stays empty, because BYD's own app plays Bluetooth. `BluetoothBridge` stopped there and never looked at other media sessions. The car-app finder scored "bluetooth" in `bluetoothcall` as a match and kept one activity per package.
- **Fix (0.5.3, still to be confirmed in the car):** `BluetoothBridge` watches the AVRCP service and every other app's media session at once and follows the one that is playing (or has a song). The tab asks for notification access when that is what hides the sessions. The finder drops call / phone apps, prefers "Bluetooth Music", and keeps separate activities. Unit test: `CarBluetoothAppTest`.
- **Evidence still needed:** the key log's `[BT] media sessions: …` and `[BT] using …` lines from the car.
- **Car test, 2026-11-24 (build 0.5.2, before the fix; key-log text `connected to AVRCP controller service` and BT Phone listed first):** the tab said "Nothing playing". Opening **Media Center (`com.byd.mediacenter`)** from the list switched the car to its Bluetooth source, and back in Wheel Radio the **AVRCP** source showed the song ("Perpetual Motion · LukHash · Better Than Reality") with working controls and "Wheel buttons control Bluetooth music". So the AVRCP service is not always empty on DiLink. It fills once Media Center makes Bluetooth the active source. Kuwo (`cn.kuwo.kwmusiccar`) and KuGou (`com.kugou.android.auto`) are online-music apps, not Bluetooth players.
- **Follow-up for 0.5.3:** `carAppScore` gave Media Center, Kuwo and KuGou the same score (2, "music"/"media"), so the order followed the launcher and Kuwo could come first. **Fixed:** `com.byd.mediacenter` / "Media Center" now scores 0 (`CarBluetoothAppTest.byd_media_center_comes_before_online_music_apps`).
- **Also reported from the car (0.5.2):** the bottom bar kept showing the paused radio station while Bluetooth played, the big player never showed Bluetooth, and there was no cover art (only "BT"). **Fixed in 0.5.3:** the mini player and big player follow Bluetooth whenever it is the chosen source (`BluetoothBridge.inUse`), with a source icon (radio / music / Bluetooth) in front of the title. For covers, Android 10's AVRCP controller has no cover art (cover art arrived in Android 11), so `BluetoothBridge` now uses any picture or picture link in the session and otherwise takes the cover from another app's session playing the same title (e.g. Media Center, with notification access). It logs `[BT] song "…" from …, cover: …` for each song. Whether Media Center publishes the cover still has to be confirmed in the car.
- **Without opening Media Center (0.5.3, still to be confirmed in the car):** Play on the Bluetooth tab, the mini player, the wheel's play key, and the app start after Bluetooth was the last source now run `BluetoothBridge.activate()`. It stops as soon as Bluetooth plays: (1) Play via the AVRCP controller, (2) Play on the car player's media session, (3) Play through the car player's MediaBrowserService, (4) only while Wheel Radio is in front: open Media Center and come back after 2 s. Each step logs `[BT] step n/4: …`, and the result is `Bluetooth plays: step n worked` or `still silent`. Settings → Show wheel button log → **Bluetooth diagnostics** logs the Bluetooth profile states, every media session (state, actions, metadata keys), every MediaBrowserService and Media Center's exported components. Verified on the emulator (no Bluetooth there): steps 1–3 report "not available", and step 4's open-and-return worked with a stand-in player, then was limited to real car Bluetooth players.

## [AUDIO] Ռադիո Ջազ (am:16) fails with ERROR_CODE_IO_BAD_HTTP_STATUS on the car — fixed
- **Severity:** Medium (one curated station; recovery retries, then skips).
- **Steps:** BYD Yuan Plus, Radio → Armenia → Ռադիո Ջազ.
- **Expected:** the station plays.
- **Actual:** the mini player stays on "Connecting…"; the key log shows `[Player] error: ERROR_CODE_IO_BAD_HTTP_STATUS` (12:04:28 and 12:04:48).
- **Evidence:** `http://am.radioaurora.am:8010/jz` from `stations_am.json`. The server answers with a non-2xx status. From the dev PC the host didn't answer at all within 10 s, so the station is down or geo-restricted. This needs a new URL; it's content, not a code fix.
- **Fix (2026-11-24):** `https://de.auroramedia.am/jazz.mp3`, the same Aurora Media server as Ավտոռադիո and Kiss FM (200 `audio/mpeg`, 256 kbps). It is the stream of the directory's "Jazz FM 106.9 Yerevan Radio" entry, so the merge now drops that duplicate row from the Armenia list.

## [AUDIO] After an app restart, the first wheel Next goes to the radio while the phone plays over Bluetooth (BYD Yuan Plus)
- **Severity:** High (the wheel switches the car from the phone's music to a radio station).
- **Steps:** play the phone through the car's Media Center and Wheel Radio's Bluetooth tab (wheel buttons control Bluetooth). Let Wheel Radio's process restart (seen in the car between 19:02 and 19:03:51; cause unknown, possibly a system kill while Media Center was in front). Press wheel Next.
- **Expected:** the phone skips to the next song.
- **Actual:** key log `19:04:01.629 [Activity] KEYCODE_MEDIA_NEXT DOWN scan=270 src=0x101` → `[Player] seekToNext`. Wheel Radio started the next radio station. At 19:04:40 the Bluetooth tab showed "Nothing playing" again.
- **Cause:** `BluetoothBridge.active` and the chosen source live only in memory, so they reset with the process. After the restart the AVRCP service had connected (19:03:51) but reported no playing song, so `handle()` returned false and the key fell through to the radio. Starting the radio probably also took audio focus from Media Center, which pauses the phone.
- **Evidence:** car photos of the key log, 2026-11-24 19:03:51–19:04:27. The log starts at 19:03:51 because `KeyLog` is in memory only.
- **Fix (0.5.3, still to be confirmed in the car):** `BluetoothBridge.active` is saved (`bt_active` in the `radio` prefs) and restored on start. When Bluetooth was the last source, `RadioService` doesn't auto-play the radio (`[Player] Bluetooth was the last source: not starting the radio`). Media-button keys that reach the session go to Bluetooth while it is the source, like the activity and BYD routes. Playing any station clears the flag. The open tab was already remembered (`last_tab`). Verified on the emulator: Bluetooth tab and no auto-play after a restart with `bt_active=true`; the flag clears when the radio plays.

## [AUDIO] Ավտոռադիո (am:01) returns HTTP 404 — fixed
- **Severity:** Medium (a curated station near the top of the Armenia list; recovery retries 4 times over ~22 s, then skips).
- **Steps:** BYD Yuan Plus, wheel Next from Հանրային ռադիո (am:00).
- **Expected:** Ավտոռադիո plays.
- **Actual:** `[Player] error: ERROR_CODE_IO_BAD_HTTP_STATUS` at 19:04:05, 19:04:11, 19:04:18 and 19:04:27, then `station unavailable, skipping to the next one`, landing on Իմ Ռադիո (am:02).
- **Evidence:** `https://de.auroramedia.am/autoradio.mp3` from `stations_am.json` returns **404** from the dev PC too. The same host still serves `aurora.mp3` and `kissfm.mp3` (206), so the Avtoradio mount was renamed or removed. This needs a new URL. It is a content fix, not a code fix.
- **Fix:** the mount is `https://de.auroramedia.am/autoradio` (no `.mp3`), which answers 206 `audio/mpeg`. Verified on the emulator: Armenia → Ավտոռադիո plays.
- **Logging gap (fixed):** the player error line now names the station and the HTTP status, e.g. `[Player] error: ERROR_CODE_IO_BAD_HTTP_STATUS HTTP 404 on "Ավտոռադիո"`.

## [AUDIO] Armenian stream check, 2026-11-24: Ռադիո Ազալիա and Ռադիո Վեմ are dead at the source
- **Method:** every stream in `stations_am.json` (46) and every Armenian Radio Browser station not already in it (6) was read for 8 s with the player's user agent, recording the HTTP status, the content type and the bytes received.
- **Result:** 48 of 52 streamed audio. Failures:
  - **Ռադիո Ազալիա (am:07)** `https://stream-153.zeno.fm/s865660v7hhvv`: HTTP **401**, also through `stream.zeno.fm` and with a browser user agent. The station's other known page (radioazalia.microwav.fm) no longer resolves. No current stream found.
  - **Ռադիո Վեմ (am:25)** `https://eu1.stream4cast.com/proxy/vemradio/stream`: HTTP **404** on eu, eu1 and eu2. vem.am's own web player uses the same URL, so the station's stream is down at the source. Re-check later before replacing it.
  - **Ռադիո Ջազ (am:16)**: no answer from the old host. Fixed, see above.
  - **Voice of Van (am:40)**: false alarm. It is a SHOUTcast v1 server (`ICY 200 OK`, `audio/mpeg`, 32 kbps). curl rejects that old reply without `--http0.9`, but the app plays it (verified on the emulator).
- **Impact:** a dead station costs the driver about 22 s (4 tries) before the player skips it, every time it comes up in the list.
- **Resolved:** am:07 and am:25 were removed from `stations_am.json` (44 stations now). The other ids keep their numbers, because favorites and the saved queue refer to stations by id. If either station comes back, add it again under a new id (am:46 and up).

## [AUDIO] Car test 0.5.3 (BYD Yuan Plus, 2026-11-25): Bluetooth tab and big-player issues — fixed in 0.5.4
- **Severity:** High (Bluetooth start and coming back to the app are unreliable in the car).
- **Evidence:** car photos of the key log and Bluetooth diagnostics, 20:05–20:07. Phone over AVRCP: `actions=0x244037`, metadata `ALBUM,ARTIST,DURATION,GENRE,NUM_TRACKS,TITLE,TRACK_NUMBER`. Media Center (`com.byd.mediacenter`) exports no MediaBrowserService, and notification access is MISSING.
1. **"Open settings" shows BYD's "Multimedia system does not support this operation."** DiLink refuses the system notification-access page. The connected layout's text also ended in "run this once over ADB:" without showing the command.
   **Fix:** both layouts always show the ADB command. When `com.byd.mediacenter` is installed, the Settings button is hidden and the text says the car doesn't open that page (`bt_setup_text_adb`, EN/RU/HY).
2. **Opening the Bluetooth tab doesn't start Bluetooth.** The tab only refreshed, and the radio kept playing.
   **Fix:** a tap on the tab runs `BluetoothBridge.onTabOpened`: it pauses the radio and calls `activate()`. It skips this only when Bluetooth reports no phone (A2DP sink, AVRCP controller and HFP client all disconnected), so the radio isn't stopped for nothing.
3. **Media Center sometimes had too little time** (fixed 2 s before coming back).
   **Fix:** step 4 now returns once Bluetooth plays, but not before 3 s, and at the latest after 5 s (`timeToReturn`, `CarBluetoothAppTest`).
4. **Sometimes Wheel Radio doesn't come back from Media Center.**
   **Cause:** Android 10 blocks background activity starts unless the app's *own* activity started or finished in the last 10 s. Opening Media Center didn't count. The return worked only within ~10 s of opening Wheel Radio (the auto-start after launch), and silently failed for a Play pressed later. Reproduced on the emulator with a stand-in "Bluetooth Music" app and 0.5.3: Play 15 s after launch, the key log says `back to Wheel Radio`, the stand-in stays in front, and logcat shows `ActivityTaskManager: Background activity start [callingPackage: dev.wheelradio … isCallingUidForeground: false …]`.
   **Fix:** Media Center is opened through `CarPlayerLauncher`, an invisible activity of ours that starts and finishes at once, which restarts that window. Same emulator run with 0.5.4: back after 5.1 s. The key log now also says `Android blocked the return` if it ever still happens.
5. **Tapping the timeline doesn't seek on Bluetooth.** This is not fixable from the head unit. AVRCP has no "go to position" command, and the phone's session offers no seek or fast-forward (`0x244037` = stop, pause, play, previous, next, prepare, repeat, shuffle).
   **Fix:** for a source without `ACTION_SEEK_TO`, the big player shows a read-only progress bar instead of a slider that jumps back (`BluetoothBridge.canSeek`, `CarBluetoothAppTest.phone_over_avrcp_cannot_seek`).
6. **Back on the big player returns to the list, then the big player is back after 1 s.**
   **Cause:** Back is a key, not a touch, so the idle time wasn't restarted and the 1 s idle check reopened it at once.
   **Fix:** closing the big player restarts the idle time (`AppModel.bigPlayer`, `AppModelTest.closing_the_big_player_restarts_the_idle_time`). Emulator: the list is still shown 4 s after Back, and the big player comes back only after the full 12 s.
7. **No cover for Bluetooth songs; Media Center shows one.** Android 10's AVRCP controller passes only text. In the car the metadata keys were `ALBUM,ARTIST,DURATION,GENRE,NUM_TRACKS,TITLE,TRACK_NUMBER`, and the key log said `cover: none`. Bluetooth cover art (AVRCP BIP) came with Android 11, and Media Center gets its covers through BYD's own stack, which apps can't reach. Borrowing the cover from Media Center's media session needs notification access, and the car has none.
   **Fix:** when there is no other cover, `CoverSearch` looks the song up on the iTunes Search API, then Deezer (no key needed). A result counts only if title and artist match, and one from the same album wins. A cover from the phone or Media Center still replaces the online one. Settings → Playback → "Find Bluetooth song covers online" (on by default) turns it off. The key log says `cover for "…" found online (iTunes|Deezer)` or `no cover online for …`. Tests: `CoverSearchTest` (recorded response shapes, no network) and `AppSettingsTest`.
   **Car photo, 2026-11-24:** Media Center shows the real cover for a Spotify song from a Pixel 7 Pro ("Perpetual Motion · LukHash", album *Better Than Reality*), so BYD's own Bluetooth stack does receive pictures. The iTunes lookup returns that same album cover. **Next step:** Bluetooth diagnostics now connect to Media Center's exported `com.byd.music.data.AudioDataService` (no permission). They log its interface, its methods, the fields of its data classes and callbacks (read from Media Center's own classes), and the protection level of the permission on `.main.MediaService`. On the emulator this was checked against a stand-in service. A car run shows whether Wheel Radio can take the cover straight from Media Center.
- **Still to confirm in the car:** items 1–4 and 7. In particular, whether Media Center keeps the Bluetooth source when we come back after 3 s.
