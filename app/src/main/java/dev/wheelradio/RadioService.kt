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

/**
 * Plays the stations as one looping playlist inside a MediaSession.
 * Route 1 for wheel keys: standard Android media buttons -> MediaSession -> player.seekToNext()/Previous().
 * Route 2: BYD's APP_REV_DATA broadcast (BydWheelKeyReceiver).
 * Volume: the wheel volume buttons change the car's media volume directly; we play as USAGE_MEDIA,
 * so no app code is needed for volume.
 */
class RadioService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var bydReceiver: BydWheelKeyReceiver
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0

    override fun onCreate() {
        super.onCreate()
        val repo = StationRepository(this)
        val stations = repo.load()

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
        exo.setMediaItems(
            stations.map { it.toMediaItem() },
            repo.lastIndex.coerceIn(0, (stations.size - 1).coerceAtLeast(0)),
            C.TIME_UNSET
        )
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                repo.lastIndex = exo.currentMediaItemIndex
                retries = 0
            }
            override fun onPlayerError(error: PlaybackException) {
                KeyLog.add("Player", "error: ${error.errorCodeName}")
                // Mobile data in a moving car drops out; retry a few times with backoff.
                if (retries < 5) {
                    retries++
                    handler.postDelayed({ exo.prepare() }, 2_000L * retries)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) retries = 0
            }
        })
        exo.prepare()
        exo.playWhenReady = true

        val player = StationSwitchingPlayer(exo)
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
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
