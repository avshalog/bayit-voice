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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.session.SessionError
import com.avshy.bayitvoice.session.SessionNotice
import com.avshy.bayitvoice.session.SessionPhase
import com.avshy.bayitvoice.session.SessionState

/** Full-screen conversation UI; works over the lock screen. */
class SessionActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The local network permission is optional: without it the external address is used.
        if (isGranted(Manifest.permission.RECORD_AUDIO)) viewModel.startConversation() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        setContent {
            BayitTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val close by viewModel.closeRequested.collectAsStateWithLifecycle()
                LaunchedEffect(close) { if (close) finish() }
                SessionScreen(
                    state = state,
                    onMic = ::startWithPermission,
                    onClose = ::finish,
                )
            }
        }

        if (savedInstanceState == null) startWithPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startWithPermission()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) viewModel.cancelAutoClose()
        return super.dispatchTouchEvent(ev)
    }

    private fun startWithPermission() {
        val needed = (arrayOf(Manifest.permission.RECORD_AUDIO) + viewModel.localNetworkPermissions())
            .filterNot(::isGranted)
        if (needed.isEmpty()) viewModel.startConversation() else permissions.launch(needed.toTypedArray())
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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

@Composable
private fun SessionScreen(state: SessionState, onMic: () -> Unit, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.session_close))
            }

            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                PhaseIndicator(state.phase)

                if (state.userText.isNotEmpty()) {
                    Text(
                        text = state.userText,
                        style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.replyText.isNotEmpty()) {
                    Text(
                        text = state.replyText,
                        style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.error?.let {
                    Text(
                        text = errorText(it),
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.notice?.let {
                    Text(
                        text = stringResource(
                            when (it) {
                                SessionNotice.PipelineMissing -> R.string.notice_pipeline_missing
                                SessionNotice.OldHaVersion -> R.string.notice_old_ha_version
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            val busy = state.phase in setOf(SessionPhase.Connecting, SessionPhase.Listening, SessionPhase.Thinking)
            FilledIconButton(
                onClick = onMic,
                enabled = !busy,
                modifier = Modifier.align(Alignment.BottomCenter).size(88.dp),
            ) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.session_mic), modifier = Modifier.size(40.dp))
            }
        }
    }
}

@Composable
private fun PhaseIndicator(phase: SessionPhase) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (phase == SessionPhase.Connecting || phase == SessionPhase.Thinking) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
        }
        val label = when (phase) {
            SessionPhase.Idle -> R.string.phase_idle
            SessionPhase.Connecting -> R.string.phase_connecting
            SessionPhase.Listening -> R.string.phase_listening
            SessionPhase.Thinking -> R.string.phase_thinking
            SessionPhase.Speaking -> R.string.phase_speaking
            SessionPhase.Done -> R.string.phase_done
            SessionPhase.Error -> R.string.phase_error
        }
        Text(stringResource(label), style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
internal fun errorText(error: SessionError): String = when (error) {
    SessionError.NotConfigured -> stringResource(R.string.error_not_configured)
    SessionError.InvalidUrl -> stringResource(R.string.error_invalid_url)
    SessionError.InsecureUrl ->stringResource(R.string.error_insecure_url)
    SessionError.AuthInvalid -> stringResource(R.string.error_auth_invalid)
    SessionError.Unreachable -> stringResource(R.string.error_unreachable)
    SessionError.Disconnected -> stringResource(R.string.error_disconnected)
    SessionError.Timeout -> stringResource(R.string.error_timeout)
    SessionError.NoSpeech -> stringResource(R.string.error_no_speech)
    SessionError.Microphone -> stringResource(R.string.error_microphone)
    SessionError.LocalNetworkDenied -> stringResource(R.string.error_local_network_denied)
    is SessionError.Server -> stringResource(R.string.error_server, error.message)
}
