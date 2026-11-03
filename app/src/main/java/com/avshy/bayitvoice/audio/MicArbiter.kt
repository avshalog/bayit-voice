package com.avshy.bayitvoice.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes sure only one consumer owns the microphone: the wake-word service or a session.
 * The service listens only while [sessionActive] is false.
 */
object MicArbiter {

    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private val wakeCapturing = MutableStateFlow(false)

    /** True once the session UI has actually started using the microphone. */
    @Volatile
    var sessionClaimed: Boolean = false
        private set

    /** Called by the service on a detection, before the session UI exists. */
    fun requestSession() {
        sessionClaimed = false
        _sessionActive.value = true
    }

    /** Called by a session before it records; waits until the wake-word capture has released the mic. */
    suspend fun beginSession() {
        sessionClaimed = true
        _sessionActive.value = true
        withTimeoutOrNull(RELEASE_TIMEOUT_MS) { wakeCapturing.first { !it } }
    }

    fun endSession() {
        sessionClaimed = false
        _sessionActive.value = false
    }

    fun setWakeCapturing(capturing: Boolean) {
        wakeCapturing.value = capturing
    }

    private const val RELEASE_TIMEOUT_MS = 1_500L
}
