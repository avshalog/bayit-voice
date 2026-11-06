package com.avshy.bayitvoice.arbitration

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.math.abs
import kotlin.math.min

/**
 * Decides which of several devices that heard the same wake word handles it (SPEC section 6.2).
 * Each device multicasts its claim on the local network for a short window, repeating it so a
 * device that detected a little later still hears it, and listens for the others' claims. All
 * devices compare the same claims the same way, so exactly one of them wins.
 */
class PeerArbiter(context: Context) {

    private val app = context.applicationContext

    /**
     * Returns the claim that beat this device's, or null when this device should answer: it had
     * the best claim, or nobody else answered, or the network gave no way to ask. Never throws.
     */
    suspend fun claim(groupKey: ByteArray, mine: Claim, windowMs: Long): Claim? = withContext(Dispatchers.IO) {
        val nif = wifiInterface() ?: run {
            Log.i(TAG, "No Wi-Fi interface for arbitration; handling the wake word alone")
            return@withContext null
        }
        val lock = app.getSystemService(WifiManager::class.java)
            .createMulticastLock(TAG).apply { setReferenceCounted(false) }
        val peers = LinkedHashMap<Long, Claim>()
        try {
            lock.acquire()
            MulticastSocket(PORT).use { socket ->
                socket.networkInterface = nif
                socket.timeToLive = 1
                val group = InetAddress.getByName(GROUP)
                socket.joinGroup(InetSocketAddress(group, PORT), nif)
                val out = mine.encode(groupKey)
                val outPacket = DatagramPacket(out, out.size, group, PORT)
                val buffer = ByteArray(Claim.MAX_PACKET_BYTES)
                val inPacket = DatagramPacket(buffer, buffer.size)

                val start = SystemClock.elapsedRealtime()
                val end = start + windowMs
                var nextSend = start
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextSend) {
                        runCatching { socket.send(outPacket) }
                        nextSend += RESEND_MS
                    }
                    if (now >= end) break
                    socket.soTimeout = (min(nextSend, end) - now).coerceAtLeast(1).toInt()
                    try {
                        inPacket.length = buffer.size
                        socket.receive(inPacket)
                        val claim = Claim.decode(buffer, inPacket.length, groupKey) ?: continue
                        if (claim.deviceId == mine.deviceId) continue // our own packet, looped back
                        if (abs(System.currentTimeMillis() - claim.timestampMs) > MAX_AGE_MS) continue
                        peers[claim.deviceId] = claim
                    } catch (_: SocketTimeoutException) {
                    }
                }
                // One last copy, for a device whose window is still open.
                runCatching { socket.send(outPacket) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Arbitration failed (${e.javaClass.simpleName}: ${e.message}); handling the wake word alone")
            return@withContext null
        } finally {
            if (lock.isHeld) lock.release()
        }

        PeerLog(app).record(peers.values)
        val winner = peers.values.filter { it.beats(mine) }.reduceOrNull { a, b -> if (a.beats(b)) a else b }
        Log.i(TAG, "Arbitration: mine %.1f vs %s -> %s".format(
            mine.score,
            if (peers.isEmpty()) "no peers" else peers.values.joinToString { "%s %.1f".format(it.deviceName, it.score) },
            if (winner == null) "handle it" else "${winner.deviceName} handles it",
        ))
        winner
    }

    /** The Wi-Fi interface: up, multicast-capable and with a private IPv4 address. */
    private fun wifiInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { a -> a is Inet4Address && a.isSiteLocalAddress } }
            .sortedByDescending { it.name.startsWith("wlan") }
            .firstOrNull()
    }.getOrNull()

    companion object {
        private const val TAG = "PeerArbiter"

        /** Organization-local scope multicast group and a port of our own. */
        const val GROUP = "239.255.66.77"
        const val PORT = 47661

        /** Claims are repeated this often during the window. */
        private const val RESEND_MS = 80L

        /** Older claims belong to an earlier wake word. */
        private const val MAX_AGE_MS = 1_000L

        const val DEFAULT_WINDOW_MS = 300L
    }
}
