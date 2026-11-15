package com.avshy.bayitvoice.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.avshy.bayitvoice.satellite.proto.MediaState

/**
 * This device as a media player (SPEC section 12): plays the URL Home Assistant or Music Assistant
 * sends. The ExoPlayer exists only while something plays or is paused, and is released a minute
 * after playback ends, so the listening service costs nothing extra when no music plays.
 *
 * All calls may come from any thread; the work runs on the main thread, which ExoPlayer needs.
 */
class LocalMediaPlayer(
    context: Context,
    /** Called on the main thread whenever the state, volume or mute changes. */
    private val onChange: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    var state: MediaState = MediaState.IDLE
        private set
    var volume: Float = DEFAULT_VOLUME
        private set
    var muted: Boolean = false
        private set

    private val releaseWhenIdle = Runnable {
        if (state == MediaState.IDLE) release()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "ExoPlayer state $playbackState")
            if (playbackState == Player.STATE_ENDED) setState(MediaState.IDLE)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            // Android may pause us itself (a call, another app): follow it.
            if (isPlaying) setState(MediaState.PLAYING)
            else if (!p.playWhenReady && state == MediaState.PLAYING) setState(MediaState.PAUSED)
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            Log.d(TAG, "Playback suppressed: reason $playbackSuppressionReason")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback failed: ${error.errorCodeName} ${error.message}")
            setState(MediaState.IDLE)
        }
    }

    /** Plays [url] now, replacing whatever played before. */
    fun play(url: String) = main.post {
        main.removeCallbacks(releaseWhenIdle)
        val p = player ?: create()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        applyVolume()
        Log.i(TAG, "Playing a ${url.substringBefore('?').substringAfterLast('.', "stream")} URL")
        if (com.avshy.bayitvoice.BuildConfig.DEBUG) Log.d(TAG, "URL path: ${url.substringBefore('?').substringAfter("://").substringAfter('/')}")
    }

    /** Resumes after a pause. */
    fun resume() = main.post {
        val p = player ?: return@post
        if (p.playbackState != Player.STATE_IDLE) p.playWhenReady = true
    }

    fun pause() = main.post {
        val p = player ?: return@post
        p.playWhenReady = false
        if (state == MediaState.PLAYING) setState(MediaState.PAUSED)
    }

    fun stop() = main.post {
        player?.stop()
        player?.clearMediaItems()
        setState(MediaState.IDLE)
    }

    fun setVolume(value: Float) = main.post {
        volume = value.coerceIn(0f, 1f)
        applyVolume()
        onChange()
    }

    fun setMuted(value: Boolean) = main.post {
        muted = value
        applyVolume()
        onChange()
    }

    /** Frees the player now (the service is stopping, or the feature was switched off). */
    fun shutdown() {
        main.post { release() }
    }

    private fun create(): ExoPlayer {
        val created = ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.addListener(listener)
        player = created
        return created
    }

    private fun applyVolume() {
        player?.volume = if (muted) 0f else volume
    }

    private fun setState(new: MediaState) {
        if (state != new) {
            state = new
            if (new == MediaState.IDLE) main.postDelayed(releaseWhenIdle, IDLE_RELEASE_MS)
            onChange()
        }
    }

    private fun release() {
        main.removeCallbacks(releaseWhenIdle)
        player?.removeListener(listener)
        player?.release()
        player = null
        if (state != MediaState.IDLE) {
            state = MediaState.IDLE
            onChange()
        }
    }

    private companion object {
        const val TAG = "LocalMediaPlayer"
        const val DEFAULT_VOLUME = 0.5f
        const val IDLE_RELEASE_MS = 60_000L
    }
}
