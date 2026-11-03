package com.avshy.bayitvoice.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.avshy.bayitvoice.R

/** Notification channels and ids used by the background parts of the app. */
object Notifications {
    const val CHANNEL_LISTENING = "listening"
    const val CHANNEL_ALERTS = "alerts"

    const val ID_LISTENING = 1
    const val ID_SESSION = 2
    const val ID_BOOT = 3

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTENING,
                context.getString(R.string.channel_listening),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }
}
