package com.avshy.bayitvoice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.satellite.SatelliteDevice

/** Satellite mode: lets this device play the music HA and Music Assistant send it (SPEC section 12). */
@Composable
fun MediaPlayerSettings() {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    var enabled by remember { mutableStateOf(settings.localPlayerEnabled) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.media_player_switch), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                settings.localPlayerEnabled = it
                SatelliteDevice.get(context).onMediaPlayerSettingChanged()
            })
        }
        Text(stringResource(R.string.media_player_help), style = MaterialTheme.typography.bodySmall)
    }
}
