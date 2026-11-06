package com.avshy.bayitvoice.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.session.SessionNotice
import com.avshy.bayitvoice.session.SessionPhase
import com.avshy.bayitvoice.session.SessionState

/** The conversation sheet: status, what the user said, the reply, and one bottom slot. */
@Composable
fun SessionSheet(state: SessionState, onMic: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SessionColors.sheet, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(SessionColors.handle, CircleShape))
        StatusRow(state.phase, state.answeredBy, onClose)
        Conversation(state)
        BottomSlot(state.phase, onMic)
    }
}

@Composable
private fun StatusRow(phase: SessionPhase, answeredBy: String?, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PhaseIcon(phase, SessionColors.accent)
        Row(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (phase == SessionPhase.Elsewhere) stringResource(R.string.phase_elsewhere, answeredBy.orEmpty())
                else stringResource(phaseLabel(phase)),
                color = SessionColors.text,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(textDirection = TextDirection.Content),
            )
            if (phase == SessionPhase.Listening || phase == SessionPhase.Thinking) {
                BouncingDots(SessionColors.accent, Modifier.padding(bottom = 6.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = SessionColors.raised, contentColor = SessionColors.muted),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.session_close), modifier = Modifier.size(18.dp))
        }
    }
}

private fun phaseLabel(phase: SessionPhase) = when (phase) {
    SessionPhase.Idle -> R.string.phase_idle
    SessionPhase.Connecting -> R.string.phase_connecting
    SessionPhase.Listening -> R.string.phase_listening
    SessionPhase.Thinking -> R.string.phase_thinking
    SessionPhase.Speaking, SessionPhase.Done -> R.string.phase_speaking
    SessionPhase.Error -> R.string.phase_error
    SessionPhase.Elsewhere -> R.string.phase_elsewhere // formatted with the device name by the caller
}

@Composable
private fun Conversation(state: SessionState) {
    val contentStyle = TextStyle(textDirection = TextDirection.Content)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 260.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(state.userText.isNotEmpty(), enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 4 }) {
            UserBubble(state.userText)
        }
        AnimatedVisibility(state.replyText.isNotEmpty(), enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 4 }) {
            Text(
                state.replyText,
                color = SessionColors.reply,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Medium,
                style = contentStyle,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.error?.let {
            Text(errorText(it), color = SessionColors.error, fontSize = 15.sp, lineHeight = 22.sp, style = contentStyle)
        }
        state.notice?.let {
            val res = when (it) {
                SessionNotice.PipelineMissing -> R.string.notice_pipeline_missing
                SessionNotice.OldHaVersion -> R.string.notice_old_ha_version
            }
            Text(stringResource(res), color = SessionColors.muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** What the user said, in a speech bubble whose curved tail points at the start edge. */
@Composable
private fun UserBubble(text: String) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.padding(start = 10.dp).widthIn(max = 300.dp)) {
        Text(
            text,
            color = SessionColors.text,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            style = TextStyle(textDirection = TextDirection.Content),
            modifier = Modifier
                .background(SessionColors.raised, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 0.dp))
                .padding(PaddingValues(horizontal = 16.dp, vertical = 10.dp)),
        )
        Canvas(Modifier.align(Alignment.BottomStart).offset(x = (-13.5).dp).size(14.dp, 18.dp)) {
            // Drawn for a tail on the right; mirrored when the start edge is on the left.
            scale(scaleX = if (rtl) 1f else -1f, scaleY = 1f) {
                scale(size.width / 14f, pivot = Offset.Zero) { drawPath(tailPath, SessionColors.raised) }
            }
        }
    }
}

private val tailPath = PathParser().parsePathString("M0.5 0 C0.5 8 4 14.5 13.5 17.6 C9 18.2 4 18 0.5 17.4 Z").toPath()

/** One fixed-height slot: the reply's waveform while it plays, otherwise the mic button when one is useful. */
@Composable
private fun BottomSlot(phase: SessionPhase, onMic: () -> Unit) {
    val slot = when (phase) {
        SessionPhase.Speaking -> Slot.Wave
        SessionPhase.Done -> Slot.SpeakAgain
        SessionPhase.Error -> Slot.TryAgain
        SessionPhase.Idle -> Slot.Speak
        else -> Slot.Empty
    }
    AnimatedContent(
        targetState = slot,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        label = "slot",
    ) { current ->
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            when (current) {
                Slot.Wave -> SpeechWave(SessionColors.accent, Modifier.fillMaxWidth().height(36.dp))
                Slot.SpeakAgain -> MicButton(R.string.session_speak_again, onMic)
                Slot.TryAgain -> MicButton(R.string.session_try_again, onMic)
                Slot.Speak -> MicButton(R.string.session_mic, onMic)
                Slot.Empty -> Unit
            }
        }
    }
}

private enum class Slot { Empty, Wave, SpeakAgain, TryAgain, Speak }

@Composable
private fun MicButton(label: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = SessionColors.accent, contentColor = SessionColors.onAccent),
        contentPadding = PaddingValues(horizontal = 22.dp),
    ) {
        Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
