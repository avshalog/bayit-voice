package com.avshy.bayitvoice.ui.idle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.music.IdleMusic
import com.avshy.bayitvoice.music.NowPlaying
import com.avshy.bayitvoice.timer.ActiveTimer
import com.avshy.bayitvoice.timer.RingKind
import com.avshy.bayitvoice.ui.timer.TimerText
import kotlinx.coroutines.delay

/*
 * The idle screen's timer chip, music card and volume slider (SPEC section 11). All are sized from [u], one
 * hundredth of the screen's short side, so they look the same in landscape and portrait.
 */

internal fun Modifier.glass(shape: Shape): Modifier =
    clip(shape).background(IdleColors.glass).border(1.dp, IdleColors.glassEdge, shape)

/** The next running timer as one row: icon, name, time left. Nothing when no timer runs. */
@Composable
internal fun TimerChip(timers: List<ActiveTimer>, u: Dp, modifier: Modifier = Modifier) {
    val next = timers.filter { it.kind == RingKind.TIMER && !it.ringing }.minByOrNull { it.leftMs() } ?: return
    val left by produceState(next.leftMs(), next) {
        while (true) {
            value = next.leftMs()
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    Row(
        modifier
            .glass(RoundedCornerShape(50))
            .padding(horizontal = u * 5.8f, vertical = u * 1.6f),
        horizontalArrangement = Arrangement.spacedBy(u * 2.7f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⏳", style = TextStyle(fontSize = u.sp(8f)))
        Text(TimerText.name(res, next), style = TextStyle(color = IdleColors.text, fontSize = u.sp(8f)))
        Text(
            TimerText.countdown(left),
            style = TextStyle(color = IdleColors.text, fontSize = u.sp(11f), fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * What the room plays, large: the artwork fills the card, dimmed, with the title, artist,
 * progress and previous / play-pause / next over it. Without artwork a plain gradient fills it.
 */
@Composable
internal fun NowPlayingCard(playing: NowPlaying, music: IdleMusic, u: Dp, modifier: Modifier = Modifier) {
    val artPx = with(LocalDensity.current) { (u * 90f).roundToPx() }
    val art by produceState<ImageBitmap?>(null, playing.artUrl) {
        value = playing.artUrl?.let { music.loadArt(it, artPx)?.asImageBitmap() }
    }
    val nowMs by produceState(System.currentTimeMillis(), playing.playing) {
        while (playing.playing) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
        value = System.currentTimeMillis()
    }
    val shape = RoundedCornerShape(u * 5f)
    val description = stringResource(R.string.idle_music_art)

    Box(modifier.clip(shape).border(1.dp, IdleColors.glassEdge, shape)) {
        val image = art
        if (image != null) {
            Image(image, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Box(
                Modifier.matchParentSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF5B6B8C), Color(0xFF2A3350))))
                    .semantics { contentDescription = description },
            )
        }
        Box(Modifier.matchParentSize().background(IdleColors.artDim))
        Column(Modifier.matchParentSize().padding(u * 5f)) {
            Text(
                playing.title.orEmpty(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = IdleColors.text, fontSize = u.sp(8f), fontWeight = FontWeight.SemiBold),
            )
            playing.artist?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = IdleColors.textSoft, fontSize = u.sp(6f)))
            }
            Spacer(Modifier.weight(1f))
            // Numbers, bars and controls read left to right in any language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val duration = playing.durationSec
                val position = playing.positionAt(nowMs)
                if (duration != null && position != null) Progress(position, duration, u)
                Row(
                    Modifier.fillMaxWidth().padding(top = u * 3f),
                    horizontalArrangement = Arrangement.spacedBy(u * 5f, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(u * 12f, IdleColors.button, stringResource(R.string.idle_music_previous), music::previous) { drawPrevious(it) }
                    RoundButton(
                        size = u * 16f,
                        background = Color.White,
                        label = stringResource(if (playing.playing) R.string.idle_music_pause else R.string.idle_music_play),
                        onClick = music::playPause,
                    ) { color -> if (playing.playing) drawPause(color) else drawPlay(color) }
                    RoundButton(u * 12f, IdleColors.button, stringResource(R.string.idle_music_next), music::next) { drawNext(it) }
                }
            }
        }
    }
}

@Composable
private fun Progress(position: Double, duration: Double, u: Dp) {
    val style = TextStyle(color = IdleColors.textSoft, fontSize = u.sp(4.4f))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 2f)) {
        Text(clock(position), style = style)
        Bar((position / duration).toFloat(), u, Modifier.weight(1f))
        Text(clock(duration), style = style)
    }
}

