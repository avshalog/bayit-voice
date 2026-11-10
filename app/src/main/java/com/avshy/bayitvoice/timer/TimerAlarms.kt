package com.avshy.bayitvoice.timer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.avshy.bayitvoice.audio.AlarmSound
import com.avshy.bayitvoice.satellite.SatelliteDevice
import com.avshy.bayitvoice.satellite.proto.TimerEvent
import com.avshy.bayitvoice.satellite.proto.TimerEventType
import com.avshy.bayitvoice.service.SessionLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What rings: an HA timer, or one of this device's alarms or reminders (SPEC section 10.1). */
enum class RingKind { TIMER, ALARM, REMINDER }

/**
 * A timer HA set on this device, or an alarm or reminder while it rings. [endsAt] is in
 * `SystemClock.elapsedRealtime()` time. For an alarm or reminder, [name] is its label or text and
 * [clock] its time of day.
 */
data class ActiveTimer(
    val id: String,
    val name: String,
    val totalSeconds: Int,
    val endsAt: Long,
    /** Set while paused: what was left when it paused. */
    val pausedLeftMs: Long? = null,
    val ringing: Boolean = false,
    val kind: RingKind = RingKind.TIMER,
    val clock: String? = null,
) {
    fun leftMs(now: Long = SystemClock.elapsedRealtime()): Long = pausedLeftMs ?: (endsAt - now).coerceAtLeast(0)
}

/**
 * The timers HA set on this device (SPEC section 10). Each one also counts down here, so it
 * rings on time even if the connection to HA drops; a ringing timer plays [AlarmSound] and
 * opens the timer screen. Holds no UI: screens are addressed by class name.
 */
class TimerAlarms private constructor(private val context: Context) {

    private val _timers = MutableStateFlow<List<ActiveTimer>>(emptyList())
    val timers: StateFlow<List<ActiveTimer>> = _timers.asStateFlow()

    val isRinging: Boolean get() = _timers.value.any { it.ringing }

    private var scope: CoroutineScope? = null
    private var events: Job? = null
    private val countdowns = HashMap<String, Job>()
    private var sound: Job? = null

    /** Ids that rang or were dismissed here; HA's own late "finished" for them is ignored. */
    private val done = ArrayDeque<String>()

    /** Follows HA's timer events. Safe to call again; [scope] should outlive the satellite. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (events?.isActive == true) return
        this.scope = scope
        events = scope.launch { SatelliteDevice.get(context).timerEvents.collect { onEvent(it) } }
    }

    /** Debug builds: a timer as if HA had set it, to try the screens without HA. */
    fun simulate(scope: CoroutineScope, name: String, seconds: Int) {
        if (this.scope == null) this.scope = scope
        onEvent(TimerEvent(TimerEventType.STARTED, "debug-${SystemClock.elapsedRealtime()}", name, seconds, seconds, true))
    }

    @Synchronized
    private fun onEvent(event: TimerEvent) {
        val id = event.timerId
        when (event.type) {
            TimerEventType.STARTED, TimerEventType.UPDATED -> {
                if (id in done) return
                val now = SystemClock.elapsedRealtime()
                val leftMs = event.secondsLeft * 1000L
                val timer = ActiveTimer(
                    id = id,
                    name = event.name,
                    totalSeconds = event.totalSeconds,
                    endsAt = now + leftMs,
                    pausedLeftMs = if (event.isActive) null else leftMs,
                )
                _timers.update { list -> list.filterNot { it.id == id } + timer }
                countdowns.remove(id)?.cancel()
                if (event.isActive) countdowns[id] = scope!!.launch {
                    delay(leftMs)
                    ring(id)
                }
                showRunning()
            }
            TimerEventType.CANCELLED -> remove(id)
            TimerEventType.FINISHED -> ring(id)
            null -> Unit
        }
    }

