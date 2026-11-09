package com.avshy.bayitvoice.ui.idle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.weather.IdleWeather
import com.avshy.bayitvoice.weather.WeatherNow
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay

/**
 * The idle screen (SPEC section 11): the clock and dates over a full-bleed scene inside a thin
 * black rounded frame, sized from the screen height so it reads from 2–3 m on a phone.
 * Night mode leaves only a dim red clock on black. Everything shifts a little each minute so a
 * static screen does not burn in. [onNightChange] lets the host dim the window.
 */
@Composable
fun IdleScreen(onNightChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val clock = remember { IdleClock(context) }
    val state by produceState(clock.now()) {
        while (true) {
            // Wake just after the next minute starts.
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
            value = clock.now()
        }
    }
    LaunchedEffect(state.night) { onNightChange(state.night) }

    val weatherSource = remember { IdleWeather(context) }
    LaunchedEffect(Unit) { weatherSource.run() }
    val weather by weatherSource.now.collectAsState(initial = null)
    // Rebuild the scene only when the condition or the part of the day changes.
    val dayPart = when (val e = weather?.sunElevation) {
        null -> if (state.minuteOfDay < 6 * 60 || state.minuteOfDay >= 18 * 60) -1 else 1
        else -> if (e < -4) -1 else if (e <= 6) 0 else 1
    }
    val spec = remember(weather?.condition, dayPart) {
        SceneSpec.of(weather?.condition, sunElevation = when (dayPart) { -1 -> -10.0; 0 -> 0.0; else -> 30.0 }, fallbackNight = dayPart < 0)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val h = maxHeight
        val w = maxWidth
        val frame = minOf(w, h) * 0.02f
        val corner = minOf(w, h) * 0.06f
        val shift = burnInShift(state.minuteOfDay)
        // Text is sized from this unit: the height in landscape, a share of the width when the
        // device stands upright, so the clock never outgrows a narrow screen.
        val unit = minOf(h, w * 0.45f)

        AnimatedContent(
            targetState = state.night,
            transitionSpec = { fadeIn(tween(1200)) togetherWith fadeOut(tween(1200)) },
            label = "night",
        ) { night ->
            if (night) {
                // Screen off: nothing is drawn and the host turns the backlight off.
                if (!state.nightScreenOff) NightClock(state.time, unit, shift)
            } else {
                Box(
                    Modifier.fillMaxSize()
                        .padding(frame)
                        .clip(RoundedCornerShape(corner)),
                ) {
                    WeatherScene(spec)
                    DayContent(state, weather, w, unit, shift)
                }
            }
        }
    }
}

