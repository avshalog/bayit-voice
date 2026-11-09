package com.avshy.bayitvoice.ui.idle

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.config.AppSettings
import java.util.Locale

/** The "Idle screen" settings section (SPEC section 11). */
@Composable
fun IdleSettings() {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    var hebrew by remember {
        val locale = Locale.getDefault().language
        mutableStateOf(settings.idleHebrewDate ?: (locale == "he" || locale == "iw"))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.idle_help), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { context.startActivity(IdleActivity.intent(context)) }) {
                Text(stringResource(R.string.idle_open))
            }
            OutlinedButton(onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
                }
            }) { Text(stringResource(R.string.idle_screensaver_settings)) }
        }
        Text(stringResource(R.string.idle_screensaver_help), style = MaterialTheme.typography.bodySmall)

        var autoStart by remember { mutableStateOf(settings.idleAutoStart) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.idle_auto_start), modifier = Modifier.weight(1f))
            Switch(checked = autoStart, onCheckedChange = { autoStart = it; settings.idleAutoStart = it })
        }
        Text(stringResource(R.string.idle_auto_start_help), style = MaterialTheme.typography.bodySmall)

        var weatherEntity by remember { mutableStateOf(settings.idleWeatherEntity.orEmpty()) }
        OutlinedTextField(
            value = weatherEntity,
            onValueChange = { value ->
                weatherEntity = value.trim()
                settings.idleWeatherEntity = weatherEntity.takeIf { it.startsWith("weather.") && it.length > 8 }
            },
            label = { Text(stringResource(R.string.idle_weather_entity)) },
            placeholder = { Text(stringResource(R.string.idle_weather_hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.idle_weather_help), style = MaterialTheme.typography.bodySmall)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.idle_hebrew_date), modifier = Modifier.weight(1f))
            Switch(checked = hebrew, onCheckedChange = { hebrew = it; settings.idleHebrewDate = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TimeField(
                label = R.string.idle_night_start,
                minute = settings.idleNightStartMinute,
                onChange = { settings.idleNightStartMinute = it },
                modifier = Modifier.weight(1f),
            )
            TimeField(
                label = R.string.idle_night_end,
                minute = settings.idleNightEndMinute,
                onChange = { settings.idleNightEndMinute = it },
                modifier = Modifier.weight(1f),
            )
        }
        Text(stringResource(R.string.idle_night_help), style = MaterialTheme.typography.bodySmall)

        Text(stringResource(R.string.idle_night_mode), style = MaterialTheme.typography.titleSmall)
        var screenOff by remember { mutableStateOf(settings.idleNightScreenOff) }
        ChoiceRow(R.string.idle_night_dim_clock, selected = !screenOff) { screenOff = false; settings.idleNightScreenOff = false }
        ChoiceRow(R.string.idle_night_screen_off, selected = screenOff) { screenOff = true; settings.idleNightScreenOff = true }

        var fixed by remember { mutableStateOf(settings.idleDayBrightness) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.idle_day_brightness_fixed), modifier = Modifier.weight(1f))
            Switch(
                checked = fixed != null,
                onCheckedChange = { on -> fixed = if (on) DEFAULT_DAY_LEVEL else null; settings.idleDayBrightness = fixed },
            )
        }
        fixed?.let { level ->
            Slider(
                value = level,
                onValueChange = { fixed = it },
                onValueChangeFinished = { settings.idleDayBrightness = fixed },
                valueRange = 0.02f..1f,
            )
        }
        Text(stringResource(R.string.idle_brightness_help), style = MaterialTheme.typography.bodySmall)
    }
}

private const val DEFAULT_DAY_LEVEL = 0.6f

@Composable
private fun ChoiceRow(label: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(stringResource(label))
    }
}

/** An HH:MM field; the value is saved only when it parses as a valid time. */
@Composable
private fun TimeField(label: Int, minute: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    var text by remember { mutableStateOf(formatMinute(minute)) }
    val valid = parseMinute(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value.filter { it.isDigit() || it == ':' }.take(5)
            parseMinute(text)?.let(onChange)
        },
        label = { Text(stringResource(label)) },
        placeholder = { Text(stringResource(R.string.idle_time_hint)) },
        isError = !valid,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

internal fun formatMinute(minute: Int) = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)

/** "23:00", "6:30" or "0630" → minutes since midnight; null when it is not a time. */
internal fun parseMinute(text: String): Int? {
    val digits = text.replace(":", "")
    val (h, m) = when {
        ':' in text -> text.split(':').let { it.getOrNull(0)?.toIntOrNull() to it.getOrNull(1)?.toIntOrNull() }
        digits.length == 4 -> digits.take(2).toIntOrNull() to digits.drop(2).toIntOrNull()
        else -> null to null
    }
    if (h == null || m == null || h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}
