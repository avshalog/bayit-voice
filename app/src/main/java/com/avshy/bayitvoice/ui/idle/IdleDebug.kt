package com.avshy.bayitvoice.ui.idle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.avshy.bayitvoice.weather.WeatherNow

/**
 * Debug builds only: lets adb force the idle screen's weather, for screenshots and demos.
 * `adb shell am start -n com.avshy.bayitvoice/.ui.idle.IdleActivity --es debug_condition rainy --es debug_sun sunrise --ef debug_temp 17`
 * `debug_sun` is sunrise, sunset, day or night. Starting without `debug_condition` clears it.
 */
object IdleDebug {
    var weather: WeatherNow? by mutableStateOf(null)
        private set

    /** A forced clock time "HH:mm" for today, or null for the real time. */
    var time: String? by mutableStateOf(null)
        private set

    fun at(): java.util.Date {
        val t = time ?: return java.util.Date()
        val (h, m) = t.split(":").map { it.toInt() }
        return java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0)
        }.time
    }

    fun set(condition: String?, sun: String?, temperature: Float?, clock: String? = null) {
        time = clock
        weather = condition?.let {
            WeatherNow(
                condition = it,
                temperature = (temperature ?: 20f).toDouble(),
                temperatureUnit = "°C",
                humidity = 60.0,
                windSpeed = 12.0,
                windUnit = "km/h",
                high = (temperature ?: 20f) + 3.0,
                low = (temperature ?: 20f) - 5.0,
                sunElevation = when (sun) {
                    "sunrise", "sunset" -> 2.0
                    "night" -> -20.0
                    else -> 40.0
                },
            )
        }
    }
}
