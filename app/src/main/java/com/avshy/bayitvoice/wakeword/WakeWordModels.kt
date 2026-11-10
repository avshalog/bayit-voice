package com.avshy.bayitvoice.wakeword

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID

/** A wake-word model the user can select. */
data class WakeWordModel(
    val id: String,
    val name: String,
    val engine: WakeEngineType,
    val builtIn: Boolean,
    /** Asset path for built-ins, absolute file path for imported models. */
    val location: String,
)

/** Built-in and imported wake-word models, and creation of engines for them. */
class WakeWordModels(context: Context) {

    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("wake_models", Context.MODE_PRIVATE)
    private val modelDir = File(context.filesDir, "wakewords").apply { mkdirs() }

    fun all(): List<WakeWordModel> = BUILT_IN + imported()

    fun find(id: String?): WakeWordModel? = all().firstOrNull { it.id == id }

    /**
     * Copies a `.tflite` file into app storage after checking that it loads as an
     * openWakeWord model. Throws [InvalidModelException] otherwise. Call off the main thread.
     */
    fun import(uri: Uri, name: String): WakeWordModel {
        val id = UUID.randomUUID().toString()
        val file = File(modelDir, "$id.tflite")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: throw InvalidModelException()
        try {
            Interpreter(map(file)).use { interpreter ->
                val shape = interpreter.getInputTensor(0).shape()
                if (shape.size != 3 || shape[2] != 96) throw InvalidModelException()
            }
        } catch (e: Exception) {
            file.delete()
            throw if (e is InvalidModelException) e else InvalidModelException()
        }
        val model = WakeWordModel(id, name.trim(), WakeEngineType.OpenWakeWord, builtIn = false, location = file.absolutePath)
        save(imported() + model)
        return model
    }

    fun rename(id: String, name: String) {
        save(imported().map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun delete(id: String) {
        val model = imported().firstOrNull { it.id == id } ?: return
        File(model.location).delete()
        save(imported() - model)
    }

    /** Creates an engine for [model]; the caller closes it. */
    fun createEngine(model: WakeWordModel): WakeWordEngine = when (model.engine) {
        WakeEngineType.OpenWakeWord -> OpenWakeWordEngine(
            melModel = mapAsset(OWW_DIR + "melspectrogram.tflite"),
            embeddingModel = mapAsset(OWW_DIR + "embedding_model.tflite"),
            wakeModel = if (model.builtIn) mapAsset(model.location) else map(File(model.location)),
        )
    }

    private fun imported(): List<WakeWordModel> {
        val array = JSONArray(prefs.getString(KEY_IMPORTED, "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            WakeWordModel(
                id = o.getString("id"),
                name = o.getString("name"),
                engine = WakeEngineType.valueOf(o.getString("engine")),
                builtIn = false,
                location = o.getString("location"),
            )
        }
    }

    private fun save(models: List<WakeWordModel>) {
        val array = JSONArray()
        models.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("engine", it.engine.name)
                    .put("location", it.location)
            )
        }
        prefs.edit { putString(KEY_IMPORTED, array.toString()) }
    }

    private fun mapAsset(path: String): ByteBuffer =
        context.assets.openFd(path).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use {
                it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    private fun map(file: File): ByteBuffer =
        RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

    class InvalidModelException : Exception("Not a valid openWakeWord model")

    companion object {
        private const val KEY_IMPORTED = "imported"
        private const val OWW_DIR = "openwakeword/"

        const val DEFAULT_MODEL_ID = "builtin:hey_bayit"

        /**
         * Shipped in assets: openWakeWord's pre-trained models (CC BY-NC-SA 4.0), and the project's
         * own "היי בית" model, trained with tools/wake_word_training on the same openWakeWord data
         * plus real recordings (v0.2). A retrained model gets a new version suffix, same id.
         */
        val BUILT_IN = listOf(
            builtIn("hey_bayit", "היי בית", "v0.2"),
            builtIn("alexa", "Alexa"),
            builtIn("hey_jarvis", "Hey Jarvis"),
            builtIn("hey_mycroft", "Hey Mycroft"),
            builtIn("hey_rhasspy", "Hey Rhasspy"),
        )

        private fun builtIn(key: String, name: String, version: String = "v0.1") = WakeWordModel(
            id = "builtin:$key",
            name = name,
            engine = WakeEngineType.OpenWakeWord,
            builtIn = true,
            location = "$OWW_DIR${key}_$version.tflite",
        )
    }
}
