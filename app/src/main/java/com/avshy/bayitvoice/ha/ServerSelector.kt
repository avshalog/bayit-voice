package com.avshy.bayitvoice.ha

import android.util.Log
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.NetworkInfo
import com.avshy.bayitvoice.config.ServerUrl

/** Picks the home or external address for the current network and connects to it. */
class ServerSelector(private val settings: AppSettings, private val network: NetworkInfo) {

    /** Addresses to try, in order. */
    fun candidates(): List<ServerUrl> {
        val home = settings.homeServer?.takeIf { it.isAllowed && canReachLocalNetwork() }
        val external = settings.externalServer?.takeIf { it.isAllowed }
        if (home == null || external == null) return listOfNotNull(home, external)

        if (settings.homeSsids.isNotEmpty()) {
            network.currentSsid()?.let { ssid ->
                return if (ssid in settings.homeSsids) listOf(home, external) else listOf(external)
            }
        }
        return if (network.isOnLocalNetwork) listOf(home, external) else listOf(external, home)
    }

    /**
     * Connects [client] to the first reachable candidate and returns it. Only an unreachable
     * address falls through to the next one; a rejected token is reported immediately.
     */
    suspend fun connect(client: HaClient, token: String): ServerUrl {
        val list = candidates()
        if (list.isEmpty()) throw HaException.Unreachable(null)
        list.forEachIndexed { index, server ->
            val last = index == list.lastIndex
            try {
                client.connect(server.webSocketUrl, token, if (last) HaClient.CONNECT_TIMEOUT_MS else FALLBACK_TIMEOUT_MS)
                Log.i(TAG, "Connected via ${if (server == settings.homeServer) "home" else "external"} address")
                return server
            } catch (e: HaException.Unreachable) {
                Log.w(TAG, "${server.host} unreachable: ${e.cause}")
                if (last) throw e
            }
        }
        error("unreachable")
    }

    private fun canReachLocalNetwork(): Boolean {
        val granted = network.hasLocalNetworkPermission
        if (!granted) Log.w(TAG, "Local network permission missing; skipping home address")
        return granted
    }

    private companion object {
        const val TAG = "ServerSelector"
        const val FALLBACK_TIMEOUT_MS = 2_000L
    }
}
