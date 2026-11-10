package com.avshy.bayitvoice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.avshy.bayitvoice.arbitration.SessionArbitration
import com.avshy.bayitvoice.audio.MicArbiter
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.ConnectionMode
import com.avshy.bayitvoice.config.NetworkInfo
import com.avshy.bayitvoice.ha.ConnectionManager
import com.avshy.bayitvoice.satellite.SatelliteDevice
import com.avshy.bayitvoice.timer.PhoneAlarms
import com.avshy.bayitvoice.session.AssistSession
import com.avshy.bayitvoice.session.SessionEnd
import com.avshy.bayitvoice.session.SessionState
import com.avshy.bayitvoice.session.WakeDetection
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings.get(app)

    /** "Keep connected" shares the service's connection; "on demand" owns one per session UI. */
    private val ownConnection = settings.connectionMode == ConnectionMode.OnDemand
    private val connections =
        if (ownConnection) ConnectionManager.create(app) else ConnectionManager.shared(app)
    private val session = AssistSession(
        settings, connections, SatelliteDevice.get(app),
        onAlarmRequest = { PhoneAlarms.handle(app, it) },
    )

    val state: StateFlow<SessionState> = session.state

    private val _closeRequested = MutableStateFlow(false)
    val closeRequested: StateFlow<Boolean> = _closeRequested.asStateFlow()

    private var conversation: Job? = null
    private var autoClose: Job? = null

    /** The current turn is over and the UI is only showing its result. */
    private var ended = false

    /** Someone touched the UI during this turn. */
    private var touched = false

    /**
     * Starts (or restarts) a conversation. [fromWakeWord] plays the listening chime first.
     * The wake-word service is paused while the conversation owns the microphone.
     */
    fun startConversation(fromWakeWord: Boolean = false, detection: WakeDetection? = null) {
        cancelAutoClose()
        ended = false
        touched = false
        _closeRequested.value = false
        val previous = conversation
        // Only a session opened by the wake word is arbitrated with other devices.
        val arbitration = SessionArbitration.forSession(getApplication(), detection)
        conversation = viewModelScope.launch {
            previous?.cancelAndJoin()
            val end = try {
                MicArbiter.beginSession()
                session.converse(fromWakeWord, detection, arbitration)
            } finally {
                MicArbiter.endSession()
            }
            when (end) {
                SessionEnd.Completed -> {
                    ended = true
                    scheduleAutoClose(if (touched) idleCloseSeconds() else settings.autoCloseSeconds)
                }
                SessionEnd.Silent -> _closeRequested.value = true
                SessionEnd.Yielded -> {
                    // Show which device is answering, then step aside.
                    delay(ELSEWHERE_NOTICE_MS)
                    _closeRequested.value = true
                }
                SessionEnd.Failed -> {
                    // Long enough to read the error, but never left on screen for good.
                    ended = true
                    scheduleAutoClose(idleCloseSeconds())
                }
            }
        }
    }

    /** Permissions needed to reach the home address, if one is configured. */
    fun localNetworkPermissions(): Array<String> =
        if (settings.homeServer != null) NetworkInfo.localNetworkPermissions() else emptyArray()

    /**
     * A touch keeps the UI open for a while, not for good: the idle timer restarts, so a screen
     * someone touched and walked away from still closes.
     */
    fun onUserTouch() {
        touched = true
        if (ended) scheduleAutoClose(idleCloseSeconds())
    }

    private fun cancelAutoClose() {
        autoClose?.cancel()
        autoClose = null
    }

    /** A touched or failed session waits at least [IDLE_CLOSE_SECONDS]; 0 in settings still means never. */
    private fun idleCloseSeconds(): Int {
        val seconds = settings.autoCloseSeconds
        return if (seconds <= 0) 0 else maxOf(seconds, IDLE_CLOSE_SECONDS)
    }

    private fun scheduleAutoClose(seconds: Int) {
        cancelAutoClose()
        if (seconds <= 0) return
        autoClose = viewModelScope.launch {
            delay(seconds * 1000L)
            _closeRequested.value = true
        }
    }

    override fun onCleared() {
        if (ownConnection) connections.reset()
    }

    private companion object {
        const val ELSEWHERE_NOTICE_MS = 1_500L
        const val IDLE_CLOSE_SECONDS = 20
    }
}
