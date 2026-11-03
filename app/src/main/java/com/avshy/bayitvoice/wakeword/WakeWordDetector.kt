package com.avshy.bayitvoice.wakeword

import android.os.SystemClock

/**
 * Runs [engine] only while [EnergyVad] hears voice, replaying a pre-roll buffer when the gate
 * opens so the start of the wake word and the model's history are not lost. Not thread-safe:
 * feed it from one thread.
 */
class WakeWordDetector(
    private val engine: WakeWordEngine,
    @Volatile var threshold: Float,
    private val onDetected: (score: Float) -> Unit,
    /** Called when the voice gate closes, with the highest score seen while it was open (tuning aid). */
    private val onUtteranceEnd: ((peakScore: Float) -> Unit)? = null,
) {
    private var peakScore = 0f
    private val vad = EnergyVad()
    private val preRoll = Array(PRE_ROLL_FRAMES) { ShortArray(engine.frameSamples) }
    private var preRollStart = 0
    private var preRollCount = 0

    private var gateOpen = false
    private var hangover = 0
    private var lastDetectionAt = 0L

    /** Debug counters, read by the service's stats logger. */
    var framesTotal = 0L
        private set
    var framesProcessed = 0L
        private set
    val noiseFloor: Double get() = vad.noiseFloor

    fun onFrame(frame: ShortArray) {
        framesTotal++
        val voice = vad.isVoice(frame)
        if (!gateOpen) {
            if (!voice) {
                remember(frame)
                return
            }
            gateOpen = true
            peakScore = 0f
            engine.reset()
            for (i in 0 until preRollCount) score(preRoll[(preRollStart + i) % PRE_ROLL_FRAMES])
            preRollCount = 0
        }
        hangover = if (voice) HANGOVER_FRAMES else hangover - 1
        score(frame)
        if (hangover <= 0) {
            gateOpen = false
            onUtteranceEnd?.invoke(peakScore)
        }
    }

    /**
     * Forgets all audio. Call whenever capture (re)starts: after a session the engine still
     * holds the wake word that started it and would detect it again on the first new frame.
     */
    fun reset() {
        engine.reset()
        gateOpen = false
        hangover = 0
        preRollStart = 0
        preRollCount = 0
    }

    private fun score(frame: ShortArray) {
        framesProcessed++
        val score = engine.process(frame)
        if (score > peakScore) peakScore = score
        if (score < threshold) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDetectionAt < COOLDOWN_MS) return
        lastDetectionAt = now
        onDetected(score)
    }

    private fun remember(frame: ShortArray) {
        val slot = (preRollStart + preRollCount) % PRE_ROLL_FRAMES
        System.arraycopy(frame, 0, preRoll[slot], 0, frame.size)
        if (preRollCount < PRE_ROLL_FRAMES) preRollCount++ else preRollStart = (preRollStart + 1) % PRE_ROLL_FRAMES
    }

    private companion object {
        /** 2 s of 80 ms frames: enough to refill the engine's whole history. */
        const val PRE_ROLL_FRAMES = 25

        /** Keep running 1.5 s after the last voiced frame, to bridge pauses inside a phrase. */
        const val HANGOVER_FRAMES = 19

        const val COOLDOWN_MS = 2_000L
    }
}
