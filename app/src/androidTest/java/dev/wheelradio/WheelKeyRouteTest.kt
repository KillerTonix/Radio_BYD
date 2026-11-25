package dev.wheelradio

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The three wheel-key routes from the audio-session report, with MainActivity in the foreground and the
 * service playing local fake streams:
 *  - Activity route: MainActivity.dispatchKeyEvent -> its MediaController
 *  - Session route: media keys the activity does not consume (PLAY_PAUSE) -> MediaSession
 *  - BYD route: APP_REV_DATA broadcast with a byte[] "list" extra (cannot be sent with `am`, so only here)
 */
@OptIn(ExperimentalTestApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class WheelKeyRouteTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(SeedPrefsRule()).around(compose)

    private lateinit var svc: ServiceController
    private val queue = defaultQueue()

    @Before fun startPlaying() {
        svc = ServiceController()
        svc.playQueue(queue, 0)
        assertWithMessage("fake stream did not start").that(svc.waitPlaying(queue[0].id)).isTrue()
        // The activity's own controller connects asynchronously in onStart(); "Pause" in the mini player
        // proves it is connected and sees the playing state, so dispatchKeyEvent can use it.
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("QA Stream 1")).fetchSemanticsNodes().isNotEmpty() }
        waitOutDebounce()
    }

    @After fun stop() {
        svc.onMain { it.pause() }
        svc.close()
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    private fun assertSettlesOn(index: Int) {
        assertThat(svc.waitPlaying(queue[index].id)).isTrue()
        Thread.sleep(1_000) // no late second switch
        assertThat(svc.index).isEqualTo(index)
    }

    @Test fun media_next_key_switches_exactly_one_station() {
        val mark = keyLogMark()
        key(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertSettlesOn(1)
        val log = keyLogSince(mark)
        assertThat(log.any { "[Activity] KEYCODE_MEDIA_NEXT DOWN" in it }).isTrue()
        assertThat(log.count { it.endsWith("[Player] seekToNext") }).isEqualTo(1)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("QA Stream 2")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun media_previous_key_wraps_to_the_last_station() {
        key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        assertSettlesOn(2)
    }

    @Test fun channel_up_and_down_work_while_the_activity_has_focus() {
        key(KeyEvent.KEYCODE_CHANNEL_UP)
        assertSettlesOn(1)
        waitOutDebounce()
        key(KeyEvent.KEYCODE_CHANNEL_DOWN)
        assertSettlesOn(0)
    }

    @Test fun mini_player_next_and_previous_buttons_switch_one_station() {
        compose.onNodeWithContentDescription("Next").performClick()
        assertSettlesOn(1)
        waitOutDebounce()
        compose.onNodeWithContentDescription("Previous").performClick()
        assertSettlesOn(0)
    }

    @Test fun mini_player_play_pause_toggles() {
        compose.onNodeWithContentDescription("Pause").performClick()
        assertThat(pollUntil(5_000) { !svc.isPlaying }).isTrue()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Play").performClick()
        assertThat(svc.waitPlaying(queue[0].id)).isTrue()
        assertThat(svc.index).isEqualTo(0)
    }

    @Test fun single_play_pause_key_pauses_without_switching() {
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertThat(pollUntil(5_000) { !svc.isPlaying }).isTrue()
        Thread.sleep(1_000) // longer than the double-tap window
        assertThat(svc.index).isEqualTo(0)
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertThat(svc.waitPlaying(queue[0].id)).isTrue()
    }

    @Test fun byd_app_rev_data_next_frame_switches_one_station() {
        val mark = keyLogMark()
        appContext.sendBroadcast(Intent(BydWheelKeyReceiver.ACTION).putExtra("list", byteArrayOf(4, 1, 0x03)))
        assertSettlesOn(1)
        assertThat(keyLogSince(mark).any { "[BYD] frame 04 01 03 code=0x03 -> NEXT" in it }).isTrue()
    }

    @Test fun byd_frame_and_media_key_for_the_same_press_switch_only_once() {
        // The same physical press arriving over two routes must not skip two stations.
        appContext.sendBroadcast(Intent(BydWheelKeyReceiver.ACTION).putExtra("list", byteArrayOf(4, 1, 0x03)))
        key(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertSettlesOn(1)
    }

    @Test fun byd_unmapped_or_malformed_frames_do_nothing() {
        appContext.sendBroadcast(Intent(BydWheelKeyReceiver.ACTION).putExtra("list", byteArrayOf(4, 1, 0x7F)))
        appContext.sendBroadcast(Intent(BydWheelKeyReceiver.ACTION).putExtra("list", byteArrayOf(5, 1, 0x03)))
        appContext.sendBroadcast(Intent(BydWheelKeyReceiver.ACTION).putExtra("list", intArrayOf(4, 1, 3)))
        Thread.sleep(2_000)
        assertThat(svc.index).isEqualTo(0)
        assertThat(svc.isPlaying).isTrue()
    }

    // ---- regressions for bugs found by the QA pipeline (see test-results/bugs.md) ----

    /** Media3 maps a PLAY_PAUSE double-tap to "next"; RadioService.onMediaButtonEvent now handles PLAY_PAUSE itself. */
    @Test fun double_play_pause_never_switches_station() {
        val mark = keyLogMark()
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        Thread.sleep(2_000)
        assertThat(svc.mediaId).isEqualTo(queue[0].id)
        assertThat(keyLogSince(mark).none { it.endsWith("[Player] seekToNext") }).isTrue()
        assertThat(keyLogSince(mark).count { "[Session] KEYCODE_MEDIA_PLAY_PAUSE" in it }).isEqualTo(2)
    }

    @Test fun double_headsethook_in_background_never_switches_station() {
        val device = UiDevice.getInstance(instrumentation)
        device.pressHome()
        Thread.sleep(1_500)
        val mark = keyLogMark()
        // UiDevice injects system-wide (instrumentation.sendKeyDownUpSync can't reach the launcher's window).
        device.pressKeyCode(KeyEvent.KEYCODE_HEADSETHOOK)
        device.pressKeyCode(KeyEvent.KEYCODE_HEADSETHOOK)
        Thread.sleep(2_000)
        assertThat(keyLogSince(mark).count { "[Session] KEYCODE_HEADSETHOOK" in it }).isEqualTo(2)
        assertThat(svc.mediaId).isEqualTo(queue[0].id)
    }

    /**
     * Not fixable inside the app: Android only routes media keys (PLAY/PAUSE/NEXT/PREVIOUS/…) to a background
     * MediaSession. CHANNEL_UP/DOWN are ordinary keys that go to whichever window has focus, so a background app
     * never sees them. Catching them would need an AccessibilityService that the driver enables by hand.
     */
    @Ignore("Platform limit: CHANNEL_UP/DOWN are not media keys and never reach a background app - see test-results/bugs.md")
    @Test fun channel_up_switches_station_with_the_app_in_background() {
        val device = UiDevice.getInstance(instrumentation)
        device.pressHome()
        Thread.sleep(1_500)
        device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP)
        assertWithMessage("CHANNEL_UP in background").that(pollUntil(5_000) { svc.index == 1 }).isTrue()
    }

    /** The key log used to be a Dialog window that took key focus; it is now an overlay in the activity's window. */
    @Test fun channel_up_switches_station_while_key_log_dialog_is_open() {
        compose.openKeyLog()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Wheel button log")).fetchSemanticsNodes().isNotEmpty() }
        val mark = keyLogMark()
        UiDevice.getInstance(instrumentation).pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP)
        assertWithMessage("CHANNEL_UP with dialog open").that(pollUntil(5_000) { svc.index == 1 }).isTrue()
        // The diagnostic screen must show the route the key really took.
        assertThat(keyLogSince(mark).any { "[Activity] KEYCODE_CHANNEL_UP DOWN" in it }).isTrue()
        compose.onNodeWithText("Wheel button log").assertIsDisplayed()
    }

    /**
     * A real 2 s hold of a wheel key: DOWN, then auto-repeat DOWNs every 50 ms after 500 ms, then UP
     * (`adb input keyevent --longpress` only sends one repeat, so step 2 could not measure this).
     */
    private fun holdKey(code: Int, holdMs: Long = 2_000) {
        val auto = instrumentation.uiAutomation
        val downAt = SystemClock.uptimeMillis()
        fun ev(action: Int, repeat: Int) = KeyEvent(
            downAt, SystemClock.uptimeMillis(), action, code, repeat, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        auto.injectInputEvent(ev(KeyEvent.ACTION_DOWN, 0), true)
        Thread.sleep(500)
        var repeat = 1
        while (SystemClock.uptimeMillis() - downAt < holdMs) {
            auto.injectInputEvent(ev(KeyEvent.ACTION_DOWN, repeat++), true)
            Thread.sleep(50)
        }
        auto.injectInputEvent(ev(KeyEvent.ACTION_UP, 0), true)
    }

    @Test fun holding_media_next_in_foreground_switches_one_station() {
        holdKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertSettlesOn(1)
    }

    /** RadioService.onMediaButtonEvent now drops auto-repeat DOWNs, like MainActivity.dispatchKeyEvent does. */
    @Test fun holding_media_next_in_background_switches_one_station() {
        UiDevice.getInstance(instrumentation).pressHome()
        Thread.sleep(1_500)
        val mark = keyLogMark()
        holdKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        Thread.sleep(1_500)
        assertThat(svc.index).isEqualTo(1)
        assertThat(keyLogSince(mark).count { "held, repeats ignored" in it }).isEqualTo(1) // logged once, not per repeat
    }

    @Test fun media_next_works_with_the_app_in_background() {
        val device = UiDevice.getInstance(instrumentation)
        device.pressHome()
        Thread.sleep(1_500)
        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertSettlesOn(1)
    }
}
