package com.avshy.bayitvoice.satellite.proto

import java.io.ByteArrayOutputStream

/**
 * A minimal protobuf (proto3) encoder for the few ESPHome API messages the app sends.
 * Default values are skipped, as proto3 does.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun uint32(field: Int, value: Int): ProtoWriter = apply {
        if (value == 0) return@apply
        tag(field, WIRE_VARINT)
        varint(value.toLong() and 0xFFFFFFFFL)
    }

    fun bool(field: Int, value: Boolean): ProtoWriter = apply {
        if (!value) return@apply
        tag(field, WIRE_VARINT)
        varint(1)
    }

    fun string(field: Int, value: String?): ProtoWriter = apply {
        if (value.isNullOrEmpty()) return@apply
        bytes(field, value.toByteArray(Charsets.UTF_8))
    }

    /** Writes the string even when empty (ESPHome's `force` option). */
    fun forcedString(field: Int, value: String): ProtoWriter = apply {
        writeLengthDelimited(field, value.toByteArray(Charsets.UTF_8))
    }

    fun bytes(field: Int, value: ByteArray?): ProtoWriter = apply {
        if (value == null || value.isEmpty()) return@apply
        writeLengthDelimited(field, value)
    }

    fun message(field: Int, value: ProtoWriter): ProtoWriter = apply {
        writeLengthDelimited(field, value.toByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeLengthDelimited(field: Int, value: ByteArray) {
        tag(field, WIRE_LENGTH_DELIMITED)
        varint(value.size.toLong())
        out.write(value)
    }

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

/** One decoded field: a varint/fixed value in [number], or the raw bytes of a length-delimited one. */
class ProtoField(val field: Int, val wireType: Int, val number: Long, val bytes: ByteArray?) {
    val int: Int get() = number.toInt()
    val bool: Boolean get() = number != 0L
    val string: String get() = bytes?.toString(Charsets.UTF_8).orEmpty()
    val float: Float get() = Float.fromBits(number.toInt())
}

/** A minimal protobuf decoder: walks the fields of a message, skipping none. */
object ProtoReader {
    fun fields(data: ByteArray): List<ProtoField> {
        val result = ArrayList<ProtoField>()
        var pos = 0
        fun varint(): Long {
            var shift = 0
            var value = 0L
            while (true) {
                if (pos >= data.size) throw ProtoException("Truncated varint")
                val b = data[pos++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
                if (shift > 63) throw ProtoException("Varint too long")
            }
        }
        while (pos < data.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            when (val wire = (key and 7).toInt()) {
                WIRE_VARINT -> result += ProtoField(field, wire, varint(), null)
                WIRE_FIXED64 -> {
                    if (pos + 8 > data.size) throw ProtoException("Truncated fixed64")
                    var v = 0L
                    for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 8
                    result += ProtoField(field, wire, v, null)
                }
                WIRE_LENGTH_DELIMITED -> {
                    val length = varint().toInt()
                    if (length < 0 || pos + length > data.size) throw ProtoException("Truncated field")
                    result += ProtoField(field, wire, 0, data.copyOfRange(pos, pos + length))
                    pos += length
                }
                WIRE_FIXED32 -> {
                    if (pos + 4 > data.size) throw ProtoException("Truncated fixed32")
                    var v = 0L
                    for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 4
                    result += ProtoField(field, wire, v, null)
                }
                else -> throw ProtoException("Unsupported wire type $wire")
            }
        }
        return result
    }
}

class ProtoException(message: String) : Exception(message)

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5
