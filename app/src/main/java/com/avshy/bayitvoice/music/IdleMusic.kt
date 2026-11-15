package com.avshy.bayitvoice.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.ha.HaClient
import com.avshy.bayitvoice.satellite.HaLink.Key
import com.avshy.bayitvoice.satellite.SatelliteDevice
import com.avshy.bayitvoice.satellite.proto.MediaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.time.OffsetDateTime

/** What the room's player is playing. Fields are null when HA has not sent them. */
data class NowPlaying(
    val playing: Boolean,
    val title: String?,
    val artist: String?,
    /** Track length in seconds; null for a live stream. */
    val durationSec: Double?,
    /** Position in seconds at [positionAtMs] (wall clock); advance it while [playing]. */
    val positionSec: Double?,
    val positionAtMs: Long?,
    /** 0..1 */
    val volume: Double?,
    /** Absolute artwork URL, or null when there is none or HA's address is unknown. */
    val artUrl: String?,
) {
    fun positionAt(nowMs: Long): Double? {
        val pos = positionSec ?: return null
        val at = positionAtMs ?: return pos
        val live = if (playing) pos + (nowMs - at) / 1000.0 else pos
        return durationSec?.let { live.coerceIn(0.0, it) } ?: live
    }
}

/**
 * The room's media player for the idle screen (SPEC section 11), over the satellite's ESPHome link:
 * its state and attributes, and play/pause, previous, next and volume as HA actions. Holds no UI.
 */
class IdleMusic(context: Context) {

    private val settings = AppSettings.get(context)
    private val device = SatelliteDevice.get(context)
    private val ha = device.ha

    /** True when the chosen player is this device's own: play/pause and volume then act on it directly. */
    private val local: Boolean get() = settings.localPlayerEnabled && settings.idleMediaPlayerLocal

    val entityId: String? get() = settings.idleMediaPlayer

    /** Non-null while the player plays or is paused; null when it stops. */
    val now: Flow<NowPlaying?> = combine(ha.states, device.localMedia) { states, own ->
        val entity = entityId
        fun value(attribute: String = "") =
            entity?.let { states[Key(it, attribute)] }?.takeIf { it.isNotEmpty() && it != "None" }
        // Our own player knows its state and volume first-hand; HA only adds the title and artwork.
        val localState = own.state.takeIf { local && it != MediaState.IDLE && it != MediaState.NONE }
        val state = if (local) {
            if (localState == null) return@combine null
            if (localState == MediaState.PAUSED) "paused" else "playing"
        } else {
            if (entity == null) return@combine null
            value() ?: return@combine null
        }
        if (state != "playing" && state != "paused") return@combine null
        NowPlaying(
            playing = state == "playing",
            title = value("media_title"),
            artist = value("media_artist") ?: value("media_album_name"),
            durationSec = value("media_duration")?.toDoubleOrNull()?.takeIf { it > 0 },
            positionSec = value("media_position")?.toDoubleOrNull(),
            positionAtMs = value("media_position_updated_at")?.let { at ->
                // HA sends Python's str(datetime): "2026-11-09 18:30:00.123456+00:00".
                runCatching { OffsetDateTime.parse(at.replace(' ', 'T')).toInstant().toEpochMilli() }.getOrNull()
            },
            volume = if (local) (if (own.muted) 0.0 else own.volume.toDouble()) else value("volume_level")?.toDoubleOrNull(),
            artUrl = value("entity_picture")?.let(::absolute),
        )
    }

    /** Asks HA for the player's state and attributes. Call while the idle screen is showing. */
    fun subscribe() {
        val entity = entityId ?: return ha.want(OWNER, emptySet())
        ha.want(OWNER, ATTRIBUTES.map { Key(entity, it) }.toSet())
    }

    fun playPause() = if (local) device.togglePlayLocal() else command("media_player.media_play_pause")

    fun next() = command("media_player.media_next_track")

    fun previous() = command("media_player.media_previous_track")

    fun setVolume(level: Double) {
        if (local) return device.setLocalVolume(level.toFloat())
        val entity = entityId ?: return
        ha.fire("media_player.volume_set", mapOf("entity_id" to entity, "volume_level" to "%.2f".format(java.util.Locale.ROOT, level.coerceIn(0.0, 1.0))))
    }

    private fun command(service: String) {
        val entity = entityId ?: return
        if (!ha.fire(service, mapOf("entity_id" to entity))) Log.w(TAG, "$service not sent: HA is not connected")
    }

    /** `entity_picture` is usually a path on HA (`/api/media_player_proxy/...?token=...`). */
    private fun absolute(url: String): String? = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> (settings.homeServer ?: settings.externalServer)?.resolve(url)
    }

    /** Downloads and decodes the artwork, scaled down to about [sizePx]. Null on any failure. */
    suspend fun loadArt(url: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            HaClient.sharedHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body?.bytes() ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.onFailure { Log.w(TAG, "Artwork not loaded: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private companion object {
        const val TAG = "IdleMusic"
        const val OWNER = "music"
        val ATTRIBUTES = listOf(
            "", "media_title", "media_artist", "media_album_name", "media_duration",
            "media_position", "media_position_updated_at", "volume_level", "entity_picture",
        )
    }
}
