package com.avshy.bayitvoice.satellite.noise

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

/**
 * Checks the responder against an initiator written separately here, straight from the Noise
 * spec, so a mistake would have to be made twice in the same way to pass.
 */
class NoiseResponderTest {

    private val prologue = "NoiseAPIInit".toByteArray() + byteArrayOf(0, 0)
    private val psk = ByteArray(32) { it.toByte() }

    @Test
    fun handshakeAndDataPhase() {
        val initiator = Initiator(psk, prologue)
        val responder = NoiseResponder(psk, prologue)

        responder.readInitiation(initiator.writeInitiation())
        initiator.readResponse(responder.writeResponse())

        val hello = "hello from HA".toByteArray()
        assertArrayEquals(hello, responder.receive.decrypt(ByteArray(0), initiator.encrypt(hello)))
        val reply = "hello from the device".toByteArray()
        assertArrayEquals(reply, initiator.decrypt(responder.send.encrypt(ByteArray(0), reply)))
        // Nonces advance on both sides.
        assertArrayEquals(hello, responder.receive.decrypt(ByteArray(0), initiator.encrypt(hello)))
    }

    @Test
    fun wrongKeyIsRejected() {
        val initiator = Initiator(ByteArray(32) { 7 }, prologue)
        val responder = NoiseResponder(psk, prologue)
        assertThrows(NoiseException::class.java) { responder.readInitiation(initiator.writeInitiation()) }
    }

    /** NNpsk0 initiator: `-> psk, e` then `<- e, ee`. */
    private class Initiator(private val psk: ByteArray, prologue: ByteArray) {
        private var h = sha256("Noise_NNpsk0_25519_ChaChaPoly_SHA256".toByteArray())
        private var ck = h.copyOf()
        private var k: ByteArray? = null
        private var n = 0L
        private val e = X25519PrivateKeyParameters(SecureRandom())
        private lateinit var sendKey: ByteArray
        private lateinit var recvKey: ByteArray
        private var sendN = 0L
        private var recvN = 0L

        init {
            mixHash(prologue)
        }

        fun writeInitiation(): ByteArray {
            val (c, th, tk) = hkdf(ck, psk, 3)
            ck = c; mixHash(th); k = tk; n = 0
            val pub = e.generatePublicKey().encoded
            mixHash(pub)
            mixKey(pub)
            val tag = aead(true, k!!, n++, h, ByteArray(0))
            mixHash(tag)
            return pub + tag
        }

        fun readResponse(message: ByteArray) {
            val re = message.copyOfRange(0, 32)
            mixHash(re)
            mixKey(re)
            val agreement = X25519Agreement().apply { init(e) }
            val shared = ByteArray(32)
            agreement.calculateAgreement(X25519PublicKeyParameters(re, 0), shared, 0)
            mixKey(shared)
            val tag = message.copyOfRange(32, message.size)
            aead(false, k!!, n++, h, tag)
            mixHash(tag)
            val (k1, k2) = hkdf(ck, ByteArray(0), 2)
            sendKey = k1
            recvKey = k2
        }

        fun encrypt(p: ByteArray) = aead(true, sendKey, sendN++, ByteArray(0), p)
        fun decrypt(c: ByteArray) = aead(false, recvKey, recvN++, ByteArray(0), c)

        private fun mixHash(d: ByteArray) { h = sha256(h + d) }
        private fun mixKey(ikm: ByteArray) {
            val (c, tk) = hkdf(ck, ikm, 2)
            ck = c; k = tk; n = 0
        }

        private fun hkdf(key: ByteArray, ikm: ByteArray, count: Int): List<ByteArray> {
            val prk = hmac(key, ikm)
            val out = ArrayList<ByteArray>()
            var prev = ByteArray(0)
            for (i in 1..count) {
                prev = hmac(prk, prev + byteArrayOf(i.toByte()))
                out += prev
            }
            return out
        }

        private fun aead(enc: Boolean, key: ByteArray, nonce: Long, ad: ByteArray, input: ByteArray): ByteArray {
            val iv = ByteArray(12)
            for (i in 0 until 8) iv[4 + i] = (nonce ushr (8 * i)).toByte()
            val c = ChaCha20Poly1305()
            c.init(enc, AEADParameters(KeyParameter(key), 128, iv, ad))
            val out = ByteArray(c.getOutputSize(input.size))
            val len = c.processBytes(input, 0, input.size, out, 0)
            c.doFinal(out, len)
            return out
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val m = HMac(SHA256Digest()).apply { init(KeyParameter(key)) }
            m.update(data, 0, data.size)
            return ByteArray(32).also { m.doFinal(it, 0) }
        }

        private fun sha256(d: ByteArray): ByteArray {
            val s = SHA256Digest()
            s.update(d, 0, d.size)
            return ByteArray(32).also { s.doFinal(it, 0) }
        }
    }
}
