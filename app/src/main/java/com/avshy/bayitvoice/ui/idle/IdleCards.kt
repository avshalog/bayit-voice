package com.avshy.bayitvoice.ui.idle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
 * The idle screen's timer chip and music card (SPEC section 11). Both are sized from [u], one
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

/** What the room plays: artwork, title, artist, progress, play/pause, next and volume. */
@Composable
internal fun MusicCard(playing: NowPlaying, music: IdleMusic, u: Dp, modifier: Modifier = Modifier) {
    val artPx = with(LocalDensity.current) { (u * 27f).roundToPx() }
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

    Row(
        modifier
            .widthIn(max = u * 96f)
            .glass(RoundedCornerShape(u * 5f))
            .padding(u * 2.2f),
        horizontalArrangement = Arrangement.spacedBy(u * 3f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, u)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 1f)) {
            Text(
                playing.title.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = IdleColors.text, fontSize = u.sp(6.4f), fontWeight = FontWeight.SemiBold),
            )
            playing.artist?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = IdleColors.textSoft, fontSize = u.sp(5f)))
            }
            // Numbers, bars and controls read left to right in any language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val duration = playing.durationSec
                val position = playing.positionAt(nowMs)
                if (duration != null && position != null) Progress(position, duration, u)
                Controls(playing, music, u)
            }
        }
    }
}

@Composable
private fun Artwork(art: ImageBitmap?, u: Dp) {
    val shape = RoundedCornerShape(u * 2.6f)
    val description = stringResource(R.string.idle_music_art)
    if (art != null) {
        Image(
            art,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(u * 27f).clip(shape),
        )
    } else {
        // No artwork (or HA's address unknown): a quiet gradient in its place.
        Box(
            Modifier.size(u * 27f).clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF5B6B8C), Color(0xFF2A3350))))
                .semantics { contentDescription = description },
        )
    }
}

@Composable
private fun Progress(position: Double, duration: Double, u: Dp) {
    val style = TextStyle(color = IdleColors.textSoft, fontSize = u.sp(3.6f))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 1.6f)) {
        Text(clock(position), style = style)
        Bar((position / duration).toFloat(), u, Modifier.weight(1f))
        Text(clock(duration), style = style)
    }
}

@Composable
private fun Bar(fraction: Float, u: Dp, modifier: Modifier) {
    Canvas(modifier.height(u * 0.9f)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color(0x40FFFFFF), cornerRadius = r)
        drawRoundRect(Color.White, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = r)
    }
}

@Composable
private fun Controls(playing: NowPlaying, music: IdleMusic, u: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 2.6f)) {
        RoundButton(
            size = u * 11f,
            background = Color.White,
            label = stringResource(if (playing.playing) R.string.idle_music_pause else R.string.idle_music_play),
            onClick = music::playPause,
        ) { color -> if (playing.playing) drawPause(color) else drawPlay(color) }
        RoundButton(
            size = u * 9f,
            background = Color(0x24FFFFFF),
            label = stringResource(R.string.idle_music_next),
            onClick = music::next,
        ) { color -> drawNext(color) }
        playing.volume?.let { VolumeSlider(it, music, u, Modifier.weight(1f)) }
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
        Canvas(Modifier.size(size * 0.46f)) { icon(iconColor) }
    }
}

/** Tap or drag to set the volume; HA is told once the finger lifts. */
@Composable
private fun VolumeSlider(volume: Double, music: IdleMusic, u: Dp, modifier: Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: volume.toFloat()
    val label = stringResource(R.string.idle_music_volume)
    Row(
        modifier.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 1.4f),
    ) {
        Canvas(Modifier.size(u * 4.6f)) { drawSpeaker(Color.White) }
        Box(
            Modifier.weight(1f).height(u * 6f)
                .pointerInput(Unit) {
                    detectTapGestures { music.setVolume((it.x / size.width).toDouble()) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragging?.let { music.setVolume(it.toDouble()) }; dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Bar(shown, u, Modifier.fillMaxWidth())
        }
    }
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
