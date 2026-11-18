package dev.wheelradio

import android.content.Context
import org.json.JSONArray

/**
 * Station list + "last played" memory.
 * v0.1 reads a bundled list from assets/stations.json. Next step: let the user add stations
 * (search via the free Radio Browser API, https://www.radio-browser.info) and store them here.
 */
class StationRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun load(): List<Station> {
        val json = context.assets.open("stations.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Station(o.getString("id"), o.getString("name"), o.getString("url"))
        }
    }

    var lastIndex: Int
        get() = prefs.getInt("last_index", 0)
        set(value) = prefs.edit().putInt("last_index", value).apply()
}
