# Radio_BYD audio and MediaSession report (audio-session-tester, step 2 of 3)

Date: 2026-11-21 · Branch: `qa/claude-test-suite` (nothing committed) · Device: `emulator-5554`

Legend: **[V]** = verified on the emulator · **[S]** = inferred from source only, not reproduced.

This is a native Kotlin/Compose app. Media3 1.4.1 ExoPlayer runs inside `RadioService` (a `MediaSessionService`). There is no HTMLMediaElement, WebView, CORS or mixed content. The native equivalents were used instead:

| Web check | Native equivalent used here |
|---|---|
| `paused === false`, `readyState >= 3` | `dumpsys media_session` → `PlaybackState state=3` (PLAYING). 6 = BUFFERING, 2 = PAUSED, 7 = ERROR. |
| `currentTime` advancing | PlaybackState `position` sampled about 3 s apart. Media3 republishes it about every 3 s. |
| `error === null` | PlaybackState `error=null`, plus `ExoPlayerImplInternal` / `WheelRadio` logcat |
| No overlapping audio | `dumpsys audio` player list: number of `dev.wheelradio` (uid 10146) players in `state:started` |
| `navigator.mediaSession.metadata` | `dumpsys media_session` `metadata: description=<title>, <subtitle>`, plus the media notification (`android.title`, `android.text`, `android.largeIcon`) |
| Mixed content / CORS | Cleartext HTTP is allowed (`usesCleartextTraffic="true"`). HTTP streams played without errors. No TLS or cleartext errors appeared in logcat. |

## 1. Environment

| Item | Value |
|---|---|
| App | `dev.wheelradio` 0.5.2 (car flavor debug), Media3 1.4.1, minSdk 29 / targetSdk 34 |
| Device | emulator-5554, Android 10 / API 29, 1920x1080 landscape, density 160 |
| adb | `$LOCALAPPDATA/Android/Sdk/platform-tools/adb` (from Git Bash with `MSYS_NO_PATHCONV=1`). The MCP android tools could not be used because of `spawn adb ENOENT`. |
| Evidence folder | `QA scratch folder audio/ (local only, not committed)` (screenshots and uiautomator dumps `s1_*` … `s8_*`, `final.*`; helper scripts `h.sh`, `putprefs.sh`, `mkfav.pl`; host stream probe `curl_results.txt`; overlap sampler `overlap.txt`; prefs backup `radio_backup.xml`) |
| Network | Live streams and the live Radio Browser API. Station names and indices below come from live data on the test date and will drift. |
| Timing accuracy | Times come from polling `dumpsys` every 0.2–2 s. Treat them as ±0.3 s (±2 s where noted). |
| Test data | The app’s `shared_prefs/radio.xml` `favorites` held temporary entries, including `http://127.0.0.1:9/dead.mp3`. The original file was restored byte for byte (md5 `d253e531…`) and `favorites` = `[]` again. |
| Final state | Wi-Fi and data on. Landscape (`user_rotation 0`). App in the foreground on **By countries** (grid). Playing `MANGORADIO` (Germany queue, index 0). Favorites `[]`. |

## 2. Per-scenario results