@Composable
private fun DayContent(state: IdleClockState, weather: WeatherNow?, w: Dp, h: Dp, shift: Offset) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = w * 0.034f, vertical = h * 0.04f)
            .offset(shift.x.toDp(h), shift.y.toDp(h)),
    ) {
        ClockAndDates(state, h, Modifier.align(Alignment.TopStart))
        if (weather != null) {
            WeatherNowText(weather, h, Modifier.align(Alignment.TopEnd))
            WeatherDetails(weather, h, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun WeatherNowText(weather: WeatherNow, h: Dp, modifier: Modifier) {
    val shadow = Shadow(color = IdleColors.textShadow, blurRadius = 22f)
    Column(modifier, horizontalAlignment = Alignment.End) {
        weather.temperature?.let {
            Text(
                text = "${it.roundToInt()}°",
                style = TextStyle(color = IdleColors.text, fontSize = h.toSp(0.24f), fontWeight = FontWeight.Light, textDirection = TextDirection.Ltr, shadow = shadow),
            )
        }
        Text(
            text = conditionText(weather.condition),
            style = TextStyle(color = IdleColors.text, fontSize = h.toSp(0.08f), fontWeight = FontWeight.Medium, shadow = shadow),
        )
        if (weather.high != null && weather.low != null) {
            Text(
                text = stringResource(R.string.idle_high_low, weather.high.roundToInt(), weather.low.roundToInt()),
                style = TextStyle(color = IdleColors.textSoft, fontSize = h.toSp(0.07f), shadow = shadow),
            )
        }
    }
}

/** Humidity and wind in a glass chip at the bottom. */
@Composable
private fun WeatherDetails(weather: WeatherNow, h: Dp, modifier: Modifier) {
    val parts = buildList {
        weather.humidity?.let { add(stringResource(R.string.idle_humidity) to "${it.roundToInt()}%") }
        weather.windSpeed?.let {
            val unit = if (weather.windUnit == "km/h") stringResource(R.string.idle_unit_kmh) else weather.windUnit
            add(stringResource(R.string.idle_wind) to "${it.roundToInt()} $unit".trim())
        }
    }
    if (parts.isEmpty()) return
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(IdleColors.glass)
            .border(1.dp, IdleColors.glassEdge, RoundedCornerShape(50))
            .padding(horizontal = h * 0.05f, vertical = h * 0.015f),
        horizontalArrangement = Arrangement.spacedBy(h * 0.06f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, value) in parts) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(h * 0.012f)) {
                Text(label, style = TextStyle(color = IdleColors.textSoft, fontSize = h.toSp(0.05f)))
                Text(value, style = TextStyle(color = IdleColors.text, fontSize = h.toSp(0.065f), fontWeight = FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun conditionText(condition: String?): String = stringResource(
    when (condition) {
        "sunny" -> R.string.weather_sunny
        "clear-night" -> R.string.weather_clear_night
        "partlycloudy" -> R.string.weather_partlycloudy
        "cloudy" -> R.string.weather_cloudy
        "fog" -> R.string.weather_fog
        "rainy" -> R.string.weather_rainy
        "pouring" -> R.string.weather_pouring
        "lightning", "lightning-rainy" -> R.string.weather_lightning
        "snowy" -> R.string.weather_snowy
        "snowy-rainy" -> R.string.weather_snowy_rainy
        "hail" -> R.string.weather_hail
        "windy", "windy-variant" -> R.string.weather_windy
        else -> R.string.weather_exceptional
    },
)

@Composable
private fun ClockAndDates(state: IdleClockState, h: Dp, modifier: Modifier) {
    val shadow = Shadow(color = IdleColors.textShadow, blurRadius = 22f)
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = state.time,
            modifier = Modifier.cropToDigits(h * 0.50f),
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                color = IdleColors.text,
                fontSize = h.toSp(0.50f),
                lineHeight = h.toSp(0.50f),
                fontWeight = FontWeight.Light,
                textDirection = TextDirection.Ltr,
                shadow = shadow,
                // Trim the font's built-in space above and below the digits, so the date sits right under them.
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
        Text(
            text = state.gregorianDate,
            modifier = Modifier.padding(top = h * 0.03f),
            style = TextStyle(color = IdleColors.text, fontSize = h.toSp(0.08f), fontWeight = FontWeight.Medium, shadow = shadow),
        )
        state.hebrewDate?.let {
            Text(
                text = it,
                style = TextStyle(color = IdleColors.textSoft, fontSize = h.toSp(0.07f), shadow = shadow),
            )
        }
    }
}

@Composable
private fun NightClock(time: String, h: Dp, shift: Offset) {
    Box(Modifier.fillMaxSize().offset(shift.x.toDp(h), shift.y.toDp(h)), contentAlignment = Alignment.Center) {
        Text(
            text = time,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                color = IdleColors.night,
                fontSize = h.toSp(0.42f),
                fontWeight = FontWeight.Light,
                textDirection = TextDirection.Ltr,
            ),
        )
    }
}

/**
 * Steps through a 5×5 grid of small offsets, one step a minute, so no pixel stays lit the same way
 * for long. Values are fractions of the screen height.
 */
private fun burnInShift(minuteOfDay: Int): Offset {
    val step = 0.006f
    val x = (minuteOfDay % 5 - 2) * step
    val y = ((minuteOfDay / 5) % 5 - 2) * step
    return Offset(x, y)
}

private fun Float.toDp(h: Dp): Dp = h * this

/**
 * A large digit line leaves a lot of empty space above and below the digits (font ascent and
 * descent), which pushed the date far from the clock. This keeps only the band the digits use:
 * measured on device, the digits run from about 0.23 to 0.95 of the font size below the line's top.
 */
private fun Modifier.cropToDigits(fontSize: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val font = fontSize.roundToPx()
    val top = (font * 0.20f).toInt()
    val height = (font * 0.78f).toInt().coerceAtMost(placeable.height)
    layout(placeable.width, height) { placeable.place(0, -top) }
}

@Composable
private fun Dp.toSp(fraction: Float): TextUnit = with(LocalDensity.current) { (this@toSp * fraction).toSp() }

/** Fixed colours: the idle screen looks the same in either system theme. */
object IdleColors {
    val text = Color(0xFFFFFFFF)
    val textSoft = Color(0xE6FFFFFF)
    val textShadow = Color(0x73000000)
    val night = Color(0xFF7A2E1C)
    val glass = Color(0x4D080C14)
    val glassEdge = Color(0x2EFFFFFF)
}
