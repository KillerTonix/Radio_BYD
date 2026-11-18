package dev.wheelradio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.SubcomposeAsyncImage

private val Bg = Color(0xFF141B26)
private val Surface1 = Color(0xFF1E2735)
private val Accent = Color(0xFF29B6F6)
private val TextDim = Color(0xFFB0BEC5)

private val tileGradients = listOf(
    listOf(Color(0xFFB71C1C), Color(0xFF1A237E)),
    listOf(Color(0xFF3E2723), Color(0xFF212121)),
    listOf(Color(0xFFAD1457), Color(0xFF1565C0)),
    listOf(Color(0xFF283593), Color(0xFF37474F)),
    listOf(Color(0xFFC62828), Color(0xFF4E342E)),
    listOf(Color(0xFF0D47A1), Color(0xFF283593)),
    listOf(Color(0xFFE65100), Color(0xFF4A148C)),
    listOf(Color(0xFF00695C), Color(0xFF1B5E20)),
    listOf(Color(0xFF6A1B9A), Color(0xFF283593)),
)

@Composable
fun WheelRadioApp(
    model: AppModel,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Surface1)) {
        BackHandler(enabled = model.openCountry != null) { model.openCountry = null }
        Column(Modifier.fillMaxSize().background(Bg)) {
            TopBar(model)
            TabRow(
                selectedTabIndex = model.tab.ordinal,
                containerColor = Bg,
                contentColor = Accent,
            ) {
                AppTab.entries.forEach { t ->
                    Tab(
                        selected = model.tab == t,
                        onClick = { model.tab = t },
                        text = {
                            Text(
                                t.title.uppercase(), fontSize = 18.sp,
                                color = if (model.tab == t) Accent else TextDim,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                when (model.tab) {
                    AppTab.COUNTRIES -> CountriesTab(model)
                    AppTab.FAVORITES -> FavoritesTab(model)
                    AppTab.LOCAL -> LocalTab(model)
                    AppTab.BLUETOOTH -> BluetoothTab(model)
                }
            }
            MiniPlayer(model, onPlayPause, onNext, onPrevious)
        }
        if (model.showKeyLog) KeyLogDialog { model.showKeyLog = false }
    }
}

@Composable
private fun TopBar(model: AppModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = model.query,
            onValueChange = model::updateQuery,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Search stations, countries, files…", fontSize = 18.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (model.query.isNotEmpty()) IconButton(onClick = { model.updateQuery("") }) {
                    Icon(Icons.Filled.Clear, "Clear search")
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        IconButton(onClick = { model.showKeyLog = true }, modifier = Modifier.padding(start = 8.dp).size(56.dp)) {
            Icon(Icons.Filled.Settings, "Key log", tint = TextDim, modifier = Modifier.size(32.dp))
        }
    }
}

// ---------- By countries ----------

@Composable
private fun CountriesTab(model: AppModel) {
    val q = model.query.trim()
    val country = model.openCountry
    when {
        country != null -> CountryStations(model, country)
        q.length >= 2 -> SearchResults(model, q)
        model.countriesLoading && model.countries.isEmpty() -> Centered { CircularProgressIndicator() }
        model.countriesError != null && model.countries.isEmpty() -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(model.countriesError!!, color = TextDim, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = model::loadCountries) {
                    Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Try again")
                }
            }
        }
        else -> CountryGrid(model, model.countries)
    }
}

@Composable
private fun CountryGrid(model: AppModel, countries: List<Country>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 280.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(countries, key = { it.code }) { c -> CountryTile(c, { model.showCountry(c) }, { model.playCountry(c) }) }
    }
}

@Composable
private fun CountryTile(c: Country, onOpen: () -> Unit, onPlay: () -> Unit) {
    val colors = tileGradients[Math.floorMod(c.code.hashCode(), tileGradients.size)]
    Box(
        Modifier.fillMaxWidth().height(116.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onOpen)
            .padding(16.dp)
    ) {
        Column(Modifier.align(Alignment.TopStart)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.flag, fontSize = 34.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    c.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("${c.stationCount} Stations", fontSize = 17.sp, color = Color.White.copy(alpha = 0.85f))
        }
        RoundIconButton(PlayerIcons.Play, "Play ${c.name}", onPlay, Modifier.align(Alignment.BottomEnd), size = 44)
    }
}

@Composable
private fun CountryStations(model: AppModel, country: Country) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.openCountry = null }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.ArrowBack, "Back", modifier = Modifier.size(30.dp))
            }
            Text("${country.flag}  ${country.name}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        when {
            model.countryLoading -> Centered { CircularProgressIndicator() }
            model.countryError != null -> Centered { Text(model.countryError!!, color = TextDim, fontSize = 18.sp) }
            else -> StationList(model, model.countryStations, "No stations found.")
        }
    }
}