| # | Scenario | Result | Observed / evidence |
|---|---|---|---|
| 1a | Armenia tile play | PASS [V] | `Հանրային ռադիո`, `description=Հանրային ռադիո, Armenia`. Time to PLAYING **1.65 s** on the first run and 1.09 s on a re-run; both include the station-list fetch. Position 0 → 2910 ms after about 3 s. One AudioTrack `started`. `last_index`=0. |
| 1b | Russia tile play | PASS [V] | `Anon.FM`, **0.81 s**. Position advances. |
| 1c | Germany tile play | PASS [V] | `MANGORADIO`, **1.14 s** (other runs 1.37 s and 1.65 s). |
| 1d | Russia row 2 | PASS [V] | `Русское Радио`, **1.05 s**, `active item id=2`, `last_index`=2. |
| 1e | Germany row 1 | PASS [V] | `SWR3`, **1.99 s**, `last_index`=1. |
| 1f | Armenia row 1 | FAIL (real dead stream) [V] | `Ավտոռադիո` (`am:01`) returns **HTTP 404**. It was auto-skipped to `Իմ Ռադիո` after about 26 s (timeline in section 4). |
| 1g | Other curated or default streams | MIXED [V] | Dead on the device: `am:07` Ռադիո Ազալիա (**401**), `am:16` Ռադիո Ջազ and `am:35` Ayp-FM (**connect timeout**), `jazz24` (DNS failure, `CONNECTION_FAILED`). These play on the device even though the host curl probe failed: `am:40` Voice of Van (SHOUTcast `;stream.nsv`, 0.4 s) and Radio Paradise (curl got 403, the device plays it after about 10 s). Directory stations Germany idx 5 and 16 (`breakz-high.rautemusik.fm` → 307 → 404) are dead even with `hidebroken=true`. That is external data, not an app bug. |
| 1h | Metadata on every change | PASS [V] | The session `description` (title, subtitle) changed on every switch in sections 2 and 3. The notification for `Հանրային ռադիո` had `android.title=Հանրային ռադիո`, `android.text=Armenia`, `largeIcon=BITMAP 100x100` (favicon loaded). Notification artwork is `null` for stations without a favicon, which is expected. |
| 2a | Mini-player Next ×10, gap about 265 ms (`input tap` overhead, 150 ms sleep) | PASS (debounce) [V] | **5 of 10 switched** and 5 logged `seekToNext ignored (duplicate within 400ms)`. Index 0 → 5. `last_index`=5 matches. It landed on a dead directory station, then auto-recovered to idx 6 (`1LIVE`). |
| 2b | Mini-player Next ×10, gap about 465 ms | PASS [V] | **10 of 10 switched**. Index 6 → 16, `last_index`=16. |
| 2c | `input keyevent 87` ×10, gap about 110 ms | PASS (debounce) [V] | **3 of 10 switched** (17 → 20). Only the Activity route logged; there was no second route. |
| 2d | `input keyevent 87` ×10, gap about 610 ms | PASS [V] | **10 of 10** (20 → 30). `TechnoBase.FM` PLAYING, `last_index`=30, 1 AudioTrack. |
| 2e | Overlap sampler during `88` ×10 at about 600 ms | PASS [V] | 25 `dumpsys audio` samples. Started players from dev.wheelradio: **never more than 1**. Total players never more than 1 (the old AudioTrack is released before the new one is created). 30 → 20, `last_index`=20. |
| 2f | Crash / ANR | PASS [V] | The pid stayed the same throughout section 2. `dumpsys dropbox` has no `data_app_crash` / `data_app_anr` entries. |
| 2g | Wrap-around | PASS [V] | Germany idx 0 + `88` → idx 288 (last). `87` → idx 0. `last_index` follows. |
| 3 | Wheel keyevents, foreground and background | PARTIAL [V] | See the key-route matrix in section 3. No double handling: every single press moved the index by exactly 1 with one `[Player]` line. **Failures:** CHANNEL_UP/DOWN do nothing in the background; a play/pause double-tap skips a station. |
| 3b | Previous on a live stream | PASS [V] | `88` always switched station (for example NIUS → NDR 2 at position above 3 s). It never restarted the current station. |
| 4 | BYD `APP_REV_DATA` broadcast | NOT TESTABLE from adb (positive path) [V] | The receiver is registered (`dumpsys activity broadcasts`: `Action: "APP_REV_DATA"`, dev.wheelradio). `am` has no byte[] extra option (`--ei/--eia/--el/--ela/--ef/--efa/--es/--esa/--ez/--eu/--ecn` only). Delivered and **silently ignored**, with no state change and **no KeyLog line**: `--eia list 4,1,3`, `--ela list 4,1,3`, `--es list 04,01,03`, `--ei list 3`, short `--eia list 4,1`, wrong group `--eia list 5,1,3`. A real `byte[]` frame must be covered by a unit or instrumented test. |
| 5a | Dead favorite then valid one (multi-item queue) | PASS (as designed) [V] | 4 errors (initial + 3 retries) at 2/4/6 s back-off, then skip. The valid station played about 25 s after the tap (section 4). The UI never froze. |
| 5b | Single-item dead queue | PASS (no freeze) / UX FAIL [V] | It stays in `state=7 error=Source error` after the 4th error. The mini player shows the title and a `Play` button, with no error text. Tapping Play or pressing `85` makes **one** new attempt, then error again. `87` and mini-player Next log `seekToNext` but do nothing. |
| 5c | Next pressed during the back-off window | PASS with delay [V] | The new station only started when the stale back-off `prepare()` fired (+1.8 s BUFFERING, +2.7 s PLAYING). See the bug section. |
| 5d | Station whose host never answers (`am:16`) | FAIL [V] | **130 s** of `Connecting…` before the first error, then 133 s to the 2nd error. Projected about 9 min before the auto-skip. |
| 5e | 15 s network loss | PASS [V] | BUFFERING at +5.4 s. No app-level error. **Same station** (SWR3) PLAYING again at +22.2 s (7 s after reconnect). `last_index` unchanged. |
| 5f | 75 s network loss (U7) | FAIL, **U7 confirmed** [V] | SWR3 → 80s80s Radio → Deutschlandfunk while offline. `last_index` 1 → 3. DLF plays when the network returns (section 4). |
| 6a | Local playback | PASS [V] | `QA Tone A440` PLAYING in 0.41 s. Position advances. Notification and session show `QA Tone A440, Device · QA Bot`. |
| 6b | Shuffle follows shuffled order and wraps | PASS [V] | With `Shuffle all (3)`, `87` sequences were `1 0 2 1 0 2 1`, `2 1 0 2 1 0 2` and `2 0 1 2 0 1 2`. The order is non-linear, cyclic with period 3, and wraps. `88` walks the same cycle backwards. A natural track end advanced 2 → 0 following the shuffle order. `shuffle`=true in prefs. |
| 6c | Starting a radio station turns shuffle off | PASS [V] | `shuffle`=false. The shuffle icon is gone from the mini player. |
| 7a | Audio focus: Play Music preview (`AudioPreviewActivity`, GAIN_TRANSIENT) | PASS [V] | WheelRadio `LOSS_TRANSIENT` → state 2 (paused). Only the Play Music MediaPlayer is `started`, so nothing overlaps. Closing the preview gave PLAYING again within about 0.1 s of focus return. |
| 7b | Audio focus: incoming call (`adb emu gsm call`) | PASS [V] | telecom took `GAIN_TRANSIENT` → WheelRadio paused. `gsm cancel` → playback resumed. |
| 7c | Ducking / permanent loss | NOT TESTED | No app on the image requests `MAY_DUCK`. Permanent loss would pause and stay paused (Media3 `handleAudioFocus=true`) [S]. |
| 8a | Swipe from recents | PASS [V] | The task left recents, the process (pid 18647) stayed alive, `RadioService isForeground=true`, and playback continued. `87`/`88` kept working through the session route. Relaunch was WARM (191 ms) and the mini player showed the current station. |
| 8b | `am force-stop` + relaunch | PASS [V] | Done 4 times. Each was a COLD start that resumed `queue[last_index]` and auto-played (for example `Radio Bollerwagen` idx 25 PLAYING 2.3–3.0 s after `am start`). |

