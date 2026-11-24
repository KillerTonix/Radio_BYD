package dev.wheelradio

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.util.Locale

// ---------- Theme ----------

/** The app's palette. Dark is the original look; Light is for bright daylight. */
@Immutable
data class AppColors(
    val bg: Color,
    val surface: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val textDim: Color,
    val warning: Color,
    val placeholder: Color,
    val logText: Color,
    val heart: Color,
    /** The dark line between the list and the mini player. */
    val divider: Color,
    val dark: Boolean,
)

private val DarkColors = AppColors(
    bg = Color(0xFF141B26), surface = Color(0xFF1E2735), accent = Color(0xFF29B6F6), onAccent = Color(0xFF141B26),
    text = Color.White, textDim = Color(0xFFB0BEC5), warning = Color(0xFFFFB74D), placeholder = Color(0xFF2E3A4D),
    logText = Color(0xFF80CBC4), heart = Color(0xFFFF5370), divider = Color(0xFF05080D), dark = true,
)

private val LightColors = AppColors(
    bg = Color(0xFFF2F4F7), surface = Color.White, accent = Color(0xFF0277BD), onAccent = Color.White,
    text = Color(0xFF17202B), textDim = Color(0xFF526070), warning = Color(0xFFB45309), placeholder = Color(0xFFDCE3EB),
    logText = Color(0xFF00695C), heart = Color(0xFFE0245E), divider = Color(0xFF3A4654), dark = false,
)

private val LocalAppColors = staticCompositionLocalOf { DarkColors }

private val Bg: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.bg
private val Surface1: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.surface
private val Accent: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.accent
private val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.onAccent
private val TextMain: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.text
private val TextDim: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.textDim
private val Warning: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.warning
private val Placeholder: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.placeholder
private val LogText: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.logText
private val Heart: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.heart
private val DividerColor: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.divider

/** Country tiles keep their coloured gradients (with white text) in both themes. */
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

/** Dark for [ThemeMode.DARK]; for AUTO, at night (19:00–7:00) or whenever the car's system is in night mode. */
@Composable
private fun rememberDarkTheme(mode: ThemeMode): Boolean {
    val systemDark = isSystemInDarkTheme()
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(mode) {
        while (mode == ThemeMode.AUTO) {
            hour = LocalTime.now().hour
            delay(60_000)
        }
    }
    return when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> systemDark || hour >= 19 || hour < 7
    }
}

/**
 * Applies the user's language, screen size and theme to everything inside. The language swaps the resources
 * Compose reads strings from (no activity restart, so playback and the current screen are untouched); the size
 * scales the density, so layout, text and touch targets grow together.
 */
@Composable
private fun AppEnvironment(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = rememberDarkTheme(settings.theme) // read before the configuration is replaced below
    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val language = settings.language
    val (config, context) = remember(baseContext, baseConfig, language) {
        val cfg = Configuration(baseConfig).apply { setLocale(language.locale) }
        val res = baseContext.createConfigurationContext(cfg).resources
        // Still the activity underneath (startActivity, permissions), only with localized resources.
        cfg to object : ContextWrapper(baseContext) {
            override fun getResources() = res
        }
    }
    val density = LocalDensity.current
    val scale = settings.uiSize.scale
    val scaled = remember(density, scale) { Density(density.density * scale, density.fontScale) }
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides config,
        LocalDensity provides scaled,
        LocalAppColors provides colors,
    ) {
        val scheme = if (dark) darkColorScheme(primary = colors.accent, background = colors.bg, surface = colors.surface)
        else lightColorScheme(primary = colors.accent, background = colors.bg, surface = colors.surface)
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The locale the UI is shown in (for country names and upper-casing). */
@Composable
@ReadOnlyComposable
private fun uiLocale(): Locale = LocalConfiguration.current.locales[0]

// ---------- App ----------

@Composable
fun WheelRadioApp(
    model: AppModel,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    AppEnvironment(model.settings) {
        BackHandler(enabled = model.openCountry != null) { model.openCountry = null }
        // Every touch anywhere (seen first, never consumed) restarts the idle time before the big player.
        LaunchedEffect(model) {
            while (true) {
                delay(1_000)
                if (model.bigPlayerDue()) model.bigPlayer = true
            }
        }
        Box(
            Modifier.fillMaxSize().pointerInput(model) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        model.onUserTouch()
                    }
                }
            }
        ) {
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
                                    stringResource(t.title).uppercase(uiLocale()), fontSize = 18.sp,
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
                // A few pixels of dark line, so the mini player doesn't run into the last list row.
                Box(Modifier.fillMaxWidth().height(4.dp).background(DividerColor))
                MiniPlayer(model, onPlayPause, onNext, onPrevious)
            }
            if (model.bigPlayer) BigPlayer(model, onPlayPause, onNext, onPrevious)
            // Overlays in the same window, so wheel keys keep reaching MainActivity while they are open.
            if (model.showSettings) SettingsScreen(model)
            if (model.showKeyLog) KeyLogDialog { model.showKeyLog = false }
        }
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
            modifier = Modifier.weight(1f).onFocusChanged { model.searchFocused = it.isFocused },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint), fontSize = 18.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (model.query.isNotEmpty()) IconButton(onClick = { model.updateQuery("") }) {
                    Icon(Icons.Filled.Clear, stringResource(R.string.clear_search))
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        IconButton(onClick = { model.showSettings = true }, modifier = Modifier.padding(start = 8.dp).size(56.dp)) {
            Icon(Icons.Filled.Settings, stringResource(R.string.settings), tint = TextDim, modifier = Modifier.size(32.dp))
        }
    }
}

