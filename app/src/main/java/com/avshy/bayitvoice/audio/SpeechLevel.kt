package com.avshy.bayitvoice.audio

import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * The loudness of the reply being played, 0..1, for the session UI's waveform.
 * Read from the player's audio session with [Visualizer]; when the device refuses
 * (some vendors do), [active] stays false and the UI draws a generic speech pattern.
 */
object SpeechLevel {

    private const val TAG = "SpeechLevel"

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var visualizer: Visualizer? = null

    fun attach(audioSessionId: Int) {
        detach()
        visualizer = try {
            Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(256)
                setDataCaptureListener(listener, Visualizer.getMaxCaptureRate(), true, false)
                enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "No audio level for the reply: ${e.javaClass.simpleName}")
            null
        }
        _active.value = visualizer != null
    }

    fun detach() {
        visualizer?.run {
            enabled = false
            release()
        }
        visualizer = null
        _active.value = false
        _level.value = 0f
    }

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) {
            // 8-bit unsigned samples centred on 128.
            var sum = 0.0
            for (b in waveform) {
                val s = ((b.toInt() and 0xFF) - 128) / 128.0
                sum += s * s
            }
            val rms = sqrt(sum / waveform.size)
            _level.value = (rms * LEVEL_GAIN).toFloat().coerceIn(0f, 1f)
        }

        override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) = Unit
    }

    /** Maps speech RMS onto the bar height; tuned on a phone so only stressed syllables reach the top. */
    private const val LEVEL_GAIN = 2.0
}
