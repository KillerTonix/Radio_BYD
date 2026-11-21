# Radio_BYD UI exploration report (ui-explorer, step 1 of 3)

Date: 2026-11-21 · Branch: `qa/claude-test-suite` (nothing committed) · Author: ui-explorer agent

Legend: **[V]** = verified on the emulator · **[S]** = inferred from source only (not reproduced)

---

## 1. Environment

| Item | Value |
|---|---|
| App | `dev.wheelradio`, car-flavor debug APK, launch activity `dev.wheelradio/.MainActivity` (`singleTask`, `windowSoftInputMode=adjustResize`, `configChanges=orientation\|screenSize\|screenLayout\|keyboardHidden\|uiMode`) |
| Stack | Kotlin + Jetpack Compose (Material3), Media3 ExoPlayer in `RadioService` (MediaSessionService). **Not a web app – Playwright cannot drive it.** Use Compose UI tests (`createAndroidComposeRule<MainActivity>`) or UiAutomator / Espresso. |
| Device | `emulator-5554`, Android 10 / API 29, 1920x1080 physical, density 160 (1 dp = 1 px, so all bounds below are also dp) |
| Landscape | `user_rotation 0` → 1920x1080 |
| Portrait | `accelerometer_rotation 0` + `user_rotation 1` → 1080x1920 (app bounds 1080x1864). `wm size 1080x1920` was **not** used; rotation was used because that is what the BYD rotating screen does. |
| Tools | `adb` + `uiautomator dump` + `screencap`. The `mcp__android-emulator__*` tools failed (`spawn adb ENOENT` – adb not on PATH for the MCP server). The test writer should call adb by full path: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb` (Git Bash needs `MSYS_NO_PATHCONV=1`). |
| Screenshots / dumps | `QA scratch folder ui/ (local only, not committed)` (`L*` = landscape, `P*` = portrait; each has a `.png` and a uiautomator `.xml`) |
| Network | Live Radio Browser API (`de1/fi1/de2/nl1.api.radio-browser.info`). Station names/counts below come from the live API on the test date and **will drift**. |
| Permission at start | `READ_EXTERNAL_STORAGE` was already granted (APK installed with `-g`). I revoked it once to see the permission flow, then granted it again. |

### Final device state (as requested)
- Landscape 1920x1080, `user_rotation 0`, `accelerometer_rotation 1` (its original value), `wm size` = physical.
- App in the foreground on **By countries** (selected tab `[0,100][480,148]`), search empty.
- Favorites cleared (`favorites` = `[]` in radio.xml).
- Radio is playing (`1LIVE`, queue index 6; see bug U7).
- **Local test audio left in `/sdcard/Music/` and indexed by MediaStore:**

| File | MediaStore `_id` | Title | Artist | Length |
|---|---|---|---|---|
| `/sdcard/Music/qa_tone_440.mp3` | 12 | `QA Tone A440` | `QA Bot` | 30 s, 440 Hz sine, mono 64 kbps |
| `/sdcard/Music/qa_tone_880.mp3` | 13 | `QA Tone A880` | `QA Bot` | 30 s, 880 Hz |
| `/sdcard/Music/qa_long_title.mp3` | 14 | `QA Very Long Song Title That Should Be Truncated In The Mini Player And Station Rows Without Overflowing The Layout` | `QA Bot With An Equally Long Artist Name For Overflow Checks` | 30 s, 660 Hz |

These are the only 3 audio rows in `content://media/external/audio/media`. Their app station IDs are `local:content://media/external_primary/audio/media/<_id>`. The source MP3s are also in the scratch `ui\` folder.

---

## 2. Global structure (from `Ui.kt` [S], confirmed on screen [V])

```
WheelRadioApp (Column)
├─ TopBar: TextField (search) + IconButton "Key log"
├─ TabRow (fixed, 4 equal tabs – NOT scrollable, NO HorizontalPager)
├─ Box(weight 1): when(model.tab) { COUNTRIES | FAVORITES | LOCAL | BLUETOOTH }
├─ MiniPlayer
└─ KeyLogDialog (when model.showKeyLog)
```

