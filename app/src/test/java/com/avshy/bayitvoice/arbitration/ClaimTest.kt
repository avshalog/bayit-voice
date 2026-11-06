package com.avshy.bayitvoice.arbitration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimTest {

    private val key = Claim.groupKey("ha:http://192.168.1.10:8123")
    private val claim = Claim(deviceId = 42L, deviceName = "סלון Pixel", score = 23.5f, timestampMs = 1_700_000_000_000L)

    @Test
    fun roundTrip() {
        val bytes = claim.encode(key)
        assertEquals(claim, Claim.decode(bytes, bytes.size, key))
    }

    /** The same claim encoded by tools/arbitration_peer.js: the app and the test tool agree on the wire format. */
    @Test
    fun matchesTheNodeTestPeer() {
        val node = "42563101000000000000002a0000018bcfe5680041bc00000ed7a1d79cd795d79f20506978656c" +
            "5a6f1ffe21e291a929dee30abd853e75c39bfe3fd78b440edf12135af7ed99a1"
        val bytes = node.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(node, claim.encode(key).joinToString("") { "%02x".format(it) })
        assertEquals(claim, Claim.decode(bytes, bytes.size, key))
    }

    @Test
    fun otherGroupIsRejected() {
        val bytes = claim.encode(key)
        assertNull(Claim.decode(bytes, bytes.size, Claim.groupKey("ha:http://10.0.0.5:8123")))
    }

    @Test
    fun tamperedOrTruncatedIsRejected() {
        val bytes = claim.encode(key)
        val tampered = bytes.copyOf().also { it[14] = (it[14] + 1).toByte() }
        assertNull(Claim.decode(tampered, tampered.size, key))
        assertNull(Claim.decode(bytes, bytes.size - 1, key))
        assertNull(Claim.decode(bytes, 10, key))
    }

    @Test
    fun decodesFromALargerBuffer() {
        val bytes = claim.encode(key)
        val buffer = bytes.copyOf(Claim.MAX_PACKET_BYTES)
        assertEquals(claim, Claim.decode(buffer, bytes.size, key))
    }

    @Test
    fun longNamesAreCutToFit() {
        val long = claim.copy(deviceName = "x".repeat(200))
        val bytes = long.encode(key)
        assertTrue(bytes.size <= Claim.MAX_PACKET_BYTES)
        assertEquals("x".repeat(40), Claim.decode(bytes, bytes.size, key)?.deviceName)
    }

    @Test
    fun higherScoreWinsAndTiesGoToLowerId() {
        val near = claim.copy(deviceId = 9, score = 30f)
        val far = claim.copy(deviceId = 1, score = 12f)
        assertTrue(near.beats(far))
        assertFalse(far.beats(near))
        val tieLow = claim.copy(deviceId = 1, score = 20f)
        val tieHigh = claim.copy(deviceId = 2, score = 20f)
        assertTrue(tieLow.beats(tieHigh))
        assertFalse(tieHigh.beats(tieLow))
    }
}
