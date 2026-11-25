package dev.wheelradio

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * Finds a song's cover online by artist, title and album, for Bluetooth songs.
 *
 * Android 10's AVRCP controller passes only text (title, artist, album, duration…), never a picture: Bluetooth
 * cover art (AVRCP BIP) arrived in Android 11, and BYD's Media Center gets its covers through BYD's own Bluetooth
 * stack, which other apps can't reach. So the cover is looked up in two free directories that need no key:
 * the iTunes Search API, then Deezer. A result counts only when both title and artist match, and one from the
 * same album wins, so a wrong cover is less likely than none.
 *
 * Call [find] from a background thread.
 */
object CoverSearch {
    data class Hit(val url: String, val service: String)

    private const val TIMEOUT_MS = 6_000

    /** Remembers answers (including "nothing found") so a song is looked up once per run. */
    private val cache = object : LinkedHashMap<String, Hit?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Hit?>?) = size > 200
    }

    fun find(artist: String, title: String, album: String): Hit? {
        if (artist.isBlank() || title.isBlank()) return null // a title alone matches too many songs
        val key = "${norm(artist)}|${norm(title)}|${norm(album)}"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        var failed = false
        val hit = listOf(
            "iTunes" to {
                val term = URLEncoder.encode("$artist $title", "UTF-8")
                pickItunes(get("https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=15"), artist, title, album)
            },
            "Deezer" to {
                val q = URLEncoder.encode("artist:\"$artist\" track:\"$title\"", "UTF-8")
                pickDeezer(get("https://api.deezer.com/search?q=$q&limit=15"), artist, title, album)
            },
        ).firstNotNullOfOrNull { (service, search) ->
            runCatching(search).onFailure { failed = true }.getOrNull()?.let { Hit(it, service) }
        }
        // A network failure isn't "no cover": try again next time the song comes up.
        if (hit != null || !failed) synchronized(cache) { cache[key] = hit }
        return hit
    }

    /** The best iTunes result's cover, enlarged from 100 px to 600 px. */
    internal fun pickItunes(json: String, artist: String, title: String, album: String): String? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        val candidates = (0 until results.length()).map { results.getJSONObject(it) }.map {
            Candidate(it.optString("artistName"), it.optString("trackName"), it.optString("collectionName"), it.optString("artworkUrl100"))
        }
        return best(candidates, artist, title, album)?.replace("100x100bb", "600x600bb")
    }

    /** The best Deezer result's cover (500 px). */
    internal fun pickDeezer(json: String, artist: String, title: String, album: String): String? {
        val data = JSONObject(json).optJSONArray("data") ?: return null
        val candidates = (0 until data.length()).map { data.getJSONObject(it) }.map {
            val al = it.optJSONObject("album")
            Candidate(
                it.optJSONObject("artist")?.optString("name").orEmpty(), it.optString("title"),
                al?.optString("title").orEmpty(), al?.optString("cover_big").orEmpty(),
            )
        }
        return best(candidates, artist, title, album)
    }

    private class Candidate(val artist: String, val title: String, val album: String, val cover: String)

    private fun best(candidates: List<Candidate>, artist: String, title: String, album: String): String? {
        val matching = candidates.filter {
            it.cover.startsWith("http") && sameTitle(it.title, title) && sameArtist(it.artist, artist)
        }
        return (matching.firstOrNull { album.isNotBlank() && sameTitle(it.album, album) } ?: matching.firstOrNull())?.cover
    }

    /** Equal once case, accents, punctuation and "(Remastered…)" / "- Live" style extras are dropped. */
    internal fun sameTitle(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        return x.isNotEmpty() && x == y
    }

    /** "Queen" matches "Queen & David Bowie"; the phone may send only the first of several artists. */
    internal fun sameArtist(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || " $x ".contains(" $y ") || " $y ".contains(" $x ")
    }

    internal fun norm(s: String): String {
        val noExtras = s
            .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ") // (feat. …), [Remastered 2011]
            .replace(Regex("""\s[-–—]\s.*$"""), " ") // "Song - Remastered 2011", "Song - Live"
        return Normalizer.normalize(noExtras, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "") // accents
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("User-Agent", "WheelRadio/1.0")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
