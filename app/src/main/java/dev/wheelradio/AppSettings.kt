package dev.wheelradio

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** UI languages. Labels are always written in their own language. */
enum class Language(val tag: String, val label: String) {
    EN("en", "English"), RU("ru", "Русский"), HY("hy", "Հայերեն");

    val locale: Locale get() = Locale(tag)
}

/** Whole-UI scale (layout, text and touch targets together). */
enum class UiSize(val scale: Float) { SMALL(0.85f), NORMAL(1f), LARGE(1.15f), XLARGE(1.3f) }

enum class ThemeMode { DARK, LIGHT, AUTO }

/**
 * User settings, stored next to the rest of the app's data in the `radio` SharedPreferences.
 * The UI holds one instance (observable Compose state); [RadioService] reads the same keys through the
 * companion helpers, so a change applies to the next station start without restarting anything.
 */
class AppSettings(context: Context) {
    private val prefs = prefs(context)

    var language by mutableStateOf(language(prefs))
        private set
    var uiSize by mutableStateOf(enumOr(prefs.getString(KEY_UI_SIZE, null), UiSize.NORMAL))
        private set
    var theme by mutableStateOf(enumOr(prefs.getString(KEY_THEME, null), ThemeMode.DARK))
        private set
    var autoplay by mutableStateOf(prefs.getBoolean(KEY_AUTOPLAY, true))
        private set
    var weakCoverage by mutableStateOf(prefs.getBoolean(KEY_WEAK_COVERAGE, false))
        private set
    var homeCountry by mutableStateOf(homeCountry(prefs))
        private set
    /** Show the full-screen player after [BIG_PLAYER_IDLE_MS] without a touch. */
    var bigPlayer by mutableStateOf(prefs.getBoolean(KEY_BIG_PLAYER, true))
        private set
    /** Look up Bluetooth songs' covers online (sends artist, title and album to iTunes / Deezer). */
    var coversOnline by mutableStateOf(prefs.getBoolean(KEY_COVERS_ONLINE, true))
        private set

    fun updateLanguage(v: Language) { language = v; prefs.edit().putString(KEY_LANGUAGE, v.tag).apply() }
    fun updateUiSize(v: UiSize) { uiSize = v; prefs.edit().putString(KEY_UI_SIZE, v.name).apply() }
    fun updateTheme(v: ThemeMode) { theme = v; prefs.edit().putString(KEY_THEME, v.name).apply() }
    fun updateAutoplay(v: Boolean) { autoplay = v; prefs.edit().putBoolean(KEY_AUTOPLAY, v).apply() }
    fun updateWeakCoverage(v: Boolean) { weakCoverage = v; prefs.edit().putBoolean(KEY_WEAK_COVERAGE, v).apply() }
    fun updateHomeCountry(code: String) { homeCountry = code; prefs.edit().putString(KEY_HOME_COUNTRY, code).apply() }
    fun updateBigPlayer(v: Boolean) { bigPlayer = v; prefs.edit().putBoolean(KEY_BIG_PLAYER, v).apply() }
    fun updateCoversOnline(v: Boolean) { coversOnline = v; prefs.edit().putBoolean(KEY_COVERS_ONLINE, v).apply() }

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_UI_SIZE = "ui_size"
        const val KEY_THEME = "theme"
        const val KEY_AUTOPLAY = "autoplay"
        const val KEY_WEAK_COVERAGE = "weak_coverage"
        const val KEY_HOME_COUNTRY = "home_country"
        const val KEY_BIG_PLAYER = "big_player"
        const val KEY_COVERS_ONLINE = "covers_online"

        /** Time without touching the screen before the big player appears. */
        const val BIG_PLAYER_IDLE_MS = 12_000L

        const val DEFAULT_HOME_COUNTRY = "AM"

        /** Time a station gets to start playing before it counts as failed (see StreamRecovery's watchdog). */
        const val FIRST_AUDIO_TIMEOUT_MS = 20_000L
        const val WEAK_COVERAGE_FIRST_AUDIO_TIMEOUT_MS = 40_000L

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences("radio", Context.MODE_PRIVATE)

        private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default

        /** The saved language, or the system's when that is Russian or Armenian, else English. */
        private fun language(prefs: SharedPreferences): Language {
            val tag = prefs.getString(KEY_LANGUAGE, null) ?: Locale.getDefault().language
            return Language.entries.firstOrNull { it.tag == tag } ?: Language.EN
        }

        private fun homeCountry(prefs: SharedPreferences) =
            prefs.getString(KEY_HOME_COUNTRY, null) ?: DEFAULT_HOME_COUNTRY

        fun language(context: Context): Language = language(prefs(context))

        fun autoplay(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOPLAY, true)

        fun firstAudioTimeoutMs(context: Context): Long =
            if (prefs(context).getBoolean(KEY_WEAK_COVERAGE, false)) WEAK_COVERAGE_FIRST_AUDIO_TIMEOUT_MS
            else FIRST_AUDIO_TIMEOUT_MS

        fun homeCountry(context: Context): String = homeCountry(prefs(context))

        fun coversOnline(context: Context): Boolean = prefs(context).getBoolean(KEY_COVERS_ONLINE, true)

        /** [base]'s resources in the app's chosen language, for text built outside the UI (e.g. RadioService). */
        fun localizedResources(base: Context, language: Language = language(base)): Resources {
            val config = Configuration(base.resources.configuration).apply { setLocale(language.locale) }
            return base.createConfigurationContext(config).resources
        }
    }
}
