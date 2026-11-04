package com.avshy.bayitvoice.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.avshy.bayitvoice.audio.SpeechLevel
import kotlin.math.PI
import kotlin.math.sin

private const val STEP_MS = 70L
private const val HISTORY = 96

/**
 * A waveform of the reply: one bar per [STEP_MS] of audio enters at the right edge and
 * scrolls left. Bar heights follow [SpeechLevel] when the device reports it, otherwise a
 * generic syllable pattern.
 */
@Composable
fun SpeechWave(color: Color, modifier: Modifier = Modifier) {
    val bars = remember { FloatArray(HISTORY) }
    val pattern = remember { speechPattern() }
    val state = remember { WaveState() }
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = withFrameMillis { it }
        var smoothed = 0f
        while (true) {
            val now = withFrameMillis { it }
            while (now - last >= STEP_MS) {
                last += STEP_MS
                val target = if (SpeechLevel.active.value) SpeechLevel.level.value else pattern[state.patternIndex++ % pattern.size]
                // Quick attack, slower release, so syllables read as bumps rather than flicker.
                smoothed += (target - smoothed) * (if (target > smoothed) 0.7f else 0.35f)
                System.arraycopy(bars, 1, bars, 0, HISTORY - 1)
                bars[HISTORY - 1] = smoothed
            }
            state.progress = (now - last).toFloat() / STEP_MS
            frame.longValue = now
        }
    }

    val swell by rememberInfiniteTransition(label = "swell")
        .animateFloat(0.8f, 1.06f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "swell")

    Canvas(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; scaleY = swell }
    ) {
        frame.longValue // redraw every frame
        val barWidth = 3.dp.toPx()
        val step = barWidth + 3.dp.toPx()
        val maxHeight = size.height * 0.92f
        val minHeight = 3.dp.toPx()
        val shift = state.progress * step
        for (i in HISTORY - 1 downTo 0) {
            val x = size.width - (HISTORY - 1 - i) * step - barWidth - shift
            if (x + barWidth < 0) break
            val v = bars[i]
            val h = minHeight + v * (maxHeight - minHeight)
            drawRoundRect(
                color = color.copy(alpha = 0.55f + 0.45f * v),
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
        // Fade both edges.
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.15f to Color.Black, 0.85f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
}

private class WaveState {
    var patternIndex = 0
    var progress = 0f
}

/** Syllable bumps grouped into words, with short pauses between them. */
private fun speechPattern(): FloatArray {
    val values = ArrayList<Float>()
    var seed = 7L
    fun rand(): Float { seed = (seed * 9301 + 49297) % 233280; return seed / 233280f }
    while (values.size < 160) {
        repeat(2 + (rand() * 3).toInt()) {
            val peak = 0.45f + rand() * 0.55f
            val len = 2 + (rand() * 2).toInt()
            for (i in 0 until len) values += (peak * sin(PI * (i + 0.5) / len).toFloat() * (0.8f + rand() * 0.2f)).coerceAtLeast(0.08f)
        }
        repeat(1 + (rand() * 2).toInt()) { values += 0.05f }
    }
    return values.toFloatArray()
}
