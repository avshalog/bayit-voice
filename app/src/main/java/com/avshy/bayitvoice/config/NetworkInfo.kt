package com.avshy.bayitvoice.config

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/** What the device knows about its current network. */
class NetworkInfo(context: Context) {

    private val context = context.applicationContext

    val hasLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True on Wi-Fi or Ethernet, where the home address may be reachable. */
    val isOnLocalNetwork: Boolean
        get() {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

    /** Current Wi-Fi SSID, or null without location permission, with location off, or off Wi-Fi. */
    fun currentSsid(): String? {
        if (!hasLocationPermission) return null
        val wifi = context.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION") // Still the simplest synchronous source of the SSID.
        val ssid = wifi.connectionInfo?.ssid ?: return null
        @Suppress("DEPRECATION")
        if (ssid == WifiManager.UNKNOWN_SSID || ssid.isBlank()) return null
        return ssid.removeSurrounding("\"")
    }
}
