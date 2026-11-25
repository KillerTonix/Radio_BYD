package dev.wheelradio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AppSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
    private val systemLocale = Locale.getDefault()

    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun restoreLocale() { Locale.setDefault(systemLocale) }

    @Test fun defaults_match_the_app_before_settings_existed() {
        val s = AppSettings(context)
        assertThat(s.language).isEqualTo(Language.EN) // Robolectric's system locale is en-US
        assertThat(s.uiSize).isEqualTo(UiSize.NORMAL)
        assertThat(s.theme).isEqualTo(ThemeMode.DARK)
        assertThat(s.autoplay).isTrue()
        assertThat(s.weakCoverage).isFalse()
        assertThat(s.homeCountry).isEqualTo("AM")
        assertThat(s.bigPlayer).isTrue()
        assertThat(s.coversOnline).isTrue()
        assertThat(AppSettings.firstAudioTimeoutMs(context)).isEqualTo(20_000L)
    }

    @Test fun every_setting_survives_a_new_instance() {
        AppSettings(context).apply {
            updateLanguage(Language.HY)
            updateUiSize(UiSize.XLARGE)
            updateTheme(ThemeMode.AUTO)
            updateAutoplay(false)
            updateWeakCoverage(true)
            updateHomeCountry("RU")
            updateBigPlayer(false)
            updateCoversOnline(false)
        }
        val s = AppSettings(context)
        assertThat(s.language).isEqualTo(Language.HY)
        assertThat(s.uiSize).isEqualTo(UiSize.XLARGE)
        assertThat(s.theme).isEqualTo(ThemeMode.AUTO)
        assertThat(s.autoplay).isFalse()
        assertThat(s.weakCoverage).isTrue()
        assertThat(s.homeCountry).isEqualTo("RU")
        assertThat(s.bigPlayer).isFalse()
        assertThat(s.coversOnline).isFalse()
        assertThat(AppSettings.coversOnline(context)).isFalse()
        // What RadioService reads
        assertThat(AppSettings.autoplay(context)).isFalse()
        assertThat(AppSettings.firstAudioTimeoutMs(context)).isEqualTo(40_000L)
        assertThat(AppSettings.language(context)).isEqualTo(Language.HY)
    }

    @Test fun first_start_follows_a_russian_or_armenian_system_language() {
        Locale.setDefault(Locale("ru", "RU"))
        assertThat(AppSettings(context).language).isEqualTo(Language.RU)
        Locale.setDefault(Locale("hy", "AM"))
        assertThat(AppSettings(context).language).isEqualTo(Language.HY)
        Locale.setDefault(Locale.GERMANY)
        assertThat(AppSettings(context).language).isEqualTo(Language.EN)
    }

    @Test fun unknown_stored_values_fall_back_to_defaults() {
        prefs.edit().putString("language", "xx").putString("ui_size", "HUGE").putString("theme", "PINK").commit()
        val s = AppSettings(context)
        assertThat(s.language).isEqualTo(Language.EN)
        assertThat(s.uiSize).isEqualTo(UiSize.NORMAL)
        assertThat(s.theme).isEqualTo(ThemeMode.DARK)
    }

    @Test fun status_lines_come_in_the_chosen_language() {
        val ru = AppSettings.localizedResources(context, Language.RU)
        val hy = AppSettings.localizedResources(context, Language.HY)
        val en = AppSettings.localizedResources(context, Language.EN)
        assertThat(en.getString(R.string.status_no_network)).startsWith("No internet")
        assertThat(ru.getString(R.string.status_no_network)).startsWith("Нет интернета")
        assertThat(hy.getString(R.string.status_no_network)).startsWith("Ինտերնետ չկա")
        assertThat(ru.getString(R.string.status_retrying, 1, 3)).contains("(1/3)")
    }

    /** A string added in English only would silently show in English inside the Russian / Armenian UI. */
    @Test fun every_app_string_has_a_russian_and_an_armenian_translation() {
        val ours = stringNames("values")
        // Proper names that are the same in every language.
        val sameEverywhere = setOf("app_name", "tab_bluetooth")
        for (dir in listOf("values-ru", "values-hy")) {
            assertThat(ours - stringNames(dir) - sameEverywhere).isEmpty()
        }
        val en = AppSettings.localizedResources(context, Language.EN)
        for (lang in listOf(Language.RU, Language.HY)) {
            val res = AppSettings.localizedResources(context, lang)
            val same = (ours - sameEverywhere).filter { name ->
                val id = R.string::class.java.getField(name).getInt(null)
                res.getString(id) == en.getString(id)
            }
            assertThat(same).isEmpty()
        }
    }

    private fun stringNames(dir: String): Set<String> {
        val xml = java.io.File("src/main/res/$dir/strings.xml").readText()
        return Regex("<string name=\"([a-z_]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()
    }

    @Test fun home_country_goes_first_and_the_other_pinned_ones_keep_their_order() {
        val all = listOf("FR", "US", "AM", "IT", "RU", "DE", "GE", "JP").mapIndexed { i, c -> Country(c, c, 100 - i) }
        assertThat(RadioBrowser.sortCountries(all, "AM").map { it.code })
            .containsExactly("AM", "RU", "DE", "US", "GE", "FR", "IT", "JP").inOrder()
        assertThat(RadioBrowser.sortCountries(all, "RU").map { it.code })
            .containsExactly("RU", "AM", "DE", "US", "GE", "FR", "IT", "JP").inOrder()
        assertThat(RadioBrowser.sortCountries(all, "JP").map { it.code })
            .containsExactly("JP", "AM", "RU", "DE", "US", "GE", "FR", "IT").inOrder()
    }

    @Test fun country_names_follow_the_ui_language() {
        val am = Country("AM", "Armenia", 1)
        assertThat(am.displayName(Locale.ENGLISH)).isEqualTo("Armenia")
        assertThat(am.displayName(Locale("ru"))).isEqualTo("Армения")
        assertThat(am.displayName(Locale("hy"))).isEqualTo("Հայաստան")
    }
}