- **There is no `Modifier.testTag` anywhere and no resource-ids.** Every node in the dump has `resource-id=""`. Compose renders all clickables as `android.view.View` and texts as `android.widget.TextView`.
- Clickable containers usually have **empty** text/desc. The text/desc is on a **child** node. With UiAutomator, find the child, then tap its parent's bounds (or tap the child's centre, which works because the click target encloses it). With Compose test APIs, use `onNodeWithText(...)` / `onNodeWithContentDescription(...)`. These merge semantics, so the tap reaches the clickable.
- **Selected tab:** the selected Tab node has `selected="true"` **and `clickable="false"`**. Unselected tabs are `clickable="true"`, `selected="false"`. This is a reliable way to assert which tab is active.
- Tab labels are rendered **uppercase**: `BY COUNTRIES`, `FAVORITES`, `LOCAL & USB` (XML `LOCAL &amp; USB`), `BLUETOOTH`. Source titles are `By countries`, `Favorites`, `Local & USB`, `Bluetooth`. In Compose tests, match `"BY COUNTRIES"`, or add tags.
- **Renamed later (`feature/separate-favorites`):** `By countries` is now `Radio` (`RADIO`) and `Local & USB` is now `Music` (`MUSIC`). This report keeps the names as they were when it was written.
- Country flag is a separate TextView (emoji regional indicators, e.g. `🇦🇲`). Country-list headers and chips concatenate the flag: header = `"🇦🇲  Armenia"` (**two spaces**), chip = `"🇦🇲 Armenia"` (**one space**).

---

## 3. Screen map

### 3.1 Top bar + tabs (all screens)

| Element | Text / contentDescription | Landscape bounds | Portrait bounds | Notes |
|---|---|---|---|---|
| Search field | EditText, empty text; placeholder TextView `Search stations, countries, files…` (the ellipsis is the single character U+2026) | `[16,34][1840,90]` | `[16,34][1000,90]` | Leading search icon has **no desc** (`null`). |
| Clear search | desc `Clear search` (clickable parent `[1792,38][1840,86]` L / `[952,38][1000,86]` P approx.) | `[1804,50][1828,74]` | `~[964,50][988,74]` | Only present when the query is not empty. |
| Key log (gear) | desc `Key log` | parent `[1848,34][1904,90]` | parent `[1008,34][1064,90]` | Opens "Wheel button log" dialog. |
| Tab 0 | `BY COUNTRIES` | `[0,100][480,148]` | `[0,100][270,148]` | |
| Tab 1 | `FAVORITES` | `[480,100][960,148]` | `[270,100][540,148]` | |
| Tab 2 | `LOCAL & USB` | `[960,100][1440,148]` | `[540,100][810,148]` | |
| Tab 3 | `BLUETOOTH` | `[1440,100][1920,148]` | `[810,100][1080,148]` | |

### 3.2 By countries: grid (`CountryGrid`, `LazyVerticalGrid(Adaptive(280.dp))`)

- Scrollable container: `[16,148][1904,940]` L / `[16,148][1064,1780]` P.
- **Columns: 6 in landscape** (tile 302 px wide), **3 in portrait** (tile 339 px wide). Tile height 116, gap 16.
- Each tile is a clickable View (opens the country). Children: flag TextView, name TextView, `"<n> Stations"` TextView, and a clickable 48x48 play button whose child has desc **`Play <Country name>`** (e.g. `Play Armenia`).
- Pinned order [V]: **Armenia, Russia, Germany, United States, Georgia, France**, then descending by station count (China, United Kingdom, Greece, Australia, Italy, Mexico, Brazil, Canada, Spain, Netherlands, Argentina, Poland, Romania, India, Philippines, Colombia, Turkey, United Arab Emirates, …).
- First-row bounds (landscape): Armenia tile `[16,164][318,280]`, play `[256,218][304,266]`; Russia `[334,164][636,280]`, play `[574,218][622,266]`; Germany `[652,164][953,280]`, play `[891,218][939,266]`; US `[969,164][1270,280]`; Georgia `[1286,164][1587,280]`; France `[1603,164][1904,280]`.
- First-row bounds (portrait): Armenia `[16,164][355,280]` (play desc `[305,230][329,254]`), Russia `[371,164][710,280]`, Germany `[726,164][1064,280]`.
- Counts shown on first load [V]: Armenia `56 Stations`, Russia `2494 Stations`, Germany `5620 Stations`, US `7119`, Georgia `28`, France `2812`. These **change after the country is opened** (see bug U4).
- Loading state: `CircularProgressIndicator` only (no text). Error state [V] (offline): `Couldn't load countries. Check the internet connection.` + button `Try again` (`[890,538][1031,586]` L).

