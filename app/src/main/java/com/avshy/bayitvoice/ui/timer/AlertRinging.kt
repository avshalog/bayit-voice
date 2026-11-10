package com.avshy.bayitvoice.ui.timer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.timer.ActiveTimer
import com.avshy.bayitvoice.timer.RingKind

/**
 * A ringing timer, alarm or reminder, in the language of the full-screen conversation
 * (FullScreenSession): the kind as the status at the top, the name as the reply in the text
 * area, a large line icon of the kind at the orb's spot, and pill buttons under it. Laid out on
 * the same 1280×800 (or 800×1280) design grid, scaled to the screen.
 */
@Composable
internal fun AlertRinging(timer: ActiveTimer, onStop: () -> Unit, onLater: (Int) -> Unit) {
    val res = LocalContext.current.resources
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(TimerColors.BACKGROUND))) {
        val portrait = maxHeight > maxWidth
        val k = if (portrait) minOf(maxWidth.value / 800f, maxHeight.value / 1280f) else minOf(maxWidth.value / 1280f, maxHeight.value / 800f)
        val s = Scale(k.coerceIn(0.4f, 1.6f))
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        // The orb's spot: on the end side in landscape, above the text in portrait.
        val spotX = if (portrait) maxWidth / 2 else (if (rtl) 300f else 1280f - 300f).let { maxWidth * (it / 1280f) }
        val spotY = if (portrait) maxHeight * (380f / 1280f) else maxHeight * (390f / 800f)

        val glowRadius = with(LocalDensity.current) { s.dp(400f).toPx() }
        val glowCenter = with(LocalDensity.current) { Offset(spotX.toPx(), spotY.toPx()) }
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.16f), Color.Transparent), glowCenter, glowRadius)))

        KindIcon(timer.kind, s, Modifier.centreAt(spotX, spotY).requiredSize(s.dp(300f)))

        // The status line and the close button, as in the conversation.
        Row(Modifier.fillMaxWidth().padding(s.dp(48f)), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    when (timer.kind) {
                        RingKind.TIMER -> R.string.timer_default_name
                        RingKind.ALARM -> R.string.alarm_default_name
                        RingKind.REMINDER -> R.string.reminder_title
                    },
                ),
                color = Accent, fontSize = s.sp(48f), lineHeight = s.sp(58f), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onStop,
                modifier = Modifier.size(s.dp(88f).coerceAtLeast(48.dp)),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Raised, contentColor = Color(TimerColors.MUTED)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.timer_close), modifier = Modifier.size(s.dp(36f)))
            }
        }

        // The name in the text area, where the reply is in the conversation.
        val title = when (timer.kind) {
            RingKind.TIMER -> TimerText.name(res, timer)
            RingKind.ALARM -> timer.name.ifBlank { stringResource(R.string.alarm_default_name) }
            RingKind.REMINDER -> timer.name
        }
        val subtitle = when (timer.kind) {
            RingKind.TIMER -> stringResource(R.string.timer_finished_text, TimerText.duration(res, timer.totalSeconds))
            else -> timer.clock.orEmpty()
        }
        Column(
            Modifier.fillMaxSize().padding(
                start = if (portrait) s.dp(64f) else maxWidth * (72f / 1280f),
                end = if (portrait) s.dp(64f) else maxWidth * (590f / 1280f),
                top = if (portrait) maxHeight * (600f / 1280f) else maxHeight * (150f / 800f),
                bottom = if (portrait) maxHeight * (330f / 1280f) else maxHeight * (96f / 800f),
            ),
            verticalArrangement = Arrangement.spacedBy(s.dp(20f), if (portrait) Alignment.Top else Alignment.CenterVertically),
        ) {
            Text(
                title, color = Color(TimerColors.TITLE), fontSize = s.sp(64f), lineHeight = s.sp(80f), fontWeight = FontWeight.SemiBold,
                maxLines = 4, style = TextStyle(textDirection = TextDirection.Content),
            )
            if (subtitle.isNotEmpty()) Text(subtitle, color = Color(TimerColors.MUTED), fontSize = s.sp(38f), lineHeight = s.sp(50f))
        }

        // Pill buttons as "speak again": under the icon in landscape, at the bottom in portrait.
        val later = when (timer.kind) {
            RingKind.TIMER -> null
            RingKind.ALARM -> LATER_MINUTES to stringResource(R.string.alarm_snooze, LATER_MINUTES)
            RingKind.REMINDER -> LATER_MINUTES to stringResource(R.string.reminder_later, LATER_MINUTES)
        }
        val stopLabel = stringResource(if (timer.kind == RingKind.REMINDER) R.string.reminder_done else R.string.timer_stop)
        val stopIcon = if (timer.kind == RingKind.REMINDER) Paths.check else Paths.stop
        val hint = stringResource(R.string.timer_stop_hint)
        if (portrait) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = s.dp(48f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(s.dp(20f)),
            ) {
                later?.let { (minutes, label) -> Pill(label, Paths.later, primary = false, s, Modifier.width(s.dp(560f))) { onLater(minutes) } }
                Pill(stopLabel, stopIcon, primary = true, s, Modifier.width(s.dp(560f)), onStop)
                Text(hint, color = Color(TimerColors.MUTED), fontSize = s.sp(28f))
            }
        } else {
            Column(
                Modifier.centreAt(spotX, spotY + s.dp(250f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(s.dp(24f)),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(s.dp(20f))) {
                    later?.let { (minutes, label) -> Pill(label, Paths.later, primary = false, s) { onLater(minutes) } }
                    Pill(stopLabel, stopIcon, primary = true, s, onClick = onStop)
                }
                Text(hint, color = Color(TimerColors.MUTED), fontSize = s.sp(28f))
            }
        }
    }
}

