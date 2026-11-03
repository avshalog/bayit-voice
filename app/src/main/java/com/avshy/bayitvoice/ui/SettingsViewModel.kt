package com.avshy.bayitvoice.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.NetworkInfo
import com.avshy.bayitvoice.config.ServerUrl
import com.avshy.bayitvoice.ha.ConnectionManager
import com.avshy.bayitvoice.ha.HaClient
import com.avshy.bayitvoice.ha.HaException
import com.avshy.bayitvoice.ha.HaVersion
import com.avshy.bayitvoice.ha.PipelineList
import com.avshy.bayitvoice.ha.Pipeline
import com.avshy.bayitvoice.service.WakeWordService
import com.avshy.bayitvoice.session.SessionError
import com.avshy.bayitvoice.session.toSessionError
import kotlinx.coroutines.async
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
    val homeUrl: String = "",
    val externalUrl: String = "",
    val homeSsids: List<String> = emptyList(),
    val hasSavedToken: Boolean = false,
    val homeStatus: ConnectionStatus = ConnectionStatus.Unknown,
    val externalStatus: ConnectionStatus = ConnectionStatus.Unknown,
    /** Error that is not tied to one address (missing token, no address). */
    val formError: SessionError? = null,
    val ssidUnavailable: Boolean = false,
    val pipelines: List<Pipeline> = emptyList(),
    val preferredPipelineId: String? = null,
    val selectedPipelineId: String? = null,
    val pipelineMissing: Boolean = false,
    val autoCloseSeconds: Int = AppSettings.DEFAULT_AUTO_CLOSE_SECONDS,
    val chimeEnabled: Boolean = true,
) {
    val isConnected: Boolean
        get() = homeStatus is ConnectionStatus.Connected || externalStatus is ConnectionStatus.Connected

    val isTesting: Boolean
        get() = homeStatus == ConnectionStatus.Testing || externalStatus == ConnectionStatus.Testing

    val isHomePlainHttp: Boolean get() = ServerUrl.parse(homeUrl)?.isHttps == false
    val isExternalPlainHttp: Boolean get() = ServerUrl.parse(externalUrl)?.isHttps == false
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings.get(app)
    private val network = NetworkInfo(app)

    private val _state = MutableStateFlow(
        SettingsUiState(
            homeUrl = settings.homeUrl.orEmpty(),
            externalUrl = settings.externalUrl.orEmpty(),
            homeSsids = settings.homeSsids.sorted(),
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

    fun onHomeUrlChange(value: String) = _state.update { it.copy(homeUrl = value) }

    fun onExternalUrlChange(value: String) = _state.update { it.copy(externalUrl = value) }

    /**
     * Tests each entered address with [newToken] (or the saved token when blank). Saves the
     * addresses and token when at least one address connects, and loads the pipelines.
     */
    fun testConnection(newToken: String) {
        val current = _state.value
        val token = newToken.trim().ifEmpty { settings.accessToken.orEmpty() }
        val homeText = current.homeUrl.trim()
        val externalText = current.externalUrl.trim()
        val home = ServerUrl.parse(homeText)
        val external = ServerUrl.parse(externalText)

        val homeError = validate(homeText, home)
        val externalError = validate(externalText, external)
        val formError = when {
            homeText.isEmpty() && externalText.isEmpty() -> SessionError.InvalidUrl
            token.isEmpty() -> SessionError.NotConfigured
            else -> null
        }
        _state.update {
            it.copy(
                formError = formError,
                homeStatus = statusBeforeTest(homeText, homeError),
                externalStatus = statusBeforeTest(externalText, externalError),
            )
        }
        if (formError != null) return

        viewModelScope.launch {
            val homeTest = async {
                when {
                    homeError != null || home == null -> null
                    !network.hasLocalNetworkPermission ->
                        TestResult(ConnectionStatus.Failed(SessionError.LocalNetworkDenied), null)
                    else -> test(home, token)
                }
            }
            val externalTest = async { if (externalError == null && external != null) test(external, token) else null }
            val homeResult = homeTest.await()
            val externalResult = externalTest.await()

            _state.update {
                it.copy(
                    homeStatus = homeResult?.status ?: it.homeStatus,
                    externalStatus = externalResult?.status ?: it.externalStatus,
                )
            }
            val pipelines = homeResult?.pipelines ?: externalResult?.pipelines ?: return@launch

            // Save only addresses that are valid; an unreachable address is still kept, since
            // it may simply be unreachable from where the device is right now.
            settings.homeUrl = home?.base?.takeIf { homeError == null }
            settings.externalUrl = external?.base?.takeIf { externalError == null }
            settings.accessToken = token
            onCredentialsChanged()
            val version = (homeResult?.status ?: externalResult?.status) as? ConnectionStatus.Connected
            settings.lastHaVersion = version?.haVersion
            applyPipelines(pipelines)
            _state.update {
                it.copy(
                    homeUrl = settings.homeUrl.orEmpty(),
                    externalUrl = settings.externalUrl.orEmpty(),
                    hasSavedToken = true,
                )
            }
        }
    }

    fun selectPipeline(id: String?) {
        settings.pipelineId = id
        _state.update { it.copy(selectedPipelineId = id, pipelineMissing = false) }
    }

    /** Adds the current Wi-Fi network to the home networks. Call after location permission is granted. */
    fun addCurrentNetwork() {
        val ssid = network.currentSsid()
        if (ssid == null) {
            _state.update { it.copy(ssidUnavailable = true) }
            return
        }
        settings.homeSsids = settings.homeSsids + ssid
        _state.update { it.copy(homeSsids = settings.homeSsids.sorted(), ssidUnavailable = false) }
    }

    fun removeNetwork(ssid: String) {
        settings.homeSsids = settings.homeSsids - ssid
        _state.update { it.copy(homeSsids = settings.homeSsids.sorted()) }
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
        onCredentialsChanged()
        _state.update {
            it.copy(
                hasSavedToken = false,
                homeStatus = ConnectionStatus.Unknown,
                externalStatus = ConnectionStatus.Unknown,
                pipelines = emptyList(),
                preferredPipelineId = null,
            )
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }

    /** The kept connection and the service must pick up a new address or token. */
    private fun onCredentialsChanged() {
        ConnectionManager.shared(getApplication()).reset()
        WakeWordService.reload(getApplication())
    }

    private class TestResult(val status: ConnectionStatus, val pipelines: PipelineList?)

    private suspend fun test(server: ServerUrl, token: String): TestResult {
        val client = HaClient()
        return try {
            val version = client.connect(server.webSocketUrl, token)
            val pipelines = client.listPipelines()
            TestResult(ConnectionStatus.Connected(version, HaVersion.supportsContinueConversation(version)), pipelines)
        } catch (e: HaException) {
            Log.w(TAG, "Test of ${server.host} failed: ${e.javaClass.simpleName}: ${e.cause}")
            TestResult(ConnectionStatus.Failed(e.toSessionError()), null)
        } finally {
            client.close()
        }
    }

    private fun applyPipelines(list: PipelineList) {
        var selected = settings.pipelineId
        val missing = selected != null && list.pipelines.none { it.id == selected }
        if (missing) {
            selected = null
            settings.pipelineId = null
        }
        _state.update {
            it.copy(
                pipelines = list.pipelines,
                preferredPipelineId = list.preferredId,
                selectedPipelineId = selected,
                pipelineMissing = missing,
            )
        }
    }

    private fun validate(text: String, server: ServerUrl?): SessionError? = when {
        text.isEmpty() -> null
        server == null -> SessionError.InvalidUrl
        !server.isAllowed -> SessionError.InsecureUrl
        else -> null
    }

    private fun statusBeforeTest(text: String, error: SessionError?): ConnectionStatus = when {
        text.isEmpty() -> ConnectionStatus.Unknown
        error != null -> ConnectionStatus.Failed(error)
        else -> ConnectionStatus.Testing
    }
}