// ---------- Radio (by countries) ----------

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
                Text(stringResource(R.string.countries_error), color = TextDim, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = model::loadCountries) {
                    Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.try_again))
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
    val name = c.displayName(uiLocale())
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
                    name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                pluralStringResource(R.plurals.station_count, c.stationCount, c.stationCount),
                fontSize = 17.sp, color = Color.White.copy(alpha = 0.85f),
            )
        }
        RoundIconButton(
            PlayerIcons.Play, stringResource(R.string.play_country, name), onPlay,
            Modifier.align(Alignment.BottomEnd), size = 44, tint = Color.White,
        )
    }
}

@Composable
private fun CountryStations(model: AppModel, country: Country) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.openCountry = null }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = TextMain, modifier = Modifier.size(30.dp))
            }
            Text(
                "${country.flag}  ${country.displayName(uiLocale())}",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextMain,
            )
        }
        when {
            model.countryLoading -> Centered { CircularProgressIndicator() }
            model.countryError != null -> Centered { Text(stringResource(R.string.stations_error), color = TextDim, fontSize = 18.sp) }
            else -> StationList(model, model.countryStations, stringResource(R.string.no_stations))
        }
    }
}

@Composable
private fun SearchResults(model: AppModel, q: String) {
    val locale = uiLocale()
    val countryHits = model.countries
        .filter { it.name.contains(q, ignoreCase = true) || it.displayName(locale).contains(q, ignoreCase = true) }
        .take(6)
    Column(Modifier.fillMaxSize()) {
        if (countryHits.isNotEmpty()) {
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                countryHits.forEach { c ->
                    AssistChip(
                        onClick = { model.updateQuery(""); model.showCountry(c) },
                        label = { Text("${c.flag} ${c.displayName(locale)}", fontSize = 18.sp) },
                    )
                }
            }
        }
        if (model.searching && model.searchResults.isEmpty()) Centered { CircularProgressIndicator() }
        else StationList(model, model.searchResults, stringResource(R.string.no_match, q))
    }
}

// ---------- Favorites ----------

