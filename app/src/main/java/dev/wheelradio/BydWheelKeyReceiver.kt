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

    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val bytes = intent.extras?.get("list") as? ByteArray ?: return
        if (bytes.size < 3 || bytes[0].toInt() != 4 || bytes[1].toInt() != 1) return // not a key frame
        val code = bytes[2].toInt() and 0xFF
        val action = keyMap[code]
        KeyLog.add("BYD", "frame ${bytes.toHex()} code=0x%02X -> %s".format(code, action ?: "unmapped"))
        action?.let(onAction)
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }

    companion object { const val ACTION = "APP_REV_DATA" }
}
