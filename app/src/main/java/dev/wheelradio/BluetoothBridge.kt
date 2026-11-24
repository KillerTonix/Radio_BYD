package dev.wheelradio

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Remote control for the phone's music playing over the car's Bluetooth.
 * The audio itself stays with the car's Bluetooth stack; we only show what's playing and send
 * play / pause / next / previous / seek. Sources, all watched at once:
 *  - Android's AVRCP controller MediaBrowserService (com.android.bluetooth), used by AOSP car media apps.
 *  - Every other app's active media session (e.g. BYD's Bluetooth music app), which needs notification access.
 *
 * On BYD DiLink the AVRCP service accepts the connection but stays empty: BYD's own Bluetooth music app
 * plays the phone. So the AVRCP service is never trusted just because it connected; the source that is
 * actually playing (or at least has a song) wins, and we switch whenever that changes.
 */
object BluetoothBridge {
    enum class Status { SEARCHING, CONNECTED, NEEDS_ACCESS, NOT_FOUND }

    var status by mutableStateOf(Status.SEARCHING)
        private set
    var source by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set
    var art by mutableStateOf<Bitmap?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** Song length in ms, 0 when the source doesn't say. */
    var durationMs by mutableStateOf(0L)
        private set
    /** Other apps' media sessions are hidden from us until notification access is granted. */
    var needsAccess by mutableStateOf(false)
        private set

    private var activeState by mutableStateOf(false)

    /**
     * True after the user played Bluetooth from our app: wheel keys, the mini player and the big player then
     * follow Bluetooth. Saved, so a restarted app comes back to Bluetooth instead of starting the radio.
     */
    var active: Boolean
        get() = activeState
        set(value) {
            if (activeState == value) return
            activeState = value
            if (!value) cancelActivation()
            appContext?.let { prefs(it).edit().putBoolean(KEY_ACTIVE, value).apply() }
        }

    /** Bluetooth is the source the app shows and controls (it may have no song yet). */
    val inUse: Boolean get() = active && status == Status.CONNECTED

    /** Bluetooth is what the user listens to through our app, with something to show. */
    val showsBluetooth: Boolean get() = inUse && title.isNotBlank()

