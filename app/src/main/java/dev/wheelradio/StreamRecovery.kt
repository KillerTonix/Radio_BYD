package dev.wheelradio

import android.os.Handler
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * What RadioService does when a station fails, so the driver isn't left in silence and doesn't lose the station.
 *
 *  - **Error, network up:** retry the same station with back-off (2 s, 4 s, 6 s), then skip to the next one.
 *  - **Error, no network** (tunnel, car park): keep the station and wait; retry it when the network returns (bug U7).
 *  - **No audio within [FIRST_AUDIO_TIMEOUT_MS]:** a host that never answers used to sit on "Connecting…" for about
 *    9 minutes (8 s connect timeout × every resolved address × ExoPlayer's internal retries × our retries). The
 *    watchdog counts that as a failure, with one retry, because each attempt already cost the driver 20 s.
 *  - **The user presses Play / Next / Previous:** pending retries are dropped and the new attempt starts at once.
 *
 * Every state change is reported through [onStatus] as a short line for the mini player ("" = nothing to show).
 * Must be used on the player's (main) thread.
 */
class StreamRecovery(
    private val player: Player,
    private val handler: Handler,
    private val isOnline: () -> Boolean,
    private val onStatus: (String) -> Unit,
) {
    private var retries = 0
    private var pendingRetry: Runnable? = null
    private var waitingForNetwork = false

    /** False from each (re)start of a station until it first reaches READY; the watchdog only runs in that window. */
    private var gotAudio = false
    private val watchdog = Runnable { onNoAudio() }
    private var watchdogArmed = false

    val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = reset()

        override fun onPlayerError(error: PlaybackException) {
            KeyLog.add("Player", "error: ${error.errorCodeName}")
            handleFailure(stalled = false)
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> { gotAudio = true; disarmWatchdog() }
                Player.STATE_BUFFERING -> if (!gotAudio && player.playWhenReady) armWatchdog()
                else -> disarmWatchdog()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Paused while connecting: no point timing it. Resumed while still connecting: start timing.
            if (!playWhenReady) disarmWatchdog()
            else if (!gotAudio && player.playbackState == Player.STATE_BUFFERING) armWatchdog()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                retries = 0
                onStatus("")
            }
        }
    }

    /** The user asked for this station again (Play on an idle player, or Next / Previous): start fresh. */
    fun onUserRetry() = reset()

    /** Connectivity came back. */
    fun onNetworkBack() {
        if (!waitingForNetwork) return
        reset()
        KeyLog.add("Player", "network back, retrying the same station")
        player.prepare()
    }

    fun release() {
        cancelRetry()
        disarmWatchdog()
    }

    private fun reset() {
        cancelRetry()
        disarmWatchdog()
        retries = 0
        waitingForNetwork = false
        gotAudio = false
        onStatus("")
    }

    private fun onNoAudio() {
        watchdogArmed = false
        KeyLog.add("Player", "no audio after ${FIRST_AUDIO_TIMEOUT_MS / 1000} s")
        player.stop() // abandon the hanging connection; the queue and position are kept
        handleFailure(stalled = true)
    }

    private fun handleFailure(stalled: Boolean) {
        cancelRetry()
        disarmWatchdog()
        gotAudio = false
        // Only network streams can be fixed by waiting for the network; local / USB files (content://, file://) can't.
        val scheme = player.currentMediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        val streaming = scheme == "http" || scheme == "https"
        if (streaming && !isOnline()) {
            // No network at all: every station would fail the same way, so skipping would only walk through
            // the queue and lose the driver's station. Wait instead.
            waitingForNetwork = true
            retries = 0
            KeyLog.add("Player", "offline, keeping the station until the network returns")
            onStatus(STATUS_NO_NETWORK)
            return
        }
        val maxRetries = if (stalled) STALL_RETRIES else ERROR_RETRIES
        when {
            retries < maxRetries -> {
                // Mobile data in a moving car drops out; retry with back-off.
                retries++
                onStatus("Can't reach the station – retrying ($retries/$maxRetries)…")
                val retry = Runnable {
                    pendingRetry = null
                    player.prepare()
                }
                pendingRetry = retry
                handler.postDelayed(retry, 2_000L * retries)
            }
            player.mediaItemCount > 1 -> {
                // Station looks dead: move on so the driver isn't left with silence.
                KeyLog.add("Player", "station unavailable, skipping to the next one")
                player.seekToNextMediaItem() // resets our state through onMediaItemTransition
                player.prepare()
            }
            else -> {
                retries = 0
                onStatus(STATUS_UNAVAILABLE)
            }
        }
    }

    private fun cancelRetry() {
        pendingRetry?.let(handler::removeCallbacks)
        pendingRetry = null
    }

    private fun armWatchdog() {
        if (watchdogArmed) return
        watchdogArmed = true
        handler.postDelayed(watchdog, FIRST_AUDIO_TIMEOUT_MS)
    }

    private fun disarmWatchdog() {
        watchdogArmed = false
        handler.removeCallbacks(watchdog)
    }

    companion object {
        const val FIRST_AUDIO_TIMEOUT_MS = 20_000L
        const val ERROR_RETRIES = 3
        const val STALL_RETRIES = 1
        const val STATUS_NO_NETWORK = "No internet – the station resumes when it's back"
        const val STATUS_UNAVAILABLE = "Station unavailable – press Play to try again"

        /** Session-extras key that carries the status line to the UI. */
        const val EXTRA_STATUS = "dev.wheelradio.status"
    }
}