@Composable
private fun Bar(fraction: Float, u: Dp, modifier: Modifier) {
    Canvas(modifier.height(u * 1.2f)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(IdleColors.track, cornerRadius = r)
        drawRoundRect(Color.White, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = r)
    }
}

@Composable
private fun RoundButton(size: Dp, background: Color, label: String, onClick: () -> Unit, icon: DrawScope.(Color) -> Unit) {
    val iconColor = if (background == Color.White) Color(0xFF10131C) else Color.White
    Box(
        Modifier.size(size).clip(CircleShape).background(background)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.42f)) { icon(iconColor) }
    }
}

/**
 * A tall volume slider beside the music card, wide enough to use with a finger: tap or drag
 * anywhere along it; HA is told once the finger lifts.
 */
@Composable
internal fun VolumeColumn(volume: Double, music: IdleMusic, u: Dp, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: volume.toFloat()
    val label = stringResource(R.string.idle_music_volume)
    val thumb = u * 9f
    Column(
        modifier.glass(RoundedCornerShape(u * 5f)).padding(vertical = u * 4f).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(u * 3f),
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = thumb / 2)
                .pointerInput(Unit) {
                    detectTapGestures { music.setVolume(levelAt(it.y, size.height).toDouble()) }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = levelAt(it.y, size.height) },
                        onDragEnd = { dragging?.let { music.setVolume(it.toDouble()) }; dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ -> dragging = levelAt(change.position.y, size.height) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val track = u.toPx() * 3.2f
                val x = (size.width - track) / 2
                val r = CornerRadius(track / 2)
                val filled = size.height * shown.coerceIn(0f, 1f)
                drawRoundRect(IdleColors.track, Offset(x, 0f), Size(track, size.height), r)
                drawRoundRect(Color.White, Offset(x, size.height - filled), Size(track, filled), r)
                val center = Offset(size.width / 2, size.height - filled)
                drawCircle(Color.White, thumb.toPx() / 2, center)
                drawCircle(IdleColors.thumbEdge, thumb.toPx() / 2, center, style = Stroke(u.toPx() * 0.5f))
            }
        }
        Canvas(Modifier.size(u * 5.5f)) { drawSpeaker(Color.White) }
    }
}

/** 0 at the bottom of the slider, 1 at the top. */
private fun levelAt(y: Float, height: Int): Float = (1f - y / height).coerceIn(0f, 1f)

private fun DrawScope.drawPrevious(color: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(color, Offset(w * 0.06f, 0f), Size(w * 0.18f, h), CornerRadius(w * 0.04f))
    drawPath(Path().apply { moveTo(w, 0f); lineTo(w * 0.3f, h / 2); lineTo(w, h); close() }, color)
}

private fun DrawScope.drawPause(color: Color) {
    val w = size.width
    drawRoundRect(color, Offset(w * 0.12f, 0f), Size(w * 0.26f, size.height), CornerRadius(w * 0.05f))
    drawRoundRect(color, Offset(w * 0.62f, 0f), Size(w * 0.26f, size.height), CornerRadius(w * 0.05f))
}

private fun DrawScope.drawPlay(color: Color) {
    val w = size.width
    val h = size.height
    drawPath(Path().apply { moveTo(w * 0.18f, 0f); lineTo(w, h / 2); lineTo(w * 0.18f, h); close() }, color)
}

private fun DrawScope.drawNext(color: Color) {
    val w = size.width
    val h = size.height
    drawPath(Path().apply { moveTo(0f, 0f); lineTo(w * 0.7f, h / 2); lineTo(0f, h); close() }, color)
    drawRoundRect(color, Offset(w * 0.76f, 0f), Size(w * 0.18f, h), CornerRadius(w * 0.04f))
}

private fun DrawScope.drawSpeaker(color: Color) {
    val w = size.width
    val h = size.height
    drawPath(
        Path().apply {
            moveTo(0f, h * 0.35f); lineTo(w * 0.25f, h * 0.35f); lineTo(w * 0.55f, h * 0.1f)
            lineTo(w * 0.55f, h * 0.9f); lineTo(w * 0.25f, h * 0.65f); lineTo(0f, h * 0.65f); close()
        },
        color,
    )
    drawArc(color, -45f, 90f, useCenter = false, topLeft = Offset(w * 0.35f, h * 0.25f), size = Size(w * 0.5f, h * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.09f))
}

private fun clock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
internal fun Dp.sp(units: Float) = with(LocalDensity.current) { (this@sp * units).toSp() }
