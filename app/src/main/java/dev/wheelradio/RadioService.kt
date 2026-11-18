package dev.wheelradio

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Plays the current list (a country's stations, favorites, or local files) in a loop inside a MediaSession.
 * Wheel keys reach it three ways (see README): MediaSession media buttons, the focused activity,
 * and BYD's APP_REV_DATA broadcast. Volume is handled by the car itself.
 */
class RadioService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var bydReceiver: BydWheelKeyReceiver
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0

    override fun onCreate() {
        super.onCreate()
        val repo = StationRepository(this)
        val queue = repo.queue

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.repeatMode = Player.REPEAT_MODE_ALL
        // Shuffle is only for local / USB songs, never for radio.
        exo.shuffleModeEnabled = repo.shuffle && queue.all { it.local }
        exo.setMediaItems(
            queue.map { it.toMediaItem() },
            repo.lastIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)),
            C.TIME_UNSET
        )
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                repo.lastIndex = exo.currentMediaItemIndex
                retries = 0
            }
            override fun onPlayerError(error: PlaybackException) {
                KeyLog.add("Player", "error: ${error.errorCodeName}")
                if (retries < 3) {
                    // Mobile data in a moving car drops out; retry with backoff.
                    retries++
                    handler.postDelayed({ exo.prepare() }, 2_000L * retries)
                } else if (exo.mediaItemCount > 1) {
                    // Station looks dead: move on so the driver isn't left with silence.
                    retries = 0
                    exo.seekToNextMediaItem()
                    exo.prepare()
                }
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                repo.shuffle = shuffleModeEnabled
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) retries = 0
            }
        })
        exo.prepare()
        exo.playWhenReady = true // resume the last station when the app starts, like a car radio

        val player = StationSwitchingPlayer(exo)
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Items sent from the UI arrive without their URI; restore it from requestMetadata.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                        if (uri == null) item else item.buildUpon().setUri(uri).build()
                    }.toMutableList()
                )
            })
            .build()

        bydReceiver = BydWheelKeyReceiver { player.perform(it) }
        bydReceiver.register(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Keep playing when the app is swiped away; the car's media source owns the lifecycle.
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bydReceiver.unregister(this)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
