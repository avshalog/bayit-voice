package com.avshy.bayitvoice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Captures 16 kHz mono PCM16 LE audio in fixed-size chunks. */
class MicRecorder {

    /**
     * Records until the calling coroutine is cancelled, handing each chunk to [onChunk].
     * The buffer passed to [onChunk] is reused; copy it if it must outlive the call.
     */
    suspend fun record(onChunk: (buffer: ByteArray, length: Int) -> Unit) = withContext(Dispatchers.IO) {
        val record = open(CHUNK_BYTES * 4)
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            record.startRecording()
            while (true) {
                coroutineContext.ensureActive()
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) throw MicrophoneException("AudioRecord read failed: $read")
                if (read > 0) onChunk(buffer, read)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * Records until cancelled, handing each full frame of [frameSamples] samples to [onFrame].
     * The array is reused for every frame; no allocation happens per frame.
     */
    suspend fun recordFrames(frameSamples: Int, onFrame: (ShortArray) -> Unit) = withContext(Dispatchers.IO) {
        val record = open(frameSamples * 2 * 4)
        val frame = ShortArray(frameSamples)
        try {
            record.startRecording()
            while (true) {
                var filled = 0
                while (filled < frameSamples) {
                    coroutineContext.ensureActive()
                    val read = record.read(frame, filled, frameSamples - filled)
                    if (read < 0) throw MicrophoneException("AudioRecord read failed: $read")
                    filled += read
                }
                onFrame(frame)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    @SuppressLint("MissingPermission") // Callers check RECORD_AUDIO before recording.
    private fun open(bufferBytes: Int): AudioRecord {
        val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minSize, bufferBytes))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicrophoneException("AudioRecord failed to initialize")
        }
        return record
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 40 ms of audio: 640 samples × 2 bytes. */
        const val CHUNK_BYTES = SAMPLE_RATE / 25 * 2
    }
}

/** The microphone could not be opened or read (e.g. permission revoked, device busy). */
class MicrophoneException(message: String) : Exception(message)
