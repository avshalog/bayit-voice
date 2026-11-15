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
import com.avshy.bayitvoice.BuildConfig
import com.avshy.bayitvoice.service.IdleVisibility
import com.avshy.bayitvoice.service.SessionLauncher

/**
 * The idle screen as an Activity (SPEC section 11), for devices without a system screen saver.
 * It shows over the lock screen and keeps the screen on while the device is charging. Opened
 * automatically ([SessionLauncher.openIdle]) it also wakes the screen, and closes itself once
 * the charger is removed.
 */
class IdleActivity : ComponentActivity() {

    private var autoStarted = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onPower(isCharging(intent))
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
        applyLaunch(intent)
        IdleWindow.hideSystemBars(window)
        val brightness = IdleBrightness(this, window, lifecycleScope)
        setContent { IdleScreen(onNightChange = brightness::apply) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyLaunch(intent)
    }

    /** An automatic start wakes the screen; a start from settings leaves that to the user. */
    private fun applyLaunch(intent: Intent) {
        val auto = intent.getBooleanExtra(SessionLauncher.EXTRA_AUTO_STARTED, false)
        autoStarted = auto
        if (BuildConfig.DEBUG) {
            if (intent.getBooleanExtra("debug_landscape", false)) {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            IdleDebug.set(
                intent.getStringExtra("debug_condition"),
                intent.getStringExtra("debug_sun"),
                if (intent.hasExtra("debug_temp")) intent.getFloatExtra("debug_temp", 20f) else null,
                intent.getStringExtra("debug_time"),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(auto)
        } else {
            @Suppress("DEPRECATION")
            if (auto) window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    override fun onStart() {
        super.onStart()
        IdleVisibility.onShown()
        // ACTION_BATTERY_CHANGED is sticky: registering returns the current state at once.
        val sticky = ContextCompat.registerReceiver(
            this, powerReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onPower(sticky?.let(::isCharging) == true)
    }

    override fun onStop() {
        unregisterReceiver(powerReceiver)
        IdleVisibility.onHidden()
        super.onStop()
    }

    private fun onPower(charging: Boolean) {
        if (!charging && autoStarted) {
            finish()
            return
        }
        if (charging) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun isCharging(intent: Intent) = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

    companion object {
        fun intent(context: Context) = Intent(context, IdleActivity::class.java)
    }
}