@Composable
private fun FavoritesTab(model: AppModel) {
    val q = model.query.trim()
    val songsView = model.showFavoriteSongs
    // Radio stations and songs are kept apart, so tapping one queues only its own kind and the wheel's
    // Next / Previous never jumps from a radio station into a song (or back).
    val all = if (songsView) model.favoriteSongs else model.favoriteRadios
    val list = if (q.isEmpty()) all else all.filter { it.name.contains(q, true) || (songsView && it.subtitle.contains(q, true)) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChoicePill(stringResource(R.string.fav_section_radio, model.favoriteRadios.size), !songsView) {
                model.showFavoriteSongs = false
            }
            ChoicePill(stringResource(R.string.fav_section_songs, model.favoriteSongs.size), songsView) {
                model.showFavoriteSongs = true
            }
            Spacer(Modifier.width(12.dp))
            // Shuffle only applies to songs.
            if (songsView && list.size > 1) Button(onClick = { model.playShuffled(list) }) {
                Icon(PlayerIcons.Shuffle, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.shuffle_favorite_songs, list.size), fontSize = 17.sp)
            }
        }
        StationList(
            model, list,
            stringResource(if (songsView) R.string.favorite_songs_empty else R.string.favorites_empty),
        )
    }
}

/** A selectable pill: the Radio / Songs switch in Favorites, and the choices in Settings. */
@Composable
private fun ChoicePill(label: String, selected: Boolean, role: Role = Role.Tab, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) Accent else Surface1)
            .selectable(selected = selected, role = role, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 18.sp, fontWeight = FontWeight.Medium,
            color = if (selected) OnAccent else TextDim,
        )
    }
}

// ---------- Music (local & USB) ----------

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
                Text(stringResource(if (granted) R.string.rescan else R.string.allow_music), fontSize = 17.sp)
            }
            if (files.isNotEmpty()) Button(onClick = { model.playShuffled(files) }) {
                Icon(PlayerIcons.Shuffle, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.shuffle_all, files.size), fontSize = 17.sp)
            }
        }
        when {
            model.localScanning -> Centered { CircularProgressIndicator() }
            else -> StationList(
                model, files,
                stringResource(
                    if (!granted && model.musicFolder == null) R.string.music_needs_access else R.string.music_empty
                ),
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
            BtCover(220)
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.bt_from_phone, bt.source), fontSize = 16.sp, color = TextDim)
                Spacer(Modifier.height(8.dp))
                Text(
                    bt.title.ifBlank { stringResource(R.string.nothing_playing) }, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                    color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (bt.artist.isNotBlank()) Text(bt.artist, fontSize = 20.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(PlayerIcons.Previous, stringResource(R.string.bt_previous_song), { bt.previous() }, size = 80, filled = false)
                    Spacer(Modifier.width(16.dp))
                    RoundIconButton(
                        if (bt.isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                        stringResource(if (bt.isPlaying) R.string.pause else R.string.play),
                        { if (bt.isPlaying) bt.pause() else { model.pauseRadio(); bt.play() } },
                        size = 96,
                    )
                    Spacer(Modifier.width(16.dp))
                    RoundIconButton(PlayerIcons.Next, stringResource(R.string.bt_next_song), { bt.next() }, size = 80, filled = false)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(if (bt.active) R.string.bt_wheel_active else R.string.bt_press_play),
                    fontSize = 15.sp, color = TextDim,
                )
                if (bt.title.isBlank()) {
                    if (bt.needsAccess) {
                        // The car's Bluetooth music app may be playing, but its session is hidden without access.
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.bt_setup_text), fontSize = 15.sp, color = Warning)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { openNotificationAccess(context) }) { Text(stringResource(R.string.open_settings)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    CarBluetoothAppButton(model)
                }
            }
        }
        BluetoothBridge.Status.SEARCHING -> Centered { CircularProgressIndicator() }
        BluetoothBridge.Status.NOT_FOUND -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.bt_not_found), fontSize = 22.sp, color = TextMain)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.bt_not_found_hint), fontSize = 17.sp, color = TextDim)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { bt.refresh() }) {
                    Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.retry))
                }
                Spacer(Modifier.height(12.dp))
                CarBluetoothAppButton(model)
            }
        }
        BluetoothBridge.Status.NEEDS_ACCESS -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.bt_setup_title), fontSize = 22.sp, color = TextMain, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.bt_setup_text), fontSize = 17.sp, color = TextDim)
                Spacer(Modifier.height(8.dp))
                Text(
                    "adb shell cmd notification allow_listener dev.wheelradio/dev.wheelradio.MediaListener",
                    fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = LogText,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { openNotificationAccess(context) }) { Text(stringResource(R.string.open_settings)) }
                    OutlinedButton(onClick = { bt.refresh() }) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

