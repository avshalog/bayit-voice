package com.avshy.bayitvoice.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.session.WakeDetection

/**
 * Opens UI from the background without loading any UI classes into the service: activities
 * are addressed by class name only.
 */
object SessionLauncher {

    const val EXTRA_FROM_WAKE_WORD = "from_wake_word"
    private const val EXTRA_DETECTED_AT = "detected_at"
    private const val EXTRA_SCORE = "score"
    private const val EXTRA_SNR_DB = "snr_db"
    private const val EXTRA_NOISE_FLOOR = "noise_floor"

    /** The wake-word measurements a session was opened with; null for any other start. */
    fun detection(intent: Intent): WakeDetection? {
        if (!intent.hasExtra(EXTRA_DETECTED_AT)) return null
        return WakeDetection(
            detectedAtMs = intent.getLongExtra(EXTRA_DETECTED_AT, 0L),
            score = intent.getFloatExtra(EXTRA_SCORE, 0f),
            snrDb = intent.getFloatExtra(EXTRA_SNR_DB, 0f),
            noiseFloorRms = intent.getDoubleExtra(EXTRA_NOISE_FLOOR, 0.0),
        )
    }

    private const val SESSION_ACTIVITY = "com.avshy.bayitvoice.ui.SessionActivity"
    private const val MAIN_ACTIVITY = "com.avshy.bayitvoice.ui.MainActivity"
    private const val START_LISTENING_ACTIVITY = "com.avshy.bayitvoice.ui.StartListeningActivity"

    /**
     * Opens the session UI after a wake word. Android only lets a background app start an
     * activity when it may "display over other apps"; otherwise a full-screen notification is
     * posted, which shows as a heads-up notification when full-screen intents are not allowed.
     */
    fun openSession(context: Context, detection: WakeDetection) {
        val intent = activityIntent(context, SESSION_ACTIVITY)
            .putExtra(EXTRA_FROM_WAKE_WORD, true)
            .putExtra(EXTRA_DETECTED_AT, detection.detectedAtMs)
            .putExtra(EXTRA_SCORE, detection.score)
            .putExtra(EXTRA_SNR_DB, detection.snrDb)
            .putExtra(EXTRA_NOISE_FLOOR, detection.noiseFloorRms)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(context)) {
            context.startActivity(intent)
            return
        }
        notify(context, Notifications.ID_SESSION, intent, R.string.notification_session_title, R.string.notification_session_text, fullScreen = true)
    }

    /** After boot: start listening directly if allowed, otherwise ask the user with a notification. */
    fun startListeningAfterBoot(context: Context) {
        val intent = activityIntent(context, START_LISTENING_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(context)) {
            context.startActivity(intent)
            return
        }
        notify(context, Notifications.ID_BOOT, intent, R.string.notification_boot_title, R.string.notification_boot_text, fullScreen = false)
    }

    /** The session started by the system's assistant gesture: hands-free, like the wake word. */
    fun assistIntent(context: Context): Intent =
        activityIntent(context, SESSION_ACTIVITY).setAction(Intent.ACTION_ASSIST)

    fun mainActivityIntent(context: Context): Intent =
        activityIntent(context, MAIN_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun activityIntent(context: Context, className: String) =
        Intent().setComponent(ComponentName(context, className))

    private fun notify(context: Context, id: Int, intent: Intent, title: Int, text: Int, fullScreen: Boolean) {
        Notifications.ensureChannels(context)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .apply { if (fullScreen) setFullScreenIntent(pending, true).setTimeoutAfter(FULL_SCREEN_TIMEOUT_MS) }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private const val FULL_SCREEN_TIMEOUT_MS = 15_000L
}
