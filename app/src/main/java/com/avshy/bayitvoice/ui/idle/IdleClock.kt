package com.avshy.bayitvoice.ui.idle

import android.content.Context
import android.icu.util.Calendar
import android.icu.util.ULocale
import com.avshy.bayitvoice.config.AppSettings
import java.util.Date
import java.util.Locale

/** What the idle screen shows for one minute (SPEC section 11). */
data class IdleClockState(
    val time: String,
    val gregorianDate: String,
    /** Null when the Hebrew date is turned off. */
    val hebrewDate: String?,
    val night: Boolean,
    /** At night the screen is off rather than showing a dim clock. */
    val nightScreenOff: Boolean,
    /** Minutes since midnight, to step the burn-in shift once a minute. */
    val minuteOfDay: Int,
)

/** Formats the time and both dates in the device's locale and 12/24-hour setting. */
class IdleClock(private val context: Context) {

    private val settings = AppSettings.get(context)

    fun now(at: Date = if (com.avshy.bayitvoice.BuildConfig.DEBUG) IdleDebug.at() else Date()): IdleClockState {
        val locale = Locale.getDefault()
        val time = android.text.format.DateFormat.getTimeFormat(context).format(at)
        val gregorian = java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL, locale).format(at)
        val showHebrew = settings.idleHebrewDate ?: (locale.language == "he" || locale.language == "iw")
        val hebrew = if (showHebrew) hebrewDate(at, locale) else null
        val cal = java.util.Calendar.getInstance().also { it.time = at }
        val minute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return IdleClockState(time, gregorian, hebrew, isNight(minute), settings.idleNightScreenOff, minute)
    }

    /** The Hebrew calendar date in the UI language; in Hebrew ICU writes it with Hebrew numerals. */
    private fun hebrewDate(at: Date, locale: Locale): String {
        val uLocale = ULocale.forLocale(locale).setKeywordValue("calendar", "hebrew")
        val calendar = Calendar.getInstance(uLocale).also { it.time = at }
        return android.icu.text.DateFormat.getDateInstance(calendar, android.icu.text.DateFormat.LONG, uLocale).format(at)
    }

    /** Night runs from the start minute to the end minute, across midnight when the start is later. */
    private fun isNight(minute: Int): Boolean {
        val start = settings.idleNightStartMinute
        val end = settings.idleNightEndMinute
        return when {
            start == end -> false
            start < end -> minute in start until end
            else -> minute >= start || minute < end
        }
    }
}
