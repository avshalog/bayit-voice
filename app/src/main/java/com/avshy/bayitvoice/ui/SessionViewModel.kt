package com.avshy.bayitvoice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.avshy.bayitvoice.audio.MicArbiter
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.ConnectionMode
import com.avshy.bayitvoice.config.NetworkInfo
import com.avshy.bayitvoice.ha.ConnectionManager
import com.avshy.bayitvoice.session.AssistSession
import com.avshy.bayitvoice.session.SessionEnd
import com.avshy.bayitvoice.session.SessionState
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
    private val session = AssistSession(settings, connections)

    val state: StateFlow<SessionState> = session.state

    private val _closeRequested = MutableStateFlow(false)
    val closeRequested: StateFlow<Boolean> = _closeRequested.asStateFlow()

    private var conversation: Job? = null
    private var autoClose: Job? = null

    /**
     * Starts (or restarts) a conversation. [fromWakeWord] plays the listening chime first.
     * The wake-word service is paused while the conversation owns the microphone.
     */
    fun startConversation(fromWakeWord: Boolean = false) {
        cancelAutoClose()
        _closeRequested.value = false
        val previous = conversation
        conversation = viewModelScope.launch {
            previous?.cancelAndJoin()
            val end = try {
                MicArbiter.beginSession()
                session.converse(fromWakeWord)
            } finally {
                MicArbiter.endSession()
            }
            when (end) {
                SessionEnd.Completed -> scheduleAutoClose()
                SessionEnd.Silent -> _closeRequested.value = true
                SessionEnd.Failed -> Unit
            }
        }
    }

    /** Permissions needed to reach the home address, if one is configured. */
    fun localNetworkPermissions(): Array<String> =
        if (settings.homeServer != null) NetworkInfo.localNetworkPermissions() else emptyArray()

    /** Any touch on the UI cancels a pending auto-close. */
    fun cancelAutoClose() {
        autoClose?.cancel()
        autoClose = null
    }

    private fun scheduleAutoClose() {
        val seconds = settings.autoCloseSeconds
        if (seconds <= 0) return
        autoClose = viewModelScope.launch {
            delay(seconds * 1000L)
            _closeRequested.value = true
        }
    }

    override fun onCleared() {
        if (ownConnection) connections.reset()
    }
}
