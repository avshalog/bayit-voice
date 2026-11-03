package com.avshy.bayitvoice.config

import java.net.URI

/** A normalized Home Assistant base URL, e.g. `http://192.168.1.10:8123`. */
data class ServerUrl(val base: String) {

    private val uri: URI = URI(base)

    val isHttps: Boolean get() = uri.scheme == "https"

    val host: String get() = uri.host.orEmpty()

    /** `http` → `ws`, `https` → `wss`, path `/api/websocket`. */
    val webSocketUrl: String
        get() = (if (isHttps) "wss" else "ws") + "://" + base.substringAfter("://") + "/api/websocket"

    /** Resolves a path returned by HA (e.g. a TTS proxy URL) against the base URL. */
    fun resolve(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else base + (if (path.startsWith("/")) path else "/$path")

    /** Plain http is only allowed for private/local addresses. */
    val isAllowed: Boolean get() = isHttps || isLocalHost(host)

    companion object {
        /**
         * Normalizes user input: trims, drops trailing slashes, defaults the scheme to
         * `http://` for local hosts and `https://` otherwise. Returns null when unparsable.
         */
        fun parse(input: String): ServerUrl? {
            var text = input.trim().trimEnd('/')
            if (text.isEmpty()) return null
            if (!text.contains("://")) {
                val hostPart = text.substringBefore('/').substringBefore(':')
                text = (if (isLocalHost(hostPart)) "http://" else "https://") + text
            }
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrEmpty()) return null
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return ServerUrl("$scheme://${uri.host.lowercase()}$port$path")
        }

        /** True for private IPv4/IPv6 ranges, loopback, `.local`/`.lan`/`.home.arpa` and single-label hostnames. */
        fun isLocalHost(rawHost: String): Boolean {
            val host = rawHost.lowercase().removePrefix("[").removeSuffix("]")
            if (host.isEmpty()) return false
            if (host == "localhost") return true
            if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") || host.endsWith(".internal")) return true

            val octets = host.split('.')
            if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
                val a = octets[0].toInt()
                val b = octets[1].toInt()
                return a == 10 || a == 127 ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) ||
                    (a == 169 && b == 254) ||
                    (a == 100 && b in 64..127) // CGNAT, e.g. Tailscale
            }
            if (host.contains(':')) {
                return host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
            }
            return !host.contains('.')
        }
    }
}
