package com.avshy.bayitvoice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.ServerUrl
import com.avshy.bayitvoice.ha.HaClient
import com.avshy.bayitvoice.ha.HaException
import com.avshy.bayitvoice.ha.HaVersion
import com.avshy.bayitvoice.ha.Pipeline
import com.avshy.bayitvoice.session.SessionError
import com.avshy.bayitvoice.session.toSessionError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionStatus {
    data object Unknown : ConnectionStatus
    data object Testing : ConnectionStatus
    data class Connected(val haVersion: String, val supportsFollowUp: Boolean) : ConnectionStatus
    data class Failed(val error: SessionError) : ConnectionStatus
}

data class SettingsUiState(
    val url: String = "",
    val hasSavedToken: Boolean = false,
    val status: ConnectionStatus = ConnectionStatus.Unknown,
    val pipelines: List<Pipeline> = emptyList(),
    val preferredPipelineId: String? = null,
    val selectedPipelineId: String? = null,
    val pipelineMissing: Boolean = false,
    val autoCloseSeconds: Int = AppSettings.DEFAULT_AUTO_CLOSE_SECONDS,
    val chimeEnabled: Boolean = true,
) {
    val isPlainHttp: Boolean get() = ServerUrl.parse(url)?.isHttps == false
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings.get(app)

    private val _state = MutableStateFlow(
        SettingsUiState(
            url = settings.serverUrl.orEmpty(),
            hasSavedToken = !settings.accessToken.isNullOrEmpty(),
            selectedPipelineId = settings.pipelineId,
            autoCloseSeconds = settings.autoCloseSeconds,
            chimeEnabled = settings.chimeEnabled,
        )
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        if (settings.isConfigured) testConnection(newToken = "")
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value) }

    /**
     * Connects with the entered URL and [newToken] (or the saved token when blank),
     * loads the pipelines and saves URL and token on success.
     */
    fun testConnection(newToken: String) {
        val server = ServerUrl.parse(_state.value.url)
        val token = newToken.trim().ifEmpty { settings.accessToken.orEmpty() }
        if (server == null) return fail(SessionError.InvalidUrl)
        if (!server.isAllowed) return fail(SessionError.InsecureUrl)
        if (token.isEmpty()) return fail(SessionError.NotConfigured)
        _state.update { it.copy(status = ConnectionStatus.Testing) }
        viewModelScope.launch {
            val client = HaClient()
            try {
                val version = client.connect(server.webSocketUrl, token)
                val list = client.listPipelines()
                settings.serverUrl = server.base
                settings.accessToken = token
                settings.lastHaVersion = version

                var selected = settings.pipelineId
                val missing = selected != null && list.pipelines.none { it.id == selected }
                if (missing) {
                    selected = null
                    settings.pipelineId = null
                }
                _state.update {
                    it.copy(
                        url = server.base,
                        hasSavedToken = true,
                        status = ConnectionStatus.Connected(version, HaVersion.supportsContinueConversation(version)),
                        pipelines = list.pipelines,
                        preferredPipelineId = list.preferredId,
                        selectedPipelineId = selected,
                        pipelineMissing = missing,
                    )
                }
            } catch (e: HaException) {
                fail(e.toSessionError())
            } finally {
                client.close()
            }
        }
    }

    fun selectPipeline(id: String?) {
        settings.pipelineId = id
        _state.update { it.copy(selectedPipelineId = id, pipelineMissing = false) }
    }

    fun setAutoCloseSeconds(seconds: Int) {
        settings.autoCloseSeconds = seconds
        _state.update { it.copy(autoCloseSeconds = settings.autoCloseSeconds) }
    }

    fun setChimeEnabled(enabled: Boolean) {
        settings.chimeEnabled = enabled
        _state.update { it.copy(chimeEnabled = enabled) }
    }

    fun signOut() {
        settings.signOut()
        _state.update {
            it.copy(hasSavedToken = false, status = ConnectionStatus.Unknown, pipelines = emptyList(), preferredPipelineId = null)
        }
    }

    private fun fail(error: SessionError) = _state.update { it.copy(status = ConnectionStatus.Failed(error)) }
}
