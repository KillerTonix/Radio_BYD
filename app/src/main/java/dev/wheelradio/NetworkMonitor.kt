package dev.wheelradio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler

/**
 * Tells RadioService whether the car has a usable internet connection, so a dropped stream in a tunnel or
 * a car park is not mistaken for a dead station (bug U7: an outage used to walk through the whole queue).
 *
 * [onOnline] runs on [handler]'s thread each time the default network comes back with internet access.
 */
class NetworkMonitor(context: Context, private val handler: Handler, private val onOnline: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var wasOnline = isOnline()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            update(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))

        override fun onLost(network: Network) = update(false)
    }

    fun start() {
        runCatching { cm?.registerDefaultNetworkCallback(callback, handler) }
            .onFailure { KeyLog.add("Network", "can't watch connectivity: ${it.message}") }
    }

    fun stop() {
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    /**
     * Asked right when a stream fails, so it doesn't depend on the callback having arrived yet.
     * Without a ConnectivityManager we can't tell, so assume online and keep the old retry/skip behaviour.
     */
    fun isOnline(): Boolean {
        val manager = cm ?: return true
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun update(online: Boolean) {
        if (online == wasOnline) return
        wasOnline = online
        KeyLog.add("Network", if (online) "back online" else "offline")
        if (online) onOnline()
    }
}