private fun openNotificationAccess(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Nothing playing usually means the car hasn't switched its audio source to Bluetooth, so the phone
 * has no media-audio device to send to. Opening the car's own Bluetooth music app does that switch.
 */
@Composable
private fun CarBluetoothAppButton(model: AppModel) {
    val context = LocalContext.current
    var choices by remember { mutableStateOf<List<BluetoothBridge.CarApp>?>(null) }
    Column {
        OutlinedButton(onClick = {
            model.pauseRadio()
            val apps = BluetoothBridge.findCarBluetoothApps(context)
            if (apps.size == 1) BluetoothBridge.openCarApp(context, apps[0]) else choices = apps
        }) { Text(stringResource(R.string.bt_open_car_app), fontSize = 17.sp) }
        Text(stringResource(R.string.bt_open_car_app_hint), fontSize = 14.sp, color = TextDim)
        choices?.let { apps ->
            Spacer(Modifier.height(8.dp))
            if (apps.isEmpty()) Text(stringResource(R.string.bt_car_app_not_found), fontSize = 15.sp, color = TextDim)
            apps.take(6).forEach { app ->
                TextButton(onClick = { choices = null; BluetoothBridge.openCarApp(context, app) }) {
                    Text("${app.label}  ·  ${app.packageName}", fontSize = 15.sp)
                }
            }
        }
    }
}

// ---------- Settings ----------

/**
 * Full-screen page over the app, drawn in the activity's own window like the key log, so wheel keys keep
 * working while it is open. Every change applies at once.
 */
@Composable
private fun SettingsScreen(model: AppModel) {
    val s = model.settings
    var pickCountry by remember { mutableStateOf(false) }
    BackHandler { model.showSettings = false }
    Column(
        Modifier.fillMaxSize().background(Bg)
            .pointerInput(Unit) { detectTapGestures { } } // nothing underneath reacts to taps
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.showSettings = false }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = TextMain, modifier = Modifier.size(30.dp))
            }
            Text(stringResource(R.string.settings), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextMain)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            SettingsSection(stringResource(R.string.settings_language)) {
                ChoiceRow {
                    Language.entries.forEach { l ->
                        ChoicePill(l.label, s.language == l, Role.RadioButton) { s.updateLanguage(l) }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_ui_size), stringResource(R.string.settings_ui_size_hint)) {
                ChoiceRow {
                    UiSize.entries.forEach { size ->
                        val label = when (size) {
                            UiSize.SMALL -> R.string.ui_size_small
                            UiSize.NORMAL -> R.string.ui_size_normal
                            UiSize.LARGE -> R.string.ui_size_large
                            UiSize.XLARGE -> R.string.ui_size_xlarge
                        }
                        ChoicePill(stringResource(label), s.uiSize == size, Role.RadioButton) { s.updateUiSize(size) }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_theme), stringResource(R.string.theme_auto_hint)) {
                ChoiceRow {
                    ThemeMode.entries.forEach { mode ->
                        val label = when (mode) {
                            ThemeMode.DARK -> R.string.theme_dark
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.AUTO -> R.string.theme_auto
                        }
                        ChoicePill(stringResource(label), s.theme == mode, Role.RadioButton) { s.updateTheme(mode) }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_playback)) {
                SwitchRow(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_hint), s.autoplay) {
                    s.updateAutoplay(it)
                }
                SwitchRow(
                    stringResource(R.string.settings_weak_coverage), stringResource(R.string.settings_weak_coverage_hint),
                    s.weakCoverage,
                ) { s.updateWeakCoverage(it) }
                SwitchRow(
                    stringResource(R.string.settings_big_player), stringResource(R.string.settings_big_player_hint),
                    s.bigPlayer,
                ) { s.updateBigPlayer(it) }
            }
            SettingsSection(stringResource(R.string.settings_home_country), stringResource(R.string.settings_home_country_hint)) {
                val home = Country(s.homeCountry, s.homeCountry, 0)
                OutlinedButton(onClick = { pickCountry = true }) {
                    Text("${home.flag}  ${home.displayName(uiLocale())}", fontSize = 18.sp)
                }
            }
            SettingsSection(stringResource(R.string.settings_diagnostics)) {
                OutlinedButton(onClick = { model.showKeyLog = true }) {
                    Text(stringResource(R.string.open_keylog), fontSize = 17.sp)
                }
            }
            Text(stringResource(R.string.settings_version, appVersion()), fontSize = 15.sp, color = TextDim)
        }
    }
    if (pickCountry) CountryPicker(model, onDismiss = { pickCountry = false })
}

