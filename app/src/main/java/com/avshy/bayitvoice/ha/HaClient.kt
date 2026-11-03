package com.avshy.bayitvoice.ha

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A single HA WebSocket connection. Messages are consumed sequentially through [receive];
 * callers filter by command id. Not thread-safe: use from one coroutine at a time.
 */
class HaClient(private val http: OkHttpClient = sharedHttp) {

    private sealed interface Incoming {
        data class Message(val json: JSONObject) : Incoming
        data class Closed(val cause: Throwable?) : Incoming
    }

    private var socket: WebSocket? = null
    private var incoming = Channel<Incoming>(Channel.UNLIMITED)
    private var nextId = 1

    var haVersion: String? = null
        private set

    val isConnected: Boolean get() = socket != null

    /** Opens the socket and authenticates. Returns the HA version from `auth_ok`. */
    suspend fun connect(webSocketUrl: String, token: String): String {
        close()
        val channel = Channel<Incoming>(Channel.UNLIMITED).also { incoming = it }
        nextId = 1
        socket = http.newWebSocket(Request.Builder().url(webSocketUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }
                    .onSuccess { channel.trySend(Incoming.Message(it)) }
                    .onFailure { Log.w(TAG, "Unparsable message", it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                channel.trySend(Incoming.Closed(null))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                channel.trySend(Incoming.Closed(t))
            }
        })

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                val first = receive()
                if (first.optString("type") != "auth_required") throw HaException.Protocol("Expected auth_required")
                sendJson(JSONObject().put("type", "auth").put("access_token", token))
                val reply = receive()
                when (reply.optString("type")) {
                    "auth_ok" -> haVersion = reply.optString("ha_version")
                    "auth_invalid" -> throw HaException.AuthInvalid()
                    else -> throw HaException.Protocol("Unexpected auth reply")
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            close()
            throw HaException.Unreachable(e)
        } catch (e: Exception) {
            close()
            throw e
        }
        return haVersion.orEmpty()
    }

    /** Sends a command with a fresh id and returns that id. */
    fun send(type: String, fields: JSONObject = JSONObject()): Int {
        val id = nextId++
        fields.put("id", id).put("type", type)
        sendJson(fields)
        return id
    }

    fun sendBinary(bytes: ByteString) {
        val ws = socket ?: throw HaException.Disconnected(null)
        if (!ws.send(bytes)) throw HaException.Disconnected(null)
    }

    /** Next message from the server; throws [HaException.Disconnected] if the socket closed. */
    suspend fun receive(): JSONObject =
        when (val item = incoming.receive()) {
            is Incoming.Message -> item.json
            is Incoming.Closed -> {
                socket = null
                incoming.trySend(item) // keep reporting closed to later callers
                if (haVersion == null) throw HaException.Unreachable(item.cause)
                throw HaException.Disconnected(item.cause)
            }
        }

    /** Waits for the `result` of command [id]; throws [HaException.CommandFailed] if unsuccessful. */
    suspend fun awaitResult(id: Int): JSONObject {
        while (true) {
            val msg = receive()
            if (msg.optString("type") != "result" || msg.optInt("id") != id) continue
            if (msg.optBoolean("success")) return msg
            val error = msg.optJSONObject("error")
            throw HaException.CommandFailed(error?.optString("code").orEmpty(), error?.optString("message").orEmpty())
        }
    }

    suspend fun listPipelines(): PipelineList {
        val result = awaitResult(send("assist_pipeline/pipeline/list")).getJSONObject("result")
        val array = result.optJSONArray("pipelines")
        val pipelines = (0 until (array?.length() ?: 0)).map { i ->
            val p = array!!.getJSONObject(i)
            Pipeline(p.getString("id"), p.optString("name"), p.optString("language"))
        }
        return PipelineList(pipelines, result.optString("preferred_pipeline").ifEmpty { null })
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
        haVersion = null
    }

    private fun sendJson(json: JSONObject) {
        val ws = socket ?: throw HaException.Disconnected(null)
        if (!ws.send(json.toString())) throw HaException.Disconnected(null)
    }

    companion object {
        private const val TAG = "HaClient"
        private const val CONNECT_TIMEOUT_MS = 10_000L

        val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

data class Pipeline(val id: String, val name: String, val language: String)

data class PipelineList(val pipelines: List<Pipeline>, val preferredId: String?)

sealed class HaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthInvalid : HaException("Authentication failed")
    class Unreachable(cause: Throwable?) : HaException("Server unreachable", cause)
    class Disconnected(cause: Throwable?) : HaException("Connection lost", cause)
    class Protocol(message: String) : HaException(message)
    class CommandFailed(val code: String, val serverMessage: String) : HaException("$code: $serverMessage")
}
