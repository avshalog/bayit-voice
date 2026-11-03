package com.avshy.bayitvoice.wakeword

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cheap voice-activity check: a frame is "voice" when its RMS rises clearly above an
 * adaptive noise floor. It only decides whether the heavy models need to run.
 */
class EnergyVad {

    var noiseFloor: Double = INITIAL_FLOOR
        private set

    var lastRms: Double = 0.0
        private set

    fun isVoice(frame: ShortArray): Boolean {
        var sum = 0.0
        for (sample in frame) sum += sample.toDouble() * sample
        val rms = sqrt(sum / frame.size)
        lastRms = rms
        val voice = rms > max(noiseFloor * VOICE_RATIO, MIN_VOICE_RMS)
        // Track the floor quickly in silence and very slowly during voice (e.g. a TV left on).
        val rate = if (voice) FLOOR_RATE_VOICE else FLOOR_RATE_SILENCE
        noiseFloor = (noiseFloor + (rms - noiseFloor) * rate).coerceIn(MIN_FLOOR, MAX_FLOOR)
        return voice
    }

    private companion object {
        const val INITIAL_FLOOR = 100.0
        const val MIN_FLOOR = 10.0
        const val MAX_FLOOR = 3000.0
        const val VOICE_RATIO = 3.0
        const val MIN_VOICE_RMS = 120.0
        const val FLOOR_RATE_SILENCE = 0.05
        const val FLOOR_RATE_VOICE = 0.002
    }
}
