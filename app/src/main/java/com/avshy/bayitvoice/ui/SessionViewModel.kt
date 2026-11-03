package com.avshy.bayitvoice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.NetworkInfo
import com.avshy.bayitvoice.ha.ServerSelector
import com.avshy.bayitvoice.session.AssistSession
import com.avshy.bayitvoice.session.SessionEnd
import com.avshy.bayitvoice.session.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings.get(app)
    private val session = AssistSession(settings, ServerSelector(settings, NetworkInfo(app)))

    val state: StateFlow<SessionState> = session.state

    private val _closeRequested = MutableStateFlow(false)
    val closeRequested: StateFlow<Boolean> = _closeRequested.asStateFlow()

    private var conversation: Job? = null
    private var autoClose: Job? = null

    /** Starts (or restarts) a conversation with a manual turn. */
    fun startConversation() {
        cancelAutoClose()
        conversation?.cancel()
        conversation = viewModelScope.launch {
            when (session.converse()) {
                SessionEnd.Completed -> scheduleAutoClose()
                SessionEnd.FollowUpSilent -> _closeRequested.value = true
                SessionEnd.Failed -> Unit
            }
        }
    }

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
        session.close()
    }
}
