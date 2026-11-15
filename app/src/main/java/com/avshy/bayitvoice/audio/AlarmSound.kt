package com.avshy.bayitvoice.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** The timer alarm: a repeating double beep on the alarm stream, until cancelled or [maxMs] passes. */
object AlarmSound {

    private val _ringing = MutableStateFlow(false)
    private var rings = 0

    /** True while any alarm or timer rings; the music player pauses meanwhile (SPEC section 12). */
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    suspend fun play(maxMs: Long) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull() ?: return
        synchronized(this) { rings++; _ringing.value = true }
        try {
            withTimeoutOrNull(maxMs) {
                while (true) {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_MS)
                    delay(PERIOD_MS)
                }
            }
        } finally {
            synchronized(this) { rings--; _ringing.value = rings > 0 }
            tone.stopTone()
            tone.release()
        }
    }

    private const val BEEP_MS = 400
    private const val PERIOD_MS = 1_200L
}
