package dev.wheelradio

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Android 10 SystemUI recycles session artwork whose longer side is exactly max(screen) / 6 and then
 * crash-loops ("System UI keeps stopping"). Seen on the 1920x1080 emulator with Radio Marshall's 320x87 logo.
 */
@RunWith(AndroidJUnit4::class)
class SystemUiSafeBitmapLoaderTest {
    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test fun artwork_of_exactly_the_system_ui_size_gets_a_one_pixel_larger_copy() {
        val logo = bitmap(320, 87)
        val safe = SystemUiSafeBitmapLoader.safeForSystemUi(logo, 320)
        assertThat(safe).isNotSameInstanceAs(logo)
        assertThat(safe.width).isEqualTo(321)
        assertThat(safe.height).isEqualTo(87)
    }

    @Test fun tall_artwork_is_checked_on_its_longer_side() {
        val safe = SystemUiSafeBitmapLoader.safeForSystemUi(bitmap(100, 320), 320)
        assertThat(safe.height).isEqualTo(321)
    }

    @Test fun other_sizes_pass_through_untouched() {
        listOf(bitmap(319, 87), bitmap(321, 87), bitmap(512, 512), bitmap(87, 300)).forEach {
            assertThat(SystemUiSafeBitmapLoader.safeForSystemUi(it, 320)).isSameInstanceAs(it)
        }
    }

    @Test fun unknown_screen_size_passes_through() {
        val logo = bitmap(320, 87)
        assertThat(SystemUiSafeBitmapLoader.safeForSystemUi(logo, 0)).isSameInstanceAs(logo)
    }

    @Config(qualifiers = "w1920dp-h1080dp-land-mdpi")
    @Test fun head_unit_screen_gives_320() {
        assertThat(SystemUiSafeBitmapLoader.systemUiArtworkSize(ApplicationProvider.getApplicationContext())).isEqualTo(320)
    }
}
