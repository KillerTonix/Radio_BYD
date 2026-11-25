package dev.wheelradio

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Opens the car's Bluetooth player for [BluetoothBridge]'s step 4, from an invisible activity of our own that
 * closes at once.
 *
 * Android 10 blocks activity starts from the background, with one exception that matters here: an app whose own
 * activity started or finished in the last 10 s may still start one. Opening Media Center straight from the app
 * didn't count, so coming back worked only within 10 s of opening Wheel Radio, and "sometimes" failed in the car.
 * Starting and finishing this activity restarts that window just before Media Center comes up.
 */
class CarPlayerLauncher : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_TARGET, ComponentName::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_TARGET)
        if (target != null) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(target)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { KeyLog.add("BT", "couldn't open ${target.packageName}: ${it.message}") }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_TARGET = "target"

        fun intent(ctx: Context, target: ComponentName): Intent =
            Intent(ctx, CarPlayerLauncher::class.java)
                .putExtra(EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
