package com.avshy.bayitvoice.arbitration

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A phone that answered a wake word over a satellite tells that satellite to run the command
 * instead (SPEC section 6.2). Signed like a [Claim], with the same group key.
 */
data class HandOff(val fromId: Long, val toId: Long, val timestampMs: Long) {

    fun encode(groupKey: ByteArray): ByteArray {
        val body = ByteBuffer.allocate(BODY_BYTES).putInt(MAGIC).putLong(fromId).putLong(toId).putLong(timestampMs).array()
        return body + hmac(groupKey, body)
    }

    companion object {
        private const val MAGIC = 0x42564801 // "BV", "H", version 1
        private const val BODY_BYTES = 4 + 8 + 8 + 8
        private const val MAC_BYTES = 32
        const val PACKET_BYTES = BODY_BYTES + MAC_BYTES

        /** Returns null for anything that is not a hand-over signed with [groupKey]. */
        fun decode(data: ByteArray, length: Int, groupKey: ByteArray): HandOff? {
            if (length != PACKET_BYTES) return null
            val body = data.copyOfRange(0, BODY_BYTES)
            if (!MessageDigest.isEqual(hmac(groupKey, body), data.copyOfRange(BODY_BYTES, PACKET_BYTES))) return null
            val buffer = ByteBuffer.wrap(body)
            if (buffer.int != MAGIC) return null
            return HandOff(buffer.long, buffer.long, buffer.long)
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }
    }
}
