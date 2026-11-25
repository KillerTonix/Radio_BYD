package dev.wheelradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Picking a Bluetooth song's cover from iTunes / Deezer answers. Hermetic: the JSON below has the shape of the
 * real responses (checked by hand), and nothing here goes to the network.
 */
@RunWith(AndroidJUnit4::class) // org.json comes from Robolectric's Android
class CoverSearchTest {
    private fun itunes(vararg r: Triple<String, String, String>) = """{"resultCount":${r.size},"results":[""" +
        r.mapIndexed { i, (artist, track, album) ->
            """{"artistName":"$artist","trackName":"$track","collectionName":"$album",
               "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/a/$i.jpg/100x100bb.jpg"}"""
        }.joinToString(",") + "]}"

    private fun deezer(vararg r: Triple<String, String, String>) = """{"data":[""" +
        r.mapIndexed { i, (artist, track, album) ->
            """{"title":"$track","artist":{"name":"$artist"},
               "album":{"title":"$album","cover_big":"https://cdn-images.dzcdn.net/images/cover/$i/500x500-000000-80-0-0.jpg"}}"""
        }.joinToString(",") + "]}"

    @Test fun itunes_cover_is_enlarged_and_the_same_album_wins() {
        val json = itunes(
            Triple("Queen", "Bohemian Rhapsody (Live in Budapest)", "Queen Budapest (Live)"),
            Triple("Queen", "Bohemian Rhapsody", "Greatest Hits"),
            Triple("Queen", "Bohemian Rhapsody", "A Night At The Opera (Deluxe Edition)"),
        )
        assertThat(CoverSearch.pickItunes(json, "Queen", "Bohemian Rhapsody", "A Night At The Opera"))
            .isEqualTo("https://is1-ssl.mzstatic.com/image/thumb/a/2.jpg/600x600bb.jpg")
        // Album unknown: the first matching song.
        assertThat(CoverSearch.pickItunes(json, "Queen", "Bohemian Rhapsody", ""))
            .isEqualTo("https://is1-ssl.mzstatic.com/image/thumb/a/0.jpg/600x600bb.jpg")
    }

    @Test fun a_wrong_artist_or_song_gives_no_cover_rather_than_a_wrong_one() {
        val json = deezer(Triple("Panic! At The Disco", "Bohemian Rhapsody", "Wayne's World"))
        assertThat(CoverSearch.pickDeezer(json, "Queen", "Bohemian Rhapsody", "")).isNull()
        assertThat(CoverSearch.pickDeezer(json, "Panic! At The Disco", "Somebody to Love", "")).isNull()
        assertThat(CoverSearch.pickDeezer("""{"data":[]}""", "Queen", "Bohemian Rhapsody", "")).isNull()
        assertThat(CoverSearch.pickItunes("""{"resultCount":0,"results":[]}""", "Queen", "Bohemian Rhapsody", "")).isNull()
    }

    @Test fun deezer_cover_with_case_accents_and_extras_ignored() {
        val json = deezer(Triple("Beyoncé", "Halo - Remastered 2020", "I Am... Sasha Fierce"))
        assertThat(CoverSearch.pickDeezer(json, "BEYONCE", "halo", "I AM...SASHA FIERCE"))
            .isEqualTo("https://cdn-images.dzcdn.net/images/cover/0/500x500-000000-80-0-0.jpg")
    }

    @Test fun matching_rules() {
        assertThat(CoverSearch.sameArtist("Queen & David Bowie", "Queen")).isTrue()
        assertThat(CoverSearch.sameArtist("Queens of the Stone Age", "Queen")).isFalse()
        assertThat(CoverSearch.sameTitle("Under Pressure (feat. David Bowie)", "Under pressure")).isTrue()
        assertThat(CoverSearch.sameTitle("Ռադիո Ջազ", "ռադիո ջազ")).isTrue() // Armenian keeps its letters
        assertThat(CoverSearch.sameTitle("(Untitled)", "")).isFalse()
        // No artist from the phone: no lookup at all (a title alone matches too many songs).
        assertThat(CoverSearch.find("", "Bohemian Rhapsody", "")).isNull()
    }
}
