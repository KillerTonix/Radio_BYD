package dev.wheelradio

import android.content.Context

/** Persistent app data: favorites, the current play queue, the chosen music folder. */
class StationRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    /** Bundled starter stations, used until the user picks something. */
    fun defaults(): List<Station> {
        val json = context.assets.open("stations.json").bufferedReader().use { it.readText() }
        return Station.listFromJson(json)
    }

    var favorites: List<Station>
        get() = Station.listFromJson(prefs.getString("favorites", null))
        set(value) = prefs.edit().putString("favorites", Station.listToJson(value)).apply()

    /** The list that next / previous (and the wheel buttons) step through. */
    var queue: List<Station>
        get() = Station.listFromJson(prefs.getString("queue", null)).ifEmpty { defaults() }
        set(value) = prefs.edit().putString("queue", Station.listToJson(value)).apply()

    var lastIndex: Int
        get() = prefs.getInt("last_index", 0)
        set(value) = prefs.edit().putInt("last_index", value).apply()

    var musicFolder: String?
        get() = prefs.getString("music_folder", null)
        set(value) = prefs.edit().putString("music_folder", value).apply()
}
