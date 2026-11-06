package com.avshy.bayitvoice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: lets adb act as if the wake word was heard, to test arbitration without
 * speaking. `adb shell am broadcast -n com.avshy.bayitvoice/.service.DebugDetectReceiver --ef score 0.9 --ef snr 20`
 */
class DebugDetectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startService(
            Intent(context, WakeWordService::class.java)
                .setAction("debug_detect")
                .putExtra("score", intent.getFloatExtra("score", 0.9f))
                .putExtra("snr", intent.getFloatExtra("snr", 20f)),
        )
    }
}
