package dev.wheelradio

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppTab(@androidx.annotation.StringRes val title: Int) {
    COUNTRIES(R.string.tab_radio), FAVORITES(R.string.tab_favorites), LOCAL(R.string.tab_music), BLUETOOTH(R.string.tab_bluetooth)
}

data class NowPlaying(
    val station: Station? = null,
    val title: String = "",
    val nowPlaying: String = "",
    val artwork: String = "",
    val artworkData: ByteArray? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val shuffle: Boolean = false,
    val canShuffle: Boolean = false,
    /** Why nothing is playing (retrying, no internet, station unavailable), from RadioService; "" when all is well. */
    val status: String = "",
)

/** Screen state. Lives as long as the activity; all network / disk work runs on Dispatchers.IO. */
class AppModel(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val repo = StationRepository(appContext)
    val settings = AppSettings(appContext)

    /** Set by the activity: replace the play queue and start playing at index. */
    var playList: (List<Station>, Int) -> Unit = { _, _ -> }

    /** Set by the activity: play the list in random order. */
    var playShuffled: (List<Station>) -> Unit = { }

    /** Set by the activity: position and duration (ms) of what our player plays; duration <= 0 for live radio. */
    var position: () -> Pair<Long, Long> = { 0L to 0L }

    /** Set by the activity: jump to a position (ms) in a local / USB song. */
    var seekTo: (Long) -> Unit = { }

    /** Set by the activity: pause our own player (before Bluetooth starts). */
    var pauseRadio: () -> Unit = { }

    /** Set by the activity: turn shuffle on / off for whatever is playing. */
    var setShuffle: (Boolean) -> Unit = { }

    private var tabState by mutableStateOf(AppTab.entries.firstOrNull { it.name == repo.lastTab } ?: AppTab.COUNTRIES)

    /** The open tab; remembered across app restarts. */
    var tab: AppTab
        get() = tabState
        set(value) {
            tabState = value
            repo.lastTab = value.name
        }

    /** The full-screen player shown after the screen goes untouched for a while. */
    var bigPlayer by mutableStateOf(false)

    /** Uptime of the last touch anywhere in the app (not Compose state: it changes on every touch). */
    @Volatile var lastTouchAt = SystemClock.uptimeMillis()
        private set

    /** The search field has focus: the driver types on the keyboard, whose touches the app doesn't see. */
    var searchFocused = false

    fun onUserTouch() { lastTouchAt = SystemClock.uptimeMillis() }

    /** Time for the big player: idle long enough, nothing else on top, and something to show. */
    fun bigPlayerDue(nowUptime: Long = SystemClock.uptimeMillis()): Boolean =
        settings.bigPlayer && !bigPlayer && !showSettings && !showKeyLog && !searchFocused &&
            nowUptime - lastTouchAt >= AppSettings.BIG_PLAYER_IDLE_MS &&
            (if (BluetoothBridge.inUse) BluetoothBridge.title.isNotBlank() else now.station != null)
    var query by mutableStateOf("")
        private set
    var showKeyLog by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var now by mutableStateOf(NowPlaying())

    var countries by mutableStateOf<List<Country>>(emptyList())
    var countriesError by mutableStateOf<String?>(null)
    var countriesLoading by mutableStateOf(false)

    var openCountry by mutableStateOf<Country?>(null)
    var countryStations by mutableStateOf<List<Station>>(emptyList())
    var countryLoading by mutableStateOf(false)
    var countryError by mutableStateOf<String?>(null)

    var searchResults by mutableStateOf<List<Station>>(emptyList())
    var searching by mutableStateOf(false)
    private var searchJob: Job? = null

    var favorites by mutableStateOf(repo.favorites)
        private set

    /** The user's pick between radio and song favorites; null until they tap one. */
    private var favoriteSongsPicked by mutableStateOf<Boolean?>(null)

    /**
     * Whether the Favorites tab shows songs (local / USB) instead of radio stations. Until the user picks one it
     * follows what is playing, and falls back to songs only when there are no radio favorites to show.
     */
    var showFavoriteSongs: Boolean
        get() = favoriteSongsPicked ?: (now.station?.local == true ||
            (favorites.none { !it.local } && favorites.any { it.local }))
        set(value) { favoriteSongsPicked = value }

    val favoriteRadios: List<Station> get() = favorites.filterNot { it.local }
    val favoriteSongs: List<Station> get() = favorites.filter { it.local }

    var localFiles by mutableStateOf<List<Station>>(emptyList())
    var localScanning by mutableStateOf(false)
    var localScanned by mutableStateOf(false)
    val musicFolder: String? get() = repo.musicFolder

    fun loadCountries() {
        if (countriesLoading) return
        countriesLoading = true
        countriesError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    RadioBrowser.countries(settings.homeCountry).map { c ->
                        val extra = CuratedStations.forCountry(appContext, c.code).size
                        if (extra > 0) c.copy(stationCount = c.stationCount + extra) else c
                    }
                }
            }
                .onSuccess { countries = it }
                .onFailure { countriesError = "Couldn't load countries. Check the internet connection." }
            countriesLoading = false
        }
    }

    fun showCountry(country: Country) {
        openCountry = country
        countryStations = emptyList()
        countryError = null
        countryLoading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { stationsFor(country.code) } }
                .onSuccess { list ->
                    if (openCountry == country) countryStations = list
                    // Show the real number once curated + directory stations are merged.
                    countries = countries.map { if (it.code == country.code) it.copy(stationCount = list.size) else it }
                }
                .onFailure { countryError = "Couldn't load stations." }
            countryLoading = false
        }
    }

    /** Play button on a country tile: start its most popular station, with the whole country as the queue. */
    fun playCountry(country: Country) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { stationsFor(country.code) } }
                .onSuccess { if (it.isNotEmpty()) playList(it, 0) }
        }
    }

    /** Curated stations first, then the directory; curated alone still works when the directory is down. */
    private fun stationsFor(code: String): List<Station> {
        val curated = CuratedStations.forCountry(appContext, code)
        val directory = runCatching { RadioBrowser.stationsByCountry(code) }
        if (curated.isEmpty()) return directory.getOrThrow()
        return CuratedStations.merge(curated, directory.getOrDefault(emptyList()))
    }

    fun updateQuery(q: String) {
        query = q
        searchJob?.cancel()
        if (q.trim().length < 2) {
            searchResults = emptyList()
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            delay(450) // wait until typing pauses
            runCatching { withContext(Dispatchers.IO) { RadioBrowser.search(q.trim()) } }
                .onSuccess { searchResults = it }
                .onFailure { searchResults = emptyList() }
            searching = false
        }
    }

    /** Pin [code] first in the country grid, without reloading the directory. */
    fun setHomeCountry(code: String) {
        settings.updateHomeCountry(code)
        countries = RadioBrowser.sortCountries(countries, code)
    }

    fun isFavorite(id: String?) = id != null && favorites.any { it.id == id }

    fun toggleFavorite(station: Station) {
        favorites = if (isFavorite(station.id)) favorites.filterNot { it.id == station.id } else favorites + station
        repo.favorites = favorites
    }

    fun scanLocal() {
        localScanning = true
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                val fromStore = runCatching { LocalMusic.scanMediaStore(appContext) }.getOrDefault(emptyList())
                val fromFolder = repo.musicFolder?.let {
                    runCatching { LocalMusic.scanFolder(appContext, Uri.parse(it)) }.getOrDefault(emptyList())
                } ?: emptyList()
                (fromFolder + fromStore).distinctBy { it.id }
            }
            localFiles = found
            localScanning = false
            localScanned = true
        }
    }

    fun setMusicFolder(uri: Uri) {
        repo.musicFolder = uri.toString()
        scanLocal()
    }

    fun rememberQueue(list: List<Station>, index: Int) {
        repo.queue = list
        repo.lastIndex = index
    }
}
