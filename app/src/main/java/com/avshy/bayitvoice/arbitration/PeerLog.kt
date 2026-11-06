package com.avshy.bayitvoice.arbitration

import android.content.Context
import androidx.core.content.edit

/** A device that answered an arbitration recently, for the troubleshooting list in settings. */
data class SeenPeer(val deviceId: Long, val name: String, val lastSeenMs: Long)

/** Remembers which peers took part in arbitrations, so settings can show them. */
class PeerLog(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("arbitration_peers", Context.MODE_PRIVATE)

    fun record(claims: Collection<Claim>) {
        if (claims.isEmpty()) return
        val now = System.currentTimeMillis()
        val peers = all().associateBy { it.deviceId }.toMutableMap()
        claims.forEach { peers[it.deviceId] = SeenPeer(it.deviceId, it.deviceName, now) }
        val kept = peers.values.sortedByDescending { it.lastSeenMs }.take(MAX_PEERS)
        prefs.edit {
            putStringSet(KEY, kept.map { "${it.deviceId}|${it.lastSeenMs}|${it.name}" }.toSet())
            putBoolean(KEY_EVER, true)
        }
    }

    /** Most recent first. */
    fun all(): List<SeenPeer> = prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { line ->
        val parts = line.split('|', limit = 3)
        if (parts.size != 3) return@mapNotNull null
        val id = parts[0].toLongOrNull() ?: return@mapNotNull null
        val seen = parts[1].toLongOrNull() ?: return@mapNotNull null
        SeenPeer(id, parts[2], seen)
    }.sortedByDescending { it.lastSeenMs }

    val everSawPeer: Boolean get() = prefs.getBoolean(KEY_EVER, false)

    private companion object {
        const val KEY = "peers"
        const val KEY_EVER = "ever"
        const val MAX_PEERS = 10
    }
}
