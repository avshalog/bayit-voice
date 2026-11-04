package com.avshy.bayitvoice.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.config.NetworkInfo

/** Onboarding and settings. */
class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()
    private val wakeViewModel: WakeWordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BayitTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Settings) }
                BackHandler(enabled = screen != Screen.Settings) { screen = Screen.Settings }
                LifecycleResumeEffect(Unit) {
                    wakeViewModel.refresh()
                    onPauseOrDispose { }
                }
                when (screen) {
                    Screen.Settings -> {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val wakeState by wakeViewModel.state.collectAsStateWithLifecycle()
                        SettingsScreen(
                            state = state,
                            viewModel = viewModel,
                            wakeState = wakeState,
                            wakeViewModel = wakeViewModel,
                            onOpenAssistant = { startActivity(SessionActivity.intent(this)) },
                            onOpenGuide = { screen = Screen.Guide },
                            onOpenAbout = { screen = Screen.About },
                        )
                    }
                    Screen.Guide -> TrainGuideScreen(onBack = { screen = Screen.Settings })
                    Screen.About -> AboutScreen(onBack = { screen = Screen.Settings })
                }
            }
        }
    }

    private enum class Screen { Settings, Guide, About }
}

@Composable
private fun SettingsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    wakeState: WakeWordUiState,
    wakeViewModel: WakeWordViewModel,
    onOpenAssistant: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }
    var pendingToken by remember { mutableStateOf("") }
    // Android 17+ needs a runtime grant to reach the home address; test either way.
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.testConnection(pendingToken)
    }
    val context = LocalContext.current
    val testConnection = {
        val needed = NetworkInfo.localNetworkPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (state.homeUrl.isNotBlank() && needed.isNotEmpty()) {
            pendingToken = token
            localNetworkPermission.launch(needed.toTypedArray())
        } else {
            viewModel.testConnection(token)
        }
        token = ""
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

            val connected = state.isConnected
            Button(onClick = onOpenAssistant, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_open_assistant))
            }

            SectionTitle(R.string.settings_section_server)
            UrlField(
                value = state.homeUrl,
                onChange = viewModel::onHomeUrlChange,
                label = R.string.settings_home_url_label,
                hint = R.string.settings_home_url_hint,
                plainHttp = state.isHomePlainHttp,
                status = state.homeStatus,
            )
            UrlField(
                value = state.externalUrl,
                onChange = viewModel::onExternalUrlChange,
                label = R.string.settings_external_url_label,
                hint = R.string.settings_external_url_hint,
                plainHttp = state.isExternalPlainHttp,
                status = state.externalStatus,
            )
            Text(stringResource(R.string.settings_urls_help), style = MaterialTheme.typography.bodySmall)

            HomeNetworks(state, viewModel)

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.settings_token_label)) },
                placeholder = {
                    if (state.hasSavedToken) Text(stringResource(R.string.settings_token_saved))
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.settings_token_help), style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = testConnection,
                    enabled = !state.isTesting,
                ) { Text(stringResource(R.string.settings_test_connection)) }
                if (state.hasSavedToken) {
                    OutlinedButton(onClick = viewModel::signOut) { Text(stringResource(R.string.settings_sign_out)) }
                }
            }
            state.formError?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            val version = (state.homeStatus as? ConnectionStatus.Connected) ?: (state.externalStatus as? ConnectionStatus.Connected)
            if (version != null && !version.supportsFollowUp) {
                Text(stringResource(R.string.notice_old_ha_version), color = MaterialTheme.colorScheme.tertiary)
            }

            if (connected) {
                HorizontalDivider()
                SectionTitle(R.string.settings_section_pipeline)
                PipelineOption(
                    title = stringResource(R.string.settings_pipeline_default),
                    subtitle = state.pipelines.firstOrNull { it.id == state.preferredPipelineId }
                        ?.let { stringResource(R.string.settings_pipeline_currently, it.name) },
                    selected = state.selectedPipelineId == null,
                    onSelect = { viewModel.selectPipeline(null) },
                )
                state.pipelines.forEach { pipeline ->
                    PipelineOption(
                        title = pipeline.name,
                        subtitle = pipeline.language,
                        selected = state.selectedPipelineId == pipeline.id,
                        onSelect = { viewModel.selectPipeline(pipeline.id) },
                    )
                }
                if (state.pipelineMissing) {
                    Text(stringResource(R.string.notice_pipeline_missing), color = MaterialTheme.colorScheme.tertiary)
                }
            }

            HorizontalDivider()
            SectionTitle(R.string.settings_section_behaviour)
            AutoCloseField(state.autoCloseSeconds, viewModel::setAutoCloseSeconds)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_chime), modifier = Modifier.weight(1f))
                Switch(checked = state.chimeEnabled, onCheckedChange = viewModel::setChimeEnabled)
            }

            HorizontalDivider()
            SectionTitle(R.string.settings_section_wake_word)
            WakeWordSettings(
                state = wakeState,
                viewModel = wakeViewModel,
                hasHomeAddress = state.homeUrl.isNotBlank(),
                onOpenGuide = onOpenGuide,
            )

            HorizontalDivider()
            TextButton(onClick = onOpenAbout) { Text(stringResource(R.string.about_title)) }
        }
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun UrlField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    hint: Int,
    plainHttp: Boolean,
    status: ConnectionStatus,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(label)) },
            placeholder = { Text(stringResource(hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (plainHttp) {
            Text(stringResource(R.string.settings_http_warning), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        ConnectionStatusText(status)
    }
}

@Composable
private fun ConnectionStatusText(status: ConnectionStatus) {
    when (status) {
        ConnectionStatus.Unknown -> Unit
        ConnectionStatus.Testing -> Text(stringResource(R.string.settings_testing))
        is ConnectionStatus.Connected ->
            Text(stringResource(R.string.settings_connected, status.haVersion), color = MaterialTheme.colorScheme.primary)
        is ConnectionStatus.Failed -> Text(errorText(status.error), color = MaterialTheme.colorScheme.error)
        ConnectionStatus.AwayFromHome -> Text(
            stringResource(R.string.settings_away_from_home),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HomeNetworks(state: SettingsUiState, viewModel: SettingsViewModel) {
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) viewModel.addCurrentNetwork()
    }
    Text(stringResource(R.string.settings_home_networks), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.settings_home_networks_help), style = MaterialTheme.typography.bodySmall)
    state.homeSsids.forEach { ssid ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(ssid, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content), modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.removeNetwork(ssid) }) { Text(stringResource(R.string.settings_remove)) }
        }
    }
    OutlinedButton(onClick = {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }) { Text(stringResource(R.string.settings_add_current_network)) }
    if (state.ssidUnavailable) {
        Text(stringResource(R.string.settings_ssid_unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PipelineOption(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
            }
        }
    }
}

@Composable
private fun AutoCloseField(seconds: Int, onChange: (Int) -> Unit) {
    var text by remember(seconds) { mutableStateOf(seconds.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value.filter(Char::isDigit).take(3)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(stringResource(R.string.settings_auto_close_label)) },
        supportingText = { Text(stringResource(R.string.settings_auto_close_help)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
