package dev.wheelradio

import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Wraps ExoPlayer so that "next/previous" always means "next/previous station":
 *  - live streams never "restart the current track" on previous;
 *  - the same wheel press arriving through two routes (MediaSession + BYD broadcast)
 *    only switches one station (debounce).
 */
class StationSwitchingPlayer(player: Player) : ForwardingPlayer(player) {
    private var lastSkipAt = 0L

    override fun seekToNext() = skip(+1, "seekToNext")
    override fun seekToNextMediaItem() = skip(+1, "seekToNextMediaItem")
    override fun seekToPrevious() = skip(-1, "seekToPrevious")
    override fun seekToPreviousMediaItem() = skip(-1, "seekToPreviousMediaItem")

    fun perform(action: WheelAction) = when (action) {
        WheelAction.NEXT -> seekToNext()
        WheelAction.PREVIOUS -> seekToPrevious()
        WheelAction.PLAY_PAUSE -> if (isPlaying) pause() else play()
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
        val target = Math.floorMod(currentMediaItemIndex + direction, count)
        wrappedPlayer.seekToDefaultPosition(target)
        wrappedPlayer.playWhenReady = true
    }

    companion object { private const val DEBOUNCE_MS = 400L }
}
