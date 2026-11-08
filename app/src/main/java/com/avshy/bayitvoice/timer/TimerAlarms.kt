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

/** A timer HA set on this device. [endsAt] is in `SystemClock.elapsedRealtime()` time. */
data class ActiveTimer(
    val id: String,
    val name: String,
    val totalSeconds: Int,
    val endsAt: Long,
    /** Set while paused: what was left when it paused. */
    val pausedLeftMs: Long? = null,
    val ringing: Boolean = false,
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

        @Volatile private var instance: TimerAlarms? = null

        fun get(context: Context): TimerAlarms =
            instance ?: synchronized(this) {
                instance ?: TimerAlarms(context.applicationContext).also { instance = it }
            }
    }
}
