package dev.wheelradio

import android.content.ComponentName
import android.content.Intent
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
        model.loadCountries()
        setContent {
            WheelRadioApp(
                model = model,
                onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                onNext = { controller?.seekToNext() },
                onPrevious = { controller?.seekToPrevious() },
                onPickFolder = { folderPicker.launch(null) },
            )
        }
    }

    private val folderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            model.setMusicFolder(uri)
        }
    }

    private fun play(list: List<Station>, index: Int) {
        if (list.isEmpty()) return
        model.rememberQueue(list, index)
        controller?.run {
            setMediaItems(list.map { it.toMediaItem() }, index, C.TIME_UNSET)
            prepare()
            play()
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val token = SessionToken(this@MainActivity, ComponentName(this@MainActivity, RadioService::class.java))
            val c = MediaController.Builder(this@MainActivity, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh(player)
            })
            refresh(c)
        }
    }

    override fun onStop() {
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
            isPlaying = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
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
