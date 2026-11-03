package com.avshy.bayitvoice.wakeword

import java.io.Closeable

/** Wake-word engines the app can run. Every model file is tagged with one of these. */
enum class WakeEngineType { OpenWakeWord }

/**
 * A streaming wake-word detector for one model. Fed fixed-size 16 kHz PCM16 frames;
 * implementations must not allocate per frame beyond what their runtime requires.
 */
interface WakeWordEngine : Closeable {

    /** Samples per frame passed to [process]. */
    val frameSamples: Int

    /** Processes one frame and returns the model's score in 0..1. */
    fun process(frame: ShortArray): Float

    /** Forgets all audio history, e.g. after a gap in the input. */
    fun reset()
}