## 3. Key-route matrix [V]

Routes: **A** = `MainActivity.dispatchKeyEvent` → MediaController (logged `[Activity] … DOWN/UP`, then `[Player] seekTo…`). **S** = MediaSession media-button route (only `[Player] seekTo…` is logged; play/pause is **not** logged at all). Δ = change in station index.

| Key | Foreground | Background (HOME, or task swiped away) | Foreground with the Key log dialog open |
|---|---|---|---|
| 87 MEDIA_NEXT | A → Δ+1 | S → Δ+1 | S → Δ+1 (the Activity route does not see it) |
| 88 MEDIA_PREVIOUS | A → Δ−1 (no restart on live) | S → Δ−1 | S (inferred from 87) |
| 166 CHANNEL_UP | A → Δ+1 | **ignored** (Δ0, nothing logged) | **ignored** |
| 167 CHANNEL_DOWN | A → Δ−1 | **ignored** | not run (same path as 166) |
| 85 PLAY_PAUSE | A logs DOWN/UP and does not consume them. The system fallback sends them to S, which toggles. | S toggles (not logged) | – |
| 126 PLAY / 127 PAUSE | As 85: play / pause | S: play / pause | – |
| 79 HEADSETHOOK | – | S toggles pause | – |
| **85 85 within 300 ms** | **Δ+1 (station skipped)** | **Δ+1** | – |
| **79 79** | – | **Δ+1** | – |
| 85, then 85 after 420 ms | pause then play, Δ0 | – | – |
| `--longpress 87` (DOWN, DOWN repeat=1, UP) | A ignores the repeat → Δ+1 | S got **both** DOWNs: `seekToNext` + `seekToNext ignored (duplicate within 400ms)` → Δ+1 (only thanks to the debounce) | – |
| 85 / 87 in ERROR state, single-item queue | 85 → one new attempt. 87 → `seekToNext` logged, nothing happens. | – | – |
| `APP_REV_DATA` (int[], long[], String, int, short, wrong group) | delivered, silently ignored, not logged | same | – |

