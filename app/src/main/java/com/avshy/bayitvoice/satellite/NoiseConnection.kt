package com.avshy.bayitvoice.satellite

import com.avshy.bayitvoice.satellite.noise.CipherState
import com.avshy.bayitvoice.satellite.noise.NoiseException
import com.avshy.bayitvoice.satellite.noise.NoiseResponder
import com.avshy.bayitvoice.satellite.proto.ApiMessage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket

/**
 * One encrypted ESPHome API connection, device side. Framing (as in ESPHome's
 * `api_frame_helper_noise.cpp`): every frame is `0x01`, a 16-bit big-endian length, the payload.
 *
 * 1. The client sends a hello frame (ignored, but part of the prologue) and a handshake frame
 *    `0x00 + Noise message`.
 * 2. The device answers a server hello `0x01 + name + 0 + mac + 0`, then `0x00 + Noise reply`.
 * 3. Data frames carry `type (16-bit BE) + length (16-bit BE) + payload`, encrypted.
 *
 * A client that speaks plaintext, or uses the wrong key, gets a reject frame `0x01 + reason`,
 * which HA reports as "encryption required" or "invalid encryption key".
 */
class NoiseConnection(
    private val socket: Socket,
    private val psk: ByteArray,
    private val name: String,
    private val mac: String,
) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = BufferedOutputStream(socket.getOutputStream())
    private lateinit var sendCipher: CipherState
    private lateinit var receiveCipher: CipherState
    private val writeLock = Any()

    /** Runs the hello and the Noise handshake; throws if the client is not a valid peer. */
    fun handshake() {
        val hello = readFrame()
        val prologue = PROLOGUE_INIT + byteArrayOf((hello.size ushr 8).toByte(), hello.size.toByte()) + hello
        writeFrame(byteArrayOf(PROTOCOL_NOISE) + name.toByteArray() + 0 + mac.toByteArray() + 0)

        val handshake = readFrame()
        if (handshake.isEmpty() || handshake[0] != STATUS_OK) {
            reject("Bad handshake error byte")
        }
        val responder = NoiseResponder(psk, prologue)
        try {
            responder.readInitiation(handshake.copyOfRange(1, handshake.size))
        } catch (e: NoiseException) {
            reject("Handshake MAC failure")
        }
        writeFrame(byteArrayOf(STATUS_OK) + responder.writeResponse())
        sendCipher = responder.send
        receiveCipher = responder.receive
    }

    fun read(): ApiMessage {
        val plain = receiveCipher.decrypt(EMPTY, readFrame())
        if (plain.size < 4) throw IOException("Short message")
        val type = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
        return ApiMessage(type, plain.copyOfRange(4, plain.size))
    }

    fun write(message: ApiMessage) = synchronized(writeLock) {
        val payload = message.payload
        val plain = ByteArray(4 + payload.size)
        plain[0] = (message.type ushr 8).toByte()
        plain[1] = message.type.toByte()
        plain[2] = (payload.size ushr 8).toByte()
        plain[3] = payload.size.toByte()
        System.arraycopy(payload, 0, plain, 4, payload.size)
        writeFrame(sendCipher.encrypt(EMPTY, plain))
    }

    fun close() {
        runCatching { socket.close() }
    }

    private fun reject(reason: String): Nothing {
        runCatching { writeFrame(byteArrayOf(STATUS_FAILED) + reason.toByteArray()) }
        throw IOException("Handshake rejected: $reason")
    }

    private fun readFrame(): ByteArray {
        val indicator = input.read()
        if (indicator < 0) throw IOException("Connection closed")
        if (indicator != FRAME_INDICATOR) {
            // A plaintext client (preamble 0x00): tell it encryption is required.
            reject("Bad indicator byte")
        }
        val length = input.readUnsignedShort()
        if (length > MAX_FRAME) reject("Bad packet len")
        return ByteArray(length).also { input.readFully(it) }
    }

    private fun writeFrame(payload: ByteArray) = synchronized(writeLock) {
        if (payload.size > MAX_FRAME) throw IOException("Frame too large")
        output.write(FRAME_INDICATOR)
        output.write(payload.size ushr 8)
        output.write(payload.size and 0xFF)
        output.write(payload)
        output.flush()
    }

    private companion object {
        val PROLOGUE_INIT = "NoiseAPIInit".toByteArray(Charsets.US_ASCII)
        val EMPTY = ByteArray(0)
        const val FRAME_INDICATOR = 0x01
        const val PROTOCOL_NOISE: Byte = 0x01
        const val STATUS_OK: Byte = 0x00
        const val STATUS_FAILED: Byte = 0x01
        const val MAX_FRAME = 65535
    }
}

private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())
