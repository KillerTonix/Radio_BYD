package dev.wheelradio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
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
    private lateinit var network: NetworkMonitor
    private lateinit var recovery: StreamRecovery

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
        // Retries, skips, the no-audio watchdog and the offline wait (see StreamRecovery). Its status line
        // travels to the UI in the session extras.
        recovery = StreamRecovery(exo, handler, isOnline = { network.isOnline() }) { status ->
            session?.setSessionExtras(Bundle().apply { putString(StreamRecovery.EXTRA_STATUS, status) })
        }
        network = NetworkMonitor(this, handler) { recovery.onNetworkBack() }
        network.start()
        exo.addListener(recovery.listener)

        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                repo.lastIndex = exo.currentMediaItemIndex
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                repo.shuffle = shuffleModeEnabled
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) BluetoothBridge.active = false // our player is the source again
            }
        })
        exo.prepare()
        exo.playWhenReady = true // resume the last station when the app starts, like a car radio

        // Play on an idle player and Next / Previous (from the UI, the session or the wheel) come through here.
        val player = StationSwitchingPlayer(exo, onUserRetry = recovery::onUserRetry)
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

                /**
                 * Media keys routed to the session (app in the background, or keys the activity doesn't consume).
                 * Media3 only calls this for ACTION_DOWN; returning true stops its default handling.
                 */
                @OptIn(UnstableApi::class)
                override fun onMediaButtonEvent(
                    mediaSession: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    @Suppress("DEPRECATION")
                    val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    val name = KeyEvent.keyCodeToString(event.keyCode)
                    if (event.repeatCount > 0) {
                        // A held wheel key auto-repeats every ~50 ms; one press must move one station, as in the activity.
                        if (event.repeatCount == 1) KeyLog.add("Session", "$name held, repeats ignored")
                        return true
                    }
                    KeyLog.add("Session", name)
                    return when (event.keyCode) {
                        // Media3 maps a PLAY_PAUSE / HEADSETHOOK double-tap to "next". On a steering wheel a quick
                        // double press, a bouncing switch or one press delivered twice must never change the station.
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                            Util.handlePlayPauseButtonAction(player)
                            true
                        }
                        else -> false // next / previous / play / pause: Media3's default, through StationSwitchingPlayer
                    }
                }
            })
            .build()

        bydReceiver = BydWheelKeyReceiver { if (!BluetoothBridge.handle(it)) player.perform(it) }
        bydReceiver.register(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Keep playing when the app is swiped away; the car's media source owns the lifecycle.
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recovery.release()
        network.stop()
        bydReceiver.unregister(this)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
