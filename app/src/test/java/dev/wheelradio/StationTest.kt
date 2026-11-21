package dev.wheelradio

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StationTest {
    private val radio = Station(
        id = "am:00",
        name = "Հանրային ռադիո",
        url = "https://eu1.stream4cast.com/proxy/publicra/stream/;stream.mp3",
        favicon = "https://radio.armblog.net/hanrayin.png",
        subtitle = "Armenia",
    )
    private val russian = Station("rb:1", "Русское Радио", "http://x.example/rr.mp3?a=1&b=2", subtitle = "Russia · pop")
    private val song = Station(
        id = "local:content://media/external_primary/audio/media/13",
        name = "QA Tone A880",
        url = "content://media/external_primary/audio/media/13",
        subtitle = "Device · QA Bot",
        local = true,
    )

    // ---- JSON ----

    @Test fun json_round_trip_keeps_every_field_and_unicode() {
        listOf(radio, russian, song).forEach { assertThat(Station.fromJson(it.toJson())).isEqualTo(it) }
    }

    @Test fun list_json_round_trip_keeps_order() {
        val list = listOf(radio, russian, song)
        assertThat(Station.listFromJson(Station.listToJson(list))).containsExactlyElementsIn(list).inOrder()
    }

    @Test fun to_json_uses_the_persisted_key_names() {
        val o = radio.toJson()
        assertThat(o.keys().asSequence().toSet()).containsExactly("id", "name", "url", "favicon", "subtitle", "local")
    }

    @Test fun null_blank_and_empty_json_give_empty_list() {
        assertThat(Station.listFromJson(null)).isEmpty()
        assertThat(Station.listFromJson("")).isEmpty()
        assertThat(Station.listFromJson("   ")).isEmpty()
        assertThat(Station.listFromJson("[]")).isEmpty()
    }

    @Test fun optional_fields_default_when_missing() {
        // stations.json entries only have id, name, url
        val s = Station.fromJson(JSONObject("""{"id":"x","name":"X","url":"https://x"}"""))
        assertThat(s).isEqualTo(Station("x", "X", "https://x", favicon = "", subtitle = "", local = false))
    }

    @Test(expected = org.json.JSONException::class)
    fun missing_required_field_throws() {
        Station.fromJson(JSONObject("""{"id":"x","name":"X"}"""))
    }

    // ---- MediaItem ----

    @Test fun radio_media_item_carries_id_uri_metadata() {
        val item = radio.toMediaItem()
        assertThat(item.mediaId).isEqualTo("am:00")
        assertThat(item.localConfiguration!!.uri.toString()).isEqualTo(radio.url)
        assertThat(item.requestMetadata.mediaUri.toString()).isEqualTo(radio.url)
        assertThat(item.mediaMetadata.title.toString()).isEqualTo(radio.name)
        assertThat(item.mediaMetadata.station.toString()).isEqualTo(radio.name)
        assertThat(item.mediaMetadata.artist.toString()).isEqualTo("Armenia")
        assertThat(item.mediaMetadata.artworkUri.toString()).isEqualTo(radio.favicon)
        assertThat(item.mediaMetadata.isPlayable).isTrue()
        assertThat(item.mediaMetadata.isBrowsable).isFalse()
    }

    @Test fun local_media_item_has_no_station_and_blank_fields_are_null() {
        val item = song.toMediaItem()
        assertThat(item.mediaMetadata.station).isNull()
        assertThat(item.mediaMetadata.artworkUri).isNull()
        val bare = Station("b", "Bare", "http://b").toMediaItem()
        assertThat(bare.mediaMetadata.artist).isNull()
    }

    @Test fun media_item_round_trip_returns_the_same_station() {
        listOf(radio, russian, song).forEach {
            assertThat(Station.fromMediaItem(it.toMediaItem())).isEqualTo(it)
        }
    }

    /** MediaController -> MediaSession strips localConfiguration; the URI must survive in requestMetadata. */
    @Test fun uri_survives_the_controller_hop_via_request_metadata() {
        val original = radio.toMediaItem()
        val overTheWire = MediaItem.Builder()
            .setMediaId(original.mediaId)
            .setMediaMetadata(original.mediaMetadata)
            .setRequestMetadata(original.requestMetadata)
            .build()
        assertThat(overTheWire.localConfiguration).isNull()
        assertThat(Station.fromMediaItem(overTheWire)).isEqualTo(radio)
    }

    @Test fun from_media_item_falls_back_to_local_configuration_uri() {
        val item = MediaItem.Builder().setMediaId("rb:9").setUri(Uri.parse("http://only/uri")).build()
        assertThat(Station.fromMediaItem(item).url).isEqualTo("http://only/uri")
    }

    @Test fun local_flag_comes_from_the_local_id_prefix() {
        val item = MediaItem.Builder().setMediaId("local:content://media/x/1").setUri("content://media/x/1").build()
        assertThat(Station.fromMediaItem(item).local).isTrue()
        assertThat(Station.fromMediaItem(radio.toMediaItem()).local).isFalse()
    }

    @Test fun from_media_item_without_metadata_gives_blank_fields() {
        val s = Station.fromMediaItem(MediaItem.Builder().setMediaId("x").build())
        assertThat(s).isEqualTo(Station("x", "", "", "", "", false))
    }

    // ---- Country ----

    @Test fun country_flag_is_built_from_regional_indicators() {
        assertThat(Country("AM", "Armenia", 1).flag).isEqualTo("🇦🇲") // 🇦🇲
        assertThat(Country("ru", "Russia", 1).flag).isEqualTo("🇷🇺")  // 🇷🇺
        assertThat(Country("DE", "Germany", 1).flag).isEqualTo("🇩🇪") // 🇩🇪
    }
}
