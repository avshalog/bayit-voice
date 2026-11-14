package com.avshy.bayitvoice.service

import android.content.Context
import com.avshy.bayitvoice.config.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Satellite mode: whether the service listens for the wake word (the "Wake word listening" switch
 * in HA and in settings). Paused, the service and its link to HA keep running, so HA can switch
 * listening back on; only the microphone capture stops. The state survives restarts.
 */
object ListenGate {
    private var flow: MutableStateFlow<Boolean>? = null

    private fun flow(context: Context): MutableStateFlow<Boolean> =
        flow ?: synchronized(this) {
            flow ?: MutableStateFlow(AppSettings.get(context).wakeListenPaused).also { flow = it }
        }

    fun paused(context: Context): StateFlow<Boolean> = flow(context)

    fun setPaused(context: Context, paused: Boolean) {
        AppSettings.get(context).wakeListenPaused = paused
        flow(context).value = paused
    }
}
