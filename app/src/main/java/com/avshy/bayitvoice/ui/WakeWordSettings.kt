package com.avshy.bayitvoice.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.config.ConnectionMode
import com.avshy.bayitvoice.config.NetworkInfo
import com.avshy.bayitvoice.wakeword.WakeWordModel

/** Settings for the wake word, background behaviour and connection mode. */
@Composable
fun WakeWordSettings(
    state: WakeWordUiState,
    viewModel: WakeWordViewModel,
    hasHomeAddress: Boolean,
    onOpenGuide: () -> Unit,
) {
    val context = LocalContext.current
    val enablePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.setEnabled(true)
    }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importUri = uri
    }
    var renaming by remember { mutableStateOf<WakeWordModel?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wake_enabled), modifier = Modifier.weight(1f))
        Switch(
            checked = state.enabled,
            onCheckedChange = { on ->
                if (on) enablePermissions.launch(wakePermissions(hasHomeAddress)) else viewModel.setEnabled(false)
            },
        )
    }
    Text(stringResource(R.string.wake_enabled_help), style = MaterialTheme.typography.bodySmall)

    Text(stringResource(R.string.wake_model), style = MaterialTheme.typography.titleSmall)
    state.models.forEach { model ->
        ModelRow(
            model = model,
            selected = model.id == state.selectedModelId,
            onSelect = { viewModel.selectModel(model.id) },
            onRename = { renaming = model },
            onDelete = { viewModel.deleteModel(model.id) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }, enabled = !state.importing) {
            Text(stringResource(R.string.wake_import))
        }
        TextButton(onClick = onOpenGuide) { Text(stringResource(R.string.wake_train_guide)) }
    }
    if (state.importFailed) {
        Text(stringResource(R.string.wake_import_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    Text(stringResource(R.string.wake_threshold, "%.2f".format(state.threshold)), style = MaterialTheme.typography.titleSmall)
    Slider(
        value = state.threshold,
        onValueChange = viewModel::setThreshold,
        onValueChangeFinished = viewModel::commitThreshold,
        valueRange = 0.1f..0.9f,
        steps = 15,
    )
    Text(stringResource(R.string.wake_threshold_help), style = MaterialTheme.typography.bodySmall)

    PermissionRow(
        label = R.string.wake_over_lock_screen,
        granted = state.canOpenOverLockScreen,
        onFix = { context.openSystemSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
    )
    PermissionRow(
        label = R.string.wake_battery,
        granted = state.ignoresBatteryOptimization,
        onFix = { context.openSystemSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) },
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wake_start_on_boot), modifier = Modifier.weight(1f))
        Switch(checked = state.startOnBoot, onCheckedChange = viewModel::setStartOnBoot)
    }

    Text(stringResource(R.string.connection_mode), style = MaterialTheme.typography.titleSmall)
    ConnectionMode.entries.forEach { mode ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { viewModel.setConnectionMode(mode) },
        ) {
            RadioButton(selected = state.connectionMode == mode, onClick = { viewModel.setConnectionMode(mode) })
            Column {
                Text(stringResource(if (mode == ConnectionMode.KeepConnected) R.string.connection_keep else R.string.connection_on_demand))
                Text(
                    stringResource(if (mode == ConnectionMode.KeepConnected) R.string.connection_keep_help else R.string.connection_on_demand_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    importUri?.let { uri ->
        NameDialog(
            title = R.string.wake_import_name,
            initial = context.displayName(uri)?.substringBeforeLast('.').orEmpty(),
            onConfirm = { name ->
                viewModel.importModel(uri, name)
                importUri = null
            },
            onDismiss = { importUri = null },
        )
    }
    renaming?.let { model ->
        NameDialog(
            title = R.string.wake_rename,
            initial = model.name,
            onConfirm = { name ->
                viewModel.renameModel(model.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun ModelRow(
    model: WakeWordModel,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
            Text(
                stringResource(if (model.builtIn) R.string.wake_model_builtin else R.string.wake_model_imported),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!model.builtIn) {
            TextButton(onClick = onRename) { Text(stringResource(R.string.wake_rename_short)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.settings_remove)) }
        }
    }
}

@Composable
private fun PermissionRow(label: Int, granted: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(label))
            Text(
                stringResource(if (granted) R.string.permission_ok else R.string.permission_missing),
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!granted) TextButton(onClick = onFix) { Text(stringResource(R.string.permission_allow)) }
    }
}

@Composable
private fun NameDialog(title: Int, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.wake_model_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

private fun wakePermissions(hasHomeAddress: Boolean): Array<String> {
    val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
    if (hasHomeAddress) permissions += NetworkInfo.localNetworkPermissions()
    return permissions.toTypedArray()
}

private fun Context.openSystemSettings(action: String) {
    runCatching { startActivity(Intent(action, Uri.parse("package:$packageName"))) }
}

private fun Context.displayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