The "Media button session" was `dev.wheelradio/androidx.media3.session.id.` throughout, and the session stayed `active=true`.

## 4. Recovery timelines [V]

**Dead favorite `http://127.0.0.1:9/dead.mp3` (queue = 8 favorites, start at 0)**

| t (s) | Event |
|---|---|
| 0.00 | tap (14:34:49.876). State 6, mini player `QA Dead Stream` / `Connecting…` / **Play** icon |
| +2.27 | error #1 `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` (ExoPlayer’s own load retries take about 2–3 s per attempt) |
| +7.30 | error #2 (2 s app back-off + about 3 s attempt) |
| +14.33 | error #3 (4 s + about 3 s) |
| +23.37 | error #4 → retries exhausted → `seekToNextMediaItem()` + `prepare()` |
| +24.2 | mini player `Հանրային ռադիո` / **Pause** |
| +26.3 | session state 3, `active item id=1`, `last_index`=1 |

The UI never froze. During the cycle the subtitle alternates between `Connecting…` and blank, and the button always shows **Play**. Nothing says the station failed.

**Real 404 (`am:01` Ավտոռադիո, Armenia queue)**: tap 14:27:36.28 → errors 38.57, 43.63, 50.69, 59.74 (`ERROR_CODE_IO_BAD_HTTP_STATUS`, `Response code: 404`) → `Իմ Ռադիո` PLAYING by 14:28:02.2 (about 26 s).

**Host that never answers (`am:16` Ռադիո Ջազ, `http://am.radioaurora.am:8010/jz`)**: tap 14:37:01.7 → state 6 `Connecting…` for **130 s** → error #1 14:39:12.0 `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` (`SocketTimeoutException … after 8000ms`, repeated over several resolved addresses and ExoPlayer load retries) → error #2 14:41:25.2. From the back-off schedule, error #4 and the skip would come at about +535 s (about 9 min). The next favorite (`am:35` Ayp-FM) behaves the same way. I stopped it manually.