### 3.3 Country station list (`CountryStations`)

| Element | Text / desc | Landscape bounds |
|---|---|---|
| Back arrow | desc `Back` (parent `[16,156][72,212]`) | `[29,169][59,199]` |
| Header | `"<flag>  <Name>"` e.g. `🇦🇲  Armenia` | `[72,169][228,199]` |
| List (LazyColumn, scrollable) | | `[16,220][1904,940]` L / `[16,220][1064,1780]` P |
| Row n (clickable, plays the list from index n) | name TextView + subtitle TextView | row 0 `[16,228][1904,304]`, row pitch 84 px |
| Row heart | desc `Add to favorites` / `Remove from favorites` (parent 56x56) | row 0 `[1834,238][1890,294]` L, `[994,238][1050,294]` P |
| Artwork | favicon image (no desc), or a letter fallback TextView (e.g. `Р`, `J`, `Q`). **The fallback letter shows up as an extra TextView inside the row.** | 52x52 at x 30–82 |

The currently playing row gets a 2 dp Accent border and Accent/bold title. Neither is exposed in semantics (no `selected` flag), so a test cannot see which row is playing.

Observed lists [V] (live data, 2026-11-21):

| Country | Rows after open | First rows (name — subtitle) |
|---|---|---|
| Armenia | **51** (46 curated `am:00`… first, then directory) | `Հանրային ռադիո` — `Armenia`; `Ավտոռադիո`; `Իմ Ռադիո`; `Լավ Ռադիո Mix`; `Լավ Ռադիո`; `Լրատվական ռադիո`; `Պաշտէ`; `Ռադիո Ազալիա`; `Ռադիո Արևիկ` |
| Russia | **292** (API `limit=300`, deduped by name) | `Anon.FM` — `The Russian Federation · anime · dnb · downtempo`; `РАДИО ВАНЯ`; `Русское Радио`; `DFM Дискач 90-х`; `DFM RUSSIAN DANCE`; `Mixadance FM`; `Вести FM` |
| Germany | **289** | `MANGORADIO` — `Germany · music · variety`; `SWR3`; `80s80s Radio`; `Deutschlandfunk \| DLF \| MP3 128k`; `Bayern 3` |
| France (stray tap) | – | `RTL`; `France Info`; `Europe 1`; `Nostalgie HQ`; … |

Back navigation [V]: the back arrow and system BACK both return to the grid. The grid's scroll position is kept. System BACK is handled by `BackHandler(enabled = openCountry != null)`.

### 3.4 Search results (`SearchResults`, Countries tab only, query.trim().length ≥ 2)

- One character (`j`): grid stays visible; the `Clear search` icon appears [V].
- ≥ 2 characters: after a 450 ms debounce, a spinner (no text) shows while the list is empty, then the station list appears (Radio Browser `search?name=`, `limit=60`).
- Country chips row: up to 6 `AssistChip`s, each with text `"<flag> <Name>"`. Chip bounds: y `[158,206]`, starting x 16. **Tapping a chip clears the query and opens that country** [V].
- Empty text (source): `No stations match "<q>".`
- `jazz` [V]: no country chip. Stations: `101 SMOOTH JAZZ`, `IBIZA CHILLOUT LOUNGE - Ambient, …` (ellipsized), `Smooth Jazz Lounge`, `Jazz Sakura (asia dream radio)`, `-- # TOP 100 CHARTS --- …`.
- `Arm` [V]: chip `🇦🇲 Armenia` `[16,158][144,206]`. Stations: `Radio Armisa`, `Armin van Buuren (Record)`, `HARMAN BEASTS`, `Al Karma FM`, `Yarmouk University FM`. **No curated Armenian stations**: they are not searchable (bug U9).
- `Islands` [V]: 6 chips. They fit in landscape but **overflow in portrait** (bug U2).
- Keyboard [V]: in landscape the IME covers y 646–1024. With `adjustResize` the content area shrinks to `[16,148][1904,562]`, and the **mini player moves up to y≈572–636** (title at `[90,592]`). IME Enter does nothing (bug U5). System BACK hides the IME first, and a second BACK exits the app if no country is open.
- On Favorites and Local tabs the same query filters the list locally: `name`/`subtitle` contains q, case-insensitive, any length ≥ 1 [V for Favorites with `m`]. On Bluetooth the query is ignored [S].

