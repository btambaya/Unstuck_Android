package tech.csalliance.unstuck.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * "We're back online" for the freshness owner.
 *
 * Android had no connectivity signal at all: a phone that lost its network in a
 * tunnel or on a wifi→cellular handover only re-synced on the next 60s tick or
 * the next foreground, and the realtime socket that died in the gap was never
 * noticed. [onRegained] fires on each network that becomes available while
 * registered — the owner coalesces, so a flapping connection costs one pull.
 *
 * Registered only while the app is foregrounded (the engine does not sync in the
 * background anyway) and unregistered on pause, so it costs nothing while away.
 */
class NetworkWatcher(context: Context, private val onRegained: () -> Unit) {
    private val appContext = context.applicationContext
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onRegained()
        }
        // registerDefaultNetworkCallback can throw on some OEM builds / when the
        // permission is missing — a missing connectivity hint must never take the
        // app down (the floor pull still covers us).
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { callback = cb }
            .onFailure { Log.w("UnstuckSync", "network callback registration failed; relying on the floor pull", it) }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }
}
