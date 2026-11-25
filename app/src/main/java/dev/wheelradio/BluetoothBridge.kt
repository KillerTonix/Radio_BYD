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
import java.util.concurrent.Executors
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
    /**
     * The source accepts a jump to a position. AVRCP has no such command: a phone over Bluetooth only offers
     * play / pause / stop / next / previous (car test: actions=0x244037), so the timeline is display-only then.
     */
    var canSeek by mutableStateOf(false)
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
            // e.g. the car's own player got the cover after AVRCP sent the title; it beats one found online.
            if (art == null || artFromWeb) borrowArt()
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
        artFromWeb = false
        durationMs = 0
        canSeek = false
        lastState = null
    }

    private fun updateMeta(m: MediaMetadataCompat?) {
        val newTitle = m?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: ""
        val songChanged = newTitle != title
        title = newTitle
        val songArtist = m?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty()
        val songAlbum = m?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM).orEmpty()
        artist = listOf(songArtist, songAlbum).filter { it.isNotBlank() }.joinToString(" · ")
        durationMs = m?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0
        val bitmap = bitmapOf(m)
        val uri = artUriOf(m)
        var searching = false
        when {
            bitmap != null -> { art = bitmap; artFromWeb = false }
            uri != null -> if (uri != artUri || art == null) { if (songChanged) art = null; artUri = uri; loadArt(uri, newTitle) }
            else -> {
                // Keep a cover found online while the same song only updates its details.
                if (songChanged || !artFromWeb) { art = null; artFromWeb = false }
                borrowArt()
                if (art == null) searching = findCoverOnline(newTitle, songArtist, songAlbum)
            }
        }
        if (songChanged && newTitle.isNotBlank()) KeyLog.add(
            "BT", "song \"$newTitle\" from ${controller?.packageName}, cover: " + when {
                bitmap != null -> "picture ${bitmap.width}x${bitmap.height}"
                uri != null -> "link $uri"
                art != null -> "borrowed from another app"
                searching -> "none, looking online"
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
            artFromWeb = false
            KeyLog.add("BT", "cover for \"$title\" from ${donor.packageName}")
        } else artUriOf(donor.metadata)?.let { loadArt(it, title) }
    }

    /** The cover shown came from [CoverSearch]; any cover from the phone or the car's player replaces it. */
    private var artFromWeb = false
    private val coverLookup = Executors.newSingleThreadExecutor()
    /** The last song an online search was logged for, so metadata updates don't repeat the line. */
    private var coverLoggedFor = ""

    /**
     * Looks the song up online when the phone and the car's player give no cover (see [CoverSearch]).
     * Returns false when it doesn't search: the setting is off, or there's no artist to go by.
     */
    private fun findCoverOnline(forTitle: String, songArtist: String, songAlbum: String): Boolean {
        val ctx = appContext ?: return false
        if (!AppSettings.coversOnline(ctx) || forTitle.isBlank() || songArtist.isBlank()) return false
        coverLookup.execute {
            if (title != forTitle) return@execute // already the next song
            val hit = runCatching { CoverSearch.find(songArtist, forTitle, songAlbum) }.getOrNull()
            main.post {
                val log = coverLoggedFor != forTitle
                coverLoggedFor = forTitle
                when {
                    title != forTitle -> Unit
                    hit != null -> {
                        if (log) KeyLog.add("BT", "cover for \"$forTitle\" found online (${hit.service})")
                        loadArt(hit.url, forTitle, fromWeb = true)
                    }
                    log -> KeyLog.add("BT", "no cover online for \"$forTitle\" by $songArtist")
                }
            }
        }
        return true
    }

    /** Reads a cover given as a link (content://, file:// or http) off the main thread, scaled to about 512 px. */
    private fun loadArt(uri: String, forTitle: String, fromWeb: Boolean = false) {
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
            if (bmp != null) main.post {
                // An online cover never replaces one that came from the phone or the car meanwhile.
                if (title == forTitle && (!fromWeb || art == null || artFromWeb)) {
                    art = bmp
                    artFromWeb = fromWeb
                }
            }
        }.start()
    }

    private fun updateState(s: PlaybackStateCompat?) {
        lastState = s
        isPlaying = s?.state == PlaybackStateCompat.STATE_PLAYING || s?.state == PlaybackStateCompat.STATE_BUFFERING
        canSeek = supportsSeek(s?.actions ?: 0)
        if (isPlaying) active = true
    }

    internal fun supportsSeek(actions: Long): Boolean = (actions and PlaybackStateCompat.ACTION_SEEK_TO) != 0L

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
    fun seekTo(ms: Long) { if (canSeek) controller?.transportControls?.seekTo(ms) }

    /**
     * A phone is connected to the car over Bluetooth (media or calls), or null when that can't be read.
     * Opening the Bluetooth tab only switches the car to Bluetooth when a phone is there to play.
     */
    fun phoneConnected(): Boolean? {
        val ctx = appContext ?: return null
        return runCatching {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return@runCatching false
            if (!adapter.isEnabled) return@runCatching false
            // A2DP sink (11), AVRCP controller (12) and HFP client (16): the car's side of a phone connection.
            listOf(11, 12, 16).any { adapter.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED }
        }.getOrNull()
    }

    /** The Bluetooth tab was opened by the driver: make Bluetooth the source, like the car's own media app. */
    fun onTabOpened(pauseRadio: () -> Unit) {
        if (isPlaying) { active = true; return }
        val phone = phoneConnected()
        if (phone == false) {
            KeyLog.add("BT", "Bluetooth tab: no phone connected, the radio keeps playing")
            return
        }
        KeyLog.add("BT", "Bluetooth tab opened: switching to Bluetooth")
        pauseRadio()
        activate()
    }

    /**
     * BYD DiLink answers the system's notification-access page with "Multimedia system does not support this
     * operation" (Yuan Plus car test, 0.5.3), so there the app offers only the ADB command.
     */
    fun notificationSettingsBlocked(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(BYD_MEDIA_CENTER, 0) }.isSuccess

    // ---------- Making Bluetooth the car's audio source ----------

    private const val AUTO_START_DELAY_MS = 2_500L
    private const val STEP_WAIT_MS = 3_000L
    /** Media Center stays in front at least this long, so it has time to claim the Bluetooth source… */
    internal const val CAR_APP_MIN_WAIT_MS = 3_000L
    /** …and at most this long. Must stay well under Android 10's 10 s window for coming back (see [CarPlayerLauncher]). */
    internal const val CAR_APP_MAX_WAIT_MS = 5_000L
    private const val RETURN_POLL_MS = 250L
    private const val RETURN_CHECK_MS = 1_500L
    private const val AVRCP_PACKAGE = "com.android.bluetooth"
    private const val BYD_MEDIA_CENTER = "com.byd.mediacenter"

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
     *  4. Only while our screen is in front: open the car's player and come back 3–5 s later.
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
            Step("open the car's player and come back", waitMs = CAR_APP_MAX_WAIT_MS + STEP_WAIT_MS) { openCarPlayerAndReturn() },
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
        // Through our own short-lived activity: that is what lets Android 10 bring Wheel Radio back afterwards.
        ctx.startActivity(
            CarPlayerLauncher.intent(ctx, ComponentName(app.packageName, app.className ?: return false))
        )
        val openedAt = SystemClock.uptimeMillis()
        val poll = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - openedAt
                if (!timeToReturn(elapsed, isPlaying)) {
                    main.postDelayed(this, RETURN_POLL_MS)
                    return
                }
                returnToApp(ctx, elapsed)
            }
        }
        main.postDelayed(poll, CAR_APP_MIN_WAIT_MS)
        return true
    }

    /** Come back once Bluetooth plays (after at least 3 s), or after 5 s whatever happened. */
    internal fun timeToReturn(elapsedMs: Long, playing: Boolean): Boolean =
        elapsedMs >= CAR_APP_MAX_WAIT_MS || (playing && elapsedMs >= CAR_APP_MIN_WAIT_MS)

    private fun returnToApp(ctx: Context, afterMs: Long) {
        if (uiVisible) {
            KeyLog.add("BT", "already back in Wheel Radio")
        } else {
            runCatching {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }.onSuccess {
                KeyLog.add("BT", "back to Wheel Radio after ${afterMs / 100 / 10.0} s (Bluetooth ${if (isPlaying) "plays" else "still silent"})")
            }.onFailure { KeyLog.add("BT", "couldn't come back to Wheel Radio: ${it.message}") }
            // Android drops a blocked activity start without an error, so check that we really are in front.
            main.postDelayed({
                if (!uiVisible) KeyLog.add("BT", "Wheel Radio is still not in front: Android blocked the return")
            }, RETURN_CHECK_MS)
        }
        refresh()
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
        packages.forEach { probeDataServices(ctx, pm, it) }
        KeyLog.add("Diag", "done")
    }

    /**
     * Media Center shows the phone's cover (Yuan Plus car test: Spotify on a Pixel 7 Pro), which reaches it through
     * BYD's own Bluetooth stack, not Android's AVRCP. It exports `com.byd.music.data.AudioDataService` with no
     * permission, probably what the launcher widget and the cluster read the song from. Its interface isn't
     * documented, so connect once, and log the interface name and its methods (read from Media Center's own
     * classes), plus the protection level of the permissions on the other services. That tells us whether, and
     * how, Wheel Radio can take the cover from there.
     */
    private fun probeDataServices(ctx: Context, pm: PackageManager, pkg: String) {
        val services = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_SERVICES).services }.getOrNull()
            ?.filter { it.exported } ?: return
        services.mapNotNull { it.permission }.distinct().forEach { p ->
            val level = runCatching {
                when (pm.getPermissionInfo(p, 0).protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE) {
                    android.content.pm.PermissionInfo.PROTECTION_NORMAL -> "normal (any app may use it)"
                    android.content.pm.PermissionInfo.PROTECTION_DANGEROUS -> "dangerous (user must allow)"
                    android.content.pm.PermissionInfo.PROTECTION_SIGNATURE -> "signature (BYD apps only)"
                    else -> "system"
                }
            }.getOrDefault("not declared")
            KeyLog.add("Diag", "  permission $p: $level")
        }
        val browser = mediaBrowserServices(ctx).toSet()
        services.filter { it.permission == null && ComponentName(pkg, it.name) !in browser }.forEach { s ->
            val component = ComponentName(pkg, s.name)
            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: android.os.IBinder?) {
                    runCatching { describeBinder(ctx, pkg, s.name, binder) }
                        .onFailure { KeyLog.add("Diag", "  ${s.name}: couldn't read: $it") }
                    main.post { runCatching { ctx.unbindService(this) } }
                }
                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            val bound = runCatching {
                ctx.bindService(Intent().setComponent(component), conn, Context.BIND_AUTO_CREATE)
            }.getOrElse { KeyLog.add("Diag", "  ${s.name}: bind refused: ${it.message}"); false }
            if (!bound) KeyLog.add("Diag", "  ${s.name}: can't connect")
        }
    }

    private fun describeBinder(ctx: Context, pkg: String, service: String, binder: android.os.IBinder?) {
        val descriptor = binder?.interfaceDescriptor
        KeyLog.add("Diag", "  $service: connected, interface ${descriptor ?: "none (binder ${binder?.javaClass?.name})"}")
        if (descriptor.isNullOrBlank()) return
        val loader = ctx.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
        // The interface, then the app's own types its methods use (callbacks, song data classes), one level deep.
        val queue = ArrayDeque(listOf(descriptor))
        val seen = HashSet<String>()
        while (queue.isNotEmpty() && seen.size < 8) {
            val name = queue.removeFirst()
            if (!seen.add(name)) continue
            val cls = runCatching { loader.loadClass(name) }.getOrNull()
            if (cls == null) {
                KeyLog.add("Diag", "    $name: class not found")
                continue
            }
            val own = { c: Class<*> -> c.name.startsWith("com.byd") || c.name.startsWith(pkg) }
            if (cls.isInterface) {
                val methods = runCatching { cls.declaredMethods.toList() }.getOrDefault(emptyList()).sortedBy { it.name }
                KeyLog.add("Diag", "    interface ${cls.name}: ${methods.size} methods")
                methods.chunked(4).forEach { chunk ->
                    KeyLog.add("Diag", "      " + chunk.joinToString("; ") { m ->
                        "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}"
                    })
                }
                methods.flatMap { it.parameterTypes.toList() + it.returnType }.filter(own).forEach { queue.add(it.name) }
            } else {
                val fields = runCatching { cls.declaredFields.toList() }.getOrDefault(emptyList())
                    .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                KeyLog.add("Diag", "    class ${cls.name}: " + fields.joinToString { "${it.name}: ${it.type.simpleName}" }.ifEmpty { "no fields" })
            }
        }
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