/** The kind's icon at the orb's size, in the conversation's line style, swinging while it rings. */
@Composable
private fun KindIcon(kind: RingKind, s: Scale, modifier: Modifier) {
    val loop = rememberInfiniteTransition(label = "ring")
    val halo by loop.animateFloat(0.15f, 0.55f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "halo")
    val swing by loop.animateFloat(
        0f, 0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 1400
                -9f at 140 using FastOutSlowInEasing
                8f at 280
                -6f at 420
                4f at 560
                0f at 700
            },
        ),
        label = "swing",
    )
    val paths = when (kind) {
        RingKind.TIMER -> Paths.hourglass
        RingKind.ALARM -> Paths.alarm
        RingKind.REMINDER -> Paths.bell
    }
    val ringWidth = with(LocalDensity.current) { s.dp(4f).toPx() }
    Canvas(modifier) {
        drawCircle(Accent.copy(alpha = halo), radius = size.minDimension * 0.46f, style = Stroke(ringWidth))
        val icon = size.minDimension * 0.62f
        val origin = Offset((size.width - icon) / 2, (size.height - icon) / 2)
        rotate(swing, pivot = Offset(size.width / 2, size.height * 0.42f)) {
            translate(origin.x, origin.y) { lineIcon(paths, Accent, icon, 1.6f) }
        }
    }
}

@Composable
private fun Pill(label: String, icon: List<String>, primary: Boolean, s: Scale, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(s.dp(112f).coerceAtLeast(56.dp)),
        shape = CircleShape,
        colors = if (primary) ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(TimerColors.ON_ACCENT))
        else ButtonDefaults.buttonColors(containerColor = Raised, contentColor = Color(TimerColors.TEXT)),
        contentPadding = PaddingValues(horizontal = s.dp(48f)),
    ) {
        val tint = if (primary) Color(TimerColors.ON_ACCENT) else Color(TimerColors.TEXT)
        Canvas(Modifier.size(s.dp(44f))) { lineIcon(icon, tint, size.minDimension, 2.2f) }
        Spacer(Modifier.width(s.dp(16f)))
        Text(label, fontSize = s.sp(38f), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Line icons on a 24-unit grid, as in the conversation's icons. */
private object Paths {
    val alarm = listOf("M12 20.5a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15z", "M12 9.5V13l2.5 2", "M4.5 4.5 2.5 6.5", "M19.5 4.5l2 2")
    val bell = listOf("M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15z", "M10 20.5a2 2 0 0 0 4 0")
    val hourglass = listOf("M7 3h10", "M7 21h10", "M8 3c0 4.5 4 5 4 9s-4 4.5-4 9", "M16 3c0 4.5-4 5-4 9s4 4.5 4 9")
    val stop = listOf("M8.5 6.5h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z")
    val check = listOf("M5 12.5l4.5 4.5L19 7.5")
    val later = listOf("M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17z", "M12 7.5V12l3 2")
}

private val pathCache = HashMap<String, Path>()

private fun DrawScope.lineIcon(paths: List<String>, color: Color, sizePx: Float, width: Float) {
    scale(sizePx / 24f, pivot = Offset.Zero) {
        val stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        paths.forEach { d -> drawPath(pathCache.getOrPut(d) { PathParser().parsePathString(d).toPath() }, color, style = stroke) }
    }
}

private fun DrawScope.translate(x: Float, y: Float, block: DrawScope.() -> Unit) =
    drawContext.transform.let { t ->
        t.translate(x, y)
        block()
        t.translate(-x, -y)
    }

/** Fills the parent and puts the element's centre at ([x], [y]) from the top-left corner. */
private fun Modifier.centreAt(x: Dp, y: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints())
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(x.roundToPx() - placeable.width / 2, y.roundToPx() - placeable.height / 2)
    }
}

/** Design pixels to dp/sp on this screen. */
private class Scale(val k: Float) {
    fun dp(v: Float): Dp = (v * k).dp
    fun sp(v: Float): TextUnit = (v * k).sp
}

private val Accent = Color(TimerColors.ACCENT)
private val Raised = Color(0xFF1E2027)
private const val LATER_MINUTES = 10
