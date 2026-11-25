package dev.wheelradio

import androidx.compose.ui.test.performClick
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import java.util.concurrent.TimeUnit

val instrumentation get() = InstrumentationRegistry.getInstrumentation()
val appContext: Context get() = instrumentation.targetContext
val radioPrefs get() = appContext.getSharedPreferences("radio", Context.MODE_PRIVATE)

/**
 * Writes radio.xml *before* anything binds RadioService, so a freshly created service resumes a
 * local fake stream instead of the bundled live stations (stations.json). Synchronous commit().
 */
fun seedPrefs(queue: List<Station>, lastIndex: Int = 0, favorites: List<Station> = emptyList()) {
    radioPrefs.edit().clear()
        .putString("queue", Station.listToJson(queue))
        .putInt("last_index", lastIndex)
        .putString("favorites", Station.listToJson(favorites))
        .putBoolean("shuffle", false)
        // The idle big player would cover the list during the long playback waits; BigPlayer tests turn it on.
        .putBoolean(AppSettings.KEY_BIG_PLAYER, false)
        .commit()
}

/** Seeds prefs before the activity rule launches MainActivity (use as the outer rule of a RuleChain). */
class SeedPrefsRule(
    private val favorites: () -> List<Station> = { emptyList() },
) : ExternalResource() {
    override fun before() {
        FakeStreamServer.outage = false
        seedPrefs(defaultQueue(), 0, favorites())
    }
}

fun defaultQueue() = listOf(FakeStreamServer.ok(1), FakeStreamServer.ok(2), FakeStreamServer.ok(3))

/** KeyLog lines added after [mark] (newest first). */
fun keyLogSince(mark: String?): List<String> = KeyLog.entries.value.takeWhile { it != mark }

fun keyLogMark(): String? = KeyLog.entries.value.firstOrNull()

/** Polls [cond] from the test thread. Returns true if it became true within [timeoutMs]. */
fun pollUntil(timeoutMs: Long, intervalMs: Long = 100, cond: () -> Boolean): Boolean {
    val end = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < end) {
        if (cond()) return true
        Thread.sleep(intervalMs)
    }
    return cond()
}

/** The service's StationSwitchingPlayer debounces skips within 400 ms, including skips from earlier tests. */
fun waitOutDebounce() = Thread.sleep(500)

/**
 * A MediaController connected to RadioService, the same way MainActivity connects.
 * Controller calls must happen on its application looper (main), hence [onMain].
 */
class ServiceController : AutoCloseable {
    val controller: MediaController = run {
        val token = SessionToken(appContext, ComponentName(appContext, RadioService::class.java))
        MediaController.Builder(appContext, token)
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()
            .get(15, TimeUnit.SECONDS)
    }

    fun <T> onMain(block: (MediaController) -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching { block(controller) } }
        return result!!.getOrThrow()
    }

    val index get() = onMain { it.currentMediaItemIndex }
    val mediaId get() = onMain { it.currentMediaItem?.mediaId }
    val isPlaying get() = onMain { it.isPlaying }
    val state get() = onMain { it.playbackState }

    /** RadioService's status line for the mini player ("" when all is well). */
    val status: String get() = onMain { it.sessionExtras.getString(StreamRecovery.EXTRA_STATUS).orEmpty() }

    /** What MainActivity.play() does when a row is tapped. */
    fun playQueue(list: List<Station>, index: Int = 0) = onMain {
        it.setMediaItems(list.map(Station::toMediaItem), index, C.TIME_UNSET)
        it.shuffleModeEnabled = false
        it.prepare()
        it.play()
    }

    fun waitPlaying(id: String, timeoutMs: Long = 15_000): Boolean = pollUntil(timeoutMs) {
        onMain { c -> c.currentMediaItem?.mediaId == id && c.isPlaying && c.playbackState == Player.STATE_READY }
    }

    override fun close() = onMain { it.release() }
}

/** A string in the app's language (the emulator's, English, unless a test switched it). */
fun appString(id: Int, vararg args: Any): String = appContext.getString(id, *args)

/** The wheel-button log now lives in Settings: gear → "Show wheel button log". */
fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.openKeyLog() {
    onNode(androidx.compose.ui.test.hasContentDescription("Settings")).performClick()
    onNode(androidx.compose.ui.test.hasText("Show wheel button log")).performClick()
}
