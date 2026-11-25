package dev.wheelradio

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

/**
 * Wraps ExoPlayer so that "next/previous" always means "next/previous station":
 *  - live streams never "restart the current track" on previous;
 *  - the same wheel press arriving through two routes (MediaSession + BYD broadcast)
 *    only switches one station (debounce).
 */
class StationSwitchingPlayer(
    player: Player,
    /** Called when the user starts a new attempt (prepare on an idle player, or a station switch). */
    private val onUserRetry: () -> Unit = {},
) : ForwardingPlayer(player) {
    private var lastSkipAt = 0L

    /**
     * Prepare from outside (MediaController "play" on an idle player, the session's play/pause handling) is the
     * user asking for this station again, so drop any pending automatic retry and start fresh.
     */
    override fun prepare() {
        onUserRetry()
        super.prepare()
    }

    override fun seekToNext() = skip(+1, "seekToNext")
    override fun seekToNextMediaItem() = skip(+1, "seekToNextMediaItem")
    override fun seekToPrevious() = skip(-1, "seekToPrevious")
    override fun seekToPreviousMediaItem() = skip(-1, "seekToPreviousMediaItem")

    @androidx.annotation.OptIn(UnstableApi::class)
    fun perform(action: WheelAction) {
        when (action) {
            WheelAction.NEXT -> seekToNext()
            WheelAction.PREVIOUS -> seekToPrevious()
            // Prepares an idle player too (after a failed station, or with auto-play off), where play() alone did nothing.
            WheelAction.PLAY_PAUSE -> Util.handlePlayPauseButtonAction(this)
        }
    }

    private fun skip(direction: Int, via: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSkipAt < DEBOUNCE_MS) {
            KeyLog.add("Player", "$via ignored (duplicate within ${DEBOUNCE_MS}ms)")
            return
        }
        lastSkipAt = now
        KeyLog.add("Player", via)
        val count = mediaItemCount
        if (count == 0) return
        // Follow the player's own order, so shuffle mode is respected (repeat-all makes it wrap around).
        val target = (if (direction > 0) wrappedPlayer.nextMediaItemIndex else wrappedPlayer.previousMediaItemIndex)
            .takeIf { it != C.INDEX_UNSET } ?: Math.floorMod(currentMediaItemIndex + direction, count)
        wrappedPlayer.seekToDefaultPosition(target)
        wrappedPlayer.playWhenReady = true
        // After an error the player is IDLE: without this the new station only started when a stale back-off
        // timer fired (up to 6 s), and in a one-station queue it never started.
        if (wrappedPlayer.playbackState == Player.STATE_IDLE) prepare()
    }

    companion object { private const val DEBOUNCE_MS = 400L }
}