@Composable
private fun SearchResults(model: AppModel, q: String) {
    val countryHits = model.countries.filter { it.name.contains(q, ignoreCase = true) }.take(6)
    Column(Modifier.fillMaxSize()) {
        if (countryHits.isNotEmpty()) {
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                countryHits.forEach { c ->
                    AssistChip(
                        onClick = { model.updateQuery(""); model.showCountry(c) },
                        label = { Text("${c.flag} ${c.name}", fontSize = 18.sp) },
                    )
                }
            }
        }
        if (model.searching && model.searchResults.isEmpty()) Centered { CircularProgressIndicator() }
        else StationList(model, model.searchResults, "No stations match \"$q\".")
    }
}

// ---------- Favorites ----------

@Composable
private fun FavoritesTab(model: AppModel) {
    val q = model.query.trim()
    val list = if (q.isEmpty()) model.favorites else model.favorites.filter { it.name.contains(q, true) }
    Column(Modifier.fillMaxSize()) {
        // Shuffle only applies to songs, so offer it for favorite local / USB songs only.
        val songs = list.filter { it.local }
        if (songs.size > 1) Button(onClick = { model.playShuffled(songs) }, modifier = Modifier.padding(vertical = 12.dp)) {
            Icon(PlayerIcons.Shuffle, null); Spacer(Modifier.width(8.dp))
            Text("Shuffle favorite songs (${songs.size})", fontSize = 17.sp)
        }
        StationList(model, list, "No favorites yet. Tap the heart next to a station to add it here.")
    }
}

// ---------- Local & USB ----------

@Composable
private fun LocalTab(model: AppModel) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        model.scanLocal()
    }
    LaunchedEffect(granted) { if (!model.localScanned && (granted || model.musicFolder != null)) model.scanLocal() }

    val q = model.query.trim()
    val files = if (q.isEmpty()) model.localFiles
    else model.localFiles.filter { it.name.contains(q, true) || it.subtitle.contains(q, true) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (granted) model.scanLocal() else launcher.launch(permission) }) {
                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp))
                Text(if (granted) "Rescan device & USB" else "Allow access to music", fontSize = 17.sp)
            }
            if (files.isNotEmpty()) Button(onClick = { model.playShuffled(files) }) {
                Icon(PlayerIcons.Shuffle, null); Spacer(Modifier.width(8.dp))
                Text("Shuffle all (${files.size})", fontSize = 17.sp)
            }
        }
        when {
            model.localScanning -> Centered { CircularProgressIndicator() }
            else -> StationList(
                model, files,
                if (!granted && model.musicFolder == null)
                    "Allow access so the app can find music on the car and on USB sticks."
                else "No audio files found. Plug in a USB stick and tap Rescan.",
            )
        }
    }
}

// ---------- Bluetooth ----------

@Composable
private fun BluetoothTab(model: AppModel) {
    val bt = BluetoothBridge
    val context = LocalContext.current
    LaunchedEffect(Unit) { bt.refresh() }
    when (bt.status) {
        BluetoothBridge.Status.CONNECTED -> Row(
            Modifier.fillMaxSize().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(220.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF2E3A4D)),
                contentAlignment = Alignment.Center,
            ) {
                val art = bt.art
                if (art != null) androidx.compose.foundation.Image(
                    art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                ) else Text("BT", fontSize = 64.sp, color = TextDim, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text("From your phone · ${bt.source}", fontSize = 16.sp, color = TextDim)
                Spacer(Modifier.height(8.dp))
                Text(
                    bt.title.ifBlank { "Nothing playing" }, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (bt.artist.isNotBlank()) Text(bt.artist, fontSize = 20.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(PlayerIcons.Previous, "Previous song", { bt.previous() }, size = 80, filled = false)
                    Spacer(Modifier.width(16.dp))
                    RoundIconButton(
                        if (bt.isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                        if (bt.isPlaying) "Pause" else "Play",
                        { if (bt.isPlaying) bt.pause() else { model.pauseRadio(); bt.play() } },
                        size = 96,
                    )
                    Spacer(Modifier.width(16.dp))
                    RoundIconButton(PlayerIcons.Next, "Next song", { bt.next() }, size = 80, filled = false)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    if (bt.active) "Wheel buttons control Bluetooth music." else "Press play here to use the wheel buttons for Bluetooth.",
                    fontSize = 15.sp, color = TextDim,
                )
            }
        }
        BluetoothBridge.Status.SEARCHING -> Centered { CircularProgressIndicator() }
        BluetoothBridge.Status.NOT_FOUND -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No Bluetooth music found.", fontSize = 22.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Connect your phone, start music on it (or in the car's Bluetooth music app), then tap Retry.",
                    fontSize = 17.sp, color = TextDim,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { bt.refresh() }) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Retry") }
            }
        }
        BluetoothBridge.Status.NEEDS_ACCESS -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("One-time setup needed", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "To control Bluetooth music, Wheel Radio needs notification access. Turn it on in settings, " +
                        "or run this once over ADB:",
                    fontSize = 17.sp, color = TextDim,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "adb shell cmd notification allow_listener dev.wheelradio/dev.wheelradio.MediaListener",
                    fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Color(0xFF80CBC4),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("Open settings") }
                    OutlinedButton(onClick = { bt.refresh() }) { Text("Retry") }
                }
            }
        }
    }
}

