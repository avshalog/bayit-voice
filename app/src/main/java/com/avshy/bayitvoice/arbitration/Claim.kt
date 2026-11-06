package com.avshy.bayitvoice.arbitration

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One device's claim to handle a wake word: who it is and how close it thinks it is.
 * On the wire it is signed with an HMAC keyed by the group, so devices of another
 * Home Assistant instance on the same network ignore it (SPEC section 6.2).
 */
data class Claim(val deviceId: Long, val deviceName: String, val score: Float, val timestampMs: Long) {

    /** Higher score wins; ties go to the lower device id, so every device reaches the same answer. */
    fun beats(other: Claim): Boolean =
        score > other.score || (score == other.score && deviceId < other.deviceId)

    fun encode(groupKey: ByteArray): ByteArray {
        val name = deviceName.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_NAME_BYTES) it.copyOf(MAX_NAME_BYTES) else it }
        val body = ByteBuffer.allocate(HEADER_BYTES + name.size)
            .putInt(MAGIC)
            .putLong(deviceId)
            .putLong(timestampMs)
            .putFloat(score)
            .put(name.size.toByte())
            .put(name)
            .array()
        return body + hmac(groupKey, body, body.size)
    }

    companion object {
        private const val MAGIC = 0x42563101 // "BV", 0x31, version 1
        private const val HEADER_BYTES = 4 + 8 + 8 + 4 + 1
        private const val MAX_NAME_BYTES = 40
        private const val MAC_BYTES = 32
        const val MAX_PACKET_BYTES = HEADER_BYTES + MAX_NAME_BYTES + MAC_BYTES

        /** Returns null for anything that is not a valid claim signed with [groupKey]. */
        fun decode(data: ByteArray, length: Int, groupKey: ByteArray): Claim? {
            if (length < HEADER_BYTES + MAC_BYTES) return null
            val bodyLength = length - MAC_BYTES
            val expected = hmac(groupKey, data, bodyLength)
            if (!MessageDigest.isEqual(expected, data.copyOfRange(bodyLength, length))) return null
            val buffer = ByteBuffer.wrap(data, 0, bodyLength)
            if (buffer.int != MAGIC) return null
            val deviceId = buffer.long
            val timestamp = buffer.long
            val score = buffer.float
            val nameLength = buffer.get().toInt() and 0xFF
            if (nameLength != bodyLength - HEADER_BYTES) return null
            val name = String(data, HEADER_BYTES, nameLength, Charsets.UTF_8)
            return Claim(deviceId, name, score, timestamp)
        }

        /** The HMAC key for a group, from its id (normally the HA address). */
        fun groupKey(groupId: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("bayitvoice-arbitration|$groupId".toByteArray(Charsets.UTF_8))

        private fun hmac(key: ByteArray, data: ByteArray, length: Int): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                update(data, 0, length)
                doFinal()
            }
    }
}
