package dev.wheelradio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The mini player tells the driver why the radio is silent, instead of just looking paused.
 * Regression for "[AUDIO] Failed station shows no error state" (test-results/bugs.md). Local fake streams only.
 */
@OptIn(ExperimentalTestApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class PlaybackStatusUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(SeedPrefsRule()).around(compose)

    private val svc by lazy { ServiceController() }

    @After fun stop() {
        svc.onMain { it.pause() }
        svc.close()
    }

    private fun waitForText(text: String, timeoutMs: Long) = compose.waitUntil(timeoutMs) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun failing_station_shows_retrying_then_unavailable_and_play_tries_again() {
        val dead = FakeStreamServer.dead404(7)
        svc.playQueue(listOf(dead), 0)

        waitForText("QA Dead 404 7", 10_000)
        waitForText("retrying (1/3)", 10_000)
        // 404s fail fast: about 2 + 4 + 6 s of back-off before giving up.
        waitForText(StreamRecovery.STATUS_UNAVAILABLE, 45_000)
        assertThat(svc.isPlaying).isFalse()

        // Play on the failed station starts a fresh attempt (it used to do nothing visible).
        val hitsBefore = FakeStreamServer.hits("/dead/404")
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Play").performClick()
        waitForText("retrying (1/3)", 10_000)
        assertThat(FakeStreamServer.hits("/dead/404")).isGreaterThan(hitsBefore)
    }

    @Test fun status_clears_when_a_working_station_plays() {
        svc.playQueue(listOf(FakeStreamServer.dead404(8), FakeStreamServer.ok(8)), 0)
        waitForText("retrying (1/3)", 10_000)

        svc.onMain { it.seekToNext() } // the driver gives up on it
        assertThat(svc.waitPlaying("qa:ok:8")).isTrue()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("retrying", substring = true)).fetchSemanticsNodes().isEmpty()
        }
    }
}
