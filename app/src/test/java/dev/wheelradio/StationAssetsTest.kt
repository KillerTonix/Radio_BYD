package dev.wheelradio

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Structure-only sanity checks on the bundled station lists. No network: whether a stream is alive
 * is tracked in test-results/bugs.md, never asserted here.
 */
@RunWith(AndroidJUnit4::class)
class StationAssetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun load(name: String): List<Station> =
        context.assets.open(name).bufferedReader().use { Station.listFromJson(it.readText()) }

    private fun checkStructure(name: String, list: List<Station>) {
        assertWithMessage("$name is empty").that(list).isNotEmpty()
        assertWithMessage("$name has duplicate ids").that(list.map { it.id }).containsNoDuplicates()
        list.forEach { s ->
            assertWithMessage("$name: blank id").that(s.id.isNotBlank()).isTrue()
            assertWithMessage("$name ${s.id}: blank name").that(s.name.isNotBlank()).isTrue()
            assertWithMessage("$name ${s.id}: name has surrounding whitespace").that(s.name).isEqualTo(s.name.trim())
            val uri = Uri.parse(s.url)
            assertWithMessage("$name ${s.id}: url ${s.url} is not http(s)").that(uri.scheme).isAnyOf("http", "https")
            assertWithMessage("$name ${s.id}: url ${s.url} has no host").that(uri.host.isNullOrBlank()).isFalse()
            assertWithMessage("$name ${s.id}: url has whitespace").that(s.url.any { it.isWhitespace() }).isFalse()
            assertWithMessage("$name ${s.id}: bundled radio must not be local").that(s.local).isFalse()
            if (s.favicon.isNotBlank()) {
                assertWithMessage("$name ${s.id}: favicon ${s.favicon}").that(Uri.parse(s.favicon).scheme).isAnyOf("http", "https")
            }
        }
    }

    @Test fun stations_json_is_well_formed() {
        val list = load("stations.json")
        checkStructure("stations.json", list)
        assertThat(list).hasSize(5)
    }

    @Test fun stations_am_json_is_well_formed() {
        val list = load("stations_am.json")
        checkStructure("stations_am.json", list)
        assertThat(list).hasSize(46)
        // ids are am:00 .. am:45 in order
        assertThat(list.map { it.id }).containsExactlyElementsIn((0 until 46).map { "am:%02d".format(it) }).inOrder()
        assertThat(list.all { it.subtitle == "Armenia" }).isTrue()
    }

    @Test fun every_curated_file_in_assets_parses() {
        val files = context.assets.list("")!!.filter { it.startsWith("stations_") && it.endsWith(".json") }
        assertThat(files).contains("stations_am.json")
        files.forEach { checkStructure(it, load(it)) }
    }

    @Test fun curated_and_default_ids_do_not_collide() {
        val ids = load("stations.json").map { it.id } + load("stations_am.json").map { it.id }
        assertThat(ids).containsNoDuplicates()
    }
}
