package com.avshy.bayitvoice.ha

import android.content.Context
import android.util.Log
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.NetworkInfo
import com.avshy.bayitvoice.config.ServerUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/** An authenticated connection and the address it reached. */
class HaConnection(val client: HaClient, val server: ServerUrl)

/**
 * Hands out a healthy, authenticated HA connection, reusing the current one when it still
 * answers a ping. In "keep connected" mode the wake-word service calls [keepAlive] so a
 * connection is ready before the wake word is heard.
 */
class ConnectionManager(private val settings: AppSettings, private val selector: ServerSelector) {

    private val mutex = Mutex()
    private var current: HaConnection? = null

    /** Returns a live connection; throws [HaException] if none can be made. */
    suspend fun acquire(): HaConnection = mutex.withLock {
        current?.let { existing ->
            if (existing.client.isConnected && existing.client.ping()) return@withLock existing
            existing.client.close()
            current = null
        }
        val token = settings.accessToken ?: throw HaException.AuthInvalid()
        val client = HaClient()
        val server = selector.connect(client, token)
        settings.lastHaVersion = client.haVersion
        client.instanceKey()?.let { settings.haInstanceKey = it }
        HaConnection(client, server).also { current = it }
    }

    /**
     * Keeps a connection open until cancelled, reconnecting with exponential backoff.
     * Stops on a rejected token, since retrying cannot fix that.
     */
    suspend fun keepAlive() {
        var backoff = INITIAL_BACKOFF_MS
        while (true) {
            val connection = try {
                acquire()
            } catch (e: HaException.AuthInvalid) {
                Log.w(TAG, "Token rejected; not keeping a connection")
                return
            } catch (e: HaException) {
                Log.i(TAG, "Connect failed (${e.javaClass.simpleName}); retrying in ${backoff / 1000} s")
                null
            }
            if (connection != null) {
                backoff = INITIAL_BACKOFF_MS
                connection.client.awaitClosed()
                Log.i(TAG, "Connection closed; reconnecting")
            } else {
                delay(backoff)
                backoff = min(backoff * 2, MAX_BACKOFF_MS)
            }
        }
    }

    /** Drops the current connection, e.g. after the address or token changed. */
    fun reset() {
        current?.client?.close()
        current = null
    }

    companion object {
        private const val TAG = "ConnectionManager"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L

        @Volatile private var shared: ConnectionManager? = null

        /** The process-wide connection used in "keep connected" mode. */
        fun shared(context: Context): ConnectionManager =
            shared ?: synchronized(this) {
                shared ?: create(context).also { shared = it }
            }

        fun create(context: Context): ConnectionManager {
            val settings = AppSettings.get(context)
            return ConnectionManager(settings, ServerSelector(settings, NetworkInfo(context)))
        }
    }
}
