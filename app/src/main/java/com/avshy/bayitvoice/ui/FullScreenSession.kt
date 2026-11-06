package com.avshy.bayitvoice.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.session.SessionNotice
import com.avshy.bayitvoice.session.SessionPhase
import com.avshy.bayitvoice.session.SessionState
import com.avshy.bayitvoice.ui.orb.Orb
import com.avshy.bayitvoice.ui.orb.OrbState

/**
 * The full-screen conversation, for a device fixed in one place: a large orb and text big
 * enough to read across the room. Before there is any text the orb is the hero in the middle;
 * once text arrives it moves aside (landscape) or up (portrait) and the text takes over.
 * Laid out on a 1280×800 (or 800×1280) design and scaled to the screen.
 */
@Composable
fun FullScreenSession(state: SessionState, onMic: () -> Unit, onClose: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(FullColors.background)) {
        val portrait = maxHeight > maxWidth
        val design = if (portrait) Design.Portrait else Design.Landscape
        val k = minOf(maxWidth.value / design.width, maxHeight.value / design.height).coerceIn(0.4f, 1.6f)
        val scale = Scale(k)
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

        val phase = state.phase
        val hasText = state.userText.isNotEmpty() || state.replyText.isNotEmpty() || state.error != null
        val hero = !hasText && phase != SessionPhase.Elsewhere
        val spot = if (hero) design.hero else design.aside
        // Landscape puts the orb on the end side, away from where the text starts.
        val spotX = if (rtl || portrait) spot.x else design.width - spot.x
        val orbX by animateDpAsState((spotX / design.width * maxWidth.value).dp, moveDp, label = "orbX")
        val orbY by animateDpAsState((spot.y / design.height * maxHeight.value).dp, moveDp, label = "orbY")
        val orbScale by animateFloatAsState(spot.scale, moveFloat, label = "orbScale")

        val orbState = orbStateFor(phase)
        val doneButton = rememberDoneButton(phase)
        val button = when {
            phase == SessionPhase.Done && doneButton -> R.string.session_speak_again
            phase == SessionPhase.Error -> R.string.session_try_again
            phase == SessionPhase.Idle -> R.string.session_mic
            else -> null
        }
        var lastOrbState by remember { mutableStateOf(OrbState.Connecting) }
        if (orbState != null) lastOrbState = orbState
        val orbAlpha by animateFloatAsState(if (orbState != null && button == null) 1f else 0f, tween(350), label = "orbAlpha")

        // A soft glow of the accent around the orb.
        val glowRadius = with(LocalDensity.current) { scale.dp(520f).toPx() * orbScale }
        val glowCenter = with(LocalDensity.current) { Offset(orbX.toPx(), orbY.toPx()) }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = 0.4f + 0.6f * orbAlpha }.background(
                Brush.radialGradient(listOf(SessionColors.accent.copy(alpha = 0.16f), Color.Transparent), glowCenter, glowRadius.coerceAtLeast(1f)),
            ),
        )

        val orbBox = scale.dp(520f)
        Orb(
            lastOrbState, orbSize = scale.dp(440f), tint = SessionColors.accent, countMul = 2.4f, speedMul = 0.8f,
            modifier = Modifier
                .centreAt(orbX, orbY)
                .requiredSize(orbBox)
                .graphicsLayer { scaleX = orbScale; scaleY = orbScale; alpha = orbAlpha },
        )

        TopBar(state, hero, scale, onClose)

        if (hasText) {
            val t = design.text
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = (t.start / design.width * maxWidth.value).dp,
                        end = (t.end / design.width * maxWidth.value).dp,
                        top = (t.top / design.height * maxHeight.value).dp,
                        bottom = (t.bottom / design.height * maxHeight.value).dp,
                    )
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(scale.dp(36f), if (portrait) Alignment.Top else Alignment.CenterVertically),
            ) {
                Texts(state, scale)
            }
        }

        AnimatedVisibility(
            button != null,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.centreAt(orbX, orbY),
        ) {
            var label by remember { mutableStateOf(R.string.session_mic) }
            if (button != null) label = button
            BigMicButton(label, scale, onMic)
        }
    }
}

