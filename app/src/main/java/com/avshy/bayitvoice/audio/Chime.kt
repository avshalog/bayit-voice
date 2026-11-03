package com.avshy.bayitvoice.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay

/** Short "listening" beep. */
object Chime {

    private const val DURATION_MS = 150

    suspend fun play() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() ?: return
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS)
            // Let the beep finish before the mic opens, so it is not streamed to STT.
            delay(DURATION_MS + 100L)
        } finally {
            tone.release()
        }
    }
}
