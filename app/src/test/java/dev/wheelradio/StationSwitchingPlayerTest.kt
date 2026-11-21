package dev.wheelradio

import android.content.Context
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Index logic of the wrapper that turns every next/previous into "switch station".
 * The ExoPlayer is never prepared: only the playlist/timeline logic is exercised, no audio.
 * The debounce uses SystemClock.elapsedRealtime(), which Robolectric controls via ShadowSystemClock.
 */
@RunWith(AndroidJUnit4::class)
class StationSwitchingPlayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var exo: ExoPlayer
    private lateinit var player: StationSwitchingPlayer

    @Before fun setUp() {
        exo = TestExoPlayerBuilder(context).build()
        exo.repeatMode = Player.REPEAT_MODE_ALL // as RadioService configures it
        exo.setMediaItems(items(5), 0, 0L)
        player = StationSwitchingPlayer(exo)
        // Robolectric's clock starts near 0, which would make the very first skip look like a duplicate
        // of lastSkipAt = 0. A real device has a large elapsedRealtime().
        advance(10_000)
    }

    @After fun tearDown() {
        exo.release()
    }

    private fun items(n: Int) = (0 until n).map {
        MediaItem.Builder().setMediaId("s$it").setUri("http://127.0.0.1:9/s$it.mp3").build()
    }

    private fun advance(ms: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Skip, then wait out the debounce so the next skip counts. */
    private fun press(action: () -> Unit) {
        action()
        advance(500)
    }

    @Test fun next_moves_exactly_one_station() {
        press { player.seekToNext() }
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        press { player.seekToNext() }
        assertThat(player.currentMediaItemIndex).isEqualTo(2)
    }

    @Test fun previous_moves_exactly_one_station() {
        exo.seekToDefaultPosition(3)
        press { player.seekToPrevious() }
        assertThat(player.currentMediaItemIndex).isEqualTo(2)
    }

    @Test fun next_on_last_station_wraps_to_first() {
        exo.seekToDefaultPosition(4)
        press { player.seekToNext() }
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
    }

    @Test fun previous_on_first_station_wraps_to_last() {
        press { player.seekToPrevious() }
        assertThat(player.currentMediaItemIndex).isEqualTo(4)
    }

    @Test fun wraps_even_when_repeat_mode_is_off() {
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.seekToDefaultPosition(4)
        press { player.seekToNext() } // nextMediaItemIndex == INDEX_UNSET -> floorMod fallback
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
        press { player.seekToPrevious() }
        assertThat(player.currentMediaItemIndex).isEqualTo(4)
    }

    @Test fun media_item_variants_behave_like_next_and_previous() {
        press { player.seekToNextMediaItem() }
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        press { player.seekToPreviousMediaItem() }
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
    }

    @Test fun second_press_within_400ms_is_ignored() {
        player.seekToNext()
        advance(399)
        player.seekToNext()
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(KeyLog.entries.value.first()).contains("[Player] seekToNext ignored (duplicate within 400ms)")
    }

    @Test fun press_exactly_400ms_later_is_accepted() {
        player.seekToNext()
        advance(400)
        player.seekToNext()
        assertThat(player.currentMediaItemIndex).isEqualTo(2)
        assertThat(KeyLog.entries.value.first()).contains("[Player] seekToNext")
        assertThat(KeyLog.entries.value.first()).doesNotContain("ignored")
    }

    @Test fun debounce_spans_directions_and_routes() {
        // The same physical press can arrive as MediaSession NEXT and as a BYD broadcast.
        player.seekToNext()
        player.perform(WheelAction.NEXT)
        player.seekToPreviousMediaItem()
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
    }

    @Test fun ten_rapid_presses_switch_once_per_debounce_window() {
        // 10 presses 110 ms apart (like `input keyevent 87` x10 in step 2) -> 3 accepted (t=0, 440, 880).
        repeat(10) {
            player.seekToNext()
            advance(110)
        }
        assertThat(player.currentMediaItemIndex).isEqualTo(3)
    }

    @Test fun previous_never_restarts_the_current_station() {
        exo.seekTo(2, 10_000L) // well past Player's 3 s "restart current item" threshold
        press { player.seekToPrevious() }
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
    }

    @Test fun skip_resumes_playback_when_paused() {
        exo.playWhenReady = false
        press { player.seekToNext() }
        assertThat(exo.playWhenReady).isTrue()
    }

    @Test fun skip_on_empty_queue_is_a_no_op() {
        exo.clearMediaItems()
        press { player.seekToNext() }
        press { player.seekToPrevious() }
        assertThat(player.mediaItemCount).isEqualTo(0)
    }

    @Test fun single_station_queue_stays_on_that_station() {
        exo.setMediaItems(items(1), 0, 0L)
        press { player.seekToNext() }
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
        press { player.seekToPrevious() }
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
    }

    @Test fun next_and_previous_follow_the_shuffle_order() {
        val order = intArrayOf(3, 1, 4, 0, 2)
        exo.setShuffleOrder(DefaultShuffleOrder(order, 42L))
        exo.shuffleModeEnabled = true
        exo.seekToDefaultPosition(3)

        val visited = mutableListOf(player.currentMediaItemIndex)
        repeat(5) {
            press { player.seekToNext() }
            visited += player.currentMediaItemIndex
        }
        assertThat(visited).containsExactly(3, 1, 4, 0, 2, 3).inOrder()

        press { player.seekToPrevious() }
        assertThat(player.currentMediaItemIndex).isEqualTo(2)
    }

    @Test fun perform_next_and_previous_switch_station() {
        press { player.perform(WheelAction.NEXT) }
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        press { player.perform(WheelAction.PREVIOUS) }
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
    }

    @Test fun perform_play_pause_toggles() {
        // Unprepared ExoPlayer never reports isPlaying; fake it from playWhenReady.
        val fake = object : ForwardingPlayer(exo) {
            override fun isPlaying() = exo.playWhenReady
        }
        val p = StationSwitchingPlayer(fake)
        exo.playWhenReady = false

        p.perform(WheelAction.PLAY_PAUSE)
        assertThat(exo.playWhenReady).isTrue()
        p.perform(WheelAction.PLAY_PAUSE)
        assertThat(exo.playWhenReady).isFalse()
        assertThat(p.currentMediaItemIndex).isEqualTo(0) // play/pause never switches station
    }
}