### 3.5 Favorites tab

| State | Content [V] |
|---|---|
| Empty | `No favorites yet. Tap the heart next to a station to add it here.` (`[684,532][1236,556]` L) |
| With items | StationList in **insertion order**. Rows start at `[16,156]` when there is no shuffle button. |
| ≥ 2 **local** favorites | Button `Shuffle favorite songs (2)` (`[16,160][~280,208]`). It **pushes the list down 72 px** (first row at `[16,228]`). It disappears when < 2 local songs are left, and the rows jump back up. Tests must re-query bounds after every toggle. |

### 3.6 Local & USB tab

| State | Button(s) | Body text [V] |
|---|---|---|
| Permission not granted | `Allow access to music` (`[16,160][267,208]`) | `Allow access so the app can find music on the car and on USB sticks.` |
| Granted, nothing found | `Rescan device & USB` (`[16,160][257,208]`) | `No audio files found. Plug in a USB stick and tap Rescan.` |
| Granted, files found | `Rescan device & USB` + `Shuffle all (3)` (`[269,160][449,208]`) | rows: title, subtitle `Device · <artist>` (`USB · …` for non-primary volumes) |

- Permission dialog (API 29 system UI) [V]: `Allow Wheel Radio to access photos and media on your device?` with buttons `Allow` / `Deny`. On the second request it adds `Deny & don’t ask again`. After Deny, the screen stays in the "Allow access" state. After Allow, it scans automatically.
- Files pushed after the tab was first scanned **do not appear until `Rescan device & USB` is tapped** [V]. There is no MediaStore observer. When the activity is recreated, entering the tab auto-scans (`LaunchedEffect`) [V].
- Rows sort by MediaStore TITLE ASC: `QA Tone A440`, `QA Tone A880`, `QA Very Long …`.
- Local tab works offline [V].
- There is **no folder-picker UI** even though the code supports `music_folder` (bug U6).

### 3.7 Bluetooth tab (emulator)

Status `NEEDS_ACCESS` [V] (notification-listener access not granted):
- `One-time setup needed`
- `To control Bluetooth music, Wheel Radio needs notification access. Turn it on in settings, or run this once over ADB:`
- `adb shell cmd notification allow_listener dev.wheelradio/dev.wheelradio.MediaListener` (monospace)
- Buttons `Open settings` (`[844,573][982,621]` L) and `Retry` (`[994,573][1077,621]` L). `Retry` does not change the state on the emulator. The key log records `[BT] AVRCP controller service not available`.
- The other states (`SEARCHING` spinner; `NOT_FOUND`: `No Bluetooth music found.`, `Retry`, `Open the car's Bluetooth music`; `CONNECTED`: `From your phone · …`, `Previous song`/`Play`/`Pause`/`Next song`) were not reachable on the emulator [S].

### 3.8 Mini player (all screens)

| Element | desc | Landscape bounds (clickable parent) | Portrait (desc node) |
|---|---|---|---|
| Artwork | none (letter fallback TextView, e.g. `S`, `Q`) | `[16,952][76,1012]` | |
| Title | station / song name, or `Nothing playing` | `[90,970][…]` | `[90,1810][…]` |
| Subtitle | `Connecting…` while buffering, else ICY song title (only if it differs from the station name) | `[90,983][…,1007]` | |
| Shuffle (**local songs only**) | `Shuffle off` / `Shuffle on` | `[1588,954][1644,1010]` | `[761,1807][791,1837]` |
| Previous | `Previous` | `[1648,954][1704,1010]` | `[821,1807][852,1838]` |
| Play/Pause | `Pause` while playing, `Play` while paused | `[1712,950][1776,1014]` | `[887,1805][922,1840]` |
| Next | `Next` | `[1784,954][1840,1010]` | `[957,1807][988,1838]` |
| Favorite | `Favorite` (**static**, does not reflect state; bug U8) | `[1848,954][1904,1010]` | `[1020,1806][1052,1838]` |