// ---------- Shared pieces ----------

@Composable
private fun StationList(model: AppModel, list: List<Station>, emptyText: String) {
    if (list.isEmpty()) {
        Centered { Text(emptyText, color = TextDim, fontSize = 18.sp) }
        return
    }
    val currentId = model.now.station?.id
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(list, key = { i, s -> s.id + i }) { i, s ->
            StationRow(
                s, s.id == currentId, model.isFavorite(s.id),
                onClick = { model.playList(list, i) },
                onFavorite = { model.toggleFavorite(s) },
            )
        }
    }
}

@Composable
private fun StationRow(s: Station, playing: Boolean, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .then(if (playing) Modifier.border(BorderStroke(2.dp, Accent), RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(s.favicon, s.name, 52)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.name, fontSize = 21.sp, color = if (playing) Accent else Color.White,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (s.subtitle.isNotBlank()) Text(
                s.subtitle, fontSize = 15.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onFavorite, modifier = Modifier.size(56.dp)) {
            Icon(
                if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (favorite) "Remove from favorites" else "Add to favorites",
                tint = if (favorite) Color(0xFFFF5370) else TextDim,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun Artwork(url: String, name: String, size: Int) {
    val fallback = @Composable {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF2E3A4D)),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), fontSize = (size / 2.2).sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp))) {
        if (url.isBlank()) fallback()
        else SubcomposeAsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(Color.White),
            loading = { fallback() }, error = { fallback() },
        )
    }
}

@Composable
private fun MiniPlayer(model: AppModel, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    val now = model.now
    Row(
        Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(now.artwork, now.title.ifBlank { "?" }, 60)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                now.title.ifBlank { "Nothing playing" }, fontSize = 22.sp, color = Color.White,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = if (now.buffering) "Connecting…" else now.nowPlaying
            if (sub.isNotBlank()) Text(sub, fontSize = 16.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (now.canShuffle) {
            IconButton(onClick = { model.setShuffle(!now.shuffle) }, modifier = Modifier.size(56.dp)) {
                Icon(
                    PlayerIcons.Shuffle, if (now.shuffle) "Shuffle on" else "Shuffle off",
                    tint = if (now.shuffle) Accent else TextDim, modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        RoundIconButton(PlayerIcons.Previous, "Previous", onPrevious, size = 56, filled = false)
        Spacer(Modifier.width(8.dp))
        RoundIconButton(
            if (now.isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
            if (now.isPlaying) "Pause" else "Play", onPlayPause, size = 64,
        )
        Spacer(Modifier.width(8.dp))
        RoundIconButton(PlayerIcons.Next, "Next", onNext, size = 56, filled = false)
        Spacer(Modifier.width(8.dp))
        val station = now.station
        IconButton(onClick = { station?.let(model::toggleFavorite) }, enabled = station != null, modifier = Modifier.size(56.dp)) {
            val fav = model.isFavorite(station?.id)
            Icon(
                if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorite",
                tint = if (fav) Color(0xFFFF5370) else Color.White, modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, size: Int = 56, filled: Boolean = true,
) {
    Box(
        modifier.size(size.dp).clip(CircleShape)
            .background(if (filled) Color.White.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size((size * 0.55).dp))
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun KeyLogDialog(onClose: () -> Unit) {
    val log by KeyLog.entries.collectAsState()
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp)
                .clip(RoundedCornerShape(16.dp)).background(Surface1).padding(20.dp)
        ) {
            Text("Wheel button log", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Press the wheel buttons; each press shows up here.", fontSize = 15.sp, color = TextDim)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(log) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFF80CBC4))
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close", fontSize = 18.sp) }
        }
    }
}
