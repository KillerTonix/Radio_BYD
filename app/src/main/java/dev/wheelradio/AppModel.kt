package dev.wheelradio

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppTab(val title: String) {
    COUNTRIES("By countries"), FAVORITES("Favorites"), LOCAL("Local & USB"), BLUETOOTH("Bluetooth")
}

data class NowPlaying(
    val station: Station? = null,
    val title: String = "",
    val nowPlaying: String = "",
    val artwork: String = "",
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val shuffle: Boolean = false,
    val canShuffle: Boolean = false,
)

/** Screen state. Lives as long as the activity; all network / disk work runs on Dispatchers.IO. */
class AppModel(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val repo = StationRepository(appContext)

    /** Set by the activity: replace the play queue and start playing at index. */
    var playList: (List<Station>, Int) -> Unit = { _, _ -> }

    /** Set by the activity: play the list in random order. */
    var playShuffled: (List<Station>) -> Unit = { }

    /** Set by the activity: pause our own player (before Bluetooth starts). */
    var pauseRadio: () -> Unit = { }

    /** Set by the activity: turn shuffle on / off for whatever is playing. */
    var setShuffle: (Boolean) -> Unit = { }

    var tab by mutableStateOf(AppTab.COUNTRIES)
    var query by mutableStateOf("")
        private set
    var showKeyLog by mutableStateOf(false)
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
                    RadioBrowser.countries().map { c ->
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
