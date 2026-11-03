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
    @SuppressLint("MissingPermission") // Callers check RECORD_AUDIO before starting a session.
    suspend fun record(onChunk: (buffer: ByteArray, length: Int) -> Unit) = withContext(Dispatchers.IO) {
        val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minSize, CHUNK_BYTES * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            record.startRecording()
            while (true) {
                coroutineContext.ensureActive()
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) error("AudioRecord read failed: $read")
                if (read > 0) onChunk(buffer, read)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 40 ms of audio: 640 samples × 2 bytes. */
        const val CHUNK_BYTES = SAMPLE_RATE / 25 * 2
    }
}
