package com.nexus.agent.domain.safety

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/**
 * SSRF guard for the `web_fetch` tool. A fetch tool fetches arbitrary URLs supplied by the user
 * or the model, which makes it a server-side request-forgery vector against the device's own
 * network (router, localhost services, cloud metadata, etc.). This validator rejects, before any
 * request is sent:
 *  - non-http(s) schemes;
 *  - obviously-internal hostnames (localhost, .local, .internal, …);
 *  - any host whose resolved addresses include private/loopback/link-local/multicast/reserved IPs.
 *
 * DNS resolution is injected so the pure IP-classification logic is unit-testable without a real
 * resolver. Resolution is a blocking call; callers must invoke it from a background dispatcher.
 */
class UrlSafetyValidator(
    private val resolver: (String) -> List<InetAddress> = { host ->
        try {
            InetAddress.getAllByName(host).toList()
        } catch (_: Exception) {
            emptyList()
        }
    },
    private val isHostBlocked: (String) -> Boolean = { isBlockedHostname(it) }
) {

    /** Validates [url] and returns the parsed [HttpUrl] on success, or a typed failure. */
    fun validate(url: String): Result<HttpUrl> {
        // toHttpUrlOrNull already rejects non-http(s) schemes and malformed URLs.
        val parsed = url.trim().toHttpUrlOrNull()
            ?: return Result.failure(IllegalStateException(ERR_MALFORMED))

        val host = parsed.host
        if (host.isEmpty()) return Result.failure(IllegalStateException(ERR_MALFORMED))
        if (isHostBlocked(host)) return Result.failure(IllegalStateException(ERR_BLOCKED))

        val addresses = resolver(host)
        if (addresses.isEmpty()) return Result.failure(IllegalStateException(ERR_UNRESOLVED))
        if (addresses.any { !isPublic(it.address) }) {
            return Result.failure(IllegalStateException(ERR_BLOCKED))
        }

        return Result.success(parsed)
    }

    private fun isPublic(bytes: ByteArray): Boolean =
        if (bytes.size == 4) isPublicIpv4(bytes) else isPublicIpv6(bytes)

    companion object {
        const val ERR_MALFORMED = "fetch_malformed_url"
        const val ERR_BLOCKED = "fetch_blocked_url"
        const val ERR_UNRESOLVED = "fetch_unresolved"

        /**
         * True only for a globally-routable, non-reserved IPv4 address. Rejects loopback, private,
         * link-local, CGNAT, documentation/test, multicast, and reserved ranges.
         */
        fun isPublicIpv4(b: ByteArray): Boolean {
            if (b.size != 4) return false
            val a = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            val b2 = b[2].toInt() and 0xFF
            return when {
                a == 0 -> false                          // 0.0.0.0/8 "this network"
                a == 10 -> false                         // 10.0.0.0/8 private
                a == 100 && b1 in 64..127 -> false       // 100.64.0.0/10 CGNAT
                a == 127 -> false                        // 127.0.0.0/8 loopback
                a == 169 && b1 == 254 -> false           // 169.254.0.0/16 link-local
                a == 172 && b1 in 16..31 -> false        // 172.16.0.0/12 private
                a == 192 && b1 == 0 && b2 == 0 -> false  // 192.0.0.0/24 IETF
                a == 192 && b1 == 0 && b2 == 2 -> false  // 192.0.2.0/24 TEST-NET
                a == 192 && b1 == 168 -> false           // 192.168.0.0/16 private
                a == 198 && (b1 == 18 || b1 == 19) -> false // 198.18.0.0/15 benchmark
                a == 198 && b1 == 51 && b2 == 100 -> false // 198.51.100.0/24 TEST-NET
                a == 203 && b1 == 0 && b2 == 113 -> false // 203.0.113.0/24 TEST-NET
                a >= 224 -> false                        // multicast 224/4 + reserved 240/4
                a == 255 -> false                        // broadcast
                else -> true
            }
        }

        /**
         * True only for a globally-routable IPv6 address. Rejects unspecified, loopback,
         * link-local, site-local, unique-local, and multicast ranges, and defers to [isPublicIpv4]
         * for IPv4-mapped/compatible addresses.
         */
        fun isPublicIpv6(b: ByteArray): Boolean {
            if (b.size != 16) return false
            if (b.all { it == 0.toByte() }) return false // ::
            if (b.dropLast(1).all { it == 0.toByte() } && b[15] == 1.toByte()) return false // ::1

            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 0xFF) return false                       // ff00::/8 multicast
            if ((b0 and 0xFE) == 0xFC) return false            // fc00::/7 unique-local
            if (b0 == 0xFE) {
                // fe80::/10 link-local or fec0::/10 site-local
                if ((b1 and 0xC0) == 0x80 || (b1 and 0xC0) == 0xC0) return false
            }

            val v4Mapped = (0..9).all { b[it] == 0.toByte() } &&
                b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
            val v4Compatible = (0..11).all { b[it] == 0.toByte() }
            if (v4Mapped || v4Compatible) {
                return isPublicIpv4(byteArrayOf(b[12], b[13], b[14], b[15]))
            }
            return true
        }

        /** Fast pre-DNS rejection of hostnames that are obviously not public. */
        fun isBlockedHostname(host: String): Boolean {
            val h = host.trim().trimEnd('.').lowercase()
            if (h.isEmpty()) return true
            if (h in setOf("localhost", "localhost.localdomain", "localhost6", "broadcasthost", "ip6-localhost", "ip6-loopback")) {
                return true
            }
            val blockedSuffixes = listOf(
                ".local", ".localdomain", ".internal", ".lan", ".home", ".localhost",
                ".test", ".invalid", ".corp", ".intranet"
            )
            return blockedSuffixes.any { h.endsWith(it) }
        }
    }
}