When the shuffle icon is absent, Previous, Play/Pause, Next and Favorite keep their right-anchored positions. Only the title width changes.

### 3.9 Key log dialog

- Title `Wheel button log`. Subtitle `Press the wheel buttons; each press shows up here.` Scrollable log (newest first, monospace), e.g. `14:07:53.582  [Activity] KEYCODE_BACK UP scan=0 src=0x0`, `[Player] seekToNext`, `[Player] seekToPrevious`, `[BT] AVRCP controller service not available`, `[Player] error: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`. Button `Close`.
- Bounds: landscape panel x 670–1250, title `[690,264]`, list `[690,325][1230,724]`, Close `[1159,736][1230,784]`. Portrait: list `[270,745][810,1144]`, Close text `[751,1170][798,1191]`.
- Dismiss [V]: `Close`, system BACK (closes the dialog only, the app stays) and an outside tap (not passed through to the grid) all work.
- The log is in-process (`KeyLog` object). It survives activity recreation but not a force-stop.

---

## 4. Scenario results

| # | Scenario | Orientation | Result | Notes |
|---|---|---|---|---|
| 1 | Screen map / UI tree | L + P | PASS | See section 3. No testTags or resource-ids. |
| 2a | Tap each tab | L + P | PASS | `selected="true"` moves accordingly. |
| 2b | Horizontal swipe on the content area | L (Countries, Favorites) + P (Local) | OBSERVED: no effect | No HorizontalPager; the tab stays the same. By design, not a bug. |
| 2c | Horizontal scroll | L + P | OBSERVED: none | TabRow is fixed; the chip row is not scrollable (see U2). |
| 3a | Armenia: open tile | L | PASS | 51 rows, curated Armenian names first. |
| 3b | Russia: open tile | L + P | PASS | 292 rows. Russia list kept across rotation. |
| 3c | Germany: open tile | L | PASS | 289 rows. |
| 3d | Back arrow | L | PASS | Returns to the grid. |
| 3e | System BACK from a country list | L + P | PASS | Returns to the grid. The activity stays resumed. |
| 3f | Tile play button (Armenia/Russia/Germany) | L | PASS | Mini player becomes `Հանրային ռադիո` / `Anon.FM` / `MANGORADIO`; MediaSession `state=3` (PLAYING); `queue` written, `last_index`=0. |
| 3g | Tap a station row | L | PASS | Germany row 2 → `SWR3` playing. Row highlighted (visual only). |
| 3h | Mini player Pause / Play | L | PASS | desc toggles `Pause`↔`Play`. |
| 3i | Mini player Next / Previous | L | PASS | `MANGORADIO`→`SWR3`→`MANGORADIO`. Next while **paused** resumes playback (observed, car-radio design). |
| 4a | Search `j` (1 char) | L | PASS | No search; grid stays. |
| 4b | Search `jazz` | L + P | PASS | Results listed (section 3.4). |
| 4c | Search `Arm` | L | PASS (with U9) | Armenia chip; chip opens Armenia and clears the query. |
| 4d | Clear search (×) | L + P | PASS | Query and results cleared, placeholder back. The IME stays open. |
| 4e | IME Enter/Done | L | FAIL (U5) | Nothing happens; the keyboard stays. |
| 4f | Search while a country list is open | L | FAIL (U1) | Query ignored. |
| 4g | Search `Islands` (6 long chips) | L PASS / P FAIL (U2) | | |
| 5a | Heart from a list row | L | PASS | desc becomes `Remove from favorites`. |
| 5b | Heart from the mini player | L | PASS | Heart filled red; desc stays `Favorite` (U8). |
| 5c | Favorites tab content | L | PASS | 3 items in insertion order. |
| 5d | `radio.xml` `favorites` key | – | PASS | Section 5. |
| 5e | Favorites filter by query `m` | L | PASS | Only `MANGORADIO`. |
| 5f | Force-stop + relaunch persistence | L | PASS | Same 3 favorites; `radio.xml` byte-identical before and after. Playback auto-resumed. |
| 5g | Un-heart all → empty state | L | PASS | `No favorites yet. Tap the heart next to a station to add it here.`; `favorites`=`[]`. |
| 6a | Local: no permission | L | PASS | `Allow access to music` + explanation text. |
| 6b | Permission Deny / Allow | L | PASS | |
| 6c | Local: granted, empty | L | PASS | `No audio files found. Plug in a USB stick and tap Rescan.` |
| 6d | Push 3 MP3s + media scan + Rescan | L | PASS | Only after a manual Rescan; `Shuffle all (3)` appears. |
| 6e | Play a local song | L + P | PASS | Mini player shows the title and a `Shuffle off` toggle. |
| 6f | Shuffle toggle | L | PASS | `Shuffle on`/`off`; `radio.xml` `shuffle` true/false. |
| 6g | Shuffle toggle hidden for radio | L | PASS | After playing Armenia radio: no shuffle node; `shuffle`=false. |
| 6h | `Shuffle favorite songs (2)` | L + P | PASS | Starts a random song with shuffle on. Next stays within the 2 favorites. |
| 7 | Bluetooth tab | L + P | OBSERVED | `NEEDS_ACCESS` screen; Retry has no effect on the emulator. |
| 8 | Key log dialog | L + P | PASS | Section 3.9. |
| 9a | Rotation keeps state | L→P→L | PASS | Same pid, no `Displayed` log, Russia list stays open: the **activity is not recreated**. |
| 9b | Layout/clipping | L + P | see section 6 | |
| 10 | Offline (wifi+data off, cold start) | L | OBSERVED + FAIL (U3, U7) | Error + `Try again`; Local tab works; the player walks through the queue. Back online + `Try again` → grid restored. |
| 11 | System BACK on a tab with no country open | L | OBSERVED | The activity finishes (launcher shown). Relaunch is WARM and resets to the By countries tab with an empty query. Playback continues. |

