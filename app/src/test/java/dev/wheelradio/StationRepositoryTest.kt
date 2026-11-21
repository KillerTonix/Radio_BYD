package dev.wheelradio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SharedPreferences "radio" (radio.xml) persistence. Robolectric gives every test a fresh prefs store. */
@RunWith(AndroidJUnit4::class)
class StationRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    private val a = Station("am:00", "Հանրային ռադիո", "https://eu1.stream4cast.com/proxy/publicra/stream/;stream.mp3", subtitle = "Armenia")
    private val b = Station("rb:2", "SWR3", "https://swr3.example/live.mp3", subtitle = "Germany")
    private val c = Station("local:content://media/external_primary/audio/media/12", "QA Tone A440", "content://media/external_primary/audio/media/12", local = true)

    @Before fun fresh() {
        prefs.edit().clear().commit()
    }

    @Test fun fresh_install_defaults() {
        val repo = StationRepository(context)
        assertThat(repo.favorites).isEmpty()
        assertThat(repo.lastIndex).isEqualTo(0)
        assertThat(repo.shuffle).isFalse()
        assertThat(repo.musicFolder).isNull()
    }

    @Test fun empty_queue_falls_back_to_bundled_stations_json() {
        val repo = StationRepository(context)
        assertThat(repo.queue).isEqualTo(repo.defaults())
        assertThat(repo.queue.first().id).isEqualTo("somafm-groovesalad")
        prefs.edit().putString("queue", "[]").commit()
        assertThat(repo.queue.map { it.id }).containsExactly(
            "somafm-groovesalad", "radioparadise-main", "jazz24", "bbc-world-service", "somafm-defcon",
        ).inOrder()
    }

    @Test fun favorites_persist_across_instances_in_insertion_order() {
        StationRepository(context).favorites = listOf(b, a, c)
        assertThat(StationRepository(context).favorites).containsExactly(b, a, c).inOrder()
    }

    @Test fun clearing_favorites_writes_an_empty_array() {
        val repo = StationRepository(context)
        repo.favorites = listOf(a)
        repo.favorites = emptyList()
        assertThat(prefs.getString("favorites", null)).isEqualTo("[]")
        assertThat(StationRepository(context).favorites).isEmpty()
    }

    @Test fun queue_and_last_index_persist() {
        val repo = StationRepository(context)
        repo.queue = listOf(a, b, c)
        repo.lastIndex = 2
        val again = StationRepository(context)
        assertThat(again.queue).containsExactly(a, b, c).inOrder()
        assertThat(again.lastIndex).isEqualTo(2)
    }

    @Test fun shuffle_and_music_folder_persist() {
        StationRepository(context).apply {
            shuffle = true
            musicFolder = "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic"
        }
        val again = StationRepository(context)
        assertThat(again.shuffle).isTrue()
        assertThat(again.musicFolder).isEqualTo("content://com.android.externalstorage.documents/tree/1234-5678%3AMusic")
    }

    @Test fun reads_the_radio_xml_shape_written_by_the_app() {
        // Excerpt of a real radio.xml from the emulator (exploration-report section 5).
        prefs.edit()
            .putString(
                "favorites",
                """[{"id":"am:00","name":"Հանրային ռադիո","url":"https://eu1.stream4cast.com/proxy/publicra/stream/;stream.mp3","favicon":"https://radio.armblog.net/hanrayin.png","subtitle":"Armenia","local":false}]""",
            )
            .putInt("last_index", 6)
            .putBoolean("shuffle", false)
            .commit()
        val repo = StationRepository(context)
        assertThat(repo.favorites.single().favicon).isEqualTo("https://radio.armblog.net/hanrayin.png")
        assertThat(repo.lastIndex).isEqualTo(6)
    }
}