    @Synchronized
    private fun ring(id: String) {
        val timer = _timers.value.firstOrNull { it.id == id } ?: return
        if (timer.ringing) return
        Log.i(TAG, "Timer '${timer.name}' finished")
        countdowns.remove(id)?.cancel()
        _timers.update { list -> list.map { if (it.id == id) it.copy(ringing = true, pausedLeftMs = null) else it } }
        if (sound?.isActive != true) sound = scope?.launch {
            AlarmSound.play(MAX_RING_MS)
            stopRinging()
        }
        SessionLauncher.openTimer(context)
    }

    /** Rings one of this device's alarms (until stopped) or reminders (a short chime). */
    @Synchronized
    fun ringAlarm(alarm: DeviceAlarm) {
        val kind = if (alarm.kind == AlarmKind.ALARM) RingKind.ALARM else RingKind.REMINDER
        val id = "alarm-${alarm.id}-${alarm.atMs}"
        Log.i(TAG, "${kind.name.lowercase()} #${alarm.id} at ${alarm.clock} is due")
        val entry = ActiveTimer(id, alarm.text, 0, SystemClock.elapsedRealtime(), ringing = true, kind = kind, clock = alarm.clock)
        _timers.update { list -> list.filterNot { it.id == id } + entry }
        val scope = scope ?: return
        if (kind == RingKind.ALARM) {
            sound?.cancel()
            sound = scope.launch {
                AlarmSound.play(MAX_ALARM_RING_MS)
                stopRinging()
            }
        } else {
            if (sound?.isActive != true) scope.launch { AlarmSound.play(REMINDER_CHIME_MS) }
            // A reminder does not keep ringing; its screen closes by itself after a while.
            countdowns[id] = scope.launch {
                delay(MAX_ALARM_RING_MS)
                remove(id)
            }
        }
        SessionLauncher.openTimer(context)
    }

    /** Snooze or "remind me later": what rings now comes back in [minutes] as a new one. */
    @Synchronized
    fun snoozeRinging(minutes: Int) {
        val alarms = DeviceAlarms.get(context)
        val at = System.currentTimeMillis() + minutes * 60_000L
        for (t in _timers.value.filter { it.ringing && it.kind != RingKind.TIMER }) {
            alarms.add(if (t.kind == RingKind.ALARM) AlarmKind.ALARM else AlarmKind.REMINDER, at, t.name)
        }
        stopRinging()
    }

    /** Stops the sound and clears every ringing timer. */
    @Synchronized
    fun stopRinging() {
        sound?.cancel()
        sound = null
        _timers.value.filter { it.ringing }.forEach { remove(it.id) }
    }

    /** Dismisses a timer on this device only; HA still counts it, but its end is ignored here. */
    @Synchronized
    fun dismiss(id: String) = remove(id)

    private fun remove(id: String) {
        countdowns.remove(id)?.cancel()
        _timers.update { list -> list.filterNot { it.id == id } }
        done.addLast(id)
        while (done.size > MAX_DONE) done.removeFirst()
        if (_timers.value.none { it.ringing }) {
            sound?.cancel()
            sound = null
        }
    }

    /** The small "timer running" chip over whatever is on screen. */
    private fun showRunning() {
        runCatching {
            context.startService(Intent().setComponent(ComponentName(context, TIMER_OVERLAY_SERVICE)))
        }.onFailure { Log.w(TAG, "Cannot show the timer chip: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "TimerAlarms"
        private const val TIMER_OVERLAY_SERVICE = "com.avshy.bayitvoice.ui.timer.TimerOverlayService"

        /** A ringing timer stops by itself after this long. */
        private const val MAX_RING_MS = 5 * 60_000L
        private const val MAX_DONE = 32

        /** An alarm stops by itself after this long, and a reminder's screen closes. */
        private const val MAX_ALARM_RING_MS = 10 * 60_000L
        private const val REMINDER_CHIME_MS = 2_500L

        @Volatile private var instance: TimerAlarms? = null

        fun get(context: Context): TimerAlarms =
            instance ?: synchronized(this) {
                instance ?: TimerAlarms(context.applicationContext).also { instance = it }
            }
    }
}
