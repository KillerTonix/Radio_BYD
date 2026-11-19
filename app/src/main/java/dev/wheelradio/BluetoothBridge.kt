package dev.wheelradio

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
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
 * play / pause / next / previous. Two ways in, tried in order:
 *  1. Android's AVRCP controller MediaBrowserService (com.android.bluetooth), used by AOSP car media apps.
 *  2. Any other app's active media session (e.g. BYD's Bluetooth music app), which needs notification access.
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

    /** True after the user played Bluetooth from our app: wheel next/previous then go to Bluetooth. */
    var active by mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null
    private var sessionManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val avrcp = ComponentName(
        "com.android.bluetooth", "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService"
    )

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = updateMeta(metadata)
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = updateState(state)
        override fun onSessionDestroyed() {
            detach()
            refresh()
        }
    }

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        refresh()
    }

    /** Look for a Bluetooth music source again (Retry button, or after granting access). */
    fun refresh() {
        val ctx = appContext ?: return
        if (controller != null) return
        status = Status.SEARCHING
        connectAvrcp(ctx)
    }

    private fun connectAvrcp(ctx: Context) {
        browser?.disconnect()
        val b = MediaBrowserCompat(ctx, avrcp, object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val token = browser?.sessionToken ?: return useSessions(ctx)
                attach(MediaControllerCompat(ctx, token), "Bluetooth (AVRCP)")
                KeyLog.add("BT", "connected to AVRCP controller service")
            }
            override fun onConnectionFailed() {
                KeyLog.add("BT", "AVRCP controller service not available")
                useSessions(ctx)
            }
            override fun onConnectionSuspended() {
                detach()
                useSessions(ctx)
            }
        }, null)
        browser = b
        runCatching { b.connect() }.onFailure { useSessions(ctx) }
    }

    private fun useSessions(ctx: Context) {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = msm
        val listenerComponent = ComponentName(ctx, MediaListener::class.java)
        val listenerDeclared = runCatching { ctx.packageManager.getServiceInfo(listenerComponent, 0) }.isSuccess
        if (!listenerDeclared) {
            // Phone build: no notification listener, so only the AVRCP route exists.
            status = Status.NOT_FOUND
            return
        }
        val sessions = try {
            msm.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            status = Status.NEEDS_ACCESS
            return
        }
        if (sessionsListener == null) {
            val l = MediaSessionManager.OnActiveSessionsChangedListener { main.post { if (controller == null) useSessions(ctx) } }
            runCatching { msm.addOnActiveSessionsChangedListener(l, listenerComponent, main) }
            sessionsListener = l
        }
        val others = sessions.filter { it.packageName != ctx.packageName }
        KeyLog.add("BT", "media sessions: " + others.joinToString { it.packageName }.ifEmpty { "none" })
        val pick = others.firstOrNull { it.packageName.contains("bluetooth", true) || it.packageName.contains("bt", true) }
            ?: others.firstOrNull()
        if (pick == null) {
            status = Status.NOT_FOUND
            return
        }
        val label = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pick.packageName, 0)).toString()
        }.getOrDefault(pick.packageName)
        attach(MediaControllerCompat(ctx, MediaSessionCompat.Token.fromToken(pick.sessionToken)), label)
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
    }

    private fun updateMeta(m: MediaMetadataCompat?) {
        title = m?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: ""
        artist = listOfNotNull(
            m?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST),
            m?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        art = m?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
    }

    private fun updateState(s: PlaybackStateCompat?) {
        isPlaying = s?.state == PlaybackStateCompat.STATE_PLAYING || s?.state == PlaybackStateCompat.STATE_BUFFERING
        if (isPlaying) active = true
    }

    fun play() { active = true; controller?.transportControls?.play() }
    fun pause() { controller?.transportControls?.pause() }
    fun playPause() = if (isPlaying) pause() else play()
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    data class CarApp(val label: String, val packageName: String)

    /**
     * Apps on the head unit that look like the car's Bluetooth music player. Opening it switches the car's
     * audio source to Bluetooth, which is what makes the phone see the car as a media-audio device.
     */
    fun findCarBluetoothApps(ctx: Context): List<CarApp> {
        val pm = ctx.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val all = pm.queryIntentActivities(launcher, 0)
            .map { CarApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != ctx.packageName }
            .distinctBy { it.packageName }
        fun score(a: CarApp): Int {
            val s = (a.label + " " + a.packageName).lowercase()
            return when {
                "bluetooth" in s || "蓝牙" in s || "bt music" in s || "btmusic" in s || ".bt" in s -> 0
                "music" in s || "音乐" in s || "media" in s || "multimedia" in s -> 1
                else -> 9
            }
        }
        val hits = all.filter { score(it) < 9 }.sortedBy { score(it) }
        KeyLog.add("BT", "car apps: " + hits.joinToString { "${it.label} (${it.packageName})" }.ifEmpty { "none found" })
        return hits
    }

    fun openCarApp(ctx: Context, app: CarApp) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        runCatching { ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        active = true
        // Give the car a moment to switch source, then look for the playing session again.
        main.postDelayed({ if (controller == null) refresh() }, 3000)
    }

    /** Wheel / media key while Bluetooth is the active source. Returns true if handled here. */
    fun handle(action: WheelAction): Boolean {
        if (!active || controller == null) return false
        KeyLog.add("BT", "wheel $action -> Bluetooth")
        when (action) {
            WheelAction.NEXT -> next()
            WheelAction.PREVIOUS -> previous()
            WheelAction.PLAY_PAUSE -> playPause()
        }
        return true
    }
}
