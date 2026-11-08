package com.avshy.bayitvoice.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.SessionStyle
import com.avshy.bayitvoice.session.WakeDetection
import com.avshy.bayitvoice.service.SessionLauncher
import com.avshy.bayitvoice.session.SessionError
import kotlinx.coroutines.flow.first

/** The conversation UI: a sheet over whatever is on screen, including the lock screen. */
class SessionActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels()

    private var pendingFromWakeWord = false
    private var pendingDetection: WakeDetection? = null

    /** Drives the sheet's slide in and out; the activity finishes once it has slid out. */
    private val sheetVisible = MutableTransitionState(false).apply { targetState = true }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The local network permission is optional: without it the external address is used.
        if (isGranted(Manifest.permission.RECORD_AUDIO)) viewModel.startConversation(pendingFromWakeWord, pendingDetection) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The sheet's own colour runs under the navigation bar; no system scrim over it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        showOverLockScreen()
        val style = AppSettings.get(this).effectiveSessionStyle
        if (style == SessionStyle.FullScreen) hideSystemBars()

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val close by viewModel.closeRequested.collectAsStateWithLifecycle()
            LaunchedEffect(close) { if (close) dismiss() }
            LaunchedEffect(Unit) {
                // Finish once the sheet has fully slid out.
                snapshotFlow { sheetVisible.isIdle && !sheetVisible.currentState && !sheetVisible.targetState }.first { it }
                finish()
            }
            BackHandler { dismiss() }
            val onMic = { startWithPermission(fromWakeWord = false) }
            if (style == SessionStyle.FullScreen) {
                // The whole screen fades in and out, instead of a sheet sliding.
                AnimatedVisibility(visibleState = sheetVisible, enter = fadeIn(tween(300)), exit = fadeOut(tween(260))) {
                    FullScreenSession(state = state, onMic = onMic, onClose = ::dismiss)
                }
            } else {
                SessionOverlay(visible = sheetVisible, onDismiss = ::dismiss) {
                    SessionSheet(state = state, futuristic = style == SessionStyle.Futuristic, onMic = onMic, onClose = ::dismiss)
                }
            }
        }

        if (savedInstanceState == null) startWithPermission(intent.isFromWakeWord(), SessionLauncher.detection(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sheetVisible.targetState = true
        startWithPermission(intent.isFromWakeWord(), SessionLauncher.detection(intent))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) viewModel.onUserTouch()
        return super.dispatchTouchEvent(ev)
    }

    private fun dismiss() {
        sheetVisible.targetState = false
    }

    /** The wake word and the system's assistant gesture both start hands-free: chime first, close on silence. */
    private fun Intent.isFromWakeWord() =
        action == Intent.ACTION_ASSIST || getBooleanExtra(SessionLauncher.EXTRA_FROM_WAKE_WORD, false)

    /** [detection] is set only for a wake word: a key press or mic tap picks this device, unarbitrated. */
    private fun startWithPermission(fromWakeWord: Boolean, detection: WakeDetection? = null) {
        pendingFromWakeWord = fromWakeWord
        pendingDetection = detection
        val needed = (arrayOf(Manifest.permission.RECORD_AUDIO) + viewModel.localNetworkPermissions())
            .filterNot(::isGranted)
        if (needed.isEmpty()) viewModel.startConversation(fromWakeWord, detection) else permissions.launch(needed.toTypedArray())
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Full screen hides the status and navigation bars; a swipe from the edge shows them for a moment. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
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

    companion object {
        fun intent(context: Context) =
            Intent(context, SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** A dimmed backdrop (tap to close) with the sheet sliding up from the bottom edge. */
@Composable
private fun SessionOverlay(visible: MutableTransitionState<Boolean>, onDismiss: () -> Unit, sheet: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(260))) {
            Box(
                Modifier.fillMaxSize()
                    .background(SessionColors.scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visibleState = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(320)) { it },
            exit = slideOutVertically(tween(260)) { it },
        ) { sheet() }
    }
}

/** Fixed dark colours: the sheet looks the same over any wallpaper and in either system theme. */
object SessionColors {
    val scrim = Color(0x59080A0C)
    val sheet = Color(0xFF1C1D22)
    val handle = Color(0xFF45484F)
    val raised = Color(0xFF2A2C33)
    val text = Color(0xFFECEDF0)
    val reply = Color(0xFFF5F6F8)
    val muted = Color(0xFFC9CCD3)
    val accent = Color(0xFF7FA6FF)
    val onAccent = Color(0xFF0E1013)
    val done = Color(0xFF5CCB8A)
    val error = Color(0xFFFFB4AB)
}

@Composable
internal fun errorText(error: SessionError): String = when (error) {
    SessionError.NotConfigured -> stringResource(R.string.error_not_configured)
    SessionError.InvalidUrl -> stringResource(R.string.error_invalid_url)
    SessionError.InsecureUrl -> stringResource(R.string.error_insecure_url)
    SessionError.AuthInvalid -> stringResource(R.string.error_auth_invalid)
    SessionError.Unreachable -> stringResource(R.string.error_unreachable)
    SessionError.Disconnected -> stringResource(R.string.error_disconnected)
    SessionError.Timeout -> stringResource(R.string.error_timeout)
    SessionError.NoSpeech -> stringResource(R.string.error_no_speech)
    SessionError.Microphone -> stringResource(R.string.error_microphone)
    SessionError.LocalNetworkDenied -> stringResource(R.string.error_local_network_denied)
    SessionError.SatelliteNotConnected -> stringResource(R.string.error_satellite_not_connected)
    is SessionError.Server -> stringResource(R.string.error_server, error.message)
}