**Network loss mid-playback (SWR3, Germany queue idx 1)**

| Outage | Timeline | Outcome |
|---|---|---|
| 15 s | +5.4 s BUFFERING. Online again at +15.3 s. PLAYING at +22.2 s. No `[Player] error` lines. | Same station, `last_index` 1 |
| 75 s | +6.0 BUFFERING · +19.1 first app error · 4 errors (+19.1/+24.1/+31.2/+40.2) · **+41.4 SWR3 → `80s80s Radio`** (idx 2) · 4 errors · **+65.9 → `Deutschlandfunk`** (idx 3) · +75.3 online · +79.1 DLF PLAYING | Station lost, `last_index` 1 → 3 (**U7 confirmed**) |

From this, any outage of **≥ about 40 s** drops the user’s station, and every further ~24.5 s offline costs one more station.

## 5. Verified vs inferred

- Verified on the emulator [V]: everything in sections 2–4 marked [V].
- Inferred from source [S]:
  - `BydWheelKeyReceiver` maps only `0x03` → NEXT. A real `[4,1,3]` byte[] would call `player.perform(NEXT)` unless `BluetoothBridge.active`.
  - The BYD route and the session route share the same `StationSwitchingPlayer` instance, so the 400 ms debounce would suppress a BYD+session duplicate. It also suppresses intentional fast presses (seen in 2a/2c).
  - The back-off `handler.postDelayed({ exo.prepare() })` is never cancelled when the station changes. That is why Next during back-off waits for the stale timer.
  - With `BluetoothBridge.active`, activity-route NEXT/PREVIOUS go to Bluetooth. This was not reachable on the emulator.
  - Pause/resume on a live stream continues from the buffer (position kept growing, for example 36.9 s after resume) rather than jumping back to live. Long pauses (calls) will play stale audio or hit a read timeout. Not measured.
  - Holding a wheel key with the app in the background: the session receives every auto-repeat DOWN (verified with one repeat). A real 2 s hold (repeats every 50 ms after 500 ms) would skip about 4 stations. The emulator has no keyboard input device to simulate a true hold (`getevent` shows only touch and power devices).

## 6. Recommendations for step 3 (deterministic tests)

No live streams in CI. Note: Playwright does not apply to this app. Use JUnit/Robolectric and androidTest.

**JVM unit tests (Robolectric where Android types are needed; add `androidx.media3:media3-test-utils` and `media3-test-utils-robolectric`):**
1. `BydWheelKeyReceiver.onReceive` with a real `Intent("APP_REV_DATA").putExtra("list", byteArrayOf(...))`:
   - `[4,1,3]` → NEXT once.
   - `[4,1,0x7F]` → no action.
   - `[5,1,3]`, `[4,2,3]`, `[4,1]` and an empty array → no action.
   - Missing extras → no action.
   - `IntArray(4,1,3)` → no action (this documents the current silent drop, see bug).
   - `0xFF` code masking (`bytes[2] and 0xFF`).
   - Assert KeyLog entries through `KeyLog.entries.value`.
2. `StationSwitchingPlayer` wrapping a `TestExoPlayerBuilder` player with 3–5 `MediaItem`s (fake URIs; no prepare needed for index logic):
   - Debounce: a second skip at 399 ms is ignored and one at 400 ms is accepted (advance `SystemClock` through Robolectric `ShadowSystemClock`).
   - Wrap-around both ways under `REPEAT_MODE_ALL`.
   - `INDEX_UNSET` fallback when repeat is off.
   - Shuffle order is followed (`setShuffleOrder(FakeShuffleOrder)` or a fixed-seed `DefaultShuffleOrder`).
   - `count == 0` → no seek.
   - `playWhenReady` becomes true after a skip.
   - `perform(PLAY_PAUSE)` toggles.
   - `seekToPrevious` never seeks within the current item.
