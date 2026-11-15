package com.avshy.bayitvoice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: forwards a debug action from adb to the (unexported) listening service.
 * Default action acts as if the wake word was heard:
 * `adb shell am broadcast -n com.avshy.bayitvoice/.service.DebugDetectReceiver --ef score 0.9 --ef snr 20`
 * Idle screen: `--es action debug_idle --es debug_condition rainy --es debug_sun sunrise --ef debug_temp 14`.
 * Others: `--es action debug_link --es link Satellite`, `--es action debug_timer --es name Pasta --ei seconds 20`.
 */
class DebugDetectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "debug_detect"
        if (action == "debug_idle") {
            // Opens the idle screen; debug_condition / debug_sun / debug_temp are forwarded (IdleDebug).
            val idle = com.avshy.bayitvoice.ui.idle.IdleActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.extras?.let(idle::putExtras)
            context.startActivity(idle)
            return
        }
        val forward = Intent(context, WakeWordService::class.java).setAction(action)
        intent.extras?.let(forward::putExtras)
        if (action == "debug_detect") {
            forward.putExtra("score", intent.getFloatExtra("score", 0.9f))
                .putExtra("snr", intent.getFloatExtra("snr", 20f))
        }
        context.startService(forward)
    }
}
