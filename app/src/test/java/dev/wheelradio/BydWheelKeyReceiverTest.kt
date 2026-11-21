package dev.wheelradio

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The BYD `APP_REV_DATA` route. `am broadcast` cannot send a byte[] extra, so the positive
 * path (a real `[4, 1, 0x03]` frame) was never exercised on the emulator in step 2.
 */
@RunWith(AndroidJUnit4::class)
class BydWheelKeyReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<WheelAction>()
    private val receiver = BydWheelKeyReceiver { actions += it }
    private var logBefore: String? = null

    @Before fun markLog() {
        logBefore = KeyLog.entries.value.firstOrNull()
    }

    /** KeyLog lines added since [markLog], oldest first. */
    private fun newLogLines() = KeyLog.entries.value.takeWhile { it != logBefore }.reversed()

    private fun frame(extra: Any?): Intent = Intent(BydWheelKeyReceiver.ACTION).apply {
        when (extra) {
            null -> Unit
            is ByteArray -> putExtra("list", extra)
            is IntArray -> putExtra("list", extra)
            is LongArray -> putExtra("list", extra)
            is String -> putExtra("list", extra)
            is Int -> putExtra("list", extra)
            else -> error("unsupported extra type")
        }
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun action_constant_matches_byd_broadcast() {
        assertThat(BydWheelKeyReceiver.ACTION).isEqualTo("APP_REV_DATA")
    }

    @Test fun wheel_next_frame_triggers_next_once_and_is_logged() {
        receiver.onReceive(context, frame(bytes(4, 1, 0x03)))

        assertThat(actions).containsExactly(WheelAction.NEXT)
        val log = newLogLines()
        assertThat(log).hasSize(1)
        assertThat(log[0]).contains("[BYD] frame 04 01 03 code=0x03 -> NEXT")
    }

    @Test fun trailing_bytes_after_the_code_are_ignored() {
        receiver.onReceive(context, frame(bytes(4, 1, 0x03, 0x00, 0x7F)))
        assertThat(actions).containsExactly(WheelAction.NEXT)
    }

    @Test fun unmapped_code_is_logged_but_does_nothing() {
        receiver.onReceive(context, frame(bytes(4, 1, 0x7F)))

        assertThat(actions).isEmpty()
        assertThat(newLogLines().single()).contains("code=0x7F -> unmapped")
    }

    @Test fun high_code_byte_is_masked_to_unsigned() {
        receiver.onReceive(context, frame(bytes(4, 1, 0xFF)))

        assertThat(actions).isEmpty()
        assertThat(newLogLines().single()).contains("frame 04 01 FF code=0xFF -> unmapped")
    }

    @Test fun wrong_group_does_nothing_but_is_logged() {
        receiver.onReceive(context, frame(bytes(5, 1, 0x03)))
        assertThat(actions).isEmpty()
        assertThat(newLogLines().single()).contains("[BYD] unrecognised: byte[] 05 01 03 (not a wheel-key frame)")
    }

    @Test fun wrong_sub_group_does_nothing_but_is_logged() {
        receiver.onReceive(context, frame(bytes(4, 2, 0x03)))
        assertThat(actions).isEmpty()
        assertThat(newLogLines().single()).contains("unrecognised: byte[] 04 02 03")
    }

    @Test fun short_and_empty_frames_do_nothing_but_are_logged() {
        receiver.onReceive(context, frame(bytes(4, 1)))
        receiver.onReceive(context, frame(bytes(4)))
        receiver.onReceive(context, frame(ByteArray(0)))
        assertThat(actions).isEmpty()
        assertThat(newLogLines()).hasSize(3)
        assertThat(newLogLines()[0]).contains("unrecognised: byte[] 04 01 (too short)")
    }

    @Test fun missing_extra_does_nothing_and_logs_the_extras_it_did_get() {
        receiver.onReceive(context, frame(null))
        receiver.onReceive(context, Intent(BydWheelKeyReceiver.ACTION).putExtra("other", bytes(4, 1, 3)))
        assertThat(actions).isEmpty()
        assertThat(newLogLines().last()).contains("no \"list\" extra; extras: [other]")
    }

    /** Documents current behaviour seen on the emulator: non-byte[] payloads are dropped. */
    @Test fun non_byte_array_payloads_do_not_trigger_actions() {
        receiver.onReceive(context, frame(intArrayOf(4, 1, 3)))
        receiver.onReceive(context, frame(longArrayOf(4, 1, 3)))
        receiver.onReceive(context, frame("04,01,03"))
        receiver.onReceive(context, frame(3))
        assertThat(actions).isEmpty()
    }

    @Test fun each_frame_is_handled_independently() {
        repeat(3) { receiver.onReceive(context, frame(bytes(4, 1, 0x03))) }
        // The receiver itself does not debounce; StationSwitchingPlayer does.
        assertThat(actions).containsExactly(WheelAction.NEXT, WheelAction.NEXT, WheelAction.NEXT)
    }

    /**
     * Regression: frames with another payload type or group used to be dropped before logging, so a drive could
     * not reveal the real BYD format. They are now logged (but never acted on).
     */
    @Test fun every_app_rev_data_frame_is_logged() {
        receiver.onReceive(context, frame(intArrayOf(4, 1, 3)))
        receiver.onReceive(context, frame(bytes(5, 1, 3)))
        val log = newLogLines()
        assertThat(log).hasSize(2)
        assertThat(log[0]).contains("unrecognised: int[] 4 1 3")
        assertThat(actions).isEmpty()
    }

    @Test fun a_repeated_unrecognised_payload_is_logged_once() {
        repeat(20) { receiver.onReceive(context, frame(bytes(7, 3, 0x10, 0x20))) }
        assertThat(newLogLines()).hasSize(1)
    }

    /** The head unit may broadcast other data constantly; it must not push wheel presses out of the 100-line log. */
    @Test fun unrecognised_payloads_stop_being_logged_after_fifty_distinct_ones_but_wheel_keys_still_are() {
        repeat(80) { receiver.onReceive(context, frame(bytes(9, 9, it))) }
        receiver.onReceive(context, frame(bytes(4, 1, 0x03)))

        val log = newLogLines()
        assertThat(log.count { "unrecognised:" in it }).isEqualTo(50)
        assertThat(log.count { "not logging any further ones" in it }).isEqualTo(1)
        assertThat(log.last()).contains("frame 04 01 03 code=0x03 -> NEXT")
        assertThat(actions).containsExactly(WheelAction.NEXT)
    }
}
