package dev.wheelradio

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Marks a test that starts with two radio favorites in radio.xml. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class SeededFavorites

/**
 * Compose UI on the 1920x1080 landscape head-unit screen. Only flows that need no network:
 * tabs, Favorites (seeded through radio.xml before launch), search filtering on Favorites with a
 * single character (no Radio Browser request), Music (local & USB), Bluetooth, Key log.
 *
 * There are no testTags in the app, so nodes are found by the texts / contentDescriptions mapped in
 * test-results/exploration-report.md (tab labels are rendered upper-case).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {
    private val favAlpha = Station("qa:fav:alpha", "QA Fav Alpha", FakeStreamServer.url("/ok/11.mp3"), subtitle = "QA favorites")
    private val favBeta = Station("qa:fav:beta", "QA Fav Beta", FakeStreamServer.url("/ok/12.mp3"), subtitle = "QA favorites")

    private val seed = object : org.junit.rules.TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                FakeStreamServer.outage = false
                val favs = if (description.getAnnotation(SeededFavorites::class.java) != null) listOf(favAlpha, favBeta) else emptyList()
                seedPrefs(defaultQueue(), 0, favs)
                base.evaluate()
            }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE))
        .around(seed)
        .around(compose)

    private fun tab(label: String) = compose.onNodeWithText(label)

    private fun favoriteIds() = Station.listFromJson(radioPrefs.getString("favorites", null)).map { it.id }

    @Test fun starts_on_by_countries_with_four_tabs_and_top_bar() {
        tab("RADIO").assertIsDisplayed().assertIsSelected()
        listOf("FAVORITES", "MUSIC", "BLUETOOTH").forEach { tab(it).assertIsDisplayed().assertIsNotSelected() }
        compose.onNodeWithText("Search stations, countries, files…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Key log").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add to favorites").assertIsDisplayed() // mini player heart
    }

    @Test fun tapping_each_tab_moves_the_selection() {
        val tabs = listOf("RADIO", "FAVORITES", "MUSIC", "BLUETOOTH")
        for (t in tabs.drop(1) + tabs.first()) {
            tab(t).performClick()
            tab(t).assertIsSelected()
            tabs.filter { it != t }.forEach { tab(it).assertIsNotSelected() }
        }
    }

    @Test fun favorites_empty_state() {
        tab("FAVORITES").performClick()
        compose.onNodeWithText("No favorites yet. Tap the heart next to a station to add it here.").assertIsDisplayed()
    }

    @SeededFavorites
    @Test fun seeded_favorites_render_in_insertion_order() {
        tab("FAVORITES").performClick()
        val alpha = compose.onNodeWithText("QA Fav Alpha").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val beta = compose.onNodeWithText("QA Fav Beta").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertThat(alpha.top).isLessThan(beta.top)
        assertThat(compose.onAllNodesWithText("QA favorites").fetchSemanticsNodes()).hasSize(2)
        assertThat(
            compose.onAllNodes(hasContentDescription("Remove from favorites")).fetchSemanticsNodes()
        ).hasSize(2)
        // Radio favorites only: no "Shuffle favorite songs" button.
        assertThat(compose.onAllNodes(hasText("Shuffle favorite songs", substring = true)).fetchSemanticsNodes()).isEmpty()
    }

    @SeededFavorites
    @Test fun un_heart_removes_favorite_and_persists_across_recreation() {
        tab("FAVORITES").performClick()
        compose.onNode(hasContentDescription("Remove from favorites") and hasAnyAncestor(hasText("QA Fav Alpha")))
            .performClick()

        compose.onNodeWithText("QA Fav Alpha").assertDoesNotExist()
        compose.onNodeWithText("QA Fav Beta").assertIsDisplayed()
        assertThat(favoriteIds()).containsExactly("qa:fav:beta")

        compose.activityRule.scenario.recreate()
        tab("RADIO").assertIsSelected() // a new AppModel starts on the first tab
        tab("FAVORITES").performClick()
        compose.onNodeWithText("QA Fav Beta").assertIsDisplayed()
        compose.onNodeWithText("QA Fav Alpha").assertDoesNotExist()
    }

    @SeededFavorites
    @Test fun removing_the_last_favorite_shows_the_empty_state_and_writes_empty_array() {
        tab("FAVORITES").performClick()
        repeat(2) {
            compose.onAllNodes(hasContentDescription("Remove from favorites"))[0].performClick()
        }
        compose.onNodeWithText("No favorites yet. Tap the heart next to a station to add it here.").assertIsDisplayed()
        assertThat(radioPrefs.getString("favorites", null)).isEqualTo("[]")
    }

    @SeededFavorites
    @Test fun one_letter_query_filters_favorites_locally_and_clear_restores() {
        tab("FAVORITES").performClick()
        // One character: filtered on-device; AppModel only calls Radio Browser for >= 2 characters.
        compose.onNode(hasSetTextAction()).performTextInput("l")
        compose.onNodeWithText("QA Fav Alpha").assertIsDisplayed()
        compose.onNodeWithText("QA Fav Beta").assertDoesNotExist()

        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("QA Fav Beta").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }

    @Test fun key_log_dialog_shows_entries_and_closes() {
        val marker = "ui-test marker ${System.nanoTime()}"
        KeyLog.add("QA", marker)
        compose.onNodeWithContentDescription("Key log").performClick()

        compose.onNodeWithText("Wheel button log").assertIsDisplayed()
        compose.onNodeWithText("Press the wheel buttons; each press shows up here.").assertIsDisplayed()
        compose.onNodeWithText(marker, substring = true).assertExists()

        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Wheel button log").assertDoesNotExist()
        tab("RADIO").assertIsSelected()
    }

    @Test fun key_log_dialog_closes_with_system_back() {
        compose.onNodeWithContentDescription("Key log").performClick()
        compose.onNodeWithText("Wheel button log").assertIsDisplayed()
        UiDevice.getInstance(instrumentation).pressBack()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Wheel button log")).fetchSemanticsNodes().isEmpty()
        }
        tab("RADIO").assertIsDisplayed() // the app stays open
    }

    @Test fun key_log_closes_with_a_tap_outside_but_not_on_the_panel() {
        compose.onNodeWithContentDescription("Key log").performClick()
        compose.onNodeWithText("Wheel button log").performClick() // tap inside the panel
        compose.onNodeWithText("Wheel button log").assertIsDisplayed()
        UiDevice.getInstance(instrumentation).click(40, 300) // scrim, left of the centred panel
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Wheel button log")).fetchSemanticsNodes().isEmpty()
        }
        tab("RADIO").assertIsDisplayed()
    }

    @Test fun local_tab_with_permission_lists_what_media_store_has() {
        tab("MUSIC").performClick()
        compose.onNodeWithText("Rescan device & USB").assertIsDisplayed()
        compose.onNodeWithText("Allow access to music").assertDoesNotExist()

        // Expected content comes from the same MediaStore query the app uses, so the test does not
        // depend on which files happen to be on the device.
        val expected = LocalMusic.scanMediaStore(appContext)
        compose.waitUntil(10_000) {
            val shuffle = compose.onAllNodes(hasText("Shuffle all (", substring = true)).fetchSemanticsNodes()
            val empty = compose.onAllNodes(hasText("No audio files found. Plug in a USB stick and tap Rescan.")).fetchSemanticsNodes()
            shuffle.isNotEmpty() || empty.isNotEmpty()
        }
        if (expected.isEmpty()) {
            compose.onNodeWithText("No audio files found. Plug in a USB stick and tap Rescan.").assertIsDisplayed()
        } else {
            compose.onNodeWithText("Shuffle all (${expected.size})").assertIsDisplayed()
            compose.onAllNodesWithText(expected.first().name)[0].assertIsDisplayed()
        }
    }

    @Test fun bluetooth_tab_shows_a_known_state() {
        tab("BLUETOOTH").performClick()
        compose.waitUntil(10_000) {
            listOf("One-time setup needed", "No Bluetooth music found.", "Wheel buttons control", "Press play here")
                .any { compose.onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** BYD rotating screen: the same favorites flow in portrait (1080x1920). */
    @SeededFavorites
    @Test fun portrait_tabs_and_favorites_are_usable() {
        val device = UiDevice.getInstance(instrumentation)
        try {
            device.setOrientationPortrait()
            compose.waitUntil(5_000) {
                compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
            compose.waitForIdle()
            listOf("RADIO", "FAVORITES", "MUSIC", "BLUETOOTH").forEach { tab(it).assertIsDisplayed() }
            tab("FAVORITES").performClick()
            compose.onNodeWithText("QA Fav Alpha").assertIsDisplayed()
            compose.onNode(hasContentDescription("Remove from favorites") and hasAnyAncestor(hasText("QA Fav Beta")))
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithText("QA Fav Beta").assertDoesNotExist()
            assertThat(favoriteIds()).containsExactly("qa:fav:alpha")
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }
}
