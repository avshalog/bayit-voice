package com.avshy.bayitvoice.wakeword

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Edits the stored input shape of a `.tflite` model in memory.
 *
 * The Java interpreter prepares a model with its stored shape as soon as it is created, before
 * `resizeInput` can be called. openWakeWord's melspectrogram model stores `[1, 1]` (its real
 * shape is dynamic), which fails to prepare. Writing the real shape into a copy avoids that.
 */
object TfliteShapes {

    // Field ids from the TFLite flatbuffer schema.
    private const val MODEL_SUBGRAPHS = 2
    private const val SUBGRAPH_TENSORS = 0
    private const val SUBGRAPH_INPUTS = 1
    private const val TENSOR_SHAPE = 0

    /** Returns a direct copy of [model] whose first input tensor has shape [dims]. */
    fun withInputShape(model: ByteBuffer, dims: IntArray): ByteBuffer {
        val copy = ByteBuffer.allocateDirect(model.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        copy.put(model.duplicate().apply { rewind() })
        copy.rewind()

        val root = copy.getInt(0)
        val subgraph = tableAt(copy, field(copy, root, MODEL_SUBGRAPHS), 0)
        val inputId = copy.getInt(deref(copy, field(copy, subgraph, SUBGRAPH_INPUTS)) + 4)
        val tensor = tableAt(copy, field(copy, subgraph, SUBGRAPH_TENSORS), inputId)
        val shape = deref(copy, field(copy, tensor, TENSOR_SHAPE))
        require(copy.getInt(shape) == dims.size) { "Input rank ${copy.getInt(shape)} != ${dims.size}" }
        dims.forEachIndexed { i, dim -> copy.putInt(shape + 4 + i * 4, dim) }
        return copy
    }

    /** Absolute position of field [id] in [table], or fails if the field is absent. */
    private fun field(b: ByteBuffer, table: Int, id: Int): Int {
        val vtable = table - b.getInt(table)
        val slot = 4 + id * 2
        require(slot < (b.getShort(vtable).toInt() and 0xffff)) { "Missing field $id" }
        val offset = b.getShort(vtable + slot).toInt() and 0xffff
        require(offset != 0) { "Missing field $id" }
        return table + offset
    }

    private fun deref(b: ByteBuffer, position: Int) = position + b.getInt(position)

    private fun tableAt(b: ByteBuffer, vectorField: Int, index: Int): Int =
        deref(b, deref(b, vectorField) + 4 + index * 4)
}