---

## 5. Persistence evidence (`adb shell run-as dev.wheelradio cat shared_prefs/radio.xml`)

- Initially (fresh install) [V]: `shared_prefs/` was **empty**. There is no `radio.xml` until the first play or favorite. The service fell back to `assets/stations.json` (`SomaFM Groove Salad`).
- After the first play: `queue` (JSON array of the whole country list) and `last_index`.
- Favorites before force-stop (decoded excerpt; file md5 prefix `d11ee6f9`):
  ```
  "id":"rb:31074f8a-e6f4-11e9-a96c-52543be04c81","name":"France Info"
  "id":"rb:78012206-1aa1-11e9-a80b-52543be04c81","name":"MANGORADIO"
  "id":"am:00","name":"Հանրային ռադիո"
  ```
- After `am force-stop dev.wheelradio` + `am start -W` (COLD): same three entries, **file md5 identical** (`d11ee6f9`). The Favorites tab shows the same three rows.
- Station JSON shape: `{"id","name","url","favicon","subtitle","local"}`. Example: `{"id":"am:00","name":"Հանրային ռադիո","url":"https://eu1.stream4cast.com/proxy/publicra/stream/;stream.mp3","favicon":"https://radio.armblog.net/hanrayin.png","subtitle":"Armenia","local":false}`. A local entry: `{"id":"local:content://media/external_primary/audio/media/13","name":"QA Tone A880","url":"content://media/external_primary/audio/media/13","favicon":"","subtitle":"Device · QA Bot","local":true}`.
- After un-hearting all: `<string name="favorites">[]</string>`. The key remains with an empty array; it is not removed.
- `shuffle`: `<boolean name="shuffle" value="true|false" />`. Written by the service's `onShuffleModeEnabledChanged`.
- `music_folder`: never written (no UI sets it; U6).
- Favorite IDs: curated stations `am:NN`, Radio Browser `rb:<stationuuid>`, local files `local:<content uri>`.

---