3. `CuratedStations.merge`:
   - Curated items first, in order.
   - Directory duplicates removed by normalized URL (scheme, `;stream.mp3`, trailing `/`, query, case) and by normalized name (`Radio`/`Ռադիո`/`Радио` prefix, punctuation, case).
   - Empty curated list → directory unchanged.
4. `Station`:
   - `toJson`/`fromJson`/`listToJson`/`listFromJson` round trip with Armenian/Cyrillic names and `;` in URLs.
   - Missing optional fields default correctly; `null`/blank JSON → empty list.
   - `toMediaItem` → `fromMediaItem` round trip: URI survives through `requestMetadata`; `local:` ids give `local=true`; `station` is null for local items.
5. `StationRepository` (Robolectric prefs): `queue` falls back to `stations.json` when empty; `lastIndex` and `shuffle` persist; `favorites` `[]` round trip.
6. Retry/skip policy: today it is an anonymous listener inside `RadioService.onCreate`, so it is only testable through Robolectric `ServiceController<RadioService>` with a seeded prefs queue and `TestPlayerRunHelper`/`ShadowLooper.idleFor(2s/4s/6s)`, using a `file:///nonexistent` or `http://127.0.0.1:9/` URI. Assert 3 retries then `currentMediaItemIndex` + 1, single-item stays in error, and `last_index` persisted. Robolectric cannot play audio, so assert only player state and index.

**Instrumented tests (androidTest on the emulator):**
- Seed `radio.xml` (queue/favorites) before launch.
- Serve audio from a **local** `MockWebServer` inside the test APK (infinite chunked `audio/mpeg` loop of a bundled short MP3, optional `icy-metaint`).
- Connect a `MediaController` to `RadioService`.
- Assert:
  - PLAYING within 5 s; `mediaMetadata.title`/`station`/`artworkUri` per station.
  - Dead URL skip in under 30 s.
  - `shutdown()` the server for 15 s → same station resumes. For 60 s → expected to stay on the station; this **fails today (U7)**, so mark it `@Ignore("U7")` or as a known failure.
  - A server that accepts TCP but never responds (`SocketPolicy.NO_RESPONSE`) → reproduces the 9-minute hang.
- Key routes: `InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KEYCODE_MEDIA_NEXT)` with the activity resumed (assert Δ=1). `UiDevice.pressHome()` and then `pressKeyCode(87/166)` (assert Δ=1 / Δ=0 documents the CHANNEL bug). Double `85` within 300 ms (documents the skip bug).
- BYD route: `context.sendBroadcast(Intent("APP_REV_DATA").putExtra("list", byteArrayOf(4,1,3)))` from the test once the service is running. This is the only way to deliver a byte[] on the emulator. Assert Δ=1.
- Overlap: assert `AudioManager.getActivePlaybackConfigurations()` contains at most 1 config for the app uid during rapid switching.
- Use `UiAutomator`/Compose selectors from `exploration-report.md` (no testTags exist yet).

## 7. Bugs filed in `test-results/bugs.md`

| Bug | Severity |
|---|---|
| U7 (step 1) **confirmed**; note appended under it | Medium (suggest High for a car) |
| [AUDIO] A station whose host never answers stays "Connecting…" for about 9 min before the skip | Medium |
| [AUDIO] Dead streams in the curated / bundled lists (`am:01`, `am:07`, `am:16`, `am:35`, `jazz24`) | Low |
| [AUDIO] Double press of Play/Pause skips to the next station | Medium |
| [AUDIO] CHANNEL_UP/DOWN do nothing unless the activity window has focus | Medium |
| [AUDIO] Failed station shows no error state; single-station queue silent with a Play button | Low |
| [AUDIO] Next/Previous during an error back-off waits for the stale retry timer | Low |
| [AUDIO] APP_REV_DATA frames with an unexpected payload dropped without a KeyLog entry | Low |
| [AUDIO] Holding a wheel key in the background repeats the skip | Low |