@Composable
private fun SettingsSection(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
        if (hint != null) Text(hint, fontSize = 15.sp, color = TextDim)
        content()
    }
}

@Composable
private fun ChoiceRow(content: @Composable RowScope.() -> Unit) =
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = content)

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().widthIn(max = 1000.dp)
            .clip(RoundedCornerShape(12.dp)).background(Surface1)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, color = TextMain, fontWeight = FontWeight.Medium)
            Text(hint, fontSize = 15.sp, color = TextDim)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The pinned countries, then all the others the directory knows, alphabetically in the UI language. */
@Composable
private fun CountryPicker(model: AppModel, onDismiss: () -> Unit) {
    val locale = uiLocale()
    val pinned = listOf("AM", "RU", "DE", "US", "GE", "FR")
    val known = (pinned + model.countries.map { it.code }).distinct().map { Country(it, it, 0) }
    val list = known.filter { it.code in pinned }.sortedBy { pinned.indexOf(it.code) } +
        known.filter { it.code !in pinned }.sortedBy { it.displayName(locale) }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(24.dp).heightIn(max = 640.dp)
                .clip(RoundedCornerShape(16.dp)).background(Surface1)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp)
        ) {
            Text(stringResource(R.string.choose_country), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(list, key = { it.code }) { c ->
                    val selected = c.code == model.settings.homeCountry
                    Text(
                        "${c.flag}  ${c.displayName(locale)}",
                        fontSize = 19.sp,
                        color = if (selected) Accent else TextMain,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton) { model.setHomeCountry(c.code); onDismiss() }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cancel), fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
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
                s.name, fontSize = 21.sp, color = if (playing) Accent else TextMain,
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
                stringResource(if (favorite) R.string.remove_favorite else R.string.add_favorite),
                tint = if (favorite) Heart else TextDim,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun Artwork(url: String, name: String, size: Int, data: Any? = null, corner: Int = 10) {
    val fallback = @Composable {
        Box(
            Modifier.fillMaxSize().background(Placeholder),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), fontSize = (size / 2.2).sp, color = TextMain, fontWeight = FontWeight.Bold)
        }
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(corner.dp))) {
        if (url.isBlank() && data == null) fallback()
        else SubcomposeAsyncImage(
            model = data ?: url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(Color.White),
            loading = { fallback() }, error = { fallback() },
        )
    }
}

/** The phone's cover art, or "BT" until the source sends one. */
@Composable
private fun BtCover(size: Int, corner: Int = 20) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(corner.dp)).background(Placeholder),
        contentAlignment = Alignment.Center,
    ) {
        val art = BluetoothBridge.art
        if (art != null) androidx.compose.foundation.Image(
            art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        ) else Text("BT", fontSize = (size / 3.4).sp, color = TextDim, fontWeight = FontWeight.Bold)
    }
}

/** What the mini player shows: our radio, a local / USB song, or the phone over Bluetooth. */
private enum class Source(val icon: ImageVector, @androidx.annotation.StringRes val label: Int) {
    RADIO(PlayerIcons.Radio, R.string.tab_radio),
    MUSIC(PlayerIcons.MusicNote, R.string.tab_music),
    BLUETOOTH(PlayerIcons.Bluetooth, R.string.tab_bluetooth),
}

/**
 * The bar at the bottom. It follows Bluetooth while that is the source the driver chose (Bluetooth tab or the
 * car's Bluetooth player), otherwise our own player; a small icon in front of the title says which.
 */
