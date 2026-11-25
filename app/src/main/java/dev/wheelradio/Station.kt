package dev.wheelradio

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

/** Anything playable: an internet station or a local / USB audio file. */
data class Station(
    val id: String,
    val name: String,
    val url: String,
    val favicon: String = "",
    val subtitle: String = "",
    val local: Boolean = false,
) {
    fun toMediaItem(): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(name)
            .setStation(if (local) null else name)
            .setArtist(subtitle.ifBlank { null })
            .setIsPlayable(true)
            .setIsBrowsable(false)
        if (favicon.isNotBlank()) meta.setArtworkUri(favicon.toUri())
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(url)
            // requestMetadata survives the controller -> service hop; RadioService rebuilds the URI from it.
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(url.toUri()).build())
            .setMediaMetadata(meta.build())
            .build()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url)
        .put("favicon", favicon).put("subtitle", subtitle).put("local", local)

    companion object {
        fun fromJson(o: JSONObject) = Station(
            o.getString("id"), o.getString("name"), o.getString("url"),
            o.optString("favicon"), o.optString("subtitle"), o.optBoolean("local"),
        )

        fun listToJson(list: List<Station>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String?): List<Station> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = JSONArray(json)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        fun fromMediaItem(item: MediaItem): Station {
            val m = item.mediaMetadata
            val uri: Uri? = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
            return Station(
                id = item.mediaId,
                name = (m.station ?: m.title ?: "").toString(),
                url = uri?.toString() ?: "",
                favicon = m.artworkUri?.toString() ?: "",
                subtitle = m.artist?.toString() ?: "",
                local = item.mediaId.startsWith("local:"),
            )
        }
    }
}

data class Country(val code: String, val name: String, val stationCount: Int) {
    /** The country's name in [locale] (falls back to the English [name]). */
    fun displayName(locale: java.util.Locale): String =
        java.util.Locale("", code).getDisplayCountry(locale).takeIf { it.isNotBlank() && it != code } ?: name

    /** Flag emoji built from the two-letter ISO code. */
    val flag: String
        get() = code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}