## 6. Layout findings

| # | Orientation | Finding | Evidence |
|---|---|---|---|
| LY1 | Portrait | Search chips overflow: the 6th chip is squeezed to `[1040,158][1080,639]` (40 px wide, 481 px tall). The chip row grows to ~480 px, so the first result starts at y=657 instead of ~224. The 5th chip wraps to 2 lines (`Falkland Islands (Islas Malvinas)`). **Bug U2.** | `P05_search_islands.png/.xml` |
| LY2 | Landscape | Long country names are ellipsized in 302 px tiles: `United Arab Emi…`. Fine in portrait (339 px tiles). Intended ellipsis, cosmetic. | `L23_keylog.png` (background), `P02_grid_portrait.png` |
| LY3 | Both | Long station names/subtitles ellipsize correctly (`IBIZA CHILLOUT LOUNGE - … Café…`, `QA Very Long … Station R…`). The heart stays reachable. | `L26_search_jazz.png`, `P06_local_portrait.png` |
| LY4 | Both | Mini player long title: landscape fits in full (`[90,970][1376,995]`); portrait ellipsizes to `QA Very Long Song Title That Should Be Truncated In The M…` before the shuffle icon. No overlap. | `L45_long_title_playing.png`, `P06_local_portrait.png` |
| LY5 | Landscape | The keyboard takes ~40 % of the height; only ~5 search rows are visible and the mini player is pushed above the IME. There is no IME action to dismiss it (U5). | `L26_search_jazz.png` |
| LY6 | Both | Tab labels fit (`LOCAL & USB` 110 px in a 270 px portrait tab). The search field narrows to 984 px in portrait; the placeholder is fully visible. | `P01_russia_portrait.png` |
| LY7 | Both | The Favorites list jumps 72 px when the `Shuffle favorite songs (n)` button appears or disappears. It caught me out (a tap at the old position missed), so automated tests must re-resolve bounds. | `L46_fav_local`, `L56_fav_cleared` |
| LY8 | Landscape | The system volume panel (emulator) overlaps the right column of the grid when volume keys are pressed. Not an app issue, but it can make UiAutomator taps on the right-hand tiles miss. | `L01_countries.png` |

---

## 7. Recommended testTags / contentDescriptions for the test writer

Nothing below exists yet. These are suggestions for the app owner. Until they are added, tests must use the text/desc selectors listed in section 3. With Compose, set `Modifier.semantics { testTagsAsResourceId = true }` on the root so UiAutomator can see the tags as resource-ids.

| Element (file: composable) | Current handle | Suggested testTag | Suggested a11y change |
|---|---|---|---|
| Search TextField (`TopBar`) | placeholder text only | `search_field` | – |
| Search leading icon | **none** (`null`) | – | keep `null` (decorative) |
| Clear search | desc `Clear search` | `search_clear` | – |
| Key log button | desc `Key log` | `keylog_button` | – |
| Tabs (`TabRow`) | uppercase text | `tab_countries`, `tab_favorites`, `tab_local`, `tab_bluetooth` | – |
| Country grid | **none** | `country_grid` | – |
| Country tile | name text (not unique: also in subtitles) | `country_tile_<CODE>` (e.g. `country_tile_AM`) | `onClickLabel = "Open <name>"` |
| Tile station count | `"<n> Stations"` | `country_count_<CODE>` | – |
| Tile play button | desc `Play <name>` | `country_play_<CODE>` | – |
| Country loading spinner | **none** | `country_loading` | desc `Loading` |
| Countries error + retry | text | `countries_error`, `countries_retry` | – |
| Country header back | desc `Back` (generic) | `country_back` | – |
| Country header title | `"<flag>  <name>"` | `country_title` | – |
| Station list (LazyColumn) | **none** | `station_list` | – |
| Station row | name text (duplicates are possible across lists) | `station_row_<station.id>` | `stateDescription = "Playing"` on the current row (today only a border shows it) |
| Row heart | desc `Add to favorites`/`Remove from favorites` (identical on every row) | `station_fav_<station.id>` | – |
| Row artwork | **none** / letter text | `station_art` | keep decorative; consider `clearAndSetSemantics {}` so the fallback letter does not appear as text |
| Empty-list text | text | `empty_state` | – |
| Search chips | `"<flag> <name>"` | `search_country_chip_<CODE>` | – |
| Search spinner | **none** | `search_loading` | – |
| Favorites shuffle button | text with count | `favorites_shuffle` | – |
| Local "Allow access" / "Rescan" button | text (changes by state) | `local_scan_button` | – |
| Local "Shuffle all (n)" | text with count | `local_shuffle_all` | – |
| Local spinner | **none** | `local_loading` | – |
| BT setup / retry / open settings | text | `bt_open_settings`, `bt_retry`, `bt_open_car_app` | – |
| Mini player container | **none** | `mini_player` | – |
| Mini player title / subtitle | text | `mini_title`, `mini_subtitle` | – |
| Mini player artwork | **none** | `mini_art` | – |
| Mini player shuffle | desc `Shuffle on/off` | `mini_shuffle` | use `toggleableState` / `Role.Switch` |
| Mini player prev / play-pause / next | desc `Previous` / `Play`·`Pause` / `Next` | `mini_prev`, `mini_play_pause`, `mini_next` | – |
| Mini player heart | desc `Favorite` (**static**) | `mini_fav` | desc `Add to favorites`/`Remove from favorites`, or `stateDescription` (U8) |
| Key log dialog / list / close | text | `keylog_dialog`, `keylog_list`, `keylog_close` | – |

