package com.avshy.bayitvoice.ui.idle

import android.content.Context
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.avshy.bayitvoice.config.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Window handling shared by the idle-screen hosts (Activity and screen saver). */
internal object IdleWindow {

    /** Full screen: the status and navigation bars are hidden; a swipe from the edge shows them for a moment. */
    fun hideSystemBars(window: Window) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * The idle screen's backlight (SPEC section 11). By day it follows the system, or a fixed level
 * from settings; at night it goes to the minimum, or off. Moving between day and night ramps
 * over a few minutes; the first value applies at once. It only affects this window: the
 * system's own brightness setting is never changed.
 */
internal class IdleBrightness(private val context: Context, private val window: Window, private val scope: CoroutineScope) {

    private val settings = AppSettings.get(context)
    private var ramp: Job? = null
    private var level: Float? = null

    fun apply(night: Boolean) {
        val target = when {
            night && settings.idleNightScreenOff -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            night -> NIGHT_LEVEL
            else -> settings.idleDayBrightness
        }
        ramp?.cancel()
        val from = level
        if (from == null) {
            set(target)
            return
        }
        val end = target ?: systemLevel()
        ramp = scope.launch {
            for (i in 1..RAMP_STEPS) {
                delay(RAMP_MS / RAMP_STEPS)
                set(from + (end - from) * i / RAMP_STEPS)
            }
            // A day that follows the system hands control back to it, auto-brightness included.
            set(target)
        }
    }

    private fun set(value: Float?) {
        level = value ?: systemLevel()
        window.attributes = window.attributes.apply {
            screenBrightness = value ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /** The system's manual brightness as 0..1; with auto-brightness on it is only an estimate, used for ramping. */
    private fun systemLevel(): Float =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f

    private companion object {
        const val NIGHT_LEVEL = 0.01f
        const val RAMP_MS = 3 * 60_000L
        const val RAMP_STEPS = 60
    }
}
