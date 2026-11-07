package com.avshy.bayitvoice.satellite

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Announces the satellite on the local network as `_esphomelib._tcp`, the way ESPHome devices
 * do, so HA's ESPHome integration discovers it.
 */
class SatelliteAdvertiser(
    context: Context,
    private val name: String,
    private val friendlyName: String,
    private val port: Int,
    private val mac: String,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registered = false

    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "Advertised as '${info.serviceName}'")
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "Advertising failed: $errorCode")
            registered = false
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
    }

    fun register() {
        if (registered) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            port = this@SatelliteAdvertiser.port
            setAttribute("mac", mac)
            setAttribute("version", SatelliteDevice.ESPHOME_VERSION)
            setAttribute("friendly_name", friendlyName)
            setAttribute("platform", "HOST")
            setAttribute("board", "host")
            setAttribute("network", "wifi")
            setAttribute("api_encryption", "Noise_NNpsk0_25519_ChaChaPoly_SHA256")
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "Cannot advertise", it) }
    }

    fun unregister() {
        if (!registered) return
        registered = false
        runCatching { nsd.unregisterService(listener) }
    }

    private companion object {
        const val TAG = "SatelliteAdvertiser"
        const val SERVICE_TYPE = "_esphomelib._tcp"
    }
}
