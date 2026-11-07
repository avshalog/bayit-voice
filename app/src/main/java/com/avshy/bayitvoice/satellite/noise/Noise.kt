package com.avshy.bayitvoice.satellite.noise

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * The responder side of `Noise_NNpsk0_25519_ChaChaPoly_SHA256`, as used by the ESPHome native
 * API (see the Noise Protocol Framework, revision 34). Pattern:
 *
 *     -> psk, e
 *     <- e, ee
 *
 * Both handshake payloads are empty. After [writeResponse] the [send] and [receive] ciphers
 * carry the data phase.
 */
class NoiseResponder(psk: ByteArray, prologue: ByteArray, private val random: SecureRandom = SecureRandom()) {
    private val symmetric = SymmetricState(PROTOCOL_NAME)
    private val psk = psk.copyOf()
    private var remoteEphemeral: ByteArray? = null

    lateinit var send: CipherState
        private set
    lateinit var receive: CipherState
        private set

    init {
        require(psk.size == 32) { "The Noise PSK must be 32 bytes" }
        symmetric.mixHash(prologue)
    }

    /** Reads the initiator's first message; throws [NoiseException] if the PSK does not match. */
    fun readInitiation(message: ByteArray) {
        if (message.size < KEY_SIZE + TAG_SIZE) throw NoiseException("Handshake message too short")
        symmetric.mixKeyAndHash(psk)
        val re = message.copyOfRange(0, KEY_SIZE)
        symmetric.mixHash(re)
        symmetric.mixKey(re) // psk modifier: e also mixes into the key
        symmetric.decryptAndHash(message.copyOfRange(KEY_SIZE, message.size))
        remoteEphemeral = re
    }

    /** Writes the response and splits the session keys. */
    fun writeResponse(): ByteArray {
        val re = remoteEphemeral ?: throw NoiseException("No initiation read")
        val generator = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(random)) }
        val pair = generator.generateKeyPair()
        val ePriv = pair.private as X25519PrivateKeyParameters
        val ePub = (pair.public as X25519PublicKeyParameters).encoded
        symmetric.mixHash(ePub)
        symmetric.mixKey(ePub)
        symmetric.mixKey(dh(ePriv, re))
        val tag = symmetric.encryptAndHash(ByteArray(0))
        val (k1, k2) = symmetric.split()
        // The initiator sends with the first key, the responder with the second.
        receive = CipherState(k1)
        send = CipherState(k2)
        return ePub + tag
    }

    private fun dh(priv: X25519PrivateKeyParameters, pub: ByteArray): ByteArray {
        val agreement = X25519Agreement().apply { init(priv) }
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
        return out
    }

    companion object {
        const val PROTOCOL_NAME = "Noise_NNpsk0_25519_ChaChaPoly_SHA256"
        const val KEY_SIZE = 32
        const val TAG_SIZE = 16
    }
}

/** A ChaCha20-Poly1305 key with its nonce counter. */
class CipherState(private val key: ByteArray?) {
    private var nonce = 0L

    val hasKey: Boolean get() = key != null

    fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        return aead(true, k, nonce++, ad, plaintext)
    }

    fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        return aead(false, k, nonce++, ad, ciphertext)
    }

    private fun aead(encrypt: Boolean, key: ByteArray, n: Long, ad: ByteArray, input: ByteArray): ByteArray {
        // 96-bit nonce: 32 zero bits, then the counter as 64-bit little-endian.
        val iv = ByteArray(12)
        for (i in 0 until 8) iv[4 + i] = (n ushr (8 * i)).toByte()
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), NoiseResponder.TAG_SIZE * 8, iv, ad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        return try {
            val len = cipher.processBytes(input, 0, input.size, out, 0)
            val total = len + cipher.doFinal(out, len)
            if (total == out.size) out else out.copyOf(total)
        } catch (e: Exception) {
            throw NoiseException("Decryption failed")
        }
    }
}

private class SymmetricState(protocolName: String) {
    private var h: ByteArray
    private var ck: ByteArray
    private var cipher = CipherState(null)

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else sha256(name)
        ck = h.copyOf()
    }

    fun mixHash(data: ByteArray) {
        h = sha256(h + data)
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, k) = hkdf(ck, ikm, 2)
        ck = newCk
        cipher = CipherState(k)
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val (newCk, tempH, k) = hkdf(ck, ikm, 3)
        ck = newCk
        mixHash(tempH)
        cipher = CipherState(k)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val c = cipher.encrypt(h, plaintext)
        mixHash(c)
        return c
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val p = cipher.decrypt(h, ciphertext)
        mixHash(ciphertext)
        return p
    }

    fun split(): Pair<ByteArray, ByteArray> {
        val keys = hkdf(ck, ByteArray(0), 2)
        return keys[0] to keys[1]
    }

    private fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
        val temp = hmac(chainingKey, ikm)
        val result = ArrayList<ByteArray>(outputs)
        var previous = ByteArray(0)
        for (i in 1..outputs) {
            previous = hmac(temp, previous + byteArrayOf(i.toByte()))
            result += previous
        }
        return result
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest()).apply { init(KeyParameter(key)) }
        mac.update(data, 0, data.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    private fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(data, 0, data.size)
        return ByteArray(HASH_LEN).also { digest.doFinal(it, 0) }
    }

    companion object {
        const val HASH_LEN = 32
    }
}

class NoiseException(message: String) : Exception(message)
