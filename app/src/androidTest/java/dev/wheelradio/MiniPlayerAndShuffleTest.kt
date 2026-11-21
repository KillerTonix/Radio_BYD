package dev.wheelradio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Mini player and local-song shuffle. "Local songs" are copies of the test fixture in the app's cache
 * dir with `local:` ids (the id prefix is what makes a Station local), so no MediaStore content or
 * storage permission is involved and the result does not depend on what is on the device.
 */
@OptIn(ExperimentalTestApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class MiniPlayerAndShuffleTest {
    private val songs = (1..3).map { n ->
        val f = File(appContext.cacheDir, "qa_local_$n.mp3")
        val uri = "file://${f.absolutePath}"
        Station("local:$uri", "QA Local Song $n", uri, subtitle = "Device · QA Bot", local = true)
    }
    private val radioFav = Station("qa:fav:radio", "QA Fav Radio", FakeStreamServer.url("/ok/21.mp3"), subtitle = "QA favorites")

    private val seed = object : ExternalResource() {
        override fun before() {
            FakeStreamServer.outage = false
            val tone = instrumentation.context.assets.open("qa_tone_440_90s.mp3").use { it.readBytes() }
            songs.forEach { File(it.url.removePrefix("file://")).writeBytes(tone) }
            seedPrefs(defaultQueue(), 0, favorites = listOf(songs[0], songs[1], radioFav))
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private val svc by lazy { ServiceController() }

    @After fun stop() {
        svc.onMain { it.shuffleModeEnabled = false; it.pause() }
        svc.close()
    }

    private fun waitForDesc(desc: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun mini_player_shows_the_playing_station_and_heart_adds_it_to_favorites() {
        svc.playQueue(defaultQueue(), 0)
        assertThat(svc.waitPlaying("qa:ok:1")).isTrue()
        waitForText("QA Stream 1")
        waitForDesc("Pause")

        compose.onNodeWithContentDescription("Favorite").performClick()
        assertThat(Station.listFromJson(radioPrefs.getString("favorites", null)).map { it.id }).contains("qa:ok:1")
        compose.onNodeWithText("FAVORITES").performClick()
        // once in the list, once in the mini player
        assertThat(compose.onAllNodesWithText("QA Stream 1").fetchSemanticsNodes()).hasSize(2)

        compose.onNodeWithContentDescription("Favorite").performClick() // toggles back
        assertThat(compose.onAllNodesWithText("QA Stream 1").fetchSemanticsNodes()).hasSize(1)
    }

    @Ignore("Known bug U8 - mini-player favorite button contentDescription does not reflect state - see test-results/bugs.md")
    @Test fun mini_player_heart_exposes_its_state() {
        svc.playQueue(defaultQueue(), 0)
        assertThat(svc.waitPlaying("qa:ok:1")).isTrue()
        waitForText("QA Stream 1")
        compose.onNodeWithContentDescription("Favorite").performClick()
        // On By countries there are no list hearts, so this can only be the mini player's.
        compose.onNodeWithContentDescription("Remove from favorites").assertExists()
    }

    @Test fun radio_station_has_no_shuffle_toggle() {
        svc.playQueue(defaultQueue(), 0)
        assertThat(svc.waitPlaying("qa:ok:1")).isTrue()
        waitForText("QA Stream 1")
        compose.onNodeWithContentDescription("Shuffle off").assertDoesNotExist()
        compose.onNodeWithContentDescription("Shuffle on").assertDoesNotExist()
    }

    @Test fun local_song_shows_shuffle_toggle_and_the_setting_persists() {
        svc.playQueue(songs, 0)
        assertThat(svc.waitPlaying(songs[0].id)).isTrue()
        waitForText("QA Local Song 1")
        waitForDesc("Shuffle off")

        compose.onNodeWithContentDescription("Shuffle off").performClick()
        waitForDesc("Shuffle on")
        assertThat(svc.onMain { it.shuffleModeEnabled }).isTrue()
        assertThat(pollUntil(3_000) { radioPrefs.getBoolean("shuffle", false) }).isTrue()

        compose.onNodeWithContentDescription("Shuffle on").performClick()
        waitForDesc("Shuffle off")
        assertThat(pollUntil(3_000) { !radioPrefs.getBoolean("shuffle", true) }).isTrue()
    }

    @Test fun shuffled_next_visits_every_song_once_per_cycle() {
        svc.playQueue(songs, 0)
        assertThat(svc.waitPlaying(songs[0].id)).isTrue()
        svc.onMain { it.shuffleModeEnabled = true }
        val start = svc.index
        val visited = mutableListOf(start)
        repeat(3) {
            waitOutDebounce()
            svc.onMain { it.seekToNext() }
            assertThat(pollUntil(5_000) { svc.index != visited.last() }).isTrue()
            visited += svc.index
        }
        // period 3: three different songs, then back to the first one
        assertThat(visited.take(3).toSet()).containsExactly(0, 1, 2)
        assertThat(visited.last()).isEqualTo(start)
    }

    @Test fun tapping_a_radio_station_turns_shuffle_off() {
        svc.playQueue(songs, 0)
        assertThat(svc.waitPlaying(songs[0].id)).isTrue()
        svc.onMain { it.shuffleModeEnabled = true }
        waitForDesc("Shuffle on")

        compose.onNodeWithText("FAVORITES").performClick()
        compose.onNodeWithText("QA Fav Radio").performClick()
        assertThat(svc.waitPlaying(radioFav.id)).isTrue()
        assertThat(svc.onMain { it.shuffleModeEnabled }).isFalse()
        assertThat(pollUntil(3_000) { !radioPrefs.getBoolean("shuffle", true) }).isTrue()
        compose.onNodeWithContentDescription("Shuffle on").assertDoesNotExist()
        compose.onNodeWithContentDescription("Shuffle off").assertDoesNotExist()
    }

    @Test fun shuffle_favorite_songs_plays_only_local_favorites_with_shuffle_on() {
        compose.onNodeWithText("FAVORITES").performClick()
        compose.onNodeWithText("Shuffle favorite songs (2)").assertIsDisplayed().performClick()

        assertThat(pollUntil(10_000) { svc.isPlaying && svc.mediaId in setOf(songs[0].id, songs[1].id) }).isTrue()
        assertThat(svc.onMain { it.mediaItemCount }).isEqualTo(2)
        assertThat(pollUntil(3_000) { svc.onMain { it.shuffleModeEnabled } }).isTrue()
        waitForDesc("Shuffle on")
    }

    @Test fun shuffle_favorites_button_disappears_below_two_local_songs() {
        compose.onNodeWithText("FAVORITES").performClick()
        compose.onNodeWithText("Shuffle favorite songs (2)").assertIsDisplayed()
        compose.onNode(hasContentDescription("Remove from favorites") and hasAnyAncestor(hasText("QA Local Song 1")))
            .performClick()
        compose.onNodeWithText("Shuffle favorite songs", substring = true).assertDoesNotExist()
        // the list re-flows (button gone): the remaining rows are still there
        compose.onNodeWithText("QA Local Song 2").assertIsDisplayed()
        compose.onNodeWithText("QA Fav Radio").assertIsDisplayed()
    }
}
