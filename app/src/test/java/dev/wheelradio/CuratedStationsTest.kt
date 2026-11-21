package dev.wheelradio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CuratedStationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun st(id: String, name: String, url: String) = Station(id, name, url)

    private val curated = listOf(
        st("am:00", "Հանրային ռադիո", "https://eu1.stream4cast.com/proxy/publicra/stream/;stream.mp3"),
        st("am:x1", "Ռադիո Վան", "https://radiovan.example.am/live"),
        st("am:x2", "Радио Ереван", "http://yerevan.example.am:8000/stream?1734367162857"),
    )

    // ---- merge ----

    @Test fun empty_curated_returns_directory_unchanged() {
        val dir = listOf(st("rb:1", "A", "http://a"), st("rb:2", "B", "http://b"))
        assertThat(CuratedStations.merge(emptyList(), dir)).isSameInstanceAs(dir)
    }

    @Test fun curated_first_in_order_then_unique_directory_in_order() {
        val dir = listOf(st("rb:1", "Alpha FM", "http://alpha"), st("rb:2", "Beta FM", "http://beta"))
        val merged = CuratedStations.merge(curated, dir)
        assertThat(merged.map { it.id }).containsExactly("am:00", "am:x1", "am:x2", "rb:1", "rb:2").inOrder()
    }

    @Test fun empty_directory_returns_curated() {
        assertThat(CuratedStations.merge(curated, emptyList())).isEqualTo(curated)
    }

    @Test fun duplicate_url_with_shoutcast_suffix_scheme_case_and_trailing_slash_is_removed() {
        val dir = listOf(
            // same as am:00 without ";stream.mp3", other scheme, upper case
            st("rb:1", "Public Radio of Armenia", "HTTP://EU1.stream4cast.com/proxy/publicra/stream"),
            st("rb:2", "Public Radio 2", "https://eu1.stream4cast.com/proxy/publicra/stream/"),
            st("rb:3", "Van FM", "https://radiovan.example.am/live/"),
        )
        assertThat(CuratedStations.merge(curated, dir).map { it.id }).containsExactly("am:00", "am:x1", "am:x2")
    }

    @Test fun duplicate_url_differing_only_in_query_is_removed() {
        val dir = listOf(st("rb:1", "Yerevan Live", "https://yerevan.example.am:8000/stream?other=1"))
        assertThat(CuratedStations.merge(curated, dir)).hasSize(3)
    }

    @Test fun duplicate_names_with_radio_prefix_in_three_scripts_are_removed() {
        val dir = listOf(
            st("rb:1", "Radio Van", "http://other-host/van"),      // Latin "van" != Armenian "վան": kept
            st("rb:2", "Վան", "http://other-host/van2"),           // "Ռադիո Վան" -> "վան"
            st("rb:3", "Ереван", "http://other-host/yerevan"),     // "Радио Ереван" -> "ереван"
            st("rb:4", "RADIO  Ереван!", "http://other-host/y2"),  // "radio" prefix + punctuation
        )
        val ids = CuratedStations.merge(curated, dir).map { it.id }
        assertThat(ids).containsAtLeast("rb:1", "am:00")
        assertThat(ids).containsNoneOf("rb:2", "rb:3", "rb:4")
    }

    @Test fun name_match_ignores_case_spaces_and_punctuation() {
        val dir = listOf(st("rb:1", "  ՀԱՆՐԱՅԻՆ   ՌԱԴԻՈ. ", "http://elsewhere/hr"))
        assertThat(CuratedStations.merge(curated, dir)).hasSize(3)
    }

    @Test fun different_station_on_same_host_is_kept() {
        val dir = listOf(st("rb:1", "Other", "https://eu1.stream4cast.com/proxy/other/stream"))
        assertThat(CuratedStations.merge(curated, dir).map { it.id }).contains("rb:1")
    }

    // ---- forCountry (bundled assets, no network) ----

    @Test fun armenia_reads_46_curated_stations_in_file_order() {
        val am = CuratedStations.forCountry(context, "AM")
        assertThat(am).hasSize(46)
        assertThat(am.first().id).isEqualTo("am:00")
        assertThat(am.first().name).isEqualTo("Հանրային ռադիո")
        assertThat(am.all { it.id.startsWith("am:") && !it.local }).isTrue()
    }

    @Test fun country_code_is_case_insensitive() {
        assertThat(CuratedStations.forCountry(context, "am"))
            .isEqualTo(CuratedStations.forCountry(context, "AM"))
    }

    @Test fun country_without_curated_file_returns_empty() {
        assertThat(CuratedStations.forCountry(context, "DE")).isEmpty()
        assertThat(CuratedStations.forCountry(context, "RU")).isEmpty()
        assertThat(CuratedStations.forCountry(context, "ZZ")).isEmpty()
    }
}
