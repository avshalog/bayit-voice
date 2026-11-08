package com.avshy.bayitvoice.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.config.AssistLink
import com.avshy.bayitvoice.service.WakeWordService

/** How this device reaches HA (SPEC section 10): as a satellite HA connects to, or directly. */
@Composable
fun AssistLinkSettings(state: SettingsUiState, viewModel: SettingsViewModel) {
    val links = listOf(AssistLink.Satellite to R.string.link_satellite, AssistLink.Direct to R.string.link_direct)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        links.forEachIndexed { index, (link, label) ->
            SegmentedButton(
                selected = state.assistLink == link,
                onClick = { viewModel.setAssistLink(link) },
                shape = SegmentedButtonDefaults.itemShape(index, links.size),
            ) { Text(stringResource(label)) }
        }
    }
    if (state.assistLink == AssistLink.Direct) {
        Text(stringResource(R.string.link_direct_help), style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(stringResource(R.string.link_satellite_help), style = MaterialTheme.typography.bodySmall)

    val status = when {
        state.satelliteConnected -> R.string.link_status_connected
        WakeWordService.isRunning -> R.string.link_status_waiting
        else -> R.string.link_status_needs_listening
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(8.dp).background(
                if (state.satelliteConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            )
        )
        Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
    }
    Text(stringResource(R.string.settings_pipeline_satellite), style = MaterialTheme.typography.bodySmall)
    if (state.satelliteConnected) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.link_device_name), style = MaterialTheme.typography.labelMedium)
        Text(viewModel.satelliteName, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.link_key), style = MaterialTheme.typography.labelMedium)
        val key = viewModel.satelliteKey
        val context = LocalContext.current
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                key.take(4) + "••••••••••" + key.takeLast(3),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { copySecret(context, key) }) { Text(stringResource(R.string.link_copy_key)) }
        }
    }
    Text(stringResource(R.string.link_pair_help), style = MaterialTheme.typography.bodySmall)
    // For networks where HA cannot discover the device: add it by hand in HA's ESPHome integration.
    viewModel.satelliteAddress()?.let { address ->
        Text(
            stringResource(R.string.link_manual_address, "⁦$address⁩"),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Copies [secret] marked as sensitive, so the keyboard's clipboard preview hides it. */
private fun copySecret(context: Context, secret: String) {
    val clip = ClipData.newPlainText("ESPHome key", secret)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