Stable selectors available **today** (UiAutomator `By.desc` / Compose `onNodeWithContentDescription`): `Key log`, `Clear search`, `Back`, `Play <Country>`, `Add to favorites`, `Remove from favorites`, `Previous`, `Play`, `Pause`, `Next`, `Favorite`, `Shuffle on`, `Shuffle off`. Texts: tab labels (uppercase), `Try again`, `Rescan device & USB`, `Allow access to music`, `Shuffle all (n)`, `Shuffle favorite songs (n)`, `Close`, `Retry`, `Open settings`, and the empty-state strings quoted above.

Test-data advice:
- Radio Browser data is live and changes daily. For deterministic tests, stub `RadioBrowser` (it is a Kotlin `object`, so it cannot be swapped without a small seam, e.g. an injectable base URL or a MockWebServer).
- Use curated `am:*` stations (bundled asset), `assets/stations.json` and the three `/sdcard/Music/qa_*.mp3` files as fixed fixtures.

---

## 8. Bugs found

(Full repro steps are also in `test-results/bugs.md`.)

| ID | Title | Severity | Basis |
|---|---|---|---|
| U1 | Search query is ignored while a country station list is open | Medium | [V] |
| U2 | Portrait: search country chips overflow; the last chip is crushed to 40 px and pushes results ~450 px down | Medium | [V] |
| U3 | Curated Armenian stations are unreachable when the Radio Browser countries request fails (offline / API down) | Medium | [V] error state + [S] |
| U4 | Country tile station count changes after opening the country (2494 → 292, 56 → 51) | Low | [V] |
| U5 | Search IME action does nothing; the keyboard can only be closed with system BACK | Low | [V] |
| U6 | No UI to pick a music folder: the USB fallback (`scanFolder`/`music_folder`) is unreachable | Medium | [S] |
| U7 | While offline the player walks through the whole queue and overwrites `last_index`, so the driver's station is lost | Medium | [V] |
| U8 | Mini-player favorite button has a static contentDescription `Favorite` (state not exposed) | Low | [V] |
| U9 | Curated Armenian stations are not found by search (`Arm` returns none of them) | Low | [V] |

Risks inferred from source but **not reproduced** (not filed as bugs):
- R1 `AppModel.showCountry` race. Opening country A, going back and quickly opening B: A's coroutine still runs `countryLoading = false` (and can set `countryError`) while B is loading. This could briefly show `No stations found.` or `Couldn't load stations.` for B. Three quick open/back/open attempts did not catch it; uiautomator dumps are too slow.
- R2 Typing on the Favorites or Local tab still fires network searches (`updateQuery` is global). This wastes mobile data in the car.
- R3 `LocalTab` permission callback calls `scanLocal()` even on Deny (harmless).
