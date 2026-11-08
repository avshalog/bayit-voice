package com.avshy.bayitvoice.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The timer alarm: a repeating double beep on the alarm stream, until cancelled or [maxMs] passes. */
object AlarmSound {

    suspend fun play(maxMs: Long) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull() ?: return
        try {
            withTimeoutOrNull(maxMs) {
                while (true) {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_MS)
                    delay(PERIOD_MS)
                }
            }
        } finally {
            tone.stopTone()
            tone.release()
        }
    }

    private const val BEEP_MS = 400
    private const val PERIOD_MS = 1_200L
}