    private const val KEY_ACTIVE = "bt_active"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE)

    /** Bluetooth was the source when the app last ran (readable before [start], e.g. by RadioService's auto-play). */
    fun wasActive(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ACTIVE, false)

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var browser: MediaBrowserCompat? = null
    private var avrcpController: MediaControllerCompat? = null
    private var avrcpTried = false
    private var sessionControllers: List<MediaControllerCompat> = emptyList()
    private var controller: MediaControllerCompat? = null
    private var lastState: PlaybackStateCompat? = null
    private var sessionManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val avrcp = ComponentName(
        "com.android.bluetooth", "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService"
    )

    /** Feeds the screen from the chosen source. */
    private val callback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = updateMeta(metadata)
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = updateState(state)
        override fun onSessionDestroyed() {
            detach()
            rescan()
        }
    }

    /** On every candidate: when one starts playing or gets a song, choose again. */
    private val watcher = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            choose()
            if (art == null) borrowArt() // e.g. the car's own player got the cover after AVRCP sent the title
        }
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = choose()
    }

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        activeState = wasActive(context)
        refresh()
        if (activeState) {
            KeyLog.add("BT", "Bluetooth was the last source")
            // Give the AVRCP connection and the session scan a moment, then make Bluetooth play again.
            main.postDelayed({ if (active && !isPlaying) activate() }, AUTO_START_DELAY_MS)
        }
    }

    /** Our screen is in front (set by MainActivity); only then may the auto-start bring the car's player up. */
    @Volatile var uiVisible = false

    /** Look for Bluetooth music again (opening the tab, Retry, or after granting access). */
    fun refresh() {
        val ctx = appContext ?: return
        if (controller == null) status = Status.SEARCHING
        if (!avrcpTried) connectAvrcp(ctx)
        scanSessions(ctx, log = true)
        choose()
    }

    private fun rescan() {
        appContext?.let { scanSessions(it, log = true) }
        choose()
    }

    private fun connectAvrcp(ctx: Context) {
        avrcpTried = true
        val b = MediaBrowserCompat(ctx, avrcp, object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val token = browser?.sessionToken ?: return
                avrcpController = MediaControllerCompat(ctx, token).also { it.registerCallback(watcher, main) }
                KeyLog.add("BT", "AVRCP controller service connected")
                choose()
            }
            override fun onConnectionFailed() {
                KeyLog.add("BT", "AVRCP controller service not available")
                choose()
            }
            override fun onConnectionSuspended() {
                avrcpController?.unregisterCallback(watcher)
                avrcpController = null
                avrcpTried = false
                choose()
            }
        }, null)
        browser = b
        runCatching { b.connect() }.onFailure { avrcpTried = true }
    }

    private fun scanSessions(ctx: Context, log: Boolean) {
        val msm = sessionManager ?: (ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
            .also { sessionManager = it }
        val listenerComponent = ComponentName(ctx, MediaListener::class.java)
        val listenerDeclared = runCatching { ctx.packageManager.getServiceInfo(listenerComponent, 0) }.isSuccess
        if (!listenerDeclared) return // phone build: no notification listener, only the AVRCP route
        val sessions = try {
            msm.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            if (!needsAccess) KeyLog.add("BT", "no notification access: other apps' media sessions are hidden")
            needsAccess = true
            return
        }
        needsAccess = false
        if (sessionsListener == null) {
            val l = MediaSessionManager.OnActiveSessionsChangedListener { main.post { rescan() } }
            runCatching { msm.addOnActiveSessionsChangedListener(l, listenerComponent, main) }
            sessionsListener = l
        }
        sessionControllers.forEach { it.unregisterCallback(watcher) }
        sessionControllers = sessions
            .filter { it.packageName != ctx.packageName }
            .map { MediaControllerCompat(ctx, MediaSessionCompat.Token.fromToken(it.sessionToken)) }
        sessionControllers.forEach { it.registerCallback(watcher, main) }
        if (log) KeyLog.add(
            "BT", "media sessions: " + sessionControllers.joinToString { c ->
                "${c.packageName} [${stateName(c.playbackState?.state)}] ${c.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()}"
            }.ifEmpty { "none" }
        )
    }

    /** Picks the best source: playing beats having a song, a Bluetooth app beats any other. */
    private fun choose() {
        val ctx = appContext ?: return
        val candidates = listOfNotNull(avrcpController, carBrowserController) + sessionControllers
        val best = candidates.maxByOrNull { score(it) }
        if (best == null || (score(best) <= 0 && best !== avrcpController)) {
            // Nothing that looks like music: only keep a non-Bluetooth app if it has something to show.
            if (controller != null) detach()
            status = when {
                needsAccess && avrcpController == null -> Status.NEEDS_ACCESS
                else -> Status.NOT_FOUND
            }
            return
        }
        if (best === controller) return
        val label = if (best === avrcpController) "Bluetooth (AVRCP)" else runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(best.packageName, 0)).toString()
        }.getOrDefault(best.packageName)
        attach(best, label)
        KeyLog.add("BT", "using $label (${best.packageName}) [${stateName(best.playbackState?.state)}]")
    }

    private fun score(c: MediaControllerCompat): Int {
        val state = c.playbackState?.state
        val playing = state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING
        val hasSong = !c.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).isNullOrBlank()
        val pkg = c.packageName.lowercase()
        val bluetoothApp = c === avrcpController || "bluetooth" in pkg || "btmusic" in pkg || ".bt" in pkg
        val callApp = "call" in pkg || "dialer" in pkg || "phone" in pkg
        if (callApp) return -1
        return (if (playing) 100 else 0) + (if (hasSong) 10 else 0) +
            (if (bluetoothApp && c !== avrcpController) 2 else 0) + (if (c === avrcpController) 1 else 0) -
            (if (!bluetoothApp && !playing && !hasSong) 50 else 0)
    }

    private fun stateName(state: Int?) = when (state) {
        PlaybackStateCompat.STATE_PLAYING -> "playing"
        PlaybackStateCompat.STATE_PAUSED -> "paused"
        PlaybackStateCompat.STATE_BUFFERING -> "buffering"
        PlaybackStateCompat.STATE_STOPPED -> "stopped"
        PlaybackStateCompat.STATE_NONE, null -> "none"
        else -> "state $state"
    }

    private fun attach(c: MediaControllerCompat, label: String) {
        detach()
        controller = c
        c.registerCallback(callback, main)
        source = label
        status = Status.CONNECTED
        updateMeta(c.metadata)
        updateState(c.playbackState)
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
        status = Status.SEARCHING
        isPlaying = false
        title = ""
        artist = ""
        art = null
        durationMs = 0
        lastState = null
    }

    private fun updateMeta(m: MediaMetadataCompat?) {
        val newTitle = m?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: ""
        val songChanged = newTitle != title
        title = newTitle
        artist = listOfNotNull(
            m?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST),
            m?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        durationMs = m?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0
        val bitmap = bitmapOf(m)
        val uri = artUriOf(m)
        when {
            bitmap != null -> art = bitmap
            uri != null -> if (uri != artUri || art == null) { if (songChanged) art = null; artUri = uri; loadArt(uri, newTitle) }
            else -> { art = null; borrowArt() }
        }
        if (songChanged && newTitle.isNotBlank()) KeyLog.add(
            "BT", "song \"$newTitle\" from ${controller?.packageName}, cover: " + when {
                bitmap != null -> "picture ${bitmap.width}x${bitmap.height}"
                uri != null -> "link $uri"
                art != null -> "borrowed from another app"
                else -> "none"
            }
        )
    }

    /** The cover link last loaded, so the same picture isn't fetched again on every metadata update. */
    private var artUri: String? = null

    private fun bitmapOf(m: MediaMetadataCompat?): Bitmap? = m?.let {
        it.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            ?: it.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
            ?: it.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)
    }

    private fun artUriOf(m: MediaMetadataCompat?): String? = m?.let {
        listOf(
            MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadataCompat.METADATA_KEY_ART_URI,
            MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { key -> it.getString(key)?.takeIf { v -> v.isNotBlank() } }
    }

    /**
     * Android 10's AVRCP controller never gets cover art (that came with Android 11), but the car's own
     * Bluetooth player (BYD Media Center) shows it. So take the picture from any other app's session that
     * plays the same song (visible with notification access).
     */
    private fun borrowArt() {
        val current = controller ?: return
        if (title.isBlank()) return
        val donor = (listOfNotNull(avrcpController, carBrowserController) + sessionControllers)
            .filter { it !== current }
            .firstOrNull { it.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).equals(title, ignoreCase = true) }
            ?: return
        val bitmap = bitmapOf(donor.metadata)
        if (bitmap != null) {
            art = bitmap
            KeyLog.add("BT", "cover for \"$title\" from ${donor.packageName}")
        } else artUriOf(donor.metadata)?.let { loadArt(it, title) }
    }

    /** Reads a cover given as a link (content://, file:// or http) off the main thread, scaled to about 512 px. */
    private fun loadArt(uri: String, forTitle: String) {
        val ctx = appContext ?: return
        Thread {
            val bmp = runCatching {
                val u = Uri.parse(uri)
                val open = {
                    if (u.scheme == "http" || u.scheme == "https") java.net.URL(uri).openStream()
                    else ctx.contentResolver.openInputStream(u)
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
                open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }.onFailure { KeyLog.add("BT", "couldn't load cover $uri: ${it.message}") }.getOrNull()
            if (bmp != null) main.post { if (title == forTitle) art = bmp }
        }.start()
    }

    private fun updateState(s: PlaybackStateCompat?) {
        lastState = s
        isPlaying = s?.state == PlaybackStateCompat.STATE_PLAYING || s?.state == PlaybackStateCompat.STATE_BUFFERING
        if (isPlaying) active = true
    }

    /** Current position in ms, run forward from the source's last report while playing. */
    fun positionMs(): Long {
        val s = lastState ?: return 0
        if (s.position < 0) return 0
        val elapsed = if (s.state == PlaybackStateCompat.STATE_PLAYING && s.lastPositionUpdateTime > 0)
            ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong() else 0
        val pos = s.position + elapsed
        return if (durationMs > 0) pos.coerceIn(0, durationMs) else pos
    }

    /** Play: resumes the phone, and first makes Bluetooth the car's source when it isn't (see [activate]). */
    fun play() = activate()
    fun pause() { cancelActivation(); controller?.transportControls?.pause() }
    fun playPause() = if (isPlaying) pause() else play()
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }

    // ---------- Making Bluetooth the car's audio source ----------

    private const val AUTO_START_DELAY_MS = 2_500L
    private const val STEP_WAIT_MS = 3_000L
    private const val CAR_APP_WAIT_MS = 2_000L
    private const val AVRCP_PACKAGE = "com.android.bluetooth"

    private var activation: Runnable? = null
    /** The car player's MediaBrowserService (e.g. BYD Media Center), once step 3 connected to it. */
    private var carBrowser: MediaBrowserCompat? = null
    private var carBrowserController: MediaControllerCompat? = null

    private class Step(val name: String, val waitMs: Long = STEP_WAIT_MS, val run: () -> Boolean)

    /**
     * Makes Bluetooth the car's audio source without the driver opening the car's player. On the Yuan Plus the
     * phone is connected but silent, and AVRCP stays empty, until something claims the Bluetooth source; Media
     * Center does that when it opens. The steps below run one after another and stop as soon as Bluetooth plays:
     *  1. Play through Android's AVRCP controller (stock Android 10 then takes the audio focus for Bluetooth).
     *  2. Play on the car player's media session (visible with notification access).
     *  3. Play through the car player's MediaBrowserService (needs no notification access).
     *  4. Only while our screen is in front: open the car's player and come back 2 s later.
     * Every step is logged, so one drive shows which one BYD honours.
     */
    fun activate() {
        appContext ?: return
        cancelActivation()
        active = true
        if (isPlaying) return
        KeyLog.add("BT", "making Bluetooth play…")
        val steps = listOf(
            Step("play via AVRCP") { avrcpController?.transportControls?.play() != null },
            Step("play on the car player's media session") {
                val session = sessionControllers.firstOrNull { isCarPlayer(it.packageName) } ?: return@Step false
                KeyLog.add("BT", "  -> ${session.packageName}")
                session.transportControls.play()
                true
            },
            Step("play through the car player's media browser") { playThroughCarBrowser() },
            Step("open the car's player and come back", waitMs = CAR_APP_WAIT_MS + STEP_WAIT_MS) { openCarPlayerAndReturn() },
        )
        runStep(steps, 0)
    }

    private fun runStep(steps: List<Step>, index: Int) {
        activation = null
        when {
            !active -> return
            isPlaying -> {
                KeyLog.add("BT", if (index == 0) "Bluetooth already plays" else "Bluetooth plays: step $index worked")
                return
            }
            index >= steps.size -> {
                KeyLog.add("BT", "Bluetooth still silent after ${steps.size} steps")
                return
            }
        }
        val step = steps[index]
        val tried = runCatching(step.run).getOrElse {
            KeyLog.add("BT", "step ${index + 1} failed: ${it.message}")
            false
        }
        KeyLog.add("BT", "step ${index + 1}/${steps.size}: ${step.name}" + if (tried) "" else " - not available")
        val next = Runnable { runStep(steps, index + 1) }
        activation = next
        main.postDelayed(next, if (tried) step.waitMs else 0)
    }

    private fun cancelActivation() {
        activation?.let { main.removeCallbacks(it) }
        activation = null
    }

    private fun appLabel(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** The car's own Bluetooth player (Media Center on BYD), going by the same rule as the car-app finder. */
    private fun isCarPlayer(pkg: String): Boolean {
        val ctx = appContext ?: return false
        return pkg != ctx.packageName && pkg != AVRCP_PACKAGE && carAppScore(CarApp(appLabel(ctx, pkg), pkg)) == 0
    }

    /** MediaBrowserServices on the head unit (Android's AVRCP service, the car's players, us). */
    private fun mediaBrowserServices(ctx: Context): List<ComponentName> =
        ctx.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }

    private fun playThroughCarBrowser(): Boolean {
        val ctx = appContext ?: return false
        carBrowserController?.let {
            it.transportControls.play()
            return true
        }
        val target = mediaBrowserServices(ctx).firstOrNull { isCarPlayer(it.packageName) } ?: return false
        KeyLog.add("BT", "  -> connecting to ${target.flattenToShortString()}")
        lateinit var b: MediaBrowserCompat
        b = MediaBrowserCompat(ctx, target, object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val c = MediaControllerCompat(ctx, b.sessionToken).also { it.registerCallback(watcher, main) }
                carBrowserController = c
                KeyLog.add("BT", "connected to ${target.packageName}'s media browser [${stateName(c.playbackState?.state)}]")
                c.transportControls.play()
                choose()
            }
            override fun onConnectionFailed() {
                KeyLog.add("BT", "${target.packageName}'s media browser refused the connection")
                carBrowser = null
            }
            override fun onConnectionSuspended() {
                carBrowserController?.unregisterCallback(watcher)
                carBrowserController = null
                carBrowser = null
                choose()
            }
        }, null)
        carBrowser = b
        b.connect()
        return true
    }

    private fun openCarPlayerAndReturn(): Boolean {
        val ctx = appContext ?: return false
        if (!uiVisible) return false // never pull the car's player over another app the driver is using
        // Only the car's Bluetooth player (Media Center / Bluetooth Music), never an online music app like Kuwo.
        val app = findCarBluetoothApps(ctx).firstOrNull { carAppScore(it) == 0 } ?: return false
        KeyLog.add("BT", "  -> opening ${app.label} (${app.packageName})")
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.className ?: return false))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        main.postDelayed({
            // Android 10 lets an app that started an activity in the last 10 s bring itself back to the front.
            runCatching {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }.onSuccess { KeyLog.add("BT", "back to Wheel Radio") }
                .onFailure { KeyLog.add("BT", "couldn't come back to Wheel Radio: ${it.message}") }
            refresh()
        }, CAR_APP_WAIT_MS)
        return true
    }

    // ---------- Diagnostics ----------

    /**
     * Writes everything that tells us how the head unit switches to Bluetooth into the key log: Bluetooth profile
     * states, every media session and MediaBrowserService, and the car players' exported components.
     */
    fun diagnose() {
        val ctx = appContext ?: return
        val pm = ctx.packageManager
        KeyLog.add("Diag", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        KeyLog.add("Diag", runCatching {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return@runCatching "no Bluetooth adapter"
            // A2DP sink (11), AVRCP controller (12) and HFP client (16) are hidden constants on Android 10.
            val profiles = listOf("A2DP" to BluetoothProfile.A2DP, "A2DP sink" to 11, "AVRCP controller" to 12, "HFP client" to 16)
            "Bluetooth ${if (adapter.isEnabled) "on" else "off"}; " +
                profiles.joinToString { (name, id) -> "$name ${connectionName(adapter.getProfileConnectionState(id))}" }
        }.getOrElse { "Bluetooth state unreadable: ${it.message}" })
        KeyLog.add(
            "Diag", "chosen: ${controller?.packageName ?: "none"} ($source), status $status, active $active, " +
                "playing $isPlaying, title \"$title\", cover ${art != null}; AVRCP ${if (avrcpController != null) "connected" else "not connected"}, " +
                "car browser ${carBrowserController?.packageName ?: "not connected"}, notification access ${if (needsAccess) "MISSING" else "ok"}"
        )
        scanSessions(ctx, log = true)
        (listOfNotNull(avrcpController, carBrowserController) + sessionControllers).forEach { c ->
            val m = c.metadata
            val keys = m?.keySet()?.sorted()?.joinToString(",") { it.substringAfterLast('.') }.orEmpty()
            KeyLog.add("Diag", "  ${c.packageName} [${stateName(c.playbackState?.state)}] actions=0x${java.lang.Long.toHexString(c.playbackState?.actions ?: 0)} meta: $keys")
        }
        KeyLog.add("Diag", "media browsers: " + mediaBrowserServices(ctx).joinToString { it.flattenToShortString() }.ifEmpty { "none" })
        val packages = (findCarBluetoothApps(ctx).filter { carAppScore(it) == 0 }.map { it.packageName } + "com.byd.mediacenter")
            .distinct()
        packages.forEach { describePackage(pm, it) }
        KeyLog.add("Diag", "done")
    }

    private fun connectionName(state: Int) = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "off"
    }

    /** The exported activities, services, receivers and providers of [pkg], a few per log line. */
    private fun describePackage(pm: PackageManager, pkg: String) {
        @Suppress("DEPRECATION")
        val info = runCatching {
            pm.getPackageInfo(
                pkg,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
            )
        }.getOrElse {
            KeyLog.add("Diag", "$pkg: not installed")
            return
        }
        KeyLog.add("Diag", "$pkg ${info.versionName}")
        fun short(name: String) = name.removePrefix(pkg)
        listOf(
            "activities" to info.activities?.filter { it.exported }?.map { short(it.name) + (it.permission?.let { p -> " [$p]" } ?: "") },
            "services" to info.services?.filter { it.exported }?.map { short(it.name) + (it.permission?.let { p -> " [$p]" } ?: "") },
            "receivers" to info.receivers?.filter { it.exported }?.map { short(it.name) + (it.permission?.let { p -> " [$p]" } ?: "") },
            "providers" to info.providers?.filter { it.exported }?.map { "${short(it.name)} (${it.authority})" },
        ).forEach { (kind, names) ->
            if (names.isNullOrEmpty()) return@forEach
            names.chunked(8).forEachIndexed { i, chunk ->
                KeyLog.add("Diag", "  $kind${if (i > 0) " (more)" else ""}: ${chunk.joinToString()}")
            }
        }
    }

    data class CarApp(val label: String, val packageName: String, val className: String? = null)

    /**
     * Apps on the head unit that look like the car's Bluetooth music player. Opening it switches the car's
     * audio source to Bluetooth, which is what makes the phone see the car as a media-audio device.
     * Activities are kept apart (one BYD package can hold both "Music" and "Bluetooth Music"), and phone /
     * call apps are left out: "BT Phone" (com.byd.bluetoothcall) used to be offered first.
     */
    fun findCarBluetoothApps(ctx: Context): List<CarApp> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = pm.queryIntentActivities(launcher, 0)
            .map { CarApp(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.packageName != ctx.packageName }
            .distinctBy { it.packageName to it.className }
        val hits = all.filter { carAppScore(it) < 9 }.sortedBy { carAppScore(it) }
        KeyLog.add("BT", "car apps: " + hits.joinToString { "${it.label} (${it.packageName}/${it.className})" }.ifEmpty { "none found" })
        return hits
    }

    /** Lower is better; 9 = not a music app. */
    internal fun carAppScore(a: CarApp): Int {
        val s = "${a.label} ${a.packageName} ${a.className.orEmpty()}".lowercase()
        val bluetooth = "bluetooth" in s || "蓝牙" in s || ".bt" in s || "btmusic" in s || "bt music" in s
        // BYD's Media Center is the car's Bluetooth music player (Yuan Plus car test, 2026-11-24).
        val bydMediaCenter = "mediacenter" in s || "media center" in s
        val music = "music" in s || "音乐" in s || "media" in s || "audio" in s || "player" in s
        val call = "call" in s || "phone" in s || "dial" in s || "contact" in s || "电话" in s
        return when {
            bluetooth && music || bydMediaCenter -> 0
            call -> 9
            bluetooth -> 1
            music -> 2
            else -> 9
        }
    }

    fun openCarApp(ctx: Context, app: CarApp) {
        val intent = if (app.className != null) Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.className))
        else ctx.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { KeyLog.add("BT", "couldn't open ${app.label}: ${it.message}") }
        active = true
        // Give the car a moment to switch source, then look for the playing session again.
        main.postDelayed({ refresh() }, 3000)
    }

    /** Wheel / media key while Bluetooth is the active source. Returns true if handled here. */
    fun handle(action: WheelAction): Boolean {
        if (!active) return false
        if (controller == null) {
            // Nothing to control yet: Play still tries to wake Bluetooth up; Next / Previous go to the radio.
            if (action != WheelAction.PLAY_PAUSE) return false
            KeyLog.add("BT", "wheel $action -> Bluetooth (not connected yet)")
            activate()
            return true
        }
        KeyLog.add("BT", "wheel $action -> Bluetooth")
        when (action) {
            WheelAction.NEXT -> next()
            WheelAction.PREVIOUS -> previous()
            WheelAction.PLAY_PAUSE -> playPause()
        }
        return true
    }
}
