package com.avshy.bayitvoice.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.avshy.bayitvoice.arbitration.SessionArbitration
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.ConnectionMode
import com.avshy.bayitvoice.ha.ConnectionManager
import com.avshy.bayitvoice.service.WakeWordService
import com.avshy.bayitvoice.wakeword.WakeWordModel
import com.avshy.bayitvoice.wakeword.WakeWordModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WakeWordUiState(
    val enabled: Boolean = false,
    val models: List<WakeWordModel> = emptyList(),
    val selectedModelId: String = WakeWordModels.DEFAULT_MODEL_ID,
    val threshold: Float = AppSettings.DEFAULT_WAKE_THRESHOLD,
    val startOnBoot: Boolean = false,
    val connectionMode: ConnectionMode = ConnectionMode.KeepConnected,
    val canOpenOverLockScreen: Boolean = false,
    val ignoresBatteryOptimization: Boolean = false,
    val importing: Boolean = false,
    val importFailed: Boolean = false,
    val loadFailed: Boolean = false,
    val arbitrationEnabled: Boolean = true,
    /** The name set in the app; empty = the system's name, shown as [systemDeviceName]. */
    val deviceName: String = "",
    val systemDeviceName: String = "",
)

/** Wake-word, background and connection-mode settings. */
class WakeWordViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings.get(app)
    private val models = WakeWordModels(app)

    private val _state = MutableStateFlow(WakeWordUiState())
    val state: StateFlow<WakeWordUiState> = _state.asStateFlow()

    init {
        reload()
    }

    /** Re-reads settings and system permissions; call when the screen resumes. */
    fun refresh() {
        reload()
        // Restart listening if it was switched on but the service is gone (app update, process death).
        if (settings.wakeWordEnabled && !WakeWordService.isRunning && hasMicPermission()) {
            WakeWordService.start(getApplication())
        }
    }

    /** Call after the needed runtime permissions were requested. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && !hasMicPermission()) return
        settings.wakeWordEnabled = enabled
        if (enabled) settings.wakeModelLoadFailed = false
        if (enabled) WakeWordService.start(getApplication()) else WakeWordService.stop(getApplication())
        reload()
    }

    fun selectModel(id: String) {
        settings.wakeModelId = id
        WakeWordService.reload(getApplication())
        reload()
    }

    fun setThreshold(value: Float) {
        _state.update { it.copy(threshold = value) }
    }

    /** Applies the threshold once the slider is released. */
    fun commitThreshold() {
        settings.wakeThreshold = _state.value.threshold
        WakeWordService.reload(getApplication())
    }

    fun setStartOnBoot(enabled: Boolean) {
        settings.startOnBoot = enabled
        reload()
    }

    fun setConnectionMode(mode: ConnectionMode) {
        settings.connectionMode = mode
        if (mode == ConnectionMode.OnDemand) ConnectionManager.shared(getApplication()).reset()
        WakeWordService.reload(getApplication())
        reload()
    }

    fun setArbitrationEnabled(enabled: Boolean) {
        settings.arbitrationEnabled = enabled
        reload()
    }

    /** A blank name goes back to the system's device name. */
    fun setDeviceName(name: String) {
        settings.deviceName = name.takeIf { it.isNotBlank() && it.trim() != _state.value.systemDeviceName }
        reload()
    }

    fun importModel(uri: Uri, name: String) {
        _state.update { it.copy(importing = true, importFailed = false) }
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching { models.import(uri, name) }.getOrNull()
            }
            _state.update { it.copy(importing = false, importFailed = imported == null) }
            if (imported != null) selectModel(imported.id) else reload()
        }
    }

    fun renameModel(id: String, name: String) {
        if (name.isBlank()) return
        models.rename(id, name)
        WakeWordService.reload(getApplication())
        reload()
    }

    fun deleteModel(id: String) {
        models.delete(id)
        if (settings.wakeModelId == id) settings.wakeModelId = WakeWordModels.DEFAULT_MODEL_ID
        WakeWordService.reload(getApplication())
        reload()
    }

    private fun reload() {
        val context = getApplication<Application>()
        val power = context.getSystemService(PowerManager::class.java)
        val all = models.all()
        _state.update {
            it.copy(
                enabled = settings.wakeWordEnabled,
                models = all,
                selectedModelId = settings.wakeModelId.takeIf { id -> all.any { m -> m.id == id } }
                    ?: WakeWordModels.DEFAULT_MODEL_ID,
                threshold = settings.wakeThreshold,
                startOnBoot = settings.startOnBoot,
                connectionMode = settings.connectionMode,
                canOpenOverLockScreen = Settings.canDrawOverlays(context),
                ignoresBatteryOptimization = power.isIgnoringBatteryOptimizations(context.packageName),
                loadFailed = settings.wakeModelLoadFailed,
                arbitrationEnabled = settings.arbitrationEnabled,
                deviceName = settings.deviceName.orEmpty(),
                systemDeviceName = SessionArbitration.systemDeviceName(context),
            )
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
