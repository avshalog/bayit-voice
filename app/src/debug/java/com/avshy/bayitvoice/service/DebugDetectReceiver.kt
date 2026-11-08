package com.avshy.bayitvoice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: forwards a debug action from adb to the (unexported) listening service.
 * Default action acts as if the wake word was heard:
 * `adb shell am broadcast -n com.avshy.bayitvoice/.service.DebugDetectReceiver --ef score 0.9 --ef snr 20`
 * Others: `--es action debug_link --es link Satellite`, `--es action debug_copy_key`.
 */
class DebugDetectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "debug_detect"
        val forward = Intent(context, WakeWordService::class.java).setAction(action)
        intent.extras?.let(forward::putExtras)
        if (action == "debug_detect") {
            forward.putExtra("score", intent.getFloatExtra("score", 0.9f))
                .putExtra("snr", intent.getFloatExtra("snr", 20f))
        }
        context.startService(forward)
    }
}
