package com.avshy.bayitvoice.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Plays a TTS URL and suspends until playback finishes. Publishes its loudness to [SpeechLevel]. */
class TtsPlayer {

    suspend fun play(url: String) = suspendCancellableCoroutine { cont ->
        val player = MediaPlayer()
        cont.invokeOnCancellation {
            SpeechLevel.detach()
            player.release()
        }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setOnPreparedListener {
            SpeechLevel.attach(it.audioSessionId)
            it.start()
        }
        player.setOnCompletionListener {
            SpeechLevel.detach()
            it.release()
            if (cont.isActive) cont.resume(Unit)
        }
        player.setOnErrorListener { mp, what, extra ->
            SpeechLevel.detach()
            mp.release()
            if (cont.isActive) cont.resumeWithException(IllegalStateException("MediaPlayer error $what/$extra"))
            true
        }
        try {
            player.setDataSource(url)
            player.prepareAsync()
        } catch (e: Exception) {
            player.release()
            if (cont.isActive) cont.resumeWithException(e)
        }
    }
}
