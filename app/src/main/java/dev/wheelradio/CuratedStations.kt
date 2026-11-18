package dev.wheelradio

import android.content.Context

/**
 * Hand-picked stations per country, shown first and merged with the Radio Browser directory.
 * Files: assets/stations_<code>.json (same format as stations.json). Armenia comes from a list the owner supplied.
 */
object CuratedStations {
    private val cache = HashMap<String, List<Station>>()

    fun forCountry(context: Context, code: String): List<Station> = cache.getOrPut(code.uppercase()) {
        runCatching {
            context.assets.open("stations_${code.lowercase()}.json").bufferedReader().use {
                Station.listFromJson(it.readText())
            }
        }.getOrDefault(emptyList())
    }

    /** Curated stations first, then directory stations that aren't already in the curated list. */
    fun merge(curated: List<Station>, directory: List<Station>): List<Station> {
        if (curated.isEmpty()) return directory
        val urls = curated.map { normUrl(it.url) }.toHashSet()
        val names = curated.map { normName(it.name) }.toHashSet()
        return curated + directory.filter { normUrl(it.url) !in urls && normName(it.name) !in names }
    }

    private fun normUrl(u: String) = u.lowercase()
        .substringAfter("://").substringBefore('?').removeSuffix("/").removeSuffix(";stream.mp3").removeSuffix("/")

    private fun normName(n: String) = n.lowercase().filter { it.isLetterOrDigit() }
        .removePrefix("radio").removePrefix("ռադիո").removePrefix("радио")
}
