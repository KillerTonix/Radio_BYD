package dev.wheelradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * BYD-specific route. On DiLink units the MCU sends wheel/panel presses to
 * com.byd.multimediaservice, which re-broadcasts them as action "APP_REV_DATA" with a
 * byte[] extra "list": [group, sub, code, ...]. Wheel/panel keys are group 4, sub 1.
 * Reported code 0x03 = wheel Next (source: open-headunit PR #917). Other codes are not
 * documented yet, so every frame is logged to KeyLog and we fill in the map from a real drive.
 *
 * This receiver only listens; it never aborts the broadcast, so BYD's own apps keep working.
 */
class BydWheelKeyReceiver(private val onAction: (WheelAction) -> Unit) : BroadcastReceiver() {

    /** BYD panel code -> action. Extend after reading the in-app key log in the car. */
    private val keyMap = mapOf(
        0x03 to WheelAction.NEXT,
        // 0x?? to WheelAction.PREVIOUS,  // TODO: confirm in the car
    )

    fun register(context: Context) {
        ContextCompat.registerReceiver(
            context, this, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun unregister(context: Context) = runCatching { context.unregisterReceiver(this) }

    /** Payloads that are not wheel-key frames, logged once each (see [logUnrecognised]). */
    private val seenOther = HashSet<String>()

    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val list = intent.extras?.get("list")
        val bytes = list as? ByteArray
        if (bytes == null || bytes.size < 3 || bytes[0].toInt() != 4 || bytes[1].toInt() != 1) {
            logUnrecognised(list, intent)
            return
        }
        val code = bytes[2].toInt() and 0xFF
        val action = keyMap[code]
        KeyLog.add("BYD", "frame ${bytes.toHex()} code=0x%02X -> %s".format(code, action ?: "unmapped"))
        action?.let(onAction)
    }

    /**
     * Anything that isn't a `byte[] [4, 1, code]` frame: another group, a short frame, an int[] / String payload,
     * or no "list" extra at all. We don't act on it, but we log it so a drive shows what this car really sends.
     * The head unit may broadcast other data often, so each distinct payload is logged once (up to
     * [MAX_DISTINCT_OTHER]); otherwise it would push the wheel presses out of the 100-line key log.
     */
    private fun logUnrecognised(list: Any?, intent: Intent) {
        val payload = when (list) {
            is ByteArray -> "byte[] ${list.toHex()}" + if (list.size >= 3) " (not a wheel-key frame)" else " (too short)"
            is IntArray -> "int[] ${list.joinToString(" ")}"
            is LongArray -> "long[] ${list.joinToString(" ")}"
            null -> "no \"list\" extra; extras: ${intent.extras?.keySet()?.sorted() ?: "none"}"
            else -> "${list.javaClass.simpleName} $list"
        }
        if (payload in seenOther) return
        if (seenOther.size == MAX_DISTINCT_OTHER) {
            seenOther += payload // count it once, so this note is logged once
            KeyLog.add("BYD", "more unrecognised frames; not logging any further ones")
            return
        }
        if (seenOther.size > MAX_DISTINCT_OTHER) return
        seenOther += payload
        KeyLog.add("BYD", "unrecognised: $payload")
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }

    companion object {
        const val ACTION = "APP_REV_DATA"
        private const val MAX_DISTINCT_OTHER = 50
    }
}
