package dev.wheelradio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The Settings page (gear in the top bar). Every change must apply at once and survive a restart. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(SeedPrefsRule()).around(compose)

    private fun openSettings() = compose.onNodeWithContentDescription("Settings").performClick()

    private fun waitGone(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
    }

    @Test fun gear_opens_settings_and_back_closes_it() {
        openSettings()
        compose.onNodeWithText("Language").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsSelected()
        compose.onNodeWithText("Русский").assertIsNotSelected()
        UiDevice.getInstance(instrumentation).pressBack()
        waitGone("Language")
        compose.onNodeWithText("RADIO").assertIsDisplayed() // the app stays open
    }

    @Test fun language_switches_the_whole_ui_at_once_and_is_remembered() {
        openSettings()
        compose.onNodeWithText("Русский").performClick()
        // The settings page itself changes straight away, with no restart.
        compose.onNodeWithText("Настройки").assertIsDisplayed()
        compose.onNodeWithText("Язык").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        listOf("РАДИО", "ИЗБРАННОЕ", "МУЗЫКА").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Следующая").assertIsDisplayed()
        assertThat(radioPrefs.getString(AppSettings.KEY_LANGUAGE, null)).isEqualTo("ru")

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("РАДИО").assertIsDisplayed()

        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Հայերեն").performClick()
        compose.onNodeWithText("Կարգավորումներ").assertIsDisplayed()
        compose.onNodeWithContentDescription("Հետ").performClick()
        compose.onNodeWithText("ՌԱԴԻՈ").assertIsDisplayed()
        compose.onNodeWithText("ԸՆՏՐՅԱԼՆԵՐ").assertIsDisplayed()
    }

    @Test fun screen_size_scales_layout_and_text_together() {
        val before = compose.onNodeWithText("RADIO").fetchSemanticsNode().boundsInRoot.height
        openSettings()
        compose.onNodeWithText("Extra large").performClick()
        compose.onNodeWithText("Extra large").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        val after = compose.onNodeWithText("RADIO").fetchSemanticsNode().boundsInRoot.height
        assertThat(after / before).isWithin(0.05f).of(UiSize.XLARGE.scale)
        assertThat(radioPrefs.getString(AppSettings.KEY_UI_SIZE, null)).isEqualTo("XLARGE")
    }

    @Test fun theme_choice_is_saved() {
        openSettings()
        compose.onNodeWithText("Dark").assertIsSelected()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithText("Light").assertIsSelected()
        compose.onNodeWithText("Dark").assertIsNotSelected()
        assertThat(radioPrefs.getString(AppSettings.KEY_THEME, null)).isEqualTo("LIGHT")
        compose.onNodeWithText("Auto").performClick()
        assertThat(radioPrefs.getString(AppSettings.KEY_THEME, null)).isEqualTo("AUTO")
    }

    @Test fun playback_switches_are_saved_and_read_by_the_service() {
        openSettings()
        val autoplay = compose.onNode(hasText("Auto-play on start"), useUnmergedTree = false)
        autoplay.assertIsOn().performClick()
        autoplay.assertIsOff()
        assertThat(AppSettings.autoplay(appContext)).isFalse()

        val weak = compose.onNode(hasText("Weak-coverage mode"))
        weak.assertIsOff().performClick()
        weak.assertIsOn()
        assertThat(AppSettings.firstAudioTimeoutMs(appContext)).isEqualTo(40_000L)
    }

    @Test fun home_country_picker_saves_the_choice() {
        openSettings()
        // Exact text: the button holds flag and name in one string (country tiles under the page split them).
        compose.onNodeWithText("🇦🇲  Armenia").performClick()
        compose.onNodeWithText("Choose your home country").assertIsDisplayed()
        compose.onNode(hasText("Russia", substring = true) and isSelectable()).performClick()
        waitGone("Choose your home country")
        compose.onNodeWithText("🇷🇺  Russia").assertIsDisplayed()
        assertThat(radioPrefs.getString(AppSettings.KEY_HOME_COUNTRY, null)).isEqualTo("RU")
    }

    @Test fun key_log_opens_from_settings_and_closing_it_returns_to_settings() {
        compose.openKeyLog()
        compose.onNodeWithText("Wheel button log").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        waitGone("Wheel button log")
        compose.onNodeWithText("Language").assertIsDisplayed()
    }
}
