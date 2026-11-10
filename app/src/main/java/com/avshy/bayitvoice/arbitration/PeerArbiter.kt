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
 *
 * A phone that won over a satellite can also hand the command to it afterwards ([handOff]), for
 * one only a satellite can run, such as a timer; the satellite waits for that ([awaitHandOff]).
 */
class PeerArbiter(context: Context) {

    private val app = context.applicationContext

    /** The claims heard, and the one that beat this device's (null: this device answers). */
    class Outcome(val winner: Claim?, val peers: List<Claim>)

    /**
     * Exchanges claims for [windowMs]. The winner is null when this device should answer: it had
     * the best claim, or nobody else answered, or the network gave no way to ask. Never throws.
     */
    suspend fun claim(groupKey: ByteArray, mine: Claim, windowMs: Long): Outcome {
        val peers = LinkedHashMap<Long, Claim>()
        val ok = exchange(mine.encode(groupKey), windowMs) { buffer, length ->
            val claim = Claim.decode(buffer, length, groupKey) ?: return@exchange false
            if (claim.deviceId != mine.deviceId && abs(System.currentTimeMillis() - claim.timestampMs) <= MAX_AGE_MS) {
                peers[claim.deviceId] = claim
            }
            false
        }
        if (!ok) return Outcome(null, emptyList())
        val winner = peers.values.filter { it.beats(mine) }.reduceOrNull { a, b -> if (a.beats(b)) a else b }
        Log.i(TAG, "Arbitration: mine %.1f%s vs %s -> %s".format(
            mine.score, if (mine.satellite) " (satellite)" else "",
            if (peers.isEmpty()) "no peers" else peers.values.joinToString { "%s %.1f%s".format(it.deviceName, it.score, if (it.satellite) " (satellite)" else "") },
            if (winner == null) "handle it" else "${winner.deviceName} handles it",
        ))
        return Outcome(winner, peers.values.toList())
    }

    /** Tells device [toId] to run the command itself, repeating the message for a short window. */
    suspend fun handOff(groupKey: ByteArray, fromId: Long, toId: Long) {
        Log.i(TAG, "Handing the command to device $toId")
        exchange(HandOff(fromId, toId, System.currentTimeMillis()).encode(groupKey), HAND_OFF_SEND_MS) { _, _ -> false }
    }

    /** True if device [fromId] handed the command to [myId] within [timeoutMs]. */
    suspend fun awaitHandOff(groupKey: ByteArray, myId: Long, fromId: Long, timeoutMs: Long): Boolean {
        var received = false
        exchange(null, timeoutMs) { buffer, length ->
            val message = HandOff.decode(buffer, length, groupKey) ?: return@exchange false
            received = message.toId == myId && message.fromId == fromId &&
                abs(System.currentTimeMillis() - message.timestampMs) <= MAX_AGE_MS
            received
        }
        Log.i(TAG, if (received) "Device $fromId handed the command over" else "No hand-over from device $fromId")
        return received
    }

    /**
     * Joins the group for [windowMs], sending [out] (if any) every [RESEND_MS] and passing every
     * packet received to [onPacket], which returns true to stop early. False if the network gave
     * no way to do it.
     */
    private suspend fun exchange(out: ByteArray?, windowMs: Long, onPacket: (ByteArray, Int) -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val nif = wifiInterface() ?: run {
                Log.i(TAG, "No Wi-Fi interface for arbitration; handling the wake word alone")
                return@withContext false
            }
            val lock = app.getSystemService(WifiManager::class.java)
                .createMulticastLock(TAG).apply { setReferenceCounted(false) }
            try {
                lock.acquire()
                MulticastSocket(PORT).use { socket ->
                    socket.networkInterface = nif
                    socket.timeToLive = 1
                    val group = InetAddress.getByName(GROUP)
                    socket.joinGroup(InetSocketAddress(group, PORT), nif)
                    val outPacket = out?.let { DatagramPacket(it, it.size, group, PORT) }
                    val buffer = ByteArray(MAX_PACKET_BYTES)
                    val inPacket = DatagramPacket(buffer, buffer.size)

                    val start = SystemClock.elapsedRealtime()
                    val end = start + windowMs
                    var nextSend = start
                    while (true) {
                        val now = SystemClock.elapsedRealtime()
                        if (outPacket != null && now >= nextSend) {
                            runCatching { socket.send(outPacket) }
                            nextSend += RESEND_MS
                        }
                        if (now >= end) break
                        val wakeAt = if (outPacket != null) min(nextSend, end) else end
                        socket.soTimeout = (wakeAt - now).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                        try {
                            inPacket.length = buffer.size
                            socket.receive(inPacket)
                            if (onPacket(buffer, inPacket.length)) break
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                    // One last copy, for a device whose window is still open.
                    if (outPacket != null) runCatching { socket.send(outPacket) }
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Arbitration failed (${e.javaClass.simpleName}: ${e.message}); handling the wake word alone")
                false
            } finally {
                if (lock.isHeld) lock.release()
            }
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

        /** Older messages belong to an earlier wake word. */
        private const val MAX_AGE_MS = 1_000L

        /** A hand-over is repeated for this long, so a satellite listening on a busy network hears it. */
        private const val HAND_OFF_SEND_MS = 400L

        private val MAX_PACKET_BYTES = maxOf(Claim.MAX_PACKET_BYTES, HandOff.PACKET_BYTES)

        const val DEFAULT_WINDOW_MS = 300L
    }
}
