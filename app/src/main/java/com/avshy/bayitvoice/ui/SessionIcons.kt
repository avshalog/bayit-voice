package com.avshy.bayitvoice.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avshy.bayitvoice.session.SessionPhase

/** Line icons on a 24-unit grid, as SVG path data. */
private object IconPaths {
    val connecting = listOf("M5 12.5a10 10 0 0 1 14 0", "M8.5 16a5 5 0 0 1 7 0", "M2 9a14.5 14.5 0 0 1 20 0", "M12 19.5h.01")
    val mic = listOf("M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5.5 11a6.5 6.5 0 0 0 13 0", "M12 17.5V21")
    val thinking = listOf(
        "M12 3c.6 4.2 2.8 6.4 7 7-4.2.6-6.4 2.8-7 7-.6-4.2-2.8-6.4-7-7 4.2-.6 6.4-2.8 7-7z",
        "M19 15.5c.25 1.6 1 2.35 2.5 2.5-1.5.15-2.25.9-2.5 2.5-.25-1.6-1-2.35-2.5-2.5 1.5-.15 2.25-.9 2.5-2.5z",
    )
    val home = listOf("M3.5 11 12 4l8.5 7", "M5.5 9.5V20h13V9.5", "M10 20v-5h4v5")
    const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
    val warning = listOf("M12 3.5 21.5 20h-19z", "M12 10v4.5", "M12 17.5h.01")
}

private val pathCache = HashMap<String, Path>()
private fun path(d: String): Path = pathCache.getOrPut(d) { PathParser().parsePathString(d).toPath() }

private fun DrawScope.strokeIcon(paths: List<String>, color: Color, width: Float) {
    scale(size.minDimension / 24f, pivot = Offset.Zero) {
        val stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        paths.forEach { drawPath(path(it), color, style = stroke) }
    }
}

@Composable
private fun LineIcon(paths: List<String>, color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, width: Float = 2f) {
    Canvas(modifier.size(size)) { strokeIcon(paths, color, width) }
}

/** The small animated icon beside the status text. */
@Composable
fun PhaseIcon(phase: SessionPhase, accent: Color, modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "phase-icon")
    Box(modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when (phase) {
            SessionPhase.Idle -> LineIcon(IconPaths.mic, accent, size = 20.dp, width = 2.2f)
            SessionPhase.Connecting -> {
                val alpha by loop.animateFloat(1f, 0.35f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "blink")
                LineIcon(IconPaths.connecting, accent, Modifier.alpha(alpha))
            }
            SessionPhase.Listening -> {
                val ping by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "ping")
                Box(
                    Modifier.size(28.dp)
                        .graphicsLayer {
                            val s = 0.8f + 0.6f * ping
                            scaleX = s; scaleY = s; alpha = 0.6f * (1f - ping)
                        }
                        .background(accent, CircleShape)
                )
                LineIcon(IconPaths.mic, accent, size = 20.dp, width = 2.2f)
            }
            SessionPhase.Thinking -> LineIcon(IconPaths.thinking, accent)
            SessionPhase.Speaking -> {
                val bob by loop.animateFloat(0f, -2f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "bob")
                LineIcon(IconPaths.home, accent, Modifier.graphicsLayer { translationY = bob * density })
            }
            SessionPhase.Done -> DoneCheck(SessionColors.done)
            SessionPhase.Elsewhere -> LineIcon(IconPaths.home, accent)
            SessionPhase.Error -> {
                val shake = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    shake.animateTo(0f, keyframes { durationMillis = 600; -2f at 75; 2f at 225; -2f at 375; 2f at 525 })
                }
                LineIcon(IconPaths.warning, SessionColors.error, Modifier.graphicsLayer { translationX = shake.value * density })
            }
        }
    }
}

/** A check mark that pops in and draws itself once, then stays. */
@Composable
private fun DoneCheck(color: Color) {
    val pop = remember { Animatable(0.4f) }
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, keyframes { durationMillis = 380; 1.18f at 230 using FastOutSlowInEasing })
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        draw.animateTo(1f, tween(350))
    }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    Canvas(Modifier.size(22.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceAtMost(1f) }) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) {
            measure.setPath(path(IconPaths.CHECK), false)
            segment.reset()
            measure.getSegment(0f, measure.length * draw.value, segment, true)
            drawPath(segment, color, style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Three dots that bounce in turn, sitting on the text's baseline like an ellipsis. */
@Composable
fun BouncingDots(color: Color, modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "dots")
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        repeat(3) { i ->
            val t by loop.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 1200
                        delayMillis = 0
                        0f at 0; 1f at 480; 0f at 960; 0f at 1200
                    },
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 150),
                ),
                label = "dot$i",
            )
            if (i > 0) Spacer(Modifier.width(3.dp))
            Box(
                Modifier.size(4.dp)
                    .graphicsLayer { translationY = -3f * density * t; alpha = 0.3f + 0.7f * t }
                    .background(color, CircleShape)
            )
        }
    }
}
