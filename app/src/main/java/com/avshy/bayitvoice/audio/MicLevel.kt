package com.avshy.bayitvoice.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The loudness of the microphone during a conversation turn, 0..1, so the session UI can
 * follow the user's voice. Written by the session's recorder only (never by the wake-word
 * service); read by the UI on each frame.
 */
object MicLevel {

    @Volatile var level = 0f
        private set

    /** Measures one chunk of 16-bit little-endian PCM. */
    fun update(buffer: ByteArray, length: Int) {
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val s = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        val rms = sqrt(sum / (length / 2).coerceAtLeast(1))
        // About 30 dB of range above a quiet room maps onto 0..1.
        val db = 20 * log10(rms.coerceAtLeast(1.0))
        level = ((db - FLOOR_DB) / RANGE_DB).toFloat().coerceIn(0f, 1f)
    }

    fun reset() {
        level = 0f
    }

    private const val FLOOR_DB = 40.0
    private const val RANGE_DB = 35.0
}
