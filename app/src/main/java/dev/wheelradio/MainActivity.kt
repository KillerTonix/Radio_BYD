package dev.wheelradio

import android.content.ComponentName
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val ui = mutableStateOf(UiState())
    private val stations by lazy { StationRepository(this).load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val log by KeyLog.entries.collectAsState()
                RadioScreen(
                    state = ui.value,
                    stations = stations,
                    keyLog = log,
                    onPrev = { controller?.seekToPrevious() },
                    onNext = { controller?.seekToNext() },
                    onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                    onSelect = { i -> controller?.run { seekToDefaultPosition(i); play() } },
                )
            }
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
        val station = p.currentMediaItem?.mediaMetadata?.station?.toString()
            ?: p.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
        val nowPlaying = p.mediaMetadata.title?.toString()?.takeIf { it != station } ?: ""
        ui.value = UiState(station, nowPlaying, p.isPlaying, p.currentMediaItemIndex, p.playbackState == Player.STATE_BUFFERING)
    }

    /**
     * Route 3: when our screen is in front, BYD hands media keys to the focused window
     * (behaviour reported by the DiPlay project). Catch them here too, and log everything.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.add(
            "Activity",
            "${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}" +
                " scan=${event.scanCode} src=0x${Integer.toHexString(event.source)}"
        )
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> WheelAction.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> WheelAction.PREVIOUS
            else -> null // volume keys etc. go to the system as usual
        }
        val c = controller
        if (action != null && c != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (action == WheelAction.NEXT) c.seekToNext() else c.seekToPrevious()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

data class UiState(
    val station: String = "",
    val nowPlaying: String = "",
    val isPlaying: Boolean = false,
    val index: Int = -1,
    val buffering: Boolean = false,
)

@Composable
private fun RadioScreen(
    state: UiState,
    stations: List<Station>,
    keyLog: List<String>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(24.dp)) {
        // Left: now playing + big controls (landscape head unit screen)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text(state.station.ifEmpty { "—" }, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.buffering) "Connecting…" else state.nowPlaying,
                fontSize = 22.sp, color = Color(0xFFB0BEC5)
            )
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton("⏮", onPrev)
                BigButton(if (state.isPlaying) "⏸" else "▶", onPlayPause)
                BigButton("⏭", onNext)
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { showLog = !showLog }) {
                Text(if (showLog) "Hide key log" else "Show key log (for testing wheel buttons)")
            }
        }
        // Right: station list or key log
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (showLog) {
                LazyColumn {
                    itemsIndexed(keyLog) { _, line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFF80CBC4))
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(stations) { i, s ->
                        val selected = i == state.index
                        Text(
                            s.name,
                            fontSize = 24.sp,
                            color = if (selected) Color(0xFF4FC3F7) else Color.White,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(i) }.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.size(110.dp)) {
        Text(label, fontSize = 40.sp)
    }
}
