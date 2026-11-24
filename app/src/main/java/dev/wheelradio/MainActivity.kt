package dev.wheelradio

import android.content.ComponentName
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = AppModel(this, lifecycleScope)
        model.playList = ::play
        model.playShuffled = { list ->
            if (list.isNotEmpty()) {
                play(list, list.indices.random())
                controller?.shuffleModeEnabled = true
            }
        }
        model.setShuffle = { on -> controller?.shuffleModeEnabled = on }
        model.pauseRadio = { controller?.pause() }
        model.position = {
            val c = controller
            if (c == null) 0L to 0L
            else c.currentPosition.coerceAtLeast(0) to (if (c.isCurrentMediaItemLive || c.duration == C.TIME_UNSET) 0L else c.duration)
        }
        model.seekTo = { ms -> controller?.seekTo(ms) }
        BluetoothBridge.start(this)
        model.loadCountries()
        setContent {
            WheelRadioApp(
                model = model,
                onPlayPause = {
                    controller?.let {
                        if (it.isPlaying) it.pause()
                        else {
                            BluetoothBridge.pause()
                            BluetoothBridge.active = false
                            // After a failed station the player is IDLE, and play() alone would do nothing.
                            if (it.playbackState == Player.STATE_IDLE) it.prepare()
                            it.play()
                        }
                    }
                },
                onNext = { controller?.seekToNext() },
                onPrevious = { controller?.seekToPrevious() },
            )
        }
    }

    private fun play(list: List<Station>, index: Int) {
        if (list.isEmpty()) return
        BluetoothBridge.pause()
        BluetoothBridge.active = false
        model.rememberQueue(list, index)
        controller?.run {
            setMediaItems(list.map { it.toMediaItem() }, index, C.TIME_UNSET)
            // Shuffle is only for local / USB songs: radio always plays in list order.
            if (list.any { !it.local }) shuffleModeEnabled = false
            prepare()
            play()
        }
    }

    override fun onStart() {
        super.onStart()
        BluetoothBridge.uiVisible = true
        lifecycleScope.launch {
            val token = SessionToken(this@MainActivity, ComponentName(this@MainActivity, RadioService::class.java))
            val c = MediaController.Builder(this@MainActivity, token)
                .setListener(object : MediaController.Listener {
                    // RadioService's status line (retrying / no internet / unavailable) arrives as session extras.
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) = refresh(controller)
                })
                .buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh(player)
            })
            refresh(c)
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app: show the list the driver left, and count the idle time from now.
        model.bigPlayer = false
        model.onUserTouch()
    }

    override fun onStop() {
        BluetoothBridge.uiVisible = false
        controller?.release()
        controller = null
        super.onStop()
    }

    private fun refresh(p: Player) {
        val item = p.currentMediaItem
        val station = item?.let { Station.fromMediaItem(it) }
        val title = station?.name ?: ""
        val song = p.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() && it != title } ?: ""
        model.now = NowPlaying(
            station = station,
            title = title,
            nowPlaying = song,
            artwork = station?.favicon ?: "",
            // Cover art embedded in a local / USB song (ID3 / FLAC picture), when there is one.
            artworkData = p.mediaMetadata.artworkData?.takeIf { station?.local == true },
            isPlaying = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            shuffle = p.shuffleModeEnabled,
            canShuffle = station?.local == true,
            status = (p as? MediaController)?.sessionExtras?.getString(StreamRecovery.EXTRA_STATUS).orEmpty(),
        )
    }

    /**
     * When our screen is in front, BYD hands media keys to the focused window
     * (behaviour reported by the DiPlay project). Catch them here too, and log everything.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.add(
            "Activity",
            "${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}" +
                " scan=${event.scanCode} src=0x${Integer.toHexString(event.source)}"
        )
        val next = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> true
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> false
            else -> null // volume keys, typing etc. go to the system as usual
        }
        if (next != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            BluetoothBridge.handle(if (next) WheelAction.NEXT else WheelAction.PREVIOUS)
        ) return true
        if (next != null && BluetoothBridge.active && event.action == KeyEvent.ACTION_UP) return true
        val c = controller
        if (next != null && c != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (next) c.seekToNext() else c.seekToPrevious()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
