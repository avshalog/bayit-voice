package com.avshy.bayitvoice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.avshy.bayitvoice.config.AppSettings

/**
 * "Start on boot": a microphone service cannot be started from the background, so this opens
 * a tiny activity that starts it (or asks the user to, when that is not allowed).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = AppSettings.get(context)
        if (settings.startOnBoot && settings.wakeWordEnabled) SessionLauncher.startListeningAfterBoot(context)
    }
}
