package com.avshy.bayitvoice.wakeword

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * openWakeWord streaming pipeline: raw audio → `melspectrogram.tflite` → `embedding_model.tflite`
 * → wake-word model. Each 80 ms frame adds 8 mel frames and one 96-d embedding; the wake-word
 * model scores the latest N embeddings. All buffers are allocated once.
 */
class OpenWakeWordEngine(
    melModel: ByteBuffer,
    embeddingModel: ByteBuffer,
    wakeModel: ByteBuffer,
) : WakeWordEngine {

    override val frameSamples: Int = FRAME_SAMPLES

    private val options = Interpreter.Options().setNumThreads(1).setUseXNNPACK(true)
    private val mel = Interpreter(melModel, options)
    private val embedding = Interpreter(embeddingModel, options)
    private val wake = Interpreter(wakeModel, options)

    /** Audio for the mel model: the new frame plus 3 hops of context from the previous one. */
    private val audioWindow = ShortArray(MEL_INPUT_SAMPLES)
    private val melIn = TensorBuffer(MEL_INPUT_SAMPLES)
    private val melFramesPerChunk: Int
    private val melOut: TensorBuffer

    private val melRing = FloatArray(EMBEDDING_WINDOW * MEL_BINS)
    private val embeddingIn = TensorBuffer(EMBEDDING_WINDOW * MEL_BINS)
    private val embeddingOut = TensorBuffer(EMBEDDING_SIZE)

    private val featureFrames: Int
    private val featureRing: FloatArray
    private val wakeIn: TensorBuffer
    private val wakeOut = TensorBuffer(1)

    private var framesSinceReset = 0

    init {
        mel.resizeInput(0, intArrayOf(1, MEL_INPUT_SAMPLES))
        mel.allocateTensors()
        melFramesPerChunk = mel.getOutputTensor(0).shape().let { it[it.size - 2] }
        melOut = TensorBuffer(melFramesPerChunk * MEL_BINS)

        val wakeShape = wake.getInputTensor(0).shape()
        require(wakeShape.size == 3 && wakeShape[2] == EMBEDDING_SIZE) {
            "Not an openWakeWord model: input shape ${wakeShape.contentToString()}"
        }
        featureFrames = wakeShape[1]
        featureRing = FloatArray(featureFrames * EMBEDDING_SIZE)
        wakeIn = TensorBuffer(featureFrames * EMBEDDING_SIZE)
        Log.i(TAG, "Loaded: $melFramesPerChunk mel frames/chunk, $featureFrames feature frames")
        reset()
    }

    override fun process(frame: ShortArray): Float {
        require(frame.size == FRAME_SAMPLES)
        // Slide the audio window and run the mel model.
        System.arraycopy(audioWindow, FRAME_SAMPLES, audioWindow, 0, MEL_CONTEXT_SAMPLES)
        System.arraycopy(frame, 0, audioWindow, MEL_CONTEXT_SAMPLES, FRAME_SAMPLES)
        melIn.floats.clear()
        for (sample in audioWindow) melIn.floats.put(sample.toFloat())
        melOut.floats.clear()
        mel.run(melIn.rewound(), melOut.rewound())

        // Append the new mel frames (with openWakeWord's x/10 + 2 transform).
        val newValues = melFramesPerChunk * MEL_BINS
        System.arraycopy(melRing, newValues, melRing, 0, melRing.size - newValues)
        melOut.floats.rewind()
        for (i in melRing.size - newValues until melRing.size) melRing[i] = melOut.floats.get() / 10f + 2f

        // One embedding from the latest 76 mel frames.
        embeddingIn.floats.clear()
        embeddingIn.floats.put(melRing)
        embeddingOut.floats.clear()
        embedding.run(embeddingIn.rewound(), embeddingOut.rewound())
        System.arraycopy(featureRing, EMBEDDING_SIZE, featureRing, 0, featureRing.size - EMBEDDING_SIZE)
        embeddingOut.floats.rewind()
        embeddingOut.floats.get(featureRing, featureRing.size - EMBEDDING_SIZE, EMBEDDING_SIZE)

        // Score the latest N embeddings.
        wakeIn.floats.clear()
        wakeIn.floats.put(featureRing)
        wakeOut.floats.clear()
        wake.run(wakeIn.rewound(), wakeOut.rewound())
        framesSinceReset++
        // Until the feature window holds only real audio, scores are not meaningful.
        return if (framesSinceReset < featureFrames) 0f else wakeOut.floats.get(0)
    }

    override fun reset() {
        audioWindow.fill(0)
        melRing.fill(1f) // openWakeWord initializes its mel buffer with ones
        featureRing.fill(0f)
        framesSinceReset = 0
    }

    override fun close() {
        mel.close()
        embedding.close()
        wake.close()
    }

    /** A direct buffer handed to the interpreter, with a float view for reading and writing. */
    private class TensorBuffer(size: Int) {
        private val bytes: ByteBuffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
        val floats: FloatBuffer = bytes.asFloatBuffer()

        fun rewound(): ByteBuffer = bytes.also { it.rewind() }
    }

    companion object {
        private const val TAG = "OpenWakeWord"

        /** 80 ms at 16 kHz. */
        const val FRAME_SAMPLES = 1280
        private const val MEL_HOP = 160
        private const val MEL_CONTEXT_SAMPLES = MEL_HOP * 3
        private const val MEL_INPUT_SAMPLES = FRAME_SAMPLES + MEL_CONTEXT_SAMPLES
        private const val MEL_BINS = 32
        private const val EMBEDDING_WINDOW = 76
        private const val EMBEDDING_SIZE = 96
    }
}
