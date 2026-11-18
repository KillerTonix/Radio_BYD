package dev.wheelradio

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Free, open station directory: https://www.radio-browser.info (no API key).
 * Call from a background thread.
 */
object RadioBrowser {
    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
    )
    private var workingServer: String? = null

    /** Countries pinned to the top of the grid, in this order. */
    private val pinned = listOf("AM", "RU", "DE", "US", "GE", "FR")

    fun countries(): List<Country> {
        val arr = JSONArray(get("/json/countries"))
        val byCode = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val code = o.optString("iso_3166_1").uppercase()
            if (code.length != 2 || !code.all { it in 'A'..'Z' }) continue
            byCode[code] = (byCode[code] ?: 0) + o.optInt("stationcount")
        }
        return byCode.filter { it.value > 0 }
            .map { (code, count) ->
                val name = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }
                Country(code, name, count)
            }
            .sortedWith(compareBy<Country> { pinned.indexOf(it.code).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenByDescending { it.stationCount })
    }

    fun stationsByCountry(code: String): List<Station> =
        parseStations(get("/json/stations/bycountrycodeexact/$code?hidebroken=true&order=clickcount&reverse=true&limit=300"))

    fun search(name: String): List<Station> =
        parseStations(get("/json/stations/search?name=${URLEncoder.encode(name, "UTF-8")}&hidebroken=true&order=clickcount&reverse=true&limit=60"))

    private fun parseStations(json: String): List<Station> {
        val arr = JSONArray(json)
        val seen = HashSet<String>()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (url.isBlank() || name.isBlank() || !seen.add(name.lowercase())) return@mapNotNull null
            val tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3)
            Station(
                id = "rb:" + o.optString("stationuuid"),
                name = name,
                url = url,
                favicon = o.optString("favicon"),
                subtitle = (listOf(o.optString("country")) + tags).filter { it.isNotBlank() }.joinToString(" · "),
            )
        }
    }

    private fun get(path: String): String {
        val order = listOfNotNull(workingServer) + servers.filter { it != workingServer }
        var last: Exception? = null
        for (server in order) {
            try {
                val conn = URL(server + path).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "WheelRadio/0.2 (Android)")
                try {
                    if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    workingServer = server
                    return body
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: RuntimeException("No server")
    }
}
