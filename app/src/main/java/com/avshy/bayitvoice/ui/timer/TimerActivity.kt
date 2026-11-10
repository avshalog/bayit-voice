package com.avshy.bayitvoice.ui.timer

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.timer.ActiveTimer
import com.avshy.bayitvoice.timer.RingKind
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalDensity
import com.avshy.bayitvoice.timer.TimerAlarms
import kotlinx.coroutines.delay

/**
 * The timer screen, over the lock screen: a ringing timer with a large stop button, or (opened
 * from the chip) the running timer with a cancel button. Closes once nothing is left to show.
 */
class TimerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val alarms = TimerAlarms.get(this)
        setContent {
            val timers by alarms.timers.collectAsStateWithLifecycle()
            LaunchedEffect(timers.isEmpty()) { if (timers.isEmpty()) finish() }
            val ringing = timers.firstOrNull { it.ringing }
            val running = timers.filter { it.kind == RingKind.TIMER }.minByOrNull { it.leftMs() }
            Box(Modifier.fillMaxSize().background(Color(TimerColors.BACKGROUND))) {
                when {
                    ringing != null -> Ringing(
                        ringing,
                        onStop = {
                            alarms.stopRinging()
                            finish()
                        },
                        onLater = { minutes ->
                            alarms.snoozeRinging(minutes)
                            finish()
                        },
                    )
                    running != null -> Running(running, onCancel = { alarms.dismiss(running.id) }, onClose = ::finish)
                }
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

private val Accent = Color(TimerColors.ACCENT)
private const val SNOOZE_MINUTES = 9
private const val REMIND_LATER_MINUTES = 10

@Composable
private fun Ringing(timer: ActiveTimer, onStop: () -> Unit, onLater: (Int) -> Unit) {
    val res = LocalContext.current.resources
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse",
    )
    // A phone on its side has about 360 dp of height: the same layout, with a smaller ring and less spacing.
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
    val compact = maxHeight < 560.dp
    val ring = if (compact) (maxHeight * 0.28f).coerceIn(72.dp, 180.dp) else 180.dp
    Column(
        Modifier.fillMaxSize().padding(if (compact) 16.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(ring).alpha(pulse).border(4.dp, Accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (timer.kind) {
                RingKind.TIMER -> Icon(Icons.Outlined.Notifications, contentDescription = null, tint = Accent, modifier = Modifier.size(ring * 0.22f))
                RingKind.ALARM -> Text("⏰", fontSize = with(LocalDensity.current) { (ring * 0.3f).toSp() })
                RingKind.REMINDER -> Text("🔔", fontSize = with(LocalDensity.current) { (ring * 0.3f).toSp() })
            }
        }
        Spacer(Modifier.height(if (compact) 14.dp else 28.dp))
        Text(
            when (timer.kind) {
                RingKind.TIMER -> TimerText.name(res, timer)
                RingKind.ALARM -> timer.name.ifBlank { stringResource(R.string.alarm_default_name) }
                RingKind.REMINDER -> timer.name
            },
            color = Color(TimerColors.TITLE), fontSize = 30.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 3,
            style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (timer.kind) {
                RingKind.TIMER -> stringResource(R.string.timer_finished_text, TimerText.duration(res, timer.totalSeconds))
                RingKind.ALARM -> timer.clock.orEmpty()
                RingKind.REMINDER -> stringResource(R.string.reminder_subtitle, timer.clock.orEmpty())
            },
            color = Color(TimerColors.MUTED), fontSize = 18.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        val height = if (compact) 56.dp else 64.dp
        Row(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Alarms snooze and reminders come back later; a timer only stops.
            val later = when (timer.kind) {
                RingKind.TIMER -> null
                RingKind.ALARM -> SNOOZE_MINUTES to stringResource(R.string.alarm_snooze, SNOOZE_MINUTES)
                RingKind.REMINDER -> REMIND_LATER_MINUTES to stringResource(R.string.reminder_later, REMIND_LATER_MINUTES)
            }
            if (later != null) {
                OutlinedButton(onClick = { onLater(later.first) }, modifier = Modifier.weight(1f).height(height)) {
                    Text(later.second, fontSize = 18.sp, color = Color(TimerColors.TEXT), textAlign = TextAlign.Center)
                }
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(TimerColors.ON_ACCENT)),
                modifier = Modifier.weight(1f).height(height),
            ) {
                Text(
                    stringResource(if (timer.kind == RingKind.REMINDER) R.string.reminder_done else R.string.timer_stop),
                    fontSize = 20.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        Text(stringResource(R.string.timer_stop_hint), color = Color(TimerColors.MUTED), fontSize = 14.sp)
        Spacer(Modifier.height(if (compact) 4.dp else 16.dp))
    }
    }
}

@Composable
private fun Running(timer: ActiveTimer, onCancel: () -> Unit, onClose: () -> Unit) {
    val res = LocalContext.current.resources
    val left by produceState(timer.leftMs(), timer) {
        while (true) {
            value = timer.leftMs()
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            TimerText.name(res, timer),
            color = Color(TimerColors.TEXT), fontSize = 22.sp,
            style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content),
        )
        Text(TimerText.countdown(left), color = Color(TimerColors.TITLE), fontSize = 72.sp, fontWeight = FontWeight.Light)
        Text(TimerText.duration(res, timer.totalSeconds), color = Color(TimerColors.MUTED), fontSize = 16.sp)
        Spacer(Modifier.height(40.dp))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.timer_cancel), color = Accent) }
        TextButton(onClick = onClose) { Text(stringResource(R.string.timer_close), color = Color(TimerColors.MUTED)) }
    }
}
