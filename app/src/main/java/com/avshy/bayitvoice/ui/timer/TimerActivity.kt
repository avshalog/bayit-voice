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
            val running = timers.minByOrNull { it.leftMs() }
            Box(Modifier.fillMaxSize().background(Color(TimerColors.BACKGROUND))) {
                when {
                    ringing != null -> Ringing(ringing, onStop = {
                        alarms.stopRinging()
                        finish()
                    })
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

@Composable
private fun Ringing(timer: ActiveTimer, onStop: () -> Unit) {
    val res = LocalContext.current.resources
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse",
    )
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(180.dp).alpha(pulse).border(4.dp, Accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Notifications, contentDescription = null, tint = Accent, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(
            TimerText.name(res, timer),
            color = Color(TimerColors.TITLE), fontSize = 30.sp, fontWeight = FontWeight.Medium,
            style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.timer_finished_text, TimerText.duration(res, timer.totalSeconds)),
            color = Color(TimerColors.MUTED), fontSize = 18.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(TimerColors.ON_ACCENT)),
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).height(64.dp),
        ) { Text(stringResource(R.string.timer_stop), fontSize = 20.sp, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.timer_stop_hint), color = Color(TimerColors.MUTED), fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
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