@Composable
private fun TopBar(state: SessionState, hero: Boolean, scale: Scale, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(scale.dp(48f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (state.phase == SessionPhase.Elsewhere) stringResource(R.string.phase_elsewhere, state.answeredBy.orEmpty())
            else stringResource(phaseLabel(state.phase)),
            color = if (hero) SessionColors.accent else SessionColors.text,
            fontSize = scale.sp(48f),
            lineHeight = scale.sp(58f),
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(textDirection = TextDirection.Content),
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(scale.dp(88f).coerceAtLeast(48.dp)),
            colors = IconButtonDefaults.iconButtonColors(containerColor = FullColors.raised, contentColor = SessionColors.muted),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.session_close), modifier = Modifier.size(scale.dp(36f)))
        }
    }
}

@Composable
private fun Texts(state: SessionState, scale: Scale) {
    val content = TextStyle(textDirection = TextDirection.Content)
    val enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 6 }
    AnimatedVisibility(state.userText.isNotEmpty(), enter = enter) {
        Box(Modifier.padding(start = scale.dp(16f)).widthIn(max = scale.dp(900f))) {
            Text(
                state.userText,
                color = SessionColors.text,
                fontSize = scale.sp(40f),
                lineHeight = scale.sp(52f),
                style = content,
                modifier = Modifier
                    .background(FullColors.bubble, RoundedCornerShape(topStart = scale.dp(36f), topEnd = scale.dp(36f), bottomEnd = scale.dp(36f)))
                    .padding(horizontal = scale.dp(30f), vertical = scale.dp(18f)),
            )
            BubbleTail(FullColors.bubble, Modifier.align(Alignment.BottomStart).offset(x = -scale.dp(23f)).size(scale.dp(24f), scale.dp(31f)))
        }
    }
    AnimatedVisibility(state.replyText.isNotEmpty(), enter = enter) {
        Text(
            state.replyText,
            color = SessionColors.reply,
            fontSize = scale.sp(54f),
            lineHeight = scale.sp(71f),
            fontWeight = FontWeight.SemiBold,
            style = content,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.error?.let {
        Text(errorText(it), color = SessionColors.error, fontSize = scale.sp(40f), lineHeight = scale.sp(52f), style = content)
    }
    state.notice?.let {
        val res = when (it) {
            SessionNotice.PipelineMissing -> R.string.notice_pipeline_missing
            SessionNotice.OldHaVersion -> R.string.notice_old_ha_version
        }
        Text(stringResource(res), color = SessionColors.muted, fontSize = scale.sp(24f), lineHeight = scale.sp(32f))
    }
}

@Composable
private fun BigMicButton(label: Int, scale: Scale, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(scale.dp(112f).coerceAtLeast(56.dp)),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = SessionColors.accent, contentColor = SessionColors.onAccent),
        contentPadding = PaddingValues(horizontal = scale.dp(52f)),
    ) {
        Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(scale.dp(44f)))
        Spacer(Modifier.width(scale.dp(16f)))
        Text(stringResource(label), fontSize = scale.sp(38f), fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Fills the parent and places the element (measured at its own size) with its centre at
 * ([x], [y]) from the top-left corner, in either layout direction.
 */
private fun Modifier.centreAt(x: Dp, y: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints())
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(x.roundToPx() - placeable.width / 2, y.roundToPx() - placeable.height / 2)
    }
}

private val moveDp = tween<Dp>(700, easing = FastOutSlowInEasing)
private val moveFloat = tween<Float>(700, easing = FastOutSlowInEasing)

/** Converts design pixels to dp/sp on this screen. */
private class Scale(val k: Float) {
    fun dp(v: Float): Dp = (v * k).dp
    fun sp(v: Float): TextUnit = (v * k).sp
}

private class Spot(val x: Float, val y: Float, val scale: Float)
private class TextArea(val top: Float, val bottom: Float, val start: Float, val end: Float)

private enum class Design(val width: Float, val height: Float, val hero: Spot, val aside: Spot, val text: TextArea) {
    Landscape(1280f, 800f, Spot(640f, 430f, 1.05f), Spot(300f, 440f, 0.7f), TextArea(top = 150f, bottom = 96f, start = 72f, end = 590f)),
    Portrait(800f, 1280f, Spot(400f, 620f, 1.05f), Spot(400f, 400f, 0.62f), TextArea(top = 640f, bottom = 96f, start = 64f, end = 64f)),
}

private object FullColors {
    val background = Color(0xFF0B0C10)
    val raised = Color(0xFF1E2027)
    val bubble = Color(0xFF22242B)
}
