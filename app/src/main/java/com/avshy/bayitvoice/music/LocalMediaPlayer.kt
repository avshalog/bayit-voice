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
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.sign

/** Why the music is lowered; the lowest level among the active sources wins (SPEC section 12). */
enum class DuckSource { SESSION, ALARM, ANNOUNCEMENT }

/**
 * This device as a media player (SPEC section 12): plays the URL Home Assistant or Music Assistant
 * sends. The ExoPlayer exists only while something plays or is paused, and is released a minute
 * after playback ends, so the listening service costs nothing extra when no music plays.
 *
 * Ducking: during a conversation the music drops to [SESSION_LEVEL]; while an alarm rings it
 * pauses and resumes afterwards. An announcement sent as an announcement plays over the music on
 * its own player while the music is lowered.
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
    private var announcer: ExoPlayer? = null

    private var musicState: MediaState = MediaState.IDLE
    private var announcing = false

    /** What HA is told: announcing while an announcement plays, otherwise the music's state. */
    val state: MediaState get() = if (announcing) MediaState.ANNOUNCING else musicState

    var volume: Float = DEFAULT_VOLUME
        private set
    var muted: Boolean = false
        private set

    private val duckLevels = EnumMap<DuckSource, Float>(DuckSource::class.java)
    private var duckGain = 1f
    private var duckTarget = 1f
    private var pausedByDuck = false

    private val releaseWhenIdle = Runnable {
        if (musicState == MediaState.IDLE && !announcing) release()
    }

    private val fade = object : Runnable {
        override fun run() {
            val diff = duckTarget - duckGain
            if (abs(diff) <= FADE_STEP) {
                duckGain = duckTarget
            } else {
                duckGain += sign(diff) * FADE_STEP
                main.postDelayed(this, FADE_TICK_MS)
            }
            applyVolume()
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) setMusicState(MediaState.IDLE)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            // Android may pause us itself (a call, another app): follow it.
            if (isPlaying) setMusicState(MediaState.PLAYING)
            else if (!p.playWhenReady && musicState == MediaState.PLAYING) setMusicState(MediaState.PAUSED)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback failed: ${error.errorCodeName} ${error.message}")
            setMusicState(MediaState.IDLE)
        }
    }

    /** Plays [url] now, replacing whatever played before. */
    fun play(url: String) = main.post {
        main.removeCallbacks(releaseWhenIdle)
        val p = player ?: create()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        // While an alarm rings the music waits, and starts when the alarm stops.
        pausedByDuck = duckTarget <= 0f
        p.playWhenReady = !pausedByDuck
        applyVolume()
        Log.i(TAG, "Playing a ${url.substringBefore('?').substringAfterLast('.', "stream")} URL")
    }

    /** Plays [url] over the music (an announcement from HA), lowering the music meanwhile. */
    fun announce(url: String) = main.post {
        main.removeCallbacks(releaseWhenIdle)
        announcer?.release()
        val a = ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_ASSISTANT)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            .build()
        a.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) endAnnouncement(a)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Announcement failed: ${error.errorCodeName}")
                endAnnouncement(a)
            }
        })
        announcer = a
        announcing = true
        setDuckInternal(DuckSource.ANNOUNCEMENT, ANNOUNCE_LEVEL)
        a.volume = if (muted) 0f else volume
        a.setMediaItem(MediaItem.fromUri(url))
        a.prepare()
        a.playWhenReady = true
        onChange()
        Log.i(TAG, "Announcing over the music")
    }

    /** Lowers the music for [source] to [level] (1 = back to normal, 0 = paused). */
    fun setDuck(source: DuckSource, level: Float) = main.post { setDuckInternal(source, level) }

    /** Resumes after a pause. */
    fun resume() = main.post {
        pausedByDuck = false
        val p = player ?: return@post
        if (p.playbackState != Player.STATE_IDLE && duckTarget > 0f) p.playWhenReady = true
    }

    fun pause() = main.post {
        pausedByDuck = false
        val p = player ?: return@post
        p.playWhenReady = false
        if (musicState == MediaState.PLAYING) setMusicState(MediaState.PAUSED)
    }

    fun stop() = main.post {
        pausedByDuck = false
        announcer?.let { endAnnouncement(it) }
        player?.stop()
        player?.clearMediaItems()
        setMusicState(MediaState.IDLE)
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

    /** Frees the players now (the service is stopping, or the feature was switched off). */
    fun shutdown() {
        main.post { release() }
    }

    private fun setDuckInternal(source: DuckSource, level: Float) {
        if (level >= 1f) duckLevels.remove(source) else duckLevels[source] = level
        duckTarget = duckLevels.values.minOrNull() ?: 1f
        val p = player
        if (p != null) {
            if (duckTarget <= 0f) {
                if (musicState == MediaState.PLAYING) {
                    pausedByDuck = true
                    p.playWhenReady = false
                }
            } else if (pausedByDuck) {
                pausedByDuck = false
                p.playWhenReady = true
            }
        }
        Log.d(TAG, "Duck $source -> $level (music at ${(duckTarget * 100).toInt()}%)")
        main.removeCallbacks(fade)
        fade.run()
    }

    private fun endAnnouncement(which: ExoPlayer) {
        if (announcer !== which) return
        which.release()
        announcer = null
        announcing = false
        setDuckInternal(DuckSource.ANNOUNCEMENT, 1f)
        if (musicState == MediaState.IDLE) main.postDelayed(releaseWhenIdle, IDLE_RELEASE_MS)
        onChange()
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
        player?.volume = if (muted) 0f else volume * duckGain
        announcer?.volume = if (muted) 0f else volume
    }

    private fun setMusicState(new: MediaState) {
        if (musicState != new) {
            musicState = new
            if (new == MediaState.IDLE && !announcing) main.postDelayed(releaseWhenIdle, IDLE_RELEASE_MS)
            onChange()
        }
    }

    private fun release() {
        main.removeCallbacks(releaseWhenIdle)
        main.removeCallbacks(fade)
        announcer?.release()
        announcer = null
        announcing = false
        player?.removeListener(listener)
        player?.release()
        player = null
        pausedByDuck = false
        if (musicState != MediaState.IDLE) {
            musicState = MediaState.IDLE
            onChange()
        }
    }

    companion object {
        private const val TAG = "LocalMediaPlayer"
        private const val DEFAULT_VOLUME = 0.5f
        private const val IDLE_RELEASE_MS = 60_000L

        /** The music's level during a conversation. */
        const val SESSION_LEVEL = 0.2f
        private const val ANNOUNCE_LEVEL = 0.2f
        private const val FADE_STEP = 0.1f
        private const val FADE_TICK_MS = 25L
    }
}
