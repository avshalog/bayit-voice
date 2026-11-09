package com.avshy.bayitvoice.ui.idle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

/**
 * The idle screen as an Activity, for devices without a system screen saver (SPEC section 11).
 * It shows over the lock screen and keeps the screen on while the device is charging.
 */
class IdleActivity : ComponentActivity() {

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = keepScreenOn(isCharging(intent))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        IdleWindow.hideSystemBars(window)
        val brightness = IdleBrightness(this, window, lifecycleScope)
        setContent { IdleScreen(onNightChange = brightness::apply) }
    }

    override fun onStart() {
        super.onStart()
        // ACTION_BATTERY_CHANGED is sticky: registering returns the current state at once.
        val sticky = ContextCompat.registerReceiver(
            this, powerReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        keepScreenOn(sticky?.let(::isCharging) == true)
    }

    override fun onStop() {
        unregisterReceiver(powerReceiver)
        super.onStop()
    }

    private fun isCharging(intent: Intent) = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        fun intent(context: Context) = Intent(context, IdleActivity::class.java)
    }
}
