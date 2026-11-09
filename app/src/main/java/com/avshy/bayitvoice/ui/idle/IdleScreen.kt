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

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val h = maxHeight
        val w = maxWidth
        val frame = minOf(w, h) * 0.02f
        val corner = minOf(w, h) * 0.06f
        val shift = burnInShift(state.minuteOfDay)

        AnimatedContent(
            targetState = state.night,
            transitionSpec = { fadeIn(tween(1200)) togetherWith fadeOut(tween(1200)) },
            label = "night",
        ) { night ->
            if (night) {
                // Screen off: nothing is drawn and the host turns the backlight off.
                if (!state.nightScreenOff) NightClock(state.time, h, shift)
            } else {
                Box(
                    Modifier.fillMaxSize()
                        .padding(frame)
                        .clip(RoundedCornerShape(corner))
                        .background(IdleColors.placeholderSky),
                ) {
                    DayContent(state, w, h, shift)
                }
            }
        }
    }
}

@Composable
private fun DayContent(state: IdleClockState, w: Dp, h: Dp, shift: Offset) {
    val shadow = Shadow(color = IdleColors.textShadow, blurRadius = 22f)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = w * 0.034f, vertical = h * 0.04f)
            .offset(shift.x.toDp(h), shift.y.toDp(h)),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = state.time,
            modifier = Modifier.cropToDigits(h * 0.50f),
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

    /** Stand-in until the weather scene (stage 2): a calm evening sky. */
    val placeholderSky = Brush.verticalGradient(listOf(Color(0xFF16233F), Color(0xFF2E4672), Color(0xFF6F7FA0)))
}
