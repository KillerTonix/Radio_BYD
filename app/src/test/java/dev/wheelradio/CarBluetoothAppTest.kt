package dev.wheelradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Test
import org.junit.runner.RunWith

/** Which head-unit app "Open the car's Bluetooth music" opens. On the Yuan Plus it used to pick BT Phone. */
@RunWith(AndroidJUnit4::class) // BluetoothBridge holds a main-looper Handler
class CarBluetoothAppTest {
    private fun score(label: String, pkg: String, cls: String? = null) =
        BluetoothBridge.carAppScore(BluetoothBridge.CarApp(label, pkg, cls))

    @Test fun bluetooth_music_beats_everything() {
        assertThat(score("Bluetooth Music", "com.byd.multimedia", "com.byd.multimedia.BtMusicActivity")).isEqualTo(0)
        assertThat(score("蓝牙音乐", "com.byd.btmusic")).isEqualTo(0)
    }

    /** Yuan Plus car test: Media Center is the app that plays the phone; Kuwo and KuGou are online music. */
    @Test fun byd_media_center_comes_before_online_music_apps() {
        val mediaCenter = score("Media Center", "com.byd.mediacenter")
        assertThat(mediaCenter).isEqualTo(0)
        assertThat(score("Kuwo", "cn.kuwo.kwmusiccar")).isGreaterThan(mediaCenter)
        assertThat(score("KuGou Music", "com.kugou.android.auto")).isGreaterThan(mediaCenter)
    }

    @Test fun phone_and_call_apps_are_never_offered() {
        assertThat(score("BT Phone", "com.byd.bluetoothcall")).isEqualTo(9)
        assertThat(score("Phone", "com.android.dialer")).isEqualTo(9)
    }

    @Test fun plain_music_apps_come_after_bluetooth_ones() {
        assertThat(score("Music", "com.byd.multimedia")).isEqualTo(2)
        assertThat(score("Bluetooth", "com.android.settings.bluetooth")).isEqualTo(1)
        assertThat(score("Image", "com.byd.gallery")).isEqualTo(9)
    }

    /** Car test 0.5.3: Media Center sometimes needed more than the old fixed 2 s to switch to Bluetooth. */
    @Test fun media_center_stays_3_to_5_seconds_then_wheel_radio_comes_back() {
        val bt = BluetoothBridge
        assertThat(bt.timeToReturn(2_000, playing = true)).isFalse() // never before 3 s
        assertThat(bt.timeToReturn(2_999, playing = true)).isFalse()
        assertThat(bt.timeToReturn(3_000, playing = true)).isTrue() // plays: come back at once
        assertThat(bt.timeToReturn(3_000, playing = false)).isFalse() // silent: give it until 5 s
        assertThat(bt.timeToReturn(4_999, playing = false)).isFalse()
        assertThat(bt.timeToReturn(5_000, playing = false)).isTrue()
        // Android 10 only lets the app come back within 10 s of its own activity starting.
        assertThat(BluetoothBridge.CAR_APP_MAX_WAIT_MS).isLessThan(10_000L)
    }

    /** The phone's AVRCP session in the car reported actions=0x244037: no seek, so the timeline is display-only. */
    @Test fun phone_over_avrcp_cannot_seek() {
        assertThat(BluetoothBridge.supportsSeek(0x244037)).isFalse()
        assertThat(BluetoothBridge.supportsSeek(0x244037L or PlaybackStateCompat.ACTION_SEEK_TO)).isTrue()
    }
}
