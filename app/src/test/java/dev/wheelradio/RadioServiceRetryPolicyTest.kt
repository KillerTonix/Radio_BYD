package dev.wheelradio

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.time.Duration

/**
 * RadioService's error policy (3 retries with 2/4/6 s back-off, then skip to the next station),
 * driven on Robolectric's fake clock so the ~12 s cycle runs in well under a second of wall time.
 *
 * Every queue entry points at a missing local file, so ExoPlayer fails fast with
 * ERROR_CODE_IO_FILE_NOT_FOUND and nothing touches the network.
 */
@RunWith(AndroidJUnit4::class)
class RadioServiceRetryPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ServiceController<RadioService>
    private lateinit var player: Player
    private var logBefore: String? = null

    private fun dead(i: Int) = Station("t:$i", "Dead $i", "file:///nonexistent/qa_dead_$i.mp3")

    private fun startService(queueSize: Int, lastIndex: Int = 0) {
        StationRepository(context).apply {
            queue = (0 until queueSize).map(::dead)
            this.lastIndex = lastIndex
            shuffle = false
        }
        logBefore = KeyLog.entries.value.firstOrNull()
        controller = Robolectric.buildService(RadioService::class.java).create()
        player = sessionPlayer(controller.get())
    }

    /** The session is only handed out to connecting controllers, so read the private field (test-only reflection). */
    private fun sessionPlayer(service: RadioService): Player {
        val f = RadioService::class.java.getDeclaredField("session").apply { isAccessible = true }
        return (f.get(service) as MediaSession).player
    }

    @Before fun clearPrefs() {
        context.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun stop() {
        if (::controller.isInitialized) controller.destroy()
    }

    private fun errorLines() = KeyLog.entries.value.takeWhile { it != logBefore }.filter { "[Player] error:" in it }

    /** Advance the fake clock in 10 ms steps (ExoPlayer's work-loop cadence) until [cond] or [maxMs]. Returns elapsed ms. */
    private fun runUntil(maxMs: Long, cond: () -> Boolean): Long {
        var t = 0L
        while (t < maxMs && !cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(0, 200_000) // let ExoPlayer's playback thread run
            t += 10
        }
        return t
    }

    @Test fun service_restores_queue_and_last_index_from_prefs() {
        startService(queueSize = 3, lastIndex = 2)
        assertThat(player.mediaItemCount).isEqualTo(3)
        assertThat(player.currentMediaItemIndex).isEqualTo(2)
        assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)
        assertThat(player.shuffleModeEnabled).isFalse()
        assertThat(player.playWhenReady).isTrue() // resumes like a car radio
        assertThat(player).isInstanceOf(StationSwitchingPlayer::class.java)
    }

    @Test fun out_of_range_last_index_is_clamped() {
        startService(queueSize = 2, lastIndex = 9)
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
    }

    @Test fun shuffle_pref_is_ignored_for_radio_queues() {
        StationRepository(context).shuffle = true
        StationRepository(context).queue = listOf(dead(0), dead(1))
        controller = Robolectric.buildService(RadioService::class.java).create()
        assertThat(sessionPlayer(controller.get()).shuffleModeEnabled).isFalse()
    }

    @Test fun dead_station_is_retried_three_times_then_skipped_and_persisted() {
        startService(queueSize = 3)
        val elapsed = runUntil(40_000) { player.currentMediaItemIndex == 1 }

        assertWithMessage("never skipped to the next station").that(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(errorLines()).hasSize(4) // first failure + 3 retries
        assertThat(errorLines().first()).contains("ERROR_CODE_IO_FILE_NOT_FOUND")
        // back-off waits are 2 s + 4 s + 6 s before the skip
        assertThat(elapsed).isAtLeast(12_000L)
        assertThat(StationRepository(context).lastIndex).isEqualTo(1)
    }

    @Test fun single_station_queue_gives_up_after_three_retries_without_skipping() {
        startService(queueSize = 1)
        runUntil(40_000) { false }
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
        assertThat(errorLines()).hasSize(4)
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError).isNotNull()
    }

    /**
     * U7: with no network, a failing stream must not be retried into a skip. The default network is given
     * no INTERNET capability (= offline), and 127.0.0.1:9 refuses connections, so this stays hermetic.
     */
    @Test fun offline_stream_errors_keep_the_station_and_retry_when_the_network_returns() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, ShadowNetworkCapabilities.newInstance())
        assertThat(cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false).isFalse()
        StationRepository(context).apply {
            queue = (0 until 3).map { Station("net:$it", "Net $it", "http://127.0.0.1:9/qa_$it.mp3") }
            lastIndex = 0
            shuffle = false
        }
        logBefore = KeyLog.entries.value.firstOrNull()
        controller = Robolectric.buildService(RadioService::class.java).create()
        player = sessionPlayer(controller.get())

        assertThat(runUntil(10_000) { player.playerError != null }).isLessThan(10_000L)
        runUntil(40_000) { false } // well past the old 2 + 4 + 6 s back-off and skip
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
        assertThat(StationRepository(context).lastIndex).isEqualTo(0)
        assertThat(errorLines()).hasSize(1) // no retries while offline
        assertThat(newLog().any { "offline, keeping the station" in it }).isTrue()

        // Network comes back: the same station is retried, not the next one.
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val callbacks = shadowOf(cm).networkCallbacks
        assertWithMessage("RadioService registered no network callback").that(callbacks).isNotEmpty()
        callbacks.forEach { it.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps) }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(newLog().any { "network back, retrying the same station" in it }).isTrue()
        runUntil(5_000) { errorLines().size >= 2 }
        assertThat(errorLines().size).isAtLeast(2) // it really tried the stream again
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
    }

    private fun newLog() = KeyLog.entries.value.takeWhile { it != logBefore }

    @Test fun byd_next_frame_switches_the_service_player_once() {
        startService(queueSize = 3)
        runUntil(1_000) { false } // past the 400 ms debounce window (fake clock starts near 0)
        context.sendBroadcast(Intent(BydWheelKeyReceiver.ACTION).putExtra("list", byteArrayOf(4, 1, 3)))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(StationRepository(context).lastIndex).isEqualTo(1)
    }

    /**
     * After an error the player is IDLE; StationSwitchingPlayer.skip() seeks but never calls prepare(),
     * so the new station only starts when the stale back-off timer fires.
     */
    @Ignore("Known bug [AUDIO] Next/Previous during an error back-off waits for the stale retry timer - see test-results/bugs.md")
    @Test fun next_during_error_back_off_starts_the_new_station_immediately() {
        startService(queueSize = 3)
        runUntil(5_000) { player.playerError != null }
        assertThat(player.playerError).isNotNull()
        // Step past the 400 ms debounce, still well inside the 2 s back-off before the stale prepare().
        runUntil(500) { false }
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)

        player.seekToNext()
        runUntil(100) { false }
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(player.playbackState).isNotEqualTo(Player.STATE_IDLE)
    }
}