@Composable
private fun MiniPlayer(model: AppModel, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    val now = model.now
    val bt = BluetoothBridge
    val useBt = bt.inUse
    val source = when {
        useBt -> Source.BLUETOOTH
        now.station?.local == true -> Source.MUSIC
        now.station != null -> Source.RADIO
        else -> null
    }
    val title = if (useBt) bt.title else now.title
    val playing = if (useBt) bt.isPlaying else now.isPlaying
    Row(
        Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (useBt) BtCover(60, corner = 10) else Artwork(now.artwork, now.title.ifBlank { "?" }, 60, now.artworkData)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (source != null) {
                    Icon(source.icon, stringResource(source.label), tint = Accent, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    title.ifBlank { stringResource(R.string.nothing_playing) }, fontSize = 22.sp, color = TextMain,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // A problem (retrying, no internet, station unavailable) beats "Connecting…" and the song title,
            // so a silent radio never just looks paused.
            val sub = when {
                useBt -> bt.artist
                now.status.isNotBlank() -> now.status
                now.buffering -> stringResource(R.string.connecting)
                else -> now.nowPlaying
            }
            val subColor = if (!useBt && now.status.isNotBlank()) Warning else TextDim
            if (sub.isNotBlank()) Text(sub, fontSize = 16.sp, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!useBt && now.canShuffle) {
            IconButton(onClick = { model.setShuffle(!now.shuffle) }, modifier = Modifier.size(56.dp)) {
                Icon(
                    PlayerIcons.Shuffle, stringResource(if (now.shuffle) R.string.shuffle_on else R.string.shuffle_off),
                    tint = if (now.shuffle) Accent else TextDim, modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        RoundIconButton(
            PlayerIcons.Previous, stringResource(R.string.previous),
            { if (useBt) bt.previous() else onPrevious() }, size = 56, filled = false,
        )
        Spacer(Modifier.width(8.dp))
        RoundIconButton(
            if (playing) PlayerIcons.Pause else PlayerIcons.Play,
            stringResource(if (playing) R.string.pause else R.string.play),
            {
                when {
                    !useBt -> onPlayPause()
                    bt.isPlaying -> bt.pause()
                    else -> { model.pauseRadio(); bt.play() }
                }
            },
            size = 64,
        )
        Spacer(Modifier.width(8.dp))
        RoundIconButton(PlayerIcons.Next, stringResource(R.string.next), { if (useBt) bt.next() else onNext() }, size = 56, filled = false)
        Spacer(Modifier.width(8.dp))
        // Phone songs can't be favorites (there is nothing to replay them from), so the heart is off for Bluetooth.
        val station = if (useBt) null else now.station
        IconButton(onClick = { station?.let(model::toggleFavorite) }, enabled = station != null, modifier = Modifier.size(56.dp)) {
            val fav = model.isFavorite(station?.id)
            Icon(
                // Same wording as the list hearts, so TalkBack and tests can tell the state.
                if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                stringResource(if (fav) R.string.remove_favorite else R.string.add_favorite),
                tint = if (fav) Heart else TextMain, modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * Full-screen "now playing", like the car's own music screen. Appears after [AppSettings.BIG_PLAYER_IDLE_MS]
 * without a touch; a tap anywhere outside the buttons (or Back) returns to the list. Wheel keys keep working
 * underneath, and it follows Bluetooth when that is what plays through the app.
 */
@Composable
private fun BigPlayer(model: AppModel, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    val bt = BluetoothBridge
    val useBt = bt.inUse
    val now = model.now
    if (if (useBt) bt.title.isBlank() else now.station == null) {
        LaunchedEffect(Unit) { model.bigPlayer = false }
        return
    }
    BackHandler { model.bigPlayer = false }

    val title = if (useBt) bt.title else now.title
    val sub = when {
        useBt -> bt.artist
        now.status.isNotBlank() -> now.status
        now.buffering -> stringResource(R.string.connecting)
        now.nowPlaying.isNotBlank() -> now.nowPlaying
        else -> now.station?.subtitle.orEmpty()
    }
    val subColor = if (!useBt && now.status.isNotBlank()) Warning else TextDim
    val playing = if (useBt) bt.isPlaying else now.isPlaying

    var pos by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(useBt) {
        while (true) {
            pos = if (useBt) bt.positionMs() to bt.durationMs else model.position()
            delay(500)
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Bg)
            .pointerInput(Unit) { detectTapGestures { model.bigPlayer = false } }
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        val portrait = maxHeight > maxWidth
        val artSize = (if (portrait) minOf(maxWidth.value * 0.7f, 420f) else minOf(maxHeight.value * 0.6f, 380f)).toInt()
        val art = @Composable {
            if (useBt) BtCover(artSize) else Artwork(now.artwork, title.ifBlank { "?" }, artSize, now.artworkData, corner = 20)
        }
        val info = @Composable { mod: Modifier ->
            Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title.ifBlank { stringResource(R.string.nothing_playing) }, fontSize = 40.sp, lineHeight = 48.sp,
                    fontWeight = FontWeight.Bold, color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        sub, fontSize = 24.sp, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                val (position, duration) = pos
                if (duration > 0) {
                    Spacer(Modifier.height(36.dp))
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Row(Modifier.widthIn(max = 900.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(dragging?.toLong() ?: position), fontSize = 18.sp, color = TextDim)
                        Slider(
                            value = (dragging ?: position.toFloat()).coerceIn(0f, duration.toFloat()),
                            valueRange = 0f..duration.toFloat(),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                dragging?.let { v -> if (useBt) bt.seekTo(v.toLong()) else model.seekTo(v.toLong()) }
                                dragging = null
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        )
                        Text(formatTime(duration), fontSize = 18.sp, color = TextDim)
                    }
                    Spacer(Modifier.height(28.dp))
                } else Spacer(Modifier.height(48.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(
                        PlayerIcons.Previous, stringResource(R.string.previous),
                        { if (useBt) bt.previous() else onPrevious() }, size = 96,
                    )
                    Spacer(Modifier.width(56.dp))
                    Box(
                        Modifier.size(120.dp).clip(CircleShape).background(Accent)
                            .clickable { if (useBt) bt.playPause() else onPlayPause() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (playing) PlayerIcons.Pause else PlayerIcons.Play,
                            stringResource(if (playing) R.string.pause else R.string.play),
                            tint = OnAccent, modifier = Modifier.size(64.dp),
                        )
                    }
                    Spacer(Modifier.width(56.dp))
                    RoundIconButton(
                        PlayerIcons.Next, stringResource(R.string.next),
                        { if (useBt) bt.next() else onNext() }, size = 96,
                    )
                }
            }
        }
        if (portrait) Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            art()
            Spacer(Modifier.height(40.dp))
            info(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            info(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
            art()
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.ROOT, "%02d:%02d", m, sec)
}

@Composable
private fun RoundIconButton(
    icon: ImageVector, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, size: Int = 56, filled: Boolean = true, tint: Color = TextMain,
) {
    Box(
        modifier.size(size.dp).clip(CircleShape)
            .background(if (filled) tint.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size((size * 0.55).dp))
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

/**
 * Drawn inside the activity's own window rather than as a Dialog: a Dialog is a separate window that takes key
 * focus, so wheel keys went to it instead of MainActivity.dispatchKeyEvent. CHANNEL_UP/DOWN were then lost and
 * the [Activity] route never showed up in the very log meant to reveal it. Closes on Close, Back, or a tap outside.
 */
@Composable
private fun KeyLogDialog(onClose: () -> Unit) {
    val log by KeyLog.entries.collectAsState()
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // Wide and tall, so a Bluetooth diagnostics dump can be photographed in a few screens.
            Modifier.widthIn(max = 1500.dp).fillMaxWidth().padding(24.dp).heightIn(max = 900.dp)
                .clip(RoundedCornerShape(16.dp)).background(Surface1)
                .pointerInput(Unit) { detectTapGestures { } } // taps on the panel don't close it
                .padding(20.dp)
        ) {
            Text(stringResource(R.string.keylog_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Text(stringResource(R.string.keylog_hint), fontSize = 15.sp, color = TextDim)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(log) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = LogText)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Dumps Bluetooth profiles, media sessions and the car players' components into this log.
                OutlinedButton(onClick = { BluetoothBridge.diagnose() }) {
                    Text(stringResource(R.string.bt_diagnostics), fontSize = 16.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(stringResource(R.string.close), fontSize = 18.sp) }
            }
        }
    }
}
