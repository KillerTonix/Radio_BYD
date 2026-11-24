package dev.wheelradio

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tab memory and the idle rule for the big player. No network: nothing here loads the directory. */
@RunWith(AndroidJUnit4::class)
class AppModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
    private fun model() = AppModel(context, CoroutineScope(Dispatchers.Unconfined))
    private val station = Station("qa:1", "QA Station", "http://127.0.0.1/qa.mp3")

    @Before fun fresh() { prefs.edit().clear().commit() }

    @Test fun first_start_opens_radio_then_the_last_tab_is_remembered() {
        assertThat(model().tab).isEqualTo(AppTab.COUNTRIES)
        model().tab = AppTab.LOCAL
        assertThat(model().tab).isEqualTo(AppTab.LOCAL)
        model().tab = AppTab.FAVORITES
        assertThat(model().tab).isEqualTo(AppTab.FAVORITES)
    }

    @Test fun unknown_saved_tab_falls_back_to_radio() {
        prefs.edit().putString("last_tab", "NO_SUCH_TAB").commit()
        assertThat(model().tab).isEqualTo(AppTab.COUNTRIES)
    }

    @Test fun big_player_after_idle_only_with_something_playing() {
        val m = model()
        val idle = SystemClock.uptimeMillis() + AppSettings.BIG_PLAYER_IDLE_MS + 1
        assertThat(m.bigPlayerDue(idle)).isFalse() // nothing loaded
        m.now = NowPlaying(station = station, title = station.name, isPlaying = true)
        assertThat(m.bigPlayerDue(SystemClock.uptimeMillis())).isFalse() // just touched (created)
        assertThat(m.bigPlayerDue(idle)).isTrue()
    }

    @Test fun big_player_waits_for_settings_key_log_search_and_the_switch() {
        val m = model()
        m.now = NowPlaying(station = station, title = station.name, isPlaying = true)
        val idle = SystemClock.uptimeMillis() + AppSettings.BIG_PLAYER_IDLE_MS + 1
        m.showSettings = true; assertThat(m.bigPlayerDue(idle)).isFalse(); m.showSettings = false
        m.showKeyLog = true; assertThat(m.bigPlayerDue(idle)).isFalse(); m.showKeyLog = false
        m.searchFocused = true; assertThat(m.bigPlayerDue(idle)).isFalse(); m.searchFocused = false
        m.settings.updateBigPlayer(false); assertThat(m.bigPlayerDue(idle)).isFalse(); m.settings.updateBigPlayer(true)
        assertThat(m.bigPlayerDue(idle)).isTrue()
        m.bigPlayer = true
        assertThat(m.bigPlayerDue(idle)).isFalse() // already showing
    }

    @Test fun a_touch_restarts_the_idle_time() {
        val m = model()
        m.now = NowPlaying(station = station, title = station.name, isPlaying = true)
        val later = SystemClock.uptimeMillis() + AppSettings.BIG_PLAYER_IDLE_MS + 1
        assertThat(m.bigPlayerDue(later)).isTrue()
        m.onUserTouch()
        assertThat(m.bigPlayerDue(m.lastTouchAt + AppSettings.BIG_PLAYER_IDLE_MS - 1)).isFalse()
        assertThat(m.bigPlayerDue(m.lastTouchAt + AppSettings.BIG_PLAYER_IDLE_MS)).isTrue()
    }
}
