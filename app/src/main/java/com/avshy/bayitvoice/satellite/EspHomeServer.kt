package com.avshy.bayitvoice.satellite

import android.util.Base64
import android.util.Log
import com.avshy.bayitvoice.satellite.proto.ApiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Accepts HA's encrypted ESPHome API connections, one at a time: a new connection replaces the
 * current one, as HA reconnects after a drop. Calls [onConnected], [onMessage] and
 * [onDisconnected] from its own coroutine.
 */
class EspHomeServer(
    private val port: Int,
    private val key: String,
    private val name: String,
    private val mac: String,
    private val onConnected: () -> Unit,
    private val onMessage: suspend (ApiMessage) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var current: NoiseConnection? = null
    private var acceptJob: Job? = null

    val isConnected: Boolean get() = current != null

    fun start(scope: CoroutineScope) {
        val psk = Base64.decode(key, Base64.NO_WRAP)
        acceptJob = scope.launch(Dispatchers.IO) {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            serverSocket = server
            Log.i(TAG, "Listening on port $port")
            while (isActive) {
                val socket = try {
                    runInterruptible { server.accept() }
                } catch (e: IOException) {
                    if (isActive) Log.w(TAG, "Accept failed: ${e.message}")
                    break
                }
                launch { serve(socket, psk) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        current?.close()
        current = null
    }

    fun send(message: ApiMessage): Boolean {
        val connection = current ?: return false
        return try {
            connection.write(message)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Send failed: ${e.message}")
            connection.close()
            false
        }
    }

    private suspend fun serve(socket: Socket, psk: ByteArray) {
        socket.tcpNoDelay = true
        val connection = NoiseConnection(socket, psk, name, mac)
        try {
            runInterruptible { connection.handshake() }
        } catch (e: Exception) {
            Log.w(TAG, "Handshake with ${socket.inetAddress?.hostAddress} failed: ${e.message}")
            connection.close()
            return
        }
        current?.let {
            Log.i(TAG, "New connection replaces the current one")
            it.close()
        }
        current = connection
        Log.i(TAG, "HA connected from ${socket.inetAddress?.hostAddress}")
        onConnected()
        try {
            while (true) {
                val message = runInterruptible { connection.read() }
                onMessage(message)
            }
        } catch (e: Exception) {
            Log.i(TAG, "Connection ended: ${e.message}")
        } finally {
            connection.close()
            if (current === connection) {
                current = null
                onDisconnected()
            }
        }
    }

    private companion object {
        const val TAG = "EspHomeServer"
    }
}
