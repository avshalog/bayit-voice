package com.avshy.bayitvoice.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avshy.bayitvoice.R

/** Onboarding and settings. */
class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BayitTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    onOpenAssistant = { startActivity(SessionActivity.intent(this)) },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: SettingsUiState, viewModel: SettingsViewModel, onOpenAssistant: () -> Unit) {
    var token by rememberSaveable { mutableStateOf("") }

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

            val connected = state.status is ConnectionStatus.Connected
            Button(onClick = onOpenAssistant, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_open_assistant))
            }

            SectionTitle(R.string.settings_section_server)
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::onUrlChange,
                label = { Text(stringResource(R.string.settings_url_label)) },
                placeholder = { Text(stringResource(R.string.settings_url_hint)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.isPlainHttp) {
                Text(stringResource(R.string.settings_http_warning), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }

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
                    onClick = {
                        viewModel.testConnection(token)
                        token = ""
                    },
                    enabled = state.status != ConnectionStatus.Testing,
                ) { Text(stringResource(R.string.settings_test_connection)) }
                if (state.hasSavedToken) {
                    OutlinedButton(onClick = viewModel::signOut) { Text(stringResource(R.string.settings_sign_out)) }
                }
            }
            ConnectionStatusText(state.status)

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
        }
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ConnectionStatusText(status: ConnectionStatus) {
    when (status) {
        ConnectionStatus.Unknown -> Unit
        ConnectionStatus.Testing -> Text(stringResource(R.string.settings_testing))
        is ConnectionStatus.Connected -> {
            Text(stringResource(R.string.settings_connected, status.haVersion), color = MaterialTheme.colorScheme.primary)
            if (!status.supportsFollowUp) {
                Text(stringResource(R.string.notice_old_ha_version), color = MaterialTheme.colorScheme.tertiary)
            }
        }
        is ConnectionStatus.Failed -> Text(errorText(status.error), color = MaterialTheme.colorScheme.error)
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
