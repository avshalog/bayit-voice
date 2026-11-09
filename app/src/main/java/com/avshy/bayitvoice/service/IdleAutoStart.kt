package com.avshy.bayitvoice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.avshy.bayitvoice.config.AppSettings

/**
 * Opens the idle screen by itself while the device charges (SPEC section 11), for systems such
 * as Realme / ColorOS that hide Android's screen saver. Registered by the listening service; it
 * loads no UI code and only starts the Activity by class name.
 *
 * - Charger connected: open it.
 * - Screen turned off while charging, while another app was in front (it timed out): open it
 *   again, waking the screen. If the idle screen itself was showing, the user turned the screen
 *   off on purpose, and it stays off.
 * The idle screen closes itself when the charger is removed, if it was opened this way.
 */
class IdleAutoStart(private val context: Context) {

    private val settings = AppSettings.get(context)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!settings.idleAutoStart) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> SessionLauncher.openIdle(context)
                Intent.ACTION_SCREEN_OFF -> if (isCharging() && !IdleVisibility.wasShowingRecently()) {
                    SessionLauncher.openIdle(context)
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun isCharging(): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }
}

/**
 * Whether the idle screen is in front, kept by the idle Activity. Plain state with no UI code,
 * so the service can read it.
 */
object IdleVisibility {
    @Volatile private var showing = false
    @Volatile private var hiddenAtMs = 0L

    fun onShown() { showing = true }

    fun onHidden() {
        showing = false
        hiddenAtMs = SystemClock.elapsedRealtime()
    }

    /** True if it is in front, or was until a moment ago (the screen-off broadcast can come after the Activity stops). */
    fun wasShowingRecently(): Boolean =
        showing || SystemClock.elapsedRealtime() - hiddenAtMs < RECENT_MS

    private const val RECENT_MS = 2_000L
}
