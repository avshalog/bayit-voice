package com.avshy.bayitvoice.ui.idle

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
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

        var mediaPlayer by remember { mutableStateOf(settings.idleMediaPlayer.orEmpty()) }
        OutlinedTextField(
            value = mediaPlayer,
            onValueChange = { value ->
                mediaPlayer = value.trim()
                settings.idleMediaPlayer = mediaPlayer.takeIf { it.startsWith("media_player.") && it.length > 13 }
            },
            label = { Text(stringResource(R.string.idle_media_player)) },
            placeholder = { Text(stringResource(R.string.idle_media_player_hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.idle_media_player_help), style = MaterialTheme.typography.bodySmall)

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

/** A time shown as HH:MM; tapping it opens a 24-hour picker, so nothing is typed into the field. */
@Composable
private fun TimeField(label: Int, minute: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    var value by remember { mutableStateOf(minute) }
    var picking by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = formatMinute(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(label)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.fillMaxWidth(),
        )
        // Covers the field, so a tap opens the picker instead of focusing the text.
        Box(Modifier.matchParentSize().clickable { picking = true })
    }
    if (picking) {
        TimePickerDialog(
            title = stringResource(label),
            minute = value,
            onConfirm = { value = it; onChange(it); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/** The Material 24-hour clock dial, with a switch to two number boxes (hour : minute). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(title: String, minute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
    var dial by remember { mutableStateOf(true) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val picker = @Composable {
        // Hours read left to right, and the dial keeps its usual layout, in Hebrew too.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (dial) TimePicker(state) else TimeInput(state)
        }
    }
    val toggle = @Composable {
        TextButton(onClick = { dial = !dial }) {
            Text(stringResource(if (dial) R.string.idle_time_keyboard else R.string.idle_time_dial))
        }
    }
    val cancel = @Composable { TextButton(onClick = onDismiss) { Text(stringResource(R.string.idle_time_cancel)) } }
    val ok = @Composable {
        TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.dialog_ok)) }
    }
    val heading = @Composable { Text(title, style = MaterialTheme.typography.labelLarge) }

    // The picker is wider than a default dialog in landscape, so the dialog sizes itself to it.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.padding(16.dp)) {
            if (landscape) {
                // A landscape screen is too short for the buttons under the dial: they go beside it.
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.padding(end = 16.dp)) {
                        heading()
                        Spacer(Modifier.height(24.dp))
                        toggle()
                        cancel()
                        ok()
                    }
                    picker()
                }
            } else {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.End) {
                    Box(Modifier.align(Alignment.Start).padding(bottom = 12.dp)) { heading() }
                    picker()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        toggle()
                        Spacer(Modifier.width(24.dp))
                        cancel()
                        ok()
                    }
                }
            }
        }
    }
}

internal fun formatMinute(minute: Int) = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
