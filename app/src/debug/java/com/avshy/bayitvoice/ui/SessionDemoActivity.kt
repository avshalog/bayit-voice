package com.avshy.bayitvoice.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.config.SessionStyle
import com.avshy.bayitvoice.session.SessionPhase
import com.avshy.bayitvoice.session.SessionState
import kotlinx.coroutines.delay

/**
 * Debug builds only: plays a sample conversation in one session style, without Home Assistant,
 * to check the session UI on a device or emulator.
 *
 * `adb shell am start -n <package>/.ui.SessionDemoActivity --es style FullScreen [--es phase Speaking]`
 * Styles: Wave, Futuristic, FullScreen. With `phase`, it holds that one state; `--ez landscape true` forces landscape.
 */
class SessionDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val style = intent.getStringExtra("style")?.let { runCatching { SessionStyle.valueOf(it) }.getOrNull() } ?: SessionStyle.Futuristic
        val fixed = intent.getStringExtra("phase")?.let { runCatching { SessionPhase.valueOf(it) }.getOrNull() }
        if (intent.getBooleanExtra("landscape", false)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (style == SessionStyle.FullScreen) {
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            val user = stringResource(R.string.demo_user_text)
            val reply = stringResource(R.string.demo_reply_text)
            var phase by remember { mutableStateOf(fixed ?: SessionPhase.Connecting) }
            LaunchedEffect(Unit) {
                if (fixed != null) return@LaunchedEffect
                while (true) {
                    for ((p, ms) in STEPS) {
                        phase = p
                        delay(ms)
                    }
                }
            }
            val state = SessionState(
                phase = phase,
                userText = if (phase in listOf(SessionPhase.Thinking, SessionPhase.Speaking, SessionPhase.Done)) user else "",
                replyText = if (phase == SessionPhase.Speaking || phase == SessionPhase.Done) reply else "",
            )
            if (style == SessionStyle.FullScreen) {
                FullScreenSession(state, onMic = { phase = SessionPhase.Connecting }, onClose = ::finish)
            } else {
                Box(Modifier.fillMaxSize().background(SessionColors.scrim), contentAlignment = Alignment.BottomCenter) {
                    SessionSheet(state, futuristic = style == SessionStyle.Futuristic, onMic = { phase = SessionPhase.Connecting }, onClose = ::finish)
                }
            }
        }
    }

    private companion object {
        val STEPS = listOf(
            SessionPhase.Connecting to 1_400L,
            SessionPhase.Listening to 3_200L,
            SessionPhase.Thinking to 1_800L,
            SessionPhase.Speaking to 5_000L,
            SessionPhase.Done to 6_000L,
        )
    }
}
