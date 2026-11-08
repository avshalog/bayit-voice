package com.avshy.bayitvoice.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.avshy.bayitvoice.BuildConfig
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.session.WakeDetection
import com.avshy.bayitvoice.audio.MicArbiter
import com.avshy.bayitvoice.audio.MicRecorder
import com.avshy.bayitvoice.audio.MicrophoneException
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.AssistLink
import com.avshy.bayitvoice.config.ConnectionMode
import com.avshy.bayitvoice.satellite.SatelliteDevice
import com.avshy.bayitvoice.timer.TimerAlarms
import com.avshy.bayitvoice.ha.ConnectionManager
import com.avshy.bayitvoice.wakeword.WakeWordDetector
import com.avshy.bayitvoice.wakeword.WakeWordEngine
import com.avshy.bayitvoice.wakeword.WakeWordModels
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground microphone service that listens for the wake word and opens the session UI.
 * Holds no UI code; the session UI is addressed by class name only.
 */
class WakeWordService : Service() {

    // A failure in one job must never take the whole app process down.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background job failed", e) },
    )
    private var listening: Job? = null
    private var connection: Job? = null
    private var engine: WakeWordEngine? = null
    private var detector: WakeWordDetector? = null
    private var currentModelName: String? = null
    @Volatile private var micSilenced = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppSettings.get(this).wakeWordEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> if (listening != null) {
                restart()
                return START_NOT_STICKY
            }
            // Debug builds: act as if the wake word was heard, to test arbitration without speaking.
            ACTION_DEBUG_DETECT -> if (BuildConfig.DEBUG && listening != null) {
                onWakeWord(intent.getFloatExtra("score", 0.9f), intent.getFloatExtra("snr", 20f))
                return START_NOT_STICKY
            }
            // Debug builds: switch between direct and satellite mode until settings have a control for it.
            ACTION_DEBUG_LINK -> if (BuildConfig.DEBUG) {
                val link = runCatching { AssistLink.valueOf(intent.getStringExtra("link").orEmpty()) }.getOrNull()
                if (link != null) AppSettings.get(this).assistLink = link
                if (listening != null) restart()
                return START_NOT_STICKY
            }
            // Debug builds: a timer as if HA had set it, to try the timer screens.
            ACTION_DEBUG_TIMER -> if (BuildConfig.DEBUG && listening != null) {
                TimerAlarms.get(this).simulate(scope, intent.getStringExtra("name").orEmpty(), intent.getIntExtra("seconds", 10))
                return START_NOT_STICKY
            }
        }
        if (!goForeground()) return START_NOT_STICKY
        if (listening == null) restart()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(recordingCallback)
        // Wait (at most one 80 ms frame) for capture to stop before freeing the native models.
        runBlocking { listening?.cancelAndJoin() }
        SatelliteDevice.get(this).stop()
        scope.cancel()
        engine?.close()
        isRunning = false
        super.onDestroy()
    }

    /** Must run while the app is visible: Android forbids starting microphone services from the background. */
    private fun goForeground(): Boolean = try {
        Notifications.ensureChannels(this)
        ServiceCompat.startForeground(this, Notifications.ID_LISTENING, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        isRunning = true
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot start listening in the foreground: ${e.javaClass.simpleName}")
        stopSelf()
        false
    }

    /** (Re)loads the model and settings and starts listening and, if configured, the kept connection. */
    private fun restart() {
        val settings = AppSettings.get(this)
        val models = WakeWordModels(this)
        val model = models.find(settings.wakeModelId) ?: models.find(WakeWordModels.DEFAULT_MODEL_ID)!!

        val previous = listening
        connection?.cancel()
        listening = scope.launch {
            // The old engine may still be mid-frame; close it only once its capture has stopped.
            previous?.cancelAndJoin()
            engine?.close()
            engine = null
            val newEngine = try {
                models.createEngine(model)
            } catch (e: Exception) {
                // Never crash-loop on a bad model: switch listening off and report it in settings.
                Log.e(TAG, "Cannot load wake word model '${model.name}'", e)
                settings.wakeWordEnabled = false
                settings.wakeModelLoadFailed = true
                stopSelf()
                return@launch
            }
            engine = newEngine
            val newDetector = WakeWordDetector(
                engine = newEngine,
                threshold = settings.wakeThreshold,
                onDetected = ::onWakeWord,
                // Debug builds log near misses, to tune the threshold or retrain a model.
                onUtteranceEnd = if (BuildConfig.DEBUG) { peak -> if (peak >= 0.05f) Log.d(TAG, "utterance peak score %.2f".format(peak)) } else null,
            )
            detector = newDetector
            Log.i(TAG, "Listening for '${model.name}' (threshold ${settings.wakeThreshold})")
            if (BuildConfig.DEBUG) launch { logStats(newDetector) }

            // Listen only while no session owns the microphone.
            MicArbiter.sessionActive.collectLatest { sessionActive ->
                if (!sessionActive) capture(newEngine.frameSamples, newDetector)
            }
        }
        val satellite = SatelliteDevice.get(this)
        if (settings.assistLink == AssistLink.Satellite) {
            satellite.wakeWords = SatelliteWakeWords(this)
            satellite.start(scope)
            satellite.sendConfiguration()
            TimerAlarms.get(this).start(scope)
        } else {
            satellite.stop()
            if (settings.connectionMode == ConnectionMode.KeepConnected && settings.isConfigured) {
                connection = scope.launch { ConnectionManager.shared(this@WakeWordService).keepAlive() }
            }
        }
        updateNotification(model.name)
    }

    /** The installed wake-word models, offered to HA; choosing one in HA switches to it. */
    private class SatelliteWakeWords(private val service: Context) : SatelliteDevice.WakeWords {
        private val settings = AppSettings.get(service)
        override fun available() = WakeWordModels(service).all().map { it.id to it.name }
        override fun activeId() = settings.wakeModelId
        override fun activate(id: String) {
            if (WakeWordModels(service).find(id) == null) return
            Log.i(TAG, "HA switched the wake word to $id")
            settings.wakeModelId = id
            reload(service)
        }
    }

    private suspend fun capture(frameSamples: Int, detector: WakeWordDetector) {
        MicArbiter.setWakeCapturing(true)
        detector.reset()
        try {
            MicRecorder().recordFrames(frameSamples, detector::onFrame)
        } catch (e: MicrophoneException) {
            Log.w(TAG, "Microphone unavailable: ${e.message}")
        } finally {
            MicArbiter.setWakeCapturing(false)
        }
    }

    private fun onWakeWord(score: Float, snrDb: Float) {
        Log.i(TAG, "Wake word detected (score %.2f, %.1f dB above the noise floor)".format(score, snrDb))
        // While a timer rings, the wake word only silences it.
        val timers = TimerAlarms.get(this)
        if (timers.isRinging) {
            timers.stopRinging()
            return
        }
        // Stop wake-word capture right away, so the same word isn't detected again meanwhile.
        MicArbiter.requestSession()
        // The session opens at once; it arbitrates with other devices itself (SPEC section 6.2).
        val detection = WakeDetection(SystemClock.elapsedRealtime(), score, snrDb, detector?.noiseFloor ?: 0.0)
        SessionLauncher.openSession(this, detection)
        // If the session UI never starts (e.g. the notification is ignored), resume listening.
        scope.launch {
            delay(SESSION_CLAIM_TIMEOUT_MS)
            if (!MicArbiter.sessionClaimed && MicArbiter.sessionActive.value) MicArbiter.endSession()
        }
    }

    private fun buildNotification(modelName: String? = null): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, SessionLauncher.mainActivityIntent(this), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notification_listening_title))
            .setContentText(
                if (micSilenced) getString(R.string.notification_mic_busy)
                else modelName?.let { getString(R.string.notification_listening_text, it) }
            )
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(modelName: String) {
        currentModelName = modelName
        getSystemService(android.app.NotificationManager::class.java)
            .notify(Notifications.ID_LISTENING, buildNotification(modelName))
    }

    /**
     * Android gives the microphone to one capturing app at a time; others receive silence.
     * Watch for that (e.g. another assistant's wake word) and say so in the notification.
     */
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            // Only this app's own recordings are reported with their silenced state.
            val silenced = configs.any { it.isClientSilenced }
            if (silenced == micSilenced) return
            micSilenced = silenced
            Log.w(TAG, if (silenced) "Microphone silenced: another app is capturing" else "Microphone available again")
            getSystemService(android.app.NotificationManager::class.java)
                .notify(Notifications.ID_LISTENING, buildNotification(currentModelName))
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(AudioManager::class.java).registerAudioRecordingCallback(recordingCallback, null)
    }

    /** Debug builds: log CPU share, memory and how often the heavy models run. */
    private suspend fun logStats(detector: WakeWordDetector) {
        var lastCpu = Process.getElapsedCpuTime()
        var lastWall = SystemClock.elapsedRealtime()
        var lastTotal = detector.framesTotal
        var lastProcessed = detector.framesProcessed
        while (scope.isActive) {
            delay(STATS_INTERVAL_MS)
            val cpu = Process.getElapsedCpuTime()
            val wall = SystemClock.elapsedRealtime()
            val total = detector.framesTotal
            val processed = detector.framesProcessed
            val cpuPercent = 100.0 * (cpu - lastCpu) / (wall - lastWall)
            val gated = if (total > lastTotal) 100.0 * (processed - lastProcessed) / (total - lastTotal) else 0.0
            val pssMb = Debug.getPss() / 1024
            Log.d(TAG, "stats: cpu %.1f%%, models ran on %.0f%% of frames, noise floor %.0f, pss %d MB"
                .format(cpuPercent, gated, detector.noiseFloor, pssMb))
            lastCpu = cpu; lastWall = wall; lastTotal = total; lastProcessed = processed
        }
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val ACTION_STOP = "stop"
        private const val ACTION_RELOAD = "reload"
        private const val ACTION_DEBUG_DETECT = "debug_detect"
        private const val ACTION_DEBUG_LINK = "debug_link"
        private const val ACTION_DEBUG_TIMER = "debug_timer"

        private const val SESSION_CLAIM_TIMEOUT_MS = 20_000L
        private const val STATS_INTERVAL_MS = 60_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Starts listening. Call from a visible activity. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** Applies changed wake-word or connection settings if the service is running. */
        fun reload(context: Context) {
            if (isRunning) context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_RELOAD))
        }
    }
}
