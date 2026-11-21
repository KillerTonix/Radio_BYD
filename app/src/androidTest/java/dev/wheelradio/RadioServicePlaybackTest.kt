package dev.wheelradio

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real ExoPlayer inside RadioService, driven through a MediaController (like MainActivity),
 * playing only from the local FakeStreamServer. No Shoutcast/Icecast or Radio Browser traffic.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class RadioServicePlaybackTest {
    private lateinit var svc: ServiceController
    private val ok1 = FakeStreamServer.ok(1)
    private val ok2 = FakeStreamServer.ok(2)
    private val ok3 = FakeStreamServer.ok(3)

    @Before fun connect() {
        FakeStreamServer.outage = false
        seedPrefs(defaultQueue()) // in case this creates the service: resume a fake stream, not live radio
        svc = ServiceController()
    }

    @After fun disconnect() {
        FakeStreamServer.outage = false
        svc.onMain { it.pause() }
        svc.close()
    }

    @Test fun local_stream_reaches_ready_and_plays() {
        svc.playQueue(listOf(ok1, ok2, ok3), 0)
        assertWithMessage("fake stream never reached READY + playing").that(svc.waitPlaying(ok1.id)).isTrue()
        assertThat(FakeStreamServer.hits("/ok/1.mp3")).isAtLeast(1)

        val p0 = svc.onMain { it.currentPosition }
        Thread.sleep(1_500)
        assertThat(svc.onMain { it.currentPosition }).isGreaterThan(p0)
        assertThat(svc.state).isEqualTo(Player.STATE_READY)
        assertThat(svc.onMain { it.playerError }).isNull()
    }

    @Test fun session_exposes_station_metadata() {
        svc.playQueue(listOf(ok1, ok2, ok3), 1)
        assertThat(svc.waitPlaying(ok2.id)).isTrue()
        val md = svc.onMain { it.currentMediaItem!!.mediaMetadata }
        assertThat(md.title.toString()).isEqualTo("QA Stream 2")
        assertThat(md.station.toString()).isEqualTo("QA Stream 2")
        assertThat(md.artist.toString()).isEqualTo("QA fixture 2")
        // The UI rebuilds the Station from the item; the URI must have survived the controller hop.
        assertThat(svc.onMain { Station.fromMediaItem(it.currentMediaItem!!) }).isEqualTo(ok2)
    }

    @Test fun seek_to_next_switches_exactly_one_station_and_persists_last_index() {
        svc.playQueue(listOf(ok1, ok2, ok3), 0)
        assertThat(svc.waitPlaying(ok1.id)).isTrue()
        waitOutDebounce()

        svc.onMain { it.seekToNext() }
        assertThat(svc.waitPlaying(ok2.id)).isTrue()
        Thread.sleep(1_000)
        assertThat(svc.index).isEqualTo(1)
        assertThat(radioPrefs.getInt("last_index", -1)).isEqualTo(1)
    }

    @Test fun previous_from_first_station_wraps_to_last() {
        svc.playQueue(listOf(ok1, ok2, ok3), 0)
        assertThat(svc.waitPlaying(ok1.id)).isTrue()
        waitOutDebounce()

        svc.onMain { it.seekToPrevious() }
        assertThat(svc.waitPlaying(ok3.id)).isTrue()
        assertThat(svc.index).isEqualTo(2)
    }

    @Test fun next_on_last_station_wraps_to_first() {
        svc.playQueue(listOf(ok1, ok2, ok3), 2)
        assertThat(svc.waitPlaying(ok3.id)).isTrue()
        waitOutDebounce()

        svc.onMain { it.seekToNext() }
        assertThat(svc.waitPlaying(ok1.id)).isTrue()
    }

    @Test fun duplicate_next_within_debounce_window_switches_once() {
        svc.playQueue(listOf(ok1, ok2, ok3), 0)
        assertThat(svc.waitPlaying(ok1.id)).isTrue()
        waitOutDebounce()

        svc.onMain { it.seekToNext(); it.seekToNext() }
        assertThat(svc.waitPlaying(ok2.id)).isTrue()
        Thread.sleep(1_000)
        assertThat(svc.index).isEqualTo(1)
    }

    @Test fun pause_and_play_keep_the_same_station() {
        svc.playQueue(listOf(ok1, ok2, ok3), 0)
        assertThat(svc.waitPlaying(ok1.id)).isTrue()
        svc.onMain { it.pause() }
        assertThat(pollUntil(3_000) { !svc.isPlaying }).isTrue()
        svc.onMain { it.play() }
        assertThat(svc.waitPlaying(ok1.id)).isTrue()
        assertThat(svc.index).isEqualTo(0)
    }

    @Test fun http_404_station_is_retried_three_times_then_skipped() {
        val dead = FakeStreamServer.dead404(1)
        val hitsBefore = FakeStreamServer.hits("/dead/404")
        val mark = keyLogMark()
        svc.playQueue(listOf(dead, ok1, ok2), 0)

        // step 2 measured ~26 s for a real 404 (errors at +2/+7/+14/+23 s)
        assertWithMessage("dead station was not skipped").that(svc.waitPlaying(ok1.id, timeoutMs = 60_000)).isTrue()
        assertThat(svc.index).isEqualTo(1)
        val errors = keyLogSince(mark).filter { "[Player] error:" in it }
        assertThat(errors.size).isAtLeast(4) // first attempt + 3 retries
        assertThat(errors.last()).contains("ERROR_CODE_IO_BAD_HTTP_STATUS")
        assertThat(FakeStreamServer.hits("/dead/404") - hitsBefore).isAtLeast(4)
        assertThat(radioPrefs.getInt("last_index", -1)).isEqualTo(1)
    }

    @Test fun connection_closed_station_is_skipped() {
        val dead = FakeStreamServer.deadClosed(1)
        svc.playQueue(listOf(dead, ok2, ok3), 0)
        assertWithMessage("dead station was not skipped").that(svc.waitPlaying(ok2.id, timeoutMs = 90_000)).isTrue()
        assertThat(svc.index).isEqualTo(1)
    }

    /** Regression: a host that never answers used to leave "Connecting…" up for about 9 minutes. */
    @Test fun station_that_never_answers_is_skipped_within_a_minute() {
        val silent = FakeStreamServer.deadSilent(1)
        val mark = keyLogMark()
        val start = android.os.SystemClock.elapsedRealtime()
        svc.playQueue(listOf(silent, ok1, ok2), 0)

        assertWithMessage("silent station was not skipped").that(svc.waitPlaying(ok1.id, timeoutMs = 75_000)).isTrue()
        val took = android.os.SystemClock.elapsedRealtime() - start
        assertThat(took).isAtLeast(40_000L) // two 20 s attempts, so a slow but working station gets a fair chance
        assertThat(took).isLessThan(70_000L)
        assertThat(keyLogSince(mark).count { "[Player] no audio after 20 s" in it }).isEqualTo(2)
        assertThat(svc.status).isEmpty() // cleared once the next station plays
    }

    @Test fun single_dead_station_ends_in_error_without_crashing_or_skipping() {
        val dead = FakeStreamServer.dead404(2)
        val mark = keyLogMark()
        svc.playQueue(listOf(dead), 0)
        assertThat(pollUntil(60_000) { keyLogSince(mark).count { "[Player] error:" in it } >= 4 }).isTrue()
        Thread.sleep(3_000)
        assertThat(svc.index).isEqualTo(0)
        assertThat(svc.state).isEqualTo(Player.STATE_IDLE)
        assertThat(svc.onMain { it.playerError }).isNotNull()
        assertThat(svc.status).isEqualTo(StreamRecovery.STATUS_UNAVAILABLE)

        // Recovers as soon as the user picks a working station.
        svc.playQueue(listOf(ok1), 0)
        assertThat(svc.waitPlaying(ok1.id)).isTrue()
    }

    /**
     * U7 regression: while the device is offline every station fails, and RadioService used to skip through the
     * queue and overwrite last_index. It must keep the user's station until connectivity returns.
     * Repro: Wi-Fi and mobile data off (no default network) and the fake stream refusing connections.
     */
    @Test fun offline_outage_does_not_walk_through_the_queue() {
        svc.playQueue(listOf(ok1, ok2, ok3), 0)
        assertThat(svc.waitPlaying(ok1.id)).isTrue()
        val auto = instrumentation.uiAutomation
        try {
            shell(auto, "svc wifi disable")
            shell(auto, "svc data disable")
            FakeStreamServer.outage = true
            svc.onMain { it.stop(); it.prepare(); it.play() } // force a reconnect, as a dropped stream would
            // ~4 errors per station and a skip about every 25 s; 60 s offline is a tunnel / car park.
            Thread.sleep(60_000)
            assertThat(svc.index).isEqualTo(0)
            assertThat(radioPrefs.getInt("last_index", -1)).isEqualTo(0)
            assertThat(svc.status).isEqualTo(StreamRecovery.STATUS_NO_NETWORK)
        } finally {
            FakeStreamServer.outage = false
            shell(auto, "svc wifi enable")
            shell(auto, "svc data enable")
        }
    }

    private fun shell(auto: UiAutomation, cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(cmd)).use { it.readBytes() }
    }
}
