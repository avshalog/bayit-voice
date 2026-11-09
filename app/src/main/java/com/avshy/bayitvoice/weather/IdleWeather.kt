package com.avshy.bayitvoice.weather

import android.content.Context
import android.util.Log
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.satellite.HaLink.Key
import com.avshy.bayitvoice.satellite.SatelliteDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** What the idle screen shows about the weather. Fields are null when HA has not sent them. */
data class WeatherNow(
    /** HA's condition, e.g. "sunny", "partlycloudy", "rainy", "clear-night". */
    val condition: String?,
    val temperature: Double?,
    val temperatureUnit: String,
    val humidity: Double?,
    val windSpeed: Double?,
    val windUnit: String,
    val high: Double?,
    val low: Double?,
    /** The sun's elevation in degrees, from `sun.sun`; null when unknown. */
    val sunElevation: Double?,
)

private data class Forecast(val high: Double?, val low: Double?)

/**
 * The idle screen's weather (SPEC section 11): the chosen `weather` entity and `sun.sun` over the
 * satellite's ESPHome link, and today's high and low from `weather.get_forecasts`. Holds no UI.
 * Direct mode does not provide weather yet.
 */
class IdleWeather(context: Context) {

    private val settings = AppSettings.get(context)
    private val ha = SatelliteDevice.get(context).ha
    private val forecast = MutableStateFlow(Forecast(null, null))

    val entityId: String? get() = settings.idleWeatherEntity

    val now: Flow<WeatherNow?> = combine(ha.states, forecast) { states, fc ->
        val entity = entityId ?: return@combine null
        fun value(attribute: String = "", of: String = entity) = states[Key(of, attribute)]
        val condition = value() ?: return@combine null
        WeatherNow(
            condition = condition,
            temperature = value("temperature")?.toDoubleOrNull(),
            temperatureUnit = value("temperature_unit") ?: "°",
            humidity = value("humidity")?.toDoubleOrNull(),
            windSpeed = value("wind_speed")?.toDoubleOrNull(),
            windUnit = value("wind_speed_unit").orEmpty(),
            high = fc.high,
            low = fc.low,
            sunElevation = value("elevation", of = SUN)?.toDoubleOrNull(),
        )
    }

    /**
     * Asks HA for the states, then refreshes the forecast every 30 minutes. Run it while the idle
     * screen is showing; it ends when cancelled.
     */
    suspend fun run() {
        val entity = entityId ?: return
        ha.want(
            setOf(
                Key(entity), Key(entity, "temperature"), Key(entity, "temperature_unit"),
                Key(entity, "humidity"), Key(entity, "wind_speed"), Key(entity, "wind_speed_unit"),
                Key(SUN, "elevation"),
            ),
        )
        while (true) {
            val fetched = fetchForecast(entity)
            if (fetched != null) forecast.value = fetched
            delay(if (fetched != null) REFRESH_MS else RETRY_MS)
        }
    }

    private suspend fun fetchForecast(entity: String): Forecast? {
        val response = ha.call("weather.get_forecasts", mapOf("entity_id" to entity, "type" to "daily")) ?: return null
        if (!response.success) {
            Log.w(TAG, "Forecast refused: ${response.error}")
            return null
        }
        return runCatching { parseToday(response.responseJson, entity) }
            .onFailure { Log.w(TAG, "Unreadable forecast: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /** `{"response": {"weather.x": {"forecast": [{"datetime", "temperature", "templow", ...}]}}}` → today's entry. */
    private fun parseToday(json: String, entity: String): Forecast? {
        val days = JSONObject(json).getJSONObject("response").getJSONObject(entity).getJSONArray("forecast")
        if (days.length() == 0) return null
        val today = LocalDate.now()
        var pick = days.getJSONObject(0)
        for (i in 0 until days.length()) {
            val day = days.getJSONObject(i)
            val date = runCatching {
                OffsetDateTime.parse(day.getString("datetime")).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
            }.getOrNull()
            if (date == today) { pick = day; break }
        }
        return Forecast(
            high = pick.optDouble("temperature").takeUnless { it.isNaN() },
            low = pick.optDouble("templow").takeUnless { it.isNaN() },
        )
    }

    private companion object {
        const val TAG = "IdleWeather"
        const val SUN = "sun.sun"
        const val REFRESH_MS = 30 * 60_000L
        // A refusal usually means actions are not allowed for the device; HA logs each one, so wait as long.
        const val RETRY_MS = 30 * 60_000L
    }
}
