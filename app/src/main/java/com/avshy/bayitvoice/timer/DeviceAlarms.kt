package com.avshy.bayitvoice.timer

import android.content.Context
import android.util.Log
import com.avshy.bayitvoice.config.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AlarmKind { ALARM, REMINDER }

/** A one-time alarm or reminder kept on this device; [atMs] is wall-clock time (epoch ms). */
data class DeviceAlarm(val id: Int, val kind: AlarmKind, val atMs: Long, val text: String) {
    val clock: String get() = CLOCK.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name.lowercase())
        .put("time", clock)
        .put("date", DATE.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())))
        .put("minutes_from_now", ((atMs - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(0))
        .put("text", text)

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}

/**
 * Alarms and reminders set by HA on this satellite (SPEC section 10.1). They are stored in the
 * settings, so they survive an HA restart and a reboot; the listening service waits for the next
 * one and hands it to [TimerAlarms] to ring. Holds no UI.
 */
class DeviceAlarms private constructor(context: Context) {

    private val settings = AppSettings.get(context)
    private val timers = TimerAlarms.get(context)
    private val _alarms = MutableStateFlow(load())

    val alarms: StateFlow<List<DeviceAlarm>> = _alarms.asStateFlow()

    private var watcher: Job? = null

    /** Called with each reminder as it fires, to tell HA (an event an automation can speak). */
    var onReminder: ((DeviceAlarm) -> Unit)? = null

    /** Waits for each alarm in turn. Safe to call again; [scope] should outlive the satellite. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            _alarms.collectLatest { list ->
                while (true) {
                    val now = System.currentTimeMillis()
                    val due = list.filter { it.atMs <= now }
                    if (due.isNotEmpty()) {
                        fire(due, now)
                        return@collectLatest // fire() changed the list; collectLatest starts over.
                    }
                    val next = list.minOfOrNull { it.atMs } ?: return@collectLatest
                    // Wake at least every 30 s, so a change of the clock or time zone is noticed.
                    delay((next - now).coerceIn(1L, CHECK_MS))
                }
            }
        }
    }

    /** Adds one; it fires the next time the clock reads [atMs] (already resolved by the caller). */
    fun add(kind: AlarmKind, atMs: Long, text: String): DeviceAlarm {
        lateinit var added: DeviceAlarm
        _alarms.update { list ->
            added = DeviceAlarm((list.maxOfOrNull { it.id } ?: 0) + 1, kind, atMs, text.trim())
            (list + added).sortedBy { it.atMs }
        }
        save()
        Log.i(TAG, "Added ${kind.name.lowercase()} #${added.id} at ${added.clock}")
        return added
    }

    /**
     * Cancels by [which]: an id, "next" (the soonest) or "all"; [kind] limits it to alarms or
     * reminders. Returns those cancelled.
     */
    fun cancel(which: String, kind: AlarmKind?): List<DeviceAlarm> {
        var removed = emptyList<DeviceAlarm>()
        _alarms.update { list ->
            val candidates = list.filter { kind == null || it.kind == kind }
            removed = when (val w = which.trim().lowercase()) {
                "all" -> candidates
                "next", "" -> listOfNotNull(candidates.minByOrNull { it.atMs })
                else -> candidates.filter { it.id.toString() == w }
            }
            list - removed.toSet()
        }
        if (removed.isNotEmpty()) save()
        return removed
    }

    private fun fire(due: List<DeviceAlarm>, now: Long) {
        _alarms.update { list -> list - due.toSet() }
        save()
        for (alarm in due) {
            if (now - alarm.atMs > LATE_LIMIT_MS) {
                Log.i(TAG, "Dropped ${alarm.kind.name.lowercase()} #${alarm.id}: missed by ${(now - alarm.atMs) / 60_000} min")
                continue
            }
            timers.ringAlarm(alarm)
            if (alarm.kind == AlarmKind.REMINDER) onReminder?.invoke(alarm)
        }
    }

    private fun load(): List<DeviceAlarm> = runCatching {
        val array = JSONArray(settings.deviceAlarmsJson ?: return emptyList())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            DeviceAlarm(o.getInt("id"), AlarmKind.valueOf(o.getString("kind")), o.getLong("at"), o.optString("text"))
        }.sortedBy { it.atMs }
    }.getOrElse { emptyList() }

    private fun save() {
        val array = JSONArray()
        for (a in _alarms.value) {
            array.put(JSONObject().put("id", a.id).put("kind", a.kind.name).put("at", a.atMs).put("text", a.text))
        }
        settings.deviceAlarmsJson = array.toString()
    }

    companion object {
        private const val TAG = "DeviceAlarms"
        private const val CHECK_MS = 30_000L

        /** One missed while the device was off still rings if it is at most this late. */
        private const val LATE_LIMIT_MS = 30 * 60_000L

        /**
         * The next time the clock reads [text] ("7", "07:00", "7:30", "19:05"), in epoch ms; null
         * if it is not a time of day.
         */
        fun nextOccurrence(text: String, now: LocalDateTime = LocalDateTime.now()): Long? {
            val match = Regex("""^\s*(\d{1,2})(?::(\d{2}))?(?::\d{2})?\s*$""").find(text) ?: return null
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
            if (hour > 23 || minute > 59) return null
            var at = now.toLocalDate().atTime(LocalTime.of(hour, minute))
            if (!at.isAfter(now)) at = at.plusDays(1)
            return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }

        @Volatile private var instance: DeviceAlarms? = null

        fun get(context: Context): DeviceAlarms =
            instance ?: synchronized(this) {
                instance ?: DeviceAlarms(context.applicationContext).also { instance = it }
            }
    }
}
